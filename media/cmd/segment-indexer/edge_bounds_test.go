package main

// /dvr 하한 Writer 배선(POK-525 계획 Ⓒ-6 · 설계 3-3 · 8-1 · E10 · E11)의 시험. 실 AWS · DB 를 부르지 않는다 — 원장 ·
// KVS 는 가짜이고, 운영 KVS 클라이언트는 조립한 값(재시도 · 끝점)만 본다.

import (
	"context"
	"errors"
	"log/slog"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

const testKVSARN = "arn:aws:cloudfront::123456789012:key-value-store/a1b2c3d4-5678-90ab-cdef-EXAMPLE11111"

// observed 는 하한 관측 한 번이다.
type observed struct {
	stream, round string
	msn           int64
}

// observeRecorder 는 observeFloor 가짜다 — 부른 값을 적는다.
type observeRecorder struct {
	mu   sync.Mutex
	seen []observed
}

func (o *observeRecorder) observe(streamID, roundID string, msn int64) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.seen = append(o.seen, observed{streamID, roundID, msn})
}

func (o *observeRecorder) calls() []observed {
	o.mu.Lock()
	defer o.mu.Unlock()
	return slices.Clone(o.seen)
}

// rowsOf 는 seq 가 from 부터 1씩 이어지는 목록 행이다 — keys 가 행마다의 ③ 키다.
func rowsOf(from int64, keys ...string) []boundary.Row {
	rows := make([]boundary.Row, len(keys))
	for i, k := range keys {
		rows[i] = boundary.Row{Seq: from + int64(i), SessionID: "sess", PlaybackS3Key: k, PlaybackUploaded: true}
	}
	return rows
}

// publishedTick 은 in 의 목록을 그대로 발행한 틱 가짜다(MSN = 첫 행 · published_seq = 끝 행).
func publishedTick(_ context.Context, st publish.State, in publish.TickInput) publish.Outcome {
	rows := in.Playlist.Rows
	st.Prev = &publish.Manifest{Published: rewind.Published{
		MediaSequence: rows[0].Seq, PublishedSeq: rows[len(rows)-1].Seq, SegmentCount: len(rows),
	}}
	return publish.Outcome{State: st, Published: true}
}

func tickWith(rows []boundary.Row) publish.TickInput {
	return publish.TickInput{Playlist: rewind.Playlist{StreamID: "s1", Owner: "sess", Rows: rows}}
}

// E11 — 한 회차 목록에 켬 전(옛 꼴) · 켬 뒤(새 꼴) 행이 섞여도 회차는 마지막 행 키에서 뽑는다. msn 은 목록의
// EXT-X-MEDIA-SEQUENCE(첫 행)다 — 끝 행을 넘기면 최신 목록의 첫 조각을 엣지가 막는다(웹 계약 불변식).
func TestObservePublishedUsesLastRowRound(t *testing.T) {
	rec := &observeRecorder{}
	tick := observePublished(publishedTick, rec.observe)
	in := tickWith(rowsOf(7,
		"dvr/s1/seg/000007.m4s",
		"dvr/s1/seg/000008.m4s",
		"dvr/s1/R1/seg/000009.m4s",
		"dvr/s1/R1/seg/000010.m4s",
	))

	out := tick(t.Context(), publish.State{}, in)

	if !out.Published {
		t.Fatal("감싼 틱이 결과를 바꿨다 — Published 거짓")
	}
	want := []observed{{"s1", "R1", 7}}
	if got := rec.calls(); !slices.Equal(got, want) {
		t.Errorf("Observe = %v, want %v", got, want)
	}
}

