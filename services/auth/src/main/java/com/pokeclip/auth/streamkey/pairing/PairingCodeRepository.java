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

    /**
     * 이 회원의 살아있는 페어링 코드를 전부 소비 처리한다(탈퇴).
     *
     * <p>🔴 <b>지우지 않고 쓴 것으로 표시한다</b> — 지우면 교환 시도가 「모르는 코드」가 되고,
     * 남기면 「이미 쓴 코드」가 된다. 둘 다 거절이지만 뒤엣것이 사실에 가깝고 rate limit 기록과도 맞는다.
     *
     * <p>만료된 코드는 건드리지 않는다 — 이미 못 쓴다. 「방금 썼다」로 덮으면 그 시각이 거짓이 되고,
     * 사고 조사에서 탈퇴 시각과 교환 시각이 붙어 보인다.
     *
     * <p>🔴 <b>이것이 없으면 탈퇴가 스트림키를 폐기해도 자격이 안 회수된다</b> — 교환 경로는
     * 로그인이 없어(코드 자체가 자격증명이다) 전면 차단 필터가 못 막고,
     * {@code StreamKeyService.ensureKey}는 살아있는 키가 없으면 <b>새로 만든다.</b>
     * 즉 살아있는 코드 하나가 탈퇴자 명의의 새 송출 자격이 된다.
     * {@code WithdrawalStreamKeyTest.탈퇴_뒤_살아있던_코드로_교환하면_거절되고_새_키도_안_생긴다}가 그 자리다.
     *
     * <p>{@code clearAutomatically}가 영속성 컨텍스트를 비우므로 <b>이 호출 앞에서 락으로 잡아 둔
     * 엔티티는 떨어져 나간다</b>({@code WithdrawalService.withdraw}의 재조회가 그것을 받는다).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PairingCode c set c.usedAt = :now "
            + "where c.userId = :userId and c.usedAt is null and c.expiresAt > :now")
    int consumeAliveOfUser(@Param("userId") Long userId, @Param("now") Instant now);
}
