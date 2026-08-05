package com.pokeclip.chat.collector;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * <p><b>enabled 기본값이 false다.</b> 켜져 있으면 CI·테스트·남의 로컬이 뜰 때마다
 * 치지직에 붙으려 하고, 연결 상한 3개(Access Token 기준)를 말없이 먹는다.
 * 그러면 정작 실측할 때 막히는데 원인이 어디에도 안 보인다.
 * 실측은 프로파일 local에서만 켠다.
 *
 * <p><b>accessToken이 @NotBlank를 통과해도 만료됐을 수 있다.</b> 그때는 부팅이
 * 성공하고 연결만 실패한다 — 그래서 실패 사유를 로그와 health 양쪽에 남긴다.
 */
@ConfigurationProperties(prefix = "pokeclip.chzzk")
@Validated
public record ChzzkProperties(
        boolean enabled,
        @NotBlank String accessToken,
        @NotBlank String baseUrl,
        Duration establishTimeout
) { }
