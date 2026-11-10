package index

// 문서 구멍 진단 질의(VODDocumentHolesSQL)의 실 PG 시험(POK-549 C6 · 설계 2.5 ② · 10.8 ① – ④). 원본 상수를 그대로
// 읽기 전용 트랜잭션 안에서 돌린다(사본 없음). 장부는 순수 INSERT 픽스처이고, 조각 행은 직접 DELETE 로 지운다 — 어느
// 삭제 경로(만료 · clip 탈퇴 정리 · 수동)든 장부에 남는 꼴은 같아서 만료 코드를 부르지 않는다. 확정 문서는 사슬 회원
// 전원이 같은 키 · 범위를 가지므로, 구멍 하나에 회원 수만큼 행이 나온다. PG_DSN 미설정이면 전량 skip 된다.
//
// 기본 픽스처(putThreeChainRound) — 닫힌 회차 R 하나에 사슬 셋:
//
//	세션     사슬       조각 seq   확정 문서 키                  문서 범위
//	A       앞 사슬 1   1 – 4     vod/{stream}/A/index.m3u8      1 – 4
//	B       앞 사슬 2   5 – 8     vod/{stream}/B/index.m3u8      5 – 8
//	C1 · C2 마지막 사슬  9 – 12    vod/{stream}/A.round/index.m3u8 1 – 12

