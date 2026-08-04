package com.pokeclip.auth.streamkey.secret;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * AES-256-GCM 봉투. 출력은 {@code nonce(12B) || ciphertext || tag(16B)}다.
 *
 * <p><b>nonce는 매번 새로 뽑는다.</b> GCM에서 같은 키로 nonce를 재사용하면
 * 두 평문의 XOR이 드러나고 인증 키까지 복구돼 사실상 키가 깨진다.
 */
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(SecretKey key) {
        this.key = key;
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return ByteBuffer.allocate(nonce.length + sealed.length)
                    .put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            // 예외 메시지에 평문을 담지 않는다.
            throw new IllegalStateException("비밀을 암호화하지 못했다", e);
        }
    }

    public String decrypt(byte[] envelope) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, envelope, 0, NONCE_BYTES));
            byte[] plain = cipher.doFinal(
                    envelope, NONCE_BYTES, envelope.length - NONCE_BYTES);

            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("비밀을 복호화하지 못했다", e);
        }
    }
}
