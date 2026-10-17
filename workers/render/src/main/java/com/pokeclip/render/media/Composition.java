package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Background;
import com.pokeclip.render.recipe.Recipe.Box;
import com.pokeclip.render.recipe.Recipe.Divider;
import com.pokeclip.render.recipe.Recipe.Frame;
import com.pokeclip.render.recipe.Recipe.Layer;
import com.pokeclip.render.recipe.Recipe.Output;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 출력 한 벌의 영상 필터를 짠다(계약6 7절). 순수 함수라 필터 문자열과 그림을 그대로 시험한다.
 *
 * <p>결과 화면은 <b>바탕 → (그림자) → 층 → (테두리) → … → 구분선</b> 순서로 쌓는다. 바탕은 원본에서 만든다(단색도) — 원본 프레임을
 * 따라가야 결과의 프레임 수·시각이 원본과 같다. 둥근 모서리·테두리·그림자는 모양이 영상 내내 같아서 <b>PNG로 한 번 그려</b> 겹친다.
 * ffmpeg의 {@code geq}로 프레임마다 계산하면 3분 클립에서 수 분이 더 든다.
 *
 * <p>꾸밈 없이 층 하나가 결과를 꽉 채우면(v1 출력) 예전과 같은 한 줄 필터를 낸다 — 이미 만든 영상과 같은 결과가 나와야 한다.
 *
 * <p>길이 단위: 테두리·구분선·모서리·그림자는 <b>결과 폭에 대한 비</b>로 온다. 편집기 미리보기가 같은 비로 그린다.
 */
public final class Composition {

    /**
     * 편집기 그림자(시안 {@code 0 4px 12px rgb(0 0 0 / 0.45)})를 결과 폭 비로 옮긴 값. 편집기 결과 칸의 기준 폭이 240px이다
     * (1440×900 화면에서 234px 실측) — 미리보기와 영상의 그림자 모양이 같아진다. CSS 흐림 반경 12px는 표준편차 6px이다.
     */
    static final double SHADOW_OFFSET = 4.0 / 240;
    static final double SHADOW_SIGMA = 6.0 / 240;
    static final double SHADOW_ALPHA = 0.45;

    /** 흐린 바탕: 세기 100이면 표준편차가 결과 폭의 4%. 편집기가 같은 식으로 그린다. */
    static final double BLUR_SIGMA_AT_FULL = 0.04;

    /** 흐린 바탕은 1/4 크기에서 흐리고 키운다 — 흐린 그림은 해상도가 필요 없고, 1080×1920에서 바로 흐리면 몇 배 느리다. */
    private static final int BLUR_DOWNSCALE = 4;

    private Composition() {
    }

    /** 영상 필터 입력으로 쓸 그림 한 장. 필터의 입력 번호는 원본(0) 다음부터 이 목록 순서다. */
    public record Image(String name, BufferedImage image) {
    }

    /**
     * @param graph  {@code [0:v:0]}에서 시작해 <b>출력 이름표 없이 끝나는</b> 필터. 부르는 쪽이 {@code ,subtitles=…}이나 {@code [vout]}을
     *               바로 잇는다
     * @param images 필터가 {@code [1:v]}, {@code [2:v]}…로 읽는 그림. 부르는 쪽이 작업 폴더에 쓰고 {@code -loop 1 -i}로 넣는다
     */
    public record Plan(String graph, List<Image> images) {
    }

