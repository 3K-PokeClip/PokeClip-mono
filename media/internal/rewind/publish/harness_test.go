package publish

// 발행 테스트의 픽스처 — 목록 행 · 회차 값과, 루프(커밋 7)가 할 일을 흉내 내는 도우미.
//
// 목록 값은 손으로 세운다. 행은 settled 인 4초 조각이고 PDT 는 4초씩 이어진다. 기본 사슬은 셋이다.
//
//	O  목록 밖의 회차(P 가 계승한 직전 회차 — 행은 싣지 않는다)
//	P  seq 100 에서 O 를 계승해 연 회차 — 첫 조각 seq 100 앞에 끊김 표시가 선다
//	S  seq 600 에서 P 를 계승해 연 회차(소유 회차) — 첫 조각 seq 600 앞에 끊김 표시가 선다
//
// S 의 목록은 S 의 행과 접두(P 의 행)를 싣는다(계승 사슬 1단계 — cache.Playlist 와 같은 거름). 1시간 창은
// 4초 조각 900개다. 컷오프는 0 이고 base 는 P · S 모두 5(S 는 P 의 base 를 복사했다)다.

import (
	"context"
	"crypto/sha256"
	"fmt"
	"log/slog"
	"maps"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// TestMain 은 릴리스 게이트용 스위치다(index/testdb_test.go 와 같은 이디엄). PG 케이스는 PG_DSN 이 없으면
// skip 되는데 skip 은 성공으로 집계된다 — REQUIRE_PG=1 이면 그 상황을 실패로 바꾼다.
func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr,
			"REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — 발행 세대 규약 PG 통합 케이스가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

// newPool 은 이 패키지 전용 테스트 DB 에 붙은 풀이다. 접미 rewindpublish 로 가른다 — 패키지 테스트
// 바이너리끼리 병렬로 돌아도 서로의 행을 비우지 않는다(pgtest.Pool). PG_DSN 이 없으면 skip 이다. 비우기는
// 오류 주입 트리거(injectUpdateFault)도 지운다 — 앞 실행이 지우지 못하고 멈췄어도 다음 테스트에 새지 않는다.
func newPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "rewindpublish", index.EnsureSchema, func(ctx context.Context, pool *pgxpool.Pool) error {
		if _, err := pool.Exec(ctx, "DROP TRIGGER IF EXISTS pc_test_fault ON stream_sessions"); err != nil {
			return err
		}
		_, err := pool.Exec(ctx, "TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions")
		return err
	})
}

// updateFault 는 stream_sessions 의 UPDATE 한 번을 서버 오류로 끝내는 테스트 DB 한정 트리거다 — 0행이 아닌 DB
// 실패를 만든다. 예외로 끝난 문장은 통째로 되돌려져 서버는 아무것도 커밋하지 않는다.
type updateFault struct {
	t    *testing.T
	pool *pgxpool.Pool
}

