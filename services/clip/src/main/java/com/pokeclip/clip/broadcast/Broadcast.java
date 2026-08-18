package com.pokeclip.clip.broadcast;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 방송 명부. 방송 한 회당 한 줄이고 stream_id가 그 방송의 이름이다. */
@Entity
@Table(name = "broadcasts")
public class Broadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stream_id", nullable = false, length = 128)
    private String streamId;

    @Column(name = "streamer_id", nullable = false, length = 128)
    private String streamerId;

    @Convert(converter = BroadcastStatusConverter.class)
    @Column(name = "status", nullable = false, length = 16)
    private BroadcastStatus status;

    /** 종료 선도착 placeholder는 null이다. */
    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    // columnDefinition만으로는 부족하다 — @JdbcTypeCode가 없으면 드라이버가 varchar로
    // 보내고 "column is of type jsonb but expression is of type character varying"로
    // INSERT가 전부 실패한다. 값이 null이어도 터진다(setNull(VARCHAR)).
    // ddl-auto=validate는 이걸 못 잡는다 (plan-critic 실측 2026-08-18).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "track_manifest", columnDefinition = "jsonb")
    private String trackManifest;

    @Column(name = "last_sequence", nullable = false)
    private Long lastSequence;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Broadcast() {
    }

    private Broadcast(String streamId, String streamerId, BroadcastStatus status,
                      Instant startedAt, Instant endedAt, String trackManifest, long lastSequence) {
        this.streamId = streamId;
        this.streamerId = streamerId;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.trackManifest = trackManifest;
        this.lastSequence = lastSequence;
        this.updatedAt = Instant.now();
    }

    public static Broadcast startedNow(String streamId, String streamerId, long sequence,
                                       Instant startedAt, String trackManifest) {
        return new Broadcast(streamId, streamerId, BroadcastStatus.LIVE,
                startedAt, null, trackManifest, sequence);
    }

    /** ADR-016의 ended placeholder — 시작을 못 본 채 끝을 먼저 받은 줄. */
    public static Broadcast endedPlaceholder(String streamId, String streamerId,
                                             long sequence, Instant endedAt) {
        return new Broadcast(streamId, streamerId, BroadcastStatus.ENDED,
                null, endedAt, null, sequence);
    }

    public Long getId() {
        return id;
    }

    public String getStreamId() {
        return streamId;
    }

    public String getStreamerId() {
        return streamerId;
    }

    public BroadcastStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getTrackManifest() {
        return trackManifest;
    }

    public Long getLastSequence() {
        return lastSequence;
    }
}
