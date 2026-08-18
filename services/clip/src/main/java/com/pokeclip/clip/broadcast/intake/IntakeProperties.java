package com.pokeclip.clip.broadcast.intake;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 방송 생명주기 이벤트 수신 설정.
 *
 * <p><b>켜는 값을 따로 둔다.</b> 큐 주소가 비었다고 저절로 꺼지면 "로컬에서 일부러
 * 안 켬"과 "운영에서 설정을 깜빡함"이 똑같이 보인다. 켜져 있는데 주소가 없으면
 * 그건 실수이므로 부팅을 거부한다 — DB 접속값 셋과 같은 이유다.
 *
 * <p>자격증명은 여기 없다. SDK 표준 체인(환경변수·프로파일·역할)이 찾는다.
 */
@ConfigurationProperties(prefix = "pokeclip.broadcast.intake")
@Validated
public record IntakeProperties(
        boolean enabled,
        String queueUrl,
        @NotBlank String region,
        /** 비면 진짜 AWS. LocalStack 주소(scheme+host+port)를 줄 수 있다. */
        String endpoint,
        /** SQS 롱폴링 대기. 소켓 타임아웃보다 짧아야 한다. 상한은 SQS가 정한 20초다. */
        @NotNull Duration waitTime,
        /** SQS가 한 번에 주는 최대치가 10이다. 넘기면 호출이 거부된다. */
        @Min(1) @Max(MAX_MESSAGES_LIMIT) int maxMessages
) {

    /** SQS가 정한 값이다(ReceiveMessage: MaxNumberOfMessages 1~10, WaitTimeSeconds 0~20). */
    static final int MAX_MESSAGES_LIMIT = 10;
    static final Duration MAX_WAIT_TIME = Duration.ofSeconds(20);

    /**
     * 상한을 넘겨도 <b>부팅은 성공하고 호출만 거부된다</b> — 폴링이 매 회차 실패해
     * 이벤트를 영영 못 받는다("Must be between 1 and 10" 등, 감사자 실측 2026-08-18).
     * health가 DOWN으로 드러내긴 하지만, 켜졌는데 큐 주소가 없으면 거부하는 것과
     * 같은 논리로 부팅에서 막는다 — 설정 실수는 배포 전에 걸리는 편이 싸다.
     *
     * <p>{@code maxMessages}는 애노테이션으로 막는다. {@code waitTime}은 Duration이라
     * 그 자리에 걸 표준 애노테이션이 없어 여기서 본다.
     */
    @PostConstruct
    void validateSqsLimits() {
        if (waitTime.isNegative() || waitTime.compareTo(MAX_WAIT_TIME) > 0) {
            throw new IllegalStateException(
                    "pokeclip.broadcast.intake.wait-time은 0초 이상 " + MAX_WAIT_TIME.toSeconds()
                    + "초 이하여야 한다(SQS 제약). 지금 값: " + waitTime);
        }
    }

    @PostConstruct
    void validateWhenEnabled() {
        if (enabled && (queueUrl == null || queueUrl.isBlank())) {
            throw new IllegalStateException(
                    "pokeclip.broadcast.intake.enabled=true인데 queue-url이 비어 있다. "
                    + "BROADCAST_QUEUE_URL을 주거나 BROADCAST_INTAKE_ENABLED=false로 꺼라.");
        }
    }

    public boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
