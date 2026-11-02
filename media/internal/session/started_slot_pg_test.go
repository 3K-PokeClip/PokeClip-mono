package session

// 계약9 started 칸(POK-196 M6-8 커밋 ④ — 설계 M6 15.4 · 15.6). 레지스트리는 새 회차를 여는 개시에서만 칸을 부르고, 같은
// 회차에 붙는 개시(TD 분할 · init 불일치 재접속 · 계승)에서는 부르지 않는다.

import (
	"context"
	"errors"
	"log/slog"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// startedCall 은 칸에 들어온 인자 한 벌이다.
type startedCall struct {
	round, ingest string
	at            time.Time
}

// recordStarted 는 started 칸의 가짜다 — 부른 인자를 차례로 적는다. err 가 있으면 그것을 돌려준다.
type recordStarted struct {
	calls []startedCall
	err   error
}

func (r *recordStarted) fn(_ context.Context, _ pgx.Tx, round, ingest string, at time.Time) error {
	r.calls = append(r.calls, startedCall{round, ingest, at})
	return r.err
}

func newStartedRegistry(rec *recordStarted) *Registry {
	return New(Options{
		FloorSlack: time.Second, ObsFresh: 30 * time.Second, ReconnectWindow: 300 * time.Second, StopSlack: 8 * time.Second,
		EnqueueStarted: rec.fn, Log: slog.New(slog.DiscardHandler),
	})
}

// 첫 개시 · 표식 분할은 새 회차라 칸을 한 번씩 부르고(회차 = 새 session_id · 시각 = 머리 started_at), TD 분할 · init
// 불일치 재접속은 같은 회차라 부르지 않는다. 잡는 결함: 세션 단위로 부르면 한 회차의 started 가 여럿이 된다(D49).
func TestOpenCallsStartedSlotOnlyForNewRound(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	var rec recordStarted
	r := newStartedRegistry(&rec)

	first := openOne(t, pool, r, Input{StreamID: "demo", Seq: 1, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent})
	seedSegment(t, pool, "demo", first, 1, wall)
	split := openOne(t, pool, r, Input{StreamID: "demo", Seq: 2, StartWallUTC: wall.Add(4 * time.Second), DurationMS: 9000, Op: OpenOrCurrent})
	seedSegment(t, pool, "demo", split, 2, wall.Add(4*time.Second))
	if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET state='ending', ending_at=now(), end_reason='init_mismatch' WHERE session_id=$1`, split); err != nil {
		t.Fatalf("init 불일치 종료 흉내 실패: %v", err)
	}
	joined := openOne(t, pool, r, Input{StreamID: "demo", Seq: 3, StartWallUTC: wall.Add(20 * time.Second), DurationMS: 4000, Op: OpenOrCurrent})
	if split == "" || joined == "" {
		t.Fatalf("개시가 서지 않았다 (분할 %q, 재접속 %q)", split, joined)
	}
	if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET stop_marked_at=$2 WHERE session_id=$1`, joined, wall.Add(30*time.Second)); err != nil {
		t.Fatalf("표식 흉내 실패: %v", err)
	}
	marked := openOne(t, pool, r, Input{StreamID: "demo", Seq: 4, StartWallUTC: wall.Add(40 * time.Second), DurationMS: 4000, Op: OpenOrCurrent})

	want := []startedCall{{first, "demo", wall}, {marked, "demo", wall.Add(40 * time.Second)}}
	if len(rec.calls) != len(want) {
		t.Fatalf("칸 호출 %d번 %+v, want %d번 %+v(첫 개시 · 표식 분할만)", len(rec.calls), rec.calls, len(want), want)
	}
	for i, w := range want {
		if g := rec.calls[i]; g.round != w.round || g.ingest != w.ingest || !g.at.Equal(w.at) {
			t.Errorf("칸 호출 %d = %+v, want %+v", i, g, w)
		}
	}
}

// 칸이 실패하면 개시도 실패다 — outbox 행 없이 세션만 커밋되면 그 회차의 started 가 영영 없다(K9 ㈎ 원자성).
func TestOpenFailsWhenStartedSlotFails(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	slotErr := errors.New("outbox 실패")
	r := newStartedRegistry(&recordStarted{err: slotErr})
	withTx(t, pool, func(tx pgx.Tx) {
		in := Input{StreamID: "demo", Seq: 1, StartWallUTC: wall, DurationMS: 4000, Op: OpenOrCurrent}
		d, err := r.Decide(ctx, tx, in, wall)
		if err != nil {
			t.Fatalf("결정 실패: %v", err)
		}
		if _, err := r.Open(ctx, tx, d, wall); !errors.Is(err, slotErr) {
			t.Fatalf("Open 오류 = %v, want 칸 오류를 감싼 값", err)
		}
	})
}
