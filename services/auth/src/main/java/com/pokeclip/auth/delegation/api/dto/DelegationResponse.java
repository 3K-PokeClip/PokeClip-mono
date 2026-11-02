package com.pokeclip.auth.delegation.api.dto;

import java.time.Instant;

/**
 * 위임 한 건. 양쪽이 같은 모양을 쓴다 — 스트리머가 보면 상대가 편집자고,
 * 편집자가 보면 상대가 스트리머다.
 *
 * <p>이메일은 주지 않는다. 목록에서 사람을 가려내는 데 이름이면 충분하고,
 * 연락처를 굳이 양쪽에 뿌릴 이유가 없다.
 *
 * <p>사진 주소는 회원 정보(me)의 {@code profileImageUrl}과 같은 규칙이다 — 올린 사진이면 우리 주소,
 * 아니면 구글 주소, 둘 다 없으면 null(화면은 이니셜). 짓는 법은 {@code PhotoUrls}에만 있고 여기는 싣기만 한다.
 */
public record DelegationResponse(
        Long id, Long counterpartId, String counterpartName, String counterpartProfileImageUrl,
        Instant grantedAt) {
}
