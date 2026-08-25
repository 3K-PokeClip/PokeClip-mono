package com.pokeclip.chat.detector;

import com.pokeclip.chat.detector.config.DetectionProperties;
import com.pokeclip.chat.detector.metrics.ChatMetricsStore;
import com.pokeclip.chat.detector.metrics.ChatWindowReader;
import com.pokeclip.chat.detector.observe.LateArrivalReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DetectorApplication {

    private static final Logger log = LoggerFactory.getLogger(DetectorApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(DetectorApplication.class, args);
    }

    /**
     * 발행 전용 스레드. <b>판정 스레드와 갈라야 한다</b> — clip이 죽어 있으면 재시도가 초
     * 단위로 걸리는데 그동안 판정이 멈추면 안 된다(POK-139).
     *
     * <p>큐를 무제한으로 두지 않는다. clip이 오래 죽어 있으면 못 보낼 카드가 무한히 쌓이고
     * 그 카드들은 어차피 도착해도 늦어 쓸모가 없다. 큐가 차면 <b>버린다</b> —
     * 그 창은 발행권이 이미 잡혀 있어 다시 시도되지 않는다.
     *
     * <p>🔴 <b>버릴 때 반드시 로그를 남긴다.</b> {@code ThreadPoolExecutor.DiscardPolicy}는
     * <b>아무 말 없이</b> 버린다 — 발행권은 이미 잡혀 다시 시도되지도 않으므로 그 카드는
     * <b>흔적 없이 사라진다.</b> 「clip이 죽어서 카드가 안 나갔다」와 「급증이 없었다」가
     * 로그에서 똑같아 보이는 것이 이 기능에서 가장 나쁜 실패다. 그래서 표준 정책 대신
     * 직접 쓴 핸들러를 건다(2026-08-26 주입으로 확인: 표준 정책이면 아무 검사도 안 깨진다).
     *
     * <p>🔴 <b>이 빈이 생기면 Boot의 {@code applicationTaskExecutor}가 사라진다</b> —
     * 자동설정이 {@code @ConditionalOnMissingBean(Executor.class)}라 물러난다(계획 검증 F10이
     * 실제 컨텍스트에서 확인: {@code TaskExecutor} 후보는 {@code [publishExecutor, taskScheduler]},
     * {@code applicationTaskExecutor}는 없음). 이 서버는 {@code @Async}도 MVC 비동기도 안 써서
     * 지금은 무해하다.
     *
     * <p><b>다만 {@code DetectionCycle} 생성자의 파라미터 이름이 {@code publishExecutor}인 것에
     * 기대고 있다</b> — 후보가 둘이라 타입만으로는 못 고르고 이름으로 풀린다.
     * <b>그 파라미터 이름을 바꾸면 주입이 모호해져 부팅이 죽는다.</b>
     */
    /**
     * {@code Duration}·{@code Supplier<Instant>}는 스프링이 만들 수 있는 타입이 아니라
     * {@code @Component}로 두면 부팅이 죽는다. 그래서 여기서 조립한다.
     */
    @Bean
    MetricsSweeper metricsSweeper(ChatMetricsStore store, DetectionProperties props) {
        return new MetricsSweeper(store, props.retention(), Instant::now);
    }

    @Bean
    LateArrivalReporter lateArrivalReporter(ChatWindowReader reader, DetectionProperties props) {
        return new LateArrivalReporter(reader,
                () -> reader.activeStreams(Instant.now().minus(props.activeStreamWindow())),
                props.windowGrace(), props.publishWindowMs(), props.lateReportInterval(), Instant::now);
    }

    @Bean
    TaskExecutor publishExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("detect-publish-");
        executor.setRejectedExecutionHandler((task, rejectingExecutor) ->
                log.warn("detect.publish_dropped reason=queue_full queueSize={} activeCount={}",
                        rejectingExecutor.getQueue().size(), rejectingExecutor.getActiveCount()));
        return executor;
    }
}
