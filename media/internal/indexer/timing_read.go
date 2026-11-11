package indexer

// 조각 시각 판독의 순서 규칙(POK-196 PDT 설계 r7 2.14 「판독 일시 실패」 · 계획 4절 c5 · R5).
//
// 판독 일시 실패는 다른 답이 아니라 늦은 답이어야 한다. 실패한 조각을 넘기고 같은 스트림의 뒤 조각을 먼저 커밋하면, 다시
// 읽은 조각은 H3 늦은 조각 검사(lateOrDuplicate)에서 영구히 빠진다. 루프는 하나(D10)라 그 자리에 머물러 다시 읽을 수
// 없으므로, 실패 조각을 Adopt 없이 미기록으로 두고 스트림에 막힘 표지를 세운다(stat_failed 와 같은 꼴 — m3c 의 Adopt 3곳
// 불변). 표지 뒤 조각도 미기록으로 둔다. 다음 재스캔(RescanEvery 5분)이 미기록 파일을 파일명 시각 순으로 다시 넘긴다.
//
// 다시 읽기 상한은 재스캔 한 번이다. 그때도 실패하면 칸 NULL 로 커밋한다(계획 c5 (ㄱ)) — w 는 파일명 시각이고 나머지 칸은
// NULL 이라 결정적으로 같은 녹화기 이어짐이 아니다. 기다려도 같은 답인 실패는 기다리지 않는다: 크기 상한 초과
// (ErrInputTooLarge — 파일은 자라기만 한다)와, 상한 뒤 포기한 스트림의 연이은 실패(머리말 배치가 바뀐 녹화기처럼 조각마다
// 같은 실패면 조각마다 재스캔 한 주기씩 장부가 밀린다)다. 판독이 한 번 성공하면 다시 기다리는 규칙으로 돌아온다.