// injectUpdateFault 는 when(트리거 WHEN 조건 — OLD · NEW 를 쓴다)에 맞는 행 UPDATE 를 끊을 수 있는 트리거를 건다.
// 걸기만 해서는 끊지 않는다 — arm 이 다음 한 번을 끊는다. 「한 번」의 표식은 시퀀스가 든다: 시퀀스 값은 예외로
// 되돌려진 트랜잭션에서도 되돌려지지 않아, 끊긴 뒤의 UPDATE(화해의 R3 등)는 그대로 간다. t.Cleanup 이 지운다.
func injectUpdateFault(t *testing.T, pool *pgxpool.Pool, when string) *updateFault {
	t.Helper()
	exec(t, pool, `CREATE SEQUENCE IF NOT EXISTS pc_test_fault_seq`)
	exec(t, pool, `SELECT setval('pc_test_fault_seq', 1)`) // 다음 nextval = 2 — 끊지 않는다
	exec(t, pool, `
		CREATE OR REPLACE FUNCTION pc_test_fault() RETURNS trigger LANGUAGE plpgsql AS $$
		BEGIN
			IF nextval('pc_test_fault_seq') = 1 THEN
				RAISE EXCEPTION 'pc_test_fault: 주입한 서버 오류';
			END IF;
			RETURN NEW;
		END $$`)
	exec(t, pool, `DROP TRIGGER IF EXISTS pc_test_fault ON stream_sessions`)
	exec(t, pool, `CREATE TRIGGER pc_test_fault BEFORE UPDATE ON stream_sessions FOR EACH ROW WHEN (`+when+`)
		EXECUTE FUNCTION pc_test_fault()`)
	t.Cleanup(func() {
		for _, sql := range []string{
			`DROP TRIGGER IF EXISTS pc_test_fault ON stream_sessions`,
			`DROP FUNCTION IF EXISTS pc_test_fault()`,
			`DROP SEQUENCE IF EXISTS pc_test_fault_seq`,
		} {
			if _, err := pool.Exec(context.Background(), sql); err != nil {
				t.Errorf("오류 주입 트리거 정리 실패: %v\n%s", err, sql)
			}
		}
	})
	return &updateFault{t: t, pool: pool}
}

// arm 은 다음 한 번(when 에 맞는 첫 행 UPDATE)을 끊는다.
func (f *updateFault) arm() {
	f.t.Helper()
	exec(f.t, f.pool, `SELECT setval('pc_test_fault_seq', 1, false)`) // 다음 nextval = 1
}

// stmtHook 은 풀이 문장 sql 을 보내기 직전에 before 를 부르는 pgx 추적기다(pgx.QueryTracer). before 가 돌려준 ctx 로
// 그 문장을 보낸다 — 끝난 ctx 를 돌려주면 그 문장은 서버에 닿지 않고 ctx 오류로 끝난다(pgconn 은 끝난 ctx 로
// 보내지 않는다). args 는 그 문장의 바인드 값이다.
type stmtHook struct {
	sql    string
	before func(ctx context.Context, args []any) context.Context
}

func (h stmtHook) TraceQueryStart(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	if d.SQL != h.sql {
		return ctx
	}
	return h.before(ctx, d.Args)
}

func (stmtHook) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}

// hookedPool 은 pool 과 같은 테스트 DB 에 붙되 문장마다 hook 을 거치는 새 풀이다 — 발행자의 DB 문장 사이(P0 과 P2′
// 사이처럼 저장소 훅이 닿지 않는 자리)에 끼어드는 자리다. 풀은 t.Cleanup 이 닫는다.
func hookedPool(t *testing.T, pool *pgxpool.Pool, hook pgx.QueryTracer) *pgxpool.Pool {
	t.Helper()
	cfg := pool.Config()
	cfg.ConnConfig.Tracer = hook
	p, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("추적 풀 생성 실패: %v", err)
	}
	t.Cleanup(p.Close)
	return p
}

// lockRow 는 다른 연결에서 회차 id 의 행을 잠근 트랜잭션을 연다(SELECT … FOR UPDATE) — 그 행을 쓰는 발행자의 문장은
// 잠금이 풀릴 때까지 서버에서 기다린다. 돌려준 release 가 트랜잭션을 되돌려 잠금을 푼다(여러 번 불러도 된다). 풀지 않고
// 끝나면 t.Cleanup 이 푼다.
func lockRow(t *testing.T, pool *pgxpool.Pool, id string) (release func()) {
	t.Helper()
	tx, err := pool.Begin(context.Background())
	if err != nil {
		t.Fatalf("잠금 트랜잭션 시작 실패: %v", err)
	}
	var once sync.Once
	release = func() {
		once.Do(func() {
			if err := tx.Rollback(context.Background()); err != nil {
				t.Errorf("잠금 트랜잭션 되돌리기 실패: %v", err)
			}
		})
	}
	t.Cleanup(release)
	if _, err := tx.Exec(context.Background(), `SELECT 1 FROM stream_sessions WHERE session_id = $1 FOR UPDATE`, id); err != nil {
		t.Fatalf("회차 %s 행 잠금 실패: %v", id, err)
	}
	return release
}

