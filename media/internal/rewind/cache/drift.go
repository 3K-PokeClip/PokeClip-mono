package cache

// 드리프트 대조와 뒤처짐 — 30초 감시의 (a) 드리프트 대조(설계 4.1 (a)) · 캐시 뷰가 장부보다 뒤처졌다는 표지(Behind) · 놓친 행을
// 되찾는 적재 요구의 백오프(stall-7125). 캐시 상태의 정의와 적재 · push 의 입구는 cache.go 다. 이 파일은 cache.go 에서 옮겨 온
// 것이다(동작 무변경).

import (
	"slices"
	"strings"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// driftBackoffMax 는 driftBackoff 의 상한이다 — 문턱이 64 감시(30초 감시로 32분)에서 멈춘다. 장부 빈자리처럼 적재로 풀리지
// 않는 뒤처짐에서 적재가 감시마다 되풀이되지 않게 늦추되, 빈자리가 채워지면 늦어도 그만큼 뒤에는 따라잡는다.
const driftBackoffMax = 5

// retuneBackoff 는 적재를 적용한 뒤 백오프를 정한다(drift 는 드리프트 적재인가). 뷰가 장부를 따라잡았으면 까닭과 상관없이
// 0 이다. 드리프트 적재가 뒤처짐을 못 풀었으면 같은 빈자리에서는 한 단 올리고(상한 driftBackoffMax) 새 빈자리면 1 에서 센다.
// 드리프트가 아닌 적재가 못 풀었으면 그대로 둔다.
func (st *stream) retuneBackoff(drift bool) {
	v := st.v
	switch {
	case v == nil || v.ledgerNext <= v.nextSeq():
		st.driftBackoff = 0
	case !drift:
	case v.nextSeq() == st.holeAt:
		st.driftBackoff = min(st.driftBackoff+1, driftBackoffMax)
	default:
		st.holeAt, st.driftBackoff = v.nextSeq(), 1
	}
}

// backoff 는 지금 효력이 있는 백오프 단이다 — 뷰 끝이 백오프를 세운 빈자리에 그대로 있을 때만 driftBackoff 이고, 아니면
// 0 이다(그 빈자리는 이미 풀렸다).
func (st *stream) backoff() int {
	if st.v == nil || st.v.nextSeq() != st.holeAt {
		return 0
	}
	return st.driftBackoff
}

// DriftProbes 는 30초 감시의 드리프트 대조 입력이다 — 뷰가 있고 적재 중이 아닌 스트림마다 캐시 머리 H 의 다음
// seq(H+1)다(스트림 순 · 설계 4.1 (a) · 판단 J39). 적재 중 · 부정 표식 스트림은 대조하지 않는다(계획 4.5 A3 결정 5 —
// 적재 중 뷰는 「모른다」 상태 · 표식은 주조 push 가 푼다). 루프가 감시 작업에 싣는다(커밋 7).
func (c *Cache) DriftProbes() []index.WatchProbe {
	if c == nil {
		return nil
	}
	var out []index.WatchProbe
	for id := range c.streams {
		if v := c.readable(id); v != nil {
			out = append(out, index.WatchProbe{StreamID: id, NextSeq: v.head() + 1})
		}
	}
	slices.SortFunc(out, func(a, b index.WatchProbe) int { return strings.Compare(a.StreamID, b.StreamID) })
	return out
}

// AuditDrift 는 (a) 드리프트 대조다(설계 4.1 (a) · 계획 부기 43 · 판단 J39) — 감시 결과의 drift 행마다 그 스트림의
// 뷰와 견준다. 불일치는 셋 가운데 하나다(settled 판정은 boundary.Settled 한 곳).
//
//	컷오프      DB 컷오프 ≠ 캐시 컷오프(DB 에 없음 포함)
//	장부 끝     장부의 그 스트림 마지막 seq 가 뷰 끝 너머다
//	H+1        DB 의 H+1 행이 settled 거나 뷰에 없다
//
// 캐시가 settled 로 아는 행은 DB 에서도 settled 라(push 는 커밋 뒤에만 · settled 단조 — A3 Q2) 머리 앞쪽은 H+1 한 행이
// 가른다. 그러나 뷰가 놓친 행은 settled 대조로 잡히지 않는다 — 그 행이 ③ 영구 실패면 영원히 settled 가 아니고 GAP 은 캐시
// 행이 있어야 선다. H+1 이 뷰 안의 미settled 행이면(그 회차가 ending 이라 사다리가 꺼졌다) 그 뒤에 다른 프로세스가 넣은 새
// 회차도 H+1 대조로는 영영 보이지 않는다. 그래서 장부 끝을 따로 견준다(stall-7125 — 2026-11-16 prod).
// 장부 끝 · H+1 이 알린 행은 뷰가 장부보다 뒤처졌다는 표지로 남는다(Behind). 결과가 닿은 시점의 뷰가 그 행들을 실었거나
// 캐시 머리가 H+1 이상이면 일치다 — 입력을 뜬 뒤 push 가 닿은 경합을 불일치로 세지 않는다(〔r53b — Q-2〕).
//
// 가드 단위는 스트림이다(장부 421 P-4) — 같은 스트림이 연속 2 << backoff() 감시(평시 두 번)에서 불일치면 요구 ③ 을
// 등재하고(까닭 드리프트 · 로그 0 — P-5) 후보 seq 가 달라도 그렇다. 일치하면 연속과 백오프가 풀린다. 감시가 실패한 틱에는 루프가 이
// 메서드를 부르지 않아 세지도 풀지도 않는다. 적재 중 · 뷰가 없는 스트림은 건너뛴다(A3 결정 5).
func (c *Cache) AuditDrift(rows []index.WatchDrift) {
	for _, r := range rows {
		v := c.readable(r.StreamID)
		if v == nil {
			continue
		}
		if r.HasNext {
			v.sawLedger(r.NextSeq)
		}
		if r.HasLast {
			v.sawLedger(r.LastSeq)
		}
		st := c.lookup(r.StreamID)
		if !v.drifted(r) {
			v.misses, st.driftBackoff = 0, 0
			continue
		}
		v.misses++
		if v.misses >= 2<<st.backoff() {
			v.driftDue = true
			c.demand(r.StreamID, nil)
		}
	}
}

// drifted 는 감시 결과 r 이 이 뷰와 어긋나는가다(AuditDrift 의 불일치).
func (v *view) drifted(r index.WatchDrift) bool {
	if !r.HasCutoff || r.CutoffSeq != v.cutoff {
		return true
	}
	if r.HasLast && r.LastSeq >= v.nextSeq() {
		return true
	}
	if v.head() >= r.NextSeq || !r.HasNext {
		return false
	}
	return r.NextSeq >= v.nextSeq() || boundary.Settled(boundary.Row(r.Next), r.CutoffSeq)
}

// sawLedger 는 장부에 seq 행이 있다는 사실을 든다(ledgerNext).
func (v *view) sawLedger(seq int64) {
	v.ledgerNext = max(v.ledgerNext, seq+1)
}

// ViewNext 는 streamID 의 뷰에 이어 붙일 다음 seq 다 — 루프가 봉인을 낼 때 떠서 봉인 전 장부 끝 확인에 넘긴다(장부 마지막 seq 가
// 이 값 이상이면 뷰가 장부보다 뒤처졌다). 뷰가 없거나 적재 중이면 거짓이다(POK-591).
func (c *Cache) ViewNext(streamID string) (int64, bool) {
	v := c.readable(streamID)
	if v == nil {
		return 0, false
	}
	return v.nextSeq(), true
}

// ObserveLedgerLast 는 봉인 전 확인이 읽은 장부 마지막 seq 를 반영한다 — 뷰 끝 너머면 뒤처짐을 적고(Behind) 드리프트 까닭 적재를
// 요구한다(빈 seq 뒤 push 와 같은 길 — 같은 빈자리에서 백오프 중이면 요구하지 않는다). 뷰 끝 안이면 아무것도 바꾸지 않는다.
// 뷰가 없거나 적재 중이면 할 일이 없다(적재가 장부 끝까지 싣는다).
func (c *Cache) ObserveLedgerLast(streamID string, last int64) {
	v := c.readable(streamID)
	if v == nil || last < v.nextSeq() {
		return
	}
	v.sawLedger(last)
	if c.lookup(streamID).backoff() == 0 {
		v.driftDue = true
		c.demand(streamID, nil)
	}
}

// Behind 는 streamID 의 뷰가 장부보다 뒤처졌다는 것을 캐시가 아는가다 — 뷰 끝 다음 행이 장부에 있다(빈 seq 뒤 push ·
// 드리프트 대조 · 빈자리에서 멈춘 적재가 알렸다). 사다리가 머리 다음 행이 뷰에 없는 정체를 방송 종료 구간(no_row)과 가르는 재료다(stall-7125
// R2). 거짓은 「뒤처지지 않았다」가 아니라 「뒤처졌다는 사실을 모른다」다. 뷰가 없거나 적재 중이면 거짓이다.
func (c *Cache) Behind(streamID string) bool {
	v := c.readable(streamID)
	return v != nil && v.ledgerNext > v.nextSeq()
}

// head 는 캐시 머리 H 다 — 뷰 첫 행부터 settled 로 이어진 마지막 seq 다(boundary.Compute 의 HeadSeq — 스캔을 뷰
// 하한에서 시작한다). 〔r53b — Q-1〕 접두가 비면(뷰 첫 행이 미settled · 행이 없음) 하한 − 1 이다 — 컷오프 − 1 이
// 아니다. 뷰가 모르는 하한 앞 행을 대조하지 않는다.
func (v *view) head() int64 {
	w, _ := boundary.Compute(snapshot{v}, v.floor)
	return w.HeadSeq
}
