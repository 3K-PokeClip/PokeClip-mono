package session

import (
	"context"
	"log/slog"
	"strings"
	"testing"
	"time"
)

// wall 은 픽스처의 기준 벽시계다. 값 자체에 의미는 없고 경계값 산술의 원점이다.
var wall = time.Date(2026, 9, 2, 10, 0, 0, 0, time.UTC)

// signalLog 는 신호(WARN) 발화를 잡아 두는 테스트용 슬로그 핸들러다.
// 신호는 "무징후 금지"(설계 S4)의 실물이라 값 판정과 같은 급으로 단언한다.
type signalLog struct{ warns []string }

func (s *signalLog) Enabled(context.Context, slog.Level) bool { return true }
func (s *signalLog) Handle(_ context.Context, rec slog.Record) error {
	if rec.Level == slog.LevelWarn {
		s.warns = append(s.warns, rec.Message)
	}
	return nil
}
func (s *signalLog) WithAttrs([]slog.Attr) slog.Handler { return s }
func (s *signalLog) WithGroup(string) slog.Handler      { return s }

func (s *signalLog) fired(name string) bool {
	for _, w := range s.warns {
		if w == name {
			return true
		}
	}
	return false
}

// newTestRegistry 는 신호를 잡는 레지스트리다. 슬랙 값은 케이스마다 다르므로 인자로 받는다.
func newTestRegistry(floorSlack, obsFresh time.Duration) (*Registry, *signalLog) {
	sig := &signalLog{}
	return New(Options{
		FloorSlack: floorSlack,
		ObsFresh:   obsFresh,
		Log:        slog.New(sig),
	}), sig
}

func TestDecideSplitsWhenRoundedDurationExceedsTargetDuration(t *testing.T) {
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	live := &liveSession{
		id: "S-old", startedAt: wall, targetDuration: 6, discontinuityBase: 3,
	}
	in := Input{
		StreamID:     "demo",
		Seq:          42,
		StartWallUTC: wall.Add(time.Minute),
		DurationMS:   6500, // math.Round(6.5) = 7 > 6 — 정수 나눗셈이면 6 이라 분할되지 않는다.
		Op:           OpenOrCurrent,
	}

	d := r.decide(live, in, wall.Add(time.Minute))

	if d.Outcome != OutcomeOpenFresh {
		t.Errorf("갈래 = %v, 기대 = %v", d.Outcome, OutcomeOpenFresh)
	}
	if d.BaseSessionID != "S-old" {
		t.Errorf("기저 세션 = %q, 기대 = %q (현 live 를 조회 없이 그대로 쓴다)", d.BaseSessionID, "S-old")
	}
	if d.SessionID != "" {
		t.Errorf("귀속 세션 = %q, 기대 = \"\" (새 세션은 ⑸ 가 만든 뒤에야 정해진다)", d.SessionID)
	}
}

func TestDecideAttributesToCurrentSessionWhenRoundedDurationFitsTargetDuration(t *testing.T) {
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	live := &liveSession{id: "S-old", startedAt: wall, targetDuration: 6}
	in := Input{
		StreamID:     "demo",
		Seq:          42,
		StartWallUTC: wall.Add(time.Minute),
		DurationMS:   6400, // math.Round(6.4) = 6 — TD 6 을 넘지 않는다.
		Op:           OpenOrCurrent,
	}

	d := r.decide(live, in, wall.Add(time.Minute))

	if d.Outcome != OutcomeCurrent {
		t.Errorf("갈래 = %v, 기대 = %v", d.Outcome, OutcomeCurrent)
	}
	if d.SessionID != "S-old" || d.BaseSessionID != "S-old" {
		t.Errorf("세션 = (귀속 %q, 기저 %q), 기대 = 둘 다 %q", d.SessionID, d.BaseSessionID, "S-old")
	}
}

