package com.pokeclip.core.auth.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByGoogleSub(String googleSub);

    /**
     * 사용자 행에 쓰기 락을 건다. 같은 사용자의 refresh 회전을 직렬화하는 용도다 —
     * 토큰 테이블이 아니라 사용자 행을 잠그는 이유는, 막아야 할 것이 "아직 존재하지
     * 않는 토큰 행"이라 잠글 대상이 없기 때문이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
