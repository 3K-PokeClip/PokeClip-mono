package com.pokeclip.chat.collector.purge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 탈퇴 채널 정리와 60일 보관(POK-256).
 *
 * @param interval    탈퇴 정리기 주기
 * @param lateWindow  탈퇴 알림 뒤 이만큼 늦게 받은 채팅까지 지운다(바구니에 남아 있던 것). 지나면 줄을 닫는다
 * @param retain      채팅·후원·방송 정보 보관 기한. 영상 보관(60일, ADR-004)과 같다
 * @param retentionInterval 보관 기한 정리기 주기
 * @param batch       한 번에 지우는 줄 수. 긴 DELETE 하나가 적재를 막지 않게 자른다
 */
@ConfigurationProperties(prefix = "pokeclip.chat-purge")
@Validated
public record ChatPurgeProperties(
        @NotNull Duration interval,
        @NotNull Duration lateWindow,
        @NotNull Duration retain,
        @NotNull Duration retentionInterval,
        @Min(1) int batch
) {
}
