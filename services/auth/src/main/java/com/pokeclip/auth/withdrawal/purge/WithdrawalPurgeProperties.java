package com.pokeclip.auth.withdrawal.purge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 탈퇴 정리 알림(POK-256).
 *
 * @param clipBaseUrl      clip 주소. 비면 부팅 거부: 비어 있으면 탈퇴한 사람의 방송 기록이 조용히 남는다
 * @param collectorBaseUrl 수집기 주소. 같은 이유로 필수
 * @param dispatch         발송기를 돌리나. 운영 켜짐, 시험만 끈다(시험은 발송 한 바퀴를 손으로 부른다)
 * @param interval         발송 주기
 */
@ConfigurationProperties(prefix = "pokeclip.withdrawal-purge")
@Validated
public record WithdrawalPurgeProperties(
        @NotBlank String clipBaseUrl,
        @NotBlank String collectorBaseUrl,
        boolean dispatch,
        @NotNull Duration interval
) {
}
