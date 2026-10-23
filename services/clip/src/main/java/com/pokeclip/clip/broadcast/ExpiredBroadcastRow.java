package com.pokeclip.clip.broadcast;

import java.time.Instant;

/**
 * 만료된 방송 한 줄과 지워도 되는 조각 구간(POK-335). 구간은 [segmentsFrom, segmentsUntil)이고 segmentsFrom이 null이면
 * 처음부터다. {@code id} · {@code expiresMicros}는 이어받기 표시용이라 응답에 안 싣는다.
 */
public record ExpiredBroadcastRow(long id, String streamId, String ingestStreamId, Instant startedAt, Instant endedAt,
                                  Instant vodExpiresAt, long expiresMicros, Instant segmentsFrom, Instant segmentsUntil) {
}
