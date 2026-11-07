package publish

// 회차 단위 VOD 의 순수 함수 시험이다(POK-549 C3 · 설계 r4 2.2) — 범위 판정(decideScope) 표 · 회차 키 · 대기 사유
// 술어 · 회차 경로 결과 분류 · 회차 plan 의 abandon 범위.

import (
	"errors"
	"fmt"
	"math"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// roundChain 은 회차 R 의 마지막 사슬 ids 다(머리가 처음 · 꼬리가 끝).
func roundChain(ids ...string) index.Chain {
	return index.Chain{StreamID: fxStream, RoundID: "R", Head: ids[0], Tail: ids[len(ids)-1], SessionIDs: ids}
}

// member 는 판정 재료 회원 하나다 — 문서는 종국 상태(frozen · frozen_with_gaps)일 때 있다.
func member(id, state string, rows bool) index.RoundMember {
	return index.RoundMember{SessionID: id, VODState: state, HasRows: rows,
		HasDocument: state == ResultFrozen || state == ResultFrozenWithGaps}
}

// decide_scope_table(설계 2.2 표 일곱 줄) — 위에서 처음 맞는 줄을 쓴다. 마지막 사슬은 B(꼬리)이고 앞 회원은 A 다.
// 변이 M3(4줄 제거) · M4(5줄 제거) · M6a(3줄 제거) · M6b(3 · 4줄 순서 뒤집기) · M8(6줄 제거)가 이 표에서 FAIL 한다.
func TestDecideScopeTable(t *testing.T) {
	last := roundChain("B")
	front := func(state string) index.RoundMember { return member("A", state, true) }
	self := member("B", "freezing", true)
	emptyFrozen := member("A", ResultFrozen, true)
	emptyFrozen.HasDocument = false // 빈 동결 — 키 NULL
	gate := func(closed bool, latest string, members ...index.RoundMember) index.RoundGate {
		return index.RoundGate{RoundID: "R", Closed: closed, Latest: latest, Members: members}
	}
	tests := []struct {
		name  string
		chain index.Chain
		gate  index.RoundGate
		want  scopeDecision
	}{
		{name: "1_M6_전_세션", chain: index.Chain{StreamID: fxStream, Head: "B", Tail: "B", SessionIDs: []string{"B"}},
			gate: gate(true, "B", front(ResultFrozen), self), want: scopeDecision{}},
		{name: "2_앞_사슬", chain: roundChain("A"), gate: gate(true, "B", front("freezing"), self), want: scopeDecision{}},
		{name: "2_모르는_회차", chain: last, gate: index.RoundGate{RoundID: "R"}, want: scopeDecision{}},
		{name: "3_사슬_하나", chain: last, gate: gate(true, "B", self), want: scopeDecision{}},
		{name: "3_사슬_하나_열린_회차", chain: last, gate: gate(false, "B", self), want: scopeDecision{}},
		{name: "3_앞_빈_세션", chain: last, gate: gate(false, "B", member("A", ResultFrozen, false), self), want: scopeDecision{}},
		{name: "3_컷오프_아래뿐인_앞_사슬", chain: last, gate: gate(false, "B", member("A", "freezing", false), self),
			want: scopeDecision{}},
		{name: "4_열린_회차", chain: last, gate: gate(false, "B", front(ResultFrozen), self),
			want: scopeDecision{Wait: ReasonRoundOpen}},
		{name: "4_열린_회차_앞_freezing", chain: last, gate: gate(false, "B", front("freezing"), self),
			want: scopeDecision{Wait: ReasonRoundOpen}},
		{name: "5_앞_freezing", chain: last, gate: gate(true, "B", front("freezing"), self),
			want: scopeDecision{Wait: ReasonFrontPending}},
		{name: "5_앞_none", chain: last, gate: gate(true, "B", front("none"), self),
			want: scopeDecision{Wait: ReasonFrontPending}},
		{name: "6_앞_freeze_failed", chain: last, gate: gate(true, "B", front(freezeStateFailed), self),
			want: scopeDecision{Fallback: FallbackFrontFailed}},
		{name: "6_앞_문서_없는_frozen", chain: last, gate: gate(true, "B", emptyFrozen, self),
			want: scopeDecision{Fallback: FallbackFrontNoDocument}},
		{name: "7_회차", chain: last, gate: gate(true, "B", front(ResultFrozen), self), want: scopeDecision{Round: true}},
		{name: "7_회차_앞_GAP_문서", chain: last, gate: gate(true, "B", front(ResultFrozenWithGaps), self),
			want: scopeDecision{Round: true}},
		{name: "7_앞_사슬_둘", chain: roundChain("C", "D"),
			gate: gate(true, "D", front(ResultFrozen), member("B", ResultFrozenWithGaps, true), member("C", "freezing", false),
				member("D", "freezing", true)),
			want: scopeDecision{Round: true}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := decideScope(tt.chain, tt.gate); got != tt.want {
				t.Errorf("decideScope = %+v, want %+v", got, tt.want)
			}
		})
	}
}

