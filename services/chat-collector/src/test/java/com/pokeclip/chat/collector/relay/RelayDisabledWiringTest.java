package com.pokeclip.chat.collector.relay;

import com.pokeclip.chat.collector.CollectorHealth;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본(꺼짐) 컨텍스트 — 중계 부품을 하나도 안 만들고 <b>clip을 안 두드린다</b>. 등록부·health·종료는
 * {@code NONE}을 받아 켜짐/꺼짐을 모른다. 다른 {@code @FakeChzzkTest}와 같은 컨텍스트를 나눠 쓴다.
 */
@FakeChzzkTest
class RelayDisabledWiringTest extends IntegrationTestSupport {

    @Autowired ApplicationContext context;

    @Test
    void 끄면_NONE만_있고_clip_클라이언트도_중계_스레드도_없다() {
        assertThat(context.getBean(RelaySink.class)).isSameAs(RelaySink.NONE);
        assertThat(context.getBean(RelayCounters.class)).isSameAs(RelayCounters.NONE);
        assertThat(context.getBean(RelayLifecycle.class)).isSameAs(RelayLifecycle.NONE);
        assertThat(context.getBeansOfType(ClipRelayClient.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatRelayer.class)).isEmpty();
        assertThat(ReflectionTestUtils.getField(context.getBean(SessionRegistry.class), "relay"))
                .isSameAs(RelaySink.NONE);
        assertThat(context.getBean(CollectorHealth.class).health().getDetails().get("relay"))
                .as("「꺼져서 0」과 「켜졌는데 0」을 가른다").isEqualTo("disabled");
    }
}
