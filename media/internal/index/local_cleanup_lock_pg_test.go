package index

// 조건 1 시험(계획 4-6 · POK-369 T1) — janitor 의 행 잠금 트랜잭션이 사이드카 · 인덱서 ·
// 스키마 보장 · clip 정리의 경보를 헛켜지 않는지, 기다리게 하면 얼마까지인지를 실제 경합으로 잰다.
// 국면 ⓐ = 선잠금 ~ UPDATE 전(remove 실행 중), ⓑ = UPDATE ~ COMMIT 전. REQUIRE_PG=1 대상.

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// lcFast 는 「기다리지 않는다」의 판정 상한이다(계획 r1 4-6 — 500ms).
const lcFast = 500 * time.Millisecond

// lcHoldDeadline 은 janitor 호출의 클라이언트 기한이다(설계 2-3 — RowLockTxnTimeout + 2s).
const lcHoldDeadline = RowLockTxnTimeout + 2*time.Second

// lcWaiterDeadline 은 잠금 대기자(③ CAS) 호출의 시험 기한이다. 제품 MarkTimeout(10s)을 쓰지 않는다
// — 잠금 해제 상한(8s)에 바짝 붙은 기한은 해제 뒤 대기자 자신의 UPDATE · 동기 커밋이 느린 러너에서
// 넘긴다(POK-382 — CI 해제 뒤 4.3s, 쓰기 제한 부하 실측 해제 뒤 최대 11.9s). 언제 풀렸는가는 탐침이
// 판정하고, 이 기한은 「무한 대기가 아니다」만 묶는다.
const lcWaiterDeadline = 30 * time.Second

// lcTracer 는 janitor 풀의 질의 추적기다. 시험이 문장 시작 지점에 끼어들어 연결을 붙잡거나
// (COMMIT 직전 멈춤) 같은 트랜잭션 안을 들여다본다. onStart 는 질의를 보낸 고루틴에서 불린다.
type lcTracer struct {
	onStart func(conn *pgx.Conn, sql string)
}

func (tr lcTracer) TraceQueryStart(ctx context.Context, conn *pgx.Conn, data pgx.TraceQueryStartData) context.Context {
	if tr.onStart != nil {
		tr.onStart(conn, data.SQL)
	}
	return ctx
}

func (lcTracer) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}

type lcJanitorOpts struct {
	tracer   pgx.QueryTracer
	via      string // host:port — 비면 base 와 같은 곳에 직접 붙는다
	maxConns int32
}

