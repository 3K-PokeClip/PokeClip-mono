package com.pokeclip.chat.collector.query;

import java.util.List;

/**
 * 차트 한 장. <b>빈 구간도 0으로 들어 있다</b> — 없으면 프론트가 선을 못 그린다.
 *
 * @param appliedOffsetMs 이 답에 실제로 쓴 보정값. 목록 창구와 <b>같은 뜻</b>이다
 */
public record ChatChartPage(int bucketSeconds, List<ChartBucket> buckets, long appliedOffsetMs) {
}
