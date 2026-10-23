package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 표와 엔티티가 어긋나지 않았는지 본다. {@code ddl-auto=validate}라 컨텍스트가 뜨는 것
 * 자체가 1차 증거이고, 저장·조회가 2차 증거다({@code BroadcastSchemaTest}와 같은 방식).
 *
 * <p>쓰기를 JDBC로 직접 하는 이유: {@code JumpCard}는 {@code @Immutable} 읽기 전용이라
 * 저장 메서드가 없다. 쓰기는 전부 네이티브 SQL이고 그래야 DB 시계와 트리거를 탄다.
 */
class JumpCardSchemaTest extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;
    private final JumpCardRepository cards;
    private final BroadcastRepository broadcasts;
    private final ObjectMapper mapper = new ObjectMapper();

    JumpCardSchemaTest(JdbcTemplate jdbc, JumpCardRepository cards, BroadcastRepository broadcasts) {
        this.jdbc = jdbc;
        this.cards = cards;
        this.broadcasts = broadcasts;
    }

    /**
     * {@code jump_cards}가 {@code broadcasts}의 자식이라 카드를 먼저 지워야 방송이 지워진다.
     * 카드를 남긴 채 끝나면 다른 클래스의 {@code broadcasts.deleteAllInBatch()}가 FK로 죽는다
     * (services/CLAUDE.md가 경고하는 그 함정. 지금은 클래스 실행 순서 덕에 안 터질 뿐이다).
     */
    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-1", TestIds.STREAMER, null, 1L, Instant.parse("2026-08-23T00:00:00Z"), null));
    }

    @Test
    void 카드를_넣으면_event_seq와_시각을_DB가_채운다() {
        insert("s-1", "auto", "evt-1", 5_043_000L, 5_020_000L, 5_062_000L, 97, "{\"multiplier\":4.2}");

        JumpCard card = cards.findAutoByStreamIdAndWindowStartMs("s-1", 5_020_000L).orElseThrow();

        assertThat(card.getEventSeq()).as("트리거가 명시한 0을 nextval로 덮어야 한다").isPositive();
        assertThat(card.getCreatedAt()).isNotNull();
        assertThat(card.getUpdatedAt()).isNotNull();
        assertThat(card.getEvidence()).contains("4.2");
        assertThat(card.getSource()).isEqualTo(JumpCardSource.AUTO);
        assertThat(card.getScore()).isEqualTo(97);
    }

    @Test
    void 바뀌면_event_seq가_오른다_트리거() {
        long id = insert("s-1", "auto", "evt-1", 5_043_000L, 5_020_000L, 5_062_000L, 97, "{\"multiplier\":4.2}");
        long before = seqOf(id);

        jdbc.update("UPDATE jump_cards SET hidden_at = now() WHERE id = ?", id);

        assertThat(seqOf(id)).as("BEFORE UPDATE 트리거가 순번을 안 올리면 스냅샷 정렬에서 뒤로 밀린다")
                .isGreaterThan(before);
        assertThat(updatedAtOf(id)).as("updated_at도 트리거가 DB 시계로 채운다").isAfter(createdAtOf(id));
    }

    @Test
    void 자동_카드는_같은_방송_창시작을_두_번_못_넣는다() {
        insert("s-1", "auto", "evt-1", 5_043_000L, 5_020_000L, 5_062_000L, 97, null);

        assertThatThrownBy(() -> insert("s-1", "auto", "evt-2", 5_043_000L, 5_020_000L, 5_062_000L, 97, null))
                .as("자동 카드 중복 방어선은 uq_jump_cards_auto_window 하나뿐이다")
                .isInstanceOf(DuplicateKeyException.class);
    }

    /**
     * POK-391(V219). 수동 카드는 구간 시작으로 막지 않는다: 방송 시각 40초 안의 수동 카드는 구간 시작이
     * 0으로 잘려 같아지는데, 20초 넘게 떨어졌으면 둘 다 카드다. 수동 카드 중복은 eventId가 막는다.
     */
    @Test
    void 자동_카드는_같은_창이면_막히고_수동은_안_막힌다() {
        insert("s-1", "hotkey", "e-1", 5_000L, 0L, 25_000L, null, null);
        insert("s-1", "hotkey", "e-2", 30_000L, 0L, 50_000L, null, null);
        insert("s-1", "auto", "evt-1", 5_000L, 0L, 25_000L, null, null);

        assertThatThrownBy(() -> insert("s-1", "auto", "evt-2", 30_000L, 0L, 50_000L, null, null))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(count()).isEqualTo(3);
    }

    /** POK-391(V219). 같은 eventId 수동 카드 둘은 방송이 달라도 막힌다(계약4: eventId는 전역 유일). */
    @Test
    void 수동_카드는_eventId가_겹치면_막힌다() {
        broadcasts.save(Broadcast.startedNow("s-2", TestIds.STREAMER, null, 1L, Instant.parse("2026-08-23T00:00:00Z"), null));
        insert("s-1", "hotkey", "e-1", 5_000L, 0L, 25_000L, null, null);

        assertThatThrownBy(() -> insert("s-2", "hotkey", "e-1", 90_000L, 50_000L, 110_000L, null, null))
                .isInstanceOf(DuplicateKeyException.class);
        insert("s-1", "auto", "e-1", 90_000L, 50_000L, 110_000L, null, null);
        assertThat(count()).as("자동 카드의 eventId는 추적용이라 겹쳐도 된다").isEqualTo(2);
    }

    @Test
    void 출처가_다르면_같은_창시작도_들어간다() {
        insert("s-1", "auto", "evt-1", 5_043_000L, 5_020_000L, 5_062_000L, 97, null);
        insert("s-1", "hotkey", null, 5_043_000L, 5_020_000L, 5_062_000L, null, null);

        assertThat(count()).isEqualTo(2);
    }

    @Test
    void 창이_뒤집히면_CHECK가_막는다() {
        assertThatThrownBy(() -> insert("s-1", "auto", "evt-1", 5_500L, 6_000L, 5_000L, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 지점이_창_밖이면_CHECK가_막는다() {
        assertThatThrownBy(() -> insert("s-1", "auto", "evt-1", 9_000L, 1_000L, 2_000L, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 없는_방송에는_못_넣는다_FK() {
        assertThatThrownBy(() -> insert("s-없음", "auto", "evt-1", 1_500L, 1_000L, 2_000L, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 칸은 지금도 비어도 된다. 4B로 만드는 수동 카드는 늘 eventId를 채우지만(POK-391), 부분 유일 색인은
     * NULL끼리 안 부딪혀 옛 행·시험 시드를 막지 않는다.
     */
    @Test
    void event_id는_비워도_된다_핫키() {
        insert("s-1", "hotkey", null, 1_500L, 1_000L, 2_000L, null, null);

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 출처_NULL은_막힌다() {
        assertThatThrownBy(() -> insert("s-1", null, "evt-1", 1_500L, 1_000L, 2_000L, null, null))
                .as("UNIQUE에 NULL이 끼면 중복 방어가 통째로 뚫린다")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 스냅샷은_evidence와_창을_옮긴다() {
        long id = insert("s-1", "auto", "evt-1", 5_043_000L, 5_020_000L, 5_062_000L, 97, "{\"multiplier\":4.2}");

        JumpCardSnapshot snapshot = JumpCardSnapshot.of(cards.findById(id).orElseThrow(), mapper);

        assertThat(snapshot.evidence().get("multiplier").asDouble()).isEqualTo(4.2);
        assertThat(snapshot.window().startMs()).isEqualTo(5_020_000L);
        assertThat(snapshot.window().endMs()).isEqualTo(5_062_000L);
    }

    /** POK-338. 사유 셋 밖의 값은 API가 아닌 길(관리 SQL)로 와도 DB가 막는다. */
    @Test
    void 숨김_사유는_정한_셋만_들어간다() {
        long id = insert("s-1", "auto", "evt-1", 1_500L, 1_000L, 2_000L, null, null);

        jdbc.update("UPDATE jump_cards SET hidden_at = now(), hidden_reason = 'duplicate' WHERE id = ?", id);
        assertThat(jdbc.queryForObject("SELECT hidden_reason FROM jump_cards WHERE id = ?", String.class, id))
                .isEqualTo("duplicate");

        assertThatThrownBy(() -> jdbc.update("UPDATE jump_cards SET hidden_reason = 'boring' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 행동_기록은_정한_행동만_받고_카드가_지워지면_같이_지워진다() {
        long id = insert("s-1", "auto", "evt-1", 1_500L, 1_000L, 2_000L, null, null);
        jdbc.update("INSERT INTO jump_card_actions (card_id, action, actor, reason) VALUES (?, 'hide', '7', 'other')", id);

        jdbc.update("INSERT INTO jump_card_actions (card_id, action, actor) VALUES (?, 'open', '7')", id);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM jump_card_actions WHERE card_id = ? AND action = 'open'", Long.class, id))
                .as("열람은 편집기 열기로 센다(POK-393)").isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO jump_card_actions (card_id, action, actor) VALUES (?, 'peek', '7')", id))
                .as("정한 행동 밖은 막힌다")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO jump_card_actions (card_id, action, actor, reason) VALUES (?, 'hide', '7', 'boring')", id))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM jump_cards WHERE id = ?", id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jump_card_actions", Long.class))
                .as("CASCADE가 없으면 카드를 지우는 시험 정리가 FK로 죽는다").isZero();
    }

    /** 탈퇴 정리가 카드를 지울 때 ON DELETE SET NULL이 recipes를 source_card_id로 찾는다. 색인이 없으면 카드마다 표 전체를 훑는다(PR #254 codex). */
    @Test
    void 편집본_출처_카드에_색인이_있다() {
        assertThat(jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'recipes' AND indexdef LIKE '%(source_card_id)%'",
                String.class)).hasSize(1);
    }

    private long insert(String streamId, String source, String eventId,
                        long ts, long start, long end, Integer score, String evidenceJson) {
        Long id = jdbc.queryForObject("""
                INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms,
                                        window_start_ms, window_end_ms, score, evidence, event_seq)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 0)
                RETURNING id
                """, Long.class, streamId, source, eventId, ts, start, end, score, evidenceJson);
        return id;
    }

    private long seqOf(long id) {
        return jdbc.queryForObject("SELECT event_seq FROM jump_cards WHERE id = ?", Long.class, id);
    }

    private Instant createdAtOf(long id) {
        return jdbc.queryForObject("SELECT created_at FROM jump_cards WHERE id = ?", Instant.class, id);
    }

    private Instant updatedAtOf(long id) {
        return jdbc.queryForObject("SELECT updated_at FROM jump_cards WHERE id = ?", Instant.class, id);
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM jump_cards", Long.class);
    }
}
