package com.pokeclip.clip.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * 켜져 있을 때만 큐 클라이언트를 만든다. 꺼져 있으면 빈이 없고 {@link RenderRequestService}는
 * {@code ObjectProvider}로 「없음」을 받아 503으로 접는다({@code IntakeConfiguration}과 같은 모양).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RenderProperties.class)
public class RenderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RenderConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "pokeclip.render", name = "enabled", havingValue = "true")
    public RenderQueueClient renderQueueClient(RenderProperties properties) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                // sqs가 apache5-client를 runtime으로 딸려오므로 SPI 후보가 둘이다 — 안 박으면 클래스패스 순서에 달린다.
                .httpClient(UrlConnectionHttpClient.builder().socketTimeout(Duration.ofSeconds(30)).build());
        if (properties.hasEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        // 큐 주소·창고 이름은 안 찍는다 — 운영 식별자라 필요 없다.
        log.info("render.enabled region={} endpointOverride={}", properties.region(), properties.hasEndpoint());
        return new RenderQueueClient(builder.build(), properties.queueUrl(), properties.dlqUrl());
    }

    /**
     * 완성 영상 주소(POK-247). 주문줄과 같이 켜진다: 창고 이름이 같은 설정에 있고, 꺼져 있으면 창고에 영상도 없다.
     * 주소를 덮으면(LocalStack) 경로 방식이다. 가상 호스트({@code 버킷.localhost})는 로컬에서 안 풀린다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "pokeclip.render", name = "enabled", havingValue = "true")
    public ClipFileSigner clipFileSigner(RenderProperties properties) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.hasEndpoint()).build());
        if (properties.hasEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return new ClipFileSigner(builder.build(), properties.outputBucket(), properties.fileUrlTtl());
    }
}
