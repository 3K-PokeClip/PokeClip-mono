package com.pokeclip.auth.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
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
