package publish

// 정체 사다리 1단계(설계 4.6.2 · 4.6.3 · 4.6.5 · 계획 4.5 A0 결정 5 · 리스크 C3 · 체크리스트 A-4 · A-5 · B-1 · B-2)와
// 겹침 스냅숏(판단 J26) — 입력 값만 보는 평가라 PG 없이 돈다.

import (
	"fmt"
	"log/slog"
	"maps"
	"slices"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// ladderBase 는 사다리 픽스처 행의 PDT 기준 시각이다(seq 0 의 PDT).
var ladderBase = time.Date(2026, 9, 28, 0, 0, 0, 0, time.UTC)

// ladderRow 는 회차 sessionID 의 4초 조각 seq 다 — PDT 는 seq 에 비례하고 ③ 는 uploaded 다.
func ladderRow(sessionID string, seq int64) boundary.Row {
	return boundary.Row{
		Seq: seq, SessionID: sessionID, DurationMS: 4000,
		PlaybackPDT:      ladderBase.Add(time.Duration(seq) * 4 * time.Second),
		PlaybackS3Key:    fmt.Sprintf("dvr/%s/seg/%06d.m4s", fxStream, seq),
		PlaybackUploaded: true,
	}
}

// dueOf6 는 픽스처 행 6 의 dueAt 이다 — PDT(24초) + 길이(4초) + E2E_BUDGET(2초).
var dueOf6 = ladderBase.Add(30 * time.Second)

// rowsSnapshot 은 손으로 세운 경계 입력이다(boundary.Snapshot) — 캐시 대신 테스트가 준다.
type rowsSnapshot struct {
	cutoff    int64
	hasCutoff bool
	rows      []boundary.Row
}

func (s rowsSnapshot) Cutoff() (int64, bool) { return s.cutoff, s.hasCutoff }

func (s rowsSnapshot) RowsFrom(from int64) []boundary.Row {
	i := slices.IndexFunc(s.rows, func(r boundary.Row) bool { return r.Seq >= from })
	if i < 0 {
		return nil
	}
	return s.rows[i:]
}

// stalledRows 는 회차 S 의 행 0..10(컷오프 0)에서 6 만 ③ 미확정인 경계 입력이다 — 머리는 5, 사다리가 보는 행은 6 이다.
func stalledRows() rowsSnapshot {
	s := rowsSnapshot{hasCutoff: true}
	for seq := int64(0); seq <= 10; seq++ {
		s.rows = append(s.rows, ladderRow("S", seq))
	}
	s.rows[6].PlaybackUploaded = false
	return s
}

// ladderInput 은 live 소유 회차 S 의 사다리 입력이다 — 머리 5 · 지금 now. 이 프로세스는 픽스처 첫 조각 시각부터 S 를
// 소유 회차로 봤다. 발행 이력은 없다(테스트가 채운다 — 이력이 있으면 stallAge 는 마지막 발행 성공부터다).
func ladderInput(snap boundary.Snapshot, now time.Time) LadderInput {
	return LadderInput{
		StreamID:   fxStream,
		Snapshot:   snap,
		Window:     boundary.Window{ScanFrom: 0, HeadSeq: 5, TailSeq: 0},
		Owner:      index.RewindSession{SessionID: "S", State: "live", InitUploaded: true},
		Now:        now,
		OwnedSince: ladderBase,
	}
}

// newLadderPublisher 는 사다리만 쓰는 발행자다 — 설계값은 기본값이고 DB · 저장소는 없다.
func newLadderPublisher(logs *logRecorder) *Publisher {
	return &Publisher{opt: DefaultOptions("w-me", fxBaseURL), log: slog.New(logs), now: time.Now}
}

// wantStep 은 k 가 있는 평가(live 소유 회차 · 컷오프 있음)가 행 seq 를 보고 L1 · L2 를 기대대로 냈는지 본다 — L1 · L2
// 가 서지 않아도 Seq 는 k 다. k 가 없는 평가는 영값(LadderStep{})을 시험이 따로 본다.
func wantStep(t *testing.T, got LadderStep, seq int64, expedite, gap bool) {
	t.Helper()
	if got != (LadderStep{Seq: seq, Expedite: expedite, Gap: gap}) {
		t.Errorf("사다리 = %+v, want 행 %d · L1 %v · L2 %v", got, seq, expedite, gap)
	}
}

// L1 · L2 문턱(설계 4.6.3 — 둘 다 ≥) — holdAge = now − dueAt(머리 다음 행)가 0.5초에 닿으면 L1(③ 백오프를 당긴다),
// 2.0초(GAP_HOLD)에 닿으면 L2(GAP 틱)다. 음수(정상 경로 — 아직 실릴 때가 아니다)면 아무것도 하지 않는다.
func TestLadderHoldAgeThresholds(t *testing.T) {
	tests := []struct {
		name          string
		hold          time.Duration
		expedite, gap bool
	}{
		{"음수", -time.Millisecond, false, false},
		{"L1_바로_앞", 499 * time.Millisecond, false, false},
		{"L1_문턱", 500 * time.Millisecond, true, false},
		{"L2_바로_앞", 1999 * time.Millisecond, true, false},
		{"L2_문턱", 2 * time.Second, true, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, step := newLadderPublisher(&logRecorder{}).EvaluateLadder(t.Context(), LadderState{}, ladderInput(stalledRows(), dueOf6.Add(tt.hold)))

			wantStep(t, step, 6, tt.expedite, tt.gap)
		})
	}
}

