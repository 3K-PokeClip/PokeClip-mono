package com.pokeclip.core.auth.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 사용자 id만 스칼라로 뽑는다. 엔티티로 읽으면 안 된다 — 영속성 컨텍스트에
     * 들어가서, 뒤에 users 행 락을 잡고 토큰을 다시 읽어도 1차 캐시가 락을 잡기
     * 전의 옛 상태를 돌려준다. 그러면 락이 있으나 마나다.
     */
    @Query("SELECT t.userId FROM RefreshToken t WHERE t.tokenHash = :hash")
    Optional<Long> findUserIdByTokenHash(@Param("hash") String hash);

    /**
     * 아직 살아있는 토큰만 무효화한다. 영향 행이 0이면 다른 요청이 이미
     * 썼다는 뜻이다 — 동시에 같은 refresh가 두 번 오면 하나만 통과한다.
     */
    @Modifying
    @Query("""
           UPDATE RefreshToken t SET t.revokedAt = :now
           WHERE t.id = :id AND t.revokedAt IS NULL
           """)
    int revokeIfAlive(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("""
           UPDATE RefreshToken t SET t.revokedAt = :now
           WHERE t.userId = :userId AND t.revokedAt IS NULL
           """)
    int revokeAllOfUser(@Param("userId") Long userId, @Param("now") Instant now);
}
