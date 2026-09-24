package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방송 정보를 켠 운영 컨텍스트가 뜨고 수집기·스케줄러가 물린다. 중계와 같이 켜서 {@code RelaySink} 후보가
 * 하나로 잡히는지도 본다(바구니가 {@code RelaySink}를 구현한다).
 */
@FakeChzzkTest
@TestPropertySource(properties = {
        "pokeclip.liveinfo.enabled=true",
        "pokeclip.liveinfo.client-id=wiring-id",
        "pokeclip.liveinfo.client-secret=wiring-secret",
        "pokeclip.liveinfo.initial-delay=PT1H",
        "pokeclip.relay.enabled=true",
        "pokeclip.link.internal-token=liveinfo-wiring-token"
})
class LiveInfoWiringTest extends IntegrationTestSupport {

    @Autowired ApplicationContext context;

    @Test
    void 켜면_수집기와_스케줄러가_뜨고_스케줄러_풀은_셋이다() {
        assertThat(context.getBeansOfType(BroadcastInfoCollector.class)).hasSize(1);
        assertThat(context.getBeansOfType(LiveInfoConfiguration.Scheduler.class)).hasSize(1);
        assertThat(context.getEnvironment().getProperty("spring.task.scheduling.pool.size")).isEqualTo("3");
    }
}
