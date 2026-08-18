package com.pokeclip.auth.delegation.api.dto;

import java.time.Instant;

/**
 * 위임 한 건. 양쪽이 같은 모양을 쓴다 — 스트리머가 보면 상대가 편집자고,
 * 편집자가 보면 상대가 스트리머다.
 *
 * <p>이메일은 주지 않는다. 목록에서 사람을 가려내는 데 이름이면 충분하고,
 * 연락처를 굳이 양쪽에 뿌릴 이유가 없다.
 */
public record DelegationResponse(
        Long id, Long counterpartId, String counterpartName, Instant grantedAt) {
}
