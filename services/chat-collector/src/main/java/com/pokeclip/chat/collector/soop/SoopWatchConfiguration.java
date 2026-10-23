package com.pokeclip.chat.collector.soop;

import com.pokeclip.chat.collector.broadcast.EndedStream;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.chat.collector.liveinfo.BroadcastInfoStore;
import com.pokeclip.chat.collector.platforms.ClipPlatformClient;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/**
 * SOOP 방송 지켜보기 배선(POK-397). 켜지면 지켜보기·스케줄러, 꺼지면 {@link SoopWatch#NONE}. 꺼져도 빈을 두는 이유는 편지 처리기·재부착·
 * 탈퇴 정리가 켜짐을 모르고 타입 하나만 받게 하려는 것이다({@code RelayConfiguration}과 같다).
 */
@Configuration
@EnableConfigurationProperties({SoopWatchProperties.class, LinkProperties.class})
public class SoopWatchConfiguration {

    /** SOOP 호출 시한. 한 틱이 방송마다 최대 두 번 부르므로 전역(접속 2초·읽기 5초)보다 짧게 둔다(계획 검증 L5). */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    @Configuration
    @ConditionalOnProperty(prefix = "pokeclip.soop", name = "watch-enabled", havingValue = "true")
    public static class Enabled {

        /** 🔴 주입받은 팩토리 빌더를 쓴다({@code detect()}는 httpclient5를 잡아 스택이 바뀐다). 빌더는 복제해서 시한을 건다. */
        @Bean
        public SoopBroadcastWatcher soopBroadcastWatcher(RestClient.Builder builder,
                                                         ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                                                         SoopWatchProperties properties, LinkProperties link,
                                                         BroadcastInfoStore store, RelaySink relay,
                                                         ClipPlatformClient platforms, SessionRegistry registry,
                                                         EndedStreamStore endedStreams) {
            properties.validate();
            SoopLinkClient links = new SoopLinkClient(builder.clone(), link);
            SoopLiveClient live = new SoopLiveClient(builder.clone().requestFactory(factoryBuilder.build(
                    HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT))),
                    properties.apiBaseUri(), properties.diagnoseResponse());
            // 종료 메모는 정상 종료만(포기 메모는 치지직 쪽 사정이라 SOOP 지켜보기와 무관하다)
            return new SoopBroadcastWatcher(links::resolve, live::check, store::insert, relay, platforms,
                    registry::activeStreamIds,
                    ids -> endedStreams.findAll(ids).values().stream().filter(memo -> !memo.stopped())
                            .map(EndedStream::streamId).collect(java.util.stream.Collectors.toSet()),
                    properties.maxWatched(), Clock.systemUTC(), SoopBroadcastWatcher.TICK_BUDGET);
        }

        @Bean
        public Scheduler soopWatchScheduler(SoopBroadcastWatcher watcher) {
            return new Scheduler(watcher);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "pokeclip.soop", name = "watch-enabled", havingValue = "false", matchIfMissing = true)
    public static class Disabled {

        @Bean
        public SoopWatch soopWatch() {
            return SoopWatch.NONE;
        }
    }

    public static class Scheduler {

        private final SoopBroadcastWatcher watcher;

        Scheduler(SoopBroadcastWatcher watcher) {
            this.watcher = watcher;
        }

        @Scheduled(fixedDelayString = "${pokeclip.soop.interval}", initialDelayString = "${pokeclip.soop.initial-delay}")
        public void tick() {
            watcher.tick();
        }
    }
}