// decide_scope_failed_before_no_document — 앞 회원에 freeze_failed 와 문서 없는 frozen 이 함께 있으면 freeze_failed 가
// 까닭이다(장애가 더 무거운 신호 — 어느 회원 순서든).
func TestDecideScopeFailedWinsOverNoDocument(t *testing.T) {
	empty := member("A", ResultFrozen, true)
	empty.HasDocument = false
	for _, members := range [][]index.RoundMember{
		{empty, member("B", freezeStateFailed, true), member("C", "freezing", true)},
		{member("A", freezeStateFailed, true), empty, member("C", "freezing", true)},
	} {
		g := index.RoundGate{RoundID: "R", Closed: true, Latest: "C", Members: members}
		if got := decideScope(roundChain("C"), g); got.Fallback != FallbackFrontFailed {
			t.Errorf("decideScope(%+v) = %+v, want Fallback %s", members, got, FallbackFrontFailed)
		}
	}
}

// vod_round_key(설계 4절 D1 · K-c) — 회차 문서 키는 vod/{stream}/{round}.round/index.m3u8 다. 둘째 성분은 점이 들어 어떤
// 세션 id 와도 같을 수 없고(session_id_dot_test.go) · seg · . · .. 가 아니며 · 다시 불러도 같다. 가장 긴 세션 id(102자)
// (seq 는 0 이상 — int64 최댓값 19자리)에서 둘째 성분은 108자다. 변이 M5(회차 키 → 머리 키)는 PG 시험이 잡는다.
func TestVODRoundKey(t *testing.T) {
	longest := "S-20261104-051100-" + strings.Repeat("s", 64) + "-" + fmt.Sprint(int64(math.MaxInt64))
	tests := []struct {
		name, stream, round, want string
	}{
		{name: "보통", stream: "str", round: "S-20261104-051100-str-4", want: "vod/str/S-20261104-051100-str-4.round/index.m3u8"},
		{name: "가장_긴_세션_id", stream: "str", round: longest, want: "vod/str/" + longest + ".round/index.m3u8"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := vodRoundKey(tt.stream, tt.round)
			if err != nil || got != tt.want {
				t.Fatalf("vodRoundKey(%q, %q) = (%q, %v), want %q", tt.stream, tt.round, got, err, tt.want)
			}
			again, _ := vodRoundKey(tt.stream, tt.round)
			if again != got {
				t.Errorf("다시 부른 키 = %q, want %q(재시도마다 같다)", again, got)
			}
			component := strings.Split(got, "/")[2]
			if component == "seg" || component == "." || component == ".." || !strings.Contains(component, ".") {
				t.Errorf("둘째 성분 %q — seg · . · .. 가 아니고 점이 들어야 한다", component)
			}
		})
	}
	if n := len(longest + ".round"); n != 108 {
		t.Errorf("가장 긴 둘째 성분 = %d자, want 108(엣지 128 안)", n)
	}
}

// vod_round_key_rejects_bad_component — 성분 검사는 목록 키(manifestKey)와 같고, 회차가 비면 키를 짓지 않는다.
func TestVODRoundKeyRejectsBadComponent(t *testing.T) {
	for _, in := range [][2]string{{"a/b", "R"}, {"str", "a/b"}, {"str", ""}, {"..", "R"}} {
		if got, err := vodRoundKey(in[0], in[1]); err == nil {
			t.Errorf("vodRoundKey(%q, %q) = %q, want 오류", in[0], in[1], got)
		}
	}
}

