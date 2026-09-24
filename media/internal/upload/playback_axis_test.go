package upload

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// ③(재생 렌디션)·init 축의 워커 본문을 잰다(계획 2.1 worker.go 행 · 4.2-R R3).
// ② 아카이브 축의 거동은 worker_test·axis_test 가 그대로 잰다.

// fakeProducer 는 ③·init 바이트 생산자의 시험 대역이다 — 요청 관측과 실패 주입(계획 6.4
// 「③ 실패 주입」)을 겸한다. 대역은 소비자 테스트가 정의한다(커밋 2 재량 #2 · Google Go
// best practices "Don't define back doors only for tests").
type fakeProducer struct {
	mu    sync.Mutex
	err   error  // nil 이 아니면 매번 이 오류로 실패한다
	init  []byte // nil 이 아니면 fakeInit 대신 이 init 을 낸다(다른 트랙 파라미터의 조각)
	calls []produceCall
}

// produceCall 은 Produce 한 번이 받은 것이다.
type produceCall struct {
	pos time.Duration
	// inputAt 은 부른 순간 입력 리더의 위치다 — 스위퍼가 mtxi 를 읽고 되돌렸는지를 본다.
	inputAt int64
}

var (
	fakeInit = []byte("fake-init:ftyp+moov")
	fakeSeg  = []byte("fake-seg:moof+mdat")
)

func fakeInitSHA() []byte {
	sum := sha256.Sum256(fakeInit)
	return sum[:]
}

func (p *fakeProducer) Produce(_ context.Context, req playback.Request) (playback.Output, error) {
	at, err := req.Input.Seek(0, io.SeekCurrent)
	if err != nil {
		at = -1
	}
	p.mu.Lock()
	p.calls = append(p.calls, produceCall{pos: req.PlaybackPos, inputAt: at})
	fail, init := p.err, p.init
	p.mu.Unlock()
	if fail != nil {
		return playback.Output{}, fail
	}
	if init == nil {
		init = fakeInit
	}
	return playback.Output{Init: init, Seg: fakeSeg, InitSHA256: sha256.Sum256(init)}, nil
}

// produceInit 는 앞으로 낼 init 을 바꾼다 — 송출 도중 트랙 파라미터가 바뀐 조각을 흉내 낸다.
func (p *fakeProducer) produceInit(b []byte) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.init = b
}

func (p *fakeProducer) produced() []produceCall {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]produceCall(nil), p.calls...)
}

// newPlaybackUploader 는 ③·init 축을 재는 업로더다. 고루틴 없이 작업을 그 자리에서 처리하고
// (runLive·runTarget·runQueue), 시계는 테스트가 민다(보정값 표·sessionInit 의 수명).
// 이벤트 집합을 붙인 **소비자 국면**이다 — 워커의 표시를 관측하려고.
func newPlaybackUploader(t *testing.T, st index.UploadStore, put Putter, prod playback.Producer, tune func(*Options)) (*Uploader, *logCapture, string, *fakeClock) {
	t.Helper()
	root, dir := newRoot(t)
	opt := DefaultOptions(root, dir)
	opt.RetryBase = time.Millisecond
	opt.SweepEvery = time.Hour
	if prod != nil {
		opt.Producer = prod
	}
	if tune != nil {
		tune(&opt)
	}
	cap := newLogCapture()
	clock := newFakeClock()
	u := newWithClock(st, put, opt, cap.logger(), clock.now)
	u.dirty = &Dirty{}
	u.armForSweepTest()
	return u, cap, dir, clock
}

// playbackTarget 은 [0] 대상 검증을 통과하는 ③ 대상이다. 도장 메타·기대 해시는 테스트가 채운다.
func playbackTarget(t *testing.T, streamID string, seq int64, sessionID, path string, bytes int64) index.UploadTarget {
	t.Helper()
	return index.UploadTarget{
		StreamID: streamID, Axis: index.AxisPlayback, Seq: seq, SessionID: sessionID,
		S3Key: segKeyOf(t, streamID, seq), LocalPath: path, Bytes: bytes,
	}
}

// runLive 는 실시간 경로(인덱서 요청·보류 재요청)의 작업 1건을 그 자리에서 처리한다.
func runLive(u *Uploader, target index.UploadTarget) outcome {
	return u.processTarget(job{target: target, origin: OriginLive})
}

