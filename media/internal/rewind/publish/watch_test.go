package publish

// 감시 · 적재의 발행 쪽(POK-195 M4 PR ⓒ 커밋 5 — 설계 4.1 (a) (c) · 계획 4.5 A2 결정 11 · A3 결정 3 · 체크리스트 419
// A-6 3 · 4 · A-7 · 판단 J39 · J42 · J46 · 장부 421 P-5 · P-7 · P-9). 판정 · 값은 순수 함수 · 캐시 메서드 · 작업이
// 돌려주고 로그는 *Publisher 의 얇은 래퍼 둘(ReportLoad · ReportWatch)이 남긴다 — 루프가 부르는 것은 커밋 7 이다.
// 순수 시험이라 PG 없이 돈다(발행자는 접속하지 않는 풀로 만든다).

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
)

// reporter 는 로그 래퍼만 쓰는 발행자다 — 풀은 접속하지 않는 주소라(pgxpool.New 는 게으르다) DB 를 보지 않는다.
func reporter(t *testing.T) (*Publisher, *logRecorder) {
	t.Helper()
	pool, err := pgxpool.New(context.Background(), "postgres://user@127.0.0.1:1/none")
	if err != nil {
		t.Fatalf("풀 생성 실패(접속은 하지 않는다): %v", err)
	}
	t.Cleanup(pool.Close)
	logs := &logRecorder{}
	return newPublisher(t, pool, newFakeStore(t), "w-me", logs), logs
}

// lines 는 로그 기록을 「등급 키 속성=값 …」 한 줄씩으로 적는다 — 속성은 이름 순이다.
func lines(logs *logRecorder) []string {
	logs.mu.Lock()
	defer logs.mu.Unlock()
	var out []string
	for _, r := range logs.recs {
		var kv []string
		for k, v := range r.attrs {
			kv = append(kv, k+"="+v)
		}
		slices.Sort(kv)
		out = append(out, strings.Join(append([]string{r.level.String(), r.msg}, kv...), " "))
	}
	return out
}

// signalLine 은 (c) 판정 신호 하나를 「감시 번호 등급 속성=값 …」 으로 적는다 — 속성은 이름 순이다.
func signalLine(round int, s absentSignal) string {
	var kv []string
	for i := 0; i+1 < len(s.attrs); i += 2 {
		kv = append(kv, fmt.Sprintf("%v=%v", s.attrs[i], s.attrs[i+1]))
	}
	slices.Sort(kv)
	return strings.Join(append([]string{fmt.Sprint(round), s.level.String()}, kv...), " ")
}

// (c) 컷오프 부재 판정(설계 4.1 (c) · 9.1 s4_alarm_continuous · s4_signal_grades · 판단 J42 · 장부 421 P-9) — 전이만
// 남긴다: 들어올 때 WARN(value 1) · 연속 39 감시까지 줄 0 · 40번째 감시에 ERROR(value 1) 한 번 · 사라지면
// INFO(value 0). 계속 부재인 동안 점멸하지 않는다(들어옴 WARN 한 번). 감시가 실패한 틱(부르지 않음)은 세지도
// 풀지도 않고, 들어옴 → 사라짐 → 다시 들어옴은 0 부터 센다. 속성은 스트림 · 연속 감시 수 · 마지막 조각 시각이다.
func TestCutoffAbsentJudgment(t *testing.T) {
	opt := DefaultOptions("w-1", fxBaseURL)
	a := index.WatchAbsent{StreamID: "a", LastWall: fxDay}
	b := index.WatchAbsent{StreamID: "b", LastWall: fxDay.Add(time.Minute)}
	rounds := map[int][]index.WatchAbsent{40: {a, b}, 41: nil, 42: {a, b}, 43: {b}, 44: {a, b}}
	var got []string
	st := CutoffAbsentState{}

	for round := 1; round <= 44; round++ {
		absent, ok := rounds[round]
		if !ok {
			absent = []index.WatchAbsent{a}
		}
		if round == 41 { // 감시 실패 — 루프가 판정을 부르지 않는다
			continue
		}
		next, sigs := judgeCutoffAbsent(opt, st, absent)
		for _, s := range sigs {
			got = append(got, signalLine(round, s))
		}
		st = next
	}

	last := fxDay.String()
	want := []string{
		"1 WARN last_wall=" + last + " stream=a ticks=1 value=1",
		"40 ERROR last_wall=" + last + " stream=a ticks=40 value=1",
		"40 WARN last_wall=" + fxDay.Add(time.Minute).String() + " stream=b ticks=1 value=1",
		"43 INFO stream=a ticks=41 value=0",
		"44 WARN last_wall=" + last + " stream=a ticks=1 value=1",
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("(c) 신호 =\n%s\nwant\n%s", strings.Join(got, "\n"), strings.Join(want, "\n"))
	}
}

