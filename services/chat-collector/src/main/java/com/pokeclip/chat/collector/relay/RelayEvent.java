package com.pokeclip.chat.collector.relay;

/**
 * 번호가 붙은 중계 한 건. <b>{@link RelayBuffer}만 만든다.</b>
 *
 * @param seq 방송마다 1부터. <b>순서와 빈틈 감지용이다 — 중복 판정 열쇠가 아니다</b>(F3).
 *            프로세스가 다시 뜨거나 세션이 닫혔다 열리면 1부터 다시 센다.
 *            창구의 {@code id}(표 PK)와 다른 축이라 짝지으면 안 된다
 * @param seqEpoch 이 {@code seq}를 세는 카운터가 <b>만들어진 시각</b>(epoch ms, 한 프로세스 안에서는 늘 커진다).
 *                 🔴 <b>이 값이 바뀌면 번호가 새로 시작한 것이다 — 기준을 버리고 메운다</b>(감사 L8).
 *                 {@code seq}만으로는 재시작 뒤 새 번호 1..k가 전부 사라지고 k+1이 우연히 직전+1이면 못 알아챈다
 */
public record RelayEvent(String streamId, long seq, long seqEpoch, RelayPayload payload) {
}
