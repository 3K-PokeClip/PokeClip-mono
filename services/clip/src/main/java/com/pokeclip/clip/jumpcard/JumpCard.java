package com.pokeclip.clip.jumpcard;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 점프카드 한 장. <b>읽기 전용이다</b> — 쓰기는 전부 {@link JumpCardRepository}의 네이티브 SQL이다.
 *
 * <p>{@code @Immutable}을 붙인 이유: save/merge로 쓰면 앱 시계가 {@code updated_at}에 들어가고
 * {@code event_seq} 트리거를 우회한다. 네이티브 SQL만 쓰면 DB 시계와 트리거를 반드시 탄다.
 */
@Entity
@Table(name = "jump_cards")
@Immutable
public class JumpCard {

    // GeneratedValue를 안 붙인다 — 이 엔티티로는 INSERT하지 않으므로 생성 전략이 필요 없다.
    @Id
    private Long id;

    @Column(name = "stream_id", nullable = false, length = 128)
    private String streamId;

    @Convert(converter = JumpCardSourceConverter.class)
    @Column(name = "source", nullable = false, length = 16)
    private JumpCardSource source;

    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(name = "stream_timestamp_ms", nullable = false)
    private long streamTimestampMs;

    @Column(name = "window_start_ms", nullable = false)
    private long windowStartMs;

    @Column(name = "window_end_ms", nullable = false)
    private long windowEndMs;

    @Column(name = "score")
    private Integer score;

    // @JdbcTypeCode가 없으면 드라이버가 varchar로 보내 jsonb 칸에 못 들어간다.
    // 값이 null이어도 터지고 ddl-auto=validate는 못 잡는다(Broadcast.trackManifest와 같은 함정).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private String evidence;

    @Column(name = "claimed_by", length = 128)
    private String claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "hidden_at")
    private Instant hiddenAt;

    @Column(name = "hidden_by", length = 128)
    private String hiddenBy;

    @Column(name = "event_seq", nullable = false)
    private long eventSeq;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JumpCard() {
    }

    public Long getId() {
        return id;
    }

    public String getStreamId() {
        return streamId;
    }

    public JumpCardSource getSource() {
        return source;
    }

    public String getEventId() {
        return eventId;
    }

    public long getStreamTimestampMs() {
        return streamTimestampMs;
    }

    public long getWindowStartMs() {
        return windowStartMs;
    }

    public long getWindowEndMs() {
        return windowEndMs;
    }

    public Integer getScore() {
        return score;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getHiddenAt() {
        return hiddenAt;
    }

    public String getHiddenBy() {
        return hiddenBy;
    }

    public long getEventSeq() {
        return eventSeq;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
