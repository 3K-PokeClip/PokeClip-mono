package com.pokeclip.render.recipe;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
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
        rejects(r -> r.put("schemaVersion", 3));
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
    void 빈_자막_글자는_clip처럼_받는다() {
        ObjectNode r = Fixtures.recipe("s1");
        ((ObjectNode) r.get("subtitles").get("segments").get(0)).put("text", "");
        assertThat(RecipeParser.parse(r).subtitles().segments().getFirst().text()).isEmpty();
        rejects(x -> ((ObjectNode) x.get("subtitles").get("segments").get(0)).put("text", 3));
        rejects(x -> ((ObjectNode) x.get("subtitles").get("segments").get(0)).putNull("text"));
    }

    @Test
    void 자막_규칙() {
        rejects(r -> ((ObjectNode) r.get("subtitles")).put("mode", "KARAOKE"));
        rejects(r -> ((ObjectNode) r.get("subtitles").get("segments").get(1)).put("startAtMs", Fixtures.BASE + 2_000));
        rejects(r -> ((ObjectNode) r.get("subtitles").get("segments").get(0)).put("endAtMs", Fixtures.BASE + 500));
    }

    @Test
    void v1_출력은_결과를_꽉_채우는_층_하나로_읽는다() {
        Recipe.Output output = RecipeParser.parse(Fixtures.recipe("s1")).outputs().getFirst();
        assertThat(output.isPlainSingle()).isTrue();
        assertThat(output.layers().getFirst().crop()).isEqualTo(new Recipe.Crop(0.341796875, 0, 0.31640625, 1));
    }

    @Test
    void v2_레시피를_읽는다() {
        Recipe recipe = RecipeParser.parse(Fixtures.recipeV2("s1"));
        Recipe.Output output = recipe.outputs().getFirst();
        assertThat(output.isPlainSingle()).isFalse();
        assertThat(output.background()).isEqualTo(new Recipe.Background.Blur(60));
        assertThat(output.layers()).hasSize(2);
        assertThat(output.layers().get(1).box()).isEqualTo(new Recipe.Box(0.18, 0.5, 0.64, 0.27));
        assertThat(output.layers().get(1).frame()).isEqualTo(new Recipe.Frame(0.004, "#ffffff", 0.016, true));
        assertThat(output.layers().get(0).frame()).isNull();
        assertThat(output.dividers()).containsExactly(new Recipe.Divider(0.25, 0.008, "#586fc4"));
        assertThat(recipe.subtitles().position()).isEqualTo(new Recipe.SubtitlePosition(Recipe.Anchor.BOTTOM, 0.97));
    }

    @Test
    void v2는_바탕과_구분선을_빼도_된다() {
        ObjectNode r = Fixtures.recipeV2("s1");
        ((ObjectNode) r.get("outputs").get(0)).remove("background");
        ((ObjectNode) r.get("outputs").get(0)).remove("dividers");
        ((ObjectNode) r.get("subtitles")).remove("position");
        Recipe recipe = RecipeParser.parse(r);
        assertThat(recipe.outputs().getFirst().background()).isNull();
        assertThat(recipe.outputs().getFirst().dividers()).isEmpty();
        assertThat(recipe.subtitles().position()).isNull();
        ObjectNode color = Fixtures.recipeV2("s1");
        ((ObjectNode) color.get("outputs").get(0)).putObject("background").put("kind", "COLOR").put("color", "#1C2440");
        assertThat(RecipeParser.parse(color).outputs().getFirst().background())
                .isEqualTo(new Recipe.Background.Color("#1C2440"));
    }

    @Test
    void 판마다_칸이_다르다() {
        // v1에 v2 칸, v2에 v1 칸 — 섞으면 한쪽 효과가 조용히 빠진다
        rejects(r -> ((ObjectNode) r.get("outputs").get(0)).putArray("layers"));
        rejects(r -> ((ObjectNode) r.get("subtitles")).putObject("position").put("anchor", "TOP").put("y", 0.1));
        rejectsV2(r -> ((ObjectNode) r.get("outputs").get(0)).putObject("crop")
                .put("x", 0).put("y", 0).put("w", 0.5).put("h", 1));
        rejectsV2(r -> ((ObjectNode) r.get("outputs").get(0)).remove("layers"));
    }

    @Test
    void v2_층_규칙() {
        rejectsV2(r -> ((ObjectNode) r.get("outputs").get(0)).putArray("layers"));
        rejectsV2(r -> {
            ArrayNode layers = (ArrayNode) r.get("outputs").get(0).get("layers");
            for (int i = 0; i < 3; i++) {
                layers.add(layers.get(0).deepCopy());
            }
        });
        rejectsV2(r -> layer(r, 1).withObject("box").put("x", 0.5));
        rejectsV2(r -> layer(r, 1).withObject("box").put("h", 0.04));
        rejectsV2(r -> layer(r, 1).withObject("box").put("y", -0.1));
        rejectsV2(r -> layer(r, 1).withObject("crop").put("w", 0.9));
        rejectsV2(r -> layer(r, 1).put("opacity", 0.5));
    }

    @Test
    void v2_테두리_규칙() {
        rejectsV2(r -> layer(r, 1).withObject("frame").put("width", 0.051));
        rejectsV2(r -> layer(r, 1).withObject("frame").put("radius", 0.11));
        rejectsV2(r -> layer(r, 1).withObject("frame").put("color", "white"));
        rejectsV2(r -> layer(r, 1).withObject("frame").put("color", "#fff"));
        rejectsV2(r -> layer(r, 1).withObject("frame").put("shadow", "yes"));
        rejectsV2(r -> layer(r, 1).withObject("frame").remove("shadow"));
    }

    @Test
    void v2_바탕_구분선_자막자리_규칙() {
        rejectsV2(r -> output(r).withObject("background").put("strength", 101));
        rejectsV2(r -> output(r).withObject("background").put("kind", "GRADIENT"));
        rejectsV2(r -> output(r).withObject("background").put("color", "#000000"));
        rejectsV2(r -> output(r).putObject("background").put("kind", "COLOR"));
        rejectsV2(r -> ((ObjectNode) output(r).get("dividers").get(0)).put("y", 1.0));
        rejectsV2(r -> ((ObjectNode) output(r).get("dividers").get(0)).put("thickness", 0));
        rejectsV2(r -> ((ObjectNode) r.get("subtitles").get("position")).put("anchor", "LEFT"));
        rejectsV2(r -> ((ObjectNode) r.get("subtitles").get("position")).put("y", 1.1));
    }

    private static ObjectNode output(ObjectNode r) {
        return (ObjectNode) r.get("outputs").get(0);
    }

    private static ObjectNode layer(ObjectNode r, int index) {
        return (ObjectNode) output(r).get("layers").get(index);
    }

    private static void rejectsV2(Consumer<ObjectNode> change) {
        ObjectNode r = Fixtures.recipeV2("s1");
        assertThat(RecipeParser.parse(r)).isNotNull();
        change.accept(r);
        assertRejected(r);
    }

    private static void rejects(Consumer<ObjectNode> change) {
        ObjectNode r = Fixtures.recipe("s1");
        change.accept(r);
        assertRejected(r);
    }

    private static void assertRejected(ObjectNode r) {
        assertThatThrownBy(() -> RecipeParser.parse(r))
                .isInstanceOfSatisfying(RenderFailure.class, f -> {
                    assertThat(f.code()).isEqualTo(ErrorCode.VALIDATION);
                    assertThat(f.retryable()).isFalse();
                });
    }
}