// holdAge 가 서지 않는 행(설계 4.6.2 · 체크리스트 A-4 2) — 머리 다음 행이 없거나(그 뒤 행이 먼저 있어도 그 행으로
// 재지 않는다), PDT · 길이가 없으면(비귀속 NULL 행 포함) holdAge 가 없어 L1 · L2 가 없다.
func TestLadderNeedsHeadNextRowWithPDTAndLength(t *testing.T) {
	late := dueOf6.Add(time.Hour)
	tests := []struct {
		name string
		snap func() rowsSnapshot
	}{
		{"행_없음", func() rowsSnapshot { s := stalledRows(); s.rows = s.rows[:6]; return s }},
		{"행_없음_뒤_행_있음", func() rowsSnapshot { s := stalledRows(); s.rows = slices.Delete(s.rows, 6, 7); return s }},
		{"비귀속_NULL_행", func() rowsSnapshot {
			s := stalledRows()
			s.rows[6] = boundary.Row{Seq: 6, DurationMS: 4000}
			return s
		}},
		{"길이_0", func() rowsSnapshot { s := stalledRows(); s.rows[6].DurationMS = 0; return s }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, step := newLadderPublisher(&logRecorder{}).EvaluateLadder(t.Context(), LadderState{}, ladderInput(tt.snap(), late))

			wantStep(t, step, 6, false, false)
		})
	}
}

// L2 는 캐시 행이 GAP 적격일 때만 선다(설계 4.6.3 GAP_ELIGIBLE ① ② ⑤ — ③ · ④ 는 GAP 트랜잭션이 DB 에서 본다).
// holdAge 가 서는 행이면 L1 은 적격과 상관없이 선다.
func TestLadderGapNeedsEligibleRow(t *testing.T) {
	late := dueOf6.Add(3 * time.Second)
	tests := []struct {
		name string
		snap func() rowsSnapshot
	}{
		{"회차_없음", func() rowsSnapshot { s := stalledRows(); s.rows[6].SessionID = ""; return s }},
		{"③_키_없음", func() rowsSnapshot { s := stalledRows(); s.rows[6].PlaybackS3Key = ""; return s }},
		{"컷오프_아래", func() rowsSnapshot { s := stalledRows(); s.cutoff = 7; return s }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, step := newLadderPublisher(&logRecorder{}).EvaluateLadder(t.Context(), LadderState{}, ladderInput(tt.snap(), late))

			wantStep(t, step, 6, true, false)
		})
	}
}

// ⑤ 컷오프는 k 를 포함한다(설계 4.6.3 GAP_ELIGIBLE ⑤ · c4-fix3 개정 3) — 되감기 첫 조각 k = 컷오프가 정체하면(창이
// 비었다 — 머리 = 컷오프 − 1) L2 가 선다. 서지 않으면 그 스트림은 목록을 영영 만들지 못한다(원장 쪽 경계는
// TestGapTickStandsAtCutoff).
func TestLadderGapAtCutoff(t *testing.T) {
	snap := stalledRows()
	snap.cutoff = 6
	in := ladderInput(snap, dueOf6.Add(2*time.Second))
	in.Window = boundary.Window{ScanFrom: 6, HeadSeq: 5, TailSeq: 6} // 빈 창(boundary.Compute)

	_, step := newLadderPublisher(&logRecorder{}).EvaluateLadder(t.Context(), LadderState{}, in)

	wantStep(t, step, 6, true, true)
}

