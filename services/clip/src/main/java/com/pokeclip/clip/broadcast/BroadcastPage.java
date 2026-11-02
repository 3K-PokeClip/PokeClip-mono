package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.collector.BroadcastTitle;
import com.pokeclip.clip.delegation.ChannelNames;
import com.pokeclip.clip.delegation.ResolveResult;

import java.time.Instant;
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
 *
 * <p>{@code editorsOnline}은 방송 번호별 「대시보드를 연 편집자 수」다(POK-398). 방송 중 줄만 들어 있고, 끝난 줄은 키가 없다(응답에서 null).
 *
 * <p>{@code titles}는 고른 제목이다(켜진 플랫폼 중 시청자 많은 쪽, POK-397 제목 규칙은 {@code BroadcastListService}). {@code platforms}는 방송 번호별
 * 켜진 플랫폼과 시청자 수이고 한 장의 모든 줄에 키가 있다(편지 플랫폼이 늘 들어간다).
 *
 * <p>{@code channelNames}는 스트리머 번호별 플랫폼 채널 이름이다(POK-430). 관계와 같은 이유(스트리머의 속성)로 줄이 아니라 스트리머 번호를
 * 키로 둔다. auth에 못 물었으면 비어 있고, 응답은 그때 두 칸 null로 싣는다.
 */
public record BroadcastPage(List<Broadcast> rows, Map<String, ResolveResult> relations, String nextCursor,
                            Map<String, Instant> origins, Map<String, String> thumbnails,
                            Map<String, BroadcastTitle> titles, Map<String, List<PlatformViewers>> platforms,
                            Map<String, String> leadPlatforms, Map<String, Integer> editorsOnline,
                            Map<String, ChannelNames> channelNames) {

    private static final BroadcastPage EMPTY =
            new BroadcastPage(List.of(), Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of());

    public static BroadcastPage empty() {
        return EMPTY;
    }
}
