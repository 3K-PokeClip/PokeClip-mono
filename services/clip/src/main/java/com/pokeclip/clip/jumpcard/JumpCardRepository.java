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
     * 그 방송 카드 전부, 순번 순.
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
     * <p>🔴 두 가지 이유가 겹친다. ① {@code event_seq}는 <b>마지막으로 바뀐 순서</b>라 행이 바뀌면
     * 트리거({@code trg_jump_cards_touch})가 순번을 올려 <b>목록에서 자리가 바뀐다</b>.
     * ② 그 시퀀스는 트랜잭션 밖에서 증가해 번호 순서와 커밋 순서가 다를 수 있고, 이어받기
     * 조건({@code seq > last})으로 쓰면 <b>카드가 조용히 빠진다</b>({@code V202} 주석·PRD 결정).
     *
     * <p>{@code stream_timestamp_ms}는 저장될 때 정해지고 그 뒤로 안 바뀐다.
     * 다만 <b>유일하지 않다</b> — 자동과 핫키가 같은 시각을 가질 수 있어({@code uq_jump_cards_window}가
     * {@code (방송, 출처, 창 시작)}이라 막지 않는다) {@code id}로 마저 가른다.
     *
     * <p>🔴 <b>{@code afterTs}와 {@code afterId}는 함께 오거나 함께 비어야 한다.</b> 시각만 주면
     * {@code (stream_timestamp_ms = :afterTs AND id > NULL)}이 NULL로 평가돼 <b>같은 방송 시간의
     * 뒷줄이 조용히 빠진다</b>(계획 검증 실측). 그것을 막는 자리는 여기가 아니라
     * {@code CursorCodec}의 칸 수 검사이고, 빠지는 모습 자체는
     * {@code JumpCardListQueryTest.afterId가_없으면_같은_방송_시간의_뒷줄이_빠진다}가 고정한다.
     */
    @Query(value = """
            SELECT * FROM jump_cards
             WHERE stream_id = :streamId
               AND (CAST(:afterTs AS BIGINT) IS NULL
                    OR stream_timestamp_ms > CAST(:afterTs AS BIGINT)
                    OR (stream_timestamp_ms = CAST(:afterTs AS BIGINT) AND id > CAST(:afterId AS BIGINT)))
             ORDER BY stream_timestamp_ms ASC, id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<JumpCard> findPage(@Param("streamId") String streamId,
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
     * 편집기가 카드를 열었다(POK-393). 같은 사람이 다시 열어도 한 줄 더 남긴다.
     *
     * <p><b>FK 위반(500) 대신 반환값으로 가른다</b>({@link #insertIfAbsent}와 같은 규칙). 카드가 판정 뒤
     * 지워졌으면(순서대로 온 경우) 0이다. 탈퇴 정리와 동시면 FK 위반 500이 날 수 있는데, 탈퇴 중인
     * 스트리머의 카드라 감수한다.
     *
     * @return 1이면 남겼다. 0이면 카드가 없다
     */
    @Modifying
    @Query(value = """
            INSERT INTO jump_card_actions (card_id, action, actor)
            SELECT id, 'open', :actor FROM jump_cards WHERE id = :cardId
            """, nativeQuery = true)
    int recordOpen(@Param("cardId") long cardId, @Param("actor") String actor);
}
