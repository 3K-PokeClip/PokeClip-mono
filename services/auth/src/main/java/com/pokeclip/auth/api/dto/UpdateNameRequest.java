package com.pokeclip.auth.api.dto;

/**
 * 회원 번호를 받지 않는다 — 토큰의 주인만 자기 것을 고친다(PRD). 모르는 필드는 Jackson이 버리므로
 * userId를 실어 보내도 아무 일이 없다.
 *
 * <p>@NotBlank를 걸지 않는다. 빈 값 판정을 서비스가 하고(트림 뒤에 재야 "   "도 걸린다) 사유를
 * NAME_BLANK로 내보내야 하는데, 빈 검증을 여기서 하면 화면이 이유를 못 읽는 400이 먼저 나간다.
 */
public record UpdateNameRequest(String name) {
}
