package com.pokeclip.render.recipe;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeParserTest {

    @Test
    void 잘_된_레시피를_읽는다() {
        Recipe recipe = RecipeParser.parse(Fixtures.recipe("s1"));
        assertThat(recipe.cut().durationMs()).isEqualTo(10_000);
        assertThat(recipe.outputs()).singleElement()
                .satisfies(o -> assertThat(o.aspect()).isEqualTo(Recipe.Aspect.VERT_9_16));
        assertThat(recipe.tracks()).extracting(Recipe.AudioTrack::trackId).containsExactly(1, 3);
        assertThat(recipe.subtitles().mode()).isEqualTo(Recipe.SubtitleMode.BURN_AND_CC);
    }

    @Test
    void 자막이_null이면_자막_없음이다() {
        ObjectNode r = Fixtures.recipe("s1");
        r.putNull("subtitles");
        assertThat(RecipeParser.parse(r).subtitles()).isNull();
    }

    @Test
    void 모르는_칸은_어느_깊이든_거부한다() {
        rejects(r -> r.put("title", "x"));
        rejects(r -> ((ObjectNode) r.get("cut")).put("fadeMs", 3));
        rejects(r -> ((ObjectNode) r.get("outputs").get(0)).put("blur", true));
        rejects(r -> ((ObjectNode) r.get("audio").get("tracks").get(0)).put("pan", 0.5));
        rejects(r -> ((ObjectNode) r.get("subtitles").get("segments").get(0)).put("color", "red"));
    }

    @Test
    void 템플릿과_구간_규칙() {
        rejects(r -> r.putNull("cut"));
        rejects(r -> ((ObjectNode) r.get("cut")).put("outAtMs", Fixtures.BASE + 1_000));
        rejects(r -> ((ObjectNode) r.get("cut")).put("outAtMs", Fixtures.BASE + 5_999));
        rejects(r -> ((ObjectNode) r.get("cut")).put("outAtMs", Fixtures.BASE + 181_001));
        rejects(r -> r.put("schemaVersion", 2));
    }

    @Test
    void 출력_규칙() {
        rejects(r -> ((ObjectNode) r.get("outputs").get(0)).put("aspect", "LANDSCAPE_16_9"));
        rejects(r -> ((ObjectNode) r.get("outputs").get(0)).put("outputId", "O1"));
        rejects(r -> r.withArray("outputs").add(r.get("outputs").get(0).deepCopy()));
        rejects(r -> ((ObjectNode) r.get("outputs").get(0).get("crop")).put("x", 0.7));
        rejects(r -> ((ObjectNode) r.get("outputs").get(0).get("crop")).put("w", 0.04));
        rejects(r -> r.putArray("outputs"));
    }

    @Test
    void 소리_규칙() {
        rejects(r -> r.withObject("audio").withArray("tracks").addObject().put("trackId", 0).put("gain", 1.0));
        rejects(r -> r.withObject("audio").withArray("tracks").addObject().put("trackId", 1).put("gain", 1.0));
        rejects(r -> ((ObjectNode) r.get("audio").get("tracks").get(0)).put("gain", 2.01));
        rejects(r -> ((ObjectNode) r.get("audio").get("tracks").get(0)).put("trackId", 6));
    }

    @Test
    void 자막_규칙() {
        rejects(r -> ((ObjectNode) r.get("subtitles")).put("mode", "KARAOKE"));
        rejects(r -> ((ObjectNode) r.get("subtitles").get("segments").get(1)).put("startAtMs", Fixtures.BASE + 2_000));
        rejects(r -> ((ObjectNode) r.get("subtitles").get("segments").get(0)).put("endAtMs", Fixtures.BASE + 500));
    }

    private static void rejects(Consumer<ObjectNode> change) {
        ObjectNode r = Fixtures.recipe("s1");
        change.accept(r);
        assertThatThrownBy(() -> RecipeParser.parse(r))
                .isInstanceOfSatisfying(RenderFailure.class, f -> {
                    assertThat(f.code()).isEqualTo(ErrorCode.VALIDATION);
                    assertThat(f.retryable()).isFalse();
                });
    }
}
