package com.pokeclip.render.support;

import com.pokeclip.render.support.DirectSegments.Origin;
import com.pokeclip.render.support.SyncCorrection.Checkpoint;
import com.pokeclip.render.support.SyncCorrection.Judged;
import com.pokeclip.render.support.SyncCorrection.Segment;
import com.pokeclip.render.support.SyncCorrection.Verdict;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 녹화기를 거친 합성 자료의 싱크 보정식(PDT 설계 r7 13.5 「보정식」 · 계획 C9). 기대 Σ 는 정수 틱으로 손으로 셌다(조각표의 τ =
 * V − 패킷 길이 합을 묶음 머리부터 확인점 앞 조각까지 더함). 자원 {@code oracle/recorder-sync-cases.json} 은 설계 13.5 「보정식
 * 재계산」 표가 쓴 세 사례의 조각표 · 확인점 원시 오차를 줄인 것이다.
 */
class SyncCorrectionTest {

    // ---------------------------------------------------------------- 실측 세 사례

    /** O_cap180: 한 묶음 · 머리 1 = 연결 첫 조각(V 536999 · 패킷 합 537000 → τ −1) · 3 · 4 · 5 · 16 · 19 · 20 · 21 이 τ +1. */
    @Test
    void O_cap180_의_Σ_와_보정_뒤_잔차() {
        Case c = load("O_cap180");

        assertThat(SyncCorrection.sigma(c.flow(), 1)).isZero();
        // 23 앞: 1 의 −1 + 3 · 4 · 5 · 16 · 19 · 20 · 21 의 +1 일곱 = +6
        assertThat(SyncCorrection.sigma(c.flow(), 23)).isEqualTo(6);
        // 45 앞: 위 +6 + 32 · 35 · 36 · 37 의 +1 넷 = +10
        assertThat(SyncCorrection.sigma(c.flow(), 45)).isEqualTo(10);
        List<Judged> judged = SyncCorrection.judge(Origin.RECORDER, c.flow(), c.checkpoints());
        assertThat(SyncCorrection.verdict(judged)).isEqualTo(Verdict.PASS);
        assertThat(judged).allSatisfy(j -> assertThat(Math.abs(j.residualMs())).isLessThan(0.0112));
    }

    /** I9_drift+0: 머리 2 정상 · 확인점 44개 — 40 앞 Σ = +11(3 · 4 · 5 · 16 · 19 · 20 · 21 · 32 · 35 · 36 · 37). */
    @Test
    void I9_drift_0_의_Σ_와_보정_뒤_잔차() {
        Case c = load("I9_drift+0");

        assertThat(SyncCorrection.sigma(c.flow(), 2)).isZero();
        assertThat(SyncCorrection.sigma(c.flow(), 5)).isEqualTo(2);
        assertThat(SyncCorrection.sigma(c.flow(), 40)).isEqualTo(11);
        List<Judged> judged = SyncCorrection.judge(Origin.RECORDER, c.flow(), c.checkpoints());
        assertThat(judged).hasSize(44);
        assertThat(SyncCorrection.verdict(judged)).isEqualTo(Verdict.PASS);
        assertThat(judged).allSatisfy(j -> assertThat(Math.abs(j.residualMs())).isLessThan(0.0112));
    }

    /** I1_sync: 묶음 둘 [2 · 3] [4 · 5] · 4 = 연결 첫 조각(τ −1) — 둘째 묶음 머리에서 0 으로 다시 센다. */
    @Test
    void I1_sync_는_묶음_경계에서_0_으로_다시_센다() {
        Case c = load("I1_sync");

        assertThat(SyncCorrection.sigma(c.flow(), 3)).isZero();
        assertThat(SyncCorrection.sigma(c.flow(), 4)).as("둘째 묶음 머리").isZero();
        assertThat(SyncCorrection.sigma(c.flow(), 5)).isEqualTo(-1);
        List<Judged> judged = SyncCorrection.judge(Origin.RECORDER, c.flow(), c.checkpoints());
        assertThat(SyncCorrection.verdict(judged)).isEqualTo(Verdict.PASS);
    }

    /** 보정 없이 원시 오차만 보면 O_cap180 · I9_drift+0 은 0.1ms 를 넘는다(보정식이 판정을 가르는 사례). */
    @Test
    void 보정을_빼면_녹화기_거친_사례가_FAIL() {
        for (String name : List.of("O_cap180", "I9_drift+0")) {
            Case c = load(name);
            assertThat(SyncCorrection.verdict(SyncCorrection.judge(Origin.DIRECT, c.flow(), c.checkpoints())))
                    .as(name).isEqualTo(Verdict.FAIL);
        }
    }

