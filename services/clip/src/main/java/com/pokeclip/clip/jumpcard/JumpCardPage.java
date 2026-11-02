package com.pokeclip.clip.jumpcard;

import java.util.List;

/**
 * 카드 목록 한 장. 줄들과 다음 장 표시.
 *
 * <p><b>줄이 {@link JumpCardSnapshot} 그대로다</b> — 통로가 밀어 주는 것과 같은 모양이라야 화면이
 * 한 벌로 처리한다(PRD 결정). 여기서 다시 선언하면 두 경로가 조용히 갈리고, 갈린 것을 화면이
 * 먼저 알게 된다.
 *
 * <p>방송 목록의 {@code BroadcastPage}와 달리 <b>관계 맵이 없다</b>. 이 문은 방송 하나를 받아
 * 그 방송의 카드만 주므로 줄마다 다를 관계가 없다 — 관계는 이미 판정기가 보고 통과시켰다.
 */
public record JumpCardPage(List<JumpCardSnapshot> cards, String nextCursor) {
}
