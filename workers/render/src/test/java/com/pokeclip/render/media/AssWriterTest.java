package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Anchor;
import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.SubtitlePosition;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssWriterTest {

    private static final List<SubtitleSegment> ONE = List.of(new SubtitleSegment(1_500, 3_000, "첫 줄\n둘째 줄"));

    @Test
    void 기준_좌표는_결과_해상도이고_글자는_편집기_글자를_옮긴_값이다() {
        String ass = AssWriter.render(ONE, new SubtitlePosition(Anchor.BOTTOM, 0.9), Aspect.VERT_9_16);
        assertThat(ass).contains("PlayResX: 1080\nPlayResY: 1920\n");
        // 11/240 × 1080 = 49.5px, 줄 높이로 × 1.448 = 71.7 · 테두리 1/240 × 1080 = 4.5 · 좌우 8/240 × 1080 = 36
        assertThat(ass).contains("Style: Default,Noto Sans CJK KR,71.7,")
                .contains(",-1,0,0,0,100,100,0,0,1,4.5,0,2,36,36,192,1\n");
        assertThat(ass).contains("Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,첫 줄\\N둘째 줄\n");
    }

    @Test
    void 위는_위_가운데_정렬에_위_여백이다() {
        String ass = AssWriter.render(ONE, new SubtitlePosition(Anchor.TOP, 0.05), Aspect.VERT_9_16);
        assertThat(ass).contains(",1,4.5,0,8,36,36,96,1\n").doesNotContain("\\pos");
    }

    @Test
    void 가운데는_줄마다_덩어리_가운데를_y에_찍는다() {
        // 여러 줄이어도 덩어리 가운데가 y — 한 줄 높이로 어림하면 두 줄 자막이 반 줄 위로 간다(PR #201 codex)
        String ass = AssWriter.render(ONE, new SubtitlePosition(Anchor.MIDDLE, 0.6), Aspect.VERT_9_16);
        assertThat(ass).contains(",,{\\an5\\pos(540,1152)}첫 줄\\N둘째 줄\n");
    }

    @Test
    void 사용자_글의_중괄호와_역슬래시는_태그로_읽히지_않게_바꾼다() {
        assertThat(AssWriter.text("{\\b1}굵게\r\n\n끝 ")).isEqualTo("｛＼b1｝굵게\\N끝");
    }

    @Test
    void 시각은_100분의_1초다() {
        assertThat(AssWriter.time(3_723_456)).isEqualTo("1:02:03.45");
    }

    @Test
    void 끝은_올려서_10ms_미만_자막도_보인다() {
        String ass = AssWriter.render(List.of(new SubtitleSegment(1_003, 1_008, "짧음"), new SubtitleSegment(2_000, 2_991, "끝")),
                new SubtitlePosition(Anchor.BOTTOM, 0.9), Aspect.VERT_9_16);
        assertThat(ass).contains("Dialogue: 0,0:00:01.00,0:00:01.01,").contains("Dialogue: 0,0:00:02.00,0:00:03.00,");
    }
}
