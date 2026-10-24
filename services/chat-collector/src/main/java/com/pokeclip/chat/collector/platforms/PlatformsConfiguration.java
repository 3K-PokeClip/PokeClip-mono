package com.pokeclip.chat.collector.platforms;

import com.pokeclip.chat.collector.link.LinkProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * clip 플랫폼 묶음 알림 한 벌(POK-397). 치지직 방송 정보와 SOOP 지켜보기가 같은 인스턴스를 나눠 쓴다(메모는 플랫폼별이라 섞이지 않는다).
 * 둘 중 하나라도 켜졌을 때만 만든다: 만들면 생성자가 clip 주소·내부 토큰을 검증하므로, 둘 다 꺼진 부팅을 죽이지 않게.
 */
@Configuration
@EnableConfigurationProperties({PlatformsProperties.class, LinkProperties.class})
@ConditionalOnExpression("${pokeclip.liveinfo.enabled:false} or ${pokeclip.soop.watch-enabled:false}")
public class PlatformsConfiguration {

    @Bean
    public ClipPlatformClient clipPlatformClient(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                                                 PlatformsProperties properties, LinkProperties link) {
        // 복제해서 시한을 건다. 받은 빌더에 직접 걸면 같은 빌더를 받는 다른 클라이언트와 시한이 섞여 읽기 어렵다.
        return ClipPlatformClient.create(builder.clone(), factoryBuilder, properties, link);
    }
}
