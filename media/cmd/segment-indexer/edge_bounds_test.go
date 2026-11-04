package main

// /dvr 하한 Writer 배선(POK-525 계획 Ⓒ-6 · 설계 3-3 · 8-1 · E10 · E11)의 시험. 실 AWS · DB 를 부르지 않는다 — 원장 ·
// KVS 는 가짜이고, 운영 KVS 클라이언트는 조립한 값(재시도 · 끝점)과 전송 가짜에 닿은 요청의 호스트만 본다(망에 나가지 않는다).

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cloudfrontkeyvaluestore"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// testKVSARN 은 설정이 받는 꼴(소문자 UUID 자원 ID — config.kvsResourcePattern)의 시험 ARN 이다.
const testKVSARN = "arn:aws:cloudfront::123456789012:key-value-store/a1b2c3d4-5678-90ab-cdef-0123456789ab"

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

// run 의 발행 조립 자리(assembleObservedPublishing)는 발행 몫을 조립한 뒤 그 틱을 하한 관측으로 감싼다 — 조립이 채운 틱이
// 새 판을 발행하면 observe 가 불린다. 조립보다 먼저 감싸면 조립이 작업 칸을 통째로 덮어 감싼 것이 사라진다. 변이 = 감싸는 줄
// 삭제 · 조립 앞으로 옮기기 → Observe 0번 FAIL(줄이 지워져도 엣지는 통과라 조용하다 — Ⓒ-6 cc 설계 M2).
func TestAssembleObservedPublishingWrapsAssembledTick(t *testing.T) {
	rec := &observeRecorder{}
	r := &rewindLoop{}
	pool := assembleObservedPublishing(r, rec.observe, func() *pgxpool.Pool {
		r.work = laneWork{tick: publishedTick}
		return nil
	})

	if pool != nil {
		t.Errorf("풀 = %v, want 조립이 돌려준 nil 그대로", pool)
	}
	if r.work.tick == nil {
		t.Fatal("틱이 nil 이다 — 조립이 채운 틱을 잃었다")
	}
	r.work.tick(t.Context(), publish.State{}, tickWith(rowsOf(4, "dvr/s1/R1/seg/000004.m4s")))
	if got, want := rec.calls(), []observed{{"s1", "R1", 4}}; !slices.Equal(got, want) {
		t.Errorf("Observe = %v, want %v — 발행 틱에 하한 관측이 끼지 않았다", got, want)
	}
}

// fakeLedger 는 원장 가짜다 — ClosedUnreleased · DeleteReleased 만 값을 지어내고 나머지는 빈 결과다.
type fakeLedger struct {
	mu     sync.Mutex
	closed []edgebounds.Floor
	err    error
	calls  int
	// deleted · delErr 는 DeleteReleased 의 답이고 deletes 는 받은 olderThan 이다(부른 순서).
	deleted int64
	delErr  error
	deletes []time.Duration
}

func (l *fakeLedger) setDelete(n int64, err error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.deleted, l.delErr = n, err
}

