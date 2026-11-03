package com.pokeclip.render.job;

import com.pokeclip.render.support.PlanOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.util.UUID;

import static com.pokeclip.render.support.Fixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 새 꼴 주문서의 렌더 계획 검사(PDT 설계 r7 5.3 규칙 1–6). 규칙마다 통과 하나 이상 · 거절 하나 이상을 두고, 거절은
 * {@code ENVELOPE_VALIDATION} 과 규칙 번호(로그 {@code render.plan_rejected rule=N})로 단언한다.
 *
 * <p>P30 이름이 붙은 시험은 clip 준비 판정(설계 16.2 P30)이 내는 주문 모양을 그대로 넣어 렌더 검사 통과를 본다. clip 쪽
 * 렌더 주문 시험이 같은 seq 숫자로 기대값을 둔다. ② 는 준비 조각이 {@code pending} 이라 clip 이 409 로 끝내 주문이 없다.
 * 조각 길이는 모두 {@value PlanOrder#DURATION_MS}ms 다.
 */
@ExtendWith(OutputCaptureExtension.class)
class RenderPlanCheckTest {

    /** long 최댓값 + 1. JSON 에서는 정수지만 long 에 담기지 않는다. */
    static final BigInteger OVER_LONG = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

    private final EnvelopeParser parser = new EnvelopeParser(MAPPER);
    private final UUID jobId = UUID.randomUUID();

    // ── 통과 ──────────────────────────────────────────────────────────

    @Test
    void 설계_5_1_예_주문을_읽어_계획을_싣는다() {
        JobEnvelope job = parser.parse(designExample().body());

        RenderPlan plan = job.plan();
        assertThat(plan.planVersion()).isEqualTo(1);
        assertThat(plan.offsetOrigin()).isEqualTo(OffsetOrigin.FILE_HEAD);
        assertThat(plan.in()).isEqualTo(new MediaCoord(101, 1_000_000));
        assertThat(plan.out()).isEqualTo(new EndBoundary(103, 2_000_000L, false));
        assertThat(plan.mediaSeqs()).containsExactly(101L, 102L, 103L);
        assertThat(plan.subtitles()).containsExactly(
                new SubtitleSpan(0, new SubtitlePoint.AtMedia(new MediaCoord(101, 1_500_000)),
                        new SubtitlePoint.AtMedia(new MediaCoord(102, 500_000))),
                new SubtitleSpan(1, new SubtitlePoint.InGap(101, 500), new SubtitlePoint.InGap(101, 1500)));
        assertThat(plan.lengthMs()).isEqualTo(9000);
        assertThat(job.sources()).extracting(SourceSegment::ref).containsExactly(
                new SourceRef(100, SourceRole.AUDIO_PRIME, 101L),
                new SourceRef(101, SourceRole.SEGMENT, null),
                new SourceRef(102, SourceRole.SEGMENT, null),
                new SourceRef(103, SourceRole.SEGMENT, null));
        assertThat(job.sources()).allSatisfy(s -> assertThat(s.sourceStartAtMs()).isNull());
        assertThat(job.sources().get(2).isDiscontinuity()).isTrue();
        assertThat(job.sources().get(3).joinsPrevious()).isTrue();
        assertThat(job.sources().get(2).joinsPrevious()).isFalse();
    }

    @Test
    void 끝을_조각_끝까지로_적은_주문을_읽는다() {
        PlanOrder order = PlanOrder.of(jobId).segment(101, false, false).segment(102, false, true)
                .in(101, 0).outToSegmentEnd(102);

        assertThat(parser.parse(order.body()).plan().out()).isEqualTo(new EndBoundary(102, null, true));
    }

    @Test
    void 확보_조각은_마지막_매체_뒤_같은_녹화_이웃이면_통과한다() {
        PlanOrder order = twoSegments().capture(103, false, true);

        assertThat(parser.parse(order.body()).sources().getLast().ref())
                .isEqualTo(new SourceRef(103, SourceRole.AUDIO_CAPTURE, null));
    }

    @Test
    void 규칙4_오프셋은_조각_길이_더하기_1초까지_받는다() {
        long limit = PlanOrder.DURATION_MS * 1000 + 1_000_000;
        PlanOrder order = PlanOrder.of(jobId).segment(101, false, false).segment(102, false, true)
                .in(101, limit).out(102, limit);

        assertThat(parser.parse(order.body()).plan().in().offsetUs()).isEqualTo(limit);
    }

    @Test
    void 규칙4_시작과_끝이_한_조각이고_끝이_조각_끝까지면_통과한다() {
        PlanOrder order = PlanOrder.of(jobId).segment(101, false, false).in(101, 3_000_000).outToSegmentEnd(101);

        assertThat(parser.parse(order.body()).plan().mediaSeqs()).containsExactly(101L);
    }

    /** P18 — 끝점 순서를 보지 않는다. 결과 0 이하인 자막은 렌더가 그 자막만 뺀다(설계 5.3 5 · 7.6). */
    @Test
    void 규칙5_P18_끝점_순서가_뒤집힌_자막도_검사는_통과한다() {
        PlanOrder order = twoSegments().subtitleAtMedia(0, 102, 500_000, 101, 1_500_000)
                .subtitleInGap(1, 101, 1500, 101, 500);

        assertThat(parser.parse(order.body()).plan().subtitles()).hasSize(2);
    }

    @Test
    void 규칙5_틈_끝점은_첫_매체_앞_회차_조각을_가리킬_수_있다() {
        PlanOrder order = twoSegments().subtitleInGap(0, 99, 0, 101, 200);

        assertThat(parser.parse(order.body()).plan().subtitles().getFirst().start())
                .isEqualTo(new SubtitlePoint.InGap(99, 0));
    }

    // ── 거절: 규칙 1(새 꼴 · 옛 꼴 판별 · 원점 표지) ─────────────────────

    @Test
    void 규칙1_P9_계획이_있는데_조각_시작_시각이_실리면_거절한다() {
        ObjectNode e = twoSegments().json();
        ((ObjectNode) e.get("sourceKeys").get(1)).put("sourceStartAtMs", 1_790_000_004_000L);

        rejectedBy(e, 1);
    }

    @Test
    void 규칙1_원점_표지가_없거나_모르는_값이면_거절한다() {
        ObjectNode missing = twoSegments().json();
        ((ObjectNode) missing.get("plan")).remove("offsetOrigin");
        rejectedBy(missing, 1);

        ObjectNode unknown = twoSegments().json();
        ((ObjectNode) unknown.get("plan")).put("offsetOrigin", "SEGMENT_START");
        rejectedBy(unknown, 1);
    }

    @Test
    void 규칙1_모르는_계획_판이나_객체가_아닌_계획은_거절한다() {
        ObjectNode version = twoSegments().json();
        ((ObjectNode) version.get("plan")).put("planVersion", 2);
        rejectedBy(version, 1);

        ObjectNode notObject = twoSegments().json();
        notObject.put("plan", "v1");
        rejectedBy(notObject, 1);
    }

    // ── 거절: 규칙 2(매체 열 · 모르는 칸) ─────────────────────────────

    @Test
    void 규칙2_매체_열이_비거나_오르지_않으면_거절한다() {
        rejectedBy(twoSegments().mediaSeqs().json(), 2);
        rejectedBy(twoSegments().mediaSeqs(102, 101).json(), 2);
        rejectedBy(twoSegments().mediaSeqs(101, 101).json(), 2);
    }

    @Test
    void 규칙2_매체_열이_segment_항목과_하나씩_맞물리지_않으면_거절한다() {
        rejectedBy(twoSegments().mediaSeqs(101).out(101, 0).json(), 2);
        rejectedBy(PlanOrder.of(jobId).segment(101, false, false).segment(102, false, true).segment(103, false, true)
                .mediaSeqs(101, 103).in(101, 0).out(103, 0).json(), 2);
    }

    @Test
    void 규칙2_매체_열의_처음과_끝이_in_out_과_다르면_거절한다() {
        rejectedBy(twoSegments().in(102, 0).out(102, 500_000).json(), 2);
        rejectedBy(twoSegments().out(101, 500_000).json(), 2);
    }

    /** 거둔 갈래(채움 · 틈 근거)가 되살아나지 않게 계획 안 모르는 칸은 어디서든 거절한다(fail-closed). */
    @Test
    void 규칙2_계획에_모르는_칸이_오면_거절한다() {
        ObjectNode fill = twoSegments().json();
        ((ObjectNode) fill.get("plan")).putArray("fill");
        rejectedBy(fill, 2);

        ObjectNode gaps = twoSegments().json();
        ((ObjectNode) gaps.get("plan")).putArray("gaps");
        rejectedBy(gaps, 2);

        ObjectNode nested = twoSegments().json();
        ((ObjectNode) nested.get("plan").get("in")).put("style", "freeze");
        rejectedBy(nested, 2);
    }

    @Test
    void 규칙2_모르는_역할의_항목은_거절한다() {
        ObjectNode e = twoSegments().json();
        ((ObjectNode) e.get("sourceKeys").get(1)).put("role", "video");

        rejectedBy(e, 2);
    }

    // ── 거절: 규칙 3(확보 · 준비 조각) ──────────────────────────────

    @Test
    void 규칙3_확보_조각이_r6_조건을_어기면_거절한다() {
        rejectedBy(twoSegments().capture(103, true, true).json(), 3);
        rejectedBy(twoSegments().capture(103, false, false).json(), 3);
        rejectedBy(twoSegments().capture(103, false, true).capture(104, false, true).json(), 3);
        rejectedBy(PlanOrder.of(jobId).segment(101, false, false).capture(103, false, true).segment(102, false, true)
                .in(101, 0).out(102, 0).json(), 3);
    }

    @Test
    void 규칙3_준비_조각의_머리가_매체_열에_없으면_거절한다() {
        rejectedBy(PlanOrder.of(jobId).prime(100, 99).segment(101, false, false).segment(102, false, true)
                .in(101, 0).out(102, 0).json(), 3);
    }

    @Test
    void 규칙3_준비_조각의_머리가_묶음_머리가_아니면_거절한다() {
        rejectedBy(PlanOrder.of(jobId).segment(101, false, false).prime(102, 103).segment(103, false, true)
                .in(101, 0).out(103, 0).json(), 3);
    }

    @Test
    void 규칙3_머리가_새_녹화면_준비_조각을_거절한다() {
        rejectedBy(PlanOrder.of(jobId).prime(100, 101).segment(101, true, false).segment(102, false, true)
                .in(101, 0).out(102, 0).json(), 3);
    }

    @Test
    void 규칙3_준비_조각_seq_가_머리_앞_두_매체_사이가_아니면_거절한다() {
        rejectedBy(PlanOrder.of(jobId).segment(101, false, false).segment(102, false, true)
                .mediaSeqs(101, 102).prime(102, 102).in(101, 0).out(102, 0).json(), 3);
        rejectedBy(PlanOrder.of(jobId).segment(201, false, false).prime(201, 203).segment(203, false, false)
                .in(201, 0).out(203, 0).json(), 3);
        rejectedBy(PlanOrder.of(jobId).prime(101, 101).segment(101, false, false).in(101, 0).out(101, 9).json(), 3);
    }

    @Test
    void 규칙3_한_머리에_준비_조각이_둘이면_거절한다() {
        rejectedBy(PlanOrder.of(jobId).prime(99, 101).prime(100, 101).segment(101, false, false)
                .in(101, 0).out(101, 9).json(), 3);
    }

    @Test
    void 규칙3_준비_조각이_머리_항목_바로_앞이_아니면_거절한다() {
        rejectedBy(PlanOrder.of(jobId).prime(202, 204).segment(201, false, false).segment(204, false, false)
                .in(201, 0).out(204, 0).json(), 3);
    }

    @Test
    void 규칙3_준비_조각에_머리_seq_가_없으면_거절한다() {
        ObjectNode e = PlanOrder.of(jobId).prime(100, 101).segment(101, false, false).in(101, 0).out(101, 9).json();
        ((ObjectNode) e.get("sourceKeys").get(0)).remove("ownerSeq");

        rejectedBy(e, 3);
    }

    // ── 거절: 규칙 4(시작 < 끝 · 오프셋 범위) ───────────────────────────

    @Test
    void 규칙4_시작이_끝보다_앞이_아니면_거절한다() {
        rejectedBy(PlanOrder.of(jobId).segment(101, false, false).in(101, 500).out(101, 500).json(), 4);
        rejectedBy(PlanOrder.of(jobId).segment(101, false, false).in(101, 900).out(101, 500).json(), 4);
    }

    /** P4 — 좌표가 조각 밖이면 엉뚱한 프레임을 고른다. */
    @Test
    void 규칙4_P4_오프셋이_조각_밖이면_거절한다() {
        long over = PlanOrder.DURATION_MS * 1000 + 1_000_001;
        rejectedBy(twoSegments().in(101, over).json(), 4);
        rejectedBy(twoSegments().out(102, over).json(), 4);
        rejectedBy(twoSegments().in(101, -1).json(), 4);
    }

    @Test
    void 규칙4_끝은_오프셋과_조각_끝까지_가운데_하나만_적는다() {
        ObjectNode both = twoSegments().json();
        ((ObjectNode) both.get("plan").get("out")).put("toSegmentEnd", true);
        rejectedBy(both, 4);

        ObjectNode neither = twoSegments().json();
        ((ObjectNode) neither.get("plan").get("out")).remove("offsetUs");
        rejectedBy(neither, 4);

        ObjectNode falseFlag = twoSegments().json();
        ((ObjectNode) falseFlag.get("plan").get("out")).remove("offsetUs");
        ((ObjectNode) falseFlag.get("plan").get("out")).put("toSegmentEnd", false);
        rejectedBy(falseFlag, 4);
    }

    // ── 거절: 규칙 5(자막) · 규칙 6(길이) ──────────────────────────────

    @Test
    void 규칙5_P18_자막_번호가_겹치거나_레시피_구간_수_밖이면_거절한다() {
        rejectedBy(twoSegments().subtitleAtMedia(0, 101, 0, 101, 1).subtitleAtMedia(0, 102, 0, 102, 1).json(), 5);
        rejectedBy(twoSegments().subtitleAtMedia(2, 101, 0, 101, 1).json(), 5);
        rejectedBy(twoSegments().subtitleAtMedia(-1, 101, 0, 101, 1).json(), 5);
    }

    @Test
    void 규칙5_P18_자막_끝점이_매체_열_밖을_가리키면_거절한다() {
        rejectedBy(twoSegments().subtitleAtMedia(0, 101, 0, 103, 1).json(), 5);
        rejectedBy(twoSegments().subtitleInGap(0, 101, 0, 105, 1).json(), 5);
    }

    @Test
    void 규칙6_예상_길이가_1ms_보다_작으면_거절한다() {
        rejectedBy(twoSegments().lengthMs(0).json(), 6);
    }

    // ── 정수 칸 범위 ─────────────────────────────────────────────────

    /** long 에 담기지 않는 정수는 예외로 새지 않고 그 칸의 규칙으로 거절한다(종결 보고 · 메시지 삭제가 되게). */
    @Test
    void 계획의_정수_칸이_long_범위_밖이면_그_칸의_규칙으로_거절한다() {
        ObjectNode version = twoSegments().json();
        ((ObjectNode) version.get("plan")).put("planVersion", OVER_LONG);
        rejectedBy(version, 1);

        ObjectNode media = twoSegments().json();
        ((ObjectNode) media.get("plan")).putArray("mediaSeqs").add(101).add(OVER_LONG);
        rejectedBy(media, 2);

        ObjectNode owner = PlanOrder.of(jobId).prime(100, 101).segment(101, false, false).in(101, 0).out(101, 9).json();
        ((ObjectNode) owner.get("sourceKeys").get(0)).put("ownerSeq", OVER_LONG);
        rejectedBy(owner, 3);

        ObjectNode in = twoSegments().json();
        ((ObjectNode) in.get("plan").get("in")).put("offsetUs", OVER_LONG);
        rejectedBy(in, 4);

        ObjectNode out = twoSegments().json();
        ((ObjectNode) out.get("plan").get("out")).put("seq", OVER_LONG);
        rejectedBy(out, 4);

        ObjectNode gap = twoSegments().subtitleInGap(0, 101, 0, 101, 1).json();
        ((ObjectNode) gap.get("plan").get("subtitles").get(0).get("end")).put("intoGapMs", OVER_LONG);
        rejectedBy(gap, 5);

        ObjectNode index = twoSegments().subtitleAtMedia(0, 101, 0, 101, 1).json();
        ((ObjectNode) index.get("plan").get("subtitles").get(0)).put("index", OVER_LONG);
        rejectedBy(index, 5);

        ObjectNode length = twoSegments().json();
        ((ObjectNode) length.get("plan")).put("lengthMs", OVER_LONG);
        rejectedBy(length, 6);
    }

    @Test
    void 새_꼴_조각_항목의_정수_칸이_long_범위_밖이면_봉투_거절이다() {
        for (String field : new String[] {"seq", "durationMs"}) {
            ObjectNode e = twoSegments().json();
            ((ObjectNode) e.get("sourceKeys").get(1)).put(field, OVER_LONG);

            assertThatThrownBy(() -> parser.parse(MAPPER.writeValueAsString(e))).as(field)
                    .isInstanceOfSatisfying(EnvelopeParser.Rejected.class,
                            r -> assertThat(r.failure().code()).isEqualTo(ErrorCode.ENVELOPE_VALIDATION));
        }
    }

    @Test
    void 거절하면_규칙_번호를_로그에_남긴다(CapturedOutput output) {
        assertThatThrownBy(() -> parser.parse(twoSegments().lengthMs(0).body()))
                .isInstanceOf(EnvelopeParser.Rejected.class);

        assertThat(output.getOut()).contains("render.plan_rejected jobId=" + jobId + " rule=6");
    }

    // ── P30(렌더 쪽) · 첫 매체 머리 ────────────────────────────────────────────

    /** ① 회차 첫 행 k(101) · 앞 행 u(100) 비귀속 · k 의 isDiscontinuity 거짓 → {u, owner k}. */
    @Test
    void P30_1_첫_묶음_머리_앞_비귀속_행을_준비로_실은_주문은_통과한다() {
        PlanOrder order = PlanOrder.of(jobId).prime(100, 101).segment(101, false, false).segment(102, false, true)
                .in(101, 1_000_000).out(102, 2_000_000);

        assertThat(parser.parse(order.body()).sources().getFirst().ref())
                .isEqualTo(new SourceRef(100, SourceRole.AUDIO_PRIME, 101L));
    }

    /** ③ 준비 조각 failed → 준비 없이 주문(clip 은 prime_skipped reason=failed). */
    @Test
    void P30_3_준비_조각이_실패해_빠진_주문은_통과한다() {
        PlanOrder order = PlanOrder.of(jobId).segment(301, false, false).segment(302, false, true)
                .in(301, 1_000_000).out(302, 2_000_000);

        assertThat(parser.parse(order.body()).sources()).hasSize(2);
    }

    /** ④ 머리 k 의 isDiscontinuity 참 → 준비를 싣지 않는다. */
    @Test
    void P30_4_새_녹화_머리에_준비가_없는_주문은_통과한다() {
        PlanOrder order = PlanOrder.of(jobId).segment(401, true, false).segment(402, false, true)
                .in(401, 1_000_000).out(402, 2_000_000);

        assertThat(parser.parse(order.body()).sources()).hasSize(2);
    }

    /** ⑤ A(501) → 비귀속 U(502) → B(503) → 둘째 묶음 머리 B 몫 {U, owner B}. */
    @Test
    void P30_5_둘째_묶음_머리_앞_비귀속_행을_준비로_실은_주문은_통과한다() {
        PlanOrder order = PlanOrder.of(jobId).segment(501, false, false).prime(502, 503).segment(503, false, false)
                .in(501, 1_000_000).out(503, 2_000_000);

        JobEnvelope job = parser.parse(order.body());

        assertThat(job.plan().mediaSeqs()).containsExactly(501L, 503L);
        assertThat(job.sources().get(1).ref()).isEqualTo(new SourceRef(502, SourceRole.AUDIO_PRIME, 503L));
    }

    /** ⑥ 앞 행이 이 방송 장부 구간 시작 앞 → 준비를 싣지 않는다(clip 은 prime_skipped reason=other_broadcast). */
    @Test
    void P30_6_앞_방송_몫이라_준비가_없는_주문은_통과한다() {
        PlanOrder order = PlanOrder.of(jobId).segment(601, false, false).segment(602, false, true)
                .in(601, 1_000_000).out(602, 2_000_000);

        assertThat(parser.parse(order.body()).sources()).hasSize(2);
    }

    /** ⑦ seq 비조밀 — 바로 앞 행 seq 가 h − 3(705 → 702). */
    @Test
    void P30_7_seq_비조밀_앞_행을_준비로_실은_주문은_통과한다() {
        PlanOrder order = PlanOrder.of(jobId).prime(702, 705).segment(705, false, false).segment(706, false, true)
                .in(705, 1_000_000).out(706, 2_000_000);

        assertThat(parser.parse(order.body()).sources().getFirst().ref())
                .isEqualTo(new SourceRef(702, SourceRole.AUDIO_PRIME, 705L));
    }

    /**
     * clip 은 첫 매체 {@code followsLedgerPrevious} 를 거짓으로 정규화해 싣지만, 참으로 와도 렌더는 첫 매체를 늘 묶음
     * 머리로 본다(거절 규칙은 없다). 그래서 그 앞 준비 조각을 받는다.
     */
    @Test
    void 첫_매체_표지가_참으로_와도_머리로_보고_준비를_받는다() {
        PlanOrder order = PlanOrder.of(jobId).prime(100, 101).segment(101, false, true).segment(102, false, true)
                .in(101, 1_000_000).out(102, 2_000_000);

        assertThat(parser.parse(order.body()).sources()).extracting(s -> s.ref().role())
                .containsExactly(SourceRole.AUDIO_PRIME, SourceRole.SEGMENT, SourceRole.SEGMENT);
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    /** 설계 r7 5.1 의 예 그대로. */
    private PlanOrder designExample() {
        return PlanOrder.of(jobId).prime(100, 101).segment(101, false, false).segment(102, true, true)
                .segment(103, false, true).in(101, 1_000_000).out(103, 2_000_000)
                .subtitleAtMedia(0, 101, 1_500_000, 102, 500_000).subtitleInGap(1, 101, 500, 101, 1500)
                .lengthMs(9000);
    }

    private PlanOrder twoSegments() {
        return PlanOrder.of(jobId).segment(101, false, false).segment(102, false, true)
                .in(101, 1_000_000).out(102, 2_000_000);
    }

    private void rejectedBy(ObjectNode envelope, int rule) {
        assertThatThrownBy(() -> parser.parse(MAPPER.writeValueAsString(envelope)))
                .isInstanceOfSatisfying(EnvelopeParser.Rejected.class, r -> {
                    assertThat(r.jobId()).isEqualTo(jobId);
                    assertThat(r.failure().code()).isEqualTo(ErrorCode.ENVELOPE_VALIDATION);
                    assertThat(r.failure()).isInstanceOfSatisfying(PlanViolation.class,
                            v -> assertThat(v.rule()).as(r.getMessage()).isEqualTo(rule));
                });
    }
}
