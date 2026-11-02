package com.pokeclip.auth.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByGoogleSub(String googleSub);

    /**
     * 호출부가 소문자로 넘긴다. 저장도 소문자로 통일돼 있다(UserCreator).
     *
     * <p>🔴 <b>탈퇴한 회원은 이 조회로 안 나온다</b>(PR #148, 전수 세기에서 찾음). 부르는 자리가
     * 초대 하나뿐이고 거기서 필요한 것이 「이 주소로 <b>초대할 수 있는</b> 계정」이라, 조건을 쿼리에
     * 두는 쪽이 호출부마다 판정을 복붙하는 것보다 낫다.
     *
     * <p>탈퇴하면 이메일이 {@code withdrawn+<번호>@invalid}로 바뀌므로 <b>보통은 아무도 그 주소를
     * 모른다.</b> 그래도 막는 이유는 그 주소가 회원 번호로 만들어져 <b>추측할 수 있기</b> 때문이다 —
     * 넣으면 영영 수락 못 할 초대가 남의 초대함에 생기고 살아있는 자리 하나를 차지한다.
     *
     * <p>이름을 바꾼 것은 일부러다. {@code findByEmail}로 두면 다음 사람이 「이메일로 찾는다」로 읽고
     * 탈퇴 여부를 안 본다 — <b>이름이 조건을 말하게 한다.</b>
     */
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findAliveByEmail(@Param("email") String email);

    /**
     * 사용자 행에 쓰기 락을 건다. 같은 사용자의 refresh 회전을 직렬화하는 용도다 —
     * 토큰 테이블이 아니라 사용자 행을 잠그는 이유는, 막아야 할 것이 "아직 존재하지
     * 않는 토큰 행"이라 잠글 대상이 없기 때문이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * 탈퇴 여부만 읽는다. 엔티티로 읽지 않는 이유는 이 조회가 <b>인증이 필요한 모든 요청</b>에
     * 붙기 때문이다 — 영속성 컨텍스트에 회원을 올리면 뒤따르는 코드가 그 캐시를 잡는다.
     *
     * <p>없는 회원이면 빈손이다. 필터는 그것을 「막지 않음」으로 다룬다 —
     * 「토큰의 주인이 없다」는 이미 각 창구가 자기 사유로 다룬다(DataInconsistencyException).
     *
     * <p><b>{@code Optional<Instant>}는 행이 없을 때와 값이 null일 때가 둘 다 빈손이다</b> —
     * 예외를 던지지 않는다. 필터가 둘을 같게 다루므로 그 성질이 여기서는 맞다.
     */
    @Query("SELECT u.deletedAt FROM User u WHERE u.id = :id")
    Optional<Instant> findDeletedAtById(@Param("id") Long id);
}
