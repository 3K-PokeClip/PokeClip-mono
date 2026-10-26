package main

// 정산 루프 × 실 장부(POK-196 M6-3b 커밋 ③ — 설계 M6 3.2 꺼짐 표 · 9절 D56 (가) · D72 · 10절 함수 칸). 차선 작업 몸체는 운영
// 몸체(newFinalizeWork — 실 index 종료 문장)이고 발행자는 없다(꺼짐). 루프 고루틴 자리는 시험 고루틴이다.

import (
	"context"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// newLedgerFinalLoop 은 실 장부 pool 위의 꺼짐 정산 루프다.
func newLedgerFinalLoop(t *testing.T, pool *pgxpool.Pool, mode finalize.Mode) *finalLoop {
	t.Helper()
	f := &finalLoop{offlineLoop: newOfflineLoop(t, nil), lanes: publish.NewLanes(), inbox: session.NewPlaybackInbox(),
		intents: session.NewIntentInbox(), sunk: &sinkLog{}}
	co := index.CloseOptions{Window: 300 * time.Second, Slack: 60 * time.Second, Log: slog.New(f.logs)}
	r := f.rewind
	r.lanes = f.lanes
	r.fin = newFinalizer(finalizerOptions{Mode: mode, Every: time.Second, CloseAfter: 360 * time.Second,
		Work: newFinalizeWork(pool, nil, co, noEndedEnqueue), Inbox: f.inbox, Intents: f.intents, Sink: f.sunk.sink})
	return f
}

// drive 는 holdTick 을 n 번(1초 간격) 돌리며 매번 결과를 모두 반영한다.
func (f *finalLoop) drive(from time.Time, n int) time.Time {
	now := from
	for range n {
		f.hold(now)
		f.settle(now)
		now = now.Add(time.Second)
	}
	return now
}

// D56 (가) · D72 — S3_BUCKET 빈 판으로 기동하면 발행 흔적이 없는 끝난 세션은 빈 동결 · 사슬 닫힘 · VOD 빈 동결 · phase 4 를
// 거쳐 ended 에 닿고, 흔적이 있는 세션(버킷 있던 판에서 발행됨)은 ending · dvr open 에 머문다(빈 동결로 닫으면 /dvr 판이
// ENDLIST 없이 남아 다시보기가 영구히 없다). 게이지 finalize_held reason=publish_off_with_trace value=1. 변이 = 흔적 판정
// 제거(판정 · 장부 겹 방어 둘 다) → 흔적 세션이 빈 동결로 닫혀 FAIL.
func TestPublishingOffBootFinalizesOnlyUntracedSessionsD72(t *testing.T) {
	pool := newWireTestPool(t)
	old := time.Now().Add(-time.Hour)
	execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, round_id, init_uploaded_at, manifest_etag)
VALUES ('N', 'd72-n', $1, 'ending', 'intentional', $1, 'RN', $1, NULL),
       ('T', 'd72-t', $1, 'ending', 'intentional', $1, 'RT', $1, '"etag-from-bucket-run"')`, old)
	f := newLedgerFinalLoop(t, pool, finalize.ModeOff)

	f.drive(loopBase, 8)

	var stateN, stateT, dvrT string
	if err := pool.QueryRow(t.Context(), `SELECT (SELECT state FROM stream_sessions WHERE session_id='N'),
	  (SELECT state FROM stream_sessions WHERE session_id='T'), (SELECT dvr_state FROM stream_sessions WHERE session_id='T')`).
		Scan(&stateN, &stateT, &dvrT); err != nil {
		t.Fatal(err)
	}
	if stateN != "ended" {
		t.Fatalf("흔적 없는 세션 N state = %s, want ended(꺼짐에서도 정산이 돈다)", stateN)
	}
	if stateT != "ending" || dvrT != "open" {
		t.Fatalf("흔적 있는 세션 T = (%s, dvr %s), want (ending, open) — 보류", stateT, dvrT)
	}
	rec := f.logs.last("finalize_held")
	if rec == nil || rec.attrs["reason"] != finalize.HoldPublishOffWithTrace || rec.attrs["value"] != int64(1) {
		t.Fatalf("finalize_held = %+v, want reason=publish_off_with_trace value=1", rec)
	}
	if sent := f.sunk.all(); len(sent) == 0 || sent[len(sent)-1].stream != "d72-n" || sent[len(sent)-1].st.Phase != index.PhaseEnded {
		t.Fatalf("상태 전달 = %+v, want 마지막이 d72-n Ended(회차 완결)", sent)
	}
}

// 10절 함수 칸 — 조립의 회차 닫힘 칸(fa.closeRound)이 4D upgraded 트랜잭션에서 회차를 닫는다(M6-3a 판의 무동작 기본값을
// 채움). 칸이 기본값이면 회차가 열린 채 남아 360초 회차 스캔을 기다린다.
func TestIntentOptionsCloseRoundSlotClosesRoundOnUpgrade(t *testing.T) {
	pool := newWireTestPool(t)
	stop := wireBase
	execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, round_id)
VALUES ('U', 'slot', $1, 'ending', 'offline', $2, 'RU')`, stop.Add(-time.Hour), stop)
	execSQL(t, pool, `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, upload_state, session_id)
VALUES ('slot', 1, 0, $1, 4000, 'k1', 'uploaded', 'U')`, stop.Add(-5*time.Second))
	fa := finalizeAssembly{close: index.CloseOptions{Window: 300 * time.Second, Slack: 60 * time.Second}, enqueue: noEndedEnqueue}
	o := newIntentOptions(slog.New(slog.DiscardHandler), fa.closeRound(), nil)

	res, err := session.ApplyIntentionalEnd(context.Background(), pool,
		session.IntentionalEnd{StreamID: "slot", StoppedAt: stop, ConnStartedAt: stop.Add(-time.Hour)}, o)
	if err != nil || res.Effect != "upgraded" {
		t.Fatalf("4D = %+v err %v, want upgraded", res, err)
	}
	var closed bool
	if err := pool.QueryRow(t.Context(), `SELECT round_closed_at IS NOT NULL FROM stream_sessions WHERE session_id = 'U'`).Scan(&closed); err != nil || !closed {
		t.Fatalf("회차 닫힘 = %v %v, want 닫힘(같은 트랜잭션)", closed, err)
	}
}

