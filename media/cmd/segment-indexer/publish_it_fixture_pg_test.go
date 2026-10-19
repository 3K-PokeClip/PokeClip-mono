package main

// 발행 루프 통합 시험의 픽스처(체크리스트 474 B-4 · 판단 J88). 실물 인덱서(PG 장부 · 세션 결정자) · 실물 캐시 · 실물 발행자 ·
// 실물 차선 · 실물 메인 루프(실시계 1초 보류 틱)에 조건부 의미론 가짜 저장소(itStore)와 발행자 풀의 문장 추적기(itTracer)를
// 붙인다. ③ · init 업로드는 시험이 장부 갱신 뒤 Dirty 표시로 흉내 낸다 — 워커가 CAS 성공 뒤 표시하는 차례와 같다(업로드 워커
// 자체의 동작은 upload 시험 몫). 발행 패키지의 fakeStore 는 _test.go 라 가져올 수 없어 같은 의미론을 이 패키지에 둔다(J88 — 가짜
// 부품은 publish_it_fakes_pg_test.go).
//
// PG_DSN 이 없으면 skip 이다(REQUIRE_PG=1 이면 TestMain 이 실패로 바꾼다 — session_wire_test.go).

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// ---------------------------------------------------------------------------
// 하네스
// ---------------------------------------------------------------------------

// itHarness 는 통합 시험 한 판이다 — 같은 PG 장부 위에 「프로세스」 하나(인덱서 · 캐시 · 발행자 · 차선 · 루프)를 띄운다.
// stop 뒤 start 를 다시 부르면 같은 장부 · 같은 저장소 위에 새 프로세스가 뜬다(재기동).
type itHarness struct {
	t      *testing.T
	pool   *pgxpool.Pool
	store  *itStore
	stream string
	root   string
	t0     time.Time // 조각 seq 0 의 계획 시작 벽시계
	// shift 는 그 뒤 조각의 벽시계를 미는 폭이다 — 송출이 멈췄다 이어진 공백(순단)을 흉내 낸다(재귀식이 벽시계를 따라 PDT 를
	// 그만큼 민다 — index playbackPDT).
	shift time.Duration

	// 프로세스 몫 — start 가 새로 만든다.
	logs        *logCapture
	tracer      *itTracer
	ix          *indexer.Indexer
	cache       *cache.Cache
	pub         *publish.Publisher
	lanes       *publish.Lanes
	rewind      *rewindLoop
	dirty       *fakeDirty
	expedites   *itEvents // L1 — seq = 사다리가 본 행
	ticks       *itEvents // 틱 작업(GAP 틱 포함)이 끝난 시각 — ok = 발행함
	gapTicks    *itEvents // GAP 틱 — seq = 후보 · ok = GAP 이 섬(GapSeq) 또는 DB 가 이미 uploaded(UploadedSeq)
	loads       *itEvents // 적재 작업 — ok = 오류 없음
	completed   chan recording.Segment
	loopErr     chan error
	cancelLanes context.CancelFunc
	stopped     bool
}

// itEvent 는 차선 작업 · 손잡이 호출 하나의 기록이다 — start 는 작업을 시작한 시각, at 은 끝난(손잡이면 부른) 시각이다.
type itEvent struct {
	start, at time.Time
	seq       int64
	ok        bool
}

// itEvents 는 고루틴 안전한 기록 목록이다(차선 고루틴 · 루프 고루틴이 쓰고 시험 고루틴이 읽는다).
type itEvents struct {
	mu sync.Mutex
	ev []itEvent
}

func (x *itEvents) add(start time.Time, seq int64, ok bool) {
	x.mu.Lock()
	defer x.mu.Unlock()
	x.ev = append(x.ev, itEvent{start: start, at: time.Now(), seq: seq, ok: ok})
}

func (x *itEvents) list() []itEvent {
	x.mu.Lock()
	defer x.mu.Unlock()
	return slices.Clone(x.ev)
}

// since 는 at 뒤의 기록이다.
func (x *itEvents) since(at time.Time) []itEvent {
	var out []itEvent
	for _, e := range x.list() {
		if e.at.After(at) {
			out = append(out, e)
		}
	}
	return out
}

// itOptions 는 프로세스 하나의 조립 선택이다 — 영값이면 운영 기본값(감시 30초 · 캐시 튜너블 기본)이다.
type itOptions struct {
	watchEvery time.Duration
	rowCap     int
}

