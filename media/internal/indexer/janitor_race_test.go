package indexer

// janitor(로컬 ② 정리 — POK-170)와 인덱서가 같은 녹화 폴더 · 같은 장부를 함께 쓸 때의 경합을
// 인덱서 층에서 잰다(POK-371 · 계획 6절 T3 2단계).
//
//   - D01 — Scan 이 폴더를 훑은 뒤 장부를 읽기 전에 janitor 가 파일을 지우고 local_path 를 NULL 로
//     바꾸면, 그 경로는 「미기록」으로 보여 거짓 신호(hole_detected · late_segment_skipped ·
//     latest_stat_failed)가 난다. 장부 쓰기(INSERT · 꼬리 정정)와 seq 소비는 0 이어야 한다
//     (설계 5-C 결정 (가) — 신호 오염만 수용, 한계 L3 · K3).
//   - D35 — janitor 가 행 잠금을 쥔 동안(ⓐ 파일 삭제 중 · ⓑ UPDATE 뒤 COMMIT 전) 인덱서가 다음
//     조각을 넣어도 기다리지 않고, insert_lock_contended · insert_retry 가 0줄이다.
//   - D40 — 인덱서 H9 의 동일 Record 재시도(첫 INSERT 는 커밋됐는데 응답이 사라짐)가 janitor ⓑ 와
//     겹쳐도 insert_lock_contended 가 0줄이고, 로그 갈래 · 장부 결과가 janitor 없는 기준선과 같다.
//
// D35 · D40 은 실 PG 와 실제 RemoveUnderRowLock 을 쓴다(PG_DSN 미설정이면 skip — TestMain 의
// REQUIRE_PG=1 게이트가 CI 에서 skip 을 실패로 바꾼다).

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// ---------------------------------------------------------------------------
// D01 — Scan 훑기와 장부 적재 사이의 janitor 삭제
// ---------------------------------------------------------------------------

// raceScanStore 는 가짜 장부다. 첫 ExistingPaths 직전에 한 번 between 을 돌린다 — Scan 의 훑기
// (collectTree)는 이미 끝났고 장부 적재(scanStream)는 아직인 순간이다.
type raceScanStore struct {
	*fakeStore
	once    sync.Once
	between func()
}

func (s *raceScanStore) ExistingPaths(ctx context.Context, streamID string) (map[string]struct{}, error) {
	s.once.Do(s.between)
	return s.fakeStore.ExistingPaths(ctx, streamID)
}

// janitorDelete 는 janitor 가 한 행에 하는 일(파일 삭제 → local_path=NULL)을 가짜 장부에 그대로 한다.
func (s *raceScanStore) janitorDelete(t *testing.T, streamID string, seq int64) {
	t.Helper()
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, r := range s.rows[streamID] {
		if r.Seq != seq {
			continue
		}
		if err := os.Remove(r.LocalPath); err != nil {
			t.Fatalf("janitor 흉내 삭제 실패 seq %d: %v", seq, err)
		}
		s.rows[streamID][i].LocalPath = ""
		return
	}
	t.Fatalf("janitor 흉내: seq %d 행이 없다", seq)
}

const raceStream = "race"

// raceScanFixture 는 seq 0 · 1 · 2 를 장부와 폴더에 함께 둔다(0 · 1 은 uploaded, 2 는 pending 꼬리).
func raceScanFixture(t *testing.T) (*fixture, *raceScanStore) {
	t.Helper()
	f := newFixture(t)
	st := &raceScanStore{fakeStore: f.store, between: func() {}}
	for seq := int64(0); seq <= 2; seq++ {
		wall := baseWall.Add(time.Duration(seq) * 4 * time.Second)
		path := f.makeFile(raceStream, segName(wall, 0), 1000)
		state := index.UploadStateUploaded
		if seq == 2 {
			state = index.UploadStatePending
		}
		f.store.seed(index.Record{
			StreamID: raceStream, Seq: seq, StartPTSMS: seq * 4000, StartWallUTC: wall,
			DurationMS: 4000, LocalPath: path, UploadState: state, Bytes: 1000,
		})
	}
	f.ix = New(st, f.probe.fn, f.adopter, f.upload, f.observer, f.opt, slog.New(f.logs))
	return f, st
}

