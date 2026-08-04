package com.pokeclip.auth.streamkey.pairing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PairingCodeRepository extends JpaRepository<PairingCode, Long> {

    Optional<PairingCode> findByCodeHash(String codeHash);

    long countByUserIdAndCreatedAtAfter(Long userId, Instant since);

    /**
     * 코드를 소비한다. <b>"동시에 두 번 교환해도 한 번만 성공한다"가 이 한
     * 문장으로 끝난다</b> — 두 요청이 동시에 와도 PostgreSQL이 행 잠금으로
     * 직렬화하고, 진 쪽은 0행을 받는다. 애플리케이션 락이 필요 없다.
     *
     * <p>0행일 때의 사유(없음·만료·이미 사용)는 호출부가 뒤이어 조회해 나눈다.
     * 그 시점엔 경합이 이미 끝나 있어 안전하다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PairingCode c set c.usedAt = :now "
            + "where c.codeHash = :codeHash and c.usedAt is null and c.expiresAt > :now")
    int markUsed(@Param("codeHash") String codeHash, @Param("now") Instant now);
}
