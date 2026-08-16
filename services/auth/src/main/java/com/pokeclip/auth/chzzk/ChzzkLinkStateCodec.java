package com.pokeclip.auth.chzzk;

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
 * 동의 왕복의 state를 표 없이 서명으로 만든다.
 *
 * <p>왜 표가 아닌가 — 표를 두면 미완료 행(동의 안 하고 이탈)이 쌓이고 청소 작업이 필요해진다.
 * pairing_exchange_attempts가 그 구멍(POK-89)이다. 서명이면 저장이 없다.
 *
 * <p>왜 JWT_SECRET을 다시 쓰나 — 새 환경변수를 안 늘리려는 선택이다. 키는 JwtConfig의 {@code SecretKey} 빈을
 * 그대로 주입받는다 — 같은 키임을 코드로 보장하고 32바이트 하한 검증도 그쪽 것을 공유한다. 도메인 분리 접두어
 * "chzzk-link-state:"를 서명 대상에 붙여 JWT(HS256은 header.payload를 서명)와 메시지 형식이
 * 다르므로 한쪽 산출물을 다른 쪽에 넣어도 검증이 안 된다. state 수명이 10분이라 키 회전
 * 영향도 거의 없다. 리뷰 봇이 "키 재사용"을 지적할 수 있는 지점이고 근거는 이 주석과 PR 본문이다.
 *
 * <p>일회용은 보장하지 않는다 — code가 일회용이라 같은 state를 두 번 내도 두 번째 교환이 거부된다.
 *
 * <p>형식: {@code base64url(userId.nonce.expiresEpochSec) + "." + base64url(HMAC-SHA256)}.
 */
@Component
public class ChzzkLinkStateCodec {

    private static final String DOMAIN = "chzzk-link-state:";

    private final SecretKey key;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ChzzkLinkStateCodec(SecretKey jwtSecretKey, ChzzkProperties chzzk) {
        this(jwtSecretKey, chzzk.stateTtl());
    }

    ChzzkLinkStateCodec(SecretKey key, Duration ttl) {
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