// 귀속 하한(설계 5.4.1 ⑴)은 **모든 연산에 항상** 걸린다. 경계는 포함(≥)이다.
func TestDecideRejectsAttributionBelowSessionFloor(t *testing.T) {
	cases := []struct {
		name    string
		offset  time.Duration // 세션 started_at 대비 조각의 start_wall_utc
		want    Outcome
		signals bool
	}{
		{name: "여유 안(−0.5초)", offset: -500 * time.Millisecond, want: OutcomeCurrent},
		{name: "여유 경계(−1초, 포함)", offset: -time.Second, want: OutcomeCurrent},
		{name: "여유 밖(−1.5초)", offset: -1500 * time.Millisecond, want: OutcomeNone, signals: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r, sig := newTestRegistry(time.Second, 30*time.Second)
			live := &liveSession{id: "S-old", startedAt: wall, targetDuration: 6}
			in := Input{
				StreamID:     "demo",
				Seq:          42,
				StartWallUTC: wall.Add(tc.offset),
				DurationMS:   4000,
				Op:           OpenOrCurrent,
			}

			d := r.decide(live, in, wall)

			if d.Outcome != tc.want {
				t.Errorf("갈래 = %v, 기대 = %v", d.Outcome, tc.want)
			}
			if tc.want == OutcomeNone && (d.SessionID != "" || d.BaseSessionID != "") {
				t.Errorf("거부인데 세션이 실렸다 (귀속 %q, 기저 %q) — ⑷⑸ 를 건너뛰어야 한다",
					d.SessionID, d.BaseSessionID)
			}
			if got := sig.fired("session_floor_rejected"); got != tc.signals {
				t.Errorf("session_floor_rejected 발화 = %v, 기대 = %v", got, tc.signals)
			}
		})
	}
}

func TestDecideOpensWhenNoLiveSessionForOpenOrCurrent(t *testing.T) {
	r, sig := newTestRegistry(time.Second, 30*time.Second)
	in := Input{StreamID: "demo", Seq: 1, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}

	d := r.decide(nil, in, wall)

	if d.Outcome != OutcomeOpen {
		t.Errorf("갈래 = %v, 기대 = %v (live 부재면 TD 판정이 성립하지 않고 개시로 간다)", d.Outcome, OutcomeOpen)
	}
	if len(sig.warns) != 0 {
		t.Errorf("신호 %v 가 떴다 — 정상 개시는 무징후다", sig.warns)
	}
}

// CurrentOnly 의 세션 부재는 **거부가 아니다** — 값만 비고 신호는 없다(설계 6.5.2 네 갈래).
func TestDecideLeavesSessionUnsetWhenCurrentOnlyAndNoLiveSession(t *testing.T) {
	r, sig := newTestRegistry(time.Second, 30*time.Second)
	in := Input{StreamID: "demo", Seq: 1, StartWallUTC: wall, DurationMS: 4000, Op: CurrentOnly}

	d := r.decide(nil, in, wall)

	if d.Outcome != OutcomeNone {
		t.Errorf("갈래 = %v, 기대 = %v", d.Outcome, OutcomeNone)
	}
	if len(sig.warns) != 0 {
		t.Errorf("신호 %v 가 떴다 — CurrentOnly 의 세션 부재는 거부가 아니라 평시 상태다", sig.warns)
	}
}

// CurrentOnly 에도 현 live 가 있으면 귀속한다 — "언제나 carrier NULL"이 아니다(계획 4.1).
func TestDecideAttributesToCurrentSessionWhenCurrentOnlyAndLiveSessionExists(t *testing.T) {
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	live := &liveSession{id: "S-old", startedAt: wall, targetDuration: 6}
	in := Input{StreamID: "demo", Seq: 9, StartWallUTC: wall.Add(time.Minute), DurationMS: 4000, Op: CurrentOnly}

	d := r.decide(live, in, wall.Add(time.Minute))

	if d.Outcome != OutcomeCurrent || d.SessionID != "S-old" {
		t.Errorf("갈래 = %v · 귀속 = %q, 기대 = %v · %q", d.Outcome, d.SessionID, OutcomeCurrent, "S-old")
	}
}

