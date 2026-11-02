package index

// 동결 렌더의 장부 읽기 — 사슬 장부(LoadChainLedger)와 회차 장부(LoadSessionLedger)(POK-196 M6-1 커밋 ④ · M6
// 설계 2.2 · 3.3 사슬 간선 규칙 · 3.1 phase 3 비귀속 행 · 9절 D57).
//
// 사슬은 같은 회차 안에서 inherits_session 으로 이어진 세션들이다. 부모의 round_id 가 NULL(M6 전 세션)이면 옛
// 규칙대로 간선을 따르고, 아니면 같은 round_id 일 때만 따른다. 회차를 가로지르는 계승 간선(늦은 회차 분리가
// 만든다)은 사슬을 잇지 않는다. 여기서는 그 규칙이 SQL 에서 어떤 세션을 사슬로 묶는지와, 사슬 범위 안의 행을
// 어떤 값으로 가져오는지를 장부 실물로 잰다. PG_DSN 미설정이면 전량 skip 된다.

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// chainTestMaxRows 는 상한을 재지 않는 시험의 사슬 적재 상한이다 — 픽스처 행 수보다 넉넉하다.
const chainTestMaxRows = 1000

// setRound 는 픽스처 회차의 round_id 를 정한다("" = NULL — M6 전 세션).
func setRound(t *testing.T, pool *pgxpool.Pool, sessionID, roundID string) {
	t.Helper()
	var v any
	if roundID != "" {
		v = roundID
	}
	if _, err := pool.Exec(context.Background(),
		`UPDATE stream_sessions SET round_id = $2 WHERE session_id = $1`, sessionID, v); err != nil {
		t.Fatalf("round_id 픽스처 실패 %s: %v", sessionID, err)
	}
}

// chainSession 은 사슬 픽스처 회차 하나다 — 시작 시각(기준에서 초) · 계승 · 회차.
type chainSession struct {
	id, inherits, round string
	at                  int
}

// putChainSessions 는 stream 에 회차들을 심는다. 모두 ending(오프라인) · init 확정 · TD 6 · base 0 이다.
func putChainSessions(t *testing.T, pool *pgxpool.Pool, stream string, sessions ...chainSession) {
	t.Helper()
	for _, s := range sessions {
		at := rewindT0.Add(time.Duration(s.at) * time.Second)
		putRewindSession(t, pool, rewindSessionRow{RewindSession{SessionID: s.id, State: "ending", EndReason: "offline",
			InitUploaded: true, FirstPDT: at, InheritsSession: s.inherits, TargetDuration: 6}, stream, at})
		setRound(t, pool, s.id, s.round)
	}
}

// chainIDs 는 꼬리 tail 의 사슬을 읽어 세션 순서(개시 순)를 돌려준다.
func chainIDs(t *testing.T, pool *pgxpool.Pool, stream, tail string) []string {
	t.Helper()
	l, err := LoadChainLedger(context.Background(), pool, Chain{StreamID: stream, Tail: tail}, chainTestMaxRows)
	if err != nil {
		t.Fatalf("LoadChainLedger(%s) 실패: %v", tail, err)
	}
	return l.Chain.SessionIDs
}

// 사슬 간선 규칙 — 같은 회차의 계승은 잇고, 회차를 가로지르는 계승은 끊고, 부모 round_id 가 NULL 이면 옛 규칙대로
// 잇는다. 다른 스트림의 같은 이름 계승은 따라가지 않는다.
//
//	A0 ← A1 ← A2   회차 RA — 셋이 한 사슬
//	A2 ← B0 ← B1   B 는 A2 를 계승하지만 회차 RB 다(늦은 분리) — B 사슬은 B0 · B1 뿐
//	N0 ← N1        N0 은 round_id NULL(M6 전) · N1 은 회차 RN — 부모가 NULL 이라 옛 규칙대로 잇는다
func TestLoadChainLedgerFollowsEdgeRule(t *testing.T) {
	pool := newTestPool(t)
	const stream = "chain-edge"
	putChainSessions(t, pool, stream,
		chainSession{id: "A0", round: "RA", at: 0},
		chainSession{id: "A1", inherits: "A0", round: "RA", at: 100},
		chainSession{id: "A2", inherits: "A1", round: "RA", at: 200},
		chainSession{id: "B0", inherits: "A2", round: "RB", at: 300},
		chainSession{id: "B1", inherits: "B0", round: "RB", at: 400},
		chainSession{id: "N0", at: 500},
		chainSession{id: "N1", inherits: "N0", round: "RN", at: 600},
	)

	tests := []struct {
		tail string
		want []string
	}{
		{"A2", []string{"A0", "A1", "A2"}},
		{"B1", []string{"B0", "B1"}},
		{"N1", []string{"N0", "N1"}},
		{"A0", []string{"A0"}},
	}
	for _, tt := range tests {
		if got := chainIDs(t, pool, stream, tt.tail); !slices.Equal(got, tt.want) {
			t.Errorf("꼬리 %s 의 사슬 = %q, want %q", tt.tail, got, tt.want)
		}
	}
}

