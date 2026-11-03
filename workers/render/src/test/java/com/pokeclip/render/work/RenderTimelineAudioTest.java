package com.pokeclip.render.work;

import com.pokeclip.render.job.OffsetOrigin;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.work.AudioRun.TrackStart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.pokeclip.render.work.RenderTimelineTest.jChain;
import static com.pokeclip.render.work.TimelineFixtures.FRAME;
import static com.pokeclip.render.work.TimelineFixtures.at;
import static com.pokeclip.render.work.TimelineFixtures.end;
import static com.pokeclip.render.work.TimelineFixtures.grid;
import static com.pokeclip.render.work.TimelineFixtures.media;
import static com.pokeclip.render.work.TimelineFixtures.plan;
import static com.pokeclip.render.work.TimelineFixtures.prime;
import static com.pokeclip.render.work.TimelineFixtures.standard;
import static com.pokeclip.render.work.TimelineFixtures.timing;
import static com.pokeclip.render.work.TimelineFixtures.track;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RenderTimeline} 의 소리 묶음(설계 7.4 소리 묶음 1–8). 흐름 시각은 파일 j 의 파일 안 시각 t → {@code S_j + (t − st_j)}
 * (S = 앞 이음 간격 합 · µs 반올림) · 간격 = 영상 기준 {@code σ_i + V_i − σ_{i+1}}(σ = D0 − st). 기본 조각은 σ = 0.1 − 0.05
 * = 0.05초 · V = 0.1333초다. 표본은 48kHz — 자를 구간 끝점은 반올림, 결과 자리는 내림.
 */
class RenderTimelineAudioTest {

