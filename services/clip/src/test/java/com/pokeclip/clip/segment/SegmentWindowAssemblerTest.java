package com.pokeclip.clip.segment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 순수 판정이라 Spring도 DB도 없이 잰다(PollBackoffTest와 같은 방식).
 *
 * <p>규칙 정본은 PRD 결정 표 「창의 정의(2026-08-24 정정)」다 — <b>가장 앞의
 * 「연속 uploaded」 구간 하나</b>를 주고, 요청 구간을 그것이 다 덮을 때만 complete다.
 */
class SegmentWindowAssemblerTest {

    private static final String UPLOADED = "uploaded";
    private static final String PENDING = "pending";
    private static final String FAILED = "failed";

    /** 조각 하나. {@code s3Key}는 이 판정에 안 쓰이므로 seq로 구분만 되게 채운다. */
    private static StreamSegmentRow row(long seq, long startPtsMs, int durationMs, String state) {
        return new StreamSegmentRow(seq, startPtsMs, durationMs, "seg/" + seq + ".ts", state, false);
    }

    private static List<Long> seqs(SegmentWindow window) {
        return window.segments().stream().map(StreamSegmentRow::seq).toList();
    }

    @Test
    void 전부_uploaded면_complete다() {
        SegmentWindow window = SegmentWindowAssembler.assemble(
                List.of(row(1, 4000, 4000, UPLOADED), row(2, 8000, 4000, UPLOADED)), 5000, 9000);

        assertThat(window.complete()).isTrue();
        assertThat(window.availableFromMs()).isEqualTo(4000);
        assertThat(window.availableUntilMs()).isEqualTo(12000);
        assertThat(seqs(window)).containsExactly(1L, 2L);
    }

    /** PRD 성공 기준 — complete가 참이면 요청 구간이 실제로 양쪽 다 덮여 있어야 한다. */
    @Test
    void complete면_from은_요청시작_이하이고_until은_요청끝_이상이다() {
        long startMs = 5000;
        long endMs = 9000;

        SegmentWindow window = SegmentWindowAssembler.assemble(
                List.of(row(1, 4000, 4000, UPLOADED), row(2, 8000, 4000, UPLOADED)), startMs, endMs);

        assertThat(window.complete()).isTrue();
        assertThat(window.availableFromMs()).isLessThanOrEqualTo(startMs);
        assertThat(window.availableUntilMs()).isGreaterThanOrEqualTo(endMs);
    }

    /** 뒤의 uploaded를 이어 붙이면 가운데가 빈 목록이 되어 화면에서 영상이 튄다. */
    @Test
    void 중간_pending에서_끊고_뒤_uploaded는_버린다() {
        SegmentWindow window = SegmentWindowAssembler.assemble(
                List.of(row(1, 0, 4000, UPLOADED), row(2, 4000, 4000, PENDING), row(3, 8000, 4000, UPLOADED)),
                0, 12000);

        assertThat(seqs(window)).containsExactly(1L);
        assertThat(window.availableUntilMs()).isEqualTo(4000);
        assertThat(window.complete()).isFalse();
    }

    /** seq2가 인덱스에 아예 없다 — 상태로는 안 보이고 번호로만 보이는 구멍이다. */
    @Test
    void seq_구멍에서도_끊는다() {
        SegmentWindow window = SegmentWindowAssembler.assemble(
                List.of(row(1, 0, 4000, UPLOADED), row(3, 8000, 4000, UPLOADED)), 0, 12000);

        assertThat(seqs(window)).containsExactly(1L);
        assertThat(window.availableUntilMs()).isEqualTo(4000);
        assertThat(window.complete()).isFalse();
    }

    /** 방금 터진 하이라이트 — 인덱스가 아직 요청 끝까지 안 자랐다. */
    @Test
    void 마지막_조각이_요청끝보다_앞이면_incomplete다() {
        SegmentWindow window = SegmentWindowAssembler.assemble(
                List.of(row(1, 0, 4000, UPLOADED), row(2, 4000, 4000, UPLOADED)), 0, 12000);

        assertThat(seqs(window)).containsExactly(1L, 2L);
        assertThat(window.availableUntilMs()).isEqualTo(8000);
        assertThat(window.complete()).isFalse();
    }

    /**
     * 🔴 계획 검증 F5. 요청 {@code [5000,9000)}인데 겹침 결과가 {@code seq5 [8000,12000)} 하나뿐이다.
     *
     * <p>머리 3초가 통째로 비었는데 <b>until만 보면 {@code 12000 >= 9000}이라 참이 된다</b> —
     * {@code from(8000) > startMs(5000)}이 거짓을 만드는 유일한 근거다.
     */
    @Test
    void 요청_머리가_비면_뒤_조각을_주되_complete는_거짓이다() {
        SegmentWindow window = SegmentWindowAssembler.assemble(List.of(row(5, 8000, 4000, UPLOADED)), 5000, 9000);

        assertThat(seqs(window)).containsExactly(5L);
        assertThat(window.availableFromMs()).isEqualTo(8000);
        assertThat(window.availableUntilMs()).isEqualTo(12000);
        assertThat(window.complete()).isFalse();
    }

