package com.pokeclip.render.work;

import com.pokeclip.render.job.EndBoundary;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.OffsetOrigin;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.work.RenderTimeline.Placement;
import com.pokeclip.render.work.RenderTimeline.VideoCut;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.pokeclip.render.work.TimelineFixtures.FRAME;
import static com.pokeclip.render.work.TimelineFixtures.at;
import static com.pokeclip.render.work.TimelineFixtures.capture;
import static com.pokeclip.render.work.TimelineFixtures.end;
import static com.pokeclip.render.work.TimelineFixtures.grid;
import static com.pokeclip.render.work.TimelineFixtures.media;
import static com.pokeclip.render.work.TimelineFixtures.plan;
import static com.pokeclip.render.work.TimelineFixtures.prime;
import static com.pokeclip.render.work.TimelineFixtures.standard;
import static com.pokeclip.render.work.TimelineFixtures.timing;
import static com.pokeclip.render.work.TimelineFixtures.track;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RenderTimeline} 의 영상 쪽 — 틱 선택(설계 7.3 · 경계 표 4판 B2) · 영상 이동량 · 표시 소유 · 결과 끝 소유(7.4) ·
 * 배치표(7.6) · 이음 목록 µs 간격(7.5). 기대값은 모두 B2 정의 산식으로 손으로 셌다(시험마다 주석). 틱 → µs 는
 * {@code 틱 × 10⁶ ÷ 90000} 이고 3000틱 = 33,333.33µs 다.
 */
class RenderTimelineTest {

    private static final List<Integer> TRACK0 = List.of(0);

    // ── 틱 선택(7.3) ─────────────────────────────────────────────────

    /**
     * 조각 10 프레임 PTS 9000 · 12000 · 15000 · 18000(x′ = 0 · 33,333.33 · 66,666.67 · 100,000µs). 시작 x′ = 33,333 은
     * 프레임 1 을 반 µs 안에서 가리키므로 {@code ≼} 가 참(2·10⁶·12000 ≤ 2·10⁶·9000 + 66,667 × 90000)이라 f_in = 12000.
     * 끝 x′ = 100,000 은 프레임 3 위라 {@code ≺} 가 거짓(1.8·10¹⁰ > 199,999 × 90000)이고 남는 마지막 = 15000 →
     * end_pts = 15001(끝 포함이 {@code +1}). Δ = −12000.
     */
    @Test
    void 반올림한_µs_좌표는_반_µs_여유로_그_프레임을_고르고_끝은_끝_포함_1을_더한다() {
        RenderTimeline t = single(OffsetOrigin.VIDEO_START, 33_333, end(10, 100_000));

        assertThat(t.videoCuts()).containsExactly(new VideoCut(10, 12_000, 15_001, -12_000));
    }

    /** 두 프레임 사이(50,000µs)는 앞 프레임(33,333.33µs = PTS 12000) — 「≼ 인 최대 PTS」. */
    @Test
    void 프레임_사이_시작은_앞_프레임() {
        RenderTimeline t = single(OffsetOrigin.VIDEO_START, 50_000, end(10, 100_000));

        assertThat(t.videoCuts().getFirst().startPts()).isEqualTo(12_000);
    }

    /** FILE_HEAD 0 → x′ = 0 − m = −50,000 · ≼ 인 프레임 없음 → 첫 프레임 9000. */
    @Test
    void 첫_프레임_앞_시작은_첫_프레임() {
        RenderTimeline t = single(OffsetOrigin.FILE_HEAD, 0, end(10, 150_000));

        assertThat(t.videoCuts().getFirst().startPts()).isEqualTo(9_000);
    }

    /**
     * FILE_HEAD 83,334 → x′ = 83,334 − m(50,000) = 33,334 → 프레임 1(12000). 원점 정규화를 빼면 x′ = 83,334 가
     * 프레임 2(66,666.67µs ≤ 83,334)를 고른다(P5).
     */
    @Test
    void 파일_머리_원점은_m_만큼_당겨_정규화한다() {
        RenderTimeline t = single(OffsetOrigin.FILE_HEAD, 83_334, end(10, 150_000));

        assertThat(t.videoCuts().getFirst().startPts()).isEqualTo(12_000);
    }

