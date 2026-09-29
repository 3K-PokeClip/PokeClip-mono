package main

// 4.1 종료 전이 루프 층 시험의 픽스처와 가짜 부품(offline_loop_test.go 가 쓴다 — 체크리스트 459 판단 J59). 인덱서는
// 실물이고 관측 · 프로브 · EndLive 는 가짜, 되감기 캐시는 실물이다.
//
// 시각: 수집 시작은 실시계(StartCollect 의 time.Now)라 시험이 합성할 수 없다. 그래서 판정 시각을 실시계 기준으로
// 거꾸로 둔다 — 기다림을 여는 관측(since)을 픽스처 기준 시각 t0 보다 15초 앞에 두면, t0 뒤에 발사한 실제 수집은 늘
// since + IdleTimeout(10초) 뒤에 시작한다. 판정은 실시계를 읽지 않으므로 틱의 now 는 실시계보다 앞이어도 뒤여도
// 된다. 재점검(마지막 수집 시작 + 30초)의 경계는 발사를 사이에 둔 실시계 두 값(fireStamp)으로 세운다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxstate"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// ---------------------------------------------------------------------------
// 픽스처
// ---------------------------------------------------------------------------

// offlineLoop 은 4.1 루프 층 픽스처다 — 루프 보조 타입과 그것이 부르는 실물 인덱서 · 캐시를 든다.
type offlineLoop struct {
	t      *testing.T
	root   string
	store  *fakeStore
	obs    *fakeObserver
	probe  *loopProbe
	logs   *logCapture
	ix     *indexer.Indexer
	cache  *cache.Cache
	ends   *fakeEndLive
	rewind rewindLoop
	// t0 은 픽스처를 만든 실시계 시각이고 since 는 t0 − 15초다 — 기다림을 여는 관측 시각의 기본값.
	t0, since time.Time
	// last 는 마지막으로 수집을 발사한 틱의 실시계 창이다(재점검 경계용).
	last fireStamp
}

// fireStamp 는 수집 발사를 사이에 둔 실시계 두 값이다 — 실제 수집 시작은 그 사이에 있다.
type fireStamp struct{ before, after time.Time }

// newOfflineLoop 은 4.1 루프 층 픽스처를 만든다. 인덱서 설정은 운영 기본값(여유 10초 · OBS_FRESH 30초)에서 시작하고
// 크기 안정 대기만 ms 급으로 줄인다(크기가 계속 변하는 조각의 측정 포기를 빨리 보려고). edit 가 나머지를 고친다.
func newOfflineLoop(t *testing.T, edit func(*indexer.Options)) *offlineLoop {
	t.Helper()
	now := time.Now()
	f := &offlineLoop{
		t: t, root: t.TempDir(), store: &fakeStore{}, obs: &fakeObserver{}, probe: &loopProbe{},
		logs: &logCapture{}, cache: &cache.Cache{}, ends: &fakeEndLive{},
		t0: now, since: now.Add(-15 * time.Second),
	}
	opt := indexer.DefaultOptions()
	opt.SegmentRoot = f.root
	opt.ObsFresh = 30 * time.Second
	opt.Settle = recording.SettleOptions{
		PollInterval: 5 * time.Millisecond, SettleWait: 10 * time.Millisecond, MaxSettle: 60 * time.Millisecond,
	}
	if edit != nil {
		edit(&opt)
	}
	log := slog.New(f.logs)
	f.ix = indexer.New(f.store, f.probe.fn, noopAdopter{}, nil, f.obs, opt, log)
	f.rewind = rewindLoop{ix: f.ix, root: f.root, cache: f.cache, log: log, endLive: f.ends.endLive}
	return f
}

// at 은 since 에서 d 만큼 지난 판정 시각이다.
func (f *offlineLoop) at(d time.Duration) time.Time { return f.since.Add(d) }

// wall 은 t0 한 시간 전에서 off 만큼 지난 조각 벽시계다 — 유입 정지(②)가 늘 참인 옛 조각이다.
func (f *offlineLoop) wall(off time.Duration) time.Time { return f.t0.Add(off - time.Hour) }

// segPath 는 wall 에 시작한 조각의 경로다(recordPath 파일 이름 형식).
func (f *offlineLoop) segPath(streamID string, wall time.Time) string {
	f.t.Helper()
	dir := filepath.Join(f.root, streamID)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		f.t.Fatalf("디렉터리 생성 실패: %v", err)
	}
	w := wall.UTC()
	return filepath.Join(dir, fmt.Sprintf("%s-%06d.mp4", w.Format("2006-01-02_15-04-05"), w.Nanosecond()/1000))
}

