package upload

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// runTarget 은 워커 고루틴 없이 작업 1건을 그 자리에서 처리한다.
// 큐를 지나면 "몇 번째 시도에서 무엇이 났는가"가 스케줄러에 달려 판정이 흔들린다.
func runTarget(t *testing.T, u *Uploader, target index.UploadTarget) outcome {
	t.Helper()
	return u.processTarget(job{target: target, origin: OriginSweep, sweepRound: 1})
}

func newWorkerUploader(t *testing.T, st index.UploadStore, put Putter, tune func(*Options)) (*Uploader, *logCapture, string) {
	t.Helper()
	root, dir := newRoot(t)
	opt := DefaultOptions(root, dir)
	opt.RetryBase = time.Millisecond
	opt.SweepEvery = time.Hour
	if tune != nil {
		tune(&opt)
	}
	cap := newLogCapture()
	u := New(st, put, opt, cap.logger())
	u.armForSweepTest()
	return u, cap, dir
}

// ① — 꼬리가 아직 자라는 중이면 아무것도 하지 않는다. PUT 도 마킹도 없다.
// 여기서 올리면 잘린 실물이 uploaded 로 굳는다(G12‴).
func TestTailSizeMismatchUploadsNothing(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir := newWorkerUploader(t, st, put, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 200) // 실물 200, 장부 128

	got := runTarget(t, u, newTarget("demo", 7, path, 128, true))

	if got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral", got)
	}
	if n := len(put.putCalls()); n != 0 {
		t.Errorf("PUT %d회, want 0회", n)
	}
	up, fl := st.markCalls()
	if len(up)+len(fl) != 0 {
		t.Errorf("마킹 %d건, want 0건", len(up)+len(fl))
	}
	rec := cap.one(t, "tail_still_growing")
	if got, want := rec.attrs["file_bytes"], int64(200); got != want {
		t.Errorf("file_bytes = %v, want %v", got, want)
	}
	// 레벨이 계약이다 — 스위프 경로의 보류는 "오래 안 풀린다"는 신호라 WARN 이다.
	if rec.level != slog.LevelWarn {
		t.Errorf("레벨 = %v, want WARN (origin=sweep)", rec.level)
	}
}

// ①-b — 같은 판정이라도 실시간 경로에서는 흔한 정상 상황이라 DEBUG 다.
// 여기서 WARN 을 내면 조각마다 경고가 떠 "업로더 기인 WARN 0건" 판정이 무너진다.
func TestTailSizeMismatchIsDebugOnLivePath(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir := newWorkerUploader(t, st, put, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 200)

	got := u.processTarget(job{target: newTarget("demo", 7, path, 128, true), origin: OriginLive})

	if got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral", got)
	}
	rec := cap.one(t, "tail_still_growing")
	if rec.level != slog.LevelDebug {
		t.Errorf("레벨 = %v, want DEBUG (origin=live)", rec.level)
	}
	if n := len(put.putCalls()); n != 0 {
		t.Errorf("PUT %d회, want 0회", n)
	}
}

// ② — PUT 이 끝난 뒤 파일이 자랐다면 마킹하지 않는다(예방 성공 신호).
// 백오프에는 등록하지 않는다 — "파일이 방금 바뀌었다"는 양의 증거를 봤으므로
// 다음 회차 판정이 실제로 달라진다(M-1 표에 없는 분기 = 미등록 고정 · C10).
func TestFileGrowthDuringPutSkipsMarkingAndDoesNotBackoff(t *testing.T) {
	st := &fakeUploadStore{}
	var path string
	put := &fakePutter{}
	u, cap, dir := newWorkerUploader(t, st, put, nil)
	path = writeSegment(t, dir, "demo", "seg.mp4", 128)
	put.fn = func(context.Context, string, io.Reader, int64) error {
		// PUT 도중 파일이 자란 상황을 만든다.
		f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
		if err != nil {
			return err
		}
		defer f.Close()
		_, err = f.Write(make([]byte, 32))
		return err
	}

	got := runTarget(t, u, newTarget("demo", 7, path, 128, false))

	if got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral", got)
	}
	up, fl := st.markCalls()
	if len(up)+len(fl) != 0 {
		t.Errorf("마킹 %d건, want 0건", len(up)+len(fl))
	}
	rec := cap.one(t, "file_changed_during_put")
	if rec.level != slog.LevelWarn {
		t.Errorf("레벨 = %v, want WARN", rec.level)
	}
	if _, blocked := u.gate.backoffBlocked(archiveKey("demo", 7)); blocked {
		t.Error("백오프가 등록됐다 — 이 분기는 미등록 고정이다")
	}
}

