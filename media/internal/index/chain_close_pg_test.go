package index

// 사슬 닫힘 · phase 4 · 꺼짐 빈 동결 · VOD 실패 확정(POK-196 M6-3b 커밋 ① — 설계 M6 2.2 · 3.3 · 종결 정정 D75 ·
// 9절 D25 · D55 다섯째 갈래 · D61). PG_DSN 미설정이면 전량 skip 된다.
//
// 배리어는 훅 대신 행 잠금으로 만든다(session/membership_pg_test.go 와 같은 꼴) — 다른 연결이 사슬 머리 행을 쥐면
// CloseChain 은 ③(사슬 행 잠금)에서 기다리고, 그동안 경쟁 문장(계승 해제)이 끼어든다.

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// recordEnqueue 는 완결 평가 칸의 가짜다 — 부른 회차를 차례로 적는다.
type recordEnqueue struct {
	mu     sync.Mutex
	rounds []string
}

func (r *recordEnqueue) fn(_ context.Context, _ pgx.Tx, round string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.rounds = append(r.rounds, round)
	return nil
}

func (r *recordEnqueue) got() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Clone(r.rounds)
}

type sessionCols struct {
	state, vod, dvr, inherits, round string
	roundClosed, initUploaded        bool
}

func colsOf(t *testing.T, pool *pgxpool.Pool, id string) sessionCols {
	t.Helper()
	var c sessionCols
	if err := pool.QueryRow(context.Background(), `
SELECT state, vod_state, dvr_state, COALESCE(inherits_session, ''), COALESCE(round_id, ''),
       round_closed_at IS NOT NULL, init_uploaded_at IS NOT NULL
  FROM stream_sessions WHERE session_id = $1`, id).
		Scan(&c.state, &c.vod, &c.dvr, &c.inherits, &c.round, &c.roundClosed, &c.initUploaded); err != nil {
		t.Fatalf("세션 조회 실패 %s: %v", id, err)
	}
	return c
}

// holdSession 은 다른 연결에서 세션 행을 FOR NO KEY UPDATE 로 쥔 채 둔다. 돌려준 함수가 커밋한다.
func holdSession(t *testing.T, pool *pgxpool.Pool, id string) (release func()) {
	t.Helper()
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := tx.Exec(ctx, `SELECT 1 FROM stream_sessions WHERE session_id = $1 FOR NO KEY UPDATE`, id); err != nil {
		t.Fatal(err)
	}
	var once sync.Once
	release = func() { once.Do(func() { _ = tx.Commit(ctx) }) }
	t.Cleanup(release)
	return release
}

