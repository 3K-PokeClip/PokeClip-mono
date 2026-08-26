package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * <b>연결을 여는 요청이 자물쇠를 쥔 채 DB 커넥션을 기다리면 안 된다.</b>
 *
 * <p>봇 지적 ②를 고치면서 스냅샷 조회가 {@code openWithSnapshot}의 자물쇠 <b>안</b>으로 들어왔다.
 * 조회가 커넥션을 기다리면 자물쇠를 쥔 채 기다리고, 그동안 {@code publish}·{@code open}·
 * {@code broadcastEnded}가 전부 막힌다(2026-08-23 실측: 풀 2·시한 3초에서 {@code publish}
 * <b>3142ms</b> · {@code open} <b>3116ms</b>).
 *
 * <p><b>거기서 되먹임이 생긴다</b> — {@code afterCommit}은 커넥션 반납 <b>전</b>이라
 * ({@code activeConnections=1}·{@code resourceBound=true} 실측) 막힌 {@code publish}가 커넥션을
 * <b>쥔 채</b> 기다린다. 커넥션이 안 돌아오니 문 쪽은 계속 굶는다 — 외부 점유자 없이
 * {@code publish} 둘만으로 풀이 마른 채 {@code connection-timeout}(운영 기본 <b>30초</b>)까지
 * 유지됐다. 이 코드가 <b>고갈을 스스로 만든다</b>는 뜻이다.
 *
 * <p>처방은 <b>조회를 락 밖으로</b>가 아니라 <b>기다림을 락 밖으로</b>다(조회 대기 자체는 락
 * 안이든 밖이든 같았다 — 510ms 대 509ms). 컨트롤러가 {@code @Transactional(readOnly = true)}로
 * 트랜잭션을 먼저 열면 커넥션 획득이 자물쇠 <b>앞</b>에서 끝나고, 자물쇠 안의 조회는 그
 * 커넥션을 <b>재사용</b>한다.
 *
 * <p><b>그물을 두 겹으로 둔다.</b> 하나는 구조({@code snapshotsOf}가 불릴 때 트랜잭션이 이미
 * 열려 있는가), 하나는 실제 막힘(ms). 구조 쪽이 결정적이고, 막힘 쪽이 <b>대가의 방향이 뒤집힌
 * 뒤의 값</b>을 계속 잰다 — 이제는 커넥션을 쥔 채 자물쇠를 기다리므로 그 시간이 짧아야 한다.
 *
 * <p><b>진짜 HTTP로 연다.</b> {@code registry}를 직접 부르면 컨트롤러의 트랜잭션을 안 타서
 * 처방이 있으나 없으나 같은 결과가 나온다 — 아무것도 안 재는 시험이 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // 풀을 2로 조여 「커넥션이 귀하다」를 만든다.
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=2",
        // 점유자가 계속 놨다 잡았다 하므로 요청은 결국 얻는다. 시한은 그 여유값이다.
        "spring.datasource.hikari.connection-timeout=10000",
        "pokeclip.jump-card.stream.heartbeat=PT1H"
})
class OpenDoesNotBlockPublishTest extends IntegrationTestSupport {

    /** 스핀 점유자가 커넥션을 쥐고 있는 시간. 결함이 있으면 락 안 조회가 최대 이만큼 기다린다. */
    private static final long 스핀_홀드_MS = 250;

    /** 재는 시간. 이 동안 계속 발행하며 최악을 찾는다. */
    private static final Duration 측정_구간 = Duration.ofSeconds(3);

    /**
     * 결함 상태 실측 <b>743ms·2022ms</b>(2회. 스핀 홀드보다 큰 것은 요청이 커넥션을 여러 번
     * 기다리기 때문이다), 고친 상태 <b>0~1ms</b>(전수 5회 + 단독 1회). 120ms는 그 사이를
     * 넉넉히 가르는 자리다 — 느린 CI에서 자물쇠 보유가 몇십 ms로 늘어도 초록이어야 한다.
     */
    private static final Duration 기준 = Duration.ofMillis(120);