// runQueue 는 워커 고루틴 없이 큐가 빌 때까지 작업을 처리한다 — 처리 중에 다시 들어온
// 작업(보류 목록 재요청)까지 같은 자리에서 돈다.
func runQueue(u *Uploader) {
	for {
		select {
		case j := <-u.queue:
			u.runJob(j)
		default:
			return
		}
	}
}

// 실물 녹화 조각 — playback 패키지 testdata 그대로다(상류 MediaMTX 녹화, 머리말에 mtxi 가 있다).
// 기대값은 전부 커밋 2 가 실물에서 따로 확인한 값이다(playback TestReadMtxiReadsRecorderValues ·
// TestRemuxOutputBytesGolden) — 검사 대상 코드로 만들지 않는다.
const (
	fixture4sPath = "../playback/testdata/segment_4s.mp4"
	fixture4sMtxi = 5_987_981_859 * time.Nanosecond
	goldenInitSHA = "83e663c55de687079f35d58c91889928dd6a86716bfa5f1cfbf11f932cf5e0cc"
	goldenInitLen = 1_159
	goldenSegSHA  = "061b4f1084743a7980edb6757344dc48101dbabbfc90cce9aa16c6f313e92f9c"
	goldenSegLen  = 141_788
)

// copyFixture 는 실물 조각을 <루트>/<streamID>/<이름> 에 복사한다.
func copyFixture(t *testing.T, dir, streamID, name string) (path string, size int64) {
	t.Helper()
	raw, err := os.ReadFile(fixture4sPath)
	if err != nil {
		t.Fatalf("실물 조각 읽기 실패: %v", err)
	}
	sub := filepath.Join(dir, streamID)
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	path = filepath.Join(sub, name)
	if err := os.WriteFile(path, raw, 0o644); err != nil {
		t.Fatalf("실물 조각 복사 실패: %v", err)
	}
	return path, int64(len(raw))
}

func mustHexBytes(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("16진 %q 해석 실패: %v", s, err)
	}
	return b
}

func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func sameInitCall(got, want initMarkCall) bool {
	return got.sessionID == want.sessionID && bytes.Equal(got.sha256, want.sha256) &&
		got.s3Key == want.s3Key && got.bytes == want.bytes && got.incompatible == want.incompatible
}

// ③ 실시간 작업은 받은 도장 위치로 재포장한 **메모리 산출**을 올린다(계획 2.1 · G4 디스크
// 쓰기 0). 잡는 결함: 녹화 파일을 그대로 올리면 되감기 객체가 합본(영상 1 + 소리 6)이고 도장이
// 없다 / 받은 위치를 버리면 리셋 뒤 도장이 역행한다.
func TestPlaybackLiveJobPutsSegmentProducedAtItsPos(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
	target := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
	target.PlaybackPos = 9 * time.Second
	target.ExpectedInitSHA = fakeInitSHA()

	if got := runLive(u, target); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success (%s)", got, cap.dump())
	}

	if got := prod.produced(); len(got) != 1 || got[0].pos != 9*time.Second {
		t.Errorf("Produce = %+v, want 1회 pos 9s", got)
	}
	calls := put.putCalls()
	if len(calls) != 1 {
		t.Fatalf("PUT = %d회, want 1회", len(calls))
	}
	if calls[0].key != "dvr/demo/seg/000007.m4s" || !bytes.Equal(calls[0].body, fakeSeg) || calls[0].size != int64(len(fakeSeg)) {
		t.Errorf("PUT = key %q size %d body %q, want dvr/demo/seg/000007.m4s · 산출 조각 %d바이트",
			calls[0].key, calls[0].size, calls[0].body, len(fakeSeg))
	}
	rec := cap.one(t, "segment_uploaded")
	if rec.attrs["bytes"] != int64(len(fakeSeg)) {
		t.Errorf("segment_uploaded bytes = %v, want %d (올린 산출 길이)", rec.attrs["bytes"], len(fakeSeg))
	}
}

// 기본 생산자는 Go 재포장이다(DefaultOptions — 계획 4.2-R RE). 실물 조각을 자기 mtxi 위치로
// 재포장한 산출이 커밋 2 의 골든과 바이트까지 같아야 한다 — 업로더는 산출을 가공하지 않는다.
func TestPlaybackDefaultProducerUploadsGoldenSegment(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, nil, nil)
	path, size := copyFixture(t, dir, "demo", "seg7.mp4")
	target := playbackTarget(t, "demo", 7, "S-1", path, size)
	target.PlaybackPos = fixture4sMtxi
	target.ExpectedInitSHA = mustHexBytes(t, goldenInitSHA)

	if got := runLive(u, target); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success (%s)", got, cap.dump())
	}
	calls := put.putCalls()
	if len(calls) != 1 || calls[0].size != goldenSegLen || sha256Hex(calls[0].body) != goldenSegSHA {
		t.Fatalf("PUT = %d회 (첫 건 size %d sha %s), want 1회 size %d sha %s",
			len(calls), calls[0].size, sha256Hex(calls[0].body), goldenSegLen, goldenSegSHA)
	}
	if marked, _ := st.playbackCalls(); len(marked) != 1 || marked[0].bytes != goldenSegLen {
		t.Errorf("③ 마킹 = %+v, want bytes %d", marked, goldenSegLen)
	}
}

