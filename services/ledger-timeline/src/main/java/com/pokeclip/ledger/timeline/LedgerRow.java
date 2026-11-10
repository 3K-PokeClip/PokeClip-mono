package com.pokeclip.ledger.timeline;

/**
 * 회차 장부 행 하나(F의 입력).
 *
 * <p>{@code startMs}는 <b>부르는 쪽이 고른 축</b>의 ms다 — 편집기 출처는 {@code start_wall_utc}(µs를 ms로 내림),
 * 서버 시계 판 2는 {@code playback_pdt}, 카드 축은 {@code start_pts_ms}(설계 r7 3.2 표). 한 번 부를 때 축은 하나다.
 *
 * <p>{@code continuesPrevious}는 목록 바로 앞 행과 같은 녹화로 이어졌는가다 —
 * {@code followsLedgerPrevious ∧ ¬isDiscontinuity}. 회차로 자른 열에서 장부 바로 앞 행이 아닌 이웃(사이에 비귀속
 * 행이 있음)은 칸 값과 무관하게 거짓이다. 만드는 쪽이 정한다(r6 3.2).
 */
public record LedgerRow(long seq, long startMs, int durationMs, boolean continuesPrevious) {
}
