package com.pokeclip.upload.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    public final List<String> results = new ArrayList<>();

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
                if (settled) {
                    reply(ex, 409, "{\"reason\":\"TERMINAL\"}");
                    return;
                }
                String outcome = field(body, "outcome");
                String detail = "UPLOADED".equals(outcome) ? field(body, "videoId") : field(body, "errorCode");
                results.add(outcome + ":" + detail);
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

    private void resolve(HttpExchange ex) throws IOException {
        ex.getRequestBody().readAllBytes();
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