// 사다리 대상(계획 4.5 A0 결정 5 · 판단 J32) — 보는 행은 스트림 머리 다음 행이고 그 행의 회차는 따지지 않는다. 접두
// 회차(ending)의 미정착 꼬리도 GAP 대상이다 — 그렇지 않으면 그 홀이 계승 회차 목록의 머리를 영영 막는다. fence 는 live
// 소유 회차의 것이다(GAP 틱은 소유 회차 목록의 틱이다).
func TestLadderTargetsPrefixSessionRow(t *testing.T) {
	snap := stalledRows()
	for i := range snap.rows[:7] {
		snap.rows[i].SessionID = "P"
	}

	_, step := newLadderPublisher(&logRecorder{}).EvaluateLadder(t.Context(), LadderState{}, ladderInput(snap, dueOf6.Add(2*time.Second)))

	wantStep(t, step, 6, true, true)
}

// 1단계(설계 4.6.5 「1단계 L2 = holdAge ≥ 2.0 단독」) — stallAge ≥ D_act(5.5초)로는 L2 가 서지 않는다. 그 갈래와 L2′ 는
// 2단계(F-7 뒤)다.
func TestLadderStageOneGapIgnoresStallAge(t *testing.T) {
	in := ladderInput(stalledRows(), dueOf6.Add(1900*time.Millisecond))
	in.PublishedAt = in.Now.Add(-6 * time.Second) // stallAge 6초 ≥ D_act 5.5초

	_, step := newLadderPublisher(&logRecorder{}).EvaluateLadder(t.Context(), LadderState{}, in)

	wantStep(t, step, 6, true, false)
}

// L4(설계 4.6.3 · 판단 J29 · J30) — 발행 이력이 있는 live 소유 회차의 stallAge(마지막 발행 성공을 받은 시각부터)가
// 갱신 의무 9.0 − T_edge 1.5 = 7.5초에 닿았는데 L2 가 서지 못하면 rewind_publish_stall_seconds ERROR 한 줄이다(속성:
// 정체 초 · L2 가 서지 못한 까닭 · 직전 틱 결과). 같은 정체에는 한 줄이고, 발행이 성공하면 다시 무장한다.
func TestLadderAlarmsOncePerStall(t *testing.T) {
	logs := &logRecorder{}
	pub := newLadderPublisher(logs)
	snap := stalledRows()
	snap.rows = snap.rows[:6] // 머리 다음 행이 아직 없다 — 유입이 멈췄다
	published := ladderBase.Add(time.Minute)
	in := ladderInput(snap, published)
	in.PublishedAt, in.OwnedSince = published, ladderBase
	in.Publish.lastAbort = abortKey{reason: reasonGapTxFailed, stage: gapStageCommit} // last_abort 는 사유만
	var st LadderState
	eval := func(at time.Time) {
		in.Now = at
		st, _ = pub.EvaluateLadder(t.Context(), st, in)
	}

	eval(published.Add(7499 * time.Millisecond))
	if n := len(logs.records(stallLog)); n != 0 {
		t.Fatalf("stallAge 7.499초에 %s %d줄, want 0", stallLog, n)
	}
	eval(published.Add(7500 * time.Millisecond))
	eval(published.Add(9 * time.Second))

	recs := logs.records(stallLog)
	if len(recs) != 1 || recs[0].level != slog.LevelError {
		t.Fatalf("%s %+v, want ERROR 한 줄(같은 정체에는 한 줄)", stallLog, recs)
	}
	want := map[string]string{"stream": fxStream, "session": "S", "seconds": "7.5", "gap": "no_row", "last_abort": "gap_tx_failed"}
	for k, v := range want {
		if got := recs[0].attrs[k]; got != v {
			t.Errorf("속성 %s = %q, want %q", k, got, v)
		}
	}

	in.PublishedAt = published.Add(10 * time.Second) // 발행 성공 — 다시 무장
	eval(in.PublishedAt.Add(7500 * time.Millisecond))
	if n := len(logs.records(stallLog)); n != 2 {
		t.Errorf("발행 성공 뒤 다시 정체한 %s %d줄, want 2", stallLog, n)
	}
}

