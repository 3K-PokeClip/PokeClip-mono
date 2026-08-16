package com.pokeclip.auth.chzzk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChzzkChannelLinkRepository extends JpaRepository<ChzzkChannelLink, Long> {

    Optional<ChzzkChannelLink> findByUserIdAndRevokedAtIsNull(Long userId);

    /** 닫힌 행도 찾힌다. GET·resolve가 BROKEN·UNLINKED를 구분해 돌려주려면 필요하다. */
    Optional<ChzzkChannelLink> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ChzzkChannelLink> findByChannelIdAndRevokedAtIsNull(String channelId);

    /**
     * 살아있는 내 연동을 닫는다. 0행이면 닫을 것이 없었다 — 첫 연동·해제 뒤 재연동·연동 없는 DELETE.
     * 재연동(USER_UNLINKED)과 해제(USER_UNLINKED)가 같은 줄을 쓴다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChzzkChannelLink l set l.revokedAt = :now, l.revokeReason = :reason "
            + "where l.userId = :userId and l.revokedAt is null")
    int revokeAlive(@Param("userId") Long userId, @Param("now") Instant now, @Param("reason") RevokeReason reason);

    /** 스케줄러 후보. id만 뽑는다 — 갱신기가 락 뒤에 다시 읽는다(락 전 엔티티 읽기 금지). */
    @Query("select l.userId from ChzzkChannelLink l "
            + "where l.revokedAt is null and l.accessExpiresAt < :threshold order by l.accessExpiresAt")
    List<Long> findUserIdsExpiringBefore(@Param("threshold") Instant threshold);
}
