package com.pokeclip.clip.broadcast;

import java.util.List;

/**
 * 방송 중 목록 한 장. <b>이어받기가 없으므로 다음 장 표시가 없고, 대신 잘렸는지가 있다</b>
 * ({@link BroadcastPage}와 갈리는 자리가 정확히 여기다).
 *
 * <p>전량을 주는 창구인데 표시를 싣는 이유 — 잘린 것을 안 알리면 받는 쪽(수집기)은
 * <b>「방송 중인 것은 이게 전부」로 읽는다</b>. 잘린 방송은 영영 안 걷히고, 그 사실이
 * 아무 데도 안 남는다.
 */
public record LiveBroadcastPage(List<LiveBroadcastRow> rows, boolean truncated) {
}
