package com.pokeclip.auth.soop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 동의 왕복의 state를 표 없이 서명으로 만든다. {@code chzzk/ChzzkLinkStateCodec}의 복제다(왜 표가 아닌가 ·
 * 왜 JWT_SECRET을 다시 쓰나 · 일회용을 보장하지 않는 이유는 그쪽 javadoc). 한쪽을 고치면 나란히 본다.
 *
 * <p>🔴 다른 것은 서명 대상 접두 {@code "soop-link-state:"} 하나다. 같은 키로 서명하므로 접두가 같으면
 * 치지직 동의에서 받은 state가 SOOP 완료에 통한다({@code SoopLinkStateCodecTest}의 여섯째 갈래).
 *
 * <p>SOOP이 콜백에 state를 돌려주는지는 문서에 없다(2026-10-23). 안 돌려주면 웹이 보관해 둔 값을 보낸다.
 *
 * <p>형식: {@code base64url(userId.nonce.expiresEpochSec) + "." + base64url(HMAC-SHA256)}.
 */
@Component
public class SoopLinkStateCodec {

    private static final String DOMAIN = "soop-link-state:";

    private final SecretKey key;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SoopLinkStateCodec(SecretKey jwtSecretKey, SoopProperties soop) {
        this(jwtSecretKey, soop.stateTtl());
    }

    SoopLinkStateCodec(SecretKey key, Duration ttl) {
        this.key = key;
        this.ttl = ttl;
    }

    public String issue(Long userId, Instant now) {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String payload = userId + "." + b64(nonce) + "." + now.plus(ttl).getEpochSecond();
        String encoded = b64(payload.getBytes(UTF_8));
        return encoded + "." + b64(hmac(encoded));
    }

    public boolean matches(String state, Long userId, Instant now) {
        if (state == null) {
            return false;
        }
        int dot = state.lastIndexOf('.');
        if (dot <= 0) {
            return false;
        }
        String encoded = state.substring(0, dot);
        byte[] presented;
        try {
            presented = Base64.getUrlDecoder().decode(state.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return false;
        }
        // 상수 시간 비교(InternalTokenFilter와 같다).
        if (!MessageDigest.isEqual(hmac(encoded), presented)) {
            return false;
        }
        String[] parts;
        try {
            parts = new String(Base64.getUrlDecoder().decode(encoded), UTF_8).split("\\.", 3);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (parts.length != 3) {
            return false;
        }
        try {
            return Long.parseLong(parts[0]) == userId
                    && Instant.ofEpochSecond(Long.parseLong(parts[2])).isAfter(now);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private byte[] hmac(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal((DOMAIN + encodedPayload).getBytes(UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256을 쓸 수 없다", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