// lcJanitorPool 은 janitor 와 같은 세션 설정(lock_timeout 2s — 설계 2-4)을 건 별도 풀이다.
func lcJanitorPool(t *testing.T, base *pgxpool.Pool, o lcJanitorOpts) *pgxpool.Pool {
	t.Helper()
	cfg := base.Config()
	if cfg.ConnConfig.RuntimeParams == nil {
		cfg.ConnConfig.RuntimeParams = map[string]string{}
	}
	cfg.ConnConfig.RuntimeParams["lock_timeout"] = "2s"
	cfg.ConnConfig.RuntimeParams["application_name"] = "lc-janitor"
	if o.tracer != nil {
		cfg.ConnConfig.Tracer = o.tracer
	}
	if o.via != "" {
		host, port, err := net.SplitHostPort(o.via)
		if err != nil {
			t.Fatalf("프록시 주소 %q 해석 실패: %v", o.via, err)
		}
		p, err := strconv.ParseUint(port, 10, 16)
		if err != nil {
			t.Fatalf("프록시 포트 %q 해석 실패: %v", port, err)
		}
		// 프록시는 평문 TCP 만 옮긴다 — TLS 시도와 대체 경로를 끈다. 원래 DSN 이 TLS 를 요구하면
		// 조용히 평문으로 바꾸지 않고 건너뛴다(시험 데이터 · 인증이 평문으로 흐르지 않게).
		if lcRequiresTLS(cfg.ConnConfig) {
			t.Skip("TLS 를 요구하는 PG_DSN(sslmode=require · verify-*) — 평문 차단 프록시를 쓸 수 없다")
		}
		cfg.ConnConfig.Host, cfg.ConnConfig.Port = host, uint16(p)
		cfg.ConnConfig.TLSConfig, cfg.ConnConfig.Fallbacks = nil, nil
	}
	if o.maxConns > 0 {
		cfg.MaxConns = o.maxConns
	}
	pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("janitor 시험 풀 생성 실패: %v", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

// lcRequiresTLS 는 cfg 에 평문 접속 경로가 없는가다(sslmode=require · verify-*). prefer · allow ·
// disable 은 평문 경로가 있다 — 원래 설정도 평문을 받아들인다.
func lcRequiresTLS(cfg *pgx.ConnConfig) bool {
	if cfg.TLSConfig == nil {
		return false
	}
	for _, fb := range cfg.Fallbacks {
		if fb.TLSConfig == nil {
			return false
		}
	}
	return true
}

// lcCommitGate 는 janitor 연결이 COMMIT 을 보내기 직전에 멈춰 세운다(국면 ⓑ).
type lcCommitGate struct {
	reached  chan struct{}
	open     chan struct{}
	reachOne sync.Once
	openOne  sync.Once
}

func newLCCommitGate(t *testing.T) *lcCommitGate {
	g := &lcCommitGate{reached: make(chan struct{}), open: make(chan struct{})}
	t.Cleanup(g.release) // 실패로 끝나도 멈춘 고루틴을 풀어 준다
	return g
}

func (g *lcCommitGate) tracer() lcTracer {
	return lcTracer{onStart: func(_ *pgx.Conn, sql string) {
		if sql != "commit" {
			return
		}
		g.reachOne.Do(func() { close(g.reached) })
		<-g.open
	}}
}

func (g *lcCommitGate) release() { g.openOne.Do(func() { close(g.open) }) }

// lcHold 는 RemoveUnderRowLock 한 번을 다른 고루틴에서 돌린다. remove 는 locked 를 닫고
// release 로 clear 값을 받을 때까지(또는 ctx 가 끝날 때까지) 행 잠금을 쥔 채 기다린다.
type lcHold struct {
	locked   chan struct{}
	lockedAt time.Time // locked 를 닫기 전에 쓴다 — 받는 쪽은 <-locked 뒤에만 읽는다
	release  chan bool
	finished chan struct{}
	out      lcHoldResult // finished 를 닫기 전에 쓴다
}

type lcHoldResult struct {
	res RowLockResult
	err error
}

func lcStartHold(ctx context.Context, store *LocalCleanupStore, stream string, seq int64, path string) *lcHold {
	h := &lcHold{locked: make(chan struct{}), release: make(chan bool, 1), finished: make(chan struct{})}
	go func() {
		defer close(h.finished)
		h.out.res, h.out.err = store.RemoveUnderRowLock(ctx, stream, seq, path, func() bool {
			h.lockedAt = time.Now()
			close(h.locked)
			select {
			case clear := <-h.release:
				return clear
			case <-ctx.Done():
				return false
			}
		})
	}()
	return h
}

func (h *lcHold) waitLocked(t *testing.T) {
	t.Helper()
	select {
	case <-h.locked:
	case <-h.finished:
		t.Fatalf("janitor 가 잠금 전에 끝났다: (%+v, %v)", h.out.res, h.out.err)
	case <-time.After(5 * time.Second):
		t.Fatal("janitor 가 5s 안에 행을 잠그지 못했다")
	}
}

func (h *lcHold) wait(t *testing.T) lcHoldResult {
	t.Helper()
	select {
	case <-h.finished:
		return h.out
	case <-time.After(15 * time.Second):
		t.Fatal("janitor 호출이 15s 안에 끝나지 않았다")
		return lcHoldResult{}
	}
}

// lcAwaitLocked 는 remove 진입 시각을 기다린다. janitor 가 remove 없이 끝나면 곧바로 실패한다.
func lcAwaitLocked(t *testing.T, locked <-chan time.Time, done <-chan lcHoldResult) time.Time {
	t.Helper()
	select {
	case at := <-locked:
		return at
	case r := <-done:
		t.Fatalf("janitor 가 remove 없이 끝났다: (%+v, %v)", r.res, r.err)
	case <-time.After(5 * time.Second):
		t.Fatal("janitor 가 5s 안에 행을 잠그지 못했다")
	}
	return time.Time{}
}

// lcLockFix 는 조건 1 고정물이다(계획 r1 4-6 준비): 세션 live 1개 · 컷오프 없음(면제) ·
// L(seq 0, ② uploaded · ③ pending — janitor 대상) · U(seq 1, ② uploaded) · T(seq 2, pending 꼬리).
type lcLockFix struct {
	stream, session, pathL string
}

const (
	lcSeqL = 0
	lcSeqU = 1
	lcSeqT = 2
)

func lcLockFixture(t *testing.T, pool *pgxpool.Pool, stream string) lcLockFix {
	t.Helper()
	ctx := context.Background()
	st := newSessionStore(pool)
	base := time.Now().UTC().Add(-10 * time.Minute).Truncate(time.Second)
	var session string
	for seq := int64(0); seq <= lcSeqT; seq++ {
		_, res, err := st.Insert(ctx, segAt(stream, seq, base.Add(time.Duration(seq)*4*time.Second), 4000), Seed{}, liveIngress())
		if err != nil || res.SessionID == "" {
			t.Fatalf("고정물 Insert seq %d = (%+v, %v), want 세션 귀속", seq, res, err)
		}
		session = res.SessionID
	}
	if _, err := pool.Exec(ctx, `UPDATE stream_segments SET upload_state = 'uploaded', uploaded_at = now()
	                              WHERE stream_id = $1 AND seq IN ($2, $3)`, stream, lcSeqL, lcSeqU); err != nil {
		t.Fatalf("고정물 업로드 상태 설정 실패: %v", err)
	}
	return lcLockFix{stream: stream, session: session, pathL: segAt(stream, lcSeqL, base, 4000).LocalPath}
}

// lcEnterPhase 는 janitor 를 국면 phase(ⓐ · ⓑ)에서 L 을 쥔 채 세운다. 돌려주는 함수가 janitor 를
// 끝까지 진행시키고(clear=true) 결과를 돌려준다.
func lcEnterPhase(t *testing.T, base *pgxpool.Pool, fix lcLockFix, phase string) func() lcHoldResult {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), lcHoldDeadline)
	gate := newLCCommitGate(t)
	opts := lcJanitorOpts{}
	if phase == "ⓑ" {
		opts.tracer = gate.tracer()
	}
	h := lcStartHold(ctx, NewLocalCleanupStore(lcJanitorPool(t, base, opts)), fix.stream, lcSeqL, fix.pathL)
	// 풀의 Close 는 빌려 간 연결이 돌아올 때까지 기다린다. 그래서 풀보다 **늦게** 등록해 먼저
	// 돌게 한다 — janitor 를 풀어 끝낸 뒤에 풀이 닫힌다.
	t.Cleanup(func() { cancel(); gate.release(); <-h.finished })
	h.waitLocked(t)
	if phase == "ⓐ" {
		return func() lcHoldResult { h.release <- true; return h.wait(t) }
	}
	h.release <- true
	select {
	case <-gate.reached:
	case <-time.After(5 * time.Second):
		t.Fatal("janitor 가 5s 안에 COMMIT 직전에 닿지 않았다")
	}
	return func() lcHoldResult { gate.release(); return h.wait(t) }
}