// 다른 스트림 — 꼬리가 다른 스트림 세션이면 사슬이 없다(오류). 사슬은 스트림을 넘지 않는다.
func TestLoadChainLedgerRejectsTailOfAnotherStream(t *testing.T) {
	pool := newTestPool(t)
	putChainSessions(t, pool, "chain-a", chainSession{id: "X0", round: "RX", at: 0})

	_, err := LoadChainLedger(context.Background(), pool, Chain{StreamID: "chain-b", Tail: "X0"}, chainTestMaxRows)
	if !errors.Is(err, ErrChainNotFound) {
		t.Fatalf("다른 스트림 꼬리의 LoadChainLedger 오류 = %v, want ErrChainNotFound", err)
	}
}

// 사슬 값 — Chain 의 머리 · 꼬리 · 회차 · 세션 순서는 장부에서 다시 읽은 값이다. 부른 쪽이 준 세션 목록이 다시 읽은
// 사슬과 다르면(그 사이 계승이 끊기거나 이어짐) 렌더하지 않고 ErrChainChanged 다.
func TestLoadChainLedgerRereadsChainAndRejectsDrift(t *testing.T) {
	pool := newTestPool(t)
	const stream = "chain-value"
	putChainSessions(t, pool, stream,
		chainSession{id: "V0", round: "RV", at: 0},
		chainSession{id: "V1", inherits: "V0", round: "RV", at: 100},
	)
	ctx := context.Background()

	l, err := LoadChainLedger(ctx, pool, Chain{StreamID: stream, Tail: "V1", SessionIDs: []string{"V0", "V1"}}, chainTestMaxRows)
	if err != nil {
		t.Fatalf("LoadChainLedger 실패: %v", err)
	}
	want := Chain{StreamID: stream, RoundID: "RV", Head: "V0", Tail: "V1", SessionIDs: []string{"V0", "V1"}}
	if !reflect.DeepEqual(l.Chain, want) {
		t.Errorf("사슬 = %+v, want %+v", l.Chain, want)
	}

	_, err = LoadChainLedger(ctx, pool, Chain{StreamID: stream, Tail: "V1", SessionIDs: []string{"V1"}}, chainTestMaxRows)
	if !errors.Is(err, ErrChainChanged) {
		t.Fatalf("다른 세션 목록의 LoadChainLedger 오류 = %v, want ErrChainChanged", err)
	}
}

