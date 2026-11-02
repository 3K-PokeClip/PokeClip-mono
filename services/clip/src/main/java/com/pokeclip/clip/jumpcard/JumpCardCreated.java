package com.pokeclip.clip.jumpcard;

/**
 * 카드가 새로 생겼다(POK-475). 커밋 뒤에 받는다({@code @TransactionalEventListener}): 되감긴 카드의 사진을 주문하지 않게.
 * 받는 쪽은 썸네일 순회기 하나다(카드 시점 사진을 1분 순회보다 먼저 주문한다). 같은 창 재전송 · 20초 안 앞 카드처럼 새로 안 만든
 * 경우에는 안 낸다.
 */
public record JumpCardCreated(long cardId) {
}
