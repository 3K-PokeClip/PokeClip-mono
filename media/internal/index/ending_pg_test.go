package index

// 정산 스캔 · 빠른 길의 판독(POK-196 M6-3b 커밋 ① — 설계 M6 2.2 실행 가능 조건 · 커서 · 종결 정정 EndingSession ·
// 9절 D25 · D26 · D66). PG_DSN 미설정이면 전량 skip 된다.

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// endRow 는 종료 문장 시험의 세션 픽스처다. 빈 문자열 · 영값은 NULL(또는 열 기본값)이다.
type endRow struct {
	id, stream, state, endReason, dvr, vod, round, inherits, etag string
	startedAt, endingAt, freezingAt, stopMarkedAt, roundClosedAt  time.Time
	initUploaded                                                  bool
}

func optText(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func optTime(t time.Time) any {
	if t.IsZero() {
		return nil
	}
	return t
}

// putEnd 는 세션 한 행을 심는다. state 기본 ending · dvr open · vod none · ending_at 은 시작 1분 뒤다.
func putEnd(t *testing.T, pool *pgxpool.Pool, r endRow) {
	t.Helper()
	if r.state == "" {
		r.state = "ending"
	}
	if r.dvr == "" {
		r.dvr = "open"
	}
	if r.vod == "" {
		r.vod = "none"
	}
	if r.endingAt.IsZero() && r.state != "live" {
		r.endingAt = r.startedAt.Add(time.Minute)
	}
	var initAt any
	if r.initUploaded {
		initAt = r.startedAt
	}
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions
    (session_id, stream_id, started_at, state, end_reason, ending_at, dvr_state, vod_state, round_id, round_closed_at,
     stop_marked_at, inherits_session, dvr_freezing_at, manifest_etag, init_uploaded_at)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)`,
		r.id, r.stream, r.startedAt, r.state, optText(r.endReason), optTime(r.endingAt), r.dvr, r.vod, optText(r.round),
		optTime(r.roundClosedAt), optTime(r.stopMarkedAt), optText(r.inherits), optTime(r.freezingAt), optText(r.etag),
		initAt); err != nil {
		t.Fatalf("세션 픽스처 실패 %s: %v", r.id, err)
	}
}

// putSeg 는 세션 조각 한 행이다 — 4초 · ② uploaded · ③ 은 pbUploaded 이면 uploaded 다.
func putSeg(t *testing.T, pool *pgxpool.Pool, stream, session string, seq int64, start time.Time, pbUploaded bool) {
	t.Helper()
	pb := "pending"
	if pbUploaded {
		pb = "uploaded"
	}
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id,
     playback_pdt, playback_s3_key, playback_upload_state)
VALUES ($1, $2, 0, $3, 4000, $4, $5, 'uploaded', 1000, $6, $3, $7, $8)`,
		stream, seq, start, fmt.Sprintf("k-%s-%d", stream, seq), fmt.Sprintf("/recordings/%s/%d.mp4", stream, seq),
		session, fmt.Sprintf("p/%s/%d.m4s", stream, seq), pb); err != nil {
		t.Fatalf("조각 픽스처 실패 %s/%d: %v", stream, seq, err)
	}
}

// testClose 는 운영 값(재접속 창 300초 · 닫힘 여유 60초)이다.
var testClose = CloseOptions{Window: 300 * time.Second, Slack: 60 * time.Second, Log: slog.New(slog.DiscardHandler)}

func scanIDs(t *testing.T, pool *pgxpool.Pool, after ScanCursor, limit int) ([]string, ScanCursor) {
	t.Helper()
	rows, next, err := ListEndingSessions(context.Background(), pool, after, limit, testClose)
	if err != nil {
		t.Fatalf("ListEndingSessions 실패: %v", err)
	}
	var ids []string
	for _, r := range rows {
		ids = append(ids, r.SessionID)
	}
	return ids, next
}