// waitLockWaiters 는 이 시험 DB 에서 행 잠금을 기다리는 백엔드가 n 개 이상이 될 때까지 기다린다.
func waitLockWaiters(t *testing.T, pool *pgxpool.Pool, n int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var waiting int
		if err := pool.QueryRow(context.Background(), `
SELECT pg_catalog.count(*) FROM pg_catalog.pg_stat_activity
 WHERE datname = pg_catalog.current_database() AND wait_event_type = 'Lock'`).Scan(&waiting); err != nil {
			t.Fatal(err)
		}
		if waiting >= n {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("잠금 대기 백엔드가 %d 개가 되지 않았다", n)
}

// 사슬 닫힘 — 꼬리가 즉시 닫는 사유(intentional)면 사슬 전원을 freezing 으로 닫고 사슬 값을 돌려준다.
func TestCloseChainClosesWholeChain(t *testing.T) {
	pool := newTestPool(t)
	old := time.Now().Add(-time.Hour)
	const s = "cc-whole"
	putEnd(t, pool, endRow{id: "A", stream: s, dvr: "frozen", endReason: "offline", round: "R", initUploaded: true, startedAt: old})
	putEnd(t, pool, endRow{id: "B", stream: s, dvr: "freeze_failed", endReason: "intentional", round: "R", inherits: "A",
		initUploaded: true, startedAt: old.Add(2 * time.Minute)})

	c, ok, err := CloseChain(context.Background(), pool, "B", testClose)
	if err != nil || !ok {
		t.Fatalf("CloseChain(B) = ok %v err %v, want 닫힘", ok, err)
	}
	want := Chain{StreamID: s, RoundID: "R", Head: "A", Tail: "B", SessionIDs: []string{"A", "B"}}
	if !slices.Equal(c.SessionIDs, want.SessionIDs) || c.Head != want.Head || c.Tail != want.Tail || c.RoundID != want.RoundID || c.StreamID != s {
		t.Fatalf("사슬 = %+v, want %+v", c, want)
	}
	for _, id := range []string{"A", "B"} {
		if got := colsOf(t, pool, id).vod; got != "freezing" {
			t.Errorf("%s vod_state = %s, want freezing", id, got)
		}
	}
}

// 닫지 않는 경우 — 창이 안 지난 offline 꼬리 · 같은 회차 후속이 있는 꼬리 · init 판정이 안 끝난 구성원 · dvr 종국 아님.
// 어느 쪽이든 쓰지 않고 ok=false 다(다음 스캔에 다시).
func TestCloseChainRefusesWhenNotClosable(t *testing.T) {
	now := time.Now()
	old := now.Add(-time.Hour)
	tests := []struct {
		name string
		rows []endRow
		segs func(t *testing.T, pool *pgxpool.Pool, s string)
		tail string
	}{
		{name: "창 안 offline", tail: "T", rows: []endRow{
			{id: "T", dvr: "frozen", endReason: "offline", initUploaded: true, startedAt: now.Add(-2 * time.Minute)}},
			segs: func(t *testing.T, pool *pgxpool.Pool, s string) {
				putSeg(t, pool, s, "T", 1, now.Add(-time.Minute), true)
			}},
		{name: "후속 있음", tail: "T", rows: []endRow{
			{id: "T", dvr: "frozen", endReason: "intentional", round: "R", initUploaded: true, startedAt: old},
			{id: "U", state: "live", round: "R", inherits: "T", startedAt: now}}},
		{name: "구성원 init 미판정", tail: "T", rows: []endRow{
			{id: "P", dvr: "frozen", endReason: "offline", round: "R", startedAt: now.Add(-5 * time.Minute)},
			{id: "T", dvr: "frozen", endReason: "intentional", round: "R", inherits: "P", initUploaded: true, startedAt: now.Add(-3 * time.Minute)}}},
		{name: "dvr 동결 전", tail: "T", rows: []endRow{
			{id: "T", dvr: "open", endReason: "intentional", initUploaded: true, startedAt: old}}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			const s = "cc-refuse"
			for _, r := range tt.rows {
				r.stream = s
				putEnd(t, pool, r)
			}
			if tt.segs != nil {
				tt.segs(t, pool, s)
			}
			if _, ok, err := CloseChain(context.Background(), pool, tt.tail, testClose); err != nil || ok {
				t.Fatalf("CloseChain = ok %v err %v, want 닫지 않음", ok, err)
			}
			for _, r := range tt.rows {
				if got := colsOf(t, pool, r.id).vod; got != "none" {
					t.Errorf("%s vod_state = %s, want none", r.id, got)
				}
			}
		})
	}
}

// D61 — 늦은 회차 분리 뒤 A 를 계승하는 B 는 다른 회차다. 사슬 간선 규칙(같은 round_id 만)이라 A 는 후속이 없는
// 꼬리이고, 스캔에 실행 가능으로 오며 A 사슬만 닫힌다 — B 송출 끝을 기다리지 않는다.
func TestCloseChainIgnoresCrossRoundEdgeD61(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now()
	const s = "cc-d61"
	putEnd(t, pool, endRow{id: "A", stream: s, dvr: "frozen", endReason: "offline", round: "R", stopMarkedAt: now.Add(-time.Minute),
		initUploaded: true, startedAt: now.Add(-time.Hour)})
	putEnd(t, pool, endRow{id: "B", stream: s, state: "live", round: "B", inherits: "A", startedAt: now.Add(-30 * time.Second)})

	if ids, _ := scanIDs(t, pool, ScanCursor{}, 50); !slices.Equal(ids, []string{"A"}) {
		t.Fatalf("스캔 = %q, want [A] — 회차를 가로지르는 계승이 후속으로 잡혔다", ids)
	}
	c, ok, err := CloseChain(context.Background(), pool, "A", testClose)
	if err != nil || !ok || !slices.Equal(c.SessionIDs, []string{"A"}) {
		t.Fatalf("CloseChain(A) = %+v ok %v err %v, want 사슬 [A] 닫힘", c, ok, err)
	}
	if got := colsOf(t, pool, "B"); got.vod != "none" || got.inherits != "A" {
		t.Fatalf("B = %+v, want vod none · 계승 그대로", got)
	}
}

