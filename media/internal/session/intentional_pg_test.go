package session

// 의도적 종료 적용(4D — POK-196 M6-3a 커밋 ② · 설계 M6 2.3 · 3.3 · 3.4 · 9절 D10 · D12 · D53 · D55 · D69 · D73 · POK-525
// 설계 r9.3 3-2 · E8)의 실 PG 시험. 함수 칸(CloseRound)은 가짜로 끼워 「같은 트랜잭션에서 불렸는가」만 잰다 — 실제 회차
// 닫힘 · 완결 평가는 M6-3b 몫이다(계획 4.R 「앞 몫 · 뒤 몫」). started 칸 가짜(enqueueStarted)는 forwarded 뒤 레지스트리
// 개시가 새 회차를 열 때 쓴다.

import (
	"context"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// intentSlots 는 함수 칸 가짜다 — 부른 회차와, 부른 순간 그 회차의 남은 행이 모두 닫혔는지(같은 트랜잭션 안에서 읽음)를 적는다.
// started 칸(enqueueStarted)은 레지스트리 Options 에 끼운다.
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
	}
}

// enqueueStarted 는 레지스트리 started 칸 가짜다(session.Options.EnqueueStarted 꼴).
func (s *intentSlots) enqueueStarted(_ context.Context, _ pgx.Tx, round, ingestStreamID string, at time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.started = append(s.started, round)
	s.startedFrom = append(s.startedFrom, ingestStreamID)
	s.startedAt = append(s.startedAt, at)
	return nil
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
			if len(slots.closed) != 0 {
				t.Errorf("미적용인데 함수 칸을 불렀다: close %v", slots.closed)
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

			if res.Effect != effectUpgraded || res.SessionID != "S-T" || res.RoundID != "S-R" {
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
			if len(slots.closed) != 1 || slots.closed[0] != "S-R" {
				t.Errorf("함수 칸 close %v, want close [S-R]", slots.closed)
			}
		})
	}
}

// dbNow 는 PG 시계다 — forwarded 표식(도착 순간 DB 시각)의 앞뒤 경계를 잰다.
func dbNow(t *testing.T, pool *pgxpool.Pool) time.Time {
	t.Helper()
	var now time.Time
	if err := pool.QueryRow(context.Background(), `SELECT pg_catalog.now()`).Scan(&now); err != nil {
		t.Fatalf("PG 시계 조회 실패: %v", err)
	}
	return now
}

// applyTimed 는 4D 를 적용하고 그 앞뒤 PG 시계를 함께 돌려준다 — forwarded 표식은 [before, after] 안에 있어야 한다.
func applyTimed(t *testing.T, pool *pgxpool.Pool, e IntentionalEnd, o IntentOptions) (res IntentionalResult, before, after time.Time) {
	t.Helper()
	before = dbNow(t, pool)
	res = apply(t, pool, e, o)
	return res, before, dbNow(t, pool)
}

// requireArrivalMark 는 세션 id 의 표식이 도착 순간 DB 시각([before, after])인가다 — 4D 의 StoppedAt 이 아니다(E8).
func requireArrivalMark(t *testing.T, pool *pgxpool.Pool, id string, before, after time.Time) time.Time {
	t.Helper()
	m := markOf(t, pool, id)
	if m == nil || m.Before(before) || m.After(after) {
		t.Fatalf("%s 표식 = %v, want 도착 순간 DB 시각 [%v, %v] — StoppedAt(%v) 이 아니다", id, m, before, after, stopAt)
	}
	return *m
}

// requireTUntouched 는 끝난 T 가 그대로인가다 — forwarded 는 T 가 아니라 회차 최신 L 에 표식을 건다.
func requireTUntouched(t *testing.T, pool *pgxpool.Pool, id, wantReason string) {
	t.Helper()
	if got := roundOf(t, pool, id); got.stopMarkedAt != nil || strOf(got.endReason) != wantReason || strOf(got.roundID) != "S-R" {
		t.Errorf("T %s = (표식 %v, 사유 %s, 회차 %s), want (NULL, %s, S-R) 그대로", id, got.stopMarkedAt, strOf(got.endReason), strOf(got.roundID), wantReason)
	}
}