    @MockitoSpyBean
    private JumpCardService service;

    private final int port;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    OpenDoesNotBlockPublishTest(@LocalServerPort int port, BroadcastRepository broadcasts,
                                CardStreamRegistry registry, JdbcTemplate jdbc, DataSource dataSource) {
        this.port = port;
        this.broadcasts = broadcasts;
        this.registry = registry;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
    }

    /**
     * 구조 그물. {@code snapshotsOf}가 자물쇠 안에서 불릴 때, 그것이 도는 트랜잭션은
     * <b>컨트롤러가 자물쇠 밖에서 이미 연 것</b>이어야 한다. 자기 트랜잭션을 새로 연다면
     * 커넥션 획득도 자물쇠 안에서 한다는 뜻이다.
     *
     * <p><b>「트랜잭션이 열려 있는가」로는 못 잰다</b> — {@code @MockitoSpyBean}은 트랜잭션 프록시
     * <b>안쪽</b>에서 돌아 {@code isActualTransactionActive()}가 어느 쪽이든 참이다(처음 그렇게
     * 짰다가 결함 상태에서 초록을 봤다). 대신 <b>트랜잭션 이름</b>을 본다 — 이름은 트랜잭션을
     * 처음 시작한 메서드로 정해지고, 참여(REQUIRED)는 이름을 바꾸지 않는다. 컨트롤러가 열었으면
     * {@code JumpCardStreamController.open}, 아니면 {@code JumpCardService.snapshotsOf}다.
     */
    @Test
    void 스냅샷은_컨트롤러가_이미_연_트랜잭션에서_읽힌다() {
        broadcasts.save(Broadcast.startedNow("s-tx", TestIds.STREAMER, 903L, Instant.now(), null));

        AtomicReference<String> 트랜잭션이름 = new AtomicReference<>();
        doAnswer(invocation -> {
            트랜잭션이름.set(TransactionSynchronizationManager.getCurrentTransactionName());
            return invocation.callRealMethod();
        }).when(service).snapshotsOf(anyString());

        try (SseReader reader = open("s-tx", TestTokens.access("2201"))) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
        }

