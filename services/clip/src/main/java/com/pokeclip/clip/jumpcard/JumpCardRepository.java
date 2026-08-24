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

    /**
     * 집을 때 판정한다 — 만료를 치우는 배경 작업이 없다. UPDATE 하나가 원자적이라 락이
     * 필요 없고, 동시 요청 둘 중 하나만 영향 행 1을 받는다.
     *
     * <p>{@code now()}는 DB 시계다. 앱 시계로 재면 서버마다 달라 "누가 먼저 잡았나"가
     * 서버에 따라 갈린다.
     *
     * <p>{@code claimed_by = :me} 갈래는 <b>본인 재호출을 연장으로 만든다</b>. 없으면
     * 본인도 영향 행 0을 받아 자기가 잡은 카드에 409를 맞는다.
     *
     * @return 1이면 내가 잡았다(또는 연장했다). 0이면 없는 카드이거나 남이 잡고 있다 — 호출자가 가른다
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE jump_cards SET claimed_by = :me, claimed_at = now()
             WHERE id = :id
               AND (claimed_by IS NULL OR claimed_by = :me OR claimed_at < now() - make_interval(secs => :ttlSeconds))
            """, nativeQuery = true)
    int claim(@Param("id") long id, @Param("me") String me, @Param("ttlSeconds") long ttlSeconds);

    /** @return 1이면 놓았다. 0이면 없는 카드이거나 남이 잡고 있다 — 호출자가 가른다 */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE jump_cards SET claimed_by = NULL, claimed_at = NULL WHERE id = :id AND claimed_by = :me",
            nativeQuery = true)
    int release(@Param("id") long id, @Param("me") String me);

    /**
     * <b>이미 숨겨진 카드는 건드리지 않는다.</b> 조건이 없으면 나중에 누른 사람이 {@code hidden_by}를
     * 덮어써 <b>「누가 숨겼나」의 추적 대상이 마지막에 누른 사람으로 바뀐다</b> — 얕은 인가를 감수한
     * 근거가 그 추적이었으므로 그것이 무너진다(로컬 리뷰 사소 ④).
     *
     * @return 1이면 이번에 숨겼다. 0이면 없는 카드이거나 <b>이미 숨겨져 있다</b> — 호출자가 가른다
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE jump_cards SET hidden_at = now(), hidden_by = :me
             WHERE id = :id AND hidden_at IS NULL
            """, nativeQuery = true)
    int hide(@Param("id") long id, @Param("me") String me);

    /**
     * 되돌리기는 누구나 한다 — 숨긴 사람만 되돌릴 수 있으면 그 사람이 자리를 비웠을 때 막힌다.
     * 안 숨겨진 카드에는 0행이다(무의미한 이벤트를 안 내보내려고).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE jump_cards SET hidden_at = NULL, hidden_by = NULL
             WHERE id = :id AND hidden_at IS NOT NULL
            """, nativeQuery = true)
    int unhide(@Param("id") long id);
}
