package com.pokeclip.auth.profile;

import java.util.Optional;

/**
 * 받는 형식 셋. 화면이 만드는 것은 PNG이고(cropImage.ts), 나머지 둘은 화면의 입력 허용 형식이다.
 *
 * <p><b>내용의 앞머리로 가른다 — 밝힌 이름표를 믿지 않는다.</b> 밝힌 형식만 보면 그림이 아닌 것에
 * 그림 이름표를 붙여 올릴 수 있고, 그것을 그대로 내보내면 보는 사람의 브라우저에서 실행된다.
 *
 * <p><b>그림을 펼쳐 보지는 않는다</b>(PRD) — 작은 파일이 펼치면 거대해지는 공격이 있는데 우리가
 * 안 펼치면 우리 서버에서는 안 일어난다. 앞머리 + 크기 상한 + 내보낼 때 형식 못박기가 방어선이다.
 */
public enum ImageType {

    PNG("image/png", new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
    /** SOI + 마커 시작. 뒤 한 바이트는 APPn 종류라 무엇이 와도 된다. */
    JPEG("image/jpeg", new int[]{0xFF, 0xD8, 0xFF}),
    /** RIFF….WEBP — 4~7바이트는 길이라 건너뛴다. WAV도 RIFF로 시작하므로 8번째부터를 반드시 본다. */
    WEBP("image/webp", null);

    private final String contentType;
    private final int[] magic;

    ImageType(String contentType, int[] magic) {
        this.contentType = contentType;
        this.magic = magic;
    }

    /** 내보낼 때 못박는 형식. 올린 쪽이 밝힌 값이 아니라 우리가 판정한 값이다. */
    public String contentType() {
        return contentType;
    }

    public static Optional<ImageType> of(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        for (ImageType type : values()) {
            if (type == WEBP ? isWebp(head) : startsWith(head, type.magic)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] head, int[] magic) {
        if (head.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((head[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWebp(byte[] head) {
        return head.length >= 12
                && startsWith(head, new int[]{0x52, 0x49, 0x46, 0x46})
                && (head[8] & 0xFF) == 0x57 && (head[9] & 0xFF) == 0x45
                && (head[10] & 0xFF) == 0x42 && (head[11] & 0xFF) == 0x50;
    }
}
