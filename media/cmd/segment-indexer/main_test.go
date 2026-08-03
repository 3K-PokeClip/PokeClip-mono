package main

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelError + 1}))
}

// 업로더 조립 3분기. 기동을 거부하는 것은 "설정 자체가 틀렸다" 하나뿐이다.
func TestNewUploaderBranches(t *testing.T) {
	root := t.TempDir()

	t.Run("S3_BUCKET_이_비면_Disabled", func(t *testing.T) {
		cfg := config.Config{SegmentRoot: root, Upload: upload.DefaultOptions(nil, root)}
		up, err := newUploader(context.Background(), cfg, nil, nil, discardLogger())
		if err != nil {
			t.Fatalf("기동 실패: %v", err)
		}
		// 비활성 업로더는 통지 채널이 nil 이라 select 에서 절대 선택되지 않는다.
		if up.Results() != nil {
			t.Error("Results() != nil — 비활성인데 select 가 이 케이스를 고를 수 있다")
		}
		// 미기동 상태이므로 Shutdown 도 안전하다.
		up.Shutdown()
	})

	t.Run("자격증명이_없으면_브레이커를_연_채_기동한다", func(t *testing.T) {
		// SDK 가 자격증명을 찾지 못하게 못 박는다.
		t.Setenv("AWS_ACCESS_KEY_ID", "")
		t.Setenv("AWS_SECRET_ACCESS_KEY", "")
		t.Setenv("AWS_EC2_METADATA_DISABLED", "true")
		t.Setenv("AWS_SHARED_CREDENTIALS_FILE", filepath.Join(t.TempDir(), "none"))
		t.Setenv("AWS_CONFIG_FILE", filepath.Join(t.TempDir(), "none"))

		r, err := os.OpenRoot(root)
		if err != nil {
			t.Fatal(err)
		}
		defer r.Close()

		cfg := config.Config{
			SegmentRoot: root,
			S3:          upload.S3Options{Bucket: "pokeclip-media-demo-2557", Region: "ap-northeast-2"},
			Upload:      upload.DefaultOptions(r, root),
		}
		up, err := newUploader(context.Background(), cfg, r, nil, discardLogger())
		if err != nil {
			t.Fatalf("기동을 거부했다 — 자격증명 부재는 기동 실패가 아니다: %v", err)
		}
		if up.Results() == nil {
			t.Error("Results() == nil — 활성 업로더여야 한다")
		}
		up.Shutdown() // 미기동 Shutdown 안전
	})
}

// os.OpenRoot 실패는 기동 실패다. 루트를 못 열면 업로더는 어떤 파일도 열 수 없다.
func TestOpenSegmentRootFailsFast(t *testing.T) {
	if _, err := os.OpenRoot(filepath.Join(t.TempDir(), "없는디렉토리")); err == nil {
		t.Fatal("없는 루트를 열었다")
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
