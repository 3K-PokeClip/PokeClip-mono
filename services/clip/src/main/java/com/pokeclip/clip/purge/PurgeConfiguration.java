package com.pokeclip.clip.purge;

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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * 창고 지우기는 렌더 줄과 같이 켜진다. 창고 이름 둘이 렌더 설정에 있고, 렌더가 꺼진 배포에는 이 서버가 만든 영상·사진이 없다.
 * 꺼져 있으면 빈이 없고 {@link StreamerPurger}가 표만 지운다(WARN 한 줄).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PurgeProperties.class)
public class PurgeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PurgeConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "pokeclip.render", name = "enabled", havingValue = "true")
    public PurgeStorage purgeStorage(RenderProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                // 주소를 덮으면(LocalStack) 경로 방식이다. 가상 호스트(버킷.localhost)는 로컬에서 안 풀린다.
                .forcePathStyle(properties.hasEndpoint())
                // s3가 apache5-client를 runtime으로 딸려오므로 SPI 후보가 둘이다. 안 박으면 클래스패스 순서에 달린다.
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(30))
                        .build());
        if (properties.hasEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        log.info("clip.purge.storage_enabled endpointOverride={}", properties.hasEndpoint());
        return new S3PurgeStorage(builder.build(), properties.outputBucket(), properties.segmentBucket());
    }
}
