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
 * 끝난 주문은 {@code proceed:false}, 주소는 처음 적힌 것만 남는다, 주소가 적힌 줄의 {@code FAILED}는 {@code checking}으로 받고
 * {@code onlyIfNoSession}이면 상태를 안 바꾸고 409 {@code SESSION_EXISTS}와 주소를 준다(POK-250).
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
    /** 설정하면 첫 result 직전에 주소가 비어 있을 때 이 주소를 적는다(실패를 보내는 사이 겹친 일꾼이 적었다, POK-250). */
    public volatile String sessionBeforeResult;
    /** true면 {@code onlyIfNoSession} 칸을 모르는 옛 clip이다(칸을 무시한다). */
    public volatile boolean oldClip;
    /** 실제로 반영된 끝 보고. 주소가 적힌 줄의 FAILED는 반영된 대로 {@code CHECKING:코드}로 남는다. */
    public final List<String> results = new ArrayList<>();
    /** 상태를 안 바꾸고 409로 돌려보낸 끝 보고의 사유(예: {@code SESSION_EXISTS}). */
    public final List<String> refusedResults = new ArrayList<>();
    /** UPLOADED 보고에 실린 썸네일 결과. {@code "SET"} · {@code "FAILED:코드"} · {@code "NONE"} · 칸이 없으면 {@code "null"}. */
    public final List<String> thumbnailResults = new ArrayList<>();
    /** result 문이 5xx를 줄 횟수(clip이 보고를 못 받는다 = 일꾼이 보고 전에 멈춘 것과 같다). */
    public volatile int resultDown;
    /** 설정하면 result를 받는 순간 이 값을 재 {@link #atResult}에 남긴다(보고 시점에 썸네일이 이미 붙었나). */
    public volatile IntSupplier probeAtResult;
    public final List<Integer> atResult = new ArrayList<>();
    /** 설정하면 두 번째 resolve부터 이 토큰을 준다(auth가 토큰을 새로 갱신했다). 강제 갱신(지문이 실린 resolve)에는 안 쓴다. */
    public volatile String accessTokenAfterFirst;
    /** 지문이 실린 resolve를 포함한 전체 resolve 수. */
    public volatile int resolves;
    /**
     * 강제 갱신(POK-250): 지문이 지금 토큰과 같으면 이 토큰으로 바꿔 준다. null이면 매번 새 토큰({@code ya29.forced-N})이다
     * (기본이 거절이면 「새 토큰도 401」 시험들이 강제 갱신의 거절로 끝나 원래 재던 것을 못 잰다).
     */
    public volatile String refreshedToken;
    /** 설정하면 강제 갱신이 이 사유로 거절된다(예: {@code BROKEN} = 구글 invalid_grant). 그 뒤로는 연동이 끊긴 것으로 답한다. */
    public volatile String refusalOnForce;
    /** true면 지문 칸을 모르는 옛 auth다(칸을 무시하고 지금 토큰을 준다). */
    public volatile boolean oldAuth;
    /** 지문이 실린 resolve 수. 상한({@link #FORCED_LIMIT})을 넘으면 500을 줘 끝없이 부르는 구현이 멈추지 않고 빨개지게 한다. */
    public volatile int forcedResolves;
    public static final int FORCED_LIMIT = 3;
    /** true면 result 문이 404(주문 없음, 탈퇴 정리 등으로 지워졌다)를 준다. */
    public volatile boolean resultNotFound;
    /** heartbeat 문이 받은 횟수와 줄 상태 코드(POK-333). 404는 이 문을 모르는 옛 clip이다. */
    public volatile int heartbeats;
    public volatile int heartbeatStatus = 200;

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
            case "heartbeat" -> {
                heartbeats++;
                reply(ex, heartbeatStatus, "{\"status\":\"" + status + "\"}");
            }
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
                if (resultNotFound) {
                    reply(ex, 404, "{\"error\":\"upload_not_found\"}");
                    return;
                }
                if (resultDown > 0) {
                    resultDown--;
                    reply(ex, 503, "{}");
                    return;
                }
                if (settled) {
                    reply(ex, 409, "{\"reason\":\"TERMINAL\"}");
                    return;
                }
                if (sessionBeforeResult != null && sessionUri == null) {
                    sessionUri = sessionBeforeResult;
                }
                String outcome = field(body, "outcome");
                if ("FAILED".equals(outcome) && sessionUri != null) {
                    if (!oldClip && body.contains("\"onlyIfNoSession\":true")) {
                        refusedResults.add("SESSION_EXISTS");
                        reply(ex, 409, "{\"reason\":\"SESSION_EXISTS\",\"sessionUri\":\"" + sessionUri + "\"}");
                        return;
                    }
                    outcome = "CHECKING";   // 진짜 clip: 주소가 적힌 줄의 실패는 확인 중이다
                }
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
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String rejected = oldAuth ? null : field(body, "rejectedTokenSha256");
        if (rejected != null) {
            resolves++;
            if (++forcedResolves > FORCED_LIMIT) {
                reply(ex, 500, "{}");
                return;
            }
            // 진짜 auth처럼 회원 행 잠금 안에서 지금 토큰의 지문과 견준다. 다르면 누가 이미 갱신했다: 지금 토큰을 준다.
            if (accessToken != null && sha256(accessToken).equals(rejected)) {
                if (refusalOnForce != null) {
                    accessToken = null;
                    refusal = refusalOnForce;
                } else {
                    accessToken = refreshedToken != null ? refreshedToken : "ya29.forced-" + forcedResolves;
                }
            }
        } else if (resolves++ >= 1 && accessTokenAfterFirst != null) {
            accessToken = accessTokenAfterFirst;
        }
        if (accessToken != null) {
            reply(ex, 200, "{\"valid\":true,\"channelId\":\"UC1\",\"accessToken\":\"" + accessToken + "\"}");
        } else {
            reply(ex, 200, "{\"valid\":false,\"reason\":\"" + refusal + "\"}");
        }
    }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
