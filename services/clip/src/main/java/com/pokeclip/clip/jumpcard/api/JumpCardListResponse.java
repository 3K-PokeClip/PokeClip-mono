package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.jumpcard.JumpCardPage;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;

import java.util.List;

/**
 * 카드 목록 화면이 받는 모양. 2번(web)과의 계약이라 칸 이름을 바꾸지 않는다.
 *
 * <p>🔴 <b>줄을 다시 선언하지 않는다.</b> {@link JumpCardSnapshot}을 그대로 싣는 것이 이 문의
 * 완료 조건이다 — 「카드 한 장의 모양이 통로로 오는 것과 칸 하나까지 같다」(PRD). 여기에 별도
 * {@code Item}을 만들면 통로와 갈리고, 화면이 같은 것을 두 벌로 처리하게 된다.
 * {@code JumpCardListShapeTest}가 두 경로의 JSON 트리를 맞대어 그것을 지킨다.
 *
 * <p><b>{@code nextCursor}는 불투명하다</b> — 웹은 이 문자열을 풀어 보거나 만들지 않고, 받은 것을
 * 그대로 되돌려 넣기만 한다. 마지막 장이면 {@code null}이다.
 */
public record JumpCardListResponse(List<JumpCardSnapshot> cards, String nextCursor) {

    public static JumpCardListResponse from(JumpCardPage page) {
        return new JumpCardListResponse(page.cards(), page.nextCursor());
    }
}
