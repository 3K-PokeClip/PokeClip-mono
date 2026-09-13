package com.pokeclip.chat.collector.relay;

import com.pokeclip.chat.collector.link.LinkProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 채팅 중계 부품. {@code pokeclip.relay.enabled}로 갈린다 — 켜지면 바구니·clip 클라이언트·중계기, 꺼지면
 * {@code NONE} 셋. <b>꺼져도 빈을 만드는 이유</b>는 등록부·health·종료가 켜짐/꺼짐을 모르고 타입 하나만
 * 받게 하려는 것이다({@code ArchiveConfiguration}이 {@code ChatArchive.NONE}을 주는 것과 같다).
 */
@Configuration
// 운영 컨텍스트는 @ConfigurationPropertiesScan이 이미 잡는다. 이 설정만 떼어 띄우는 검사를 위해 둔다
// (ReattachConfiguration과 같은 이유).
@EnableConfigurationProperties({RelayProperties.class, LinkProperties.class})
public class RelayConfiguration {

    /**
     * 중계 전용 시한(F11). 전역({@code spring.http.clients.*} 접속 2초·읽기 5초)을 물려받으면
     * 중계 스레드가 하나이고 방송별로 차례로 보내므로 clip이 매달릴 때 방송 K개가 한 바퀴에 최대 7K초 밀린다.
     * 실시간 가치는 1초 뒤 사라지고 목표가 p95 1초다.
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    @Configuration
    @ConditionalOnProperty(prefix = "pokeclip.relay", name = "enabled", havingValue = "true")
    public static class Enabled {

        /**
         * 🔴 <b>주입받은 팩토리 빌더를 쓴다</b> — {@code spring.http.clients.imperative.factory=jdk}가 고른
         * 그 빌더다. {@code ClientHttpRequestFactoryBuilder.detect()}를 여기서 새로 부르면 클래스패스의
         * httpclient5(AWS SDK가 끌어온다)가 잡혀 스택이 조용히 바뀐다.
         *
         * <p>{@code public}인 것은 시한 검사({@code ClipRelayClientTest})가 이 메서드를 <b>직접</b> 부르기
         * 때문이다 — 검사가 시한을 손으로 다시 조립하면 운영 배선을 안 잰다(clip {@code CollectorHttpConfig}와 같다).
         */
        @Bean
        public ClipRelayClient clipRelayClient(RestClient.Builder builder,
                                               ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                                               RelayProperties relay, LinkProperties link) {
            return new ClipRelayClient(builder.requestFactory(factoryBuilder.build(
                    HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT))),
                    relay, link);
        }

        /** 등록부가 {@link RelaySink}로, 중계기가 이 타입으로 <b>같은 인스턴스</b>를 받는다. */
        @Bean
        public RelayBuffer relayBuffer(RelayProperties relay) {
            return new RelayBuffer(relay.bufferCapacity());
        }

        /**
         * 만들면서 스레드를 띄운다({@code ArchiveConfiguration}과 같다). {@code destroyMethod}를 끈다 —
         * 닫기는 러너의 종료가 저장·아카이브와 <b>나란히</b> 공유 기한 안에서 한다(태스크 17, F1).
         * 스프링 파괴 순서에 맡기면 아무도 이 빈에 의존하지 않을 때 러너보다 먼저 파괴될 수 있다.
         */
        @Bean(destroyMethod = "")
        public ChatRelayer chatRelayer(RelayBuffer buffer, ClipRelayClient client, RelayProperties relay) {
            ChatRelayer relayer = new ChatRelayer(buffer, client, relay);
            relayer.start();
            return relayer;
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "pokeclip.relay", name = "enabled", havingValue = "false", matchIfMissing = true)
    public static class Disabled {

        @Bean
        public RelaySink relaySink() {
            return RelaySink.NONE;
        }

        @Bean
        public RelayCounters relayCounters() {
            return RelayCounters.NONE;
        }

        @Bean
        public RelayLifecycle relayLifecycle() {
            return RelayLifecycle.NONE;
        }
    }
}
