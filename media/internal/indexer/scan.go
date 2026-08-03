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

// recoverTail 은 Scan(a) 다. 프로그램이 죽어 있는 동안 꼬리 파일이 더 자랐을 수 있다.
func (ix *Indexer) recoverTail(ctx context.Context, root, streamID string) error {
	cur := ix.cursors[streamID]
	if cur.Tail == nil || cur.Tail.UploadState != index.UploadStatePending {
		return nil
	}

	fi, err := os.Stat(cur.Tail.LocalPath)
	if os.IsNotExist(err) {
		// MediaMTX 보존기간(recordDeleteAfter 기본 1d)이 지나 지워졌다(9절 L3).
		// pending 인 채 원본이 사라지면 POK-30 업로더가 그 행을 올릴 수 없다.
		ix.log.Warn("tail_file_missing",
			"stream_id", streamID, "seq", cur.Tail.Seq, "path", cur.Tail.LocalPath)
		return nil
	}
	if err != nil {
		ix.log.Warn("tail_stat_failed", "stream_id", streamID, "path", cur.Tail.LocalPath, "err", err)
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
