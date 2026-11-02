package com.pokeclip.render.support;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * clip 보고 문의 가짜. 받은 보고를 순서대로 적고, 종류별로 미리 넣어 둔 답을 준다(없으면 200).
 * STARTED의 기본 답은 {@code proceed:true} + 토큰 {@link #TOKEN}이다.
 *
 * <p>루프백에만 묶는다. 와일드카드에 묶으면 같은 기계의 다른 프로세스가 포트를 가로챈다(clip POK-174 실측).
 */
public final class FakeClip implements AutoCloseable {

    public static final String TOKEN = "0f8b9c1e-2a3d-4e5f-8a9b-0c1d2e3f4a5b";

    public record Received(String jobId, String type, JsonNode body, String internalToken, String idempotencyKey) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<String[]>> scripted = new ConcurrentHashMap<>();

    public FakeClip() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/internal/jobs/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String jobId = path.split("/")[3];
            JsonNode body = Fixtures.MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            String type = body.path("eventType").asString();
            received.add(new Received(jobId, type, body, exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key")));
            String[] reply = next(type);
            byte[] bytes = reply[1].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(Integer.parseInt(reply[0]), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    /** 이 종류 보고에 다음 한 번 줄 답을 넣는다(여러 번 넣으면 차례로). */
    public FakeClip answer(String type, int status, String body) {
        scripted.computeIfAbsent(type, k -> new ArrayDeque<>()).add(new String[] {String.valueOf(status), body});
        return this;
    }

    public FakeClip finalAttempt() {
        return answer("STARTED", 200, "{\"proceed\":true,\"executionToken\":\"" + TOKEN
                + "\",\"attemptOrdinal\":3,\"isFinalAttempt\":true}");
    }

    private String[] next(String type) {
        Deque<String[]> queue = scripted.get(type);
        if (queue != null && !queue.isEmpty()) {
            return queue.poll();
        }
        if ("STARTED".equals(type)) {
            return new String[] {"200", "{\"proceed\":true,\"executionToken\":\"" + TOKEN
                    + "\",\"attemptOrdinal\":1,\"isFinalAttempt\":false}"};
        }
        return new String[] {"200", "{}"};
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> received() {
        return received;
    }

    public List<String> types() {
        return received.stream().map(Received::type).toList();
    }

    public Received last(String type) {
        return received.stream().filter(r -> r.type().equals(type)).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError(type + " 보고가 없다: " + types()));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
