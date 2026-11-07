package publish

// 회차 단위 VOD — POK-549 설계 r4 2.2. 사슬이 둘 이상인 회차는 마지막 사슬(회차 최신 세션이 든 사슬)의 VOD 동결이 회차
// 전체를 한 문서(회차 키)로 렌더한다. 범위는 판정(decideScope)이 정한다:
//
//	사슬 범위 · 지금 키   회차 범위가 마지막 사슬 범위와 같다(사슬 하나 · M6 전 세션 · 앞 사슬이 컷오프 아래뿐) — 지금과 바이트 동일
//	회차 대기            회차가 열렸거나 앞 사슬 VOD 가 종국 전 — 장애가 아닌 재시도(IsSettleWait)
//	회차 범위 · 회차 키   그 밖
//
// 회차 경로가 결정적으로 실패하면(roundFallsBack) 같은 호출에서 사슬 범위로 물러선다(K-a kty 확정 2026-11-04). 그 결과의
// Result · Retry · Reason · Err 는 그 사슬 호출의 것이고, 회차 쪽 원인은 FallbackCause · FallbackErr 에 싣는다(Z3) —
// 판정부가 Reason 하나로 장애 횟수를 세므로 회차 원인을 Reason 에 실으면 정산 대기가 장애로 세어진다(R29).

