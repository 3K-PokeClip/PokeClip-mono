package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.chat.collector.relay.RelayProperties;
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
 * {@code HighlightRequest}: eventId · source · streamTimestampMs · window{startMs, endMs} · evidence)다.
 */
class ClipDemoCardClientTest {

    private static final String TOKEN = "internal-token-for-demo-card-test";
    private static final DemoHighlight.Card CARD = new DemoHighlight.Card("demo-1800000000000", 45_000, 45_000, 75_000, 70);

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 201;

    @BeforeEach
    void start() throws IOException {
        // 🔴 루프백에 묶는다. 와일드카드면 남의 프로세스가 포트를 가로챈다(ClipPlatformClientTest와 같은 이유)
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        assertThat(body.path("window").path("endMs").asLong()).isEqualTo(75_000);
        assertThat(body.path("evidence").path("messageCount").asInt()).as("화면이 「채팅 급증 (70건)」으로 읽는다").isEqualTo(70);
        assertThat(body.path("evidence").has("ratio")).as("평소 채팅이 없는 방송의 배율은 지어낸 숫자라 안 싣는다").isFalse();
    }

    /** 이미 있는 카드(200) · 방송 없음(404)도 상태 그대로 돌려준다 — 부르는 쪽이 로그로 가른다 */
    @Test
    void 받은_상태를_그대로_돌려준다() {
        status = 404;

        assertThat(client(baseUrl()).send("s-1", CARD)).isEqualTo(404);
    }

    @Test
    void 못_닿으면_던지지_않고_마이너스_1이다() throws IOException {
        assertThat(client(closedPortBaseUrl()).send("s-1", CARD)).isEqualTo(-1);
    }

    @Test
    void 운영_배선은_전용_시한을_건다() {
        ClipDemoCardClient client = ClipDemoCardClient.create(RestClient.builder(), ClientHttpRequestFactoryBuilder.jdk(),
                relay(baseUrl()), new LinkProperties("http://localhost:8082", TOKEN));

        assertThat(client.send("s-1", CARD)).isEqualTo(201);
        assertThat(ClipDemoCardClient.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void 주소나_토큰이_비면_부팅이_죽는다() {
        assertThatThrownBy(() -> client("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClipDemoCardClient(RestClient.builder(), relay(baseUrl()),
                new LinkProperties("http://localhost:8082", ""))).isInstanceOf(IllegalStateException.class);
    }

    private ClipDemoCardClient client(String baseUrl) {
        return new ClipDemoCardClient(RestClient.builder(), relay(baseUrl), new LinkProperties("http://localhost:8082", TOKEN));
    }

    private static RelayProperties relay(String baseUrl) {
        return new RelayProperties(true, 10_000, Duration.ofMillis(100), baseUrl);
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
