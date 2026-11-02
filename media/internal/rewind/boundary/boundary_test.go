package boundary_test

// 되감기 경계(설계 4.1 settled 술어 · 4.2 경계 계산)의 단위 검증 — POK-195 M4 PR ⓑ.
//
// 픽스처 번호는 설계가 이름을 준 G6 목록이다(f9·f10·f11·f12·f15·f17·f18·f19 와 f2 의 경계 몫).
// G9 leaf 이름(f0_…·f6c_…)과 헷갈리지 않게 주석에 g6_ 접두를 붙인다.
// 기대값은 전부 손으로 셈한 리터럴이며 셈은 각 테스트 주석에 적는다. 조각 길이는 f12 를 빼고
// 4초(4000ms) 고정이다(설계 4.8.1 단순화 규약 — 가변 길이는 f12 가 본다).
//
// 외부 테스트 패키지로 두는 이유: 운영 구현(rewind/cache)도 패키지 밖에서 Snapshot 을 만족시킨다.
// 픽스처 구현이 같은 자리에서 같은 계약만으로 성립하는지가 곧 경계의 공개 계약 검증이다.

import (
	"fmt"
	"slices"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// ledger 는 Snapshot 의 픽스처 구현이다 — 한 스트림의 컷오프와 장부 행(seq 오름차순).
// 운영 구현과 같은 계약을 지킨다: RowsFrom 은 seq ≥ from 인 행만 오름차순으로 준다.
type ledger struct {
	cutoff    int64
	hasCutoff bool
	rows      []boundary.Row
}

func (l ledger) Cutoff() (int64, bool) { return l.cutoff, l.hasCutoff }

func (l ledger) RowsFrom(from int64) []boundary.Row {
	for i, r := range l.rows {
		if r.Seq >= from {
			return l.rows[i:]
		}
	}
	return nil
}

// cutoffAt 은 컷오프가 있는 스트림의 장부다. 행 묶음은 seq 순서대로 넘긴다.
func cutoffAt(cutoff int64, parts ...[]boundary.Row) ledger {
	return ledger{cutoff: cutoff, hasCutoff: true, rows: slices.Concat(parts...)}
}

// pdtBase 는 픽스처 행의 playback_pdt 기준 시각이다. 경계는 PDT 의 유무만 본다.
var pdtBase = time.Date(2026, 9, 24, 0, 0, 0, 0, time.UTC)

// uploaded 는 settled 의 네 조건(PDT·세션·키 존재 + ③ uploaded)을 다 채운 행이다.
func uploaded(seq int64, durationMS int32) boundary.Row {
	return boundary.Row{
		Seq:              seq,
		SessionID:        "S-20260924-0000",
		DurationMS:       durationMS,
		PlaybackPDT:      pdtBase.Add(time.Duration(seq) * 4 * time.Second),
		PlaybackS3Key:    fmt.Sprintf("dvr/str/seg/%06d.m4s", seq),
		PlaybackUploaded: true,
	}
}

// pending 은 ③ 가 아직 올라가지 않았고 GAP 원장에도 없는 행이다. 나머지는 uploaded 와 같다.
func pending(seq int64, durationMS int32) boundary.Row {
	r := uploaded(seq, durationMS)
	r.PlaybackUploaded = false
	return r
}

// uploadedRun 은 from 부터 to 까지(양끝 포함) 같은 길이의 uploaded 행이다.
func uploadedRun(from, to int64, durationMS int32) []boundary.Row {
	var rows []boundary.Row
	for seq := from; seq <= to; seq++ {
		rows = append(rows, uploaded(seq, durationMS))
	}
	return rows
}

// pendingRun 은 from 부터 to 까지(양끝 포함) 같은 길이의 pending 행이다.
func pendingRun(from, to int64, durationMS int32) []boundary.Row {
	var rows []boundary.Row
	for seq := from; seq <= to; seq++ {
		rows = append(rows, pending(seq, durationMS))
	}
	return rows
}

// span 은 장부 행 가운데 창 [TailSeq, HeadSeq] 에 드는 행의 수와 길이 합이다. 구현(머리에서
// 거꾸로 누적)과 다른 길(앞에서부터 거르기)로 센다.
func span(l ledger, w boundary.Window) (n int, ms int64) {
	for _, r := range l.rows {
		if r.Seq >= w.TailSeq && r.Seq <= w.HeadSeq {
			n++
			ms += int64(r.DurationMS)
		}
	}
	return n, ms
}

// mustCompute 는 컷오프가 있는 장부의 경계를 계산한다. 컷오프가 있으면 계산은 언제나 성립한다.
func mustCompute(t *testing.T, l ledger, prevTail int64) boundary.Window {
	t.Helper()
	w, ok := boundary.Compute(l, prevTail)
	if !ok {
		t.Fatalf("Compute(prevTail=%d) 가 컷오프(%d)가 있는데 계산하지 않았다", prevTail, l.cutoff)
	}
	return w
}

// TestSettled 는 settled 술어(설계 4.1 정본)의 항을 하나씩 뺀다. 기준 행은 컷오프 행 자신이며
// 네 조건과 ③ uploaded 를 다 채웠다 — 각 경우는 그 행에서 **한 칸만** 바꾼다.
func TestSettled(t *testing.T) {
	tests := []struct {
		name   string
		change func(r *boundary.Row)
		want   bool
	}{
		// 컷오프 행 자신이 접두 시작점이다(firstAvail = cutoff, 포함).
		{"컷오프_행_자신", func(*boundary.Row) {}, true},
		// 컷오프 미만은 홀이 아니라 범위 밖이다(설계 4.2 ⓐ).
		{"컷오프_미만", func(r *boundary.Row) { r.Seq = 99 }, false},
		{"playback_pdt_NULL", func(r *boundary.Row) { r.PlaybackPDT = time.Time{} }, false},
		{"session_id_NULL", func(r *boundary.Row) { r.SessionID = "" }, false},
		{"playback_s3_key_NULL", func(r *boundary.Row) { r.PlaybackS3Key = "" }, false},
		{"미업로드", func(r *boundary.Row) { r.PlaybackUploaded = false }, false},
		// GAP 원장에 있으면 ③ 없이도 settled 다 — 목록이 그 자리를 GAP 줄로 메운다.
		{"미업로드_GAP_원장", func(r *boundary.Row) { r.PlaybackUploaded, r.IsGap = false, true }, true},
		{"업로드_GAP_원장_둘다", func(r *boundary.Row) { r.IsGap = true }, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			row := uploaded(100, 4000)
			tt.change(&row)

			if got := boundary.Settled(row, 100); got != tt.want {
				t.Errorf("Settled(%+v, cutoff=100) = %v, want %v", row, got, tt.want)
			}
		})
	}
}

