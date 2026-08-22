package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
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
        broadcasts.save(Broadcast.startedNow("s-1", "u-1", 1L, Instant.parse("2026-08-23T00:00:00Z"), null));
    }

    @Test
    void 카드를_넣으면_event_seq와_시각을_DB가_채운다() {
        insert("s-1", "auto", "evt-1", 5_043_000L, 5_020_000L, 5_062_000L, 97, "{\"multiplier\":4.2}");

        JumpCard card = cards.findByStreamIdAndSourceAndWindowStartMs("s-1", JumpCardSource.AUTO, 5_020_000L)
                .orElseThrow();

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
    void 같은_방송_출처_창시작은_두_번_못_넣는다() {
        insert("s-1", "auto", "evt-1", 5_043_000L, 5_020_000L, 5_062_000L, 97, null);

        assertThatThrownBy(() -> insert("s-1", "auto", "evt-2", 5_043_000L, 5_020_000L, 5_062_000L, 97, null))
                .as("중복 방어선은 uq_jump_cards_window 하나뿐이다")
                .isInstanceOf(DuplicateKeyException.class);
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

    /** 핫키(POK-119)는 판별기 이벤트 번호가 없다. 추적용 칸이라 비어도 된다. */
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
    void 스냅샷은_claimExpiresAt을_ttl로_계산한다() {
        long id = insert("s-1", "auto", "evt-1", 5_043_000L, 5_020_000L, 5_062_000L, 97, "{\"multiplier\":4.2}");
        jdbc.update("UPDATE jump_cards SET claimed_by = 'u-9', claimed_at = now() WHERE id = ?", id);

        JumpCardSnapshot snapshot = JumpCardSnapshot.of(cards.findById(id).orElseThrow(),
                Duration.ofMinutes(30), mapper);

        assertThat(snapshot.claimedBy()).isEqualTo("u-9");
        assertThat(snapshot.claimExpiresAt()).isEqualTo(snapshot.claimedAt().plus(Duration.ofMinutes(30)));
        assertThat(snapshot.evidence().get("multiplier").asDouble()).isEqualTo(4.2);
        assertThat(snapshot.hidden()).isFalse();
        assertThat(snapshot.window().startMs()).isEqualTo(5_020_000L);
        assertThat(snapshot.window().endMs()).isEqualTo(5_062_000L);
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