    /**
     * 「아직 안 올라옴」과 「영영 없음」을 이 API가 구분 못 하므로 둘 다 뒤를 보여준다(PRD 결정).
     * 막으면 고칠 수 있는 경우와 못 고치는 경우가 똑같이 막힌다.
     */
    @Test
    void 앞_조각이_pending이어도_뒤의_uploaded_구간을_준다() {
        SegmentWindow window = SegmentWindowAssembler.assemble(
                List.of(row(1, 0, 4000, PENDING), row(2, 4000, 4000, UPLOADED)), 0, 12000);

        assertThat(seqs(window)).containsExactly(2L);
        assertThat(window.availableFromMs()).isEqualTo(4000);
        assertThat(window.complete()).isFalse();
    }

    @Test
    void 겹침_결과가_아예_없으면_빈_목록이고_from_until이_요청시작이다() {
        SegmentWindow window = SegmentWindowAssembler.assemble(List.of(), 5000, 9000);

        assertThat(window.segments()).isEmpty();
        assertThat(window.availableFromMs()).isEqualTo(5000);
        assertThat(window.availableUntilMs()).isEqualTo(5000);
        assertThat(window.complete()).isFalse();
    }

    /**
     * 뒤집힌 구간({@code startMs >= endMs})은 조각이 실려도 완전일 수 없다.
     * 빈 결과에 이미 서 있는 근거가 비지 않은 결과에도 같게 서야 한다.
     *
     * <p><b>지금 운영 호출자로는 도달 불가다</b> — {@code previewWindow}가 맨 앞에서 구간을
     * 검증한다. 그런데 이 메서드는 {@code public static}이고 소비자가 둘(편집기 미리보기 ·
     * 렌더 잡 POK-125)인데, {@code previewWindow}는 {@code requesterSubject}를 요구해
     * <b>렌더 잡이 그대로 못 부른다</b>(감사 2차). 새 진입점이 생기는 날 구간 검증은 안 따라오고,
     * 렌더 잡은 {@code complete}로 발행 여부를 가른다 — 「완전하다」를 받으면 엉뚱한 클립이 나간다.
     *
     * <p>조각이 실렸다는 단언이 먼저다 — 빈 창이면 {@code complete=false}가 자동으로 참이라
     * 아무것도 안 잰다.
     */
    @Test
    void 뒤집힌_구간은_조각이_실려도_complete가_아니다() {
        SegmentWindow window = SegmentWindowAssembler.assemble(
                List.of(row(1, 4000, 4000, UPLOADED), row(2, 8000, 4000, UPLOADED)), 9000, 5000);

        assertThat(seqs(window)).containsExactly(1L, 2L);
        assertThat(window.complete()).isFalse();
    }

    /**
     * 계약-세그먼트인덱스 3절 — {@code failed → uploaded} 역전이가 실재하므로
     * {@code failed}를 종국 상태로 판단하지 않는다. pending과 같은 「아직 아님」이다.
     */
    @Test
    void failed도_pending과_같이_아직_아님으로_본다() {
        SegmentWindow window = SegmentWindowAssembler.assemble(
                List.of(row(1, 0, 4000, FAILED), row(2, 4000, 4000, UPLOADED)), 0, 12000);

        assertThat(seqs(window)).containsExactly(2L);
        assertThat(window.availableFromMs()).isEqualTo(4000);
        assertThat(window.complete()).isFalse();
    }

    /**
     * 불연속(재연결)은 pts에 공백을 만들지만 조각이 빠진 게 아니다 — 연속의 축은 seq다.
     *
     * <p>🔴 불연속 조각을 <b>가운데</b>에 둔다. 마지막 자리에 두면 「끊기지 않는다」를
     * 잴 수 없다(계획 검증 m2).
     */
    @Test
    void 불연속_조각은_끊지_않고_그대로_싣는다() {
        StreamSegmentRow jumped = new StreamSegmentRow(2, 4000, 4000, "seg/2.ts", UPLOADED, true);

        SegmentWindow window = SegmentWindowAssembler.assemble(
                List.of(row(1, 0, 4000, UPLOADED), jumped, row(3, 8000, 4000, UPLOADED)), 0, 12000);

        assertThat(seqs(window)).containsExactly(1L, 2L, 3L);
        assertThat(window.segments().get(1).discontinuity()).isTrue();
        assertThat(window.availableUntilMs()).isEqualTo(12000);
        assertThat(window.complete()).isTrue();
    }
}
