package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardErrors.ClaimedByOtherException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.InvalidHighlightException;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 카드 행동 기록(POK-338). 카드 표의 claimed_*·hidden_*는 다시 집거나 되돌리면 덮이고 지워지므로,
 * 거짓양성률·카드에서 영상까지의 시간은 이 기록으로만 잰다.
 *
 * <p>규칙: <b>상태가 실제로 바뀐 호출만</b> 남는다. 멱등 성공(이미 그 상태)과 거절은 안 남는다 —
 * 남기면 같은 사람이 단추를 두 번 누른 것이 「두 번 숨겼다」로 세진다. 예외 하나: 본인이 다시 집는 것은
 * 「다시 열었다」라서 남는다(열람을 집기로 갈음했다, 2026-10-15 결정).
 */
class JumpCardActionLogTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    private final JumpCardService service;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    JumpCardActionLogTest(JumpCardService service, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.service = service;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-1", TestIds.STREAMER, null, 1L, Instant.now(), null));
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    @Test
    void 집기_놓기_다시_집기_숨기기_되돌리기가_순서대로_전부_남는다() {
        long id = 카드();

        service.claim(id, "1201");
        service.release(id, "1201");
        service.claim(id, "1202");
        service.hide(id, "1202", "not_highlight");
        service.unhide(id, "1203");

        assertThat(기록(id)).containsExactly(
                "claim 1201 null",
                "release 1201 null",
                "claim 1202 null",
                "hide 1202 not_highlight",
                "unhide 1203 null");
    }

    @Test
    void 본인이_다시_집으면_다시_연_것으로_한_줄_더_남는다() {
        long id = 카드();

        service.claim(id, "1201");
        service.claim(id, "1201");

        assertThat(기록(id)).containsExactly("claim 1201 null", "claim 1201 null");
    }

    @Test
    void 바뀌지_않은_호출은_안_남는다() {
        long id = 카드();

        service.release(id, "1201");          // 아무도 안 잡았다 — 멱등 성공
        service.unhide(id, "1201");           // 안 숨겨졌다 — 멱등 성공
        service.hide(id, "1201", null);
        service.hide(id, "1202", "duplicate"); // 이미 숨겨졌다 — 숨긴 사람·사유를 안 덮는다

        assertThat(기록(id)).containsExactly("hide 1201 null");
    }

    @Test
    void 거절은_안_남는다() {
        long id = 카드();
        service.claim(id, "1201");

        assertThatThrownBy(() -> service.claim(id, "1202")).isInstanceOf(ClaimedByOtherException.class);
        assertThatThrownBy(() -> service.hide(id, "1202", "boring")).isInstanceOf(InvalidHighlightException.class);

        assertThat(기록(id)).containsExactly("claim 1201 null");
    }

    private long 카드() {
        return service.record("s-1", new HighlightRequest("evt-1", "auto", 5_043_000L,
                new HighlightRequest.Window(5_020_000L, 5_062_000L), 97, null)).card().id();
    }

    private List<String> 기록(long cardId) {
        return jdbc.query("SELECT action, actor, reason FROM jump_card_actions WHERE card_id = ? ORDER BY id",
                (rs, n) -> rs.getString("action") + " " + rs.getString("actor") + " " + rs.getString("reason"),
                cardId);
    }
}
