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
// Scan 은 수집과 처리를 동기로 잇는 편의 경로다 — 프로덕션 loop 은 이것 대신
// StartCollect/ApplyCollect(collect.go)를 쓴다(수집 대기 점유 0). 여기 남긴 이유는
// "수집→처리" 의 의미론을 한 이름으로 재는 테스트들 때문이며, 두 경로가 같은
// collectTree·scanStream 을 지나므로 판정이 갈리지 않는다.
func (ix *Indexer) Scan(ctx context.Context, root string) error {
	_, err := ix.ApplyCollect(ctx, root, ix.collectTree(ctx, root, root))
	return err
}

// collectTree 는 트리를 훑어 스트림별 세그먼트 목록을 만든다. **수집 워커 고루틴에서 돈다** —
// ix 의 맵을 만지면 안 되고(D10), 고루틴 안전한 로그만 허용된다. 거부 디렉토리 경고
// 재료는 결과에 실어 ApplyCollect(단일 고루틴)가 warnedRejected 맵으로 처리한다.
//
// walkRoot ≠ parseRoot 인 호출은 표적 재수집(장벽 스트림 하위 디렉토리만 훑되 경로 정본화
// 기준은 전체 루트)뿐이다 — WalkDir 호출 자리를 이 함수 하나로 유지한다(m2 예외 1).
//
// 예산(ScanCollectBudget)은 soft 다: 넘기면 truncated 로 표시하고 걷은 데까지 돌려준다 —
// 부분 결과는 처리되고 다음 주기가 재개한다(f6j).
func (ix *Indexer) collectTree(ctx context.Context, walkRoot, parseRoot string) collectResult {
	res := collectResult{
		byStream: map[string][]recording.Segment{},
		rejected: map[string]error{},
	}
	start := time.Now()

	err := filepath.WalkDir(walkRoot, func(path string, d fs.DirEntry, err error) error { //nolint:forbidigo // 예외 1(m2) — 수집 워커 자신이 격리 단위다. 개별 호출에 5초 상한을 씌우면 45초 수집 예산 위에서 정상 수집이 매 주기 절단된다(.golangci.yml 머리 주석).
		if cerr := ctx.Err(); cerr != nil {
			// SIGTERM 등 부모 취소는 절단이 아니라 중단이다(f6j 대조군).
			return cerr
		}
		if time.Since(start) > ix.opt.ScanCollectBudget {
			res.truncated = true
			return fs.SkipAll
		}
		if err != nil {
			// 훑는 도중 사라진 파일 때문에 전체 스캔을 포기하지는 않는다.
			ix.log.Warn("walk_entry_failed", "path", path, "err", err)
			return nil
		}
		if d.IsDir() {
			return nil
		}
		seg, parseErr := recording.ParseSegmentPath(parseRoot, path)
		if parseErr != nil {
			if errors.Is(parseErr, recording.ErrInvalidStreamID) {
				// 세그먼트 파일 모양이지만 스트림 이름이 화이트리스트를 통과하지 못했다.
				res.rejected[filepath.Dir(path)] = parseErr
				return nil
			}
			// 세그먼트 파일이 아니다. 녹화 폴더에는 다른 파일이 섞일 수 있다.
			ix.log.Debug("non_segment_file_skipped", "path", path, "err", parseErr)
			return nil
		}
		res.byStream[seg.StreamID] = append(res.byStream[seg.StreamID], seg)
		return nil
	})
	res.elapsed = time.Since(start)
	res.err = err
	return res
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
	// 락 경합 순서 장벽은 **성공 완주 뒤에만** 내린다(함수 말미) — 진입 시 내리면 재처리 중
	// 일시 실패(경합 재발·정착 불가·stat 실패) 뒤 후속 조각이 좌초 조각의 seq 를 선점한다
	// (cx 재검 차단 1). Handle 은 ReasonScan 을 장벽과 무관하게 통과시키므로 여기서
	// 내리지 않아도 재처리는 돈다.
	heldBarrier := ix.insertHold[streamID]

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
		// 미기록분이 없다 = 좌초 조각까지 이미 장부에 있다 — 장벽을 내려도 안전하다.
		ix.releaseInsertHold(streamID, heldBarrier)
		return nil
	}
	sortByStartWall(pending)

	// (c) 최신 1개를 제외한 나머지를 ReasonScan 으로 오름차순 순차 처리한다.
	//
	// 이 순서가 계약이다. 최신 파일을 먼저 확정하면 커서의 LastStartWall 이 최신 시각으로
	// 전진해, 뒤이어 처리되는 오래된 파일들이 전부 H3 에 걸려 영구 skip 된다.
	// 커서는 항상 시각 오름차순으로만 전진해야 한다.
	//
	// 장벽 국면 추가 규칙(cx 재검 차단 1): 조각이 **일시 사유**(경합 재발·정착 불가·stat
	// 실패)로 미기록이면 그 지점에서 주기를 중단한다 — 계속 가면 뒤 조각이 좌초 조각의
	// seq 를 선점해 H3 영구 거절을 만든다. poison(값 문제)은 원래도 그 슬롯을 버리는
	// 설계라 계속 간다. 비장벽 국면은 기존 동작 그대로다.
	for _, seg := range pending[:len(pending)-1] {
		seg.Reason = recording.ReasonScan
		streakBefore := ix.poisonStreak[streamID]
		if err := ix.Handle(ctx, seg); err != nil {
			return err
		}
		if heldBarrier && !ix.pathIndexed(streamID, seg.Path) && ix.poisonStreak[streamID] == streakBefore {
			ix.log.Warn("insert_hold_scan_stalled",
				"stream_id", streamID, "path", seg.Path,
				"note", "장벽 재처리 중 일시 실패 — 순서 보존을 위해 이 주기를 중단한다")
			return nil
		}
	}

	// (d) 최신 파일 판정(D11). 무조건 보류하면 "방송 종료 후 재기동" 파일이 영구 누락된다.
	latest := pending[len(pending)-1]
	fi, err := ix.statT(latest.Path, "scan_latest")
	if err != nil {
		ix.log.Warn("latest_stat_failed", "stream_id", streamID, "path", latest.Path, "err", err)
		return nil // 장벽이 있었다면 유지 — 다음 주기 재시도
	}
	if time.Since(fi.ModTime()) >= ix.opt.IdleTimeout {
		latest.Reason = recording.ReasonScan
		streakBefore := ix.poisonStreak[streamID]
		if err := ix.Handle(ctx, latest); err != nil {
			return err
		}
		if heldBarrier && !ix.pathIndexed(streamID, latest.Path) && ix.poisonStreak[streamID] == streakBefore {
			ix.log.Warn("insert_hold_scan_stalled",
				"stream_id", streamID, "path", latest.Path,
				"note", "장벽 재처리 중 최신 조각 일시 실패 — 이 주기를 중단한다")
			return nil
		}
		ix.releaseInsertHold(streamID, heldBarrier)
		return nil
	}
	// 최신 조각이 아직 자라는 중이다. 장벽 국면에서는 되돌리지 않고 장벽을 유지한다 —
	// 되돌린 조각의 실시간 재발화는 어차피 장벽에 걸리고, 다음 주기의 유휴 판정이 회수한다.
	// 미기록 최신 조각을 남긴 채 장벽을 내리면 후속 실시간 조각이 그 seq 를 선점한다.
	if heldBarrier {
		ix.log.Debug("insert_hold_latest_growing", "stream_id", streamID, "path", latest.Path)
		return nil
	}
	// 아직 최근에 쓰인 흔적이 있으면 녹화 중일 수 있으니 감시자에게 넘겨 계속 지켜보게 한다.
	ix.adopt.Adopt(latest)
	return nil
}

// pathIndexed 는 "이 파일이 장부(메모리 이력)에 올랐는가"다 — advance 가 INSERT 성공 때만
// 키를 더하므로 장벽 재처리의 성공 판정으로 쓴다.
func (ix *Indexer) pathIndexed(streamID, path string) bool {
	_, ok := ix.indexed[streamID][path]
	return ok
}

// releaseInsertHold 는 순서 장벽의 유일한 해제 지점이다 — 성공 완주에서만 부른다.
func (ix *Indexer) releaseInsertHold(streamID string, held bool) {
	if !held {
		return
	}
	delete(ix.insertHold, streamID)
	ix.log.Info("insert_hold_released", "stream_id", streamID)
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
	fi, err := ix.statT(cur.Tail.LocalPath, "scan_tail")
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
