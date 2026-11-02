package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyBroadcastGoneException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyEventOwnedByOtherException;
import com.pokeclip.clip.jumpcard.JumpCardService.RecordResult;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.jumpcard.stream.CardStreamRegistry;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 수동 카드 저장(POK-391, 계약4 4B). 규칙 셋: 같은 eventId는 같은 카드 · 같은 방송의 앞 수동 카드와
 * 누른 시각이 20초 안이면(양방향) 새로 안 만들고 그 카드 · 구간은 [T−40초, T+20초], 0 아래는 0.
 *
 * <p>시각 T는 부르는 쪽(태스크 3의 마킹 서비스)이 영상 위치 ms로 바꿔 넘긴다. 여기서는 그 값을 그대로 쓴다.
 */
class HotkeyCardRecordTest extends IntegrationTestSupport {

    private static final String 방송 = "s-hotkey";
    private static final String 다른_방송 = "s-hotkey-2";
    private static final String 남의_방송 = "s-hotkey-other";
    private static final String 나 = TestIds.STREAMER;
    private static final String 남 = "8";

    /** 동시 시험 반복 수. 한 번만 돌리면 직렬로 끝난 회차와 겹친 회차를 못 가른다. */
    private static final int ROUNDS = 50;

    private final JumpCardService service;
    private final JumpCardRepository cards;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    @MockitoSpyBean
    private CardStreamRegistry registry;

