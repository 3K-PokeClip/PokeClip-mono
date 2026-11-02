package com.pokeclip.auth.delegation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EditorDelegationRepository extends JpaRepository<EditorDelegation, Long> {

    boolean existsByStreamerIdAndEditorIdAndRevokedAtIsNull(Long streamerId, Long editorId);

    List<EditorDelegation> findByStreamerIdAndRevokedAtIsNullOrderByGrantedAtDesc(Long streamerId);

    List<EditorDelegation> findByEditorIdAndRevokedAtIsNullOrderByGrantedAtDesc(Long editorId);

    Optional<EditorDelegation> findByIdAndRevokedAtIsNull(Long id);

    /**
     * 해제도 조건을 건 UPDATE 하나다. 양쪽이 동시에 눌러도 하나만 성공한다.
     *
     * <p><b>인가를 쿼리에 박아 둔다.</b> 호출부도 앞서 소유를 확인하지만, 그건 revoked_by를
     * STREAMER/EDITOR 중 무엇으로 쓸지 고르는 용도가 크다. 조건이 쿼리에 없으면 다음 사람이
     * 이 UPDATE를 다른 자리에서 재사용할 때 인가가 통째로 빠진다(authz-auditor 라운드 2).
     * 조회와 UPDATE 양쪽에 조건이 있는 것은 중복이 아니라 겹치는 방어다.
     *
     * <p>{@code revokedAt IS NULL}은 「누가 끊었나」를 덮어쓰지 못하게 막는다 — 쫓겨난 편집자가
     * 나중에 호출해 EDITOR로 바꾸면 이력이 거짓이 된다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE EditorDelegation d SET d.revokedAt = :now, d.revokedBy = :by "
            + "WHERE d.id = :id AND d.revokedAt IS NULL "
            + "AND (d.streamerId = :actorId OR d.editorId = :actorId)")
    int revoke(@Param("id") Long id, @Param("actorId") Long actorId,
               @Param("by") RevokedBy by, @Param("now") Instant now);

    /**
     * 이 회원이 낀 살아있는 위임을 <b>양쪽 방향 모두</b> 닫는다(탈퇴).
     *
     * <p>위 {@code revoke}를 못 쓰는 이유: 그것은 위임 하나를 id로 집고 「행위자가 당사자인가」를
     * 조건에 박는다. 탈퇴는 <b>대상이 여럿이고 행위자가 곧 당사자</b>라 모양이 다르다.
     *
     * <p>사유가 {@code WITHDRAWAL}인 이유(PRD D5): 「스트리머가 내보냄」·「편집자가 나감」으로 적으면
     * 상대 화면에서 사람이 한 행동으로 보인다. 계정이 사라진 것은 다른 사건이다.
     *
     * <p>{@code revokedAt IS NULL}은 위 {@code revoke}와 같은 것을 막는다 — 이미 끊긴 위임의
     * 「누가 끊었나」와 「언제 끊겼나」를 덮어쓰면 이력이 거짓이 된다(쫓겨난 편집자가 나중에 탈퇴해
     * 「내가 나갔다」로 고치는 경로).
     *
     * <p>🔴 <b>회원 조건이 한 줄에 두 갈래다.</b> 한 갈래만 재는 시험으로는 나머지를 지워도 초록이고,
     * 조건이 통째로 빠지면 <b>전 회원의 위임이 닫힌다</b>(탈퇴자 쪽 단언은 전부 초록이라 조용하다).
     * {@code WithdrawalDelegationTest}가 스트리머 갈래·편집자 갈래·남의 관계를 각각 따로 잰다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE EditorDelegation d SET d.revokedAt = :now, "
            + "d.revokedBy = com.pokeclip.auth.delegation.RevokedBy.WITHDRAWAL "
            + "WHERE d.revokedAt IS NULL AND (d.streamerId = :userId OR d.editorId = :userId)")
    int revokeAllOfUser(@Param("userId") Long userId, @Param("now") Instant now);
}
