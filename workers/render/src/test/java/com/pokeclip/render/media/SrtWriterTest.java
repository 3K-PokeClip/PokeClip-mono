package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Cut;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SrtWriterTest {

    private final Cut cut = new Cut(10_000, 20_000);

    @Test
    void 컷_축으로_옮기고_경계에서_자르고_밖은_버린다() {
        List<SubtitleSegment> clipped = SrtWriter.clipped(List.of(
                new SubtitleSegment(5_000, 9_000, "밖"),
                new SubtitleSegment(9_000, 11_500, "걸침"),
                new SubtitleSegment(12_000, 13_000, "안"),
                new SubtitleSegment(19_500, 25_000, "끝 걸침"),
                new SubtitleSegment(20_000, 21_000, "딱 끝")), cut);
        assertThat(clipped).containsExactly(
                new SubtitleSegment(0, 1_500, "걸침"),
                new SubtitleSegment(2_000, 3_000, "안"),
                new SubtitleSegment(9_500, 10_000, "끝 걸침"));
    }

    @Test
    void 빈_글자_자막은_뺀다() {
        assertThat(SrtWriter.clipped(List.of(new SubtitleSegment(11_000, 12_000, "  "),
                new SubtitleSegment(12_000, 13_000, "")), cut)).isEmpty();
    }

    @Test
    void srt_형식() {
        String srt = SrtWriter.render(List.of(new SubtitleSegment(0, 1_500, "첫\n\n줄"),
                new SubtitleSegment(3_723_004, 3_724_000, "둘")));
        assertThat(srt).isEqualTo("1\n00:00:00,000 --> 00:00:01,500\n첫\n줄\n\n"
                + "2\n01:02:03,004 --> 01:02:04,000\n둘\n\n");
    }

    @Test
    void 컷_안에_하나도_없으면_빈_문자열() {
        assertThat(SrtWriter.clipped(List.of(new SubtitleSegment(0, 1_000, "밖")), cut)).isEmpty();
        assertThat(SrtWriter.render(List.of())).isEmpty();
    }

    /**
     * 새 꼴: 결과 µs 두 끝을 둘 다 ms 로 내린다. 번인(libass)이 프레임 시각을 ms 로 내려 비교하므로, 프레임 자리에 놓인 끝을
     * 올리면 그 프레임까지 자막이 하나 더 보인다.
     */
    @Test
    void 결과_µs_두_끝을_ms_로_내린다() {
        assertThat(SrtWriter.onResult(533_333, 1_066_667, "가"))
                .contains(new SubtitleSegment(533, 1_066, "가"));
        assertThat(SrtWriter.onResult(0, 9_000_000, "끝까지"))
                .contains(new SubtitleSegment(0, 9_000, "끝까지"));
    }

    @Test
    void 내린_길이가_0_인_자막과_빈_글자_자막은_뺀다() {
        assertThat(SrtWriter.onResult(1_000_100, 1_000_900, "가")).isEqualTo(Optional.empty());
        assertThat(SrtWriter.onResult(0, 1_000_000, " \n ")).isEqualTo(Optional.empty());
    }
}