// ③ — CAS 거부와 DB 오류는 Result 를 보내지 않는다.
// 보내면 메모리 커서가 DB 와 갈리고 그 커서가 확인 2 에서 교정을 막는다.
func TestNoResultOnCASRejectionOrDBError(t *testing.T) {
	for _, c := range []struct {
		name string
		hook func(context.Context, string, int64, int64) (bool, error)
		log  string
	}{
		{"CAS_거부", func(context.Context, string, int64, int64) (bool, error) { return false, nil }, "upload_cas_rejected"},
		{"DB_오류", func(context.Context, string, int64, int64) (bool, error) { return false, errors.New("연결 끊김") }, "mark_error"},
	} {
		t.Run(c.name, func(t *testing.T) {
			st := &fakeUploadStore{onMarkUploaded: c.hook}
			u, cap, dir := newWorkerUploader(t, st, &fakePutter{}, nil)
			path := writeSegment(t, dir, "demo", "seg.mp4", 128)

			got := runTarget(t, u, newTarget("demo", 7, path, 128, false))

			if got != outcomeNeutral {
				t.Errorf("outcome = %v, want neutral", got)
			}
			select {
			case res := <-u.results:
				t.Fatalf("Result 가 왔다: %+v", res)
			default:
			}
			cap.one(t, c.log)
			// 두 분기 모두 백오프에 등록한다(M-1) — 미등록이면 이미 성공한 PUT 을
			// SweepEvery 마다 통째로 다시 올리는 핫루프가 된다.
			if _, blocked := u.gate.backoffBlocked(archiveKey("demo", 7)); !blocked {
				t.Error("백오프가 등록되지 않았다")
			}
		})
	}
}

// ④ — 재시도마다 파일을 다시 열고, 어떤 중도 return 에서도 FD 가 새지 않는다.
//
// 이음매를 만들지 않고 실프로세스 FD 를 센다. Options.Root 가 구체 타입이라
// 가짜를 주입할 수 없고, 이음매를 내면 attemptOnce 가 인터페이스로 번진다(④ 확정).
func TestNoFileDescriptorLeakAcrossRetries(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{fn: func(context.Context, string, io.Reader, int64) error {
		return errors.New("항상 실패")
	}}
	u, _, dir := newWorkerUploader(t, st, put, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 64)
	target := newTarget("demo", 7, path, 64, false)

	runTarget(t, u, target) // 워밍업 — 첫 호출의 지연 할당을 계수에서 뺀다

	before := fdCount(t)
	for i := 0; i < 100; i++ {
		runTarget(t, u, target)
	}
	after := fdCount(t)

	t.Logf("FD 계수 before=%d after=%d", before, after)
	if after-before > 0 {
		t.Errorf("FD 가 %d개 늘었다 (before=%d after=%d)", after-before, before, after)
	}
}

func fdCount(t *testing.T) int {
	t.Helper()
	dir := "/proc/self/fd"
	if _, err := os.Stat(dir); err != nil {
		dir = "/dev/fd"
	}
	// ReadDir 은 항목마다 stat 을 걸어 /dev/fd 에서 실패한다(자기 자신의 fd 가 섞인다).
	// 이름만 읽으면 충분하다.
	f, err := os.Open(dir)
	if err != nil {
		t.Fatalf("FD 디렉토리 %s 를 열지 못했다: %v", dir, err)
	}
	defer f.Close()
	names, err := f.Readdirnames(-1)
	if err != nil {
		t.Fatalf("FD 디렉토리 %s 를 읽지 못했다: %v", dir, err)
	}
	return len(names)
}

// ⑤ — ENOENT 는 MarkFailed 가 확정된 뒤에만 격리한다(CX6-1).
// 마킹이 실패한 행을 격리하면 DB 상 pending 인데 메모리 격리 목록에 올라
// 재기동 전까지 회수 불가가 되어 G11′ 을 정면으로 어긴다.
func TestFileMissingQuarantinesOnlyAfterConfirmedMark(t *testing.T) {
	cases := []struct {
		name           string
		hook           func(context.Context, string, int64, int64) (bool, error)
		wantQuarantine bool
		wantResult     bool
		wantBackoff    bool
		wantOutcome    outcome
	}{
		{"마킹_확정", nil, true, true, false, outcomeSoft},
		{"DB_오류", func(context.Context, string, int64, int64) (bool, error) {
			return false, errors.New("연결 끊김")
		}, false, false, true, outcomeNeutral},
		{"CAS_거부", func(context.Context, string, int64, int64) (bool, error) {
			return false, nil
		}, false, false, true, outcomeNeutral},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			st := &fakeUploadStore{onMarkFailed: c.hook}
			put := &fakePutter{}
			u, cap, dir := newWorkerUploader(t, st, put, nil)
			// 디렉토리는 만들되 파일은 만들지 않는다 → Root.Open 이 ENOENT.
			if err := os.MkdirAll(filepath.Join(dir, "demo"), 0o755); err != nil {
				t.Fatal(err)
			}
			target := newTarget("demo", 7, filepath.Join(dir, "demo", "gone.mp4"), 64, false)

			got := runTarget(t, u, target)

			if got != c.wantOutcome {
				t.Errorf("outcome = %v, want %v", got, c.wantOutcome)
			}
			k := archiveKey("demo", 7)
			if u.gate.quarantined(k) != c.wantQuarantine {
				t.Errorf("격리 = %v, want %v", u.gate.quarantined(k), c.wantQuarantine)
			}
			if _, blocked := u.gate.backoffBlocked(k); blocked != c.wantBackoff {
				t.Errorf("백오프 = %v, want %v", blocked, c.wantBackoff)
			}
			gotResult := len(u.results) == 1
			if gotResult != c.wantResult {
				t.Errorf("Result 송신 = %v, want %v", gotResult, c.wantResult)
			}
			// M-3 — 파일 부재는 재시도해도 결과가 같으므로 루프를 즉시 벗어난다.
			// 행당 1회를 넘으면 AC4 조건 1의 등호가 무너진다.
			if n := cap.count("upload_file_missing"); n != 1 {
				t.Errorf("upload_file_missing = %d건, want 1건 (행당 1회)", n)
			}
			if n := len(put.putCalls()); n != 0 {
				t.Errorf("PUT %d회, want 0회", n)
			}
		})
	}
}

