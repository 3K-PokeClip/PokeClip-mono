package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 표와 엔티티가 어긋나지 않았는지 본다. ddl-auto=validate라 컨텍스트가 뜨는 것
 * 자체가 1차 증거이고, 저장·조회가 2차 증거다.
 */
class BroadcastSchemaTest extends IntegrationTestSupport {

    private final BroadcastRepository broadcasts;
    private final BroadcastEventRepository events;

    BroadcastSchemaTest(BroadcastRepository broadcasts, BroadcastEventRepository events) {
        this.broadcasts = broadcasts;
        this.events = events;
    }

    /**
     * 테스트 클래스들이 같은 컨텍스트·같은 DB를 공유하는데 stream_id·event_id를
     * 겹쳐 쓴다. 정리가 없으면 단독 실행은 통과하고 모듈 전체에서만 터진다
     * (plan-critic 실측 2026-08-18, services/CLAUDE.md가 경고하는 그 모양).
     */
    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        events.deleteAllInBatch();
        broadcasts.deleteAllInBatch();
    }

    @Test
    void 명부에_방송을_저장하고_읽는다() {
        broadcasts.save(Broadcast.startedNow("stream-1", "streamer-1", 1L,
                Instant.parse("2026-08-18T00:00:00Z"), null));

        assertThat(broadcasts.findByStreamId("stream-1")).isPresent().get()
                .satisfies(b -> {
                    assertThat(b.getStatus()).isEqualTo(BroadcastStatus.LIVE);
                    assertThat(b.getLastSequence()).isEqualTo(1L);
                    assertThat(b.getStartedAt()).isNotNull();
                    assertThat(b.getEndedAt()).isNull();
                });
    }

    @Test
    void 종료_placeholder는_시작_시각이_비어_있다() {
        broadcasts.save(Broadcast.endedPlaceholder("stream-2", "streamer-1", 5L,
                Instant.parse("2026-08-18T01:00:00Z")));

        assertThat(broadcasts.findByStreamId("stream-2")).isPresent().get()
                .satisfies(b -> {
                    assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
                    assertThat(b.getStartedAt()).isNull();
                });
    }

    @Test
    void 받은_편지를_기록하고_번호로_찾는다() {
        events.save(BroadcastEvent.of("evt-1", "stream-1",
                LifecycleEventType.BROADCAST_STARTED, 1L, Instant.now()));

        assertThat(events.existsByEventId("evt-1")).isTrue();
        assertThat(events.existsByEventId("evt-없음")).isFalse();
    }
}
