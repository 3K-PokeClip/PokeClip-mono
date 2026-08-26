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
 * <h2>{@code DELETE}를 배치로 자르지 않는 이유 — 실측했다</h2>
 *
 * 「한 문장이 {@code socketTimeout}(10초)에 걸리면 트랜잭션이 통째로 되돌아가고, 다음 주기가
 * <b>같은 크기를 다시</b> 시도하는데 그 사이 표는 더 커진다 — 한 번 못 넘기면 영영 못 넘긴다」는
 * 걱정이 있었다. <b>고리는 실재하지만 진입점이 없다.</b>
 *
 * <table>
 *   <tr><th>시나리오</th><th>걸린 시간</th></tr>
 *   <tr><td>140만 줄(PRD 추산 상한)을 한 문장으로</td><td>328~468 ms</td></tr>
 *   <tr><td>같은 것을 <b>커밋까지</b></td><td>316 ms</td></tr>
 *   <tr><td>정상 운영(보관 기간이 지난 한 주기치)</td><td>63 ms</td></tr>
 * </table>
 *
 * <p>10초까지 <b>20배 넘는 여유</b>다. 넘기려면 대략 <b>5,600만 줄</b>이 필요하다(추산의 40배).
 * 2026-08-26 실측 — {@code V401} 그대로(유니크 + 인덱스 둘), 전용 컨테이너, 140만 줄 278MB.
 *
 * <p><b>안 잰 것 셋</b>: 로컬 디스크(운영 스토리지가 아니다) · 다른 부하와 겹칠 때 · 인덱스
 * 정리(autovacuum 몫이라 이 시간에 안 들어간다). <b>20배 여유가 이 셋으로 뒤집히지는 않는다.</b>
 * 표가 이 규모의 열 배를 넘기 시작하면 다시 잰다.
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
