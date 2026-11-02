package com.pokeclip.chat.collector.relay;

/**
 * 수신 스레드가 보는 중계 입구. <b>넣기만 한다</b> — 여기서 I/O를 하면 WS 수신 스레드가
 * 붙들려 채팅 폭주 때 수신이 밀린다(저장·아카이브와 같은 규칙).
 *
 * <p>구현이 둘이라 인터페이스다 — 꺼져 있으면 {@link #NONE}, 켜져 있으면 {@link RelayBuffer}.
 * 세션·등록부는 켜짐/꺼짐을 모른다.
 */
public interface RelaySink {

    /**
     * @param streamId null이면 버린다(옛 경로는 방송 번호가 없다)
     */
    void offer(String streamId, RelayPayload payload);

    /**
     * 끝난 방송의 번호 카운터를 치운다. 등록부가 세션을 닫거나 방송을 갈아낄 때 부른다 —
     * 안 치우면 프로세스가 오래 돌수록 방송 수만큼 쌓인다.
     */
    default void forget(String streamId) {
    }

    RelaySink NONE = (streamId, payload) -> { };
}