// 효과 표 forwarded(설계 r9.3 3-2 · E8) — 끝난 T 가 회차 R 의 최신이 아니고 최신 L 이 live 다. 4D 는 L 에 도착 순간 DB
// 시각 표식을 걸고 회차는 닫지 않는다(L 이 아직 송출 중). T · round_id 는 그대로다. 멈춘 뒤 합류한 L 과 멈추기 전부터 있던
// (조각 없는) L 이 같은 효과다. 신호는 INFO intentional_end effect=forwarded · latest · marked_at 이다.
// 변이: 표식을 T 에 → T 표식 단언 FAIL · 표식 = StoppedAt → 도착 시각 단언 FAIL.
func TestApplyIntentionalEndForwardsMarkToLiveLatest(t *testing.T) {
	for _, c := range []struct {
		name     string
		latestAt time.Duration
	}{
		{"멈춘_뒤_합류한_live", 20 * time.Second},
		{"멈추기_전부터_있던_live", -30 * time.Minute},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ended", endReason: "offline", vodState: "frozen", roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
			seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
			seedLedger(t, pool, ledgerRow{id: "S-L", streamID: "demo", state: "live", roundID: "S-R", startedAt: stopAt.Add(c.latestAt)})
			slots := &intentSlots{}
			o := slots.options()
			sig := &signalLog{}
			o.Log = slog.New(sig)

			res, before, after := applyTimed(t, pool, endAt("demo"), o)

			if res.Effect != effectForwarded || res.SessionID != "S-T" || res.RoundID != "S-R" {
				t.Fatalf("결과 = %+v, want forwarded · S-T · S-R", res)
			}
			mark := requireArrivalMark(t, pool, "S-L", before, after)
			if got := roundOf(t, pool, "S-L"); got.state != "live" || strOf(got.roundID) != "S-R" || got.endReason != nil {
				t.Errorf("L = (%s, 회차 %s, 사유 %s), want (live, S-R, NULL) — forwarded 는 live 를 끝내지 않는다", got.state, strOf(got.roundID), strOf(got.endReason))
			}
			requireTUntouched(t, pool, "S-T", "offline")
			if len(slots.closed) != 0 {
				t.Errorf("live L 인데 함수 칸을 불렀다: close %v", slots.closed)
			}
			a := sig.attrs("intentional_end")
			if a["effect"] != effectForwarded || a["latest"] != "S-L" || a["marked_at"] != mark {
				t.Errorf("intentional_end 속성 = %v, want effect forwarded · latest S-L · marked_at %v", a, mark)
			}
		})
	}
}

// 효과 표 forwarded(L 이 ending · ended) — L 에 같은 표식을 걸고 사유 · vod · round_id 는 그대로 두며, 같은 트랜잭션 끝에서
// CloseRound 칸(회차 닫힘 조건 「표식 있음」 + 완결 평가)을 R 로 한 번 부른다. ending 갈래는 POK-525 전에 round_split_blocked
// 로 막히던 꼴(동결 중 사슬 A–B)이다 — 행을 옮기지 않으니 막을 까닭이 없다.
// 변이: ending L 의 CloseRound 생략 → close 단언 FAIL · 표식을 T 에 → T 단언 FAIL.
func TestApplyIntentionalEndForwardsMarkToEndedLatestAndClosesRound(t *testing.T) {
	for _, c := range []struct {
		name, state, reason, vod, inherits string
	}{
		{"ending_동결_중_사슬", "ending", endReasonTDExceeded, "freezing", "S-T"},
		{"ended", "ended", "offline", "frozen", ""},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ending", endReason: "offline", vodState: c.vod, roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
			seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
			seedLedger(t, pool, ledgerRow{id: "S-L", streamID: "demo", state: c.state, endReason: c.reason, vodState: c.vod, roundID: "S-R", inherits: c.inherits, startedAt: stopAt.Add(20 * time.Second)})
			slots := &intentSlots{}

			res, before, after := applyTimed(t, pool, endAt("demo"), slots.options())

			if res.Effect != effectForwarded || res.SessionID != "S-T" || res.RoundID != "S-R" {
				t.Fatalf("결과 = %+v, want forwarded · S-T · S-R", res)
			}
			requireArrivalMark(t, pool, "S-L", before, after)
			got := roundOf(t, pool, "S-L")
			if got.state != c.state || strOf(got.endReason) != c.reason || strOf(got.roundID) != "S-R" || vodOf(t, pool, "S-L") != c.vod {
				t.Errorf("L = (%s, %s, 회차 %s, vod %s), want (%s, %s, S-R, %s) — 사유 · 동결은 그대로", got.state, strOf(got.endReason), strOf(got.roundID), vodOf(t, pool, "S-L"), c.state, c.reason, c.vod)
			}
			requireTUntouched(t, pool, "S-T", "offline")
			if len(slots.closed) != 1 || slots.closed[0] != "S-R" {
				t.Errorf("함수 칸 close %v, want close [S-R]", slots.closed)
			}
		})
	}
}