// ⑮-a — [0] 대상 검증 전수. 루트 이탈 판정이 dir 일치 판정보다 앞이다.
//
// 두 조건을 동시에 위반하는 입력에서 dir_mismatch 가 먼저 나오면 AC5 의 3중 필터
// (stream_id=evil · seq=0 · reason=root_escape)가 위음성 FAIL 이 된다(r5 · cc M-1).
func TestTargetValidationRejectReasons(t *testing.T) {
	u, cap, dir := newWorkerUploader(t, &fakeUploadStore{}, &fakePutter{}, nil)
	ok := writeSegment(t, dir, "demo", "seg.mp4", 64)

	cases := []struct {
		name   string
		target index.UploadTarget
		reason string
	}{
		{"허용되지_않는_stream_id", index.UploadTarget{
			StreamID: "demo/../etc", Axis: index.AxisArchive, Seq: 7, S3Key: index.S3Key("demo", 7, fixtureWall),
			LocalPath: ok, Bytes: 64}, "bad_stream_id"},
		{"키_문법_위반", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisArchive, Seq: 7, S3Key: "streams/demo/seg_000007.m4s",
			LocalPath: ok, Bytes: 64}, "bad_key"},
		{"키의_stream_불일치", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisArchive, Seq: 7, S3Key: index.S3Key("other", 7, fixtureWall),
			LocalPath: ok, Bytes: 64}, "bad_key"},
		{"키의_seq_불일치", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisArchive, Seq: 8, S3Key: index.S3Key("demo", 7, fixtureWall),
			LocalPath: ok, Bytes: 64}, "seq_mismatch"},
		{"절대경로_루트_이탈", index.UploadTarget{
			StreamID: "evil", Axis: index.AxisArchive, Seq: 0, S3Key: index.S3Key("evil", 0, fixtureWall),
			LocalPath: "/home/sidecar/.aws/credentials", Bytes: 64}, "root_escape"},
		{"상대경로_상위_이탈", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisArchive, Seq: 7, S3Key: index.S3Key("demo", 7, fixtureWall),
			LocalPath: filepath.Join(dir, "..", "outside.mp4"), Bytes: 64}, "root_escape"},
		{"디렉토리_불일치", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisArchive, Seq: 7, S3Key: index.S3Key("demo", 7, fixtureWall),
			LocalPath: filepath.Join(dir, "other", "seg.mp4"), Bytes: 64}, "dir_mismatch"},
		{"bytes_0", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisArchive, Seq: 7, S3Key: index.S3Key("demo", 7, fixtureWall),
			LocalPath: ok, Bytes: 0}, "bad_bytes"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			cap.reset()
			got := runTarget(t, u, c.target)
			if got != outcomeNeutral {
				t.Errorf("outcome = %v, want neutral", got)
			}
			rec := cap.one(t, "upload_target_rejected")
			if rec.level != slog.LevelError {
				t.Errorf("레벨 = %v, want ERROR", rec.level)
			}
			if rec.attrs["reason"] != c.reason {
				t.Errorf("reason = %v, want %q", rec.attrs["reason"], c.reason)
			}
			if !u.gate.quarantined(archiveKey(c.target.StreamID, c.target.Seq)) {
				t.Error("격리되지 않았다 — 검증 실패는 마킹이 없으므로 즉시 격리다")
			}
		})
	}
}

// ⑮-b — Root.Open 오류의 분류 전수.
// EMFILE·ENFILE 은 일시적 자원 고갈이라 격리하면 안 되고, 나머지는 재시도해도 결과가 같다.
func TestOpenErrorClassification(t *testing.T) {
	cases := []struct {
		name       string
		err        error
		wantKind   attemptKind
		wantLog    string
		quarantine bool
	}{
		{"ENOENT", fs.ErrNotExist, attemptFileMissing, "", false},
		{"EMFILE", syscall.EMFILE, attemptDone, "upload_open_transient", false},
		{"ENFILE", syscall.ENFILE, attemptDone, "upload_open_transient", false},
		{"EACCES", syscall.EACCES, attemptDone, "upload_target_rejected", true},
		{"ELOOP", syscall.ELOOP, attemptDone, "upload_target_rejected", true},
		{"ErrInvalid", os.ErrInvalid, attemptDone, "upload_target_rejected", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			u, cap, _ := newWorkerUploader(t, &fakeUploadStore{}, &fakePutter{}, nil)
			k := archiveKey("demo", 7)
			wrapped := &fs.PathError{Op: "open", Path: "demo/seg.mp4", Err: c.err}

			got := u.classifyOpenError(wrapped, index.UploadTarget{StreamID: "demo", Axis: index.AxisArchive, Seq: 7}, k, u.log)

			if got.kind != c.wantKind {
				t.Errorf("kind = %v, want %v", got.kind, c.wantKind)
			}
			if c.wantLog != "" {
				cap.one(t, c.wantLog)
			}
			if u.gate.quarantined(k) != c.quarantine {
				t.Errorf("격리 = %v, want %v", u.gate.quarantined(k), c.quarantine)
			}
		})
	}
}

