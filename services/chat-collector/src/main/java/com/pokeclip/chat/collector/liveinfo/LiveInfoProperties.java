package com.pokeclip.chat.collector.liveinfo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 방송 정보 주기 수집 설정(POK-234 PR-C).
 *
 * @param clientId     치지직 앱 인증 헤더. <b>전체 라이브 목록</b>이 앱 인증이라 필요하다 —
 *                     채널 하나의 시청자 수를 묻는 공식 창구가 없어 목록을 훑는다
 * @param maxPages     목록 한 바퀴의 페이지 상한. 목록은 시청자 수 내림차순이라 작은 방송은 뒤에 있다
 */
@ConfigurationProperties(prefix = "pokeclip.liveinfo")
public record LiveInfoProperties(boolean enabled, Duration interval, Duration initialDelay,
                                 String clientId, String clientSecret, int maxPages) {

    public void validate() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "pokeclip.liveinfo가 켜졌는데 CHZZK_CLIENT_ID·CHZZK_CLIENT_SECRET이 비어 있다.");
        }
        // 0이면 @Scheduled(fixedDelay)가 끝나자마자 다시 돌아 목록 500장을 쉬지 않고 훑는다(PR #180 codex).
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalStateException("pokeclip.liveinfo.interval은 0보다 커야 한다: " + interval);
        }
        if (maxPages < 1) {
            throw new IllegalStateException("pokeclip.liveinfo.max-pages는 1 이상이어야 한다.");
        }
    }
}
