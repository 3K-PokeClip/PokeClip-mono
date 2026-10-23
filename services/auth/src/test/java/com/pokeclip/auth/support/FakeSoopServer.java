package com.pokeclip.auth.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 가짜 SOOP. 경로 셋: 토큰 발급·갱신({@code /auth/token}) · 아이디({@code /validate/live/status}) ·
 * 닉네임({@code /user/stationinfo}). 셋 다 form 본문이고 응답은 공식 문서의 꼴이다
 * (토큰은 snake_case 평면, API는 {@code {"result":1,"data":{…}}}).
 *
 * <p>{@link FakeChzzkServer}의 골격을 그대로 따른다: 루프백에 못박은 실제 소켓(지연·시간 초과가 실제로 걸려야 한다),
 * 스레드 안전한 카운터·기록, static 공유라 각 클래스가 {@code @BeforeEach}에서 {@link #reset()}한다.
 */
public final class FakeSoopServer implements AutoCloseable {

    /**
     * 기본 응답의 SOOP 아이디. 공개 API 응답에 이 값이 새는지 값으로 찾는다(계획 검증 C7).
     * 🔴 기본 꼴은 실측(2026-10-23)이다: 방송 중이 아니면 방송 여부 체크가 아이디를 안 주므로, 이 값은 방송국 정보의
     * {@code profile_image} 주소({@code …/LOGO/<두 글자>/<아이디>/<아이디>.jpg})에만 실린다.
     */
    public static final String DEFAULT_USER_ID = "soop-user-a1";
    public static final String DEFAULT_NICK = "숲닉네임";

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final AtomicInteger statusCounter = new AtomicInteger();
    private final AtomicInteger stationCounter = new AtomicInteger();
    private final AtomicReference<Response> tokenResponse = new AtomicReference<>();
    private final AtomicReference<Response> statusResponse = new AtomicReference<>();
    private final AtomicReference<Response> stationResponse = new AtomicReference<>();
    private final AtomicReference<Duration> tokenDelay = new AtomicReference<>(Duration.ZERO);
    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> statusRequests = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> stationRequests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> lastTokenContentType = new AtomicReference<>();

    private FakeSoopServer(HttpServer server) {
        this.server = server;
    }

    public static FakeSoopServer start() {
        try {
            // 루프백에 못박는 이유는 FakeChzzkServer 주석과 같다(와일드카드면 남이 같은 번호를 가로챈다).
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeSoopServer fake = new FakeSoopServer(server);
            fake.reset();
            server.createContext("/auth/token", fake::handleToken);
            server.createContext("/validate/live/status", fake::handleStatus);
            server.createContext("/user/stationinfo", fake::handleStation);
            server.setExecutor(Executors.newCachedThreadPool());
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
     * 기본 응답: token 200(access at-n · refresh rt-n · expires_in 28800 정수 · scope) ·
     * status 200(user_id {@link #DEFAULT_USER_ID}) · stationinfo 200(user_nick {@link #DEFAULT_NICK}).
     */
    public void reset() {
        tokenCounter.set(0);
        statusCounter.set(0);
        stationCounter.set(0);
        tokenResponse.set(null);
        statusResponse.set(new Response(200, "{\"result\":1,\"data\":{\"broad_no\":\"0\",\"status\":\"streamend\"}}"));
        stationResponse.set(new Response(200, stationBody(DEFAULT_NICK, logoUrl(DEFAULT_USER_ID))));
        tokenDelay.set(Duration.ZERO);
        tokenRequests.clear();
        statusRequests.clear();
        stationRequests.clear();
        lastTokenContentType.set(null);
    }

    /** 실측 꼴의 방송국 정보 본문(다섯 칸). profileImage가 null이면 그 칸을 뺀다. */
    public static String stationBody(String nick, String profileImage) {
        return "{\"result\":1,\"data\":{\"user_nick\":\"" + nick + "\",\"station_name\":\"방송국\","
                + (profileImage == null ? "" : "\"profile_image\":\"" + profileImage + "\",")
                + "\"lately_broad_date\":\"2026-10-23 12:00:00\",\"favorite_cnt\":3}}";
    }

    /** 실측 꼴의 대표 이미지 주소. 아이디가 두 자리에 들어간다. */
    public static String logoUrl(String userId) {
        return "https://profile.img.example.test/LOGO/" + userId.substring(0, 2) + "/" + userId + "/" + userId + ".jpg?v=1";
    }

    /** 방송 중일 때의 방송 여부 체크 본문(이때만 user_id가 온다). */
    public void statusOnAir(String userId) {
        statusResponds(200, "{\"result\":1,\"data\":{\"broad_no\":\"123\",\"status\":\"onair\",\"user_id\":\"" + userId + "\"}}");
    }

    /** 다음 호출부터 이 응답. 기본(카운터 토큰)으로 돌리려면 reset(). */
    public void tokenResponds(int status, String body) {
        tokenResponse.set(new Response(status, body));
    }

    public void tokenDelays(Duration delay) {
        tokenDelay.set(delay);
    }

    public void statusResponds(int status, String body) {
        statusResponse.set(new Response(status, body));
    }

    public void stationResponds(int status, String body) {
        stationResponse.set(new Response(status, body));
    }

    public int tokenCalls() {
        return tokenCounter.get();
    }

    public int statusCalls() {
        return statusCounter.get();
    }

    public int stationCalls() {
        return stationCounter.get();
    }

    /** 받은 form 본문(grant_type·code·refresh_token 확인용). */
    public List<Map<String, String>> tokenRequests() {
        return List.copyOf(tokenRequests);
    }

    public List<Map<String, String>> statusRequests() {
        return List.copyOf(statusRequests);
    }

    public List<Map<String, String>> stationRequests() {
        return List.copyOf(stationRequests);
    }

    public String lastTokenContentType() {
        return lastTokenContentType.get();
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        int n = tokenCounter.incrementAndGet();
        lastTokenContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        tokenRequests.add(readForm(exchange));
        sleep(tokenDelay.get());
        Response fixed = tokenResponse.get();
        if (fixed != null) {
            respond(exchange, fixed);
            return;
        }
        respond(exchange, new Response(200, "{\"access_token\":\"at-" + n + "\",\"refresh_token\":\"rt-" + n + "\","
                + "\"token_type\":\"Bearer\",\"expires_in\":28800,\"scope\":\"broad,station,chat\"}"));
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        statusCounter.incrementAndGet();
        statusRequests.add(readForm(exchange));
        respond(exchange, statusResponse.get());
    }

    private void handleStation(HttpExchange exchange) throws IOException {
        stationCounter.incrementAndGet();
        stationRequests.add(readForm(exchange));
        respond(exchange, stationResponse.get());
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        Map<String, String> form = new HashMap<>();
        if (raw.isBlank()) {
            return form;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), UTF_8);
            form.put(key, value);
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
