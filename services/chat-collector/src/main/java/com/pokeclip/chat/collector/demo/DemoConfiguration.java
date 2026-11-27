package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.link.LinkProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 데모 카드를 clip에 넣는 쪽을 고른다. <b>clip 주소({@code pokeclip.demo.clip-base-url})와 내부 토큰({@code pokeclip.link.internal-token})이
 * 둘 다 있으면 넣는다.</b>
 *
 * <p>중계({@code pokeclip.relay.enabled})와 묶지 않는다(PR #558 리뷰). 중계가 꺼져도 화면({@code useChatPanelMockState})은 저장된 채팅을
 * 5초마다 읽어 데모 채팅을 띄우는데, 판별기는 데모 채널을 안 세므로 여기서 카드를 안 내면 그 배포에서는 카드가 영영 안 선다.
 *
 * <p>둘 중 하나가 비어도 부팅은 안 죽인다 — 데모는 발표용 도구라 그것 때문에 채팅 수집이 멈추면 안 된다. 그때는 누를 때
 * {@code demo.chat_card.skipped reason=no_clip_link}가 남는다.
 */
@Configuration
// 운영 컨텍스트는 @ConfigurationPropertiesScan이 이미 잡는다. 이 설정만 떼어 부르는 시험을 위해 둔다(RelayConfiguration과 같은 이유)
@EnableConfigurationProperties({DemoProperties.class, LinkProperties.class})
public class DemoConfiguration {

    @Bean
    public DemoHighlight.Sink demoCardSink(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                                           DemoProperties demo, LinkProperties link) {
        if (blank(demo.clipBaseUrl()) || blank(link.internalToken())) {
            return DemoHighlight.Sink.NONE;
        }
        return ClipDemoCardClient.create(builder, factoryBuilder, demo.clipBaseUrl(), link.internalToken());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
