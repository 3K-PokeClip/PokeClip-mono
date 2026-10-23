package com.pokeclip.clip.broadcast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 계약9 봉투(ADR-016). 필드 열이 정본이다.
 *
 * <p><b>타입은 아직 1번과 대조되지 않았다.</b> 발행 코드가 없어 대조할 실물이 없고,
 * ADR-016은 이름만 나열한다. 어긋나면 고칠 곳은 여기 하나다.
 *
 * <p>모르는 필드는 무시한다 — 계약 규약이 "필드 추가는 안전"이라, 1번이 칸을
 * 더했다고 우리가 죽으면 안 된다.
 *
 * <p>Jackson 3다(Boot 4.1.0). 애노테이션만 2 자리에 남아 있다 —
 * jackson-annotations 2.21이 딸려오고 Jackson 3이 그대로 인식한다.
 *
 * <p>{@code ingestStreamId}는 물리 키다(2026-09-03 계약9 추가, POK-233). 조각 장부·녹화 경로의 {@code stream_id}이고,
 * {@code streamId}는 방송마다 새 회차 번호가 되어 식별·멱등·순서에만 쓴다. 계약은 둘 다 필수지만 <b>없어도 받는다</b>.
 * 1번은 소비자 배포 뒤에야 이 칸을 싣고, 그 전 편지는 {@code streamId}가 곧 물리 키다(그래서 null이면 그 값을 쓴다).
 *
 * <p>{@code platform}은 선택 칸이다(POK-398). 대문자 코드({@code CHZZK}·{@code SOOP})이고 안 실으면 치지직으로 본다.
 * 마지막 칸으로 더하고 옛 열 칸 생성자를 남겼다: 그 생성자를 쓰는 곳은 플랫폼 칸이 생기기 전과 같은 편지(칸 없음)를 만든다.
 * JSON은 정규 생성자로 읽는다({@code LifecycleEnvelopeTest}가 잰다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LifecycleEnvelope(
        int schemaVersion,
        String eventId,
        String eventType,
        Instant occurredAt,
        String streamId,
        String streamerId,
        String ingestStreamId,
        long sequence,
        String traceId,
        JsonNode payload,
        String platform
) {

    public LifecycleEnvelope(int schemaVersion, String eventId, String eventType, Instant occurredAt, String streamId,
                             String streamerId, String ingestStreamId, long sequence, String traceId, JsonNode payload) {
        this(schemaVersion, eventId, eventType, occurredAt, streamId, streamerId, ingestStreamId, sequence, traceId,
                payload, null);
    }

    public LifecycleEventType type() {
        return LifecycleEventType.from(eventType);
    }

    /** 시작 봉투의 trackManifest. 없으면 null. jsonb에 통째로 넣는다. */
    public String trackManifestJson() {
        if (payload == null || payload.get("trackManifest") == null) {
            return null;
        }
        return payload.get("trackManifest").toString();
    }
}