// D75 (가) — 계승 해제가 CloseChain 의 사슬 계산과 ③ 사이에 커밋한다. CloseChain 은 ③ 뒤 사슬을 다시 계산해 달라진
// 것을 보고 되돌린다(ok=false). 다음 시도는 줄어든 사슬로 닫는다.
//
//	A ← B ← C(꼬리 · intentional)   B 는 init 미확정이지만 정산 창(15분)이 지나 init 판정이 끝났다
//	T0 이 A 를 쥔다 → CloseChain(C) 이 ③ 에서 A 를 기다림 → B 의 첫 init(비호환)이 계승을 끊고 커밋 → T0 놓음
func TestCloseChainRecomputesAfterRowLocksD75(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	old := time.Now().Add(-time.Hour)
	const s = "cc-d75a"
	putEnd(t, pool, endRow{id: "A", stream: s, dvr: "frozen", endReason: "offline", round: "R", initUploaded: true, startedAt: old})
	putEnd(t, pool, endRow{id: "B", stream: s, dvr: "frozen", endReason: "offline", round: "R", inherits: "A",
		startedAt: old.Add(time.Minute), endingAt: old.Add(2 * time.Minute)})
	putEnd(t, pool, endRow{id: "C", stream: s, dvr: "frozen", endReason: "intentional", round: "R", inherits: "B",
		initUploaded: true, startedAt: old.Add(3 * time.Minute)})

	release := holdSession(t, pool, "A")
	type result struct {
		c   Chain
		ok  bool
		err error
	}
	done := make(chan result, 1)
	go func() {
		c, ok, err := CloseChain(ctx, pool, "C", testClose)
		done <- result{c, ok, err}
	}()
	waitLockWaiters(t, pool, 1)
	mark, err := NewUploadStore(pool).MarkInitUploaded(ctx, "B", []byte{1}, "init/B.mp4", 10, true)
	if err != nil || mark != InitMarkRevoked {
		t.Fatalf("B 계승 해제 = %v err %v, want revoked(사슬이 아직 열려 있다)", mark, err)
	}
	release()
	r := <-done
	if r.err != nil || r.ok {
		t.Fatalf("CloseChain(C) = ok %v err %v, want 되돌림(사슬이 그 사이 바뀌었다)", r.ok, r.err)
	}
	for _, id := range []string{"A", "B", "C"} {
		if got := colsOf(t, pool, id).vod; got != "none" {
			t.Fatalf("%s vod_state = %s, want none(낡은 사슬을 닫지 않음)", id, got)
		}
	}
	c, ok, err := CloseChain(ctx, pool, "C", testClose)
	if err != nil || !ok || !slices.Equal(c.SessionIDs, []string{"B", "C"}) {
		t.Fatalf("다음 CloseChain(C) = %+v ok %v err %v, want 줄어든 사슬 [B C]", c, ok, err)
	}
}

// D75 (나) — 계승 해제가 ③ 을 기다리는 동안 CloseChain 이 커밋한다. 해제 문장의 vod_state='none' 조건이 닫힌 사슬의
// 간선을 지킨다 — init 은 확정되고 계승은 그대로이며 결과는 InitMarkRevokeSkipped 다(부른 쪽이 WARN 한 줄).
func TestRevokeAfterChainCloseKeepsEdgeD75(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	old := time.Now().Add(-time.Hour)
	const s = "cc-d75b"
	putEnd(t, pool, endRow{id: "A", stream: s, dvr: "frozen", endReason: "offline", round: "R", initUploaded: true, startedAt: old})
	putEnd(t, pool, endRow{id: "B", stream: s, dvr: "frozen", endReason: "intentional", round: "R", inherits: "A",
		startedAt: old.Add(time.Minute), endingAt: old.Add(2 * time.Minute)})

	release := holdSession(t, pool, "A")
	closed := make(chan error, 1)
	go func() {
		_, ok, err := CloseChain(ctx, pool, "B", testClose)
		if err == nil && !ok {
			err = errors.New("닫지 않았다")
		}
		closed <- err
	}()
	waitLockWaiters(t, pool, 1)
	marked := make(chan InitMark, 1)
	go func() {
		m, err := NewUploadStore(pool).MarkInitUploaded(ctx, "B", []byte{1}, "init/B.mp4", 10, true)
		if err != nil {
			t.Errorf("MarkInitUploaded: %v", err)
		}
		marked <- m
	}()
	waitLockWaiters(t, pool, 2)
	release()
	if err := <-closed; err != nil {
		t.Fatalf("CloseChain(B): %v", err)
	}
	if m := <-marked; m != InitMarkRevokeSkipped {
		t.Fatalf("닫힌 사슬의 늦은 init = %v, want revoke_skipped", m)
	}
	if got := colsOf(t, pool, "B"); got.inherits != "A" || !got.initUploaded || got.vod != "freezing" {
		t.Fatalf("B = %+v, want 계승 A 그대로 · init 확정 · vod freezing", got)
	}
}

