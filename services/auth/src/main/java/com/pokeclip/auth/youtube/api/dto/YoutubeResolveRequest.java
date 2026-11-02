package com.pokeclip.auth.youtube.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * clip·워커가 회원 번호를 보낸다. {@code rejectedTokenSha256}(POK-250, 없어도 됨)은 업로드 일꾼이 유튜브에서 401을 받은 access의
 * SHA-256(소문자 16진 64자)이다. 일꾼이 만드는 값이라 모양이 틀리면 우리 버그다: {@code userId} 누락과 같은 400으로 보낸다.
 */
public record YoutubeResolveRequest(@NotNull Long userId, @Pattern(regexp = "[0-9a-f]{64}") String rejectedTokenSha256) {
}