// D25 종단 — 옛 ending 300건(그 가운데 round_id NULL 인 옛 계승 사슬 20개 · 각 셋)이 한꺼번에 있어도 정산 루프가 모두
// ended 로 거두고, 동시에 도는 정산 걸음은 동시 상한(4)을 넘지 않는다. 쪽 50 · 커서 순회 · 빠른 길이 함께 나아간다.
// 변이 = 동시 상한 제거 → 동시 걸음 최댓값 단언 FAIL.
func TestFinalizeBacklogOf300ReachesEndedWithinLimitsD25(t *testing.T) {
	pool := newWireTestPool(t)
	old := time.Now().Add(-2 * time.Hour)
	execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, round_id, init_uploaded_at)
SELECT 'b' || i, 'd25-' || i, $1::timestamptz + i * interval '1 second', 'ending', 'intentional', $1::timestamptz + i * interval '1 second',
       'R' || i, $1
  FROM pg_catalog.generate_series(1, 240) AS i`, old)
	execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, round_id, init_uploaded_at,
                             inherits_session)
SELECT 'c' || k || '-' || m, 'd25-c' || k, $1::timestamptz + (k * 10 + m) * interval '1 second', 'ending', 'intentional',
       $1::timestamptz + (k * 10 + m) * interval '1 second', NULL, $1, CASE WHEN m > 0 THEN 'c' || k || '-' || (m - 1) END
  FROM pg_catalog.generate_series(1, 20) AS k, pg_catalog.generate_series(0, 2) AS m`, old)
	f := newLedgerFinalLoop(t, pool, finalize.ModeOff)
	var (
		mu           sync.Mutex
		flying, peak int
		step         = f.rewind.fin.work.step
	)
	f.rewind.fin.work.step = func(ctx context.Context, s index.EndingSession, d finalize.Decision) finalStep {
		mu.Lock()
		flying++
		peak = max(peak, flying)
		mu.Unlock()
		defer func() {
			mu.Lock()
			flying--
			mu.Unlock()
		}()
		return step(ctx, s, d)
	}

	now := loopBase
	left := -1
	for i := 0; i < 3000 && left != 0; i += 50 {
		now = f.drive(now, 50)
		if err := pool.QueryRow(t.Context(), `SELECT pg_catalog.count(*) FROM stream_sessions WHERE state <> 'ended'`).Scan(&left); err != nil {
			t.Fatal(err)
		}
	}
	if left != 0 {
		t.Fatalf("ended 가 아닌 세션 %d 건이 남았다 — 정산이 밀린 것을 끝까지 거두지 못했다", left)
	}
	mu.Lock()
	defer mu.Unlock()
	if peak > finalize.DefaultLimits.Total {
		t.Fatalf("동시 정산 걸음 최대 %d, want ≤ %d(동시 상한)", peak, finalize.DefaultLimits.Total)
	}
	if peak < finalize.DefaultLimits.Total {
		t.Fatalf("동시 정산 걸음 최대 %d — 상한까지 내지 않았다(시험이 상한을 재지 못한다)", peak)
	}
}

