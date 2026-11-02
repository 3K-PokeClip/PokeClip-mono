package com.pokeclip.chat.collector.query;

import java.util.List;

/**
 * 목록 한 장.
 *
 * @param nextCursor      다음 장이 없으면 {@code null}이다. <b>빈 문자열로 접지 마라</b> —
 *                        「끝났다」와 「첫 장부터 다시」가 같아진다
 * @param appliedOffsetMs 이 답에 실제로 쓴 보정값. 판정과 무관하게 늘 실린다 —
 *                        프론트가 화면 위치를 되돌리는 유일한 재료다
 */
public record ChatWindowPage(List<ChatWindowItem> items, String nextCursor, long appliedOffsetMs) {
}
