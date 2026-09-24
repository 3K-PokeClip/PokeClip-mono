package com.pokeclip.chat.collector.chzzk;

/**
 * SYSTEM은 connected · subscribed · unsubscribed · revoked 넷뿐이다.
 *
 * @param eventType subscribed·unsubscribed·revoked에만 실린다(CHAT·DONATION·SUBSCRIPTION).
 *                  <b>없으면 빈 문자열이다 — null이 아니다.</b> 이 값으로 갈래를 가르는
 *                  {@code StreamSession}의 {@code equals} 비교가 null이면 NPE로 죽는데,
 *                  그 자리가 WS 수신 콜백이라 방송 전체 수신이 멈춘다
 */
public record SystemEvent(String type, String sessionKey, String eventType) { }
