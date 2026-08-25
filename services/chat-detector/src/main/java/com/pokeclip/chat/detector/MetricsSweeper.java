package com.pokeclip.chat.detector;

import com.pokeclip.chat.detector.metrics.ChatMetricsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 보관 기간이 지난 집계 줄을 주기적으로 치운다.
 *
 * <p>창 셋을 쓰면 방송 1시간에 약 2,300줄이 쌓인다. 동시 방송 100개·6시간이면 140만 줄이라
 * 치우지 않으면 계속 커진다.
 *
 * <p><b>{@code @Component}가 아니다.</b> {@code Duration}·{@code Supplier<Instant>}는 스프링이
 * 만들 수 있는 타입이 아니라 부팅이 죽는다(chat-collector가 실측한 자리다).
 * 조립은 {@link DetectorApplication}의 {@code @Bean}이 한다.
 */
public class MetricsSweeper {

    private static final Logger log = LoggerFactory.getLogger(MetricsSweeper.class);

    private final ChatMetricsStore store;
    private final Duration retention;
    private final Supplier<Instant> clock;

    public MetricsSweeper(ChatMetricsStore store, Duration retention, Supplier<Instant> clock) {
        this.store = store;
        this.retention = retention;
        this.clock = clock;
    }

    /**
     * <b>{@code Throwable}까지 잡는다.</b> {@code @Scheduled}는 태스크가 한 번이라도 던지면
     * 그 뒤 주기가 안 돈다 — 표가 영영 안 치워지는데 아무 신호도 없다.
     *
     * <p>치운 줄이 없으면 로그를 안 남긴다 — 10분마다 {@code count=0}이 쌓이면 진짜 신호가 묻힌다.
     */
    @Scheduled(fixedDelayString = "${pokeclip.detection.sweep-interval}",
            initialDelayString = "${pokeclip.detection.sweep-interval}")
    public void sweep() {
        try {
            int swept = store.sweepOlderThan(clock.get().minus(retention));
            if (swept > 0) {
                log.info("detect.metrics_swept count={}", swept);
            }
        } catch (Throwable t) {
            log.warn("detect.metrics_sweep_failed causeType={}", t.getClass().getSimpleName());
        }
    }
}