// lcFileFixture 는 remove 가 실제로 지울 파일을 만든다.
func lcFileFixture(t *testing.T) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "seg.mp4")
	if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
		t.Fatalf("파일 고정물 실패: %v", err)
	}
	return p
}

func lcFileExists(t *testing.T, p string) bool {
	t.Helper()
	_, err := os.Stat(p)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("파일 확인 실패: %v", err)
	}
	return err == nil
}

func lcSQLState(err error) string {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		return pgErr.Code
	}
	return "(PgError 아님)"
}

// lcOp 는 janitor 보유 중에 다른 세션이 하는 동작 하나다.
type lcOp struct {
	name   string
	phases []string
	run    func(ctx context.Context, t *testing.T, pool *pgxpool.Pool, fix lcLockFix) error
}

// lcNonBlockingOps 는 janitor 가 L 을 쥔 동안 기다리면 안 되는 동작들이다(4-6 (a)~(c) · (e)~(i)).
func lcNonBlockingOps() []lcOp {
	both := []string{"ⓐ", "ⓑ"}
	return []lcOp{
		{"(a) 다음 seq Insert(주조 적격 · 세션 귀속)", both, func(ctx context.Context, t *testing.T, pool *pgxpool.Pool, fix lcLockFix) error {
			r := segAt(fix.stream, 3, time.Now().UTC().Add(-5*time.Minute), 4000)
			out, res, err := newSessionStore(pool).Insert(ctx, r, eligibleSeed(time.Now().UTC()), liveIngress())
			if err == nil && (out != InsertInserted || res.SessionID == "") {
				t.Errorf("Insert = (%v, 세션 %q), want (InsertInserted, 귀속)", out, res.SessionID)
			}
			return err
		}},
		{"(b) UpdateTail(T)", both, func(ctx context.Context, t *testing.T, pool *pgxpool.Pool, fix lcLockFix) error {
			ok, err := NewPGStore(pool, nil, nil).UpdateTail(ctx, fix.stream, lcSeqT, 4000, 2000)
			if err == nil && !ok {
				t.Error("UpdateTail updated = false, want true")
			}
			return err
		}},
		{"(c) MarkUploaded(T)", both, func(ctx context.Context, t *testing.T, pool *pgxpool.Pool, fix lcLockFix) error {
			ok, err := NewUploadStore(pool).MarkUploaded(ctx, fix.stream, lcSeqT, 1000+lcSeqT)
			if err == nil && !ok {
				t.Error("MarkUploaded marked = false, want true")
			}
			return err
		}},
		{"(c) MarkFailed(T)", both, func(ctx context.Context, t *testing.T, pool *pgxpool.Pool, fix lcLockFix) error {
			ok, err := NewUploadStore(pool).MarkFailed(ctx, fix.stream, lcSeqT, 1000+lcSeqT)
			if err == nil && !ok {
				t.Error("MarkFailed marked = false, want true")
			}
			return err
		}},
		{"(e) MarkInitUploaded(세션)", both, func(ctx context.Context, t *testing.T, pool *pgxpool.Pool, fix lcLockFix) error {
			m, err := NewUploadStore(pool).MarkInitUploaded(ctx, fix.session, sha32(0x22), "k", 10, false)
			if err == nil && m != InitMarkSuccess {
				t.Errorf("MarkInitUploaded = %v, want success", m)
			}
			return err
		}},
		{"(f) SettlePlaybackFailure(init_mismatch, U)", both, func(ctx context.Context, t *testing.T, pool *pgxpool.Pool, fix lcLockFix) error {
			ok, err := NewUploadStore(pool).SettlePlaybackFailure(ctx, fix.stream, lcSeqU, fix.session, ReasonInitMismatch)
			if err == nil && !ok {
				t.Error("SettlePlaybackFailure marked = false, want true")
			}
			return err
		}},
		{"(g) 읽기 경로 전부", both, lcReadEverything},
		{"(h) 세션 행 FOR UPDATE NOWAIT", both, func(ctx context.Context, t *testing.T, pool *pgxpool.Pool, fix lcLockFix) error {
			tx, err := pool.Begin(ctx)
			if err != nil {
				return err
			}
			defer func() { _ = tx.Rollback(ctx) }()
			_, err = tx.Exec(ctx, `SELECT 1 FROM stream_sessions WHERE session_id = $1 FOR UPDATE NOWAIT`, fix.session)
			return err
		}},
		{"(i) L 과 같은 경로 Insert", []string{"ⓐ"}, func(ctx context.Context, t *testing.T, pool *pgxpool.Pool, fix lcLockFix) error {
			r := segAt(fix.stream, 9, time.Now().UTC(), 4000)
			r.LocalPath = fix.pathL
			out, _, err := NewPGStore(pool, nil, nil).Insert(ctx, r, Seed{}, SessionSource{})
			if err == nil && out != InsertDuplicatePath {
				t.Errorf("Insert = %v, want InsertDuplicatePath", out)
			}
			return err
		}},
	}
}

