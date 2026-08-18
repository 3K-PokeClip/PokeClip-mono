package com.pokeclip.auth.delegation.api.dto;

import com.pokeclip.auth.delegation.EditorInvitation;

import java.time.Instant;

/**
 * 편집자가 보는 초대 한 건. 상대(보낸 스트리머)를 보여준다.
 *
 * <p>스트리머의 이메일은 주지 않는다 — 초대를 받았다는 것이 상대 연락처를 알 근거는 아니다.
 * 목록에는 응답 가능한 것만 담기므로 status 필드도 없다.
 */
public record ReceivedInvitationResponse(
        Long id, Long streamerId, String streamerName, Instant expiresAt, Instant createdAt) {

    public static ReceivedInvitationResponse of(EditorInvitation invitation, String streamerName) {
        return new ReceivedInvitationResponse(
                invitation.getId(), invitation.getStreamerId(), streamerName,
                invitation.getExpiresAt(), invitation.getCreatedAt());
    }
}
