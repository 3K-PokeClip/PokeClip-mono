package com.pokeclip.auth.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 가짜 치지직. 경로 3개 — 토큰 발급·갱신(/auth/v1/token) · 철회(/auth/v1/token/revoke) ·
 * 내 채널(/open/v1/users/me). 응답 형식은 실물과 같게 {@code content} 래핑이다.
 *
 * <p>실제 소켓을 쓰는 이유는 {@link FakeHttpServer}와 같다 — 지연(풀 고갈 검사)과
 * 타임아웃이 실제로 걸려야 한다. 카운터·기록은 스레드 안전하다(동시성 테스트가 병렬로 친다).
 *
 * <p>{@link #reset()} 계약: 지연 0 · 카운터 0 · 기록 비움 · 기본 응답. static으로 공유되므로
 * 한 클래스가 건 설정이 다음 클래스로 새지 않게 {@code @BeforeEach}에서 부른다.
 */
public final class FakeChzzkServer implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final AtomicInteger revokeCounter = new AtomicInteger();
    private final AtomicInteger meCounter = new AtomicInteger();
    private final AtomicReference<Response> tokenResponse = new AtomicReference<>();
    private final AtomicReference<Response> revokeResponse = new AtomicReference<>();
    private final AtomicReference<Response> meResponse = new AtomicReference<>();
    private final AtomicReference<Duration> tokenDelay = new AtomicReference<>(Duration.ZERO);
    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<String> revokedTokens = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> lastMeBearer = new AtomicReference<>();

    private FakeChzzkServer(HttpServer server) {
        this.server = server;
    }

    public static FakeChzzkServer start() {
        try {
            // 🔴 <b>주소를 정하지 않으면 IPv6 와일드카드에 붙고, 그러면 남이 같은 번호의
            //    127.0.0.1 을 잡을 수 있다</b> — ServerSocket 의 기본 reuseAddress 가 true 라
            //    아무 프로그램이나 기본 설정 그대로 가로챈다(이 기계에서 100/100 실측).
            //    가로채면 더 구체적인 주소라 localhost 요청을 통째로 가져가고, 시험은
            //    「연결 실패」나 「호출 0회」로 간헐 실패한다 — 재현이 안 돼 원인을 못 찾는다.
            //    루프백에 못박으면 커널이 그 번호를 예약해 창 자체가 없어진다(0/100).
            //    POK-174(clip) 세션이 6,000회 중 4회를 실제로 잡아 알려 왔다.
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeChzzkServer fake = new FakeChzzkServer(server);
            fake.reset();
            server.createContext("/auth/v1/token/revoke", fake::handleRevoke);
            server.createContext("/auth/v1/token", fake::handleToken);
            server.createContext("/open/v1/users/me", fake::handleMe);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 기본 응답: token 200(access at-n · refresh rt-n · expiresIn 86400 · scope chat), revoke 200, me 200(chan-default).
     * expiresIn은 실물과 같게 <b>정수</b>다(2026-08-17 실측 — 공식 문서 표의 String과 다르다).
     */
    public void reset() {
        tokenCounter.set(0);
        revokeCounter.set(0);
        meCounter.set(0);
        tokenResponse.set(null);
        revokeResponse.set(new Response(200, "{\"code\":200,\"message\":null}"));
        meResponse.set(new Response(200,
                "{\"code\":200,\"message\":null,\"content\":{\"channelId\":\"chan-default\",\"channelName\":\"채널\"}}"));
        tokenDelay.set(Duration.ZERO);
        tokenRequests.clear();
        revokedTokens.clear();
        lastMeBearer.set(null);
    }

    /** 다음 호출부터 이 응답. null 본문 대신 기본(카운터 토큰)으로 돌리려면 reset(). */
    public void tokenResponds(int status, String body) {
        tokenResponse.set(new Response(status, body));
    }

    public void tokenDelays(Duration delay) {
        tokenDelay.set(delay);
    }

    public void meResponds(int status, String body) {
        meResponse.set(new Response(status, body));
    }

    public void revokeResponds(int status, String body) {
        revokeResponse.set(new Response(status, body));
    }

    public int tokenCalls() {
        return tokenCounter.get();
    }

    public int revokeCalls() {
        return revokeCounter.get();
    }

    public int meCalls() {
        return meCounter.get();
    }

    /** 받은 JSON 본문(grantType·refreshToken·code·state 확인용). 값은 문자열로 평탄화. */
    public List<Map<String, String>> tokenRequests() {
        return List.copyOf(tokenRequests);
    }

    /** revoke 본문의 token 값들. */
    public List<String> revokedTokens() {
        return List.copyOf(revokedTokens);
    }

    /** me가 마지막으로 받은 Authorization 헤더 값. */
    public String lastMeBearer() {
        return lastMeBearer.get();
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        int n = tokenCounter.incrementAndGet();
        tokenRequests.add(readJson(exchange));
        sleep(tokenDelay.get());
        Response fixed = tokenResponse.get();
        if (fixed != null) {
            respond(exchange, fixed);
            return;
        }
        respond(exchange, new Response(200, "{\"code\":200,\"message\":null,\"content\":{"
                + "\"accessToken\":\"at-" + n + "\",\"refreshToken\":\"rt-" + n + "\","
                + "\"tokenType\":\"Bearer\",\"expiresIn\":86400,\"scope\":\"chat\"}}"));
    }

    private void handleRevoke(HttpExchange exchange) throws IOException {
        revokeCounter.incrementAndGet();
        Map<String, String> body = readJson(exchange);
        if (body.get("token") != null) {
            revokedTokens.add(body.get("token"));
        }
        respond(exchange, revokeResponse.get());
    }

    private void handleMe(HttpExchange exchange) throws IOException {
        meCounter.incrementAndGet();
        lastMeBearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getRequestBody().readAllBytes();
        respond(exchange, meResponse.get());
    }

    private static Map<String, String> readJson(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        if (raw.isBlank()) {
            return Map.of();
        }
        Map<?, ?> parsed = JSON.readValue(raw, Map.class);
        java.util.Map<String, String> flat = new java.util.HashMap<>();
        parsed.forEach((k, v) -> flat.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return flat;
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, Response response) throws IOException {
        byte[] bytes = response.body().getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
