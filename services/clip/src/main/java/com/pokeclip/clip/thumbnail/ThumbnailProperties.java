package com.pokeclip.clip.thumbnail;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 썸네일(POK-277) 설정. 꺼져 있으면 주문을 안 내고 목록의 사진 주소가 전부 {@code null}이다(화면은 자리표시를 그린다).
 * 사진 창고는 렌더 설정의 {@code output-bucket}, 조각 창고는 {@code segment-bucket}을 쓴다.
 */
@ConfigurationProperties(prefix = "pokeclip.thumbnail")
@Validated
public record ThumbnailProperties(
        boolean enabled,
        /** 사진 주문줄(표준 큐) {@code jobs-thumbnail}. 렌더 줄과 따로 둔다: 렌더 일꾼은 한 번에 한 주문이라 15분짜리 뒤에 줄을 서면 라이브 사진이 멈춘다 */
        String queueUrl,
        @NotBlank String region,
        /** 비면 진짜 AWS. LocalStack 주소를 줄 수 있다. */
        String endpoint,
        /** 주문을 훑는 주기. 라이브 사진이 이 간격으로 바뀐다 */
        @NotNull Duration interval,
        /** 사진 주소의 수명. 화면은 목록을 다시 받을 때 새 주소를 받는다 */
        @NotNull Duration urlTtl
) {

    static final Duration MAX_URL_TTL = Duration.ofDays(7);

    @PostConstruct
    void validate() {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalStateException("pokeclip.thumbnail.interval은 0초보다 커야 한다: " + interval);
        }
        if (urlTtl.isZero() || urlTtl.isNegative() || urlTtl.compareTo(MAX_URL_TTL) > 0) {
            throw new IllegalStateException("pokeclip.thumbnail.url-ttl은 0초보다 크고 7일 이하여야 한다(S3 미리서명 한도): " + urlTtl);
        }
        if (enabled && (queueUrl == null || queueUrl.isBlank())) {
            throw new IllegalStateException("pokeclip.thumbnail.enabled=true인데 queue-url이 비어 있다. "
                    + "THUMBNAIL_QUEUE_URL을 주거나 THUMBNAIL_ENABLED=false로 꺼라.");
        }
    }

    public boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
