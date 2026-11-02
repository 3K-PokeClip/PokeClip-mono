package com.pokeclip.chat.collector.relay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 가짜 clip 접수 문({@code POST /internal/broadcasts/{id}/chat-events}). 받은 요청을 전부 남긴다.
 *
 * <p>🔴 <b>루프백에만 묶는다.</b> 와일드카드면 남의 프로세스가 포트를 가로챈다(이 저장소 실측 500/500).
 * 🔴 <b>핸들러의 스레드 이름으로 「누가 보냈나」를 재지 마라</b> — 여기서 보이는 것은 이 서버의
 * 스레드다(계획 검증 F2 재현: 호출 스레드 {@code chzzk-relay} → 핸들러가 본 이름 {@code HTTP-Dispatcher}).
 * 보낸 스레드는 보내는 쪽 대역에서 잰다.
 */
final class FakeClipRelay implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Received(String path, String internalToken, String contentType, JsonNode body) {
    }

    private final HttpServer server;
    private final ExecutorService threads;
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Instant> firstRequestAt = new AtomicReference<>();

    private volatile int status = 200;
    private volatile String responseBody = "{\"accepted\":0,\"dropped\":0}";
    private volatile Duration delay = Duration.ZERO;
    /** null이 아니면 <b>첫 요청만</b> 이 빗장이 풀릴 때까지 응답을 붙든다(F13 묶음 시험의 손잡이). */
    private volatile CountDownLatch firstRequestGate;

    private FakeClipRelay(HttpServer server, ExecutorService threads) {
        this.server = server;
        this.threads = threads;
    }

    static FakeClipRelay start() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "fake-clip-relay");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(threads);
            FakeClipRelay fake = new FakeClipRelay(server, threads);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String baseUrl() {
        return "http://" + server.getAddress().getAddress().getHostAddress()
                + ":" + server.getAddress().getPort();
    }

    void respondWith(int status) {
        this.status = status;
    }

    void respondBody(String body) {
        this.responseBody = body;
    }

    void holdFor(Duration delay) {
        this.delay = delay;
    }

    void holdFirstRequest() {
        firstRequestGate = new CountDownLatch(1);
    }

    void releaseFirstRequest() {
        CountDownLatch gate = firstRequestGate;
        if (gate != null) {
            gate.countDown();
        }
    }

    List<Received> requests() {
        return List.copyOf(requests);
    }

    int callCount() {
        return requests.size();
    }

    Duration sinceFirstRequest() {
        Instant at = firstRequestAt.get();
        return at == null ? Duration.ZERO : Duration.between(at, Instant.now());
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        JsonNode body = raw.length == 0 ? null : MAPPER.readTree(new String(raw, StandardCharsets.UTF_8));
        firstRequestAt.compareAndSet(null, Instant.now());
        boolean first = requests.isEmpty();
        requests.add(new Received(exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
        try {
            CountDownLatch gate = firstRequestGate;
            if (first && gate != null) {
                gate.await(30, TimeUnit.SECONDS);
            }
            if (!delay.isZero()) {
                Thread.sleep(delay.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, out.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(out);
        }
    }

    @Override
    public void close() {
        releaseFirstRequest();
        server.stop(0);
        threads.shutdownNow();
    }
}