    // ---------------------------------------------------------------- 합성 사례

    /** 머리 −3001 꼴: 연결 첫 조각(머리 패킷 90 · 2910 …)은 V − n·간격 = −3001 이지만 패킷 길이 합 기준 τ = −1 이다. */
    @Test
    void 연결_첫_조각은_패킷_길이_합으로_센다() {
        List<Segment> flow = List.of(seg(1, 180, 536_999, 537_000, true), seg(2, 120, 360_000, 360_000, false),
                seg(3, 120, 360_001, 360_000, false));

        assertThat(SyncCorrection.sigma(flow, 2)).isEqualTo(-1);
        assertThat(SyncCorrection.sigma(flow, 3)).isEqualTo(-1);
        List<Judged> judged = SyncCorrection.judge(Origin.RECORDER, flow, List.of(cp(3, "0.0111")));
        // 원시 +0.0111 + Σ(−1) × 1/90 = 0.0000 → PASS. 글자 그대로(−3001)면 −33.33ms
        assertThat(judged.getFirst().residualTimes90()).isEqualByComparingTo("-0.001");
        assertThat(SyncCorrection.verdict(judged)).isEqualTo(Verdict.PASS);
    }

    /** 준비 조각 τ_p ≠ 0: 그 τ 가 묶음 Σ 의 첫 항이다 — 머리 안 확인점도 Σ = τ_p 이다. */
    @Test
    void 준비_조각_τ_가_첫_항이다() {
        List<Segment> flow = List.of(seg(5, 120, 360_000, 360_000, true), prime(6, 360_001, 360_000),
                seg(7, 120, 360_000, 360_000, true), seg(8, 120, 359_999, 360_000, false),
                seg(9, 120, 360_000, 360_000, false));

        assertThat(SyncCorrection.sigma(flow, 7)).as("머리 안 = τ_p").isEqualTo(1);
        assertThat(SyncCorrection.sigma(flow, 9)).as("τ_p + τ_7 + τ_8").isZero();
        assertThat(SyncCorrection.sigma(flow, 8)).isEqualTo(1);
        assertThat(SyncCorrection.sigma(flow, 5)).as("앞 묶음은 준비 조각과 무관").isZero();
        // 영상이 τ_p 만큼 늦게 놓여 원시 오차 ≈ −Σ: −0.0111 + 1/90 → 0
        List<Judged> judged = SyncCorrection.judge(Origin.RECORDER, flow, List.of(cp(7, "-0.0111")));
        assertThat(judged.getFirst().sigmaTicks()).isEqualTo(1);
        assertThat(judged.getFirst().residualTimes90()).isEqualByComparingTo("0.001");
    }

    /** 부호: Σ > 0 이면 원시 오차가 음수 쪽이고 보정이 0 쪽으로 돌린다. 부호를 뒤집으면 판정선을 넘는다. */
    @Test
    void 보정은_원시_오차에_Σ_를_더한다() {
        List<Segment> flow = new ArrayList<>(List.of(seg(1, 120, 360_000, 360_000, true)));
        for (long seq = 2; seq <= 9; seq++) {
            flow.add(seg(seq, 120, 360_001, 360_000, false));
        }
        flow.add(seg(10, 120, 360_000, 360_000, false));

        List<Judged> judged = SyncCorrection.judge(Origin.RECORDER, flow, List.of(cp(10, "-0.0889")));

        assertThat(judged.getFirst().sigmaTicks()).isEqualTo(8);
        assertThat(judged.getFirst().residualTimes90()).isEqualByComparingTo("-0.001");
        assertThat(SyncCorrection.verdict(judged)).isEqualTo(Verdict.PASS);
    }

    /** 이상 사건(|τ| > 1 · 소리 패킷 빠짐)이 확인점 묶음 안에 있으면 그 확인점은 판정에서 빠지고 PASS 가 아니다. */
    @Test
    void 이상_사건이_묶음_안에_있으면_PASS_가_아니다() {
        List<Segment> flow = List.of(seg(1, 120, 360_000, 360_000, true), seg(2, 120, 359_997, 360_000, false),
                seg(3, 120, 360_000, 360_000, false), seg(4, 120, 360_000, 360_000, true));

        List<Judged> judged = SyncCorrection.judge(Origin.RECORDER, flow, List.of(cp(3, "0.0"), cp(4, "0.0")));

        assertThat(judged.get(0).excluded()).isTrue();
        assertThat(judged.get(1).excluded()).as("다른 묶음").isFalse();
        assertThat(SyncCorrection.verdict(judged)).isEqualTo(Verdict.EXCLUDED);
        List<Segment> dropped = List.of(seg(1, 120, 360_000, 360_000, true),
                new Segment(2, 120, 360_000, 360_000, 3_000, false, false, 1), seg(3, 120, 360_000, 360_000, false));
        assertThat(SyncCorrection.verdict(SyncCorrection.judge(Origin.RECORDER, dropped, List.of(cp(3, "0.0")))))
                .as("소리 패킷 빠짐").isEqualTo(Verdict.EXCLUDED);
    }

