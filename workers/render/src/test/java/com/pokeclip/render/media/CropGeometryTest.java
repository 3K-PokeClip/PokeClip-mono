package com.pokeclip.render.media;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.Crop;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CropGeometryTest {

    @Test
    void 가로_방송에서_가운데_세로를_자른다() {
        // 1920x1080에서 9:16. 높이 1080 전부, 폭 607.5px → 짝수로 606
        CropGeometry.Pixels p = CropGeometry.toPixels(new Crop(0.341796875, 0, 0.31640625, 1), Aspect.VERT_9_16,
                1920, 1080);
        assertThat(p.width() % 2).isZero();
        assertThat(p.height()).isEqualTo(1080);
        assertThat(p.width()).isEqualTo(606);
        // 중심 유지: 원래 중심 x = 960
        assertThat(p.x() + p.width() / 2).isEqualTo(960);
    }

    @Test
    void 일퍼센트_안쪽_잔차는_가운데를_지키며_줄인다() {
        // 폭을 0.8% 넓게 줬다 → 폭만 줄고 중심은 그대로
        double w = 0.31640625 * 1.008;
        double x = 0.5 - w / 2;
        CropGeometry.Pixels p = CropGeometry.toPixels(new Crop(x, 0, w, 1), Aspect.VERT_9_16, 1920, 1080);
        assertThat((double) p.width() / p.height()).isCloseTo(9.0 / 16, org.assertj.core.data.Offset.offset(0.004));
        assertThat(Math.abs(p.x() + p.width() / 2 - 960)).isLessThanOrEqualTo(1);
    }

    @Test
    void 일퍼센트를_넘으면_찌그러지니_거부한다() {
        assertThatThrownBy(() -> CropGeometry.toPixels(new Crop(0.3, 0, 0.4, 1), Aspect.VERT_9_16, 1920, 1080))
                .isInstanceOfSatisfying(RenderFailure.class, f -> assertThat(f.code()).isEqualTo(ErrorCode.VALIDATION));
    }

    @Test
    void 정사각은_높이_전부와_같은_폭() {
        CropGeometry.Pixels p = CropGeometry.toPixels(new Crop(0.21875, 0, 0.5625, 1), Aspect.SQUARE_1_1, 1920, 1080);
        assertThat(p.width()).isEqualTo(1080);
        assertThat(p.height()).isEqualTo(1080);
        assertThat(p.filter()).isEqualTo("crop=1080:1080:420:0");
    }
}
