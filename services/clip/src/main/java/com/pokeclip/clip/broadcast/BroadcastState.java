package com.pokeclip.clip.broadcast;

import java.util.List;

/**
 * 화면이 보는 두 덩어리. 표의 상태는 셋인데 화면은 「방송 중」과 「지난 방송」 둘로 나뉜다.
 *
 * <p><b>한 목록에 섞지 않는 이유</b>(PRD 결정) — 섞으면 오래 켜둔 방송이 첫 장 밖으로 밀려
 * 방송 중인데 라이브 띠가 안 뜬다.
 */
public enum BroadcastState {

    LIVE(List.of("live")),
    /** {@code vod_ready}는 아직 쓰이지 않지만 표 제약에 있는 값이라 여기 넣는다. */
    PAST(List.of("ended", "vod_ready"));

    private final List<String> dbValues;

    BroadcastState(List<String> dbValues) {
        this.dbValues = dbValues;
    }

    public List<String> dbValues() {
        return dbValues;
    }
}
