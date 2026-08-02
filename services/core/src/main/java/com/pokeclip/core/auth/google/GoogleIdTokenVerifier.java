package com.pokeclip.core.auth.google;

import com.pokeclip.core.auth.AuthException;
import com.pokeclip.core.auth.AuthFailure;
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

        // email_verified를 보지 않는다. 계정 식별은 sub로만 하고 이메일은 표시용이라
        // 미인증 이메일이 남의 계정으로 이어지지 않는다. 다만 편집자 초대를 이메일로
        // 매칭하게 되면 그때는 봐야 한다 — 미인증 이메일이 위임 탈취 경로가 된다.
        return new GoogleUser(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("picture"));
    }
}