import (
	"errors"
	"io/fs"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// 판독을 포기하고 칸 NULL 로 커밋한 까닭이다(timing_facts_partial 의 reason).
const (
	partialReadFailed    = "read_failed"
	partialInputTooLarge = "input_too_large"
)

// timingRead 는 조각 하나의 시각 판독 결과다. st 가 nil 이면 판독을 포기하고 칸 NULL 로 커밋하는 행이다 — failure 가 그
// 까닭이고 err 가 마지막 판독 오류다.
type timingRead struct {
	st      *playback.SegmentTiming
	failure string
	err     error
}

// mtxi 는 ③ 도장 사슬의 재료다. nil 이면 머리말에 mtxi 가 없거나 판독을 포기한 행이다 — 사슬은 둘을 같게 다룬다(기대값
// 고정 · playback.NextLink).
func (r timingRead) mtxi() *playback.Mtxi {
	if r.st == nil {
		return nil
	}
	return r.st.Mtxi
}

// timingReadState 는 스트림 하나의 판독 상태다. blocked 는 막힘 표지다 — 일시 실패로 미기록에 둔 조각의 경로와 파일명
// 시각이고, 표지마다 그보다 늦은 같은 스트림 조각은 커밋하지 않는다. 표지는 그 조각 자신의 판독 결과(성공 · 포기)나 그 조각이
// 사라졌다는 확인으로만 내린다 — 다른 조각의 결과로 내리면 표지 뒤 조각이 먼저 커밋된다(c5 검수 H1). 그래서 막힌 조각이
// 둘 이상일 수 있다(늦게 도착한 이른 조각이 또 실패). gaveUp 은 마지막 판독 결과가 포기였다는 뜻이다(조각 무관 — 판독기
// 자체가 같은 실패를 되풀이하는지의 표지라, 어느 조각이든 판독이 성공하면 내린다).
type timingReadState struct {
	blocked map[string]time.Time
	gaveUp  bool
}

// readState 는 스트림의 판독 상태다(없으면 만든다).
func (ix *Indexer) readState(streamID string) *timingReadState {
	st, ok := ix.timingReads[streamID]
	if !ok {
		st = &timingReadState{blocked: map[string]time.Time{}}
		ix.timingReads[streamID] = st
	}
	return st
}

// settle 은 조각 path 의 판독이 끝났다(성공 · 포기)는 기록이다 — 그 조각의 표지만 내리고 포기 이력을 결과로 바꾼다.
//
// 표지를 내릴 때는 따로 신호를 내지 않는다(세울 때의 timing_read_deferred 와 비대칭 — c5 검수 cc L1). 정지가 끝난 시각은 같은
// 경로의 줄로 읽는다: 성공은 segment_indexed, 포기는 timing_facts_partial(reason read_failed), 소멸은
// timing_read_block_dropped 다.
func (st *timingReadState) settle(path string, gaveUp bool) {
	delete(st.blocked, path)
	st.gaveUp = gaveUp
}

// readTiming 은 H5 마지막 판독이다. ok 가 거짓이면 이 이벤트를 커밋 없이 끝내고 Adopt 하지 않는다 — 시간 초과(래치 경로
// 그대로)이거나, 일시 실패로 막힘 표지를 세웠다. ok 가 참이면 커밋한다(판독 성공 또는 포기).
func (ix *Indexer) readTiming(seg recording.Segment) (timingRead, bool) {
	st, err := ix.timingT(seg.Path)
	if errors.Is(err, fsop.ErrStalled) {
		return timingRead{}, false
	}
	rs := ix.readState(seg.StreamID)
	if err == nil {
		rs.settle(seg.Path, false)
		return timingRead{st: &st}, true
	}
	if failure, giveUp := rs.giveUp(seg, err); giveUp {
		rs.settle(seg.Path, true)
		return timingRead{failure: failure, err: err}, true
	}
	rs.blocked[seg.Path] = seg.StartWall
	ix.log.Warn("timing_read_deferred", "stream_id", seg.StreamID, "path", seg.Path, "reason", seg.Reason, "err", err,
		"note", "미기록으로 두고 같은 스트림의 뒤 조각을 막는다. 다음 재스캔이 다시 읽는다")
	return timingRead{}, false
}

// giveUp 은 판독 실패 err 를 기다리지 않고 포기할지다. 오류는 sentinel 이 아니어도(열기 실패 등) 일시 실패로 친다 —
// 「모르는 오류면 커밋」 으로 새면 막힘이 뚫린다.
func (st *timingReadState) giveUp(seg recording.Segment, err error) (failure string, giveUp bool) {
	_, blocked := st.blocked[seg.Path]
	switch {
	case errors.Is(err, playback.ErrInputTooLarge):
		return partialInputTooLarge, true
	case st.gaveUp:
		return partialReadFailed, true
	case blocked && seg.Reason == recording.ReasonScan:
		// 재스캔이 막힌 조각을 다시 넘겼는데 또 실패했다 — 상한(재스캔 한 번)이다. 워처 · 훅의 재전달은 세지 않는다.
		return partialReadFailed, true
	}
	return "", false
}

// heldBehindBlock 은 seg 가 어느 막힘 표지보다 늦은 같은 스트림 조각인가다. 참이면 그 조각을 커밋하지도 Adopt 하지도 않는다.
// 표지의 조각 자신과 모든 표지보다 이른 조각은 막지 않는다 — 순서에 필요한 것은 「표지 뒤 조각을 먼저 커밋하지 않는다」 뿐이다.
// 신호의 blocked_path 는 seg 를 막는 표지 가운데 가장 이른 것이다(같으면 경로 순) — 맵 순회 순서와 무관하게 실행마다 같고,
// 그 표지가 막힘의 뿌리다(c5 확인 패스 cc 부수 2).
func (ix *Indexer) heldBehindBlock(seg recording.Segment) bool {
	st, ok := ix.timingReads[seg.StreamID]
	if !ok {
		return false
	}
	var root string
	var rootWall time.Time
	for path, wall := range st.blocked {
		if path == seg.Path || seg.StartWall.Before(wall) {
			continue
		}
		if root == "" || wall.Before(rootWall) || wall.Equal(rootWall) && path < root {
			root, rootWall = path, wall
		}
	}
	if root == "" {
		return false
	}
	ix.log.Info("timing_read_blocked", "stream_id", seg.StreamID, "path", seg.Path, "blocked_path", root)
	return true
}

// dropVanishedBlocks 는 재스캔의 미기록 목록에 없는 막힌 조각 가운데, 이미 장부에 있거나 파일이 실제로 지워진(stat
// ENOENT — statT 관문 · 시간 상한 그대로) 조각의 표지를 내린다. 내리지 않으면 그 스트림이 영구히 선다. 목록 부재만으로는
// 내리지 않는다 — 표지보다 먼저 디렉터리를 읽은 수집 결과나 부분 수집이면 살아 있는 조각도 목록에 없다(c5 검수 H2). stat 이
// 그 밖의 오류(정지 포함)면 표지를 그대로 두고 다음 재스캔이 다시 본다.
//
// 한계(c5 확인 패스 cc 부수 1): 막힌 조각이 목록에는 계속 나오는데 그 조각의 stat 만 ENOENT 밖 오류(EIO · EACCES)로 계속
// 실패하면, measure 가 stat_failed 로 끝나 판독까지 가지 못하고 이 함수도 목록에 있는 경로라 건너뛴다 — 뒤 조각이 그
// 오류가 풀릴 때까지 선다. c5 전에는 그 조각 하나만 빠졌다. 녹화 디렉터리는 녹화기 혼자 쓰는 로컬 볼륨이라 우리 송출
// 흐름에서 그 꼴이 나는 경로는 없어 막지 않는다 — 나면 stat_failed 가 같은 경로로 되풀이된다.
func (ix *Indexer) dropVanishedBlocks(streamID string, pending []recording.Segment) {
	st, ok := ix.timingReads[streamID]
	if !ok {
		return
	}
	listed := make(map[string]bool, len(pending))
	for _, seg := range pending {
		listed[seg.Path] = true
	}
	for path := range st.blocked {
		if listed[path] {
			continue
		}
		why := ""
		if _, inLedger := ix.indexed[streamID][path]; inLedger {
			why = "in_ledger"
		} else if _, err := ix.statT(path, "timing_block"); errors.Is(err, fs.ErrNotExist) {
			why = "file_gone"
		}
		if why == "" {
			continue
		}
		delete(st.blocked, path)
		ix.log.Warn("timing_read_block_dropped", "stream_id", streamID, "path", path, "why", why,
			"note", "막힌 조각이 장부에 있거나 지워졌다 — 표지를 내리고 뒤 조각을 잇는다")
	}
}
