package com.pokeclip.render.thumbnail;

import com.pokeclip.render.job.OffsetOrigin;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.media.SegmentTiming.ColorTags;
import com.pokeclip.render.media.SegmentTiming.TrackRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 찍을 자리(PDT 설계 r7 8.1). 카드 · 라이브는 {@code (원점 + offsetMs) − st}(원점 = {@code VIDEO_START} 면 영상 첫 DTS
 * {@code D0} · {@code FILE_HEAD} 면 파일 머리 {@code Hd}), 완성 영상은 {@code offsetMs} 그대로다. 셋 모두 {@code [0, P_last]} 로
 * 자른다 — {@code P_last} = 영상 PTS 최댓값 − {@code st}(ms 로 내림). 기대값은 이 정의에서 손으로 셌다.
 */
class ThumbnailSeekTest {

    private static final long TS = 90_000;
    private static final long FRAME = 3_000;

    /**
     * 녹화 조각 꼴(seg_1 실측 판독과 같은 수): 소리가 파일 머리 0 · 영상 첫 DTS 4800틱(53.333ms) · 120프레임 · B 프레임 없음.
     * {@code st = Hd = 0}. 마지막 PTS = 4800 + 119 × 3000 = 361800틱 = 4020ms.
     */
    private static SegmentTiming 녹화_조각() {
        return timing(4_800, 0, 0, 0, 120);
    }

    /**
     * 영상이 머리인 B 프레임 조각: 영상 첫 DTS 0 · 첫 PTS 6000틱(66.667ms — 구성 시차) · 소리는 100ms 부터. ffmpeg 입력 시각 0
     * {@code st} = 스트림 시작(PTS) 최솟값 = 66.667ms, 파일 머리 {@code Hd} = 첫 DTS 최솟값 = 0.
     */
    private static SegmentTiming 영상_머리_B_프레임_조각() {
        return timing(0, 6_000, 0, 66_667, 120);
    }

    /** {@code d0} · PTS 앞섬(첫 PTS − d0) · Hd · st(µs) · 프레임 수로 판독 값을 짠다. PTS 는 첫 PTS 부터 3000틱 간격이다. */
    private static SegmentTiming timing(long d0, long ptsLead, long headUs, long startUs, int frames) {
        long[] pts = new long[frames];
        for (int i = 0; i < frames; i++) {
            pts[i] = d0 + ptsLead + i * FRAME;
        }
        long lengthTicks = frames * FRAME;
        return new SegmentTiming(TS, d0, headUs, pts, lengthTicks, lengthTicks * 1_000_000 / TS,
                List.of(new TrackRange(0, 48_000, 0, 192_000, 188)), startUs, d0 * 1_000_000 / TS - headUs, lengthTicks,
                FRAME, 640, 360, "yuv420p", ColorTags.UNKNOWN);
    }

    private static ThumbnailJob job(String kind, long offsetMs, OffsetOrigin origin) {
        return new ThumbnailJob(kind, "7", Instant.parse("2026-10-04T10:00:00Z"), "segments-test", "seg/1.m4s",
                offsetMs, origin, "clips-test", "thumbnails/" + kind + "/7.jpg");
    }

    private static ThumbnailProcessor.Seek seek(ThumbnailJob job, SegmentTiming timing) {
        return ThumbnailProcessor.Seek.of(job, timing);
    }

    // ── card · live ─────────────────────────────────────────────────────