// 적재 결과 래퍼(장부 421 P-7 · 판단 J46 · 계획 4.5 A3 결정 3 · B #7) — 적재가 행 수 상한에 닿았으면 ERROR
// rewind_ledger_load_degraded(reason=row_cap) 한 줄, 적재 작업이 실패했으면 ERROR 같은 키(reason=load_failed) 한
// 줄이다(시도마다). 드리프트 까닭으로 연 적재가 적용됐으면 WARN cache_drift_repaired(kind=head) 한 줄이다(P-5 —
// driftRepaired 는 CompleteLoad 의 돌려준 값). 부른 쪽 ctx(루프 수명)가 끝났으면 멈춘 작업이라 아무것도 남기지 않는다.
func TestReportLoadSignals(t *testing.T) {
	loaded := index.RewindLedger{CutoffSeq: 60, HasCutoff: true, FloorSeq: 61,
		Rows: []index.RewindRow{{Seq: 61}, {Seq: 62}}}
	capped := loaded
	capped.Capped = true
	stopped, cancel := context.WithCancel(context.Background())
	cancel()
	for _, tt := range []struct {
		name  string
		ctx   context.Context
		out   LoadOutcome
		drift bool
		want  []string
	}{
		{"적재", context.Background(), LoadOutcome{Token: 3, RewindLedger: loaded}, false, nil},
		{"상한_도달", context.Background(), LoadOutcome{Token: 3, RewindLedger: capped}, false,
			[]string{"ERROR rewind_ledger_load_degraded floor=61 reason=row_cap rows=2 stream=str"}},
		{"적재_실패", context.Background(), LoadOutcome{Token: 3, Err: errors.New("pg 끊김")}, false,
			[]string{"ERROR rewind_ledger_load_degraded err=pg 끊김 reason=load_failed stream=str"}},
		{"드리프트_수리", context.Background(), LoadOutcome{Token: 3, RewindLedger: loaded}, true,
			[]string{"WARN cache_drift_repaired cutoff=60 floor=61 kind=head stream=str"}},
		{"상한_도달_드리프트_수리", context.Background(), LoadOutcome{Token: 3, RewindLedger: capped}, true, []string{
			"ERROR rewind_ledger_load_degraded floor=61 reason=row_cap rows=2 stream=str",
			"WARN cache_drift_repaired cutoff=60 floor=61 kind=head stream=str",
		}},
		{"멈춘_작업", stopped, LoadOutcome{Token: 3, Err: context.Canceled}, false, nil},
	} {
		t.Run(tt.name, func(t *testing.T) {
			pub, logs := reporter(t)

			pub.ReportLoad(tt.ctx, fxStream, tt.out, tt.drift)

			if got := lines(logs); !reflect.DeepEqual(got, tt.want) {
				t.Errorf("로그 = %q, want %q", got, tt.want)
			}
		})
	}
}