// exec 는 픽스처 SQL 한 문장이다.
func exec(t *testing.T, pool *pgxpool.Pool, sql string, args ...any) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), sql, args...); err != nil {
		t.Fatalf("픽스처 실패: %v\n%s", err, sql)
	}
}

// insertSessions 는 픽스처 f 의 회차를 stream_sessions 에 넣는다 — 소유 회차는 live · init 확정이고 나머지는
// 끝난 회차다. 사슬 픽스처면 계승 대상 O 도 넣는다(행은 없어도 FK 가 참조한다).
func insertSessions(t *testing.T, pool *pgxpool.Pool, f *fixture) {
	t.Helper()
	if _, chained := f.sessions["P"]; chained {
		insertSession(t, pool, fxStream, rewind.Session{ID: "O", TargetDuration: 6}, "ended")
		insertSession(t, pool, fxStream, f.sessions["P"], "ended")
	}
	insertSession(t, pool, fxStream, f.sessions[f.owner], "live")
}

// insertSession 은 회차 s 를 state 로 넣는다 — init 은 확정이고 세대 규약 열(fence · 세대 · ETag)은 DDL 기본값이다.
func insertSession(t *testing.T, pool *pgxpool.Pool, streamID string, s rewind.Session, state string) {
	t.Helper()
	var inherits any
	if s.InheritsSession != "" {
		inherits = s.InheritsSession
	}
	exec(t, pool, `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, init_uploaded_at,
		                             discontinuity_base, inherits_session, target_duration)
		VALUES ($1, $2, now(), $3, now(), $4, $5, $6)`,
		s.ID, streamID, state, s.DiscontinuityBase, inherits, s.TargetDuration)
}

// dbSession 은 stream_sessions 한 행 가운데 세대 규약이 쓰는 열이다. NULL 은 nil 이다.
type dbSession struct {
	gen      int64
	etag     *string
	base     int64
	pubSeq   int64
	fence    *string
	expires  *time.Time
	inherits *string
	state    string
}

// readSession 은 회차 id 의 세대 규약 열을 읽는다.
func readSession(t *testing.T, pool *pgxpool.Pool, id string) dbSession {
	t.Helper()
	var d dbSession
	err := pool.QueryRow(context.Background(), `
		SELECT manifest_gen, manifest_etag, discontinuity_base, published_seq, writer_fence, fence_expires_at,
		       inherits_session, state
		  FROM stream_sessions WHERE session_id = $1`, id).
		Scan(&d.gen, &d.etag, &d.base, &d.pubSeq, &d.fence, &d.expires, &d.inherits, &d.state)
	if err != nil {
		t.Fatalf("회차 %s 읽기 실패: %v", id, err)
	}
	return d
}

// expireLease 는 회차 id 의 lease 를 지난 시각으로 돌린다 — writer 가 lease 를 잃은 국면(멈춤 · 사망)을 기다리지
// 않고 만든다.
func expireLease(t *testing.T, pool *pgxpool.Pool, id string) {
	t.Helper()
	exec(t, pool, `UPDATE stream_sessions SET fence_expires_at = now() - interval '1 second' WHERE session_id = $1`, id)
}

// strOrNil 은 NULL 가능 text 를 비교용 문자열로 편다("<NULL>" = NULL).
func strOrNil(s *string) string {
	if s == nil {
		return "<NULL>"
	}
	return *s
}

// logRecorder 는 발행자가 남긴 로그를 모은다 — 중단 · 포기 로그의 사유 · 등급을 테스트가 본다. 발행자는
// Logger.With 를 쓰지 않으므로 속성은 기록마다 온전하다.
type logRecorder struct {
	mu   sync.Mutex
	recs []logRec
}

