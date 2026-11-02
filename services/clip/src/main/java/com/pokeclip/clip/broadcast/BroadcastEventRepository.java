package com.pokeclip.clip.broadcast;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface BroadcastEventRepository extends JpaRepository<BroadcastEvent, Long> {

    boolean existsByEventId(String eventId);

    /**
     * 이 한 줄이 멱등의 방어선이다. 먼저 조회하고 나중에 넣는 방식은 동시 요청에
     * 뚫린다(auth PairingAttemptRecorder가 같은 함정을 겪었다). ON CONFLICT는
     * PostgreSQL이 원자적으로 판정하므로 그 틈이 없다.
     *
     * <p><b>예외가 아니라 반환값으로 가른다.</b> 예외로 가르면 event_id 중복과
     * streamer_id NOT NULL 위반이 같은 타입이라 구분이 안 되고, 저장 실패가
     * 중복으로 보고돼 러너가 메시지를 지운다(plan-critic 실측 2026-08-18).
     *
     * @return 1이면 새로 넣었다. 0이면 이미 있던 편지다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO broadcast_events (event_id, stream_id, event_type, sequence_no, processed_at)
            VALUES (:eventId, :streamId, :eventType, :sequenceNo, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("streamId") String streamId,
                       @Param("eventType") String eventType,
                       @Param("sequenceNo") long sequenceNo,
                       @Param("processedAt") Instant processedAt);
}
