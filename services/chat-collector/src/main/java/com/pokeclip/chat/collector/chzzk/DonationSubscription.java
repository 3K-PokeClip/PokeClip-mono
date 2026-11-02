package com.pokeclip.chat.collector.chzzk;

/**
 * 이 방송의 후원 구독이 어떻게 됐나. <b>채팅 구독과 운명이 갈린다</b> —
 * 후원이 거부돼도 채팅 수집은 그대로 산다(POK-234).
 *
 * <p>{@code wireName()}이 창구 응답에 그대로 실린다 — 소문자 넷 중 하나다.
 * clip·web과의 약속이므로 값을 바꾸면 저쪽 배선도 같이 바꾼다.
 */
public enum DonationSubscription {

    /** 구독을 시도한 적이 없다(등록부에 없는 방송 포함). */
    NONE("none"),
    /** 구독 REST가 200을 줬다. */
    SUBSCRIBED("subscribed"),
    /** 401·403 — 권한이 없다. 재시도해도 안 풀린다. */
    REFUSED("refused"),
    /** 그 밖의 실패(5xx·네트워크). 다음 수립에서 다시 시도된다. */
    FAILED("failed");

    private final String wire;

    DonationSubscription(String wire) {
        this.wire = wire;
    }

    public String wireName() {
        return wire;
    }
}