// ⑯ — 정규 파일이 아니면 크기를 믿을 수 없다. fi 를 무조건 역참조하지 않는 것과
// 같은 자리의 판정이며, 여기서 panic 이 나면 워커 고루틴이 통째로 죽는다.
func TestNonRegularFileIsRejectedWithoutPanic(t *testing.T) {
	u, cap, dir := newWorkerUploader(t, &fakeUploadStore{}, &fakePutter{}, nil)
	// 디렉토리를 대상으로 준다 — Root.Open 은 성공하고 Stat 도 성공하지만 정규 파일이 아니다.
	if err := os.MkdirAll(filepath.Join(dir, "demo", "notafile.mp4"), 0o755); err != nil {
		t.Fatal(err)
	}
	target := newTarget("demo", 7, filepath.Join(dir, "demo", "notafile.mp4"), 64, false)

	got := runTarget(t, u, target)

	if got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral", got)
	}
	rec := cap.one(t, "upload_target_rejected")
	if rec.attrs["reason"] != "not_regular" {
		t.Errorf("reason = %v, want not_regular", rec.attrs["reason"])
	}
}

// ⑰ — 비꼬리는 크기가 어긋나도 올리고, 재시도를 소진하면 보류 없이 failed 로 확정한다.
// 교정 경로가 없는 행을 영구 pending 으로 두면 워커를 점유하고 streak 에도 안 잡힌다.
func TestNonTailRowIsMarkedFailedWithoutRecheck(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{fn: func(context.Context, string, io.Reader, int64) error {
		return errors.New("항상 실패")
	}}
	u, cap, dir := newWorkerUploader(t, st, put, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 200) // 실물 200, 장부 128

	got := runTarget(t, u, newTarget("demo", 7, path, 128, false))

	if got != outcomeSoft {
		t.Errorf("outcome = %v, want soft", got)
	}
	if n := len(put.putCalls()); n != 4 {
		t.Errorf("PUT %d회, want 4회(RetryMax)", n)
	}
	if n := cap.count("upload_retry"); n != 3 {
		t.Errorf("upload_retry = %d건, want 3건 (마지막 시도는 재시도 로그가 없다)", n)
	}
	// 비꼬리 크기 불일치는 경고만 하고 계속한다(L14). 판정이 attemptOnce 안에 있으므로
	// 시도마다 한 건씩 난다.
	if n := cap.count("upload_size_mismatch"); n != 4 {
		t.Errorf("upload_size_mismatch = %d건, want 4건(시도마다 1건)", n)
	}
	if n := cap.count("upload_stat_failed"); n != 0 {
		t.Errorf("비꼬리인데 재측정을 했다 (%d건)", n)
	}
	if n := cap.count("mark_failed_deferred"); n != 0 {
		t.Errorf("비꼬리인데 확정을 보류했다 (%d건)", n)
	}
	_, fl := st.markCalls()
	if len(fl) != 1 {
		t.Fatalf("MarkFailed %d건, want 1건", len(fl))
	}
	rec := cap.one(t, "upload_failed")
	if rec.attrs["err_class"] != "soft" {
		t.Errorf("err_class = %v, want soft", rec.attrs["err_class"])
	}
}