import (
	"context"
	"errors"
	"fmt"
	"slices"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// 회차 대기 사유 — FreezeOutcome.Reason 값이다. 둘 다 장애가 아니다(오래 기다림 경보가 구분해 싣는다).
const (
	ReasonRoundOpen    = "round_open"    // 회차가 아직 닫히지 않았다
	ReasonFrontPending = "front_pending" // 행이 있는 앞 사슬의 VOD 가 아직 종국이 아니다
)

// IsSettleWait 는 reason 이 정산 · 회차 대기(장애가 아닌 재시도)인가다 — unsettled · round_open · front_pending.
// 판정부가 이 술어 하나로 갈라 센다(사유를 하나씩 나열하다 빠뜨리는 일을 구조로 막는다 — R21).
func IsSettleWait(reason string) bool {
	return reason == ReasonUnsettled || IsRoundWait(reason)
}

// IsRoundWait 는 reason 이 회차 대기(round_open · front_pending)인가다 — 오래 기다림 경보가 쓴다.
func IsRoundWait(reason string) bool {
	return reason == ReasonRoundOpen || reason == ReasonFrontPending
}

// 물러선 까닭 — FreezeOutcome.Fallback 값이다(평시 빈 값).
const (
	FallbackFrontFailed     = "front_freeze_failed"
	FallbackFrontNoDocument = "front_no_document"   // (K-b kty 확정 2026-11-04)
	FallbackRoundRender     = "round_render_failed" // (K-a kty 확정 2026-11-04)
)

// VOD 동결의 범위 — FreezeOutcome.Scope 값이다.
const (
	ScopeChain = "chain" // 사슬 범위 · 지금 키(vodKey)
	ScopeRound = "round" // 회차 범위 · 회차 키(vodRoundKey)
)

// vodRescoped 는 꼬리 잠금 아래 재판정이 사슬 범위로 갔다는 내부 표지다(계획 P8) — FreezeVOD 밖으로 나가지 않는다.
// 그 결과의 Fallback 이 재판정의 까닭이다.
const vodRescoped = "rescoped_chain"

// scopeDecision 은 decideScope 의 결과다.
type scopeDecision struct {
	Round    bool   // 참이면 회차 범위 · 회차 키
	Wait     string // 비지 않으면 회차 대기 사유(ReasonRoundOpen · ReasonFrontPending)
	Fallback string // 사슬 범위로 물러선 까닭(평시 빈 값)
}

// decideScope 는 마지막 사슬 c 의 VOD 동결 범위를 판정 재료 g 로 정한다 — 위에서부터 처음 맞는 줄이다(설계 2.2 표).
// 앞 회원은 회차 세션 가운데 c 밖에 있고 렌더할 행이 있는 세션이다.
//
//	1 c.RoundID 없음(M6 전 세션)                     사슬 범위
//	2 g.Latest ≠ c.Tail(c 가 앞 사슬)                사슬 범위
//	3 앞 회원 없음(사슬 하나와 같다)                  사슬 범위 — 회차 닫힘을 기다리지 않는다(3줄이 4줄 앞)
//	4 회차가 열렸다                                   대기 round_open
//	5 앞 회원 VOD 가 종국 전                          대기 front_pending
//	6 앞 회원이 freeze_failed · 문서 없는 종국        사슬 범위 + Fallback front_freeze_failed · front_no_document
//	7 그 밖                                           회차 범위
//
// 회차 세션은 seq 순으로 개시한다(앞 세션의 행이 뒤 세션의 행보다 앞) — 그래서 컷오프가 앞 사슬을 넘으면 그 회원은 HasRows
// 거짓이라 앞 회원이 아니고, 뒤 사슬의 첫 렌더 행은 max(MinSeq, 컷오프)다. 이 불변식이 깨진 입력은 발행 전 검사 S5 (b)
// 위반 → validate 실패 → 사슬 범위 물러섬(안전 쪽)으로 간다.
func decideScope(c index.Chain, g index.RoundGate) scopeDecision {
	if c.RoundID == "" || g.Latest != c.Tail {
		return scopeDecision{}
	}
	var front []index.RoundMember
	for _, m := range g.Members {
		if m.HasRows && !slices.Contains(c.SessionIDs, m.SessionID) {
			front = append(front, m)
		}
	}
	switch {
	case len(front) == 0:
		return scopeDecision{}
	case !g.Closed:
		return scopeDecision{Wait: ReasonRoundOpen}
	case slices.ContainsFunc(front, func(m index.RoundMember) bool { return !vodTerminal(m.VODState) }):
		return scopeDecision{Wait: ReasonFrontPending}
	case slices.ContainsFunc(front, func(m index.RoundMember) bool { return m.VODState == freezeStateFailed }):
		return scopeDecision{Fallback: FallbackFrontFailed}
	case slices.ContainsFunc(front, func(m index.RoundMember) bool { return !m.HasDocument }):
		return scopeDecision{Fallback: FallbackFrontNoDocument}
	}
	return scopeDecision{Round: true}
}

// vodTerminal 은 vod_state 가 종국(더는 바뀌지 않음)인가다.
func vodTerminal(state string) bool {
	return state == ResultFrozen || state == ResultFrozenWithGaps || state == freezeStateFailed
}

// roundFallsBack 은 회차 경로 결과 o 가 같은 호출의 사슬 범위 물러섬 대상인가다(계획 C3 분류표 · K3 · K22). 결정적 실패
// 넷만 물러선다 — 행 · 본문 상한(too_large) · 발행 전 검사(validate) · 범위 안 다른 회차 행(foreign_session) · 렌더할 수
// 없는 입력(render). 그 밖(일시 오류 · chain_changed · vod_conflict · 정산 대기 · 이미 종국 · 표 밖 사유)은 그대로다 —
// 재시도는 사다리가 다시 부르고, chain_changed 는 사슬 경로도 같은 결과다.
func roundFallsBack(o FreezeOutcome) bool {
	switch o.Reason {
	case vodTooLarge, freezeValidate, vodForeign, freezeRender:
		return o.Result == ResultFailed
	}
	return false
}

// vodRoundKey 는 회차 roundID 의 VOD 객체 키다(K-c kty 확정 2026-11-04).
//
//	vod/{streamID}/{roundID}.round/index.m3u8
//
// 둘째 성분에 점이 들어 어떤 사슬 키(vodKey — 점 든 머리를 거부한다)와도 겹치지 않는다(R6). 성분 검사는 목록 키와 같다.
func vodRoundKey(streamID, roundID string) (string, error) {
	if roundID == "" {
		return "", errors.New("publish: 회차가 없어 회차 VOD 키를 지을 수 없다")
	}
	return vodObjectKey(streamID, roundID+".round")
}

// FreezeChainVOD 는 회차 판정 없이 사슬 범위 · 지금 키로 동결한다 — 물러섬을 이미 겪은 세션에 루프가 부른다.
func (p *Publisher) FreezeChainVOD(ctx context.Context, chain index.Chain, abandon bool) FreezeOutcome {
	if len(chain.SessionIDs) == 0 {
		return vodFailed(vodChainChanged, errEmptyChain)
	}
	return p.freezeChain(ctx, chain, abandon, "")
}

// freezeScoped 는 판정 d 대로 동결한다 — 대기면 재시도, 사슬 범위면 사슬 경로, 회차 범위면 회차 경로와 그 물러섬이다.
func (p *Publisher) freezeScoped(ctx context.Context, chain index.Chain, abandon bool, d scopeDecision) FreezeOutcome {
	switch {
	case d.Wait != "":
		out := vodRetry(d.Wait, nil)
		out.Scope = ScopeRound
		return out
	case !d.Round:
		return p.freezeChain(ctx, chain, abandon, d.Fallback)
	}
	out := p.freezeRound(ctx, chain, abandon)
	switch {
	case out.Reason == vodRescoped:
		return p.freezeChain(ctx, chain, abandon, out.Fallback)
	case roundFallsBack(out):
		co := p.freezeChain(ctx, chain, abandon, FallbackRoundRender)
		co.FallbackCause, co.FallbackErr = out.Reason, out.Err
		return co
	}
	return out
}

// freezeChain 은 사슬 범위 · 지금 키의 동결이다 — fallback 은 물러선 까닭(평시 빈 값)이다.
func (p *Publisher) freezeChain(ctx context.Context, chain index.Chain, abandon bool, fallback string) FreezeOutcome {
	out := p.runVOD(ctx, chain, abandon, false)
	out.Scope, out.Fallback = ScopeChain, fallback
	return out
}

// freezeRound 는 회차 범위 · 회차 키의 동결이다.
func (p *Publisher) freezeRound(ctx context.Context, chain index.Chain, abandon bool) FreezeOutcome {
	out := p.runVOD(ctx, chain, abandon, true)
	out.Scope = ScopeRound
	return out
}

// runVOD 는 키를 정하고 동결 한 번을 돈다.
func (p *Publisher) runVOD(ctx context.Context, chain index.Chain, abandon, round bool) FreezeOutcome {
	key, err := vodKey(chain.StreamID, chain.SessionIDs[0])
	if round {
		key, err = vodRoundKey(chain.StreamID, chain.RoundID)
	}
	if err != nil {
		return vodFailed(freezeRender, err)
	}
	return (&vodJob{p: p, chain: chain, key: key, round: round}).freeze(ctx, abandon)
}

// rejudge 는 꼬리 잠금 아래 다시 읽은 판정 재료 g 로 회차 범위를 다시 판정한다(계획 P8). 회차 범위면 ok 다. 대기면 그
// 재시도를, 사슬 범위면 vodRescoped 표지(Fallback = 그 까닭)를 돌려준다 — 부른 쪽은 등재 없이 커밋한다.
func rejudge(c index.Chain, g index.RoundGate) (FreezeOutcome, bool) {
	d := decideScope(c, g)
	switch {
	case d.Round:
		return FreezeOutcome{}, true
	case d.Wait != "":
		return vodRetry(d.Wait, nil), false
	}
	out := vodFailed(vodRescoped, nil)
	out.Fallback = d.Fallback
	return out, false
}

// roundInput 은 회차 장부 l 의 렌더 입력이다 — 앞 회원은 회차 세션 가운데 다시 읽은 마지막 사슬 밖의 세션이다.
func roundInput(l index.RoundLedger) vodInput {
	in := vodInput{chain: l.Chain.SessionIDs, cutoffSeq: l.CutoffSeq, hasCutoff: l.HasCutoff, rows: l.Rows, sessions: l.Sessions,
		front: map[string]bool{}}
	for _, s := range l.Sessions {
		if !slices.Contains(l.Chain.SessionIDs, s.SessionID) {
			in.front[s.SessionID] = true
		}
	}
	return in
}

// judgeRound 는 읽은 회차 장부 l(읽기 오류 err)로 plan 을 세운다. 상한을 넘은 적재(ErrRoundTooLarge)는 too_large 실패라
// 물러섬 대상이고, 사슬 변경 · 꼬리 없음은 chain_changed 실패 · 그 밖 읽기 오류는 재시도다(사슬 경로 judge 와 같은 사다리).
// 다시 읽은 회차가 키를 지은 회차(v.chain.RoundID)와 다르면 chain_changed 실패다 — 회차 불변 트리거로 닿지 않는 방어다
// (security C3 L1).
func (v *vodJob) judgeRound(l index.RoundLedger, err error, abandon bool) (vodInput, vodPlan, FreezeOutcome, bool) {
	switch {
	case errors.Is(err, index.ErrChainChanged), errors.Is(err, index.ErrChainNotFound):
		return vodInput{}, vodPlan{}, vodFailed(vodChainChanged, err), false
	case errors.Is(err, index.ErrRoundTooLarge):
		return vodInput{}, vodPlan{}, vodFailed(vodTooLarge, err), false
	case err != nil:
		return vodInput{}, vodPlan{}, vodRetry(freezeLedger, err), false
	case l.Chain.RoundID != v.chain.RoundID:
		return vodInput{}, vodPlan{}, vodFailed(vodChainChanged, fmt.Errorf("publish: 다시 읽은 회차 %q 가 키의 회차 %q 와 다르다",
			l.Chain.RoundID, v.chain.RoundID)), false
	}
	in := roundInput(l)
	plan, out, ok := v.planInput(in, abandon)
	return in, plan, out, ok
}

// lastChainHasGaps 는 마지막 사슬 범위 [abandonFirst, abandonLast] 안에 GAP 줄이 있는가다(K26). 그 GAP 의 출처(정산 기한 ·
// 비귀속 · 앞선 DVR GAP 원장)는 가리지 않는다 — 사슬 경로의 정산 포기 경보 조건(Abandon ∧ frozen_with_gaps)과 같게 맞췄다.
func (p vodPlan) lastChainHasGaps() bool {
	if !p.lastChain {
		return false
	}
	return slices.ContainsFunc(p.rows, func(r boundary.Row) bool {
		return r.IsGap && r.Seq >= p.abandonFirst && r.Seq <= p.abandonLast
	})
}
