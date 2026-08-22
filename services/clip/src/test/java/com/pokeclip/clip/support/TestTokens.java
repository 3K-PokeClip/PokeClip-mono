package com.pokeclip.clip.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * 시험 전용 토큰 발급기. <b>{@code src/main}에는 이런 코드가 없어야 한다</b>(ADR-048) —
 * 발급자는 auth 하나다. clip이 JwtEncoder를 갖는 순간 토큰의 출처가 둘이 된다.
 *
 * <p>키는 {@code application-test.yml}의 {@code pokeclip.jwt.secret}과 같은 문자열이어야
 * 한다. 갈리면 모든 인증 시험이 401로 죽는다.
 */
public final class TestTokens {

    public static final String SECRET = "test-only-jwt-secret-at-least-32-bytes!!";

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private static final JwtEncoder ENCODER = new NimbusJwtEncoder(
            new ImmutableSecret<>(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));

    public static String access(String userId) {
        return access(userId, Instant.now().plus(DEFAULT_TTL));
    }

    public static String access(String userId, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("pokeclip-auth")
                .subject(userId)
                // issuedAt을 exp 앞으로 민다 — NimbusJwtEncoder가 expiresAt <= issuedAt을 거부한다.
                // 그냥 Instant.now()를 쓰면 만료 토큰 시험이 401을 재기 전에 토큰 생성에서
                // IllegalArgumentException으로 죽는다(plan-critic 실측).
                .issuedAt(expiresAt.minus(DEFAULT_TTL))
                .expiresAt(expiresAt)
                .build();
        return ENCODER.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /**
     * exp를 아예 안 담은 토큰. <b>이 입구는 오직 exp 검증(JwtConfig의
     * {@code setAllowEmptyExpiryClaim(false)})을 재려고 있다</b> — 다른 시험에 쓰지 않는다.
     *
     * <p>그 한 줄이 없으면 Nimbus 기본값이 「exp 없으면 통과」라서 <b>영원히 안 죽는 토큰</b>이
     * 생긴다. 한 번 새어 나가면 회수할 방법이 없다.
     *
     * <p>{@code issuedAt}은 둔다 — exp만 없는 토큰을 만들어야 재는 것이 exp 하나로 좁혀진다.
     */
    public static String accessWithoutExpiry(String userId) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("pokeclip-auth")
                .subject(userId)
                .issuedAt(Instant.now())
                .build();
        return ENCODER.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /**
     * 서명 부분의 <b>첫</b> 글자를 바꾼다. 형식은 멀쩡하고 서명만 틀린 토큰이 된다.
     *
     * <p>마지막 글자를 바꾸면 안 된다. HS256 서명 32바이트는 base64url 43글자이고
     * 마지막 글자는 유효 비트가 4개뿐이라(뒤 2비트는 버려진다) 다른 글자가 같은 바이트로
     * 디코딩된다 — 계획의 「마지막 글자를 'a'로」 방식은 <b>원래 글자가 'Y'일 때 서명이
     * 그대로여서 토큰이 통과했다</b>(400회 중 19회, 전부 'Y'. 마지막 글자는 항상 정규형
     * 16글자 중 하나라 확률이 1/16이다). 첫 글자는 6비트가 전부 유효해서 바꾸면 반드시
     * 바이트가 달라진다 — 같은 400회에서 통과 0회.
     */
    public static String tampered(String token) {
        int signatureAt = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureAt);
        return token.substring(0, signatureAt)
                + (first == 'a' ? 'b' : 'a')
                + token.substring(signatureAt + 1);
    }

    private TestTokens() {
    }
}
