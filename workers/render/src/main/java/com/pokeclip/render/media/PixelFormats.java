package com.pokeclip.render.media;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ffprobe 화소 꼴 이름({@code pix_fmt}) → 화소당 날(디코드된) 바이트. 조각 단계 중간 파일 바이트 상한이 쓴다(D35). OBS 송출은
 * 방송인 설정을 따르므로 4:2:0 8비트만 오지 않는다(I444 · 10비트 등).
 *
 * <p>값은 반바이트 단위 정수다 — 4:2:0 의 화소당 1.5바이트를 실수 없이 3 으로 담는다. 색 표본 비율로 4:2:0 = 3 · 4:2:2 = 4 ·
 * 4:4:4(gbrp 포함) = 6 · 회색 = 2 이고, 8비트를 넘는 깊이는 저장 크기(16비트 칸)라 그 두 배다. 표에 없는 꼴(알파 · 꽉 찬 RGB ·
 * 4:1:0 등)과 판독 없음은 {@link #FALLBACK_HALF_BYTES} 로 크게 잡는다.
 */
public final class PixelFormats {

    /** 모르는 꼴 · 판독 없음 — 6바이트(16비트 4:4:4 저장 크기 · 표의 가장 큰 값). */
    static final int FALLBACK_HALF_BYTES = 12;

    /** 깊이 꼬리(9–16비트 · 바이트 순서) — 없으면 8비트다. */
    private static final String DEPTH = "((9|1[0-6])(le|be))?";

    /** 8비트를 넘는 꼴 — 평면 꼴의 깊이 꼬리, 또는 반평면 p01x · p21x · p41x 무리(늘 16비트 칸). */
    private static final Pattern DEEP = Pattern.compile(".*[a-z](9|1[0-6])(le|be)|p[024]1[0-6](le|be)");

    /** 색 표본 비율 표(이름 전체가 맞아야 한다 · 8비트 기준 반바이트). */
    private static final List<Row> TABLE = List.of(
            new Row("yuvj?420p" + DEPTH + "|nv12|nv21|p01[0-6](le|be)", 3),
            new Row("yuvj?422p" + DEPTH + "|yuyv422|uyvy422|yvyu422|nv16|p21[0-6](le|be)", 4),
            new Row("yuvj?444p" + DEPTH + "|gbrp" + DEPTH + "|nv24|p41[0-6](le|be)", 6),
            new Row("gray" + DEPTH, 2));

    private PixelFormats() {
    }

    /** 화소당 날 바이트 × 2. 모르거나 없으면 {@link #FALLBACK_HALF_BYTES}. */
    public static int rawHalfBytesPerPixel(String pixFmt) {
        if (pixFmt == null) {
            return FALLBACK_HALF_BYTES;
        }
        for (Row row : TABLE) {
            if (row.name().matcher(pixFmt).matches()) {
                return DEEP.matcher(pixFmt).matches() ? row.halfBytes() * 2 : row.halfBytes();
            }
        }
        return FALLBACK_HALF_BYTES;
    }

    private record Row(Pattern name, int halfBytes) {

        Row(String name, int halfBytes) {
            this(Pattern.compile(name), halfBytes);
        }
    }
}
