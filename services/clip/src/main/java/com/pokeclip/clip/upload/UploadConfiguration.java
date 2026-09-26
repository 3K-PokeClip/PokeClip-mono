package com.pokeclip.clip.upload;

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
import java.time.Duration;

/** 켜져 있을 때만 줄 클라이언트를 만든다({@code RenderConfiguration}과 같은 모양). */
@Configuration
@EnableConfigurationProperties(UploadProperties.class)
public class UploadConfiguration {

    private static final Logger log = LoggerFactory.getLogger(UploadConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "pokeclip.upload", name = "enabled", havingValue = "true")
    public UploadQueueClient uploadQueueClient(UploadProperties properties) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                // SPI 후보가 둘이라 명시한다(RenderConfiguration 주석).
                .httpClient(UrlConnectionHttpClient.builder().socketTimeout(Duration.ofSeconds(30)).build());
        if (properties.hasEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        log.info("upload.enabled region={} endpointOverride={}", properties.region(), properties.hasEndpoint());
        return new UploadQueueClient(builder.build(), properties.queueUrl(), properties.dlqUrl());
    }
}
