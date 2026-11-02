package com.pokeclip.clip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * auth와 같은 대칭키(HS256)로 토큰을 <b>검증만</b> 한다(ADR-048). 같은 키로 발급도 할 수
 * 있지만 발급자는 auth 하나다 — 이 서버에 JwtEncoder를 만들지 않는다. 만드는 순간
 * 토큰의 출처가 둘이 된다. 시험이 쓰는 발급기는 test 소스의 TestTokens에만 있다.
 */
@Configuration
public class JwtConfig {

    private static final String ALGORITHM = "HmacSHA256";

    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] bytes = properties.secret().getBytes(StandardCharsets.UTF_8);

        // 길이 검증을 @Size로 하지 않는 이유: 바인딩 실패 리포트가 거부된 값을
        // 평문으로 찍는다. 32바이트에 한 글자 모자란 진짜 시크릿이 로그에 남는다.
        // 여기서 막으면 예외 메시지에 값이 들어가지 않는다.
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "pokeclip.jwt.secret이 32바이트 미만이다 (HS256에 필요한 최소 길이). "
                            + "값은 로그에 남기지 않는다.");
        }
        return new SecretKeySpec(bytes, ALGORITHM);
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        // exp 없는 토큰을 통과시키지 않는다. 기본값이 허용이다.
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(timestamps);

        return decoder;
    }
}
