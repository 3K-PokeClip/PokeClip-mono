package publish

// 회차 단위 VOD 의 PG 시험이다(POK-549 C3 · 설계 r4 2.2 · 7절 R5 · R9 · R10 · R13 · R15 · R22 · R26 · R29 · 계획 K4 · K22 ·
// K26). 새 칸(Scope · Fallback*) · 새 함수(FreezeChainVOD)를 쓰므로 음성 대조 대상이 아니다 — 변이로 교정한다. 픽스처는
// vod_round_negctl_pg_test.go 의 것을 쓴다.

import (
	"context"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// stmtLog 는 풀이 보낸 문장을 적는 추적기다.
type stmtLog struct {
	mu   sync.Mutex
	sqls []string
}

func (l *stmtLog) TraceQueryStart(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.sqls = append(l.sqls, d.SQL)
	return ctx
}

func (*stmtLog) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}

// count 는 part 가 든 문장 수다.
func (l *stmtLog) count(part string) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	n := 0
	for _, s := range l.sqls {
		if strings.Contains(s, part) {
			n++
		}
	}
	return n
}

// 문장 표지 — index 의 비공개 문장 글자에서 고른 조각이다.
const (
	rowsStmtPart = "AS is_gap"                               // chainRowsSQL(사슬 · 회차 행 적재)
	gateStmtPart = "bool_and(s.round_closed_at IS NOT NULL)" // roundGateSQL(회차 판정 재료)
)

// tracedPublisher 는 p 와 같은 DB · 저장소를 쓰되 보낸 문장을 적는 발행자다.
func tracedPublisher(t *testing.T, p *Publisher, store *fakeStore) (*Publisher, *stmtLog) {
	t.Helper()
	log := &stmtLog{}
	return newPublisher(t, hookedPool(t, p.pool, log), store, "w-traced", &logRecorder{}), log
}

// wantFallback 은 결과 out 이 사슬 범위 물러섬(까닭 fallback · 원인 cause)인지 본다.
func wantFallback(t *testing.T, out FreezeOutcome, fallback, cause string) {
	t.Helper()
	if out.Scope != ScopeChain || out.Fallback != fallback || out.FallbackCause != cause {
		t.Errorf("물러섬 = Scope %q · Fallback %q · FallbackCause %q, want chain · %q · %q", out.Scope, out.Fallback,
			out.FallbackCause, fallback, cause)
	}
}

// round_vod_waits_for_front_freeze(R5 · R13) — 앞 사슬 A 의 VOD 가 freezing 이면 회차 대기(front_pending)다 — 행을 싣지
// 않는다(판정 재료 한 문장뿐 · 행 적재 0). A 가 먼저 사슬 문서로 확정하면(앞 사슬이라 사슬 범위) 다음 호출이 회차 문서다.
// 변이 M4(5줄 제거)는 첫 호출이 회차 문서를 써 FAIL.
func TestFreezeVODWaitsForFrontFreeze(t *testing.T) {
	ss := twoChainRound()
	ss[0].vod, ss[0].doc = "freezing", false
	p, store := newRoundVOD(t, ss, twoChainRows())
	traced, log := tracedPublisher(t, p, store)

	out := traced.FreezeVOD(t.Context(), chainOf("A", "B"), true)

	if !out.Retry || out.Reason != ReasonFrontPending || out.Scope != ScopeRound || out.Fallback != "" {
		t.Fatalf("앞 사슬 freezing 동결 = %+v, want 재시도 front_pending · Scope round", out)
	}
	if rows, gates := log.count(rowsStmtPart), log.count(gateStmtPart); rows != 0 || gates != 1 {
		t.Errorf("대기 호출 문장 = 행 적재 %d · 판정 재료 %d, want 0 · 1(R13)", rows, gates)
	}

	front := p.FreezeVOD(t.Context(), chainOf("A", "A"), true)
	if front.Result != ResultFrozen || front.Scope != ScopeChain {
		t.Fatalf("앞 사슬 A 동결 = %+v, want 사슬 범위 frozen", front)
	}
	wantOutcome(t, p.FreezeVOD(t.Context(), chainOf("A", "B"), true), AxisVOD, ResultFrozen, false)
	wantRoundFrozen(t, p, store, ResultFrozen, 0, 9, "B")
}

