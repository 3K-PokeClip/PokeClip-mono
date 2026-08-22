package com.pokeclip.auth.delegation.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * clip이 보낸다. 둘 다 <b>숫자 회원 번호</b>(users.id)다.
 *
 * <p>{@code streamerId}가 아니라 {@code streamerUserId}인 이유: {@code streamerId}는 Media→clip
 * 편지에서 <b>문자열</b>(broadcasts.streamer_id VARCHAR)로 이미 쓰인다. 이름이 같으면 clip이
 * 그 값을 그대로 넣는다 — POK-127이 겪는 타입 불일치가 여기로 번진다.
 */
public record DelegationResolveRequest(@NotNull Long userId, @NotNull Long streamerUserId) {
}