    /**
     * 단일 조각 · f_in = 12000(0.1333초) · E = 0.0666667초. 시작 = f_in − st = 0.08333초 → 4000표본 · 끝 = E − Δ − st =
     * 0.0666667 + 0.1333333 − 0.05 = 0.15초 → 7200 · 결과 [0, 3200).
     */
    @Test
    void 단일_조각_묶음은_f_in_자리부터_결과_끝_자리까지_표본으로_자른다() {
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(10, 33_333), end(10, 100_000), List.of(10L)),
                List.of(media(10, false)), Map.of(10L, standard(4)), List.of(0));

        assertThat(t.audioRuns()).containsExactly(new AudioRun(0, null, List.of(10L), null, List.of(), 4_000, 7_200,
                List.of(new TrackStart(0, false, 0)), 0, 3_200));
    }

    /**
     * A · B J 는 묶음 하나. 간격 = 0.05 + 0.13333 − 0.05 = 0.13333초 → 133,333µs. 시작 = 0.1 − 0.05 = 0.05초 → 2400 ·
     * 끝 = E(0.20000033) + 0.1 − 0.05 = 0.25000033초 → 12000 · 결과 [0, 9600). A 소리 끝을 0.15초로 두어, 간격을 소리 끝
     * 기준으로 잡으면(지금 코드 {@code spacing}) 150,000µs 로 갈린다(P29).
     */
    @Test
    void 같은_녹화_조각들은_영상_기준_간격으로_한_묶음에_잇는다() {
        SegmentTiming a = timing(grid(0, FRAME, 4), 12_000, track(2_400, 2_400 + 7_200, 10));
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 66_667), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true)), Map.of(1L, a, 2L, standard(4)), List.of(0));

        assertThat(t.audioRuns()).containsExactly(new AudioRun(0, null, List.of(1L, 2L), null, List.of(133_333L),
                2_400, 12_000, List.of(new TrackStart(0, false, 0)), 0, 9_600));
    }

    /**
     * 끊긴 이웃 C 앞에서 묶음이 갈린다. 첫 묶음 끝 = A 영상 DTS 끝 σ + V = 0.05 + 0.1 = 0.15초 → 7200. 둘째 묶음 시작 =
     * C 의 D0 자리 σ = 0.05초 → 2400(재접속 뒤 영상 앞 소리 m 은 버림) · 결과 자리 floor(9000/90000 × 48000) = 4800 ·
     * 끝 = E(0.16666697) − 0 − 0.05 = 0.11666697초 → 5600 · 결과 끝 floor(0.16666697 × 48000) = 8000. 트랙 1 은 C 에서
     * 0.11초(파일 안)에 시작해 C 묶음 시작(0.05초 + st)보다 0.01초 늦다 → 앞 무음 480표본.
     */
    @Test
    void 끊긴_이웃_뒤_묶음은_D0_자리부터이고_늦게_시작한_트랙은_앞_무음을_표본으로_적는다() {
        SegmentTiming a = timing(grid(0, FRAME, 4), 9_000, track(2_400, 10), track(2_400, 10));
        SegmentTiming c = timing(grid(0, FRAME, 4), 12_000, track(2_400, 10), track(5_280, 10));
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(3, 66_667), List.of(1L, 3L)),
                List.of(media(1, false), media(3, false)), Map.of(1L, a, 3L, c), List.of(0, 1));

        assertThat(t.audioRuns()).containsExactly(
                new AudioRun(0, null, List.of(1L), null, List.of(), 2_400, 7_200,
                        List.of(new TrackStart(0, false, 0), new TrackStart(1, false, 0)), 0, 4_800),
                new AudioRun(1, null, List.of(3L), null, List.of(), 2_400, 5_600,
                        List.of(new TrackStart(0, false, 0), new TrackStart(1, false, 480)), 4_800, 8_000));
        // 설계 15절 render.audio_run 의 frontSilence 칸 = 고른 트랙 순서의 앞 무음 표본
        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.audio_run"))
                .extracting(RenderTimeline.Note::fields)
                .containsExactly(
                        "run=0 seqs=[1] prime=null capture=null fromSample=2400 toSample=7200 frontSilence=[0, 0]"
                                + " resultStartSample=0",
                        "run=1 seqs=[3] prime=null capture=null fromSample=2400 toSample=5600 frontSilence=[0, 480]"
                                + " resultStartSample=4800");
        // render.audio_pad: 트랙 1 의 C 앞 무음 480표본 = 10ms · 소리는 C 끝(흐름 0.26초)까지 있어 끝 무음 0
        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.audio_pad"))
                .extracting(RenderTimeline.Note::fields)
                .containsExactly("run=1 track=1 frontMs=10 backMs=0");
    }

    /**
     * 트랙 소리가 자를 구간 끝보다 먼저 끝나면 그 차가 끝 무음({@code apad})이다. 단일 조각 [4000, 7200) · 트랙 끝 PTS 8400
     * → 흐름 0.175 − 0.05 = 0.125초 = 6000표본 → 소리 2000표본 · 묶음 길이 3200 → 끝 무음 1200표본 = 25ms.
     */
    @Test
    void 트랙_소리가_자를_구간보다_먼저_끝나면_끝_무음을_신호로_남긴다() {
        SegmentTiming early = timing(grid(0, FRAME, 4), 12_000, track(2_400, 8_400, 10));
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(10, 33_333), end(10, 100_000), List.of(10L)),
                List.of(media(10, false)), Map.of(10L, early), List.of(0));

        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.audio_pad"))
                .extracting(RenderTimeline.Note::fields)
                .containsExactly("run=0 track=0 frontMs=0 backMs=25");
    }

    /** 앞 · 끝 무음이 모두 1ms 미만이면 신호를 내지 않는다(묶음 · 트랙마다 줄이 쌓이지 않게). */
    @Test
    void 앞_끝_무음이_없으면_무음_신호가_없다() {
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(10, 33_333), end(10, 100_000), List.of(10L)),
                List.of(media(10, false)), Map.of(10L, standard(4)), List.of(0));

        assertThat(t.notes()).extracting(RenderTimeline.Note::event).doesNotContain("render.audio_pad");
    }

    /**
     * 마지막 묶음 끝에 확보 조각을 붙인다. 파일 [1, 2, 4] · 흐름 자리 0 · 133,333.33 · 266,666.67µs → 간격 133,333 ·
     * 133,334. 자를 구간은 확보 없이와 같다(2400 · 12000).
     */
    @Test
    void 원래_out_조각이_마지막이면_확보_조각을_마지막_묶음_끝에_붙인다() {
        RenderTimeline t = jChain(end(2, 66_667), true);

        AudioRun run = t.audioRuns().getFirst();
        assertThat(run.captureSeq()).isEqualTo(4L);
        assertThat(run.fileSeqs()).containsExactly(1L, 2L, 4L);
        assertThat(run.concatDurationsUs()).containsExactly(133_333L, 133_334L);
        assertThat(run.fromSample()).isEqualTo(2_400);
        assertThat(run.toSample()).isEqualTo(12_000);
    }

    /**
     * 묶음 머리 앞 준비 조각. 파일 [0, 1] · 머리 1 의 흐름 자리 = rnd(133,333.33) = 133,333µs. 시작 = 0.133333 + 0.1 −
     * 0.05 = 0.183333초 → 8799.98 → 8800 · 끝 = E(0.06666700) − Δ(0) + 0.133333 − 0.05 = 0.25초 → 12000 · 결과 3200.
     */
    @Test
    void 준비_조각은_그_머리_묶음_맨_앞에만_넣고_자를_구간_앞에_둔다() {
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(1, 66_667), List.of(1L)),
                List.of(prime(0, 1), media(1, false)), Map.of(0L, standard(4), 1L, standard(4)), List.of(0));

        assertThat(t.audioRuns()).containsExactly(new AudioRun(0, 0L, List.of(1L), null, List.of(133_333L), 8_800,
                12_000, List.of(new TrackStart(0, false, 0)), 0, 3_200));
        assertThat(t.videoCuts()).extracting(RenderTimeline.VideoCut::seq).containsExactly(1L);
        assertThat(t.placements()).extracting(RenderTimeline.Placement::seq).containsExactly(1L);
    }

    /**
     * 둘째 묶음 머리(끊긴 이웃 C)도 같은 규칙으로 준비 조각을 쓴다(설계 7.4 소리 묶음 7). 파일 [2, 3] · C 흐름 자리 133,333µs. 시작 =
     * 0.133333 + σ(0.05) → 8800 · 끝 = E(0.16666697) − 0 + 0.133333 − 0.05 = 0.25000030초 → 12000 · 결과 [4800, 8000).
     */
    @Test
    void 둘째_묶음_머리도_준비_조각을_쓴다() {
        SegmentTiming a = timing(grid(0, FRAME, 4), 9_000, track(2_400, 10));
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(3, 66_667), List.of(1L, 3L)),
                List.of(media(1, false), prime(2, 3), media(3, false)),
                Map.of(1L, a, 2L, standard(4), 3L, standard(4)), List.of(0));

        assertThat(t.audioRuns()).last().isEqualTo(new AudioRun(1, 2L, List.of(3L), null, List.of(133_333L), 8_800,
                12_000, List.of(new TrackStart(0, false, 0)), 4_800, 8_000));
    }

    /** 고른 트랙이 묶음 모든 조각에서 패킷 0 이면 그 트랙은 무음 대체(anullsrc · 설계 7.4 소리 묶음 6). */
    @Test
    void 묶음_전체에_트랙_패킷이_없으면_그_트랙은_무음_대체로_표시한다() {
        SegmentTiming a = timing(grid(0, FRAME, 4), 12_000, track(2_400, 10), track(0, 0));
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 66_667), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true)), Map.of(1L, a, 2L, a), List.of(0, 1));

        assertThat(t.audioRuns().getFirst().tracks())
                .containsExactly(new TrackStart(0, false, 0), new TrackStart(1, true, 0));
        assertThat(t.notes()).extracting(RenderTimeline.Note::event).contains("render.track_silent");
    }
}
