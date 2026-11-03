package com.pokeclip.clip.segment;

import com.pokeclip.clip.segment.AudioCaptureRule.Outcome;
import com.pokeclip.clip.segment.CutDecision.End;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컷 끝 소리 확보(계약-세그먼트인덱스 6-6-8 · 설계 r7 9.4). 순수 판정이라 DB 없이 잰다. 렌더 주문과 소리 주소 API 가 이 판정
 * 하나를 같이 쓴다.
 */
class AudioCaptureRuleTest {

    /** 후보 행의 파일명 시각. 「지금」 은 여기서 잰다. */
    private static final long NEXT_WALL = 1_000_000_000_000L;

    private static RoundRow last(Integer version) {
        return new RoundRow(10, NEXT_WALL - 4_000, null, 4_000, false, true, RoundRow.UPLOADED, version, "k10");
    }

    private static RoundRow next(boolean discontinuity, String state) {
        return new RoundRow(11, NEXT_WALL, null, 4_000, discontinuity, true, state, null, "k11");
    }

    private static Supplier<Optional<RoundRow>> some(RoundRow row) {
        return () -> Optional.of(row);
    }

    private static Instant at(Duration afterNextWall) {
        return Instant.ofEpochMilli(NEXT_WALL).plus(afterNextWall);
    }

    private static final Instant NOW = at(Duration.ofMinutes(1));

    /** 회차가 끝났다 · 안 끝났다. */
    private static final BooleanSupplier ENDED = () -> true;
    private static final BooleanSupplier OPEN = () -> false;

    // ── 거리 조건(판 2) ───────────────────────────────────────────

    /** 끝이 조각 끝에서 H(1000ms) 넘게 안쪽이면 확보 없음 — 다음 행이 있는지도 묻지 않는다(6-6-8 2항). */
    @Test
    void 판_2_에서_끝이_조각_끝에서_1초_넘게_안쪽이면_다음_행을_묻지_않고_진행한다() {
        AtomicInteger asked = new AtomicInteger();
        Supplier<Optional<RoundRow>> counting = () -> {
            asked.incrementAndGet();
            return Optional.of(next(false, RoundRow.UPLOADED));
        };

        Outcome outcome = AudioCaptureRule.decide(last(2), new End(10, 3_000_000L), counting, OPEN, NOW);

        assertThat(outcome).isInstanceOf(Outcome.Proceed.class);
        assertThat(asked).hasValue(0);
    }

    /** 경계 — 정확히 dur − H 는 거리 조건 거짓(엄격 부등식), 1ms 넘으면 참. */
    @Test
    void 판_2_거리_조건은_dur_빼기_H_보다_클_때만_참이다() {
        assertThat(AudioCaptureRule.decide(last(2), new End(10, 3_000_000L), some(next(false, RoundRow.UPLOADED)),
                OPEN, NOW)).isInstanceOf(Outcome.Proceed.class);
        assertThat(AudioCaptureRule.decide(last(2), new End(10, 3_001_000L), some(next(false, RoundRow.UPLOADED)),
                OPEN, NOW)).isInstanceOf(Outcome.Attach.class);
    }

    @Test
    void 끝이_조각_끝까지면_판_2_도_거리_조건이_참이다() {
        assertThat(AudioCaptureRule.decide(last(2), new End(10, null), some(next(false, RoundRow.UPLOADED)), OPEN,
                NOW)).isEqualTo(new Outcome.Attach(next(false, RoundRow.UPLOADED)));
    }

    /** 판 NULL 은 거리 조건을 보지 않는다 — 다음 행이 녹화 이어짐이면 늘 싣는다. */
    @Test
    void 판_NULL_은_끝이_조각_안쪽이어도_이어진_다음_행을_싣는다() {
        assertThat(AudioCaptureRule.decide(last(null), new End(10, 500_000L), some(next(false, RoundRow.UPLOADED)),
                OPEN, NOW)).isEqualTo(new Outcome.Attach(next(false, RoundRow.UPLOADED)));
    }

    /** 「끝남」(회차 완결은 장부 왕복이다)은 다음 행이 장부에 없을 때만 묻는다 — 거리 조건이 거짓이거나 후보가 있으면 보지 않는다. */
    @Test
    void 회차_끝남은_다음_행이_없을_때만_묻는다() {
        AtomicInteger asked = new AtomicInteger();
        BooleanSupplier ended = () -> {
            asked.incrementAndGet();
            return true;
        };

        AudioCaptureRule.decide(last(2), new End(10, 1_000_000L), some(next(false, RoundRow.UPLOADED)), ended, NOW);
        AudioCaptureRule.decide(last(null), new End(10, null), some(next(false, RoundRow.PENDING)), ended, NOW);
        assertThat(asked).as("거리 조건 거짓 · 후보 있음").hasValue(0);

        assertThat(AudioCaptureRule.decide(last(null), new End(10, null), Optional::empty, ended, NOW))
                .isInstanceOf(Outcome.Proceed.class);
        assertThat(asked).hasValue(1);
    }

    // ── 후보 행 ────────────────────────────────────────────────

    @Test
    void 다음_행이_새_녹화면_싣지_않고_진행한다() {
        assertThat(AudioCaptureRule.decide(last(null), new End(10, null), some(next(true, RoundRow.UPLOADED)), OPEN,
                NOW)).isInstanceOf(Outcome.Proceed.class);
    }

    @Test
    void 다음_행이_아직_안_올라갔으면_기다린다() {
        assertThat(AudioCaptureRule.decide(last(null), new End(10, null), some(next(false, RoundRow.PENDING)), OPEN,
                NOW)).isInstanceOf(Outcome.Wait.class);
    }

    /**
     * P13 — {@code failed} 는 종국이 아니라 15분까지 기다리고, 넘으면 확보 없이 진행한다(영구 409 를 없앰). 두 판 모두.
     */
    @Test
    void 다음_행이_failed_면_14분엔_기다리고_16분엔_없이_진행한다() {
        for (Integer version : new Integer[] {null, 2}) {
            RoundRow failed = next(false, RoundRow.FAILED);

            assertThat(AudioCaptureRule.decide(last(version), new End(10, null), some(failed), OPEN,
                    at(Duration.ofMinutes(14)))).as("판 " + version).isInstanceOf(Outcome.Wait.class);
            assertThat(AudioCaptureRule.decide(last(version), new End(10, null), some(failed), OPEN,
                    at(Duration.ofMinutes(16)))).as("판 " + version).isEqualTo(new Outcome.FailedSkip(failed, 16));
        }
    }

    @Test
    void 다음_행이_없으면_끝난_회차는_진행하고_안_끝났으면_기다린다() {
        assertThat(AudioCaptureRule.decide(last(null), new End(10, null), Optional::empty, ENDED, NOW))
                .isInstanceOf(Outcome.Proceed.class);
        assertThat(AudioCaptureRule.decide(last(null), new End(10, null), Optional::empty, OPEN, NOW))
                .isInstanceOf(Outcome.Wait.class);
    }
}