// 4-6 (a)~(c) · (e)~(i) · D35 · D36 — janitor 가 L 을 쥔 동안 다른 행 · 다른 표의 쓰기와 읽기는
// 기다리지 않는다. 기다리면 그 경로의 기한(MarkTimeout · lock_timeout)이 헛경보를 낼 수 있다.
func TestRowLockDoesNotStallOtherWriters(t *testing.T) {
	pool := newTestPool(t)
	n := 0
	for _, o := range lcNonBlockingOps() {
		for _, phase := range o.phases {
			n++
			t.Run(o.name+" · "+phase, func(t *testing.T) {
				fix := lcLockFixture(t, pool, "lc-lock-"+strconv.Itoa(n))
				finish := lcEnterPhase(t, pool, fix, phase)
				ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
				defer cancel()

				began := time.Now()
				err := o.run(ctx, t, pool, fix)
				took := time.Since(began)
				jr := finish()

				if err != nil || took > lcFast {
					t.Errorf("%s = (%v) · %v, want 오류 0 · ≤ %v — janitor 잠금을 기다렸다(SQLSTATE %s)", o.name, err, took, lcFast, lcSQLState(err))
				}
				if jr.err != nil || jr.res.Outcome != RowLockCleared {
					t.Errorf("janitor = (%+v, %v), want Cleared", jr.res, jr.err)
				}
			})
		}
	}
}

// lcReadEverything 은 (g) — 사이드카 · 인덱서 · 되감기의 읽기 경로를 모두 돈다.
// ⓑ 에서 ExistingPaths 는 L 의 옛 경로를 포함해야 한다(W7 증명 2단계 — 미커밋 NULL 은 안 보인다).
func lcReadEverything(ctx context.Context, t *testing.T, pool *pgxpool.Pool, fix lcLockFix) error {
	st := NewPGStore(pool, nil, nil)
	paths, err := st.ExistingPaths(ctx, fix.stream)
	if err != nil {
		return err
	}
	if _, ok := paths[fix.pathL]; !ok {
		t.Errorf("ExistingPaths 에 L 의 경로 %q 가 없다 — 미커밋 NULL 이 보였다", fix.pathL)
	}
	if _, err := st.LoadCursor(ctx, fix.stream); err != nil {
		return err
	}
	up := NewUploadStore(pool)
	for _, axis := range []Axis{AxisArchive, AxisPlayback, AxisInit} {
		if _, _, err := up.PendingUploads(ctx, axis, 0, 10, SweepCursor{}); err != nil {
			return err
		}
	}
	_, err = LoadRewindLedger(ctx, pool, fix.stream, ledgerBounds)
	return err
}

