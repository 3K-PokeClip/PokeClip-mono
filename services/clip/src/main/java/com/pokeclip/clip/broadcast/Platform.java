package com.pokeclip.clip.broadcast;

import java.util.regex.Pattern;

/**
 * 방송 플랫폼 코드(POK-398). 치지직은 {@code CHZZK}, SOOP은 {@code SOOP}이다.
 *
 * <p>열거형이 아니라 문자열인 것은 플랫폼이 늘 때 clip을 다시 배포하지 않고 받으려는 것이다. 대신 형식은 여기 한 자리에서만
 * 본다: 대문자 영문·숫자·밑줄 1~16자. 16은 칸 길이({@code broadcasts.platform VARCHAR(16)})이고, 넘는 값을 그대로 저장하면
 * 영영 실패해 같은 방송의 뒤 편지가 전부 막힌다(FIFO).
 *
 * <p>소문자는 대문자로 올려 받지 않는다. 받는 쪽이 고쳐 주면 발행 쪽 실수가 안 드러난다.
 */
public final class Platform {

    /** 칸을 안 실은 편지의 값. 플랫폼 칸이 생기기 전 방송은 전부 치지직이었다. 마이그레이션의 기본값과 같아야 한다. */
    public static final String DEFAULT = "CHZZK";

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9_]{1,16}");

    static boolean isWellFormed(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    private Platform() {
    }
}
