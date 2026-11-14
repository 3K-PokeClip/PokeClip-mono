package com.pokeclip.chat.collector.sync;

import java.time.Instant;

/**
 * 조각 장부({@code stream_segments}) 한 행에서 이 카드가 쓰는 칸만 담는다.
 *
 * @param seq            조각 번호. <b>재생 순서는 이것이다</b> — 벽시계가 아니다
 * @param startPtsMs     영상 기준 시작 위치(ms)
 * @param startWallUtc   파일명 시각(파일 머리). 판 NULL 행의 floor 축이다
 * @param playbackPdt    영상 첫 표본의 벽시각. 판 2 행의 floor 축이고 판 2 행에는 늘 있다. 판 NULL 행은 비거나 다른 규약의
 *                       값이라 읽지 않는다(계약 세그먼트인덱스 6-6-9)
 * @param timingVersion  행의 시각 규약 판. {@code null} = 옛 식, {@code 2} = PDT 개정(6-6-10 I17)
 * @param durationMs     실측 길이(ms). 마지막 조각의 상한이 여기서 온다
 * @param discontinuity  앞 조각과 끊겼는가. 참이면 그 사이는 녹화에 없다
 */
public record LedgerSegment(long seq, long startPtsMs, Instant startWallUtc, Instant playbackPdt,
                            Integer timingVersion, int durationMs, boolean discontinuity) {

    /** 판 2 행인가. 판 2 행은 floor · 위치를 {@code playback_pdt} 축 F로 낸다. */
    public boolean v2() {
        return timingVersion != null && timingVersion == 2;
    }
}