// round_vod_front_without_document_falls_back(R9 · K-b) — 앞 사슬이 freeze_failed 이거나 문서 없이 종국이면 같은 호출이
// 사슬 범위 · 지금 키로 물러서고 회차 장부는 읽지 않는다(판정 재료 문장 하나). 변이 M8(6줄 제거)은 회차 경로가 돌아 FAIL.
func TestFreezeVODFallsBackWhenFrontHasNoDocument(t *testing.T) {
	tests := []struct {
		name, state, fallback string
	}{
		{name: "앞_freeze_failed", state: freezeStateFailed, fallback: FallbackFrontFailed},
		{name: "앞_문서_없는_frozen", state: ResultFrozen, fallback: FallbackFrontNoDocument},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ss := twoChainRound()
			ss[0].vod, ss[0].doc = tt.state, false
			p, store := newRoundVOD(t, ss, twoChainRows())
			traced, log := tracedPublisher(t, p, store)

			out := traced.FreezeVOD(t.Context(), chainOf("A", "B"), false)

			wantOutcome(t, out, AxisVOD, ResultFrozen, false)
			wantFallback(t, out, tt.fallback, "")
			if gates := log.count(gateStmtPart); gates != 1 {
				t.Errorf("판정 재료 문장 %d번, want 1(회차 장부를 읽지 않는다)", gates)
			}
			if v := readVOD(t, p, "B"); strOrNil(v.key) != "vod/str/B/index.m3u8" || *v.first != 5 || *v.last != 9 {
				t.Errorf("B VOD 열 = %+v(키 %s), want 사슬 키 · 범위 5–9", v, strOrNil(v.key))
			}
		})
	}
}

// foreignRound 은 회차 A 의 두 사슬 사이(seq 5)에 다른 회차 X 의 행 하나가 낀 장부다 — 회차 경로만 foreign_session 으로
// 결정적으로 실패하고(사슬 범위 6–10 밖이라) 사슬 경로는 선다(K5 ①). B 의 행 9 는 ③ 미착지다.
func foreignRound(t *testing.T) (*Publisher, *fakeStore) {
	t.Helper()
	ss := append(twoChainRound(), roundSess{id: "X", round: "X", td: 6, vod: ResultFrozen})
	rows := append(twoChainRows()[:5], vodRow{session: "X", state: "uploaded"})
	for range 5 {
		rows = append(rows, vodRow{session: "B", state: "uploaded"})
	}
	rows[9].state = "pending"
	return newRoundVOD(t, ss, rows)
}

// round_render_failure_keeps_settle_wait(R10 · R29 · Z3) — 회차 경로가 결정적으로 실패하고 같은 호출의 사슬 경로가 정산
// 대기면 결과는 정산 대기다 — Reason 은 사슬 호출의 unsettled 이고 회차 원인은 FallbackCause · FallbackErr 에 있다. 정산
// 창이 끝나면(abandon) 같은 물러섬으로 사슬 문서를 확정한다. 변이 M15(물러섬 제거)는 foreign_session 실패로 · M21(Reason
// 에 회차 원인)은 Reason 으로 FAIL.
func TestFreezeVODRoundFailureFallsBackWithChainReason(t *testing.T) {
	p, store := foreignRound(t)

	out := p.FreezeVOD(t.Context(), chainOf("A", "B"), false)

	if !out.Retry || out.Reason != ReasonUnsettled || out.FallbackErr == nil {
		t.Fatalf("물러섬 결과 = %+v, want 재시도 unsettled · FallbackErr 있음", out)
	}
	wantFallback(t, out, FallbackRoundRender, vodForeign)

	out = p.FreezeVOD(t.Context(), chainOf("A", "B"), true)
	wantOutcome(t, out, AxisVOD, ResultFrozenWithGaps, false)
	wantFallback(t, out, FallbackRoundRender, vodForeign)
	if v := readVOD(t, p, "B"); strOrNil(v.key) != "vod/str/B/index.m3u8" || *v.first != 6 || *v.last != 10 {
		t.Errorf("B VOD 열 = %+v(키 %s), want 사슬 키 · 범위 6–10", v, strOrNil(v.key))
	}
	if body, _ := stored(store, roundDocKey); body != nil {
		t.Errorf("회차 문서가 있다 — 물러섬은 회차 키에 쓰지 않는다")
	}
}

