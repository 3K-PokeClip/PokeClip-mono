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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p><b>{@code delegation}에서 {@code support}로 옮겨 public이 됐다</b>(POK-174).
 * 스프링 컨텍스트에 태우는 하나를 {@link IntegrationTestSupport}가 들고 있어서, 그 클래스가
 * 볼 수 있는 자리여야 한다. 창구가 둘(판정·목록)이 되면서 <b>경로별 응답</b>도 생겼다 —
 * 한 시험에서 판정은 OWNER로, 목록은 500으로 두는 갈래가 필요하다.
 */
public final class FakeAuth implements AutoCloseable {

    /** 경로에 답이 없을 때 쓰는 값. {@link #reset()}이 되돌려 두는 기본이기도 하다. */
    private static final Response 아무_경로도_안_정했을_때 = new Response(503, "");

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final ExecutorService threads;
    private final Map<String, Response> byPath = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<Instant> firstRequestAt = new AtomicReference<>();

    private volatile Response fallback = 아무_경로도_안_정했을_때;
    private volatile Duration delay = Duration.ZERO;

    /** {상태, Location}. {@link #redirectTo}가 걸면 경로를 안 가리고 이것으로만 답한다. */
    private volatile String[] redirect;

    private FakeAuth(HttpServer server, ExecutorService threads) {
        this.server = server;
        this.threads = threads;
    }

    /**
     * 🔴 <b>루프백 주소에 바인딩한다 — 와일드카드({@code new InetSocketAddress(0)})가 아니다.</b>
     *
     * <p>와일드카드로 잡으면 <b>자리를 못 지킨다.</b> 그것은 IPv6 와일드카드({@code [::]})에
     * 붙는데, 커널은 같은 포트의 <b>IPv4 루프백</b> 바인딩을 주소 계열이 달라 충돌로 보지 않는다.
     * 그래서 뒤늦게 뜬 아무 프로세스나 {@code 127.0.0.1:같은포트}를 잡을 수 있고, 잡으면
     * <b>더 구체적인 주소</b>라 {@code localhost} 트래픽을 통째로 가져간다 — 우리 핸들러는 한 번도
     * 안 돌고 {@code callCount()}가 0이 된다. 그 0은 「요청이 안 나갔다」로 읽혀
     * <b>원인이 우리 코드에 있는 것처럼 보인다.</b>
     *
     * <p><b>실측(2026-08-26)</b> — 이 기계에서 임시 포트를 3,000번 잡아 요청을 보내니 넷이
     * 남의 서버로 갔다: MCP 서버의 {@code 401 …restricted mode} · IntelliJ 빌드 서버(57972)의
     * 읽기 시한 · 404 하나 · 401 하나. 세션 다섯이 한 기계에서 도는 중이라 임시 포트 대역에
     * 남의 루프백 서버가 계속 생긴다. 전수 16회 중 2회 났던 간헐 실패의 원인이 이것이다.
     *
     * <p>루프백에 바인딩하면 커널이 그 포트를 <b>예약</b>한다 — 남이 같은 포트를 잡으려 하면
     * {@code BindException}이고, 임시 포트 자동 할당도 이미 잡힌 포트를 피한다(2,000회 실측 0건).
     * {@link com.pokeclip.clip.support.FakeAuthPortTest}가 그 자리를 지킨다.
     */
    public static FakeAuth start() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
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

    /** 경로를 안 가리고 다 이 답을 준다. 창구가 하나뿐인 시험이 쓴다. */
    public void respondWith(int status, String body) {
        this.fallback = new Response(status, body);
    }

    /**
     * 그 경로에만 이 답을 준다. 다른 경로는 {@link #respondWith(int, String)}로 정한 값,
     * 그것도 없으면 <b>503</b>이다 — 「안 정한 창구」가 조용히 200을 주면 시험이
     * <b>아무것도 안 재면서 초록</b>이 된다.
     */
    public void respondWith(String path, int status, String body) {
        byPath.put(path, new Response(status, body));
    }

    /**
     * 경로를 안 가리고 이 상태 코드와 {@code Location}으로만 답한다.
     *
     * <p><b>리다이렉트를 안 따라가는 것을 재려고 있다.</b> HC5 기본 전략은 {@code 307}·{@code 308}에서
     * <b>원 요청을 그대로 다시 보낸다</b> — 헤더와 본문이 통째로 따라가므로 {@code X-Internal-Token}이
     * 리다이렉트가 가리키는 아무 출처에나 도착한다. {@code 301}·{@code 302}·{@code 303}은 POST를
     * GET으로 바꾼 <b>새 요청</b>이라 토큰·본문은 안 따라가지만 <b>따라가는 것 자체는 같다</b>.
     */
    public void redirectTo(int status, String location) {
        this.redirect = new String[]{String.valueOf(status), location};
    }

    public void holdFor(Duration delay) {
        this.delay = delay;
    }

    /**
     * 응답·지연·기록을 처음 상태로 되돌린다.
     *
     * <p>🔴 <b>컨텍스트에 태운 하나는 상태가 JVM 전역이다.</b> 앞 시험이 걸어 둔 답이 남으면
     * 뒤 시험이 자기가 안 건 답으로 통과한다 — {@link IntegrationTestSupport}가
     * {@code @BeforeEach}로 이것을 부른다(상위 클래스의 {@code @BeforeEach}가 먼저 돈다).
     */
    public void reset() {
        byPath.clear();
        redirect = null;
        fallback = 아무_경로도_안_정했을_때;
        delay = Duration.ZERO;
        calls.set(0);
        lastToken.set(null);
        lastBody.set(null);
        lastPath.set(null);
        firstRequestAt.set(null);
    }

    /**
     * <b>이름이 아니라 실제로 바인딩한 주소를 돌려준다.</b> {@code localhost}는 {@code 127.0.0.1}과
     * {@code ::1} 둘로 풀리므로, 루프백 하나에만 바인딩해 놓고 이름으로 부르면 해석 순서가
     * 바뀌는 환경에서 못 닿는다. 여기서 바인딩한 주소를 그대로 쓰면 그 갈림이 아예 없다.
     */
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

    public String lastBody() {
        return lastBody.get();
    }

    public String lastPath() {
        return lastPath.get();
    }

    /** 첫 요청이 <b>서버에 도착한 시각</b>부터 잰다. 클라이언트 조립 시간이 안 섞인다. */
    public Duration sinceFirstRequest() {
        Instant at = firstRequestAt.get();
        return at == null ? Duration.ZERO : Duration.between(at, Instant.now());
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        firstRequestAt.compareAndSet(null, Instant.now());
        String path = exchange.getRequestURI().getPath();
        lastPath.set(path);
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
