package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.link.LinkProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수집기 → clip 데모 카드. clip 쪽 계약은 판별기와 같은 {@code POST /internal/broadcasts/{id}/highlights}(계약 2A ·
 * {@code HighlightRequest}: eventId · source · streamTimestampMs · window{startMs, endMs} · evidence)다. 배선({@link DemoConfiguration})도 여기서 잰다.
 */
class ClipDemoCardClientTest {

    private static final String TOKEN = "internal-token-for-demo-card-test";
    private static final DemoHighlight.Card CARD = new DemoHighlight.Card("demo-1800000000000", 45_000, 45_000, 60_000, 70);

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 201;
    private volatile Duration delay = Duration.ZERO;

    @BeforeEach
    void start() throws IOException {
        // 🔴 루프백에 묶는다. 와일드카드면 남의 프로세스가 포트를 가로챈다(ClipPlatformClientTest와 같은 이유)
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 판별기와_같은_문에_자동_카드_모양으로_넣는다() {
        int answered = client(baseUrl()).send("live/A 1", CARD);

        assertThat(answered).isEqualTo(201);
        assertThat(paths).as("방송 번호는 경로 변수로 인코딩된다")
                .containsExactly("POST /internal/broadcasts/live%2FA%201/highlights");
        assertThat(tokens).containsExactly(TOKEN);
        JsonNode body = new ObjectMapper().readTree(bodies.get(0));
        assertThat(body.path("eventId").asString()).isEqualTo("demo-1800000000000");
        assertThat(body.path("source").asString()).as("clip 2A는 자동 카드만 받는다").isEqualTo("auto");
        assertThat(body.path("streamTimestampMs").asLong()).isEqualTo(45_000);
        assertThat(body.path("window").path("startMs").asLong()).isEqualTo(45_000);
        assertThat(body.path("window").path("endMs").asLong()).isEqualTo(60_000);
        assertThat(body.path("evidence").path("messageCount").asInt()).as("화면이 「채팅 급증 (70건)」으로 읽는다").isEqualTo(70);
        assertThat(body.path("evidence").has("ratio")).as("평소 채팅이 없는 방송의 배율은 지어낸 숫자라 안 싣는다").isFalse();
    }

    /** 이미 있는 카드(200) · 방송 없음(404)도 상태 그대로 돌려준다 — 부르는 쪽이 다시 보낼지 가른다 */
    @Test
    void 받은_상태를_그대로_돌려준다() {
        status = 404;

        assertThat(client(baseUrl()).send("s-1", CARD)).isEqualTo(404);
    }

    @Test
    void 못_닿으면_던지지_않고_마이너스_1이다() throws IOException {
        assertThat(client(closedPortBaseUrl()).send("s-1", CARD)).isEqualTo(-1);
    }

    /**
     * 운영 배선은 전용 시한을 건다 — 행동으로 잰다(PR #558 리뷰: 상수만 보면 {@code create()}에서 시한 배선이 빠져도 초록이다). clip이 매달리면
     * 카드 실행기 스레드가 묶여 다음 카드도 안 나간다. 쌍둥이 {@code ClipRelayClientTest}와 같은 꼴이다
     */
    @Test
    void 운영_배선은_clip이_답을_안_주면_전용_시한에서_끊는다() {
        delay = ClipDemoCardClient.READ_TIMEOUT.plusSeconds(2);
        ClipDemoCardClient client = ClipDemoCardClient.create(RestClient.builder(), ClientHttpRequestFactoryBuilder.jdk(),
                baseUrl(), TOKEN);

        long started = System.nanoTime();
        int answered = client.send("s-1", CARD);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(answered).isEqualTo(-1);
        assertThat(took).as("시한까지는 기다린다").isGreaterThanOrEqualTo(ClipDemoCardClient.READ_TIMEOUT.minusMillis(200));
        assertThat(took).as("응답이 올 때까지 매달리지 않는다").isLessThan(ClipDemoCardClient.READ_TIMEOUT.plusSeconds(1));
    }

    @Test
    void 주소나_토큰이_비면_만들지_않는다() {
        assertThatThrownBy(() -> client("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClipDemoCardClient(RestClient.builder(), baseUrl(), ""))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 배선(PR #558 리뷰): clip 주소와 내부 토큰이 있으면 중계와 상관없이 clip 클라이언트가 물리고, 하나라도 비면 보내지 않는다 — 부팅은 안
     * 죽인다. 중계가 꺼진 운영 컨텍스트에서 실제로 물리는지는 {@code DemoChatEndpointTest}가 본다
     */
    @Test
    void 주소와_토큰이_있으면_clip_클라이언트가_물리고_하나라도_비면_안_보낸다() {
        DemoConfiguration configuration = new DemoConfiguration();

        assertThat(configuration.demoCardSink(RestClient.builder(), ClientHttpRequestFactoryBuilder.jdk(),
                new DemoProperties(baseUrl()), link(TOKEN))).isInstanceOf(ClipDemoCardClient.class);
        assertThat(configuration.demoCardSink(RestClient.builder(), ClientHttpRequestFactoryBuilder.jdk(),
                new DemoProperties(baseUrl()), link(""))).isSameAs(DemoHighlight.Sink.NONE);
        assertThat(configuration.demoCardSink(RestClient.builder(), ClientHttpRequestFactoryBuilder.jdk(),
                new DemoProperties(" "), link(TOKEN))).isSameAs(DemoHighlight.Sink.NONE);
        assertThat(configuration.demoCardSink(RestClient.builder(), ClientHttpRequestFactoryBuilder.jdk(),
                new DemoProperties(null), link(TOKEN))).isSameAs(DemoHighlight.Sink.NONE);
    }

    private ClipDemoCardClient client(String baseUrl) {
        return new ClipDemoCardClient(RestClient.builder(), baseUrl, TOKEN);
    }

    private static LinkProperties link(String token) {
        return new LinkProperties("http://localhost:8082", token);
    }

    private String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    private static String closedPortBaseUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }
}
