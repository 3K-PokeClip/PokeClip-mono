package main

// 회차 소속 배선 시험(POK-196 M6-3a 커밋 ①) — 조립이 표식 여유(K8 8초)를 레지스트리에 싣고, 개시가 끝내려던 live 를
// 잃은 실패(session.ErrLiveLost)를 장부의 개시 경합(index.ErrSessionContended — 재시도)으로 옮기는가.

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// 조립된 결정자는 live 의 표식 + 8초 조각에서 표식 분할(TD 분할처럼 개시 갈래)을 낸다. 잡는 결함: 조립이 여유를
// 싣지 않으면 영값 = 「표식으로 가르지 않음」이라 의도적 종료가 회차를 끊지 못한다.
func TestSessionDeciderSplitsAtStopMarkPlusEightSeconds(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := context.Background()
	mark := wireBase
	if _, err := pool.Exec(ctx, `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, first_pdt, round_id, stop_marked_at)
		VALUES ('S-live', 'marked', $1, 'live', $1, 'S-live', $2)`, mark.Add(-time.Hour), mark); err != nil {
		t.Fatalf("표식 live 픽스처 실패: %v", err)
	}
	d := newSessionDecider(time.Second, 30*time.Second, 300*time.Second, slog.New(slog.NewTextHandler(io.Discard, nil)))
	for _, c := range []struct {
		off       time.Duration
		wantOpens bool
	}{{8*time.Second - time.Millisecond, false}, {8 * time.Second, true}} {
		tx, err := pool.Begin(ctx)
		if err != nil {
			t.Fatalf("트랜잭션 시작 실패: %v", err)
		}
		dec, err := d.Decide(ctx, tx, index.SessionInput{
			StreamID: "marked", Seq: 9, StartWallUTC: mark.Add(c.off), DurationMS: 2000,
			SessionSource: index.SessionSource{Op: index.SessionOpenOrCurrent},
		}, mark.Add(c.off))
		_ = tx.Rollback(ctx)
		if err != nil {
			t.Fatalf("Decide 실패: %v", err)
		}
		if dec.Opens != c.wantOpens || (c.wantOpens && dec.EndingSessionID != "S-live") {
			t.Errorf("표식 + %v → (개시 %v, ending %q), want (%v, S-live 이면 개시)", c.off, dec.Opens, dec.EndingSessionID, c.wantOpens)
		}
	}
}

