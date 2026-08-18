package com.pokeclip.chat.collector.broadcast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 계약9 봉투(ADR-016). 칸 아홉이 정본이다.
 *
 * <p><b>타입은 아직 1번과 대조되지 않았다.</b> 발행 코드가 없어 대조할 실물이 없고,
 * ADR-016은 이름만 나열한다. 어긋나면 고칠 곳은 여기 하나다.
 *
 * <p>모르는 칸은 무시한다 — 계약 규약이 "필드 추가는 안전"이라, 1번이 칸을 더했다고
 * 우리가 죽으면 안 된다.
 *
 * <p>Jackson 3다(Boot 4.1.0). 애노테이션만 2 자리에 남아 있다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LifecycleEnvelope(
        int schemaVersion,
        String eventId,
        String eventType,
        Instant occurredAt,
        String streamId,
        String streamerId,
        long sequence,
        String traceId,
        JsonNode payload
) {

    public LifecycleEventType type() {
        return LifecycleEventType.from(eventType);
    }
}
