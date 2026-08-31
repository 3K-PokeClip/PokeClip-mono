package com.pokeclip.chat.collector.broadcast.reattach;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.web.support.LogCaptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * clip의 {@code GET /internal/broadcasts/live}를 부르는 쪽. 계약 정본은 clip의
 * {@code LiveBroadcastsResponse}와 {@code services/README.md}「방송 중 목록 창구」다.
 *
 * <p><b>가짜 clip은 진짜로 듣는 소켓이어야 한다.</b> {@code MockWebServer}는 이 저장소에도
 * 그레이들 캐시에도 없고(계획 검증 C2), {@code MockRestServiceServer}는 요청 팩토리를
 * 갈아치워 「주입받은 빌더를 쓰는가」를 통째로 무력화한다.
 * <b>같은 자리의 기존 검사 {@code link/ChzzkLinkClientTest}가 정확히 이 모양이다</b> —
 * {@code com.sun.net.httpserver.HttpServer}로 가짜를 세운다. 그것을 베꼈다.
 */
class LiveBroadcastClientTest {

    private static final String INTERNAL_TOKEN = "internal-token-for-test";

    /**
     * 가짜 clip이 붙들고 있는 시간. 아래 READ_TIMEOUT보다 훨씬 길다.
     * <b>둘의 값은 {@code ChzzkLinkClientTest}에서 이미 근거를 재서 정한 것을 그대로 쓴다</b> —
     * 시한만 늘리고 지연을 그대로 두면 임계가 지연을 넘어서 판별력이 통째로 사라진다.
     */
    private static final Duration CLIP_DELAY = Duration.ofSeconds(20);

    /** 이 값이 실제로 걸리는지가 시한 검사의 표적이다. 운영값은 application.yml에 있다. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private FakeClip clip;

    @BeforeEach
    void startFakeClip() {
        clip = FakeClip.start();
    }

    @AfterEach
    void stopFakeClip() {
        clip.close();
    }

    @Test
    void 방송_목록을_받아_읽는다() {
        givenClipResponds(200, """
                {"broadcasts":[
                   {"streamId":"live-A-001","streamerId":"7","startedAt":"2026-08-31T04:00:00Z"}
                 ],"truncated":false}""");

        LiveBroadcasts result = client().list();

        assertThat(result.truncated()).isFalse();
        assertThat(result.broadcasts()).singleElement().satisfies(item -> {
            assertThat(item.streamId()).isEqualTo("live-A-001");
            assertThat(item.streamerId()).isEqualTo("7");
            assertThat(item.startedAt()).isEqualTo(Instant.parse("2026-08-31T04:00:00Z"));
        });
    }

    /**
     * clip이 이 칸을 <b>지우지 않고 {@code null}로 싣는다</b>(그쪽 record의 주석이 그것을
     * 못박았다). 그런 줄을 통째로 버리면 그 방송은 재부착이 영영 못 줍고, 수집기는 그런
     * 줄이 있었다는 것조차 모른다.
     */
    @Test
    void 시작_시각이_null인_줄도_버리지_않고_읽는다() {
        givenClipResponds(200, """
                {"broadcasts":[{"streamId":"live-A-001","streamerId":"7","startedAt":null}],
                 "truncated":false}""");

        LiveBroadcasts result = client().list();

        assertThat(result.broadcasts()).singleElement()
                .satisfies(item -> assertThat(item.startedAt()).isNull());
    }