// 마지막 행이 옛 꼴이면(앞 행이 새 꼴이어도 — 끈 상자로 넘어간 목록) Observe 하지 않는다. 앞 행의 회차를 고르면 그
// 회차 하한이 옛 꼴 조각에는 닿지 않는데도 원장 행이 생겨 VOD 를 묶는다.
func TestObservePublishedSkipsLegacyLastRow(t *testing.T) {
	cases := map[string][]boundary.Row{
		"전부 옛 꼴":      rowsOf(1, "dvr/s1/seg/000001.m4s", "dvr/s1/seg/000002.m4s"),
		"새 꼴 뒤 옛 꼴":   rowsOf(1, "dvr/s1/R1/seg/000001.m4s", "dvr/s1/seg/000002.m4s"),
		"끝 행 키 없음":    rowsOf(1, "dvr/s1/R1/seg/000001.m4s", ""),
		"끝 행 문법 밖":    rowsOf(1, "dvr/s1/R1/seg/000001.m4s", "dvr/s1/R1/../seg/000002.m4s"),
		"끝 행 다른 스트림":  rowsOf(1, "dvr/s1/R1/seg/000001.m4s", "dvr/s2/R1/seg/000002.m4s"),
		"끝 행 seq 어긋남": rowsOf(1, "dvr/s1/R1/seg/000001.m4s", "dvr/s1/R1/seg/000009.m4s"),
	}
	for name, rows := range cases {
		t.Run(name, func(t *testing.T) {
			rec := &observeRecorder{}

			observePublished(publishedTick, rec.observe)(t.Context(), publish.State{}, tickWith(rows))

			if got := rec.calls(); len(got) != 0 {
				t.Errorf("Observe = %v, want 0번", got)
			}
		})
	}
}

// 발행하지 않은 틱(같은 본문 생략 · 중단) · 발행본이 끝 행과 다른 틱은 Observe 하지 않는다.
func TestObservePublishedSkipsUnpublished(t *testing.T) {
	in := tickWith(rowsOf(1, "dvr/s1/R1/seg/000001.m4s", "dvr/s1/R1/seg/000002.m4s"))
	cases := map[string]func(context.Context, publish.State, publish.TickInput) publish.Outcome{
		"발행 안 함": func(_ context.Context, st publish.State, _ publish.TickInput) publish.Outcome {
			return publish.Outcome{State: st}
		},
		"발행본 없음": func(_ context.Context, st publish.State, _ publish.TickInput) publish.Outcome {
			return publish.Outcome{State: st, Published: true}
		},
		"발행본 끝 행이 다름": func(_ context.Context, st publish.State, _ publish.TickInput) publish.Outcome {
			st.Prev = &publish.Manifest{Published: rewind.Published{MediaSequence: 1, PublishedSeq: 1}}
			return publish.Outcome{State: st, Published: true}
		},
	}
	for name, tick := range cases {
		t.Run(name, func(t *testing.T) {
			rec := &observeRecorder{}

			observePublished(tick, rec.observe)(t.Context(), publish.State{}, in)

			if got := rec.calls(); len(got) != 0 {
				t.Errorf("Observe = %v, want 0번", got)
			}
		})
	}
}

// Writer 가 없으면(observe nil) 틱을 감싸지 않는다 — 발행 몫이 없으면(tick nil) 그대로 nil 이다.
func TestObservePublishedWithoutWriterKeepsTick(t *testing.T) {
	if observePublished(nil, (&observeRecorder{}).observe) != nil {
		t.Error("tick nil 을 감쌌다")
	}
	in := tickWith(rowsOf(1, "dvr/s1/R1/seg/000001.m4s"))
	if out := observePublished(publishedTick, nil)(t.Context(), publish.State{}, in); !out.Published {
		t.Error("observe nil 에서 틱 결과가 바뀌었다")
	}
}

// fakeLedger 는 원장 가짜다 — ClosedUnreleased 만 값을 지어내고 나머지는 빈 결과다.
type fakeLedger struct {
	mu     sync.Mutex
	closed []edgebounds.Floor
	err    error
	calls  int
}

func (l *fakeLedger) setClosed(rows []edgebounds.Floor, err error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.closed, l.err = rows, err
}

func (l *fakeLedger) probes() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.calls
}

func (l *fakeLedger) Raise(context.Context, []edgebounds.Floor) ([]edgebounds.Floor, error) {
	return nil, nil
}

func (l *fakeLedger) ClosedUnreleased(_ context.Context, limit int) ([]edgebounds.Floor, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.calls++
	return slices.Clone(l.closed[:min(len(l.closed), limit)]), l.err
}