// vod_key_rejects_dot_head(security C3 L2) — 사슬 키의 머리 성분에는 점이 없어야 한다 — 회차 키 둘째 성분({round}.round)과
// 겹치는 사슬 키를 「세션 id 에 점이 없다」는 바깥 가정 없이 키 함수가 막는다. 점 없는 머리는 지금 키 그대로다(회차 키는 점을
// 그대로 쓴다). 고치기 전(6fcef57b)은 점 든 머리를 받아 FAIL.
func TestVODKeyRejectsDotHead(t *testing.T) {
	for _, head := range []string{"A.round", "S-1.x", "."} {
		if got, err := vodKey("str", head); err == nil {
			t.Errorf("vodKey(str, %q) = %q, want 오류(점 든 머리)", head, got)
		}
	}
	if got, err := vodKey("str", "S-20261104-051100-str-4"); err != nil || got != "vod/str/S-20261104-051100-str-4/index.m3u8" {
		t.Errorf("vodKey(점 없는 머리) = (%q, %v), want 지금 키", got, err)
	}
	if got, err := vodRoundKey("str", "A"); err != nil || got != "vod/str/A.round/index.m3u8" {
		t.Errorf("vodRoundKey(str, A) = (%q, %v), want 회차 키", got, err)
	}
}

// judge_round_rejects_other_round(security C3 L1) — 다시 읽은 회차 장부의 회차가 부른 쪽 사슬의 회차(키를 지은 값)와 다르면
// chain_changed 실패다 — 그 키에 다른 회차의 문서를 쓰지 않는다. 물러섬 대상도 아니다. 고치기 전(6fcef57b)은 plan 을 세워 FAIL.
func TestJudgeRoundRejectsOtherRound(t *testing.T) {
	v := &vodJob{chain: roundChain("B"), round: true}
	l := roundLedgerOf(3, 3)
	l.Chain.RoundID = "X"

	_, _, out, ok := v.judgeRound(l, nil, false)

	if ok || out.Result != ResultFailed || out.Reason != vodChainChanged || roundFallsBack(out) {
		t.Errorf("다른 회차 장부 = (%+v, %t), want chain_changed 실패 · 물러섬 아님", out, ok)
	}
}

// settle_wait_predicates(설계 2.2 · R21) — 정산 · 회차 대기는 장애가 아닌 재시도다. 판정부는 IsSettleWait 하나로 가른다.
// 변이 M11(술어에서 사유 하나 제거)은 이 표와 cmd 음성 대조가 잡는다.
func TestSettleWaitPredicates(t *testing.T) {
	tests := []struct {
		reason       string
		settle, wait bool
	}{
		{reason: ReasonUnsettled, settle: true},
		{reason: ReasonRoundOpen, settle: true, wait: true},
		{reason: ReasonFrontPending, settle: true, wait: true},
		{reason: freezeDB},
		{reason: vodTooLarge},
		{reason: freezeLedger},
		{reason: ""},
	}
	for _, tt := range tests {
		if got := IsSettleWait(tt.reason); got != tt.settle {
			t.Errorf("IsSettleWait(%q) = %t, want %t", tt.reason, got, tt.settle)
		}
		if got := IsRoundWait(tt.reason); got != tt.wait {
			t.Errorf("IsRoundWait(%q) = %t, want %t", tt.reason, got, tt.wait)
		}
	}
}

// round_outcome_classification(계획 C3 분류표 · K3 · K22) — 회차 경로의 결정적 실패 넷만 같은 호출에서 사슬 범위로 물러서고,
// 나머지(일시 오류 · 사슬 변경 · 이미 종국 · 정산 대기 · 표 밖 사유)는 그대로 돌려준다. 변이 「unsettled 를 물러섬으로」 ·
// M15(물러섬 제거)가 이 표에서 FAIL 한다.
func TestRoundOutcomeClassification(t *testing.T) {
	fallback := []FreezeOutcome{
		vodFailed(vodTooLarge, nil), vodFailed(freezeValidate, errors.New("S5")), vodFailed(vodForeign, nil),
		vodFailed(freezeRender, nil),
	}
	keep := []FreezeOutcome{
		vodRetry(freezeLedger, nil), vodRetry(vodLedgerWrite, nil), vodRetry(vodContended, nil), vodRetry(freezeDB, nil),
		vodRetry(freezePut412, nil), vodRetry(freezePutUnknown, nil), vodRetry(freezeHeadFailed, nil),
		vodRetry(freezeConfirm, nil), vodRetry(ReasonUnsettled, nil), vodFailed(vodChainChanged, nil),
		vodFailed(vodConflict, nil), vodFailed(freezeStateFailed, nil), vodFailed("표_밖_사유", nil),
		{Axis: AxisVOD, Result: ResultFrozen}, {Axis: AxisVOD, Result: ResultFrozenWithGaps},
	}
	for _, o := range fallback {
		if !roundFallsBack(o) {
			t.Errorf("roundFallsBack(%s %s) = false, want 물러섬", o.Result, o.Reason)
		}
	}
	for _, o := range keep {
		if roundFallsBack(o) {
			t.Errorf("roundFallsBack(%s %s) = true, want 그대로", o.Result, o.Reason)
		}
	}
}

