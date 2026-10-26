package main

// 회차 소속 배선 시험(POK-196 M6-3a 커밋 ①) — 조립이 표식 여유(K8 8초)를 레지스트리에 싣고, 개시가 끝내려던 live 를
// 잃은 실패(session.ErrLiveLost)를 장부의 개시 경합(index.ErrSessionContended — 재시도)으로 옮기는가.

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

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
