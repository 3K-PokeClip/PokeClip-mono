package upload

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 24시간 회차(약 21,600행) 복원이 다른 회차와 종료를 막지 않는가(c10b 확인 패스 2회차 cx H 둘). 행은 장부에 직접 놓는다 — 시험대의
// add 는 행마다 실물을 판독해 2만 행이면 느리다.

const longSessionRows = 21_600

// ledgerRow 는 원천 판독 없이 장부에 놓는 귀속 행이다(원래 사실 칸 없음 — 고정 · 지나가기 행).
func (b *restoreBench) ledgerRow(seq int64, path string, size int64, playbackState index.UploadState) {
	row := index.PlaybackChainRow{Seq: seq, SessionID: benchSession, LocalPath: path, Bytes: &size,
		S3Key: fmt.Sprintf("streams/%s/2026-11-04/05/seg_%06d.m4s", benchStream, seq), UploadState: index.UploadStatePending,
		PlaybackS3Key: segKeyOf(b.t, benchStream, seq), PlaybackUploadState: playbackState, DurationMS: 4000}
	b.ledger.mu.Lock()
	b.ledger.rows[seq] = row
	b.ledger.mu.Unlock()
}

// 표 갱신이 다른 회차의 RestoredLink 를 막지 않는다(cx H 1 재현 입력: 0 uploaded → 1–21600 원천 부재 pending → 21601 uploaded →
// 21602 pending · 두 번째 복원이 앞 무리를 다시 접는다). 문턱 = T-14 인덱서 통로 5ms. 잡는 결함: 공용 뮤텍스 안에서 링크마다 표
// 전체를 훑어(O(n²)) 2.45–2.72초 멈춘다.
func TestRestoreLongSessionDoesNotStallOtherSessions(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(0, 1, 600*time.Second, up)
	var passed []int64
	for i := int64(1); i <= longSessionRows; i++ {
		b.ledgerRow(i, filepath.Join(b.dir, benchStream, fmt.Sprintf("gone%d.mp4", i)), 143_007, pending)
		passed = append(passed, i)
	}
	step := b.step()
	b.contiguous(longSessionRows+1, 2, 600*time.Second+(longSessionRows+1)*step, up, pending)
	live := b.liveChain(passed...)
	b.uploadAt(live, 0, longSessionRows+1)
	b.u.sessions.setRestored("S-other", restoredLink{link: playback.Link{Seq: 1, SessionID: "S-other"}})
	b.restore()
	b.queued()

	// 긴 접기는 진행 신호를 남긴다 — 정지와 구별된다(c10b 확인 패스 2회차 cc 부수 3). 한 번 복원 × 21,600행 ÷ 1,000.
	perFold := longSessionRows / restoreProgressEvery
	if n := b.cap.count("chain_restore_progress"); n < perFold {
		t.Errorf("첫 복원 chain_restore_progress %d줄, want ≥ %d", n, perFold)
	}
	// 벽시계 문턱은 기계 부하(OS 스케줄링 · GC 정지)에 흔들린다 — 부하 평균 90–120 에서 경합 없는 잠금 탐침도 최대 42ms 를 봤다.
	// 그래서 같은 재접기를 몇 번 재어 한 번이라도 문턱 안이면 통과로 본다. 잡는 결함은 매번 초 단위로 멈추므로 늘 실패한다.
	var worsts []time.Duration
	for range restoreStallAttempts {
		before := b.cap.count("chain_restore_progress")
		worst := worstRestoredLinkDuring(b, "S-other", b.restore)
		if n := b.cap.count("chain_restore_progress") - before; n < perFold {
			t.Errorf("재접기 chain_restore_progress %d줄, want ≥ %d — 앞 무리를 다시 접지 않았다", n, perFold)
		}
		worsts = append(worsts, worst)
		if worst <= restoreStallLimit {
			break
		}
	}
	t.Logf("다른 회차 RestoredLink 최대 지연(회차별) %v", worsts)
	if best := slices.Min(worsts); best > restoreStallLimit {
		t.Errorf("다른 회차 RestoredLink 최대 지연 %v(%d번 모두 문턱 밖), want ≤ %v", worsts, len(worsts), restoreStallLimit)
	}
}