// roundLedgerOf 는 회차 R(앞 사슬 A · 마지막 사슬 B)의 장부다 — A 의 행 0..front-1 · B 의 행 front..front+last-1(전부 settled).
func roundLedgerOf(front, last int) index.RoundLedger {
	l := index.RoundLedger{
		Chain:     roundChain("B"),
		HasCutoff: true,
		Sessions: []index.RewindSession{
			{SessionID: "A", TargetDuration: 6, InitUploaded: true, MinSeq: 0},
			{SessionID: "B", TargetDuration: 8, InitUploaded: true, MinSeq: int64(front)},
		},
	}
	for i := range front + last {
		session := "A"
		if i >= front {
			session = "B"
		}
		l.Rows = append(l.Rows, ledgerRow(session, int64(i)))
	}
	return l
}

// round_plan_too_large_falls_back(K5 ②) — 회차 plan 이 vodMaxRows 를 넘으면 too_large 실패이고 그 결과는 물러섬이다.
// 적재가 상한에서 끊은 회차(index.ErrRoundTooLarge)도 같다.
func TestRoundPlanTooLargeFallsBack(t *testing.T) {
	v := &vodJob{chain: roundChain("B")}

	_, _, out, ok := v.judgeRound(roundLedgerOf(vodMaxRows, 1), nil, false)
	if ok || out.Reason != vodTooLarge || !roundFallsBack(out) {
		t.Errorf("행 %d 개 회차 plan = (%+v, %t), want too_large 물러섬", vodMaxRows+1, out, ok)
	}
	_, _, out, ok = v.judgeRound(index.RoundLedger{}, fmt.Errorf("적재: %w", index.ErrRoundTooLarge), false)
	if ok || out.Reason != vodTooLarge || !roundFallsBack(out) {
		t.Errorf("judgeRound(ErrRoundTooLarge) = (%+v, %t), want too_large 물러섬", out, ok)
	}
	_, _, out, ok = v.judgeRound(index.RoundLedger{}, fmt.Errorf("재독: %w", index.ErrChainChanged), false)
	if ok || out.Reason != vodChainChanged || roundFallsBack(out) {
		t.Errorf("judgeRound(ErrChainChanged) = (%+v, %t), want chain_changed 그대로", out, ok)
	}
}

// round_plan_abandon_span(K6 · R3 · R26 · M2 · M18) — 회차 plan 에서 앞 회원 행은 정산을 끝낸 것으로 보고(포기 참 —
// 미착지면 GAP), 마지막 사슬 행만 부른 쪽 abandon 을 따른다. abandon 갱신 범위는 마지막 사슬 행의 첫 · 끝이다.
func TestRoundPlanAbandonsFrontRowsOnly(t *testing.T) {
	v := &vodJob{chain: roundChain("B")}
	l := roundLedgerOf(3, 3)
	l.Rows[1].PlaybackUploaded = false // 앞 회원 A 의 미착지 행

	_, plan, out, ok := v.judgeRound(l, nil, false)

	if !ok {
		t.Fatalf("회차 plan = %+v, want 통과(앞 회원 미착지 행은 기다리지 않는다)", out)
	}
	if want := []vodMark{{seq: 1, session: "A", reason: "settle_deadline"}}; fmt.Sprint(plan.marks) != fmt.Sprint(want) {
		t.Errorf("GAP 원장 = %+v, want %+v", plan.marks, want)
	}
	if !plan.lastChain || plan.abandonFirst != 3 || plan.abandonLast != 5 {
		t.Errorf("abandon 범위 = (%t %d–%d), want 마지막 사슬 3–5", plan.lastChain, plan.abandonFirst, plan.abandonLast)
	}
	if plan.lastChainHasGaps() {
		t.Error("lastChainHasGaps = true, want false(GAP 은 앞 사슬에만)")
	}

	l.Rows[4].PlaybackUploaded = false // 마지막 사슬 B 의 미착지 행 · 정산 창 안
	if _, _, out, ok := v.judgeRound(l, nil, false); ok || !out.Retry || out.Reason != ReasonUnsettled {
		t.Errorf("마지막 사슬 미착지 · 창 안 = (%+v, %t), want unsettled 재시도", out, ok)
	}
	_, plan, _, ok = v.judgeRound(l, nil, true)
	if !ok || !plan.lastChainHasGaps() {
		t.Errorf("마지막 사슬 미착지 · 포기 = (ok %t · 마지막 사슬 GAP %t), want 통과 · GAP 있음", ok, plan.lastChainHasGaps())
	}
}

