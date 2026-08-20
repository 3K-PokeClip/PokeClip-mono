package com.pokeclip.chat.collector.broadcast;

import java.time.Instant;

/**
 * 끝난 방송 메모 한 줄 — {@code chat_ended_streams}의 표 모양 그대로다.
 *
 * <p>{@code endedAt}(편지에 적힌 종료 시각)과 {@code createdAt}(우리가 메모를 남긴 시각)은
 * 다른 것이다. 치우기의 기준은 <b>{@code createdAt}</b>이다 — 종료 통보가 한참 늦게 오면
 * 두 값이 크게 벌어지는데, 그때 {@code endedAt}으로 재면 메모가 남자마자 지워진다.
 */
public record EndedStream(String streamId, long lastSequence, Instant endedAt, Instant createdAt) {
}
