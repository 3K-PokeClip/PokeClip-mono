package com.pokeclip.clip.broadcast.intake;

import jakarta.annotation.PostConstruct;
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
        /** SQS 롱폴링 대기. 소켓 타임아웃보다 짧아야 한다. */
        @NotNull Duration waitTime,
        @Min(1) int maxMessages
) {

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