// phase 4 — 사슬 전원이 dvr · vod 종국이면 ended 로 바꾸고, 같은 트랜잭션에서 그 회차의 닫힘 · 완결 평가를 부른다.
func TestMarkChainEndedEndsChainAndEvaluatesRound(t *testing.T) {
	pool := newTestPool(t)
	old := time.Now().Add(-time.Hour)
	const s = "me-basic"
	putEnd(t, pool, endRow{id: "A", stream: s, dvr: "frozen", vod: "frozen", endReason: "offline", round: "R", initUploaded: true, startedAt: old})
	putEnd(t, pool, endRow{id: "B", stream: s, dvr: "frozen", vod: "frozen", endReason: "intentional", round: "R", inherits: "A",
		initUploaded: true, startedAt: old.Add(time.Minute)})

	var rec recordEnqueue
	c := Chain{StreamID: s, RoundID: "R", Head: "A", Tail: "B", SessionIDs: []string{"A", "B"}}
	n, err := MarkChainEnded(context.Background(), pool, c, testClose, rec.fn)
	if err != nil || n != 2 {
		t.Fatalf("MarkChainEnded = %d err %v, want 2", n, err)
	}
	for _, id := range []string{"A", "B"} {
		if got := colsOf(t, pool, id); got.state != "ended" || !got.roundClosed {
			t.Errorf("%s = %+v, want ended · 회차 닫힘(최신 사유 intentional)", id, got)
		}
	}
	if got := rec.got(); !slices.Equal(got, []string{"R"}) {
		t.Fatalf("완결 평가 = %q, want [R]", got)
	}
}

// D55 다섯째 갈래 앞 몫 — 동결 중 사슬이 늦은 분리로 회차 B 로 통째로 옮겨졌다. phase 4 가 완결 평가할 회차는 Chain 의
// RoundID(옛 회차)가 아니라 잠금 뒤 사슬 행에서 다시 읽은 round_id 다.
func TestMarkChainEndedEvaluatesRereadRoundD55(t *testing.T) {
	pool := newTestPool(t)
	old := time.Now().Add(-time.Hour)
	const s = "me-d55"
	putEnd(t, pool, endRow{id: "X", stream: s, dvr: "frozen", vod: "frozen", endReason: "intentional", round: "B",
		initUploaded: true, startedAt: old})

	var rec recordEnqueue
	c := Chain{StreamID: s, RoundID: "R", Head: "X", Tail: "X", SessionIDs: []string{"X"}}
	if n, err := MarkChainEnded(context.Background(), pool, c, testClose, rec.fn); err != nil || n != 1 {
		t.Fatalf("MarkChainEnded = %d err %v", n, err)
	}
	if got := rec.got(); !slices.Equal(got, []string{"B"}) {
		t.Fatalf("완결 평가 회차 = %q, want [B](잠금 뒤 다시 읽은 회차)", got)
	}
}

