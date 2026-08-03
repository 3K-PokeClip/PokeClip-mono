package com.pokeclip.auth.streamkey;

import java.security.SecureRandom;

/**
 * Crockford Base32 (ADR-019). 0-9 + A-Z에서 I·L·O·U를 뺀 32글자다.
 * 32는 2의 거듭제곱이라 nextInt(32)로 뽑으면 편향이 없다.
 */
public final class CrockfordBase32 {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private CrockfordBase32() {
    }

    public static String random(SecureRandom random, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    /**
     * 사람이 친 값을 표준형으로 만든다. 예외 메시지에 입력값을 넣지 않는다 —
     * 페어링 코드가 그대로 로그에 남는다.
     */
    public static String normalize(String input) {
        String upper = input.replace("-", "").toUpperCase();
        StringBuilder out = new StringBuilder(upper.length());

        for (char c : upper.toCharArray()) {
            char mapped = switch (c) {
                case 'I', 'L' -> '1';
                case 'O' -> '0';
                default -> c;
            };
            if (ALPHABET.indexOf(mapped) < 0) {
                throw new IllegalArgumentException(
                        "Crockford Base32가 아닌 문자가 있다 (값은 남기지 않는다)");
            }
            out.append(mapped);
        }
        return out.toString();
    }
}
