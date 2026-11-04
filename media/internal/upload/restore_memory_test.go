package upload

import (
	"encoding/binary"
	"os"
	"runtime"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 복원 메모리 상한(c10b 보안 검수 H): ③ 업로드가 길게 막혀 한 회차에 구멍 행이 수백 개 쌓인 뒤 복원하면, 읽은 ② 본문을 무리
// 끝까지 쥐어 GB 단위로 늘고 사이드카가 OOM 으로 죽는다(인덱서와 같은 프로세스 — 재기동 뒤 같은 구멍에서 다시 죽는다). 우리 송출
// (영상 8Mbps + 소리 6트랙)의 4초 조각은 약 4.6MB(실측 4.27–4.48MB) · 분당 15행이라, S3 장애 30분이면 구멍 450행 · 약 2GB 다.
// 시험은 실물 조각 뒤에 free 상자를 붙여 4.6MB 로 키운 행 restoreMemoryRows(기본 200 · 약 920MB)를 한 무리로 두고 복원하며 힙
// 최고치를 잰다. 잡는 결함: 본문을 무리 끝까지 쥐면 최고치가 행 수에 비례한다.

const restoreMemoryRowBytes = 4_600_000

// paddedSegment 는 실물 조각 src 뒤에 free 상자를 붙여 size 바이트로 키운 사본이다(최상위 상자가 입력을 빈틈없이 덮는다).
func paddedSegment(src []byte, size int) []byte {
	pad := size - len(src)
	out := make([]byte, len(src), size)
	copy(out, src)
	out = binary.BigEndian.AppendUint32(out, uint32(pad))
	out = append(out, "free"...)
	return append(out, make([]byte, pad-8)...)
}

func restoreMemoryRows() int {
	if n, err := strconv.Atoi(os.Getenv("RESTORE_MEMORY_ROWS")); err == nil && n > 0 {
		return n
	}
	return 200
}

// heapPeak 는 f 를 도는 동안 힙 사용량(HeapAlloc)의 최고치에서 시작 값을 뺀 것이다.
func heapPeak(f func()) uint64 {
	runtime.GC()
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	base := ms.HeapAlloc
	var peak atomic.Uint64
	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			var s runtime.MemStats
			runtime.ReadMemStats(&s)
			if s.HeapAlloc > peak.Load() {
				peak.Store(s.HeapAlloc)
			}
			select {
			case <-stop:
				return
			case <-time.After(2 * time.Millisecond):
			}
		}
	}()
	f()
	close(stop)
	wg.Wait()
	if p := peak.Load(); p > base {
		return p - base
	}
	return 0
}

func TestRestoreLongHoleKeepsMemoryBounded(t *testing.T) {
	b := newRestoreBench(t)
	n := restoreMemoryRows()
	step := b.step()
	b.add(benchRow{seq: 0, session: benchSession, rec: recA, number: 1, dts: 600 * time.Second, playback: up, archive: up})
	for i := 1; i <= n; i++ {
		src := withMtxi(t, b.raw, recA, uint64(i+1), 600*time.Second+time.Duration(i)*step)
		// ② 는 로컬에서 읽는다 — 가짜 S3 에 ② 객체를 두면 시험대가 쥐는 GB 가 GC 문턱을 끌어올려 복원 몫을 가린다.
		b.add(benchRow{seq: int64(i), session: benchSession, src: paddedSegment(src, restoreMemoryRowBytes),
			archive: index.UploadStatePending})
		delete(b.srcs, int64(i)) // 시험 쪽 사본은 쥐지 않는다 — 재는 것은 복원이 쥐는 메모리다(컨테이너 OOM 재현도 같다)
	}
	b.setPlaybackObject(0, 600*time.Second) // 리셋 없는 회차라 닻 도장 = 자기 mtxi

	peak := heapPeak(func() {
		b.restore()
	})

	t.Logf("구멍 %d행 × %d바이트: 힙 최고치 증가 %.1fMB", n, restoreMemoryRowBytes, float64(peak)/1e6)
	if l, ok := b.u.RestoredLink(benchSession); !ok || l.Seq != int64(n) {
		t.Fatalf("복원 링크 = (%+v, %v), want seq %d (%s)", l, ok, n, b.cap.dump())
	}
	if limit := uint64(160 << 20); peak > limit {
		t.Errorf("힙 최고치 증가 %.1fMB, want ≤ %dMB — 본문을 행 수만큼 쥐었다", float64(peak)/1e6, limit>>20)
	}
}