// 연산은 영값을 갖지 않는다 — 안 채운 필드가 조용히 어느 갈래로 접히면 배선 누락이 새어 나간다.
//
// tx 에 nil 을 넘기는 것이 단언의 일부다: 거부가 **조회보다 앞**이라 SQL 이 한 번도 나가지
// 않는다(배선 결함에 DB 왕복을 쓰지 않는다).
func TestDecideRejectsUnspecifiedOp(t *testing.T) {
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	in := Input{StreamID: "demo", Seq: 1, StartWallUTC: wall, DurationMS: 4000}

	if _, err := r.Decide(context.Background(), nil, in, wall); err == nil {
		t.Fatal("연산 영값인데 에러가 없다 — 누락이 조용히 새면 안 된다")
	}
}

// corroborated 는 ⓐ2 자격을 전부 충족하는 관측이다. 케이스는 여기서 한 항씩만 무너뜨린다.
func corroborated(now time.Time) Observation {
	return Observation{
		Publishing:     true,
		EpochKnown:     true,
		ObservedAt:     now.Add(-5 * time.Second),
		EpochStartedAt: now.Add(-time.Minute),
		EpochSlack:     0,
	}
}

func TestDecideOpensWhenCorroboratedAndNoLiveSession(t *testing.T) {
	r, sig := newTestRegistry(time.Second, 30*time.Second)
	in := Input{
		StreamID:     "demo",
		Seq:          1,
		StartWallUTC: wall,
		DurationMS:   4000,
		Op:           CurrentOrOpenIfCorroborated,
		Obs:          corroborated(wall),
	}

	d := r.decide(nil, in, wall)

	if d.Outcome != OutcomeOpen {
		t.Errorf("갈래 = %v, 기대 = %v (ⓐ2 자격 충족은 개시한다)", d.Outcome, OutcomeOpen)
	}
	// 갈래만 맞고 새 세션 행의 값이 안 실리면 Open 이 거부한다 —
	// "연다"는 판정은 여기까지 봐야 한다(갈래만 보면 개시 불가를 개시로 읽는다).
	if d.plan == nil {
		t.Error("개시 결정에 새 세션 행의 값이 실리지 않았다 — Open 이 거부하므로 세션이 열리지 않는다")
	}
	if len(sig.warns) != 0 {
		t.Errorf("신호 %v 가 떴다 — 정상 개시는 무징후다", sig.warns)
	}
}