// round_unsettled_is_not_fallback(K22) — 회차 경로에서 마지막 사슬의 정산 창 안 ③ 미착지는 물러섬이 아니라 그대로
// 정산 대기다(Fallback 빈 값 — 다음 호출도 회차 경로). 착지하면 회차 문서로 확정한다. 변이 「unsettled 를 물러섬으로」는
// Fallback 이 차 FAIL.
func TestFreezeVODRoundUnsettledStaysOnRoundPath(t *testing.T) {
	rows := twoChainRows()
	rows[7].state = "pending"
	p, store := newRoundVOD(t, twoChainRound(), rows)

	out := p.FreezeVOD(t.Context(), chainOf("A", "B"), false)

	if !out.Retry || out.Reason != ReasonUnsettled || out.Scope != ScopeRound || out.Fallback != "" || out.FallbackCause != "" {
		t.Fatalf("회차 경로 정산 대기 = %+v, want 재시도 unsettled · Scope round · Fallback 빈 값", out)
	}
	exec(t, p.pool, `UPDATE stream_segments SET playback_upload_state = 'uploaded' WHERE stream_id = $1 AND seq = 7`, fxStream)
	wantOutcome(t, p.FreezeVOD(t.Context(), chainOf("A", "B"), false), AxisVOD, ResultFrozen, false)
	wantRoundFrozen(t, p, store, ResultFrozen, 0, 9, "B")
}

// round_vs_chain_writer_order(R15) — 교체 겹침에서 사슬 경로(옛 writer 흉내 — FreezeChainVOD)와 회차 경로가 어느 순서로
// 돌아도 먼저 확정한 판 하나로 정합한다. 늦은 쪽은 제 키에 쓴 문서를 남기고(고아 많아야 하나) 먼저 확정한 결과를 받는다.
func TestFreezeVODRoundAndChainWritersConverge(t *testing.T) {
	tests := []struct {
		name       string
		roundFirst bool
		wantKey    string
	}{
		{name: "사슬_먼저", wantKey: "vod/str/B/index.m3u8"},
		{name: "회차_먼저", roundFirst: true, wantKey: roundDocKey},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p, store := newRoundVOD(t, twoChainRound(), twoChainRows())
			round := func() FreezeOutcome { return p.FreezeVOD(t.Context(), chainOf("A", "B"), false) }
			chain := func() FreezeOutcome { return p.FreezeChainVOD(t.Context(), chainOf("A", "B"), false) }
			first, second := chain, round
			if tt.roundFirst {
				first, second = round, chain
			}

			wantOutcome(t, first(), AxisVOD, ResultFrozen, false)
			wantOutcome(t, second(), AxisVOD, ResultFrozen, false)

			if v := readVOD(t, p, "B"); strOrNil(v.key) != tt.wantKey {
				t.Errorf("B VOD 키 = %s, want %s(먼저 확정한 판)", strOrNil(v.key), tt.wantKey)
			}
			if puts := store.putCalls(); len(puts) != 2 {
				t.Errorf("PUT %d번, want 2(키 둘 — 고아 하나)", len(puts))
			}
		})
	}
}

