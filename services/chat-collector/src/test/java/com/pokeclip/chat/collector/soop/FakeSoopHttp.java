package com.pokeclip.chat.collector.soop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * 가짜 auth resolve 창구와 가짜 SOOP 방송 여부 체크를 한 서버에 둔다(POK-397). 답은 경로마다 줄로 쌓고, 마지막 답은 계속 쓴다.
 *
 * <p>🔴 루프백에만 묶는다. 와일드카드면 남의 프로세스가 포트를 가로챈다(이 저장소 실측).
 */
final class FakeSoopHttp implements AutoCloseable {

    record Received(String method, String path, String rawQuery, String contentType, String internalToken, String body) {
    }

    record Response(int status, String body) {
    }

    static final String RESOLVE = "/internal/soop-link/resolve";
    static final String STATUS = "/validate/live/status";
    static final String CATEGORIES = "/broad/category/list";

    private final HttpServer server;
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private final Deque<Response> resolveAnswers = new ArrayDeque<>();
    private final Deque<Response> statusAnswers = new ArrayDeque<>();
    private final Deque<Response> categoryAnswers = new ArrayDeque<>();

    private FakeSoopHttp(HttpServer server) {
        this.server = server;
    }

    static FakeSoopHttp start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "fake-soop-http");
                thread.setDaemon(true);
                return thread;
            }));
            FakeSoopHttp fake = new FakeSoopHttp(server);
            server.createContext(RESOLVE, exchange -> fake.handle(exchange, fake.resolveAnswers));
            server.createContext(STATUS, exchange -> fake.handle(exchange, fake.statusAnswers));
            server.createContext(CATEGORIES, exchange -> fake.handle(exchange, fake.categoryAnswers));
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String baseUrl() {
        return "http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    synchronized FakeSoopHttp resolveAnswers(Response... answers) {
        resolveAnswers.clear();
        resolveAnswers.addAll(List.of(answers));
        return this;
    }

    synchronized FakeSoopHttp statusAnswers(Response... answers) {
        statusAnswers.clear();
        statusAnswers.addAll(List.of(answers));
        return this;
    }

    synchronized FakeSoopHttp categoryAnswers(Response... answers) {
        categoryAnswers.clear();
        categoryAnswers.addAll(List.of(answers));
        return this;
    }

    List<Received> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    static Response ok(String body) {
        return new Response(200, body);
    }

    static String granted(String soopUserId, String accessToken, String expiresAt) {
        return "{\"valid\":true,\"soopUserId\":\"" + soopUserId + "\",\"accessToken\":\"" + accessToken
                + "\",\"expiresAt\":\"" + expiresAt + "\"}";
    }

    static String rejected(String reason) {
        return "{\"valid\":false,\"reason\":\"" + reason + "\"}";
    }

    private void handle(HttpExchange exchange, Deque<Response> answers) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("X-Internal-Token"), body));
        Response answer;
        synchronized (this) {
            answer = answers.size() > 1 ? answers.pollFirst() : answers.peekFirst();
        }
        if (answer == null) {
            answer = new Response(500, "{}");
        }
        byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), out.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