// ⑱ — [3] 꼬리 재측정. 판정 재료가 없으면 판정하지 않는다.
func TestTailRecheckAfterRetriesExhausted(t *testing.T) {
	alwaysFail := func() Putter {
		return &fakePutter{fn: func(context.Context, string, io.Reader, int64) error {
			return errors.New("항상 실패")
		}}
	}

	// (a) 재측정 자체가 불가 — 마킹 없이 pending 으로 두고 백오프에 등록한다(failures +1).
	t.Run("재open_실패는_마킹없이_보류하고_지수백오프", func(t *testing.T) {
		st := &fakeUploadStore{}
		put := alwaysFail()
		u, cap, dir := newWorkerUploader(t, st, put, nil)
		path := writeSegment(t, dir, "demo", "seg.mp4", 128)
		// 마지막 PUT 시도 뒤 파일이 사라진 상황을 만든다.
		calls := 0
		put.(*fakePutter).fn = func(context.Context, string, io.Reader, int64) error {
			calls++
			if calls == 4 {
				_ = os.Remove(path)
			}
			return errors.New("항상 실패")
		}

		got := runTarget(t, u, newTarget("demo", 7, path, 128, true))

		if got != outcomeNeutral {
			t.Errorf("outcome = %v, want neutral", got)
		}
		rec := cap.one(t, "upload_stat_failed")
		if rec.attrs["stage"] != "tail_recheck" {
			t.Errorf("stage = %v, want tail_recheck", rec.attrs["stage"])
		}
		_, fl := st.markCalls()
		if len(fl) != 0 {
			t.Errorf("MarkFailed %d건, want 0건 — 판정 재료가 없으면 판정하지 않는다", len(fl))
		}
		k := archiveKey("demo", 7)
		if u.gate.quarantined(k) {
			t.Error("격리됐다 — 다음 회차가 되돌릴 수 있어야 한다")
		}
		if u.gate.failuresOf(k) != 1 {
			t.Errorf("failures = %d, want 1", u.gate.failuresOf(k))
		}
	})

	// (b) 재측정 성공 + 크기 불일치 — 1회차 고정 지연, failures 불변.
	t.Run("크기_불일치는_1회차_고정_지연", func(t *testing.T) {
		st := &fakeUploadStore{}
		put := alwaysFail()
		u, cap, dir := newWorkerUploader(t, st, put, nil)
		path := writeSegment(t, dir, "demo", "seg.mp4", 128)
		calls := 0
		put.(*fakePutter).fn = func(context.Context, string, io.Reader, int64) error {
			calls++
			if calls == 4 {
				f, _ := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
				_, _ = f.Write(make([]byte, 16))
				_ = f.Close()
			}
			return errors.New("항상 실패")
		}

		base := time.Now()
		got := runTarget(t, u, newTarget("demo", 7, path, 128, true))

		if got != outcomeNeutral {
			t.Errorf("outcome = %v, want neutral", got)
		}
		rec := cap.one(t, "mark_failed_deferred")
		if rec.attrs["db_bytes"] != int64(128) || rec.attrs["file_bytes"] != int64(144) {
			t.Errorf("필드 = db %v file %v, want 128 / 144", rec.attrs["db_bytes"], rec.attrs["file_bytes"])
		}
		_, fl := st.markCalls()
		if len(fl) != 0 {
			t.Errorf("MarkFailed %d건, want 0건", len(fl))
		}
		k := archiveKey("demo", 7)
		if u.gate.failuresOf(k) != 0 {
			t.Errorf("failures = %d, want 0 (불변)", u.gate.failuresOf(k))
		}
		nextAt, blocked := u.gate.backoffBlocked(k)
		if !blocked {
			t.Fatal("백오프가 걸리지 않았다")
		}
		if d := nextAt.Sub(base); d > u.opt.SweepEvery+time.Second || d < u.opt.SweepEvery-time.Second {
			t.Errorf("다음 시도까지 %v, want 약 %v (1회차 고정)", d, u.opt.SweepEvery)
		}
	})

	// (c) 재측정 성공 + 크기 일치 — 정상적으로 failed 확정.
	t.Run("크기_일치는_failed_확정", func(t *testing.T) {
		st := &fakeUploadStore{}
		u, cap, dir := newWorkerUploader(t, st, alwaysFail(), nil)
		path := writeSegment(t, dir, "demo", "seg.mp4", 128)

		got := runTarget(t, u, newTarget("demo", 7, path, 128, true))

		if got != outcomeSoft {
			t.Errorf("outcome = %v, want soft", got)
		}
		if _, fl := st.markCalls(); len(fl) != 1 {
			t.Errorf("MarkFailed %d건, want 1건", len(fl))
		}
		cap.one(t, "upload_failed")
	})
}

// ⑱-e — [2](e) 의 두 분기(PUT 후 재측정 실패·파일 성장)는 백오프에 등록하지 않는다.
// 재측정 실패는 파일이 사라진 경우로 만든다 — 같은 fd 로 재는데도 오류가 나는 상황이다.
func TestPostPutStatBranchesDoNotRegisterBackoff(t *testing.T) {
	st := &fakeUploadStore{}
	var path string
	put := &fakePutter{}
	u, _, dir := newWorkerUploader(t, st, put, nil)
	path = writeSegment(t, dir, "demo", "seg.mp4", 128)
	put.fn = func(context.Context, string, io.Reader, int64) error {
		f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
		if err != nil {
			return err
		}
		defer f.Close()
		_, err = f.Write(make([]byte, 8))
		return err
	}

	runTarget(t, u, newTarget("demo", 7, path, 128, false))

	if _, blocked := u.gate.backoffBlocked(archiveKey("demo", 7)); blocked {
		t.Error("백오프가 등록됐다 — [2](e) 두 분기는 미등록 고정이다(C10)")
	}
}