// 실행 가능 조건(설계 2.2 표) — 지금 다음 걸음이 있는 행만 고른다. 후속을 기다리는 비꼬리 · 창이 안 지난 꼬리 ·
// 갓 freezing 이 된 행(다른 writer 가 동결 중) · live · ended 는 고르지 않는다.
func TestListEndingSessionsSelectsOnlyExecutableRows(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now()
	old := now.Add(-20 * time.Minute)
	const s = "end-exec"
	rows := []endRow{
		{id: "open", dvr: "open", startedAt: old},
		{id: "freezing-young", dvr: "freezing", freezingAt: now.Add(-10 * time.Second), startedAt: old},
		{id: "freezing-old", dvr: "freezing", freezingAt: now.Add(-90 * time.Second), startedAt: old},
		{id: "parent", dvr: "frozen", endReason: "offline", round: "R1", initUploaded: true, startedAt: old},
		{id: "child", dvr: "frozen", endReason: "offline", round: "R1", inherits: "parent", initUploaded: true,
			startedAt: now.Add(-30 * time.Second), endingAt: now.Add(-20 * time.Second)},
		{id: "tail-intentional", dvr: "frozen", endReason: "intentional", initUploaded: true, startedAt: old.Add(time.Second)},
		{id: "tail-marked", dvr: "frozen", endReason: "offline", stopMarkedAt: old, initUploaded: true, startedAt: old.Add(2 * time.Second)},
		{id: "tail-window", dvr: "freeze_failed", endReason: "offline", initUploaded: true, startedAt: old.Add(3 * time.Second)},
		{id: "tail-unsettled-init", dvr: "frozen", endReason: "intentional", startedAt: now.Add(-2 * time.Minute)},
		{id: "vod-freezing", dvr: "frozen", vod: "freezing", initUploaded: true, startedAt: old.Add(4 * time.Second)},
		{id: "vod-frozen", dvr: "frozen", vod: "frozen", initUploaded: true, startedAt: old.Add(5 * time.Second)},
		{id: "live", state: "live", startedAt: now},
		{id: "ended", state: "ended", dvr: "frozen", vod: "frozen", startedAt: old.Add(6 * time.Second)},
	}
	for _, r := range rows {
		r.stream = s
		if r.id == "live" {
			r.stream = s + "-live"
		}
		putEnd(t, pool, r)
	}
	// child 는 창이 아직 안 지난 꼬리다(마지막 조각 끝 = 20초 전). tail-window 는 마지막 조각 끝이 20분 전이다.
	putSeg(t, pool, s, "child", 1, now.Add(-24*time.Second), true)
	putSeg(t, pool, s, "tail-window", 2, old, true)

	got, _ := scanIDs(t, pool, ScanCursor{}, 50)
	want := []string{"open", "freezing-old", "tail-intentional", "tail-marked", "tail-window", "vod-freezing", "vod-frozen"}
	slices.Sort(got)
	slices.Sort(want)
	if !slices.Equal(got, want) {
		t.Fatalf("실행 가능 행 = %q, want %q", got, want)
	}
}

// 커서(D66) — 후속을 기다리는 비꼬리 60 행은 쪽을 채우지 않고, 첫 스캔이 꼬리를 고른다.
func TestListEndingSessionsSkipsWaitingNonTailsD66(t *testing.T) {
	pool := newTestPool(t)
	base := time.Now().Add(-time.Hour)
	const s = "end-nontail"
	for i := range 60 {
		parent := fmt.Sprintf("n%02d", i)
		putEnd(t, pool, endRow{id: parent, stream: s, dvr: "frozen", endReason: "offline", round: "R", initUploaded: true,
			startedAt: base.Add(time.Duration(2*i) * time.Second)})
		putEnd(t, pool, endRow{id: parent + "-c", stream: s, state: "live", round: "R", inherits: parent,
			startedAt: base.Add(time.Duration(2*i+1) * time.Second)})
		if _, err := pool.Exec(context.Background(), `UPDATE stream_sessions SET state = 'ended' WHERE session_id = $1`, parent+"-c"); err != nil {
			t.Fatal(err)
		}
	}
	putEnd(t, pool, endRow{id: "tail", stream: s, dvr: "frozen", endReason: "intentional", initUploaded: true,
		startedAt: base.Add(10 * time.Minute)})

	got, next := scanIDs(t, pool, ScanCursor{}, 50)
	if !slices.Equal(got, []string{"tail"}) {
		t.Fatalf("첫 스캔 = %q, want [tail] — 비꼬리가 쪽을 채웠다", got)
	}
	if next != (ScanCursor{}) {
		t.Fatalf("쪽이 limit 보다 작은데 next = %+v, want 영값", next)
	}
}

