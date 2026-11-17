package com.pokeclip.render.work;

import com.pokeclip.render.job.EndBoundary;
import com.pokeclip.render.job.MediaCoord;
import com.pokeclip.render.job.OffsetOrigin;
import com.pokeclip.render.job.RenderPlan;
import com.pokeclip.render.job.SourceRef;
import com.pokeclip.render.job.SourceRole;
import com.pokeclip.render.job.SourceSegment;
import com.pokeclip.render.job.SubtitleSpan;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.media.SegmentTiming.TrackRange;

import java.util.List;
import java.util.stream.LongStream;

/**
 * {@link RenderTimeline} 시험의 손 계산용 조각 · 주문 모양. 기대값은 시험마다 PDT 설계 r7 7.3 · 7.4 · 7.6 산식으로 손으로 적는다.
 *
 * <p>기본 조각: 영상 time_base 1/{@value #TS} · 첫 DTS {@value #D0}틱(100,000µs) · 프레임 간격 3000틱(30fps) · 첫 PTS = D0
 * (c = 0) · 파일 머리 {@value #HEAD_US}µs(m = 50,000µs) · 파일 {@code format.start_time} 도 {@value #HEAD_US}µs. 판독 레코드의
 * 설계 3.3 일곱 칸 생성자가 st = Hd 로 두고 m · 간격 중앙값도 낸다. 소리 트랙은 1/48000 · 첫 PTS 2400(= 0.05초 = 파일 머리).
 */
final class TimelineFixtures {

    static final long TS = 90_000;
    static final long D0 = 9_000;
    static final long FRAME = 3_000;
    static final long HEAD_US = 50_000;
    static final long AUDIO_FIRST = 2_400;

    private TimelineFixtures() {
    }

    /** {@code D0 + c + interval·k}(k = 0..frames−1) PTS 열. */
    static long[] grid(long c, long interval, int frames) {
        return LongStream.range(0, frames).map(k -> D0 + c + interval * k).toArray();
    }

    /** 기본 조각(프레임 {@code frames} 장 · V = frames × 3000). */
    static SegmentTiming standard(int frames) {
        return timing(grid(0, FRAME, frames), frames * FRAME, track(AUDIO_FIRST, 10));
    }

    static SegmentTiming timing(long[] pts, long videoLengthTicks, TrackRange... audio) {
        return timing(TS, pts, videoLengthTicks, audio);
    }

    /**
     * 시험이 SegmentTiming 생성자를 부르는 유일한 자리. ② 가 레코드 꼴을 바꾸면 여기만 고친다.
     */
    static SegmentTiming timing(long ts, long[] pts, long videoLengthTicks, TrackRange... audio) {
        return new SegmentTiming(ts, D0, HEAD_US, pts, videoLengthTicks, 0, List.of(audio));
    }

    static TrackRange track(long firstPts, long packets) {
        return new TrackRange(0, 48_000, firstPts, firstPts + 9_600, packets);
    }

    static TrackRange track(long firstPts, long endPts, long packets) {
        return new TrackRange(0, 48_000, firstPts, endPts, packets);
    }

    /** 결과에 드는 조각. {@code joins} 면 앞 매체와 같은 녹화로 이어진다(J). */
    static SourceSegment media(long seq, boolean joins) {
        return new SourceSegment("b", "k" + seq, new SourceRef(seq, SourceRole.SEGMENT, null), null, 4_000, false,
                joins);
    }

    static SourceSegment prime(long seq, long owner) {
        return new SourceSegment("b", "k" + seq, new SourceRef(seq, SourceRole.AUDIO_PRIME, owner), null, 4_000, false,
                false);
    }

    static SourceSegment capture(long seq) {
        return new SourceSegment("b", "k" + seq, new SourceRef(seq, SourceRole.AUDIO_CAPTURE, null), null, 4_000,
                false, true);
    }

    static RenderPlan plan(OffsetOrigin origin, MediaCoord in, EndBoundary out, List<Long> mediaSeqs,
                           List<SubtitleSpan> subtitles) {
        return new RenderPlan(1, origin, in, out, mediaSeqs, subtitles, 1_000);
    }

    static RenderPlan plan(OffsetOrigin origin, MediaCoord in, EndBoundary out, List<Long> mediaSeqs) {
        return plan(origin, in, out, mediaSeqs, List.of());
    }

    static MediaCoord at(long seq, long offsetUs) {
        return new MediaCoord(seq, offsetUs);
    }

    static EndBoundary end(long seq, long offsetUs) {
        return new EndBoundary(seq, offsetUs, false);
    }
}
