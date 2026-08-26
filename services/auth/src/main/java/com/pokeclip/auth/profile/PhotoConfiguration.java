package com.pokeclip.auth.profile;

import com.pokeclip.auth.token.JwtProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 창고 이름이 비면 <b>아무 부품도 만들지 않는다.</b> {@code @ConditionalOnProperty}로는 이 판정을
 * 할 수 없다 — <b>빈 문자열도 「값이 있음」으로 매치된다</b>(chat-collector 실측). 코드로 가른다.
 *
 * <p>꺼진 상태가 CI·팀원 로컬의 기본이고, 1번의 창고 준비를 안 기다리고 개발할 수 있는 이유다.
 * 그때도 이름 수정·로그인은 그대로 돈다(ProfilePhotoDisabledTest).
 */
@Configuration
public class PhotoConfiguration {

    @Bean
    PhotoStorage photoStorage(PhotoProperties properties, JwtProperties jwtProperties) {
        if (!properties.enabled()) {
            return PhotoStorage.NONE;
        }
        requireDistinctSecrets(properties, jwtProperties);
        return new S3PhotoStorage(PhotoS3Clients.create(properties), properties.bucket());
    }

    /**
     * 🔴 <b>사진 표와 로그인 토큰은 다른 키로 서명한다</b>(PhotoToken의 javadoc). 그래야
     * 「이 표로는 그림 한 장 말고 아무것도 못 한다」가 규칙이 아니라 구조가 된다.
     *
     * <p><b>오늘 창구를 지키는 것은 키가 아니라 문법이다</b> — 로그인 토큰은 세 칸, 사진 표는
     * 네 칸이라 서로의 파서가 상대를 못 읽는다(감사자가 두 키를 같게 놓고 창구를 전부 두들겨
     * 확인했다: 하나도 안 뚫린다). <b>그래서 이 줄이 필요하다</b> — 표 형식을 JWT로 바꾸는 순간
     * 그 방어가 통째로 사라지고, 키가 같게 배포돼 있으면 사진 표가 그날 로그인 토큰이 된다.
     *
     * <p>{@code PhotoProperties}가 아니라 여기서 보는 이유: 그 record는 {@code JwtProperties}를
     * 못 본다. 대가로 {@code profile} → {@code token} 의존이 하나 생기는데 의도한 방향이다.
     *
     * <p>{@code JwtConfig.jwtSecretKey}와 같은 방식으로 <b>값을 메시지에 넣지 않는다</b> —
     * 부팅 실패 메시지는 로그·CI 출력에 그대로 남는다.
     */
    private static void requireDistinctSecrets(PhotoProperties properties, JwtProperties jwtProperties) {
        if (properties.tokenSecret().equals(jwtProperties.secret())) {
            throw new IllegalStateException(
                    "PROFILE_PHOTO_TOKEN_SECRET이 JWT_SECRET과 같은 값이다 — 사진 표와 로그인 토큰은 "
                            + "다른 키로 서명해야 「그 표로는 그림 한 장뿐」이 구조가 된다. 값은 로그에 남기지 않는다.");
        }
    }
}
