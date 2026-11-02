package com.pokeclip.chat.detector.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 채팅 시각을 영상 위치로 바꿔 주는 창구(POK-92)의 주소. */
@ConfigurationProperties(prefix = "pokeclip.collector-client")
@Validated
public record CollectorClientProperties(@NotBlank String baseUrl) {
}