// init 작업은 세션 첫 조각을 재포장한 산출 init 을 올리고 **그 바이트의 해시**를 첫 init CAS 에
// 기록한다(설계 5.3ⓑ · 5.5.5). 키는 예약값이 아니라 playback.InitKey 파생이다(계획 2.1 — 대상이
// 키를 비워 와도 된다). 기본 생산자의 실물 산출로 잰다 — 값은 커밋 2 골든.
func TestInitJobRecordsHashOfProducedInit(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, nil, nil)
	path, size := copyFixture(t, dir, "demo", "first.mp4")
	target := index.UploadTarget{StreamID: "demo", Axis: index.AxisInit, SessionID: "S-1", LocalPath: path, Bytes: size}

	if got := runLive(u, target); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success (%s)", got, cap.dump())
	}
	calls := put.putCalls()
	if len(calls) != 1 || calls[0].key != "dvr/demo/init/S-1.mp4" || calls[0].size != goldenInitLen || sha256Hex(calls[0].body) != goldenInitSHA {
		t.Fatalf("PUT = %+v, want 1회 dvr/demo/init/S-1.mp4 · %d바이트 · sha %s", calls, goldenInitLen, goldenInitSHA)
	}
	want := initMarkCall{sessionID: "S-1", sha256: mustHexBytes(t, goldenInitSHA), s3Key: "dvr/demo/init/S-1.mp4", bytes: goldenInitLen}
	if got := st.initCalls(); len(got) != 1 || !sameInitCall(got[0], want) {
		t.Errorf("MarkInitUploaded = %+v, want 1회 %+v (ⓐ 단독 국면 incompatible=false 고정)", got, want)
	}
}

// ③·init CAS 가 성공하면 워커가 이벤트 집합에 표시한다(계획 뮤테이션 20). 잡는 결함: 표시가
// 없으면 루프가 캐시의 settled 비트·init 게이트를 못 뒤집어 목록이 갱신되지 않는다.
func TestWorkerMarksDirtyAfterPlaybackAndInitCAS(t *testing.T) {
	u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, &fakeProducer{}, nil)
	initJob := initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)
	seg := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
	seg.ExpectedInitSHA = fakeInitSHA()

	if got := runLive(u, initJob); got != outcomeSuccess {
		t.Fatalf("init outcome = %v (%s)", got, cap.dump())
	}
	if got := runLive(u, seg); got != outcomeSuccess {
		t.Fatalf("③ outcome = %v (%s)", got, cap.dump())
	}

	sameEvents(t, u.Dirty().Peek(), []DirtyEvent{
		{Kind: DirtyUploaded, StreamID: "demo", Axis: index.AxisPlayback, Seq: 7, SessionID: "S-1"},
		{Kind: DirtyUploaded, StreamID: "demo", Axis: index.AxisInit, SessionID: "S-1"},
	})
}

// 소비자가 없는 국면(ⓐ 단독)에는 이벤트 집합을 만들지 않는다 — 아무도 비우지 않는 원소가
// 며칠씩 쌓이면 안 된다(계획 6.4 음성 대조). 사실은 이미 장부에 있어 부팅 재구성이 복원한다.
func TestUploaderHasNoDirtySetWithoutConsumer(t *testing.T) {
	root, dir := newRoot(t)
	opt := DefaultOptions(root, dir)
	opt.Producer = &fakeProducer{}
	u := New(&fakeUploadStore{}, &fakePutter{}, opt, newLogCapture().logger())
	u.armForSweepTest()
	seg := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
	seg.ExpectedInitSHA = fakeInitSHA()

	if got := runLive(u, seg); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success — 집합이 없어도 워커는 멈추지 않는다", got)
	}
	if u.Dirty() != nil {
		t.Error("Dirty() != nil — 소비자가 없는데 집합이 생겼다")
	}
	if Disabled(newLogCapture().logger()).Dirty() != nil {
		t.Error("Disabled 의 Dirty() != nil")
	}
}

