package com.pokeclip.auth.profile;

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
    PhotoStorage photoStorage(PhotoProperties properties) {
        if (!properties.enabled()) {
            return PhotoStorage.NONE;
        }
        return new S3PhotoStorage(PhotoS3Clients.create(properties), properties.bucket());
    }
}
