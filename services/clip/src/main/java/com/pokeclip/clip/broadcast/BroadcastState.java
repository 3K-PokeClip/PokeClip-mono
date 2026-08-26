package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.paging.InvalidListParamException;

import java.util.List;

/**
 * 화면이 보는 두 덩어리. 표의 상태는 셋인데 화면은 「방송 중」과 「지난 방송」 둘로 나뉜다.
 *
 * <p><b>한 목록에 섞지 않는 이유</b>(PRD 결정) — 섞으면 오래 켜둔 방송이 첫 장 밖으로 밀려
 * 방송 중인데 라이브 띠가 안 뜬다.
 */
public enum BroadcastState {

    LIVE("live", List.of("live")),
    /** {@code vod_ready}는 아직 쓰이지 않지만 표 제약에 있는 값이라 여기 넣는다. */
    PAST("past", List.of("ended", "vod_ready"));

    private final String param;
    private final List<String> dbValues;

    BroadcastState(String param, List<String> dbValues) {
        this.param = param;
        this.dbValues = dbValues;
    }

    public List<String> dbValues() {
        return dbValues;
    }

    /**
     * 🔴 <b>계약이 소문자라 직접 옮긴다.</b> {@code @RequestParam}에 열거형을 그대로 받으면
     * 스프링 기본 변환기가 대소문자를 가려 <b>{@code state=live}가 400</b>이 된다
     * (계획 검증 실측: {@code MethodArgumentTypeMismatchException}). PRD·README·2번에게 줄
     * 계약이 전부 소문자다.
     *
     * <p>이름을 {@code toLowerCase()}로 만들지 않고 칸을 따로 두는 이유 — {@code PAST}의
     * 바깥 이름 {@code "past"}는 표의 값 어디에도 없다. 두 세계의 이름이 우연히 겹칠 때만
     * 파생이 되는데, 여기는 절반이 안 겹친다.
     *
     * @throws InvalidListParamException 모르는 값이다 (400, {@code field=state})
     */
    public static BroadcastState fromParam(String value) {
        for (BroadcastState state : values()) {
            if (state.param.equals(value)) {
                return state;
            }
        }
        throw new InvalidListParamException("state");
    }
}
