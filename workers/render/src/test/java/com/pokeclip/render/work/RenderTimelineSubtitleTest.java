package com.pokeclip.render.work;

import com.pokeclip.render.job.EndBoundary;
import com.pokeclip.render.job.OffsetOrigin;
import com.pokeclip.render.job.SubtitlePoint;
import com.pokeclip.render.job.SubtitlePoint.AtMedia;
import com.pokeclip.render.job.SubtitlePoint.InGap;
import com.pokeclip.render.job.SubtitleSpan;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.work.RenderTimeline.PlacedSubtitle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.pokeclip.render.work.TimelineFixtures.FRAME;
import static com.pokeclip.render.work.TimelineFixtures.at;
import static com.pokeclip.render.work.TimelineFixtures.end;
import static com.pokeclip.render.work.TimelineFixtures.grid;
import static com.pokeclip.render.work.TimelineFixtures.media;
import static com.pokeclip.render.work.TimelineFixtures.plan;
import static com.pokeclip.render.work.TimelineFixtures.standard;
import static com.pokeclip.render.work.TimelineFixtures.timing;
import static com.pokeclip.render.work.TimelineFixtures.track;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자막 끝점 → 결과 µs(설계 7.6): 매체 자리는 {@code zeroAtUs + clamp(x′, fromUs, toUs)} · 틈 자리는 afterSeq 다음 조각의
 * 결과 시작 · 결과 끝 소유로 빠진 조각은 가까운 남은 경계 · {@code [0, lengthUs]} 로 자르고 0 이하 길이는 뺀다.
 *
 * <p>A(1) · C(3) 끊긴 이웃 사례(VIDEO_START)의 배치표: A = zeroAt 0 · [0, 100,000] · C = zeroAt 100,000 · [0, 66,667] ·
 * lengthUs 166,667(RenderTimelineTest 의 표시 소유 시험과 같은 사례).
 */
class RenderTimelineSubtitleTest {

    @Test
    void 매체_자리는_그_조각_배치에_오프셋을_더하고_조각_몫으로_자른다() {
        RenderTimeline t = cut(List.of());

        assertThat(t.resultUsOf(new AtMedia(at(1, 50_000)))).isEqualTo(50_000);
        assertThat(t.resultUsOf(new AtMedia(at(1, 150_000)))).isEqualTo(100_000);
        assertThat(t.resultUsOf(new AtMedia(at(3, 20_000)))).isEqualTo(120_000);
        assertThat(t.resultUsOf(new AtMedia(at(3, 900_000)))).isEqualTo(166_667);
    }

    /** 틈은 이어 붙어 사라진다 — 틈 안 자리는 뒤 조각의 결과 시작. 첫 매체 앞 틈은 0 · 마지막 뒤는 lengthUs. */
    @Test
    void 틈_자리는_다음_조각의_결과_시작이다() {
        RenderTimeline t = cut(List.of());

        assertThat(t.resultUsOf(new InGap(1, 500))).isEqualTo(100_000);
        assertThat(t.resultUsOf(new InGap(0, 500))).isEqualTo(0);
        assertThat(t.resultUsOf(new InGap(3, 500))).isEqualTo(166_667);
    }

    /** 설계 5.1 꼴: 틈을 걸친 자막은 남고, 두 끝이 같은 틈인 자막은 길이 0 이라 빠진다(계약6 100행). */
    @Test
    void 길이_0_이하_자막은_빼고_나머지는_결과_µs로_옮긴다() {
        RenderTimeline t = cut(List.of(
                new SubtitleSpan(0, point(1, 50_000), new InGap(1, 500)),
                new SubtitleSpan(1, new InGap(1, 500), new InGap(1, 1_500)),
                new SubtitleSpan(2, point(3, 20_000), point(3, 900_000))));

        assertThat(t.subtitles()).containsExactly(
                new PlacedSubtitle(0, 50_000, 100_000),
                new PlacedSubtitle(2, 120_000, 166_667));
        assertThat(t.notes()).filteredOn(n -> n.event().equals("render.subtitle_dropped"))
                .singleElement().satisfies(n -> assertThat(n.fields()).contains("index=1"));
    }

    /**
     * FILE_HEAD 계획은 자막 오프셋도 m 만큼 당긴다. 같은 녹화 A · B · 끝 B FILE_HEAD 20,000(소리만 끝): A = zeroAt 0 ·
     * [0, 103,333] · B = zeroAt 133,333 · [−50,000, −30,000]. A 60,000 → x′ 10,000 → 10,000 · B 0 → x′ −50,000 →
     * 83,333.
     */
    @Test
    void 파일_머리_원점_자막은_m_만큼_당긴다() {
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.FILE_HEAD, at(1, 50_000), end(2, 20_000), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true)), Map.of(1L, standard(4), 2L, standard(4)), List.of(0));

        assertThat(t.resultUsOf(new AtMedia(at(1, 60_000)))).isEqualTo(10_000);
        assertThat(t.resultUsOf(new AtMedia(at(2, 0)))).isEqualTo(83_333);
    }

    /** 결과 끝 소유로 빠진 끝 조각을 가리키면 앞 배치의 끝(= lengthUs)에 붙인다. */
    @Test
    void 빠진_조각을_가리키면_가까운_남은_경계로_붙인다() {
        RenderTimeline t = RenderTimeline.of(
                plan(OffsetOrigin.VIDEO_START, at(1, 0), end(2, 100_000), List.of(1L, 2L)),
                List.of(media(1, false), media(2, true)), Map.of(1L, standard(5_415), 2L, standard(4)), List.of(0));

        assertThat(t.resultUsOf(new AtMedia(at(2, 0)))).isEqualTo(180_000_000);
        assertThat(t.resultUsOf(new AtMedia(at(1, 0)))).isEqualTo(0);
    }

    private static SubtitlePoint point(long seq, long offsetUs) {
        return new AtMedia(at(seq, offsetUs));
    }

    private static RenderTimeline cut(List<SubtitleSpan> subtitles) {
        SegmentTiming shortV = timing(grid(0, FRAME, 4), 9_000, track(2_400, 10));
        return RenderTimeline.of(plan(OffsetOrigin.VIDEO_START, at(1, 0), new EndBoundary(3, 66_667L, false),
                        List.of(1L, 3L), subtitles),
                List.of(media(1, false), media(3, false)), Map.of(1L, shortV, 3L, standard(4)), List.of(0));
    }
}
