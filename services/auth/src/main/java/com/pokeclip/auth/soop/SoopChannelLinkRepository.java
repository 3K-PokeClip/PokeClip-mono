package com.pokeclip.auth.soop;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SoopChannelLinkRepository extends JpaRepository<SoopChannelLink, Long> {

    Optional<SoopChannelLink> findByUserIdAndRevokedAtIsNull(Long userId);

    /** 닫힌 행도 찾힌다. GET·resolve가 BROKEN·UNLINKED를 구분해 돌려주려면 필요하다. */
    Optional<SoopChannelLink> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<SoopChannelLink> findBySoopUserIdAndRevokedAtIsNull(String soopUserId);

    /**
     * 살아 있는 내 연동을 닫고 SOOP 아이디·닉네임을 같은 문장에서 비운다(약관 제11조 ⑰). 0행이면 닫을 것이 없었다.
     * 재연동·해제·갱신 거절·탈퇴가 모두 이 줄을 쓴다.
     *
     * <p>한 문장이어야 하는 이유 둘: ① 비우기만 먼저 나가면 V116 CHECK(살아 있는 행은 두 칸 필수)에 걸린다
     * ② 엔티티를 고쳐 닫으면 IDENTITY 키의 재연동 INSERT가 그 변경보다 먼저 나가 유니크에 걸리고, 탈퇴 뒤의
     * clearAutomatically 쿼리가 flush 전 변경을 지운다(계획 검증 C1). 그래서 {@code flushAutomatically}도 켠다.
     * ref 둘은 행에 남지만 호출자는 커밋 뒤 정리용으로 이 UPDATE <b>전에</b> 읽어 둔다(clear 뒤엔 엔티티가 떨어진다).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SoopChannelLink l set l.revokedAt = :now, l.revokeReason = :reason, "
            + "l.soopUserId = null, l.nickname = null "
            + "where l.userId = :userId and l.revokedAt is null")
    int closeAndPurgeAlive(@Param("userId") Long userId, @Param("now") Instant now,
                           @Param("reason") RevokeReason reason);

    /**
     * 스케줄러 후보. 만료 임박이거나 마지막 확인이 오래된 살아 있는 행의 회원 번호만 뽑는다(갱신기가 락 뒤에 다시 읽는다).
     * 마지막 확인이 오래된 것부터라 상한으로 잘려도 오래 안 본 회원이 먼저 온다.
     */
    @Query("select l.userId from SoopChannelLink l "
            + "where l.revokedAt is null and (l.accessExpiresAt < :expiringBefore or l.lastRefreshedAt < :checkedBefore) "
            + "order by l.lastRefreshedAt")
    List<Long> findIdsExpiringOrStale(@Param("expiringBefore") Instant expiringBefore,
                                      @Param("checkedBefore") Instant checkedBefore, Pageable pageable);
}
