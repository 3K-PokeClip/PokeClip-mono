package com.pokeclip.auth.streamername;

/**
 * 한 스트리머의 플랫폼별 채널 이름. 살아 있는 연동이 없으면 그 칸은 null이다(POK-430).
 *
 * <p>치지직은 연동 때 받은 채널 이름, SOOP은 연동 때 받은 닉네임이다. 둘 다 연동 뒤 바뀐 이름을 따라가지 않는다.
 */
public record StreamerChannelNames(String chzzk, String soop) {

    public static final StreamerChannelNames NONE = new StreamerChannelNames(null, null);
}