// round_unattributed_rows_and_janitor(R22 · 프로필 불변식 2) — 회차 경로가 사슬 사이 비귀속 행(② uploaded 하나 · ② pending
// 하나)을 처음 vod_abandoned 로 등재해도, janitor 의 ③ 포기 갈래는 ② uploaded 행만 고른다.
func TestFreezeVODRoundUnattributedRowsKeepJanitorRule(t *testing.T) {
	rows := append(twoChainRows()[:5], vodRow{state: "pending"}, vodRow{state: "pending"})
	for range 3 {
		rows = append(rows, vodRow{session: "B", state: "uploaded"})
	}
	p, _ := newRoundVOD(t, twoChainRound(), rows)

	wantOutcome(t, p.FreezeVOD(t.Context(), chainOf("A", "B"), false), AxisVOD, ResultFrozenWithGaps, false)

	wantGapLedger(t, p, map[int64]string{5: "A/mixed_boundary/vod_abandoned", 6: "A/mixed_boundary/vod_abandoned"})
	exec(t, p.pool, `UPDATE stream_sessions SET state = 'ended', ended_at = now() - interval '2 days'`)
	exec(t, p.pool, `
		UPDATE stream_segments
		   SET local_path = 'seg/' || seq, start_wall_utc = now() - interval '2 days', uploaded_at = now() - interval '2 days',
		       upload_state = CASE WHEN seq = 5 THEN 'uploaded' ELSE 'pending' END
		 WHERE stream_id = $1 AND seq IN (5, 6)`, fxStream)
	segs, _, err := index.NewLocalCleanupStore(p.pool).DeletableSegments(t.Context(),
		index.LocalCleanupPolicy{ReconnectWindow: time.Second, ExternalDeleteAge: 365 * 24 * time.Hour}, 10, index.LocalCursor{})
	if err != nil {
		t.Fatalf("삭제 후보 조회 실패: %v", err)
	}
	if len(segs) != 1 || segs[0].Seq != 5 {
		t.Errorf("삭제 후보 = %+v, want seq 5 하나(② pending seq 6 은 고르지 않는다)", segs)
	}
}

// round_abandon_update_spans_last_chain(R26 · K6) — 포기 동결의 vod_abandoned 갱신은 마지막 사슬 범위(5–9)만이다 — 앞 범위의
// recorded 미착지 GAP 원장 행(seq 2)은 앞 사슬 동결이 정한 그대로다. 변이 M18(범위를 plan 첫 · 끝으로)은 seq 2 로 FAIL.
func TestFreezeVODRoundAbandonUpdatesLastChainOnly(t *testing.T) {
	rows := twoChainRows()
	rows[2].state, rows[7].state = "pending", "pending"
	p, _ := newRoundVOD(t, twoChainRound(), rows)
	exec(t, p.pool, `INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason)
		VALUES ($1, 2, 'A', 'upload_stall'), ($1, 7, 'B', 'upload_stall')`, fxStream)

	wantOutcome(t, p.FreezeVOD(t.Context(), chainOf("A", "B"), true), AxisVOD, ResultFrozenWithGaps, false)

	wantGapLedger(t, p, map[int64]string{2: "A/upload_stall/recorded", 7: "B/upload_stall/vod_abandoned"})
}

// round_last_chain_gaps(K26 · R19) — LastChainHasGaps 는 마지막 사슬 범위 안 GAP 줄 유무다. 앞 사슬에만 GAP 이 있으면
// 거짓이고, 마지막 사슬 범위에 정산 기한이 아닌 GAP(앞선 DVR GAP 원장)만 있어도 참이다 — 사슬 경로의 경보 조건이 GAP 출처를
// 가리지 않는 것과 같게 맞췄다.
func TestFreezeVODRoundReportsLastChainGaps(t *testing.T) {
	tests := []struct {
		name string
		seq  int64
		want bool
	}{
		{name: "앞_사슬에만_GAP", seq: 2, want: false},
		{name: "마지막_사슬에_DVR_GAP", seq: 7, want: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p, _ := newRoundVOD(t, twoChainRound(), twoChainRows())
			session := map[bool]string{false: "A", true: "B"}[tt.seq >= 5]
			exec(t, p.pool, `INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason) VALUES ($1, $2, $3, 'upload_stall')`,
				fxStream, tt.seq, session)

			out := p.FreezeVOD(t.Context(), chainOf("A", "B"), false)

			if out.Result != ResultFrozenWithGaps || out.Scope != ScopeRound || out.LastChainHasGaps != tt.want {
				t.Errorf("동결 = %+v, want frozen_with_gaps · Scope round · LastChainHasGaps %t", out, tt.want)
			}
		})
	}
}

