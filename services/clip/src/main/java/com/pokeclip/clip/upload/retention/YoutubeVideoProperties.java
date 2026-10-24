package com.pokeclip.clip.upload.retention;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 올린 영상 번호의 30일 확인(POK-423). 켜고 끄는 값이 없다: 끄면 처리방침 13절 「30일마다 확인」을 안 지킨다.
 *
 * @param checkInterval 확인 순회 주기
 * @param checkAfter    마지막 확인(없으면 올린 시각)에서 이만큼 지나면 다시 묻는다. 처리방침이 30일이라 그보다 길게 두지 않는다
 */
@ConfigurationProperties(prefix = "pokeclip.youtube-video")
@Validated
public record YoutubeVideoProperties(@NotNull Duration checkInterval, @NotNull Duration checkAfter) {
}
