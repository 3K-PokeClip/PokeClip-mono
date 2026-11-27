package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.chat.collector.relay.RelayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 데모 카드를 clip에 넣는 쪽을 고른다. <b>중계({@code pokeclip.relay.enabled})를 따른다</b> — 데모 채팅은 중계로만 화면에 가므로,
 * 중계가 꺼진 배포(로컬 기본 · CI)에서 카드만 서면 채팅 없이 카드가 선다. 켜고 끄는 값을 따로 두지 않은 것은 둘이 어긋날 일을
 * 만들지 않으려는 것이다.
 */
@Configuration
public class DemoConfiguration {

    @Configuration
    @ConditionalOnProperty(prefix = "pokeclip.relay", name = "enabled", havingValue = "true")
    public static class Enabled {

        @Bean
        public DemoHighlight.Sink demoCardSink(RestClient.Builder builder,
                                               ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                                               RelayProperties relay, LinkProperties link) {
            return ClipDemoCardClient.create(builder, factoryBuilder, relay, link);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "pokeclip.relay", name = "enabled", havingValue = "false", matchIfMissing = true)
    public static class Disabled {

        @Bean
        public DemoHighlight.Sink demoCardSink() {
            return DemoHighlight.Sink.NONE;
        }
    }
}