// 컷오프가 없는 스트림은 되감기를 제공하지 않는다(설계 4.2 ⓑ) — 행이 1시간 넘게 쌓여 있어도
// 창을 만들지 않는다.
func TestComputeWithoutCutoffGivesNoWindow(t *testing.T) {
	l := ledger{rows: uploadedRun(0, 1199, 4000)}

	got, ok := boundary.Compute(l, 0)

	if ok || got != (boundary.Window{}) {
		t.Errorf("Compute(컷오프 없음) = %+v, %v; want 영값, false", got, ok)
	}
}

// scanFrom = max(firstAvail, 직전 tailSeq)(설계 4.2). 직전 꼬리가 컷오프 아래면(부트스트랩 0 포함)
// 컷오프에서, 위면 직전 꼬리에서 스캔한다. 정상 국면의 직전 꼬리에서 시작해도 창은 처음부터 센
// 것과 같다.
// 컷오프 12 · seq 12..2011(2000조각) → headSeq 2011 · tailSeq = 2011 − 899 = 1112(900조각 = 1시간).
func TestComputeScanFromIsMaxOfCutoffAndPrevTail(t *testing.T) {
	l := cutoffAt(12, uploadedRun(12, 2011, 4000))
	tests := []struct {
		name         string
		prevTail     int64
		wantScanFrom int64
	}{
		{"부트스트랩_0", 0, 12},
		{"직전_꼬리_컷오프", 12, 12},
		{"직전_꼬리_컷오프_위", 500, 500},
		{"직전_꼬리_그대로", 1112, 1112},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := mustCompute(t, l, tt.prevTail)

			want := boundary.Window{ScanFrom: tt.wantScanFrom, HeadSeq: 2011, TailSeq: 1112}
			if got != want {
				t.Errorf("Compute(prevTail=%d) = %+v, want %+v", tt.prevTail, got, want)
			}
		})
	}
}

// g6_f9 — 59분. 컷오프부터 머리까지가 1시간이 안 되면 창은 컷오프에서 시작한다(tailSeq = firstAvail).
// 컷오프 100 · seq 100..984(885조각 × 4초 = 3,540초 = 59분).
func TestG6F9UnderAnHourTailIsCutoff(t *testing.T) {
	l := cutoffAt(100, uploadedRun(100, 984, 4000))

	got := mustCompute(t, l, 0)

	if want := (boundary.Window{ScanFrom: 100, HeadSeq: 984, TailSeq: 100}); got != want {
		t.Errorf("Compute = %+v, want %+v", got, want)
	}
}

