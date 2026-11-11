package upload

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"sync"
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
	other := b.u.sessions.beginRestore("S-other")
	b.u.sessions.setRestored("S-other", other.gen, restoredLink{link: playback.Link{Seq: 1, SessionID: "S-other"}})
	b.u.sessions.endRestore("S-other")
	b.restore()
	b.queued()

	// 긴 접기는 진행 신호를 남긴다 — 정지와 구별된다(c10b 확인 패스 2회차 cc 부수 3). 한 번 복원 × 21,600행 ÷ 1,000.
	perFold := longSessionRows / restoreProgressEvery
	if n := b.cap.count("chain_restore_progress"); n < perFold {
		t.Errorf("첫 복원 chain_restore_progress %d줄, want ≥ %d", n, perFold)
	}
	// 벽시계 문턱은 기계 부하(OS 스케줄링 · GC 정지)에 흔들린다 — 부하 평균 90–120 에서 경합 없는 잠금 탐침도 최대 42ms 를 봤다.
	// 그래서 같은 재접기를 몇 번 재어 한 번이라도 문턱 안이면 통과로 본다. 잡는 결함은 매번 초 단위로 멈추므로 늘 실패한다.
	// 부하 의존 시험이다(PR 종결 r7 cc L4 — 부하 평균 58 의 전체 race 에서 3번 모두 문턱 밖 · 7–10 에서 3/3 통과). 재는 것(다른 회차
	// 조회 지연)이 곧 벽시계라 문턱을 부하에 맞춰 늘리면 그 자체를 바꾼다 — 고치지 않고 README 「테스트」 에 한계로 적었다.
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
	// restoreFoldStartWait 는 Shutdown 시험이 접기 시작을 기다리는 상한이다 — 넘으면 잴 수 없어 건너뛴다.
	restoreFoldStartWait = 3 * time.Minute
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
//
// 접기가 시작된 순간(restorePoint anchored)을 손잡이로 받아 그때 멈춘다 — 행 사이 멈춤 확인이 없으면 그 자리에서부터 21,600행을 다
// 읽으므로 결함이 그대로 드러난다. 그 앞의 뒤로 읽기(고정 행 21,599개)는 부하에 따라 길어진다 — 진행 신호(1,000행 접기)까지 1분을
// 기다리던 옛 전제는 부하 평균 58 에서 닿지 못해 재야 할 Shutdown(17.9ms) 대신 전제 미달을 실패로 냈다(PR 종결 r7 cc L4). 3분 안에
// 접기가 시작되지 않으면 잴 수 없으므로 건너뛴다(Skip — 실패가 아니다).
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
	folding := make(chan struct{})
	var once sync.Once
	b.u.sessions.pause = func(p restorePoint) { // Start 전에 둔다 — 기동 뒤 바꾸면 복원 고루틴과 경합이다
		if p == pointAnchored {
			once.Do(func() { close(folding) })
		}
	}
	b.u.Start(context.Background())
	b.u.RequestUpload(index.UploadTarget{StreamID: benchStream, Axis: index.AxisPlayback, Seq: longSessionRows,
		SessionID: benchSession, PosUnknown: true})
	select {
	case <-folding:
	case <-time.After(restoreFoldStartWait):
		b.u.Shutdown()
		t.Skipf("%v 안에 접기가 시작되지 않았다(뒤로 읽기가 부하로 길다) — 접기 중 Shutdown 을 잴 수 없다", restoreFoldStartWait)
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
