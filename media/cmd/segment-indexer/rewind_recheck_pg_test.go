package main

// ending 재조회 × 실 장부(POK-525 Ⓒ-7 — 설계 5-3 · 8-2 E19). 상자 둘이 한 DB 를 본다. 각 상자는 상태 몫(Board · M5
// 받은편지함)과 운영 상태 읽기 몸체(index.LoadStreamPlayback)를 들고, 정산 스캔은 비워 스스로 장부를 바꾸지 않는다 —
// 전이는 다른 상자(또는 Writer)가 DB 에서 일으키고, 상태 표지는 어느 상자에도 오지 않는다. 루프 고루틴 자리는 시험
// 고루틴이다.

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// newRecheckBox 는 실 장부 pool 위의 상자 하나다. 상태 전달 sink 는 그 상자의 M5 받은편지함이라 읽기 결과가 Board 까지
// 간다.
func newRecheckBox(t *testing.T, pool *pgxpool.Pool) *finalLoop {
	t.Helper()
	f := &finalLoop{offlineLoop: newOfflineLoop(t, nil), lanes: publish.NewLanes(), inbox: session.NewPlaybackInbox(),
		sunk: &sinkLog{}}
	r := f.rewind
	r.lanes = f.lanes
	assembleStatus(t.Context(), bucketConfig(), r, nil)
	co := index.CloseOptions{Window: 300 * time.Second, Slack: 60 * time.Second, Log: slog.New(f.logs)}
	work := newFinalizeWork(pool, nil, co, noEndedEnqueue)
	work.scan = func(_ context.Context, after index.ScanCursor) finalScan { return finalScan{next: after} }
	r.fin = newFinalizer(finalizerOptions{Mode: finalize.ModeOff, Every: time.Second, CloseAfter: 360 * time.Second,
		Work: work, Inbox: f.inbox, Sink: r.finals.Post, PlaybackReadMax: 2})
	return f
}

// roundMode 는 상자 Board 의 회차 경로 답이다(없으면 빈 값).
func (f *finalLoop) roundMode(s, round string, now time.Time) status.Mode {
	st, answer, _ := f.rewind.board.Load().FindRound(s, round, now, statusStale)
	if answer != status.Found {
		return ""
	}
	return st.Mode
}

// stepBoxes 는 상자마다 holdTick 하나를 돌리고 결과를 모두 반영한다.
func stepBoxes(now time.Time, boxes ...*finalLoop) {
	for _, b := range boxes {
		b.hold(now)
		b.settle(now)
	}
}

// recheckStream 은 E19 시험의 스트림 · 회차다.
const (
	recheckStream = "e19"
	recheckRound  = "R19"
)

// plantFrozenRound 는 완결 · VOD 동결된 회차 하나다(행 둘 — 범위 첫 · 끝 조각).
func plantFrozenRound(t *testing.T, pool *pgxpool.Pool, now time.Time) {
	t.Helper()
	execSQL(t, pool, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ending_at, ended_at, round_id, round_closed_at,
                             vod_state, vod_s3_key, vod_first_seq, vod_last_seq)
VALUES ('S19', $1, $3, 'ended', $4, $4, $2, $4, 'frozen', 'vod/e19/R19/index.m3u8', 1, 2)`,
		recheckStream, recheckRound, now.Add(-time.Hour), now.Add(-10*time.Minute))
	execSQL(t, pool, `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, upload_state, session_id, playback_pdt)
