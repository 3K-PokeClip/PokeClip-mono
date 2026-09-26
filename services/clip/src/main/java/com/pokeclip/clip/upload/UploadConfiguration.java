package com.pokeclip.clip.upload;

import com.pokeclip.clip.render.RenderProperties;
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
    public UploadQueueClient uploadQueueClient(UploadProperties properties, RenderProperties render) {
        requireSourceBucket(render.outputBucket());
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

    /**
     * 올릴 파일은 완성 영상 창고에 있다. 그 이름은 렌더 설정에 있고 렌더를 끄면 그쪽은 검사를 안 한다: 비어도 부팅하면
     * 주문은 201인데 주문서의 창고가 빈 문자열이라 일꾼이 영상을 못 받는다(PR #198 codex).
     */
    static void requireSourceBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("pokeclip.upload.enabled=true인데 올릴 영상의 창고(pokeclip.render.output-bucket)가 "
                    + "비어 있다. CLIPS_BUCKET을 주거나 UPLOAD_ENABLED=false로 꺼라.");
        }
    }
}
