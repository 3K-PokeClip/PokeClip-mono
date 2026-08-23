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
     * 같은 방송 줄을 고치는 동안 다른 처리를 세운다. FIFO 큐가 같은 그룹을 동시에
     * 주지 않으므로 드물지만, 그것은 큐의 보장이지 우리 코드의 보장이 아니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Broadcast b where b.streamId = :streamId")
    Optional<Broadcast> findByStreamIdForUpdate(@Param("streamId") String streamId);
}
