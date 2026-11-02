package com.pokeclip.auth.soop;

/**
 * validate/live/status의 user_id + user/stationinfo의 user_nick. {@code {}}에 통째로 넣지 않는다:
 * SOOP 아이디·닉네임은 로그에 찍지 않는다.
 */
public record SoopIdentity(String soopUserId, String nickname) {
}