func (l *fakeLedger) MarkReleased(context.Context, []edgebounds.Floor) error { return nil }

func (l *fakeLedger) DeleteReleased(context.Context, time.Duration) (int64, error) { return 0, nil }

// fakeStore 는 KVS 가짜다 — 판 하나로 Describe 만 답한다.
type fakeKVS struct{}

func (fakeKVS) Describe(context.Context) (edgebounds.Stat, error) {
	return edgebounds.Stat{ETag: "E1"}, nil
}

func (fakeKVS) Update(context.Context, string, []edgebounds.Entry, []string) (string, error) {
	return "E2", nil
}

func (fakeKVS) Fetch(context.Context, string) (string, bool, error) { return "", false, nil }

// storeOpener 는 newStore 가짜다 — 받은 ARN 을 적고 err 가 있으면 실패한다.
type storeOpener struct {
	mu   sync.Mutex
	arns []string
	err  error
}

func (s *storeOpener) open(_ context.Context, kvsARN string) (edgebounds.Store, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.arns = append(s.arns, kvsARN)
	if s.err != nil {
		return nil, s.err
	}
	return fakeKVS{}, nil
}

func (s *storeOpener) opened() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.arns)
}

// testEdgeBoundsDeps 는 주기를 줄인 배선 의존이다.
func testEdgeBoundsDeps(l edgebounds.Ledger, s *storeOpener, logs *logCapture) edgeBoundsDeps {
	opt := edgeBoundsOptions("w-test", slog.New(logs), time.Now)
	opt.ReleaseEvery, opt.FlushEvery = 10*time.Millisecond, 10*time.Millisecond
	return edgeBoundsDeps{ledger: l, newStore: s.open, opt: opt}
}

func twoClosed() []edgebounds.Floor {
	return []edgebounds.Floor{{StreamID: "s1", RoundID: "R1", Value: 3}, {StreamID: "s2", RoundID: "R2", Value: 9}}
}

// E10 — ARN 을 비운 채 기동했는데 원장에 닫힌 미해제 행이 있으면 기동 때 ERROR set(rows) 한 줄이다. 행이 0 이 되면
// 같은 이름 · 같은 등급으로 clear 한 줄이다(LOG_LEVEL=error 수집에서도 회복이 보인다).
func TestEdgeBoundsWithoutARNReportsUnreleasedRows(t *testing.T) {
	logs := &logCapture{}
	logs.errorOnly.Store(true)
	l := &fakeLedger{closed: twoClosed()}
	s := &storeOpener{}

	observe, stop := launchEdgeBounds(t.Context(), config.Config{}, testEdgeBoundsDeps(l, s, logs))
	defer stop()

	if observe != nil {
		t.Error("ARN 빔인데 observe 가 있다")
	}
	if got := s.opened(); len(got) != 0 {
		t.Errorf("ARN 빔인데 KVS 를 열었다 %v", got)
	}
	if n := logs.countLevel(slog.LevelError, signalUnreleasedWithoutWriter); n != 1 {
		t.Fatalf("기동 직후 %s ERROR %d줄, want 1", signalUnreleasedWithoutWriter, n)
	}
	if a := logs.attrs(signalUnreleasedWithoutWriter); a["value"] != int64(1) || a["rows"] != int64(2) {
		t.Errorf("set 줄 속성 = %v, want value=1 rows=2", a)
	}

	l.setClosed(nil, nil)

	waitFor(t, "clear 한 줄", func() bool { return logs.countLevel(slog.LevelError, signalUnreleasedWithoutWriter) == 2 })
	if a := logs.attrs(signalUnreleasedWithoutWriter); a["value"] != int64(0) {
		t.Errorf("clear 줄 속성 = %v, want value=0", a)
	}
	before := l.probes()
	waitFor(t, "다음 감시", func() bool { return l.probes() > before+2 })
	if n := logs.count(signalUnreleasedWithoutWriter); n != 2 {
		t.Errorf("%s %d줄, want 2 — 같은 상태가 이어지면 줄을 다시 내지 않는다", signalUnreleasedWithoutWriter, n)
	}
}

