package com.pokeclip.clip.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 가짜 수집기(chat-collector). {@link FakeAuth}를 그대로 베낀 쌍둥이다 — 바인딩 주소·기본 응답·
 * 초기화 규칙이 같은 이유는 거기 주석에 있고, <b>여기 복사하지 않는다</b>(한쪽만 낡는다).
 *
 * <p><b>다른 점 하나 — 쿼리를 기억한다.</b> 이 카드의 표적이 「clip이 <b>무엇을</b> 넘기는가」다.
 * 브라우저가 준 것을 통째로 넘기면 프론트가 {@code channelId}(=보정값 열쇠)를 정하게 되므로,
 * {@link #lastQuery()}가 넘어간 쿼리 문자열을 그대로 준다.
 *
 * <p>기본 응답은 <b>503</b>이다 — 답을 안 건 시험이 200을 받으면 아무것도 안 재면서 초록이 된다.
 */
public final class FakeCollector implements AutoCloseable {

    private static final Response 아무_경로도_안_정했을_때 = new Response(503, "");

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final ExecutorService threads;
    private final Map<String, Response> byPath = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    private volatile Response fallback = 아무_경로도_안_정했을_때;
    private volatile Duration delay = Duration.ZERO;

    /** {상태, Location}. {@link #redirectTo}가 걸면 경로를 안 가리고 이것으로만 답한다. */
    private volatile String[] redirect;

    private FakeCollector(HttpServer server, ExecutorService threads) {
        this.server = server;
        this.threads = threads;
    }

    /** 🔴 <b>루프백에만 바인딩한다.</b> 근거는 {@link FakeAuth#start()} 주석 전문. */
    public static FakeCollector start() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "fake-collector");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(threads);
            FakeCollector fake = new FakeCollector(server, threads);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void respondWith(int status, String body) {
        this.fallback = new Response(status, body);
    }

    public void respondWith(String path, int status, String body) {
        byPath.put(path, new Response(status, body));
    }

    /**
     * 경로를 안 가리고 이 상태 코드와 {@code Location}으로만 답한다.
     *
     * <p><b>리다이렉트를 안 따라가는 것을 재려고 있다.</b> {@link FakeAuth#redirectTo(int, String)}의
     * 쌍둥이인데 <b>노출이 더 넓다</b> — auth 호출은 POST라 {@code 301}·{@code 302}·{@code 303}이
     * GET으로 격하된 <b>새 요청</b>이 되어 헤더·본문이 안 따라가고 {@code 307}·{@code 308}만
     * 열쇠를 흘린다. <b>수집기 호출은 원래 GET</b>이라 다섯 상태 전부가 원 요청 그대로 재전송되고
     * {@code X-Internal-Token}이 리다이렉트가 가리키는 아무 출처에나 도착한다.
     */
    public void redirectTo(int status, String location) {
        this.redirect = new String[]{String.valueOf(status), location};
    }

    public void holdFor(Duration delay) {
        this.delay = delay;
    }

    public void reset() {
        byPath.clear();
        redirect = null;
        fallback = 아무_경로도_안_정했을_때;
        delay = Duration.ZERO;
        calls.set(0);
        lastToken.set(null);
        lastPath.set(null);
        lastQuery.set(null);
    }

    /** 이름이 아니라 실제로 바인딩한 주소다({@link FakeAuth#baseUrl()}와 같은 이유). */
    public String baseUrl() {
        InetSocketAddress bound = server.getAddress();
        String host = bound.getAddress().getHostAddress();
        return "http://" + (bound.getAddress() instanceof Inet6Address ? "[" + host + "]" : host)
                + ":" + bound.getPort();
    }

    public int callCount() {
        return calls.get();
    }

    public String lastToken() {
        return lastToken.get();
    }

    public String lastPath() {
        return lastPath.get();
    }

    /** 쿼리가 아예 없으면 빈 문자열이다 — {@code null}이면 단언마다 널 검사가 붙는다. */
    public String lastQuery() {
        String query = lastQuery.get();
        return query == null ? "" : query;
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        lastPath.set(path);
        lastQuery.set(exchange.getRequestURI().getRawQuery());
        lastToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        exchange.getRequestBody().readAllBytes();
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (redirect != null) {
            exchange.getResponseHeaders().add("Location", redirect[1]);
            exchange.sendResponseHeaders(Integer.parseInt(redirect[0]), -1);
            exchange.close();
            return;
        }
        Response response = byPath.getOrDefault(path, fallback);
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        // 0은 "길이를 모른다"는 뜻이라 청크 응답이 된다. 빈 본문은 -1이다.
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
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
