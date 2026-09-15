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

    /**
     * 🔴 <b>켜져 있는데 1번 장부 표가 없으면 부팅을 거부한다</b>(봇 리뷰 1판 codex P2). 장부는 clip의 Flyway가
     * 만들지 않는다 — media의 인덱서가 만든다(ADR-030). dev 배포처럼 media 없이 clip만 뜨는 자리에서 이 스위치를
     * 켜면 매 회차 질의가 표 없음으로 터지고, 회차는 {@code Throwable}을 삼키므로 <b>clip은 초록인데 치우개는 영영
     * 안 돈다</b> — 이 서버가 반복해서 데인 「설정은 켰는데 그 기능만 조용히 죽어 있다」의 모양이다.
     * 기본이 꺼짐이라 media 없는 배포의 부팅은 안 바뀐다.
     */
    static void requireSegmentLedger(JdbcTemplate jdbc) {
        String found = jdbc.queryForObject("SELECT to_regclass('stream_segments')::text", String.class);
        if (found == null) {
            throw new IllegalStateException(
                    "pokeclip.broadcast.reaper.enabled=true인데 stream_segments 표가 없다. 치우개는 1번 장부의 마지막 조각을 "
                    + "읽는데 그 표는 media 인덱서가 만든다 — media를 먼저 세우거나 BROADCAST_REAPER_ENABLED=false로 꺼라.");
        }
    }

    @Bean
    public StaleBroadcastReaper staleBroadcastReaper(JdbcTemplate jdbc, BroadcastRepository broadcasts,
                                                     TransactionTemplate tx, ReaperProperties properties,
                                                     ObjectProvider<EndedListener> endedListener) {
        requireSegmentLedger(jdbc);
        log.info("broadcast.reaper.enabled interval={} grace={}", properties.interval(), properties.grace());
        return new StaleBroadcastReaper(jdbc, broadcasts, tx, endedListener.getIfAvailable(),
                properties.grace(), Instant::now);
    }
}
