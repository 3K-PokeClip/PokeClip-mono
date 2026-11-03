package com.pokeclip.ledger.timeline;

import com.pokeclip.ledger.timeline.Place.AfterLast;
import com.pokeclip.ledger.timeline.Place.BeforeFirst;
import com.pokeclip.ledger.timeline.Place.Gap;
import com.pokeclip.ledger.timeline.Place.Media;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 순수 함수라 Spring도 DB도 없이 잰다.
 *
 * <p>규칙 정본은 계약 세그먼트인덱스 6-6-6(F 1–5항 · 반개 구간의 끝)과 경계 표 B8(부분 겹침은 나중 조각)이다.
 * 같은 규칙을 세 곳(services · 렌더 · 웹)이 읽는 사례는 공유 시험 표에 있고 {@link LedgerTimelineCasesTest}가
 * 돌린다. 여기는 r6 T-c1 반례와 입력 검사처럼 표 밖의 것만 둔다.
 */
class LedgerTimelineTest {

    /** 장부 이웃으로 이어진 행(is_discontinuity 거짓 · 장부 바로 앞 행이 매체). */
    private static LedgerRow joined(long seq, long startMs, int durationMs) {
        return new LedgerRow(seq, startMs, durationMs, true);
    }

    /** 끊긴 행(재접속 · 비귀속 행을 사이에 둠 · 스트림 첫 행). */
    private static LedgerRow cut(long seq, long startMs, int durationMs) {
        return new LedgerRow(seq, startMs, durationMs, false);
    }

    /** r6 T-c1 cx R11-2 반례 — 이어진 이웃 사이 71ms 장부 틈 안은 뒤 조각 시작이다(F 3항). */
    @Test
    void 이어진_틈_안의_시각은_뒤_조각_시작이다() {
        List<LedgerRow> rows = List.of(cut(1, 0, 4000), joined(2, 4071, 4000));

        assertThat(LedgerTimeline.locate(rows, 4050)).isEqualTo(new Media(2, 0));
    }

    /** r6 T-c1 cx-9 — 같은 자리를 끝으로 읽으면 「out 직전」 이라 앞 조각 끝이다. */
    @Test
    void 이어진_틈_안의_끝은_앞_조각_끝이다() {
        List<LedgerRow> rows = List.of(cut(1, 0, 4000), joined(2, 4071, 4000));

        assertThat(LedgerTimeline.locateEnd(rows, 4050)).isEqualTo(new Media(1, 4000));
    }

    @Test
    void 덮는_행_안이면_그_행_자리다() {
        List<LedgerRow> rows = List.of(cut(1, 0, 4000), joined(2, 4000, 4000));

        assertThat(LedgerTimeline.locate(rows, 5500)).isEqualTo(new Media(2, 1500));
    }

    /** 장부 1ms 겹침(F 1항) · 1초 미만 부분 겹침(B8) — 겹친 시각은 seq 큰 나중 조각이다. */
    @Test
    void 겹친_시각은_나중_조각이다() {
        List<LedgerRow> rows = List.of(cut(1, 0, 4000), joined(2, 3600, 4000));

        assertThat(LedgerTimeline.locate(rows, 3800)).isEqualTo(new Media(2, 200));
    }

    @Test
    void 끊긴_틈_안이면_공백이다() {
        List<LedgerRow> rows = List.of(cut(1, 0, 4000), cut(2, 6000, 4000));

        assertThat(LedgerTimeline.locate(rows, 4500)).isEqualTo(new Gap(1, 2, 500, 2000));
    }

    /** 끝 out 이 끊긴 이웃 시작과 같으면 공백 끝까지 들어간 자리다(out 직전은 공백 안). */
    @Test
    void 끊긴_이웃_시작과_같은_끝은_공백이다() {
        List<LedgerRow> rows = List.of(cut(1, 0, 4000), cut(2, 6000, 4000));

        assertThat(LedgerTimeline.locateEnd(rows, 6000)).isEqualTo(new Gap(1, 2, 2000, 2000));
    }

    @Test
    void 첫_행_앞이면_BeforeFirst다() {
        List<LedgerRow> rows = List.of(cut(1, 1000, 4000));

        assertThat(LedgerTimeline.locate(rows, 999)).isEqualTo(new BeforeFirst());
        assertThat(LedgerTimeline.locateEnd(rows, 1000)).isEqualTo(new BeforeFirst());
    }

    /** 「밖」 과 「아직」 은 가르지 않는다 — 부르는 쪽이 회차 완결 술어로 가른다(r6 3.2). */
    @Test
    void 마지막_행_뒤면_AfterLast다() {
        List<LedgerRow> rows = List.of(cut(1, 0, 4000));

        assertThat(LedgerTimeline.locate(rows, 4000)).isEqualTo(new AfterLast());
        assertThat(LedgerTimeline.locateEnd(rows, 4001)).isEqualTo(new AfterLast());
        assertThat(LedgerTimeline.locateEnd(rows, 4000)).isEqualTo(new Media(1, 4000));
    }

    @Test
    void 행이_없으면_BeforeFirst다() {
        assertThat(LedgerTimeline.locate(List.of(), 0)).isEqualTo(new BeforeFirst());
        assertThat(LedgerTimeline.locateEnd(List.of(), 0)).isEqualTo(new BeforeFirst());
    }

    /** seq 순이 아니면 「seq 최대」 를 목록 순서로 고를 수 없다 — 조용히 틀린 행을 고르지 않게 막는다. */
    @Test
    void seq가_오름차순이_아니면_거절한다() {
        List<LedgerRow> rows = List.of(cut(2, 0, 4000), cut(1, 4000, 4000));

        assertThatThrownBy(() -> LedgerTimeline.locate(rows, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seq");
    }

    /** 같은 seq 두 행도 「seq 최대」 가 하나로 서지 않는다 — 경계값(같음)도 거절한다. */
    @Test
    void 같은_seq가_두_번_나오면_거절한다() {
        List<LedgerRow> rows = List.of(cut(1, 0, 4000), cut(1, 4000, 4000));

        assertThatThrownBy(() -> LedgerTimeline.locate(rows, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seq");
    }

    @Test
    void 길이가_음수인_행은_거절한다() {
        List<LedgerRow> rows = List.of(cut(1, 0, -1));

        assertThatThrownBy(() -> LedgerTimeline.locateEnd(rows, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMs");
    }

    /** 설계 9.2 8 · 9.3 — (out − in) − Σ 이어 붙여 빠진 틈의 컷 안 몫. */
    @Test
    void 예상_길이는_범위에서_이어_붙인_틈_몫을_뺀다() {
        assertThat(LedgerTimeline.lengthMs(1000, 61000, List.of(2000L, 500L))).isEqualTo(57500);
        assertThat(LedgerTimeline.lengthMs(1000, 61000, List.of())).isEqualTo(60000);
        // 경계값 — 틈 몫 합이 범위와 같으면 길이 0 이고 거절하지 않는다.
        assertThat(LedgerTimeline.lengthMs(0, 1000, List.of(1000L))).isEqualTo(0);
    }

    @Test
    void 끝이_시작보다_앞이면_길이를_거절한다() {
        assertThatThrownBy(() -> LedgerTimeline.lengthMs(2000, 1000, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outMs");
    }

    @Test
    void 틈_몫이_음수거나_범위보다_크면_거절한다() {
        assertThatThrownBy(() -> LedgerTimeline.lengthMs(0, 1000, List.of(-1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerTimeline.lengthMs(0, 1000, List.of(600L, 600L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
