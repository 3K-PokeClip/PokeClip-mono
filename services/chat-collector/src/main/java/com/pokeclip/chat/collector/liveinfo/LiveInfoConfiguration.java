package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * 방송 정보 주기 수집(POK-234 PR-C). {@code pokeclip.liveinfo.enabled}가 켜졌을 때만 뜬다(기본 꺼짐 —
 * 켜져 있으면 CI·팀원 로컬이 뜰 때마다 치지직 앱 인증 호출을 쓴다).
 */
@Configuration
@ConditionalOnProperty(prefix = "pokeclip.liveinfo", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(LiveInfoProperties.class)
public class LiveInfoConfiguration {

    @Bean
    public BroadcastInfoCollector broadcastInfoCollector(SessionRegistry registry, RestClient.Builder builder,
                                                         ChzzkProperties chzzk, LiveInfoProperties properties,
                                                         BroadcastInfoStore store, RelaySink relay) {
        properties.validate();
        ChzzkLiveInfoClient client = new ChzzkLiveInfoClient(builder.build(), chzzk.baseUrl(),
                properties.clientId(), properties.clientSecret());
        return new BroadcastInfoCollector(registry, client, store::insert, relay, properties, Clock.systemUTC());
    }

    @Bean
    public Scheduler broadcastInfoScheduler(BroadcastInfoCollector collector) {
        return new Scheduler(collector);
    }

    public static class Scheduler {

        private final BroadcastInfoCollector collector;

        Scheduler(BroadcastInfoCollector collector) {
            this.collector = collector;
        }

        @Scheduled(fixedDelayString = "${pokeclip.liveinfo.interval}",
                initialDelayString = "${pokeclip.liveinfo.initial-delay}")
        public void tick() {
            collector.tick();
        }
    }
}
