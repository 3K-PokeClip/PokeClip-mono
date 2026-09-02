package main

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelError + 1}))
}

// stubStore 는 마킹만 기록하는 UploadStore 다. 워커가 어디까지 갔는지 관측하는 창이다.
type stubStore struct {
	mu     sync.Mutex
	failed []int64
}

func (s *stubStore) MarkUploaded(context.Context, string, int64, int64) (bool, error) {
	return true, nil
}
func (s *stubStore) MarkFailed(_ context.Context, _ string, seq, _ int64) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.failed = append(s.failed, seq)
	return true, nil
}
func (s *stubStore) PendingUploads(_ context.Context, _ float64, _ int, after index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
	return nil, after, nil
}
func (s *stubStore) MarkInitUploaded(context.Context, string, []byte) (bool, error) {
	return true, nil
}
func (s *stubStore) CountBacklog(context.Context) (int64, int64, int64, error) { return 0, 0, 0, nil }

func (s *stubStore) failedSeqs() []int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]int64(nil), s.failed...)
}

// noCredentialsEnv 는 SDK 가 어떤 자격증명도 찾지 못하게 못 박는다.
// CI 가 OIDC 로 자격증명을 주입하면 조용히 반대 분기를 타므로 파일·IMDS·env 를 전부 막는다.
func noCredentialsEnv(t *testing.T) {
	t.Helper()
	for _, k := range []string{
		"AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
		"AWS_PROFILE", "AWS_ROLE_ARN", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_CONTAINER_CREDENTIALS_FULL_URI",
		"AWS_CONTAINER_CREDENTIALS_RELATIVE_URI",
	} {
		t.Setenv(k, "")
	}
	t.Setenv("AWS_EC2_METADATA_DISABLED", "true")
	t.Setenv("AWS_SHARED_CREDENTIALS_FILE", filepath.Join(t.TempDir(), "none"))
	t.Setenv("AWS_CONFIG_FILE", filepath.Join(t.TempDir(), "none"))
}

func uploadCfg(t *testing.T, root *os.Root, dir string) config.Config {
	t.Helper()
	opt := upload.DefaultOptions(nil, dir)
	opt.SweepEvery = time.Hour // 스위퍼가 판정을 흔들지 않게 한다
	return config.Config{
		SegmentRoot: dir,
		// 엔드포인트를 닫힌 포트로 둔다. 어떤 경우에도 실제 S3 로 나가지 않는다.
		S3: upload.S3Options{
			Bucket: "pokeclip-media-demo-2557", Region: "ap-northeast-2",
			Endpoint: "http://127.0.0.1:1", ForcePathStyle: true,
		},
		Upload: opt,
	}
}

// 업로더 조립 3분기. 기동을 거부하는 것은 "설정 자체가 틀렸다" 하나뿐이다.
func TestNewUploaderBranches(t *testing.T) {
	t.Run("S3_BUCKET_이_비면_Disabled", func(t *testing.T) {
		dir := t.TempDir()
		cfg := config.Config{SegmentRoot: dir, Upload: upload.DefaultOptions(nil, dir)}
		up, err := newUploader(context.Background(), cfg, nil, nil, discardLogger())
		if err != nil {
			t.Fatalf("기동 실패: %v", err)
		}
		// 비활성 업로더는 통지 채널이 nil 이라 select 에서 절대 선택되지 않는다.
		if up.Results() != nil {
			t.Error("Results() != nil — 비활성인데 select 가 이 케이스를 고를 수 있다")
		}
		up.Start(context.Background())
		if up.RequestUpload(index.UploadTarget{StreamID: "s", Axis: index.AxisArchive, Seq: 1, Bytes: 1}) {
			t.Error("비활성인데 접수됐다")
		}
		up.Shutdown() // 미기동 Shutdown 안전
	})

	// 자격증명을 못 얻으면 기동은 진행하되 **브레이커를 연 채로** 시작한다.
	// 열지 않으면 만료된 환경에서 조각마다 PUT 을 RetryMax 회씩 태운다.
	t.Run("자격증명이_없으면_접수가_차단된다", func(t *testing.T) {
		noCredentialsEnv(t)
		root, dir := openTempRoot(t)
		st := &stubStore{}

		up, err := newUploader(context.Background(), uploadCfg(t, root, dir), root, st, discardLogger())
		if err != nil {
			t.Fatalf("기동을 거부했다 — 자격증명 부재는 기동 실패가 아니다: %v", err)
		}
		up.Start(context.Background())
		defer up.Shutdown()

		target := newLocalTarget(t, dir, "demo", 7)
		if up.RequestUpload(target) {
			t.Error("브레이커가 열려 있어야 하는데 접수됐다 — OpenCircuit 이 빠졌다")
		}
	})

	// 자격증명이 있으면 접수되고, 워커가 실제로 그 대상을 집는다.
	// 대상 파일이 없으므로 네트워크로 나가지 않고 MarkFailed 로 끝난다 —
	// 그 호출이 Root·SegmentRoot 가 제대로 주입됐다는 증거다(둘 중 하나라도 비면
	// 열기 단계에서 panic 하거나 upload_target_rejected 로 빠져 마킹이 없다).
	t.Run("자격증명이_있으면_접수되고_대상이_처리된다", func(t *testing.T) {
		t.Setenv("AWS_ACCESS_KEY_ID", "test")
		t.Setenv("AWS_SECRET_ACCESS_KEY", "test")
		t.Setenv("AWS_SESSION_TOKEN", "test")
		t.Setenv("AWS_EC2_METADATA_DISABLED", "true")
		root, dir := openTempRoot(t)
		st := &stubStore{}

		// 경로 검증용 문자열을 일부러 틀리게 넣는다. main 이 cfg.SegmentRoot 로 정규화하지
		// 않으면 [0] 검증이 루트 이탈로 거부해 마킹까지 가지 못한다.
		cfg := uploadCfg(t, root, dir)
		cfg.Upload.SegmentRoot = "/틀린루트"

		up, err := newUploader(context.Background(), cfg, root, st, discardLogger())
		if err != nil {
			t.Fatalf("기동 실패: %v", err)
		}
		up.Start(context.Background())
		defer up.Shutdown()

		if !up.RequestUpload(newLocalTarget(t, dir, "demo", 7)) {
			t.Fatal("자격증명이 있는데 접수가 거부됐다")
		}
		deadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(deadline) {
			if len(st.failedSeqs()) == 1 {
				return
			}
			time.Sleep(5 * time.Millisecond)
		}
		t.Fatalf("워커가 대상을 처리하지 않았다 (MarkFailed=%v) — Root·SegmentRoot 주입을 확인하라",
			st.failedSeqs())
	})

	// 설정 자체가 틀리면 기동 실패다. 삼키면 업로더 없이 조용히 도는 프로세스가 된다.
	t.Run("SDK_설정이_틀리면_기동_실패", func(t *testing.T) {
		noCredentialsEnv(t)
		t.Setenv("AWS_RETRY_MODE", "그런모드없음")
		root, dir := openTempRoot(t)

		_, err := newUploader(context.Background(), uploadCfg(t, root, dir), root, &stubStore{}, discardLogger())
		if err == nil {
			t.Fatal("에러가 없다 — NewS3Putter 의 오류를 삼켰다")
		}
	})
}

