package com.pokeclip.clip.jumpcard;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JumpCardRepository extends JpaRepository<JumpCard, Long> {

    /** 자연키 조회. {@code uq_jump_cards_window}와 같은 세 칸이다. */
    Optional<JumpCard> findByStreamIdAndSourceAndWindowStartMs(String streamId, JumpCardSource source, long windowStartMs);

    /** 연결 직후 스냅샷. 숨긴 카드도 포함한다 — 숨김은 표시 여부이지 삭제가 아니다. */
    List<JumpCard> findAllByStreamIdOrderByEventSeqAsc(String streamId);

    /**
     * 이 한 줄이 중복 방어선이다. 조회 후 삽입은 동시 요청에 뚫린다 — ON CONFLICT는
     * PostgreSQL이 원자적으로 판정하므로 그 틈이 없다.
     *
     * <p><b>예외가 아니라 반환값으로 가른다.</b> 예외로 가르면 FK·CHECK 위반이 중복으로
     * 보고돼 판별기가 "성공"으로 읽고 다시 안 보낸다(POK-82 함정).
     *
     * <p>{@code event_seq}에 0을 넣는 이유: NOT NULL 칸이라 값이 있어야 하고,
     * BEFORE INSERT 트리거가 nextval로 덮는다.
     *
     * @return 1이면 새 카드, 0이면 같은 (방송·출처·창 시작)이 이미 있다
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms,
                                    window_start_ms, window_end_ms, score, evidence, event_seq)
            VALUES (:streamId, :source, :eventId, :ts, :start, :end, :score, CAST(:evidence AS jsonb), 0)
            ON CONFLICT (stream_id, source, window_start_ms) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("streamId") String streamId,
                       @Param("source") String source,
                       @Param("eventId") String eventId,
                       @Param("ts") long ts,
                       @Param("start") long start,
                       @Param("end") long end,
                       @Param("score") Integer score,
                       @Param("evidence") String evidenceJson);
}
