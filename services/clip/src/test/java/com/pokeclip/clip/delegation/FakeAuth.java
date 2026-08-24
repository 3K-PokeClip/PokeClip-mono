package com.pokeclip.clip.delegation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 가짜 auth. 응답과 지연을 갈아 끼우고, 받은 요청을 센다.
 * {@code chat-collector}의 {@code ChzzkLinkClientTest.FakeAuth}를 clip으로 옮긴 것이다 —
 * Gradle 테스트 소스는 모듈 간 공유가 안 된다({@code clip/CLAUDE.md} 함정).
 *
 * <p><b>진짜로 듣는 소켓이어야 한다.</b> {@code MockRestServiceServer}는 요청 팩토리를
 * 갈아치우므로, 이 카드의 표적인 「주입받은 빌더를 쓰는가」를 통째로 무력화한다.
 * 지연도 만들 수 없다.
 *
 * <p>전용 실행기를 준다 — 기본값(null)은 디스패처 스레드에서 핸들러를 돌리므로
 * 지연을 걸면 서버 전체가 멈추고, 그 스레드는 데몬이 아니라 JVM 종료도 늦춘다.
 */
final class FakeAuth implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService threads;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<Instant> firstRequestAt = new AtomicReference<>();

    private volatile int status = 200;
    private volatile String body = "";
    private volatile Duration delay = Duration.ZERO;

    private FakeAuth(HttpServer server, ExecutorService threads) {
        this.server = server;
        this.threads = threads;
    }

    static FakeAuth start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "fake-auth");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(threads);
            FakeAuth fake = new FakeAuth(server, threads);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void respondWith(int status, String body) {
        this.status = status;
        this.body = body;
    }

    void holdFor(Duration delay) {
        this.delay = delay;
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    int callCount() {
        return calls.get();
    }

    String lastToken() {
        return lastToken.get();
    }

    String lastBody() {
        return lastBody.get();
    }

    String lastPath() {
        return lastPath.get();
    }

    /** 첫 요청이 <b>서버에 도착한 시각</b>부터 잰다. 클라이언트 조립 시간이 안 섞인다. */
    Duration sinceFirstRequest() {
        Instant at = firstRequestAt.get();
        return at == null ? Duration.ZERO : Duration.between(at, Instant.now());
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        firstRequestAt.compareAndSet(null, Instant.now());
        lastPath.set(exchange.getRequestURI().getPath());
        lastToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        // 0은 "길이를 모른다"는 뜻이라 청크 응답이 된다. 빈 본문은 -1이다.
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        threads.shutdownNow();
    }
}