func openTempRoot(t *testing.T) (*os.Root, string) {
	t.Helper()
	dir := t.TempDir()
	r, err := os.OpenRoot(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = r.Close() })
	return r, dir
}

// newLocalTarget 은 [0] 검증을 통과하지만 실물이 없는 대상이다.
func newLocalTarget(t *testing.T, dir, streamID string, seq int64) index.UploadTarget {
	t.Helper()
	wall := time.Date(2026, 8, 3, 0, 0, 0, 0, time.UTC)
	return index.UploadTarget{
		StreamID: streamID, Axis: index.AxisArchive, Seq: seq,
		S3Key:     index.S3Key(streamID, seq, wall),
		LocalPath: filepath.Join(dir, streamID, "seg.mp4"),
		Bytes:     100,
	}
}

// tq-B6 — defer 등록 순서가 종료 순서를 정한다.
//
// defer 는 LIFO 다. up.Shutdown 은 반환 후 UploadStore 를 쓰지 않지만 **반환 전까지는**
// 쓰므로 pool.Close 보다 뒤에 등록돼야 하고, root.Close 도 마찬가지다(워커가 파일을 연다).
// 순서가 뒤집히면 닫힌 풀로 MarkUploaded 를 부르거나 닫힌 루트로 파일을 연다.
//
// run() 은 DB·워처가 있어야 돌아 단위 테스트가 불가능하다. 그래서 등록 순서 자체를
// 소스에서 확인한다 — 이 회귀는 "누가 defer 한 줄을 위로 옮기는" 형태로 오기 때문에
// 위치 검사만으로도 정확히 잡힌다.
func TestShutdownDeferRegistrationOrder(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	text := string(src)

	order := []string{"defer pool.Close()", "defer root.Close()", "defer up.Shutdown()"}
	prev := -1
	for _, want := range order {
		at := strings.Index(text, want)
		if at < 0 {
			t.Fatalf("%q 를 찾지 못했다 — 종료 절차가 사라졌거나 이름이 바뀌었다", want)
		}
		if at <= prev {
			t.Fatalf("%q 의 등록 위치가 앞선다. 등록 순서는 %v 여야 하고, "+
				"LIFO 이므로 실행은 그 역순(Shutdown -> root.Close -> pool.Close)이다", want, order)
		}
		prev = at
	}
}

// 업로더가 붙으면서 루프에 두 신호가 늘었다. 둘 다 **같은 select** 에서 처리돼야 한다 —
// 다른 고루틴에서 ApplyUploadResult·ReleaseHeldTails 를 부르면 D10 단일 호출자 규약이
// 깨져 커서가 경합한다.
//
// "보낸 것이 받아진다"가 관측점이다. 해당 case 를 지우면 송신이 영영 블록되어 죽는다.
func TestLoopConsumesUploaderSignals(t *testing.T) {
	f := newLoopFixture(t, false)
	results := make(chan upload.Result)
	holds := make(chan time.Time)
	f.deps.uploadResults = results
	f.deps.holdTicks = holds
	cancel := f.run()

	select {
	case results <- upload.Result{StreamID: "demo", Seq: 0, State: index.UploadStateUploaded}:
	case <-time.After(2 * time.Second):
		t.Fatal("루프가 업로드 결과를 받지 않는다")
	}
	select {
	case holds <- time.Now():
	case <-time.After(2 * time.Second):
		t.Fatal("루프가 보류 틱을 받지 않는다")
	}

	// 두 신호를 처리한 뒤에도 파일 감시 경로는 그대로 돈다.
	f.feedSegment("2026-07-25_10-00-00-000000.mp4")

	if err := f.stop(cancel); err != nil {
		t.Fatalf("loop() = %v, want nil", err)
	}
}