// 사슬 범위의 행 — [사슬 첫 seq, 끝 seq] 안의 행 전부를 seq 순으로 읽는다. 사슬 밖 비귀속 행(세션 NULL — 재기동 ② ·
// init 불일치 분리 창)도 그 자리에 온다(D57 — 발행 층이 GAP 줄로 채운다). 범위 밖 행(앞 회차 · 뒤 방송 · 다른
// 스트림)은 오지 않는다. 회차는 사슬 세션만 온다. 컷오프 · ③ 상태 · GAP 원장 소속은 되감기 적재와 같은 값이다.
func TestLoadChainLedgerReadsRowsInChainRange(t *testing.T) {
	pool := newTestPool(t)
	const stream = "chain-rows"
	putChainSessions(t, pool, stream,
		chainSession{id: "P0", round: "RP", at: 0},
		chainSession{id: "C0", round: "RC", at: 100},
		chainSession{id: "C1", inherits: "C0", round: "RC", at: 200},
		chainSession{id: "Z0", round: "RZ", at: 900},
	)
	at := func(s int) time.Time { return rewindT0.Add(time.Duration(s) * time.Second) }
	for _, r := range []struct {
		seq      int64
		wall     int
		session  string
		uploaded bool
	}{
		{1, 0, "P0", true},
		{2, 100, "C0", true},
		{3, 104, "C0", true},
		{4, 150, "", false}, // 사슬 안 비귀속 행
		{5, 200, "C1", true},
		{6, 204, "C1", false},
		{7, 300, "", false}, // 사슬 끝 뒤 비귀속 행 — 범위 밖
		{8, 900, "Z0", true},
	} {
		putRewindRow(t, pool, stream, r.seq, at(r.wall), 4000, r.session, r.uploaded)
	}
	putPublishedGap(t, pool, stream, 6, "C1")
	cutoffAt(t, pool, stream, 1)
	putRewindRow(t, pool, "chain-rows-other", 3, at(104), 4000, "", false)

	l, err := LoadChainLedger(context.Background(), pool, Chain{StreamID: stream, Tail: "C1"}, chainTestMaxRows)
	if err != nil {
		t.Fatalf("LoadChainLedger 실패: %v", err)
	}

	if !l.HasCutoff || l.CutoffSeq != 1 {
		t.Errorf("컷오프 = (%d, %v), want (1, 참)", l.CutoffSeq, l.HasCutoff)
	}
	key := func(seq int64) string { return fmt.Sprintf("dvr/%s/seg/%06d.m4s", stream, seq) }
	wantRows := []RewindRow{
		{Seq: 2, SessionID: "C0", DurationMS: 4000, PlaybackPDT: at(100), PlaybackS3Key: key(2), PlaybackUploaded: true},
		{Seq: 3, SessionID: "C0", DurationMS: 4000, PlaybackPDT: at(104), PlaybackS3Key: key(3), PlaybackUploaded: true},
		{Seq: 4, DurationMS: 4000},
		{Seq: 5, SessionID: "C1", DurationMS: 4000, PlaybackPDT: at(200), PlaybackS3Key: key(5), PlaybackUploaded: true},
		{Seq: 6, SessionID: "C1", DurationMS: 4000, PlaybackPDT: at(204), PlaybackS3Key: key(6), IsGap: true},
	}
	if !reflect.DeepEqual(l.Rows, wantRows) {
		t.Errorf("행 =\n%+v\nwant\n%+v", l.Rows, wantRows)
	}
	var ids []string
	for _, s := range l.Sessions {
		ids = append(ids, s.SessionID)
	}
	if !slices.Equal(ids, []string{"C0", "C1"}) {
		t.Errorf("회차 = %q, want [C0 C1]", ids)
	}
	if c1 := l.Sessions[1]; c1.InheritsSession != "C0" || c1.MinSeq != 5 || !c1.InitUploaded || c1.TargetDuration != 6 {
		t.Errorf("C1 회차 = %+v, want 계승 C0 · 최소 seq 5 · init 확정 · TD 6", c1)
	}
}

// 행 없는 사슬 — 범위가 없어 행도 없다(발행 층이 빈 동결로 닫는다). 회차는 사슬 세션 그대로 온다.
func TestLoadChainLedgerWithoutRows(t *testing.T) {
	pool := newTestPool(t)
	const stream = "chain-empty"
	putChainSessions(t, pool, stream, chainSession{id: "E0", round: "RE", at: 0})

	l, err := LoadChainLedger(context.Background(), pool, Chain{StreamID: stream, Tail: "E0"}, chainTestMaxRows)
	if err != nil {
		t.Fatalf("LoadChainLedger 실패: %v", err)
	}
	if len(l.Rows) != 0 || len(l.Sessions) != 1 || l.HasCutoff {
		t.Errorf("행 없는 사슬 = (행 %d · 회차 %d · 컷오프 %v), want (0 · 1 · 거짓)", len(l.Rows), len(l.Sessions), l.HasCutoff)
	}
}

// 적재 상한(보안 78acee38 L-1 · db L1) — 사슬 범위의 행 읽기는 컷오프 이상만 maxRows 까지 싣는다. 컷오프 아래 행은
// 렌더하지 않으므로 세지 않고, 상한을 넘는 사슬은 행을 다 쌓기 전에 ErrChainTooLarge 다.
func TestLoadChainLedgerCapsRowsAtOrAboveCutoff(t *testing.T) {
	pool := newTestPool(t)
	const stream = "chain-cap"
	putChainSessions(t, pool, stream, chainSession{id: "K0", round: "RK", at: 0})
	at := func(s int) time.Time { return rewindT0.Add(time.Duration(s) * time.Second) }
	for seq := int64(1); seq <= 5; seq++ {
		putRewindRow(t, pool, stream, seq, at(int(seq)*4), 4000, "K0", true)
	}
	cutoffAt(t, pool, stream, 3)
	ctx := context.Background()

	l, err := LoadChainLedger(ctx, pool, Chain{StreamID: stream, Tail: "K0"}, 3)
	if err != nil {
		t.Fatalf("상한 3 · 컷오프 이상 3행의 LoadChainLedger 실패: %v", err)
	}
	var seqs []int64
	for _, r := range l.Rows {
		seqs = append(seqs, r.Seq)
	}
	if !slices.Equal(seqs, []int64{3, 4, 5}) {
		t.Errorf("행 seq = %v, want [3 4 5](컷오프 아래는 싣지 않는다)", seqs)
	}
	if _, err := LoadChainLedger(ctx, pool, Chain{StreamID: stream, Tail: "K0"}, 2); !errors.Is(err, ErrChainTooLarge) {
		t.Errorf("상한 2 의 LoadChainLedger 오류 = %v, want ErrChainTooLarge", err)
	}
}

