package com.pokeclip.render.media;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.Crop;

/**
 * 정규화 crop을 원본 픽셀로 옮긴다(계약6 2절: 3층 전용 검사).
 *
 * <ol>
 *   <li>픽셀 종횡비 {@code (w×srcW)/(h×srcH)}가 목표와 ±1% 안이어야 한다. 넘으면 늘려 맞추면 왜곡이라 VALIDATION.</li>
 *   <li>±1% 잔차는 <b>중심 유지 축소</b>로 흡수한다. 늘리지도, 한쪽으로 밀지도 않는다. 그래야 결과가 결정적이다.</li>
 *   <li>짝수 픽셀로 내린다(yuv420p는 홀수 폭을 못 쓴다).</li>
 * </ol>
 */
public final class CropGeometry {

    static final double TOLERANCE = 0.01;

    private CropGeometry() {
    }

    public record Pixels(int x, int y, int width, int height) {
        public String filter() {
            return "crop=" + width + ":" + height + ":" + x + ":" + y;
        }
    }

    public static Pixels toPixels(Crop crop, Aspect aspect, int srcW, int srcH) {
        double w = crop.w() * srcW;
        double h = crop.h() * srcH;
        double ratio = w / h;
        if (Math.abs(ratio / aspect.ratio() - 1) > TOLERANCE) {
            throw RenderFailure.permanent(ErrorCode.VALIDATION,
                    "crop 비율이 " + aspect + "와 1% 넘게 다르다(영상이 찌그러진다)");
        }
        double cx = crop.x() * srcW + w / 2;
        double cy = crop.y() * srcH + h / 2;
        if (ratio > aspect.ratio()) {
            w = h * aspect.ratio();
        } else {
            h = w / aspect.ratio();
        }
        int width = even(w);
        int height = even(h);
        int x = clamp((int) Math.round(cx - width / 2.0), 0, srcW - width);
        int y = clamp((int) Math.round(cy - height / 2.0), 0, srcH - height);
        if (width < 2 || height < 2) {
            throw RenderFailure.permanent(ErrorCode.VALIDATION, "crop 영역이 너무 작다");
        }
        return new Pixels(x, y, width, height);
    }

    private static int even(double value) {
        int floor = (int) Math.floor(value);
        return floor - (floor % 2);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
