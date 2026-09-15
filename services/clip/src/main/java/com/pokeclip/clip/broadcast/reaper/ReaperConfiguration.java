package com.pokeclip.clip.broadcast.reaper;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.intake.EndedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * 켜져 있을 때만 치우개를 만든다. {@code @EnableScheduling}도 여기 붙는다 — 이 서버의 첫 {@code @Scheduled}라
 * 앱 클래스에 두면 꺼진 배포에서도 스케줄러 스레드가 뜬다.
 *
 * <p>화면 통보 훅은 {@code ObjectProvider}로 받는다 — 통로 등록부({@code CardStreamRegistry})가 없는
 * 슬라이스 컨텍스트에서도 뜨게, 그리고 편지 러너가 같은 훅을 같은 방식으로 받는다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "pokeclip.broadcast.reaper", name = "enabled", havingValue = "true")
public class ReaperConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ReaperConfiguration.class);

    @Bean
    public StaleBroadcastReaper staleBroadcastReaper(JdbcTemplate jdbc, BroadcastRepository broadcasts,
                                                     TransactionTemplate tx, ReaperProperties properties,
                                                     ObjectProvider<EndedListener> endedListener) {
        log.info("broadcast.reaper.enabled interval={} grace={}", properties.interval(), properties.grace());
        return new StaleBroadcastReaper(jdbc, broadcasts, tx, endedListener.getIfAvailable(),
                properties.grace(), Instant::now);
    }
}
