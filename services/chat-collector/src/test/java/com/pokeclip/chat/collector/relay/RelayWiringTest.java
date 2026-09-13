package com.pokeclip.chat.collector.relay;

import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>중계를 켠 운영 컨텍스트</b>에서 부품이 실제로 물리는가.
 *
 * <p>🔴 수집기에는 {@code HttpClientSettings}·{@code ClientHttpRequestFactoryBuilder}를 주입받는 코드가
 * 이 카드 전에 <b>0곳</b>이었다(F11). 자동 설정이 그 빌더 빈을 정말 하나 만들어 주는지, 그리고
 * {@code spring.http.clients.imperative.factory=jdk}를 따르는지는 떼어 낸 설정으로는 못 잰다 —
 * 전체 컨텍스트를 띄운다.
 */
@FakeChzzkTest
@TestPropertySource(properties = {
        "pokeclip.relay.enabled=true",
        "pokeclip.link.internal-token=relay-wiring-token"
})
class RelayWiringTest extends IntegrationTestSupport {

    @Autowired ApplicationContext context;

    @Test
    void 켜면_clip_중계_클라이언트가_JDK_스택의_빌더로_만들어진다() {
        assertThat(context.getBeansOfType(ClipRelayClient.class)).hasSize(1);
        assertThat(context.getBeansOfType(ClientHttpRequestFactoryBuilder.class))
                .as("빌더 빈이 둘이면 주입이 모호해 부팅이 죽고, 없으면 중계만 못 뜬다")
                .hasSize(1);
        assertThat(context.getBean(ClientHttpRequestFactoryBuilder.class))
                .as("httpclient5가 클래스패스에 있어도 운영 설정(jdk)을 따라야 한다 — Apache 5 wire 로거가 헤더를 찍는다")
                .isInstanceOf(JdkClientHttpRequestFactoryBuilder.class);

        // 빈 목록만 보면 「주입받은 빌더를 안 쓰고 detect()를 새로 부른」 배선이 초록이다 — 만들어진
        // 클라이언트의 실제 요청 팩토리를 본다(CollectorConfigTest와 같은 방식, 리플렉션).
        Object restClient = ReflectionTestUtils.getField(context.getBean(ClipRelayClient.class), "restClient");
        assertThat(ReflectionTestUtils.getField(restClient, "clientRequestFactory"))
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }
}