func TestJanitorRaceScanFalseSignalsWithoutLedgerWrites(t *testing.T) {
	tests := []struct {
		name    string
		deleted []int64        // janitor 가 훑기와 적재 사이에 지우는 행
		want    map[string]int // 메시지 → 줄 수(WARN/ERROR 전부 — 여기 없는 WARN/ERROR 는 0 이어야 한다)
	}{
		{name: "음성 대조 — 삭제 없음", deleted: nil, want: map[string]int{}},
		{
			// seq 1 하나 → pending 은 그것뿐이라 「최신 미기록」 판정의 stat 이 실패한다.
			name: "가운데 한 행", deleted: []int64{1},
			want: map[string]int{"hole_detected": 1, "latest_stat_failed": 1},
		},
		{
			// seq 0 · 1 → 앞 것은 Handle 의 H3(늦은 조각) 재적재에서 NULL 을 보고 ERROR, 뒤 것은 stat 실패.
			name: "앞 두 행", deleted: []int64{0, 1},
			want: map[string]int{"hole_detected": 2, "late_segment_skipped": 1, "latest_stat_failed": 1},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f, st := raceScanFixture(t)
			st.between = func() {
				for _, seq := range tt.deleted {
					st.janitorDelete(t, raceStream, seq)
				}
			}
			before := f.store.records(raceStream)

			if err := f.ix.Scan(context.Background(), f.root); err != nil {
				t.Fatalf("Scan 오류 = %v, want nil — 거짓 신호는 Scan 을 멈추지 않는다", err)
			}

			assertRaceSignals(t, f.logs, tt.want)
			assertNoLedgerWrites(t, f, before)
		})
	}
}

// assertRaceSignals 는 WARN 이상 로그가 정확히 want 와 같은지 본다.
func assertRaceSignals(t *testing.T, logs *logCapture, want map[string]int) {
	t.Helper()
	got := warnAndErrorCounts(logs)
	for msg, n := range want {
		if got[msg] != n {
			t.Errorf("%s 줄 수 = %d, want %d (전체 %v)", msg, got[msg], n, got)
		}
	}
	for msg, n := range got {
		if _, ok := want[msg]; !ok {
			t.Errorf("예상 밖 WARN/ERROR %s = %d줄 (전체 %v)", msg, n, got)
		}
	}
}

// assertNoLedgerWrites 는 Scan 이 장부에 아무것도 쓰지 않았는지 본다 — INSERT 시도 0 · 꼬리 정정 0 ·
// 행 집합(seq) 그대로.
func assertNoLedgerWrites(t *testing.T, f *fixture, before []index.Record) {
	t.Helper()
	if n := f.store.insertCallCount(); n != 0 {
		t.Errorf("INSERT 시도 = %d, want 0 — 지운 경로를 새 조각으로 다시 넣으면 seq 를 소비한다", n)
	}
	f.store.mu.Lock()
	tails := len(f.store.updateTail)
	f.store.mu.Unlock()
	if tails != 0 {
		t.Errorf("꼬리 정정 = %d, want 0", tails)
	}
	after := f.store.records(raceStream)
	if len(after) != len(before) {
		t.Fatalf("행 수 = %d, want %d", len(after), len(before))
	}
	for i := range after {
		if after[i].Seq != before[i].Seq {
			t.Errorf("행 %d 의 seq = %d, want %d — seq 는 바뀌지 않는다", i, after[i].Seq, before[i].Seq)
		}
	}
}

func warnAndErrorCounts(logs *logCapture) map[string]int {
	logs.mu.Lock()
	defer logs.mu.Unlock()
	out := map[string]int{}
	for _, r := range logs.records {
		if r.Level >= slog.LevelWarn {
			out[r.Message]++
		}
	}
	return out
}

// warnAndErrorSeq 는 WARN 이상 메시지를 기록 순서대로 돌려준다(기준선 대조용).
func warnAndErrorSeq(logs *logCapture) []string {
	logs.mu.Lock()
	defer logs.mu.Unlock()
	var out []string
	for _, r := range logs.records {
		if r.Level >= slog.LevelWarn {
			out = append(out, r.Message)
		}
	}
	return out
}

// ---------------------------------------------------------------------------
// 실 PG — janitor 의 행 잠금을 실제 RemoveUnderRowLock 으로 쥔다
// ---------------------------------------------------------------------------

// raceTracer 는 janitor 연결이 COMMIT 을 보내기 직전에 멈춰 세운다(국면 ⓑ). 질의를 보낸
// 고루틴에서 불리므로 여기서 기다리면 그 트랜잭션이 UPDATE 뒤 · COMMIT 전에 머문다.
type raceTracer struct {
	reached  chan struct{}
	open     chan struct{}
	reachOne sync.Once
	openOne  sync.Once
}

func newRaceTracer() *raceTracer {
	return &raceTracer{reached: make(chan struct{}), open: make(chan struct{})}
}

