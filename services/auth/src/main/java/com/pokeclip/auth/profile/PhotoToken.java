package com.pokeclip.auth.profile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 그림 한 장을 여는 표.
 *
 * <p><b>JWT가 아니고 로그인 토큰과 다른 키를 쓴다.</b> 그래야 「이 표로는 다른 것을 아무것도 못 한다」가
 * 규칙이 아니라 구조가 된다 — 규칙은 어길 수 있지만 키가 다르면 어길 방법이 없다(PRD).
 * 로그인 토큰 검증기는 이 글자를 아예 못 읽고, 이 검증기도 로그인 토큰을 못 읽는다.
 * ChzzkLinkStateCodec이 JWT_SECRET을 재사용하는 것과 정반대 선택이고, 이유는 대가가 달라서다 —
 * 그쪽은 서버만 읽는 state이고 이쪽은 <b>주소에 실려 브라우저 히스토리·리퍼러·프록시 로그에 남는다.</b>
 *
 * <p><b>「자격증명을 주소에 안 싣는다」의 예외가 아니다.</b> 그 원칙이 지키는 물건은 계정 전체를 여는
 * 열쇠(갱신 토큰)다. 이 표는 그 회원의 프로필 그림 한 장을 열 뿐이고 몇 분이면 죽는다.
 * <b>여기에 다른 권한을 얹지 마라</b> — 얹는 순간 진짜 자격증명이 되고 그 원칙과 정면으로 부딪힌다.
 *
 * <p>모양: {@code userId.exp.version.signature}. 네 칸을 점으로 가르는 것이 안전한 이유는
 * base64url 알파벳(A-Z a-z 0-9 - _)에 점이 없어서다 — 서명 안에 구분자가 섞일 수 없다.
 *
 * <p><b>만료를 10분 경계에 맞춰 만든다.</b> 회원 정보는 60초마다·탭에 돌아올 때마다 다시 불리는데,
 * 부를 때마다 표를 새로 만들면 주소가 매번 달라져 같은 그림을 계속 다시 받는다. 경계에 맞추면
 * 같은 10분 안에서는 글자까지 같아 브라우저 캐시가 그대로 먹는다.
 */
public final class PhotoToken {

    /** 10분. 이 창 안에서는 같은 글자가 나온다. */
    public static final long SLOT_SECONDS = 600;

    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private PhotoToken() { }

    /** {@code photoVersion}의 단위는 여기서 정하지 않는다 — 부르는 쪽(PhotoUrls)이 정하고 여기는 싣기만 한다. */
    public static String issue(String secret, long userId, long photoVersion, Instant now) {
        long exp = expiryFor(now);
        String payload = userId + "." + exp + "." + photoVersion;
        return payload + "." + sign(secret, payload);
    }

    /**
     * 다음 10분 경계 + 10분. 그래서 남은 수명이 10~20분이고, 같은 창 안에서는 값이 고정이다.
     * 「지금 + 20분」으로 만들면 초마다 값이 달라져 캐시가 통째로 무의미해진다.
     */
    private static long expiryFor(Instant now) {
        long sec = now.getEpochSecond();
        return (sec / SLOT_SECONDS + 1) * SLOT_SECONDS + SLOT_SECONDS;
    }

    public static boolean verify(String secret, String token, long expectedUserId, Instant now) {
        if (token == null) {
            return false;
        }
        // limit −1이 아니면 split이 <b>꼬리의 빈 칸을 지운다</b>(Java split(regex) 규약).
        // 그러면 "7.100.0.sig."도 길이가 4이고 parts[3]은 여전히 서명이라 그대로 통과한다 —
        // 점을 몇 개든 붙일 수 있으니 같은 사진에 유효한 주소가 무한히 생기고, 같은 십분 창에서
        // 글자까지 같게 만들어 둔 캐시 전제가 무너진다.
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        long userId;
        long exp;
        try {
            userId = Long.parseLong(parts[0]);
            exp = Long.parseLong(parts[1]);
            // version은 모양만 보고 값은 안 본다. 일부러 그렇게 뒀다 — 이 값이 하는 일은 사진을 바꿨을 때
            // 주소를 달라지게 해서 캐시를 비우는 것뿐이라, 틀려도 대가가 「캐시가 한 번 더 도는 것」이다.
            // 반대로 표의 version과 지금 표(表)의 version이 같은지까지 보면, 사진을 바꾸는 순간 브라우저가
            // 아직 들고 있는 옛 주소가 전부 404가 된다. 검사를 빠뜨린 것이 아니니 조이지 마라.
            Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (userId != expectedUserId || exp <= now.getEpochSecond()) {
            return false;
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        // 시간 일정 비교 — 서명 비교에 조기 반환이 있으면 한 바이트씩 맞춰 갈 수 있다
        // (ChzzkLinkStateCodec·InternalTokenFilter와 같다).
        return MessageDigest.isEqual(
                sign(secret, payload).getBytes(UTF_8),
                parts[3].getBytes(UTF_8));
    }

    private static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), HMAC));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            // 알고리즘은 JDK 표준이고 키는 부팅에서 검증됐다 — 여기 오면 설정이 아니라 런타임이 깨진 것이다
            throw new IllegalStateException("사진 표 서명 실패", e);
        }
    }
}
