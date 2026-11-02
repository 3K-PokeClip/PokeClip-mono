package com.pokeclip.clip.delegation;

/**
 * 「이 회원이 이 스트리머와 무슨 사이인가」에 대한 auth의 답.
 *
 * <p>앞 셋은 auth의 {@code DelegationRelation}과 이름이 같다(계약이다). 마지막 하나는
 * <b>auth가 답을 안 준 것</b>이고 auth 쪽에는 없는 값이다 — 「권한이 없다」와 「모른다」를
 * 합치면 auth가 아픈 동안 주인이 자기 방송을 못 여는 것을 404로 오해한다.
 */
public enum ResolveResult {
    OWNER,
    EDITOR,
    NONE,
    /** 못 닿음·오류 응답·읽을 수 없는 답. 문은 안 연다(fail-closed). */
    UNAVAILABLE
}
