package com.pokeclip.auth.delegation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 위임 한 건. 편집자가 그 스트리머에 대해 전부 할 수 있다는 사실 자체다.
 *
 * <p>행을 지우지 않는다. 끊긴 시각과 누가 끊었는지를 남긴다(auth/CLAUDE.md).
 */
@Entity
@Table(name = "editor_delegations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EditorDelegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "streamer_id", nullable = false)
    private Long streamerId;

    @Column(name = "editor_id", nullable = false)
    private Long editorId;

    @Column(name = "invitation_id", nullable = false)
    private Long invitationId;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_by", length = 16)
    private RevokedBy revokedBy;

    public static EditorDelegation of(Long streamerId, Long editorId, Long invitationId, Instant now) {
        EditorDelegation d = new EditorDelegation();
        d.streamerId = streamerId;
        d.editorId = editorId;
        d.invitationId = invitationId;
        d.grantedAt = now;
        return d;
    }
}
