package main

// 4D 수신부 통합(POK-472 설계 8절) — 실 ApplyIntentionalEnd + 가짜 resolve 로 live 세션에 요청을 보낸다. 효과 셋 · Skip
// 일곱 자체의 pg 시험은 M6-3a(internal/session) 몫이다.

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// live 세션에 4D → 202 · stop_marked_at 기록 · 표지 1. 같은 요청을 다시 보내면 장부는 그대로다(표식 COALESCE — 멱등).
func TestEndSignalAppliesToLiveSessionPG(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := context.Background()
	now := time.Now().UTC().Truncate(time.Millisecond)
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, vod_state, round_id, target_duration)
VALUES ('S-X', $1, $2, 'live', 'none', 'S-X', 6)`, wireToken, now.Add(-time.Hour)); err != nil {
		t.Fatalf("세션 픽스처 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id)
VALUES ($1, 1, 0, $2, 4000, 'k-1', '/recordings/x/1.mp4', 'uploaded', 1000, 'S-X')`, wireToken, now.Add(-6*time.Second)); err != nil {
		t.Fatalf("조각 픽스처 실패: %v", err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	inbox, intents := session.NewPlaybackInbox(), session.NewIntentInbox()
	recv, err := newEndSignalReceiver(endSignalDeps{
		resolve: fakeResolver(t), apply: intentApplier(pool, newIntentOptions(log, nil, nil)),
		changed: inbox.Changed, postIntent: intents.Post, log: log,
	})
	if err != nil {
		t.Fatalf("수신부 조립 실패: %v", err)
	}
	srv := httptest.NewServer(recv.Route().Handler)
	defer srv.Close()

	send := func() int {
		body := `{"streamid":"#!::r=` + wireToken + `,m=publish","elapsedSinceStopMs":420,"connectionDurationMs":3600000}`
		req, _ := http.NewRequest(http.MethodPost, srv.URL+"/api/ingest/end-signal", strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+wireBearer)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("요청 실패: %v", err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	markOf := func() *time.Time {
		var m *time.Time
		if err := pool.QueryRow(ctx, `SELECT stop_marked_at FROM stream_sessions WHERE session_id = 'S-X'`).Scan(&m); err != nil {
			t.Fatalf("표식 조회 실패: %v", err)
		}
		return m
	}

	if code := send(); code != http.StatusAccepted {
		t.Fatalf("첫 요청 = %d, want 202", code)
	}
	first := markOf()
	if first == nil {
		t.Fatal("stop_marked_at 이 비었다 — live 에 표식이 없다")
	}
	if got := inbox.Drain(); !slices.Equal(got, []string{wireToken}) {
		t.Fatalf("표지 = %v, want [%s]", got, wireToken)
	}
	if got := intents.Drain(); len(got) != 0 {
		t.Fatalf("계승 해제 요청 = %v, want 없음", got)
	}

	time.Sleep(1100 * time.Millisecond) // 키당 초당 1 창을 넘긴다
	if code := send(); code != http.StatusAccepted {
		t.Fatalf("반복 요청 = %d, want 202", code)
	}
	if again := markOf(); again == nil || !again.Equal(*first) {
		t.Fatalf("반복 뒤 표식 = %v, want %v 그대로(멱등)", again, first)
	}
	var state string
	if err := pool.QueryRow(ctx, `SELECT state FROM stream_sessions WHERE session_id = 'S-X'`).Scan(&state); err != nil || state != "live" {
		t.Fatalf("state = %q (%v), want live — 4D 는 live 를 끝내지 않는다", state, err)
	}
}

// P4(설계 7절) — 실 intentApplier 가 고갈된 풀을 만나도 Apply 시한(1.5초)에 503 apply_timeout 으로 돌아온다. 크기 2 풀의
// 연결을 시험이 모두 쥔 채 4D 를 보낸다. 풀 획득 대기가 Apply ctx 를 따르지 않으면 쥔 연결이 풀릴 때까지 매달린다(가짜
// Apply 의 시한만 보는 TestApplyTimeoutP4 가 못 보는 쪽).
func TestEndSignalPoolExhaustedIs503WithinApplyTimeoutPG(t *testing.T) {
	db := newWireTestPool(t)
	cfg := db.Config()
	cfg.MaxConns = 2
	small, err := pgxpool.NewWithConfig(t.Context(), cfg)
	if err != nil {
		t.Fatalf("크기 2 풀 생성 실패: %v", err)
	}
	t.Cleanup(small.Close)
	var held []*pgxpool.Conn
	for range cfg.MaxConns {
		c, err := small.Acquire(t.Context())
		if err != nil {
			t.Fatalf("연결 점유 실패: %v", err)
		}
		held = append(held, c)
	}
	release := sync.OnceFunc(func() {
		for _, c := range held {
			c.Release()
		}
	})

	logs, log := newLockedLogger()
	recv, err := newEndSignalReceiver(endSignalDeps{
		resolve: fakeResolver(t), apply: intentApplier(small, newIntentOptions(log, nil, nil)),
		changed: func(string) {}, postIntent: func(string, string) {}, log: log,
	})
	if err != nil {
		t.Fatalf("수신부 조립 실패: %v", err)
	}
	srv := httptest.NewServer(recv.Route().Handler)
	t.Cleanup(srv.Close)
	t.Cleanup(release) // 나중에 등록해 먼저 돈다 — 매달린 처리기가 있어도 srv.Close 가 기다리다 멈추지 않는다

	body := `{"streamid":"#!::r=` + wireToken + `,m=publish","elapsedSinceStopMs":420,"connectionDurationMs":3600000}`
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/api/ingest/end-signal", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+wireBearer)
	start := time.Now()
	resp, err := (&http.Client{Timeout: 5 * time.Second}).Do(req)
	el := time.Since(start)
	release()
	if err != nil {
		t.Fatalf("요청 실패(%v 뒤): %v — 풀 획득 대기가 Apply 시한을 따르지 않았다", el, err)
	}
	resp.Body.Close()
	if el > 1600*time.Millisecond {
		t.Fatalf("응답까지 %v, want ≤ 1.6s", el)
	}
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("상태 = %d, want 503", resp.StatusCode)
	}
	if i := indexOf(logs.msgs(t), "end_signal:apply_timeout"); i < 0 {
		t.Fatalf("로그 = %v, want end_signal reason=apply_timeout", logs.msgs(t))
	}
}