// 행이 0 인 채 기동하면 신호 줄이 없다(clear 는 set 뒤에만).
func TestEdgeBoundsWithoutARNNoRowsNoSignal(t *testing.T) {
	logs := &logCapture{}
	l := &fakeLedger{}

	_, stop := launchEdgeBounds(t.Context(), config.Config{}, testEdgeBoundsDeps(l, &storeOpener{}, logs))
	stop()

	if n := logs.count(signalUnreleasedWithoutWriter); n != 0 {
		t.Errorf("%s %d줄, want 0", signalUnreleasedWithoutWriter, n)
	}
	if n := logs.countLevel(slog.LevelInfo, "edge_bounds_disabled"); n != 1 {
		t.Errorf("edge_bounds_disabled INFO %d줄, want 1", n)
	}
}

// 감시 조회가 실패하면 실패가 이어지는 동안 WARN 한 줄이고, set · clear 상태는 바꾸지 않는다.
func TestEdgeBoundsUnreleasedProbeFailureWarnsOnce(t *testing.T) {
	logs := &logCapture{}
	l := &fakeLedger{err: errors.New("db down")}

	_, stop := launchEdgeBounds(t.Context(), config.Config{}, testEdgeBoundsDeps(l, &storeOpener{}, logs))
	defer stop()
	waitFor(t, "감시 셋", func() bool { return l.probes() >= 3 })

	if n := logs.countLevel(slog.LevelWarn, signalUnreleasedCheckFailed); n != 1 {
		t.Errorf("%s WARN %d줄, want 1", signalUnreleasedCheckFailed, n)
	}
	if n := logs.count(signalUnreleasedWithoutWriter); n != 0 {
		t.Errorf("조회 실패에 %s %d줄, want 0", signalUnreleasedWithoutWriter, n)
	}
}

// ARN 이 꼴 밖이면(설정이 비우고 이유를 남김) ERROR edge_bounds_config_invalid 한 줄(원문 없음)이고 Writer 없이 미해제
// 감시만 돈다.
func TestEdgeBoundsConfigInvalidLogsReasonOnly(t *testing.T) {
	const raw = "arn:aws:cloudfront::evil.example.com:key-value-store/x"
	logs := &logCapture{}
	l := &fakeLedger{closed: twoClosed()}
	s := &storeOpener{}
	cfg := config.Config{EdgeBoundsConfigInvalid: "REWIND_EDGE_BOUNDS_KVS_ARN 은 꼴 밖이다 — 하한 Writer 를 끈다"}

	observe, stop := launchEdgeBounds(t.Context(), cfg, testEdgeBoundsDeps(l, s, logs))
	defer stop()

	if observe != nil || len(s.opened()) != 0 {
		t.Errorf("설정 거부인데 Writer 를 띄웠다(observe %v · 연 ARN %v)", observe != nil, s.opened())
	}
	if n := logs.countLevel(slog.LevelError, "edge_bounds_config_invalid"); n != 1 {
		t.Fatalf("edge_bounds_config_invalid ERROR %d줄, want 1", n)
	}
	if a := logs.attrs("edge_bounds_config_invalid"); a["reason"] != cfg.EdgeBoundsConfigInvalid {
		t.Errorf("reason = %v, want 설정의 이유 그대로", a["reason"])
	}
	logs.mu.Lock()
	for _, r := range logs.records {
		r.Attrs(func(a slog.Attr) bool {
			if strings.Contains(a.Value.String(), raw) {
				t.Errorf("%s 줄에 ARN 원문이 실렸다", r.Message)
			}
			return true
		})
	}
	logs.mu.Unlock()
	if n := logs.countLevel(slog.LevelError, signalUnreleasedWithoutWriter); n != 1 {
		t.Errorf("설정 거부 + 미해제 행에 %s %d줄, want 1", signalUnreleasedWithoutWriter, n)
	}
}

