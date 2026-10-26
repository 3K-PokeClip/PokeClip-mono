package session

// 의도적 종료 적용(4D — POK-196 M6-3a 커밋 ② · 설계 M6 2.3 · 3.3 · 3.4 · 9절 D10 · D12 · D53 · D55 · D64 · D69 · D73)의
// 실 PG 시험. 함수 칸(CloseRound · EnqueueStarted)은 가짜로 끼워 「같은 트랜잭션에서 불렸는가」만 잰다 — 실제 회차
// 닫힘 · 완결 평가는 M6-3b, started outbox 는 M6-8 몫이다(계획 4.R 「앞 몫 · 뒤 몫」).

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// intentSlots 는 함수 칸 가짜다 — 부른 회차와, 부른 순간 그 회차의 남은 행이 모두 닫혔는지(같은 트랜잭션 안에서 읽음)를 적는다.
type intentSlots struct {
	mu          sync.Mutex
	closed      []string
	closedShut  []bool
	started     []string
	startedAt   []time.Time
	startedFrom []string
}

func (s *intentSlots) options() IntentOptions {
	return IntentOptions{
		ShortConn: 4 * time.Second,
		Slack:     8 * time.Second,
		CloseRound: func(ctx context.Context, tx pgx.Tx, round string) error {
			var shut bool
			if err := tx.QueryRow(ctx, `
SELECT COALESCE(pg_catalog.bool_and(round_closed_at IS NOT NULL), false)
  FROM stream_sessions WHERE round_id = $1`, round).Scan(&shut); err != nil {
				return err
			}
			s.mu.Lock()
			defer s.mu.Unlock()
			s.closed = append(s.closed, round)
			s.closedShut = append(s.closedShut, shut)
			return nil
		},
		EnqueueStarted: func(_ context.Context, _ pgx.Tx, round, ingestStreamID string, at time.Time) error {
			s.mu.Lock()
			defer s.mu.Unlock()
			s.started = append(s.started, round)
			s.startedFrom = append(s.startedFrom, ingestStreamID)
			s.startedAt = append(s.startedAt, at)
			return nil
		},
	}
}

// stopAt 은 시험의 멈춘 시각(StoppedAt)이다 — 연결은 한 시간 전에 시작했다.
var stopAt = wall

func endAt(stream string) IntentionalEnd {
	return IntentionalEnd{StreamID: stream, StoppedAt: stopAt, ConnStartedAt: stopAt.Add(-time.Hour)}
}

func apply(t *testing.T, pool *pgxpool.Pool, e IntentionalEnd, o IntentOptions) IntentionalResult {
	t.Helper()
	res, err := ApplyIntentionalEnd(context.Background(), pool, e, o)
	if err != nil {
		t.Fatalf("ApplyIntentionalEnd 실패: %v", err)
	}
	return res
}

func markOf(t *testing.T, pool *pgxpool.Pool, id string) *time.Time {
	t.Helper()
	return roundOf(t, pool, id).stopMarkedAt
}

