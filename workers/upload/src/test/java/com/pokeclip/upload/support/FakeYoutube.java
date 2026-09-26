package com.pokeclip.upload.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 유튜브 이어 올리기를 규칙대로 흉내 내는 가짜. <b>한 주소가 바이트를 다 받았을 때만 영상을 만들고 그 수를 센다</b> —
 * 시험이 재는 것은 결국 {@link #videosCreated()}가 1인가다.
 *
 * <p>손잡이: 시작에서 쿼터 거절 · 마지막 조각의 응답 버리기(영상은 만들고 연결만 끊는다 = 「성공했는데 응답만 못 받음」) ·
 * 조각에 5xx 몇 번 · 조각 거절(400) · 주소 없애기(404).
 */
public class FakeYoutube implements AutoCloseable {

    public static final String GOOD_TOKEN = "ya29.fake-good-token";
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    private final HttpServer server;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionSeq = new AtomicInteger();
    private final AtomicInteger videos = new AtomicInteger();
    private final List<String> seenAuth = new ArrayList<>();

    public volatile boolean quotaOnStart;
    /** 채널 업로드 한도. 유튜브는 이것을 403이 아니라 400으로 준다(PR #199 codex). */
    public volatile boolean uploadLimitOnStart;
    public volatile boolean dropFinalResponseOnce;
    public volatile int chunkServerErrors;
    public volatile boolean rejectChunks;
    public volatile boolean goneSessions;

    public static final class Session {
        public final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        public long size;
        public String title;
        public String privacy;
        public String videoId;
    }

    public FakeYoutube() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/upload", this::start);
        server.createContext("/session/", this::session);
        server.start();
    }

    public String startUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
    }

    public int videosCreated() {
        return videos.get();
    }

    public int sessionsStarted() {
        return sessionSeq.get();
    }

    public Session session(String uri) {
        return sessions.get(uri.substring(uri.lastIndexOf('/') + 1));
    }

    /** 시험이 「다른 일꾼이 이미 받아 둔 주소」를 만든다. */
    public String openSession(long size) {
        String id = "s" + sessionSeq.incrementAndGet();
        Session s = new Session();
        s.size = size;
        sessions.put(id, s);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/session/" + id;
    }

    public List<String> seenAuth() {
        return seenAuth;
    }

    private void start(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        seenAuth.add(String.valueOf(auth));
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!("Bearer " + GOOD_TOKEN).equals(auth)) {
            reply(ex, 401, "{}");
            return;
        }
        if (uploadLimitOnStart) {
            reply(ex, 400, "{\"error\":{\"code\":400,\"errors\":[{\"reason\":\"uploadLimitExceeded\"}]}}");
            return;
        }
        if (quotaOnStart) {
            reply(ex, 403, "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"quotaExceeded\"}]}}");
            return;
        }
        String uri = openSession(Long.parseLong(ex.getRequestHeaders().getFirst("X-Upload-Content-Length")));
        Session s = session(uri);
        s.title = field(body, "title");
        s.privacy = field(body, "privacyStatus");
        ex.getResponseHeaders().add("Location", uri);
        reply(ex, 200, "");
    }

    private void session(HttpExchange ex) throws IOException {
        Session s = sessions.get(ex.getRequestURI().getPath().substring("/session/".length()));
        byte[] body = ex.getRequestBody().readAllBytes();
        if (s == null || goneSessions) {
            reply(ex, 404, "{}");
            return;
        }
        String range = ex.getRequestHeaders().getFirst("Content-Range");
        if (range.startsWith("bytes */")) {
            if (s.videoId != null) {
                reply(ex, 200, "{\"id\":\"" + s.videoId + "\"}");
            } else {
                incomplete(ex, s);
            }
            return;
        }
        if (chunkServerErrors > 0) {
            chunkServerErrors--;
            reply(ex, 503, "{}");
            return;
        }
        if (rejectChunks) {
            reply(ex, 400, "{\"error\":{\"errors\":[{\"reason\":\"invalidVideo\"}]}}");
            return;
        }
        Matcher m = RANGE.matcher(range);
        if (!m.matches() || Long.parseLong(m.group(1)) != s.bytes.size()) {
            // 받은 자리와 다른 곳부터 보내면 유튜브처럼 받은 데까지를 알려 준다.
            incomplete(ex, s);
            return;
        }
        s.bytes.write(body);
        if (s.bytes.size() < s.size) {
            incomplete(ex, s);
            return;
        }
        s.videoId = "vid" + videos.incrementAndGet();
        if (dropFinalResponseOnce) {
            dropFinalResponseOnce = false;
            // 영상은 만들었고 응답만 버린다. 일꾼은 끊김을 본다.
            ex.close();
            return;
        }
        reply(ex, 201, "{\"id\":\"" + s.videoId + "\"}");
    }

    private void incomplete(HttpExchange ex, Session s) throws IOException {
        if (s.bytes.size() > 0) {
            ex.getResponseHeaders().add("Range", "bytes=0-" + (s.bytes.size() - 1));
        }
        ex.sendResponseHeaders(308, -1);
        ex.close();
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
