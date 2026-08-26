package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.Spies;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * <b>연결을 여는 임계구역이 방송 상태 읽기까지 덮는가.</b>
 *
 * <p>상태를 락 <b>밖에서</b> 읽으면 「읽은 뒤 ~ 명부에 오르기 전」 창이 열린다. 그 창에서 방송이
 * 끝나면 이미 읽은 값이 {@code LIVE}라 {@code ended=false}로 열리고, {@code broadcastEnded}는
 * 연결이 명부에 없어 지나간다 — <b>연결이 토큰 만료까지 살아 있고</b> 클라이언트는 방송이 끝난
 * 줄 모른다(PR #109 봇 지적 ②, 2026-08-23 재현: 10초 뒤에도 안 닫힘, 표는 이미 {@code ended}).
 *
 * <p>🔴 <b>POK-174가 창의 표적과 뜻을 함께 바꿨다.</b>
 * <ul>
 *   <li><b>표적</b> — 전에는 {@code JumpCardService.snapshotsOf}를 감쌌는데 초기 카드 전송이
 *       없어져 그 메서드가 {@code open} 경로에서 <b>아예 안 불린다</b>. 그대로 두면 세 갈래가
 *       모두 빨간불인데 그중 둘은 「뜻이 뒤집혀서」가 아니라 <b>창이 안 열려서</b>다 —
 *       빨강이지만 아무것도 안 잰다(계획 검증 C1). 그래서 자물쇠 안에 살아남는 읽기인
 *       {@code broadcasts.findByStreamId}로 옮기고, <b>창이 실제로 열렸음을 단언</b>한다</li>
 *   <li><b>뜻</b> — 카드 갈래 하나만 뒤집힌다. <b>열기 전에 있던</b> 카드는 이제 안 오고
 *       (목록 문이 맡는다) <b>창 안에 커밋된</b> 카드는 여전히 온다 — 자물쇠가
 *       {@code publish}를 줄 세우기 때문이다. {@code ended} 갈래 둘은 그대로다</li>
 * </ul>
 *
 * <p><b>창을 벌리는 방법</b> — 조회를 spy로 감싸 <b>실제 조회가 끝난 뒤</b> 사건을 일으킨다.
 * 사건은 <b>다른 스레드</b>에서 시작하고 500ms를 잔다. 임계구역이 덮고 있으면 그 스레드는
 * 자물쇠에서 기다리다 우리가 끝난 뒤에 돌고, 안 덮고 있으면 500ms 안에 통째로 끝난다.
 * 「안 기다려서 통과」의 반대 방향이라 느린 기계에서 더 안전하다.
 *
 * <p>그 조회가 {@code @Transactional(readOnly = true)} 안이라 <b>같은 스레드에서 UPDATE를
 * 하면 안 된다</b> — PostgreSQL이 "cannot execute UPDATE in a read-only transaction"으로 거부한다
 * (재현 중 실측). 그래서 사건이 별도 스레드다.
 *
 * <p>🔴 <b>같은 조회를 {@code JumpCardService.record}도 부른다.</b> 창 안에서 카드를 저장하면
 * 이 spy가 다시 불리므로, <b>첫 호출에서만</b> 사건을 일으킨다. 안 그러면 사건이 자기를 다시
 * 부른다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamOpenWindowTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    /** 자물쇠 안에 살아남는 읽기를 감싼다 — 여기가 창을 벌리는 자리다(클래스 주석). */
    @MockitoSpyBean
    private BroadcastRepository broadcasts;

    private final int port;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;

    /** 창이 실제로 열렸나. 없으면 이 클래스는 언제든 자동 초록/자동 빨강이 된다. */
    private final AtomicBoolean 창이_열렸다 = new AtomicBoolean();

    StreamOpenWindowTest(@LocalServerPort int port, CardStreamRegistry registry, JdbcTemplate jdbc) {
        this.port = port;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
        창이_열렸다.set(false);
        // 답을 안 걸면 503이라 통로가 안 열린다 — 이 클래스는 자격이 아니라 창을 잰다.
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    /**
     * 🔴 <b>뜻이 뒤집힌 갈래.</b> 전에는 「창에 커밋된 카드도 <u>초기 스냅샷과 함께</u> 온다」를 쟀고,
     * 지금은 「<b>열기 전 카드는 안 오고</b> 창에 커밋된 카드는 {@code publish}로 온다」를 잰다.
     * 뒤엣것이 오는 근거는 자물쇠다 — {@code publish}가 우리가 끝날 때까지 줄을 선다.
     */
    @Test
    void 열기_전_카드는_안_오고_창에_커밋된_카드는_그대로_온다() {
        broadcasts.save(Broadcast.startedNow("s-win-card", TestIds.STREAMER, 701L, Instant.now(), null));
        post2A("s-win-card", "evt-before", 1_000_000L);

        AtomicInteger inWindowStatus = new AtomicInteger();
        열린_창에서("s-win-card", () -> {
            Thread writer = new Thread(() -> inWindowStatus.set(
                    post2A("s-win-card", "evt-in-window", 5_000_000L).statusCode()), "in-window-writer");
            writer.start();
            잠깐(500);
        });

        try (SseReader reader = open("s-win-card", TestTokens.access("2001"))) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
            창이_열렸는가();
            awaitUntil(() -> 카드창시작(reader).contains(5_000_000L), Duration.ofSeconds(5));
            assertThat(카드창시작(reader))
                    .as("창에 커밋된 카드가 publish에도 안 실리면 카드 목록 문을 다시 부를 때까지"
                            + " 영영 안 온다 — POK-174 뒤로 재연결은 안 메운다")
                    .containsExactly(5_000_000L);
        }
        assertThat(inWindowStatus.get()).as("창 안의 저장 자체는 성공해야 시험이 의미가 있다")
                .isIn(200, 201);
    }

    /** {@code ended} 갈래는 POK-174에서 뜻이 안 바뀐다 — 초기 전송에서 살아남은 것이 이것이다. */
    @Test
    void 상태를_읽은_뒤_연결이_열리는_사이에_방송이_끝나도_ended를_받는다() {
        broadcasts.save(Broadcast.startedNow("s-win-end", TestIds.STREAMER, 702L, Instant.now(), null));
        post2A("s-win-end", "evt-1", 1_000_000L);

        열린_창에서("s-win-end", () -> {
            Thread ender = new Thread(() -> {
                jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = now() "
                        + "WHERE stream_id = 's-win-end'");
                registry.broadcastEnded("s-win-end");
            }, "in-window-ender");
            ender.start();
            잠깐(500);
        });

        try (SseReader reader = open("s-win-end", TestTokens.access("2002"))) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
            창이_열렸는가();
            assertThat(reader.awaitName("ended", Duration.ofSeconds(5)))
                    .as("표는 ended인데 ended를 못 받으면 그 연결은 토큰 만료까지 살아 있고 "
                            + "클라이언트는 방송이 끝난 줄 모른다").isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(5))).isTrue();
        }
    }

    /**
     * <b>창에서 방송이 끝났는데 {@code broadcastEnded} 알림은 이미 지나간 경우.</b>
     *
     * <p>위 시험은 창 안에서 {@code broadcastEnded}를 <b>부른다</b> — 그것은 자물쇠에 걸려
     * 스냅샷 뒤에 줄을 서므로, 재조회가 없어도 {@code ended}가 온다. 진짜로 재조회에만 기대는
     * 갈래는 <b>알림이 이미 지나간 뒤</b>다: 표는 {@code ended}인데 이 연결은 그때 명부에
     * 없었으니 아무도 알려 주지 않는다. 그러면 {@code Supplier} 안의 <b>방송 상태 재조회</b>가
     * 유일한 방어다.
     *
     * <p>🔴 이 시험이 지키는 것은 상태를 <b>자물쇠 안에서, 창이 지난 뒤에</b> 읽는다는 것이다.
     * 값으로 미리 읽어 넘기면(자물쇠 밖) 창에서 끝난 방송이 {@code LIVE}로 낡아 {@code ended=false}로
     * 열린다. 그래서 여기서는 사건을 <b>조회보다 앞</b>에 둔다({@link #조회_직전에}).
     *
     * <p>같은 트랜잭션에서 <b>먼저 엔티티를 올려 두면</b> 이 조회가 1차 캐시의 낡은 인스턴스를
     * 받는다는 위험도 있었는데, POK-174에서 <b>자격 판정이 트랜잭션 밖으로 나가</b> 그 경로가 닫혔다
     * (판정은 스칼라만 뽑고 자기 트랜잭션에서 끝난다). 판정을 트랜잭션 안으로 되돌리는 날
     * 그 위험이 되살아난다.
     */
    @Test
    void 창에서_방송이_끝나고_알림이_지나가도_재조회가_ended를_잡는다() {
        broadcasts.save(Broadcast.startedNow("s-win-stale", TestIds.STREAMER, 703L, Instant.now(), null));
        post2A("s-win-stale", "evt-1", 1_000_000L);

        조회_직전에("s-win-stale", () -> {
            // broadcastEnded를 부르지 않는다 — 알림이 이미 지나간 상황을 만드는 것이 요점이다.
            // readOnly 트랜잭션 안이라 UPDATE는 별도 스레드여야 한다(클래스 주석).
            Thread ender = new Thread(() -> jdbc.update(
                    "UPDATE broadcasts SET status = 'ended', ended_at = now() "
                            + "WHERE stream_id = 's-win-stale'"), "in-window-ender");
            ender.start();
            try {
                ender.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try (SseReader reader = open("s-win-stale", TestTokens.access("2003"))) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
            창이_열렸는가();
            assertThat(reader.awaitName("ended", Duration.ofSeconds(5)))
                    .as("재조회가 낡은 값을 보면 ended=false로 열려 이 연결은 토큰 만료까지 산다")
                    .isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(5))).isTrue();
        }
    }

    /**
     * <b>창이 실제로 열렸나.</b> 열기가 끝난 <b>직후</b>에 묻는다 — 감사 라운드 5 경미 ①.
     *
     * <p>전에는 이 단언이 각 갈래의 <b>맨 마지막</b>에 있었다. 그 자리에서도 「조용한 경우」
     * (창이 안 열렸는데 1차 단언은 통과)는 잡지만, <b>계획 검증 C1이 걱정한 바로 그 상황</b>
     * (스파이가 표적을 놓쳐 세 갈래가 다 빨강)에서 개발자가 보는 첫 메시지가 「창에 커밋된
     * 카드가 안 온다」·「ended를 못 받는다」였다. 그러면 하네스가 아니라 <b>기능</b>을 의심한다
     * (2026-08-26 재현: 스파이 인자를 빗나가게 하니 3건 전부 그 메시지였다).
     *
     * <p><b>상태 코드 단언보다는 뒤다.</b> 통로가 404·503이면 조회에 닿기도 전이라 창도 안
     * 열리는데, 그때 이 메시지가 먼저 나오면 <b>진짜 원인(응답 본문)이 가려진다</b>.
     * 앞 단언이 「연결은 섰다」를 보장한 자리가 이 질문의 자리다.
     */
    private void 창이_열렸는가() {
        assertThat(창이_열렸다).as("창이 한 번도 안 열렸다 — 스파이가 표적을 놓쳤다. 이 갈래는 아무것도 안 잰다").isTrue();
    }

    /**
     * 자물쇠 안의 조회가 <b>실제로 끝난 뒤</b> {@code inside}를 돌게 한다. 조회를 앞으로 당기면
     * 안 된다 — 그러면 창 안에서 생긴 변화를 그 조회가 주워 담아 <b>고치기 전에도 초록</b>이 된다.
     *
     * <p><b>첫 호출에서만</b> 돈다(클래스 주석). {@code JumpCardService.record}가 같은 조회를
     * 쓰므로, 창 안에서 카드를 저장하면 이 답이 자기를 다시 부른다.
     */
    private void 열린_창에서(String streamId, Runnable inside) {
        창을_건다(streamId, inside, false);
    }

    /**
     * <b>트랜잭션은 열렸는데 아직 안 읽은 사이</b>에 {@code inside}를 돌게 한다.
     * {@link #열린_창에서}와 정확히 조회 순서 하나가 다르다.
     *
     * <p><b>왜 반대 방향이 필요한가</b> — 「조회가 <u>신선한 값</u>을 보는가」는 사건이 조회
     * <b>앞</b>에 있어야 잰다. 뒤에 두면 조회는 옛 값을 보는 것이 당연해서 아무것도 안 잰다.
     * 실제로 그렇게 짰다가 {@code ended}가 안 와서 빨간불을 봤다(2026-08-26).
     */
    private void 조회_직전에(String streamId, Runnable before) {
        창을_건다(streamId, before, true);
    }

    private void 창을_건다(String streamId, Runnable 사건, boolean 조회보다_앞) {
        AtomicInteger 호출수 = new AtomicInteger();
        doAnswer(invocation -> {
            boolean 첫_호출 = 호출수.incrementAndGet() == 1;
            if (첫_호출) {
                창이_열렸다.set(true);
            }
            if (첫_호출 && 조회보다_앞) {
                사건.run();
            }
            // callRealMethod()가 아니라 Spies다 — 리포지터리 목에서는 그것이 안 된다(Spies 주석).
            Object real = Spies.real(invocation);
            if (첫_호출 && !조회보다_앞) {
                사건.run();
            }
            return real;
        }).when(broadcasts).findByStreamId(streamId);
    }

    private List<Long> 카드창시작(SseReader reader) {
        return reader.named().stream().filter(e -> "card".equals(e.name()))
                .map(e -> MAPPER.readTree(e.data()).get("window").get("startMs").asLong()).toList();
    }

    private HttpResponse<String> post2A(String streamId, String eventId, long start) {
        String body = """
                {"eventId":"%s","source":"auto","streamTimestampMs":%d,
                 "window":{"startMs":%d,"endMs":%d},"score":97}
                """.formatted(eventId, start + 23_000L, start, start + 42_000L);
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                    + "/internal/broadcasts/" + streamId + "/highlights"))
                            .header("X-Internal-Token", INTERNAL)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SseReader open(String streamId, String token) {
        return new SseReader("http://localhost:" + port + "/api/clip/broadcasts/" + streamId + "/events",
                Map.of("Authorization", "Bearer " + token));
    }

    private void 잠깐(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return;
            }
            잠깐(50);
        }
    }
}
