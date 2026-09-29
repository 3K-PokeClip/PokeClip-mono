package publish

// 정체 사다리 1단계 — 설계 4.6.2 · 4.6.3 · 4.6.5 · 계획 4.5 A0 결정 5 · 리스크 C3 · 부기 9 · 체크리스트 A-4 · A-5.
// 루프가 holdTicks(1초)마다 스트림 하나에 한 번 평가하고(커밋 7), 평가는 루프가 든 값만 본다 — 캐시 · 발행 상태 ·
// 시각(DB · 저장소 조회 0 — 계획 2.3 main.go 행).
//
//	holdAge  = now − dueAt(k)    dueAt(k) = playback_pdt(k) + duration(k) + E2E_BUDGET    k = 스트림 머리 다음 행
//	stallAge = now − max(마지막 발행 성공을 받은 시각, 처음 소유 회차로 본 시각)            발행 이력이 있을 때만(J29)
//
//	L1  holdAge ≥ 0.5초                                  k 의 ③ 백오프를 당긴다(upload.Uploader.Expedite)
//	L2  holdAge ≥ GAP_HOLD ∧ 적격 ①②⑤(캐시 행)             k 로 GAP 틱(③ fence · ④ 미uploaded 는 GAP 트랜잭션이 본다)
//	L4  stallAge ≥ 갱신 의무 − T_edge ∧ L2 가 서지 못함     rewind_publish_stall_seconds ERROR(정체 한 번에 한 줄)
//
// L2 를 결정한 행의 「L2 가 서지 못함」은 사실로 받는다 — L2 를 결정한 평가는 GAP 틱에 차례를 주고 L4 를 누르고, 결과가
// 돌아온 그 행의 GAP 틱이 서지 않았다는 사실(LadderInput.UnstoodGapSeq)이 있을 때만 누르지 않는다(gap=unstood).
//
// 1단계라 L2 는 holdAge 단독이다(설계 4.6.5 — stallAge ≥ D_act 갈래와 L2′ 는 2단계, L3 는 M6). 사다리는 live 소유
// 회차에서만 돈다(부기 9) — 소유 회차가 ending 이 되면 꺼지고, 발행을 멈춘 사실을 회차마다 한 번 WARN 으로 남긴다(C3).

