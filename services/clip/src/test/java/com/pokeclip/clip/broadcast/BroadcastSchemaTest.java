package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    BroadcastSchemaTest(BroadcastRepository broadcasts, BroadcastEventRepository events, JdbcTemplate jdbc) {
        this.broadcasts = broadcasts;
        this.events = events;
        this.jdbc = jdbc;
    }

    /**
     * 테스트 클래스들이 같은 컨텍스트·같은 DB를 공유하는데 stream_id·event_id를
     * 겹쳐 쓴다. 정리가 없으면 단독 실행은 통과하고 모듈 전체에서만 터진다
     * (plan-critic 실측 2026-08-18, services/CLAUDE.md가 경고하는 그 모양).
     */
    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        // jump_cards가 broadcasts의 자식이다. 카드를 남긴 클래스가 앞에 돌면
        // 아래 삭제가 FK로 죽는다(POK-118). 실행 순서에 기대지 않는다.
        jdbc.update("DELETE FROM jump_cards");
        events.deleteAllInBatch();
        broadcasts.deleteAllInBatch();
    }

    @Test
    void 명부에_방송을_저장하고_읽는다() {
        broadcasts.save(Broadcast.startedNow("stream-1", TestIds.STREAMER, null, 1L,
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
        broadcasts.save(Broadcast.endedPlaceholder("stream-2", TestIds.STREAMER, null, 5L,
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

    /**
     * 플랫폼 칸의 기본값(POK-398)을 잰다. 시험 DB는 빈 표에 마이그레이션을 거니 「마이그레이션 전부터 있던 줄」은 여기서 못
     * 만든다. 대신 칸을 빼고 SQL로 넣어 기본값이 채우는지 본다. 운영의 기존 줄이 받는 것도 같은 기본값이다
     * (PostgreSQL 11부터 상수 기본값은 표를 다시 쓰지 않고 읽을 때 채운다). 기본값이 없으면 NOT NULL로 여기서 죽는다.
     */
    @Test
    void 플랫폼을_안_적은_줄은_치지직이다() {
        jdbc.update("INSERT INTO broadcasts (stream_id, streamer_id, status, last_sequence) VALUES ('stream-p', ?, 'live', 1)",
                TestIds.STREAMER);

        assertThat(jdbc.queryForObject("SELECT platform FROM broadcasts WHERE stream_id = 'stream-p'", String.class))
                .isEqualTo("CHZZK");
        assertThat(broadcasts.findByStreamId("stream-p").orElseThrow().getPlatform()).isEqualTo("CHZZK");
    }

    @Test
    void 플랫폼을_저장하고_읽는다() {
        broadcasts.save(Broadcast.startedNow("stream-soop", TestIds.STREAMER, null, 1L,
                Instant.parse("2026-08-18T00:00:00Z"), null, "SOOP"));

        assertThat(broadcasts.findByStreamId("stream-soop").orElseThrow().getPlatform()).isEqualTo("SOOP");
    }
}
