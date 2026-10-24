package com.pokeclip.clip.collector;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 방송 목록 한 장에 대해 수집기가 준 방송 정보(POK-259 · POK-397). {@link BroadcastTitleClient}가 한 번의 왕복으로 받는다.
 *
 * @param titles    방송 번호 → 플랫폼과 무관한 마지막 제목(옛 칸). 제목이 없는 방송은 키가 없다
 * @param platforms     방송 번호 → 플랫폼마다 최신 값. 옛 수집기는 이 칸을 안 주므로 그때는 비어 있다
 * @param platformAware 응답에 {@code platforms} 칸이 있었다(새 수집기). 옛 수집기·못 물음이면 거짓이고, 그때 제목은 옛 칸을 쓴다
 */
public record BroadcastLatest(Map<String, BroadcastTitle> titles, Map<String, List<PlatformLatest>> platforms,
                              boolean platformAware) {

    public static final BroadcastLatest EMPTY = new BroadcastLatest(Map.of(), Map.of(), false);

    /**
     * 한 방송의 한 플랫폼 최신 값. {@code viewers}·{@code observedAt}은 수집기의 최신 줄, {@code title}·{@code category}는 그 플랫폼의
     * 제목 있는 최신 줄이다(수집기 {@code BroadcastInfoStore.PlatformLatest}). 못 읽은 칸은 {@code null}이다.
     */
    public record PlatformLatest(String platform, String title, String category, Integer viewers, Instant observedAt) {
    }
}