// 4-6 (d) — L 자신의 ③ CAS 는 ⓐ 동안 기다렸다가 해제 뒤 성공한다. 잠금을 푼 것이 janitor 의
// COMMIT 인가는 탐침이 판정한다: 탐침 획득이 BEGIN + RowLockTxnTimeout 보다 이르다(서버가 세션을
// 끝내 풀었다면 그 뒤다). 해제 → ③ CAS 반환 시간은 판정하지 않고 기록만 한다 — CAS 자신의
// UPDATE · 동기 커밋(WAL flush)이 들어 있어 느린 러너에서 초 단위로 늘어난다(POK-382, CI 실측
// 해제 뒤 4.3s 로 옛 상한 보유 + 1s 초과).
func TestRowLockPlaybackCASWaitsForReleaseThenSucceeds(t *testing.T) {
	pool := newTestPool(t)
	fix := lcLockFixture(t, pool, "lc-lock-d")
	ctx, cancel := context.WithTimeout(context.Background(), lcHoldDeadline)
	defer cancel()
	// janitor 가 BEGIN 을 보낸 시각 — 서버가 transaction_timeout 을 재기 시작하는 시각보다 이르다.
	begun := make(chan time.Time, 1)
	tracer := lcTracer{onStart: func(_ *pgx.Conn, sql string) {
		if sql == "begin" {
			select {
			case begun <- time.Now():
			default:
			}
		}
	}}
	h := lcStartHold(ctx, NewLocalCleanupStore(lcJanitorPool(t, pool, lcJanitorOpts{tracer: tracer})), fix.stream, lcSeqL, fix.pathL)
	h.waitLocked(t)
	var txnBegun time.Time
	select {
	case txnBegun = <-begun:
	default:
		t.Fatal("janitor 의 BEGIN 을 추적하지 못했다")
	}
	const hold = 2 * time.Second
	// 탐침을 ③ CAS 보다 먼저 대기열에 세워, 잠금이 풀리면 탐침이 먼저 받게 한다.
	probe := lcStartLockProbe(pool, fix.stream, lcSeqL)
	lcWaitLockWaiters(t, pool, 1)

	type mark struct {
		ok  bool
		err error
		at  time.Time
	}
	got := make(chan mark, 1)
	go func() {
		mctx, mcancel := context.WithTimeout(context.Background(), lcWaiterDeadline)
		defer mcancel()
		ok, err := NewUploadStore(pool).MarkPlaybackUploaded(mctx, fix.stream, lcSeqL, 777)
		got <- mark{ok, err, time.Now()}
	}()
	select {
	case m := <-got:
		t.Fatalf("③ CAS 가 janitor 보유 중에 끝났다: %+v — 대상 행 잠금이 걸리지 않았다", m)
	case <-time.After(hold):
	}
	released := time.Now()
	h.release <- true
	m := <-got
	jr := h.wait(t)
	freed := lcAwaitProbe(t, probe)

	switch {
	case freed.err != nil:
		t.Errorf("탐침 FOR UPDATE = %v, want janitor 해제 뒤 획득", freed.err)
	case freed.at.Before(released):
		t.Errorf("탐침이 해제 지시 %v 전에 잠금을 얻었다 — 대상 행 잠금이 걸리지 않았다", released.Sub(freed.at))
	case freed.at.Sub(txnBegun) >= RowLockTxnTimeout:
		t.Errorf("BEGIN → 탐침 획득 = %v, want < %v — janitor 의 COMMIT 이 아니라 서버 상한이 잠금을 풀었다", freed.at.Sub(txnBegun), RowLockTxnTimeout)
	default:
		t.Logf("기록: 해제 지시 → 탐침 획득 = %v", freed.at.Sub(released))
	}
	if m.err != nil || !m.ok {
		t.Fatalf("MarkPlaybackUploaded = (%v, %v), want (true, nil) — 기한 %v", m.ok, m.err, lcWaiterDeadline)
	}
	t.Logf("기록: 해제 지시 → ③ CAS 반환 = %v", m.at.Sub(released))
	if jr.err != nil || jr.res.Outcome != RowLockCleared {
		t.Fatalf("janitor = (%+v, %v), want Cleared", jr.res, jr.err)
	}
	if p := lcLocalPath(t, pool, fix.stream, lcSeqL); p != nil {
		t.Errorf("local_path = %q, want NULL", *p)
	}
	if state, at, _ := playbackStateOf(t, pool, fix.stream, lcSeqL); state != "uploaded" || !at {
		t.Errorf("③ = (%q, %v), want (uploaded, true)", state, at)
	}
}

// 4-6 (d′) · D34 · E3′ — act 5.9s 뒤 UPDATE 까지 하고 COMMIT 이 망에서 사라져도(프록시 막힘),
// 서버의 transaction_timeout 이 잠금을 푼다. 판정은 탐침의 해제 시각(≤ 8s + 300ms)이 맡고, 그동안
// 기다린 ③ CAS 는 오류 없이 끝나는지만 본다. 변이(transaction_timeout 제거)면 탐침이 기한 안에
// 잠금을 얻지 못해 실패한다.
func TestRowLockServerTimeoutFreesWaiterWhenCommitIsLost(t *testing.T) {
	pool := newTestPool(t)
	fix := lcLockFixture(t, pool, "lc-lock-dprime")
	cfg := pool.Config().ConnConfig
	if strings.HasPrefix(cfg.Host, "/") {
		t.Skip("유닉스 소켓 DSN — TCP 차단 프록시를 쓸 수 없다")
	}
	bh := pgtest.StartBlackhole(t, net.JoinHostPort(cfg.Host, strconv.Itoa(int(cfg.Port))))
	tracer := lcTracer{onStart: func(_ *pgx.Conn, sql string) {
		if sql == "commit" {
			bh.SetBlocked(true) // UPDATE 는 서버에 닿았고 COMMIT 은 닿지 않는다
		}
	}}
	store := NewLocalCleanupStore(lcJanitorPool(t, pool, lcJanitorOpts{tracer: tracer, via: bh.Addr(), maxConns: 1}))
	jctx, jcancel := context.WithTimeout(context.Background(), lcHoldDeadline)
	defer jcancel()

	locked := make(chan time.Time, 1)
	done := make(chan lcHoldResult, 1)
	go func() {
		res, err := store.RemoveUnderRowLock(jctx, fix.stream, lcSeqL, fix.pathL, func() bool {
			locked <- time.Now()
			time.Sleep(5900 * time.Millisecond) // act ≤ FS_OP_TIMEOUT 상한 근처
			return true
		})
		done <- lcHoldResult{res, err}
	}()
	lockedAt := lcAwaitLocked(t, locked, done)
	// 해제 시각은 대기자 쪽 잠금 획득으로 잰다(D34 식). ③ CAS 의 반환 시각에는 그 자신의
	// UPDATE · 동기 커밋(WAL flush)이 들어 있어, 잠금 상한이 아니라 디스크 속도로 FAIL 할 수 있다.
	// 탐침을 ③ CAS 보다 먼저 대기열에 세워, 잠금이 풀리면 탐침이 먼저 받게 한다.
	probe := lcStartLockProbe(pool, fix.stream, lcSeqL)
	lcWaitLockWaiters(t, pool, 1)

	mctx, mcancel := context.WithTimeout(context.Background(), lcWaiterDeadline)
	defer mcancel()
	began := time.Now()
	ok, err := NewUploadStore(pool).MarkPlaybackUploaded(mctx, fix.stream, lcSeqL, 777)
	took := time.Since(began)
	jcancel() // 막힌 연결의 janitor 를 끝낸다
	jr := <-done
	freed := lcAwaitProbe(t, probe)

	// 대기자 응답 시간은 판정하지 않는다(기한 lcWaiterDeadline 만) — 해제 시각은 아래 탐침이 판정한다. 옛 상한(8s + 1.5s)은
	// 해제 뒤 CAS 자신의 응답 시간까지 묶어 느린 러너에서 깨졌다(POK-382, CI 실측 탐침 해제 7.999s ·
	// 대기자 10s 기한 초과).
	if err != nil || !ok {
		t.Errorf("대기자 MarkPlaybackUploaded = (%v, %v) · %v, want (true, nil) — 기한 %v", ok, err, took, lcWaiterDeadline)
	} else {
		t.Logf("기록: 대기자 ③ CAS = %v", took)
	}
	held := freed.at.Sub(lockedAt)
	switch {
	case freed.err != nil:
		t.Errorf("탐침 FOR UPDATE = %v, want 서버가 janitor 세션을 끝낸 뒤 획득", freed.err)
	case held > RowLockTxnTimeout+300*time.Millisecond:
		t.Errorf("선잠금 성공 → 해제 = %v, want ≤ %v", held, RowLockTxnTimeout+300*time.Millisecond)
	default:
		t.Logf("기록: 선잠금 성공 → 해제 = %v", held)
	}
	if jr.err == nil || jr.res.Stage != StageCommit || !jr.res.Acted || !jr.res.Cleared {
		t.Errorf("janitor = (%+v, %v), want StageCommit · Acted · Cleared 오류", jr.res, jr.err)
	}
	t.Logf("기록: COMMIT 유실 janitor 오류 = %v (SQLSTATE %s)", jr.err, lcSQLState(jr.err))
	if p := lcLocalPath(t, pool, fix.stream, lcSeqL); p == nil || *p != fix.pathL {
		t.Errorf("local_path = %v, want %q — 커밋되지 않은 NULL 이 남았다", p, fix.pathL)
	}
}

