package upload

// 스위퍼 arm 수명 검증(POK-168 M1 — 수명 계약 3 · f6p ⓒⓓⓔ, F-42 수용 기준).

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// armStore 는 스위프 조회 횟수만 센다.
type armStore struct {
	mu      sync.Mutex
	queries int
}

func (s *armStore) PendingUploads(context.Context, float64, int, index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.queries++
	return nil, index.SweepCursor{}, nil
}
func (s *armStore) MarkUploaded(context.Context, string, int64, int64) (bool, error) {
	return true, nil
}
func (s *armStore) MarkFailed(context.Context, string, int64, int64) (bool, error) { return true, nil }
func (s *armStore) MarkInitUploaded(context.Context, string, []byte) (bool, error) { return true, nil }
func (s *armStore) CountBacklog(context.Context) (int64, int64, int64, error)      { return 0, 0, 0, nil }

func (s *armStore) count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.queries
}

func newArmUploader(t *testing.T, armFallback time.Duration) (*Uploader, *armStore, *logCapture) {
	t.Helper()
	st := &armStore{}
	logs := newLogCapture()
	opt := DefaultOptions(nil, "/recordings")
	opt.SweepEvery = 10 * time.Millisecond
	opt.ArmFallback = armFallback
	u := New(st, &fakePutter{}, opt, logs.logger())
	return u, st, logs
}

// f6p ⓒ — arm 전에는 스위프가 한 번도 돌지 않고, arm 후에 돈다.
func TestSweeperWaitsForArm(t *testing.T) {
	u, st, _ := newArmUploader(t, 0) // 폴백 없음 — arm 신호만 기다린다
	u.Start(context.Background())
	defer u.Shutdown()

	time.Sleep(60 * time.Millisecond) // SweepEvery 6회분
	if got := st.count(); got != 0 {
		t.Fatalf("arm 전에 스위프가 %d회 돌았다(0 기대) — 커서 선점 경로 부활", got)
	}

	u.ArmSweeper()
	deadline := time.Now().Add(2 * time.Second)
	for st.count() == 0 {
		if time.Now().After(deadline) {
			t.Fatal("arm 후에도 스위프가 돌지 않는다")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// f6p ⓓ — ArmSweeper 는 멱등이고(두 번 불러도 panic 없음), Start 전 호출도 안전하며,
// arm 전 Shutdown 이 영구 대기하지 않는다.
func TestArmIdempotentAndUnarmedShutdownSafe(t *testing.T) {
	u, _, _ := newArmUploader(t, 0)
	u.ArmSweeper() // Start 전 — armCh 는 생성자에서 만들어져 있다
	u.ArmSweeper() // 멱등(Once)

	u2, _, _ := newArmUploader(t, 0)
	u2.Start(context.Background())

	done := make(chan struct{})
	go func() {
		u2.Shutdown() // 미arm — sweepCancel 이 waitArm 을 깨워야 한다
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("미arm Shutdown 이 영구 대기한다(F-42 위반)")
	}
}

// f6p ⓔ — 폴백 경과 시 무조건 arm + sweeper_arm_fallback WARN.
func TestArmFallbackFires(t *testing.T) {
	u, st, logs := newArmUploader(t, 20*time.Millisecond)
	u.Start(context.Background())
	defer u.Shutdown()

	deadline := time.Now().Add(2 * time.Second)
	for st.count() == 0 {
		if time.Now().After(deadline) {
			t.Fatal("폴백 경과 후에도 스위프가 돌지 않는다")
		}
		time.Sleep(5 * time.Millisecond)
	}
	if logs.count("sweeper_arm_fallback") != 1 {
		t.Fatalf("sweeper_arm_fallback WARN 이 %d건이다(1건 기대)", logs.count("sweeper_arm_fallback"))
	}
}

// Disabled 업로더의 ArmSweeper 는 no-op 이다(패닉·상태 변화 없음).
func TestArmSweeperDisabledNoop(t *testing.T) {
	u := Disabled(newLogCapture().logger())
	u.ArmSweeper()
	u.Shutdown()
}
