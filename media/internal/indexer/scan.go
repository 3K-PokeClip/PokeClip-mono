package indexer

import (
	"context"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// Scan 은 폴더 전체를 훑어 미기록 파일을 찾아 처리한다. 여러 번 불러도 결과가 같다(멱등).
// 재스캔은 이벤트 유실·오버플로 등 모든 이상의 보편 복구 수단이다(D10).
//
// 스트림마다 (a)꼬리 복구 -> (b)정렬 -> (c)옛것부터 순차 처리 -> (d)최신 1개 판정 순으로 간다.
//
// TODO(C2): 지금은 매번 전체 walk 다. 트리거는 "recordings 파일 수 1만 초과"이며,
// 그때는 mtime 증분 walk 로 바꾼다.
func (ix *Indexer) Scan(ctx context.Context, root string) error {
	byStream, err := ix.collect(root)
	if err != nil {
		return err
	}

	for streamID, segs := range byStream {
		if err := ctx.Err(); err != nil {
			return err
		}
		if err := ix.scanStream(ctx, root, streamID, segs); err != nil {
			return err
		}
	}
	return nil
}

// collect 은 트리를 훑어 스트림별 세그먼트 목록을 만든다.
func (ix *Indexer) collect(root string) (map[string][]recording.Segment, error) {
	byStream := map[string][]recording.Segment{}

	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			// 훑는 도중 사라진 파일 때문에 전체 스캔을 포기하지는 않는다.
			ix.log.Warn("walk_entry_failed", "path", path, "err", err)
			return nil
		}
		if d.IsDir() {
			return nil
		}
		seg, parseErr := recording.ParseSegmentPath(root, path)
		if parseErr != nil {
			if errors.Is(parseErr, recording.ErrInvalidStreamID) {
				// 세그먼트 파일 모양이지만 스트림 이름이 화이트리스트를 통과하지 못했다.
				// 조용히 버리면 원인을 알 수 없고, 매 스캔마다 경고하면 로그가 쏟아진다.
				ix.warnRejectedStream(filepath.Dir(path), parseErr)
				return nil
			}
			// 세그먼트 파일이 아니다. 녹화 폴더에는 다른 파일이 섞일 수 있다.
			ix.log.Debug("non_segment_file_skipped", "path", path, "err", parseErr)
			return nil
		}
		byStream[seg.StreamID] = append(byStream[seg.StreamID], seg)
		return nil
	})
	if err != nil {
		return nil, err
	}
	return byStream, nil
}

func (ix *Indexer) scanStream(ctx context.Context, root, streamID string, segs []recording.Segment) error {
	// 커서와 이력 맵을 DB 에서 새로 적재해 교체한다.
	// 맵에 항목이 들어가는 경로는 INSERT 직후 하나뿐이고 그 시점에 DB 에도 있으므로
	// 교체로 잃을 항목이 없다. 다만 이 교체가 C3(이력 맵 정리 정책)를 해소하지는 않는다.
	cur, err := ix.store.LoadCursor(ctx, streamID)
	if err != nil {
		return err
	}
	indexed, err := ix.store.ExistingPaths(ctx, streamID)
	if err != nil {
		return err
	}
	ix.cursors[streamID] = &cur
	ix.indexed[streamID] = indexed
	// 커서가 통째로 바뀌었다 — 옛 seq 를 가리키는 보류·요청 상태를 새 커서에 맞춘다.
	ix.reconcileUploadState(streamID)

	// 스캔 요약은 **모든 종료 경로**에서 1회 나와야 한다. 조기 반환이 3곳이라 말미에 두면
	// 평시 경로에서 아예 찍히지 않는다(그것이 r1 안이 부적합했던 이유다).
	//
	// 반드시 **클로저** defer 여야 한다. `defer ix.log.Info(..., pendingCount, ...)` 로 쓰면
	// 인자가 등록 시점에 평가돼 언제나 0 이 박제된다.
	//
	// 위치가 커서 적재 뒤인 이유: 적재가 실패하면 Scan 전체가 에러로 중단되므로 "훑었다"는
	// 요약을 남기는 것이 오히려 거짓 신호다.
	// indexed 는 advance 가 INSERT 때마다 키를 더하는 살아 있는 맵이다. 그대로 len 을 재면
	// 스캔 중 늘어난 만큼이 섞여 files = indexed + pending 이라는 읽는 이의 산수가 깨진다.
	// 그래서 "스캔 시작 시점에 이미 DB 에 있던 행 수"로 못 박아 둔다.
	indexedAtStart := len(indexed)
	var pendingCount, holes int
	defer func() {
		ix.log.Info("scan_summary",
			"stream_id", streamID, "files", len(segs), "indexed", indexedAtStart,
			"pending", pendingCount, "holes", holes)
	}()

	// (a) 꼬리 재성장 복구 — 재기동으로 잃은 교정 창을 되살린다.
	if err := ix.recoverTail(ctx, root, streamID); err != nil {
		return err
	}

	// (b) 미기록 파일을 파일명 시각 오름차순으로 정렬한다.
	pending := make([]recording.Segment, 0, len(segs))
	for _, seg := range segs {
		if _, ok := ix.indexed[streamID][seg.Path]; !ok {
			pending = append(pending, seg)
		}
	}
	pendingCount = len(pending)
	holes = ix.reportHoles(streamID, cur.Tail, pending)
	if len(pending) == 0 {
		return nil
	}
	sortByStartWall(pending)

	// (c) 최신 1개를 제외한 나머지를 ReasonScan 으로 오름차순 순차 처리한다.
	//
	// 이 순서가 계약이다. 최신 파일을 먼저 확정하면 커서의 LastStartWall 이 최신 시각으로
	// 전진해, 뒤이어 처리되는 오래된 파일들이 전부 H3 에 걸려 영구 skip 된다.
	// 커서는 항상 시각 오름차순으로만 전진해야 한다.
	for _, seg := range pending[:len(pending)-1] {
		seg.Reason = recording.ReasonScan
		if err := ix.Handle(ctx, seg); err != nil {
			return err
		}
	}

	// (d) 최신 파일 판정(D11). 무조건 보류하면 "방송 종료 후 재기동" 파일이 영구 누락된다.
	latest := pending[len(pending)-1]
	fi, err := os.Stat(latest.Path)
	if err != nil {
		ix.log.Warn("latest_stat_failed", "stream_id", streamID, "path", latest.Path, "err", err)
		return nil
	}
	if time.Since(fi.ModTime()) >= ix.opt.IdleTimeout {
		latest.Reason = recording.ReasonScan
		return ix.Handle(ctx, latest)
	}
	// 아직 최근에 쓰인 흔적이 있으면 녹화 중일 수 있으니 감시자에게 넘겨 계속 지켜보게 한다.
	ix.adopt.Adopt(latest)
	return nil
}