// 감시 결과 래퍼(장부 421 P-7 · 계획 4.5 A2 결정 11 · 판단 J42) — 감시 화해가 돌려준 목록마다 WARN
// cache_drift_repaired(kind=init_uploaded · revoked) 한 줄이고, (c) 판정의 전이를 rewind_cutoff_absent 로 남긴 뒤 새
// 상태를 돌려준다(루프가 갈아 끼운다). 드리프트 재료는 여기서 로그하지 않는다 — 수리(적재 적용) 전이다(P-5).
func TestReportWatchSignals(t *testing.T) {
	pub, logs := reporter(t)
	w := index.RewindWatch{
		Drift:  []index.WatchDrift{{StreamID: fxStream, NextSeq: 7, CutoffSeq: 1, HasCutoff: true}},
		Absent: []index.WatchAbsent{{StreamID: "a", LastWall: fxDay}},
	}

	st := pub.ReportWatch(context.Background(), CutoffAbsentState{}, w,
		[]index.InitRepair{{StreamID: fxStream, SessionID: "A", Revoked: true}, {StreamID: fxStream, SessionID: "B"}})
	pub.ReportWatch(context.Background(), st, index.RewindWatch{}, nil)

	want := []string{
		"WARN cache_drift_repaired kind=init_uploaded revoked=true session=A stream=str",
		"WARN cache_drift_repaired kind=init_uploaded revoked=false session=B stream=str",
		"WARN rewind_cutoff_absent last_wall=" + fxDay.String() + " stream=a ticks=1 value=1",
		"INFO rewind_cutoff_absent stream=a ticks=1 value=0",
	}
	if got := lines(logs); !reflect.DeepEqual(got, want) {
		t.Errorf("로그 =\n%s\nwant\n%s", strings.Join(got, "\n"), strings.Join(want, "\n"))
	}
}

// 드리프트 신호 표(장부 421 P-5 · 이월 절 2 · 체크리스트 419 B-1) — 루프가 할 일(커밋 7)을 흉내 내 캐시와 두 래퍼를
// 차례로 부른다. cache_drift_repaired(kind=head)는 드리프트 까닭으로 연 적재가 CompleteLoad 로 적용됐을 때 한
// 줄이다 — 적용이 곧 교정이고 앞뒤 머리를 견주지 않는다. 감지(두 감시 연속 불일치 → 요구 등재) 때는 줄 0, 적재가
// 실패하면 그 줄 0 · ERROR reason=load_failed 한 줄, 토큰이 달라 버린 결과도 줄 0(까닭은 다시 연 적재로 이어진다),
// 드리프트가 아닌 요구의 적재도 줄 0 이다. 요구가 겹쳐 합쳐져도 까닭은 남는다.
func TestDriftRepairedLoggedOnlyAfterRepair(t *testing.T) {
	pub, logs := reporter(t)
	ctx := context.Background()
	f := soloFixture(9)
	ledger := index.RewindLedger{CutoffSeq: 0, HasCutoff: true, Sessions: []index.RewindSession{{SessionID: "S",
		State: "live", InitUploaded: true, TargetDuration: 6}}}
	for _, r := range f.rows[:6] { // 머리 5 까지만 캐시에 있다 — DB 는 6 도 settled
		ledger.Rows = append(ledger.Rows, index.RewindRow(r))
	}
	c := &cache.Cache{}
	c.CompleteLoad(fxStream, c.BeginLoad(fxStream, nil), ledger)
	drifted := index.RewindWatch{Drift: []index.WatchDrift{{StreamID: fxStream, NextSeq: 6, CutoffSeq: 0, HasCutoff: true,
		Next: index.RewindRow(f.rows[6]), HasNext: true}}}
	watch := func() { // 감시 한 번 — 대조 · 화해 · 감시 래퍼
		c.AuditDrift(drifted.Drift)
		pub.ReportWatch(ctx, CutoffAbsentState{}, drifted, c.ReconcileWatch(drifted.Live))
	}
	step := func(name string, want int) {
		t.Helper()
		if got := len(logs.records(driftRepairedLog)); got != want {
			t.Errorf("%s 뒤 cache_drift_repaired %d줄, want %d", name, got, want)
		}
	}

	watch()
	watch()
	d := c.DemandedLoads()
	step("감지(두 감시 연속 불일치)", 0)
	if len(d) != 1 {
		t.Fatalf("감지 뒤 요구 = %+v, want 한 건", d)
	}
	c.DemandLoad(fxStream, nil) // 다른 요구가 겹쳤다 — 합쳐져도 까닭은 남는다
	c.DemandedLoads()

	failed := c.BeginLoad(fxStream, d[0].Hint)
	pub.ReportLoad(ctx, fxStream, LoadOutcome{Token: uint64(failed), Err: errors.New("pg 끊김")}, false)
	c.DemandLoad(fxStream, nil) // 판단 J46 — 적재 중을 유지하고 요구 목록에 다시
	step("적재 실패", 0)
	if !c.Loading(fxStream) || len(logs.records(loadDegradedLog)) != 1 {
		t.Errorf("적재 실패 뒤 (적재 중, load_failed 줄) = (%v, %d), want (참, 1)", c.Loading(fxStream), len(logs.records(loadDegradedLog)))
	}

	retry := c.DemandedLoads()
	stale := c.BeginLoad(fxStream, retry[0].Hint)
	fresh := c.BeginLoad(fxStream, retry[0].Hint) // 다시 연 적재(예: 로그 넘침)
	full := ledger
	full.Rows = nil
	for _, r := range f.rows {
		full.Rows = append(full.Rows, index.RewindRow(r))
	}
	applied, drift := c.CompleteLoad(fxStream, stale, full)
	pub.ReportLoad(ctx, fxStream, LoadOutcome{Token: uint64(stale), RewindLedger: full}, applied && drift)
	step("토큰이 달라 버린 결과", 0)

	applied, drift = c.CompleteLoad(fxStream, fresh, full)
	pub.ReportLoad(ctx, fxStream, LoadOutcome{Token: uint64(fresh), RewindLedger: full}, applied && drift)
	step("드리프트 까닭 적재의 적용", 1)

	c.DemandLoad(fxStream, nil) // 드리프트가 아닌 요구
	next := c.BeginLoad(fxStream, nil)
	applied, drift = c.CompleteLoad(fxStream, next, full)
	pub.ReportLoad(ctx, fxStream, LoadOutcome{Token: uint64(next), RewindLedger: full}, applied && drift)
	step("드리프트가 아닌 요구의 적재", 1)
}