// logRec 은 로그 한 줄이다.
type logRec struct {
	level slog.Level
	msg   string
	attrs map[string]string
}

func (r *logRecorder) Enabled(context.Context, slog.Level) bool { return true }

func (r *logRecorder) Handle(_ context.Context, rec slog.Record) error {
	attrs := map[string]string{}
	rec.Attrs(func(a slog.Attr) bool {
		attrs[a.Key] = a.Value.String()
		return true
	})
	r.mu.Lock()
	defer r.mu.Unlock()
	r.recs = append(r.recs, logRec{level: rec.Level, msg: rec.Message, attrs: attrs})
	return nil
}

func (r *logRecorder) WithAttrs([]slog.Attr) slog.Handler { return r }

func (r *logRecorder) WithGroup(string) slog.Handler { return r }

// aborts 는 중단 · 포기 로그(rewind_publish_aborted)의 reason 들이다(남긴 순서).
func (r *logRecorder) aborts() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var reasons []string
	for _, rec := range r.recs {
		if rec.msg == abortedLog {
			reasons = append(reasons, rec.attrs["reason"])
		}
	}
	return reasons
}

// abortRecs 는 중단 · 포기 로그 기록 전부다.
func (r *logRecorder) abortRecs() []logRec {
	return r.records(abortedLog)
}

// records 는 로그 키 msg 로 남긴 기록 전부다(남긴 순서).
func (r *logRecorder) records(msg string) []logRec {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []logRec
	for _, rec := range r.recs {
		if rec.msg == msg {
			out = append(out, rec)
		}
	}
	return out
}

// stepClock 은 부를 때마다 steps 의 다음 값만큼 앞으로 가는 시계다(다 쓰면 선다) — 결정 9 의 m0 · P3 시각을
// 테스트가 정한다. 기준은 실제 지금이라 P3 ctx 의 마감은 실제 시계로도 미래다(PUT 이 실제로 간다).
type stepClock struct {
	mu    sync.Mutex
	now   time.Time
	steps []time.Duration
}

func newStepClock(steps ...time.Duration) *stepClock {
	return &stepClock{now: time.Now(), steps: steps}
}

func (c *stepClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	t := c.now
	if len(c.steps) > 0 {
		c.now = c.now.Add(c.steps[0])
		c.steps = c.steps[1:]
	}
	return t
}

// newPublisher 는 테스트 발행자다 — writer 토큰 writer · 기본 설계값 · 로그는 logs.
func newPublisher(t *testing.T, pool *pgxpool.Pool, store Store, writer string, logs *logRecorder) *Publisher {
	t.Helper()
	opt := DefaultOptions(writer, fxBaseURL)
	opt.Log = slog.New(logs)
	p, err := New(pool, store, opt)
	if err != nil {
		t.Fatalf("New 실패: %v", err)
	}
	return p
}

// loop 은 루프(커밋 7)가 할 일을 흉내 낸다 — 발행 상태를 들고, 창의 목록 값과 DISC-SEQ 를 만들어 틱을 내고,
// 결과의 새 상태로 갈아 끼운다. 계승 취소 결과는 장부 값(캐시 흉내)에 반영한다.
type loop struct {
	t   *testing.T
	pub *Publisher
	fx  *fixture
	st  State
}