    /**
     * @param prefix 그림 파일 이름 앞붙이(출력마다 다르게 — 같은 폴더에 쓴다)
     * @throws com.pokeclip.render.job.RenderFailure 층의 자르는 자리와 놓일 자리의 비율이 1% 넘게 다르면(VALIDATION)
     */
    public static Plan plan(Output output, int srcW, int srcH, String prefix) {
        int outW = output.aspect().width();
        int outH = output.aspect().height();
        if (output.isPlainSingle()) {
            CropGeometry.Pixels crop = CropGeometry.toPixels(output.layers().getFirst().crop(), output.aspect().ratio(),
                    srcW, srcH);
            return new Plan("[0:v:0]setsar=1," + crop.filter() + ",scale=" + outW + ":" + outH
                    + ":flags=lanczos,setsar=1", List.of());
        }

        List<Layer> layers = output.layers();
        List<Image> images = new ArrayList<>();
        StringBuilder graph = new StringBuilder("[0:v:0]setsar=1,split=").append(layers.size() + 1);
        for (int i = 0; i <= layers.size(); i++) {
            graph.append("[s").append(i).append(']');
        }
        graph.append(';').append(background(output.background(), "[s" + layers.size() + "]", outW, outH))
                .append("[bg]");

        String current = "[bg]";
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            Box box = layer.box();
            Rect at = rect(box, outW, outH);
            // 검사는 레시피가 말한 비율로, 자르기는 짝수로 반올림된 칸의 비율로 — 늘려 그리면 찌그러진다
            double declared = (box.w() * outW) / (box.h() * outH);
            CropGeometry.Pixels crop = CropGeometry.toPixels(layer.crop(), declared, (double) at.w / at.h, srcW, srcH);
            graph.append(";[s").append(i).append(']').append(crop.filter())
                    .append(",scale=").append(at.w).append(':').append(at.h).append(":flags=lanczos,setsar=1");

            Frame frame = layer.frame();
            int radius = frame == null ? 0 : (int) Math.round(frame.radius() * outW);
            int line = frame == null ? 0 : (int) Math.round(frame.width() * outW);
            if (radius > 0) {
                images.add(new Image(prefix + "l" + i + "_mask.png", mask(at.w, at.h, radius)));
                graph.append(",format=yuva420p[l").append(i).append("r];[").append(images.size())
                        .append(":v]format=gray[l").append(i).append("m];[l").append(i).append("r][l").append(i)
                        .append("m]alphamerge");
            }
            graph.append("[l").append(i).append(']');

            if (frame != null && frame.shadow()) {
                Shadow shadow = shadow(at.w, at.h, radius, outW);
                images.add(new Image(prefix + "l" + i + "_shadow.png", shadow.image));
                current = overlay(graph, current, "[" + images.size() + ":v]", at.x - shadow.pad, at.y - shadow.pad,
                        "l" + i + "s");
            }
            current = overlay(graph, current, "[l" + i + "]", at.x, at.y, "l" + i + "o");
            if (line > 0) {
                images.add(new Image(prefix + "l" + i + "_ring.png", ring(at.w, at.h, radius, line, frame.color())));
                current = overlay(graph, current, "[" + images.size() + ":v]", at.x, at.y, "l" + i + "b");
            }
        }