// phase 4 거절 — 구성원 하나라도 vod 종국이 아니면 아무것도 쓰지 않고 완결 평가도 부르지 않는다.
func TestMarkChainEndedRefusesUntilAllTerminal(t *testing.T) {
	pool := newTestPool(t)
	old := time.Now().Add(-time.Hour)
	const s = "me-refuse"
	putEnd(t, pool, endRow{id: "A", stream: s, dvr: "frozen", vod: "frozen", round: "R", initUploaded: true, startedAt: old})
	putEnd(t, pool, endRow{id: "B", stream: s, dvr: "frozen", vod: "freezing", round: "R", inherits: "A", initUploaded: true,
		startedAt: old.Add(time.Minute)})

	var rec recordEnqueue
	c := Chain{StreamID: s, RoundID: "R", Head: "A", Tail: "B", SessionIDs: []string{"A", "B"}}
	if n, err := MarkChainEnded(context.Background(), pool, c, testClose, rec.fn); err != nil || n != 0 {
		t.Fatalf("MarkChainEnded = %d err %v, want 0", n, err)
	}
	if got := colsOf(t, pool, "A").state; got != "ending" {
		t.Fatalf("A state = %s, want ending", got)
	}
	if got := rec.got(); len(got) != 0 {
		t.Fatalf("완결 평가 = %q, want 없음", got)
	}
}

// 꺼짐 빈 동결(D72 장부 몫) — 발행 흔적(manifest_etag · ③ uploaded)이 없을 때만 PUT 없이 닫는다. 흔적이 있으면 쓰지
// 않는다(finalize 의 보류와 겹 방어).
func TestFreezeEmptyDVRRequiresNoPublishTrace(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	old := time.Now().Add(-time.Hour)
	const s = "fe-dvr"
	putEnd(t, pool, endRow{id: "bare", stream: s, startedAt: old})
	putEnd(t, pool, endRow{id: "etag", stream: s, etag: `"e"`, startedAt: old.Add(time.Second)})
	putEnd(t, pool, endRow{id: "pb", stream: s, dvr: "freezing", freezingAt: old, startedAt: old.Add(2 * time.Second)})
	putSeg(t, pool, s, "pb", 1, old, true)

	for id, want := range map[string]bool{"bare": true, "etag": false, "pb": false} {
		got, err := FreezeEmptyDVR(ctx, pool, id, testClose)
		if err != nil || got != want {
			t.Errorf("FreezeEmptyDVR(%s) = %v err %v, want %v", id, got, err, want)
		}
	}
	if got := colsOf(t, pool, "bare").dvr; got != "frozen" {
		t.Fatalf("bare dvr = %s, want frozen", got)
	}
	for _, id := range []string{"etag", "pb"} {
		if got := colsOf(t, pool, id).dvr; got == "frozen" {
			t.Fatalf("%s 가 흔적이 있는데 빈 동결로 닫혔다", id)
		}
	}
}

// 꺼짐 VOD 빈 동결 — 사슬 어느 구성원에도 ③ uploaded 가 없을 때만 사슬 전원을 닫는다. VOD 실패 확정은 freezing 사슬
// 전원을 freeze_failed 로 쓴다.
func TestFreezeEmptyVODAndFailChainVOD(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	old := time.Now().Add(-time.Hour)
	const s = "fe-vod"
	putEnd(t, pool, endRow{id: "A", stream: s, dvr: "frozen", vod: "freezing", round: "R", startedAt: old})
	putEnd(t, pool, endRow{id: "B", stream: s, dvr: "frozen", vod: "freezing", round: "R", inherits: "A", startedAt: old.Add(time.Minute)})
	putEnd(t, pool, endRow{id: "C", stream: s, dvr: "frozen", vod: "freezing", round: "R2", startedAt: old.Add(2 * time.Minute)})
	putSeg(t, pool, s, "C", 1, old, true)

	ab := Chain{StreamID: s, RoundID: "R", Head: "A", Tail: "B", SessionIDs: []string{"A", "B"}}
	if ok, err := FreezeEmptyVOD(ctx, pool, ab, testClose); err != nil || !ok {
		t.Fatalf("FreezeEmptyVOD(AB) = %v err %v, want 닫힘", ok, err)
	}
	cc := Chain{StreamID: s, RoundID: "R2", Head: "C", Tail: "C", SessionIDs: []string{"C"}}
	if ok, err := FreezeEmptyVOD(ctx, pool, cc, testClose); err != nil || ok {
		t.Fatalf("FreezeEmptyVOD(C) = %v err %v, want 흔적 있어 닫지 않음", ok, err)
	}
	if ok, err := FailChainVOD(ctx, pool, cc, testClose); err != nil || !ok {
		t.Fatalf("FailChainVOD(C) = %v err %v", ok, err)
	}
	for id, want := range map[string]string{"A": "frozen", "B": "frozen", "C": "freeze_failed"} {
		if got := colsOf(t, pool, id).vod; got != want {
			t.Errorf("%s vod = %s, want %s", id, got, want)
		}
	}
}