// g6_f10 — 60분. tailSeq 는 suffixMs(t) ≥ 3,600,000 인 t 의 **최댓값** T* 다(리스크 B1 — r4 의 min 은
// suffixMs 가 t 에 대해 단조 감소라 언제나 firstAvail 을 골랐다).
// 컷오프 0 · seq 0..1199(1200조각) → suffixMs(t) = (1200 − t) × 4000 ≥ 3,600,000 ⇔ t ≤ 300.
// T* = 300 이고 suffixMs(300) = 3,600,000 이 정확히 1시간이다. min 이면 0, ">" 이면 299 가 나온다.
func TestG6F10TailIsMaxSeqReachingAnHour(t *testing.T) {
	l := cutoffAt(0, uploadedRun(0, 1199, 4000))

	got := mustCompute(t, l, 0)

	if want := (boundary.Window{ScanFrom: 0, HeadSeq: 1199, TailSeq: 300}); got != want {
		t.Fatalf("Compute = %+v, want %+v", got, want)
	}
	if n, ms := span(l, got); n != 900 || ms != 3_600_000 {
		t.Errorf("창 = %d조각 %dms, want 900조각 3600000ms", n, ms)
	}
}

// g6_f11 — 60분 + 1조각. 창 길이는 [1시간, 1시간 + max(duration)) 안이다(설계 4.2).
// 컷오프 0 · seq 0..900(901조각 = 3,604,000ms) → suffixMs(t) = (901 − t) × 4000 ≥ 3,600,000 ⇔ t ≤ 1.
// T* = 1 이고 창 = seq 1..900 = 3,600,000ms 로 [3,600,000, 3,604,000) 안이다. min 이면 창이
// 3,604,000ms 가 되어 반열린 상한을 넘는다.
func TestG6F11WindowWithinHourPlusMaxDuration(t *testing.T) {
	l := cutoffAt(0, uploadedRun(0, 900, 4000))

	got := mustCompute(t, l, 0)

	if want := (boundary.Window{ScanFrom: 0, HeadSeq: 900, TailSeq: 1}); got != want {
		t.Errorf("Compute = %+v, want %+v", got, want)
	}
	if _, ms := span(l, got); ms < 3_600_000 || ms >= 3_604_000 {
		t.Errorf("창 길이 = %dms, want [3600000, 3604000)", ms)
	}
}

// g6_f12 — 가변 EXTINF. 4.000초 고정 단순화가 깨져도 산식이 성립한다. 비정수 초 길이를 셋 넣는다
// (4.012초·2.042초는 playback 실물 조각 segment_4s·segment_tail_2s 의 길이, 3.988초는 그 대칭).
//
//	컷오프 50 · seq 50..399 4000ms(350조각) · seq 400..699 4012ms(300조각)
//	          · seq 700 2042ms(짧은 조각) · seq 701..1000 3988ms(300조각)
//
// 머리 1000 에서 거꾸로 센다: 300 × 3988 + 2042 + 300 × 4012 = 2,402,042ms. 1시간까지 남은
// 1,197,958ms 를 4000ms 조각으로 채우려면 300개가 필요하다(299개 = 1,196,000 < 남은 값).
// 그래서 T* = 399 − 299 = 100 · suffixMs(100) = 3,602,042 · suffixMs(101) = 3,598,042 < 3,600,000.
// 창 = 901조각 — "900조각 = 1시간" 가정이면 101 이 나온다. 창 길이는 [3,600,000, 3,604,012) 안이다.
func TestG6F12VariableDurations(t *testing.T) {
	l := cutoffAt(50,
		uploadedRun(50, 399, 4000),
		uploadedRun(400, 699, 4012),
		uploadedRun(700, 700, 2042),
		uploadedRun(701, 1000, 3988),
	)

	got := mustCompute(t, l, 0)

	if want := (boundary.Window{ScanFrom: 50, HeadSeq: 1000, TailSeq: 100}); got != want {
		t.Fatalf("Compute = %+v, want %+v", got, want)
	}
	if n, ms := span(l, got); n != 901 || ms != 3_602_042 {
		t.Errorf("창 = %d조각 %dms, want 901조각 3602042ms", n, ms)
	}
}

