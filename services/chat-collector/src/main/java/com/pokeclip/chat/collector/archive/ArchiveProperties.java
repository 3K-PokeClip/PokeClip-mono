package com.pokeclip.chat.collector.archive;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * S3 아카이브 설정. <b>bucket이 비면 아카이브가 통째로 꺼진다</b>(1번 media의 S3_BUCKET 관례) —
 * CI·팀원 로컬의 기본 상태다. 켜졌을 때 못 올리는 것은 health가 아니라 카운터로 드러낸다.
 * 자격증명은 여기 없다 — SDK 표준 체인(환경변수·프로파일·역할)이 찾는다.
 */
@ConfigurationProperties(prefix = "pokeclip.chzzk.archive")
@Validated
public record ArchiveProperties(
        String bucket,
        @NotBlank String region,
        /** 비면 진짜 AWS. LocalStack·MinIO 등 호환 스토리지 주소(scheme+host+port). */
        String endpoint,
        boolean forcePathStyle,
        /** 못 올린 파일 대기 줄 상한(파일 수). 넘치면 오래된 것부터 버리고 센다. */
        @Min(1) int pendingMax,
        /** 수신→아카이브 바구니 상한(채팅 수). 넘치면 오래된 것부터 버리고 센다. */
        @Min(1) int bufferCapacity
) {

    public boolean enabled() {
        return bucket != null && !bucket.isBlank();
    }

    public boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