    HotkeyCardRecordTest(JumpCardService service, JumpCardRepository cards,
                         BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.service = service;
        this.cards = cards;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow(방송, 나, null, 1L, Instant.now(), null));
        broadcasts.save(Broadcast.startedNow(다른_방송, 나, null, 1L, Instant.now(), null));
        broadcasts.save(Broadcast.startedNow(남의_방송, 남, null, 1L, Instant.now(), null));
        clearInvocations(registry);
    }

    // ── eventId ─────────────────────────────────────────────────

    @Test
    void 처음이면_만들고_같은_eventId면_그_카드() {
        String eventId = eventId();
        RecordResult first = service.recordHotkey(방송, 나, eventId, 100_000L);
        RecordResult again = service.recordHotkey(방송, 나, eventId, 100_000L);

        assertThat(first.created()).isTrue();
        assertThat(first.card().source()).isEqualTo(JumpCardSource.HOTKEY);
        assertThat(again.created()).isFalse();
        assertThat(again.card().id()).isEqualTo(first.card().id());
        assertThat(cardCount()).isEqualTo(1);
    }

    /**
     * 같은 eventId가 시각을 바꿔 다시 와도 그 카드다. 20초 판정보다 eventId가 앞이라는 것을 잰다 :
     * 60초 떨어진 시각이라 20초 판정만으로는 새 카드가 된다.
     */
    @Test
    void 같은_eventId는_시각이_멀어도_그_카드() {
        String eventId = eventId();
        RecordResult first = service.recordHotkey(방송, 나, eventId, 100_000L);
        RecordResult again = service.recordHotkey(방송, 나, eventId, 160_000L);

        assertThat(again.created()).isFalse();
        assertThat(again.card().id()).isEqualTo(first.card().id());
        assertThat(again.card().streamTimestampMs()).as("앞 카드를 그대로 돌려준다").isEqualTo(100_000L);
    }

    /** 계약4: eventId는 전역 유일로 본다. 같은 회원의 다른 방송 카드여도 그 카드다. */
    @Test
    void 같은_eventId가_내_다른_방송_카드여도_그_카드() {
        String eventId = eventId();
        RecordResult first = service.recordHotkey(다른_방송, 나, eventId, 100_000L);
        RecordResult again = service.recordHotkey(방송, 나, eventId, 100_000L);

        assertThat(again.created()).isFalse();
        assertThat(again.card().id()).isEqualTo(first.card().id());
        assertThat(cardCount()).isEqualTo(1);
    }

    /** 계획 검증 E4. 남의 방송 카드를 eventId로 집으면 그 카드를 내주지 않는다(본문에 남의 카드가 실린다). */
    @Test
    void 같은_eventId가_남의_카드면_거절하고_새로_안_만든다() {
        String eventId = eventId();
        service.recordHotkey(남의_방송, 남, eventId, 100_000L);

        assertThatThrownBy(() -> service.recordHotkey(방송, 나, eventId, 100_000L))
                .isInstanceOf(HotkeyEventOwnedByOtherException.class);
        assertThat(cardCount()).isEqualTo(1);
    }

    // ── 20초 ────────────────────────────────────────────────────

    @Test
    void 앞_카드_20초_안이면_새로_안_만들고_앞_카드() {
        RecordResult first = service.recordHotkey(방송, 나, eventId(), 100_000L);
        RecordResult near = service.recordHotkey(방송, 나, eventId(), 119_999L);

        assertThat(near.created()).isFalse();
        assertThat(near.card().id()).isEqualTo(first.card().id());
        assertThat(cardCount()).isEqualTo(1);
    }

    @Test
    void 정확히_20초면_새_카드() {
        service.recordHotkey(방송, 나, eventId(), 100_000L);
        RecordResult far = service.recordHotkey(방송, 나, eventId(), 120_000L);

        assertThat(far.created()).isTrue();
        assertThat(cardCount()).isEqualTo(2);
    }

    /** 양방향이다. 늦게 온 재전송이 앞 시각이어도 같게 본다(PRD). */
    @Test
    void 늦게_온_앞_시각도_20초_안이면_무시() {
        RecordResult first = service.recordHotkey(방송, 나, eventId(), 100_000L);
        RecordResult earlier = service.recordHotkey(방송, 나, eventId(), 80_001L);

        assertThat(earlier.created()).isFalse();
        assertThat(earlier.card().id()).isEqualTo(first.card().id());
    }

    @Test
    void 앞쪽으로도_정확히_20초면_새_카드() {
        service.recordHotkey(방송, 나, eventId(), 100_000L);

        assertThat(service.recordHotkey(방송, 나, eventId(), 80_000L).created()).isTrue();
    }

    /** 20초 안에 둘이 있으면 가까운 쪽이다. 어느 것을 줘도 「무시」는 맞지만 응답이 흔들리면 화면이 흔들린다. */
    @Test
    void 이십초_안에_둘이면_가까운_카드() {
        service.recordHotkey(방송, 나, eventId(), 100_000L);
        RecordResult later = service.recordHotkey(방송, 나, eventId(), 125_000L);

        RecordResult between = service.recordHotkey(방송, 나, eventId(), 116_000L);

        assertThat(between.created()).isFalse();
        assertThat(between.card().id()).isEqualTo(later.card().id());
    }

    @Test
    void 자동_카드는_20초_판정에_안_든다() {
        service.record(방송, new HighlightRequest("evt-auto", "auto", 100_000L,
                new HighlightRequest.Window(80_000L, 120_000L), 90, null));

        RecordResult hotkey = service.recordHotkey(방송, 나, eventId(), 100_000L);

        assertThat(hotkey.created()).isTrue();
        assertThat(cardCount()).isEqualTo(2);
    }

    @Test
    void 다른_방송이면_20초여도_새_카드() {
        service.recordHotkey(다른_방송, 나, eventId(), 100_000L);

        assertThat(service.recordHotkey(방송, 나, eventId(), 100_000L).created()).isTrue();
    }

    // ── 구간 ────────────────────────────────────────────────────

    /**
     * 방송 시각 40초 안의 두 카드는 구간 시작이 둘 다 0으로 잘린다. 옛 유일 제약
     * {@code (방송, 출처, 구간 시작)}이면 둘째가 막혔다(V219이 그것을 자동 카드 전용으로 좁혔다).
     */
    @Test
    void 방송_앞_40초_안에서_떨어진_수동_카드_둘이_다_생긴다() {
        RecordResult a = service.recordHotkey(방송, 나, eventId(), 5_000L);
        RecordResult b = service.recordHotkey(방송, 나, eventId(), 30_000L);

        assertThat(a.created()).isTrue();
        assertThat(b.created()).isTrue();
        assertThat(a.card().window().startMs()).isZero();
        assertThat(b.card().window().startMs()).isZero();
    }

    @Test
    void 구간은_앞40_뒤20_0아래는_0() {
        JumpCardSnapshot early = service.recordHotkey(방송, 나, eventId(), 10_000L).card();
        JumpCardSnapshot later = service.recordHotkey(방송, 나, eventId(), 100_000L).card();

        assertThat(early.streamTimestampMs()).isEqualTo(10_000L);
        assertThat(early.window().startMs()).isZero();
        assertThat(early.window().endMs()).isEqualTo(30_000L);
        assertThat(later.streamTimestampMs()).isEqualTo(100_000L);
        assertThat(later.window().startMs()).isEqualTo(60_000L);
        assertThat(later.window().endMs()).isEqualTo(120_000L);
        assertThat(later.score()).isNull();
        assertThat(later.evidence()).isNull();
    }

    /** 방송 시작 시각 그 자리(0)도 카드가 된다. 음수는 부르는 쪽이 막을 값이라 여기서 거절한다. */
    @Test
    void 시각_0은_받고_음수는_거절한다() {
        assertThat(service.recordHotkey(방송, 나, eventId(), 0L).card().window().startMs()).isZero();
        assertThatThrownBy(() -> service.recordHotkey(방송, 나, eventId(), -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 계획 검증 E8. 고른 방송이 그 사이 지워졌으면(잠금 조회가 빈손) 404 갈래다. FK 위반 500이 아니다. */
    @Test
    void 방송이_없으면_전용_예외이고_카드는_안_생긴다() {
        assertThatThrownBy(() -> service.recordHotkey("s-없음", 나, eventId(), 1_000L))
                .isInstanceOf(HotkeyBroadcastGoneException.class);
        assertThat(cardCount()).isZero();
    }

    /** 감사 1회차 A1. 넘겨받은 방송이 이 회원의 것이 아니면 「없다」(404 갈래)이고 카드는 안 생긴다. */
    @Test
    void 남의_방송을_넘기면_없는_방송과_같은_예외이고_카드는_안_생긴다() {
        assertThatThrownBy(() -> service.recordHotkey(남의_방송, 나, eventId(), 100_000L))
                .isInstanceOf(HotkeyBroadcastGoneException.class);
        assertThat(cardCount()).isZero();
    }

    // ── 발행 ────────────────────────────────────────────────────

    @Test
    void 새_카드는_커밋_뒤_SSE로_나가고_무시된_것은_안_나간다() {
        String eventId = eventId();
        RecordResult first = service.recordHotkey(방송, 나, eventId, 100_000L);
        verify(registry, times(1)).publish(argThat(card -> card.id() == first.card().id()));

        clearInvocations(registry);
        service.recordHotkey(방송, 나, eventId, 100_000L);
        service.recordHotkey(방송, 나, eventId(), 110_000L);
        verify(registry, never()).publish(any());
    }

    // ── 동시성 ──────────────────────────────────────────────────

    /**
     * 계획 검증 E2. 두 호출 다 예외 없음 · created 참은 하나 · 같은 id. 출발선을 세우고 50회 돈다.
     * 겹쳐 돈다는 증거는 결함 주입이다(보고서 03): {@code insertHotkey}의 {@code ON CONFLICT}를 떼고
     * 방송 잠금도 일반 조회로 바꾸면 이 시험이 빨간불이다.
     */
    @Test
    void 같은_eventId_동시_둘이면_하나() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM jump_cards");
            String eventId = eventId();

            List<RecordResult> results = 동시에(
                    () -> service.recordHotkey(방송, 나, eventId, 100_000L),
                    () -> service.recordHotkey(방송, 나, eventId, 100_000L));

            assertThat(results).as("회차 %d", round).extracting(RecordResult::created)
                    .containsExactlyInAnyOrder(true, false);
            assertThat(results.get(0).card().id()).as("회차 %d", round).isEqualTo(results.get(1).card().id());
            assertThat(cardCount()).as("회차 %d", round).isEqualTo(1);
        }
    }

    /**
     * 다른 eventId 둘이 20초 안에 동시에 오면 하나다. 막는 것은 방송 행 잠금뿐이다: 유일 색인은
     * eventId가 달라 안 막는다. 잠금을 일반 조회로 바꾸는 결함 주입에서 카드가 둘이 된다(보고서 03).
     */
    @Test
    void 다른_eventId_20초_안_동시_둘이면_하나() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM jump_cards");

            List<RecordResult> results = 동시에(
                    () -> service.recordHotkey(방송, 나, eventId(), 100_000L),
                    () -> service.recordHotkey(방송, 나, eventId(), 105_000L));

            assertThat(results).as("회차 %d", round).extracting(RecordResult::created)
                    .containsExactlyInAnyOrder(true, false);
            assertThat(results.get(0).card().id()).as("회차 %d", round).isEqualTo(results.get(1).card().id());
            assertThat(cardCount()).as("회차 %d", round).isEqualTo(1);
        }
    }

    /**
     * 감사 1회차 A3. 같은 eventId가 <b>같은 회원의 다른 방송 둘</b>로 동시에 온다. 방송 잠금이 서로 달라 줄을 안 선다 :
     * 막는 것은 eventId 유일 색인과 {@code inserted == 0} 뒤 다시 읽기다. 그 분기를 지우면 진 쪽이 남의 줄을
     * {@code created=true}로 돌려준다(보고서 05의 주입).
     */
    @Test
    void 같은_eventId가_내_다른_방송_둘로_동시에_와도_하나() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM jump_cards");
            String eventId = eventId();

            List<RecordResult> results = 동시에(
                    () -> service.recordHotkey(방송, 나, eventId, 100_000L),
                    () -> service.recordHotkey(다른_방송, 나, eventId, 100_000L));

            assertThat(results).as("회차 %d", round).extracting(RecordResult::created)
                    .containsExactlyInAnyOrder(true, false);
            assertThat(results.get(0).card().id()).as("회차 %d", round).isEqualTo(results.get(1).card().id());
            assertThat(cardCount()).as("회차 %d", round).isEqualTo(1);
        }
    }

    /** 감사 1회차 A3. 같은 eventId가 내 방송과 남의 방송으로 동시에 오면 하나는 만들고 하나는 「남의 카드」로 거절한다. */
    @Test
    void 같은_eventId가_남의_방송과_동시에_오면_하나는_만들고_하나는_거절() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM jump_cards");
            String eventId = eventId();

            List<Object> outcomes = 동시에(
                    () -> 결과나_예외(() -> service.recordHotkey(방송, 나, eventId, 100_000L)),
                    () -> 결과나_예외(() -> service.recordHotkey(남의_방송, 남, eventId, 100_000L)));

            assertThat(outcomes).as("회차 %d %s", round, outcomes)
                    .filteredOn(o -> o instanceof RecordResult r && r.created()).hasSize(1);
            assertThat(outcomes).as("회차 %d %s", round, outcomes)
                    .filteredOn(o -> o instanceof HotkeyEventOwnedByOtherException).hasSize(1);
            assertThat(cardCount()).as("회차 %d", round).isEqualTo(1);
        }
    }

    // ── 2A 회귀 ─────────────────────────────────────────────────

    @Test
    void 자동_카드_중복은_예전처럼_같은_창이면_한_장() {
        RecordResult first = service.record(방송, new HighlightRequest("evt-a", "auto", 100_000L,
                new HighlightRequest.Window(80_000L, 120_000L), 90, null));
        RecordResult again = service.record(방송, new HighlightRequest("evt-b", "auto", 101_000L,
                new HighlightRequest.Window(80_000L, 121_000L), 91, null));

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.card().id()).isEqualTo(first.card().id());
        assertThat(cardCount()).isEqualTo(1);
    }

    /**
     * 계획 검증 E7. 자동 카드 재조회가 같은 창 시작의 수동 카드를 집지 않는다. 옛 재조회는 출처를 인자로
     * 받았다: 자동 전용이 된 뒤 출처 조건이 빠지면 여기서 수동 카드가 나온다.
     */
    @Test
    void 자동_카드_재조회는_같은_창_시작의_수동_카드를_안_집는다() {
        RecordResult hotkey = service.recordHotkey(방송, 나, eventId(), 10_000L);
        assertThat(hotkey.card().window().startMs()).isZero();

        RecordResult auto = service.record(방송, new HighlightRequest("evt-a", "auto", 10_000L,
                new HighlightRequest.Window(0L, 30_000L), 90, null));

        assertThat(auto.created()).isTrue();
        assertThat(auto.card().source()).isEqualTo(JumpCardSource.AUTO);
        assertThat(auto.card().id()).isNotEqualTo(hotkey.card().id());
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private static String eventId() {
        return UUID.randomUUID().toString();
    }

    private long cardCount() {
        return jdbc.queryForObject("SELECT count(*) FROM jump_cards", Long.class);
    }

    /** 런타임 예외를 결과로 받는다: 「하나는 거절」을 재려면 진 쪽의 예외가 값이어야 한다. */
    private static Object 결과나_예외(Callable<Object> task) throws Exception {
        try {
            return task.call();
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** 둘을 같은 출발선에 세운다. 예외는 그대로 던져 「두 호출 다 예외 없음」을 잰다. */
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
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new AssertionError("동시 호출 하나가 예외로 끝났다", e.getCause());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
