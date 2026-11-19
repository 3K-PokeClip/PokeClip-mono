package com.pokeclip.auth.streamkey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
     * 교환이 돌려준 <b>그 키</b>에 「연동 코드가 쓰였다」를 적는다(POK-607).
     *
     * <p>🔴 키를 회원이 아니라 streamid 해시로 고른다. 교환이 옛 키를 읽은 뒤 재발급(rotate)이 커밋되면
     * 회원의 살아있는 키는 플러그인이 받지 못한 새 키다 — 회원으로 고르면 그 새 키에 「연결했다」가 찍혀,
     * 송출할 수 없는 회원에게 웹이 연결됐다고 말한다. 폐기됐으면 0행이다(재발급·탈퇴와 겹쳤다).
     * 교환은 이미 성공했고 이 시각은 화면 안내용이라 호출부는 0행을 실패로 다루지 않는다.
     *
     * <p>🔴 <b>자기 트랜잭션을 연다</b> — 교환이 커밋된 뒤에 부르기 때문이다({@code PairingCodeService.exchange}).
     * 교환 안에서 부르면 코드 행(markUsed)을 쥔 채 키 행을 기다리는데, 탈퇴는 키 행(revokeAlive)을 쥔 채
     * 코드 행(consumeAliveOfUser)을 기다려 둘이 엇갈린다(deadlock). 커밋 뒤 호출에서는 바깥 트랜잭션에
     * 참여하지 않도록 REQUIRES_NEW여야 한다(스프링 TransactionSynchronization#afterCommit 계약).
     *
     * <p>🔴 <b>앞으로만 간다</b> — {@code now}는 교환 요청이 들어온 시각이고 커밋 뒤 기록은 순서가 뒤집힐 수 있다.
     * 같은 키로 거의 동시에 교환 둘이 들어와 늦게 시작한 쪽이 먼저 적으면, 조건 없이 덮을 때 앞선 시각이 남아
     * 웹이 지켜보던 코드의 발급 시각보다 앞서 연결을 놓친다(PR #539 리뷰). 더 늦은 값일 때만 바꾼다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StreamKey k set k.lastPairedAt = :now "
            + "where k.streamidHash = :streamidHash and k.revokedAt is null "
            + "and (k.lastPairedAt is null or k.lastPairedAt < :now)")
    int markPaired(@Param("streamidHash") String streamidHash, @Param("now") Instant now);

    /**
     * Media가 송출을 받으려 이 키를 확인(resolve)했을 때, 연결 시각이 비어 있으면 채운다(POK-607).
     * V120 전에 코드를 넣은 회원을 다음 방송에서 저절로 「연결함」으로 올린다. 이미 있으면 0행이다 —
     * 이 칸은 「코드가 쓰인 시각」이라 송출할 때마다 덮지 않는다. resolve는 트랜잭션이 없어 여기서 연다.
     * 채우는 값은 호출부가 정한다(키를 만든 시각 — {@code StreamKeyService.resolve}).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StreamKey k set k.lastPairedAt = :at "
            + "where k.id = :id and k.revokedAt is null and k.lastPairedAt is null")
    int markPairedIfUnset(@Param("id") Long id, @Param("at") Instant at);

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