        assertThat(트랜잭션이름.get())
                .as("자물쇠 안의 조회가 트랜잭션을 새로 연다 = 커넥션 획득이 자물쇠 안이다. "
                        + "풀이 비면 자물쇠를 쥔 채 connection-timeout(운영 30초)만큼 기다리고, "
                        + "그동안 막히는 publish는 afterCommit이라 커넥션을 쥐고 있어 되먹임이 된다")
                .isEqualTo("com.pokeclip.clip.jumpcard.stream.JumpCardStreamController.open");
    }

    /**
     * 막힘 그물. 풀이 계속 바쁜 상태에서 연결을 열며, 그동안 발행이 얼마나 막히는지 최악을 잰다.
     *
     * <p><b>점유를 스핀으로 한다</b> — 한 번 잡고 오래 쥐면 컨트롤러의 <b>첫 조회</b>
     * ({@code findByStreamId}, 자물쇠 밖이다)가 그 대기를 통째로 흡수해 자물쇠 안 조회는
     * 기다릴 것이 없어진다. 그러면 결함이 있어도 초록이다(처음 이 시험을 그렇게 짜서
     * <b>0ms</b>가 나왔다). 놨다 잡았다를 반복해야 「첫 조회는 통과했는데 두 번째는 못 얻는」
     * 자리가 생긴다.
     *
     * <p>한 번만 재지 않고 <b>구간 내내 반복해서 최악</b>을 본다. 자물쇠가 잡히는 시점을 밖에서
     * 맞출 수 없으므로, 한 번만 재면 그 순간이 자물쇠 밖이어서 통과할 수 있다.
     */
    @Test
    void 풀이_바쁜_동안_연결을_열어도_카드_발행이_막히지_않는다() throws Exception {
        broadcasts.save(Broadcast.startedNow("s-blk", TestIds.STREAMER, 901L, Instant.now(), null));
        카드를_심는다("s-blk");
        // 재는 쪽이 DB를 쓰면 무엇 때문에 막혔는지 갈리지 않는다. 미리 읽어 둔다.
        JumpCardSnapshot 카드 = registry == null ? null : service.snapshotsOf("s-blk").get(0);

        CountDownLatch 점유시작 = new CountDownLatch(2);
        AtomicBoolean 그만 = new AtomicBoolean();
        상시로_점유한다(점유시작, 그만);
        스핀으로_점유한다(점유시작, 그만);
        assertThat(점유시작.await(10, TimeUnit.SECONDS)).as("풀이 바빠야 이 시험이 무언가를 잰다").isTrue();

        AtomicReference<SseReader> 연결 = new AtomicReference<>();
        Thread 여는쪽 = new Thread(() -> 연결.set(open("s-blk", TestTokens.access("2202"))), "여는쪽");
        여는쪽.start();

        Duration 최악 = Duration.ZERO;
        long 마감 = System.nanoTime() + 측정_구간.toNanos();
        int 횟수 = 0;
        while (System.nanoTime() < 마감) {
            Instant t0 = Instant.now();
            registry.publish(카드);
            Duration 막힘 = Duration.between(t0, Instant.now());
            최악 = 막힘.compareTo(최악) > 0 ? 막힘 : 최악;
            횟수++;
            Thread.sleep(25);
        }

        그만.set(true);
        여는쪽.join(30_000);
        try (SseReader reader = 연결.get()) {
            assertThat(reader).as("연결이 결국 열려야 한다 — 안 열리면 다른 것을 잰 것이다").isNotNull();
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
        }

        System.out.printf("[문이 발행을 막는가] 발행 %d회 중 최악 막힘 %dms (풀 %d · 스핀 홀드 %dms)%n",
                횟수, 최악.toMillis(), ((HikariDataSource) dataSource).getMaximumPoolSize(), 스핀_홀드_MS);

        assertThat(최악)
                .as("연결을 여는 요청이 커넥션을 기다리는 동안 발행이 막혔다. 자물쇠를 쥔 채 "
                        + "기다린다는 뜻이고, 막힌 발행은 afterCommit이라 커넥션을 쥐고 있어 되먹임이 된다")
                .isLessThan(기준);
    }

    /** 커넥션 하나를 시험 내내 쥔다. 남은 하나를 두고 경합이 일어나게 만드는 역할이다. */
    private void 상시로_점유한다(CountDownLatch 점유시작, AtomicBoolean 그만) {
        데몬(() -> {
            try (Connection ignored = dataSource.getConnection()) {
                점유시작.countDown();
                while (!그만.get()) {
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "커넥션-상시점유자");
    }

    /** 잡고 놓기를 반복한다. 「첫 조회는 얻었는데 두 번째는 못 얻는」 자리를 만드는 것이 목적이다. */
    private void 스핀으로_점유한다(CountDownLatch 점유시작, AtomicBoolean 그만) {
        데몬(() -> {
            boolean 처음 = true;
            while (!그만.get()) {
                try (Connection ignored = dataSource.getConnection()) {
                    if (처음) {
                        점유시작.countDown();
                        처음 = false;
                    }
                    Thread.sleep(스핀_홀드_MS);
                } catch (Exception e) {
                    return;
                }
            }
        }, "커넥션-스핀점유자");
    }

    private void 데몬(Runnable body, String 이름) {
        Thread t = new Thread(body, 이름);
        t.setDaemon(true);
        t.start();
    }

    private SseReader open(String streamId, String token) {
        return new SseReader("http://localhost:" + port + "/api/clip/broadcasts/" + streamId + "/events",
                Map.of("Authorization", "Bearer " + token));
    }

    private void 카드를_심는다(String streamId) {
        jdbc.update("INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, "
                        + "window_start_ms, window_end_ms, score, evidence, event_seq) "
                        + "VALUES (?, 'auto', 'evt-blk', 23000, 0, 42000, 97, CAST(? AS jsonb), 0)",
                streamId, "{\"multiplier\":4.2,\"messageCount\":183}");
    }
}
