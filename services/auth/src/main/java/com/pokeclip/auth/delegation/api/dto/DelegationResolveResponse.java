package com.pokeclip.auth.delegation.api.dto;

import com.pokeclip.auth.delegation.DelegationRelation;

/** 항상 200. 판정은 relation 하나로 끝난다 — 비밀이 안 실려 NON_NULL도 toString 방어도 필요 없다. */
public record DelegationResolveResponse(DelegationRelation relation) {
}
