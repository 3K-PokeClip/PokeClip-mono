package upload

import (
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// newTestUploader 는 가짜 부품으로 조립한 업로더다. 시계는 실제 시계를 쓴다 —
// 종료 유예처럼 진짜로 흘러야 하는 시간이 있기 때문이다.
func newTestUploader(t *testing.T, st index.UploadStore, put Putter, tune func(*Options)) (*Uploader, *logCapture, string) {
	t.Helper()
	root, dir := newRoot(t)
	opt := DefaultOptions(root, dir)
	// 테스트가 실제로 기다리는 시간을 짧게 줄인다. 값의 의미는 그대로다.
	opt.ShutdownGrace = 200 * time.Millisecond
	opt.ShutdownMarkGrace = 200 * time.Millisecond
	opt.RetryBase = time.Millisecond
	opt.SweepEvery = time.Hour // 스위퍼가 판정을 흔들지 않게 한다
	if tune != nil {
		tune(&opt)
	}
	cap := newLogCapture()
	return New(st, put, opt, cap.logger()), cap, dir
}

// 정상 경로 하나 — 요청이 큐를 지나 PUT 되고 장부에 확정된 뒤 Result 가 온다.
// 이후의 모든 종료 테스트가 이 경로 위에서 성립한다.
func TestRequestUploadFlowsThroughWorkerToResult(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, _, dir := newTestUploader(t, st, put, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 128)

	u.Start(context.Background())
	u.ArmSweeper()
	defer u.Shutdown()

	target := newTarget("demo", 7, path, 128, false)
	if !u.RequestUpload(target) {
		t.Fatal("RequestUpload = false, want true")
	}

	select {
	case res := <-u.Results():
		if res.StreamID != "demo" || res.Seq != 7 || res.State != index.UploadStateUploaded {
			t.Fatalf("Result = %+v, want demo/7 uploaded", res)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Result 가 오지 않았다")
	}

	calls := put.putCalls()
	if len(calls) != 1 {
		t.Fatalf("PUT 횟수 = %d, want 1", len(calls))
	}
	if calls[0].key != target.S3Key || calls[0].size != 128 || len(calls[0].body) != 128 {
		t.Errorf("PUT = key %q size %d body %d바이트, want %q / 128 / 128",
			calls[0].key, calls[0].size, len(calls[0].body), target.S3Key)
	}
	uploaded, _ := st.markCalls()
	if len(uploaded) != 1 || uploaded[0].expectBytes != 128 {
		t.Errorf("MarkUploaded 호출 = %+v, want 1건 expectBytes=128", uploaded)
	}
}

// ⑭-a — Disabled 는 아무 일도 하지 않는다.
// 2·3번의 기본 상태(S3_BUCKET 미설정)가 이 경로다. accepted=false 가 계약이며,
// 그것이 없으면 인덱서의 requested 가 영구 잔류해 D13 교정이 죽는다.
func TestDisabledUploaderDoesNothing(t *testing.T) {
	cap := newLogCapture()
	u := Disabled(cap.logger())

	if u.Results() != nil {
		t.Error("Results() != nil — select 에서 선택될 수 있다")
	}
	if u.RequestUpload(index.UploadTarget{StreamID: "s", Seq: 1}) {
		t.Error("RequestUpload = true, want false")
	}
	if got := u.enqueue(index.UploadTarget{StreamID: "s", Seq: 1}, OriginSweep); got != EnqueueRejected {
		t.Errorf("enqueue = %v, want EnqueueRejected — 스위퍼 커서가 멈추면 안 된다", got)
	}
	// 미기동 상태이므로 Start·Shutdown 둘 다 안전해야 한다.
	u.Start(context.Background())
	u.ArmSweeper()
	u.Shutdown()
	u.Shutdown()
	if n := cap.count("uploader_disabled"); n != 1 {
		t.Errorf("uploader_disabled = %d건, want 1", n)
	}
}

// ⑭-b — 미기동 Shutdown 은 no-op 이다.
// w.Start 나 ix.Scan 이 실패하면 up.Start 전에 defer 가 돈다(결정 17″ 4번).
func TestShutdownBeforeStartIsNoOp(t *testing.T) {
	u, _, _ := newTestUploader(t, &fakeUploadStore{}, &fakePutter{}, nil)
	done := make(chan struct{})
	go func() { u.Shutdown(); close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("미기동 Shutdown 이 반환하지 않았다")
	}
	// 두 번째 호출도 안전하다.
	u.Shutdown()
}

// ⑭-c — 종료 3단계는 큐에 남은 작업을 버리고 그 수를 남긴다. 조용히 버리지 않는다.
func TestShutdownDropsQueuedJobsWithWarning(t *testing.T) {
	release := make(chan struct{})
	put := &fakePutter{fn: func(ctx context.Context, _ string, _ io.Reader, _ int64) error {
		select {
		case <-release:
		case <-ctx.Done():
			return ctx.Err()
		}
		return nil
	}}
	u, cap, dir := newTestUploader(t, &fakeUploadStore{}, put, nil)
	u.Start(context.Background())
	u.ArmSweeper()

	// 첫 건이 워커를 붙잡는 사이 나머지가 큐에 쌓인다.
	for seq := int64(0); seq < 4; seq++ {
		path := writeSegment(t, dir, "demo", "seg"+string(rune('0'+seq))+".mp4", 16)
		if !u.RequestUpload(newTarget("demo", seq, path, 16, false)) {
			t.Fatalf("seq %d 접수 실패", seq)
		}
	}
	waitFor(t, func() bool { return len(put.putCalls()) == 1 }, "첫 PUT 시작")

	u.Shutdown()

	rec := cap.one(t, "upload_shutdown_dropped")
	if got, ok := rec.attrs["count"].(int64); !ok || got != 3 {
		t.Errorf("upload_shutdown_dropped count = %v, want 3", rec.attrs["count"])
	}
}

// ⑭-d — PUT 진행 중 종료. 4단계 유예 뒤 5단계 putCancel 이 PUT 을 깨워야
// 무기한 대기가 되지 않는다.
func TestShutdownCancelsInFlightPut(t *testing.T) {
	put := &fakePutter{fn: func(ctx context.Context, _ string, _ io.Reader, _ int64) error {
		<-ctx.Done() // putCancel 만이 이 대기를 깬다
		return ctx.Err()
	}}
	u, cap, dir := newTestUploader(t, &fakeUploadStore{}, put, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 16)

	u.Start(context.Background())
	u.ArmSweeper()
	if !u.RequestUpload(newTarget("demo", 1, path, 16, false)) {
		t.Fatal("접수 실패")
	}
	waitFor(t, func() bool { return len(put.putCalls()) == 1 }, "PUT 시작")

	done := make(chan time.Duration, 1)
	go func() {
		start := time.Now()
		u.Shutdown()
		done <- time.Since(start)
	}()
	select {
	case elapsed := <-done:
		if elapsed > 3*time.Second {
			t.Errorf("종료에 %v 걸렸다 — 상한을 넘겼다", elapsed)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Shutdown 이 반환하지 않았다 — join 에는 타임아웃이 없다")
	}
	if n := cap.count("upload_aborted_shutdown"); n != 1 {
		t.Errorf("upload_aborted_shutdown = %d건, want 1", n)
	}
}

// ⑭-e — 재시도 백오프 대기 중 종료. 시간만 재는 sleep 이면 최악 종료가 백오프만큼 늘어난다.
func TestShutdownWakesRetryBackoffWait(t *testing.T) {
	put := &fakePutter{fn: func(context.Context, string, io.Reader, int64) error {
		return errors.New("일시 실패")
	}}
	u, _, dir := newTestUploader(t, &fakeUploadStore{}, put, func(o *Options) {
		o.RetryBase = 30 * time.Second // 깨우지 못하면 종료가 이만큼 늘어난다
	})
	path := writeSegment(t, dir, "demo", "seg.mp4", 16)

	u.Start(context.Background())
	u.ArmSweeper()
	if !u.RequestUpload(newTarget("demo", 1, path, 16, false)) {
		t.Fatal("접수 실패")
	}
	waitFor(t, func() bool { return len(put.putCalls()) >= 1 }, "첫 PUT 시도")

	done := make(chan struct{})
	go func() { u.Shutdown(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("백오프 대기가 종료를 붙잡았다")
	}
}

// ⑭-f — markCancel 로 마킹이 끊기면 결과가 불명이므로 Result 를 보내지 않는다.
// 보내면 메모리 커서가 DB 와 갈리고, 그 커서가 확인 2 에서 교정을 막는다(CX-3 ③).
func TestNoResultWhenMarkIsCancelled(t *testing.T) {
	st := &fakeUploadStore{
		onMarkUploaded: func(ctx context.Context, _ string, _, _ int64) (bool, error) {
			<-ctx.Done() // markCancel 만이 이 대기를 깬다
			return false, ctx.Err()
		},
	}
	u, _, dir := newTestUploader(t, st, &fakePutter{}, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 16)

	u.Start(context.Background())
	u.ArmSweeper()
	if !u.RequestUpload(newTarget("demo", 1, path, 16, false)) {
		t.Fatal("접수 실패")
	}
	uploadedSeen := func() bool { u, _ := st.markCalls(); return len(u) == 1 }
	waitFor(t, uploadedSeen, "MarkUploaded 진입")

	u.Shutdown()

	select {
	case res := <-u.Results():
		t.Fatalf("결과 불명인데 Result 가 왔다: %+v", res)
	default:
	}
}

// ㉑ — sweep_round 는 JSON 정수로 직렬화된다.
// AC4 가 이 필드의 차로 회차를 세므로 문자열이면 판정식이 통째로 깨진다.
func TestSweepRoundSerializesAsJSONInteger(t *testing.T) {
	put := &fakePutter{fn: func(context.Context, string, io.Reader, int64) error {
		return errors.New("실패")
	}}
	var buf syncBuffer
	u, _, dir := newTestUploader(t, &fakeUploadStore{}, put, nil)
	u.log = jsonLogger(&buf)
	path := writeSegment(t, dir, "demo", "seg.mp4", 16)

	u.Start(context.Background())
	u.ArmSweeper()
	u.sweepRound = 3 // 스위퍼 고루틴이 쓰는 필드. 여기서는 테스트가 대신 세팅한다
	if got := u.enqueue(newTarget("demo", 1, path, 16, false), OriginSweep); got != EnqueueAdmitted {
		t.Fatalf("enqueue = %v, want Admitted", got)
	}
	waitFor(t, func() bool { return buf.contains(`"msg":"upload_failed"`) }, "upload_failed 로그")
	u.Shutdown()

	if !buf.contains(`"sweep_round":3`) {
		t.Errorf("sweep_round 가 정수로 직렬화되지 않았다:\n%s", buf.String())
	}
	if !buf.contains(`"origin":"sweep"`) {
		t.Errorf("origin 필드가 없다:\n%s", buf.String())
	}
}

// origin=live 는 회차가 없으므로 0 고정이다(부록 머리말).
func TestLiveOriginCarriesZeroSweepRound(t *testing.T) {
	put := &fakePutter{fn: func(context.Context, string, io.Reader, int64) error {
		return errors.New("실패")
	}}
	var buf syncBuffer
	u, _, dir := newTestUploader(t, &fakeUploadStore{}, put, nil)
	u.log = jsonLogger(&buf)
	path := writeSegment(t, dir, "demo", "seg.mp4", 16)

	u.Start(context.Background())
	u.ArmSweeper()
	u.sweepRound = 9
	if !u.RequestUpload(newTarget("demo", 1, path, 16, false)) {
		t.Fatal("접수 실패")
	}
	waitFor(t, func() bool { return buf.contains(`"msg":"upload_failed"`) }, "upload_failed 로그")
	u.Shutdown()

	if !buf.contains(`"sweep_round":0`) || !buf.contains(`"origin":"live"`) {
		t.Errorf("live 로그의 origin/sweep_round 가 계약과 다르다:\n%s", buf.String())
	}
}

// tq-9 — 종료 후에는 접수가 반드시 거부된다.
// true 를 돌려주면 인덱서가 requested 를 기록하는데 그 작업은 영원히 처리되지 않는다.
func TestRequestUploadIsRejectedAfterShutdown(t *testing.T) {
	u, _, dir := newTestUploader(t, &fakeUploadStore{}, &fakePutter{}, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 16)
	target := newTarget("demo", 1, path, 16, false)

	u.Start(context.Background())
	u.ArmSweeper()
	if !u.RequestUpload(target) {
		t.Fatal("기동 중 접수가 거부됐다")
	}
	u.Shutdown()

	if u.RequestUpload(newTarget("demo", 2, path, 16, false)) {
		t.Error("종료 후 접수가 허용됐다")
	}
	if got := u.enqueue(target, OriginSweep); got != EnqueueRejected {
		t.Errorf("종료 후 enqueue = %v, want Rejected", got)
	}
}

// tq-B4 — QueueFull 에서도 RequestUpload 는 반드시 false 다.
//
// true 를 돌려주면 인덱서가 requested 를 기록하고 held 를 지운다. 큐에 없는 조각을
// "요청 완료"로 믿게 되므로 그 조각은 스위퍼가 집을 때까지 영구 지연되고, 그동안
// D13 교정도 막힌다. Rejected 와 QueueFull 의 구분은 스위퍼 커서에만 쓸모가 있다.
func TestRequestUploadReturnsFalseWhenQueueIsFull(t *testing.T) {
	block := make(chan struct{})
	put := &fakePutter{fn: func(ctx context.Context, _ string, _ io.Reader, _ int64) error {
		select {
		case <-block:
		case <-ctx.Done():
			return ctx.Err()
		}
		return nil
	}}
	u, _, dir := newTestUploader(t, &fakeUploadStore{}, put, func(o *Options) { o.QueueLen = 1 })
	u.Start(context.Background())
	u.ArmSweeper()
	defer func() { close(block); u.Shutdown() }()

	// 워커가 1건을 실제로 꺼낸 뒤 큐를 정확히 가득 채운다.
	first := writeSegment(t, dir, "demo", "busy0.mp4", 16)
	if !u.RequestUpload(newTarget("demo", 0, first, 16, false)) {
		t.Fatal("첫 접수가 거부됐다")
	}
	waitFor(t, func() bool { return len(put.putCalls()) == 1 }, "워커가 첫 건을 꺼낼 때까지")
	second := writeSegment(t, dir, "demo", "busy1.mp4", 16)
	if !u.RequestUpload(newTarget("demo", 1, second, 16, false)) {
		t.Fatal("두 번째 접수가 거부됐다")
	}

	// 이제 큐가 꽉 찼다.
	path := writeSegment(t, dir, "demo", "full.mp4", 16)
	target := newTarget("demo", 9, path, 16, false)
	if got := u.enqueue(target, OriginLive); got != EnqueueQueueFull {
		t.Fatalf("준비 실패: enqueue = %v, want QueueFull", got)
	}
	if u.RequestUpload(target) {
		t.Error("QueueFull 인데 RequestUpload = true — 큐에 없는 조각을 요청 완료로 믿게 된다")
	}
}
