package com.pokeclip.chat.collector.platforms;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.web.support.LogCaptor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수집기 → clip 「이 방송이 이 플랫폼에서 켜져 있다」 알림(POK-397). clip 쪽 계약은 {@code PUT /internal/broadcasts/{id}/platforms/{P}}
 * (204 · 404 {@code broadcast_not_found}). 재는 것: 경로·토큰·본문 · 204를 받으면 그 (방송, 플랫폼)은 다시 안 보냄 · 실패면 다음에
 * 다시 보냄 · 실패 경고는 (방송, 플랫폼)마다 첫 번만 WARN · 던지지 않음 · 전용 시한.
 */
class ClipPlatformClientTest {

    private static final String TOKEN = "internal-token-for-platform-test";
    private static final Instant SEEN = Instant.parse("2026-10-23T10:00:00Z");

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 204;
    private volatile Duration delay = Duration.ZERO;
    /** 참이면 응답 없이 연결을 끊는다(예외 갈래: 받는 쪽은 상태 코드 없이 입출력 오류를 본다). */
    private volatile boolean dropConnection;

    @BeforeEach
    void start() throws IOException {
        // 🔴 루프백에 묶는다. 와일드카드면 남의 프로세스가 포트를 가로챈다(이 저장소 실측).
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
            if (dropConnection) {
                exchange.close();
                return;
            }
            if (status == 204) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                byte[] out = "{\"error\":\"broadcast_not_found\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, out.length);
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 처음_알리면_PUT이_나가고_받았으면_다시_안_보낸다() {
        ClipPlatformClient client = client(baseUrl());

        client.seen("live/A 1", Platform.CHZZK, SEEN);
        client.seen("live/A 1", Platform.CHZZK, SEEN.plusSeconds(60));

        assertThat(paths).as("방송 번호는 경로 변수로 인코딩된다").containsExactly(
                "PUT /internal/broadcasts/live%2FA%201/platforms/CHZZK");
        assertThat(tokens).containsExactly(TOKEN);
        assertThat(bodies).containsExactly("{\"seenAt\":\"2026-10-23T10:00:00Z\"}");
    }

    @Test
    void 플랫폼과_방송이_다르면_따로_알린다() {
        ClipPlatformClient client = client(baseUrl());

        client.seen("s-1", Platform.CHZZK, SEEN);
        client.seen("s-1", Platform.SOOP, SEEN);
        client.seen("s-2", Platform.CHZZK, SEEN);

        assertThat(paths).containsExactly("PUT /internal/broadcasts/s-1/platforms/CHZZK",
                "PUT /internal/broadcasts/s-1/platforms/SOOP", "PUT /internal/broadcasts/s-2/platforms/CHZZK");
    }

    /**
     * 404(편지보다 먼저 왔다 · 옛 clip이라 경로가 없다)·5xx면 다음 관측에 다시 보낸다. 경고는 (방송, 플랫폼)마다 첫 번만 WARN이고 뒤는
     * DEBUG다: 옛 clip과 섞인 배포 중에는 방송 수 × 매분 실패한다(계획 검증 L4). 받으면 다시 안 보낸다.
     */
    @Test
    void 실패하면_다음에_다시_보내고_경고는_첫_번만이다() {
        ClipPlatformClient client = client(baseUrl());
        status = 404;

        try (LogCaptor captor = new LogCaptor()) {
            client.seen("s-1", Platform.CHZZK, SEEN);
            client.seen("s-1", Platform.CHZZK, SEEN);
            status = 503;
            client.seen("s-1", Platform.CHZZK, SEEN);

            List<String> warns = captor.events().stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(e -> e.getFormattedMessage())
                    .filter(m -> m.startsWith("platforms.notify_failed"))
                    .toList();
            assertThat(warns).singleElement().satisfies(m -> assertThat(m)
                    .contains("stream=s-1").contains("platform=CHZZK").contains("status=404")
                    .doesNotContain(TOKEN));
        }
        status = 204;
        client.seen("s-1", Platform.CHZZK, SEEN);
        client.seen("s-1", Platform.CHZZK, SEEN);

        assertThat(paths).hasSize(4);
    }

    @Test
    void 못_닿아도_던지지_않고_다음에_다시_보낸다() throws IOException {
        client(closedPortBaseUrl()).seen("s-1", Platform.CHZZK, SEEN);

        // 🔴 같은 인스턴스로 실패 뒤 성공을 잰다. 새 인스턴스로 두 번째를 부르면 메모가 비어 있어, 예외 갈래에서 실패를 「받음」으로
        // 적는 결함도 초록이다(감사 1회차 m1, 주입 C2).
        ClipPlatformClient client = client(baseUrl());
        dropConnection = true;
        client.seen("s-1", Platform.CHZZK, SEEN);
        dropConnection = false;
        client.seen("s-1", Platform.CHZZK, SEEN);
        client.seen("s-1", Platform.CHZZK, SEEN);

        assertThat(paths).as("끊긴 한 번 + 다시 보낸 한 번, 받은 뒤로는 안 보냄").hasSize(2);
    }

    /** 걷는 방송 목록에서 빠진 방송의 메모는 지운다(메모가 끝없이 쌓이지 않게). 다시 걸으면 다시 알린다. 다른 플랫폼 메모는 그대로다. */
    @Test
    void 목록에서_빠진_방송의_메모를_지운다() {
        ClipPlatformClient client = client(baseUrl());
        client.seen("s-1", Platform.CHZZK, SEEN);
        client.seen("s-2", Platform.CHZZK, SEEN);
        client.seen("s-1", Platform.SOOP, SEEN);

        client.retain(Platform.CHZZK, Set.of("s-2"));
        client.seen("s-1", Platform.CHZZK, SEEN);
        client.seen("s-2", Platform.CHZZK, SEEN);
        client.seen("s-1", Platform.SOOP, SEEN);

        assertThat(paths).hasSize(4).last().isEqualTo("PUT /internal/broadcasts/s-1/platforms/CHZZK");
    }

    /** 시한은 운영 배선({@link ClipPlatformClient#create})을 그대로 불러 행동으로 잰다. 방송 정보 틱 하나를 오래 붙잡지 않는다. */
    @Test
    void clip이_답을_안_주면_전용_시한에서_끊는다() {
        delay = Duration.ofSeconds(20);
        ClipPlatformClient client = ClipPlatformClient.create(RestClient.builder(), ClientHttpRequestFactoryBuilder.jdk(),
                new PlatformsProperties(baseUrl()), new LinkProperties("http://localhost:8082", TOKEN));

        long started = System.nanoTime();
        client.seen("s-1", Platform.CHZZK, SEEN);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(paths).as("요청이 안 나갔으면 시한을 잰 것이 아니다").hasSize(1);
        assertThat(took).as("읽기 시한 1초").isLessThan(Duration.ofMillis(3_000));
    }

    @Test
    void 주소나_토큰이_비면_만들_때_죽는다() {
        assertThatThrownBy(() -> client(""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CLIP_BASE_URL");
        assertThatThrownBy(() -> new ClipPlatformClient(RestClient.builder(), new PlatformsProperties(baseUrl()),
                new LinkProperties("http://localhost:8082", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERNAL_API_TOKEN");
    }

    /** 운영과 같은 JDK 스택으로 못박는다(Apache 5의 wire 로거가 헤더를 찍는다, 중계 시험과 같은 이유). */
    private ClipPlatformClient client(String baseUrl) {
        return new ClipPlatformClient(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()),
                new PlatformsProperties(baseUrl),
                new LinkProperties("http://localhost:8082", TOKEN));
    }

    private String baseUrl() {
        return "http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    private static String closedPortBaseUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort();
        }
    }
}
