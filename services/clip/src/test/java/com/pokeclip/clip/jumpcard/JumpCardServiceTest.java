package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.ClaimedByOtherException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.JumpCardNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.NotClaimOwnerException;
import com.pokeclip.clip.jumpcard.JumpCardService.RecordResult;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 저장의 규칙을 잰다 — 처음이면 만들고, 같은 창이 다시 오면 기존 카드를 돌려준다.
 *
 * <p>중복 판정을 DB가 한다는 것이 이 설계의 핵심이다. 조회 후 삽입이면
 * {@code 같은_창이_동시에…}가 깨진다.
 */
class JumpCardServiceTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    // static 메서드(auto)가 쓰므로 인스턴스 필드로 두면 컴파일되지 않는다.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JumpCardService service;
    private final JumpCardRepository cards;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JumpCardServiceTest(JumpCardService service, JumpCardRepository cards,
                        BroadcastRepository broadcasts, JdbcTemplate jdbc,
                        TransactionTemplate transactions) {
        this.service = service;
        this.cards = cards;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** 카드가 broadcasts의 자식이라 카드를 먼저 지운다. 안 지우면 다음 정리가 FK로 죽는다. */
    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-1", TestIds.STREAMER, 1L, Instant.now(), null));
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    private static HighlightRequest auto(String eventId, long start) {
        return new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"));
    }

    @Test
    void 처음_보면_만들고_created가_참이다() {
        RecordResult result = service.record("s-1", auto("evt-1", 5_020_000L));

        assertThat(result.created()).isTrue();
        assertThat(result.card().eventSeq()).isPositive();
        assertThat(result.card().source()).isEqualTo(JumpCardSource.AUTO);
        assertThat(result.card().streamTimestampMs()).isEqualTo(5_043_000L);
    }

    @Test
    void 같은_창이_다시_오면_기존_카드를_돌려주고_행은_하나다() {
        RecordResult first = service.record("s-1", auto("evt-1", 5_020_000L));
        RecordResult again = service.record("s-1", auto("evt-재전송", 5_020_000L));

        assertThat(again.created()).isFalse();
        assertThat(again.card().id()).isEqualTo(first.card().id());
        assertThat(cards.findAll()).as("정확히 한 줄이어야 한다").hasSize(1);
    }

    /**
     * 조회 후 삽입 방식이면 둘 다 "아직 없다"로 통과해 여기서 깨진다.
     *
     * <p><b>이 단언은 두 스레드가 직렬로 돌아도 통과한다</b>(POK-82와 같은 한계).
     * 겹쳤다는 증거는 결함 주입뿐이고 그 기록은 progress에 있다.
     */
    @Test
    void 같은_창이_동시에_와도_하나만_만들어지고_둘_다_정상으로_끝난다() throws Exception {
        List<RecordResult> results = 동시에(
                () -> service.record("s-1", auto("a", 5_020_000L)),
                () -> service.record("s-1", auto("b", 5_020_000L)));

        assertThat(results).extracting(RecordResult::created).containsExactlyInAnyOrder(true, false);
        assertThat(cards.findAll()).hasSize(1);
    }

    /** FK 위반이 아니라 404가 나가야 판별기가 재시도 상한을 셀 수 있다. */
    @Test
    void 없는_방송이면_BroadcastNotFound다() {
        assertThatThrownBy(() -> service.record("s-없음", auto("e", 1_000L)))
                .isInstanceOf(BroadcastNotFoundException.class);
        assertThat(cards.findAll()).isEmpty();
    }

    /** 종료 이벤트가 먼저 도착해도 판별기의 마지막 카드가 버려지면 안 된다. */
    @Test
    void 끝난_방송에도_들어간다() {
        broadcasts.save(Broadcast.endedPlaceholder("s-ended", TestIds.STREAMER, 9L, Instant.now()));

        assertThat(service.record("s-ended", auto("e", 1_000L)).created()).isTrue();
    }

    @Test
    void evidence와_score가_없어도_들어간다() {
        RecordResult result = service.record("s-1", new HighlightRequest("e", "auto", 1_500L,
                new HighlightRequest.Window(1_000L, 2_000L), null, null));

        assertThat(result.created()).isTrue();
        assertThat(result.card().score()).isNull();
        assertThat(result.card().evidence()).isNull();
    }

    @Test
    void snapshotsOf는_순번_순이고_숨긴_것도_포함한다() {
        long a = service.record("s-1", auto("a", 1_000L)).card().id();
        long b = service.record("s-1", auto("b", 9_000L)).card().id();
        // a가 뒤에 바뀌었으니 순번이 b보다 크다 — 정렬 기준이 id가 아니라 event_seq임을 잰다.
        jdbc.update("UPDATE jump_cards SET hidden_at = now(), hidden_by = '1204' WHERE id = ?", a);

        List<JumpCardSnapshot> snapshots = service.snapshotsOf("s-1");

        assertThat(snapshots).extracting(JumpCardSnapshot::id).containsExactly(b, a);
        assertThat(snapshots.get(1).hidden()).isTrue();
        assertThat(snapshots.get(1).hiddenBy()).isEqualTo("1204");
    }

    // ── 점유·숨김 (태스크 5) ────────────────────────────────────────────────

    /**
     * 편집자 둘이 같은 카드를 동시에 집는다. 집을 때 판정하는 UPDATE 하나가 원자적이라
     * 둘 중 하나만 영향 행 1을 받는다.
     *
     * <p><b>이 단언도 직렬 실행에서 통과한다.</b> 겹쳤다는 증거는 결함 주입뿐이다.
     */
    @Test
    void 빈_카드를_둘이_동시에_집으면_한_명만_성공한다() throws Exception {
        long id = 카드();

        List<Object> results = 동시에(() -> 결과로(() -> service.claim(id, "1201")),
                () -> 결과로(() -> service.claim(id, "1202")));

        assertThat(results).filteredOn(r -> r instanceof JumpCardSnapshot).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof ClaimedByOtherException).hasSize(1);
    }

    /** 본인 재호출은 연장이다. 이 갈래가 없으면 자기가 잡은 카드도 못 잡는다. */
    @Test
    void 본인이_다시_집으면_성공이고_만료가_연장된다() throws Exception {
        long id = 카드();
        JumpCardSnapshot first = service.claim(id, "1201");
        Thread.sleep(20);

        JumpCardSnapshot again = service.claim(id, "1201");

        assertThat(again.claimedAt()).isAfter(first.claimedAt());
        assertThat(again.claimExpiresAt()).isAfter(first.claimExpiresAt());
    }

    @Test
    void 남의_것은_만료_전엔_거절되고_거절에_현재_카드가_실린다() {
        long id = 카드();
        service.claim(id, "1201");

        assertThatThrownBy(() -> service.claim(id, "1202"))
                .isInstanceOf(ClaimedByOtherException.class)
                .satisfies(e -> assertThat(((ClaimedByOtherException) e).current().claimedBy())
                        .as("409에 「누가 잡고 있나」가 실려야 웹이 이름을 띄운다").isEqualTo("1201"));
    }

    /** 만료를 치우는 배경 작업이 없다. 시각 비교는 DB 시계로 한다. */
    @Test
    void 남의_것도_만료_후엔_집는다() {
        long id = 카드();
        service.claim(id, "1201");
        jdbc.update("UPDATE jump_cards SET claimed_at = now() - interval '31 minutes' WHERE id = ?", id);

        assertThat(service.claim(id, "1202").claimedBy()).isEqualTo("1202");
    }

    /**
     * 「이미 놓인 상태」가 목표이므로 멱등이 맞다. 403을 주면 웹이 새로고침 뒤 놓기를
     * 눌렀을 때 오류를 보게 된다 — 계획 검증에서 정의가 빠져 있던 자리다(2026-08-23).
     */
    @Test
    void 아무도_안_잡은_카드를_놓으면_그냥_성공한다() {
        long id = 카드();

        assertThatNoException().isThrownBy(() -> service.release(id, "1201"));
        assertThat(cards.findById(id).orElseThrow().getClaimedBy()).isNull();
    }

    @Test
    void 놓기는_본인만_된다() {
        long id = 카드();
        service.claim(id, "1201");

        assertThatThrownBy(() -> service.release(id, "1202")).isInstanceOf(NotClaimOwnerException.class);

        service.release(id, "1201");
        assertThat(cards.findById(id).orElseThrow().getClaimedBy()).isNull();
    }

    @Test
    void 없는_카드는_JumpCardNotFound다() {
        assertThatThrownBy(() -> service.claim(999L, "1203")).isInstanceOf(JumpCardNotFoundException.class);
    }

    @Test
    void 숨기고_되돌린다_누가_숨겼는지_남는다() {
        long id = 카드();

        JumpCardSnapshot hidden = service.hide(id, "1201");
        assertThat(hidden.hidden()).isTrue();
        assertThat(hidden.hiddenBy()).isEqualTo("1201");

        JumpCardSnapshot back = service.unhide(id, "1202");
        assertThat(back.hidden()).isFalse();
        assertThat(back.hiddenBy()).isNull();
        assertThat(back.eventSeq()).as("바뀌었으니 순번이 올라야 한다").isGreaterThan(hidden.eventSeq());
    }

    /**
     * <b>겹침을 강제해서 잰다.</b> 자유 경합 시험(위)의 단언은 두 스레드가 직렬로 돌아도 통과한다 —
     * 그것이 POK-82가 남긴 한계다. 여기서는 승자의 트랜잭션을 <b>열어 둔 채</b> 패자를 보내
     * 겹침이 스케줄러 운이 아니라 <b>PostgreSQL 행 락</b>으로 강제되게 한다. 그래서 간헐 실패가 없다.
     *
     * <p>재는 것 셋 — B가 실제로 <b>막혔는가</b>(시간) · 막힌 뒤 받은 것이 「A가 잡고 있다」인가 ·
     * 최종 주인이 A인가. 비동기 감사 1차가 만든 장치다(`wait_event=transactionid` 직접 관측).
     */
    @Test
    void 승자의_트랜잭션이_열려_있으면_패자는_행_락에_막혔다가_거절된다() throws Exception {
        long id = 카드();
        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = pool.submit(() -> transactions.executeWithoutResult(status -> {
                service.claim(id, "1201");
                aClaimed.countDown();
                try {
                    // 커밋을 700ms 붙잡는다 — 그 사이 B의 UPDATE가 행 락에서 멈춘다.
                    aMayCommit.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(aClaimed.await(5, TimeUnit.SECONDS)).as("A가 잡기도 전이면 뒤가 무의미하다").isTrue();

            Future<Object> loser = pool.submit(() -> {
                long startedAt = System.nanoTime();
                try {
                    return new Object[]{service.claim(id, "1202"), Duration.ofNanos(System.nanoTime() - startedAt)};
                } catch (RuntimeException e) {
                    return new Object[]{e, Duration.ofNanos(System.nanoTime() - startedAt)};
                }
            });

            Thread.sleep(700);
            aMayCommit.countDown();
            winner.get(10, TimeUnit.SECONDS);

            Object[] result = (Object[]) loser.get(10, TimeUnit.SECONDS);
            Duration blockedFor = (Duration) result[1];

            assertThat(blockedFor).as("안 막혔다면 두 트랜잭션이 안 겹친 것이다").isGreaterThan(Duration.ofMillis(400));
            assertThat(result[0]).isInstanceOf(ClaimedByOtherException.class);
            assertThat(((ClaimedByOtherException) result[0]).current().claimedBy()).isEqualTo("1201");
        } finally {
            aMayCommit.countDown();
            pool.shutdownNow();
        }

        assertThat(cards.findById(id).orElseThrow().getClaimedBy())
                .as("먼저 잡은 쪽이 주인으로 남아야 한다").isEqualTo("1201");
    }

    /**
     * <b>이미 숨긴 카드를 남이 다시 숨겨도 「누가 숨겼나」가 안 바뀐다.</b>
     *
     * <p>조건 없이 덮어쓰면 추적 대상이 <b>마지막에 누른 사람</b>으로 바뀐다 — 감사가 얕은 인가를
     * 감수한 근거가 「{@code hidden_by}로 추적된다」였으므로 그 근거가 무너진다(로컬 리뷰 사소 ④).
     *
     * <p>그렇다고 404를 주지 않는다. 웹이 새로고침 뒤 숨기기를 눌렀을 때 오류를 보게 된다 —
     * {@code release}를 멱등으로 둔 것과 같은 이유로 <b>200 + 현재 카드</b>다.
     */
    @Test
    void 이미_숨긴_카드를_다시_숨겨도_숨긴_사람이_안_바뀐다() {
        long id = 카드();
        JumpCardSnapshot first = service.hide(id, "1201");

        JumpCardSnapshot again = service.hide(id, "1202");

        assertThat(again.hidden()).as("이미 숨김이어도 성공이다(멱등)").isTrue();
        assertThat(again.hiddenBy()).as("추적 대상이 마지막에 누른 사람으로 바뀌었다").isEqualTo("1201");
        assertThat(again.eventSeq()).as("아무것도 안 바뀌었으니 순번도 그대로다").isEqualTo(first.eventSeq());
    }

    /** 안 숨겨진 카드를 되돌리는 것도 성공이고, 바뀐 게 없으니 순번도 안 오른다. */
    @Test
    void 안_숨겨진_카드를_되돌려도_성공이고_순번이_안_오른다() {
        long id = 카드();
        long before = service.snapshotsOf("s-1").get(0).eventSeq();

        JumpCardSnapshot result = service.unhide(id, "1201");

        assertThat(result.hidden()).isFalse();
        assertThat(result.eventSeq()).isEqualTo(before);
    }

    private long 카드() {
        return service.record("s-1", auto("evt-1", 5_020_000L)).card().id();
    }

    /** 예외도 결과로 모은다 — 던져 버리면 "누가 이겼나"를 못 본다. */
    private Object 결과로(Callable<?> task) {
        try {
            return task.call();
        } catch (Exception e) {
            return e;
        }
    }

    /**
     * 둘을 같은 출발선에 세워 동시에 보낸다. 예외도 결과로 모은다 — 던져 버리면
     * "누가 이겼나"를 못 보고 시험이 원인 대신 증상만 남긴다.
     */
    @SafeVarargs
    private <T> List<T> 동시에(Callable<T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<T> results = Collections.synchronizedList(new ArrayList<>());
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
