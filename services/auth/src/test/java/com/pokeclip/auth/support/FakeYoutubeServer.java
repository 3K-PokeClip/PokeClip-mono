package com.pokeclip.auth.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 가짜 구글. 경로 3개 — 토큰 발급·갱신({@code /token}) · 철회({@code /revoke}) ·
 * 채널 목록({@code /youtube/v3/channels}). {@link FakeChzzkServer}와 달리 본문이
 * <b>form urlencoded</b>이고 응답에 {@code content} 래핑이 없다.
 *
 * <p>실제 소켓을 쓰는 이유는 {@link FakeHttpServer}와 같다 — 지연(풀 고갈 검사)과
 * 타임아웃이 실제로 걸려야 한다. 카운터·기록은 스레드 안전하다(동시성 테스트가 병렬로 친다).
 *
 * <p>{@link #reset()} 계약: 지연 0 · 카운터 0 · 기록 비움 · 기본 응답 · 캐스케이드 꺼짐.
 * static으로 공유되므로 한 클래스가 건 설정이 다음 클래스로 새지 않게 {@code @BeforeEach}에서 부른다.
 */
public final class FakeYoutubeServer implements AutoCloseable {

    /** 실측 A가 확정할 때까지 0절의 문서 값을 쓴다. 동의 화면에서 둘 다 체크된 정상 응답이다. */
    public static final String SCOPE_GRANTED =
            "https://www.googleapis.com/auth/youtube.upload https://www.googleapis.com/auth/youtube.readonly";

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final AtomicInteger revokeCounter = new AtomicInteger();
    private final AtomicInteger channelsCounter = new AtomicInteger();
    private final AtomicReference<Response> tokenResponse = new AtomicReference<>();
    private final AtomicReference<Response> revokeResponse = new AtomicReference<>();
    private final AtomicReference<Response> channelsResponse = new AtomicReference<>();
    private final AtomicReference<Duration> tokenDelay = new AtomicReference<>(Duration.ZERO);
    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<String> revokedTokens = new CopyOnWriteArrayList<>();
    private final AtomicReference<Map<String, String>> lastRevokeRequest = new AtomicReference<>();
    private final AtomicReference<String> lastTokenContentType = new AtomicReference<>();
    private final AtomicReference<String> lastChannelsBearer = new AtomicReference<>();
    private final AtomicReference<String> lastChannelsQuery = new AtomicReference<>();
    /** 켜면 실물처럼 동작한다 — revoke 한 번이면 그 뒤의 갱신이 전부 죽는다. */
    private final AtomicBoolean cascadeOnRevoke = new AtomicBoolean();
    private final AtomicBoolean revokedAny = new AtomicBoolean();

    private FakeYoutubeServer(HttpServer server) {
        this.server = server;
    }

    public static FakeYoutubeServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            FakeYoutubeServer fake = new FakeYoutubeServer(server);
            fake.reset();
            server.createContext("/token", fake::handleToken);
            server.createContext("/revoke", fake::handleRevoke);
            server.createContext("/youtube/v3/channels", fake::handleChannels);
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

    public String tokenUri() {
        return baseUrl() + "/token";
    }

    public String revokeUri() {
        return baseUrl() + "/revoke";
    }

    /**
     * 기본 응답: token 200(access at-n · refresh rt-n · expires_in 3600(정수) · scope 둘 다),
     * revoke 200 {}, channels 200(chan-default 하나).
     */
    public void reset() {
        tokenCounter.set(0);
        revokeCounter.set(0);
        channelsCounter.set(0);
        tokenResponse.set(null);
        revokeResponse.set(new Response(200, "{}"));
        channelsResponse.set(new Response(200,
                "{\"kind\":\"youtube#channelListResponse\",\"items\":[{\"id\":\"chan-default\","
                        + "\"snippet\":{\"title\":\"채널\"}}],\"pageInfo\":{\"totalResults\":1}}"));
        tokenDelay.set(Duration.ZERO);
        tokenRequests.clear();
        revokedTokens.clear();
        lastRevokeRequest.set(null);
        lastTokenContentType.set(null);
        lastChannelsBearer.set(null);
        lastChannelsQuery.set(null);
        cascadeOnRevoke.set(false);
        revokedAny.set(false);
    }

    /** 다음 호출부터 이 응답. 기본(카운터 토큰)으로 돌리려면 reset(). */
    public void tokenResponds(int status, String body) {
        tokenResponse.set(new Response(status, body));
    }

    public void tokenDelays(Duration delay) {
        tokenDelay.set(delay);
    }

    public void channelsResponds(int status, String body) {
        channelsResponse.set(new Response(status, body));
    }

    public void revokeResponds(int status, String body) {
        revokeResponse.set(new Response(status, body));
    }

    /**
     * 켜면 revoke를 한 번 받은 뒤로 {@code grant_type=refresh_token} 요청에
     * {@code 400 {"error":"invalid_grant"}}를 준다 — 구글 revoke가 그 사용자의 프로젝트 동의 전체를
     * 무효화하는 실물 거동이다(계획 0절·2절 결정 8). 이 모드가 없으면 재연동이 깨졌는지 잴 방법이 없다.
     * 교환(새 동의)은 계속 성공한다 — 새 동의가 옛 grant를 대체한다.
     */
    public void cascadeOnRevoke(boolean on) {
        cascadeOnRevoke.set(on);
    }

    public int tokenCalls() {
        return tokenCounter.get();
    }

    public int revokeCalls() {
        return revokeCounter.get();
    }

    public int channelsCalls() {
        return channelsCounter.get();
    }

    /** 받은 form 본문(grant_type·refresh_token·code·client_id 확인용). */
    public List<Map<String, String>> tokenRequests() {
        return List.copyOf(tokenRequests);
    }

    /** revoke 본문의 token 값들. */
    public List<String> revokedTokens() {
        return List.copyOf(revokedTokens);
    }

    /** revoke가 마지막으로 받은 form 본문 전체 — 구글엔 token 말고 다른 필드가 없다. */
    public Map<String, String> lastRevokeRequest() {
        return lastRevokeRequest.get();
    }

    public String lastTokenContentType() {
        return lastTokenContentType.get();
    }

    /** channels가 마지막으로 받은 Authorization 헤더 값. */
    public String lastChannelsBearer() {
        return lastChannelsBearer.get();
    }

    public String lastChannelsQuery() {
        return lastChannelsQuery.get();
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        int n = tokenCounter.incrementAndGet();
        lastTokenContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        Map<String, String> form = readForm(exchange);
        tokenRequests.add(form);
        sleep(tokenDelay.get());

        if (cascadeOnRevoke.get() && revokedAny.get() && "refresh_token".equals(form.get("grant_type"))) {
            respond(exchange, new Response(400, "{\"error\":\"invalid_grant\"}"));
            return;
        }
        Response fixed = tokenResponse.get();
        if (fixed != null) {
            respond(exchange, fixed);
            return;
        }
        respond(exchange, new Response(200, "{\"access_token\":\"at-" + n + "\",\"refresh_token\":\"rt-" + n + "\","
                + "\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"" + SCOPE_GRANTED + "\"}"));
    }

    private void handleRevoke(HttpExchange exchange) throws IOException {
        revokeCounter.incrementAndGet();
        Map<String, String> form = readForm(exchange);
        lastRevokeRequest.set(form);
        if (form.get("token") != null) {
            revokedTokens.add(form.get("token"));
        }
        revokedAny.set(true);
        respond(exchange, revokeResponse.get());
    }

    private void handleChannels(HttpExchange exchange) throws IOException {
        channelsCounter.incrementAndGet();
        lastChannelsBearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastChannelsQuery.set(exchange.getRequestURI().getQuery());
        exchange.getRequestBody().readAllBytes();
        respond(exchange, channelsResponse.get());
    }

    /** application/x-www-form-urlencoded 본문을 평탄한 맵으로. 같은 키가 두 번 오면 마지막 값이 남는다. */
    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        Map<String, String> form = new HashMap<>();
        if (raw.isBlank()) {
            return form;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                form.put(URLDecoder.decode(pair, UTF_8), "");
                continue;
            }
            form.put(URLDecoder.decode(pair.substring(0, eq), UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), UTF_8));
        }
        return form;
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
