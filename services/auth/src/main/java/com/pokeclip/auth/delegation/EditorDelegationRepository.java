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
}
