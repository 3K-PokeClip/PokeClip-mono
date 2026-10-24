package com.pokeclip.auth.api.dto;

import com.pokeclip.auth.user.User;

/**
 * @param analyticsOptOut 설정 › 계정의 「이용 분석 참여」를 껐나(V120). 대시보드가 읽고 분석 도구로 보내지 않는다
 */
public record MeResponse(Long id, String email, String name, String profileImageUrl, boolean analyticsOptOut) {

    /**
     * 사진 주소는 <b>받아서 싣기만 한다</b> — 짓는 법(표 서명·주소 앞부분·꺼짐 판정)은
     * {@code PhotoUrls}에 있다. DTO가 설정을 알면 이 record가 스프링 빈에 매이고,
     * 세 창구가 각자 다른 주소를 만들 여지가 생긴다.
     */
    public static MeResponse from(User user, String profileImageUrl) {
        return new MeResponse(
                user.getId(), user.getEmail(), user.getName(), profileImageUrl, user.isAnalyticsOptOut());
    }
}