// 4-6 (d″) · X1 A — 잠금 트랜잭션 안에서만 transaction_timeout 8s · synchronous_commit off 다.
// 같은 연결의 다음 질의에서는 원래 값이다(SET LOCAL 범위 — 연결 · 역할 수준으로 새지 않는다).
func TestRowLockTxnLimitsAreLocalToTheLockTransaction(t *testing.T) {
	pool := newTestPool(t)
	lcPut(t, pool, lcRow{stream: "lc-lock-dpp", seq: 0})
	ctx := context.Background()
	var lockConn *pgx.Conn
	tracer := lcTracer{onStart: func(conn *pgx.Conn, sql string) {
		if sql == rowLockSQL {
			lockConn = conn
		}
	}}
	jpool := lcJanitorPool(t, pool, lcJanitorOpts{tracer: tracer, maxConns: 1})
	var defaultSync string
	if err := jpool.QueryRow(ctx, `SHOW synchronous_commit`).Scan(&defaultSync); err != nil {
		t.Fatalf("기본 synchronous_commit 조회 실패: %v", err)
	}

	var inSync, inTimeout string
	var probeErr error
	_, err := NewLocalCleanupStore(jpool).RemoveUnderRowLock(ctx, "lc-lock-dpp", 0, lcPath("lc-lock-dpp", 0), func() bool {
		// remove 는 RemoveUnderRowLock 의 고루틴에서 불리고 그 동안 연결은 쉬고 있다 —
		// 같은 트랜잭션 안에서 설정을 읽는다.
		probeErr = lockConn.QueryRow(ctx,
			`SELECT current_setting('synchronous_commit'), current_setting('transaction_timeout')`).Scan(&inSync, &inTimeout)
		return true
	})
	if err != nil || probeErr != nil {
		t.Fatalf("RemoveUnderRowLock = %v · 트랜잭션 안 조회 = %v", err, probeErr)
	}

	if inSync != "off" || inTimeout != "8s" {
		t.Errorf("트랜잭션 안 (synchronous_commit, transaction_timeout) = (%q, %q), want (off, 8s)", inSync, inTimeout)
	}
	var outSync, outTimeout string
	if err := jpool.QueryRow(ctx, `SELECT current_setting('synchronous_commit'), current_setting('transaction_timeout')`).Scan(&outSync, &outTimeout); err != nil {
		t.Fatalf("트랜잭션 밖 조회 실패: %v", err)
	}
	if outSync != defaultSync || outTimeout != "0" {
		t.Errorf("트랜잭션 밖 (synchronous_commit, transaction_timeout) = (%q, %q), want (%q, 0) — 설정이 연결에 샜다", outSync, outTimeout, defaultSync)
	}
}

