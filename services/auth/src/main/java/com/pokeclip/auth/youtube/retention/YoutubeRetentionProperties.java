package com.pokeclip.auth.youtube.retention;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 유튜브 데이터 보관(POK-423, 처리방침 13절). 기한 셋은 처리방침 문장 그대로다: 바꾸면 처리방침도 바꿔야 한다.
 *
 * @param run                 정리 스레드를 돌리나. 운영 켜짐, 시험만 끈다(시험은 한 바퀴를 손으로 부른다)
 * @param interval            한 바퀴 주기
 * @param unlinkedGrace       서비스에서 해제한 뒤 채널 정보 · 영상 번호를 지우기까지(처리방침 「7일 안」)
 * @param brokenGrace         구글 쪽 철회 · 갱신 불가 뒤 지우기까지(처리방침 「30일 안」)
 * @param channelRefreshAfter 연동 중 채널 정보를 다시 받는 간격(처리방침 「30일마다」)
 */
@ConfigurationProperties(prefix = "pokeclip.youtube.retention")
@Validated
public record YoutubeRetentionProperties(
        boolean run,
        @NotNull Duration interval,
        @NotNull Duration unlinkedGrace,
        @NotNull Duration brokenGrace,
        @NotNull Duration channelRefreshAfter
) {
}