// L4 의 귀속 판정(판단 J30) — 회차는 있는데 PDT 나 길이가 빠진 행(적격 ②)은 holdAge 도 잴 수 없어 L1 · L2 가 없고,
// 서지 못한 까닭은 incomplete 다(기다리면 서는 holding 이 아니다).
func TestLadderAlarmNamesIncompleteRow(t *testing.T) {
	tests := []struct {
		name string
		edit func(r *boundary.Row)
	}{
		{"PDT_없음", func(r *boundary.Row) { r.PlaybackPDT = time.Time{} }},
		{"길이_0", func(r *boundary.Row) { r.DurationMS = 0 }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			logs := &logRecorder{}
			snap := stalledRows()
			tt.edit(&snap.rows[6])
			in := ladderInput(snap, dueOf6.Add(time.Hour))
			in.PublishedAt = in.Now.Add(-8 * time.Second) // stallAge 8초 — L4

			_, step := newLadderPublisher(logs).EvaluateLadder(t.Context(), LadderState{}, in)

			wantStep(t, step, 6, false, false)
			if recs := logs.records(stallLog); len(recs) != 1 || recs[0].attrs["gap"] != "incomplete" {
				t.Errorf("%s %+v, want 한 줄(gap=incomplete)", stallLog, recs)
			}
		})
	}
}

// L4 의 귀속 판정(판단 J30) — 머리 다음 행이 적격인데 holdAge 가 GAP_HOLD 에 못 미쳤으면 holding(기다리면 선다)이고,
// 컷오프 아래 행이면 before_cutoff 다. 어느 쪽이든 L1 은 선다. 나머지 셋은 no_row(TestLadderAlarmsOncePerStall) ·
// incomplete(TestLadderAlarmNamesIncompleteRow) · unstood(TestLadderAlarmsWhenGapDoesNotStand)가 본다.
func TestLadderAlarmNamesHoldingAndBeforeCutoffRows(t *testing.T) {
	tests := []struct {
		name   string
		cutoff int64
		hold   time.Duration // 행 6 의 holdAge
		gap    string
	}{
		{"GAP_HOLD_앞", 0, time.Second, "holding"}, // 적격 · holdAge 1초 < GAP_HOLD 2초
		{"컷오프_아래", 7, time.Minute, "before_cutoff"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			logs := &logRecorder{}
			snap := stalledRows()
			snap.cutoff = tt.cutoff
			in := ladderInput(snap, dueOf6.Add(tt.hold))
			in.PublishedAt = in.Now.Add(-8 * time.Second) // stallAge 8초 — L4

			_, step := newLadderPublisher(logs).EvaluateLadder(t.Context(), LadderState{}, in)

			wantStep(t, step, 6, true, false)
			if recs := logs.records(stallLog); len(recs) != 1 || recs[0].level != slog.LevelError || recs[0].attrs["gap"] != tt.gap {
				t.Errorf("%s %+v, want ERROR 한 줄(gap=%s)", stallLog, recs, tt.gap)
			}
		})
	}
}

// L2 가 서는 동안 L4 는 GAP 틱에 차례를 준다(설계 4.6.3 「위 중 아무것도 성립 못 함」 · c4-fix3 개정 1 · kty #77) —
// 사다리는 결정의 이력으로 GAP 틱의 실패를 추측하지 않는다. 결과가 돌아왔는데 서지 않은 GAP 틱의 후보(UnstoodGapSeq)가
// 입력에 없으면, 같은 행에 L2 를 몇 번 결정해도 L4 가 없다 — 아직 결과가 오지 않았거나 나가지 못한 GAP 틱은 서지
// 않았다는 사실이 아니다.
func TestLadderAlarmYieldsToGap(t *testing.T) {
	logs := &logRecorder{}
	pub := newLadderPublisher(logs)
	first := dueOf6.Add(3 * time.Second) // 행 6 은 적격이고 holdAge 3초 — L2
	in := ladderInput(stalledRows(), first)
	in.PublishedAt = first.Add(-8 * time.Second) // stallAge 8초 — L2 가 서지 못했다면 L4 다
	var st LadderState

	for i := range 5 {
		in.Now = first.Add(time.Duration(i) * time.Second)
		var step LadderStep
		st, step = pub.EvaluateLadder(t.Context(), st, in)
		wantStep(t, step, 6, true, true)
	}

	if recs := logs.records(stallLog); len(recs) != 0 {
		t.Errorf("서지 않았다는 사실 없이 같은 행에 L2 를 다섯 번 결정한 %s %+v, want 없음", stallLog, recs)
	}
}