// makeFile 은 wall 에 시작한 조각 파일을 만들고 mtime 을 한 시간 전으로 돌린다(유휴 판정을 지난다).
func (f *offlineLoop) makeFile(streamID string, wall time.Time) string {
	f.t.Helper()
	p := f.segPath(streamID, wall)
	if err := os.WriteFile(p, make([]byte, 100), 0o600); err != nil {
		f.t.Fatalf("파일 생성 실패: %v", err)
	}
	f.touch(p, time.Now().Add(-time.Hour))
	return p
}

func (f *offlineLoop) touch(path string, at time.Time) {
	f.t.Helper()
	if err := os.Chtimes(path, at, at); err != nil {
		f.t.Fatalf("mtime 조정 실패: %v", err)
	}
}

// danglingSegment 는 가리키는 파일이 없는 조각 모양의 심볼릭 링크다 — 수집은 그 항목을 보지만 stat 은 늘 실패한다.
func (f *offlineLoop) danglingSegment(streamID string, wall time.Time) string {
	f.t.Helper()
	p := f.segPath(streamID, wall)
	if err := os.Symlink(filepath.Join(f.root, "없는-파일"), p); err != nil {
		f.t.Fatalf("심볼릭 링크 생성 실패: %v", err)
	}
	return p
}

// handle 은 path 의 조각을 reason 유입으로 인덱서에 넣는다(워처 FIFO · 훅 리더가 하는 일).
func (f *offlineLoop) handle(path string, reason recording.CompletionReason) {
	f.t.Helper()
	seg, err := recording.ParseSegmentPath(f.root, path)
	if err != nil {
		f.t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = reason
	if err := f.ix.Handle(f.t.Context(), seg); err != nil {
		f.t.Fatalf("Handle 실패: %v", err)
	}
}

// boot 는 스트림마다 옛 조각 둘(wall 0 · +4초)을 워처 유입으로 넣는다 — 커서 꼬리 seq 1 이 선다. 수집은 돌리지
// 않으므로 어떤 기다림의 점검 자격도 없다(마지막 수집 시작이 영값이다).
func (f *offlineLoop) boot(streams ...string) {
	f.t.Helper()
	for _, s := range streams {
		f.handle(f.makeFile(s, f.wall(0)), recording.ReasonNextFile)
		f.handle(f.makeFile(s, f.wall(4*time.Second)), recording.ReasonNextFile)
		if rows := f.store.rowsOf(s); len(rows) != 2 {
			f.t.Fatalf("부트 뒤 %s 행 = %d개, want 2 — 준비가 어긋났다", s, len(rows))
		}
	}
}

// offAir 는 스트림의 관측을 at 의 「송출 중 아님」(항목 부재 형상 — 폴 성공 · Publishing 거짓)으로 둔다.
func (f *offlineLoop) offAir(streamID string, at time.Time) {
	f.obs.set(streamID, mtxstate.Observation{ObservedAt: at, EpochKnown: true})
}

// onAir 는 스트림의 관측을 at 의 「송출 중」(tier ⓘ)으로 둔다.
func (f *offlineLoop) onAir(streamID string, at time.Time) {
	f.obs.set(streamID, mtxstate.Observation{
		Publishing: true, ObservedAt: at, EpochStartedAt: at.Add(-time.Minute),
		EpochKnown: true, Tier: mtxstate.TierOnlineTime,
	})
}

// tick 은 holdTicks case 의 4.1 몫을 now 로 한 번 돌린다 — 루프 보조 타입을 부르고, 그 틱이 점검 수집을 발사했으면
// 루프의 CollectDone case 처럼 결과를 받아 적용한다. 발사했는가를 돌려준다.
func (f *offlineLoop) tick(now time.Time) bool { return f.tickApplying(f.t.Context(), now) }

// tickApplying 은 tick 이되 발사한 수집을 applyCtx 로 적용한다 — 끝난 ctx 면 그 수집은 완주가 아니다.
//
// 발사 여부는 CollectOverdue(-1) 로 동기에 잰다: 음수 예산은 경과가 늘 넘으므로 수집이 비행 중(아직 적용 안 됨)이면
// 참이다. 이 픽스처에서 수집을 적용하는 것은 시험뿐이라 틱 뒤에 비행 중이면 곧 그 틱이 발사한 것이다.
func (f *offlineLoop) tickApplying(applyCtx context.Context, now time.Time) bool {
	f.t.Helper()
	before := time.Now()
	f.rewind.endOfflineSessions(f.t.Context(), now)
	if !f.ix.CollectOverdue(-1) {
		return false
	}
	f.last = fireStamp{before: before, after: time.Now()}
	f.apply(applyCtx)
	return true
}

// apply 는 비행 중인 수집의 결과를 받아 ctx 로 적용한다(루프의 CollectDone case).
func (f *offlineLoop) apply(ctx context.Context) {
	f.t.Helper()
	select {
	case res := <-f.ix.CollectDone():
		if _, err := f.ix.ApplyCollect(ctx, f.root, res); err != nil {
			f.t.Fatalf("ApplyCollect 실패: %v", err)
		}
	case <-time.After(5 * time.Second):
		f.t.Fatal("수집 결과가 5초 안에 오지 않았다")
	}
}

// periodic 은 4.1 밖의 수집(주기 재스캔) 한 번을 발사하고 적용한다.
func (f *offlineLoop) periodic() { f.periodicWith(f.t.Context(), f.t.Context()) }

// periodicWith 는 periodic 이되 수집을 startCtx 로 발사하고 applyCtx 로 적용한다 — 끝난 startCtx 는 수집 오류
// (순회가 ctx 로 멈춤), 끝난 applyCtx 는 적용 중 중단이다. 발사 창을 f.last 에 적는다.
func (f *offlineLoop) periodicWith(startCtx, applyCtx context.Context) {
	f.t.Helper()
	before := time.Now()
	if !f.ix.StartCollect(startCtx, f.root) {
		f.t.Fatal("주기 수집이 거부됐다 — 앞 수집이 비행 중이다")
	}
	f.last = fireStamp{before: before, after: time.Now()}
	f.apply(applyCtx)
}

// judge 는 스트림들의 관측을 now 의 「송출 중 아님」으로 새로 두고 tick 한다 — 관측 시각을 옮겨도 열린 기다림의
// since 는 그대로다(판정 층 시험의 judge 와 같은 형).
func (f *offlineLoop) judge(now time.Time, streams ...string) bool {
	f.t.Helper()
	for _, s := range streams {
		f.offAir(s, now)
	}
	return f.tick(now)
}

// drive 는 since+from 부터 since+to 앞까지 1초마다 judge 를 부르고 4.1 이 점검 수집을 발사한 횟수를 돌려준다.
func (f *offlineLoop) drive(from, to time.Duration, streams ...string) (fires int) {
	f.t.Helper()
	for d := from; d < to; d += time.Second {
		if f.judge(f.at(d), streams...) {
			fires++
		}
	}
	return fires
}

// view 는 스트림에 컷오프 있는 뷰를 적재로 세운다 — 회차 sessionID 가 state 로 들고 init 이 올라가 있다.
func (f *offlineLoop) view(streamID, sessionID, state string) {
	f.cache.CompleteLoad(streamID, f.cache.BeginLoad(streamID, nil), index.RewindLedger{
		HasCutoff: true,
		Sessions:  []index.RewindSession{{SessionID: sessionID, State: state, InitUploaded: true, TargetDuration: 6}},
	})
}

// sessionState 는 캐시가 드는 그 회차의 state 다(모르면 "").
func (f *offlineLoop) sessionState(streamID, sessionID string) string {
	s, ok := f.cache.Session(streamID, sessionID)
	if !ok {
		return ""
	}
	return s.State
}

// tripLatch 는 스트림에 멈추는 조각을 워처 유입으로 흘려 FS 래치를 세운다 — 프로브가 FSOpTimeout 을 넘겨 버려지면
// 인덱서가 래치를 트립한다(fs_degraded). 그 조각은 장부에 들지 않고 파일은 곧바로 지운다(뒤 수집이 보지 않게).
// 멈춘 프로브 워커는 시험이 끝날 때 풀어 준다.
func (f *offlineLoop) tripLatch(streamID string) {
	f.t.Helper()
	p := f.makeFile(streamID, f.wall(20*time.Second))
	f.t.Cleanup(f.probe.stallOn(p))
	f.handle(p, recording.ReasonNextFile)
	if f.logs.countLevel(slog.LevelError, "fs_degraded") == 0 {
		f.t.Fatal("래치가 트립되지 않았다 — 준비가 어긋났다")
	}
	if err := os.Remove(p); err != nil {
		f.t.Fatalf("멈춘 조각 삭제 실패: %v", err)
	}
}

// fakeObserver 는 관측 폴러 대신 관측 스냅샷을 직접 준다. 루프 고루틴과 시험 고루틴이 함께 만진다(락).
type fakeObserver struct {
	mu  sync.Mutex
	obs map[string]mtxstate.Observation
}

func (o *fakeObserver) Latest(streamID string) mtxstate.Observation {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.obs[streamID]
}

func (o *fakeObserver) set(streamID string, v mtxstate.Observation) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.obs == nil {
		o.obs = map[string]mtxstate.Observation{}
	}
	o.obs[streamID] = v
}

