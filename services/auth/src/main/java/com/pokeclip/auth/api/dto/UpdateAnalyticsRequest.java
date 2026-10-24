package com.pokeclip.auth.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 설정 › 계정의 「이용 분석 참여」 토글 — {@code optOut=true}면 끈다. 회원 번호를 받지 않는다(토큰의 주인만 자기 것을 고친다).
 *
 * <p>{@code Boolean}에 {@code @NotNull}을 건다 — 원시 {@code boolean}이면 빠진 필드가 조용히 {@code false}(=참여)로
 * 읽혀, 끄려던 요청이 켜는 요청이 될 수 있다.
 */
public record UpdateAnalyticsRequest(@NotNull Boolean optOut) {
}