    /**
     * 문항 2 — 위 검사들은 <b>clip을 아예 안 부르고 상수를 돌려주는 구현에도 초록</b>이다.
     * 요청이 실제로 나갔는지, 계약대로 생겼는지는 상대 쪽에서 센다.
     *
     * <p>헤더를 빠뜨리면 운영에서 <b>전부 401</b>이 되고, 재부착은 매 회차 예외만 던지며
     * 방송을 하나도 못 줍는다.
     *
     * <p><b>양성 대조를 같이 둔다</b> — 요청이 서버에 닿지도 않았는데 {@code null}이
     * 「헤더가 없다」로 읽히면 아무것도 안 재게 된다({@code ChzzkLinkClientTest}가 데인 자리:
     * 전수 실행 5회 중 1회가 그 모양이었다).
     */
    @Test
    void 내부_토큰을_헤더에_싣는다() {
        givenClipResponds(200, """
                {"broadcasts":[],"truncated":false}""");

        client().list();

        assertThat(clip.callCount())
                .as("요청이 실제로 나갔는가 — 0이면 서버에 닿기 전에 끝난 것")
                .isEqualTo(1);
        assertThat(clip.lastInternalToken()).isEqualTo(INTERNAL_TOKEN);
        assertThat(clip.lastPath()).isEqualTo("/internal/broadcasts/live");
    }

    /**
     * 상한에 닿는 것 자체가 「명부가 이상하다」는 신호다 — 종료 알림을 놓친 방송이
     * 영원히 {@code live}로 남고 치우는 장치가 없다(POK-218이 찾아 별도 카드가 났다).
     */
    @Test
    void 잘렸으면_경고를_남긴다() {
        givenClipResponds(200, """
                {"broadcasts":[],"truncated":true}""");

        try (LogCaptor captor = new LogCaptor()) {
            client().list();
            assertThat(captor.levelOf("chat.reattach.live_list_truncated")).isEqualTo(Level.WARN);
        }
    }

    /** 위 시험은 「WARN이 있다」만 잰다 — 늘 찍는 구현도 통과한다. 그 방향을 여기서 막는다. */
    @Test
    void 안_잘렸으면_경고가_없다() {
        givenClipResponds(200, """
                {"broadcasts":[],"truncated":false}""");

        try (LogCaptor captor = new LogCaptor()) {
            client().list();
            assertThat(captor.levelOf("chat.reattach.live_list_truncated")).isNull();
        }
    }

    /**
     * 실패는 <b>예외로 나간다</b> — 빈 목록으로 접으면 「clip이 죽었다」와 「방송이 없다」가
     * 같아지고, 부르는 쪽(태스크 7)이 그 둘을 못 가른다.
     *
     * <p>🔴 {@code captor.messages()}는 포맷된 한 줄만 준다. 나중에 이 자리에
     * {@code log.warn("...", e)} 같은 catch가 생기면 <b>토큰이 ThrowableProxy 안으로 새는데
     * 그 그물은 못 잡는다</b>(POK-127에서 실제로 일어난 일이다). 그래서 예외 사슬까지 편다.
     */
    @Test
    void clip이_401이면_예외가_나가고_토큰이_로그에_안_샌다() {
        givenClipResponds(401, "");

        try (LogCaptor captor = new LogCaptor()) {
            assertThatThrownBy(() -> client().list()).isInstanceOf(RuntimeException.class);
            assertThat(captor.messages()).noneMatch(line -> line.contains(INTERNAL_TOKEN));
            assertThat(renderFully(captor))
                    .as("예외 안으로 새는 토큰은 messages()로 안 보인다")
                    .doesNotContain(INTERNAL_TOKEN);
        }
    }

