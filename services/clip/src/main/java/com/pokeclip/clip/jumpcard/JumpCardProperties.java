package com.pokeclip.clip.jumpcard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code @EnableConfigurationProperties}를 붙이지 않는다 — {@code ClipApplication}에
 * {@code @ConfigurationPropertiesScan}이 이미 있어 스캔만으로 붙는다.
 */
@ConfigurationProperties(prefix = "pokeclip.jump-card")
public record JumpCardProperties(Duration claimTtl) {

    /** 설정이 없어도 부팅한다. 비밀이 아니라 숫자 설정이라 기본값을 두는 것이 옳다. */
    public JumpCardProperties {
        if (claimTtl == null) {
            claimTtl = Duration.ofMinutes(30);
        }
    }
}
