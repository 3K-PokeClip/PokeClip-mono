package com.pokeclip.chat.detector.observe;

import com.pokeclip.chat.detector.metrics.ChatWindowReader;
import com.pokeclip.chat.detector.metrics.LateArrivalCount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * 유예값을 넘겨 도착한 채팅이 몇 건인지 주기적으로 센다.
 *
 * <p><b>이 숫자가 없으면 유예는 영영 2초에 머문다</b>(사용자 결정). 유예는 우리 구간 목표
 * 3초의 3분의 2를 먹는 큰 값이라, 실측 없이 고정하면 안 된다.
 *
 * <p><b>읽는 법</b>: {@code beyondGrace}가 0에 가까우면 유예를 줄여 지연을 벌 수 있다.
 * {@code beyondWindowAndGrace}가 크면 실제로 채팅을 놓치고 있으므로 늘려야 한다.
 * 얼마로 올릴지는 {@code maxDelayMs}가 정한다.
 *
 * <p><b>{@code @Component}가 아니다.</b> {@code Duration}·{@code Supplier}는 스프링이 만들 수
 * 있는 타입이 아니라 부팅이 죽는다. 조립은 {@code DetectorApplication}의 {@code @Bean}이 한다.
 */
public class LateArrivalReporter {

    private static final Logger log = LoggerFactory.getLogger(LateArrivalReporter.class);

    private final ChatWindowReader reader;
    private final Supplier<List<String>> activeStreams;
    private final Duration grace;
    private final long publishWindowMs;
    private final Duration lookback;
    private final Supplier<Instant> clock;

    public LateArrivalReporter(ChatWindowReader reader, Supplier<List<String>> activeStreams,
                               Duration grace, long publishWindowMs, Duration lookback,
                               Supplier<Instant> clock) {
        this.reader = reader;
        this.activeStreams = activeStreams;
        this.grace = grace;
        this.publishWindowMs = publishWindowMs;
        this.lookback = lookback;
        this.clock = clock;
    }

    /**
     * <b>{@code Throwable}까지 잡는다.</b> {@code @Scheduled}는 한 번 던지면 그 뒤 주기가 안 돈다.
     * 그리고 이것은 <b>관측</b>이다 — 관측이 판별을 멈추면 앞뒤가 바뀐다.
     */
    @Scheduled(fixedDelayString = "${pokeclip.detection.late-report-interval}",
            initialDelayString = "${pokeclip.detection.late-report-interval}")
    public void report() {
        try {
            Instant now = clock.get();
            Instant since = now.minus(lookback);
            List<String> streams = activeStreams.get();
            if (streams.isEmpty()) {
                // 조용한 시간대에 0만 적힌 줄이 쌓이지 않게 한다.
                return;
            }

            long graceMs = grace.toMillis();
            LateArrivalCount total = LateArrivalCount.EMPTY;
            for (String streamId : streams) {
                total = total.plus(reader.lateArrivals(streamId, since, now,
                        graceMs, publishWindowMs + graceMs));
            }

            // 한 줄에 다 싣는다. 나눠 찍으면 같은 시점의 값을 이어 붙이는 일이 읽는 쪽 몫이 된다.
            log.info("detect.late_arrivals windowGraceMs={} lookbackMs={} streams={} "
                            + "total={} beyondGrace={} beyondWindowAndGrace={} maxDelayMs={}",
                    graceMs, lookback.toMillis(), streams.size(),
                    total.total(), total.beyondGrace(), total.beyondWindowAndGrace(),
                    total.maxDelayForLog());
        } catch (Throwable t) {
            log.warn("detect.late_arrivals_failed causeType={}", t.getClass().getSimpleName());
        }
    }
}
