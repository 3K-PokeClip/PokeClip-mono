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

    /**
     * 플랫폼 칸(POK-398)은 선택이다. 칸을 더하려고 부 생성자를 하나 남겼는데, 생성자가 둘이면 Jackson이 어느 쪽으로 읽는지가
     * 갈릴 수 있다. 짧은 쪽으로 읽으면 이 칸이 늘 null이라 SOOP 방송이 전부 치지직으로 저장된다. 그래서 JSON으로 잰다.
     */
    @Test
    void 플랫폼_칸을_읽는다() {
        String json = """
                {"schemaVersion":1,"eventId":"evt-4","eventType":"broadcast.started",
                 "occurredAt":"2026-08-18T00:00:00Z","streamId":"s","streamerId":"7",
                 "sequence":1,"traceId":"t","payload":{},"platform":"SOOP"}
                """;

        assertThat(mapper.readValue(json, LifecycleEnvelope.class).platform()).isEqualTo("SOOP");
    }

    @Test
    void 플랫폼_칸이_없으면_null이다() {
        String json = """
                {"schemaVersion":1,"eventId":"evt-5","eventType":"broadcast.started",
                 "occurredAt":"2026-08-18T00:00:00Z","streamId":"s","streamerId":"7",
                 "sequence":1,"traceId":"t","payload":{}}
                """;

        assertThat(mapper.readValue(json, LifecycleEnvelope.class).platform()).isNull();
    }

    @Test
    void 모르는_이벤트_종류는_거부한다() {
        assertThatThrownBy(() -> LifecycleEventType.from("broadcast.paused"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