// ③ 산출에 실패하면 아무것도 올리지 않는다(계획 2.1 · A6 「잘린 실물 고착」 방어 · 6.4 「③ 실패
// 주입」). 다시 읽으면 달라질 수 있는 실패 — 입력 해석 실패(쓰는 중에 잘린 입력일 수 있다)와 임의
// 오류 — 는 재시도 사다리를 탄다: 시도마다 다시 만들고, 소진하면 ③ 을 failed 로 확정하며(재수집
// 대상 — 계약 5-5), 그 판정은 ③ 브레이커에 실린다(설정 문제가 아니라 soft). 잡는 결함: 실패를
// 삼키고 빈 본문을 올리면 0바이트 ③ 이 uploaded 로 굳는다 / 재시도 없이 버리면 일시 오류 하나에
// 조각이 통째로 빠진다. 같은 입력이면 늘 같은 실패는 TestUnproducibleInputFailsWithoutRetry.
func TestPlaybackProduceFailureRetriesWithoutPublishing(t *testing.T) {
	for _, c := range []struct {
		name string
		err  error
	}{
		{"입력_해석_실패", playback.ErrMalformedInput},
		{"임의_오류", io.ErrUnexpectedEOF},
	} {
		t.Run(c.name, func(t *testing.T) {
			st := &fakeUploadStore{}
			put := &fakePutter{}
			prod := &fakeProducer{err: c.err}
			u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
			target := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
			target.ExpectedInitSHA = fakeInitSHA()

			if got := runLive(u, target); got != outcomeSoft {
				t.Errorf("outcome = %v, want soft — 설정 문제가 아니다", got)
			}
			if n := len(prod.produced()); n != u.opt.RetryMax {
				t.Errorf("Produce = %d회, want %d회 — 시도마다 다시 만든다", n, u.opt.RetryMax)
			}
			if n := len(put.putCalls()); n != 0 {
				t.Errorf("PUT = %d회, want 0회 — 실패한 산출은 올리지 않는다", n)
			}
			marked, failed := st.playbackCalls()
			if len(marked) != 0 || len(failed) != 1 || failed[0].streamID != "demo" || failed[0].seq != 7 || failed[0].reason == index.ReasonInitMismatch {
				t.Errorf("③ 마킹 = uploaded %+v · failed %+v, want failed 1회(세션을 끝내지 않는 사유)", marked, failed)
			}
			if _, blocked := u.gate.backoffBlocked(targetKeyOf(target)); !blocked {
				t.Error("백오프가 등록되지 않았다 — 재수집이 SweepEvery 마다 같은 실패를 되풀이한다")
			}
			if n := cap.count("upload_retry"); n != u.opt.RetryMax-1 {
				t.Errorf("upload_retry = %d건, want %d건", n, u.opt.RetryMax-1)
			}
			cap.one(t, "upload_failed")
			if len(u.Dirty().Peek()) != 0 {
				t.Errorf("이벤트 = %+v, want 없음 — 올라간 것이 없다", u.Dirty().Peek())
			}
		})
	}
}

// init 산출 실패도 같은 사다리다. init 에는 실패 CAS 가 없으므로(설계 5.5.5) 장부는 건드리지 않는다.
func TestInitProduceFailureRetriesWithoutMarking(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	prod := &fakeProducer{err: playback.ErrMalformedInput}
	u, _, dir, _ := newPlaybackUploader(t, st, put, prod, nil)

	if got := runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)); got != outcomeSoft {
		t.Errorf("outcome = %v, want soft", got)
	}
	if n := len(prod.produced()); n != u.opt.RetryMax {
		t.Errorf("Produce = %d회, want %d회 — 입력 해석 실패는 사다리를 탄다", n, u.opt.RetryMax)
	}
	if n := len(put.putCalls()); n != 0 {
		t.Errorf("PUT = %d회, want 0회", n)
	}
	if got := st.initCalls(); len(got) != 0 {
		t.Errorf("MarkInitUploaded = %+v, want 0회", got)
	}
}

// unproducibleCases 는 같은 입력이면 늘 같은 산출 실패다 — 무결성 ⓑ·ⓒ·ⓓ(트랙 부족·빈 트랙·크기
// 상한). 재포장이 오류값을 감싸 돌려주므로(%w) 감싼 형태도 같은 부류다.
var unproducibleCases = []struct {
	name string
	err  error
}{
	{"영상1_소리1_아님", playback.ErrMissingTracks},
	{"샘플_0", playback.ErrEmptyTrack},
	{"크기_상한_초과", playback.ErrInputTooLarge},
	{"감싼_오류", fmt.Errorf("%w: 17MiB", playback.ErrInputTooLarge)},
}