    /** 평시(소리가 머리 · Hd = st) 파일 머리 원점은 offsetMs 그대로 — 원점 칸 없는 지금 주문과 같은 자리다. */
    @Test
    void 카드_파일_머리_원점은_평시에_offsetMs_그대로다() {
        assertThat(seek(job("card", 1_000, OffsetOrigin.FILE_HEAD), 녹화_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(1_000, false));
        assertThat(seek(job("card", 0, OffsetOrigin.FILE_HEAD), 녹화_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(0, false));
    }

    /** 영상 첫 DTS 원점(판 2)은 D0 − st = 53.333ms 만큼 뒤 — ms 로 반올림해 53. */
    @Test
    void 카드_영상_시작_원점은_영상_첫_DTS_만큼_뒤다() {
        assertThat(seek(job("card", 1_000, OffsetOrigin.VIDEO_START), 녹화_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(1_053, false));
        assertThat(seek(job("card", 0, OffsetOrigin.VIDEO_START), 녹화_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(53, false));
    }

    /** 파일 머리와 입력 시각 0 이 µs 반올림 차로 1µs 갈려도 ms 자리는 같다 — 내리면 999 가 되어 지금 주문과 갈린다. */
    @Test
    void 반올림_차_1µs_는_자리를_바꾸지_않는다() {
        assertThat(seek(job("card", 1_000, OffsetOrigin.FILE_HEAD), timing(4_800, 0, 0, 1, 120)))
                .isEqualTo(new ThumbnailProcessor.Seek(1_000, false));
    }

    /** 라이브도 카드와 같은 식이다. */
    @Test
    void 라이브도_원점_식을_쓴다() {
        assertThat(seek(job("live", 2_000, OffsetOrigin.VIDEO_START), 녹화_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(2_053, false));
        assertThat(seek(job("live", 2_000, OffsetOrigin.FILE_HEAD), 영상_머리_B_프레임_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(1_933, false));
    }

    /**
     * 영상이 머리인 B 프레임 조각은 ffmpeg 입력 시각 0(st = 첫 PTS)이 파일 머리(첫 DTS)보다 66.667ms 늦다. 두 원점 모두 0(= D0 = Hd)
     * 기준 자리에서 st 를 빼 1000 → 933.333 → 933. 0 이면 −66.667 이라 0 으로 자른다.
     */
    @Test
    void 입력_시각_0_이_머리보다_늦으면_그만큼_앞당기고_0_아래는_자른다() {
        assertThat(seek(job("card", 1_000, OffsetOrigin.FILE_HEAD), 영상_머리_B_프레임_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(933, false));
        assertThat(seek(job("card", 1_000, OffsetOrigin.VIDEO_START), 영상_머리_B_프레임_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(933, false));
        assertThat(seek(job("card", 0, OffsetOrigin.FILE_HEAD), 영상_머리_B_프레임_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(0, true));
    }

    /** 끝을 넘으면 마지막 표시 프레임 시작(P_last = 361800틱 − 0 = 4020ms)으로 자른다. */
    @Test
    void 카드가_끝을_넘으면_마지막_표시_프레임에서_자른다() {
        assertThat(seek(job("card", 9_000, OffsetOrigin.FILE_HEAD), 녹화_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(4_020, true));
        assertThat(seek(job("card", 4_000, OffsetOrigin.VIDEO_START), 녹화_조각()))
                .as("4000 + 53 = 4053 > 4020").isEqualTo(new ThumbnailProcessor.Seek(4_020, true));
        assertThat(seek(job("card", 4_020, OffsetOrigin.FILE_HEAD), 녹화_조각()))
                .as("딱 P_last 는 자르지 않음").isEqualTo(new ThumbnailProcessor.Seek(4_020, false));
    }

    /** P_last 도 st 기준이다 — B 프레임 영상 머리 조각의 마지막 PTS 6000 + 119 × 3000 = 363000틱 = 4033.333ms − 66.667 = 3966.667 → 3966. */
    @Test
    void 마지막_표시_프레임도_입력_시각_0_기준으로_내린다() {
        assertThat(seek(job("card", 9_000, OffsetOrigin.FILE_HEAD), 영상_머리_B_프레임_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(3_966, true));
    }

    // ── clip ────────────────────────────────────────────────────────────

    /** 완성 영상은 결과 파일 안 자리라 원점을 더하지 않는다 — 원점 칸이 와도 쓰지 않는다. */
    @Test
    void 완성_영상은_offsetMs_그대로이고_원점을_쓰지_않는다() {
        assertThat(seek(job("clip", 1_000, OffsetOrigin.FILE_HEAD), 녹화_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(1_000, false));
        assertThat(seek(job("clip", 1_000, OffsetOrigin.VIDEO_START), 녹화_조각()))
                .isEqualTo(new ThumbnailProcessor.Seek(1_000, false));
    }

    /**
     * 완성 영상의 끝도 P_last 로 자른다 — 30fps 90프레임 결과(첫 PTS 0)의 마지막 PTS 267000틱 = 2966.667ms → 내려 2966. 올려
     * 2967 로 두면 마지막 프레임보다 뒤라 ffmpeg 가 그림을 내지 못한다.
     */
    @Test
    void 완성_영상이_끝을_넘으면_마지막_표시_프레임으로_내려_자른다() {
        SegmentTiming result = timing(0, 0, 0, 0, 90);

        assertThat(seek(job("clip", 3_000, OffsetOrigin.FILE_HEAD), result))
                .isEqualTo(new ThumbnailProcessor.Seek(2_966, true));
    }
}
