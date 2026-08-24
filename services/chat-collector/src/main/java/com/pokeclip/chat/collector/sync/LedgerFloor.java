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
     * <p><b>{@code floor}와 그 바로 다음 조각만 보는 구현으로는 못 잡는다</b> — 멀리 떨어진
     * 조각 하나만 과거로 튀면 그 쌍은 멀쩡해 보인다. {@code seq1~4}가 정상이고 {@code seq10}만
     * 과거로 튄 데이터에서 {@code t}를 {@code seq3} 구간으로 물으면 floor는 {@code seq3},
     * 그 다음은 {@code seq4}라 둘 사이가 비감소인데 {@code maxCandidateSeq}는 10이다(실 PG 재현).
     * 그래서 후보 집합 <b>전체</b>의 최댓값을 본다.
     *
     * <p><b>다만 「이웃 비교」를 표 전체의 인접 쌍 전수 비교로 읽으면 그쪽은 잡는다</b> —
     * 모든 인접 쌍이 비감소면 추이성으로 전체가 비감소이므로, 전수 비교가 놓치는 역행은
     * 존재하지 않는다. 우리가 그 방식을 안 쓰는 이유는 <b>못 잡아서가 아니라 표를 통째로
     * 훑어야 하기 때문</b>이다. 여기 방식은 스칼라 서브쿼리 둘이라 왕복 추가가 0회다.
     */
    public boolean wallClockInverted() {
        return maxCandidateSeq != segment.seq() || earlierIsFuture;
    }
}