// 같은 입력이면 늘 같은 산출 실패는 사다리를 타지 않는다(cc r3 #1): 첫 시도에서 ③ 을 failed 로
// 확정하고(재수집 대상 — 계약 5-5) 키 백오프를 건다. 잡는 결함: 사다리(1+2+4초)는 하나뿐인 워커를
// 붙들어, 트랙이 빠진 송출 하나가 조각마다 7초를 먹고 그동안 모든 스트림의 ②·③ 이 큐에서 기다리다
// 넘친 몫이 upload_queue_full 로 떨어진다. S3 에 닿지 않았으니 브레이커 판정 재료가 아니다(neutral).
func TestUnproducibleInputFailsWithoutRetry(t *testing.T) {
	for _, c := range unproducibleCases {
		t.Run(c.name, func(t *testing.T) {
			st := &fakeUploadStore{}
			put := &fakePutter{}
			prod := &fakeProducer{err: c.err}
			u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
			target := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
			target.ExpectedInitSHA = fakeInitSHA()

			if got := runLive(u, target); got != outcomeNeutral {
				t.Errorf("outcome = %v, want neutral — S3 에 닿지 않았다", got)
			}
			if n := len(prod.produced()); n != 1 {
				t.Errorf("Produce = %d회, want 1회 — 다시 만들어도 같다", n)
			}
			if n := cap.count("upload_retry"); n != 0 {
				t.Errorf("upload_retry = %d건, want 0건", n)
			}
			if n := len(put.putCalls()); n != 0 {
				t.Errorf("PUT = %d회, want 0회", n)
			}
			marked, failed := st.playbackCalls()
			if len(marked) != 0 || len(failed) != 1 || failed[0].seq != 7 || failed[0].reason == index.ReasonInitMismatch {
				t.Errorf("③ 마킹 = uploaded %+v · failed %+v, want failed 1회(세션을 끝내지 않는 사유)", marked, failed)
			}
			if _, blocked := u.gate.backoffBlocked(targetKeyOf(target)); !blocked {
				t.Error("백오프가 등록되지 않았다 — 재수집마다 같은 실패를 되풀이한다")
			}
			if rec := cap.one(t, "upload_failed"); rec.level != slog.LevelError {
				t.Errorf("upload_failed 수준 = %v, want ERROR", rec.level)
			}
		})
	}
}

// init 도 같은 규칙이다 — 같은 입력이면 늘 같은 실패는 첫 시도에서 끝내고 키 백오프를 건다. init 에는
// 실패 CAS 가 없으므로(설계 5.5.5) 장부는 그대로다.
func TestInitUnproducibleInputFailsWithoutRetry(t *testing.T) {
	for _, c := range unproducibleCases {
		t.Run(c.name, func(t *testing.T) {
			st := &fakeUploadStore{}
			put := &fakePutter{}
			prod := &fakeProducer{err: c.err}
			u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
			initJob := initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)

			if got := runLive(u, initJob); got != outcomeNeutral {
				t.Errorf("outcome = %v, want neutral", got)
			}
			if n := len(prod.produced()); n != 1 {
				t.Errorf("Produce = %d회, want 1회", n)
			}
			if n := len(put.putCalls()) + len(st.initCalls()); n != 0 {
				t.Errorf("PUT·init CAS = %d회, want 0회", n)
			}
			if _, blocked := u.gate.backoffBlocked(targetKeyOf(initJob)); !blocked {
				t.Error("백오프가 등록되지 않았다")
			}
			cap.one(t, "upload_failed")
		})
	}
}

// 생산자가 배선되지 않았으면(nil) 널 오브젝트가 끼워진다 — ③·init 작업은 만들지 않고 실패하며
// 워커는 죽지 않는다. 잡는 결함: nil 인터페이스 호출 panic 이 워커 고루틴을 통째로 멈춘다.
func TestMissingProducerFailsPlaybackJobsWithoutPanic(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	root, dir := newRoot(t)
	opt := DefaultOptions(root, dir)
	opt.RetryBase = time.Millisecond
	opt.Producer = nil
	u := New(st, put, opt, newLogCapture().logger())
	u.armForSweepTest()
	target := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
	target.ExpectedInitSHA = fakeInitSHA()

	if got := runLive(u, target); got != outcomeSoft {
		t.Errorf("outcome = %v, want soft", got)
	}
	if n := len(put.putCalls()); n != 0 {
		t.Errorf("PUT = %d회, want 0회", n)
	}
}

