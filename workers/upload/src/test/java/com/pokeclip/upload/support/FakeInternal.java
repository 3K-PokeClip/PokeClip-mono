package com.pokeclip.upload.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * clip 일꾼 문 셋과 auth resolve를 한 서버로 흉내 낸다. clip 쪽 판정은 진짜 clip({@code UploadReportService})과 같은 규칙이다:
 * 끝난 주문은 {@code proceed:false}, 주소는 처음 적힌 것만 남는다.
 */
public class FakeInternal implements AutoCloseable {

    public static final String TOKEN = "internal-test-token";

    private final HttpServer server;
    public volatile String status = "queued";
    public volatile String sessionUri;
    public volatile int starts;
    /** auth가 줄 것. null이면 거절 사유 {@link #refusal}. */
    public volatile String accessToken = FakeYoutube.GOOD_TOKEN;
    public volatile String refusal = "BROKEN";
    /** clip start가 5xx를 줄 횟수. */
    public volatile int clipDown;
    /** 설정하면 session 문이 이 주소를 먼저 적힌 것으로 둔다(겹친 일꾼 흉내). */
    public volatile String preRecordOnSession;
    /** 설정하면 두 번째 start부터 이 주소가 적혀 있다(이 일꾼이 시작하는 사이 다른 일꾼이 적었다). */
    public volatile String lateSession;
    public final List<String> results = new ArrayList<>();
    /** UPLOADED 보고에 실린 썸네일 결과. {@code "SET"} · {@code "FAILED:코드"} · {@code "NONE"} · 칸이 없으면 {@code "null"}. */
    public final List<String> thumbnailResults = new ArrayList<>();
    /** result 문이 5xx를 줄 횟수(clip이 보고를 못 받는다 = 일꾼이 보고 전에 멈춘 것과 같다). */
    public volatile int resultDown;
    /** 설정하면 result를 받는 순간 이 값을 재 {@link #atResult}에 남긴다(보고 시점에 썸네일이 이미 붙었나). */
    public volatile IntSupplier probeAtResult;
    public final List<Integer> atResult = new ArrayList<>();
    /** 설정하면 두 번째 resolve부터 이 토큰을 준다(auth가 토큰을 새로 갱신했다). */
    public volatile String accessTokenAfterFirst;
    public volatile int resolves;

    public FakeInternal() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/internal/uploads/", this::uploads);
        server.createContext("/internal/youtube-link/resolve", this::resolve);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private synchronized void uploads(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!TOKEN.equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) {
            reply(ex, 401, "{}");
            return;
        }
        String door = ex.getRequestURI().getPath().replaceAll(".*/", "");
        boolean settled = status.equals("uploaded") || status.equals("failed") || status.equals("checking");
        switch (door) {
            case "start" -> {
                if (clipDown > 0) {
                    clipDown--;
                    reply(ex, 503, "{}");
                    return;
                }
                if (settled) {
                    reply(ex, 200, "{\"proceed\":false,\"status\":\"" + status + "\"}");
                    return;
                }
                status = "uploading";
                if (starts >= 1 && lateSession != null && sessionUri == null) {
                    sessionUri = lateSession;
                }
                starts++;
                reply(ex, 200, "{\"proceed\":true,\"status\":\"uploading\",\"attempt\":" + starts + ",\"sessionUri\":"
                        + (sessionUri == null ? "null" : "\"" + sessionUri + "\"") + "}");
            }
            case "session" -> {
                if (settled) {
                    reply(ex, 409, "{\"reason\":\"TERMINAL\"}");
                    return;
                }
                if (sessionUri == null && preRecordOnSession != null) {
                    sessionUri = preRecordOnSession;
                }
                if (sessionUri == null) {
                    sessionUri = field(body, "sessionUri");
                }
                reply(ex, 200, "{\"sessionUri\":\"" + sessionUri + "\"}");
            }
            case "result" -> {
                if (resultDown > 0) {
                    resultDown--;
                    reply(ex, 503, "{}");
                    return;
                }
                if (settled) {
                    reply(ex, 409, "{\"reason\":\"TERMINAL\"}");
                    return;
                }
                String outcome = field(body, "outcome");
                String detail = "UPLOADED".equals(outcome) ? field(body, "videoId") : field(body, "errorCode");
                results.add(outcome + ":" + detail);
                if ("UPLOADED".equals(outcome)) {
                    String thumb = field(body, "thumbnailOutcome");
                    String code = field(body, "thumbnailErrorCode");
                    thumbnailResults.add(code == null ? String.valueOf(thumb) : thumb + ":" + code);
                    if (probeAtResult != null) {
                        atResult.add(probeAtResult.getAsInt());
                    }
                }
                status = switch (outcome) {
                    case "UPLOADED" -> "uploaded";
                    case "FAILED" -> "failed";
                    default -> "checking";
                };
                reply(ex, 200, "{\"status\":\"" + status + "\"}");
            }
            default -> reply(ex, 404, "{}");
        }
    }

    private synchronized void resolve(HttpExchange ex) throws IOException {
        ex.getRequestBody().readAllBytes();
        if (resolves++ >= 1 && accessTokenAfterFirst != null) {
            accessToken = accessTokenAfterFirst;
        }
        if (accessToken != null) {
            reply(ex, 200, "{\"valid\":true,\"channelId\":\"UC1\",\"accessToken\":\"" + accessToken + "\"}");
        } else {
            reply(ex, 200, "{\"valid\":false,\"reason\":\"" + refusal + "\"}");
        }
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