// 적재 실패 갈래(판단 J46 · 장부 421 P-7 · 체크리스트 419 B-1) — 적재 작업이 DB 오류로 끝나면 그 스트림은 적재 중을
// 유지하고(발행 · 사다리가 서지 않는다) 요구 목록에 다시 오른다(힌트를 잇는다). 그 동안의 유일한 신호는 시도마다
// ERROR rewind_ledger_load_degraded(reason=load_failed) 한 줄이다 — 반복 주기(30초 감시)는 커밋 7 이다.
func TestLoadFailureKeepsLoadingAndLogsEachAttempt(t *testing.T) {
	pub, logs := reporter(t)
	c := &cache.Cache{}
	hint := int64(40)

	for attempt := 1; attempt <= 2; attempt++ {
		token := c.BeginLoad(fxStream, &hint)
		pub.ReportLoad(context.Background(), fxStream, LoadOutcome{Token: uint64(token), Err: errors.New("시한 초과")}, false)
		c.DemandLoad(fxStream, nil)

		d := c.DemandedLoads()
		if !c.Loading(fxStream) || len(d) != 1 || d[0].Hint == nil || *d[0].Hint != 40 {
			t.Errorf("시도 %d 뒤 (적재 중, 요구) = (%v, %+v), want (참, [%s · 힌트 40])", attempt, c.Loading(fxStream), d, fxStream)
		}
		if recs := logs.records(loadDegradedLog); len(recs) != attempt || recs[attempt-1].level != slog.LevelError ||
			recs[attempt-1].attrs["reason"] != "load_failed" {
			t.Errorf("시도 %d 뒤 load_failed 줄 = %+v, want 시도마다 ERROR 한 줄", attempt, recs)
		}
	}
}
