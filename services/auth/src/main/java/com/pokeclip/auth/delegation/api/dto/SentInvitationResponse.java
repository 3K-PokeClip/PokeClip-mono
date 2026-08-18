package com.pokeclip.auth.delegation.api.dto;

import com.pokeclip.auth.delegation.EditorInvitation;
import com.pokeclip.auth.delegation.InvitationView;

import java.time.Instant;

/** 스트리머가 보는 초대 한 건. 상대(받는 사람)를 보여준다. */
public record SentInvitationResponse(
        Long id, Long inviteeId, String inviteeName, String inviteeEmail,
        InvitationView status, Instant expiresAt, Instant createdAt) {

    public static SentInvitationResponse of(EditorInvitation invitation, String name, String email, Instant now) {
        return new SentInvitationResponse(
                invitation.getId(), invitation.getInviteeId(), name, email,
                invitation.view(now), invitation.getExpiresAt(), invitation.getCreatedAt());
    }
}
