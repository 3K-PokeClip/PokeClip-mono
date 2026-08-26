package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.delegation.ResolveResult;

import java.util.List;
import java.util.Map;

/**
 * 방송 목록 한 장. 줄들과 <b>줄마다의 관계</b>, 그리고 다음 장 표시.
 *
 * <p>관계를 줄에 안 담고 옆에 맵으로 두는 이유 — 관계는 <b>방송이 아니라 스트리머</b>의
 * 속성이라 같은 스트리머의 방송 열 줄이 같은 값을 갖는다. 줄마다 담으면 그 사실이 흐려지고,
 * 나중에 관계가 하나 더 생길 때 줄을 다 고쳐야 한다.
 *
 * <p>키가 {@code String}인 것은 {@code broadcasts.streamer_id}가 {@code VARCHAR}이기 때문이다 —
 * 조회 조건과 같은 모양이라야 여기서 못 찾는 줄이 안 생긴다.
 */
public record BroadcastPage(List<Broadcast> rows, Map<String, ResolveResult> relations, String nextCursor) {

    private static final BroadcastPage EMPTY = new BroadcastPage(List.of(), Map.of(), null);

    public static BroadcastPage empty() {
        return EMPTY;
    }
}
