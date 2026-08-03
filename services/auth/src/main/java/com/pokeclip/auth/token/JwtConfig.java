package com.pokeclip.auth.token;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtConfig {

    private static final String ALGORITHM = "HmacSHA256";

    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] bytes = properties.secret().getBytes(StandardCharsets.UTF_8);

        // 길이 검증을 @Size로 하지 않는 이유: 바인딩 실패 리포트가 거부된 값을
        // 평문으로 찍는다. 32자에 한 글자 모자란 진짜 시크릿이 로그에 남는다.
        // 여기서 막으면 예외 메시지에 값이 들어가지 않는다.
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "pokeclip.jwt.secret이 32바이트 미만이다 (HS256에 필요한 최소 길이). "
                            + "값은 로그에 남기지 않는다.");
        }
        return new SecretKeySpec(bytes, ALGORITHM);
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
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
