package com.pokeclip.render.media;

import com.pokeclip.render.media.SegmentTiming.ColorTags;
import com.pokeclip.render.media.SegmentTiming.TrackRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 판독 값 묶음 자체(PDT 설계 r7 3.3 · 7.2). 판독은 {@link MediaProbeTimingTest}. */
class SegmentTimingTest {

    private static final TrackRange MONO = new TrackRange(0, 48_000, 0, 1_024, 1, 1, "aac", 48_000, 1, "SHA256:aa");

    @Test
    void 표시_순_PTS_배열은_밖에서_바꿔도_안이_바뀌지_않는다() {
        long[] pts = {0, 3_000, 6_000};
        SegmentTiming t = timing(pts);

        pts[0] = 99;
        t.videoPtsSorted()[1] = 99;

        assertThat(t.videoPtsSorted()).containsExactly(0, 3_000, 6_000);
    }

    @Test
    void 프레임_수와_마지막_PTS_를_낸다() {
        SegmentTiming t = timing(new long[] {100, 3_100, 6_100});

        assertThat(t.frameCount()).isEqualTo(3);
        assertThat(t.lastPts()).isEqualTo(6_100);
    }

    @Test
    void 같은_소리_설정은_코덱_표본율_채널_수_코덱_설정_바이트가_모두_같을_때다() {
        assertThat(MONO.sameConfig(new TrackRange(3, 48_000, 512, 9_000, 5, 4, "aac", 48_000, 1, "SHA256:aa")))
                .isTrue();
        assertThat(MONO.sameConfig(new TrackRange(0, 48_000, 0, 1_024, 1, 1, "opus", 48_000, 1, "SHA256:aa")))
                .isFalse();
        assertThat(MONO.sameConfig(new TrackRange(0, 44_100, 0, 1_024, 1, 1, "aac", 44_100, 1, "SHA256:aa")))
                .isFalse();
        assertThat(MONO.sameConfig(new TrackRange(0, 48_000, 0, 1_024, 1, 1, "aac", 48_000, 2, "SHA256:aa")))
                .isFalse();
        assertThat(MONO.sameConfig(new TrackRange(0, 48_000, 0, 1_024, 1, 1, "aac", 48_000, 1, "SHA256:bb")))
                .isFalse();
    }

    @Test
    void 코덱_설정_바이트가_없는_트랙끼리는_같지_않다고_본다() {
        TrackRange a = new TrackRange(0, 48_000, 0, 1_024, 1, 1, "aac", 48_000, 1, null);

        assertThat(a.sameConfig(a)).isFalse();
    }

    @Test
    void 설계_꼴_다섯_칸_트랙은_설정을_모른다() {
        TrackRange t = new TrackRange(2, 48_000, 0, 2_048, 2);

        assertThat(t.streamIndex()).isEqualTo(-1);
        assertThat(t.sampleRate()).isEqualTo(48_000);
        assertThat(t.sameConfig(t)).isFalse();
    }

    @Test
    void 설계_꼴_일곱_칸으로_만들면_나머지는_판독_정의에서_낸다() {
        // st = Hd · m = D0/ts − Hd · 패킷 길이 합 = V · 간격 = PTS 간격 중앙값 · 화면 · 색은 모름
        SegmentTiming t = new SegmentTiming(90_000, 19_919, 0, new long[] {19_919, 22_919, 25_919}, 9_000,
                4_221_322, List.of(MONO));

        assertThat(t.formatStartUs()).isEqualTo(0);
        assertThat(t.headLeadUs()).isEqualTo(221_322);
        assertThat(t.videoPacketTicks()).isEqualTo(9_000);
        assertThat(t.frameIntervalTicks()).isEqualTo(3_000);
        assertThat(t.width()).isZero();
        assertThat(t.color()).isEqualTo(ColorTags.UNKNOWN);
    }

    /**
     * 간격이 고르지 않은(가변 프레임률) 조각의 프레임 간격은 가장 짧은 간격이 아니라 이웃 PTS 간격의 중앙값이다 — 짝수 개면 위쪽
     * 가운데. {@code toSegmentEnd} 끝(마지막 PTS + 간격)이 이 값을 쓴다(설계 7.3 4).
     */
    @Test
    void 프레임_간격은_고르지_않은_이웃_PTS_간격의_중앙값이다() {
        // 간격 3000 · 1000 · 3000 · 3000 → 정렬 1000 · 3000 · 3000 · 3000 → 3000(최솟값 1000)
        long[] fourGaps = {0, 3_000, 4_000, 7_000, 10_000};
        // 간격 1000 · 2000 · 4000 · 5000 · 6000 · 7000(여섯 개) → 위쪽 가운데 5000
        long[] sixGaps = {0, 1_000, 3_000, 7_000, 12_000, 18_000, 25_000};

        assertThat(SegmentTiming.medianInterval(fourGaps)).isEqualTo(3_000);
        assertThat(SegmentTiming.medianInterval(sixGaps)).isEqualTo(5_000);
        assertThat(new SegmentTiming(90_000, 0, 0, fourGaps, 13_000, 0, List.of(MONO)).frameIntervalTicks())
                .isEqualTo(3_000);
    }

    private static SegmentTiming timing(long[] pts) {
        return new SegmentTiming(90_000, pts[0], 0, pts, 9_000, 100_000, List.of(MONO), 0, 0, 9_000, 3_000, 640, 360,
                "yuv420p", ColorTags.UNKNOWN);
    }
}