VALUES ($1, 1, 0, $2, 4000, 'k1', 'uploaded', 'S19', $2), ($1, 2, 4000, $3, 4000, 'k2', 'uploaded', 'S19', $3)`,
		recheckStream, now.Add(-time.Hour), now.Add(-time.Hour+4*time.Second))
}

// awaitRoundMode 는 from 부터 1초 간격으로 상자들을 돌려, 회차 경로가 상자마다 want 가 되는 시각을 돌려준다. limit 안에
// 닿지 않은 상자가 있으면 FAIL 이다.
func awaitRoundMode(t *testing.T, from time.Time, limit time.Duration, want status.Mode, boxes ...*finalLoop) time.Time {
	t.Helper()
	for now := from; !now.After(from.Add(limit)); now = now.Add(time.Second) {
		stepBoxes(now, boxes...)
		reached := true
		for _, b := range boxes {
			reached = reached && b.roundMode(recheckStream, recheckRound, now) == want
		}
		if reached {
			return now
		}
	}
	for i, b := range boxes {
		t.Errorf("상자 %d 회차 경로 = %q, want %q(%v 안에)", i, b.roundMode(recheckStream, recheckRound, from.Add(limit)), want, limit)
	}
	t.FailNow()
	return time.Time{}
}

// E19 — 다른 상자의 Writer 가 해제 행을 지워도, 상태 표지를 받지 못한 두 상자의 Board 가 재조회 주기 몇 번 안에 vod 로
// 넘어간다(읽기 상한 넉넉 — 2). 지운 직후 주기 안에서는 ending 에 머문다(재조회 말고 알리는 길이 없다). 판정식의 「원장
// 행이 있으면 ending」 줄(Ⓒ-3)에 기댄다. 변이 = 재조회 제거 → 주기 몇 번이 지나도 ending 고착 FAIL.
func TestEndingRecheckE19LedgerDeleteByOtherBoxTurnsBoardsVOD(t *testing.T) {
	pool := newWireTestPool(t)
	execSQL(t, pool, `DELETE FROM stream_edge_bounds`)
	t.Cleanup(func() { _, _ = pool.Exec(context.Background(), `DELETE FROM stream_edge_bounds`) })
	plantFrozenRound(t, pool, time.Now().UTC().Truncate(time.Microsecond))
	execSQL(t, pool, `INSERT INTO stream_edge_bounds (stream_id, round_id, dvr_floor, released_at)
VALUES ($1, $2, 7, pg_catalog.now() - interval '1 hour')`, recheckStream, recheckRound)
	a, b := newRecheckBox(t, pool), newRecheckBox(t, pool)
	a.inbox.Changed(recheckStream) // 각 상자가 회차를 처음 아는 자리(커밋 표지)
	b.inbox.Changed(recheckStream)
	settled := awaitRoundMode(t, loopBase, 2*time.Second, status.ModeEnding, a, b)

	if n, err := index.NewEdgeBoundsLedger(pool).DeleteReleased(t.Context(), 0); err != nil || n != 1 {
		t.Fatalf("상자 A Writer 의 DeleteReleased = (%d, %v), want (1, nil)", n, err)
	}
	beforePeriod := loopBase.Add(endingRecheckEvery - time.Second)
	for now := settled.Add(time.Second); !now.After(beforePeriod); now = now.Add(time.Second) {
		stepBoxes(now, a, b)
	}
	if m := b.roundMode(recheckStream, recheckRound, beforePeriod); m != status.ModeEnding {
		t.Fatalf("주기 전 상자 B 회차 경로 = %q, want ending(표지 없이는 아직 모른다)", m)
	}
	awaitRoundMode(t, beforePeriod.Add(time.Second), 2*endingRecheckEvery, status.ModeVOD, a, b)
}

// 5-3 끝 문단 — 같은 틈의 기존 몫: 다른 상자가 끝낸 회차 닫힘 · VOD 동결도 표지 없이 재조회로 활성 상자에 닿는다. 원장
// 표와 무관하다(Ⓒ-3 없이 선다). 변이 = 재조회 제거 → ending 고착 FAIL.
func TestEndingRecheckPicksUpVODFrozenByOtherBox(t *testing.T) {
	pool := newWireTestPool(t)
	now := time.Now().UTC().Truncate(time.Microsecond)
	plantFrozenRound(t, pool, now)
	execSQL(t, pool, `UPDATE stream_sessions SET state = 'ending', ended_at = NULL, round_closed_at = NULL,
       vod_state = 'none', vod_s3_key = NULL, vod_first_seq = NULL, vod_last_seq = NULL WHERE session_id = 'S19'`)
	b := newRecheckBox(t, pool)
	b.inbox.Changed(recheckStream)
	settled := awaitRoundMode(t, loopBase, 2*time.Second, status.ModeEnding, b)

	// 다른 상자의 정산이 회차를 닫고 VOD 를 동결했다 — 이 상자에는 표지가 오지 않는다.
	execSQL(t, pool, `UPDATE stream_sessions SET state = 'ended', ended_at = $1, round_closed_at = $1, vod_state = 'frozen',
       vod_s3_key = 'vod/e19/R19/index.m3u8', vod_first_seq = 1, vod_last_seq = 2 WHERE session_id = 'S19'`, now)
	awaitRoundMode(t, settled.Add(time.Second), 2*endingRecheckEvery, status.ModeVOD, b)
}