    /**
     * 단일 조각 결과 끝: b = Δ + D0 = −3000틱 · x = 100,000µs → E = −33,333.33 + 100,000 = 66,666.67µs →
     * lengthUs 66,667 · 48kHz 표본 floor(66,666.67µs × 0.048) = 3200. 배치표: zeroAt = rnd(−33,333.33) = −33,333 ·
     * from = 결과 0 − zeroAt = 33,333 · to = 66,667 − (−33,333) = 100,000 · headLead = m = 50,000.
     */
    @Test
    void 단일_조각_결과_끝_배치표() {
        RenderTimeline t = single(OffsetOrigin.VIDEO_START, 33_333, end(10, 100_000));

        assertThat(t.lengthUs()).isEqualTo(66_667);
        assertThat(t.resultEndSamples()).isEqualTo(3_200);
        assertThat(t.placements()).containsExactly(new Placement(10, -33_333, 33_333, 100_000, 50_000));
        assertThat(t.videoJoinDurationsUs()).isEmpty();
        assertThat(t.lengthCapped()).isFalse();
    }

    // ── J 이음 · 영상 이동량(7.4) ─────────────────────────────────────

    /**
     * A(1) · B(2) J. Δ_A = −f_in = −9000 · Δ_B = Δ_A + (D0 + V) − D0 = −9000 + 21000 − 9000 = 3000. 끝 B 66,667µs
     * → B 프레임 0 · 1(≺: 66,666.67 은 반 µs 안이라 거짓) → end_pts 12001. R = a + Δ: 0 · 12000 →
     * 이음 간격 rnd(133,333.33) − 0 = 133,333µs. E = 12000틱 + 66,667µs = 200,000.33µs → 200,000.
     * 배치표 B: zeroAt rnd(133,333.33) = 133,333 · from = rnd(133,333.33 − m) − 133,333 = −50,000 · to = 200,000 −
     * 133,333 = 66,667. A: [0, R_B = 133,333].
     */
    @Test
    void 같은_녹화_이음은_앞_조각_DTS_끝에_뒤_조각_첫_DTS를_놓는다() {
        RenderTimeline t = jChain(end(2, 66_667), false);

        assertThat(t.videoCuts()).containsExactly(
                new VideoCut(1, 9_000, 18_001, -9_000),
                new VideoCut(2, 9_000, 12_001, 3_000));
        assertThat(t.videoJoinDurationsUs()).containsExactly(133_333L);
        assertThat(t.lengthUs()).isEqualTo(200_000);
        assertThat(t.resultEndSamples()).isEqualTo(9_600);
        assertThat(t.placements()).containsExactly(
                new Placement(1, 0, 0, 133_333, 50_000),
                new Placement(2, 133_333, -50_000, 66_667, 50_000));
        assertJoinInvariant(t);
    }

