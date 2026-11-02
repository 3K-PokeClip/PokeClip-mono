package com.pokeclip.auth.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>파일이 스스로 밝힌 형식을 믿지 않는다.</b> 밝힌 형식만 보면 그림이 아닌 것에 그림 이름표를
 * 붙여 올릴 수 있고, 우리가 그것을 그대로 내보내면 보는 사람의 브라우저에서 실행된다.
 *
 * <p><b>그림을 펼쳐 보지는 않는다</b>(PRD) — 작은 파일이 펼치면 거대해지는 공격이 있는데,
 * 우리가 안 펼치면 우리 서버에서는 그 일이 안 일어난다. 앞머리 + 크기 상한 + 내보낼 때
 * 형식 못박기가 방어선이다.
 */
class ImageTypeTest {

    private static byte[] head(int... bytes) {
        byte[] b = new byte[Math.max(bytes.length, 16)];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        return b;
    }

    @Test
    void PNG를_가른다() {
        assertThat(ImageType.of(head(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))).contains(ImageType.PNG);
    }

    @Test
    void JPEG를_가른다() {
        assertThat(ImageType.of(head(0xFF, 0xD8, 0xFF, 0xE0))).contains(ImageType.JPEG);
    }

    @Test
    void WebP를_가른다() {
        // RIFF....WEBP — 4~7바이트는 길이라 무엇이 와도 된다
        assertThat(ImageType.of(head(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
                0x57, 0x45, 0x42, 0x50))).contains(ImageType.WEBP);
    }

    @Test
    void RIFF지만_WEBP가_아니면_거부한다() {
        // WAV도 RIFF로 시작한다 — 여기서 안 가르면 소리 파일이 그림으로 통과한다
        assertThat(ImageType.of(head(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
                0x57, 0x41, 0x56, 0x45))).isEmpty();
    }

    @Test
    void 실행파일은_거부한다() {
        assertThat(ImageType.of(head(0x4D, 0x5A))).isEmpty();               // MZ (PE)
        assertThat(ImageType.of(head(0x7F, 0x45, 0x4C, 0x46))).isEmpty();   // ELF
        assertThat(ImageType.of(head(0xCA, 0xFE, 0xBA, 0xBE))).isEmpty();   // class
    }

    @Test
    void SVG는_거부한다() {
        // SVG는 그림이지만 스크립트를 품을 수 있다. 화면이 만드는 것도 PNG라 받을 이유가 없다.
        assertThat(ImageType.of("<svg xmlns=\"http://www.w3.org/2000/svg\">".getBytes())).isEmpty();
    }

    @Test
    void 너무_짧으면_거부한다() {
        assertThat(ImageType.of(new byte[0])).isEmpty();
        assertThat(ImageType.of(new byte[]{(byte) 0x89, 0x50})).isEmpty();
    }

    /** 내보낼 때 못박을 형식이다 — 올린 쪽이 밝힌 값을 그대로 쓰지 않는 것이 이 열거의 존재 이유다. */
    @Test
    void 형식마다_내보낼_이름표를_들고_있다() {
        assertThat(ImageType.PNG.contentType()).isEqualTo("image/png");
        assertThat(ImageType.JPEG.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageType.WEBP.contentType()).isEqualTo("image/webp");
    }
}
