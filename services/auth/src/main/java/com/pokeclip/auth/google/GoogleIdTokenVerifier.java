package com.pokeclip.auth.google;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.List;
import java.util.Set;

public class GoogleIdTokenVerifier {

    /**
     * 구글은 id_token의 iss를 이 둘 중 하나로 보낸다고 문서에 명시하고, 구글 공식
     * 검증 라이브러리도 둘 다 받는다. 하나만 받으면 우리가 더 엄격해서 로그인이
     * 전부 막힌다 — JwtIssuerValidator가 단일 값만 받아 쓰지 않는 이유다.
     */
    private static final Set<String> ACCEPTED_ISSUERS =
            Set.of("https://accounts.google.com", "accounts.google.com");

    private final JwtDecoder decoder;

    public GoogleIdTokenVerifier(NimbusJwtDecoder decoder, String clientId) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        // exp 없는 토큰을 통과시키지 않는다. 기본값이 허용이다.
        timestamps.setAllowEmptyExpiryClaim(false);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtClaimValidator<Object>("iss",
                        iss -> iss != null && ACCEPTED_ISSUERS.contains(iss.toString())),
                new JwtClaimValidator<List<String>>("aud",
                        aud -> aud != null && aud.contains(clientId))));
        this.decoder = decoder;
    }

    public GoogleUser verify(String idToken) {
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException e) {
            throw new AuthException(AuthFailure.GOOGLE_ID_TOKEN_INVALID, "구글 id_token 검증 실패", e);
        }

        // 🔴 이메일 인증을 마친 계정만 받는다(POK-256, auth/CLAUDE.md 알려진 구멍 0번을 갚는다).
        // 이메일이 편집자 초대의 열쇠라(POK-57), 미인증 주소로 가입하면 남에게 갈 초대를 대신 받을 수 있었다.
        // 구글은 이 값을 대개 불리언으로, 옛 발급분은 문자열 "true"로 싣는다. 둘 다 참으로 읽고 나머지(빠짐 포함)는 막는다.
        //
        // users.email의 유일 제약(POK-57, 같은 주소를 다른 sub가 들고 오면 409)은 그대로다.
        Object emailVerified = jwt.getClaim("email_verified");
        if (!Boolean.TRUE.equals(emailVerified) && !"true".equals(emailVerified)) {
            throw new AuthException(AuthFailure.GOOGLE_EMAIL_UNVERIFIED, "이메일 인증을 안 마친 구글 계정");
        }
        return new GoogleUser(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("picture"));
    }
}
