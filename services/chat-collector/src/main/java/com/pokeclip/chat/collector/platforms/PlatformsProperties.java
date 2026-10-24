package com.pokeclip.chat.collector.platforms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * clip 플랫폼 묶음 알림 설정(POK-397). 재부착·중계와 같은 환경변수({@code CLIP_BASE_URL})를 받되 record는 따로 둔다: 셋은 따로 켠다.
 *
 * <p>검증을 애노테이션이 아니라 {@link #validate()}로 둔 이유는 {@code RelayProperties}와 같다({@code @ConfigurationPropertiesScan}이
 * 모든 컨텍스트에 올리므로, 걸면 알림을 안 쓰는 부팅까지 죽는다). {@link ClipPlatformClient} 생성자가 부른다.
 */
@ConfigurationProperties(prefix = "pokeclip.platforms")
public record PlatformsProperties(String clipBaseUrl) {

    /** @throws IllegalStateException clip 주소가 비어 있으면 */
    public void validate() {
        if (clipBaseUrl == null || clipBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "pokeclip.platforms.clip-base-url이(가) 비어 있다. CLIP_BASE_URL 환경변수를 준다.");
        }
    }
}