// ErrLiveLost 는 재시도 갈래(ErrSessionContended)로 간다 — 그 밖으로 가면 무결성 오류 경로(fatal)가 된다.
func TestSessionDeciderOpenMapsLiveLostToContended(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, first_pdt, round_id)
		VALUES ('S-x', 'lost', $1, 'live', $1, 'S-x')`, wireBase.Add(-time.Hour)); err != nil {
		t.Fatalf("live 픽스처 실패: %v", err)
	}
	d := newSessionDecider(time.Second, 30*time.Second, 300*time.Second, slog.New(slog.NewTextHandler(io.Discard, nil)))
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	dec, err := d.Decide(ctx, tx, index.SessionInput{
		StreamID: "lost", Seq: 9, StartWallUTC: wireBase, DurationMS: 9000,
		SessionSource: index.SessionSource{Op: index.SessionOpenOrCurrent},
	}, wireBase)
	if err != nil || !dec.Opens {
		t.Fatalf("Decide = (%+v, %v), want TD 분할", dec, err)
	}
	if _, err := tx.Exec(ctx, `UPDATE stream_sessions SET state='ending', end_reason='offline' WHERE session_id='S-x'`); err != nil {
		t.Fatalf("종료 흉내 실패: %v", err)
	}
	_, err = d.Open(ctx, tx, dec, wireBase)
	if !errors.Is(err, index.ErrSessionContended) || !errors.Is(err, session.ErrLiveLost) {
		t.Errorf("Open 오류 = %v, want ErrSessionContended 로 감싼 ErrLiveLost", err)
	}
}

// IntentOptions 조립(M6 10절 · 계획 4.12 커밋 ②) — 함수 칸이 비면 무동작 + WARN intentional_round_close_unwired 기본값을
// 끼운다(nil 호출 panic 금지). 수치는 4초 · 8초(K8)이고 표식 여유는 레지스트리와 같은 상수다. 채운 칸은 그대로 둔다
// (M6-3b 가 CloseRound, M6-8 이 EnqueueStarted 를 채운다).
func TestNewIntentOptionsFillsUnwiredSlotsWithWarnNoop(t *testing.T) {
	var logs bytes.Buffer
	o := newIntentOptions(slog.New(slog.NewTextHandler(&logs, nil)), nil, nil)

	if o.ShortConn != 4*time.Second || o.Slack != 8*time.Second || intentStopSlack != 8*time.Second {
		t.Errorf("수치 = (%v, %v), want (4s, 8s — intentStopSlack)", o.ShortConn, o.Slack)
	}
	if o.CloseRound == nil || o.EnqueueStarted == nil || o.Log == nil {
		t.Fatal("비어 있는 칸이 남았다 — 4D 적용이 오류로 돈다")
	}
	if err := o.CloseRound(context.Background(), nil, "S-R"); err != nil {
		t.Errorf("기본 CloseRound = %v, want nil(무동작)", err)
	}
	if err := o.EnqueueStarted(context.Background(), nil, "S-B", "demo", wireBase); err != nil {
		t.Errorf("기본 EnqueueStarted = %v, want nil(무동작)", err)
	}
	out := logs.String()
	if strings.Count(out, "intentional_round_close_unwired") != 2 || !strings.Contains(out, "level=WARN") ||
		!strings.Contains(out, "slot=close_round") || !strings.Contains(out, "slot=enqueue_started") {
		t.Errorf("WARN 두 줄(slot 둘)이 아니다:\n%s", out)
	}

	called := false
	custom := newIntentOptions(slog.New(slog.NewTextHandler(io.Discard, nil)),
		func(context.Context, pgx.Tx, string) error { called = true; return nil }, nil)
	_ = custom.CloseRound(context.Background(), nil, "S-R")
	if !called {
		t.Error("채운 CloseRound 칸을 기본값으로 덮었다")
	}
}

// 기본값 조립으로 실제 4D 적용이 돈다 — M6-3a 만 머지된 판에서 upgraded 는 사유 · 표식만 쓰고(회차 닫힘은 M6-3b 의 회차
// 스캔이 거둔다) panic 하지 않는다.
func TestDefaultIntentOptionsApplyWithoutPanic(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, first_pdt, round_id)
		VALUES ('S-T', 'intent', $1, 'ending', 'offline', $2, $1, 'S-R')`, wireBase.Add(-time.Hour), wireBase); err != nil {
		t.Fatalf("세션 픽스처 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id)
		VALUES ('intent', 1, 0, $1, 4000, 'k-1', '/recordings/intent/1.mp4', 'uploaded', 1000, 'S-T')`, wireBase.Add(-5*time.Second)); err != nil {
		t.Fatalf("조각 픽스처 실패: %v", err)
	}
	o := newIntentOptions(slog.New(slog.NewTextHandler(io.Discard, nil)), nil, nil)
	res, err := session.ApplyIntentionalEnd(ctx, pool, session.IntentionalEnd{
		StreamID: "intent", StoppedAt: wireBase, ConnStartedAt: wireBase.Add(-time.Hour),
	}, o)
	if err != nil || res.Effect != "upgraded" {
		t.Fatalf("결과 = (%+v, %v), want upgraded", res, err)
	}
	var reason string
	if err := pool.QueryRow(ctx, `SELECT end_reason FROM stream_sessions WHERE session_id='S-T'`).Scan(&reason); err != nil || reason != "intentional" {
		t.Errorf("end_reason = (%q, %v), want intentional", reason, err)
	}
}

// seed 칸(계획 4.2 · 4.12 커밋 ④) — 기동 적재는 index.LoadPlaybackStates(within 30분 — 경계 확정 3)다. 닫힘 전 Ending 은
// 시각과 무관하게 실리고, 40분 전 완결 값은 빠진다. 잡는 결함: within 을 다른 값으로 두면 재기동 뒤 끝난 방송이 404 가
// 되거나(짧음) 오래된 VOD 가 Board 에 올라간다(김).
func TestPlaybackSeedLoadsStatesWithinThirtyMinutes(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := context.Background()
	now := time.Now().UTC()
	for _, q := range []struct {
		sql  string
		args []any
	}{
		{`INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ending_at, round_id)
		  VALUES ('S-a', 'a', $1, 'ending', $2, 'S-a')`, []any{now.Add(-3 * time.Hour), now.Add(-2 * time.Hour)}},
		{`INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ending_at, ended_at, round_id, round_closed_at)
		  VALUES ('S-b', 'b', $1, 'ended', $2, $2, 'S-b', $3)`, []any{now.Add(-time.Hour), now.Add(-25 * time.Minute), now.Add(-26 * time.Minute)}},
		{`INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ending_at, ended_at, round_id, round_closed_at)
		  VALUES ('S-c', 'c', $1, 'ended', $2, $2, 'S-c', $3)`, []any{now.Add(-2 * time.Hour), now.Add(-40 * time.Minute), now.Add(-41 * time.Minute)}},
	} {
		if _, err := pool.Exec(ctx, q.sql, q.args...); err != nil {
			t.Fatalf("픽스처 실패: %v", err)
		}
	}

	got, err := newPlaybackSeed(pool)(ctx)

	if err != nil {
		t.Fatalf("seed 실패: %v", err)
	}
	if got["a"].Phase != index.PhaseEnding || got["b"].Phase != index.PhaseEnded || len(got) != 2 {
		t.Errorf("seed = %+v, want a Ending · b Ended(25분) · c 없음(40분)", got)
	}
}

// main.go 는 seed 칸에 newPlaybackSeed(pool) 를 넘긴다(②a 의 nil 을 채움 — 계획 5절 main.go 차례 M6-3a). 호출 한 줄의
// 배선이라 조립 문장을 직접 대조한다 — nil 로 돌아가면 재기동 적재가 조용히 꺼진다(INFO count=0).
func TestMainWiresPlaybackSeed(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 읽기 실패: %v", err)
	}
	if !strings.Contains(string(src), "assembleStatus(ctx, cfg, rewind, newPlaybackSeed(pool))") {
		t.Error("main.go 가 assembleStatus 의 seed 칸에 newPlaybackSeed(pool) 를 넘기지 않는다")
	}
}
