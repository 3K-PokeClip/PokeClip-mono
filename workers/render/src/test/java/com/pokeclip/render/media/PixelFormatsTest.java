package com.pokeclip.render.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화소 꼴 이름 → 화소당 날 바이트(반바이트 단위 · D35). 기대값은 꼴 정의에서 손으로 셈한 값이다 — 4:2:0 은 화소당 밝기 1 + 색
 * 1/4 × 2 = 1.5, 4:2:2 는 2, 4:4:4 는 3, 회색은 1, 8비트 넘는 깊이는 저장 크기라 그 두 배.
 */
class PixelFormatsTest {

    /** 모르는 꼴의 보수 값 — 6바이트(16비트 4:4:4 저장 크기). 코드 상수를 쓰지 않고 따로 적는다. */
    private static final int FALLBACK = 12;

    @ParameterizedTest(name = "{0} → 반바이트 {1}")
    @CsvSource({
        "yuv420p, 3",
        "yuvj420p, 3",
        "nv12, 3",
        "nv21, 3",
        "yuv422p, 4",
        "yuyv422, 4",
        "nv16, 4",
        "yuv444p, 6",
        "nv24, 6",
        "gbrp, 6",
        "gray, 2",
    })
    void 여덟_비트_꼴은_색_표본_비율로_낸다(String pixFmt, int halfBytes) {
        assertThat(PixelFormats.rawHalfBytesPerPixel(pixFmt)).isEqualTo(halfBytes);
    }

    @ParameterizedTest(name = "{0} → 반바이트 {1}")
    @CsvSource({
        "yuv420p10le, 6",
        "yuv420p12be, 6",
        "p010le, 6",
        "p016be, 6",
        "yuv422p10le, 8",
        "p210le, 8",
        "yuv444p10le, 12",
        "yuv444p16le, 12",
        "p410le, 12",
        "gbrp12le, 12",
        "gray10le, 4",
        "gray16be, 4",
    })
    void 여덟_비트를_넘는_깊이는_저장_크기라_두_배다(String pixFmt, int halfBytes) {
        assertThat(PixelFormats.rawHalfBytesPerPixel(pixFmt)).isEqualTo(halfBytes);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"rgb24", "yuva420p", "gbrap", "yuv410p", "unknown", "YUV420P"})
    void 모르거나_없는_꼴은_보수_값_6바이트다(String pixFmt) {
        assertThat(PixelFormats.rawHalfBytesPerPixel(pixFmt)).isEqualTo(FALLBACK);
    }

    @Test
    void 보수_값은_아는_꼴_가운데_가장_큰_것_이상이다() {
        assertThat(FALLBACK).isGreaterThanOrEqualTo(PixelFormats.rawHalfBytesPerPixel("yuv444p16le"));
    }
}