// newITHarness 는 PG 장부를 비우고 스트림의 컷오프(seq 0)를 넣은 하네스다. 프로세스는 start 가 띄운다.
func newITHarness(t *testing.T) *itHarness {
	t.Helper()
	skipWithoutPG(t)
	pool := newWireTestPool(t)
	h := &itHarness{t: t, pool: pool, store: newITStore(t), stream: "it7b", root: t.TempDir(),
		t0: time.Now().UTC().Truncate(time.Second)}
	h.exec(`INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel) VALUES ($1, 0, 'live_ingress', 'watcher')`, h.stream)
	return h
}

// writerToken 은 프로세스마다 새 writer 토큰이다(조립의 crypto/rand 규칙과 같은 모양 — J75).
func writerToken(t *testing.T) string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		t.Fatalf("writer 토큰 생성 실패: %v", err)
	}
	return hex.EncodeToString(b)
}

// start 는 프로세스 하나를 띄운다 — 발행자 풀(추적기 · 인덱서 풀과 따로 — J76) · 인덱서(PG 장부) · 캐시 · 발행자 · 차선 · 루프
// 보조 타입을 조립하고, 실시계 1초 보류 틱으로 메인 루프를 돌린다. 루프의 ctx 가 곧 차선 ctx 다(rewindLoop 문서).
func (h *itHarness) start(o itOptions) {
	h.t.Helper()
	h.logs, h.tracer, h.dirty = &logCapture{}, &itTracer{}, &fakeDirty{}
	h.expedites, h.ticks, h.gapTicks, h.loads = &itEvents{}, &itEvents{}, &itEvents{}, &itEvents{}
	h.completed, h.loopErr, h.stopped = make(chan recording.Segment), make(chan error, 1), false
	log := slog.New(h.logs)

	cfg := h.pool.Config()
	cfg.ConnConfig.Tracer = h.tracer
	pubPool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		h.t.Fatalf("발행자 풀 생성 실패: %v", err)
	}
	h.t.Cleanup(pubPool.Close)

	store := index.NewPGStore(h.pool, newSessionDecider(time.Second, 30*time.Second, 300*time.Second, log), playback.SegKey)
	opt := indexer.DefaultOptions()
	opt.SegmentRoot = h.root
	h.ix = indexer.New(store, func(string) (int64, error) { return 4000, nil }, noopAdopter{}, nil, nil, opt, log)
	h.cache = &cache.Cache{Options: cache.Options{LedgerRowCap: o.rowCap}}
	h.ix.SetRewindCache(h.cache)

	popt := publish.DefaultOptions(writerToken(h.t), "https://media.example.com")
	popt.Log = log
	if h.pub, err = publish.New(pubPool, h.store, popt); err != nil {
		h.t.Fatalf("발행자 생성 실패: %v", err)
	}
	h.lanes = publish.NewLanes()
	watchEvery := o.watchEvery
	if watchEvery == 0 {
		watchEvery = 30 * time.Second
	}
	pub, ticks, gapTicks, expedites, loads := h.pub, h.ticks, h.gapTicks, h.expedites, h.loads
	h.rewind = &rewindLoop{
		ix: h.ix, root: h.root, cache: h.cache, log: log,
		endLive: func(ctx context.Context, streamID string) (string, error) {
			return index.EndLive(ctx, h.pool, streamID)
		},
		pub: pub, lanes: h.lanes,
		work: laneWork{
			tick: func(ctx context.Context, st publish.State, in publish.TickInput) publish.Outcome {
				start := time.Now()
				out := pub.Tick(ctx, st, in)
				ticks.add(start, -1, out.Published)
				if k := in.GapCandidate; k != nil {
					gapTicks.add(start, *k, out.GapSeq != nil || out.UploadedSeq != nil)
				}
				return out
			},
			renew: pub.RenewFence,
			load: func(ctx context.Context, in publish.LoadInput) publish.LoadOutcome {
				start := time.Now()
				out := pub.LoadLedger(ctx, in)
				loads.add(start, -1, out.Err == nil)
				return out
			},
			watch: pub.Watch,
		},
		peekDirty: h.dirty.peek, ackDirty: h.dirty.ack,
		expedite:        func(_ string, seq int64) { expedites.add(time.Now(), seq, true) },
		reconnectWindow: 300 * time.Second, watchEvery: watchEvery, holdEvery: time.Second,
		lazyRenewAfter: popt.LazyRenewAfter,
	}

	laneCtx, cancelLanes := context.WithCancel(context.Background())
	h.cancelLanes = cancelLanes
	holds := time.NewTicker(time.Second)
	deps := loopDeps{
		ix: h.ix, root: h.root, rescanEvery: time.Hour, log: log,
		completed: h.completed, holdTicks: holds.C,
		armSweeper: func() {}, stallFactor: collectStallFactor, rewind: h.rewind,
	}
	loopErr := h.loopErr
	go func() {
		defer holds.Stop()
		loopErr <- loop(laneCtx, deps)
	}()
	h.t.Cleanup(h.stop)
}

