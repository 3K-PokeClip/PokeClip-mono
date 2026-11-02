package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 새 카드 알림(POK-475). 썸네일 순회기가 이것을 받아 2초 안에 사진을 주문한다. 시험 설정은 썸네일이 꺼짐이라 순회기 대신
 * 같은 모양(커밋 뒤에 받는)의 듣는 쪽을 붙여 잰다.
 *
 * <p>재는 것: <b>새로 만든 카드만</b> 알린다(자동 카드 같은 창 재전송 · 같은 eventId · 20초 안 앞 카드는 안 알린다). 알리면 순회기가
 * 이미 사진이 있는 카드를 헛되이 묻고, 안 알리면 새 카드가 1분 순회를 기다린다.
 */
@Import(JumpCardCreatedEventTest.Listener.class)
class JumpCardCreatedEventTest extends IntegrationTestSupport {

    private static final String 방송 = "s-created";

    private final JumpCardService service;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final Listener listener;

    JumpCardCreatedEventTest(JumpCardService service, BroadcastRepository broadcasts, JdbcTemplate jdbc,
                             Listener listener) {
        this.service = service;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.listener = listener;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow(방송, TestIds.STREAMER, null, 1L, Instant.now(), null));
        listener.received.clear();
    }

    @Test
    void 자동_카드는_새로_만들_때만_알린다() {
        long id = service.record(방송, 자동("evt-1")).card().id();
        service.record(방송, 자동("evt-재전송"));

        assertThat(listener.received).containsExactly(id);
    }

    @Test
    void 수동_카드는_새로_만들_때만_알린다() {
        String eventId = UUID.randomUUID().toString();
        long id = service.recordHotkey(방송, TestIds.STREAMER, eventId, 100_000L).card().id();
        service.recordHotkey(방송, TestIds.STREAMER, eventId, 100_000L);                          // 같은 eventId
        service.recordHotkey(방송, TestIds.STREAMER, UUID.randomUUID().toString(), 110_000L);     // 20초 안 앞 카드

        assertThat(listener.received).containsExactly(id);
    }

    private static HighlightRequest 자동(String eventId) {
        return new HighlightRequest(eventId, "auto", 5_043_000L,
                new HighlightRequest.Window(5_020_000L, 5_062_000L), 97, null);
    }

    /** 순회기와 같은 받는 법: 커밋 뒤. 트랜잭션 밖에서 알리면 안 받는다(기본값). */
    @TestComponent
    static class Listener {
        final List<Long> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(JumpCardCreated event) {
            received.add(event.cardId());
        }
    }
}
