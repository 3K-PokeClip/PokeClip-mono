package com.pokeclip.chat.collector.query;

import java.time.Instant;

/**
 * 차트 점 하나. 구간은 {@code [start, start + bucketSeconds)}다.
 *
 * @param start <b>표 축</b>이다({@link ChatWindowQuery} 머리의 축 표 — 목록 창구와 같은 규칙).
 *              화면 위치는 {@code start − appliedOffsetMs}로 얻는다. 🔴 여기서만 미리 빼서
 *              내보내면 목록과 축이 갈려 프론트가 한쪽만 되돌린다(계획 검증 F4)
 */
public record ChartBucket(Instant start, long chats, long donations) {
}
