package indexer

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

func (f *fixture) scan() {
	f.t.Helper()
	if err := f.ix.Scan(context.Background(), f.root); err != nil {
		f.t.Fatalf("Scan 실패: %v", err)
	}
}

// 케이스7 — Scan(a) 는 프로그램이 죽어 있는 동안 더 자란 꼬리를 복구한다.
// 재기동으로 잃은 교정 창을 되살리는 자리다.
func TestCase07ScanRecoversGrownTail(t *testing.T) {
	f := newFixture(t)
	path := f.makeFile("s1", segName(baseWall, 0), 2500)
	f.store.seed(index.Record{
		StreamID: "s1", Seq: 0, StartPTSMS: 0, StartWallUTC: baseWall,
		DurationMS: 2000, LocalPath: path, UploadState: index.UploadStatePending,
		Bytes: 1000, // DB 에 적힌 크기보다 디스크가 크다 = 그 사이 더 써졌다
	})
	f.probe.vals = []int64{4000}

	f.scan()

	if len(f.store.updateTail) != 1 {
		t.Fatalf("UpdateTail 호출 = %d, want 1", len(f.store.updateTail))
	}
	call := f.store.updateTail[0]
	if call.durationMS != 4000 || call.bytes != 2500 {
		t.Fatalf("UpdateTail(duration=%d, bytes=%d), want 4000 / 2500", call.durationMS, call.bytes)
	}
}

// Scan(a) 는 꼬리 파일이 사라졌으면 WARN 을 남긴다(L3 — recordDeleteAfter 1d 경로).
func TestScanWarnsWhenTailFileMissing(t *testing.T) {
	f := newFixture(t)
	f.makeFile("s1", segName(baseWall, 4*time.Second), 1000) // 스트림 디렉토리를 만들기 위한 다른 파일
	f.store.seed(index.Record{
		StreamID: "s1", Seq: 0, StartPTSMS: 0, StartWallUTC: baseWall,
		DurationMS: 4000, LocalPath: f.root + "/s1/사라진파일.mp4",
		UploadState: index.UploadStatePending, Bytes: 1000,
	})

	f.scan()

	if n := f.logs.count(slog.LevelWarn, "tail_file_missing"); n != 1 {
		t.Fatalf("tail_file_missing = %d건, want 1", n)
	}
}

// 케이스16-1 — 정렬 함수 전용. 역순으로 넣어도 파일명 시각 오름차순으로 나와야 한다.
// 이 순서가 계약이다(설계 3절 3-(c)). 최신 파일을 먼저 확정하면 커서가 최신 시각으로 전진해
// 뒤이어 처리되는 오래된 파일들이 전부 H3 에 걸려 영구 skip 된다.
func TestCase16SortPendingByStartWallAscending(t *testing.T) {
	mk := func(offset time.Duration) recording.Segment {
		return recording.Segment{StreamID: "s1", Path: segName(baseWall, offset), StartWall: baseWall.Add(offset)}
	}
	segs := []recording.Segment{mk(8 * time.Second), mk(0), mk(4 * time.Second)}

	sortByStartWall(segs)

	for i := 1; i < len(segs); i++ {
		if segs[i].StartWall.Before(segs[i-1].StartWall) {
			t.Fatalf("정렬 결과가 오름차순이 아니다: %v 뒤에 %v", segs[i-1].StartWall, segs[i].StartWall)
		}
	}
	if !segs[0].StartWall.Equal(baseWall) {
		t.Fatalf("첫 원소 = %v, want %v", segs[0].StartWall, baseWall)
	}
}

// 케이스16-2 — Scan 이 미기록 파일에 파일명 시각 오름차순으로 seq 를 부여한다.
func TestCase16ScanAssignsSeqInAscendingWallOrder(t *testing.T) {
	f := newFixture(t)
	f.probe.vals = []int64{4000}

	offsets := []time.Duration{8 * time.Second, 0, 4 * time.Second, 12 * time.Second}
	for _, o := range offsets {
		f.makeFile("s1", segName(baseWall, o), 1000)
	}

	f.scan()

	rows := f.store.records("s1")
	if len(rows) != 4 {
		t.Fatalf("행 수 = %d, want 4 (최신 파일도 mtime 이 오래돼 즉시 확정 대상이다)", len(rows))
	}
	for i := 1; i < len(rows); i++ {
		if !rows[i].StartWallUTC.After(rows[i-1].StartWallUTC) {
			t.Fatalf("seq %d 의 wall(%v)이 seq %d(%v)보다 뒤가 아니다",
				rows[i].Seq, rows[i].StartWallUTC, rows[i-1].Seq, rows[i-1].StartWallUTC)
		}
		if rows[i].Seq != rows[i-1].Seq+1 {
			t.Fatalf("seq 가 연속이 아니다: %d -> %d", rows[i-1].Seq, rows[i].Seq)
		}
	}
	if n := f.logs.count(slog.LevelError, "late_segment_skipped"); n != 0 {
		t.Fatalf("late_segment_skipped = %d건, want 0 — 처리 순서가 뒤집혔다", n)
	}
}

