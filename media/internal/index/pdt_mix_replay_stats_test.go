package index

import (
	"fmt"
	"math"
	"slices"
)

// 실측 1 의 잴 것 (a)–(f)(설계 2.9). 재생 결과 행(pdt_mix_replay_test.go)에서 센다.

// replayStats 는 판 하나(또는 여럿의 합)의 실측 1 값이다.
type replayStats struct {
	falseChapters int     // (a) 같은 접속 안 new_chapter_* 수(접속 첫 조각 제외)
	connFirst     int     // 접속 첫 조각 수(표 첫 행 제외)
	connFirstGap  int     // 그 가운데 new_chapter_gap — 실제 틈을 새 장으로 잡았는가
	leadAbs       []int64 // (b) 영상 머리 조각의 |PDT − w|
	pulls         int     // (c) 견줘서 Δ < −θ 인 행 수((나) 겹침 · (가) 앞섬 유지)
	overlapMax    int64   // (c) 장부에 쓴 겹침 E − PDT 의 최대
	leadMax       int64   // (c) 그 행들의 −Δ 최대
	recoverMax    int     // (c) 그 뒤 |Δ| ≤ θ 로 견주기까지 조각 수의 최대(접속 끝까지 못 오면 끝까지 센 값)
	roundTrips    int     // (d) new_chapter_gap 뒤 2조각 안에 Δ < −θ 로 견준 수
	continueRun   int     // (e) 견주지 않고 이은(continue) 최장 연속
	audioDeltaMax int64   // (f) 견준 소리 머리 조각(접속 첫 조각 제외) Δ 의 최대
	audioDeltaN   int     // (f) 그 표본 수
}

// measure 는 재생 결과 rows 의 실측 1 값이다. theta 는 ms 다.
func measure(rows []replayRow, theta int64) replayStats {
	var st replayStats
	run := 0
	for i, r := range rows {
		connFirst := i == 0 || rows[i-1].seg.conn != r.seg.conn
		newChapter := r.out.Branch == BranchNewChapterGap || r.out.Branch == BranchNewChapterOverlap
		switch {
		case i == 0:
		case connFirst:
			st.connFirst++
			if r.out.Branch == BranchNewChapterGap {
				st.connFirstGap++
			}
		case newChapter:
			st.falseChapters++
		}
		if r.out.Branch == BranchContinue {
			run++
			st.continueRun = max(st.continueRun, run)
		} else {
			run = 0
		}
		if r.seg.headVideo && r.seg.hasVideo {
			st.leadAbs = append(st.leadAbs, abs64(r.out.PDTMS-r.seg.wMS))
		}
		if r.out.Compared && !r.seg.headVideo && !connFirst {
			if st.audioDeltaN == 0 || r.out.DeltaMS > st.audioDeltaMax {
				st.audioDeltaMax = r.out.DeltaMS
			}
			st.audioDeltaN++
		}
		if r.out.Compared && r.out.DeltaMS < -theta {
			st.pulls++
			st.overlapMax = max(st.overlapMax, r.e-r.out.PDTMS)
			st.leadMax = max(st.leadMax, -r.out.DeltaMS)
			st.recoverMax = max(st.recoverMax, recoverAfter(rows, i, theta))
		}
		if r.out.Branch == BranchNewChapterGap && !connFirst && pullWithin(rows, i, 2, theta) {
			st.roundTrips++
		}
	}
	return st
}

// recoverAfter 는 rows[i] 뒤에서 |Δ| ≤ θ 로 견준 첫 행까지의 조각 수다(같은 접속 안 · 못 오면 접속
// 끝까지 센 값).
func recoverAfter(rows []replayRow, i int, theta int64) int {
	n := 0
	for j := i + 1; j < len(rows) && rows[j].seg.conn == rows[i].seg.conn; j++ {
		n++
		if rows[j].out.Compared && abs64(rows[j].out.DeltaMS) <= theta {
			return n
		}
	}
	return n
}

// pullWithin 은 rows[i] 뒤 n 조각 안(같은 접속)에 Δ < −θ 로 견준 행이 있는가다 — 앞으로 뛴 뒤 뒤로
// 당기는 왕복(예측표 P8).
func pullWithin(rows []replayRow, i, n int, theta int64) bool {
	for j := i + 1; j <= i+n && j < len(rows) && rows[j].seg.conn == rows[i].seg.conn; j++ {
		if rows[j].out.Compared && rows[j].out.DeltaMS < -theta {
			return true
		}
	}
	return false
}

// add 는 두 판의 값을 합친 새 값이다 — 수는 더하고 최대는 최대로.
func (st replayStats) add(o replayStats) replayStats {
	out := replayStats{
		falseChapters: st.falseChapters + o.falseChapters,
		connFirst:     st.connFirst + o.connFirst,
		connFirstGap:  st.connFirstGap + o.connFirstGap,
		leadAbs:       slices.Concat(st.leadAbs, o.leadAbs),
		pulls:         st.pulls + o.pulls,
		overlapMax:    max(st.overlapMax, o.overlapMax),
		leadMax:       max(st.leadMax, o.leadMax),
		recoverMax:    max(st.recoverMax, o.recoverMax),
		roundTrips:    st.roundTrips + o.roundTrips,
		continueRun:   max(st.continueRun, o.continueRun),
		audioDeltaN:   st.audioDeltaN + o.audioDeltaN,
		audioDeltaMax: o.audioDeltaMax,
	}
	if st.audioDeltaN > 0 && (o.audioDeltaN == 0 || st.audioDeltaMax > o.audioDeltaMax) {
		out.audioDeltaMax = st.audioDeltaMax
	}
	return out
}

// statsColumns 는 row 가 내는 칸의 이름이다.
var statsColumns = []string{
	"(a) 거짓 새 장", "접속 경계 gap/수", "(b) 영상 머리 \\|PDT − w\\| p50 · p99 · 최대",
	"(c) Δ<−θ 수 · 겹침 최대 · 앞섬 최대 · 회복 조각", "(d) 왕복", "(e) continue 최장", "(f) 견준 소리 머리 Δ 최대",
}

// row 는 보고 한 줄이다.
func (st replayStats) row() string {
	p50, p99, mx := quantiles(st.leadAbs)
	f := "—"
	if st.audioDeltaN > 0 {
		f = fmt.Sprintf("%d(n=%d)", st.audioDeltaMax, st.audioDeltaN)
	}
	return fmt.Sprintf("| %d | %d/%d | %d · %d · %d | %d · %d · %d · %d | %d | %d | %s |",
		st.falseChapters, st.connFirstGap, st.connFirst, p50, p99, mx,
		st.pulls, st.overlapMax, st.leadMax, st.recoverMax, st.roundTrips, st.continueRun, f)
}

// quantiles 는 p50 · p99(최근접 순위) · 최대다. 비어 있으면 0 셋이다.
func quantiles(v []int64) (p50, p99, mx int64) {
	if len(v) == 0 {
		return 0, 0, 0
	}
	s := slices.Sorted(slices.Values(v))
	at := func(q float64) int64 { return s[int(math.Ceil(q*float64(len(s))))-1] }
	return at(0.5), at(0.99), s[len(s)-1]
}

func abs64(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}