// 미적용 갈래 다섯(3.4 후보 규칙 · 효과 표) — 쓰지 않고 Skip 이 사유다. 잡는 결함: 후보가 없는데 엉뚱한 세션에 표식 /
// 짧은 연결(시작 실패한 송출)의 신호로 회차를 끊음 / 다른 회차가 멈춘 시각 근처에 시작했는데 아무 쪽에나 적용 /
// 닫힌 회차를 다시 엶.
func TestApplyIntentionalEndSkipsWithoutWriting(t *testing.T) {
	for _, c := range []struct {
		name  string
		seed  func(t *testing.T, pool *pgxpool.Pool)
		e     IntentionalEnd
		want  string
		wantT string
	}{
		{"no_session", func(*testing.T, *pgxpool.Pool) {}, endAt("demo"), skipNoSession, ""},
		{"conn_mismatch", func(t *testing.T, pool *pgxpool.Pool) {
			seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-T", startedAt: stopAt.Add(-3 * time.Hour)})
			seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-3*time.Hour))
		}, endAt("demo"), skipConnMismatch, ""},
		{"short_conn", func(t *testing.T, pool *pgxpool.Pool) {
			seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "live", roundID: "S-T", startedAt: stopAt.Add(-3 * time.Second)})
			seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-3*time.Second))
		}, IntentionalEnd{StreamID: "demo", StoppedAt: stopAt, ConnStartedAt: stopAt.Add(-3 * time.Second)}, skipShortConn, "S-T"},
		{"ambiguous", func(t *testing.T, pool *pgxpool.Pool) {
			seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-T", startedAt: stopAt.Add(-time.Hour)})
			seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
			seedLedger(t, pool, ledgerRow{id: "S-O", streamID: "demo", state: "live", roundID: "S-O", startedAt: stopAt.Add(3 * time.Second)})
		}, endAt("demo"), skipAmbiguous, "S-T"},
		{"round_closed", func(t *testing.T, pool *pgxpool.Pool) {
			seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-T", roundClosedAt: stopAt, startedAt: stopAt.Add(-time.Hour)})
			seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
		}, endAt("demo"), skipRoundClosed, "S-T"},
		{"M6_전_NULL_회차는_닫힌_것과_같다", func(t *testing.T, pool *pgxpool.Pool) {
			seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ending", endReason: "offline", startedAt: stopAt.Add(-time.Hour)})
			seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
		}, endAt("demo"), skipRoundClosed, "S-T"},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			c.seed(t, pool)
			slots := &intentSlots{}

			res := apply(t, pool, c.e, slots.options())

			if res.Effect != "" || res.Skip != c.want || res.SessionID != c.wantT {
				t.Errorf("결과 = %+v, want Skip %q · SessionID %q · Effect 빈 값", res, c.want, c.wantT)
			}
			if c.wantT != "" {
				if got := roundOf(t, pool, c.wantT); got.stopMarkedAt != nil || strOf(got.endReason) == endReasonIntentional {
					t.Errorf("미적용인데 %s 를 썼다: 표식 %v · 사유 %s", c.wantT, got.stopMarkedAt, strOf(got.endReason))
				}
			}
			if len(slots.closed)+len(slots.started) != 0 {
				t.Errorf("미적용인데 함수 칸을 불렀다: close %v · started %v", slots.closed, slots.started)
			}
		})
	}
}