// D34 — transaction_timeout 실효: act 가 9s 걸려도 잠금은 8s(+300ms) 안에 풀린다. 그 뒤 COMMIT
// 단계는 세션 종료 오류 25P04 다(StageCommit · Acted · Cleared — T2 의 reason=txn_timeout 갈래).
// 풀은 죽은 연결을 버리고 다음 호출을 받는다.
func TestRowLockTransactionTimeoutBoundsHold(t *testing.T) {
	pool := newTestPool(t)
	lcPut(t, pool, lcRow{stream: "lc-lock-tt", seq: 0}, lcRow{stream: "lc-lock-tt", seq: 1})
	jpool := lcJanitorPool(t, pool, lcJanitorOpts{maxConns: 1})
	store := NewLocalCleanupStore(jpool)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	locked := make(chan time.Time, 1)
	acquired := make(chan time.Time, 1)
	done := make(chan lcHoldResult, 1)
	go func() {
		res, err := store.RemoveUnderRowLock(ctx, "lc-lock-tt", 0, lcPath("lc-lock-tt", 0), func() bool {
			locked <- time.Now()
			select { // act 9s — 대기자가 잠금을 얻으면 더 기다릴 것이 없다
			case <-acquired:
			case <-time.After(9 * time.Second):
			}
			return true
		})
		done <- lcHoldResult{res, err}
	}()
	lockedAt := lcAwaitLocked(t, locked, done)
	waitErr := lcLockRowBlocking(ctx, pool, "lc-lock-tt", 0)
	acquiredAt := time.Now()
	acquired <- acquiredAt
	jr := <-done

	if waitErr != nil {
		t.Fatalf("대기자 FOR UPDATE 실패: %v", waitErr)
	}
	if held := acquiredAt.Sub(lockedAt); held > RowLockTxnTimeout+300*time.Millisecond {
		t.Fatalf("보유 = %v, want ≤ %v", held, RowLockTxnTimeout+300*time.Millisecond)
	} else {
		t.Logf("기록: 보유 = %v", held)
	}
	if !IsTxnTimeout(jr.err) || jr.res != (RowLockResult{Acted: true, Cleared: true, Stage: StageCommit}) {
		t.Fatalf("janitor = (%+v, %v · SQLSTATE %s), want (Acted · Cleared · StageCommit, 25P04 세션 종료)", jr.res, jr.err, lcSQLState(jr.err))
	}
	res, err := store.RemoveUnderRowLock(ctx, "lc-lock-tt", 1, lcPath("lc-lock-tt", 1), func() bool { return true })
	if err != nil || res.Outcome != RowLockCleared {
		t.Fatalf("세션 종료 뒤 다음 호출 = (%+v, %v), want Cleared — 풀이 죽은 연결을 버리지 못했다", res, err)
	}
}

// lcLockRowBlocking 은 다른 세션에서 그 행을 (기다려서) FOR UPDATE 로 잠그고 곧바로 되돌린다.
func lcLockRowBlocking(ctx context.Context, pool *pgxpool.Pool, stream string, seq int64) error {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	_, err = tx.Exec(ctx, `SELECT 1 FROM stream_segments WHERE stream_id=$1 AND seq=$2 FOR UPDATE`, stream, seq)
	return err
}

// D29 — 다른 세션이 쥔 행은 NOWAIT 로 즉시 Busy(파일 무접촉)다. lock_timeout(2s)까지 기다리면
// 잠긴 행마다 주기가 2s 씩 밀린다. Busy 뒤 같은 연결(풀 1개)은 다음 호출에 그대로 쓰이고
// 잠금 트랜잭션의 설정이 남지 않는다.
func TestRowLockNoWaitReturnsBusyImmediately(t *testing.T) {
	pool := newTestPool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	lcPut(t, pool, lcRow{stream: "lc-lock-nw", seq: 0}, lcRow{stream: "lc-lock-nw", seq: 1})
	other, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("다른 세션 시작 실패: %v", err)
	}
	defer func() { _ = other.Rollback(context.Background()) }()
	if _, err := other.Exec(ctx, `SELECT 1 FROM stream_segments WHERE stream_id='lc-lock-nw' AND seq=0 FOR UPDATE`); err != nil {
		t.Fatalf("다른 세션 잠금 실패: %v", err)
	}
	jpool := lcJanitorPool(t, pool, lcJanitorOpts{maxConns: 1})
	store := NewLocalCleanupStore(jpool)

	began := time.Now()
	res, err := store.RemoveUnderRowLock(ctx, "lc-lock-nw", 0, lcPath("lc-lock-nw", 0), lcMustNotRemove(t))
	took := time.Since(began)

	if err != nil || res != (RowLockResult{Outcome: RowLockBusy}) || took > lcFast {
		t.Fatalf("RemoveUnderRowLock = (%+v, %v) · %v, want (Busy, nil) · ≤ %v", res, err, took, lcFast)
	}
	next, err := store.RemoveUnderRowLock(ctx, "lc-lock-nw", 1, lcPath("lc-lock-nw", 1), func() bool { return true })
	if err != nil || next.Outcome != RowLockCleared {
		t.Fatalf("Busy 뒤 다음 호출 = (%+v, %v), want Cleared", next, err)
	}
	var timeout string
	if err := jpool.QueryRow(ctx, `SHOW transaction_timeout`).Scan(&timeout); err != nil || timeout != "0" {
		t.Fatalf("Busy 뒤 연결의 transaction_timeout = (%q, %v), want 0", timeout, err)
	}
}