// stop 은 프로세스를 끝낸다 — 차선 ctx(= 루프 ctx)를 끊어 루프가 돌아오면 종료 순서(stopPublishing — 대기 뒤 fence 반납)를 돈다.
// 두 번 불러도 된다.
func (h *itHarness) stop() {
	if h.stopped {
		return
	}
	h.stopped = true
	h.cancelLanes()
	select {
	case err := <-h.loopErr:
		if err != nil {
			h.t.Errorf("loop() = %v, want nil", err)
		}
	case <-time.After(10 * time.Second):
		h.t.Errorf("루프가 10초 안에 돌아오지 않았다")
	}
	runCtx, cancel := context.WithCancel(context.Background())
	cancel() // 종료 신호로 끝난 run ctx
	if err := stopPublishing(runCtx, h.cancelLanes, h.lanes, h.pub.Release); err != nil {
		h.t.Errorf("stopPublishing = %v, want nil", err)
	}
}

// exec 는 픽스처 SQL 한 문장이다(인덱서 풀 — 추적하지 않는다).
func (h *itHarness) exec(sql string, args ...any) {
	h.t.Helper()
	if _, err := h.pool.Exec(context.Background(), sql, args...); err != nil {
		h.t.Fatalf("픽스처 실패: %v\n%s", err, sql)
	}
}

// wall 은 seq 조각의 계획 시작 벽시계다 — t0 + 4s × seq + shift.
func (h *itHarness) wall(seq int64) time.Time {
	return h.t0.Add(time.Duration(seq)*4*time.Second + h.shift)
}

// segment 는 seq 조각을 루프의 완성 채널로 흘린다 — 시작 벽시계는 wall(seq)이고 길이는 프로브의 4초다. 루프가 장부에 넣을
// 때까지 기다리고 그 회차 ID 를 돌려준다.
func (h *itHarness) segment(seq int64) string {
	h.t.Helper()
	wall := h.wall(seq)
	dir := filepath.Join(h.root, h.stream)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		h.t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	p := filepath.Join(dir, fmt.Sprintf("%s-%06d.mp4", wall.Format("2006-01-02_15-04-05"), wall.Nanosecond()/1000))
	if err := os.WriteFile(p, make([]byte, 1000), 0o600); err != nil {
		h.t.Fatalf("파일 생성 실패: %v", err)
	}
	seg, err := recording.ParseSegmentPath(h.root, p)
	if err != nil {
		h.t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = recording.ReasonNextFile
	select {
	case h.completed <- seg:
	case <-time.After(10 * time.Second):
		h.t.Fatalf("루프가 조각 %d 를 받지 않는다", seq)
	}
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		var sid *string
		err := h.pool.QueryRow(context.Background(),
			`SELECT session_id FROM stream_segments WHERE stream_id = $1 AND seq = $2`, h.stream, seq).Scan(&sid)
		if err == nil && sid != nil {
			return *sid
		}
		time.Sleep(20 * time.Millisecond)
	}
	h.t.Fatalf("조각 %d 가 10초 안에 장부에 들지 않았다", seq)
	return ""
}

// uploadPlayback 은 seq 의 ③ 확정을 흉내 낸다 — 장부를 uploaded 로 바꾼 뒤 Dirty 에 표시한다(워커의 CAS 뒤 표시와 같은 차례).
func (h *itHarness) uploadPlayback(seq int64, sessionID string) {
	h.t.Helper()
	if err := h.markPlayback(seq, sessionID); err != nil {
		h.t.Fatal(err)
	}
}

// markPlayback 은 uploadPlayback 의 몸체다 — 시험 고루틴 밖(추적기 훅)에서도 부를 수 있게 실패를 돌려준다.
func (h *itHarness) markPlayback(seq int64, sessionID string) error {
	if _, err := h.pool.Exec(context.Background(),
		`UPDATE stream_segments SET playback_upload_state = 'uploaded', playback_uploaded_at = now(), playback_bytes = 1000
		  WHERE stream_id = $1 AND seq = $2`, h.stream, seq); err != nil {
		return fmt.Errorf("③ 확정 흉내 실패 seq=%d: %w", seq, err)
	}
	h.dirty.mark(upload.DirtyEvent{Kind: upload.DirtyUploaded, StreamID: h.stream, Axis: index.AxisPlayback, Seq: seq, SessionID: sessionID})
	return nil
}

