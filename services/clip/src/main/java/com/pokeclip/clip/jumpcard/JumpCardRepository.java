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

    /**
     * 그 방송 카드 전부, 순번 순. 숨긴 카드도 포함한다 — 숨김은 표시 여부이지 삭제가 아니다.
     *
     * <p>🔴 <b>이름이 「연결 직후 스냅샷」이던 자리다. POK-174가 그 전송을 없앴다</b> —
     * 통로는 지난 카드를 안 보내고 따라잡기는 <b>바로 아래 {@link #findPage}</b>가 맡는다.
     * 그래서 이 메서드의 <b>운영 호출자가 0</b>이다(남긴 이유는 {@code JumpCardService.snapshotsOf} 주석).
     * 네 줄 아래 javadoc이 「{@code event_seq}로 이어받으면 카드가 조용히 빠진다」고 말하는 것과
     * 같은 사실이다 — <b>새 목록 문에서 이 정렬을 쓰지 마라</b>.
     */
    List<JumpCard> findAllByStreamIdOrderByEventSeqAsc(String streamId);

    /**
     * 카드 목록 한 장. <b>정렬이 {@code event_seq}가 아니라 {@code stream_timestamp_ms}다.</b>
     *
     * <p>🔴 두 가지 이유가 겹친다. ① {@code event_seq}는 <b>마지막으로 바뀐 순서</b>라 카드를
     * 숨기면 트리거({@code trg_jump_cards_touch})가 순번을 올려 <b>목록에서 자리가 바뀐다</b>.
     * ② 그 시퀀스는 트랜잭션 밖에서 증가해 번호 순서와 커밋 순서가 다를 수 있고, 이어받기
     * 조건({@code seq > last})으로 쓰면 <b>카드가 조용히 빠진다</b>({@code V202} 주석·PRD 결정).
     *
     * <p>{@code stream_timestamp_ms}는 저장될 때 정해지고 쓰기 경로(점유·숨김)가 안 건드린다.
     * 다만 <b>유일하지 않다</b> — 자동과 핫키가 같은 시각을 가질 수 있어({@code uq_jump_cards_window}가
     * {@code (방송, 출처, 창 시작)}이라 막지 않는다) {@code id}로 마저 가른다.
     *
     * <p>🔴 <b>{@code afterTs}와 {@code afterId}는 함께 오거나 함께 비어야 한다.</b> 시각만 주면
     * {@code (stream_timestamp_ms = :afterTs AND id > NULL)}이 NULL로 평가돼 <b>같은 방송 시간의
     * 뒷줄이 조용히 빠진다</b>(계획 검증 실측). 그것을 막는 자리는 여기가 아니라
     * {@code CursorCodec}의 칸 수 검사이고, 빠지는 모습 자체는
     * {@code JumpCardListQueryTest.afterId가_없으면_같은_방송_시간의_뒷줄이_빠진다}가 고정한다.
     *
     * <p>{@code :includeHidden = TRUE} 갈래를 SQL 안에 두는 이유는 {@code BroadcastRepository.findPage}와
     * 같다 — 쿼리를 둘로 나누면 나머지 조건이 갈릴 자리가 생긴다.
     */
    @Query(value = """
            SELECT * FROM jump_cards
             WHERE stream_id = :streamId
               AND (:includeHidden = TRUE OR hidden_at IS NULL)
               AND (CAST(:afterTs AS BIGINT) IS NULL
                    OR stream_timestamp_ms > CAST(:afterTs AS BIGINT)
                    OR (stream_timestamp_ms = CAST(:afterTs AS BIGINT) AND id > CAST(:afterId AS BIGINT)))
             ORDER BY stream_timestamp_ms ASC, id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<JumpCard> findPage(@Param("streamId") String streamId,
                            @Param("includeHidden") boolean includeHidden,
                            @Param("afterTs") Long afterTs,
                            @Param("afterId") Long afterId,
                            @Param("limit") int limit);

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