import (
	"context"
	"log/slog"
	"slices"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// stallLog 는 목록 발행이 멈췄다는 신호다 — 설계 관측 목록의 rewind_publish_stall_seconds(계획 5절 기정 신호)를 메트릭
// 기반이 없어 로그 키로 남긴다. L4 경보(ERROR)와 ending 회차의 발행 정지(C3 WARN)가 같은 이름을 쓴다(리스크 C3
// 「라벨 재사용」 · 판단 J30).
const stallLog = "rewind_publish_stall_seconds"

// L2 가 서지 못한 까닭 — L4 경보의 gap 속성 값이다(판단 J30 「귀속 판정」).
const (
	l2NoRow        = "no_row"        // ① 머리 다음 행이 캐시 뷰에 없다(유입이 멈췄거나 아직 오지 않았다)
	l2Incomplete   = "incomplete"    // ② 회차 · PDT · ③ 키 · 길이 가운데 없는 것이 있다(비귀속 행 포함)
	l2BeforeCutoff = "before_cutoff" // ⑤ 컷오프 아래 행이다
	l2Holding      = "holding"       // 적격이지만 holdAge 가 GAP_HOLD 에 못 미쳤다
	l2Unstood      = "unstood"       // 결과가 돌아온 이 행의 GAP 틱이 서지 않았다(LadderInput.UnstoodGapSeq)
)

// LadderInput 은 정체 사다리 한 번의 평가 입력이다 — 루프가 캐시 · 발행 상태로 만든 값이다(판단 J29 · J33).
type LadderInput struct {
	// StreamID 는 그 스트림이다(로그 속성).
	StreamID string
	// Snapshot 은 그 스트림의 경계 입력(캐시)이고 Window 는 그것으로 잰 이번 창이다. 사다리가 보는 행 k 는 스트림 머리
	// 다음 행(Window.HeadSeq + 1)이고 그 행의 회차는 따지지 않는다(계획 4.5 A0 결정 5 · 판단 J32).
	Snapshot boundary.Snapshot
	Window   boundary.Window
	// Owner 는 루프가 고른 소유 회차다(판단 J33) — 사다리는 live 소유 회차에서만 돈다.
	Owner index.RewindSession
	// Publish 는 소유 회차 목록의 발행 상태다(루프가 든 값) — 발행 이력(화해가 되살린 P)과 직전 틱 결과를 읽는다.
	Publish State
	// UnstoodGapSeq 는 결과가 돌아왔는데 서지 않은 직전 GAP 틱의 후보 seq 다(nil 이면 없다 — seq 0 과 가른다 ·
	// Outcome.GapSeq 와 같은 형). 결과가 돌아왔다는 것은 그 틱이 나가서 Outcome 을 돌려받았다는 뜻이다 — 아직 나가지
	// 못했거나 결과가 오지 않은 틱은 들지 않고, 후보 행의 원장 문장까지 갔는지는 묻지 않는다. 서지 않았다는 것은 그
	// 결과에 GapSeq(GAP 이 섬)도 UploadedSeq(이미 올라가 있었음)도 없다는 뜻이다 — 조건은 이것 하나라 부적격 · 출처
	// 불일치 · P0 실패 · gap_tx_failed · Err 에 더해 준비 단계(fence 획득 0행 · 화해가 P 를 바꿈 · 화해 실패)에서 끝난
	// 틱도 든다. 사다리는 L2 를 결정한 행 k 가 이 값일 때만 「L2 가 서지 못함」으로 읽고(gap=unstood), 아니면 L4 를
	// 누른다 — GAP 틱의 실패를 결정의 이력으로 추측하지 않는다(c4-fix3 개정 1 · kty #77).
	//
	// 루프가 GAP 틱 결과마다 갈아 끼운다(커밋 7) — 서지 않았으면 그 후보 seq 를 다음 평가부터 싣고, GapSeq 나
	// UploadedSeq 가 있으면 비운다(nil). 옛 값이 남아도 k 와 다르면 판정에 닿지 않는다. 단서: 준비 단계에서 끝난 틱은
	// 후보 행을 시도하지 않은 틱이다 — 이 갈래를 「서지 않음」에서 뺄지는 루프 설계(커밋 7)가 정한다. 빼려면 가를 결과
	// 칸이 있어야 하고(Outcome 에는 없다), 빼면 그 갈래가 되풀이되는 동안 L2 결정이 L4 를 누른다.
	UnstoodGapSeq *int64
	// PublishedAt 은 이 프로세스가 그 회차의 발행 성공(Outcome.Published)을 마지막으로 받은 시각이고(없으면 영값)
	// OwnedSince 는 그 회차를 처음 소유 회차로 본 시각이다. stallAge 는 둘 가운데 늦은 쪽부터 잰다(판단 J29 —
	// 설계의 원천 published_at 은 P4 가 쓰지 않는다 · 부기 42). OwnedSince 는 영값이면 안 된다 — 루프가 반드시 채운다
	// (커밋 7). 영값이면 화해가 되살린 P 만 있는 회차에서 stallAge 의 기점이 영값이 되어 L4 가 서지 않는다(무음 — 앞선
	// 경보 기록이 남은 상태면 뜻 없는 정체 초로 뜨고, ending WARN 의 seconds 도 뜻을 잃는다).
	PublishedAt time.Time
	OwnedSince  time.Time
	// Now 는 평가 시각이다.
	Now time.Time
}

// LadderState 는 한 스트림의 사다리 상태다 — 루프가 스트림마다 들고 평가가 돌려준 새 값으로 갈아 끼운다(판단 J34 —
// 발행자 State 와 같은 형). 영값은 「아직 아무것도 남기지 않았다」다. 신호의 되풀이 가드(L4 경보 · ending WARN)만
// 든다 — GAP 틱이 서지 않았다는 사실은 상태가 아니라 입력(LadderInput.UnstoodGapSeq)으로 받는다.
type LadderState struct {
	// alarmed 는 L4 경보를 남긴 정체의 기준 시각(stallAge 의 기점)이다 — 같은 정체에는 한 줄만 남긴다. 발행이
	// 성공하면 기점이 바뀌어 다시 무장한다(판단 J30 — A1 1회 가드와 같은 형).
	alarmed time.Time
	// warned 는 ending WARN 을 남긴 회차다 — 회차마다 한 번이다(리스크 C3).
	warned string
}

// LadderStep 은 사다리 한 번의 평가가 루프에 시키는 일이다. 로그(L4 경보 · ending WARN)는 EvaluateLadder 가 남긴다.
type LadderStep struct {
	// Seq 는 사다리가 본 행 k 다 — 아래 둘의 대상이다.
	Seq int64
	// Expedite 는 L1 이다 — 루프가 k 의 ③ 백오프를 당긴다(upload.Uploader.Expedite).
	Expedite bool
	// Gap 은 L2 다 — 루프가 k 를 GAP 후보로 GAP 틱을 낸다(TickInput.GapCandidate · 창은 WithGap 위에서 잰다). 틱이므로
	// 발행 억제 게이트(ShouldTick)를 지나야 낸다.
	Gap bool
}

// EvaluateLadder 는 정체 사다리를 한 번 평가한다(파일 머리의 표) — st 는 그 스트림의 직전 사다리 상태이고, 새 상태와
// 루프가 할 일을 돌려준다. 컷오프가 없는 스트림(되감기를 제공하지 않는다)에는 할 일이 없다. 판정은 evaluateLadder 가
// 하고 이 메서드는 그 신호(L4 경보 · ending WARN)를 로그로 남기기만 한다 — 되풀이 가드는 돌려준 상태에 있으므로, 돌려준
// 상태를 갈아 끼우지 않고 같은 상태로 다시 부르면 같은 신호가 다시 남는다. 루프는 holdTicks 마다 평가하고 돌려준 상태로
// 갈아 끼운다(커밋 7). GAP 틱이 서지 않았다는 사실은 그 결과를 받은 루프가 입력(UnstoodGapSeq)으로 싣는다.
func (p *Publisher) EvaluateLadder(ctx context.Context, st LadderState, in LadderInput) (LadderState, LadderStep) {
	next, step, sig := evaluateLadder(p.opt, st, in)
	if sig != nil {
		p.log.Log(ctx, sig.level, stallLog, sig.attrs...)
	}
	return next, step
}

// ladderSignal 은 평가가 남길 신호 한 줄이다 — stallLog 키의 등급과 속성(키 · 값 쌍)이다.
type ladderSignal struct {
	level slog.Level
	attrs []any
}

// evaluateLadder 는 EvaluateLadder 의 판정이다 — 입력 값과 설계값 opt 만 보고(로그 · 시계 · DB · 저장소 0) 새 상태 ·
// 할 일 · 남길 신호(없으면 nil)를 돌려준다.
func evaluateLadder(opt Options, st LadderState, in LadderInput) (LadderState, LadderStep, *ladderSignal) {
	if in.Owner.State != "live" {
		next, sig := warnEnded(st, in)
		return next, LadderStep{}, sig
	}
	cutoff, ok := in.Snapshot.Cutoff()
	if !ok {
		return st, LadderStep{}, nil
	}
	k := in.Window.HeadSeq + 1
	row, found := rowAt(in.Snapshot, k)
	hold, holdKnown := holdAge(row, found, in.Now, opt.E2EBudget)
	step := LadderStep{Seq: k, Expedite: holdKnown && hold >= opt.ExpediteAfter}
	blocked := gapIneligible(row, found, cutoff)
	if blocked == "" && hold < opt.GapHold {
		blocked = l2Holding
	}
	step.Gap = blocked == ""
	if step.Gap {
		if in.UnstoodGapSeq == nil || *in.UnstoodGapSeq != k { // GAP 틱에 차례를 주고 L4 를 누른다
			return st, step, nil
		}
		blocked = l2Unstood
	}
	next, sig := alarm(opt, st, in, blocked)
	return next, step, sig
}

// rowAt 은 경계 입력에서 seq 행이다. 없으면 거짓이다.
func rowAt(s boundary.Snapshot, seq int64) (boundary.Row, bool) {
	if rows := s.RowsFrom(seq); len(rows) > 0 && rows[0].Seq == seq {
		return rows[0], true
	}
	return boundary.Row{}, false
}

// holdAge 는 행 r 이 실렸어야 할 시각(dueAt)부터 now 까지다(설계 4.6.2 — 정상 경로는 음수). 행이 없거나 PDT · 길이가
// 없으면 잴 수 없다(거짓).
func holdAge(r boundary.Row, found bool, now time.Time, budget time.Duration) (time.Duration, bool) {
	if !found || r.PlaybackPDT.IsZero() || r.DurationMS <= 0 {
		return 0, false
	}
	return now.Sub(r.PlaybackPDT.Add(time.Duration(r.DurationMS)*time.Millisecond + budget)), true
}

// gapIneligible 은 캐시 행 r 이 GAP 적격이 아닌 까닭이다("" = 적격 — 설계 4.6.3 GAP_ELIGIBLE ① ② ⑤). ③ fence 유효와
// ④ 미uploaded 는 GAP 트랜잭션이 DB 에서 본다 — 캐시가 늦어도 허위 GAP 이 서지 않게(gapRecordSQL).
func gapIneligible(r boundary.Row, found bool, cutoff int64) string {
	switch {
	case !found:
		return l2NoRow
	case r.SessionID == "" || r.PlaybackPDT.IsZero() || r.PlaybackS3Key == "" || r.DurationMS <= 0:
		return l2Incomplete
	case r.Seq < cutoff:
		return l2BeforeCutoff
	}
	return ""
}

// alarm 은 L4 다 — 발행 이력이 있는 live 소유 회차의 stallAge 가 갱신 의무 − T_edge 에 닿았는데 L2 가 서지 못하면
// ERROR 신호 하나를 돌려준다(설계 4.6.3 「ERROR + 귀속 판정 기록」). 속성은 정체 초 · L2 가 서지 못한 까닭(blocked —
// 빈 값이 아니다) · 직전 틱의 중단 사유(없으면 빈 값)다. 첫 발행 전에는 없다 — 방송 시작 지연은 다른 신호
// 몫이다(판단 J29).
func alarm(opt Options, st LadderState, in LadderInput, blocked string) (LadderState, *ladderSignal) {
	since, ok := stallSince(in)
	if !ok || st.alarmed.Equal(since) {
		return st, nil
	}
	stall := in.Now.Sub(since)
	if stall < opt.RefreshObligation-opt.EdgeDelay {
		return st, nil
	}
	st.alarmed = since
	return st, &ladderSignal{level: slog.LevelError, attrs: []any{"stream", in.StreamID, "session", in.Owner.SessionID,
		"seconds", stall.Seconds(), "gap", blocked, "last_abort", in.Publish.lastAbort.reason}}
}

// warnEnded 는 리스크 C3 다 — live 가 아닌 소유 회차에서는 사다리가 꺼지고, 발행을 멈춘 사실을 회차마다 한 번 WARN
// 신호로 돌려준다. 한 번도 발행하지 않은 회차면 멈출 발행이 없었으니 신호가 없다(판단 J34).
func warnEnded(st LadderState, in LadderInput) (LadderState, *ladderSignal) {
	since, ok := stallSince(in)
	if !ok || st.warned == in.Owner.SessionID {
		return st, nil
	}
	st.warned = in.Owner.SessionID
	return st, &ladderSignal{level: slog.LevelWarn, attrs: []any{"stream", in.StreamID, "session", in.Owner.SessionID,
		"state", in.Owner.State, "seconds", in.Now.Sub(since).Seconds()}}
}

// stallSince 는 stallAge 의 기점이다(판단 J29) — 마지막 발행 성공을 받은 시각과 처음 소유 회차로 본 시각 가운데 늦은
// 쪽이다. 발행 이력(이 프로세스의 발행 성공 · 화해가 되살린 P)이 없으면 거짓이다.
func stallSince(in LadderInput) (time.Time, bool) {
	if in.PublishedAt.IsZero() && in.Publish.Prev == nil {
		return time.Time{}, false
	}
	if in.PublishedAt.After(in.OwnedSince) {
		return in.PublishedAt, true
	}
	return in.OwnedSince, true
}

// WithGap 은 경계 입력 s 위에 행 seq 를 GAP 원장에 든 것처럼(settled) 덮는 읽기 덮개다 — GAP 틱의 창을 재는 겹침
// 스냅숏이다(판단 J26 · 계획 3절 boundary.Snapshot 구현). 루프는 이 위에서 boundary.Compute 로 창을 정해 GAP 틱에
// 넘긴다. 그 창의 TailSeq 는 GAP 이 선 결과(Outcome.GapSeq)를 받은 뒤에만 다음 계산의 prevTail 로 쓴다 — GAP 이 서지
// 않은 틱의 겹침 꼬리를 주면 다음 목록의 MSN 이 역행한다(루프 몫 — 커밋 7).
func WithGap(s boundary.Snapshot, seq int64) boundary.Snapshot {
	return gapOverlay{Snapshot: s, seq: seq}
}

// gapOverlay 는 WithGap 의 덮개다. 컷오프는 밑 스냅숏 그대로다.
type gapOverlay struct {
	boundary.Snapshot
	seq int64
}

// RowsFrom 은 밑 스냅숏의 행에서 seq 행만 GAP 으로 바꾼 사본이다 — 밑 스냅숏의 슬라이스는 읽기만 한다(캐시 뷰 그
// 자체일 수 있다 — boundary.Snapshot 의 약속).
func (o gapOverlay) RowsFrom(from int64) []boundary.Row {
	rows := o.Snapshot.RowsFrom(from)
	i := slices.IndexFunc(rows, func(r boundary.Row) bool { return r.Seq == o.seq })
	if i < 0 {
		return rows
	}
	rows = slices.Clone(rows)
	rows[i].IsGap = true
	return rows
}
