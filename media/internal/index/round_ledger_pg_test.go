package index

// 회차 VOD 의 대기 판정 재료(LoadRoundGate)와 회차 장부 읽기(LoadRoundLedger · ReadRoundLedger) · 깨우기 대상
// (RoundLatestEnding)의 실 PG 시험(POK-549 C1 · 설계 2.1 · R8). 사슬 장부(finalize_pg_test.go)와 같은 픽스처
// 기준 시각(rewindT0)과 조각 심기(putRewindRow)를 쓰고, 회차 칸까지 심는 세션만 이 파일에 둔다. PG_DSN 미설정이면 전량
// skip 된다.

import (
	"context"
	"errors"
	"reflect"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// roundTestMaxRows 는 상한을 재지 않는 시험의 회차 적재 상한이다 — 픽스처 행 수보다 넉넉하다.
const roundTestMaxRows = 1000

// roundSession 은 회차 판정 재료까지 심는 세션 하나다. at 은 rewindT0 에서 초 · state 빈 값은 ending · vodState 빈 값은
// none · 빈 문자열 칸은 NULL 이다. closed 면 round_closed_at 을 채운다.
type roundSession struct {
	id, inherits, round, state, vodState, vodKey string
	at                                           int
	closed                                       bool
}

// putRoundSessions 는 stream 에 세션들을 심는다. 모두 init 확정 · TD 6 · base 0 이다. round_id 는 INSERT 때 정한다 —
// 회차 불변 트리거가 그 뒤의 변경을 거부한다.
func putRoundSessions(t *testing.T, pool *pgxpool.Pool, stream string, sessions ...roundSession) {
	t.Helper()
	nullable := func(v string) any {
		if v == "" {
			return nil
		}
		return v
	}
	for _, s := range sessions {
		at := rewindT0.Add(time.Duration(s.at) * time.Second)
		state, vodState := s.state, s.vodState
		if state == "" {
			state = "ending"
		}
		if vodState == "" {
			vodState = "none"
		}
		var endingAt, endedAt, closedAt any
		if state != "live" {
			endingAt = at.Add(time.Minute)
		}
		if state == "ended" {
			endedAt = at.Add(2 * time.Minute)
		}
		if s.closed {
			closedAt = at.Add(time.Hour)
		}
		if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions
    (session_id, stream_id, started_at, state, ending_at, ended_at, init_uploaded_at, discontinuity_base, first_pdt,
     inherits_session, target_duration, round_id, round_closed_at, vod_state, vod_s3_key)
VALUES ($1, $2, $3, $4, $5, $6, $7, 0, $3, $8, 6, $9, $10, $11, $12)`,
			s.id, stream, at, state, endingAt, endedAt, at.Add(time.Second), nullable(s.inherits), nullable(s.round),
			closedAt, vodState, nullable(s.vodKey)); err != nil {
			t.Fatalf("회차 세션 픽스처 삽입 실패 %s: %v", s.id, err)
		}
	}
}

// putRoundRows 는 session 의 조각 행 seq first..last 를 4초 간격 · ③ 확정으로 심는다(session 빈 값은 비귀속 행).
func putRoundRows(t *testing.T, pool *pgxpool.Pool, stream, session string, first, last int64) {
	t.Helper()
	for seq := first; seq <= last; seq++ {
		putRewindRow(t, pool, stream, seq, rewindT0.Add(time.Duration(seq)*4*time.Second), 4000, session, session != "")
	}
}

// 판정 재료 경우표 — 회원은 그 회차 세션만(개시 순) · Closed 는 회원 전원의 닫힘 · HasDocument 는 키 유무 · HasRows 는
// 컷오프 이상 행 유무다. 같은 스트림 다른 회차 세션과 M6 전 세션(round_id NULL — 판정식은 session_id 를 회차 열쇠로
// 삼지만 회차 VOD 의 회원이 아니다)은 섞이지 않는다.
func TestLoadRoundGateReadsJudgeMaterials(t *testing.T) {
	tests := []struct {
		name     string
		sessions []roundSession
		rows     map[string][2]int64 // 세션 → seq 첫 · 끝
		cutoff   int64               // 0 이면 컷오프 없음
		round    string
		want     RoundGate
	}{
		{
			name: "M6 전 세션은 회원이 아니다",
			sessions: []roundSession{
				{id: "RL", at: 0, state: "ended", vodState: "frozen", vodKey: "vod/s/RL/index.m3u8"},
			},
			rows:  map[string][2]int64{"RL": {1, 2}},
			round: "RL",
			want:  RoundGate{RoundID: "RL"},
		},
		{
			name: "사슬 하나 · 다른 회차 세션은 섞이지 않는다",
			sessions: []roundSession{
				{id: "S0", round: "S0", at: 0, closed: true},
				{id: "S1", inherits: "S0", round: "S0", at: 100, closed: true},
				{id: "Z0", round: "Z0", at: 900},
			},
			rows:   map[string][2]int64{"S0": {1, 2}, "S1": {3, 4}, "Z0": {5, 6}},
			cutoff: 1,
			round:  "S0",
			want: RoundGate{RoundID: "S0", Closed: true, Latest: "S1", Members: []RoundMember{
				{SessionID: "S0", VODState: "none", HasRows: true},
				{SessionID: "S1", VODState: "none", HasRows: true},
			}},
		},
		{
			name: "빈 세션은 행이 없다",
			sessions: []roundSession{
				{id: "E0", round: "E0", at: 0, closed: true},
				{id: "E1", round: "E0", at: 100, closed: true},
			},
			rows:   map[string][2]int64{"E1": {1, 2}},
			cutoff: 1,
			round:  "E0",
			want: RoundGate{RoundID: "E0", Closed: true, Latest: "E1", Members: []RoundMember{
				{SessionID: "E0", VODState: "none"},
				{SessionID: "E1", VODState: "none", HasRows: true},
			}},
		},
		{
			name: "컷오프 아래뿐인 앞 사슬은 행이 없다",
			sessions: []roundSession{
				{id: "C0", round: "C0", at: 0, closed: true},
				{id: "C1", round: "C0", at: 100, closed: true},
			},
			rows:   map[string][2]int64{"C0": {1, 2}, "C1": {3, 4}},
			cutoff: 3,
			round:  "C0",
			want: RoundGate{RoundID: "C0", Closed: true, Latest: "C1", Members: []RoundMember{
				{SessionID: "C0", VODState: "none"},
				{SessionID: "C1", VODState: "none", HasRows: true},
			}},
		},
		{
			name: "컷오프 없는 스트림은 행 전부를 센다",
			sessions: []roundSession{
				{id: "N0", round: "N0", at: 0, closed: true},
			},
			rows:  map[string][2]int64{"N0": {1, 1}},
			round: "N0",
			want: RoundGate{RoundID: "N0", Closed: true, Latest: "N0", Members: []RoundMember{
				{SessionID: "N0", VODState: "none", HasRows: true},
			}},
		},
		{
			name: "회원 하나라도 안 닫혔으면 열린 회차다",
			sessions: []roundSession{
				{id: "O0", round: "O0", at: 0, closed: true},
				{id: "O1", round: "O0", at: 100},
			},
			rows:   map[string][2]int64{"O0": {1, 2}, "O1": {3, 4}},
			cutoff: 1,
			round:  "O0",
			want: RoundGate{RoundID: "O0", Latest: "O1", Members: []RoundMember{
				{SessionID: "O0", VODState: "none", HasRows: true},
				{SessionID: "O1", VODState: "none", HasRows: true},
			}},
		},
		{
			name: "앞 사슬 VOD 상태 넷 · 문서 유무",
			sessions: []roundSession{
				{id: "F0", round: "F0", at: 0, closed: true, vodState: "freezing"},
				{id: "F1", round: "F0", at: 100, closed: true, vodState: "freeze_failed"},
				{id: "F2", round: "F0", at: 200, closed: true, vodState: "frozen"},
				{id: "F3", round: "F0", at: 300, closed: true, vodState: "frozen_with_gaps", vodKey: "vod/s/F3/index.m3u8"},
				{id: "F4", round: "F0", at: 400, closed: true},
			},
			rows:   map[string][2]int64{"F0": {1, 1}, "F1": {2, 2}, "F2": {3, 3}, "F3": {4, 4}, "F4": {5, 5}},
			cutoff: 1,
			round:  "F0",
			want: RoundGate{RoundID: "F0", Closed: true, Latest: "F4", Members: []RoundMember{
				{SessionID: "F0", VODState: "freezing", HasRows: true},
				{SessionID: "F1", VODState: "freeze_failed", HasRows: true},
				{SessionID: "F2", VODState: "frozen", HasRows: true},
				{SessionID: "F3", VODState: "frozen_with_gaps", HasDocument: true, HasRows: true},
				{SessionID: "F4", VODState: "none", HasRows: true},
			}},
		},
		{
			name:  "모르는 회차는 회원이 없다",
			round: "nope",
			want:  RoundGate{RoundID: "nope"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			const stream = "round-gate"
			putRoundSessions(t, pool, stream, tt.sessions...)
			for _, s := range tt.sessions {
				if r, ok := tt.rows[s.id]; ok {
					putRoundRows(t, pool, stream, s.id, r[0], r[1])
				}
			}
			if tt.cutoff != 0 {
				cutoffAt(t, pool, stream, tt.cutoff)
			}

			got, err := LoadRoundGate(context.Background(), pool, stream, tt.round)
			if err != nil {
				t.Fatalf("LoadRoundGate 실패: %v", err)
			}
			if !reflect.DeepEqual(got, tt.want) {
				t.Errorf("LoadRoundGate =\n%+v\nwant\n%+v", got, tt.want)
			}
		})
	}
}

// R8 — 회차 최신 세션은 판정식(LookupRoundPlayback.LatestSessionID)과 같은 세션이다. started_at 이 같으면 session_id
// 큰 쪽인데 「크다」는 PG 정렬 규칙(collation)이 정한다. 대소문자 짝(r8-a · r8-B)은 바이트 순서와 en_US 정렬이 갈리는
// 짝이라 Go 문자열 비교로 고르면 판정식과 다른 세션이 나온다(C 정렬 DB 에서는 둘이 같아 판별력이 없다). live 가 있으면
// 개시가 늦은 세션보다 그 live 가 먼저다. 깨우기 대상(RoundLatestEnding)도 같은 세션을 고른다.
func TestRoundLatestMatchesPlaybackVerdict(t *testing.T) {
	tests := []struct {
		name     string
		sessions []roundSession
	}{
		{name: "개시 동률 · 대소문자", sessions: []roundSession{
			{id: "r8-a", round: "r8-a", at: 0}, {id: "r8-B", round: "r8-a", at: 0},
		}},
		{name: "개시 동률 · 비ASCII", sessions: []roundSession{
			{id: "r8-z", round: "r8-z", at: 0}, {id: "r8-가", round: "r8-z", at: 0},
		}},
		{name: "live 가 개시 늦은 세션보다 먼저", sessions: []roundSession{
			{id: "r8-L", round: "r8-L", at: 0, state: "live"}, {id: "r8-M", round: "r8-L", at: 100},
		}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			const stream = "round-r8"
			putRoundSessions(t, pool, stream, tt.sessions...)
			round := tt.sessions[0].round
			ctx := context.Background()

			verdict, err := LookupRoundPlayback(ctx, pool, stream, round)
			if err != nil || verdict.LatestSessionID == "" {
				t.Fatalf("LookupRoundPlayback = (%+v, %v), want 최신 세션", verdict, err)
			}
			g, err := LoadRoundGate(ctx, pool, stream, round)
			if err != nil {
				t.Fatalf("LoadRoundGate 실패: %v", err)
			}
			if g.Latest != verdict.LatestSessionID {
				t.Errorf("Gate.Latest = %q, want 판정식 최신 %q", g.Latest, verdict.LatestSessionID)
			}
			id, ok, err := RoundLatestEnding(ctx, pool, stream, round, nil)
			if err != nil {
				t.Fatalf("RoundLatestEnding 실패: %v", err)
			}
			wantOK := verdict.Phase != PhaseLive
			if ok != wantOK || (ok && id != verdict.LatestSessionID) {
				t.Errorf("RoundLatestEnding = (%q, %v), want (%q, %v)", id, ok, verdict.LatestSessionID, wantOK)
			}
		})
	}
}

// 회차 장부 — 범위는 회차 세션 행의 [첫 seq, 끝 seq](컷오프 아래는 싣지 않는다)이고, 그 안의 비귀속 행도 그 자리에
// 온다. 범위 밖 행(다른 회차 · 다른 스트림)은 오지 않는다. 세션은 회차 세션 전부(개시 순) · 사슬은 장부에서 다시 읽은
// 마지막 사슬 · 판정 재료는 같은 스냅숏의 것이다. 부른 쪽 트랜잭션 안에서 읽어도(ReadRoundLedger) 같은 값이다.
//
//	P0          회차 RP(범위 밖 앞 회차)                 seq 1
//	A0          회차 A0 앞 사슬 · freeze 끝(frozen)      seq 2–3(2 는 컷오프 아래)
//	(비귀속)                                              seq 4
//	B0 ← B1     회차 A0 마지막 사슬                      seq 5–6 · 7–8
//	Z0          회차 RZ(범위 밖 뒤 회차)                 seq 9
func TestLoadRoundLedgerReadsRoundRange(t *testing.T) {
	pool := newTestPool(t)
	const stream = "round-ledger"
	putRoundSessions(t, pool, stream,
		roundSession{id: "P0", round: "P0", at: 0, closed: true},
		roundSession{id: "A0", round: "A0", at: 100, closed: true, vodState: "frozen", vodKey: "vod/s/A0/index.m3u8"},
		roundSession{id: "B0", round: "A0", at: 200, closed: true},
		roundSession{id: "B1", inherits: "B0", round: "A0", at: 300, closed: true},
		roundSession{id: "Z0", round: "Z0", at: 900},
	)
	putRoundRows(t, pool, stream, "P0", 1, 1)
	putRoundRows(t, pool, stream, "A0", 2, 3)
	putRoundRows(t, pool, stream, "", 4, 4)
	putRoundRows(t, pool, stream, "B0", 5, 6)
	putRoundRows(t, pool, stream, "B1", 7, 8)
	putRoundRows(t, pool, stream, "Z0", 9, 9)
	putPublishedGap(t, pool, stream, 3, "A0")
	cutoffAt(t, pool, stream, 3)
	putRewindRow(t, pool, "round-ledger-other", 5, rewindT0, 4000, "", false)
	ctx := context.Background()
	last := Chain{StreamID: stream, RoundID: "A0", Head: "B0", Tail: "B1", SessionIDs: []string{"B0", "B1"}}

	l, err := LoadRoundLedger(ctx, pool, last, roundTestMaxRows)
	if err != nil {
		t.Fatalf("LoadRoundLedger 실패: %v", err)
	}
	if !reflect.DeepEqual(l.Chain, last) {
		t.Errorf("사슬 = %+v, want %+v", l.Chain, last)
	}
	if !l.HasCutoff || l.CutoffSeq != 3 {
		t.Errorf("컷오프 = (%d, %v), want (3, 참)", l.CutoffSeq, l.HasCutoff)
	}
	var seqs []int64
	for _, r := range l.Rows {
		seqs = append(seqs, r.Seq)
	}
	if !slices.Equal(seqs, []int64{3, 4, 5, 6, 7, 8}) {
		t.Errorf("행 seq = %v, want [3 4 5 6 7 8]", seqs)
	}
	if len(l.Rows) == 6 && (!l.Rows[0].IsGap || l.Rows[0].SessionID != "A0" || l.Rows[1].SessionID != "") {
		t.Errorf("앞 두 행 = %+v · %+v, want A0 GAP 원장 행 · 비귀속 행", l.Rows[0], l.Rows[1])
	}
	var ids []string
	for _, s := range l.Sessions {
		ids = append(ids, s.SessionID)
	}
	if !slices.Equal(ids, []string{"A0", "B0", "B1"}) {
		t.Errorf("세션 = %q, want [A0 B0 B1]", ids)
	}
	gate, err := LoadRoundGate(ctx, pool, stream, "A0")
	if err != nil {
		t.Fatalf("LoadRoundGate 실패: %v", err)
	}
	if !reflect.DeepEqual(l.Gate, gate) {
		t.Errorf("장부 판정 재료 =\n%+v\nwant\n%+v", l.Gate, gate)
	}

	tx, err := pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead})
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	inTx, err := ReadRoundLedger(ctx, tx, last, roundTestMaxRows)
	if err != nil {
		t.Fatalf("ReadRoundLedger 실패: %v", err)
	}
	if !reflect.DeepEqual(inTx, l) {
		t.Errorf("ReadRoundLedger =\n%+v\nwant LoadRoundLedger 와 같음\n%+v", inTx, l)
	}
}

// 사슬 대조 — 부른 쪽 세션 목록이 다시 읽은 마지막 사슬과 다르면 ErrChainChanged · 꼬리가 장부에 없으면
// ErrChainNotFound · 꼬리에 회차가 없으면(M6 전) 오류다. 어느 쪽도 회차를 렌더하지 않는다.
func TestLoadRoundLedgerRejectsChainMismatch(t *testing.T) {
	pool := newTestPool(t)
	const stream = "round-chain"
	putRoundSessions(t, pool, stream,
		roundSession{id: "M0", round: "M0", at: 0, closed: true},
		roundSession{id: "M1", inherits: "M0", round: "M0", at: 100, closed: true},
		roundSession{id: "L0", at: 200},
	)
	ctx := context.Background()

	_, err := LoadRoundLedger(ctx, pool, Chain{StreamID: stream, Tail: "M1", SessionIDs: []string{"M1"}}, roundTestMaxRows)
	if !errors.Is(err, ErrChainChanged) {
		t.Errorf("다른 세션 목록 오류 = %v, want ErrChainChanged", err)
	}
	_, err = LoadRoundLedger(ctx, pool, Chain{StreamID: "round-chain-other", Tail: "M1"}, roundTestMaxRows)
	if !errors.Is(err, ErrChainNotFound) {
		t.Errorf("다른 스트림 꼬리 오류 = %v, want ErrChainNotFound", err)
	}
	if _, err := LoadRoundLedger(ctx, pool, Chain{StreamID: stream, Tail: "L0"}, roundTestMaxRows); err == nil {
		t.Errorf("회차 없는 꼬리의 LoadRoundLedger 오류 = nil, want 오류")
	}
}

// 적재 상한 — 회차 범위의 컷오프 이상 행이 maxRows 를 넘으면 행을 다 쌓기 전에 ErrRoundTooLarge 다. 사슬 장부의
// ErrChainTooLarge 와 다른 값이라 발행 층이 회차 경로 실패(물러섬)로 가를 수 있다. 컷오프 아래 행은 세지 않는다.
func TestLoadRoundLedgerCapsRowsAtOrAboveCutoff(t *testing.T) {
	pool := newTestPool(t)
	const stream = "round-cap"
	putRoundSessions(t, pool, stream,
		roundSession{id: "K0", round: "K0", at: 0, closed: true},
		roundSession{id: "K1", round: "K0", at: 100, closed: true},
	)
	putRoundRows(t, pool, stream, "K0", 1, 3)
	putRoundRows(t, pool, stream, "K1", 4, 5)
	cutoffAt(t, pool, stream, 2)
	ctx := context.Background()
	last := Chain{StreamID: stream, Tail: "K1", SessionIDs: []string{"K1"}}

	l, err := LoadRoundLedger(ctx, pool, last, 4)
	if err != nil || len(l.Rows) != 4 {
		t.Fatalf("상한 4 · 컷오프 이상 4행 = (행 %d, %v), want (4, nil)", len(l.Rows), err)
	}
	_, err = LoadRoundLedger(ctx, pool, last, 3)
	if !errors.Is(err, ErrRoundTooLarge) || errors.Is(err, ErrChainTooLarge) {
		t.Errorf("상한 3 오류 = %v, want ErrRoundTooLarge(ErrChainTooLarge 아님)", err)
	}
}

// 깨우기 대상 — 최신 세션이 ending 이고 except(부른 사슬) 밖일 때만 그 id 다. 최신이 live · ended · except 안이거나
// 모르는 회차면 ok=false 다.
func TestRoundLatestEnding(t *testing.T) {
	tests := []struct {
		name     string
		sessions []roundSession
		except   []string
		wantID   string
		wantOK   bool
	}{
		{
			name:     "최신 ending · except 밖",
			sessions: []roundSession{{id: "W0", round: "W0", at: 0, state: "ended"}, {id: "W1", round: "W0", at: 100}},
			except:   []string{"W0"},
			wantID:   "W1", wantOK: true,
		},
		{
			name:     "최신이 except 안",
			sessions: []roundSession{{id: "W0", round: "W0", at: 0}, {id: "W1", round: "W0", at: 100}},
			except:   []string{"W0", "W1"},
		},
		{
			name:     "최신 live",
			sessions: []roundSession{{id: "W0", round: "W0", at: 0}, {id: "W1", round: "W0", at: 100, state: "live"}},
			except:   []string{"W0"},
		},
		{
			name:     "최신 ended",
			sessions: []roundSession{{id: "W0", round: "W0", at: 0}, {id: "W1", round: "W0", at: 100, state: "ended"}},
			except:   []string{"W0"},
		},
		{
			name: "모르는 회차",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			const stream = "round-wake"
			putRoundSessions(t, pool, stream, tt.sessions...)

			id, ok, err := RoundLatestEnding(context.Background(), pool, stream, "W0", tt.except)
			if err != nil || id != tt.wantID || ok != tt.wantOK {
				t.Errorf("RoundLatestEnding = (%q, %v, %v), want (%q, %v, nil)", id, ok, err, tt.wantID, tt.wantOK)
			}
		})
	}
}
