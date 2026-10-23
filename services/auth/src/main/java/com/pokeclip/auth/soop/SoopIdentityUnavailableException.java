package com.pokeclip.auth.soop;

/**
 * SOOP이 토큰은 줬는데 아이디를 어디서도 못 읽었다: 방송 중이 아니라 방송 여부 체크에 {@code user_id}가 없고, 방송국 정보의
 * 대표 이미지가 LOGO 꼴이 아니다(대표 이미지를 안 올린 방송국의 기본 이미지·없음). 거부도 일시 장애도 아니다: 방송을 켠 상태에서
 * 다시 연동하면 풀린다(사용자 결정 B, 2026-10-23). 값은 아무것도 싣지 않는다.
 */
public class SoopIdentityUnavailableException extends RuntimeException {

    public SoopIdentityUnavailableException() {
        super("SOOP 아이디를 읽지 못했다(방송 중이 아니고 대표 이미지가 LOGO 꼴이 아니다)");
    }
}