// forwarded 뒤의 개시(설계 r9.3 3-2 「그 뒤」 · E8) — live L 의 표식 + 여유(8초) 이상에서 시작한 조각은 표식 분할로 L 을
// intentional 로 끝내고 새 회차를 연다. 새 회차의 started 편지는 레지스트리 개시가 한 통 넣는다(registry.go newRound 갈래).
// 그 앞 조각(도착 앞 · 여유 안 · 경계 1µs 전)은 L 에 붙는다 — 이미 녹화된 조각은 옛 회차에 남는다.
// 변이: 표식 = StoppedAt → 「도착 앞」 · 「여유 안」 조각이 새 회차를 열어 FAIL.
func TestForwardedMarkOpensNewRoundFromNextSegment(t *testing.T) {
	for _, c := range []struct {
		name     string
		off      time.Duration
		newRound bool
	}{
		{"도착_앞", -time.Second, false},
		{"도착_뒤_여유_안", time.Second, false},
		{"여유_경계_1µs_전", 8*time.Second - time.Microsecond, false},
		{"여유_경계", 8 * time.Second, true},
		{"여유_경계_1µs_뒤", 8*time.Second + time.Microsecond, true},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ended", endReason: "offline", vodState: "frozen", roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
			seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
			seedLedger(t, pool, ledgerRow{id: "S-L", streamID: "demo", state: "live", roundID: "S-R", startedAt: stopAt.Add(20 * time.Second)})
			res, before, after := applyTimed(t, pool, endAt("demo"), (&intentSlots{}).options())
			if res.Effect != effectForwarded {
				t.Fatalf("4D = %+v, want forwarded", res)
			}
			mark := requireArrivalMark(t, pool, "S-L", before, after)
			slots := &intentSlots{}
			r, _ := newRoundRegistry()
			r.opt.EnqueueStarted = slots.enqueueStarted

			opened := openOne(t, pool, r, Input{StreamID: "demo", Seq: 9, StartWallUTC: mark.Add(c.off), DurationMS: 4000, Op: OpenOrCurrent})

			if !c.newRound {
				if opened != "" || len(slots.started) != 0 {
					t.Fatalf("표식 %+v 조각이 연 세션 = %q · started %v, want L 에 붙음", c.off, opened, slots.started)
				}
				if got := roundOf(t, pool, "S-L"); got.state != "live" {
					t.Errorf("L state = %s, want live", got.state)
				}
				return
			}
			if opened == "" {
				t.Fatalf("표식 %+v 조각이 새 세션을 열지 않았다", c.off)
			}
			if got := roundOf(t, pool, opened); strOf(got.roundID) != opened || got.stopMarkedAt != nil || got.inherits != nil {
				t.Errorf("새 세션 = (회차 %s, 표식 %v, 계승 %s), want (자기 회차 %s, NULL, NULL)", strOf(got.roundID), got.stopMarkedAt, strOf(got.inherits), opened)
			}
			if got := roundOf(t, pool, "S-L"); got.state != "ending" || strOf(got.endReason) != endReasonIntentional || strOf(got.roundID) != "S-R" {
				t.Errorf("L = (%s, %s, 회차 %s), want (ending, intentional, S-R)", got.state, strOf(got.endReason), strOf(got.roundID))
			}
			if len(slots.started) != 1 || slots.started[0] != opened {
				t.Errorf("started 편지 = %v, want [%s] 한 통", slots.started, opened)
			}
		})
	}
}