// 서지 않은 GAP(c4-fix3 개정 1 · kty #77 · 설계 4.6.3 L4 「L2 가 서지 못함」) — 결과가 돌아온 직전 GAP 틱이 행 k 에서
// 서지 않았다는 사실(UnstoodGapSeq = k — 예: 캐시는 적격인데 DB 가 부적격이었다 · GAP 트랜잭션이 실패했다 · 준비
// 단계에서 끝났다)이 입력에 있으면, L2 를 결정한 평가도 L4 를 누르지 않고 gap=unstood 로 남긴다 — 첫 평가부터다. 같은
// 정체에는 한 줄이고, 발행이 성공하면 다시 무장한다.
func TestLadderAlarmsWhenGapDoesNotStand(t *testing.T) {
	logs := &logRecorder{}
	pub := newLadderPublisher(logs)
	first := dueOf6.Add(3 * time.Second) // 행 6 은 적격이고 holdAge 3초 — L2
	unstood := int64(6)
	in := ladderInput(stalledRows(), first)
	in.PublishedAt = first.Add(-8 * time.Second) // stallAge 8초 — L4 문턱 7.5초를 넘었다
	in.UnstoodGapSeq = &unstood
	var st LadderState
	eval := func(at time.Time) LadderStep {
		in.Now = at
		var step LadderStep
		st, step = pub.EvaluateLadder(t.Context(), st, in)
		return step
	}

	if step := eval(first); !step.Gap || len(logs.records(stallLog)) != 1 {
		t.Fatalf("첫 평가 = %+v · %s %+v, want L2 · 한 줄(사실이 입력에 있으면 첫 평가부터)", step, stallLog, logs.records(stallLog))
	}
	eval(first.Add(time.Second))
	recs := logs.records(stallLog)
	if len(recs) != 1 || recs[0].level != slog.LevelError || recs[0].attrs["gap"] != "unstood" {
		t.Fatalf("%s %+v, want ERROR 한 줄(gap=unstood · 같은 정체에는 한 줄)", stallLog, recs)
	}
	in.PublishedAt = first.Add(time.Minute) // 발행 성공 — 다시 무장
	eval(in.PublishedAt.Add(8 * time.Second))
	if n := len(logs.records(stallLog)); n != 2 {
		t.Errorf("발행 성공 뒤 다시 정체한 %s %d줄, want 2", stallLog, n)
	}
}

// 서지 않은 GAP 의 경보 조건(c4-fix3 개정 1) — UnstoodGapSeq 는 L2 를 결정한 그 행 k 일 때만 L4 를 연다. k 와 다르면
// 누른다 — 머리가 넘어가 k 가 뒤 행이 됐든(새 행의 GAP 틱이 차례다) 값이 k 보다 크든. k 여도 stallAge 가 문턱(갱신 의무
// 9.0 − T_edge 1.5 = 7.5초) 아래면 경보가 없다.
func TestLadderUnstoodGapNeedsSameRowAndStall(t *testing.T) {
	now := dueOf6.Add(time.Minute) // 행 6 · 7 모두 holdAge 가 GAP_HOLD 를 넘었다 — L2
	tests := []struct {
		name    string
		head    int64 // 머리 — 사다리가 보는 행 k 는 head + 1 이다
		unstood int64 // UnstoodGapSeq
		stall   time.Duration
		alarm   bool
	}{
		{"같은_행_문턱", 5, 6, 7500 * time.Millisecond, true},
		{"같은_행_문턱_앞", 5, 6, 7499 * time.Millisecond, false},
		{"머리가_넘어감", 6, 6, time.Minute, false}, // 행 6 의 GAP 이 섰고 행 7 이 정체
		{"k_보다_큰_값", 5, 7, time.Minute, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			logs := &logRecorder{}
			unstood := tt.unstood
			in := ladderInput(stalledRows(), now)
			in.Window.HeadSeq = tt.head
			in.PublishedAt, in.UnstoodGapSeq = now.Add(-tt.stall), &unstood

			_, step := newLadderPublisher(logs).EvaluateLadder(t.Context(), LadderState{}, in)

			wantStep(t, step, tt.head+1, true, true)
			if recs := logs.records(stallLog); (len(recs) == 1) != tt.alarm {
				t.Errorf("UnstoodGapSeq %d · k %d · stallAge %v 의 %s %+v, want L4 %v", tt.unstood, tt.head+1, tt.stall, stallLog, recs, tt.alarm)
			}
		})
	}
}