// 회차 장부 — DVR 동결 렌더의 입력은 그 회차의 마지막 조각까지다. 뒤에 다른 회차 · 비귀속 행이 쌓였어도(되감기 적재는
// 최신 쪽부터 실어 그 꼬리를 놓친다) 회차 꼬리까지 싣고 그 뒤는 싣지 않는다. 하한은 되감기 적재와 같은 규칙(컷오프 ·
// 되짚기 · 힌트)이고, 계승 접두를 위해 앞 회차 행도 하한부터 온다.
func TestLoadSessionLedgerReadsUpToSessionTail(t *testing.T) {
	pool := newTestPool(t)
	const stream = "session-ledger"
	putChainSessions(t, pool, stream,
		chainSession{id: "S0", round: "R0", at: 0},
		chainSession{id: "S1", inherits: "S0", round: "R0", at: 100},
		chainSession{id: "T0", round: "R1", at: 900},
	)
	at := func(s int) time.Time { return rewindT0.Add(time.Duration(s) * time.Second) }
	for _, r := range []struct {
		seq     int64
		wall    int
		session string
	}{{10, 0, "S0"}, {11, 4, "S0"}, {12, 100, "S1"}, {13, 104, "S1"}, {14, 200, ""}, {15, 900, "T0"}} {
		putRewindRow(t, pool, stream, r.seq, at(r.wall), 4000, r.session, true)
	}
	cutoffAt(t, pool, stream, 11)
	ctx := context.Background()

	l, err := LoadSessionLedger(ctx, pool, stream, "S1", ledgerBounds)
	if err != nil {
		t.Fatalf("LoadSessionLedger 실패: %v", err)
	}
	var seqs []int64
	for _, r := range l.Rows {
		seqs = append(seqs, r.Seq)
	}
	if !slices.Equal(seqs, []int64{11, 12, 13}) || l.FloorSeq != 11 || !l.HasCutoff {
		t.Errorf("(행, 하한, 컷오프) = (%v, %d, %v), want ([11 12 13], 11, 참)", seqs, l.FloorSeq, l.HasCutoff)
	}
	var ids []string
	for _, s := range l.Sessions {
		ids = append(ids, s.SessionID)
	}
	if !slices.Equal(ids, []string{"S0", "S1"}) {
		t.Errorf("회차 = %q, want [S0 S1]", ids)
	}

	// 되짚기 4초 — 회차 꼬리 13 에서 거꾸로 길이를 더해 4초에 닿는 가장 늦은 seq 가 하한이다(최신 행 15 기준이 아니다).
	short, err := LoadSessionLedger(ctx, pool, stream, "S1", LedgerBounds{Lookback: 4 * time.Second, RowCap: 100})
	if err != nil {
		t.Fatalf("LoadSessionLedger(되짚기 4초) 실패: %v", err)
	}
	if short.FloorSeq != 13 || len(short.Rows) != 1 || short.Rows[0].Seq != 13 {
		t.Errorf("되짚기 4초 = (하한 %d · 행 %d), want (13 · [13])", short.FloorSeq, len(short.Rows))
	}
}

// 행 없는 회차 · 컷오프 없는 스트림 — 둘 다 행이 없다(발행 층이 빈 동결로 닫는다). 컷오프가 없으면 HasCutoff 가 거짓이다.
func TestLoadSessionLedgerWithoutRowsOrCutoff(t *testing.T) {
	pool := newTestPool(t)
	putChainSessions(t, pool, "session-norows", chainSession{id: "Q0", round: "RQ", at: 0})
	cutoffAt(t, pool, "session-norows", 0)
	putChainSessions(t, pool, "session-nocut", chainSession{id: "W0", round: "RW", at: 0})
	putRewindRow(t, pool, "session-nocut", 0, rewindT0, 4000, "W0", true)
	ctx := context.Background()

	l, err := LoadSessionLedger(ctx, pool, "session-norows", "Q0", ledgerBounds)
	if err != nil || !l.HasCutoff || len(l.Rows) != 0 {
		t.Errorf("행 없는 회차 = (컷오프 %v · 행 %d, %v), want (참 · 0, nil)", l.HasCutoff, len(l.Rows), err)
	}
	l, err = LoadSessionLedger(ctx, pool, "session-nocut", "W0", ledgerBounds)
	if err != nil || l.HasCutoff || len(l.Rows) != 0 {
		t.Errorf("컷오프 없는 스트림 = (컷오프 %v · 행 %d, %v), want (거짓 · 0, nil)", l.HasCutoff, len(l.Rows), err)
	}
}