// otherInitSHA 는 세션의 MAP 과 다른 기대 해시다(다른 코덱 파라미터로 확정된 세션).
func otherInitSHA() []byte { return bytes.Repeat([]byte{0xee}, sha256.Size) }

// 산출 init 이 세션의 MAP(기대 해시)과 다르면 올리지 않고, 조각 failed 와 세션
// ending(init_mismatch)을 한 CAS 로 영속한다(설계 5.3ⓒ · 계획 뮤테이션 23). 기대 해시가 작업에
// 실려 온 스위퍼 모양 대상으로 잰다. 잡는 결함: 대조가 없으면 다른 MAP 으로 만든 조각이 그 세션
// 목록에 실려 디코드가 깨진다 / 결속 사유를 빠뜨리면 조각만 failed 이고 세션은 계속 산다.
func TestPlaybackInitMismatchFailsSegmentAndEndsSessionWithoutPut(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, &fakeProducer{}, nil)
	target := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
	target.PlaybackPos = 9 * time.Second
	target.ExpectedInitSHA = otherInitSHA()

	if got := runLive(u, target); got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral — 설정 문제가 아니다", got)
	}
	if n := len(put.putCalls()); n != 0 {
		t.Fatalf("PUT = %d회, want 0회 — 다른 MAP 의 조각은 올리지 않는다", n)
	}
	marked, failed := st.playbackCalls()
	want := playbackFailCall{streamID: "demo", seq: 7, sessionID: "S-1", reason: index.ReasonInitMismatch}
	if len(marked) != 0 || len(failed) != 1 || failed[0] != want {
		t.Errorf("③ 마킹 = uploaded %+v · failed %+v, want failed 1회 %+v", marked, failed, want)
	}
	rec := cap.one(t, "session_init_mismatch")
	if rec.level != slog.LevelError || rec.attrs["session_id"] != "S-1" {
		t.Errorf("session_init_mismatch = %v %v, want ERROR session_id=S-1", rec.level, rec.attrs)
	}
	sameEvents(t, u.Dirty().Peek(), []DirtyEvent{{Kind: DirtySplit, StreamID: "demo", SessionID: "S-1", Reason: index.ReasonInitMismatch}})
	if _, blocked := u.gate.backoffBlocked(targetKeyOf(target)); !blocked {
		t.Error("백오프가 등록되지 않았다 — 재수집마다 같은 불일치 ERROR 가 되풀이된다")
	}
}

// 실시간 ③ 작업은 기대 해시를 싣지 않는다 — 그 세션의 init CAS 가 확정한 해시(sessionInit)가
// 기대값이다(계획 2.1). 잡는 결함: 필드가 비었다고 보류하면 init 확정 뒤의 실시간 ③ 이 전부
// 보류 목록으로 가고 상한을 넘은 몫이 pending 으로 굳는다.
func TestConfirmedInitBecomesExpectedHashOfLiveJobs(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, &fakeProducer{}, nil)
	if got := runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)); got != outcomeSuccess {
		t.Fatalf("init outcome = %v (%s)", got, cap.dump())
	}

	seg := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
	if got := runLive(u, seg); got != outcomeSuccess {
		t.Fatalf("③ outcome = %v, want success — 확정된 세션의 실시간 ③ 은 보류하지 않는다 (%s)", got, cap.dump())
	}
	if marked, _ := st.playbackCalls(); len(marked) != 1 || marked[0].seq != 7 {
		t.Errorf("③ 마킹 = %+v, want seq 7 1회", marked)
	}

	// 다른 세션의 확정값은 기대값이 아니다 — 세션마다 따로다.
	other := playbackTarget(t, "demo", 30, "S-2", writeSegment(t, dir, "demo", "seg30.mp4", 64), 64)
	runLive(u, other)
	for _, c := range put.putCalls() {
		if c.key == other.S3Key {
			t.Errorf("S-2 조각이 올라갔다 — S-1 의 init 을 S-2 의 기대값으로 썼다")
		}
	}
}