// 서지 않았다는 사실은 L2 를 결정한 행에만 쓴다(c4-fix3 개정 1) — 캐시가 그 행을 부적격으로 보면(③ 키가 빠졌다 — 적격
// ②) L2 가 없고, L4 의 gap 속성은 캐시의 까닭(incomplete)이다.
func TestLadderUnstoodGapNeedsDecidedGap(t *testing.T) {
	logs := &logRecorder{}
	unstood := int64(6)
	snap := stalledRows()
	snap.rows[6].PlaybackS3Key = ""
	in := ladderInput(snap, dueOf6.Add(time.Minute))
	in.PublishedAt, in.UnstoodGapSeq = in.Now.Add(-8*time.Second), &unstood

	_, step := newLadderPublisher(logs).EvaluateLadder(t.Context(), LadderState{}, in)

	wantStep(t, step, 6, true, false)
	if recs := logs.records(stallLog); len(recs) != 1 || recs[0].attrs["gap"] != "incomplete" {
		t.Errorf("%s %+v, want 한 줄(gap=incomplete — 캐시의 까닭)", stallLog, recs)
	}
}

// 발행 이력(체크리스트 B-1 「r46 미확인 5」 · 판단 J29) — L4 를 끄는 조건은 앞의 비귀속(NULL) 행이 아니라 소유 회차의
// 발행 이력 없음이다. ⓐ 한 번도 발행하지 못한 회차는 L2 도(적격 ② 거짓) L4 도 없다 — 첫 발행 전에는 stallAge 가 서지
// 않는다. ⓑ 발행 이력이 있는 회차 앞에 NULL 행이 서면(귀속 하한 거부 — live 회차가 있어도 생긴다) L2 는 서지 못하고
// stallAge 7.5초에 L4 가 뜬다. 이력은 이 프로세스의 발행 성공이거나 화해가 되살린 P 다 — 되살린 P 만 있으면 stallAge
// 는 처음 소유 회차로 본 시각부터 잰다(L4 줄의 정체 초로 본다).
func TestLadderAlarmNeedsPublishHistory(t *testing.T) {
	owned := ladderBase
	tests := []struct {
		name    string
		history func(in *LadderInput)
		seconds []string // L4 줄의 정체 초(없으면 L4 가 없다)
	}{
		{"ⓐ_발행_이력_없음", func(*LadderInput) {}, nil},
		{"ⓑ_발행_성공", func(in *LadderInput) { in.PublishedAt = owned.Add(time.Second) }, []string{"3599"}},
		{"ⓑ_화해가_되살린_P", func(in *LadderInput) { in.Publish.Prev = &Manifest{ETag: `"p"`} }, []string{"3600"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			logs := &logRecorder{}
			snap := stalledRows()
			snap.rows[6] = boundary.Row{Seq: 6, DurationMS: 4000} // 비귀속 NULL 행
			in := ladderInput(snap, owned.Add(time.Hour))
			in.OwnedSince = owned
			tt.history(&in)

			_, step := newLadderPublisher(logs).EvaluateLadder(t.Context(), LadderState{}, in)

			wantStep(t, step, 6, false, false)
			var got []string
			for _, r := range logs.records(stallLog) {
				got = append(got, r.attrs["seconds"])
			}
			if !slices.Equal(got, tt.seconds) {
				t.Errorf("%s 의 정체 초 %v, want %v", stallLog, got, tt.seconds)
			}
		})
	}
}

// 사다리는 live 소유 회차에서만 돈다(계획 부기 9 · 리스크 C3 · 뮤테이션 16) — ending 소유 회차는 머리 다음 행이
// 정체해도 할 일이 영값이고(k 가 없다), 발행 이력이 있어 stallAge 가 7.5초를 넘어도 L4 ERROR 가 없다(ENDLIST 가 없는
// M4 에서 끝난 목록이 경보를 쏟아 내지 않게).
func TestLadderOffForEndingOwner(t *testing.T) {
	tests := []struct {
		name string
		snap func() rowsSnapshot
	}{
		{"머리_다음_행_정체", stalledRows}, // live 였다면 L1 · L2
		{"머리_다음_행_없음", func() rowsSnapshot { s := stalledRows(); s.rows = s.rows[:6]; return s }}, // live 였다면 L4
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			logs := &logRecorder{}
			in := ladderInput(tt.snap(), dueOf6.Add(time.Hour))
			in.Owner.State = "ending"
			in.PublishedAt = dueOf6

			_, step := newLadderPublisher(logs).EvaluateLadder(t.Context(), LadderState{}, in)

			if step != (LadderStep{}) {
				t.Errorf("ending 소유 회차의 사다리 = %+v, want 영값(k 가 없다)", step)
			}
			for _, r := range logs.records(stallLog) {
				if r.level == slog.LevelError {
					t.Errorf("ending 소유 회차의 L4 ERROR %+v, want 없음", r)
				}
			}
		})
	}
}