// ⓐ2 자격(설계 6.5.2)은 다섯 항의 곱이다. 한 항씩 무너뜨려 갈래와 신호를 함께 잰다.
// 에폭 여유(EpochSlack)는 값 축이라 0 과 10초 두 값으로 잰다 — 0 이면 모호 구간이 공집합이다.
func TestDecideFoldsToCurrentOnlyWhenCorroborationFails(t *testing.T) {
	const slack = 10 * time.Second
	epoch := wall.Add(-time.Minute) // corroborated() 가 쓰는 에폭 시각과 같다

	cases := []struct {
		name       string
		mutate     func(*Input)
		want       Outcome
		wantSignal bool
	}{
		{
			name:   "송출 중이 아니다",
			mutate: func(in *Input) { in.Obs.Publishing = false },
			want:   OutcomeNone,
		},
		{
			name:   "에폭 미상(fail-closed)",
			mutate: func(in *Input) { in.Obs.EpochKnown = false },
			want:   OutcomeNone,
		},
		{
			name:   "에폭 하한 위반 — 여유 0",
			mutate: func(in *Input) { in.StartWallUTC = epoch.Add(-time.Millisecond) },
			want:   OutcomeNone, // 여유 0 이면 모호 구간이 공집합이라 신호 없이 접힌다
		},
		{
			name: "에폭 하한 위반 — 여유 10초",
			mutate: func(in *Input) {
				in.Obs.EpochSlack = slack
				in.StartWallUTC = epoch.Add(-slack - time.Second)
			},
			want: OutcomeNone,
		},
		{
			name: "모호 구간 안 — 여유 10초",
			mutate: func(in *Input) {
				in.Obs.EpochSlack = slack
				in.StartWallUTC = epoch.Add(-5 * time.Second)
			},
			want:       OutcomeNone,
			wantSignal: true,
		},
		{
			name: "모호 구간 하단 경계(−여유, 포함)",
			mutate: func(in *Input) {
				in.Obs.EpochSlack = slack
				in.StartWallUTC = epoch.Add(-slack)
			},
			want:       OutcomeNone,
			wantSignal: true,
		},
		{
			name: "모호 구간 상단 경계(= 에폭 시각, 제외)",
			mutate: func(in *Input) {
				in.Obs.EpochSlack = slack
				in.StartWallUTC = epoch
			},
			want: OutcomeOpen,
		},
		{
			name:   "관측이 낡았다(31초)",
			mutate: func(in *Input) { in.Obs.ObservedAt = wall.Add(-31 * time.Second) },
			want:   OutcomeNone,
		},
		{
			name:   "관측 신선도 경계(30초, 포함)",
			mutate: func(in *Input) { in.Obs.ObservedAt = wall.Add(-30 * time.Second) },
			want:   OutcomeOpen,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r, sig := newTestRegistry(time.Second, 30*time.Second)
			in := Input{
				StreamID:     "demo",
				Seq:          1,
				StartWallUTC: wall,
				DurationMS:   4000,
				Op:           CurrentOrOpenIfCorroborated,
				Obs:          corroborated(wall),
			}
			tc.mutate(&in)

			d := r.decide(nil, in, wall)

			if d.Outcome != tc.want {
				t.Errorf("갈래 = %v, 기대 = %v", d.Outcome, tc.want)
			}
			if got := sig.fired("session_epoch_ambiguous"); got != tc.wantSignal {
				t.Errorf("session_epoch_ambiguous 발화 = %v, 기대 = %v", got, tc.wantSignal)
			}
			if sig.fired("session_floor_rejected") {
				t.Error("session_floor_rejected 가 떴다 — 귀속 하한은 현 live 세션이 있을 때의 축이다")
			}
		})
	}
}

func TestSessionIDDerivesFromStreamOpeningWallAndSeq(t *testing.T) {
	got := sessionID("demo-stream", time.Date(2026, 8, 31, 1, 7, 3, 0, time.UTC), 42)

	if want := "S-20260831-010703-demo-stream-42"; got != want {
		t.Errorf("session_id = %q, 기대 = %q", got, want)
	}
}

// 같은 순간이면 표기 시간대와 무관하게 같은 id 여야 한다 — 파생이 결정적이라는 뜻이다.
func TestSessionIDNormalizesToUTC(t *testing.T) {
	utc := time.Date(2026, 8, 31, 1, 7, 3, 0, time.UTC)
	kst := utc.In(time.FixedZone("KST", 9*60*60))

	if got, want := sessionID("demo", kst, 7), sessionID("demo", utc, 7); got != want {
		t.Errorf("session_id = %q, 기대 = %q (같은 순간은 같은 id)", got, want)
	}
}

// 로거를 안 준 레지스트리도 신호를 낼 수 있어야 한다 — 없으면 첫 WARN 에서 nil 참조로 죽는다.
func TestNewFallsBackToDefaultLoggerWhenNoneGiven(t *testing.T) {
	r := New(Options{FloorSlack: time.Second, ObsFresh: 30 * time.Second})
	live := &liveSession{id: "S-old", startedAt: wall, targetDuration: 6}
	in := Input{
		StreamID: "demo", Seq: 1, StartWallUTC: wall.Add(-time.Hour), // 하한 위반 → WARN 발화
		DurationMS: 4000, Op: OpenOrCurrent,
	}

	d := r.decide(live, in, wall)

	if d.Outcome != OutcomeNone {
		t.Errorf("갈래 = %v, 기대 = %v", d.Outcome, OutcomeNone)
	}
}

