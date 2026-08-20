package com.pokeclip.chat.collector.broadcast;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약9 봉투(ADR-016)를 읽는다.
 *
 * <p><b>1번의 발행 코드가 없어 이 타입은 아직 실물과 대조되지 않았다.</b> 가정이 모인
 * 자리가 여기 하나이므로, 어긋나면 봉투와 이 파일만 고친다.
 *
 * <p>clip에도 같은 계약을 읽는 봉투가 있지만 <b>거기서 결론을 빌려오지 않았다</b> —
 * clip은 모르는 종류에 예외를 던진다. 우리는 폴링 루프가 그 예외를 타고 죽으면
 * 안 되므로 값으로 돌려준다.
 */
class LifecycleEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 모르는 칸을 무시하는 힘이 두 겹이라 <b>둘을 따로 잰다.</b>
     * <p>Jackson 3의 기본 매퍼는 {@code FAIL_ON_UNKNOWN_PROPERTIES}가 꺼져 있어
     * 애노테이션이 없어도 읽힌다(실측). 그래서 기본 매퍼만으로는 애노테이션이
     * 지켜지지 않는다 — 켜 둔 매퍼로 한 번 더 읽어 그 겹을 잰다.
     * <p>문항 2(자동으로 참이 되는 입력): 봉투를 안 읽으면 단언할 대상이 없다 — 해당 없음.
     * <p>문항 5(그 결함에서 빨간불): 애노테이션을 떼면 기본 매퍼 줄은 <b>초록으로 남고</b>
     * 엄격 매퍼 줄이 {@code UnrecognizedPropertyException}으로 빨간불이 된다(확인함).
     */
    @Test
    void 모르는_필드가_있어도_읽는다() {
        String json = """
            {"schemaVersion":1,"eventId":"e1","eventType":"broadcast.started",
             "occurredAt":"2026-08-18T10:00:00Z","streamId":"s1","streamerId":"42",
             "sequence":7,"traceId":"t1","payload":{},"신규칸":"값"}""";
        LifecycleEnvelope envelope = mapper.readValue(json, LifecycleEnvelope.class);
        assertThat(envelope.streamId()).isEqualTo("s1");
        assertThat(envelope.type()).isEqualTo(LifecycleEventType.STARTED);

        ObjectMapper strict = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        assertThat(strict.readValue(json, LifecycleEnvelope.class).streamId()).isEqualTo("s1");
    }

    /**
     * 문항 4(단언을 통과시키는 잘못된 결과): 위 검사는 {@code type()}이 {@code eventType}을
     * 아예 안 보고 STARTED만 돌려줘도 통과한다. 또 칸 아홉 중 여덟이 어긋나 있어도 통과한다.
     * 그래서 종료 봉투로 갈리는 것을 보고 칸도 전부 대조한다.
     */
    @Test
    void 칸_아홉을_그대로_읽고_종료와_시작을_가른다() {
        String json = """
            {"schemaVersion":1,"eventId":"e2","eventType":"broadcast.ended",
             "occurredAt":"2026-08-18T11:22:33Z","streamId":"s2","streamerId":"77",
             "sequence":9,"traceId":"t2","payload":{"reason":"normal"}}""";
        LifecycleEnvelope envelope = mapper.readValue(json, LifecycleEnvelope.class);
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.eventId()).isEqualTo("e2");
        assertThat(envelope.eventType()).isEqualTo("broadcast.ended");
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-08-18T11:22:33Z"));
        assertThat(envelope.streamId()).isEqualTo("s2");
        assertThat(envelope.streamerId()).isEqualTo("77");
        assertThat(envelope.sequence()).isEqualTo(9L);
        assertThat(envelope.traceId()).isEqualTo("t2");
        assertThat(envelope.payload().get("reason").asString()).isEqualTo("normal");
        assertThat(envelope.type()).isEqualTo(LifecycleEventType.ENDED);
    }

    @Test
    void 모르는_eventType은_UNKNOWN이다() {
        assertThat(LifecycleEventType.from("broadcast.paused")).isEqualTo(LifecycleEventType.UNKNOWN);
    }
}