// input 은 창 [from, to] 의 틱 입력이다. DISC-SEQ 는 P 가 없으면 소유 회차의 개시 base(결정 6), 있으면
// 절대식(결정 1)이다 — P 의 행은 장부에서 다시 뽑는다(결정 4).
func (l *loop) input(from, to int64) TickInput {
	l.t.Helper()
	pl := l.fx.window(from, to)
	in := TickInput{Playlist: pl, DiscontinuitySequence: l.fx.sessions[l.fx.owner].DiscontinuityBase}
	if p := l.st.Prev; p != nil && len(pl.Rows) > 0 {
		d, err := NextDiscontinuitySequence(p.Published, l.fx.window(p.MediaSequence, p.PublishedSeq), pl.Rows[0].Seq)
		if err != nil {
			l.t.Fatalf("NextDiscontinuitySequence(P %+v, MSN %d) 실패: %v", p.Published, pl.Rows[0].Seq, err)
		}
		in.DiscontinuitySequence = d
	}
	return in
}

// tick 은 창 [from, to] 로 틱을 한 번 내고 결과를 돌려준다.
func (l *loop) tick(ctx context.Context, from, to int64) Outcome {
	l.t.Helper()
	return l.run(ctx, l.input(from, to))
}

// run 은 입력 in 으로 틱을 한 번 내고 결과를 장부 값에 반영한다 — 계승 취소 · 선 GAP · DB 가 본 ③ 확정을 캐시에
// 넣는 루프의 일(커밋 5 · 7)의 흉내다.
func (l *loop) run(ctx context.Context, in TickInput) Outcome {
	l.t.Helper()
	out := l.pub.Tick(ctx, l.st, in)
	l.st = out.State
	if out.Revoked {
		l.fx.revoke()
	}
	if out.GapSeq != nil {
		l.fx.markGap(*out.GapSeq)
	}
	if out.UploadedSeq != nil {
		l.fx.markUploaded(*out.UploadedSeq)
	}
	return out
}

// mustPublish 는 틱이 새 판을 발행했어야 하는 걸음이다.
func (l *loop) mustPublish(ctx context.Context, from, to int64) Outcome {
	l.t.Helper()
	out := l.tick(ctx, from, to)
	if !out.Published || out.Err != nil {
		l.t.Fatalf("틱 [%d, %d] = %+v, want 발행", from, to, out)
	}
	return out
}

const (
	// fxStream 은 픽스처 스트림이다.
	fxStream = "str"
	// fxBaseURL 은 픽스처 목록의 URI 앞머리다.
	fxBaseURL = "https://media.pokeclip.com"
)

// fxDay 는 픽스처 행의 PDT 기준 시각이다(seq 0 의 PDT).
var fxDay = time.Date(2026, 9, 26, 0, 0, 0, 0, time.UTC)

// fxRow 는 settled 인 장부 행 하나다 — 4초 조각이고 PDT 는 seq 에 비례한다.
func fxRow(sessionID string, seq int64) boundary.Row {
	return boundary.Row{
		Seq:              seq,
		SessionID:        sessionID,
		DurationMS:       4000,
		PlaybackPDT:      fxDay.Add(time.Duration(seq) * 4 * time.Second),
		PlaybackS3Key:    fmt.Sprintf("dvr/%s/seg/%06d.m4s", fxStream, seq),
		PlaybackUploaded: true,
	}
}

// fixture 는 한 스트림의 장부(행 · 회차)다 — 캐시가 들고 있을 값을 손으로 세운 것이다.
type fixture struct {
	owner    string
	sessions map[string]rewind.Session
	// rows 는 seq 오름차순이다. 세션 필터는 window 가 한다.
	rows []boundary.Row
}

// chainFixture 는 머리 주석의 O ← P ← S 사슬이다. S 의 행은 seq 600..lastSeq 이다.
func chainFixture(lastSeq int64) *fixture {
	f := &fixture{
		owner: "S",
		sessions: map[string]rewind.Session{
			"P": {ID: "P", InheritsSession: "O", DiscontinuityBase: 5, TargetDuration: 6, MinSeq: 100, InitUploaded: true},
			"S": {ID: "S", InheritsSession: "P", DiscontinuityBase: 5, TargetDuration: 6, MinSeq: 600, InitUploaded: true},
		},
	}
	for seq := int64(100); seq < 600; seq++ {
		f.rows = append(f.rows, fxRow("P", seq))
	}
	for seq := int64(600); seq <= lastSeq; seq++ {
		f.rows = append(f.rows, fxRow("S", seq))
	}
	return f
}

