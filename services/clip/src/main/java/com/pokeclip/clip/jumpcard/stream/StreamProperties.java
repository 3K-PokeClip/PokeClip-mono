package com.pokeclip.clip.jumpcard.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 실시간 통로 설정. <b>전부 기본값이 있어 yml에 한 줄도 없어도 부팅한다</b> — 비밀이 아니라
 * 숫자 설정이다.
 *
 * <p><b>{@code sendTimeout}이 없다.</b> 막힌 {@code send}를 밖에서 끊는 장치는 작동하지 않는다
 * (2026-08-23 실측) — {@code send()}와 {@code completeWithError()}가 같은 {@code writeLock}을 써서
 * 끊으러 간 스레드가 같이 멈춘다. 막힌 연결은 스트라이프 격리로 가두고 서버의 write timeout이 푼다.
 */
@ConfigurationProperties(prefix = "pokeclip.jump-card.stream")
public record StreamProperties(Duration heartbeat, Duration timeout, int stripes, int queueCapacity,
                               int maxPerUser, int maxPerStream, int maxTotal) {

    public StreamProperties {
        // 0 이하를 허용하면 SseEmitter가 「시한 없음」이 되어 연결이 안 죽는다 —
        // 설정 한 줄(timeout: PT0S)만으로 만료 토큰 불사 연결과 같은 상태가 된다(인가 2차 감사).
        // 아래 숫자 넷과 같은 모양으로 막는다. 대칭이 깨져 있던 자리다.
        if (heartbeat == null || heartbeat.isZero() || heartbeat.isNegative()) {
            heartbeat = Duration.ofSeconds(20);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofHours(4);
        }
        if (stripes <= 0) {
            stripes = 4;
        }
        if (queueCapacity <= 0) {
            queueCapacity = 1000;
        }
        if (maxPerUser <= 0) {
            maxPerUser = 4;
        }
        if (maxPerStream <= 0) {
            maxPerStream = 50;
        }
        if (maxTotal <= 0) {
            maxTotal = 500;
        }
    }
}