// 컷오프가 없는 스트림(되감기를 제공하지 않는다)에는 사다리가 없다 — 머리 다음 행이 정체해도 k 가 없어 할 일이
// 영값이다.
func TestLadderOffWithoutCutoff(t *testing.T) {
	snap := stalledRows()
	snap.hasCutoff = false

	_, step := newLadderPublisher(&logRecorder{}).EvaluateLadder(t.Context(), LadderState{}, ladderInput(snap, dueOf6.Add(time.Hour)))

	if step != (LadderStep{}) {
		t.Errorf("컷오프가 없는 스트림의 사다리 = %+v, want 영값(k 가 없다)", step)
	}
}

// C3 ending WARN(리스크 C3 · 체크리스트 A-5 · 판단 J34) — 소유 회차가 live 가 아니게 되어(ending · ended) 발행이 멈춘
// 사실을 rewind_publish_stall_seconds WARN 으로 회차마다 한 번 남긴다. 속성은 스트림 · 회차 · 그 state · 정체 초(마지막
// 발행 성공과 처음 소유 회차로 본 시각 가운데 늦은 쪽부터)다. 같은 회차의 두 번째 관측은 0 줄이다. 다른 회차가 멈추면
// 다시 남긴다. 한 번도 발행하지 않은 회차는 멈출 발행이 없었으니 남기지 않는다.
func TestLadderWarnsEndingSessionOnce(t *testing.T) {
	logs := &logRecorder{}
	pub := newLadderPublisher(logs)
	var st LadderState
	none := func(*LadderInput) {}
	published := func(in *LadderInput) { in.PublishedAt = dueOf6 }                 // 정체 초 60(발행 성공부터)
	restored := func(in *LadderInput) { in.Publish.Prev = &Manifest{ETag: `"p"`} } // 정체 초 90(되살린 P 만)
	observe := func(session, state string, history func(in *LadderInput)) {
		in := ladderInput(stalledRows(), dueOf6.Add(time.Minute))
		in.Owner = index.RewindSession{SessionID: session, State: state, InitUploaded: true}
		history(&in)
		st, _ = pub.EvaluateLadder(t.Context(), st, in)
	}

	observe("S", "ending", published)
	observe("S", "ending", published)
	observe("U", "ending", none)
	observe("T", "ending", published)
	observe("V", "ended", restored)

	var got []map[string]string
	for _, r := range logs.records(stallLog) {
		if r.level != slog.LevelWarn {
			t.Errorf("C3 로그 %+v, want WARN", r)
		}
		got = append(got, r.attrs)
	}
	want := []map[string]string{
		{"stream": fxStream, "session": "S", "state": "ending", "seconds": "60"},
		{"stream": fxStream, "session": "T", "state": "ending", "seconds": "60"},
		{"stream": fxStream, "session": "V", "state": "ended", "seconds": "90"},
	}
	if !slices.EqualFunc(got, want, maps.Equal) {
		t.Errorf("C3 WARN 속성 %v, want %v(회차마다 한 번 · 발행 이력 없는 U 는 없음)", got, want)
	}
}

// 정상 경로(설계 4.6.2 · 6.4 음성 대조) — 머리 다음 행이 아직 실릴 때가 아니고(holdAge 음수) 방금 발행했으면 사다리는
// 아무것도 하지 않는다.
func TestLadderQuietOnNormalPath(t *testing.T) {
	logs := &logRecorder{}
	in := ladderInput(stalledRows(), dueOf6.Add(-time.Second))
	in.PublishedAt = in.Now.Add(-4 * time.Second)

	st, step := newLadderPublisher(logs).EvaluateLadder(t.Context(), LadderState{}, in)

	wantStep(t, step, 6, false, false)
	if len(logs.recs) != 0 || st != (LadderState{}) {
		t.Errorf("정상 경로의 로그 %+v · 상태 %+v, want 없음 · 그대로", logs.recs, st)
	}
}

