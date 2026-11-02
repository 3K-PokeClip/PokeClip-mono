package com.pokeclip.clip.broadcast.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(IntakeProperties.class)
public class IntakeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IntakeConfiguration.class);

    /**
     * 꺼져 있으면 클라이언트를 아예 등록하지 않는다.
     *
     * <p><b>{@code Optional<SqsClient>}로 등록하지 않는다.</b> Spring은
     * {@code Optional<T>} 주입 지점을 "T 타입 빈을 optional로 찾기"로 가로채므로,
     * 타입이 {@code Optional}인 빈은 후보에서 빠지고 주입되는 값은 항상 비어 있다.
     * 그러면 켜도 폴링이 영원히 시작되지 않는데 테스트는 전부 초록이다
     * (plan-critic 실측 2026-08-18: 빈은 isPresent=true인데 러너가 받은 값은 false,
     * getBeanNamesForType(SqsClient.class).length == 0).
     * 러너는 {@code ObjectProvider<SqsClient>}로 받는다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "pokeclip.broadcast.intake", name = "enabled", havingValue = "true")
    public SqsClient sqsClient(IntakeProperties properties) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                // 롱폴링(20초)보다 소켓 타임아웃이 짧으면 매 회차가 끊긴다.
                .httpClient(UrlConnectionHttpClient.builder()
                        .socketTimeout(properties.waitTime().plusSeconds(10))
                        .build());
        if (properties.hasEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        // 큐 주소는 안 찍는다 — 운영 식별자라 필요 없다.
        log.info("broadcast.intake.enabled region={} endpointOverride={}",
                properties.region(), properties.hasEndpoint());
        return builder.build();
    }

    /**
     * 켜짐/꺼짐과 무관하게 항상 등록한다 — health가 "빈이 없음"과 "꺼져 있음"을
     * 구분하려면 꺼졌다는 사실 자체를 알아야 한다.
     */
    @Bean
    public IntakeStatus intakeStatus(IntakeProperties properties) {
        return new IntakeStatus(properties.enabled());
    }
}
