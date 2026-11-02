package com.pokeclip.chat.detector.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxAttempts 재시도를 포함한 총 시도 횟수. <b>무한 재시도를 안 하는 이유</b>: 늦게
 *                    도착한 카드는 편집자 화면에 한참 전 장면을 뒤늦게 쏟아 오히려 해롭다
 */
@ConfigurationProperties(prefix = "pokeclip.clip-client")
@Validated
public record ClipClientProperties(@NotBlank String baseUrl, @Min(1) int maxAttempts) {
}