func (tr *raceTracer) TraceQueryStart(ctx context.Context, _ *pgx.Conn, data pgx.TraceQueryStartData) context.Context {
	if data.SQL == "commit" {
		tr.reachOne.Do(func() { close(tr.reached) })
		<-tr.open
	}
	return ctx
}

func (*raceTracer) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}

func (tr *raceTracer) release() { tr.openOne.Do(func() { close(tr.open) }) }

// raceJanitorPool 은 janitor 와 같은 세션 설정(cmd/janitor applySessionLimits)을 건 별도 풀이다.
func raceJanitorPool(t *testing.T, base *pgxpool.Pool, tracer pgx.QueryTracer) *pgxpool.Pool {
	t.Helper()
	cfg := base.Config()
	if cfg.ConnConfig.RuntimeParams == nil {
		cfg.ConnConfig.RuntimeParams = map[string]string{}
	}
	rp := cfg.ConnConfig.RuntimeParams
	rp["statement_timeout"] = "15s"
	rp["lock_timeout"] = "2s"
	rp["idle_in_transaction_session_timeout"] = "30s"
	rp["application_name"] = "race-janitor"
	cfg.ConnConfig.Tracer = tracer
	cfg.MaxConns = 2
	pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("janitor 시험 풀 생성 실패: %v", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

// raceHold 는 RemoveUnderRowLock 한 번이 행을 쥔 상태다. finish 가 janitor 를 끝까지 보내고 결과를 준다.
type raceHold struct {
	finish func() (index.RowLockResult, error)
}

// holdRowLock 은 janitor 를 phase(ⓐ · ⓑ)에서 그 행을 쥔 채 세운다. ⓐ 는 remove 콜백 안에서
// 기다리고, ⓑ 는 remove 가 clear=true 를 돌려준 뒤 COMMIT 직전에서 기다린다. 파일은 지우지 않는다 —
// 여기서 재는 것은 잠금 상호작용뿐이다(파일 부재의 영향은 D01 이 잰다).
func holdRowLock(t *testing.T, base *pgxpool.Pool, streamID string, seq int64, path, phase string) raceHold {
	t.Helper()
	tr := newRaceTracer()
	store := index.NewLocalCleanupStore(raceJanitorPool(t, base, tr))
	ctx, cancel := context.WithTimeout(context.Background(), index.RowLockTxnTimeout+5*time.Second)
	locked, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var res index.RowLockResult
	var err error
	go func() {
		defer close(done)
		res, err = store.RemoveUnderRowLock(ctx, streamID, seq, path, func() bool {
			close(locked)
			if phase == "ⓐ" {
				select {
				case <-release:
				case <-ctx.Done():
				}
			}
			return true
		})
	}()
	var relOne sync.Once
	finish := func() (index.RowLockResult, error) {
		relOne.Do(func() { close(release); tr.release() })
		<-done
		return res, err
	}
	// 풀(raceJanitorPool 의 Cleanup)보다 늦게 등록해 먼저 돈다 — janitor 를 풀어 끝낸 뒤 풀이 닫힌다.
	t.Cleanup(func() { _, _ = finish(); cancel() })
	awaitRaceSignal(t, locked, done, "행 잠금")
	if phase == "ⓑ" {
		awaitRaceSignal(t, tr.reached, done, "COMMIT 직전")
	}
	return raceHold{finish: finish}
}

func awaitRaceSignal(t *testing.T, sig, done <-chan struct{}, what string) {
	t.Helper()
	select {
	case <-sig:
	case <-done:
		t.Fatalf("janitor 가 %s 전에 끝났다", what)
	case <-time.After(5 * time.Second):
		t.Fatalf("janitor 가 5s 안에 %s 에 닿지 않았다", what)
	}
}

// raceIngest 는 wallAgo 전 벽시계의 조각을 유입 경로 하나로 넣고 걸린 시간을 돌려준다.
func raceIngest(t *testing.T, f *phaseFixture, reason recording.CompletionReason, wallAgo time.Duration) (time.Duration, error) {
	t.Helper()
	seg := f.segment(wallAgo, reason)
	began := time.Now()
	var err error
	if reason == recording.ReasonScan {
		err = f.ix.Scan(context.Background(), f.root)
	} else {
		err = f.ix.Handle(context.Background(), seg)
	}
	return time.Since(began), err
}

// raceTail 은 그 스트림의 마지막 행(seq · 경로 · local_path NULL 여부)이다.
func raceTail(t *testing.T, f *phaseFixture) (seq int64, path string, n int) {
	t.Helper()
	ctx := context.Background()
	if err := f.pool.QueryRow(ctx, `SELECT count(*) FROM stream_segments WHERE stream_id = $1`, f.stream).Scan(&n); err != nil {
		t.Fatalf("행 수 조회 실패: %v", err)
	}
	if n == 0 {
		return 0, "", 0
	}
	var p *string
	if err := f.pool.QueryRow(ctx, `SELECT seq, local_path FROM stream_segments WHERE stream_id = $1
	                                ORDER BY seq DESC LIMIT 1`, f.stream).Scan(&seq, &p); err != nil {
		t.Fatalf("꼬리 조회 실패: %v", err)
	}
	if p != nil {
		path = *p
	}
	return seq, path, n
}

// ---------------------------------------------------------------------------
// D35 — janitor 잠금 중 다음 조각 INSERT
// ---------------------------------------------------------------------------

// raceInsertBudget 은 잠금과 무관한 INSERT 한 번(Handle · Scan 포함)의 상한이다. 기다리면
// lock_timeout(5s) 또는 janitor 의 transaction_timeout(8s) 근처까지 늘어 이 값을 넘는다.
const raceInsertBudget = time.Second

func TestJanitorRaceLockDoesNotStallNextInsert(t *testing.T) {
	phases := []struct{ mark, slug string }{{"ⓐ", "a"}, {"ⓑ", "b"}}
	for _, ph := range phases {
		for _, reason := range []recording.CompletionReason{recording.ReasonNextFile, recording.ReasonScan} {
			t.Run(ph.mark+"/"+reasonName(reason), func(t *testing.T) {
				f := newPhaseFixture(t, "jrace-lock-"+ph.slug+"-"+reasonName(reason), phase{watcher: true})
				f.publishing(time.Second, time.Minute)
				for _, ago := range []time.Duration{40 * time.Second, 36 * time.Second, 32 * time.Second} {
					if _, err := raceIngest(t, f, recording.ReasonNextFile, ago); err != nil {
						t.Fatalf("고정물 조각 유입 실패: %v", err)
					}
				}
				seq, path, n := raceTail(t, f)
				if n != 3 {
					t.Fatalf("고정물 행 수 = %d, want 3", n)
				}
				markRaceUploaded(t, f)
				hold := holdRowLock(t, f.pool, f.stream, seq, path, ph.mark)

				took, err := raceIngest(t, f, reason, 28*time.Second)

				if err != nil {
					t.Fatalf("janitor 잠금 중 유입 오류 = %v, want nil", err)
				}
				_, _, after := raceTail(t, f)
				if after != 4 {
					t.Errorf("janitor 잠금 중 유입 뒤 행 수 = %d, want 4", after)
				}
				if took > raceInsertBudget {
					t.Errorf("유입 대기 = %v, want ≤ %v — janitor 잠금을 기다렸다", took, raceInsertBudget)
				}
				assertNoLockSignals(t, f.logs)
				if res, herr := hold.finish(); herr != nil || res.Outcome != index.RowLockCleared {
					t.Errorf("janitor 결과 = (%+v, %v), want Cleared — 자극이 끝까지 닿지 않았다", res, herr)
				}
				t.Logf("기록: %s %s 유입 대기 = %v", ph.mark, reasonName(reason), took)
			})
		}
	}
}

func reasonName(r recording.CompletionReason) string {
	if r == recording.ReasonScan {
		return "scan"
	}
	return "next-file"
}

func markRaceUploaded(t *testing.T, f *phaseFixture) {
	t.Helper()
	if _, err := f.pool.Exec(context.Background(), `UPDATE stream_segments SET upload_state = 'uploaded', uploaded_at = now()
	                                                 WHERE stream_id = $1`, f.stream); err != nil {
		t.Fatalf("고정물 업로드 상태 설정 실패: %v", err)
	}
}

func assertNoLockSignals(t *testing.T, logs *logCapture) {
	t.Helper()
	for _, msg := range []string{"insert_lock_contended", "insert_retry"} {
		if n := logs.count(slog.LevelWarn, msg); n != 0 {
			t.Errorf("%s = %d줄, want 0 — janitor 잠금이 인덱서 신호를 헛켰다", msg, n)
		}
	}
}

// ---------------------------------------------------------------------------
// D40 — H9 동일 Record 재시도 × janitor ⓑ
// ---------------------------------------------------------------------------

// errLostReply 는 「첫 INSERT 는 서버에서 커밋됐는데 응답이 사라졌다」를 흉내 낸 오류다. PgError 가
// 아니라 드라이버 계층 실패로 보이므로 H9 가 같은 Record 로 다시 시도한다(classifyInsertError).
var errLostReply = errors.New("시험: INSERT 응답 유실")

// lostReplyStore 는 armed 가 켜진 뒤 첫 성공 INSERT 의 응답을 버린다. afterCommit 은 그 커밋 직후 ·
// 오류를 돌려주기 전에 한 번 돈다(janitor 가 그 틈에 방금 커밋된 행을 쥔다).
type lostReplyStore struct {
	index.Store
	mu          sync.Mutex
	armed       bool
	afterCommit func(rec index.Record)
}

func (s *lostReplyStore) Insert(ctx context.Context, r index.Record, seed index.Seed, src index.SessionSource) (index.InsertOutcome, index.SeedResult, error) {
	out, res, err := s.Store.Insert(ctx, r, seed, src)
	s.mu.Lock()
	fire := s.armed && err == nil && out == index.InsertInserted
	if fire {
		s.armed = false
	}
	s.mu.Unlock()
	if !fire {
		return out, res, err
	}
	if s.afterCommit != nil {
		s.afterCommit(r)
	}
	return index.InsertInserted, index.SeedResult{}, errLostReply
}

// raceRetryRun 은 동일 Record 재시도 한 번의 관측이다.
type raceRetryRun struct {
	took    time.Duration
	err     error
	signals []string // WARN 이상 메시지(기록 순서)
	rows    int
	tailNil bool // 재시도한 행의 local_path 가 NULL 인가
}

// runSameRecordRetry 는 조각 하나를 넣어 커서를 만든 뒤, 다음 조각의 첫 INSERT 응답을 버려
// H9 가 같은 Record 로 다시 넣게 한다. withJanitor 면 그 커밋 직후 janitor 가 그 행을 ⓑ 로 쥔다.
func runSameRecordRetry(t *testing.T, name string, withJanitor bool) raceRetryRun {
	t.Helper()
	f := newPhaseFixture(t, name, phase{watcher: true})
	f.publishing(time.Second, time.Minute)
	if _, err := raceIngest(t, f, recording.ReasonNextFile, 40*time.Second); err != nil {
		t.Fatalf("첫 조각 유입 실패: %v", err)
	}
	ls := &lostReplyStore{Store: f.store, armed: true}
	if withJanitor {
		ls.afterCommit = func(rec index.Record) {
			holdRowLock(t, f.pool, rec.StreamID, rec.Seq, rec.LocalPath, "ⓑ")
		}
	}
	f.store = ls
	f.rebuild(4000)

	took, err := raceIngest(t, f, recording.ReasonNextFile, 36*time.Second)

	if ls.armed {
		t.Fatal("응답 유실이 일어나지 않았다 — 자극 미도달")
	}
	_, path, n := raceTail(t, f)
	return raceRetryRun{took: took, err: err, signals: warnAndErrorSeq(f.logs), rows: n, tailNil: path == ""}
}

func TestJanitorRaceSameRecordRetryMatchesBaseline(t *testing.T) {
	base := runSameRecordRetry(t, "jrace-retry-base", false)
	got := runSameRecordRetry(t, "jrace-retry", true)

	if n := countMsg(got.signals, "insert_lock_contended"); n != 0 {
		t.Errorf("insert_lock_contended = %d줄, want 0 — janitor ⓑ 가 단일 쓰기자 신호를 헛켰다 (%v)", n, got.signals)
	}
	if (got.err == nil) != (base.err == nil) {
		t.Errorf("Handle 오류 = %v, want 기준선과 같은 갈래 (%v)", got.err, base.err)
	}
	if !equalStrings(got.signals, base.signals) {
		t.Errorf("WARN/ERROR 갈래 = %v, want 기준선 %v", got.signals, base.signals)
	}
	if got.rows != base.rows || got.tailNil != base.tailNil {
		t.Errorf("장부 = (행 %d · NULL %v), want 기준선 (행 %d · NULL %v)", got.rows, got.tailNil, base.rows, base.tailNil)
	}
	// 원본은 janitor ⓑ 의 transaction_timeout(8s)이 끝나야 재시도가 풀린다(실측 8.02s) — 부하 큰 CI 의
	// PG 응답 지연을 흡수하게 여유를 2s 로 둔다. 판별은 변이 VT3-2(11s)가 갈래 단언으로도 잡는다(C22).
	if limit := index.RowLockTxnTimeout + 2*time.Second; got.took > limit {
		t.Errorf("재시도 대기 = %v, want ≤ %v", got.took, limit)
	}
	t.Logf("기록: 기준선 대기 %v · 갈래 %v / janitor ⓑ 겹침 대기 %v · 갈래 %v", base.took, base.signals, got.took, got.signals)
}

func countMsg(msgs []string, want string) int {
	n := 0
	for _, m := range msgs {
		if m == want {
			n++
		}
	}
	return n
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
