package com.pokeclip.auth.delegation;

/**
 * clip이 묻는 「이 사람과 이 스트리머는 무슨 사이인가」의 답. 내부 창구 둘이 같은 세 단어를 쓴다.
 *
 * <p>NONE은 「없는 회원 번호」·「해제된 편집자」·「초대만 받음」·「방향 반대」를 구분하지 않는다 —
 * 어느 쪽이든 clip이 할 일(안 보여줌)이 같다. 행동이 안 갈리면 이유를 줄 까닭이 없다(PRD 결정).
 */
public enum DelegationRelation {
    OWNER, EDITOR, NONE
}
