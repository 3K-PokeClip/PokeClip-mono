package com.pokeclip.chat.collector.sync;

import java.time.Instant;

/**
 * 조각 장부({@code stream_segments}) 한 행에서 이 카드가 쓰는 칸만 담는다.
 *
 * @param seq            조각 번호. <b>재생 순서는 이것이다</b> — 벽시계가 아니다
 * @param startPtsMs     영상 기준 시작 위치(ms)
 * @param startWallUtc   그 조각이 실제로 시작한 벽시계 시각
 * @param durationMs     실측 길이(ms). 마지막 조각의 상한이 여기서 온다
 * @param discontinuity  앞 조각과 끊겼는가. 참이면 그 사이는 녹화에 없다
 */
public record LedgerSegment(long seq, long startPtsMs, Instant startWallUtc,
                            int durationMs, boolean discontinuity) {
}