const (
	// restoreStallLimit 은 T-14 인덱서 통로 문턱이다.
	restoreStallLimit = 5 * time.Millisecond
	// restoreStallAttempts 는 부하 잡음을 거르려 같은 재접기를 재는 최대 횟수다.
	restoreStallAttempts = 3
)

// worstRestoredLinkDuring 은 run 이 도는 동안 회차 sessionID 의 RestoredLink 를 되풀이해 부르고 가장 긴 한 번을 돌려준다.
func worstRestoredLinkDuring(b *restoreBench, sessionID string, run func()) time.Duration {
	var worst atomic.Int64
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			select {
			case <-stop:
				return
			default:
			}
			start := time.Now()
			b.u.RestoredLink(sessionID)
			if d := int64(time.Since(start)); d > worst.Load() {
				worst.Store(d)
			}
			time.Sleep(100 * time.Microsecond)
		}
	}()
	run()
	close(stop)
	<-done
	return time.Duration(worst.Load())
}

// 긴 로컬 접기 중에도 Shutdown 이 기본 유예(4s + 2s) 안에 끝난다 — 접기가 행 사이에서 멈춤 신호를 본다(cx H 2 재현 입력: 0 mtxi
// 닻 → 1–21599 고정 uploaded → 21600 pending · 로컬 조각당 4.6MB). 잡는 결함: 취소된 뒤에도 마지막 행까지 읽어 16–24초 걸린다.
func TestShutdownStopsLongRestoreFold(t *testing.T) {
	raw, err := os.ReadFile(fixture4sPath)
	if err != nil {
		t.Fatal(err)
	}
	root, dir := newRoot(t)
	b := &restoreBench{t: t, store: &fakeUploadStore{}, ledger: &fakeLedger{rows: map[int64]index.PlaybackChainRow{}},
		objs: newFakeObjects(), raw: raw, srcs: map[int64][]byte{}, dir: dir, cap: newLogCapture()}
	opt := DefaultOptions(root, dir)
	opt.Producer = playback.Remuxer{}
	b.u = New(ledgerStore{b.store, b.ledger}, b.objs, opt, b.cap.logger())
	b.contiguous(0, 1, 600*time.Second, up)
	b.setPlaybackObject(0, 600*time.Second)
	pinned := filepath.Join(dir, benchStream, "pinned.mp4")
	big := paddedSegment(withoutMtxi(t, raw), restoreMemoryRowBytes)
	if err := os.WriteFile(pinned, big, 0o644); err != nil {
		t.Fatal(err)
	}
	for i := int64(1); i < longSessionRows; i++ {
		p := filepath.Join(dir, benchStream, fmt.Sprintf("p%d.mp4", i))
		if err := os.Link(pinned, p); err != nil {
			t.Fatal(err)
		}
		b.ledgerRow(i, p, int64(len(big)), up)
	}
	b.ledgerRow(longSessionRows, filepath.Join(dir, benchStream, "p1.mp4"), int64(len(big)), pending)
	b.u.Start(context.Background())
	b.u.RequestUpload(index.UploadTarget{StreamID: benchStream, Axis: index.AxisPlayback, Seq: longSessionRows,
		SessionID: benchSession, PosUnknown: true})
	// 접기가 한창일 때 멈춘다 — 고정 대기는 부하 아래서 접기 앞 단계(뒤로 읽기)에 걸려 접기 안의 멈춤 확인을 재지 못했다.
	// 수정 전 코드에는 진행 신호가 없어 기다림이 시한까지 간다 — 그래도 Shutdown 을 재어 RED 수치를 남긴다(waitFor 는 Fatal 이다).
	deadline := time.Now().Add(time.Minute)
	for b.cap.count("chain_restore_progress") == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if b.cap.count("chain_restore_progress") == 0 {
		t.Errorf("1분 안에 chain_restore_progress 가 없다 — 접기 중 Shutdown 을 재지 못했다")
	}

	start := time.Now()
	shut := make(chan struct{})
	go func() { b.u.Shutdown(); close(shut) }()
	select {
	case <-shut:
	case <-time.After(60 * time.Second):
		t.Fatal("Shutdown 이 60초 안에 끝나지 않았다")
	}

	t.Logf("Shutdown %v", time.Since(start))
	if d := time.Since(start); d >= 6*time.Second {
		t.Errorf("Shutdown %v, want < 6s(기본 유예 4s + 2s)", d)
	}
}
