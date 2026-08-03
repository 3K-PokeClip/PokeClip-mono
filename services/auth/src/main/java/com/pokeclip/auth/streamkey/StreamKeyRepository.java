package com.pokeclip.auth.streamkey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface StreamKeyRepository extends JpaRepository<StreamKey, Long> {

    /** 폐기된 것도 찾힌다. resolve가 REVOKED를 구분해 돌려주려면 필요하다. */
    Optional<StreamKey> findByStreamidHash(String streamidHash);

    Optional<StreamKey> findByUserIdAndRevokedAtIsNull(Long userId);

    /**
     * 살아있는 키를 폐기한다. 0행이면 키가 없거나 동시 재발급에 진 것이다 —
     * 둘 다 "내가 폐기한 키는 없다"가 참이라 호출부에서 같게 다룬다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StreamKey k set k.revokedAt = :now "
            + "where k.userId = :userId and k.revokedAt is null")
    int revokeAlive(@Param("userId") Long userId, @Param("now") Instant now);
}
