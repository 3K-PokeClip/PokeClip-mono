package com.pokeclip.auth.streamkey.secret;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class SecretStoreConfig {

    private static final int AES_256_BYTES = 32;

    /**
     * base64 디코딩과 길이 검증을 여기서 한다. 프로퍼티에 @Size·@Pattern을 걸면
     * 바인딩 실패 리포트가 거부된 값을 평문으로 찍는다(JwtConfig와 같은 이유).
     * 여기서 막으면 예외 메시지에 값이 들어가지 않는다.
     */
    @Bean
    AesGcmCipher secretCipher(SecretStoreProperties properties) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(properties.key());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "pokeclip.secret-store.key가 base64가 아니다. 값은 로그에 남기지 않는다");
        }
        if (raw.length != AES_256_BYTES) {
            throw new IllegalStateException(
                    "pokeclip.secret-store.key가 32바이트가 아니다 (AES-256에 필요한 길이). "
                            + "값은 로그에 남기지 않는다");
        }
        SecretKey key = new SecretKeySpec(raw, "AES");
        return new AesGcmCipher(key);
    }
}
