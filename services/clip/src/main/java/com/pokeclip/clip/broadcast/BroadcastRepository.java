package com.pokeclip.clip.broadcast;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