        // 마지막 합성의 이름표를 떼고 구분선을 그 줄에 잇는다 — 부르는 쪽이 바로 뒤에 자막·출력 이름표를 붙인다
        graph.setLength(graph.length() - current.length());
        for (Divider divider : output.dividers()) {
            int y = (int) Math.round(divider.y() * outH);
            int thickness = Math.max(1, (int) Math.round(divider.thickness() * outW));
            graph.append(",drawbox=x=0:y=").append(y).append(":w=iw:h=").append(thickness)
                    .append(":color=").append(ffColor(divider.color())).append(":t=fill");
        }
        return new Plan(graph.toString(), List.copyOf(images));
    }

    /** 원본을 결과 크기로 흐리게 깔거나, 원본 프레임을 따라가는 단색 판을 만든다. */
    private static String background(Background background, String input, int outW, int outH) {
        if (background instanceof Background.Blur blur && blur.strength() == 0) {
            // 세기 0은 흐리지 않은 원본을 꽉 차게 깐다 — 1/4로 줄였다 키우면 미리보기와 달리 뭉개진다(PR #201 codex)
            return input + "scale=" + outW + ":" + outH + ":force_original_aspect_ratio=increase,crop=" + outW + ":"
                    + outH + ",setsar=1";
        }
        if (background instanceof Background.Blur blur) {
            int w = even(outW / BLUR_DOWNSCALE);
            int h = even(outH / BLUR_DOWNSCALE);
            double sigma = blur.strength() / 100.0 * BLUR_SIGMA_AT_FULL * outW / BLUR_DOWNSCALE;
            String chain = input + "scale=" + w + ":" + h + ":force_original_aspect_ratio=increase,crop=" + w + ":" + h;
            if (sigma >= 0.01) {
                chain += ",gblur=sigma=" + decimal(sigma);
            }
            return chain + ",scale=" + outW + ":" + outH + ",setsar=1";
        }
        String color = background instanceof Background.Color c ? c.color() : "#000000";
        // 작게 잘라 키운 뒤 칠한다 — 원본을 결과 크기로 키우는 비용을 안 쓴다. 프레임 시각만 원본을 따라가면 된다
        return input + "crop=16:16:0:0,scale=" + outW + ":" + outH + ":flags=neighbor,setsar=1,drawbox=x=0:y=0:w=iw:h=ih:color="
                + ffColor(color) + ":t=fill";
    }

    private static String overlay(StringBuilder graph, String base, String top, int x, int y, String label) {
        graph.append(';').append(base).append(top).append("overlay=x=").append(x).append(":y=").append(y)
                .append("[").append(label).append(']');
        return "[" + label + "]";
    }

    /** 결과 안 자리를 픽셀로. 짝수로 내린다(yuv420p). 결과 밖으로는 안 나간다. */
    record Rect(int x, int y, int w, int h) {
    }

    static Rect rect(Box box, int outW, int outH) {
        int x = even((int) Math.round(box.x() * outW));
        int y = even((int) Math.round(box.y() * outH));
        int w = even(Math.min((int) Math.round(box.w() * outW), outW - x));
        int h = even(Math.min((int) Math.round(box.h() * outH), outH - y));
        return new Rect(x, y, w, h);
    }

    /** 둥근 사각형 안쪽이 흰 회색조 그림. {@code alphamerge}가 이것을 층의 투명도로 쓴다. */
    static BufferedImage mask(int w, int h, int radius) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = smooth(image);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(0, 0, w, h, radius * 2.0, radius * 2.0));
        g.dispose();
        return image;
    }

    /** 층 둘레의 선. 층 안쪽 가장자리에 그린다(미리보기의 CSS 테두리도 칸 안쪽이다). */
    static BufferedImage ring(int w, int h, int radius, int line, String color) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(image);
        Area area = new Area(new RoundRectangle2D.Double(0, 0, w, h, radius * 2.0, radius * 2.0));
        int inner = Math.max(0, radius - line);
        area.subtract(new Area(new RoundRectangle2D.Double(line, line, w - 2.0 * line, h - 2.0 * line,
                inner * 2.0, inner * 2.0)));
        g.setColor(Color.decode(color));
        g.fill(area);
        g.dispose();
        return image;
    }

    private record Shadow(BufferedImage image, int pad) {
    }

    /** 층 뒤에 까는 흐린 검은 그림자. 그림이 층보다 {@code pad}만큼 사방으로 크다. */
    private static Shadow shadow(int w, int h, int radius, int outW) {
        double sigma = SHADOW_SIGMA * outW;
        int offset = (int) Math.round(SHADOW_OFFSET * outW);
        int pad = (int) Math.ceil(3 * sigma) + offset;
        BufferedImage image = new BufferedImage(w + 2 * pad, h + 2 * pad, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(image);
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(0, 0, 0, (int) Math.round(SHADOW_ALPHA * 255)));
        g.fill(new RoundRectangle2D.Double(pad, pad + offset, w, h, radius * 2.0, radius * 2.0));
        g.dispose();
        blurAlpha(image, sigma);
        return new Shadow(image, pad);
    }

    /** 가우스 흐림을 알파에만 건다(색은 검정 그대로). 가로·세로로 나눠 두 번. */
    static void blurAlpha(BufferedImage image, double sigma) {
        int w = image.getWidth();
        int h = image.getHeight();
        int reach = (int) Math.ceil(3 * sigma);
        if (reach < 1) {
            return;
        }
        double[] kernel = new double[2 * reach + 1];
        double sum = 0;
        for (int i = -reach; i <= reach; i++) {
            kernel[i + reach] = Math.exp(-(i * i) / (2 * sigma * sigma));
            sum += kernel[i + reach];
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= sum;
        }
        double[] alpha = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                alpha[y * w + x] = image.getRGB(x, y) >>> 24;
            }
        }
        double[] pass = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = 0;
                for (int k = -reach; k <= reach; k++) {
                    int xx = Math.min(w - 1, Math.max(0, x + k));
                    v += alpha[y * w + xx] * kernel[k + reach];
                }
                pass[y * w + x] = v;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = 0;
                for (int k = -reach; k <= reach; k++) {
                    int yy = Math.min(h - 1, Math.max(0, y + k));
                    v += pass[yy * w + x] * kernel[k + reach];
                }
                image.setRGB(x, y, ((int) Math.round(Math.min(255, v)) << 24));
            }
        }
    }

    private static Graphics2D smooth(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** {@code #RRGGBB} → ffmpeg 색 {@code 0xRRGGBB}. */
    static String ffColor(String hex) {
        return "0x" + hex.substring(1).toUpperCase(Locale.ROOT);
    }

    private static int even(int value) {
        return value - (value % 2);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