// 케이스17 — Scan(d) 최신 파일 분기(D11).
// mtime 이 오래된 최신 파일은 즉시 확정하고, 최근에 쓰인 최신 파일은 워처에 인계한다.
// 무조건 보류로 구현하면 "방송 종료 후 재기동" 파일이 영구 누락된다.
func TestCase17ScanLatestFileBranch(t *testing.T) {
	t.Run("mtime_이_오래된_최신_파일은_즉시_확정한다", func(t *testing.T) {
		f := newFixture(t)
		f.probe.vals = []int64{4000}
		f.makeFile("s1", segName(baseWall, 0), 1000) // makeFile 은 mtime 을 1시간 전으로 둔다

		f.scan()

		rows := f.store.records("s1")
		if len(rows) != 1 {
			t.Fatalf("행 수 = %d, want 1", len(rows))
		}
		if f.adopter.count() != 0 {
			t.Fatalf("Adopt 호출 = %d, want 0", f.adopter.count())
		}
	})

	t.Run("최근에_쓰인_최신_파일은_워처에_인계한다", func(t *testing.T) {
		f := newFixture(t)
		f.probe.vals = []int64{4000}
		p := f.makeFile("s1", segName(baseWall, 0), 1000)
		f.touch(p, time.Now()) // 아직 녹화 중일 수 있다

		f.scan()

		if n := len(f.store.records("s1")); n != 0 {
			t.Fatalf("행 수 = %d, want 0 — 녹화 중일 수 있는 파일을 확정했다", n)
		}
		if f.adopter.count() != 1 {
			t.Fatalf("Adopt 호출 = %d, want 1", f.adopter.count())
		}
	})

	t.Run("최신_파일이_보류돼도_그_앞_파일들은_확정된다", func(t *testing.T) {
		f := newFixture(t)
		f.probe.vals = []int64{4000}
		f.makeFile("s1", segName(baseWall, 0), 1000)
		f.makeFile("s1", segName(baseWall, 4*time.Second), 1000)
		latest := f.makeFile("s1", segName(baseWall, 8*time.Second), 1000)
		f.touch(latest, time.Now())

		f.scan()

		if n := len(f.store.records("s1")); n != 2 {
			t.Fatalf("행 수 = %d, want 2", n)
		}
		if f.adopter.count() != 1 {
			t.Fatalf("Adopt 호출 = %d, want 1", f.adopter.count())
		}
	})
}

// Scan 은 반복 호출해도 결과가 같다(멱등). 재스캔이 보편 복구 수단이기 때문이다(D10).
func TestScanIsIdempotent(t *testing.T) {
	f := newFixture(t)
	f.probe.vals = []int64{4000}
	for _, o := range []time.Duration{0, 4 * time.Second, 8 * time.Second} {
		f.makeFile("s1", segName(baseWall, o), 1000)
	}

	f.scan()
	first := len(f.store.records("s1"))
	f.scan()
	second := len(f.store.records("s1"))

	if first != 3 || second != 3 {
		t.Fatalf("행 수 = %d -> %d, want 3 -> 3", first, second)
	}
	if n := f.logs.count(slog.LevelError, "late_segment_skipped"); n != 0 {
		t.Fatalf("late_segment_skipped = %d건, want 0 — 재스캔이 오탐을 냈다(X1 회귀)", n)
	}
}

// Scan 은 세그먼트 파일이 아닌 것을 조용히 건너뛴다.
func TestScanIgnoresNonSegmentFiles(t *testing.T) {
	f := newFixture(t)
	f.probe.vals = []int64{4000}
	f.makeFile("s1", segName(baseWall, 0), 1000)
	f.makeFile("s1", "README.txt", 10)

	f.scan()

	if n := len(f.store.records("s1")); n != 1 {
		t.Fatalf("행 수 = %d, want 1", n)
	}
}

// 여러 스트림이 섞여 있어도 스트림별로 독립 처리한다.
func TestScanHandlesMultipleStreams(t *testing.T) {
	f := newFixture(t)
	f.probe.vals = []int64{4000}
	f.makeFile("s1", segName(baseWall, 0), 1000)
	f.makeFile("s1", segName(baseWall, 4*time.Second), 1000)
	f.makeFile("s2", segName(baseWall, 40*time.Second), 1000)

	f.scan()

	if n := len(f.store.records("s1")); n != 2 {
		t.Fatalf("s1 행 수 = %d, want 2", n)
	}
	if n := len(f.store.records("s2")); n != 1 {
		t.Fatalf("s2 행 수 = %d, want 1", n)
	}
	if got := f.store.records("s2")[0].Seq; got != 0 {
		t.Fatalf("s2 seq = %d, want 0", got)
	}
}