// 실시간 경로의 대조 — 확정된 sessionInit 과 산출 init 이 다르면 5.3ⓒ 다(계획 뮤테이션 23 의
// 실시간 쪽). 송출 도중 트랙 파라미터가 바뀐 조각을 흉내 낸다.
func TestLiveJobWhoseInitDiffersFromConfirmedInitEndsSession(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
	if got := runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)); got != outcomeSuccess {
		t.Fatalf("init outcome = %v (%s)", got, cap.dump())
	}
	prod.produceInit([]byte("init-with-new-codec-params"))

	seg := playbackTarget(t, "demo", 8, "S-1", writeSegment(t, dir, "demo", "seg8.mp4", 64), 64)
	runLive(u, seg)

	for _, c := range put.putCalls() {
		if c.key == seg.S3Key {
			t.Fatal("MAP 이 다른 조각이 올라갔다")
		}
	}
	if _, failed := st.playbackCalls(); len(failed) != 1 || failed[0].reason != index.ReasonInitMismatch || failed[0].sessionID != "S-1" {
		t.Errorf("③ failed = %+v, want 1회 init_mismatch·S-1", failed)
	}
	cap.one(t, "session_init_mismatch")
}

// ③ 축 크기 계약 ①(계획 2.1): 입력 파일이 아직 자라는 꼬리면 재포장하지 않는다 — 자라는
// 파일을 재포장하면 잘린 렌디션이 uploaded 로 굳는다(A6 · G12‴).
func TestPlaybackGrowingTailIsNotProduced(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
	target := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 200), 128) // 실물 200, 장부 128
	target.IsTail = true
	target.ExpectedInitSHA = fakeInitSHA()

	if got := runLive(u, target); got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral", got)
	}
	if n := len(prod.produced()); n != 0 {
		t.Errorf("Produce = %d회, want 0회 — 자라는 꼬리는 재포장하지 않는다", n)
	}
	if n := len(put.putCalls()); n != 0 {
		t.Errorf("PUT = %d회, want 0회", n)
	}
	if marked, failed := st.playbackCalls(); len(marked)+len(failed) != 0 {
		t.Errorf("③ 마킹 = %+v %+v, want 없음", marked, failed)
	}
	cap.one(t, "tail_still_growing")
}

// ③ 축 크기 계약 ②(계획 2.1): PUT 뒤 재측정은 **입력 파일 fd** 다 — 산출 길이는 입력이 같으면
// 늘 같아 잴 뜻이 없다. 올리는 사이 입력이 자랐으면 방금 올린 산출은 잘린 입력에서 나왔으므로
// 장부에 적지 않는다.
func TestPlaybackInputChangedDuringPutIsNotMarked(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, &fakeProducer{}, nil)
	path := writeSegment(t, dir, "demo", "seg7.mp4", 64)
	put.fn = func(context.Context, string, io.Reader, int64) error {
		f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
		if err != nil {
			return err
		}
		defer f.Close()
		_, err = f.Write(make([]byte, 32))
		return err
	}
	target := playbackTarget(t, "demo", 7, "S-1", path, 64)
	target.ExpectedInitSHA = fakeInitSHA()

	if got := runLive(u, target); got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral", got)
	}
	if marked, _ := st.playbackCalls(); len(marked) != 0 {
		t.Errorf("③ 마킹 = %+v, want 없음 — 입력이 바뀐 사이의 산출은 확정하지 않는다", marked)
	}
	rec := cap.one(t, "file_changed_during_put")
	if rec.attrs["put_size"] != int64(64) || rec.attrs["after_size"] != int64(96) {
		t.Errorf("file_changed_during_put = %v, want 입력 크기 64 → 96", rec.attrs)
	}
}