// round_rejudged_under_tail_lock(P8 · K4) — 잠금 밖 판정이 회차 범위였어도 꼬리 잠금 아래 다시 읽은 판정 재료가 다르면
// 그 판정을 따른다: 대기면 등재 · PUT 없이 대기 재시도, 사슬 범위면 등재 없이 커밋하고 같은 호출이 사슬 경로(그 판정의
// Fallback)로 간다. 회차 문서도 회차 등재도 남지 않는다.
func TestFreezeVODRejudgesScopeUnderTailLock(t *testing.T) {
	tests := []struct {
		name     string
		change   string
		reason   string
		fallback string
	}{
		{name: "앞_사슬이_freeze_failed_로", change: `UPDATE stream_sessions SET vod_state = 'freeze_failed', vod_s3_key = NULL
			WHERE session_id = 'A'`, fallback: FallbackFrontFailed},
		{name: "회차가_다시_열림", change: `UPDATE stream_sessions SET round_closed_at = NULL WHERE session_id = 'A'`,
			reason: ReasonRoundOpen},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rows := append(twoChainRows()[:5], vodRow{state: "uploaded"})
			for range 4 {
				rows = append(rows, vodRow{session: "B", state: "uploaded"})
			}
			p, store := newRoundVOD(t, twoChainRound(), rows)
			a, reached, release := blockedWriter(t, p, store, vodLockTailSQL)
			done := make(chan FreezeOutcome, 1)
			go func() { done <- a.FreezeVOD(t.Context(), chainOf("A", "B"), false) }()
			waitReached(t, reached, "꼬리 잠금 문장")

			exec(t, p.pool, tt.change)
			release()
			out := <-done

			if body, _ := stored(store, roundDocKey); body != nil {
				t.Errorf("회차 문서가 있다 — 재판정이 회차 범위가 아니면 회차 키에 쓰지 않는다")
			}
			wantGapLedger(t, p, map[int64]string{})
			if tt.reason != "" {
				if !out.Retry || out.Reason != tt.reason || len(store.putCalls()) != 0 {
					t.Errorf("재판정 대기 = %+v(PUT %d), want 재시도 %s · PUT 0", out, len(store.putCalls()), tt.reason)
				}
				return
			}
			wantFallback(t, out, tt.fallback, "")
			if out.Result != ResultFrozen {
				t.Errorf("재판정 물러섬 = %+v, want 사슬 범위 frozen", out)
			}
		})
	}
}

// m6_pre_chain_head(설계 미확인 · cc · cx C1 이월) — 마지막 사슬 머리 H 가 M6 전 세션(round_id NULL)이면 H 는 회차 세션이
// 아니다. H 의 행이 회차 범위 안에 들면 회차 경로가 그 행을 렌더하지 못해 같은 호출에서 사슬 범위로 물러서고, 범위 밖이면
// 회차 문서는 회차 세션 행만 싣는다(H 의 행 없음 — H 의 VOD 열도 회차 문서를 가리킨다). 개시 규칙(round_rule.go 3행 —
// M6 전 세션을 계승한 세션은 새 회차를 연다)에서 이 사슬에 앞 회원이 생길 수 없어 둘 다 도달 불가 픽스처다 — 지금 동작을
// 고정해 둔다.
func TestFreezeVODWithPreM6ChainHead(t *testing.T) {
	front := roundSess{id: "A", round: "A", td: 6, vod: ResultFrozen, doc: true}
	head := roundSess{id: "H", td: 6, vod: "freezing"}
	tail := roundSess{id: "T", round: "A", inherits: "H", td: 6, vod: "freezing"}
	rowsOf := func(sessions ...string) []vodRow {
		var rows []vodRow
		for _, s := range sessions {
			for range 3 {
				rows = append(rows, vodRow{session: s, state: "uploaded"})
			}
		}
		return rows
	}

	t.Run("범위_안", func(t *testing.T) {
		p, _ := newRoundVOD(t, []roundSess{front, head, tail}, rowsOf("A", "H", "T"))

		out := p.FreezeVOD(t.Context(), chainOf("A", "H", "T"), false)

		wantOutcome(t, out, AxisVOD, ResultFrozen, false)
		wantFallback(t, out, FallbackRoundRender, freezeRender)
		if v := readVOD(t, p, "H"); strOrNil(v.key) != "vod/str/H/index.m3u8" || *v.first != 3 || *v.last != 8 {
			t.Errorf("H VOD 열 = %+v(키 %s), want 사슬 키 · 범위 3–8", v, strOrNil(v.key))
		}
	})
	t.Run("범위_밖", func(t *testing.T) {
		p, store := newRoundVOD(t, []roundSess{head, front, tail}, rowsOf("H", "A", "T"))

		out := p.FreezeVOD(t.Context(), chainOf("A", "H", "T"), false)

		wantOutcome(t, out, AxisVOD, ResultFrozen, false)
		if out.Scope != ScopeRound {
			t.Errorf("Scope = %q, want round", out.Scope)
		}
		wantRoundFrozen(t, p, store, ResultFrozen, 3, 8, "H", "T")
	})
}

