package com.pokeclip.upload.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 유튜브 이어 올리기를 규칙대로 흉내 내는 가짜. <b>한 주소가 바이트를 다 받았을 때만 영상을 만들고 그 수를 센다</b> :
 * 시험이 재는 것은 결국 {@link #videosCreated()}가 1인가다.
 *
 * <p>손잡이: 시작에서 쿼터 거절 · 마지막 조각의 응답 버리기(영상은 만들고 연결만 끊는다 = 「성공했는데 응답만 못 받음」) ·
 * 조각에 5xx 몇 번 · 조각 거절(400) · 주소 없애기(404).
 *
 * <p>썸네일({@code thumbnails.set}, POK-291)은 {@code /upload/thumbnails/set}에 따로 둔다. JDK 서버는 가장 긴 접두사 문맥을 고르므로
 * 시작 문맥({@code /upload})이 가로채지 않는다. 손잡이는 {@link #thumbnailReplies}(「403 forbidden」처럼 상태와 사유를 차례로 준다)와
 * {@link #thumbnailToken}(썸네일 문이 받아 주는 토큰)이다.
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
    /** 속도 제한(403 rateLimitExceeded). 시간이 지나면 풀린다. */
    public volatile boolean rateLimitOnStart;
    /** 조각 전송에 403 속도 제한을 줄 횟수. */
    public volatile int rateLimitOnChunk;
    public volatile boolean dropFinalResponseOnce;
    public volatile int chunkServerErrors;
    public volatile boolean rejectChunks;
    public volatile boolean goneSessions;
    /** 썸네일 문이 받아 주는 토큰. 다르면 401(긴 업로드 뒤 토큰이 끝난 흉내). */
    public volatile String thumbnailToken = GOOD_TOKEN;
    /** 썸네일 문이 차례로 줄 실패. {@code "403 forbidden"}처럼 상태와 사유. 비면 성공(영상이 있을 때). */
    public final Deque<String> thumbnailReplies = new ConcurrentLinkedDeque<>();
    private final AtomicInteger thumbnailCalls = new AtomicInteger();
    private final List<Thumbnail> thumbnails = Collections.synchronizedList(new ArrayList<>());
    private final ObjectMapper mapper = new ObjectMapper();

    /** 붙은 썸네일 하나. */
    public record Thumbnail(String videoId, String contentType, byte[] bytes) {
    }

    public static final class Session {
        public final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        public long size;
        public String title;
        public String description;
        public String privacy;
        /** 시작 본문의 {@code snippet.tags}. 칸이 없으면 null. */
        public List<String> tags;
        /** 시작 본문의 {@code status.selfDeclaredMadeForKids}. 칸이 없거나 불리언이 아니면 null. */
        public Boolean madeForKids;
        public String videoId;
    }

    public FakeYoutube() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/upload", this::start);
        server.createContext("/upload/thumbnails/set", this::thumbnail);
        server.createContext("/session/", this::session);
        server.start();
    }

    public String startUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
    }

    public int videosCreated() {
        return videos.get();
    }

    public String thumbnailUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/upload/thumbnails/set";
    }

    /** 썸네일 문에 온 요청 수(실패 포함). */
    public int thumbnailCalls() {
        return thumbnailCalls.get();
    }

    /** 실제로 붙은 썸네일들. */
    public List<Thumbnail> thumbnailsSet() {
        synchronized (thumbnails) {
            return List.copyOf(thumbnails);
        }
    }

    /** 시험이 「이미 다 받은 주소」를 만든다(다른 일꾼이 끝까지 보냈다). */
    public void completeSession(String uri, byte[] bytes) {
        Session s = session(uri);
        s.bytes.writeBytes(bytes);
        s.videoId = "vid" + videos.incrementAndGet();
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
        if (rateLimitOnStart) {
            reply(ex, 403, "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}");
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
        // 정규식은 문자열 값만 잡는다. 배열(tags)·불리언(madeForKids)까지 재려고 본문을 JSON으로 읽는다.
        JsonNode root = mapper.readTree(body);
        JsonNode snippet = root.path("snippet");
        JsonNode status = root.path("status");
        s.title = snippet.path("title").asString(null);
        s.description = snippet.path("description").asString(null);
        s.privacy = status.path("privacyStatus").asString(null);
        if (snippet.has("tags")) {
            s.tags = new ArrayList<>();
            for (JsonNode tag : snippet.path("tags")) {
                s.tags.add(tag.asString());
            }
        }
        JsonNode kids = status.path("selfDeclaredMadeForKids");
        s.madeForKids = kids.isBoolean() ? kids.asBoolean() : null;
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
        if (rateLimitOnChunk > 0) {
            rateLimitOnChunk--;
            reply(ex, 403, "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}");
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

    private void thumbnail(HttpExchange ex) throws IOException {
        thumbnailCalls.incrementAndGet();
        byte[] body = ex.getRequestBody().readAllBytes();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        seenAuth.add(String.valueOf(auth));
        if (!("Bearer " + thumbnailToken).equals(auth)) {
            reply(ex, 401, "{\"error\":{\"code\":401,\"errors\":[{\"reason\":\"authError\"}]}}");
            return;
        }
        String scripted = thumbnailReplies.poll();
        if (scripted != null) {
            String[] parts = scripted.split(" ", 2);
            reply(ex, Integer.parseInt(parts[0]), "{\"error\":{\"code\":" + parts[0] + ",\"errors\":[{\"reason\":\""
                    + (parts.length > 1 ? parts[1] : "") + "\"}]}}");
            return;
        }
        String query = ex.getRequestURI().getQuery();
        Matcher m = Pattern.compile("videoId=([^&]+)").matcher(query == null ? "" : query);
        String videoId = m.find() ? m.group(1) : null;
        boolean exists = sessions.values().stream().anyMatch(s -> videoId != null && videoId.equals(s.videoId));
        if (!exists || query == null || !query.contains("uploadType=media")) {
            reply(ex, 404, "{\"error\":{\"code\":404,\"errors\":[{\"reason\":\"videoNotFound\"}]}}");
            return;
        }
        thumbnails.add(new Thumbnail(videoId, ex.getRequestHeaders().getFirst("Content-Type"), body));
        reply(ex, 200, "{\"kind\":\"youtube#thumbnailSetResponse\",\"items\":[]}");
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

    @Override
    public void close() {
        server.stop(0);
    }
}