// ㉒ 로그 계약 — 연속 보류의 deferred_streak 이 로그 필드로 드러난다.
// 임계를 넘어도 새 로그 이름을 만들지 않는다(N-3 관측안).
func TestDeferredStreakAppearsInLog(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{fn: func(context.Context, string, io.Reader, int64) error {
		return errors.New("항상 실패")
	}}
	u, cap, dir := newWorkerUploader(t, st, put, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 128)
	target := newTarget("demo", 7, path, 128, true)

	// 매 회차: PUT 시점에는 장부와 크기가 같고(그래서 [3]까지 간다) 마지막 시도에서
	// 파일이 자란다(그래서 재측정이 불일치를 본다). 회차 시작에 크기를 되돌려 같은
	// 상황을 6번 반복한다. 백오프는 접수 게이트에서 보므로 여기서는 회차를 이어 돌린다.
	calls := 0
	put.fn = func(context.Context, string, io.Reader, int64) error {
		calls++
		if calls%4 == 0 {
			f, _ := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
			_, _ = f.Write(make([]byte, 16))
			_ = f.Close()
		}
		return errors.New("항상 실패")
	}
	for i := 1; i <= 6; i++ {
		if err := os.Truncate(path, 128); err != nil {
			t.Fatal(err)
		}
		runTarget(t, u, target)
	}

	recs := cap.find("mark_failed_deferred")
	if len(recs) != 6 {
		t.Fatalf("mark_failed_deferred = %d건, want 6건 (%s)", len(recs), cap.dump())
	}
	for i, r := range recs {
		if got, want := r.attrs["deferred_streak"], int64(i+1); got != want {
			t.Errorf("%d번째 deferred_streak = %v, want %v", i+1, got, want)
		}
	}
	// 임계(5)를 넘어도 새 이름의 로그를 만들지 않는다.
	for _, name := range []string{"deferred_streak_exceeded", "mark_failed_deferred_warn"} {
		if n := cap.count(name); n != 0 {
			t.Errorf("%s 가 %d건 나왔다 — 임계 초과는 필드로만 드러낸다", name, n)
		}
	}
}

// tq-4 / ⑯·⑱ — f.Stat 오류 3분기. 어디서 나든 nil 역참조 없이 neutral 로 끝나고,
// 마킹이 없어 행은 pending 으로 남는다.
//
// 이음매를 쓰는 이유: 유효한 fd 의 fstat 은 EBADF 말고는 사실상 실패하지 않는데,
// [2](b)의 pre 는 Open 과 Stat 사이에 테스트가 낄 틈이 없고 [3]의 fd 는 테스트에
// 노출되지 않는다. 셋 다 죽은 코드로 두는 것보다 이 필드 하나가 낫다.
func TestStatFailureNeverMarksAndNeverPanics(t *testing.T) {
	cases := []struct {
		name string
		// failOn 은 몇 번째 Stat 호출을 실패시킬지다.
		//   1 = [2](b) PUT 전 / 2 = [2](e) PUT 후 재측정 / 5 = [3] 꼬리 재측정
		failOn   int
		putFails bool
		isTail   bool
		wantStat string
		// wantBackoff 는 M-1 표의 처우다. [3] 재측정 실패만 등록한다 —
		// 그 행은 방금 PUT 을 RetryMax 회 소진했으므로, 미등록이면 그 연발이 매 회차
		// 되풀이되는 핫루프가 되고 마킹이 없어 자연 종료 조건도 없다.
		// [2](b)·[2](e)는 미등록 고정이다(C10).
		wantBackoff bool
	}{
		{"PUT_전", 1, false, false, "pre", false},
		{"PUT_후_재측정", 2, false, false, "post", false},
		{"꼬리_재측정", 5, true, true, "tail_recheck", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			st := &fakeUploadStore{}
			put := &fakePutter{}
			if c.putFails {
				put.fn = func(context.Context, string, io.Reader, int64) error {
					return errors.New("항상 실패")
				}
			}
			u, cap, dir := newWorkerUploader(t, st, put, nil)
			path := writeSegment(t, dir, "demo", "seg.mp4", 128)

			var calls int
			real := u.statFile
			u.statFile = func(f *os.File) (fs.FileInfo, error) {
				calls++
				if calls == c.failOn {
					return nil, syscall.EBADF
				}
				return real(f)
			}

			got := runTarget(t, u, newTarget("demo", 7, path, 128, c.isTail))

			if got != outcomeNeutral {
				t.Errorf("outcome = %v, want neutral", got)
			}
			rec := cap.one(t, "upload_stat_failed")
			if rec.attrs["stage"] != c.wantStat {
				t.Errorf("stage = %v, want %q", rec.attrs["stage"], c.wantStat)
			}
			up, fl := st.markCalls()
			if len(up)+len(fl) != 0 {
				t.Errorf("마킹 %d건, want 0건 — 판정 재료가 없으면 pending 으로 둔다", len(up)+len(fl))
			}
			select {
			case res := <-u.results:
				t.Errorf("Result 가 왔다: %+v", res)
			default:
			}
			if _, blocked := u.gate.backoffBlocked(archiveKey("demo", 7)); blocked != c.wantBackoff {
				t.Errorf("백오프 등록 = %v, want %v", blocked, c.wantBackoff)
			}
		})
	}
}