// 커서(D66) — 정산 대기 꼬리(실행 가능 · 진행 없음) 60 행이 쪽을 채워도 keyset 커서로 두 스캔 안에 닫힐 꼬리에 닿는다.
func TestListEndingSessionsCursorReachesLaterTailD66(t *testing.T) {
	pool := newTestPool(t)
	base := time.Now().Add(-time.Hour)
	const s = "end-cursor"
	for i := range 60 {
		putEnd(t, pool, endRow{id: fmt.Sprintf("w%02d", i), stream: s, dvr: "frozen", vod: "freezing", initUploaded: true,
			startedAt: base.Add(time.Duration(i) * time.Second)})
	}
	putEnd(t, pool, endRow{id: "zz-tail", stream: s, dvr: "frozen", endReason: "intentional", initUploaded: true,
		startedAt: base.Add(5 * time.Minute)})

	first, next := scanIDs(t, pool, ScanCursor{}, 50)
	if len(first) != 50 || slices.Contains(first, "zz-tail") {
		t.Fatalf("첫 스캔 %d 행(꼬리 포함 %v), want 정산 대기 50 행", len(first), slices.Contains(first, "zz-tail"))
	}
	if next.SessionID != first[49] {
		t.Fatalf("next = %+v, want 마지막 행 %s", next, first[49])
	}
	second, wrap := scanIDs(t, pool, next, 50)
	if !slices.Contains(second, "zz-tail") || len(second) != 11 {
		t.Fatalf("둘째 스캔 = %q, want 나머지 10 + zz-tail", second)
	}
	if wrap != (ScanCursor{}) {
		t.Fatalf("마지막 쪽 뒤 next = %+v, want 영값(처음으로)", wrap)
	}
}

// 판독 칸 — 발행 흔적(manifest_etag ∨ ③ uploaded) · init 판정(확정 ∨ 정산 창 만료) · 후속 · LastEnd. 빠른 길
// (LoadEndingSession)은 스캔과 같은 판독식이라 같은 값이다(종결 정정 cc M1).
func TestEndingSessionColumnsMatchFastPath(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now()
	const s = "end-cols"
	putEnd(t, pool, endRow{id: "etag", stream: s, endReason: "offline", etag: `"e1"`, startedAt: now.Add(-3 * time.Minute)})
	putEnd(t, pool, endRow{id: "pb", stream: s, endReason: "offline", startedAt: now.Add(-2 * time.Minute), initUploaded: true})
	putSeg(t, pool, s, "pb", 1, now.Add(-100*time.Second), true)
	putEnd(t, pool, endRow{id: "bare", stream: s, endReason: "td_exceeded", round: "RB", startedAt: now.Add(-time.Minute)})
	putSeg(t, pool, s, "bare", 2, now.Add(-50*time.Second), false)
	putEnd(t, pool, endRow{id: "stale", stream: s + "-2", endReason: "offline", startedAt: now.Add(-40 * time.Minute),
		endingAt: now.Add(-16 * time.Minute)})

	rows, _, err := ListEndingSessions(context.Background(), pool, ScanCursor{}, 50, testClose)
	if err != nil {
		t.Fatal(err)
	}
	byID := map[string]EndingSession{}
	for _, r := range rows {
		byID[r.SessionID] = r
	}
	want := map[string]struct{ trace, settled bool }{
		"etag": {true, false}, "pb": {true, true}, "bare": {false, false}, "stale": {false, true},
	}
	for id, w := range want {
		r, ok := byID[id]
		if !ok {
			t.Fatalf("%s 가 스캔에 없다(dvr open 은 실행 가능)", id)
		}
		if r.HasPublishTrace != w.trace || r.InitSettled != w.settled {
			t.Errorf("%s: HasPublishTrace=%v InitSettled=%v, want %v %v", id, r.HasPublishTrace, r.InitSettled, w.trace, w.settled)
		}
		fast, found, err := LoadEndingSession(context.Background(), pool, id)
		if err != nil || !found {
			t.Fatalf("LoadEndingSession(%s) = found %v err %v", id, found, err)
		}
		fast.Now, r.Now = time.Time{}, time.Time{}
		if fast != r {
			t.Errorf("%s: 빠른 길 %+v ≠ 스캔 %+v", id, fast, r)
		}
	}
	if b := byID["bare"]; b.RoundID != "RB" || b.EndReason != "td_exceeded" || !b.LastEnd.Equal(now.Add(-46*time.Second).Truncate(time.Microsecond)) {
		t.Errorf("bare 행 = %+v — round · 사유 · LastEnd(마지막 조각 끝) 이 틀렸다", b)
	}
	if st := byID["stale"]; !st.LastEnd.Equal(st.EndingAt) {
		t.Errorf("조각 없는 행의 LastEnd = %v, want ending_at %v", st.LastEnd, st.EndingAt)
	}
	if _, found, err := LoadEndingSession(context.Background(), pool, "missing"); err != nil || found {
		t.Fatalf("없는 세션 = found %v err %v, want 없음", found, err)
	}
}