// uploadInit 은 회차의 init 확정을 흉내 낸다(장부 init 열 + Dirty init 원소 · 해제 거짓).
func (h *itHarness) uploadInit(sessionID string) {
	h.t.Helper()
	key, err := playback.InitKey(h.stream, sessionID)
	if err != nil {
		h.t.Fatalf("InitKey 실패: %v", err)
	}
	h.exec(`UPDATE stream_sessions SET init_uploaded_at = now(), init_s3_key = $2, init_sha256 = '\x00'::bytea, init_bytes = 1
	         WHERE session_id = $1`, sessionID, key)
	h.dirty.mark(upload.DirtyEvent{Kind: upload.DirtyUploaded, StreamID: h.stream, Axis: index.AxisInit, SessionID: sessionID})
}

// waitUntil 은 seq 조각이 끝나는 계획 시각(wall(seq) + 4s)에서 off 만큼 뒤까지 잔다.
func (h *itHarness) waitUntil(seq int64, off time.Duration) {
	time.Sleep(time.Until(h.wall(seq).Add(4*time.Second + off)))
}

// itSeed 는 장부에 바로 넣는 회차 한 행이다 — 기동 전 상태(재기동 · 계승 · 빈 접두)를 인덱서를 거치지 않고 만든다.
type itSeed struct {
	id, state, inherits string
	initUp              bool
	base                int64
	firstPDT            time.Time
	// targetDuration 은 target_duration(초)이다 — 0 이면 표 기본값(6)이다.
	targetDuration int32
}

// seedSession 은 회차 s 를 장부에 넣는다 — ending 이면 ending_at 을 지금으로, init 확정이면 init 네 열을 채운다.
func (h *itHarness) seedSession(s itSeed) {
	h.t.Helper()
	var inherits, initKey *string
	var initAt *time.Time
	var initSHA []byte
	var initBytes *int64
	if s.inherits != "" {
		inherits = &s.inherits
	}
	if s.initUp {
		key, err := playback.InitKey(h.stream, s.id)
		if err != nil {
			h.t.Fatalf("InitKey 실패: %v", err)
		}
		now, one := time.Now(), int64(1)
		initKey, initAt, initSHA, initBytes = &key, &now, []byte{0}, &one
	}
	h.exec(`INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ending_at, first_pdt, inherits_session,
	           discontinuity_base, init_uploaded_at, init_s3_key, init_sha256, init_bytes, target_duration)
	         VALUES ($1, $2, $3, $4, CASE WHEN $4 = 'ending' THEN now() END, $3, $5, $6, $7, $8, $9, $10, COALESCE(NULLIF($11, 0), 6))`,
		s.id, h.stream, s.firstPDT, s.state, inherits, s.base, initAt, initKey, initSHA, initBytes, s.targetDuration)
}

// seedRows 는 회차 sessionID 의 조각 from..to 를 장부에 넣는다 — 길이 durMS · 벽시계 = PDT = pdt0 + durMS × (seq − from) · ③
// 상태(playback_upload_state)는 state(seq) 가 정한다.
func (h *itHarness) seedRows(sessionID string, from, to int64, pdt0 time.Time, durMS int32, state func(seq int64) string) {
	h.t.Helper()
	for seq := from; seq <= to; seq++ {
		key, err := playback.SegKey(h.stream, seq)
		if err != nil {
			h.t.Fatalf("SegKey 실패: %v", err)
		}
		pdt := pdt0.Add(time.Duration(seq-from) * time.Duration(durMS) * time.Millisecond)
		h.exec(`INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, session_id,
		           playback_pdt, playback_s3_key, playback_upload_state)
		         VALUES ($1, $2, 0, $3, $4, $5, $6, $3, $5, $7)`, h.stream, seq, pdt, durMS, key, sessionID, state(seq))
	}
}

// allUploaded 는 seedRows 의 state — 모든 행이 ③ 확정이다.
func allUploaded(int64) string { return "uploaded" }

