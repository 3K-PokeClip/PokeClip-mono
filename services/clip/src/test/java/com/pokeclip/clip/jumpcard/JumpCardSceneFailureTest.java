package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService.RecordResult;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 카드 장면(POK-196)은 부가 칸이다 — 장부를 못 읽거나 장면 계산이 실패해도 카드는 저장 · 응답되고 두 칸만 빈다(WARN
 * {@code jumpcard.scene_unavailable}). 장면은 저장 트랜잭션 앞에서 읽는다: 트랜잭션 안에서 장부 SQL 이 실패하면 PostgreSQL 이
 * 그 트랜잭션을 무르게 해 카드 저장까지 잃는다.
 *
 * <p>장면 함수를 감싸 실패를 넣는다. 장부 SQL 실패는 실제 연결에서 틀린 질의를 돌려 만든다 — 저장과 같은 트랜잭션에 들면 그
 * 트랜잭션이 오염되는 것까지 재현하려는 것이다.
 */
class JumpCardSceneFailureTest extends IntegrationTestSupport {

    private static final String 방송 = "S-scenefail";
    private static final String 키 = "SCENEFAIL";
    private static final String 주인 = TestIds.STREAMER;

    private final JumpCardService service;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final LedgerSeed seed;

    @MockitoSpyBean
    private CardPositionReader scenes;

    JumpCardSceneFailureTest(JumpCardService service, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.service = service;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 심는다() {
        방송과_카드를_비운다(jdbc);
        AUTH.respondWith("/internal/editor-delegations/resolve", 200, "{\"relation\":\"OWNER\"}");
        broadcasts.save(Broadcast.startedNow(방송, 주인, 키, 1L, at(0), null));
        seed.session(방송, 키, 방송, null);
        for (int i = 0; i < 20; i++) {
            seed.row(키, i + 1).pts(4_000L * i).wallAndPdt(4_000L * i).session(방송).insert();
        }
    }

    @Test
    void 장부_SQL이_실패해도_자동_카드는_저장되고_두_칸만_빈다() {
        doAnswer(invocation -> {
            jdbc.queryForList("SELECT pokeclip_no_such_column FROM public.stream_segments");
            return Map.of();
        }).when(scenes).scenesOf(any(Broadcast.class), anyCollection());

        try (LogCaptor logs = new LogCaptor()) {
            RecordResult result = service.record(방송, 자동_카드(30_000));

            assertThat(result.created()).isTrue();
            assertThat(result.card().playerWindow()).isNull();
            assertThat(result.card().playerAtMs()).isNull();
            assertThat(카드_수()).isEqualTo(1);
            assertThat(logs.messages())
                    .contains("jumpcard.scene_unavailable streamId=S-scenefail reason=BadSqlGrammarException");
        }
    }

    /** 장부 칸의 불변식이 깨져 장면 계산이 던져도(예: 공통 모듈의 행 열 검사) 카드 저장 · 목록 · 가리기가 500 이 아니다. */
    @Test
    void 장면_계산이_실패해도_카드는_저장되고_목록_가리기도_두_칸만_빈다() {
        doThrow(new IllegalArgumentException("행이 seq 오름차순이 아니다"))
                .when(scenes).scenesOf(any(Broadcast.class), anyCollection());

        try (LogCaptor logs = new LogCaptor()) {
            RecordResult result = service.record(방송, 자동_카드(30_000));
            List<JumpCardSnapshot> page = service.listOf(주인, 방송, false, null, null).cards();
            JumpCardSnapshot hidden = service.hide(result.card().id(), 주인);

            assertThat(result.created()).isTrue();
            assertThat(result.card().playerAtMs()).isNull();
            assertThat(page).singleElement().satisfies(card -> assertThat(card.playerWindow()).isNull());
            assertThat(hidden.hidden()).isTrue();
            assertThat(hidden.playerAtMs()).isNull();
            assertThat(logs.messages())
                    .contains("jumpcard.scene_unavailable streamId=S-scenefail reason=IllegalArgumentException");
        }
    }

    /** 가리기 · 되살리기 · 중복 저장(재조회 갈래)도 새 카드와 같은 장면을 싣는다. */
    @Test
    void 가리기_되살리기_중복_저장은_새_카드와_같은_장면을_싣는다() {
        HighlightRequest request = 자동_카드(30_000);
        JumpCardSnapshot made = service.record(방송, request).card();

        JumpCardSnapshot hidden = service.hide(made.id(), 주인);
        JumpCardSnapshot shown = service.unhide(made.id(), 주인);
        RecordResult again = service.record(방송, new HighlightRequest(UUID.randomUUID().toString(), "auto",
                request.streamTimestampMs(), request.window(), 90, null));

        assertThat(made.playerAtMs()).isEqualTo(at(30_000).toEpochMilli());
        assertThat(made.playerWindow()).isNotNull();
        assertThat(again.created()).isFalse();
        for (JumpCardSnapshot card : List.of(hidden, shown, again.card())) {
            assertThat(card.playerWindow()).isEqualTo(made.playerWindow());
            assertThat(card.playerAtMs()).isEqualTo(made.playerAtMs());
        }
    }

    private static HighlightRequest 자동_카드(long position) {
        return new HighlightRequest(UUID.randomUUID().toString(), "auto", position,
                new HighlightRequest.Window(position - 20_000, position + 20_000), 90, null);
    }

    private int 카드_수() {
        return jdbc.queryForObject("SELECT count(*) FROM jump_cards WHERE stream_id = ?", Integer.class, 방송);
    }
}