// loopProbe 는 조각 길이 프로브다 — 기본은 4000ms 이고 경로마다 길이 0 · 크기 성장 · 파일 삭제 · 멈춤을 준다. 프로브는
// FS 워커 고루틴에서 돈다(락).
type loopProbe struct {
	mu     sync.Mutex
	zero   map[string]bool
	grow   map[string]int
	remove map[string]bool
	stall  map[string]chan struct{}
}

func (p *loopProbe) fn(path string) (int64, error) {
	p.mu.Lock()
	stall, zero := p.stall[path], p.zero[path]
	if n := p.grow[path]; n > 0 { // 재는 동안에도 쓰이고 있다 — 프로브마다 파일이 자란다
		p.grow[path] = n - 1
		_ = os.WriteFile(path, make([]byte, 100*(6-n)), 0o600)
	}
	if p.remove[path] { // 확인 stat 은 지났고 측정 도중 파일이 사라진다
		delete(p.remove, path)
		_ = os.Remove(path)
	}
	p.mu.Unlock()
	if stall != nil {
		<-stall
	}
	if zero {
		return 0, nil
	}
	return 4000, nil
}

func (p *loopProbe) zeroOn(path string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.zero = setKey(p.zero, path, true)
}

func (p *loopProbe) growOn(path string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.grow = setKey(p.grow, path, 3)
}

