package com.pokeclip.clip.purge;

import com.pokeclip.clip.render.RenderProperties;
import com.pokeclip.clip.upload.UploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
 * 창고 지우기는 렌더 줄이나 업로드 줄이 켜지면 생긴다. 렌더가 꺼져도 업로드가 켜졌으면 사용자가 올린 썸네일 그림이 출력 창고
 * ({@code upload-thumbnails/{streamerUserId}/})에 쌓인다(POK-291): 그때 빈이 없으면 탈퇴 정리가 그 접두사를 명부에서 빼기만 하고
 * 「완료」로 닫아 그림이 영구히 남는다. 출력 창고 이름은 어느 쪽이 켜져도 부팅 때 검사한다(렌더 설정 · {@code UploadConfiguration}).
 * 녹화 조각 창고 이름은 렌더 설정에만 있어 비어 있으면 조각 지우기만 건너뛴다({@link PurgeStorage#deletesSegments()}).
 * 둘 다 꺼져 있으면 빈이 없고 {@link StreamerPurger}가 표만 지운다(WARN 한 줄).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PurgeProperties.class)
public class PurgeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PurgeConfiguration.class);

    @Bean
    @ConditionalOnExpression("${pokeclip.render.enabled:false} or ${pokeclip.upload.enabled:false}")
    public PurgeStorage purgeStorage(RenderProperties properties, UploadProperties upload) {
        // 창고 주소(지역·덮은 주소)는 켜진 쪽 설정을 따른다. 렌더가 꺼졌으면 그림을 둔 쪽(UploadConfiguration)과 같은 값이다.
        String region = properties.enabled() ? properties.region() : upload.region();
        boolean hasEndpoint = properties.enabled() ? properties.hasEndpoint() : upload.hasEndpoint();
        String endpoint = properties.enabled() ? properties.endpoint() : upload.endpoint();
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                // 주소를 덮으면(LocalStack) 경로 방식이다. 가상 호스트(버킷.localhost)는 로컬에서 안 풀린다.
                .forcePathStyle(hasEndpoint)
                // s3가 apache5-client를 runtime으로 딸려오므로 SPI 후보가 둘이다. 안 박으면 클래스패스 순서에 달린다.
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(30))
                        .build());
        if (hasEndpoint) {
            builder.endpointOverride(URI.create(endpoint));
        }
        log.info("clip.purge.storage_enabled endpointOverride={} segments={}", hasEndpoint,
                properties.segmentBucket() != null && !properties.segmentBucket().isBlank());
        return new S3PurgeStorage(builder.build(), properties.outputBucket(), properties.segmentBucket());
    }
}
