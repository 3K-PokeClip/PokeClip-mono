package com.pokeclip.clip.mark;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 계약4 4B의 파생 값: {@code HMAC-SHA256(key = passphrase, message = "pokeclip-4b-v1")}의 소문자 hex 64자.
 * 플러그인이 스트림키 원문 대신 이 값을 {@code Authorization: Bearer}로 보낸다: passphrase가
 * 선 위로 안 나가게 하려는 계약이다.
 *
 * <p>스프링을 안 쓴다. 계약4 시험 벡터로 단독 시험한다({@code MarkSignatureTest}).
 */
public final class MarkSignature {

    /** 계약4가 정한 메시지. 판이 바뀌면 플러그인과 같이 바꾼다: 한쪽만 바꾸면 모든 마킹이 401이다. */
    private static final byte[] MESSAGE = "pokeclip-4b-v1".getBytes(StandardCharsets.US_ASCII);

    private static final String ALGORITHM = "HmacSHA256";

    private MarkSignature() {
    }

    /**
     * @throws IllegalArgumentException passphrase가 비면({@code SecretKeySpec}이 빈 키를 거부한다)
     */
    public static String derive(String passphrase) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(passphrase.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(MESSAGE));
        } catch (GeneralSecurityException e) {
            // HmacSHA256은 모든 JDK에 있어야 하는 알고리즘이다. 여기 오면 런타임이 깨진 것이다.
            // 메시지에 키를 안 싣는다.
            throw new IllegalStateException("HmacSHA256을 쓸 수 없다", e);
        }
    }

    /**
     * 보낸 값이 이 passphrase의 파생 값과 같은가. 상수 시간으로 비교한다: 앞에서부터 다른 자리에서
     * 멈추는 비교는 응답 시간으로 맞는 글자 수를 흘린다.
     *
     * <p>대소문자를 접지 않는다(계약이 소문자로 정했다). 값이 없거나 길이가 다르면 거짓이고 던지지 않는다.
     * hex가 아닌 글자는 {@code US_ASCII}가 {@code ?}로 바꾸는데, 파생 값에는 {@code ?}가 없어 같아질 수 없다.
     */
    public static boolean matches(String passphrase, String bearer) {
        if (passphrase == null || passphrase.isEmpty() || bearer == null) {
            return false;
        }
        byte[] expected = derive(passphrase).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, bearer.getBytes(StandardCharsets.US_ASCII));
    }
}
