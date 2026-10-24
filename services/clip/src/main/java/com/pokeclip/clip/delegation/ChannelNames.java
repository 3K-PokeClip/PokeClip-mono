package com.pokeclip.clip.delegation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 한 스트리머의 플랫폼별 채널 이름(POK-430). auth에 저장된 <b>지금 연동된</b> 이름이다: 치지직은 채널 이름, SOOP은 닉네임.
 * 연동이 없거나 auth에 못 물었으면 그 칸이 {@code null}이다.
 *
 * <p>JSON 키는 플랫폼 코드({@code CHZZK} · {@code SOOP}, 방송 목록의 {@code platform} 칸과 같은 글자)라 화면이 대표 플랫폼 코드로
 * 바로 고른다. 키는 값이 null이어도 늘 둘 다 나간다. {@code Map.of}로 만들지 않는 이유: null 값을 못 넣어 목록 전체가 500이 된다.
 */
public record ChannelNames(@JsonProperty("CHZZK") String chzzk, @JsonProperty("SOOP") String soop) {

    /** 모름. 연동이 없거나 auth에 못 물었다. */
    public static final ChannelNames UNKNOWN = new ChannelNames(null, null);
}
