package com.pokeclip.chat.collector.soop;

import com.pokeclip.chat.collector.liveinfo.BroadcastInfoStore;
import com.pokeclip.chat.collector.platforms.ClipPlatformClient;
import com.pokeclip.chat.collector.platforms.PlatformsConfiguration;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SOOP 지켜보기 배선(POK-397). 새 스프링 컨텍스트를 시험 지원 클래스에 더하지 않으려고 {@link ApplicationContextRunner}로 이 설정만 띄운다.
 * 재는 것: 켜면 지켜보기·스케줄러·공용 clip 알림이 뜬다(방송 정보가 꺼져 있어도) · 꺼지면 {@link SoopWatch#NONE} · 내부 토큰이 비면 부팅이 죽는다.
 */
class SoopWatchWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SoopWatchConfiguration.class, PlatformsConfiguration.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(ClientHttpRequestFactoryBuilder.class, ClientHttpRequestFactoryBuilder::jdk)
            .withBean(BroadcastInfoStore.class, () -> mock(BroadcastInfoStore.class))
            .withBean(RelaySink.class, () -> RelaySink.NONE)
            .withBean(SessionRegistry.class, () -> mock(SessionRegistry.class))
            .withBean(com.pokeclip.chat.collector.broadcast.EndedStreamStore.class,
                    () -> mock(com.pokeclip.chat.collector.broadcast.EndedStreamStore.class))
            .withPropertyValues(
                    "pokeclip.soop.api-base-uri=http://localhost:1",
                    "pokeclip.soop.interval=PT1M",
                    "pokeclip.soop.fast-interval=PT10S",
                    "pokeclip.soop.fast-window=PT3M",
                    "pokeclip.soop.initial-delay=PT1H",
                    "pokeclip.soop.max-watched=100",
                    "pokeclip.platforms.clip-base-url=http://localhost:1",
                    "pokeclip.link.auth-base-url=http://localhost:1",
                    "pokeclip.link.internal-token=wiring-token",
                    "pokeclip.liveinfo.enabled=false");

    @Test
    void 켜면_지켜보기와_스케줄러와_공용_clip_알림이_뜬다() {
        runner.withPropertyValues("pokeclip.soop.watch-enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SoopWatch.class)).isInstanceOf(SoopBroadcastWatcher.class);
            assertThat(context).hasSingleBean(SoopWatchConfiguration.Scheduler.class);
            assertThat(context).as("방송 정보가 꺼져 있어도 지켜보기가 쓴다").hasSingleBean(ClipPlatformClient.class);
        });
    }

    /** 꺼짐이 기본이다(PR3b 배포 뒤에 켠다). 그때 SOOP·auth를 부르는 부품이 아예 없다. */
    @Test
    void 꺼지면_빈_지켜보기만_있다() {
        runner.withPropertyValues("pokeclip.soop.watch-enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SoopWatch.class)).isSameAs(SoopWatch.NONE);
            assertThat(context).doesNotHaveBean(SoopWatchConfiguration.Scheduler.class);
            assertThat(context).doesNotHaveBean(ClipPlatformClient.class);
        });
    }

    @Test
    void 켰는데_내부_토큰이_비면_부팅이_죽는다() {
        runner.withPropertyValues("pokeclip.soop.watch-enabled=true", "pokeclip.link.internal-token=").run(context ->
                assertThat(context).hasFailed());
    }
}