// reportHoles 는 인덱스 구멍의 **위치와 시각**을 남긴다. 개수만으로는 무엇을 잃었는지
// 되짚을 수 없어 사람이 손쓸 수가 없다.
//
// 구멍의 정의: 파일 집합 − DB 경로 집합(= pending) 중 **커서 꼬리보다 이른 StartWall** 인 것.
// 꼬리보다 늦은 미기록은 아직 처리 전인 정상 상태이므로 구멍이 아니다.
// 재스캔이 5분마다 도므로 남아 있는 한 주기마다 재확인된다.
func (ix *Indexer) reportHoles(streamID string, tail *index.TailRow, pending []recording.Segment) int {
	if tail == nil {
		return 0 // 아직 한 건도 기록하지 않았다 = 비교 기준이 없다
	}
	n := 0
	for _, seg := range pending {
		if !seg.StartWall.Before(tail.StartWallUTC) {
			continue
		}
		n++
		ix.log.Warn("hole_detected",
			"stream_id", streamID, "path", seg.Path, "start_wall", seg.StartWall,
			"last_seq", tail.Seq, "last_indexed_wall", tail.StartWallUTC)
	}
	return n
}

// recoverTail 은 Scan(a) 다. 프로그램이 죽어 있는 동안 꼬리 파일이 더 자랐을 수 있다.
func (ix *Indexer) recoverTail(ctx context.Context, root, streamID string) error {
	cur := ix.cursors[streamID]
	// nil 가드는 stat 보다 앞에 그대로 둔다 — 없으면 곧바로 nil 역참조다.
	if cur.Tail == nil {
		return nil
	}

	// 상태 검사와 stat 의 순서를 바꿨다. 이제 uploaded·failed 꼬리의 성장도 correctTail 의
	// 3분기로 판정된다(G12‴ 탐지 사슬). 대가는 비pending 꼬리에 stat 1회가 더 드는 것인데,
	// 스트림당 5분에 한 번 도는 경로라 무시할 수 있다. 다만 "비용 0"은 아니다.
	fi, err := os.Stat(cur.Tail.LocalPath)
	pending := cur.Tail.UploadState == index.UploadStatePending
	if os.IsNotExist(err) {
		if !pending {
			// recordDeleteAfter·janitor 가 지운 정상 상황이다. WARN 으로 두면 5분마다 폭주한다.
			ix.log.Debug("tail_file_gone", "stream_id", streamID, "seq", cur.Tail.Seq,
				"path", cur.Tail.LocalPath, "upload_state", string(cur.Tail.UploadState))
			return nil
		}
		// pending 인 채 원본이 사라지면 업로더가 그 행을 올릴 수 없다(9절 L3).
		ix.log.Warn("tail_file_missing",
			"stream_id", streamID, "seq", cur.Tail.Seq, "path", cur.Tail.LocalPath)
		return nil
	}
	if err != nil {
		// 같은 규칙이다 — 확정된 행의 stat 실패는 조사 대상이 아니다.
		if !pending {
			ix.log.Debug("tail_stat_failed", "stream_id", streamID, "seq", cur.Tail.Seq,
				"path", cur.Tail.LocalPath, "upload_state", string(cur.Tail.UploadState), "err", err)
			return nil
		}
		ix.log.Warn("tail_stat_failed", "stream_id", streamID, "seq", cur.Tail.Seq,
			"path", cur.Tail.LocalPath, "err", err)
		return nil
	}
	if fi.Size() <= cur.Tail.Bytes {
		return nil
	}

	seg, err := recording.ParseSegmentPath(root, cur.Tail.LocalPath)
	if err != nil {
		ix.log.Warn("tail_path_unparsable", "stream_id", streamID, "path", cur.Tail.LocalPath, "err", err)
		return nil
	}
	seg.Reason = recording.ReasonRegrown
	return ix.correctTail(ctx, seg)
}

// sortByStartWall 은 파일명 시각 오름차순으로 정렬한다. 파일명에 시각이 박혀 있으므로
// 이름순이 곧 시간순이지만, 순서가 계약이므로 발견 순서에 기대지 않고 명시적으로 정렬한다.
func sortByStartWall(segs []recording.Segment) {
	slices.SortStableFunc(segs, func(a, b recording.Segment) int {
		return a.StartWall.Compare(b.StartWall)
	})
}

// warnRejectedStream 은 거부된 스트림 디렉토리마다 WARN 을 한 번만 남긴다.
func (ix *Indexer) warnRejectedStream(dir string, cause error) {
	if ix.warnedRejected[dir] {
		return
	}
	ix.warnedRejected[dir] = true
	ix.log.Warn("stream_id_rejected", "dir", dir, "err", cause)
}
