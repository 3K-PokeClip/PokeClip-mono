package com.pokeclip.auth.streamkey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    /**
     * 이 회원의 <b>키 전부(폐기된 것 포함)</b>가 가리키는 비밀값 자리. 탈퇴의 커밋 뒤 정리가 쓴다.
     *
     * <p>🔴 <b>살아있는 키 하나로는 부족하다</b>({@code findByUserIdAndRevokedAtIsNull}).
     * 「그 회원의 비밀값은 정확히 하나」는 <b>불변식이 아니다</b> — 재발급은 새 비밀값을 만들고
     * 직전 것을 <b>커밋 뒤에</b> 지우는데, 그 삭제가 한 번이라도 실패하면 폐기된 키가 자기 비밀값을
     * 계속 가리킨 채 남는다(주입으로 그 상태를 만들어 봤다: 다섯 개). 살아있는 것 하나만 지우면
     * 나머지를 남긴 채 204가 나간다.
     *
     * <p>부수 효과로 <b>순서 제약이 없어진다</b> — {@code revokeAlive} 앞에서 읽어 둘 필요가 없다.
     * 그 UPDATE는 {@code revoked_at}만 채우고 행과 {@code passphrase_ref}는 그대로 살기 때문이다.
     */
    @Query("select k.passphraseRef from StreamKey k where k.userId = :userId")
    List<String> passphraseRefsOfUser(@Param("userId") Long userId);
}