// tq-7 — [0] 대상 검증은 브레이커 판정보다 앞이다.
// 순서가 뒤집히면 검증도 못 통과할 행이 HalfOpen 의 단 한 번뿐인 probe 를 태워
// 실제 복구 판정을 계속 미룬다.
func TestInvalidTargetDoesNotConsumeHalfOpenProbe(t *testing.T) {
	u, _, dir := newWorkerUploader(t, &fakeUploadStore{}, &fakePutter{}, func(o *Options) {
		o.CircuitMax = 1
		o.CircuitCooldown = time.Millisecond
	})
	ok := writeSegment(t, dir, "demo", "seg.mp4", 64)

	u.brk.of(index.AxisArchive).record(outcomeHard, errors.New("boom"))
	time.Sleep(5 * time.Millisecond)
	if got := u.brk.of(index.AxisArchive).current(); got != circuitHalfOpen {
		t.Fatalf("준비 실패: state = %v, want HalfOpen", got)
	}

	// bytes 가 0 이라 [0] 에서 거부된다.
	bad := newTarget("demo", 7, ok, 0, false)
	if got := runTarget(t, u, bad); got != outcomeNeutral {
		t.Fatalf("outcome = %v, want neutral", got)
	}

	if u.brk.of(index.AxisArchive).probeUsed {
		t.Error("검증 실패 행이 probe 를 소비했다")
	}
	if !u.brk.of(index.AxisArchive).allowWork(archiveKey("demo", 8)) {
		t.Error("정상 행이 probe 를 쓰지 못한다 — 반열림 복구 판정이 미뤄진다")
	}
}