func (l *fakeLedger) deleteCalls() []time.Duration {
	l.mu.Lock()
	defer l.mu.Unlock()
	return slices.Clone(l.deletes)
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

func (l *fakeLedger) DeleteReleased(_ context.Context, olderThan time.Duration) (int64, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.deletes = append(l.deletes, olderThan)
	return l.deleted, l.delErr
}

// fakeKVS 는 KVS 가짜다 — 판 하나로 Describe 만 답한다.
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
	// 다음 감시 한 번이면 된다 — 몇 주기가 도는지에 기대지 않는다(느린 CI 에서도 한 주기만 기다린다).
	before := l.probes()
	waitFor(t, "다음 감시", func() bool { return l.probes() > before })
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

// 감시는 확인마다 유예(ReleaseSettle)가 지난 해제 행을 지운다 — 해제 뒤 유예 안에 Writer 가 멈추고 Writer 없는 상자로
// 넘어가도 해제된 행이 원장에 남아 그 회차를 끝남 대기에 묶지 않는다. 지운 행이 있을 때만 INFO 한 줄(rows)이다.
//
// 「정확히 한 번」 을 세는 시험이라 감시 타이머가 끼지 않게 한다 — 배선 몫은 주기를 시험보다 길게 두고 멈춘 뒤 세어 기동
// 동기 확인 하나만 보고, 주기 몫(지운 행 0 이면 줄 없음)은 감시 한 번(check)을 직접 부른다. 10ms 타이머를 둔 채 세면 단언 전에 틱이 한 번
// 더 돌아 정상 코드도 FAIL 한다(종결 r1 codex 설계 M1). 주기마다 확인이 도는 것은 probes 를 세는 이웃 시험이 본다.
// 변이 = 삭제 호출 제거 · 유예 0 → FAIL.
func TestEdgeBoundsWithoutWriterDeletesSettledReleasedRows(t *testing.T) {
	logs := &logCapture{}
	l := &fakeLedger{deleted: 2}
	deps := testEdgeBoundsDeps(l, &storeOpener{}, logs)
	deps.opt.ReleaseEvery = time.Hour // 시험 동안 감시 타이머가 돌지 않는다

	_, stop := launchEdgeBounds(t.Context(), config.Config{}, deps)
	stop()

	if got, want := l.deleteCalls(), []time.Duration{deps.opt.ReleaseSettle}; !slices.Equal(got, want) {
		t.Fatalf("기동 동기 확인의 DeleteReleased = %v, want %v", got, want)
	}
	if n := logs.countLevel(slog.LevelInfo, signalReleasedRowsDeleted); n != 1 {
		t.Fatalf("%s INFO %d줄, want 1", signalReleasedRowsDeleted, n)
	}
	if a := logs.attrs(signalReleasedRowsDeleted); a["rows"] != int64(2) {
		t.Errorf("rows = %v, want 2", a["rows"])
	}

	p := &unreleasedProbe{ledger: l, timeout: time.Second, settle: deps.opt.ReleaseSettle, log: slog.New(logs)}
	l.setDelete(0, nil)
	p.check(t.Context())

	if got, want := l.deleteCalls(), []time.Duration{deps.opt.ReleaseSettle, deps.opt.ReleaseSettle}; !slices.Equal(got, want) {
		t.Errorf("DeleteReleased = %v, want %v", got, want)
	}
	if n := logs.count(signalReleasedRowsDeleted); n != 1 {
		t.Errorf("지운 행 0 인 감시에도 %s 가 났다(%d줄, want 1)", signalReleasedRowsDeleted, n)
	}
}

// 삭제가 실패하면 실패가 이어지는 동안 check_failed WARN 한 줄이다. 미해제 조회는 그대로 돌아 set · clear 를 맞춘다 —
// 삭제 실패가 미해제 신호를 막거나 지우지 않는다.
func TestEdgeBoundsReleasedDeleteFailureKeepsUnreleasedSignal(t *testing.T) {
	logs := &logCapture{}
	l := &fakeLedger{closed: twoClosed(), delErr: errors.New("db down")}

	_, stop := launchEdgeBounds(t.Context(), config.Config{}, testEdgeBoundsDeps(l, &storeOpener{}, logs))
	defer stop()

	if n := logs.countLevel(slog.LevelError, signalUnreleasedWithoutWriter); n != 1 {
		t.Fatalf("삭제 실패 + 미해제 행에 %s %d줄, want 1(set)", signalUnreleasedWithoutWriter, n)
	}
	waitFor(t, "감시 셋", func() bool { return l.probes() >= 3 })
	if n := logs.countLevel(slog.LevelWarn, signalUnreleasedCheckFailed); n != 1 {
		t.Errorf("%s WARN %d줄, want 1 — 실패 구간당 한 줄", signalUnreleasedCheckFailed, n)
	}
	if msg, _ := logs.attrs(signalUnreleasedCheckFailed)["err"].(string); !strings.Contains(msg, "DeleteReleased") {
		t.Errorf("err = %q, want DeleteReleased 실패가 드러남", msg)
	}
	if n := logs.count(signalUnreleasedWithoutWriter); n != 1 {
		t.Errorf("삭제 실패가 이어지는데 %s %d줄, want 1(set 유지)", signalUnreleasedWithoutWriter, n)
	}

	l.setClosed(nil, nil)

	waitFor(t, "clear 한 줄", func() bool { return logs.countLevel(slog.LevelError, signalUnreleasedWithoutWriter) == 2 })
	if a := logs.attrs(signalUnreleasedWithoutWriter); a["value"] != int64(0) {
		t.Errorf("clear 줄 속성 = %v, want value=0", a)
	}
	if n := logs.countLevel(slog.LevelWarn, signalUnreleasedCheckFailed); n != 1 {
		t.Errorf("%s WARN %d줄, want 1", signalUnreleasedCheckFailed, n)
	}
	if n := logs.count(signalReleasedRowsDeleted); n != 0 {
		t.Errorf("삭제가 실패했는데 %s %d줄, want 0", signalReleasedRowsDeleted, n)
	}
}

// 삭제가 회복되면 실패 구간이 끝난다 — 다음 실패에 WARN 이 다시 한 줄 난다.
func TestEdgeBoundsReleasedDeleteRecoveryEndsFailureInterval(t *testing.T) {
	logs := &logCapture{}
	l := &fakeLedger{delErr: errors.New("db down")}
	p := &unreleasedProbe{ledger: l, timeout: time.Second, settle: edgeBoundsReleaseSettle, log: slog.New(logs)}

	p.check(t.Context())
	p.check(t.Context())
	l.setDelete(0, nil)
	p.check(t.Context())
	l.setDelete(0, errors.New("db down again"))
	p.check(t.Context())

	if n := logs.countLevel(slog.LevelWarn, signalUnreleasedCheckFailed); n != 2 {
		t.Errorf("%s WARN %d줄, want 2(실패 구간 둘)", signalUnreleasedCheckFailed, n)
	}
}

// ctx 가 끝나 실패한 삭제 · 조회는 알리지 않는다(종료는 장애가 아니다).
func TestEdgeBoundsProbeCanceledIsNotFailure(t *testing.T) {
	logs := &logCapture{}
	l := &fakeLedger{err: context.Canceled, delErr: context.Canceled}
	p := &unreleasedProbe{ledger: l, timeout: time.Second, settle: edgeBoundsReleaseSettle, log: slog.New(logs)}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()

	p.check(ctx)

	if n := logs.count(signalUnreleasedCheckFailed); n != 0 {
		t.Errorf("ctx 취소에 %s %d줄, want 0", signalUnreleasedCheckFailed, n)
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
	if n := len(l.deleteCalls()); n == 0 {
		t.Error("설정 거부 갈래의 감시가 해제 행을 지우지 않았다")
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

// 로거가 nil 이면 기본 로거를 쓴다(edgebounds.New 와 같다) — 배선의 첫 줄(edge_bounds_disabled 등)이 nil 로거에서 패닉하지
// 않는다. 변이 = 가드 제거 → FAIL.
func TestEdgeBoundsOptionsDefaultsNilLogger(t *testing.T) {
	opt := edgeBoundsOptions("w-test", nil, time.Now)
	if opt.Log == nil {
		t.Fatal("Log = nil, want 기본 로거")
	}
	opt.ReleaseEvery = 10 * time.Millisecond

	_, stop := launchEdgeBounds(t.Context(), config.Config{}, edgeBoundsDeps{ledger: &fakeLedger{}, newStore: (&storeOpener{}).open, opt: opt})
	stop()
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
	if n := len(l.deleteCalls()); n == 0 {
		t.Error("강등 갈래의 감시가 해제 행을 지우지 않았다")
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

// kvsRequestHost 는 newKVSClient 의 클라이언트로 DescribeKeyValueStore 한 번을 보내되 망에 나가지 않고, SDK 가 끝점 해결 ·
// 서명까지 마친 요청의 호스트를 돌려준다(전송 가짜가 요청을 받아 적고 실패로 답한다). 해결 · 서명이 실패하면 그 오류다.
// 클라이언트의 지역이 region 이 아니면 시험이 멈춘다(전제 — 바깥 셸 · 공유 설정이 지역을 채우지 않았다).
func kvsRequestHost(t *testing.T, region string) (string, error) {
	t.Helper()
	client, err := newKVSClient(t.Context())
	if err != nil {
		t.Fatalf("newKVSClient: %v", err)
	}
	if got := client.Options().Region; got != region {
		t.Fatalf("클라이언트 지역 = %q, want %q", got, region)
	}
	var host string
	errSent := errors.New("전송 가짜 — 망에 나가지 않는다")
	_, err = client.DescribeKeyValueStore(t.Context(), &cloudfrontkeyvaluestore.DescribeKeyValueStoreInput{
		KvsARN: aws.String(testKVSARN),
	}, func(o *cloudfrontkeyvaluestore.Options) {
		o.HTTPClient = httpClientFunc(func(r *http.Request) (*http.Response, error) {
			host = r.URL.Host
			return nil, errSent
		})
	})
	if errors.Is(err, errSent) {
		return host, nil
	}
	return "", err
}

// httpClientFunc 는 함수 하나로 된 SDK 전송이다.
type httpClientFunc func(*http.Request) (*http.Response, error)

func (f httpClientFunc) Do(r *http.Request) (*http.Response, error) { return f(r) }

// kvsClientEnv 는 SDK 가 시험용 정적 자격증명 · 빈 공유 설정만 보게 하고 AWS 지역을 region 으로 둔다(빈 값이면 지역 없음).
func kvsClientEnv(t *testing.T, region string) {
	t.Helper()
	assemblyCredentialsEnv(t)
	t.Setenv("AWS_ENDPOINT_URL_CLOUDFRONT_KEYVALUESTORE", "")
	t.Setenv("AWS_REGION", region)
	t.Setenv("AWS_DEFAULT_REGION", "")
}

// 끝점은 ARN 의 계정 번호로 정해지는 전역 끝점이다(<계정>.cloudfront-kvs.global.api.aws) — SDK 끝점 규칙이 지역을 보지
// 않으므로 상자의 AWS 지역이 비어도 기동(newKVSClient) · 호출의 끝점 해결 · 서명(SigV4A 지역 *)이 모두 선다. 그래서 지역 빔은
// edge_bounds_degraded 로 강등할 갈래가 아니다(Ⓒ-6 보안 설계 M2 — 이 시험이 그 전제를 고정한다). 지역이 있어도 같은 호스트다.
func TestNewKVSClientResolvesGlobalARNHost(t *testing.T) {
	for name, region := range map[string]string{"지역 빔": "", "지역 있음": "ap-northeast-2"} {
		t.Run(name, func(t *testing.T) {
			kvsClientEnv(t, region)

			host, err := kvsRequestHost(t, region)

			if err != nil {
				t.Fatalf("끝점 해결 · 서명 실패: %v", err)
			}
			if want := "123456789012.cloudfront-kvs.global.api.aws"; host != want {
				t.Errorf("요청 호스트 = %q, want %q", host, want)
			}
		})
	}
}