// ARN 이 있으면 검증된 cfg.EdgeBoundsKVSARN 그대로 KVS 를 열고 Writer 를 띄운다 — INFO edge_bounds_started 한 줄 ·
// observe 있음 · 미해제 감시 없음. stop 은 Run 이 돌아오기를 기다린다.
func TestEdgeBoundsWithARNStartsWriter(t *testing.T) {
	logs := &logCapture{}
	l := &fakeLedger{closed: twoClosed()}
	s := &storeOpener{}

	observe, stop := launchEdgeBounds(t.Context(), config.Config{EdgeBoundsKVSARN: testKVSARN}, testEdgeBoundsDeps(l, s, logs))

	if observe == nil {
		t.Fatal("Writer 를 띄웠는데 observe 가 없다")
	}
	observe("s1", "R1", 5) // 경합 시험(-race) — Run 고루틴과 함께 돈다
	if got := s.opened(); !slices.Equal(got, []string{testKVSARN}) {
		t.Errorf("연 ARN = %v, want [설정 값]", got)
	}
	if n := logs.countLevel(slog.LevelInfo, "edge_bounds_started"); n != 1 {
		t.Errorf("edge_bounds_started INFO %d줄, want 1", n)
	}
	waitFor(t, "Writer 해제 틱", func() bool { return l.probes() >= 2 })
	stop()
	if n := logs.count(signalUnreleasedWithoutWriter); n != 0 {
		t.Errorf("Writer 가 있는데 %s %d줄, want 0", signalUnreleasedWithoutWriter, n)
	}
	after := l.probes()
	time.Sleep(30 * time.Millisecond)
	if l.probes() != after {
		t.Error("stop 뒤에도 Writer 가 원장을 읽는다")
	}
}

// KVS 를 열지 못하면 ERROR edge_bounds_degraded 한 줄이고 Writer 없이 미해제 감시가 돈다(기동은 계속).
func TestEdgeBoundsStoreFailureDegrades(t *testing.T) {
	logs := &logCapture{}
	l := &fakeLedger{closed: twoClosed()}
	s := &storeOpener{err: errors.New("aws config")}

	observe, stop := launchEdgeBounds(t.Context(), config.Config{EdgeBoundsKVSARN: testKVSARN}, testEdgeBoundsDeps(l, s, logs))
	defer stop()

	if observe != nil {
		t.Error("KVS 를 못 열었는데 observe 가 있다")
	}
	if n := logs.countLevel(slog.LevelError, "edge_bounds_degraded"); n != 1 {
		t.Errorf("edge_bounds_degraded ERROR %d줄, want 1", n)
	}
	if n := logs.countLevel(slog.LevelError, signalUnreleasedWithoutWriter); n != 1 {
		t.Errorf("%s %d줄, want 1", signalUnreleasedWithoutWriter, n)
	}
}

// 운영 KVS 클라이언트는 SDK 재시도를 끄고(시도 1 — 조건부 쓰기의 재시도는 Writer 의 판 대조 하나뿐) SDK 표준 끝점
// 덮어쓰기(AWS_ENDPOINT_URL 등)를 받지 않는다 — 끝점은 검증된 ARN 의 계정 번호로만 정해진다.
func TestNewKVSClientPinsRetryAndEndpoint(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("AWS_CONFIG_FILE", filepath.Join(dir, "config"))
	t.Setenv("AWS_SHARED_CREDENTIALS_FILE", filepath.Join(dir, "credentials"))
	t.Setenv("AWS_REGION", "ap-northeast-2")
	t.Setenv("AWS_ENDPOINT_URL", "https://elsewhere.example.com")
	t.Setenv("AWS_ENDPOINT_URL_CLOUDFRONT_KEYVALUESTORE", "https://elsewhere.example.com")

	client, err := newKVSClient(t.Context())
	if err != nil {
		t.Fatalf("newKVSClient: %v", err)
	}

	o := client.Options()
	if o.RetryMaxAttempts != 1 {
		t.Errorf("RetryMaxAttempts = %d, want 1", o.RetryMaxAttempts)
	}
	if o.BaseEndpoint != nil {
		t.Errorf("BaseEndpoint = %q, want nil(ARN 끝점)", *o.BaseEndpoint)
	}
}
