package com.pokeclip.render.media;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.Background;
import com.pokeclip.render.recipe.Recipe.Box;
import com.pokeclip.render.recipe.Recipe.Crop;
import com.pokeclip.render.recipe.Recipe.Divider;
import com.pokeclip.render.recipe.Recipe.Frame;
import com.pokeclip.render.recipe.Recipe.Layer;
import com.pokeclip.render.recipe.Recipe.Output;
import com.pokeclip.render.recipe.RecipeParser;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositionTest {

    private static final Crop VERT = new Crop(0.341796875, 0, 0.31640625, 1);

    @Test
    void 꾸밈_없는_한_장은_예전_한_줄_필터다() {
        Composition.Plan plan = Composition.plan(Output.single("o1", Aspect.VERT_9_16, VERT), 640, 360, "o1_");
        assertThat(plan.graph()).isEqualTo("[0:v:0]setsar=1,crop=202:360:219:0,scale=1080:1920:flags=lanczos,setsar=1");
        assertThat(plan.images()).isEmpty();
    }

    @Test
    void 분할은_두_층을_위아래로_쌓고_구분선을_긋는다() {
        // 위 60%: 1080x1152 = 0.9375 → 640x360에서 높이 전부면 폭 337.5px = 0.52734375
        // 아래 40%: 1080x768 = 1.40625 → 높이 0.5(180px)면 폭 253.125px = 0.3955078125
        Output split = new Output("o1", Aspect.VERT_9_16, null, List.of(
                new Layer(new Crop(0.2, 0, 0.52734375, 1), new Box(0, 0, 1, 0.6), null),
                new Layer(new Crop(0.1, 0.5, 0.3955078125, 0.5), new Box(0, 0.6, 1, 0.4), null)),
                List.of(new Divider(0.6, 2.0 / 240, "#586fc4")));
        Composition.Plan plan = Composition.plan(split, 640, 360, "o1_");
        assertThat(plan.images()).isEmpty();
        assertThat(plan.graph()).isEqualTo("[0:v:0]setsar=1,split=3[s0][s1][s2];"
                + "[s2]crop=16:16:0:0,scale=1080:1920:flags=neighbor,setsar=1,drawbox=x=0:y=0:w=iw:h=ih:color=0x000000:t=fill[bg];"
                + "[s0]crop=336:360:129:0,scale=1080:1152:flags=lanczos,setsar=1[l0];"
                + "[bg][l0]overlay=x=0:y=0[l0o];"
                + "[s1]crop=252:180:65:180,scale=1080:768:flags=lanczos,setsar=1[l1];"
                + "[l0o][l1]overlay=x=0:y=1152,drawbox=x=0:y=1152:w=iw:h=9:color=0x586FC4:t=fill");
    }

    @Test
    void 가운데는_흐린_바탕_위에_원본_비율로_놓는다() {
        // 16:9 층을 결과 폭 전부로 놓으면 높이 = 폭 × 9/16 × 9/16 = 0.31640625
        Output center = new Output("o1", Aspect.VERT_9_16, new Background.Blur(60), List.of(
                new Layer(new Crop(0.08, 0.08, 0.84, 0.84), new Box(0, (1 - 0.31640625) / 2, 1, 0.31640625), null)),
                List.of());
        String graph = Composition.plan(center, 640, 360, "o1_").graph();
        // 세기 60 → σ = 0.6 × 4% × 1080 = 25.92px, 1/4 크기에서 6.48
        assertThat(graph).contains("[s1]scale=270:480:force_original_aspect_ratio=increase,crop=270:480,"
                + "gblur=sigma=6.480,scale=1080:1920,setsar=1[bg]");
        assertThat(graph).endsWith("[bg][l0]overlay=x=0:y=656");
    }

    @Test
    void 세기_0이면_흐리지_않고_단색은_그_색이다() {
        Layer full = new Layer(VERT, Box.FULL, new Frame(0, "#ffffff", 0, false));
        assertThat(Composition.plan(new Output("o1", Aspect.VERT_9_16, new Background.Blur(0), List.of(full), List.of()),
                640, 360, "p").graph()).doesNotContain("gblur");
        assertThat(Composition.plan(new Output("o1", Aspect.VERT_9_16, new Background.Color("#1c2440"), List.of(full),
                List.of()), 640, 360, "p").graph()).contains("color=0x1C2440:t=fill[bg]");
    }

    @Test
    void 작은_화면은_그림자_둥근_모서리_테두리를_그림으로_겹친다() {
        Output output = RecipeParser.parse(Fixtures.recipeV2("s1")).outputs().getFirst();
        Composition.Plan plan = Composition.plan(output, 640, 360, "o1_");
        assertThat(plan.images()).extracting(Composition.Image::name)
                .containsExactly("o1_l1_mask.png", "o1_l1_shadow.png", "o1_l1_ring.png");
        // 입력 번호는 원본(0) 다음부터 그림 순서다: 모서리 1 · 그림자 2 · 테두리 3
        assertThat(plan.graph())
                .contains("alphamerge[l1]", "[1:v]format=gray[l1m]", "[2:v]overlay=", "[3:v]overlay=x=194:y=960")
                .endsWith(",drawbox=x=0:y=480:w=iw:h=9:color=0x586FC4:t=fill");
        // 쌓는 순서: 그림자 → 층 → 테두리
        String graph = plan.graph();
        assertThat(graph.indexOf("[2:v]overlay")).isLessThan(graph.indexOf("[l1]overlay"));
        assertThat(graph.indexOf("[l1]overlay")).isLessThan(graph.indexOf("[3:v]overlay"));
    }

    @Test
    void 자르는_자리와_놓일_자리의_비율이_다르면_거부한다() {
        Output wrong = new Output("o1", Aspect.VERT_9_16, null,
                List.of(new Layer(VERT, new Box(0, 0, 1, 0.5), null)), List.of());
        assertThatThrownBy(() -> Composition.plan(wrong, 640, 360, "p"))
                .isInstanceOfSatisfying(RenderFailure.class, f -> assertThat(f.code()).isEqualTo(ErrorCode.VALIDATION));
    }

    @Test
    void 자리는_짝수_픽셀이고_결과_밖으로_안_나간다() {
        assertThat(Composition.rect(new Box(0.18, 0.5, 0.64, 0.27), 1080, 1920))
                .isEqualTo(new Composition.Rect(194, 960, 690, 518));
        assertThat(Composition.rect(new Box(0.5, 0.5, 0.5000001, 0.5), 1080, 1920))
                .isEqualTo(new Composition.Rect(540, 960, 540, 960));
    }

    @Test
    void 모서리_그림은_안쪽이_희고_귀퉁이가_검다() {
        BufferedImage mask = Composition.mask(100, 60, 20);
        assertThat(mask.getRaster().getSample(50, 30, 0)).isEqualTo(255);
        assertThat(mask.getRaster().getSample(0, 0, 0)).isZero();
        assertThat(mask.getRaster().getSample(50, 0, 0)).isGreaterThan(200);
    }

    @Test
    void 테두리_그림은_가장자리만_칠하고_안은_비운다() {
        BufferedImage ring = Composition.ring(100, 60, 0, 4, "#ff0000");
        assertThat(ring.getRGB(1, 30)).isEqualTo(0xFFFF0000);
        assertThat(ring.getRGB(50, 30) >>> 24).isZero();
        assertThat(ring.getRGB(50, 58)).isEqualTo(0xFFFF0000);
    }

    @Test
    void 흐림은_알파를_퍼뜨리고_합을_지킨다() {
        BufferedImage image = new BufferedImage(41, 41, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(20, 20, 0xFF000000);
        Composition.blurAlpha(image, 2);
        assertThat(image.getRGB(20, 20) >>> 24).isBetween(1, 254);
        assertThat(image.getRGB(22, 20) >>> 24).isPositive();
        assertThat(image.getRGB(0, 0) >>> 24).isZero();
    }
}
