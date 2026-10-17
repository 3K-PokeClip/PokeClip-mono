package com.pokeclip.clip.upload;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 유튜브 업로드 주문(POK-220) 설정. 줄 둘(주문줄·실패 큐)의 좌표. 켜는 값을 따로 두는 이유는 {@code RenderProperties}와 같다 :
 * 켜져 있는데 좌표가 비면 부팅을 거부하고, 꺼져 있으면 주문 문만 503 {@code upload_unavailable}이다(일꾼 보고 문은 돈다).
 * 올릴 파일이 있는 창고는 렌더 설정의 {@code output-bucket}을 쓴다: 완성 영상이 거기 있다.
 */
@ConfigurationProperties(prefix = "pokeclip.upload")
@Validated
public record UploadProperties(
        boolean enabled,
        /** 주문줄(표준 큐) {@code jobs-upload}. */
        String queueUrl,
        /** 실패 큐. 정리기가 읽는다. */
        String dlqUrl,
        @NotBlank String region,
        /** 비면 진짜 AWS. LocalStack 주소를 줄 수 있다. */
        String endpoint,
        @NotNull Duration outboxRetryInterval,
        @NotNull Duration reconcileInterval
) {

    @PostConstruct
    void validateWhenEnabled() {
        if (!enabled) {
            return;
        }
        require(queueUrl, "queue-url", "UPLOAD_QUEUE_URL");
        require(dlqUrl, "dlq-url", "UPLOAD_DLQ_URL");
    }

    private static void require(String value, String property, String env) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("pokeclip.upload.enabled=true인데 " + property + "이 비어 있다. "
                    + env + "을 주거나 UPLOAD_ENABLED=false로 꺼라.");
        }
    }

    public boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