// D56 (나) — 버킷은 설정됐는데 발행자 조립이 실패한 강등 판에서, 끝난 세션은 dvr open · ending 에 머문다(빈 동결로 닫지
// 않는다). 회차 닫힘 · 상태 전달은 돌아 Ending 이 전달된다. 같은 장부로 발행자 조립이 성공한 판을 다시 띄우면 동결이 이어
// 간다(frozen). 변이 = 판별을 발행자 nil 로(강등을 꺼짐처럼) → 강등 판이 빈 동결로 닫아 FAIL.
func TestDegradedHoldsUntilRestartWithPublisherD56(t *testing.T) {
	pool := newWireTestPool(t)
	old := time.Now().Add(-time.Hour)
	execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, round_id, init_uploaded_at)
VALUES ('D', 'd56-b', $1, 'ending', 'intentional', $1, 'RD', $1)`, old)

	degraded := newLedgerFinalLoop(t, pool, finalizeMode("bucket", false))
	degraded.drive(loopBase, 6)
	var dvr, state string
	var closed bool
	read := func() {
		t.Helper()
		if err := pool.QueryRow(t.Context(), `SELECT dvr_state, state, round_closed_at IS NOT NULL FROM stream_sessions WHERE session_id = 'D'`).
			Scan(&dvr, &state, &closed); err != nil {
			t.Fatal(err)
		}
	}
	read()
	if dvr != "open" || state != "ending" || !closed {
		t.Fatalf("강등 판 D = (dvr %s, %s, 회차 닫힘 %v), want (open, ending, 닫힘) — 보류 · 회차 닫힘은 돈다", dvr, state, closed)
	}
	if rec := degraded.logs.last("finalize_held"); rec == nil || rec.attrs["reason"] != finalize.HoldPublishDegraded {
		t.Fatalf("finalize_held = %+v, want reason=publish_degraded", rec)
	}
	if sent := degraded.sunk.all(); len(sent) == 0 || sent[len(sent)-1].stream != "d56-b" || sent[len(sent)-1].st.Phase != index.PhaseEnding {
		t.Fatalf("상태 전달 = %+v, want 마지막이 d56-b Ending", sent)
	}

	restarted := newLedgerFinalLoop(t, pool, finalize.ModePublishing)
	restarted.rewind.fin.work = newFinalizeWork(pool, newLedgerPublisher(t, pool), restartClose, noEndedEnqueue)
	restarted.drive(loopBase.Add(time.Minute), 6)
	read()
	if dvr != "frozen" {
		t.Fatalf("재기동 판 D dvr = %s, want frozen(발행 몫 회복 뒤 동결이 이어 감) — 재시도 %+v · 걸음 실패 %+v", dvr,
			restarted.logs.last("session_freeze_retry"), restarted.logs.last("finalize_step_failed"))
	}
}

// restartClose 는 재기동 판 정산 몫의 종료 문장 옵션이다(운영 값).
var restartClose = index.CloseOptions{Window: 300 * time.Second, Slack: 60 * time.Second, Log: slog.New(slog.DiscardHandler)}

// newLedgerPublisher 는 실 장부 pool 위의 발행자다 — 저장소는 조건부 의미론 가짜(itStore)다.
func newLedgerPublisher(t *testing.T, pool *pgxpool.Pool) *publish.Publisher {
	t.Helper()
	opt := publish.DefaultOptions(writerToken(t), "https://media.example.com")
	opt.Log = slog.New(slog.DiscardHandler)
	pub, err := publish.New(pool, newITStore(t), opt)
	if err != nil {
		t.Fatalf("발행자 생성 실패: %v", err)
	}
	return pub
}
