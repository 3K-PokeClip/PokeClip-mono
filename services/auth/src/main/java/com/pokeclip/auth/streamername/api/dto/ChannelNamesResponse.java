package com.pokeclip.auth.streamername.api.dto;

import com.pokeclip.auth.streamername.StreamerChannelNames;

import java.util.Map;

/**
 * 요청한 번호마다 한 항목. 연동이 없는 번호도 빼지 않고 두 칸 null로 싣는다: 받는 쪽은 「키가 없다」와
 * 「이름이 없다」를 가를 일이 없다.
 *
 * <p>accessible 창구(AccessibleStreamersResponse)는 「서버용 창구에 화면용 정보를 싣지 않는다」로 번호와 관계만
 * 준다. 이름은 그 창구에 덧붙이지 않고 이 창구를 따로 열었다: 자격 판정을 부르는 자리(문 여섯)마다 이름이 딸려
 * 가지 않고, 이름이 필요한 목록 한 곳만 부른다(POK-430).
 */
public record ChannelNamesResponse(Map<Long, StreamerChannelNames> names) {
}
