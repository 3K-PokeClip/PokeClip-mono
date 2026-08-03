package indexer

import (
	"context"
	"sort"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
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