import (
	"context"
	"fmt"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// vodHole 은 진단 질의 결과 한 행이다.
type vodHole struct {
	streamID, sessionID, vodKey string
	first, last, missing        int64
}

// queryVODHoles 는 VODDocumentHolesSQL 을 읽기 전용 트랜잭션 안에서 돌려 결과를 낸다 — 질의가 무엇이든 쓰면 25006 으로
// 실패한다.
func queryVODHoles(t *testing.T, pool *pgxpool.Pool) []vodHole {
	t.Helper()
	ctx := context.Background()
	tx, err := pool.BeginTx(ctx, pgx.TxOptions{AccessMode: pgx.ReadOnly})
	if err != nil {
		t.Fatalf("읽기 전용 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	rows, err := tx.Query(ctx, VODDocumentHolesSQL)
	if err != nil {
		t.Fatalf("VODDocumentHolesSQL 실행 실패: %v", err)
	}
	got, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (vodHole, error) {
		var h vodHole
		err := r.Scan(&h.streamID, &h.sessionID, &h.vodKey, &h.first, &h.last, &h.missing)
		return h, err
	})
	if err != nil {
		t.Fatalf("VODDocumentHolesSQL 결과 읽기 실패: %v", err)
	}
	return got
}

// docKey 는 시험 스트림의 확정 문서 키다 — 둘째 성분은 사슬 머리 세션 id 또는 `{round}.round` 다.
func docKey(stream, name string) string {
	return fmt.Sprintf("vod/%s/%s/index.m3u8", stream, name)
}

// setVODRange 는 세션들의 확정 문서 범위(vod_first_seq · vod_last_seq)를 쓴다.
func setVODRange(t *testing.T, pool *pgxpool.Pool, stream string, first, last int64, ids ...string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
UPDATE stream_sessions SET vod_first_seq = $3, vod_last_seq = $4
 WHERE stream_id = $1 AND session_id = ANY($2)`, stream, ids, first, last); err != nil {
		t.Fatalf("문서 범위 픽스처 실패 %v: %v", ids, err)
	}
}

// markExpired 는 세션들에 조회 제외 표시(expired_at)를 쓴다.
func markExpired(t *testing.T, pool *pgxpool.Pool, stream string, ids ...string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
UPDATE stream_sessions SET expired_at = now() WHERE stream_id = $1 AND session_id = ANY($2)`, stream, ids); err != nil {
		t.Fatalf("만료 표시 픽스처 실패 %v: %v", ids, err)
	}
}

// deleteRows 는 stream 의 조각 행 seq 들을 지운다 — 삭제 경로 하나를 흉내 낸다.
func deleteRows(t *testing.T, pool *pgxpool.Pool, stream string, seqs ...int64) {
	t.Helper()
	tag, err := pool.Exec(context.Background(),
		`DELETE FROM stream_segments WHERE stream_id = $1 AND seq = ANY($2)`, stream, seqs)
	if err != nil {
		t.Fatalf("조각 행 삭제 실패 %v: %v", seqs, err)
	}
	if tag.RowsAffected() != int64(len(seqs)) {
		t.Fatalf("조각 행 삭제 = %d행, want %d행(%v)", tag.RowsAffected(), len(seqs), seqs)
	}
}

// putThreeChainRound 는 stream 에 위 표의 닫힌 회차를 심는다. lastState 는 마지막 사슬 회원의 state 다(ended ·
// ending). 앞 사슬 둘은 ended 다.
func putThreeChainRound(t *testing.T, pool *pgxpool.Pool, stream, lastState string) {
	t.Helper()
	roundKey := docKey(stream, "A.round")
	putRoundSessions(t, pool, stream,
		roundSession{id: "A", round: "A", at: 0, closed: true, state: "ended", vodState: "frozen", vodKey: docKey(stream, "A")},
		roundSession{id: "B", round: "A", at: 100, closed: true, state: "ended", vodState: "frozen", vodKey: docKey(stream, "B")},
		roundSession{id: "C1", round: "A", at: 200, closed: true, state: lastState, vodState: "frozen", vodKey: roundKey},
		roundSession{id: "C2", inherits: "C1", round: "A", at: 300, closed: true, state: lastState, vodState: "frozen", vodKey: roundKey},
	)
	putRoundRows(t, pool, stream, "A", 1, 4)
	putRoundRows(t, pool, stream, "B", 5, 8)
	putRoundRows(t, pool, stream, "C1", 9, 10)
	putRoundRows(t, pool, stream, "C2", 11, 12)
	setVODRange(t, pool, stream, 1, 4, "A")
	setVODRange(t, pool, stream, 5, 8, "B")
	setVODRange(t, pool, stream, 1, 12, "C1", "C2")
}

// 지운 조각을 가리키는 ended 문서를 찾는다 — 회차 문서는 마지막 사슬 회원 수(둘)만큼 같은 키로 나오고, 지운 행이 든
// 앞 사슬 문서도 함께 나온다(같은 구멍이 사슬 문서에도 있다 — 설계 2.5). missing 은 문서 범위 안에서 빠진 행 수다.
// `+ 1` 이 빠진 질의(M22)는 성한 문서를 걸지 않지만 정확히 한 행 빠진 문서를 놓치고 두 행 경우 missing 을 1 작게 낸다 —
// 그래서 한 행 경우가 교정 시험이다(계획 K16).
func TestVODDocumentHolesFindsDocumentsMissingRows(t *testing.T) {
	const stream = "holes"
	roundKey := docKey(stream, "A.round")
	roundHoles := func(missing int64) []vodHole {
		return []vodHole{
			{stream, "C1", roundKey, 1, 12, missing},
			{stream, "C2", roundKey, 1, 12, missing},
		}
	}
	tests := []struct {
		name    string
		deleted []int64
		want    []vodHole
	}{
		{
			name: "성한 회차 문서는 0행",
			want: nil,
		},
		{
			name:    "가운데 앞 사슬 한 행",
			deleted: []int64{6},
			want:    append([]vodHole{{stream, "B", docKey(stream, "B"), 5, 8, 1}}, roundHoles(1)...),
		},
		{
			name:    "가운데 앞 사슬 두 행",
			deleted: []int64{6, 7},
			want:    append([]vodHole{{stream, "B", docKey(stream, "B"), 5, 8, 2}}, roundHoles(2)...),
		},
		{
			name:    "머리 한 행",
			deleted: []int64{1},
			want:    append([]vodHole{{stream, "A", docKey(stream, "A"), 1, 4, 1}}, roundHoles(1)...),
		},
		{
			name:    "꼬리 쪽 한 행",
			deleted: []int64{12},
			want:    roundHoles(1),
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			putThreeChainRound(t, pool, stream, "ended")
			if len(tt.deleted) > 0 {
				deleteRows(t, pool, stream, tt.deleted...)
			}
			got := queryVODHoles(t, pool)
			if !slices.Equal(got, tt.want) {
				t.Errorf("VODDocumentHolesSQL (지운 seq %v) = %+v, want %+v", tt.deleted, got, tt.want)
			}
			if len(tt.want) == 0 {
				return
			}
			// 회차 문서는 마지막 사슬 회원 수만큼 같은 키로 나온다 — 읽는 사람이 vod_s3_key 로 묶는다(계획 K17).
			var members []string
			for _, h := range got {
				if h.vodKey == roundKey {
					members = append(members, h.sessionID)
				}
			}
			if want := []string{"C1", "C2"}; !slices.Equal(members, want) {
				t.Errorf("회차 문서 키 %s 의 행 = %v, want 마지막 사슬 회원 %v", roundKey, members, want)
			}
		})
	}
}