// D10 — 연결 중간 TD 분할로 열린 live 도 후보다. T 는 「조각이 연결 창과 겹치고 started_at ≤ StoppedAt」 인 세션 중
// started_at 최대다 → 분할로 열린 live X2 에 표식(marked). 두 번째 4D 는 첫 표식을 덮지 않는다(COALESCE).
// 변이 = 후보에 `started_at ≤ ConnStartedAt + 8초` 조건 복귀 → X1 이 뽑혀 marked 가 아니어서 FAIL.
func TestApplyIntentionalEndMarksLiveOpenedByMidConnectionSplit(t *testing.T) {
	pool := newTestPool(t)
	seedLedger(t, pool, ledgerRow{id: "S-X1", streamID: "demo", state: "ending", endReason: endReasonTDExceeded, roundID: "S-X1", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-X1", 1, stopAt.Add(-time.Hour))
	seedLedger(t, pool, ledgerRow{id: "S-X2", streamID: "demo", state: "live", roundID: "S-X1", startedAt: stopAt.Add(-30 * time.Minute)})
	seedSegment(t, pool, "demo", "S-X2", 2, stopAt.Add(-5*time.Second))
	slots := &intentSlots{}

	res := apply(t, pool, endAt("demo"), slots.options())

	if res.Effect != effectMarked || res.SessionID != "S-X2" || res.RoundID != "S-X1" {
		t.Fatalf("결과 = %+v, want marked · S-X2 · 회차 S-X1", res)
	}
	got := roundOf(t, pool, "S-X2")
	if got.state != "live" || got.stopMarkedAt == nil || !got.stopMarkedAt.Equal(stopAt) {
		t.Errorf("S-X2 (state, 표식) = (%s, %v), want (live, %v) — 4D 는 live 를 끝내지 않는다", got.state, got.stopMarkedAt, stopAt)
	}
	later := endAt("demo")
	later.StoppedAt = stopAt.Add(2 * time.Second)
	later.ConnStartedAt = later.StoppedAt.Add(-time.Hour)
	apply(t, pool, later, slots.options())
	if m := markOf(t, pool, "S-X2"); m == nil || !m.Equal(stopAt) {
		t.Errorf("두 번째 4D 뒤 표식 = %v, want 첫 표식 %v 유지(COALESCE)", m, stopAt)
	}
	if len(slots.closed) != 0 {
		t.Errorf("marked 에서 회차 닫힘을 불렀다: %v", slots.closed)
	}
}

// D53 — 4D 직후 그 연결의 마지막 조각이 늦게(10초 뒤) INSERT 돼도 옛 세션에 붙는다. 표식만 쓰고 live 를 끝내지 않기
// 때문이다. 변이 = 4D 가 live 를 바로 ending → 늦은 조각이 새 세션을 열어 FAIL.
func TestLateLastSegmentAfterIntentionalEndStaysOnOldSession(t *testing.T) {
	pool := newTestPool(t)
	seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-X", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-X", 1, stopAt.Add(-6*time.Second))
	slots := &intentSlots{}
	if res := apply(t, pool, endAt("demo"), slots.options()); res.Effect != effectMarked {
		t.Fatalf("결과 = %+v, want marked", res)
	}
	r, _ := newRoundRegistry()
	late := Input{StreamID: "demo", Seq: 2, StartWallUTC: stopAt.Add(-2 * time.Second), DurationMS: 2000, Op: OpenOrCurrent}
	withTx(t, pool, func(tx pgx.Tx) {
		d, err := r.Decide(context.Background(), tx, late, stopAt.Add(10*time.Second))
		if err != nil {
			t.Fatalf("결정 실패: %v", err)
		}
		if d.Outcome != OutcomeCurrent || d.SessionID != "S-X" {
			t.Errorf("늦은 마지막 조각 = (%v, %q), want (current, S-X) — 유령 회차를 열면 안 된다", d.Outcome, d.SessionID)
		}
	})
}

// 효과 표 upgraded — 회차 최신인 ending · ended T 의 사유를 intentional 로 바꾸고 표식을 쓰고 같은 트랜잭션에서 회차
// 닫힘(CloseRound 칸)을 부른다. ended 는 사유만 바꾸고 동결 · VOD 는 그대로다. started 는 부르지 않는다.
func TestApplyIntentionalEndUpgradesLatestEndedSession(t *testing.T) {
	for _, state := range []string{"ending", "ended"} {
		t.Run(state, func(t *testing.T) {
			pool := newTestPool(t)
			vod := "none"
			if state == "ended" {
				vod = "frozen"
			}
			seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: state, endReason: "offline", vodState: vod, roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
			seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
			slots := &intentSlots{}

			res := apply(t, pool, endAt("demo"), slots.options())

			if res.Effect != effectUpgraded || res.SessionID != "S-T" || res.RoundID != "S-R" || res.RevokeSession != "" {
				t.Fatalf("결과 = %+v, want upgraded · S-T · S-R", res)
			}
			got := roundOf(t, pool, "S-T")
			if got.state != state || strOf(got.endReason) != endReasonIntentional || got.stopMarkedAt == nil || !got.stopMarkedAt.Equal(stopAt) {
				t.Errorf("S-T = (%s, %s, %v), want (%s, intentional, %v)", got.state, strOf(got.endReason), got.stopMarkedAt, state, stopAt)
			}
			var vodState string
			if err := pool.QueryRow(context.Background(), `SELECT vod_state FROM stream_sessions WHERE session_id='S-T'`).Scan(&vodState); err != nil || vodState != vod {
				t.Errorf("vod_state = (%q, %v), want %q 그대로", vodState, err, vod)
			}
			if len(slots.closed) != 1 || slots.closed[0] != "S-R" || len(slots.started) != 0 {
				t.Errorf("함수 칸 close %v · started %v, want close [S-R] · started 없음", slots.closed, slots.started)
			}
		})
	}
}

// 효과 표 round_split(D55 넷째 갈래 앞 몫) — 4D 가 새 송출 B 의 합류 뒤 닿음. B(started_at ≥ StoppedAt 최소)와 그 뒤 세션이
// 새 회차 B 로 옮겨 가고 표식이 지워지며, R 의 남는 행이 닫힌다. 같은 트랜잭션에서 B started 칸과 R 의 CloseRound 칸을
// 부르고, CloseRound 를 부르는 순간 R 은 이미 닫혀 있다(완결 평가만 남는다). 변이 = 분리 tx 의 CloseRound 호출 제거 → FAIL.
func TestApplyIntentionalEndSplitsRoundAfterLateJoin(t *testing.T) {
	pool := newTestPool(t)
	seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ended", endReason: "offline", vodState: "frozen", roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
	seedLedger(t, pool, ledgerRow{id: "S-B", streamID: "demo", state: "ending", endReason: endReasonTDExceeded, roundID: "S-R", startedAt: stopAt.Add(20 * time.Second), stopMarkedAt: stopAt.Add(-time.Minute)})
	seedLedger(t, pool, ledgerRow{id: "S-C", streamID: "demo", state: "live", roundID: "S-R", startedAt: stopAt.Add(40 * time.Second)})
	slots := &intentSlots{}

	res := apply(t, pool, endAt("demo"), slots.options())

	if res.Effect != effectRoundSplit || res.SessionID != "S-T" || res.RoundID != "S-R" || res.RevokeSession != "" {
		t.Fatalf("결과 = %+v, want round_split · S-T · S-R · 해제 없음", res)
	}
	for _, id := range []string{"S-B", "S-C"} {
		if got := roundOf(t, pool, id); strOf(got.roundID) != "S-B" || got.stopMarkedAt != nil {
			t.Errorf("%s (round_id, 표식) = (%s, %v), want (S-B, NULL)", id, strOf(got.roundID), got.stopMarkedAt)
		}
	}
	var closed bool
	if err := pool.QueryRow(context.Background(), `SELECT round_closed_at IS NOT NULL FROM stream_sessions WHERE session_id='S-T'`).Scan(&closed); err != nil || !closed {
		t.Errorf("R 의 남는 행 닫힘 = (%v, %v), want true", closed, err)
	}
	if len(slots.started) != 1 || slots.started[0] != "S-B" || slots.startedFrom[0] != "demo" || !slots.startedAt[0].Equal(stopAt.Add(20*time.Second)) {
		t.Errorf("started 칸 = %v · %v · %v, want [S-B] · [demo] · [B 시작]", slots.started, slots.startedFrom, slots.startedAt)
	}
	if len(slots.closed) != 1 || slots.closed[0] != "S-R" || !slots.closedShut[0] {
		t.Errorf("CloseRound 칸 = %v(닫힌 뒤 %v), want [S-R] · R 을 직접 닫은 뒤", slots.closed, slots.closedShut)
	}
}

// round_split 의 계승 해제 요청(D55 해제 갈래의 계산 몫) — 새 회차 첫 세션 B 가 R 에 남는 세션을 계승 중이고 간선 양 끝이
// 열린 사슬(vod none)이면 분리하고 RevokeSession = B 다. 해제 작업 자체(Head 화해 → P2′)는 루프 몫이다(M6-3b).
func TestApplyIntentionalEndRequestsRevokeWhenSplitCutsInheritance(t *testing.T) {
	pool := newTestPool(t)
	seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
	seedLedger(t, pool, ledgerRow{id: "S-B", streamID: "demo", state: "live", roundID: "S-R", inherits: "S-T", startedAt: stopAt.Add(20 * time.Second)})
	slots := &intentSlots{}

	res := apply(t, pool, endAt("demo"), slots.options())

	if res.Effect != effectRoundSplit || res.RevokeSession != "S-B" {
		t.Fatalf("결과 = %+v, want round_split · RevokeSession S-B", res)
	}
	if got := roundOf(t, pool, "S-B"); strOf(got.roundID) != "S-B" || strOf(got.inherits) != "S-T" {
		t.Errorf("S-B (round_id, 계승) = (%s, %s), want (S-B, S-T — 간선은 루프의 해제 작업이 끊는다)", strOf(got.roundID), strOf(got.inherits))
	}
}

// D64 (가) — 사슬 A–B 가 이미 닫혔는데(vod freezing) 늦은 4D 가 그 사슬 안에서 회차를 나누려 함 → round_split_blocked ·
// round_id · vod_* 그대로 · 함수 칸 없음. 변이 = 분리 가능 재검증 제거 → B 의 round_id 가 바뀌어 FAIL.
func TestApplyIntentionalEndBlocksSplitInsideClosedChain(t *testing.T) {
	pool := newTestPool(t)
	seedLedger(t, pool, ledgerRow{id: "S-A", streamID: "demo", state: "ending", endReason: "offline", vodState: "freezing", roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-A", 1, stopAt.Add(-5*time.Second))
	seedLedger(t, pool, ledgerRow{id: "S-B", streamID: "demo", state: "ending", endReason: "offline", vodState: "freezing", roundID: "S-R", inherits: "S-A", startedAt: stopAt.Add(20 * time.Second)})
	slots := &intentSlots{}

	res := apply(t, pool, endAt("demo"), slots.options())

	if res.Effect != "" || res.Skip != skipRoundSplitBlocked {
		t.Fatalf("결과 = %+v, want round_split_blocked", res)
	}
	for _, id := range []string{"S-A", "S-B"} {
		var round, vod string
		var closed bool
		if err := pool.QueryRow(context.Background(), `SELECT round_id, vod_state, round_closed_at IS NOT NULL FROM stream_sessions WHERE session_id=$1`, id).
			Scan(&round, &vod, &closed); err != nil || round != "S-R" || vod != "freezing" || closed {
			t.Errorf("%s = (%s, %s, 닫힘 %v, %v), want (S-R, freezing, false) 그대로", id, round, vod, closed, err)
		}
	}
	if len(slots.closed)+len(slots.started) != 0 {
		t.Errorf("미적용인데 함수 칸을 불렀다: %v · %v", slots.closed, slots.started)
	}
}

// D69 (나) — round_split 이 소속 잠금을 기다리는 동안 TD 분할 개시가 커밋 → 분리는 소속 행을 다시 골라 새 live C 까지
// 보고 옮긴다(C.round_id = B).
func TestApplyIntentionalEndMovesSessionOpenedWhileWaiting(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
	seedLedger(t, pool, ledgerRow{id: "S-B", streamID: "demo", state: "live", roundID: "S-R", startedAt: stopAt.Add(20 * time.Second)})
	r, _ := newRoundRegistry()

	openTx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("개시 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = openTx.Rollback(ctx) }()
	split := Input{StreamID: "demo", Seq: 9, StartWallUTC: stopAt.Add(time.Minute), DurationMS: 9000, Op: OpenOrCurrent}
	d, err := r.Decide(ctx, openTx, split, split.StartWallUTC)
	if err != nil || d.Outcome != OutcomeOpenFresh {
		t.Fatalf("TD 분할 결정 = (%v, %v)", d.Outcome, err)
	}
	cID, err := r.Open(ctx, openTx, d, split.StartWallUTC)
	if err != nil {
		t.Fatalf("TD 분할 개시 실패: %v", err)
	}

	slots := &intentSlots{}
	done := make(chan IntentionalResult, 1)
	go func() {
		res, _ := ApplyIntentionalEnd(ctx, pool, endAt("demo"), slots.options())
		done <- res
	}()
	waitLockWaiter(t, pool)
	if err := openTx.Commit(ctx); err != nil {
		t.Fatalf("개시 커밋 실패: %v", err)
	}
	var res IntentionalResult
	select {
	case res = <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("4D 적용이 끝나지 않았다")
	}
	if res.Effect != effectRoundSplit {
		t.Fatalf("결과 = %+v, want round_split", res)
	}
	for _, id := range []string{"S-B", cID} {
		if got := roundOf(t, pool, id); strOf(got.roundID) != "S-B" {
			t.Errorf("%s round_id = %s, want S-B — 기다리는 동안 열린 세션도 옮겨야 한다", id, strOf(got.roundID))
		}
	}
}

// D73(4D 쪽) — 늦게 온 옛 연결 조각이 연 live L 이 최신 ending B 보다 이른 started_at 이어도 4D 의 소속 잠금은 L 을 잡는다.
// 변이 = 선택 규칙을 started_at 최대로 → B 를 잡아 L 을 쥔 경쟁에 막히지 않아 FAIL.
func TestApplyIntentionalEndLocksLiveRowAsMembership(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	seedLedger(t, pool, ledgerRow{id: "S-L", streamID: "demo", state: "live", roundID: "S-L", startedAt: stopAt.Add(-40 * time.Minute)})
	seedLedger(t, pool, ledgerRow{id: "S-B", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-B", startedAt: stopAt.Add(-30 * time.Minute)})
	seedSegment(t, pool, "demo", "S-B", 1, stopAt.Add(-5*time.Second))
	commit := holdRow(t, pool, "S-L", "")
	slots := &intentSlots{}

	done := make(chan error, 1)
	go func() {
		_, err := ApplyIntentionalEnd(ctx, pool, endAt("demo"), slots.options())
		done <- err
	}()
	select {
	case err := <-done:
		t.Fatalf("4D 가 L 의 잠금을 기다리지 않고 돌아왔다(err %v) — 소속 행이 live L 이 아니다", err)
	case <-time.After(300 * time.Millisecond):
	}
	commit()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("4D 적용 실패: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("L 잠금이 풀린 뒤에도 4D 가 끝나지 않았다")
	}
}

// D12 — 4D · TD 분할 · EndLive 가 같은 live 행을 차례로 건드려도 사유가 덮이지 않는다: 4D 표식 → TD 분할(X 를 td_exceeded ·
// C 가 표식 승계) → EndLive(C 를 끝냄). X 의 사유 · 표식은 그대로다. 변이 = endLiveSQL 의 `AND state='live'` 제거 →
// EndLive 가 X 의 사유를 덮어 FAIL.
func TestIntentionalEndTDSplitAndEndLiveDoNotOverwriteEachOther(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-X", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-X", 1, stopAt.Add(-5*time.Second))
	slots := &intentSlots{}
	if res := apply(t, pool, endAt("demo"), slots.options()); res.Effect != effectMarked {
		t.Fatalf("4D = %+v, want marked", res)
	}
	r, _ := newRoundRegistry()
	c := openOne(t, pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: stopAt.Add(3 * time.Second), DurationMS: 9000, Op: OpenOrCurrent})
	ended, err := index.EndLive(ctx, pool, "demo")
	if err != nil || ended != c {
		t.Fatalf("EndLive = (%q, %v), want %s", ended, err, c)
	}
	x := roundOf(t, pool, "S-X")
	if x.state != "ending" || strOf(x.endReason) != endReasonTDExceeded || x.stopMarkedAt == nil || !x.stopMarkedAt.Equal(stopAt) {
		t.Errorf("S-X = (%s, %s, %v), want (ending, td_exceeded, %v)", x.state, strOf(x.endReason), x.stopMarkedAt, stopAt)
	}
	if got := roundOf(t, pool, c); got.state != "ending" || strOf(got.roundID) != "S-X" || got.stopMarkedAt == nil {
		t.Errorf("C = (%s, %s, %v), want (ending, S-X, 승계 표식)", got.state, strOf(got.roundID), got.stopMarkedAt)
	}
}

// contended(D74 의 4D 갈래) — 위상 잠금을 남이 쥐고 있으면 lock_timeout(5초) 뒤 미적용 contended 로 돌아온다(오류가 아니다).
// 위상 잠금이 상한 설정 다음 문장이라 잠금 대기가 5초에 끊긴다. 변이 = 위상 잠금을 상한 설정 앞에 둠 → lock_timeout 이
// 걸리지 않아 트랜잭션 시한(10초)까지 기다리다 오류로 FAIL.
func TestApplyIntentionalEndReportsContendedOnTopologyLockTimeout(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-X", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-X", 1, stopAt.Add(-5*time.Second))
	holder, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("경쟁 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = holder.Rollback(ctx) }()
	if _, err := holder.Exec(ctx, topologyLockSQL, "demo"); err != nil {
		t.Fatalf("경쟁 위상 잠금 실패: %v", err)
	}
	slots := &intentSlots{}

	started := time.Now()
	res, err := ApplyIntentionalEnd(ctx, pool, endAt("demo"), slots.options())
	took := time.Since(started)

	if err != nil || res.Skip != skipContended || res.Effect != "" {
		t.Fatalf("결과 = (%+v, %v), want Skip contended · 오류 없음", res, err)
	}
	if took > 8*time.Second {
		t.Errorf("contended 까지 %v, want 5초 언저리(lock_timeout)", took)
	}
	if m := markOf(t, pool, "S-X"); m != nil {
		t.Errorf("contended 인데 표식을 썼다: %v", *m)
	}
}

// 함수 칸 · 수치가 비면 쓰기 전에 오류다(조립 결함 — nil 호출로 panic 하지 않는다).
func TestApplyIntentionalEndRejectsUnwiredOptions(t *testing.T) {
	pool := newTestPool(t)
	seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-X", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-X", 1, stopAt.Add(-5*time.Second))
	full := (&intentSlots{}).options()
	for name, o := range map[string]IntentOptions{
		"CloseRound_없음":     {ShortConn: full.ShortConn, Slack: full.Slack, EnqueueStarted: full.EnqueueStarted},
		"EnqueueStarted_없음": {ShortConn: full.ShortConn, Slack: full.Slack, CloseRound: full.CloseRound},
		"수치_없음":             {CloseRound: full.CloseRound, EnqueueStarted: full.EnqueueStarted},
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := ApplyIntentionalEnd(context.Background(), pool, endAt("demo"), o); err == nil {
				t.Error("비어 있는 IntentOptions 로 적용이 돌았다")
			}
			if m := markOf(t, pool, "S-X"); m != nil {
				t.Errorf("오류인데 표식을 썼다: %v", *m)
			}
		})
	}
}

// D71(분리 쪽) — round_split 트랜잭션이 live B 를 소속 잠금 · 회차 행 잠금(FOR NO KEY UPDATE)으로 쥔 채 멈춰 있어도 그
// 세션의 「계속」 조각 INSERT(FK = FOR KEY SHARE)는 기다리지 않는다. 멈춤은 CloseRound 칸 안(모든 잠금 뒤)이다.
// 변이 = 회차 행 잠금을 FOR UPDATE 로 → INSERT 가 막혀 시한 FAIL.
func TestSplitHoldingLiveRowDoesNotBlockSegmentInsert(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
	seedLedger(t, pool, ledgerRow{id: "S-B", streamID: "demo", state: "live", roundID: "S-R", startedAt: stopAt.Add(20 * time.Second)})
	o := (&intentSlots{}).options()
	paused, release := make(chan struct{}), make(chan struct{})
	o.CloseRound = func(context.Context, pgx.Tx, string) error {
		close(paused)
		<-release
		return nil
	}
	done := make(chan error, 1)
	go func() {
		_, err := ApplyIntentionalEnd(ctx, pool, endAt("demo"), o)
		done <- err
	}()
	select {
	case <-paused:
	case <-time.After(5 * time.Second):
		t.Fatal("분리 트랜잭션이 CloseRound 칸까지 오지 않았다")
	}

	ictx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	_, err := pool.Exec(ictx, `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id)
VALUES ('demo', 2, 0, $1, 4000, 'k-2', '/recordings/demo/2.mp4', 'uploaded', 1000, 'S-B')`, stopAt.Add(24*time.Second))
	close(release)
	if err != nil {
		t.Fatalf("분리 중 조각 INSERT 실패: %v — FK 잠금이 소속 · 회차 행 잠금에 막혔다", err)
	}
	if err := <-done; err != nil {
		t.Fatalf("분리 실패: %v", err)
	}
}
