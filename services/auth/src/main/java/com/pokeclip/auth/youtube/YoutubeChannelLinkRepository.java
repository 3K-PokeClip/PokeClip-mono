package com.pokeclip.auth.youtube;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface YoutubeChannelLinkRepository extends JpaRepository<YoutubeChannelLink, Long> {

    Optional<YoutubeChannelLink> findByUserIdAndRevokedAtIsNull(Long userId);

    /** 닫힌 행도 찾힌다. GET·resolve가 BROKEN·UNLINKED를 구분해 돌려주려면 필요하다. */
    Optional<YoutubeChannelLink> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<YoutubeChannelLink> findByChannelIdAndRevokedAtIsNull(String channelId);

    /**
     * 살아있는 내 연동을 닫는다. 0행이면 닫을 것이 없었다 — 첫 연동·해제 뒤 재연동·연동 없는 DELETE.
     * 재연동(USER_UNLINKED)과 해제(USER_UNLINKED)가 같은 줄을 쓴다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update YoutubeChannelLink l set l.revokedAt = :now, l.revokeReason = :reason "
            + "where l.userId = :userId and l.revokedAt is null")
    int revokeAlive(@Param("userId") Long userId, @Param("now") Instant now, @Param("reason") RevokeReason reason);

    /**
     * 철회 점검 후보 — 「오래 확인 안 한 살아있는 행」. 치지직(만료 임박)과 축이 다르다:
     * 구글 access는 1시간이라 만료로 고르면 살아있는 행이 늘 전부 걸린다.
     * id만 뽑는다 — 갱신기가 락 뒤에 다시 읽는다(락 전 엔티티 읽기 금지).
     */
    @Query("select l.userId from YoutubeChannelLink l "
            + "where l.revokedAt is null and l.lastRefreshedAt < :threshold order by l.lastRefreshedAt")
    List<Long> findUserIdsNotRefreshedSince(@Param("threshold") Instant threshold);
}