// round_cutoff_inside_later_chain(cx C3 컷오프 경계 · cc C2 축 2) — 컷오프가 뒤 사슬 중간이어도 발행 전 검사 S5 (b)(세션이
// 바뀌는 줄에만 끊김 표시)를 지키며 확정한다. 컷오프 아래 행만 있는 세션은 앞 회원이 아니다(HasRows 거짓). 두 경우:
//
//	A · B 사슬 · 컷오프 7(B 중간)       앞 회원 없음 → 사슬 범위 · B 키 · 범위 7–9 · 끊김 표시 없음(첫 행 B 가 소유)
//	A · B · C 사슬 · 컷오프 5(B 중간)   앞 회원 B → 회차 범위 · 회차 키 · 범위 5–9 · 끊김 표시는 C 의 첫 행(seq 7) 앞에만
//
// 판별: 회차 행 적재가 컷오프 아래부터 싣거나, 계승 칸 채움이 소유 세션까지 덮으면 끊김 표시 위치 · 범위로 FAIL.
func TestFreezeVODCutoffInsideLaterChain(t *testing.T) {
	tests := []struct {
		name        string
		ss          []roundSess
		sessions    string // seq 0..9 의 세션(한 글자씩)
		cutoff      int64
		chain       index.Chain
		key, scope  string
		first       int64
		discAt      []int64 // 끊김 표시가 서는 seq
		frozenTails []string
	}{
		{name: "AB_사슬_B_중간", ss: twoChainRound(), sessions: "AAAAABBBBB", cutoff: 7, chain: chainOf("A", "B"),
			key: "vod/str/B/index.m3u8", scope: ScopeChain, first: 7, frozenTails: []string{"B"}},
		{name: "ABC_사슬_B_중간", ss: []roundSess{
			{id: "A", round: "A", td: 6, vod: ResultFrozen, doc: true},
			{id: "B", round: "A", td: 6, vod: ResultFrozen, doc: true},
			{id: "C", round: "A", td: 8, vod: "freezing"},
		}, sessions: "AAAABBBCCC", cutoff: 5, chain: chainOf("A", "C"), key: roundDocKey, scope: ScopeRound, first: 5,
			discAt: []int64{7}, frozenTails: []string{"C"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rows := make([]vodRow, len(tt.sessions))
			for i, c := range tt.sessions {
				rows[i] = vodRow{session: string(c), state: "uploaded"}
			}
			p, store := newRoundVOD(t, tt.ss, rows)
			exec(t, p.pool, `UPDATE stream_cutoffs SET cutoff_seq = $2 WHERE stream_id = $1`, fxStream, tt.cutoff)

			out := p.FreezeVOD(t.Context(), tt.chain, false)

			wantOutcome(t, out, AxisVOD, ResultFrozen, false)
			if out.Scope != tt.scope || out.Fallback != "" {
				t.Errorf("동결 = %+v, want Scope %s · 물러섬 없음", out, tt.scope)
			}
			body, _ := stored(store, tt.key)
			if body == nil {
				t.Fatalf("문서 %s 가 없다", tt.key)
			}
			lines := vodLines(body)
			if len(lines) != int(10-tt.first) {
				t.Fatalf("문서 조각 %d 개, want %d(컷오프 %d 이상):\n%s", len(lines), 10-tt.first, tt.cutoff, body)
			}
			for i, line := range lines {
				seq := tt.first + int64(i)
				if got := strings.HasPrefix(line, "#EXT-X-DISCONTINUITY "); got != slices.Contains(tt.discAt, seq) {
					t.Errorf("seq %d 줄 %q — 끊김 표시는 seq %v 앞에만", seq, line, tt.discAt)
				}
			}
			for _, id := range tt.frozenTails {
				if v := readVOD(t, p, id); strOrNil(v.key) != tt.key || v.first == nil || *v.first != tt.first || v.last == nil || *v.last != 9 {
					t.Errorf("%s VOD 열 = %+v(키 %s), want %s · 범위 %d–9", id, v, strOrNil(v.key), tt.key, tt.first)
				}
			}
		})
	}
}