// soloFixture 는 계승 없는 회차 하나(S — seq 0 에서 새로 연 회차 · base 0)의 장부다. 행은 seq 0..lastSeq 다.
func soloFixture(lastSeq int64) *fixture {
	f := &fixture{
		owner: "S",
		sessions: map[string]rewind.Session{
			"S": {ID: "S", TargetDuration: 6, MinSeq: 0, InitUploaded: true},
		},
	}
	for seq := int64(0); seq <= lastSeq; seq++ {
		f.rows = append(f.rows, fxRow("S", seq))
	}
	return f
}

// window 는 창 [from, to] 의 목록 값이다 — 소유 회차의 행과, 소유 회차가 계승 회차면 계승한 회차의 행(접두)을
// 싣는다(cache.Playlist 와 같은 거름). BaseURL 은 비워 둔다 — 발행자가 채운다.
func (f *fixture) window(from, to int64) rewind.Playlist {
	owner := f.sessions[f.owner]
	p := rewind.Playlist{StreamID: fxStream, Owner: f.owner}
	hasPrefix := false
	for _, r := range f.rows {
		if r.Seq < from || r.Seq > to {
			continue
		}
		switch {
		case r.SessionID == f.owner:
		case owner.InheritsSession != "" && r.SessionID == owner.InheritsSession:
			hasPrefix = true
		default:
			continue
		}
		p.Rows = append(p.Rows, r)
	}
	if hasPrefix {
		p.Sessions = append(p.Sessions, f.sessions[owner.InheritsSession])
	}
	p.Sessions = append(p.Sessions, owner)
	return p
}

// revoke 는 소유 회차의 계승 취소를 장부 값에 반영한다 — 루프가 P2′ 결과를 캐시에 넣는 일(커밋 5 · 7)의 흉내다.
func (f *fixture) revoke() {
	s := f.sessions[f.owner]
	s.InheritsSession, s.DiscontinuityBase = "", 0
	f.sessions[f.owner] = s
}

// markGap 은 seq 행을 GAP 원장에 든 것으로 둔다 — 루프가 cache.ApplyPublishedGap 을 부르는 일의 흉내다. 없는 행이면
// 아무것도 하지 않는다.
func (f *fixture) markGap(seq int64) {
	for i := range f.rows {
		if f.rows[i].Seq == seq {
			f.rows[i].IsGap = true
		}
	}
}

// markUploaded 는 seq 행의 ③ 이 올라간 것으로 둔다 — cache.ApplyPlaybackUploaded 의 흉내다.
func (f *fixture) markUploaded(seq int64) {
	for i := range f.rows {
		if f.rows[i].Seq == seq {
			f.rows[i].PlaybackUploaded = true
		}
	}
}

// setInitUploaded 는 회차 id 의 init 업로드 여부를 장부 값에서 바꾼다.
func (f *fixture) setInitUploaded(id string, uploaded bool) {
	s := f.sessions[id]
	s.InitUploaded = uploaded
	f.sessions[id] = s
}

// without 는 seq 인 행을 뺀 목록 값이다 — 캐시가 행을 놓친 뷰(대조 실패 픽스처)다.
func without(p rewind.Playlist, seq int64) rewind.Playlist {
	p.Rows = slices.DeleteFunc(slices.Clone(p.Rows), func(r boundary.Row) bool { return r.Seq == seq })
	return p
}

// mustRender 는 목록 값 pl 의 본문이다(BaseURL 은 부르는 쪽이 채운다).
func mustRender(t *testing.T, pl rewind.Playlist) []byte {
	t.Helper()
	body, err := rewind.Render(pl)
	if err != nil {
		t.Fatalf("Render 실패: %v", err)
	}
	return body
}