    /**
     * 🔴 토큰은 {@link ReattachProperties}가 아니라 {@link LinkProperties}에서 온다
     * (계획 검증 C4). 이 서버에 이미 그 관례가 있다 —
     * {@code status/InternalApiConfiguration}이 수집 상태 창구의 토큰을 같은 자리에서
     * 가져오고 <b>그 창구도 auth를 안 부른다</b>. 서버 넷이 공유하는 비밀 하나이므로
     * 프로퍼티를 새로 만들면 같은 값을 두 곳에서 읽게 되고 한쪽만 고쳐져 갈라진다.
     */
    @Test
    void 내부_토큰이_비면_이_클라이언트를_만들_때_부팅이_죽는다() {
        LinkProperties blankToken = new LinkProperties("http://localhost:8082", "");

        assertThatThrownBy(() -> new LiveBroadcastClient(
                RestClient.builder(), reattachProperties(), blankToken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_TOKEN");
    }

    /**
     * {@code ${VAR:}} + 검증 규칙({@code services/CLAUDE.md}). 기본값을 안 주면 리터럴
     * {@code "${CLIP_BASE_URL}"}이 바인딩돼 <b>서버는 뜨고 재부착만 매 회차 실패</b>한다.
     */
    @Test
    void clip_주소가_비면_이_클라이언트를_만들_때_부팅이_죽는다() {
        ReattachProperties blankUrl = new ReattachProperties(
                "", true, Duration.ofMinutes(1), Duration.ofSeconds(5));

        assertThatThrownBy(() -> new LiveBroadcastClient(
                RestClient.builder(), blankUrl, linkProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLIP_BASE_URL");
    }

    /**
     * 문항 3 — 「주입받은 {@code RestClient.Builder}를 쓴다」를 값이 아니라 <b>행동</b>으로 잰다.
     *
     * <p>🔴 <b>이 검사는 결함 주입이 초록으로 나와서 생겼다.</b> {@code builder.build()}를
     * {@code RestClient.create()}로 되돌려도 나머지 여덟 건이 전부 초록이었다 — 가짜 clip이
     * 즉시 답하므로 시한이 걸리든 말든 결과가 같다. 즉 <b>그 자리를 재는 단언이 0개였다.</b>
     *
     * <p>재는 것이 이 서버가 이미 한 번 데인 자리다({@code CLAUDE.md}) —
     * {@code RestClient.create()}는 자동 설정을 우회해 {@code spring.http.clients.*}의 시한이
     * <b>어디에도 안 걸린다. 설정 파일은 완벽한데 타임아웃이 없고 증상이 조용하다</b>(평소엔
     * 응답이 빨라 아무 일도 없다). 검토 일곱 바퀴가 못 잡았고 리뷰 봇이 잡았다.
     *
     * <p>재부착에서 이것이 아픈 이유: 주기 실행이 clip 하나 때문에 무기한 매달리면 그 회차가
     * 영영 안 끝나고, 다음 회차도 안 온다 — 재부착이 조용히 죽는다.
     *
     * <p>시간은 <b>가짜 서버가 요청을 받은 시각</b>부터 잰다 — 클라이언트 조립 시간이 섞이면
     * 무엇을 쟀는지 흐려진다(POK-84 선례: 6.375초 → 0.696초).
     */
    @Test
    void clip이_답을_안_주면_주입받은_빌더의_시한에서_끊는다() {
        givenClipResponds(200, """
                {"broadcasts":[],"truncated":false}""");
        clip.holdFor(CLIP_DELAY);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(READ_TIMEOUT);

        LiveBroadcastClient client = new LiveBroadcastClient(
                RestClient.builder().requestFactory(factory), reattachProperties(), linkProperties());
        assertThatThrownBy(client::list).isInstanceOf(RuntimeException.class);

        Duration heldFor = clip.sinceFirstRequest();
        // 양성 대조. 요청이 아예 안 나갔다면 위 시간은 아무것도 안 잰 것이다.
        assertThat(clip.callCount())
                .as("요청을 안 보냈으면 시한을 잰 것이 아니다")
                .isEqualTo(1);
        assertThat(heldFor)
                .as("가짜 clip이 붙들고 있는 " + CLIP_DELAY.toSeconds()
                        + "초를 다 기다렸다면 주입받은 빌더를 안 쓴 것이다")
                .isLessThan(READ_TIMEOUT.multipliedBy(3));
    }

    private void givenClipResponds(int status, String body) {
        clip.respondWith(status, body);
    }

    /**
     * <b>요청 팩토리를 JDK로 못박는다 — 운영과 같은 스택이다</b>
     * ({@code spring.http.clients.imperative.factory=jdk}).
     *
     * <p>아무것도 안 고르면 Apache 5가 잡힌다 — AWS SDK가 httpclient5를 클래스패스에 올려
     * 두기 때문이다. 그 스택의 wire 로거는 TRACE에서 요청 헤더를 통째로 찍는다
     * ({@code ChzzkLinkClientTest}가 실제로 빨간불로 잡은 적이 있다).
     */
    private LiveBroadcastClient client() {
        return new LiveBroadcastClient(
                RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()),
                reattachProperties(), linkProperties());
    }

    private ReattachProperties reattachProperties() {
        return new ReattachProperties(clip.baseUrl(), true, Duration.ofMinutes(1), Duration.ofSeconds(5));
    }

    private LinkProperties linkProperties() {
        return new LinkProperties("http://localhost:8082", INTERNAL_TOKEN);
    }

    /** 포맷된 한 줄에 더해 예외 사슬의 메시지까지 편다. */
    private static String renderFully(LogCaptor captor) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : captor.events()) {
            text.append(event.getFormattedMessage()).append('\n');
            for (IThrowableProxy throwable = event.getThrowableProxy();
                 throwable != null; throwable = throwable.getCause()) {
                text.append(throwable.getClassName()).append(": ")
                        .append(throwable.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * 가짜 clip. 응답을 갈아 끼우고, 받은 요청을 센다.
     *
     * <p>🔴 <b>와일드카드에 바인딩하지 않는다</b>(계획 검증 T3). {@code new InetSocketAddress(0)}은
     * 모든 인터페이스에 묶여 <b>남의 프로세스가 그 포트를 가로챈다</b> — 이 프로젝트에서 CI를
     * 8회에 1번 깨던 결함이 정확히 그것이었고, 실제로 MCP 서버와 IntelliJ 빌드 서버가 답한 것을
     * 잡았다(통제 측정 500/500 → 0/500). <b>베낀 원본({@code ChzzkLinkClientTest})은 POK-219가
     * 같이 고쳤다</b> — 이 서버의 가짜 서버가 이제 전부 루프백이다.
     *
     * <p>전용 실행기를 준다 — 기본값(null)은 디스패처 스레드에서 핸들러를 돌리므로 서버 전체가
     * 그 한 요청에 묶이고, 그 스레드는 데몬이 아니라 JVM 종료도 늦춘다.
     */
    private static final class FakeClip implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService threads;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> lastInternalToken = new AtomicReference<>();
        private final AtomicReference<String> lastPath = new AtomicReference<>();

        private final AtomicReference<Instant> firstRequestAt = new AtomicReference<>();

        private volatile int status = 200;
        private volatile String body = "";
        private volatile Duration delay = Duration.ZERO;

        private FakeClip(HttpServer server, ExecutorService threads) {
            this.server = server;
            this.threads = threads;
        }

        static FakeClip start() {
            try {
                HttpServer server = HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "fake-clip");
                    thread.setDaemon(true);
                    return thread;
                });
                server.setExecutor(threads);
                FakeClip fake = new FakeClip(server, threads);
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

        /** 첫 요청이 <b>서버에 도착한 시각</b>부터 잰다. 클라이언트 조립 시간이 안 섞인다. */
        Duration sinceFirstRequest() {
            Instant at = firstRequestAt.get();
            return at == null ? Duration.ZERO : Duration.between(at, Instant.now());
        }

        String baseUrl() {
            return "http://" + server.getAddress().getAddress().getHostAddress()
                    + ":" + server.getAddress().getPort();
        }

        int callCount() {
            return calls.get();
        }

        String lastInternalToken() {
            return lastInternalToken.get();
        }

        String lastPath() {
            return lastPath.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            firstRequestAt.compareAndSet(null, Instant.now());
            lastPath.set(exchange.getRequestURI().getPath());
            lastInternalToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            exchange.getRequestBody().readAllBytes();
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
}