// vodDocHole 은 진단 질의(index.VODDocumentHolesSQL) 결과 한 행이다.
type vodDocHole struct {
	stream, session, key string
	first, last, missing int64
}

// vodDocumentHoles 는 p 의 DB 에서 진단 질의 원본을 읽기 전용 트랜잭션으로 돌린다.
func vodDocumentHoles(t *testing.T, p *Publisher) []vodDocHole {
	t.Helper()
	tx, err := p.pool.BeginTx(t.Context(), pgx.TxOptions{AccessMode: pgx.ReadOnly})
	if err != nil {
		t.Fatalf("읽기 전용 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(t.Context()) }()
	rows, err := tx.Query(t.Context(), index.VODDocumentHolesSQL)
	if err != nil {
		t.Fatalf("VODDocumentHolesSQL 실행 실패: %v", err)
	}
	holes, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (vodDocHole, error) {
		var h vodDocHole
		err := r.Scan(&h.stream, &h.session, &h.key, &h.first, &h.last, &h.missing)
		return h, err
	})
	if err != nil {
		t.Fatalf("VODDocumentHolesSQL 결과 읽기 실패: %v", err)
	}
	return holes
}

// round_documents_have_no_holes(P12 · 설계 10.8 ⑤ · 계획 C6) — 실제 확정 경로로 앞 사슬 A 의 사슬 문서(범위 0–4)와 마지막
// 사슬 B 의 회차 문서(범위 0–9)를 확정하고 회차를 ended 로 두면, 진단 질의는 0행이다(확정 순간 문서 범위의 seq 가 모두
// 장부 행이었다). 그 0행이 헛것이 아님을 보이려고 같은 DB 에서 A 의 행 하나(seq 2)를 지우면 두 문서가 missing 1 로 나온다.
func TestRoundDocumentsHaveNoHolesAfterFreeze(t *testing.T) {
	ss := twoChainRound()
	ss[0].vod, ss[0].doc = "freezing", false
	p, store := newRoundVOD(t, ss, twoChainRows())

	if front := p.FreezeVOD(t.Context(), chainOf("A", "A"), false); front.Result != ResultFrozen || front.Scope != ScopeChain {
		t.Fatalf("앞 사슬 A 동결 = %+v, want 사슬 범위 frozen", front)
	}
	wantOutcome(t, p.FreezeVOD(t.Context(), chainOf("A", "B"), false), AxisVOD, ResultFrozen, false)
	wantRoundFrozen(t, p, store, ResultFrozen, 0, 9, "B")
	endRound(t, p, "A", "B")

	if holes := vodDocumentHoles(t, p); len(holes) != 0 {
		t.Errorf("확정 직후 VODDocumentHolesSQL = %+v, want 0행", holes)
	}

	exec(t, p.pool, `DELETE FROM stream_segments WHERE stream_id = $1 AND seq = 2`, fxStream)
	want := []vodDocHole{
		{fxStream, "A", "vod/str/A/index.m3u8", 0, 4, 1},
		{fxStream, "B", roundDocKey, 0, 9, 1},
	}
	if holes := vodDocumentHoles(t, p); !slices.Equal(holes, want) {
		t.Errorf("seq 2 삭제 뒤 VODDocumentHolesSQL = %+v, want %+v", holes, want)
	}
}
