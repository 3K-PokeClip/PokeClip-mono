package com.pokeclip.chat.collector.broadcast;

/**
 * 계약9의 {@code eventType}을 우리가 다루는 셋으로 가른다.
 *
 * <p><b>모르는 종류에 예외를 던지지 않는다.</b> clip은 같은 계약을 읽으면서 던지지만,
 * 우리 소비 지점은 SQS 폴링 루프라 예외가 거기까지 올라가면 그 방송뿐 아니라
 * 수집기 전체의 편지 수신이 멈춘다. 대신 UNKNOWN을 값으로 돌려주고
 * 소비자가 세어서 드러낸다.
 */
public enum LifecycleEventType {
    STARTED,
    ENDED,
    UNKNOWN;

    public static LifecycleEventType from(String eventType) {
        if ("broadcast.started".equals(eventType)) {
            return STARTED;
        }
        if ("broadcast.ended".equals(eventType)) {
            return ENDED;
        }
        return UNKNOWN;
    }
}
