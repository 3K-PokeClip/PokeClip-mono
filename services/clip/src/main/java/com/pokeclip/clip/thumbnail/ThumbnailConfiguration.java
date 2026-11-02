package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.render.RenderProperties;
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
 * 켜져 있을 때만 주문줄·서명기·순회기를 만든다. 꺼져 있으면 셋 다 없고, 목록은 {@code ObjectProvider}로 「없음」을 받아 사진 주소를
 * 비운다. 일꾼 보고 문은 꺼져 있어도 돈다(끄기 직전에 나간 주문의 보고를 버리지 않게).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ThumbnailProperties.class)
public class ThumbnailConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "pokeclip.thumbnail", name = "enabled", havingValue = "true")
    public ThumbnailQueueClient thumbnailQueueClient(ThumbnailProperties properties, RenderProperties render) {
        requireBuckets(render);
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                // SPI 후보가 둘이라 명시한다(RenderConfiguration 주석).
                .httpClient(UrlConnectionHttpClient.builder().socketTimeout(Duration.ofSeconds(30)).build());
        if (properties.hasEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        log.info("thumbnail.enabled region={} endpointOverride={} interval={}",
                properties.region(), properties.hasEndpoint(), properties.interval());
        return new ThumbnailQueueClient(builder.build(), properties.queueUrl());
    }

    /** 주소를 덮으면(LocalStack) 경로 방식이다. 가상 호스트는 로컬에서 안 풀린다({@code RenderConfiguration}과 같다). */
    @Bean
    @ConditionalOnProperty(prefix = "pokeclip.thumbnail", name = "enabled", havingValue = "true")
    public ThumbnailSigner thumbnailSigner(ThumbnailProperties properties, RenderProperties render) {
        requireBuckets(render);
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.hasEndpoint()).build());
        if (properties.hasEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return new ThumbnailSigner(builder.build(), render.outputBucket(), properties.urlTtl());
    }

    /**
     * 사진은 완성 영상 창고에 두고 조각 창고에서 뽑는다. 두 이름은 렌더 설정에 있고 렌더를 끄면 그쪽은 검사를 안 하므로 여기서
     * 본다: 비어도 부팅하면 주문서의 창고가 빈 문자열이라 일꾼이 매번 실패한다({@code UploadConfiguration}과 같은 이유).
     */
    static void requireBuckets(RenderProperties render) {
        if (render.outputBucket() == null || render.outputBucket().isBlank()
                || render.segmentBucket() == null || render.segmentBucket().isBlank()) {
            throw new IllegalStateException("pokeclip.thumbnail.enabled=true인데 창고 이름(CLIPS_BUCKET·SEGMENT_BUCKET)이 "
                    + "비어 있다. 둘을 주거나 THUMBNAIL_ENABLED=false로 꺼라.");
        }
    }
}
