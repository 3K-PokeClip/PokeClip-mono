package com.pokeclip.clip.broadcast;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 받은 편지 기록. event_id UNIQUE가 멱등의 진짜 방어선이다(POK-87).
 *
 * <p>명부의 id가 아니라 봉투에 적힌 stream_id를 적는다 — 처리에 실패해 명부 줄이
 * 안 생긴 편지도 남아야 나중에 추적할 수 있다.
 */
@Entity
@Table(name = "broadcast_events")
public class BroadcastEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "stream_id", nullable = false, length = 128)
    private String streamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private LifecycleEventType eventType;

    @Column(name = "sequence_no", nullable = false)
    private Long sequenceNo;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected BroadcastEvent() {
    }

    private BroadcastEvent(String eventId, String streamId, LifecycleEventType eventType,
                           long sequenceNo, Instant processedAt) {
        this.eventId = eventId;
        this.streamId = streamId;
        this.eventType = eventType;
        this.sequenceNo = sequenceNo;
        this.processedAt = processedAt;
    }

    public static BroadcastEvent of(String eventId, String streamId, LifecycleEventType eventType,
                                    long sequenceNo, Instant processedAt) {
        return new BroadcastEvent(eventId, streamId, eventType, sequenceNo, processedAt);
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getStreamId() {
        return streamId;
    }

    public LifecycleEventType getEventType() {
        return eventType;
    }

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