// remark 는 이미 ③ 확정인 seq 의 원소를 Dirty 에 다시 표시한다 — 틱 하나를 부른다(같은 본문이면 PUT 0).
func (h *itHarness) remark(seq int64, sessionID string) {
	h.dirty.mark(upload.DirtyEvent{Kind: upload.DirtyUploaded, StreamID: h.stream, Axis: index.AxisPlayback, Seq: seq, SessionID: sessionID})
}

// waitLog 는 메시지 msg 의 기록이 n 줄이 될 때까지 기다린다(상한 limit).
func (h *itHarness) waitLog(msg string, n int, limit time.Duration) {
	h.t.Helper()
	deadline := time.Now().Add(limit)
	for len(h.logRecords(msg)) < n {
		if time.Now().After(deadline) {
			h.t.Fatalf("%s 가 %v 안에 %d줄이 되지 않았다(지금 %d줄)", msg, limit, n, len(h.logRecords(msg)))
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// waitLoads 는 적재 작업이 n 번 끝날 때까지 기다리고(10초 상한), 루프가 그 결과를 반영하도록 100ms 를 더 쉰다.
func (h *itHarness) waitLoads(n int) {
	h.t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for len(h.loads.list()) < n {
		if time.Now().After(deadline) {
			h.t.Fatalf("적재 %d번이 10초 안에 끝나지 않았다(지금 %d번)", n, len(h.loads.list()))
		}
		time.Sleep(20 * time.Millisecond)
	}
	time.Sleep(100 * time.Millisecond)
}

// manifestKey 는 회차의 목록 객체 키다.
func (h *itHarness) manifestKey(sessionID string) string {
	return "dvr/" + h.stream + "/" + sessionID + "/index.m3u8"
}

// bodySegments 는 목록 본문의 조각 seq(차례)와 GAP 줄인 seq 다 — URI 줄의 키를 playback.ParseSegKey 로 읽는다.
func bodySegments(body []byte) (seqs []int64, gaps map[int64]bool, err error) {
	gaps = map[int64]bool{}
	gap := false
	for _, line := range strings.Split(string(body), "\n") {
		switch {
		case line == "#EXT-X-GAP":
			gap = true
		case strings.HasPrefix(line, "https://"):
			key := line[strings.Index(line[len("https://"):], "/")+len("https://")+1:]
			_, seq, perr := playback.ParseSegKey(key)
			if perr != nil {
				return nil, nil, fmt.Errorf("URI 줄 %q: %w", line, perr)
			}
			seqs = append(seqs, seq)
			if gap {
				gaps[seq] = true
			}
			gap = false
		}
	}
	return seqs, gaps, nil
}

// itSession 은 회차의 세대 규약 열 몇 개다.
type itSession struct {
	gen      int64
	etag     *string
	fence    *string
	inherits *string
	base     int64
}

func (h *itHarness) session(id string) itSession {
	h.t.Helper()
	var s itSession
	if err := h.pool.QueryRow(context.Background(),
		`SELECT manifest_gen, manifest_etag, writer_fence, inherits_session, discontinuity_base FROM stream_sessions WHERE session_id = $1`,
		id).Scan(&s.gen, &s.etag, &s.fence, &s.inherits, &s.base); err != nil {
		h.t.Fatalf("회차 %s 조회 실패: %v", id, err)
	}
	return s
}

// itLog 는 잡은 로그 한 줄이다 — 등급 · 시각 · 속성.
type itLog struct {
	level slog.Level
	at    time.Time
	attrs map[string]string
}

// String 은 등급과 속성이다(계측 기록용).
func (l itLog) String() string { return fmt.Sprintf("%s%v", l.level, l.attrs) }

// logRecords 는 메시지 msg 의 기록이다(남긴 차례).
func (h *itHarness) logRecords(msg string) []itLog {
	h.logs.mu.Lock()
	defer h.logs.mu.Unlock()
	var out []itLog
	for _, r := range h.logs.records {
		if r.Message != msg {
			continue
		}
		l := itLog{level: r.Level, at: r.Time, attrs: map[string]string{}}
		r.Attrs(func(a slog.Attr) bool {
			l.attrs[a.Key] = a.Value.String()
			return true
		})
		out = append(out, l)
	}
	return out
}

// logDump 는 잡은 로그 전부다(시험이 실패했을 때 남길 진단).
func (h *itHarness) logDump() string {
	h.logs.mu.Lock()
	defer h.logs.mu.Unlock()
	var b strings.Builder
	for _, r := range h.logs.records {
		fmt.Fprintf(&b, "%s %s %s", r.Time.Format("15:04:05.000"), r.Level, r.Message)
		r.Attrs(func(a slog.Attr) bool {
			fmt.Fprintf(&b, " %s=%v", a.Key, a.Value)
			return true
		})
		b.WriteString("\n")
	}
	return b.String()
}

// abortReasons 는 rewind_publish_aborted 의 reason 들이다(남긴 차례).
func (h *itHarness) abortReasons() []string {
	var out []string
	for _, l := range h.logRecords("rewind_publish_aborted") {
		out = append(out, l.attrs["reason"])
	}
	return out
}

// ---------------------------------------------------------------------------
// 계측
// ---------------------------------------------------------------------------

// tripWindows 는 틱마다의 DB 왕복이다 — P0 하나에서 다음 P0 직전까지의 창에 든 P0 · P4 · lazy 갱신 문장 수와 그 창의 길이다
// (계획 커밋 순서 7 〔r51〕 ③ · 4.5 D — 틱당 상한 3 = P0 1 · P4 1 · lazy ≤ 1). 마지막 P0 의 창은 끝을 몰라 세지 않는다.
func tripWindows(stmts []itStmt) (counts []int, spans []time.Duration) {
	var p0 []time.Time
	for _, s := range stmts {
		if s.class == "p0" {
			p0 = append(p0, s.start)
		}
	}
	for i := 0; i+1 < len(p0); i++ {
		n := 0
		for _, s := range stmts {
			if (s.class == "p0" || s.class == "p4" || s.class == "renew") && !s.start.Before(p0[i]) && s.start.Before(p0[i+1]) {
				n++
			}
		}
		counts, spans = append(counts, n), append(spans, p0[i+1].Sub(p0[i]))
	}
	return counts, spans
}

// starts 는 종류 class 문장의 시작 시각이다(오류 포함 · 시작 순).
func starts(stmts []itStmt, class string) []time.Time {
	var out []time.Time
	for _, s := range stmts {
		if s.class == class {
			out = append(out, s.start)
		}
	}
	return out
}

// latencies 는 종류 class 문장의 걸린 시간이다(오류 없는 문장만).
func latencies(stmts []itStmt, class string) []time.Duration {
	var out []time.Duration
	for _, s := range stmts {
		if s.class == class && s.err == nil {
			out = append(out, s.end.Sub(s.start))
		}
	}
	return out
}

// dist 는 길이 목록의 요약이다 — 개수 · 최소 · 중앙 · 95백분위 · 최대.
func dist(xs []time.Duration) string {
	if len(xs) == 0 {
		return "n=0"
	}
	s := slices.Clone(xs)
	slices.Sort(s)
	q := func(p float64) time.Duration { return s[int(p*float64(len(s)-1))] }
	return fmt.Sprintf("n=%d min=%v p50=%v p95=%v max=%v", len(s), s[0], q(0.5), q(0.95), s[len(s)-1])
}

// histogram 은 정수 목록의 도수다(값:개수, 값 순).
func histogram(xs []int) string {
	m := map[int]int{}
	for _, x := range xs {
		m[x]++
	}
	var parts []string
	for _, k := range slices.Sorted(maps.Keys(m)) {
		parts = append(parts, fmt.Sprintf("%d:%d", k, m[k]))
	}
	return strings.Join(parts, " ")
}

// fmtErr 는 오류의 글이다(nil 이면 "").
func fmtErr(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

// slicesMax 는 길이 목록의 최댓값이다(비면 0).
func slicesMax(xs []time.Duration) time.Duration {
	var m time.Duration
	for _, x := range xs {
		m = max(m, x)
	}
	return m
}

// countOK 는 ok 인 기록 수다.
func countOK(ev []itEvent) int {
	n := 0
	for _, e := range ev {
		if e.ok {
			n++
		}
	}
	return n
}

// skipWithoutPG 는 PG_DSN 이 없으면 시험을 건너뛴다(REQUIRE_PG=1 이면 TestMain 이 이미 실패로 바꿨다).
func skipWithoutPG(t *testing.T) {
	t.Helper()
	if os.Getenv("PG_DSN") == "" {
		t.Skip("PG_DSN 이 없다 — 통합 시험 건너뜀")
	}
}

// atomicGate 는 한 번만 참을 돌려주는 문이다(훅이 한 번만 주입하게).
type atomicGate struct{ done atomic.Bool }

func (g *atomicGate) once() bool { return g.done.CompareAndSwap(false, true) }