func (p *loopProbe) removeOn(path string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.remove = setKey(p.remove, path, true)
}

// stallOn 은 path 의 프로브를 멈춘다. 돌려준 함수가 풀어 준다.
func (p *loopProbe) stallOn(path string) (release func()) {
	p.mu.Lock()
	defer p.mu.Unlock()
	ch := make(chan struct{})
	p.stall = setKey(p.stall, path, ch)
	return func() { close(ch) }
}

// setKey 는 m 에 k → v 를 넣은 맵이다(m 이 nil 이면 새로 만든다).
func setKey[V any](m map[string]V, k string, v V) map[string]V {
	if m == nil {
		m = map[string]V{}
	}
	m[k] = v
	return m
}

// fakeEndLive 는 가짜 index.EndLive 다 — 부른 스트림을 차례로 적고 result 가 정한 값을 돌려준다(nil 이면 "S-"+스트림
// — 1행). 루프 고루틴에서도 불리므로 락을 쥔다.
type fakeEndLive struct {
	mu     sync.Mutex
	calls  []string
	result func(ctx context.Context, streamID string) (string, error)
}

func (e *fakeEndLive) endLive(ctx context.Context, streamID string) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.calls = append(e.calls, streamID)
	if e.result == nil {
		return "S-" + streamID, nil
	}
	return e.result(ctx, streamID)
}

func (e *fakeEndLive) called() []string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return slices.Clone(e.calls)
}

// unreadableDir 는 dir 의 권한을 모두 거둔다. 돌려준 함수를 부르거나 시험이 끝나면 되돌린다.
func unreadableDir(t *testing.T, dir string) (restore func()) {
	t.Helper()
	if err := os.Chmod(dir, 0o000); err != nil {
		t.Fatalf("권한 변경 실패: %v", err)
	}
	restore = func() {
		if err := os.Chmod(dir, 0o755); err != nil {
			t.Errorf("권한 복구 실패: %v", err)
		}
	}
	t.Cleanup(restore)
	return restore
}

// cancelledContext 는 이미 끝난 ctx 다.
func cancelledContext(t *testing.T) context.Context {
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	return ctx
}

// ladderPublisher 는 사다리 평가만 쓰는 발행자다 — 풀은 접속하지 않는 주소(pgxpool.New 는 게으르다)이고 저장소는
// 부르지 않는다.
func ladderPublisher(t *testing.T) *publish.Publisher {
	t.Helper()
	pool, err := pgxpool.New(t.Context(), "postgres://user@127.0.0.1:1/none")
	if err != nil {
		t.Fatalf("풀 생성 실패(접속은 하지 않는다): %v", err)
	}
	t.Cleanup(pool.Close)
	opt := publish.DefaultOptions("w-offline", "https://media.example.com")
	opt.Log = slog.New(&logCapture{})
	p, err := publish.New(pool, nopPublishStore{}, opt)
	if err != nil {
		t.Fatalf("발행자 생성 실패: %v", err)
	}
	return p
}

// nopPublishStore 는 발행자의 저장소 자리다 — 사다리 평가는 저장소를 부르지 않는다.
type nopPublishStore struct{}

var errNopStore = errors.New("nopPublishStore: 부르지 않는 저장소다")

func (nopPublishStore) Put(context.Context, string, publish.Precondition, []byte, map[string]string) (string, error) {
	return "", errNopStore
}

func (nopPublishStore) Head(context.Context, string) (publish.Stat, error) {
	return publish.Stat{}, errNopStore
}

func (nopPublishStore) Get(context.Context, string) ([]byte, error) { return nil, errNopStore }
