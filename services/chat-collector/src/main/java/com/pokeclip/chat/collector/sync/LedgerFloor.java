package com.pokeclip.chat.collector.sync;

/**
 * floor 한 행 + <b>같은 왕복에서</b> 얻은 시계 역행 신호 둘.
 *
 * <p>신호를 따로 물으면 왕복이 늘고, 그 사이 장부에 조각이 들어오면 floor와 신호가
 * 다른 시점을 보게 된다. 스칼라 서브쿼리 둘을 floor 조회에 얹어 왕복 추가는 0회다.
 *
 * @param segment         {@code start_wall_utc <= t}인 조각 중 (벽시계 DESC, seq DESC) 첫 행
 * @param maxCandidateSeq 같은 후보 집합에서 가장 큰 seq. floor와 다르면 <b>뒤 조각이 과거로 튀었다</b>
 * @param earlierIsFuture floor보다 seq가 작은데 벽시계는 floor보다 늦은 조각이 있는가.
 *                        참이면 <b>앞 조각이 미래에 있다</b>
 */
public record LedgerFloor(LedgerSegment segment, long maxCandidateSeq, boolean earlierIsFuture) {

    /**
     * <b>신호 둘을 {@code OR}로 묶는다 — 하나로는 절반을 놓친다.</b> 실 PG 재현에서
     * 「앞 조각이 미래」는 {@code maxCandidateSeq}가 깨끗한 채로, 「뒤 조각이 과거로 튐」은
     * {@code earlierIsFuture}가 깨끗한 채로 나타났다. 정상 방송 셋에서는 둘 다 안 켜진다(오탐 0).
     *
     * <p><b>바로 다음 조각과의 비교로는 못 잡는다</b> — 멀리 떨어진 조각 하나만 과거로
     * 튄 경우가 이웃 비교를 통과한다. 그래서 후보 집합 전체의 최댓값을 본다.
     */
    public boolean wallClockInverted() {
        return maxCandidateSeq != segment.seq() || earlierIsFuture;
    }
}