// g6_f15 — 빈 창. scanFrom 부터 settled 행이 하나도 없으면 headSeq = scanFrom − 1 이다
// (tailSeq = 컷오프 = headSeq + 1 이라 창에 든 행이 없다). 주조 직후가 정상적으로 이 상태다 —
// 컷오프 행의 ③ 가 아직 pending 이다(G9 t3_cutoff_settles_after_uploaded 의 첫 단언과 같은 국면).
// 그 뒤의 uploaded 행은 창에 실리지 않는다 — 머리는 접두의 끝이지 settled 행의 최댓값이 아니다.
func TestG6F15EmptyWindowHeadIsScanFromMinusOne(t *testing.T) {
	tests := []struct {
		name string
		l    ledger
	}{
		{"컷오프_행_pending", cutoffAt(20, pendingRun(20, 20, 4000), uploadedRun(21, 30, 4000))},
		{"컷오프_이상_행_없음", cutoffAt(20)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := mustCompute(t, tt.l, 0)

			if want := (boundary.Window{ScanFrom: 20, HeadSeq: 19, TailSeq: 20}); got != want {
				t.Errorf("Compute = %+v, want %+v", got, want)
			}
		})
	}
}

// g6_f17 — 컷오프 미만은 홀이 아니라 범위 밖이다(설계 4.2 ⓐ). 컷오프 앞 행이 settled 가 아니어도
// 머리는 컷오프부터 뻗고(③ 는 컷오프 이상만 올린다 — 스위퍼 ③ 조회의 seq >= cutoff_seq), 창은
// 컷오프보다 앞으로 가지 않는다.
// 컷오프 100 · seq 90..99 pending · seq 100..400 uploaded(301조각 = 1,204,000ms < 1시간)
// → headSeq 400 · tailSeq = firstAvail = 100.
func TestG6F17BelowCutoffIsOutOfRangeNotHole(t *testing.T) {
	l := cutoffAt(100, pendingRun(90, 99, 4000), uploadedRun(100, 400, 4000))

	got := mustCompute(t, l, 0)

	if want := (boundary.Window{ScanFrom: 100, HeadSeq: 400, TailSeq: 100}); got != want {
		t.Errorf("Compute = %+v, want %+v", got, want)
	}
}

// g6_f18 — 홀 1개(리스크 B2). 설계 4.2 가 cc 검증 성질로 적은 사슬 headSeq < H → tailSeq < H →
// 다음 scanFrom < H 를 두 번의 계산으로 고정한다 — 홀 뒤 조각은 창에 실리지 않고(404 방지),
// 다음 계산도 홀을 건너뛰지 않는다.
// 컷오프 0 · seq 0..1999 에서 H = 1500 만 ③ pending(GAP 원장 없음).
// 1회차: headSeq 1499 · tailSeq = 1499 − 899 = 600(900조각 = 1시간).
// 2회차(직전 꼬리 600): scanFrom 600 < H · headSeq 여전히 1499.
// 음성 대조: 같은 H 가 GAP 원장에 있으면 통과한다 → headSeq 1999 · tailSeq = 1999 − 899 = 1100.
func TestG6F18HoleStopsHeadAcrossComputations(t *testing.T) {
	t.Run("홀_앞에서_멈춘다", func(t *testing.T) {
		l := cutoffAt(0, uploadedRun(0, 1499, 4000), pendingRun(1500, 1500, 4000), uploadedRun(1501, 1999, 4000))

		first := mustCompute(t, l, 0)
		if want := (boundary.Window{ScanFrom: 0, HeadSeq: 1499, TailSeq: 600}); first != want {
			t.Fatalf("1회차 Compute = %+v, want %+v", first, want)
		}
		second := mustCompute(t, l, first.TailSeq)
		if want := (boundary.Window{ScanFrom: 600, HeadSeq: 1499, TailSeq: 600}); second != want {
			t.Errorf("2회차 Compute(prevTail=%d) = %+v, want %+v", first.TailSeq, second, want)
		}
	})
	t.Run("GAP_원장에_있으면_통과", func(t *testing.T) {
		hole := pending(1500, 4000)
		hole.IsGap = true
		l := cutoffAt(0, uploadedRun(0, 1499, 4000), []boundary.Row{hole}, uploadedRun(1501, 1999, 4000))

		got := mustCompute(t, l, 600)

		if want := (boundary.Window{ScanFrom: 600, HeadSeq: 1999, TailSeq: 1100}); got != want {
			t.Errorf("Compute(prevTail=600) = %+v, want %+v", got, want)
		}
	})
}