// D26 — 30초 스캔 비용. 세션 1만 행 · ending 300 행에서 실행 계획이 stream_sessions · stream_segments 를 순차 스캔하지
// 않는다(부분 인덱스 stream_sessions_ending_idx · 후속 EXISTS 는 같은 스트림 행만 · LastEnd 는 stream_segments_session_idx).
func TestListEndingSessionsPlanUsesIndexesD26(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ending_at, end_reason, init_uploaded_at)
SELECT 'p' || i, 'plan-' || (i % 400), pg_catalog.now() - pg_catalog.make_interval(secs => i),
       CASE WHEN i % 33 = 0 THEN 'ending' ELSE 'ended' END, pg_catalog.now() - pg_catalog.make_interval(secs => i - 1),
       'offline', pg_catalog.now()
  FROM pg_catalog.generate_series(1, 10000) AS i`); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, upload_state, session_id)
SELECT 'plan-' || (i % 400), i, 0, pg_catalog.now(), 4000, 'k' || i, 'uploaded', 'p' || i
  FROM pg_catalog.generate_series(1, 10000) AS i`); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `ANALYZE stream_sessions; ANALYZE stream_segments`); err != nil {
		t.Fatal(err)
	}
	var plan []byte
	if err := pool.QueryRow(ctx, `EXPLAIN (FORMAT JSON) `+listEndingSessionsSQL, endingArgs(ScanCursor{}, 50, testClose)...).Scan(&plan); err != nil {
		t.Fatalf("EXPLAIN 실패: %v", err)
	}
	var doc []map[string]any
	if err := json.Unmarshal(plan, &doc); err != nil {
		t.Fatal(err)
	}
	var seq []string
	walkPlan(doc[0]["Plan"], func(n map[string]any) {
		if n["Node Type"] == "Seq Scan" {
			seq = append(seq, fmt.Sprint(n["Relation Name"]))
		}
	})
	if len(seq) > 0 {
		t.Fatalf("정산 스캔 실행 계획에 순차 스캔 %q — 부분 인덱스 · 같은 스트림 조건이 빠졌다\n%s", seq, plan)
	}
	if !strings.Contains(string(plan), "stream_sessions_ending_idx") {
		t.Fatalf("정산 스캔이 stream_sessions_ending_idx 를 쓰지 않는다\n%s", plan)
	}
}

func walkPlan(n any, visit func(map[string]any)) {
	m, ok := n.(map[string]any)
	if !ok {
		return
	}
	visit(m)
	if kids, ok := m["Plans"].([]any); ok {
		for _, k := range kids {
			walkPlan(k, visit)
		}
	}
}