// ③ 실패 폭풍은 ③ 브레이커만 연다 — ② 클립 소재 업로드는 계속 돈다(계획 6.4 음성 대조 ·
// 설계 5.5.3). 워커 경로를 끝까지 지나 잰다: ③ 조각의 PUT 이 설정 오류(403)로 연속 실패한다.
func TestPlaybackHardFailuresOpenOnlyPlaybackBreaker(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{fn: func(_ context.Context, key string, _ io.Reader, _ int64) error {
		if key == "streams/demo/2026-08-03/00/seg_000100.m4s" {
			return nil // ② 는 멀쩡하다
		}
		return statusErr{403}
	}}
	u, _, dir, _ := newPlaybackUploader(t, st, put, &fakeProducer{}, nil)
	for seq := int64(1); seq <= int64(u.opt.CircuitMax); seq++ {
		tgt := playbackTarget(t, "demo", seq, "S-1", writeSegment(t, dir, "demo", fmt.Sprintf("seg%d.mp4", seq), 64), 64)
		tgt.ExpectedInitSHA = fakeInitSHA()
		u.runJob(job{target: tgt, origin: OriginLive})
	}

	next := playbackTarget(t, "demo", 50, "S-1", writeSegment(t, dir, "demo", "seg50.mp4", 64), 64)
	if got := u.enqueue(next, OriginLive); got != EnqueueRejected {
		t.Errorf("③ 접수 = %v, want rejected — ③ 브레이커가 열렸다", got)
	}
	archive := newTarget("demo", 100, writeSegment(t, dir, "demo", "seg100.mp4", 64), 64, false)
	if got := u.enqueue(archive, OriginLive); got != EnqueueAdmitted {
		t.Fatalf("② 접수 = %v, want admitted — ③ 의 폭풍이 ② 를 세우면 안 된다", got)
	}
	runQueue(u)
	if uploaded, _ := st.markCalls(); len(uploaded) != 1 {
		t.Errorf("② 마킹 = %+v, want 1회", uploaded)
	}
}

// 종료(putCtx 취소) 중에 재포장이 끊기면 실패가 아니다 — 재시도·실패 확정 없이 끝낸다
// (G18″ 종료 상한 · worker 의 PUT 취소와 같은 규율). 잡는 결함: 종료 한 번에 진행 중이던
// ③ 이 failed 로 적히고 브레이커 판정에 실린다.
func TestPlaybackProduceAbortedByShutdownIsNotAFailure(t *testing.T) {
	st := &fakeUploadStore{}
	prod := &fakeProducer{err: context.Canceled}
	u, cap, dir, _ := newPlaybackUploader(t, st, &fakePutter{}, prod, nil)
	u.putCancel()
	target := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
	target.ExpectedInitSHA = fakeInitSHA()

	if got := runLive(u, target); got != outcomeShutdown {
		t.Errorf("outcome = %v, want shutdown", got)
	}
	if n := len(prod.produced()); n != 1 {
		t.Errorf("Produce = %d회, want 1회 — 종료 중에는 재시도하지 않는다", n)
	}
	if _, failed := st.playbackCalls(); len(failed) != 0 {
		t.Errorf("③ failed = %+v, want 없음", failed)
	}
	cap.one(t, "upload_aborted_shutdown")
}

// upload_size_mismatch 의 크기 키는 축마다 뜻이 같아야 한다(cx r3 #3). ② 는 녹화 파일을 그대로 올려
// 입력 크기가 곧 PUT 크기라 put_bytes 이고, ③·init 은 재포장 산출을 올리므로 그 값은 입력 크기다
// (input_bytes). 같은 키로 적으면 운영자가 S3 전송 바이트 불일치로 오진한다.
func TestSizeMismatchLogNamesInputSizeByAxis(t *testing.T) {
	for _, c := range []struct {
		name  string
		axis  index.Axis
		key   string
		other string
	}{
		{"②_아카이브", index.AxisArchive, "put_bytes", "input_bytes"},
		{"③_재생", index.AxisPlayback, "input_bytes", "put_bytes"},
		{"init", index.AxisInit, "input_bytes", "put_bytes"},
	} {
		t.Run(c.name, func(t *testing.T) {
			u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, &fakeProducer{}, nil)
			path := writeSegment(t, dir, "demo", "seg7.mp4", 96) // 실물 96 · 장부 64 — 비꼬리라 올린다
			target := index.UploadTarget{StreamID: "demo", Axis: c.axis, SessionID: "S-1", LocalPath: path, Bytes: 64}
			switch c.axis {
			case index.AxisArchive:
				target = newTarget("demo", 7, path, 64, false)
			case index.AxisPlayback:
				target = playbackTarget(t, "demo", 7, "S-1", path, 64)
				target.ExpectedInitSHA = fakeInitSHA()
			}

			if got := runLive(u, target); got != outcomeSuccess {
				t.Fatalf("outcome = %v, want success (%s)", got, cap.dump())
			}
			rec := cap.one(t, "upload_size_mismatch")
			if rec.attrs[c.key] != int64(96) || rec.attrs["db_bytes"] != int64(64) {
				t.Errorf("upload_size_mismatch = %v, want %s=96 · db_bytes=64", rec.attrs, c.key)
			}
			if v, ok := rec.attrs[c.other]; ok {
				t.Errorf("upload_size_mismatch 에 %s=%v 가 실렸다 — 이 축의 입력 크기 키는 %s 다", c.other, v, c.key)
			}
		})
	}
}