// g6_f19 — session_id 가 NULL 인 행은 settled 가 아니다(목록 미등재 — 머리가 그 앞에서 멈춘다).
// 이 행은 PDT·키·③ uploaded 를 다 갖추고 세션만 비었다. "세션이 있으면 PDT·키도 있다"는 쓰기
// 경로의 성질일 뿐 DB 제약이 아니라서(설계 4.1 — 세 NULL 검사가 NOT VALID CHECK 를 대체한다)
// 술어가 세션을 따로 봐야 한다.
// 컷오프 0 · seq 0..99 uploaded 중 seq 50 만 session_id NULL → headSeq 49 · tailSeq 0(1시간 미만).
func TestG6F19NullSessionIsNotSettled(t *testing.T) {
	orphan := uploaded(50, 4000)
	orphan.SessionID = ""
	l := cutoffAt(0, uploadedRun(0, 49, 4000), []boundary.Row{orphan}, uploadedRun(51, 99, 4000))

	got := mustCompute(t, l, 0)

	if want := (boundary.Window{ScanFrom: 0, HeadSeq: 49, TailSeq: 0}); got != want {
		t.Errorf("Compute = %+v, want %+v", got, want)
	}
}

// f2Ledger 는 설계 4.8.1 전제 수치다 — 스트림 str_7a · 컷오프 12 · 세션 A seq 398..1030(첫 PDT
// 01:07:59Z) · 120초 순단 · 세션 B seq 1031..1297(첫 PDT 01:52:11Z) · 4초 고정. seq 1297 은 ③ 가
// 올라가지 않았고 GAP 원장(upload_stall)에 있다.
func f2Ledger() ledger {
	startA := time.Date(2026, 8, 31, 1, 7, 59, 0, time.UTC)
	startB := time.Date(2026, 8, 31, 1, 52, 11, 0, time.UTC)
	var rows []boundary.Row
	for seq := int64(398); seq <= 1297; seq++ {
		r := boundary.Row{
			Seq:              seq,
			DurationMS:       4000,
			PlaybackS3Key:    fmt.Sprintf("dvr/str_7a/seg/%06d.m4s", seq),
			PlaybackUploaded: true,
		}
		if seq <= 1030 {
			r.SessionID, r.PlaybackPDT = "S-20260831-0107", startA.Add(time.Duration(seq-398)*4*time.Second)
		} else {
			r.SessionID, r.PlaybackPDT = "S-20260831-0152", startB.Add(time.Duration(seq-1031)*4*time.Second)
		}
		rows = append(rows, r)
	}
	gap := &rows[len(rows)-1]
	gap.PlaybackUploaded, gap.IsGap = false, true
	return ledger{cutoff: 12, hasCutoff: true, rows: rows}
}

// g6_f2(경계 몫) — 설계 4.8.1 전제 수치를 경계 산식으로 다시 센다: headSeq 1297(GAP 1개 포함) ·
// tailSeq 398 · 900조각 · 3,600.000초. tailSeq 가 398 인 이유 = suffixMs(398) = 900 × 4000 =
// 3,600,000 ≥ 3.6e6 이고 suffixMs(399) = 3,596,000 < 3.6e6 이다(설계 4.8.1 검산 줄).
// MSN·DISC-SEQ·본문 텍스트는 렌더 몫이라 여기서 만들지 않는다. 직전 꼬리가 없을 때(부트스트랩 —
// scanFrom 은 컷오프 12, 설계 4.8.1 은 창 안만 주므로 이 픽스처에 seq 12..397 은 없다)와 직전 꼬리가
// 398 일 때 답이 같아야 한다.
func TestG6F2BoundaryMatchesDesignFigures(t *testing.T) {
	l := f2Ledger()
	tests := []struct {
		name     string
		prevTail int64
		want     boundary.Window
	}{
		{"부트스트랩", 0, boundary.Window{ScanFrom: 12, HeadSeq: 1297, TailSeq: 398}},
		{"직전_꼬리_398", 398, boundary.Window{ScanFrom: 398, HeadSeq: 1297, TailSeq: 398}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := mustCompute(t, l, tt.prevTail)

			if got != tt.want {
				t.Fatalf("Compute(prevTail=%d) = %+v, want %+v", tt.prevTail, got, tt.want)
			}
			if n, ms := span(l, got); n != 900 || ms != 3_600_000 {
				t.Errorf("창 = %d조각 %dms, want 900조각 3600000ms", n, ms)
			}
		})
	}
}
