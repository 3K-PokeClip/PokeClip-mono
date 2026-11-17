package com.pokeclip.ledger.timeline;

import java.util.List;

/**
 * 장부 시각 ↔ 매체 자리 변환 F와 예상 결과 길이(계약 세그먼트인덱스 6-6-6 · 설계 r7 3.2).
 *
 * <p>입력 행은 <b>한 회차의 행을 seq 오름차순</b>으로 준다. 회차로 자르기 · 축 고르기 · 이웃 이어짐 판정은 부르는
 * 쪽 몫이다 — 여기는 그 결과만 받아 자리를 낸다. 행 시각은 단조가 아닐 수 있다(경계 표 B8 부분 겹침).
 * 그래서 이분 탐색이 아니라 처음부터 끝까지 훑어 「시작 ≤ t 인 행 가운데 seq 최대」 를 고른다.
 */
public final class LedgerTimeline {

    private LedgerTimeline() {
    }

    /**
     * F(t) — 계약 6-6-6 1–5항. {@code startMs ≤ t} 인 행 가운데 seq가 가장 큰 행 k를 고른다(겹침은 나중 조각 —
     * 경계 표 B8). k가 t를 덮으면 그 자리, 아니면 뒤 행이 이어졌으면 뒤 행 시작, 끊겼으면 공백이다.
     */
    public static Place locate(List<LedgerRow> rows, long t) {
        requireValidRows(rows);
        int k = lastStartingAtOrBefore(rows, t, true);
        if (k < 0) {
            return new Place.BeforeFirst();
        }
        LedgerRow row = rows.get(k);
        if (t < endOf(row)) {
            return new Place.Media(row.seq(), t - row.startMs());
        }
        return beyond(rows, k, t, false);
    }

    /**
     * 반개 구간 {@code [in, out)} 의 끝 out — 「out 직전」 자리. 고르는 행은 {@code startMs < out} 이고, 이어진
     * 장부 틈 안 · 이어진 이웃 시작과 같은 끝은 앞 조각 끝 {@code (k, dur(k))} 로 낸다(r6 3.4 — 같은 매체 자리
     * {@code (k+1, 0)} 가운데 앞쪽 표기로 고정). 그래서 매체 자리의 offset은 {@code 1 ≤ offsetMs ≤ dur} 다.
     *
     * <p>이어진 이웃 시작과 같은 끝은 두 행이 겹쳐도(보정 1ms · 경계 표 B8) 덮음보다 먼저 앞 조각 끝이다 — 계약
     * 6-6-6 「이 자리는 매체로 (k, 0) 과 같다」 는 장부 시각이 아니라 매체가 이어졌다는 데 기대므로, 겹친 장부
     * 시각 안의 자리로 내면 맞닿은 다음 컷 시작 {@code (k+1, 0)} 과 사이 매체가 빠진다. 끊긴 이웃은 매체가
     * 이어지지 않아 이 규칙 밖이다.
     */
    public static Place locateEnd(List<LedgerRow> rows, long out) {
        requireValidRows(rows);
        int k = lastStartingAtOrBefore(rows, out, false);
        if (k < 0) {
            return new Place.BeforeFirst();
        }
        LedgerRow row = rows.get(k);
        if (k + 1 < rows.size() && rows.get(k + 1).continuesPrevious() && rows.get(k + 1).startMs() == out) {
            return new Place.Media(row.seq(), row.durationMs());
        }
        if (out <= endOf(row)) {
            return new Place.Media(row.seq(), out - row.startMs());
        }
        return beyond(rows, k, out, true);
    }

    /**
     * 예상 결과 길이 = {@code (out − in) − Σ 이어 붙여 빠진 틈의 컷 안 몫}(설계 r7 9.2 8 · 9.3). 렌더 때 거절에
     * 쓰지 않는 기록 값이다 — 이음 자리 머리 앞섬 · 겹침 같은 매체 차는 렌더가 실제 배치로 잰다.
     */
    public static long lengthMs(long inMs, long outMs, List<Long> joinedCutPartMs) {
        if (outMs < inMs) {
            throw new IllegalArgumentException("outMs(" + outMs + ")가 inMs(" + inMs + ")보다 앞이다");
        }
        long joined = 0;
        for (long part : joinedCutPartMs) {
            if (part < 0) {
                throw new IllegalArgumentException("이어 붙인 틈 몫이 음수다: " + part);
            }
            joined += part;
        }
        long range = outMs - inMs;
        if (joined > range) {
            throw new IllegalArgumentException("이어 붙인 틈 몫 합(" + joined + ")이 범위(" + range + ")보다 크다");
        }
        return range - joined;
    }

    /**
     * k 끝 뒤의 시각 — 뒤 행이 없으면 「밖 · 아직」, 이어졌으면 같은 매체 자리(시작 쪽은 뒤 행 {@code (k+1, 0)} ·
     * 끝 쪽은 앞 행 {@code (k, dur(k))}), 끊겼으면 공백(F 3 · 4 · 5항). 고른 k가 seq 최대라 뒤 행 시작은 늘 t보다
     * 뒤다.
     */
    private static Place beyond(List<LedgerRow> rows, int k, long t, boolean endSide) {
        if (k == rows.size() - 1) {
            return new Place.AfterLast();
        }
        LedgerRow row = rows.get(k);
        LedgerRow next = rows.get(k + 1);
        if (next.continuesPrevious()) {
            return endSide ? new Place.Media(row.seq(), row.durationMs()) : new Place.Media(next.seq(), 0);
        }
        return new Place.Gap(row.seq(), next.seq(), t - endOf(row), next.startMs() - endOf(row));
    }

    private static int lastStartingAtOrBefore(List<LedgerRow> rows, long t, boolean inclusive) {
        int found = -1;
        for (int i = 0; i < rows.size(); i++) {
            long start = rows.get(i).startMs();
            if (inclusive ? start <= t : start < t) {
                found = i;
            }
        }
        return found;
    }

    private static long endOf(LedgerRow row) {
        return row.startMs() + row.durationMs();
    }

    /**
     * 「seq 최대」 를 목록 순서로 고르므로 seq 순서가 어긋나면 조용히 틀린 행을 고르고, 길이가 음수면 끝이 시작보다
     * 앞선다 — 들어올 때 막는다.
     */
    private static void requireValidRows(List<LedgerRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            LedgerRow row = rows.get(i);
            if (row.durationMs() < 0) {
                throw new IllegalArgumentException(
                        "durationMs가 음수다: seq=" + row.seq() + " durationMs=" + row.durationMs());
            }
            if (i > 0 && row.seq() <= rows.get(i - 1).seq()) {
                throw new IllegalArgumentException(
                        "행이 seq 오름차순이 아니다: " + rows.get(i - 1).seq() + " 뒤에 " + row.seq());
            }
        }
    }
}
