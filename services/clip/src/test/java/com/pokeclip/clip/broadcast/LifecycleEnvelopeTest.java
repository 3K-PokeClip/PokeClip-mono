package com.pokeclip.clip.broadcast;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 계약9 봉투(ADR-016)를 읽는다. 타입은 1번이 발행 코드를 아직 안 만들어 대조할
 * 실물이 없다 — PRD의 가정 항목이다. 여기가 그 가정이 모인 유일한 자리이고,
 * 어긋나면 이 파일 하나만 고친다.
 */
class LifecycleEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 시작_봉투를_읽는다() {
        String json = """
                {
                  "schemaVersion": 1,
                  "eventId": "evt-1",
                  "eventType": "broadcast.started",
                  "occurredAt": "2026-08-18T00:00:00Z",
                  "streamId": "stream-1",
                  "streamerId": "7",
                  "sequence": 1,
                  "traceId": "trace-1",
                  "payload": {"trackManifest": {"manifestVersion": 3}}
                }
                """;

        LifecycleEnvelope envelope = mapper.readValue(json, LifecycleEnvelope.class);

        assertThat(envelope.eventId()).isEqualTo("evt-1");
        assertThat(envelope.type()).isEqualTo(LifecycleEventType.BROADCAST_STARTED);
        assertThat(envelope.sequence()).isEqualTo(1L);
        assertThat(envelope.occurredAt()).isNotNull();
        assertThat(envelope.trackManifestJson()).contains("manifestVersion");
    }

    @Test
    void 모르는_필드가_있어도_읽는다() {
        // 1번이 칸을 더해도 우리가 죽지 않아야 한다. 계약 규약이 "필드 추가는 안전"이다.
        String json = """
                {"schemaVersion":1,"eventId":"evt-2","eventType":"broadcast.ended",
                 "occurredAt":"2026-08-18T01:00:00Z","streamId":"stream-1",
                 "streamerId":"7","sequence":2,"traceId":"t","payload":{},
                 "futureField":"값"}
                """;

        assertThat(mapper.readValue(json, LifecycleEnvelope.class).type())
                .isEqualTo(LifecycleEventType.BROADCAST_ENDED);
    }

    @Test
    void trackManifest가_없으면_null이다() {
        String json = """
                {"schemaVersion":1,"eventId":"evt-3","eventType":"broadcast.ended",
                 "occurredAt":"2026-08-18T01:00:00Z","streamId":"s","streamerId":"st",
                 "sequence":2,"traceId":"t","payload":{}}
                """;

        assertThat(mapper.readValue(json, LifecycleEnvelope.class).trackManifestJson()).isNull();
    }

    @Test
    void 모르는_이벤트_종류는_거부한다() {
        assertThatThrownBy(() -> LifecycleEventType.from("broadcast.paused"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
