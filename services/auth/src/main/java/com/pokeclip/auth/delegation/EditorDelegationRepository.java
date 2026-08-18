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

    /** 해제도 조건을 건 UPDATE 하나다. 양쪽이 동시에 눌러도 하나만 성공한다. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE EditorDelegation d SET d.revokedAt = :now, d.revokedBy = :by "
            + "WHERE d.id = :id AND d.revokedAt IS NULL")
    int revoke(@Param("id") Long id, @Param("by") RevokedBy by, @Param("now") Instant now);
}
