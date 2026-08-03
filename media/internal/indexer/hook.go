// 이 파일은 MediaMTX 세션 훅으로 확인된 "재접속 경계"를 다룬다.
//
// 왜 필요한가: 0.9초 재접속은 기존 벽시계 드리프트 판정(tolerance 1.5s)을 그냥 통과한다.
// 즉 실제로는 끊겼다 이어진 지점인데 is_discontinuity=false 로 기록된다. 훅은 그 지점을
// 알려 주는 **1차 신호**이고, 드리프트·파일 감시·주기 재스캔은 안전망으로 그대로 남는다.
// 그래서 훅이 통째로 죽어도 현행 동작으로 되돌아갈 뿐 새 실패 모드가 생기지 않는다.
//
// 로그 판독 규칙: hook_break_discarded 와 hook_break_dropped 가 같은 시각에 함께 나오면
// **한 번의 정리**다. discarded 는 "판정 대상이던 경계 1건이 왜 버려졌는가"
// (already_passed·no_tail·duplicate_path)이고, dropped 는 "그와 함께 정리된, 세그먼트가
// 한 건도 없던 더 오래된 경계들"이다. 미탐 건수는 discarded 만 센다 — dropped 는 애초에
// 붙일 세그먼트가 없던 경계이므로 미탐이 아니다.
//
// (빈 줄로 package 절과 떼어 둔다 — 붙이면 godoc 이 패키지 주석으로 오인해
// indexer.go 의 정본 패키지 설명을 밀어낸다.)

package indexer