    /**
     * 셋 이은 J 열의 이음 간격은 누적 정확값을 µs 로 반올림한 차다(7.5 · 쌓이지 않음). R = 0 · 12000 · 24000틱 →
     * rnd 0 · 133,333 · 266,667 → 간격 133,333 · 133,334.
     */
    @Test
    void 이음_간격은_누적_정확값의_반올림_차다() {
        Map<Long, SegmentTiming> timings = Map.of(1L, standard(4), 2L, standard(4), 3L, standard(4));
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), new EndBoundary(3, null, true), List.of(1L, 2L, 3L)),
                List.of(media(1, false), media(2, true), media(3, true)), timings, TRACK0);

        assertThat(t.videoJoinDurationsUs()).containsExactly(133_333L, 133_334L);
        assertJoinInvariant(t);
    }

    // ── 표시 소유(끊긴 이웃 · B8) ─────────────────────────────────────

    /**
     * A(1) V = 9000(마지막 PTS 18000 = D0 + V — ra 꼴) · C(3) 끊긴 이웃 c = 0. A 는 PTS < D0 + V + c_C = 18000 만 가져
     * 18000 이 빠진다(나중 조각이 이김). Δ_C = −9000 + 18000 − 9000 = 0 → R_C = 9000틱 = 100,000µs. 끝 C 66,667 →
     * C 프레임 9000 · 12000. E = 9000틱 + 66,667µs = 166,666.97 → 166,667.
     */
    @Test
    void 끊긴_이웃_경계에서는_뒤_조각_첫_프레임_이후의_앞_조각_프레임이_빠진다() {
        RenderTimeline t = cut(end(3, 66_667));

        assertThat(t.videoCuts()).containsExactly(
                new VideoCut(1, 9_000, 15_001, -9_000),
                new VideoCut(3, 9_000, 12_001, 0));
        assertThat(t.videoJoinDurationsUs()).containsExactly(100_000L);
        assertThat(t.lengthUs()).isEqualTo(166_667);
        assertThat(t.placements()).containsExactly(
                new Placement(1, 0, 0, 100_000, 50_000),
                new Placement(3, 100_000, 0, 66_667, 50_000));
        assertThat(t.notes()).extracting(RenderTimeline.Note::event).contains("render.frame_overlap");
        assertJoinInvariant(t);
    }

    // ── 끝 조각 프레임 0(7.3 6 끝 쪽) ─────────────────────────────────

    /**
     * J 끝 조각 B 에 프레임이 없으면 「소리만」 으로 남긴다. FILE_HEAD 끝 20,000 → x′ = −30,000(≺ 인 프레임 없음).
     * b = 12000틱 · x = −30,000 → E = 133,333.33 − 30,000 = 103,333.33 → 103,333. B 결과 시작(파일 머리 자리) =
     * 83,333.33 < E 라 B 는 남고, 배치표 B = [−m, x′_out] = [−50,000, −30,000](설계 7.6). A 는 프레임 전부 · E 까지.
     */
    @Test
    void 같은_녹화_끝_조각에_프레임이_없으면_소리만_남긴다() {
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.FILE_HEAD, at(1, 50_000), end(2, 20_000), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true)), Map.of(1L, standard(4), 2L, standard(4)), TRACK0);

        assertThat(t.videoCuts()).containsExactly(new VideoCut(1, 9_000, 18_001, -9_000));
        assertThat(t.lengthUs()).isEqualTo(103_333);
        assertThat(t.placements()).containsExactly(
                new Placement(1, 0, 0, 103_333, 50_000),
                new Placement(2, 133_333, -50_000, -30_000, 50_000));
        assertThat(t.audioRuns().getFirst().seqs()).containsExactly(1L, 2L);
    }

    /**
     * 끊긴 이웃 C 끝 x′ = 0 이면 C 를 빼고 끝을 「A, toSegmentEnd」 로 바꾼다(재접속 뒤 소리 없음). 원래 out 이 빠졌으니
     * 확보 조각 4 도 뺀다(설계 7.4 소리 묶음 8). A 의 마지막 프레임 18000 은 C 가 없으니 표시 소유로 빠지지 않는다 — 끝 = A 마지막
     * 프레임(T-8c 2). b = Δ_A + 마지막 PTS + 간격 = −9000 + 18000 + 3000 = 12000틱 → E = 133,333.
     */
    @Test
    void 끊긴_이웃_끝_조각에_프레임이_없으면_빼고_앞_조각_끝까지로_물린다() {
        SegmentTiming shortV = timing(grid(0, FRAME, 4), 9_000, track(2_400, 10));
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(3, 0), List.of(1L, 3L)),
                List.of(media(1, false), media(3, false), capture(4)),
                Map.of(1L, shortV, 3L, standard(4), 4L, standard(4)), TRACK0);

        assertThat(t.videoCuts()).containsExactly(new VideoCut(1, 9_000, 18_001, -9_000));
        assertThat(t.lengthUs()).isEqualTo(133_333);
        assertThat(t.placements()).extracting(Placement::seq).containsExactly(1L);
        assertThat(t.audioRuns()).singleElement().satisfies(r -> assertThat(r.captureSeq()).isNull());
        assertThat(t.notes()).extracting(RenderTimeline.Note::event)
                .contains("render.out_retreated", "render.capture_dropped");
        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.capture_dropped"))
                .singleElement().satisfies(n -> assertThat(n.fields()).contains("reason=out_retreated"));
    }

    // ── 첫 조각 프레임 0(7.3 6 시작 쪽) ───────────────────────

    /**
     * A V = 9000 · 시작 x′ = 100,000(PTS 18000) → 남는 A 프레임 18000 이 표시 소유로 빠져 A 가 비면 A 를 빼고 시작을
     * 「C, 첫 프레임」 으로 바꾼다. Δ_C = −9000 · 결과 첫 프레임 = 결과 0. 빠진 A 의 준비 조각 0 은 owner_dropped.
     */
    @Test
    void 첫_조각에_남는_프레임이_없으면_다음_조각_첫_프레임부터_시작한다() {
        SegmentTiming shortV = timing(grid(0, FRAME, 4), 9_000, track(2_400, 10));
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 100_000), end(3, 66_667), List.of(1L, 3L)),
                List.of(prime(0, 1), media(1, false), media(3, false)),
                Map.of(0L, standard(4), 1L, shortV, 3L, standard(4)), TRACK0);

        assertThat(t.videoCuts()).containsExactly(new VideoCut(3, 9_000, 12_001, -9_000));
        assertThat(t.lengthUs()).isEqualTo(66_667);
        assertThat(t.audioRuns()).singleElement().satisfies(r -> {
            assertThat(r.seqs()).containsExactly(3L);
            assertThat(r.primeSeq()).isNull();
        });
        assertThat(t.notes()).extracting(RenderTimeline.Note::event)
                .contains("render.in_advanced", "render.prime_skipped");
        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.prime_skipped"))
                .singleElement().satisfies(n -> assertThat(n.fields()).contains("reason=owner_dropped"));
    }

    // ── 결과 끝 소유 · 180초(7.4 결과 끝 소유 1–4) ──────────────────────

    /**
     * 설계 7.4 반례 2 꼴. A 5415장(V = 16,245,000틱 = 180.5초) · B J. 끝 = B 0.1초 → 180.6초 > 180 → b = 0 · x =
     * 180,000,000. B 결과 시작(파일 머리) = 180.45초 ≥ 180 → B 를 뺀다(확보 조각 4 도 · capped). A 는 결과 시각
     * {@code ≺ 180초} 만: (PTS − 9000) ≤ 16,199,999.955 → 마지막 16,197,000 → end_pts 16,206,001.
     */
    @Test
    void 결과_끝이_180초를_넘으면_모든_조각을_180초에서_자르고_넘는_조각은_뺀다() {
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 100_000), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true), capture(4)),
                Map.of(1L, standard(5_415), 2L, standard(4), 4L, standard(4)), TRACK0);

        assertThat(t.videoCuts()).containsExactly(new VideoCut(1, 9_000, 16_206_001, -9_000));
        assertThat(t.lengthUs()).isEqualTo(180_000_000);
        assertThat(t.resultEndSamples()).isEqualTo(8_640_000);
        assertThat(t.lengthCapped()).isTrue();
        assertThat(t.audioRuns()).singleElement().satisfies(r -> {
            assertThat(r.seqs()).containsExactly(1L);
            assertThat(r.captureSeq()).isNull();
        });
        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.capture_dropped"))
                .singleElement().satisfies(n -> assertThat(n.fields()).contains("reason=capped"));
    }

    /**
     * 끝이 앞 조각 m 안(설계 7.4 결과 끝 소유 4). A 5401장(V = 16,203,000틱 = 180.0333초) · B J. 180초 끝이 A 마지막
     * m(50ms) 안 → B 결과 시작(파일 머리 179.9833초) < 180 이라 B 는 남고 프레임이 없어 「소리만」. 배치표 B: zeroAt =
     * rnd(180,033,333.33) = 180,033,333 · from = rnd(179,983,333.33) − zeroAt = −50,000 · to = 180,000,000 − 180,033,333 =
     * −33,333.
     */
    @Test
    void 끝이_앞_조각_마지막_m_안에_들면_다음_J_조각은_소리만으로_남는다() {
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 100_000), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true)), Map.of(1L, standard(5_401), 2L, standard(4)), TRACK0);

        assertThat(t.videoCuts()).containsExactly(new VideoCut(1, 9_000, 16_206_001, -9_000));
        assertThat(t.audioRuns()).singleElement().satisfies(r -> assertThat(r.seqs()).containsExactly(1L, 2L));
        assertThat(t.placements()).last()
                .isEqualTo(new Placement(2, 180_033_333, -50_000, -33_333, 50_000));
    }

    /**
     * 물린 뒤 재상한(설계 7.4 결과 끝 소유 4). A 29.97fps(간격 3003) 5395장 · V = 16,198,182 = 마지막 PTS − D0 · B 끊긴
     * 이웃 c = 3003. 끝 B 0.1초 → 180초 상한. B 결과 시작 16,198,182틱 < 16,200,000 이라 남지만 첫 프레임 16,201,185 ≥ 상한이라 비고, 끊긴 이웃이라
     * 물린다 → 새 끝 = A 마지막 PTS + 간격 = 16,201,185틱(180.0132초) → 다시 180초 상한. A 마지막 프레임(결과
     * 16,198,182)은 남는다 → end_pts 16,207,183.
     */
    @Test
    void 끝을_물린_뒤에도_180초_상한을_다시_건다() {
        SegmentTiming a = timing(grid(0, 3_003, 5_395), 16_198_182, track(2_400, 10));
        SegmentTiming b = timing(grid(3_003, FRAME, 4), 12_000, track(2_400, 10));
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 100_000), List.of(1L, 2L)),
                List.of(media(1, false), media(2, false)), Map.of(1L, a, 2L, b), TRACK0);

        assertThat(t.videoCuts()).containsExactly(new VideoCut(1, 9_000, 16_207_183, -9_000));
        assertThat(t.lengthUs()).isEqualTo(180_000_000);
        assertThat(t.lengthCapped()).isTrue();
        assertThat(t.notes()).extracting(RenderTimeline.Note::event).contains("render.out_retreated");
        // B 의 x′ ≺ 100,000 프레임(33,366.67 · 66,700µs — 100,033.33 은 넘음) 2장이 물림으로 빠졌다(물림은 out_retreated
        // 몫이라 droppedSeqs 에는 없다)
        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.end_owned")).singleElement()
                .satisfies(n -> assertThat(n.fields())
                        .isEqualTo("endUs=180000000 droppedFrames=2 droppedSeqs=[] capped=true"));
    }

    // ── 실패 ──────────────────────────────────────────────────────────

    @Test
    void 조각끼리_영상_timescale_이_다르면_SOURCE_MISMATCH() {
        SegmentTiming other = timing(1_000, grid(0, 33, 4), 132, track(2_400, 10));

        assertThatThrownBy(() -> RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 66_667), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true)), Map.of(1L, standard(4), 2L, other), TRACK0))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH));
    }

    /** 단일 조각에서 끝이 첫 프레임 앞(x′ = 0 · ≺ 거짓)이면 자를 프레임이 없다. */
    @Test
    void 자를_프레임이_없으면_SOURCE_RANGE() {
        assertThatThrownBy(() -> single(OffsetOrigin.FILE_HEAD, 0, end(10, 50_000)))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_RANGE));
    }

    @Test
    void 고른_트랙이_조각에_없으면_SOURCE_MISSING() {
        assertThatThrownBy(() -> RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(10, 0), end(10, 100_000), List.of(10L)),
                List.of(media(10, false)), Map.of(10L, standard(4)), List.of(0, 1)))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISSING));
    }

    /**
     * 틱 정수식이 long 을 넘는 판독(c = 10¹³틱 → 2·10⁶·PTS 넘침). 다시 받아도 같은 조각이라 일시 실패(재시도)가 아니라
     * 종결이다(sec M1).
     */
    @Test
    void 시각_계산이_long_을_넘는_조각이면_SOURCE_MISMATCH_종결() {
        SegmentTiming huge = timing(grid(10_000_000_000_000L, FRAME, 4), 12_000, track(2_400, 10));

        assertPermanentMismatch(() -> RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(10, 0), end(10, 100_000), List.of(10L)),
                List.of(media(10, false)), Map.of(10L, huge), TRACK0));
    }

    /**
     * 이음 불변식이 깨지는 판독: A 의 PTS 가 V(2틱)보다 훨씬 넓게 퍼졌다(9000 · 39000). B 는 J 라 A 프레임 둘이 다 남고
     * (e − a = 30001) R_B − R_A = 2 라 {@code R_{j+1} − R_j ≥ e_j − a_j} 가 깨진다. 조각 꼴 탓이라 종결이다(sec M1).
     */
    @Test
    void 이음_불변식이_깨지는_조각이면_SOURCE_MISMATCH_종결() {
        SegmentTiming spread = timing(new long[] {9_000, 39_000}, 2, track(2_400, 10));

        assertPermanentMismatch(() -> RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 400_000), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true)), Map.of(1L, spread, 2L, standard(4)), TRACK0));
    }

    // ── 결과 끝 소유 신호(설계 15절 render.end_owned) ─────────────────────

    /**
     * 위 180초 사례의 신호. 끝 소유 앞에 남던 프레임 = A 5415 + B 3(x′ ≺ 100,000) = 5418 · 뒤 = A 5400(end_pts 16,206,001)
     * → droppedFrames 18 · B 는 결과 시작이 끝 이상이라 droppedSeqs [2] · endUs = lengthUs.
     */
    @Test
    void 결과_끝_소유_신호는_끝_µs_빠진_프레임_수_빠진_조각을_싣는다() {
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 100_000), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true), capture(4)),
                Map.of(1L, standard(5_415), 2L, standard(4), 4L, standard(4)), TRACK0);

        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.end_owned")).singleElement()
                .satisfies(n -> assertThat(n.fields())
                        .isEqualTo("endUs=180000000 droppedFrames=18 droppedSeqs=[2] capped=true"));
    }

    /** 끝이 앞 조각 m 안 사례: 조각은 안 빠지고(B 소리만) 프레임만 빠진다 — A 1장 · B 3장. */
    @Test
    void 조각이_안_빠져도_프레임이_빠지면_결과_끝_소유_신호를_남긴다() {
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 100_000), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true)), Map.of(1L, standard(5_401), 2L, standard(4)), TRACK0);

        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.end_owned")).singleElement()
                .satisfies(n -> assertThat(n.fields())
                        .isEqualTo("endUs=180000000 droppedFrames=4 droppedSeqs=[] capped=true"));
    }

    /** 끝이 out 조각 안에서 끝나면 끝 소유가 더 빼는 프레임이 없다 — 신호도 없다. */
    @Test
    void 끝_소유가_뺀_것이_없으면_결과_끝_소유_신호가_없다() {
        RenderTimeline t = single(OffsetOrigin.VIDEO_START, 33_333, end(10, 100_000));

        assertThat(t.notes()).extracting(RenderTimeline.Note::event).doesNotContain("render.end_owned");
    }

    // ── 확보 조각을 쓸지(설계 7.4 소리 묶음 8 · 1) ───────────────────────

    /**
     * 확보 조각을 쓸지는 결과 끝 소유를 마친 계획이 정한다 — 원래 out 조각이 마지막으로 남으면(J 사슬 끝 B 0.0667초) 내보내고,
     * 끝 조각이 물려 빠지면(끊긴 이웃 끝 x′ = 0) 내보내지 않는다. 확보 조각 판독 없이 정해진다.
     */
    @Test
    void 확보_조각을_쓸지는_끝_소유를_마친_계획이_판독_없이_정한다() {
        RenderTimeline.Draft used = RenderTimeline.draft(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 66_667), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true), capture(4)),
                Map.of(1L, standard(4), 2L, standard(4)), TRACK0);
        SegmentTiming shortV = timing(grid(0, FRAME, 4), 9_000, track(2_400, 10));
        RenderTimeline.Draft retreated = RenderTimeline.draft(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(3, 0), List.of(1L, 3L)),
                List.of(media(1, false), media(3, false), capture(4)),
                Map.of(1L, shortV, 3L, standard(4)), TRACK0);

        assertThat(used.capture()).hasValueSatisfying(c -> assertThat(c.seq()).isEqualTo(4L));
        assertThat(retreated.capture()).isEmpty();
        assertThat(retreated.finish(null).notes()).filteredOn(n -> n.event().equals("render.capture_dropped"))
                .singleElement().satisfies(n -> assertThat(n.fields()).isEqualTo("seq=4 reason=out_retreated"));
    }

    /** 쓰지 않을 확보 조각은 판독 값이 무엇이든(영상 시간 단위가 달라도) 결과를 짜는 데 걸리지 않는다. */
    @Test
    void 쓰지_않을_확보_조각은_시간_단위가_달라도_결과를_짠다() {
        SegmentTiming shortV = timing(grid(0, FRAME, 4), 9_000, track(2_400, 10));
        SegmentTiming otherTs = timing(45_000, grid(0, FRAME, 4), 12_000, track(2_400, 10));
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(3, 0), List.of(1L, 3L)),
                List.of(media(1, false), media(3, false), capture(4)),
                Map.of(1L, shortV, 3L, standard(4), 4L, otherTs), TRACK0);

        assertThat(t.audioRuns()).singleElement().satisfies(r -> assertThat(r.captureSeq()).isNull());
        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.capture_dropped"))
                .singleElement().satisfies(n -> assertThat(n.fields()).contains("reason=out_retreated"));
    }

    /** 쓸 확보 조각의 영상 시간 단위가 결과 조각과 다르면 종결이다(흐름 자리를 같은 틱으로 셀 수 없다). */
    @Test
    void 쓸_확보_조각의_시간_단위가_다르면_종결이다() {
        SegmentTiming otherTs = timing(45_000, grid(0, FRAME, 4), 12_000, track(2_400, 10));
        RenderTimeline.Draft draft = RenderTimeline.draft(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 66_667), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true), capture(4)),
                Map.of(1L, standard(4), 2L, standard(4)), TRACK0);

        assertPermanentMismatch(() -> draft.finish(otherTs));
    }

    /** 계획이 쓰겠다고 한 확보 조각의 판독을 빼고 마치거나, 쓰지 않는다는데 넘기면 부른 쪽 결함이다. */
    @Test
    void 확보_조각_판독은_계획이_쓸_때만_넘긴다() {
        RenderTimeline.Draft used = RenderTimeline.draft(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 66_667), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true), capture(4)),
                Map.of(1L, standard(4), 2L, standard(4)), TRACK0);
        RenderTimeline.Draft none = RenderTimeline.draft(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 66_667), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true)), Map.of(1L, standard(4), 2L, standard(4)), TRACK0);

        assertThatThrownBy(() -> used.finish(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> none.finish(standard(4))).isInstanceOf(IllegalArgumentException.class);
    }

    // ── 도우미 ────────────────────────────────────────────────────────

    private static void assertPermanentMismatch(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RenderFailure.class, f -> {
            assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH);
            assertThat(f.retryable()).isFalse();
        });
    }

    private static RenderTimeline single(OffsetOrigin origin, long inUs, EndBoundary out) {
        return RenderTimeline.of(plan(origin, at(10, inUs), out, List.of(10L)), List.of(media(10, false)),
                Map.of(10L, standard(4)), TRACK0);
    }

    /** A(1) · B(2) 기본 조각 J. 시작 A 0(VIDEO_START). */
    static RenderTimeline jChain(EndBoundary out, boolean withCapture) {
        return RenderTimeline.of(plan(OffsetOrigin.VIDEO_START, at(1, 0), out, List.of(1L, 2L)),
                withCapture ? List.of(media(1, false), media(2, true), capture(4))
                        : List.of(media(1, false), media(2, true)),
                Map.of(1L, standard(4), 2L, standard(4), 4L, standard(4)), TRACK0);
    }

    /** A(1) V = 9000(ra 꼴) · C(3) 끊긴 이웃. 시작 A 0(VIDEO_START). */
    static RenderTimeline cut(EndBoundary out) {
        SegmentTiming shortV = timing(grid(0, FRAME, 4), 9_000, track(2_400, 10));
        return RenderTimeline.of(plan(OffsetOrigin.VIDEO_START, at(1, 0), out, List.of(1L, 3L)),
                List.of(media(1, false), media(3, false)), Map.of(1L, shortV, 3L, standard(4)), TRACK0);
    }

    /** 이음 불변식 {@code R_{j+1} − R_j ≥ e_j − a_j > 0}(설계 7.5 · 문서 리뷰 이월 L2). */
    static void assertJoinInvariant(RenderTimeline t) {
        List<VideoCut> cuts = t.videoCuts();
        assertThat(cuts.getFirst().resultTicks()).isZero();
        for (int j = 0; j + 1 < cuts.size(); j++) {
            VideoCut a = cuts.get(j);
            VideoCut b = cuts.get(j + 1);
            assertThat(a.endPts() - a.startPts()).isPositive();
            assertThat(b.resultTicks() - a.resultTicks()).isGreaterThanOrEqualTo(a.endPts() - a.startPts());
        }
    }
}
