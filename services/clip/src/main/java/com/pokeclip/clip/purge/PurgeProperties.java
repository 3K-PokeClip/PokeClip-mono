package com.pokeclip.clip.purge;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 탈퇴 정리기(POK-256). 주기 하나뿐이다. 켜고 끄는 값이 없다: 꺼 두면 탈퇴한 사람의 기록이 남는다.
 */
@ConfigurationProperties(prefix = "pokeclip.purge")
@Validated
public record PurgeProperties(@NotNull Duration interval) {
}