// LoadChain — 꼬리의 사슬 값만 읽는다(렌더 행 없이 · 사슬 간선 규칙).
func TestLoadChainReadsChainOnly(t *testing.T) {
	pool := newTestPool(t)
	old := time.Now().Add(-time.Hour)
	const s = "lc-chain"
	putEnd(t, pool, endRow{id: "A", stream: s, round: "R", startedAt: old})
	putEnd(t, pool, endRow{id: "B", stream: s, round: "R", inherits: "A", startedAt: old.Add(time.Minute)})
	c, err := LoadChain(context.Background(), pool, s, "B")
	if err != nil || c.Head != "A" || !slices.Equal(c.SessionIDs, []string{"A", "B"}) || c.RoundID != "R" {
		t.Fatalf("LoadChain = %+v err %v", c, err)
	}
	if _, err := LoadChain(context.Background(), pool, s, "missing"); !errors.Is(err, ErrChainNotFound) {
		t.Fatalf("없는 꼬리 = %v, want ErrChainNotFound", err)
	}
}

// D25 — dev 의 옛 ending 수백 건(옛 round_id NULL 계승 사슬 포함)이 정산 스캔만으로 모두 ended 에 닿는다. 꺼짐 갈래
// (발행 흔적 없음 — 빈 동결)의 장부 문장만으로 돈다 — 실행 가능 행 · 쪽 50 · 커서 순회 · 사슬 간선 규칙.
func TestEndingBacklogDrainsToEndedD25(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	old := time.Now().Add(-3 * time.Hour)
	const s = "d25"
	for i := range 100 {
		at := old.Add(time.Duration(3*i) * time.Second)
		head := fmt.Sprintf("h%03d", i)
		putEnd(t, pool, endRow{id: head, stream: s, endReason: "offline", initUploaded: true, startedAt: at})
		putEnd(t, pool, endRow{id: head + "m", stream: s, endReason: "offline", inherits: head, initUploaded: true,
			startedAt: at.Add(time.Second)})
		putEnd(t, pool, endRow{id: head + "t", stream: s, endReason: "offline", inherits: head + "m", initUploaded: true,
			startedAt: at.Add(2 * time.Second)})
	}
	for range 40 {
		var cur ScanCursor
		for {
			rows, next, err := ListEndingSessions(ctx, pool, cur, 50, testClose)
			if err != nil {
				t.Fatal(err)
			}
			for _, r := range rows {
				driveOffline(t, pool, r)
			}
			if next == (ScanCursor{}) {
				break
			}
			cur = next
		}
		var left int
		if err := pool.QueryRow(ctx, `SELECT pg_catalog.count(*) FROM stream_sessions WHERE state <> 'ended'`).Scan(&left); err != nil {
			t.Fatal(err)
		}
		if left == 0 {
			return
		}
	}
	t.Fatal("정산 스캔 40 바퀴 뒤에도 ended 가 아닌 세션이 남았다")
}

// driveOffline 은 스캔 행 하나의 다음 걸음을 꺼짐 갈래 장부 문장으로 돌린다(시험 전용 운전자 — 판정은 커밋 ② finalize).
func driveOffline(t *testing.T, pool *pgxpool.Pool, r EndingSession) {
	t.Helper()
	ctx := context.Background()
	switch {
	case r.DVRState == "open":
		if _, err := FreezeEmptyDVR(ctx, pool, r.SessionID, testClose); err != nil {
			t.Fatal(err)
		}
	case r.VODState == "none":
		if _, _, err := CloseChain(ctx, pool, r.SessionID, testClose); err != nil {
			t.Fatal(err)
		}
	case r.VODState == "freezing":
		c, err := LoadChain(ctx, pool, r.StreamID, r.SessionID)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := FreezeEmptyVOD(ctx, pool, c, testClose); err != nil {
			t.Fatal(err)
		}
	default:
		c, err := LoadChain(ctx, pool, r.StreamID, r.SessionID)
		if err != nil {
			t.Fatal(err)
		}
		var rec recordEnqueue
		if _, err := MarkChainEnded(ctx, pool, c, testClose, rec.fn); err != nil {
			t.Fatal(err)
		}
	}
}
