package com.pokeclip.chat.collector.broadcast.attach;

import com.pokeclip.chat.collector.broadcast.StreamerId;

/**
 * 줄 이름. <b>{@link StreamerId#parse}와 같은 정규화를 쓴다</b> — 그쪽이 {@code trim()}을
 * 하므로 여기서 안 하면 {@code " 7"}과 {@code "7"}이 같은 회원인데 다른 줄이 되고,
 * 그러면 같은 스트리머의 두 방송이 병렬로 수립돼 등록부의 갈아끼움 판정이 꼬인다.
 *
 * <p><b>숫자로 파싱하지 않는다.</b> 못 읽는 식별자도 줄에는 들어가야 하고(판정기가
 * 세어야 하는 값이다), 여기서 가르면 그 카운터가 층을 넘어온다.
 */
public final class LaneKey {
    private LaneKey() { }

    public static String of(String rawStreamerId) {
        return rawStreamerId == null ? "" : rawStreamerId.trim();
    }
}
