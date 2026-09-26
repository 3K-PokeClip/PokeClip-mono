package com.pokeclip.clip.render;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 영상 만들기 주문(POK-125) 설정. 큐 둘(주문줄·실패 큐)과 창고 둘(완성 영상·조각)의 좌표.
 *
 * <p><b>켜는 값을 따로 둔다</b>({@code IntakeProperties}와 같은 이유) — 큐 주소가 비었다고 저절로 꺼지면
 * 「로컬에서 일부러 안 켬」과 「운영에서 깜빡함」이 같아 보인다. 켜져 있는데 좌표가 비면 부팅을 거부한다.
 * 꺼져 있으면 주문 문이 503 {@code render_unavailable}이고 보고 문·조회 문은 그대로 돈다.
 *
 * <p>자격증명은 여기 없다. SDK 표준 체인(환경변수·프로파일·EC2 역할)이 찾는다.
 */
@ConfigurationProperties(prefix = "pokeclip.render")
@Validated
public record RenderProperties(
        boolean enabled,
        /** 주문줄(표준 큐). 계약1 1절 {@code jobs-render}. */
        String queueUrl,
        /** 실패 큐(DLQ). 정리기가 읽는다. */
        String dlqUrl,
        @NotBlank String region,
        /** 비면 진짜 AWS. LocalStack 주소를 줄 수 있다. */
        String endpoint,
        /** 완성 영상을 놓을 창고. 주문서의 {@code outputPrefix}가 {@code s3://{이 값}/clips/{clipId}}다. */
        String outputBucket,
        /** 조각(1번 장부의 {@code s3_key})이 있는 창고. 주문서의 {@code sourceKeys[].bucket}. */
        String segmentBucket,
        /** 못 실은 주문을 다시 보내는 주기. 이 시간보다 오래된 미발행 주문만 다시 보낸다. */
        @NotNull Duration outboxRetryInterval,
        /** 실패 큐를 훑는 주기. */
        @NotNull Duration reconcileInterval,
        /** 한 주문에 허용하는 실행(STARTED) 횟수. 이 번째가 마지막 시도다(계약1 attemptOrdinal 한도). */
        @Min(1) @Max(10) int maxAttempts,
        /** 주문서 크기 상한(UTF-8 바이트). 계약1 2절 200KB. */
        @Min(1024) int maxMessageBytes,
        /** 완성 영상 주소(POK-247)의 수명. 재생 출입증과 같은 60분: 한 편을 보다가 끊기지 않을 만큼. */
        @NotNull Duration fileUrlTtl
) {

    @PostConstruct
    void validateWhenEnabled() {
        if (!enabled) {
            return;
        }
        require(queueUrl, "queue-url", "RENDER_QUEUE_URL");
        require(dlqUrl, "dlq-url", "RENDER_DLQ_URL");
        require(outputBucket, "output-bucket", "CLIPS_BUCKET");
        require(segmentBucket, "segment-bucket", "SEGMENT_BUCKET");
    }

    private static void require(String value, String property, String env) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("pokeclip.render.enabled=true인데 " + property + "이 비어 있다. "
                    + env + "을 주거나 RENDER_ENABLED=false로 꺼라.");
        }
    }

    public boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
