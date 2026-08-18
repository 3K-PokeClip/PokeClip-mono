package com.pokeclip.auth.delegation.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 320은 users.email의 길이와 같다. 더 긴 값은 어차피 저장된 계정과 못 맞는다.
 *
 * <p><b>여기 걸린 값은 Spring이 WARN 로그에 그대로 찍는다</b>
 * ({@code DefaultHandlerExceptionResolver}, 실측). 그런데도 두는 이유는,
 * 남는 것이 <b>가입될 수 없는 값뿐</b>이기 때문이다 — 형식이 틀렸거나 320자를 넘는 주소다.
 * (앞서 「형식이 틀린 값만」이라고 적었는데 반만 맞았다. 형식은 유효하고 322자인 주소도
 * 여기 걸려 전체가 실린다 — authz-auditor 라운드 1.)
 * 열거는 유효하고 등록 가능한 주소로 하는데 그런 값은 검증을 통과해 이 경로에 안 온다.
 * 그래서 걱정한 「가입 여부 열거 흔적」은 쌓이지 않는다.
 */
public record InviteRequest(@NotBlank @Email @Size(max = 320) String email) {
}
