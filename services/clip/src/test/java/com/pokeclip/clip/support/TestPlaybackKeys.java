package com.pokeclip.clip.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * 출입증 시험 전용 RSA 키 한 쌍. JVM에 하나만 만들고 PEM·공개키를 같이 내준다 —
 * 서명이 <b>이 공개키로 검증되는가</b>를 시험이 재야 「쿠키 셋이 있다」가 아니라
 * 「CloudFront가 통과시킬 쿠키다」를 잰다.
 *
 * <p>파일로 두지 않는 이유: 비밀키가 공개 저장소에 올라간다. 시험용이라도 두지 않는다.
 */
public final class TestPlaybackKeys {

    public static final String KEY_PAIR_ID = "K-TEST-KEYPAIR";

    private static final KeyPair PAIR = generate();

    private TestPlaybackKeys() {
    }

    /** PKCS#8 PEM 본문. 운영이 환경변수로 넣는 모양과 같다(개행 포함). */
    public static String privateKeyPem() {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(PAIR.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    public static PublicKey publicKey() {
        return PAIR.getPublic();
    }

    /** CloudFront 서명 = 정책 바이트의 SHA1withRSA. 쿠키의 base64 변형({@code -_~})을 되돌린 뒤 검증한다. */
    public static boolean verifies(String policyCookie, String signatureCookie) {
        try {
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(PAIR.getPublic());
            verifier.update(decodeCloudFront(policyCookie));
            return verifier.verify(decodeCloudFront(signatureCookie));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** CloudFront는 표준 base64의 {@code +}·{@code =}·{@code /}를 {@code -}·{@code _}·{@code ~}로 바꾼다. */
    public static byte[] decodeCloudFront(String cookieValue) {
        return Base64.getDecoder().decode(cookieValue.replace('-', '+').replace('_', '=').replace('~', '/'));
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