// sha256Of 는 본문의 sha256 이다.
func sha256Of(body []byte) [sha256.Size]byte { return sha256.Sum256(body) }

// peer 는 같은 풀 · 저장소 · 장부를 보는 다른 writer 의 루프다 — 재기동한 프로세스(새 토큰 · 영값 상태)나 lease 를
// 잃은 좀비의 후임이다. 장부 값은 복사한다(writer 마다 캐시가 따로다).
func (l *loop) peer(writer string, logs *logRecorder) *loop {
	l.t.Helper()
	fx := &fixture{owner: l.fx.owner, sessions: maps.Clone(l.fx.sessions), rows: slices.Clone(l.fx.rows)}
	return &loop{t: l.t, pub: newPublisher(l.t, l.pub.pool, l.pub.store, writer, logs), fx: fx}
}

// holdPut 은 첫 PUT 을 붙잡아 두는 훅이다 — 요청은 저장소에 갔지만 아직 적용되지 않았다(늦게 닿는 쓰기 — 가정 (δ)
// 이 깨진 국면). 부른 writer 는 context.DeadlineExceeded 를 받는다(결과 모름). 붙잡은 요청은 테스트가 원하는
// 순간에 applyPut 으로 적용한다.
func holdPut(f *fakeStore, held *putCall) func(putCall) error {
	return func(c putCall) error {
		*held = c
		f.setOnPut(nil)
		return context.DeadlineExceeded
	}
}

// storedGen 은 저장된 판의 메타 pc-gen 이다(없으면 0).
func storedGen(t *testing.T, f *fakeStore, key string) int64 {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	d, err := parseMeta(f.objects[key].meta, "S")
	if err != nil {
		t.Fatalf("저장된 판의 메타 해석 실패: %v", err)
	}
	return d.Gen
}

// stored 는 저장된 판의 본문과 ETag 다.
func stored(f *fakeStore, key string) ([]byte, string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	o := f.objects[key]
	return o.body, o.etag
}

// dsns 는 본문의 조각 URI → Discontinuity Sequence Number 다(RFC 8216bis-22 6.2.1 — DISC-SEQ + 그 조각 URI 줄까지
// 앞에 선 끊김 표시 수).
func dsns(t *testing.T, body []byte) map[string]int64 {
	t.Helper()
	out := map[string]int64{}
	var base, tags int64
	for _, line := range strings.Split(strings.TrimSuffix(string(body), "\n"), "\n") {
		switch {
		case strings.HasPrefix(line, "#EXT-X-DISCONTINUITY-SEQUENCE:"):
			n, err := strconv.ParseInt(strings.TrimPrefix(line, "#EXT-X-DISCONTINUITY-SEQUENCE:"), 10, 64)
			if err != nil {
				t.Fatalf("DISC-SEQ 줄 %q: %v", line, err)
			}
			base = n
		case line == "#EXT-X-DISCONTINUITY":
			tags++
		case line != "" && !strings.HasPrefix(line, "#"):
			out[line] = base + tags
		}
	}
	return out
}

// sameCommonDSN 은 두 본문에 함께 실린 조각의 DSN 이 같은지 본다(RFC 8216bis-22 6.2.2 — 남은 조각의 DSN 불변).
// 함께 실린 조각이 하나도 없으면 실패다 — 견줄 것이 없는 비교는 아무것도 재지 않는다.
func sameCommonDSN(t *testing.T, before, after []byte) {
	t.Helper()
	a, b := dsns(t, before), dsns(t, after)
	common := 0
	for uri, d := range a {
		if e, ok := b[uri]; ok {
			common++
			if d != e {
				t.Errorf("조각 %s 의 DSN 이 %d → %d 로 바뀌었다", uri, d, e)
			}
		}
	}
	if common == 0 {
		t.Error("두 본문에 함께 실린 조각이 없다")
	}
}
