package com.pokeclip.auth.streamername.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * clip이 보낸다. 방송 목록 한 장에 나온 스트리머 번호(users.id).
 *
 * <p>상한 100은 수집기 broadcast-info/latest와 같은 수다. clip 목록 한 장이 최대 100줄이라 서로 다른 스트리머도
 * 100을 넘지 않는다.
 */
public record ChannelNamesRequest(@NotNull @Size(max = 100) List<@NotNull Long> streamerUserIds) {
}