import (
	"context"
	"sort"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// breakQueueMax 는 스트림당 무장 경계의 상한이다.
// 세그먼트가 영영 오지 않는 스트림에서 큐가 무한히 자라는 것을 막는다.
// 초과분은 가장 오래된 것부터 버리며, 그 시점 이후로 GH1(재접속 검출)은 보장되지 않는다.
const breakQueueMax = 64

// sessionMark 는 offline 훅 1건의 시각·출처다.
type sessionMark struct {
	At     time.Time
	Source string
}

// sessionBreak 은 "다음 세그먼트에 불연속을 붙여야 한다"는 무장된 세션 경계 1건이다.
type sessionBreak struct {
	OfflineAt time.Time
	OnlineAt  time.Time
	// OffSource·OnSource 는 MTX_SOURCE_ID 다. **판정에 쓰지 않고 관측에만 쓴다** —
	// 실측이 "세션 내 동일·경계에서 변경"을 보였지만 그 성질에 판정을 걸면 미확인 의존이 커진다.
	OffSource string
	OnSource  string
}

// breakDecision 은 이 세그먼트가 어떤 경계를 소비할지의 판정 결과다.
// Index >= 0 이면 breaks[stream] 의 그 위치까지(포함) 정리 대상이다.
type breakDecision struct {
	// Apply 가 true 면 is_discontinuity 를 강제 참으로 만든다.
	Apply bool
	// Index 는 -1 이면 소비 없음이다.
	Index int
	Brk   sessionBreak
}

// noBreak 는 "소비할 경계가 없다"는 판정이다.
var noBreak = breakDecision{Index: -1}

// reconcileBreaks 는 커서 꼬리 시각으로 "이미 지나간" 경계를 정리한다.
//
// 꼬리가 어떤 무장 경계의 시각 조건을 이미 만족한다면, 그 경계의 조각은 무장 전에 이미
// 장부에 올랐다는 뜻이다. 그대로 두면 **다음** 조각에 붙어 영속 오표시가 된다(GH1 모순).
// 그래서 폐기하고 미탐으로 강등한다 — 벽시계 안전망이 남아 있다.
//
// 호출 지점은 두 곳이다.
//   - Handle 진입부(H1/H2 조기 반환보다 **앞**): 중복·늦은 세그먼트로 commit 이 실행되지
//     않아도 정리가 이루어지게 한다. **정확성의 담당자는 이쪽이다.**
//   - 무장 직후(markOnline): 무장 순간 이미 꼬리가 경계를 지났으면 즉시 폐기한다. 조기 최적화다.
//
// 결정성의 근거: advance 가 커서를 즉시 갱신하고 H3 가 시간 역행 INSERT 를 금지하므로
// 꼬리 시각은 "장부에 오른 마지막 조각"의 단조 축이다. 중복 이벤트의 도착 여부와 무관하게
// 같은 입력이면 같은 결과가 나온다.
func (ix *Indexer) reconcileBreaks(streamID string, cur *index.Cursor) {
	if cur == nil || cur.Tail == nil {
		return
	}
	q := ix.breaks[streamID]
	if len(q) == 0 {
		return
	}

	cand := -1
	for i, b := range q {
		if ix.tailPassed(cur.Tail.StartWallUTC, b) {
			cand = i // 꼬리가 이미 지나간 경계 중 가장 최신을 고른다
		}
	}
	if cand < 0 {
		return
	}

	discarded := q[cand]
	// releaseBreak 이 cand 보다 오래된 미소비 경계도 함께 정리하며 hook_break_dropped 를 낸다.
	// 즉 이 정리 한 번에서 discarded 와 dropped 가 함께 나올 수 있다 — 미탐 건수는
	// discarded 만 센다(dropped 는 애초에 붙일 세그먼트가 없던 경계다).
	ix.releaseBreak(streamID, breakDecision{Index: cand})
	ix.log.Info("hook_break_discarded",
		"stream_id", streamID, "reason", "already_passed",
		"offline_at", discarded.OfflineAt, "online_at", discarded.OnlineAt,
		"tail_start_wall", cur.Tail.StartWallUTC, "tail_seq", cur.Tail.Seq)
}

// tailPassed 는 "이 벽시계 시각이 경계 b 의 소비 조건을 이미 만족하는가"다.
// peekBreak 의 조건과 **같은 식**이어야 한다 — 어긋나면 폐기와 소비의 기준이 갈린다.
func (ix *Indexer) tailPassed(wall time.Time, b sessionBreak) bool {
	return wall.After(b.OfflineAt) && !wall.Before(b.OnlineAt.Add(-ix.opt.BreakGuard))
}

// peekBreak 은 소비할 경계를 고르되 **해제하지 않는다**(순수 조회).
//
// 해제를 INSERT 결과가 나온 뒤로 미루는 이유: buildRecord 는 SeqConflict 재시도에서 두 번
// 불리는데, 그때 판정을 다시 계산하면 재적재로 바뀐 커서 때문에 값이 흔들린다.
// 조회와 해제를 나누면 재시도에서도 같은 판정을 그대로 재사용할 수 있다.
//
// 소비 조건은 둘 다 필요하다.
//   - seg.StartWall > OfflineAt — 이전 세션의 마지막 partial 조각을 **구조적으로** 배제한다.
//     정상 종료 시 그 조각도 offline 직후 complete 가 발화하므로, 없으면 표시를 가로챈다.
//   - seg.StartWall >= OnlineAt - Guard — 새 세션의 첫 조각인지의 하한.
//     훅 채널 세그먼트는 스풀 순서가 1차 방어지만, **워처 채널 세그먼트는 파일명 시각밖에
//     없으므로 Guard 가 유일한 방어다.**
func (ix *Indexer) peekBreak(cur *index.Cursor, seg recording.Segment) breakDecision {
	q := ix.breaks[seg.StreamID] // 조회 키는 seg.StreamID. 무장 키(MTX_PATH)와 바이트 동일해야 한다.
	cand := -1
	for i, b := range q {
		if seg.StartWall.Before(b.OnlineAt.Add(-ix.opt.BreakGuard)) {
			// 큐가 OnlineAt 오름차순이므로 이 뒤는 전부 더 늦다. 볼 필요가 없다.
			break
		}
		if !seg.StartWall.After(b.OfflineAt) {
			continue // 이전 세션의 조각이다
		}
		cand = i // 조건을 만족하는 것 중 **가장 최신**을 고른다(연쇄 재접속 대비)
	}

	if cand < 0 {
		return noBreak
	}
	if cur.Tail == nil {
		// 꼬리가 없으면 이 조각은 seq 0 이라 불연속 표시가 무의미하다.
		// 플래그 없이 해제만 한다 — 이월시키면 다음 정상 조각에 오탐이 붙는다.
		return breakDecision{Apply: false, Index: cand}
	}
	return breakDecision{Apply: true, Index: cand, Brk: q[cand]}
}

// releaseBreak 은 경계를 큐에서 뺀다. 소비 대상보다 오래된 미소비 경계도 함께 정리한다.
//
// 그 오래된 것들은 "세그먼트가 한 건도 오지 않은 세션"이다. 남겨 두면 큐가 새고
// 나중 조각에 엉뚱한 경계가 붙는다.
func (ix *Indexer) releaseBreak(streamID string, dec breakDecision) {
	q := ix.breaks[streamID]
	if dec.Index < 0 || dec.Index >= len(q) {
		return
	}
	for _, b := range q[:dec.Index] {
		ix.log.Info("hook_break_dropped",
			"stream_id", streamID, "offline_at", b.OfflineAt, "online_at", b.OnlineAt,
			"reason", "no_segment_in_session")
	}
	ix.breaks[streamID] = q[dec.Index+1:]
}

// HandleHook 은 훅 이벤트 1개를 처리한다.
//
// 호출자는 Scan·Handle 과 동일한 단일 고루틴(main 루프)뿐이다 — D10 규약을 깨면 seq 중복이 난다.
// 에러 계약은 Handle 과 같다: 반환 에러는 "프로세스를 끝내야 하는 상황"만 뜻하고,
// 이벤트 1개를 버리는 판정은 로그를 남기고 nil 을 돌려준다.
func (ix *Indexer) HandleHook(ctx context.Context, ev mtxhook.Event) error {
	switch ev.Kind {
	case mtxhook.KindOffline:
		ix.markOffline(ev)
		return nil
	case mtxhook.KindOnline:
		ix.markOnline(ev)
		return nil
	case mtxhook.KindSegmentComplete:
		return ix.handleHookSegment(ctx, ev)
	default:
		// zero value 가 정상 경로로 흘러든 것이다. Handle 의 H0 와 같은 사고 신호다.
		ix.log.Error("hook_unknown_kind", "kind", ev.Kind, "stream_id", ev.StreamID)
		return nil
	}
}

// markOffline 은 offline 훅을 짝 대기 자리에 넣는다.
//
// 스풀 append 순서가 훅 발화 순서와 같다는 보장이 없으므로(디스패치 지연은 순서 보장이 아니다)
// 짝짓기는 도착 순서가 아니라 **시각 비교**로 한다.
func (ix *Indexer) markOffline(ev mtxhook.Event) {
	s := ev.StreamID // 원문 그대로. 변형 금지(breaks 키 계약).

	if ev.At.Before(ix.lastOnlineAt[s]) {
		// 이미 지나간 online 이전의 offline 이다. 이것을 받아 두면 유실된 offline₂ 자리에
		// 옛 offline₁ 이 끼어 엉뚱한 경계가 무장된다.
		ix.log.Debug("hook_offline_stale", "stream_id", s, "at", ev.At,
			"last_online_at", ix.lastOnlineAt[s], "cause", "before_watermark")
		return
	}

	if cur, ok := ix.pendingOffline[s]; ok && !ev.At.After(cur.At) {
		ix.log.Debug("hook_offline_stale", "stream_id", s, "at", ev.At,
			"pending_at", cur.At, "cause", "older_than_pending")
		return
	}
	ix.pendingOffline[s] = sessionMark{At: ev.At, Source: ev.SourceID}
}

// markOnline 은 online 훅으로 짝을 성립시켜 경계를 무장한다.
func (ix *Indexer) markOnline(ev mtxhook.Event) {
	s := ev.StreamID // 원문 그대로. 변형 금지(breaks 키 계약).

	// watermark 는 짝 성립 여부와 **무관하게** 모든 online 에서 먼저 전진한다.
	// 조건부로 대입하면 짝 없는 online 뒤에 온 옛 offline 을 걸러 내지 못한다.
	if ev.At.After(ix.lastOnlineAt[s]) {
		ix.lastOnlineAt[s] = ev.At
	}

	off, ok := ix.pendingOffline[s]
	if !ok {
		ix.log.Info("hook_online_unpaired", "stream_id", s, "online_at", ev.At, "cause", "no_offline")
		return
	}
	if !off.At.Before(ev.At) {
		// OnlineAt <= OfflineAt 인 쌍은 순서 역전이므로 신뢰하지 않는다.
		// 선택은 일관되게 오탐보다 미탐이다 — 벽시계 안전망이 남아 있다.
		ix.log.Info("hook_online_unpaired", "stream_id", s,
			"offline_at", off.At, "online_at", ev.At, "cause", "inverted")
		return
	}

	brk := sessionBreak{
		OfflineAt: off.At, OnlineAt: ev.At,
		OffSource: off.Source, OnSource: ev.SourceID,
	}
	delete(ix.pendingOffline, s)
	ix.log.Info("hook_break_armed", "stream_id", s, "offline_at", brk.OfflineAt, "online_at", brk.OnlineAt)
	ix.enqueueBreak(s, brk)

	if off.Source != "" && off.Source == ev.SourceID {
		// 세션이 갈렸는데 출처가 같다 = 실측과 다른 상황이다. 관측만 하고 무장은 유지한다.
		ix.log.Warn("hook_break_same_source", "stream_id", s, "source_id", ev.SourceID)
	}

	// 무장 순간 이미 꼬리가 그 경계를 지났으면 즉시 폐기한다.
	//
	// **커서가 적재된 스트림에서만** 한다(결정 ②). ix.state()를 부르지 않는 이유는 두 가지다.
	// ① 훅 이벤트가 DB I/O 를 유발하면 안 된다(에러 계약과 충돌). ② 커서가 없다는 것은
	// 그 스트림의 세그먼트를 아직 한 건도 처리하지 않았다는 뜻이고, 꼬리가 없으면 폐기할
	// 근거도 없다. 건너뛰어도 정확성은 보존된다 — 첫 세그먼트가 오면 Handle 진입부의
	// reconcile 이 커서 적재 후 실행된다. 여기 호출은 조기 최적화일 뿐이다.
	if cur, ok := ix.cursors[s]; ok {
		ix.reconcileBreaks(s, cur)
	}
}

// handleHookSegment 는 segcomplete 훅을 세그먼트로 바꿔 판정 없이 그대로 Handle 에 밀어넣는다.
//
// 파일 감시 경로도 같은 파일을 ReasonNextFile 로 올리며, 먼저 온 쪽이 INSERT 하고
// 나중 것은 H2 경로 중복이 흡수한다. 그 흡수가 성립하는 전제가 ToSegment 의 경로 정본화다.
func (ix *Indexer) handleHookSegment(ctx context.Context, ev mtxhook.Event) error {
	seg, err := mtxhook.ToSegment(ix.opt.SegmentRoot, ev)
	if err != nil {
		// 형식 이상·루트 밖이다. 이 이벤트만 버린다 — 파일 감시 경로가 안전망으로 남아 있다.
		ix.log.Warn("hook_segment_path_rejected",
			"stream_id", ev.StreamID, "segment_path", ev.SegmentPath,
			"root", ix.opt.SegmentRoot, "err", err)
		return nil
	}
	return ix.Handle(ctx, seg)
}

// enqueueBreak 는 무장 경계를 OnlineAt 오름차순으로 **정렬 삽입**한다.
//
// 단순 append 를 쓰면 안 된다: 역순 입력이 흡수돼 뒤 경계가 먼저 들어온 큐에서
// peekBreak 의 순회 break 가 앞 경계 뒤를 보지 못해 조용한 미탐이 된다.
func (ix *Indexer) enqueueBreak(streamID string, b sessionBreak) {
	q := ix.breaks[streamID]
	i := sort.Search(len(q), func(i int) bool { return q[i].OnlineAt.After(b.OnlineAt) })
	q = append(q, sessionBreak{})
	copy(q[i+1:], q[i:])
	q[i] = b

	if len(q) > breakQueueMax {
		dropped := q[0]
		q = q[1:]
		ix.log.Warn("hook_break_queue_overflow",
			"stream_id", streamID, "limit", breakQueueMax,
			"dropped_offline_at", dropped.OfflineAt, "dropped_online_at", dropped.OnlineAt,
			"note", "이 스트림은 세그먼트가 오지 않고 있다. 이후 GH1 은 보장되지 않는다")
	}
	ix.breaks[streamID] = q
}
