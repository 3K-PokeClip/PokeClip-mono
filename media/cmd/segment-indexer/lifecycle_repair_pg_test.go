package main

// J6′ — 꺼진 사이드카가 닫은 회차의 ended 를 같은 DB 의 발행 사이드카가 보정으로 낸다(sgd 설계 8절 · R8). 꺼진 쪽은
// outbox 문장을 하나도 보내지 않으므로(영값 게이트) outbox 권한이 전혀 없는 역할로 돌려 그것을 결과로 증명한다. 옛 판
// 꺼짐 · 강등 사이드카도 같은 경로(영값 게이트 → 문장 0)라 이 시험이 옛 판 갈래도 함께 고정한다.

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/broadcast"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// outboxCounter 는 풀이 보낸 문장을 센다(pgx.QueryTracer) — 전체 수 · outbox 를 글자로 담은 수 · 42501 수.
type outboxCounter struct {
	mu                    sync.Mutex
	total, outbox, denied int
}

func (c *outboxCounter) TraceQueryStart(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.total++
	if strings.Contains(d.SQL, "broadcast_lifecycle_outbox") {
		c.outbox++
	}
	return ctx
}

func (c *outboxCounter) TraceQueryEnd(_ context.Context, _ *pgx.Conn, d pgx.TraceQueryEndData) {
	if !pgtest.IsInsufficientPrivilege(d.Err) {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.denied++
}

func (c *outboxCounter) counts() (total, outbox, denied int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.total, c.outbox, c.denied
}

// J6′ — no_topic 사이드카 A(영값 게이트 · outbox 권한 0)가 회차 R 을 닫은 뒤 발행 사이드카 B(media_indexer 역할)를
// 띄우면, B 의 첫 tick 보정(기동 뒤 곧바로)이 R.ended 를 넣어 내고 WARN lifecycle_ended_repaired 가 한 줄이다. A 는
// outbox 문장 0 · 42501 0 이다.
// cmd 시험은 보정 주기를 바꿀 수 없어 「닫힘 뒤 기동」 순서로 30초 기다림을 없앤다.
// 잡는 결함: 발행기 보정이 없으면 꺼진 쪽이 닫은 회차의 ended 가 영영 없다(P0 · V1).
func TestOffSidecarClosedRoundIsRepairedByPublisherJ6(t *testing.T) {
	admin := newWireTestPool(t)
	ctx := context.Background()
	const s, round = "j6", "J6-R"
	var now time.Time
	if err := admin.QueryRow(ctx, "SELECT pg_catalog.now()").Scan(&now); err != nil {
		t.Fatalf("DB 시계 읽기: %v", err)
	}
	start := now.Add(-time.Hour) // 보정 창(58일) 안 — 고정 시각(wireBase)은 날짜가 지나면 창 밖이 된다
	seedOutboxSession(t, admin, round, s, "ended", "offline", round, "", "frozen", start, time.Time{})
	seedOutboxSegment(t, admin, s, round, 1, start)
	if _, err := admin.Exec(ctx, `
INSERT INTO broadcast_lifecycle_outbox (event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id, streamer_id, state)
VALUES ($1, $2, $3, 1, $4, $5, '42', 'published')`, broadcast.StartedEventID(round), round, broadcast.EventStarted, start, s); err != nil {
		t.Fatalf("started 픽스처 실패: %v", err)
	}
	counter := closeWithOffSidecar(t, admin, round)

	log, buf := expiryLog()
	var sent sync.Map
	publish := func(_ context.Context, m broadcast.Message) error { sent.Store(m.DeduplicationID, true); return nil }
	b := pgtest.NewMediaIndexerRole(t, admin) // B 도 운영 역할 권한(30_grants.sql)으로 보정한다
	stop, err := runLifecyclePublisher(t.Context(), b.Pool, broadcast.PublisherOptions{Publish: publish, Log: log})
	if err != nil {
		t.Fatalf("발행기 기동 실패: %v", err)
	}
	state := waitOutboxState(t, admin, broadcast.EndedEventID(round), "published", 10*time.Second)
	stop()

	if _, ok := sent.Load(broadcast.EndedEventID(round)); state != "published" || !ok {
		t.Fatalf("R.ended state %q · SNS 에 냄 %v, want published · 참 — 전체 %s", state, ok, buf.String())
	}
	repaired := logLines(t, buf, "lifecycle_ended_repaired")
	if len(repaired) != 1 || repaired[0]["round"] != round || repaired[0]["level"] != "WARN" {
		t.Errorf("lifecycle_ended_repaired %v, want WARN round=%s 한 줄", repaired, round)
	}
	if total, outbox, denied := counter.counts(); total == 0 || outbox != 0 || denied != 0 {
		t.Errorf("A 의 문장 전체 %d · outbox 글자 %d · 42501 %d, want >0 · 0 · 0", total, outbox, denied)
	}
}

// closeWithOffSidecar 는 꺼진 사이드카 A 의 조립(영값 게이트)과 outbox 권한을 모두 거둔 media_indexer 역할로 회차
// round 를 실제 회차 스캔 · 닫힘 경로로 닫고, A 의 문장 계수기를 돌려준다.
func closeWithOffSidecar(t *testing.T, admin *pgxpool.Pool, round string) *outboxCounter {
	t.Helper()
	ctx := context.Background()
	role := pgtest.NewMediaIndexerRole(t, admin)
	for _, p := range []string{"SELECT", "INSERT", "UPDATE", "DELETE"} {
		role.Revoke(t, p, "broadcast_lifecycle_outbox")
	}
	counter := &outboxCounter{}
	a := pgtest.TracedPool(ctx, t, role.Pool, counter)
	fa := newFinalizeAssembly(config.Config{ReconnectWindow: 300 * time.Second}, broadcast.Gate{}, slog.New(slog.DiscardHandler))
	rounds, err := index.ListClosableRounds(ctx, a, 50, fa.close)
	if err != nil || len(rounds) != 1 || rounds[0].RoundID != round {
		t.Fatalf("회차 스캔 = %+v err %v, want [%s]", rounds, err, round)
	}
	if closed, err := index.CloseRound(ctx, a, rounds[0], fa.close, fa.enqueue); err != nil || !closed {
		t.Fatalf("CloseRound = %v err %v, want 닫힘", closed, err)
	}
	if n := outboxCount(t, admin, round, broadcast.EventEnded); n != 0 {
		t.Fatalf("꺼진 쪽 닫힘 뒤 ended %d 행, want 0 — 보정 전에 ended 가 생겼다", n)
	}
	return counter
}

// waitOutboxState 는 행 eventID 의 state 가 want 가 되기를 limit 까지 기다리고 마지막 값을 돌려준다(행이 없으면 "").
func waitOutboxState(t *testing.T, pool *pgxpool.Pool, eventID, want string, limit time.Duration) string {
	t.Helper()
	var state string
	for deadline := time.Now().Add(limit); time.Now().Before(deadline); time.Sleep(50 * time.Millisecond) {
		state = ""
		err := pool.QueryRow(context.Background(), `SELECT state FROM broadcast_lifecycle_outbox WHERE event_id = $1`, eventID).
			Scan(&state)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			t.Fatalf("outbox 조회 실패: %v", err)
		}
		if state == want {
			break
		}
	}
	return state
}
