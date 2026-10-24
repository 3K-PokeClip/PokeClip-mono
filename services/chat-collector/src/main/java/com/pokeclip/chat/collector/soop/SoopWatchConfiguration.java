package com.pokeclip.chat.collector.soop;

import com.pokeclip.chat.collector.broadcast.EndedStream;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.chat.collector.liveinfo.BroadcastInfoStore;
import com.pokeclip.chat.collector.platforms.ClipPlatformClient;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.soop.chat.SoopChat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * 카테고리 이름 표 받기 · 사진 검색을 돌리는 스레드 하나(POK-462). 지켜보기 틱이 기다리지 않게 따로 둔다. 데몬이라 종료를 막지 않고, 줄에 쌓이는 것은
     * 이름마다 한 번이라 작다. 종료 중 실패한 받기는 다음 부팅이 다시 한다.
     */
    static final java.util.concurrent.ExecutorService CATEGORY_LOOKUPS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "soop-category");
        thread.setDaemon(true);
        return thread;
    });

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
                                                         EndedStreamStore endedStreams,
                                                         ObjectProvider<SoopChat> chat,
                                                         @Value("${pokeclip.soop.chat.client-id:}") String soopClientId,
                                                         @Value("${pokeclip.chzzk.base-url:}") String chzzkBaseUrl,
                                                         @Value("${pokeclip.liveinfo.client-id:}") String chzzkClientId,
                                                         @Value("${pokeclip.liveinfo.client-secret:}") String chzzkClientSecret) {
            properties.validate();
            SoopLinkClient links = new SoopLinkClient(builder.clone(), link);
            RestClient.Builder timed = builder.clone().requestFactory(factoryBuilder.build(
                    HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)));
            SoopLiveClient live = new SoopLiveClient(timed.clone(), properties.apiBaseUri(), properties.diagnoseResponse(),
                    categoryNames(timed.clone(), properties.apiBaseUri(), soopClientId),
                    categoryImages(timed.clone(), chzzkBaseUrl, chzzkClientId, chzzkClientSecret));
            // 종료 메모는 정상 종료만(포기 메모는 치지직 쪽 사정이라 SOOP 지켜보기와 무관하다)
            SoopBroadcastWatcher watcher = new SoopBroadcastWatcher(links::resolve, live::check, store::insert, relay, platforms,
                    registry::activeStreamIds,
                    ids -> endedStreams.findAll(ids).values().stream().filter(memo -> !memo.stopped())
                            .map(EndedStream::streamId).collect(java.util.stream.Collectors.toSet()),
                    properties.maxWatched(), Clock.systemUTC(), SoopBroadcastWatcher.TICK_BUDGET);
            // 채팅 받기(PR4). 꺼져 있으면 SoopChat.NONE이라 아무 일도 안 한다
            watcher.attachChat(chat.getIfAvailable(() -> SoopChat.NONE));
            return watcher;
        }

        /**
         * 카테고리 이름 표(POK-462). 앱 아이디는 채팅 받기와 같은 SOOP 앱 값({@code SOOP_CLIENT_ID})이다. 비면 표 없이 카테고리는 null이다
         * (지켜보기만 켜고 앱 값을 안 넣은 환경이 지금처럼 돈다).
         */
        static java.util.function.Function<Object, String> categoryNames(RestClient.Builder builder, String apiBaseUri, String clientId) {
            if (clientId == null || clientId.isBlank()) {
                return number -> null;
            }
            SoopCategoryNames names = new SoopCategoryNames(new SoopCategoryClient(builder, apiBaseUri, clientId)::fetch,
                    CATEGORY_LOOKUPS, Clock.systemUTC());
            names.prefetch();
            return names::nameOf;
        }

        /**
         * SOOP 카테고리 사진을 치지직 카테고리 검색으로 빌린다(POK-462 사용자 결정). 앱 값은 방송 정보 수집과 같은 치지직 앱
         * ({@code CHZZK_CLIENT_ID}·{@code CHZZK_CLIENT_SECRET})이다. 비면 사진은 늘 null이다.
         */
        static java.util.function.Function<String, String> categoryImages(RestClient.Builder builder, String chzzkBaseUrl,
                                                                          String clientId, String clientSecret) {
            if (isBlank(chzzkBaseUrl) || isBlank(clientId) || isBlank(clientSecret)) {
                return name -> null;
            }
            com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient chzzk =
                    new com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient(builder.build(), chzzkBaseUrl, clientId, clientSecret);
            return new BorrowedCategoryImages(chzzk::searchCategories, CATEGORY_LOOKUPS, Clock.systemUTC())::imageOf;
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        @Bean(destroyMethod = "close")
        public Scheduler soopWatchScheduler(SoopBroadcastWatcher watcher, SoopWatchProperties properties) {
            return new Scheduler(watcher, properties);
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

    /**
     * 지켜보기 틱을 전용 스레드 하나에서 돌린다(POK-456). 🔴 새 방송을 넣는 순간 바로 한 번 묻게 하려고 공용 {@code @Scheduled} 대신 쓴다:
     * 공용 스케줄러는 스레드가 여섯이라 거기서 틱을 하나 더 부르면 주기 틱과 겹쳐 돈다(틱은 한 스레드 전제, 미룬 방송 · 토큰 칸).
     * 깨우기가 몰려도(재부착이 한 번에 여럿 넣음) 줄에 하나만 둔다.
     */
    public static class Scheduler implements AutoCloseable {

        private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "soop-watch");
            t.setDaemon(true);
            return t;
        });
        private final AtomicBoolean wakePending = new AtomicBoolean();

        Scheduler(SoopBroadcastWatcher watcher, SoopWatchProperties properties) {
            watcher.pace(new SoopBroadcastWatcher.Pacing(properties.fastInterval(), properties.interval(),
                    properties.fastWindow()), () -> wake(watcher));
            thread.scheduleWithFixedDelay(watcher::tick, properties.initialDelay().toMillis(),
                    properties.fastInterval().toMillis(), TimeUnit.MILLISECONDS);
        }

        private void wake(SoopBroadcastWatcher watcher) {
            if (!wakePending.compareAndSet(false, true)) {
                return;
            }
            try {
                thread.execute(() -> {
                    wakePending.set(false);
                    watcher.tick();
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // 끄는 중이다. 편지 스레드로 번지지 않게 삼킨다
                wakePending.set(false);
            }
        }

        @Override
        public void close() {
            thread.shutdownNow();
        }
    }
}