// 단계 분류 — 연결 획득 실패는 StageAcquire, 선잠금 단계의 55P03 아닌 오류는 StageLock 이다.
// 둘 다 remove 를 부르지 않는다(파일 무접촉).
func TestRowLockClassifiesFailuresBeforeRemoveByStage(t *testing.T) {
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	res, err := NewLocalCleanupStore(newTestPool(t)).RemoveUnderRowLock(cancelled, "s", 0, "p", lcMustNotRemove(t))
	if err == nil || res.Stage != StageAcquire || res.Acted {
		t.Errorf("취소된 ctx = (%+v, %v), want StageAcquire 오류 · Acted=false", res, err)
	}

	res, err = NewLocalCleanupStore(lcEmptyPool(t)).RemoveUnderRowLock(context.Background(), "s", 0, "p", lcMustNotRemove(t))
	if err == nil || res.Stage != StageLock || res.Acted || res.Outcome != 0 {
		t.Errorf("표 없는 DB = (%+v, %v), want StageLock 오류(42P01) · Acted=false · Outcome 영값", res, err)
	}
	if code := lcSQLState(err); code != "42P01" {
		t.Errorf("표 없는 DB SQLSTATE = %s, want 42P01", code)
	}
}

// 4-6 (j) · W7′ — ⓑ 에서 L 과 같은 경로를 새 seq 로 Insert 하면 고유 검사가 janitor 를 기다린다.
// 대기는 transaction_timeout 이 묶는다(≤ 8s + 1s). store 의 즉시 재시도 두 창(5s × 2)이 그보다
// 길어 ErrLockContended 가 나오지 않는다 — 결과는 janitor 가 끝난 뒤의 고유 위반 갈래다.
func TestRowLockSamePathInsertWaitIsBoundedInUpdatePhase(t *testing.T) {
	pool := newTestPool(t)
	fix := lcLockFixture(t, pool, "lc-lock-j")
	_ = lcEnterPhase(t, pool, fix, "ⓑ") // 문을 열지 않는다 — 서버가 세션을 끝낼 때까지 ⓑ 그대로

	r := segAt(fix.stream, 9, time.Now().UTC(), 4000)
	r.LocalPath = fix.pathL
	began := time.Now()
	out, _, err := NewPGStore(pool, nil, nil).Insert(context.Background(), r, Seed{}, SessionSource{})
	took := time.Since(began)

	if err != nil || errors.Is(err, ErrLockContended) {
		t.Fatalf("Insert 오류 = %v, want nil — janitor 기인 ErrLockContended 는 0 이어야 한다", err)
	}
	// 상한 여유 2s 는 부하 큰 CI 의 PG 응답 지연 흡수다(C22 쌍둥이 — 종결 r1 R7).
	if limit := RowLockTxnTimeout + 2*time.Second; took < time.Second || took > limit {
		t.Fatalf("Insert 대기 = %v, want 1s ≤ 대기 ≤ %v (ⓑ 에서는 기다리되 상한 안)", took, limit)
	}
	t.Logf("기록: ⓑ 같은 경로 Insert = %v · 대기 %v", out, took)
}

// 4-6 (m) · D40 · W7′ — 인덱서 H9 동일 Record 재시도(첫 INSERT 는 커밋됐는데 응답 유실)가
// janitor ⓑ 와 겹쳐도 ErrLockContended 가 나오지 않고, 결과 갈래는 janitor 없는 기준선과 같다.
// 변이(transaction_timeout 을 결속 ② 경계 너머 11s 로)면 두 창 합 10s 에서 ErrLockContended 다.
func TestRowLockSameRecordRetryMatchesBaselineOutcome(t *testing.T) {
	pool := newTestPool(t)
	st := NewPGStore(pool, nil, nil)
	ctx := context.Background()

	base := segAt("lc-lock-m-base", 0, time.Now().UTC(), 4000)
	if _, _, err := st.Insert(ctx, base, Seed{}, SessionSource{}); err != nil {
		t.Fatalf("기준선 첫 Insert 실패: %v", err)
	}
	want, _, err := st.Insert(ctx, base, Seed{}, SessionSource{})
	if err != nil {
		t.Fatalf("기준선 재시도 실패: %v", err)
	}
	t.Logf("기록: 기준선 동일 Record 재시도 갈래 = %v (InsertSeqConflict = PK 먼저 · InsertDuplicatePath = local_path 먼저)", want)

	r := segAt("lc-lock-m", 0, time.Now().UTC(), 4000)
	if _, _, err := st.Insert(ctx, r, Seed{}, SessionSource{}); err != nil {
		t.Fatalf("첫 Insert 실패: %v", err)
	}
	_ = lcEnterPhase(t, pool, lcLockFix{stream: r.StreamID, pathL: r.LocalPath}, "ⓑ")

	began := time.Now()
	got, _, err := st.Insert(ctx, r, Seed{}, SessionSource{})
	took := time.Since(began)

	if errors.Is(err, ErrLockContended) || err != nil {
		t.Fatalf("재시도 Insert 오류 = %v, want nil — ErrLockContended 헛점등", err)
	}
	if got != want {
		t.Fatalf("재시도 갈래 = %v, want 기준선 %v", got, want)
	}
	// 상한 여유 2s 는 부하 큰 CI 의 PG 응답 지연 흡수다(C22 쌍둥이 — 종결 r1 R7). 판별은 변이(11s)의
	// ErrLockContended 갈래 단언이 잡는다.
	if limit := RowLockTxnTimeout + 2*time.Second; took > limit {
		t.Fatalf("재시도 대기 = %v, want ≤ %v", took, limit)
	}
	t.Logf("기록: janitor ⓑ 와 겹친 재시도 대기 = %v", took)
}
