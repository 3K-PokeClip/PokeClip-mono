package com.pokeclip.chat.collector.relay;

/**
 * 번호가 붙은 중계 한 건. <b>{@link RelayBuffer}만 만든다.</b>
 *
 * @param seq 방송마다 1부터. <b>순서와 빈틈 감지용이다 — 중복 판정 열쇠가 아니다</b>(F3).
 *            프로세스가 다시 뜨거나 세션이 닫혔다 열리면 1부터 다시 센다.
 *            창구의 {@code id}(표 PK)와 다른 축이라 짝지으면 안 된다
 */
public record RelayEvent(String streamId, long seq, RelayPayload payload) {
}
