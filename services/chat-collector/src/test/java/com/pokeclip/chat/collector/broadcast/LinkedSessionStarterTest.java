package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.link.ChzzkLinkClient;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 붙이기 문이 <b>봉투 없이</b> 값 셋만으로 열리는가 (POK-219 태스크 2).
 *
 * <p>재부착에는 SQS 봉투가 없다. 문이 봉투를 받으면 재부착이 가짜 봉투를 지어내야 하고,
 * 그러면 봉투의 다른 칸(순번·추적 번호)이 뜻 없는 값으로 채워져 <b>로그가 거짓말을 한다.</b>
 *
 * <p><b>시작 시각이 실제로 흘렀는지를 {@code isStaleStart}로 잰다.</b> 방송 번호만 단언하면
 * 시작 시각을 통째로 버리는 구현도 초록이다 — 그러면 갈아끼움 판정이 모든 방송을 「낡음」으로
 * 읽는다({@code Instant.EPOCH}가 언제나 더 이르다).
 */
@FakeChzzkTest
class LinkedSessionStarterTest extends IntegrationTestSupport {

    private static final Instant 시작 = Instant.parse("2026-08-31T04:00:00Z");

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private SessionRegistry registry;
    private FakeAuth auth;

    @AfterEach
    void tearDown() {
        if (registry != null) registry.closeAll();
        if (auth != null) auth.stop();
        behavior.reset();
    }

    @Test
    void 봉투_없이_방송_번호와_시작_시각만으로_붙일_수_있다() {
        auth = FakeAuth.start();
        auth.grants(7L, "channel-1", "token-1");
        registry = newRegistry();
        LinkedSessionStarter starter = new LinkedSessionStarter(newLinkClient(), registry);

        ProcessResult result = starter.start("live-A-001", new StreamerId(true, 7L), 시작);

        assertThat(result).isEqualTo(ProcessResult.PROCESSED);
        assertThat(registry.currentStreamIdOf(7L)).isEqualTo("live-A-001");
        // 시작 시각이 SessionKey까지 갔는가 — 양쪽으로 잰다. 한쪽만 보면 시각을
        // 버리고 상수를 넣은 구현도 통과한다.
        assertThat(registry.isStaleStart(7L, 시작.minusSeconds(1))).isTrue();
        assertThat(registry.isStaleStart(7L, 시작.plusSeconds(1))).isFalse();
    }

    /**
     * 🔴 <b>{@code PROCESSED}가 아니다</b>(POK-219 감사 라운드 3). 러너에게는 둘 다
     * 「지운다」로 같지만 <b>「붙었나」의 답이 정반대</b>라, 뭉쳐 두면 재부착이 안 붙은
     * 방송에 공백을 찍는다. <b>메모도 여기서 안 남긴다</b> — 재부착에는 지울 편지가
     * 없는데 여기서 남기면 재부착이 자기를 24시간 막는다.
     *
     * <p>메모를 남기는 쪽은 {@code BroadcastEventProcessorTest}가 잰다.
     */
    @Test
    void 연동이_영구히_거절되면_세션을_안_열고_LINK_REFUSED로_돌려준다() {
        auth = FakeAuth.start();
        auth.refuses(7L, "NOT_LINKED");
        registry = newRegistry();
        LinkedSessionStarter starter = new LinkedSessionStarter(newLinkClient(), registry);

        ProcessResult result = starter.start("live-A-001", new StreamerId(true, 7L), 시작);

        assertThat(result).isEqualTo(ProcessResult.LINK_REFUSED);
        assertThat(registry.activeCount()).isZero();
    }

    private ChzzkLinkClient newLinkClient() {
        // 🔴 <b>ASCII만 쓴다.</b> 이 값은 HTTP 헤더로 나가고 JDK HttpClient가
        // 한글 헤더 값을 IllegalArgumentException으로 거절한다 — 그 예외는
        // ChzzkLinkClient의 catch(Exception)이 「auth에 못 닿았다」로 바꿔 삼키므로
        // RETRY_LATER만 보이고 원인은 안 보인다(이 검사를 쓰다 실제로 밟았다).
        return new ChzzkLinkClient(restClientBuilder,
                new LinkProperties(auth.baseUrl(), "internal-token"));
    }

    private SessionRegistry newRegistry() {
        return new SessionRegistry(
                new ChzzkProperties(true, "설정-토큰-쓰면-안-된다", "http://localhost:" + port,
                        Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofSeconds(60)),
                restClientBuilder, new ChatBuffer(1_000),
                TestPersistence.disabledPersister(), ChatArchive.NONE);
    }

    /**
     * auth의 {@code POST /internal/chzzk-link/resolve}를 흉내 낸다.
     *
     * <p>🔴 <b>루프백에 바인딩한다</b>({@code InetSocketAddress(0)}이 아니라). 와일드카드에
     * 걸면 남의 프로세스가 포트를 가로채 CI를 8회에 1번 깬다(POK-174 실측).
     */
    private static final class FakeAuth {

        private static final Pattern USER_ID = Pattern.compile("\"userId\"\\s*:\\s*(\\d+)");

        private final HttpServer server;
        private final Map<Long, String> bodies = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();

        private FakeAuth(HttpServer server) {
            this.server = server;
        }

        static FakeAuth start() {
            try {
                HttpServer server = HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "fake-auth-starter");
                    thread.setDaemon(true);
                    return thread;
                }));
                FakeAuth fake = new FakeAuth(server);
                server.createContext("/", fake::handle);
                server.start();
                return fake;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void grants(long userId, String channelId, String accessToken) {
            bodies.put(userId, "{\"valid\":true,\"channelId\":\"" + channelId + "\",\"accessToken\":\""
                    + accessToken + "\",\"expiresAt\":\"2027-01-01T00:00:00Z\"}");
        }

        void refuses(long userId, String reason) {
            bodies.put(userId, "{\"valid\":false,\"reason\":\"" + reason + "\"}");
        }

        String baseUrl() {
            // 주소가 아니라 <b>이름</b>으로 잇는다. 루프백 바인딩이 IPv6면
            // getHostString()이 대괄호 없는 {@code 0:0:0:0:0:0:0:1}을 줘서
            // URI가 IllegalArgumentException으로 깨진다(이 기계에서 실측).
            return "http://localhost:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = USER_ID.matcher(request);
            long userId = matcher.find() ? Long.parseLong(matcher.group(1)) : -1L;
            byte[] body = bodies.getOrDefault(userId, "{\"valid\":false,\"reason\":\"NOT_LINKED\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
