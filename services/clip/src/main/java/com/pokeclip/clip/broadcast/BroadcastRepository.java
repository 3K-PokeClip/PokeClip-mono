package com.pokeclip.clip.broadcast;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

    Optional<Broadcast> findByStreamId(String streamId);

    /**
     * <b>「있느냐」만 묻는 자리에서 이것을 쓴다 — {@code findByStreamId}로 바꾸면 안 된다.</b>
     *
     * <p>파생 {@code exists} 쿼리는 스칼라를 돌려주므로 엔티티를 <b>영속성 컨텍스트에 안 올린다</b>.
     * 그 차이가 실제로 문제가 된 자리가 {@code JumpCardStreamController.open}이다 — 거기서는
     * 같은 트랜잭션 안에서 방송 상태를 <b>나중에 다시</b> 읽어 「그 사이에 끝났는가」를 보는데,
     * 앞에서 {@code findByStreamId}로 엔티티를 올려 두면 뒤의 조회가 JPQL을 던지고도
     * <b>1차 캐시의 낡은 인스턴스</b>를 돌려준다(JPA가 DB에서 읽은 값을 버린다).
     * 2026-08-23에 그 회귀를 실제로 냈고 {@code StreamOpenWindowTest}가 잡았다.
     */
    boolean existsByStreamId(String streamId);

    /**
     * 스트리머 번호 <b>한 칸만</b> 뽑는다. auth에 자격을 물으려면 이 값이 필요한데,
     * {@code findByStreamId}로 엔티티를 올리면 안 되기 때문에 따로 있다.
     *
     * <p><b>{@code existsByStreamId} 주석과 같은 뿌리다.</b> 스칼라 조회는 엔티티를
     * 영속성 컨텍스트에 안 올린다. {@code BroadcastAccessGuard}가 이것만 쓰는 이유는
     * 판정 뒤에 부르는 쪽({@code JumpCardStreamController.open})이 같은 트랜잭션 안에서
     * 방송 상태를 <b>다시</b> 읽어 「그 사이에 끝났는가」를 보기 때문이다 — 앞에서 엔티티를
     * 올려 두면 그 재조회가 JPQL을 던지고도 1차 캐시의 낡은 인스턴스를 돌려준다.
     * 계획 검증이 재현했다({@code StreamOpenWindowTest} FAILED).
     *
     * <p><b>{@code SegmentQueryService}가 {@code findByStreamId}를 쓰는 것은 정상이다</b> —
     * 그쪽은 판정 뒤에 방송을 다시 안 읽는다(만료 판정이 네이티브 스칼라 쿼리라 1차 캐시를
     * 안 지난다). 쌍둥이지만 역할이 달라 다른 것이 맞다.
     */
    @Query("select b.streamerId from Broadcast b where b.streamId = :streamId")
    Optional<String> findStreamerIdByStreamId(@Param("streamId") String streamId);

    /**
     * 같은 방송 줄을 고치는 동안 다른 처리를 세운다. FIFO 큐가 같은 그룹을 동시에
     * 주지 않으므로 드물지만, 그것은 큐의 보장이지 우리 코드의 보장이 아니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Broadcast b where b.streamId = :streamId")
    Optional<Broadcast> findByStreamIdForUpdate(@Param("streamId") String streamId);

    /**
     * 방송 목록 한 장. <b>정렬과 이어받기가 둘 다 {@code id}다</b> — 시작·종료 시각은
     * 뒤늦게 온 알림이 갱신하므로({@link Broadcast#applyStarted}·{@link Broadcast#applyEnded})
     * 이어받기 기준으로 쓰면 중복·누락이 난다. {@code id}는 그 방송을 처음 안 순서이고
     * 절대 안 변한다 — 같은 방송의 알림이 다시 와도 새 줄이 안 생긴다({@code stream_id} UNIQUE).
     *
     * <p><b>{@code streamerIds}는 문자열이다.</b> auth는 숫자를 주는데 이 칸은 {@code VARCHAR}라
     * 부르는 쪽이 바꿔 넣는다. 🔴 <b>그 변환이 관대하지 않다</b> — {@code "007"}이 든 줄은
     * {@code 7}로 못 찾는다. 세그먼트 조회는 반대 방향({@code parseLong})이라 그 줄을 열어 주므로
     * <b>두 문의 판정이 갈린다.</b> 알려진 한계이고 {@code BroadcastListQueryTest}가 고정한다.
     *
     * <p>{@code afterId}가 {@code null}이면 첫 장이다. {@code :afterId IS NULL} 갈래를 SQL 안에
     * 두는 이유 — 쿼리를 둘로 나누면 나머지 조건이 갈릴 자리가 생긴다.
     */
    @Query(value = """
            SELECT * FROM broadcasts
             WHERE streamer_id IN (:streamerIds)
               AND status IN (:statuses)
               AND (CAST(:afterId AS BIGINT) IS NULL OR id < CAST(:afterId AS BIGINT))
             ORDER BY id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<Broadcast> findPage(@Param("streamerIds") List<String> streamerIds,
                             @Param("statuses") List<String> statuses,
                             @Param("afterId") Long afterId,
                             @Param("limit") int limit);

    /**
     * 지금 방송 중인 줄을 최근 시작 순으로. <b>수집기가 재시작 뒤 붙을 대상</b>이다(POK-218).
     *
     * <p><b>상태를 파라미터가 아니라 리터럴로 쓴다.</b> 파라미터도 지금은 custom plan을 골라
     * 부분 색인을 타지만(PostgreSQL 17 실측), generic plan이 선택되는 날 플래너가 값을 몰라
     * 색인의 술어를 함의한다고 증명하지 못해 Seq Scan이 된다({@code force_generic_plan}으로
     * 재현). 리터럴은 그 가능성 자체를 없앤다. <b>대가는 {@link BroadcastStatus#LIVE}와 갈릴 수
     * 있다는 것</b>이고, 갈리면 조회가 조용히 0행이 된다 —
     * {@code LiveBroadcastQueryTest.상태_문자열이_열거형과_갈리지_않는다}가 이 SQL을 직접 읽어 막는다.
     *
     * <p>🔴 <b>{@code NULLS LAST}가 정렬과 색인 양쪽에 있어야 한다.</b> PostgreSQL은 {@code DESC}에서
     * {@code NULLS FIRST}가 기본이라, 이것이 없으면 시각이 빈 줄이 <b>맨 앞</b>을 먹어 상한 500이
     * 그런 줄로 다 차면 진짜 방송이 하나도 안 나간다. 운영 경로로는 그런 줄이 도달 불가인 것을
     * {@code LiveStartedAtNeverNullTest}가 재현으로 고정했지만, <b>그 방어는 러너의 봉투 검증 한
     * 줄뿐</b>이라 사라지는 날을 대비해 둔다 — 비용이 0이다.
     * 색인({@code V205})도 같은 {@code NULLS LAST}여야 한다. 안 그러면 정렬이 안 맞아 {@code Sort}가
     * 붙고 색인이 통째로 버려진다(계획 검증 실측: 버퍼 9 → 1,428).
     */
    @Query(value = """
            SELECT stream_id AS streamId, streamer_id AS streamerId, started_at AS startedAt
              FROM broadcasts
             WHERE status = 'live'
             ORDER BY started_at DESC NULLS LAST
             LIMIT :limit
            """, nativeQuery = true)
    List<LiveBroadcastRow> findLive(@Param("limit") int limit);

    /**
     * 보관 기한이 지났는가를 <b>DB 시계로</b> 판정한다(PRD 결정). 앱 시계로 재면 서버마다
     * 판정이 갈려, 같은 방송이 어느 인스턴스에 붙느냐에 따라 보이기도 하고 안 보이기도 한다.
     *
     * <p><b>{@code Boolean}(박스형)으로 받는다</b> — primitive {@code boolean}이면 0행일 때
     * 언박싱에서 죽는다. 0행은 실제로 생길 수 있다: 부르는 쪽이 방송을 먼저 조회하고
     * <b>그 사이에 auth 왕복(최대 7초)이 끼므로</b>, 만료 삭제 배치가 붙는 날 그 창에서
     * 행이 사라질 수 있다. 그때 옳은 답은 만료가 아니라 「없는 방송」 쪽이지만, 여기서는
     * {@code null}로 두어 <b>죽지 않는 것이 먼저다</b>.
     *
     * <p>그래서 부르는 쪽은 {@code Boolean.TRUE.equals(...)}로 판정한다 —
     * 0행({@code null})과 기한이 NULL인 방송(아직 안 끝남) 둘 다 「만료 아님」으로 떨어진다.
     */
    @Query(value = "SELECT b.vod_expires_at IS NOT NULL AND b.vod_expires_at < now() "
            + "FROM broadcasts b WHERE b.stream_id = :streamId", nativeQuery = true)
    Boolean isVodExpired(@Param("streamId") String streamId);
}