// 걸리면 안 되는 문서 — 사슬 하나의 성한 문서 · 지운 행이 범위 밖인 같은 스트림 다른 회차 문서 · 빈 동결(키 · 범위
// 없음) · 조회 제외 표시가 있는 문서 · 문서 범위 밖 행만 지운 경우 · 아직 ending 인 세션의 확정 문서는 모두 0행이다.
func TestVODDocumentHolesIgnoresHealthyAndExcludedDocuments(t *testing.T) {
	const stream = "holes-no"
	tests := []struct {
		name  string
		setup func(t *testing.T, pool *pgxpool.Pool)
	}{
		{
			name: "사슬 하나 성한 문서",
			setup: func(t *testing.T, pool *pgxpool.Pool) {
				key := docKey(stream, "S0")
				putRoundSessions(t, pool, stream,
					roundSession{id: "S0", round: "S0", at: 0, closed: true, state: "ended", vodState: "frozen", vodKey: key},
					roundSession{id: "S1", inherits: "S0", round: "S0", at: 100, closed: true, state: "ended", vodState: "frozen", vodKey: key},
				)
				putRoundRows(t, pool, stream, "S0", 1, 2)
				putRoundRows(t, pool, stream, "S1", 3, 4)
				setVODRange(t, pool, stream, 1, 4, "S0", "S1")
			},
		},
		{
			// 앞 회차 P 는 만료가 표시하고 행을 지운 꼴이다 — 뒤 회차 Q 의 문서 범위에는 지운 행이 없다.
			name: "같은 스트림 다른 회차 문서",
			setup: func(t *testing.T, pool *pgxpool.Pool) {
				putRoundSessions(t, pool, stream,
					roundSession{id: "P0", round: "P0", at: 0, closed: true, state: "ended", vodState: "frozen", vodKey: docKey(stream, "P0")},
					roundSession{id: "Q0", round: "Q0", at: 1000, closed: true, state: "ended", vodState: "frozen", vodKey: docKey(stream, "Q0")},
				)
				putRoundRows(t, pool, stream, "P0", 1, 4)
				putRoundRows(t, pool, stream, "Q0", 5, 8)
				setVODRange(t, pool, stream, 1, 4, "P0")
				setVODRange(t, pool, stream, 5, 8, "Q0")
				markExpired(t, pool, stream, "P0")
				deleteRows(t, pool, stream, 1, 2, 3, 4)
			},
		},
		{
			// 빈 동결은 문서 키도 범위도 쓰지 않는다(freezeEmptyVODSQL).
			name: "빈 동결",
			setup: func(t *testing.T, pool *pgxpool.Pool) {
				putRoundSessions(t, pool, stream,
					roundSession{id: "F0", round: "F0", at: 0, closed: true, state: "ended", vodState: "frozen"},
				)
				for seq := int64(1); seq <= 4; seq++ {
					putRewindRow(t, pool, stream, seq, rewindT0.Add(time.Duration(seq)*4*time.Second), 4000, "F0", false)
				}
				deleteRows(t, pool, stream, 2)
			},
		},
		{
			name: "조회 제외 표시가 있는 문서",
			setup: func(t *testing.T, pool *pgxpool.Pool) {
				putRoundSessions(t, pool, stream,
					roundSession{id: "X0", round: "X0", at: 0, closed: true, state: "ended", vodState: "frozen", vodKey: docKey(stream, "X0")},
				)
				putRoundRows(t, pool, stream, "X0", 1, 4)
				setVODRange(t, pool, stream, 1, 4, "X0")
				markExpired(t, pool, stream, "X0")
				deleteRows(t, pool, stream, 2)
			},
		},
		{
			// 회차 문서 범위(1 – 12) 바로 뒤 다음 회차 N 의 행만 지운다.
			name: "문서 범위 밖 행만 삭제",
			setup: func(t *testing.T, pool *pgxpool.Pool) {
				putThreeChainRound(t, pool, stream, "ended")
				putRoundSessions(t, pool, stream, roundSession{id: "N0", round: "N0", at: 2000, state: "live"})
				putRoundRows(t, pool, stream, "N0", 13, 14)
				deleteRows(t, pool, stream, 13)
			},
		},
		{
			// 마지막 사슬이 회차 문서를 확정했지만 phase 4(ended) 전이다 — 지운 seq 10 은 회차 문서 범위에만 든다.
			name: "ending 세션의 확정 문서",
			setup: func(t *testing.T, pool *pgxpool.Pool) {
				putThreeChainRound(t, pool, stream, "ending")
				deleteRows(t, pool, stream, 10)
			},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			tt.setup(t, pool)
			if got := queryVODHoles(t, pool); len(got) != 0 {
				t.Errorf("VODDocumentHolesSQL = %+v, want 0행", got)
			}
		})
	}
}
