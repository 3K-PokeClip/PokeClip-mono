package com.pokeclip.chat.collector.soop.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 진짜 SOOP 대신 같은 모양의 Chat SDK 스크립트를 내리는 시험용 서버(POK-397 PR4). 공식 문서의 메서드 이름 그대로다:
 * {@code new SOOP.ChatSDK(id, secret)} · {@code init} · {@code setAuth} · {@code connect} · {@code handleMessageReceived} ·
 * {@code handleChatClosed} · {@code handleError}.
 *
 * <p>{@code connect()}가 불리면 이 서버에 「붙었다」를 알리고(앱 키 · 토큰을 같이, 시험이 무엇이 넘어갔는지 본다), 그 연결 몫의 대본을 받아
 * 차례로 흘린다. 대본은 시험이 {@link #script}로 연결마다 하나씩 쌓는다. 비면 아무것도 안 흘린다.
 *
 * <p>루프백에만 붙는다(services/CLAUDE.md 「시험용 임시 포트」).
 */
final class FakeSoopSdkServer implements AutoCloseable {

    /** 대본의 한 줄. {@code action}이 null이면 연결 닫힘({@code handleChatClosed}), {@code error}면 오류다. */
    record Step(String action, String json) {
        static Step message(String action, String userId, String nickname, String text) {
            return new Step(action, "{\"userId\":\"" + userId + "\",\"userNickname\":\"" + nickname + "\",\"message\":\"" + text
                    + "\",\"color\":\"\"}");
        }

        /** 후원. 개수 · 불리언은 SDK처럼 JSON 값 그대로({@code count}는 글이 아니면 그대로 싣는다: 숫자가 아닌 값 시험) */
        static Step donation(String action, String userId, String nickname, String countJson, boolean fromVod,
                             boolean fromStation, boolean relaysBroad) {
            return new Step(action, "{\"userId\":\"" + userId + "\",\"userNickname\":\"" + nickname + "\",\"count\":" + countJson
                    + ",\"fromVod\":" + fromVod + ",\"fromStation\":" + fromStation + ",\"relaysBroad\":" + relaysBroad + "}");
        }

        static Step other(String action) {
            return new Step(action, "{\"userList\":[]}");
        }

        static Step closed() {
            return new Step(null, null);
        }

        static Step error(String code) {
            return new Step("__error", "\"" + code + "\"");
        }
    }

    /** 붙을 때 SDK가 받은 값. */
    record Connect(String clientId, String clientSecret, String token) { }

    private final HttpServer server;
    private final Deque<List<Step>> scripts = new ArrayDeque<>();
    final List<Connect> connects = new CopyOnWriteArrayList<>();
    final List<String> sdkLoads = new CopyOnWriteArrayList<>();
    private volatile boolean sdkMissing;

    FakeSoopSdkServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/sdk.js", this::sdk);
        server.createContext("/connected", this::connected);
        server.createContext("/script", this::nextScript);
        server.start();
    }

    String sdkUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/sdk.js";
    }

    /** 다음 연결 하나가 흘릴 대본. */
    synchronized void script(Step... steps) {
        scripts.add(List.of(steps));
    }

    /** SDK 스크립트를 404로 준다(불러오기 실패). */
    void sdkMissing(boolean missing) {
        this.sdkMissing = missing;
    }

    private void sdk(HttpExchange exchange) throws IOException {
        sdkLoads.add("load");
        if (sdkMissing) {
            send(exchange, 404, "text/plain", "");
            return;
        }
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String js = """
                window.SOOP = { ChatSDK: function (clientId, clientSecret) {
                  var self = this;
                  self.handlers = {};
                  self.init = function () {};
                  self.setAuth = function (token) { self.token = token; };
                  self.handleMessageReceived = function (cb) { self.handlers.message = cb; };
                  self.handleChatClosed = function (cb) { self.handlers.closed = cb; };
                  self.handleError = function (cb) { self.handlers.error = cb; };
                  self.connect = function () {
                    return fetch('%1$s/connected', { method: 'POST', body: JSON.stringify({ clientId: clientId,
                        clientSecret: clientSecret, token: self.token }) })
                      .then(function () { return fetch('%1$s/script'); })
                      .then(function (r) { return r.json(); })
                      .then(function (steps) {
                        var i = 0;
                        function next() {
                          if (i >= steps.length) { return; }
                          var s = steps[i++];
                          if (s.action === null) { self.handlers.closed(); }
                          else if (s.action === '__error') { self.handlers.error(s.data, 'm'); }
                          else { self.handlers.message(s.action, s.data); }
                          setTimeout(next, 20);
                        }
                        setTimeout(next, 20);
                        return { ok: true };
                      });
                  };
                } };
                """.formatted(base);
        send(exchange, 200, "application/javascript", js);
    }

    private void connected(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        connects.add(new Connect(field(body, "clientId"), field(body, "clientSecret"), field(body, "token")));
        send(exchange, 200, "application/json", "{}");
    }

    private void nextScript(HttpExchange exchange) throws IOException {
        List<Step> steps;
        synchronized (this) {
            steps = scripts.isEmpty() ? List.of() : scripts.poll();
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"action\":").append(step.action() == null ? "null" : "\"" + step.action() + "\"")
                    .append(",\"data\":").append(step.json() == null ? "null" : step.json()).append('}');
        }
        json.append(']');
        send(exchange, 200, "application/json", json.toString());
    }

    private static String field(String json, String name) {
        String key = "\"" + name + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        // 탭의 출처(http://localhost)와 다르므로 fetch가 막히지 않게 연다(진짜 SDK의 일과 무관한 시험 배선)
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