// forwarded 는 계승 간선 · round_id 를 건드리지 않는다 — 옮기는 세션이 없어 회차를 가로지르는 간선이 생기지 않는다(설계
// r9.3 3-2). L 이 끝난 T 를 계승 중이어도 L 에 표식만 건다.
func TestForwardedLeavesInheritanceEdge(t *testing.T) {
	pool := newTestPool(t)
	seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
	seedLedger(t, pool, ledgerRow{id: "S-L", streamID: "demo", state: "live", roundID: "S-R", inherits: "S-T", startedAt: stopAt.Add(20 * time.Second)})

	res := apply(t, pool, endAt("demo"), (&intentSlots{}).options())

	if res.Effect != effectForwarded {
		t.Fatalf("결과 = %+v, want forwarded", res)
	}
	if got := roundOf(t, pool, "S-L"); strOf(got.roundID) != "S-R" || strOf(got.inherits) != "S-T" || got.stopMarkedAt == nil {
		t.Errorf("L (round_id, 계승, 표식) = (%s, %s, %v), want (S-R, S-T, 표식)", strOf(got.roundID), strOf(got.inherits), got.stopMarkedAt)
	}
}

// D69 (나) 재확인 창 — 소속 잠금 ② 와 회차 행 잠금 ③ 사이에 다른 개시가 끼어드는 경우다. 개시 1(B 의 TD 분할 → C)이
// B 를 쥔 동안 4D 가 B 를 기다리고, 개시 1 이 커밋하면 4D 는 소속 행을 다시 골라 C 를 잡는다. 그 뒤 ③ 은 셋째
// 트랜잭션이 S-T(③ 정렬의 첫 행)에 건 FOR SHARE 에서 멈춘다(배리어). 그 사이 개시 2(C 의 TD 분할 → D)가 돈다 —
// 4D 가 C 를 쥐고 있으므로 개시 2 는 C 에서 기다리다가, forwarded 가 live C 에 건 표식을 RETURNING 으로 물려받는다.
// 변이 = lockMembership 의 재확인 제거 → 4D 는 B 만 쥐고, 개시 2 가 먼저 표식 없는 D 를 커밋하며, 이미 시작된 ③ 문장의
// 스냅숏에는 D 가 없어 표식이 끝난 C 에 걸려 송출 중인 D 에 표식이 없어 FAIL. 제품 코드에 시험 갈래를 두지 않고 행
// 잠금만으로 결정적으로 재현한다.
func TestApplyIntentionalEndRelocksMembershipBeforeRoundRows(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	seedLedger(t, pool, ledgerRow{id: "S-T", streamID: "demo", state: "ending", endReason: "offline", roundID: "S-R", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-T", 1, stopAt.Add(-5*time.Second))
	seedLedger(t, pool, ledgerRow{id: "S-B", streamID: "demo", state: "live", roundID: "S-R", startedAt: stopAt.Add(20 * time.Second)})
	r, _ := newRoundRegistry()

	barrier, barrierPID := beginWithPID(t, pool)
	if _, err := barrier.Exec(ctx, `SELECT 1 FROM stream_sessions WHERE session_id = 'S-T' FOR SHARE`); err != nil {
		t.Fatalf("배리어 잠금 실패: %v", err)
	}
	open1, open1PID := beginWithPID(t, pool)
	first := Input{StreamID: "demo", Seq: 9, StartWallUTC: stopAt.Add(time.Minute), DurationMS: 9000, Op: OpenOrCurrent}
	d, err := r.Decide(ctx, open1, first, first.StartWallUTC)
	if err != nil || d.Outcome != OutcomeOpenFresh {
		t.Fatalf("개시 1 TD 분할 결정 = (%v, %v)", d.Outcome, err)
	}
	cID, err := r.Open(ctx, open1, d, first.StartWallUTC)
	if err != nil {
		t.Fatalf("개시 1 실패: %v", err)
	}

	done := make(chan IntentionalResult, 1)
	go func() {
		res, _ := ApplyIntentionalEnd(ctx, pool, endAt("demo"), (&intentSlots{}).options())
		done <- res
	}()
	waitBlockedBy(t, pool, open1PID)
	if err := open1.Commit(ctx); err != nil {
		t.Fatalf("개시 1 커밋 실패: %v", err)
	}
	waitBlockedBy(t, pool, barrierPID)

	second := decideOpenAsync(pool, r, Input{StreamID: "demo", Seq: 10, StartWallUTC: stopAt.Add(2 * time.Minute), DurationMS: 12000, Op: OpenOrCurrent})
	secondDone := waitBlockedOrDone(t, pool, second, 2)
	if err := barrier.Rollback(ctx); err != nil {
		t.Fatalf("배리어 해제 실패: %v", err)
	}

	var res IntentionalResult
	select {
	case res = <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("4D 적용이 끝나지 않았다")
	}
	if secondDone == nil {
		secondDone = second
	}
	waitResult(t, secondDone)
	if res.Effect != effectForwarded {
		t.Fatalf("결과 = %+v, want forwarded", res)
	}
	var dID string
	var dRound *string
	var dMark *time.Time
	var dClosed bool
	if err := pool.QueryRow(ctx, `SELECT session_id, round_id, stop_marked_at, round_closed_at IS NOT NULL FROM stream_sessions WHERE stream_id = 'demo' AND state = 'live'`).
		Scan(&dID, &dRound, &dMark, &dClosed); err != nil {
		t.Fatalf("개시 2 의 live D 조회 실패: %v", err)
	}
	if dID == cID || strOf(dRound) != "S-R" || dMark == nil || dClosed {
		t.Errorf("D %s (round_id, 표식, 닫힘) = (%s, %v, %v), want (S-R, 표식, false) — ② 와 ③ 사이에 열린 송출에 표식이 없다", dID, strOf(dRound), dMark, dClosed)
	}
}

// beginWithPID 는 트랜잭션을 열고 그 백엔드 pid 를 돌려준다. 테스트가 끝나면 되돌린다(커밋했으면 무동작).
func beginWithPID(t *testing.T, pool *pgxpool.Pool) (pgx.Tx, int32) {
	t.Helper()
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	t.Cleanup(func() { _ = tx.Rollback(ctx) })
	var pid int32
	if err := tx.QueryRow(ctx, `SELECT pg_catalog.pg_backend_pid()`).Scan(&pid); err != nil {
		t.Fatalf("pid 조회 실패: %v", err)
	}
	return tx, pid
}

// waitBlockedBy 는 어떤 백엔드가 pid 에 막혀 기다릴 때까지 기다린다 — 「누가 무엇을 기다리는가」를 잠금 그래프로 확인한다.
func waitBlockedBy(t *testing.T, pool *pgxpool.Pool, pid int32) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var blocked bool
		if err := pool.QueryRow(context.Background(), `
SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_stat_activity
                WHERE datname = pg_catalog.current_database() AND $1::int = ANY(pg_catalog.pg_blocking_pids(pid)))`, pid).
			Scan(&blocked); err != nil {
			t.Fatalf("잠금 그래프 조회 실패: %v", err)
		}
		if blocked {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("5초 안에 pid %d 를 기다리는 백엔드가 생기지 않았다", pid)
}

// waitBlockedOrDone 은 ch 가 끝나거나 막힌 백엔드가 n 개가 될 때까지 기다린다. ch 가 먼저 끝났으면 그 값을 담은 새 채널을,
// 막힘으로 끝났으면 nil 을 돌려준다(그때는 ch 를 그대로 기다리면 된다).
func waitBlockedOrDone(t *testing.T, pool *pgxpool.Pool, ch <-chan error, n int) <-chan error {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case err := <-ch:
			out := make(chan error, 1)
			out <- err
			return out
		default:
		}
		var blocked int
		if err := pool.QueryRow(context.Background(), `
SELECT pg_catalog.count(*) FROM pg_catalog.pg_stat_activity
 WHERE datname = pg_catalog.current_database() AND pg_catalog.cardinality(pg_catalog.pg_blocking_pids(pid)) > 0`).
			Scan(&blocked); err != nil {
			t.Fatalf("잠금 그래프 조회 실패: %v", err)
		}
		if blocked >= n {
			return nil
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("5초 안에 개시 2 가 끝나지도, 막힌 백엔드가 %d 개가 되지도 않았다", n)
	return nil
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
	ended, err := index.EndLive(ctx, pool, "demo", crossClose, noEnqueue)
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

// 보안 사후 검수 미확인 1 — 4D 표식 뒤 송출이 이어져도 표식이 그 뒤의 평범한 종료를 intentional 로 만들지 않는다. 표식
// + 여유(8초) 이상에 시작한 조각은 표식 있는 live 를 intentional 로 끝내고 표식 없는 새 세션을 열므로(표식 분할), 그 뒤
// 오프라인 전이(EndLive)는 offline 이다. 여유 안의 조각은 표식 있는 live 에 붙어 그 live 의 오프라인 전이가 intentional 이다
// (D54 기록 — 남는 송출 8초 이하). 변이 = 결정자의 표식 분할 제거 → 뒤 송출의 종료가 intentional 로 FAIL.
func TestStopMarkDoesNotTurnLaterOfflineEndIntentional(t *testing.T) {
	ctx := context.Background()
	for _, c := range []struct {
		name        string
		after       time.Duration
		wantSplit   bool
		wantEndedAs string
	}{
		{"여유_밖_송출은_새_세션의_offline", 12 * time.Second, true, "offline"},
		{"여유_안_송출은_표식_live_의_intentional", 3 * time.Second, false, endReasonIntentional},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-X", startedAt: stopAt.Add(-time.Hour)})
			seedSegment(t, pool, "demo", "S-X", 1, stopAt.Add(-5*time.Second))
			slots := &intentSlots{}
			if res := apply(t, pool, endAt("demo"), slots.options()); res.Effect != effectMarked {
				t.Fatalf("4D = %+v, want marked", res)
			}
			r, _ := newRoundRegistry()
			opened := openOne(t, pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: stopAt.Add(c.after), DurationMS: 4000, Op: OpenOrCurrent})
			if (opened != "") != c.wantSplit {
				t.Fatalf("표식 + %v 조각이 연 세션 = %q, want 분할 %t", c.after, opened, c.wantSplit)
			}

			ended, err := index.EndLive(ctx, pool, "demo", crossClose, noEnqueue)
			if err != nil {
				t.Fatalf("EndLive 실패: %v", err)
			}
			got := roundOf(t, pool, ended)
			if strOf(got.endReason) != c.wantEndedAs {
				t.Errorf("오프라인 전이로 끝난 %s 의 사유 = %s, want %s", ended, strOf(got.endReason), c.wantEndedAs)
			}
			if c.wantSplit {
				if got.stopMarkedAt != nil {
					t.Errorf("분할로 연 세션 %s 에 표식 %v — 표식은 승계되지 않아야 한다", ended, got.stopMarkedAt)
				}
				if x := roundOf(t, pool, "S-X"); x.state != "ending" || strOf(x.endReason) != endReasonIntentional {
					t.Errorf("표식 live S-X = (%s, %s), want (ending, intentional)", x.state, strOf(x.endReason))
				}
			}
		})
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
		"CloseRound_없음": {ShortConn: full.ShortConn, Slack: full.Slack},
		"수치_없음":         {CloseRound: full.CloseRound},
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