// tq-8 — 통지 채널이 가득 차면 판정을 버린다. 여기서 막히면 워커가 서고,
// 종료가 무기한 대기가 되어 G18″ 이 무너진다. 커서 동기화는 최선 노력이다(L7).
func TestSendResultDropsWhenChannelFull(t *testing.T) {
	u, cap, dir := newWorkerUploader(t, &fakeUploadStore{}, &fakePutter{}, func(o *Options) {
		o.ResultBufLen = 1
	})
	path := writeSegment(t, dir, "demo", "seg.mp4", 128)
	u.results <- Result{StreamID: "other", Seq: 1, State: index.UploadStateUploaded} // 자리를 미리 채운다

	done := make(chan outcome, 1)
	go func() { done <- runTarget(t, u, newTarget("demo", 7, path, 128, false)) }()

	select {
	case got := <-done:
		if got != outcomeSuccess {
			t.Errorf("outcome = %v, want success — 통지 실패가 업로드 결과를 바꾸면 안 된다", got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("통지에서 막혔다 — 워커가 서고 종료가 무기한 대기가 된다")
	}
	cap.one(t, "upload_mark_skipped")
}

// P1 — 재시도 소진 → hard 분류 → 브레이커 open 까지의 종단간 경로.
// hard 를 soft 로 뭉개면 설정이 통째로 틀린 상황에서도 전역 차단이 걸리지 않아
// 조각마다 RetryMax 회 PUT 을 계속 태운다.
func TestHardFailureOpensCircuitEndToEnd(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{fn: func(context.Context, string, io.Reader, int64) error {
		return fmt.Errorf("put: %w", statusErr{403}) // 자격증명·권한 문제 = hard
	}}
	u, cap, dir := newWorkerUploader(t, st, put, func(o *Options) { o.CircuitMax = 1 })
	path := writeSegment(t, dir, "demo", "seg.mp4", 128)

	// 워커가 실제로 하는 일 그대로 — 처리 후 결과를 브레이커에 반영한다.
	u.runJob(job{target: newTarget("demo", 7, path, 128, false), origin: OriginSweep, sweepRound: 1})

	rec := cap.one(t, "upload_failed")
	if rec.attrs["err_class"] != "hard" {
		t.Errorf("err_class = %v, want hard", rec.attrs["err_class"])
	}
	if got := u.brk.of(index.AxisArchive).current(); got != circuitOpen {
		t.Fatalf("브레이커 = %v, want Open — hard 종결이 streak 에 안 잡혔다", got)
	}
	cap.one(t, "circuit_open")

	// 반대 방향도 고정한다: 미상 오류는 soft 라 브레이커를 열지 않는다.
	softPut := &fakePutter{fn: func(context.Context, string, io.Reader, int64) error {
		return errors.New("connection reset by peer")
	}}
	u2, cap2, dir2 := newWorkerUploader(t, &fakeUploadStore{}, softPut, func(o *Options) { o.CircuitMax = 1 })
	p2 := writeSegment(t, dir2, "demo", "seg.mp4", 128)
	u2.runJob(job{target: newTarget("demo", 7, p2, 128, false), origin: OriginSweep, sweepRound: 1})
	if got := u2.brk.of(index.AxisArchive).current(); got != circuitClosed {
		t.Errorf("브레이커 = %v, want Closed — 미상 오류를 hard 로 오분류하면 정상 트래픽까지 막는다", got)
	}
	if n := cap2.count("circuit_open"); n != 0 {
		t.Errorf("circuit_open = %d건, want 0", n)
	}
}

// OpenCircuit 은 기동 시 자격증명을 얻지 못했을 때 밖에서 거는 전역 차단이다.
// 열린 뒤에는 접수 자체가 거부되어야 PUT 이 한 건도 나가지 않는다.
func TestOpenCircuitRejectsEnqueue(t *testing.T) {
	u, cap, dir := newWorkerUploader(t, &fakeUploadStore{}, &fakePutter{}, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 128)

	u.OpenCircuit("credentials_unavailable")

	if got := u.brk.of(index.AxisArchive).current(); got != circuitOpen {
		t.Fatalf("브레이커 = %v, want Open", got)
	}
	if got := u.enqueue(newTarget("demo", 7, path, 128, false), OriginSweep); got != EnqueueRejected {
		t.Errorf("enqueue = %v, want Rejected", got)
	}
	// 브레이커는 축별 3개이고 OpenCircuit 은 셋을 전부 연다(설계 5.5.3 · 계획 4.5) —
	// 자격증명 부재는 축과 무관한 사정이다. 로그도 축마다 하나씩, axis 라벨을 달고 나온다.
	opened := cap.find("circuit_open")
	if len(opened) != len(axisAll) {
		t.Fatalf("circuit_open 로그 = %d건, want %d건 (축마다 1건)", len(opened), len(axisAll))
	}
	seen := map[any]bool{}
	for _, rec := range opened {
		seen[rec.attrs["axis"]] = true
	}
	for _, a := range axisAll {
		if !seen[a.String()] {
			t.Errorf("axis=%s 의 circuit_open 이 없다", a)
		}
	}

	// 비활성 업로더에서는 아무 일도 일어나지 않는다.
	Disabled(newLogCapture().logger()).OpenCircuit("credentials_unavailable")
}

// tq-11 — 재시도 백오프 대기는 workerStop **단독**으로도 깨져야 한다.
// putCtx 만 보면 종료 4단계(close(workerStop))가 백오프를 못 깨고,
// 5단계 putCancel 까지 유예가 통째로 소모된다.
func TestRetryBackoffWakesOnWorkerStopAlone(t *testing.T) {
	put := &fakePutter{fn: func(context.Context, string, io.Reader, int64) error {
		return errors.New("항상 실패")
	}}
	u, _, dir := newWorkerUploader(t, &fakeUploadStore{}, put, func(o *Options) {
		o.RetryBase = 30 * time.Second // 못 깨우면 이만큼 붙잡힌다
	})
	path := writeSegment(t, dir, "demo", "seg.mp4", 128)

	done := make(chan outcome, 1)
	go func() {
		done <- u.processTarget(job{target: newTarget("demo", 7, path, 128, false), origin: OriginSweep})
	}()
	waitFor(t, func() bool { return len(put.putCalls()) == 1 }, "첫 PUT 시도")

	close(u.workerStop) // putCtx 는 살아 있다

	select {
	case got := <-done:
		if got != outcomeShutdown {
			t.Errorf("outcome = %v, want shutdown", got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("workerStop 만으로는 백오프 대기가 깨지지 않는다")
	}
}

// tq-12 — 마킹까지 성공한 행은 백오프에서 풀린다.
// 풀지 않으면 정상 복구된 행이 지수 간격을 계속 짊어진다.
func TestSuccessfulUploadClearsBackoff(t *testing.T) {
	u, _, dir := newWorkerUploader(t, &fakeUploadStore{}, &fakePutter{}, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 128)
	k := archiveKey("demo", 7)

	u.gate.registerFailure(k)
	if _, blocked := u.gate.backoffBlocked(k); !blocked {
		t.Fatal("준비 실패: 백오프가 걸리지 않았다")
	}

	if got := runTarget(t, u, newTarget("demo", 7, path, 128, false)); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success", got)
	}

	if _, blocked := u.gate.backoffBlocked(k); blocked {
		t.Error("성공 후에도 백오프가 남아 있다")
	}
}

// tq-13 — 게이트 순서 계약: in-flight 는 **마지막**에 잡는다.
// 앞선 게이트(격리·백오프)가 거부하면서 in-flight 를 잡아 두면 그 행은 아무 작업도
// 없는데 in-flight 로 남아 이후 모든 접수가 조용히 거부된다.
func TestRejectingGatesDoNotHoldInflight(t *testing.T) {
	u, _, dir := newWorkerUploader(t, &fakeUploadStore{}, &fakePutter{}, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 128)

	t.Run("백오프_거부", func(t *testing.T) {
		k := archiveKey("demo", 1)
		u.gate.registerFailure(k)
		if got := u.enqueue(newTarget("demo", 1, path, 128, false), OriginSweep); got != EnqueueRejected {
			t.Fatalf("enqueue = %v, want Rejected", got)
		}
		if !u.gate.acquireInflight(k) {
			t.Error("거부하면서 in-flight 를 잡아 뒀다")
		}
	})

	t.Run("격리_거부", func(t *testing.T) {
		k := archiveKey("demo", 2)
		u.gate.quarantine(k)
		if got := u.enqueue(newTarget("demo", 2, path, 128, false), OriginSweep); got != EnqueueRejected {
			t.Fatalf("enqueue = %v, want Rejected", got)
		}
		if !u.gate.acquireInflight(k) {
			t.Error("거부하면서 in-flight 를 잡아 뒀다")
		}
	})
}