    /** 경계 시험: 반올림 전 정확값이 판정선과 같으면 FAIL, 바로 아래면 PASS(설계 13.5 · 계획 C9). */
    @Test
    void 판정선과_정확히_같은_차는_FAIL() {
        List<Segment> flow = List.of(seg(1, 120, 360_000, 360_000, true), seg(2, 120, 360_000, 360_000, false));

        assertThat(verdict(flow, "-0.1")).isEqualTo(Verdict.FAIL);
        assertThat(verdict(flow, "0.1")).isEqualTo(Verdict.FAIL);
        assertThat(verdict(flow, "-0.0999999")).isEqualTo(Verdict.PASS);
        List<Segment> nine = new ArrayList<>(List.of(seg(1, 120, 360_000, 360_000, true)));
        for (long seq = 2; seq <= 10; seq++) {
            nine.add(seg(seq, 120, 360_001, 360_000, false));
        }
        nine.add(seg(11, 120, 360_000, 360_000, false));
        // Σ = +9틱 = 0.1ms 정확히 · 원시 −0.2 → 잔차 −0.1 → FAIL. 원시 −0.19999 → PASS
        assertThat(verdictAt(nine, 11, "-0.2")).isEqualTo(Verdict.FAIL);
        assertThat(verdictAt(nine, 11, "-0.19999")).isEqualTo(Verdict.PASS);
    }

    @Test
    void 바로_쓴_합성은_보정하지_않는다() {
        List<Segment> flow = List.of(seg(1, 120, 360_001, 360_000, true), seg(2, 120, 360_000, 360_000, false));

        List<Judged> judged = SyncCorrection.judge(Origin.DIRECT, flow, List.of(cp(2, "-0.0111")));

        assertThat(judged.getFirst().sigmaTicks()).isZero();
        assertThat(judged.getFirst().residualTimes90()).isEqualByComparingTo("-0.999");
    }

    // ---------------------------------------------------------------- 자료

    record Case(List<Segment> flow, List<Checkpoint> checkpoints) {
    }

    private static Verdict verdict(List<Segment> flow, String rawMs) {
        return verdictAt(flow, 2, rawMs);
    }

    private static Verdict verdictAt(List<Segment> flow, long seq, String rawMs) {
        return SyncCorrection.verdict(SyncCorrection.judge(Origin.RECORDER, flow, List.of(cp(seq, rawMs))));
    }

    private static Segment seg(long seq, int frames, long v, long packets, boolean head) {
        return new Segment(seq, frames, v, packets, 3_000, head, false, 0);
    }

    private static Segment prime(long seq, long v, long packets) {
        return new Segment(seq, 120, v, packets, 3_000, false, true, 0);
    }

    private static Checkpoint cp(long seq, String rawMs) {
        return new Checkpoint(seq, new BigDecimal(rawMs));
    }

    private static Case load(String name) {
        JsonNode root;
        try (InputStream in = SyncCorrectionTest.class.getResourceAsStream("/oracle/recorder-sync-cases.json")) {
            root = Fixtures.MAPPER.readTree(Objects.requireNonNull(in).readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        for (JsonNode c : root.path("cases")) {
            if (!c.path("name").asString().equals(name)) {
                continue;
            }
            List<Segment> flow = new ArrayList<>();
            for (JsonNode s : c.path("segments")) {
                flow.add(new Segment(s.path("seq").asLong(), s.path("frames").asInt(), s.path("videoTicks").asLong(),
                        s.path("packetTicks").asLong(), s.path("intervalTicks").asLong(), s.path("runHead").asBoolean(),
                        false, s.path("audioGaps").asInt()));
            }
            List<Checkpoint> cps = new ArrayList<>();
            for (JsonNode p : c.path("checkpoints")) {
                cps.add(new Checkpoint(p.path("seq").asLong(), new BigDecimal(p.path("rawErrorMs").asString())));
            }
            assertThat(c.path("origin").asString()).isEqualTo("RECORDER");
            return new Case(flow, cps);
        }
        throw new IllegalArgumentException(name);
    }
}