// 평가의 순수성(체크리스트 A-4 8 · c4-fix1 개정 5) — 판정(evaluateLadder)은 입력 값과 설계값만 보고 새 상태 · 할 일 ·
// 남길 신호를 값으로 돌려준다. 같은 입력이면 몇 번 불러도 같은 값이다. EvaluateLadder 는 그 신호를 로그로 남기는 얇은
// 래퍼라, 돌려준 상태를 갈아 끼우지 않고 같은 상태로 다시 부르면 같은 신호가 다시 남는다(되풀이 가드는 상태에 있다).
func TestLadderVerdictIsPure(t *testing.T) {
	snap := stalledRows()
	snap.rows = snap.rows[:6] // 머리 다음 행이 없다 — L2 는 서지 못한다
	in := ladderInput(snap, ladderBase.Add(time.Hour))
	in.PublishedAt = in.Now.Add(-8 * time.Second) // stallAge 8초 — L4 가 선다
	opt := DefaultOptions("w-me", fxBaseURL)

	st1, step1, sig1 := evaluateLadder(opt, LadderState{}, in)
	st2, step2, sig2 := evaluateLadder(opt, LadderState{}, in)

	if sig1 == nil || sig2 == nil || sig1.level != slog.LevelError || !slices.Equal(sig1.attrs, sig2.attrs) ||
		st1 != st2 || step1 != step2 {
		t.Fatalf("판정 두 번 = (%+v, %+v, %+v) · (%+v, %+v, %+v), want 같은 값 · L4 ERROR 신호", st1, step1, sig1, st2, step2, sig2)
	}
	logs := &logRecorder{}
	pub := newLadderPublisher(logs)
	pub.EvaluateLadder(t.Context(), LadderState{}, in)
	pub.EvaluateLadder(t.Context(), LadderState{}, in)
	if n := len(logs.records(stallLog)); n != 2 {
		t.Errorf("같은 상태로 두 번 부른 %s %d줄, want 2(돌려준 상태를 갈아 끼우지 않으면 같은 신호가 다시 남는다)", stallLog, n)
	}
}

// 겹침 스냅숏(판단 J26 · 계획 3절 boundary.Snapshot 구현) — GAP 후보 k 를 GAP 원장에 든 것처럼(settled) 덮는 읽기
// 덮개다. 그 위에서 잰 창은 k 를 지나 머리가 뻗는다. 밑 스냅숏의 행은 바꾸지 않는다(RowsFrom 의 슬라이스는 읽기만 한다
// — 캐시 뷰 그 자체다). 컷오프는 밑 스냅숏 그대로다.
func TestWithGapOverlaysOneRow(t *testing.T) {
	base := stalledRows()

	overlay := WithGap(base, 6)

	w, ok := boundary.Compute(overlay, 0)
	if !ok || w.HeadSeq != 10 {
		t.Errorf("겹침 창 = (%+v, %v), want 머리 10(k 를 지나 뻗는다)", w, ok)
	}
	if w, _ := boundary.Compute(base, 0); w.HeadSeq != 5 {
		t.Errorf("밑 스냅숏의 창 머리 = %d, want 5(k 앞에서 멈춘다)", w.HeadSeq)
	}
	rows := overlay.RowsFrom(5)
	if len(rows) != 6 || rows[0].IsGap || !rows[1].IsGap || rows[1].Seq != 6 || rows[2].IsGap {
		t.Errorf("겹침 행 %+v, want seq 6 만 GAP", rows)
	}
	if rows := overlay.RowsFrom(7); len(rows) != 4 || slices.ContainsFunc(rows, func(r boundary.Row) bool { return r.IsGap }) {
		t.Errorf("k 뒤부터의 겹침 행 %+v, want 밑 스냅숏 그대로 4행(GAP 없음)", rows)
	}
	if base.rows[6].IsGap {
		t.Error("밑 스냅숏의 행 6 이 GAP 으로 바뀌었다, want 그대로")
	}
	if cutoff, ok := overlay.Cutoff(); cutoff != 0 || !ok {
		t.Errorf("겹침 컷오프 = (%d, %v), want (0, true)", cutoff, ok)
	}
}