// 갈래는 로그·실패 메시지에 숫자가 아니라 이름으로 보여야 한다(index.InsertOutcome 이디엄).
func TestOutcomeStringNamesEveryBranch(t *testing.T) {
	cases := map[Outcome]string{
		OutcomeNone:      "none",
		OutcomeCurrent:   "current",
		OutcomeOpen:      "open",
		OutcomeOpenFresh: "open_fresh",
	}
	for o, want := range cases {
		if got := o.String(); got != want {
			t.Errorf("Outcome(%d).String() = %q, 기대 = %q", int(o), got, want)
		}
	}
	if got := Outcome(9).String(); got == "" {
		t.Error("모르는 값도 무언가는 찍혀야 한다")
	}
}

// 아래 두 가드는 **DB 를 건드리기 전**이라 tx 가 nil 이어도 성립한다 —
// nil 을 넘기는 것 자체가 "여기서 SQL 이 나가지 않는다"는 단언이다.

func TestOpenRejectsDecisionThatIsNotAnOpening(t *testing.T) {
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	live := &liveSession{id: "S-old", startedAt: wall, targetDuration: 6}
	in := Input{StreamID: "demo", Seq: 3, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}
	d := r.decide(live, in, wall)
	if d.Outcome != OutcomeCurrent {
		t.Fatalf("전제 실패: 갈래 = %v", d.Outcome)
	}

	if _, err := r.Open(context.Background(), nil, d, wall); err == nil {
		t.Error("계속 갈래인데 개시가 통과했다 — 세션 표는 무변경이어야 한다")
	}
}

// 개시 갈래인데 재료가 없는 것은 **결정부 배선 결함**이다 — 갈래가 아니라는 말로 덮지 않는다
// (덮으면 로그가 "개시 갈래가 아닌 결정(open)"이라는 자기모순을 찍고 원인을 가린다).
func TestOpenNamesMissingOpenPlanAsWiringDefect(t *testing.T) {
	r, _ := newTestRegistry(time.Second, 30*time.Second)

	// 결정부가 plan 을 빠뜨린 상태를 그대로 만든다(C1 이 실제로 만들었던 값).
	_, err := r.Open(context.Background(), nil, Decision{Outcome: OutcomeOpen}, wall)

	if err == nil {
		t.Fatal("재료 없는 개시 결정이 통과했다")
	}
	if strings.Contains(err.Error(), "개시 갈래가 아닌") {
		t.Errorf("메시지가 자기모순이다(갈래는 open 인데 '개시 갈래가 아닌'이라 말한다): %v", err)
	}
}

// first_pdt 는 개시 1회로 굳고 60일 잔존한다 — 영값이 장부에 박히면 소급 정정이 불가능하다.
func TestOpenRejectsZeroFirstPDT(t *testing.T) {
	r, _ := newTestRegistry(time.Second, 30*time.Second)
	in := Input{StreamID: "demo", Seq: 3, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}
	d := r.decide(nil, in, wall)
	if d.Outcome != OutcomeOpen {
		t.Fatalf("전제 실패: 갈래 = %v", d.Outcome)
	}

	if _, err := r.Open(context.Background(), nil, d, time.Time{}); err == nil {
		t.Error("first_pdt 영값인데 개시가 통과했다")
	}
}

// 개시 세션의 target_duration = max(6, round(dur/1000)) — 설계 4.9.1 공식 그대로다.
// 정수 나눗셈이면 6500ms 가 6 이 되어 EXTINF 6.5 조각이 TD 6 세션에 들어간다(M4 가 발행을 막는다).
func TestOpenTargetDurationRoundsAndFloorsAtSix(t *testing.T) {
	cases := []struct {
		durationMS int32
		want       int32
	}{
		{durationMS: 4000, want: 6}, // 하한
		{durationMS: 6499, want: 6}, // 반올림 내림
		{durationMS: 6500, want: 7}, // 반올림 올림 — 정수 나눗셈이면 6
		{durationMS: 8000, want: 8}, // 하한 위
		{durationMS: 12000, want: 12},
	}
	for _, tc := range cases {
		if got := openTargetDuration(tc.durationMS); got != tc.want {
			t.Errorf("openTargetDuration(%d) = %d, 기대 = %d", tc.durationMS, got, tc.want)
		}
	}
}
