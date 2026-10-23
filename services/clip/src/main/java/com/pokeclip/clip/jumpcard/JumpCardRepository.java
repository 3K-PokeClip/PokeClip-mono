package com.pokeclip.clip.jumpcard;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JumpCardRepository extends JpaRepository<JumpCard, Long> {

    /**
     * 자동 카드의 자연키 조회. {@code uq_jump_cards_auto_window}(V219)와 같은 두 칸이다.
     *
     * <p>출처를 인자가 아니라 <b>{@code 'auto'} 글자</b>로 적는다(계획 검증 E7): 부분 색인은 질의에 같은 술어가
     * 글자로 있어야 탄다. 그리고 같은 창 시작의 수동 카드를 집지 않는다(수동 카드는 그 색인 밖이라 겹칠 수 있다).
     */
    @Query(value = "SELECT * FROM jump_cards WHERE stream_id = :streamId AND window_start_ms = :windowStartMs"
            + " AND source = 'auto'", nativeQuery = true)
    Optional<JumpCard> findAutoByStreamIdAndWindowStartMs(@Param("streamId") String streamId,
                                                          @Param("windowStartMs") long windowStartMs);

    /**
     * 수동 카드를 eventId로. 방송을 안 건다: 계약4가 eventId를 전역 유일로 본다(다른 방송의 카드여도 그 카드).
     * {@code uq_jump_cards_hotkey_event}(V219)를 탄다.
     */
    @Query(value = "SELECT * FROM jump_cards WHERE event_id = :eventId AND source = 'hotkey'", nativeQuery = true)
    Optional<JumpCard> findHotkeyByEventId(@Param("eventId") String eventId);

    /**
     * 같은 방송에서 누른 시각이 {@code |stream_timestamp_ms − ts| < withinMs}인 수동 카드 중 가장 가까운 것.
     * 양방향이다: 늦게 온 재전송이 앞 시각이어도 같게 본다(PRD). 같은 거리면 먼저 만든 카드.
     * {@code idx_jump_cards_stream_ts}(V204)의 범위로 좁힌다.
     */
    @Query(value = """
            SELECT * FROM jump_cards
             WHERE stream_id = :streamId AND source = 'hotkey'
               AND stream_timestamp_ms > :ts - :withinMs AND stream_timestamp_ms < :ts + :withinMs
             ORDER BY abs(stream_timestamp_ms - :ts), id
             LIMIT 1
            """, nativeQuery = true)
    Optional<JumpCard> findHotkeyNear(@Param("streamId") String streamId,
                                      @Param("ts") long ts,
                                      @Param("withinMs") long withinMs);

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
     * 다만 <b>유일하지 않다</b>: 자동과 핫키가 같은 시각을 가질 수 있어(자동 카드 유일 색인
     * {@code uq_jump_cards_auto_window}는 자동끼리만 막고 시각을 안 본다) {@code id}로 마저 가른다.
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
     * <b>자동 카드(2A) 전용이다</b>(POK-391부터. 수동 카드는 {@link #insertHotkey}). 이 한 줄이 중복 방어선이다. 조회 후 삽입은 동시 요청에 뚫린다: ON CONFLICT는
     * PostgreSQL이 원자적으로 판정하므로 그 틈이 없다.
     *
     * <p><b>예외가 아니라 반환값으로 가른다.</b> 예외로 가르면 FK·CHECK 위반이 중복으로
     * 보고돼 판별기가 "성공"으로 읽고 다시 안 보낸다(POK-82 함정).
     *
     * <p>{@code event_seq}에 0을 넣는 이유: NOT NULL 칸이라 값이 있어야 하고,
     * BEFORE INSERT 트리거가 nextval로 덮는다.
     *
     * <p>출처는 {@code 'auto'} 글자다. 충돌 대상이 부분 색인({@code uq_jump_cards_auto_window}, V219)이라
     * {@code ON CONFLICT}에 같은 술어를 적어야 그 색인을 고른다. 안 적으면 42P10으로 실패한다(계획 검증 E13).
     *
     * @return 1이면 새 카드, 0이면 같은 (방송·창 시작)의 자동 카드가 이미 있다
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms,
                                    window_start_ms, window_end_ms, score, evidence, event_seq)
            VALUES (:streamId, 'auto', :eventId, :ts, :start, :end, :score, CAST(:evidence AS jsonb), 0)
            ON CONFLICT (stream_id, window_start_ms) WHERE source = 'auto' DO NOTHING
            """, nativeQuery = true)
    int insertAutoIfAbsent(@Param("streamId") String streamId,
                           @Param("eventId") String eventId,
                           @Param("ts") long ts,
                           @Param("start") long start,
                           @Param("end") long end,
                           @Param("score") Integer score,
                           @Param("evidence") String evidenceJson);

    /**
     * 수동 카드 한 장(POK-391). 방송 행 잠금 안에서 부른다: 20초 판정은 그 잠금이 지킨다.
     * eventId 유일 색인은 마지막 방어선이다: 잠금 밖에서 같은 eventId가 <b>다른 방송</b>으로 동시에 오면
     * 여기서 부딪힌다. 예외 대신 0을 돌려주고 부르는 쪽이 eventId로 다시 읽는다({@link #insertAutoIfAbsent}와 같은 규칙).
     *
     * @return 1이면 새 카드, 0이면 같은 eventId의 수동 카드가 이미 있다
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms,
                                    window_start_ms, window_end_ms, event_seq)
            VALUES (:streamId, 'hotkey', :eventId, :ts, :start, :end, 0)
            ON CONFLICT (event_id) WHERE source = 'hotkey' DO NOTHING
            """, nativeQuery = true)
    int insertHotkey(@Param("streamId") String streamId,
                     @Param("eventId") String eventId,
                     @Param("ts") long ts,
                     @Param("start") long start,
                     @Param("end") long end);

    /**
     * 편집기가 카드를 열었다(POK-393). 같은 사람이 다시 열어도 한 줄 더 남긴다.
     *
     * <p><b>FK 위반(500) 대신 반환값으로 가른다</b>({@link #insertAutoIfAbsent}와 같은 규칙). 카드가 판정 뒤
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