// round_plan_without_last_chain_rows(K28) — 마지막 사슬에 컷오프 이상 행이 없고 앞 회원에만 행이 있으면 abandon 갱신
// 범위가 없다(빈 슬라이스를 색인하지 않는다).
func TestRoundPlanWithoutLastChainRows(t *testing.T) {
	v := &vodJob{chain: roundChain("B")}

	_, plan, out, ok := v.judgeRound(roundLedgerOf(3, 0), nil, true)

	if !ok || len(plan.rows) != 3 {
		t.Fatalf("회차 plan = (%+v, %t · 행 %d), want 통과 · 앞 회원 행 3", out, ok, len(plan.rows))
	}
	if plan.lastChain || plan.lastChainHasGaps() {
		t.Errorf("abandon 범위 = %t · GAP %t, want 없음", plan.lastChain, plan.lastChainHasGaps())
	}
}

// chain_plan_abandon_span_is_plan_span(K6) — 사슬 경로의 abandon 갱신 범위는 지금처럼 plan 의 첫 · 끝 행이다.
func TestChainPlanAbandonSpanIsPlanSpan(t *testing.T) {
	l := gapKeyLedger(legacyKey)
	v := &vodJob{chain: l.Chain}

	plan, out, ok := v.plan(l, true)

	if !ok || !plan.lastChain || plan.abandonFirst != plan.rows[0].Seq || plan.abandonLast != plan.rows[len(plan.rows)-1].Seq {
		t.Errorf("사슬 plan = (%+v, %t) 범위 %t %d–%d, want plan 첫 · 끝", out, ok, plan.lastChain, plan.abandonFirst, plan.abandonLast)
	}
}

// rejudge_table(P8 · K4) — 꼬리 잠금 아래 다시 읽은 판정 재료가 회차 범위면 그대로 가고, 대기면 그 재시도, 사슬 범위면
// 사슬 경로 표지(vodRescoped · Fallback = 그 까닭)다.
func TestRejudgeFollowsRereadGate(t *testing.T) {
	last := roundChain("B")
	gate := func(closed bool, latest, frontState string) index.RoundGate {
		return index.RoundGate{RoundID: "R", Closed: closed, Latest: latest,
			Members: []index.RoundMember{member("A", frontState, true), member("B", "freezing", true)}}
	}
	tests := []struct {
		name             string
		gate             index.RoundGate
		ok               bool
		reason, fallback string
		retry            bool
	}{
		{name: "회차_범위_그대로", gate: gate(true, "B", ResultFrozen), ok: true},
		{name: "열림", gate: gate(false, "B", ResultFrozen), reason: ReasonRoundOpen, retry: true},
		{name: "앞_freezing", gate: gate(true, "B", "freezing"), reason: ReasonFrontPending, retry: true},
		{name: "앞_freeze_failed", gate: gate(true, "B", freezeStateFailed), reason: vodRescoped, fallback: FallbackFrontFailed},
		{name: "최신이_바뀜", gate: gate(true, "C", ResultFrozen), reason: vodRescoped},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			out, ok := rejudge(last, tt.gate)
			if ok != tt.ok || out.Reason != tt.reason || out.Fallback != tt.fallback || out.Retry != tt.retry {
				t.Errorf("rejudge = (%+v, %t), want (Reason %q · Fallback %q · Retry %t, %t)", out, ok, tt.reason, tt.fallback,
					tt.retry, tt.ok)
			}
		})
	}
}
