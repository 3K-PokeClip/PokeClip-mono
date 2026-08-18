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
     * 같은 방송 줄을 고치는 동안 다른 처리를 세운다. FIFO 큐가 같은 그룹을 동시에
     * 주지 않으므로 드물지만, 그것은 큐의 보장이지 우리 코드의 보장이 아니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Broadcast b where b.streamId = :streamId")
    Optional<Broadcast> findByStreamIdForUpdate(@Param("streamId") String streamId);
}
