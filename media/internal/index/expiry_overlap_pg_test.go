package index

// 만료의 회차 문서 처분(POK-551 c1) PG 통합 시험 — 문서는 범위 겹침으로 고르고(T1 · T3 · T7), 주인은 VOD 확정 상태로
// 고르며(T2 · T4), 확정될 수 있는 회차 · 사슬이 구간을 앞으로 품을 수 있으면 미룬다(T2b · T2c · T2d · T3b · T8).
// 범위 칸이 NULL 인 문서는 지금처럼 사슬로 고른다(T6). 선택은 행 단계와 같은 행 집합을 본다(M14 시험).
// PG_DSN 이 없으면 skip(REQUIRE_PG=1 이면 실패).
//
// ovSweep 은 만료 루프(internal/expiry)의 한 방송 순서를 장부 함수로 그대로 돈다 — 미룸 판정 → 0단계 표시 → 대상 행 →
// 문서 → 행 삭제. S3 는 없다(문서 「삭제」 는 선택 목록이다). 재시도 집합 · 전환 줄은 만료 루프 시험의 몫이다.

import (
	"context"
	"fmt"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// ovSession 은 겹침 시험의 세션 한 행이다. 빈 값 칸은 NULL 이거나 기본값이다.
type ovSession struct {
	id, round, inherits string
	state               string // 빈 값 = ended
	vod                 string // vod_state — 빈 값 = none
	key                 string // vod_s3_key — 빈 값 = NULL
	first, last         int64  // 문서 범위 — key 가 있고 noRange 가 거짓일 때만 쓴다
	noRange             bool   // 키는 있는데 범위 칸이 NULL 인 문서(M6 전 확정)
	open                bool   // 회차 안 닫힘(round_closed_at NULL) — round 가 있을 때만 뜻이 있다
	at                  int    // started_at = expBase + at 분 — 계승 자식이 나중이어야 잇는다
}

func ovPut(t *testing.T, pool *pgxpool.Pool, stream string, ss ...ovSession) {
	t.Helper()
	for _, s := range ss {
		state, vod := s.state, s.vod
		if state == "" {
			state = "ended"
		}
		if vod == "" {
			vod = "none"
		}
		var endedAt, closedAt, first, last any
		if state == "ended" {
			endedAt = expBase.Add(time.Hour)
		}
		if s.round != "" && !s.open {
			closedAt = expBase.Add(2 * time.Hour)
		}
		if s.key != "" && !s.noRange {
			first, last = s.first, s.last
		}
		_, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ended_at, inherits_session, round_id,
                             round_closed_at, vod_state, vod_s3_key, vod_first_seq, vod_last_seq)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
			s.id, stream, expBase.Add(time.Duration(s.at)*time.Minute), state, endedAt, expNullable(s.inherits),
			expNullable(s.round), closedAt, vod, expNullable(s.key), first, last)
		if err != nil {
			t.Fatalf("세션 픽스처 삽입 실패 %s: %v", s.id, err)
		}
	}
}

// ovSeg 는 세션 session(빈 값 = 세션 NULL 행)의 조각을 seq 마다 하나씩 넣는다(벽시각 expAt(seq)).
func ovSeg(t *testing.T, pool *pgxpool.Pool, stream, session string, seqs ...int64) {
	t.Helper()
	for _, seq := range seqs {
		expPutSeg(t, pool, expSegRow{stream: stream, seq: seq, session: session})
	}
}

// ovSpan 은 seq a..b 조각의 벽시각 구간이다(하한 포함 · 상한 배타).
func ovSpan(stream string, a, b int64) ExpiryQuery {
	from := expAt(a)
	return expRange(stream, &from, expAt(b+1))
}

// ovOutcome 은 ovSweep 한 번의 결과다.
type ovOutcome struct {
	deferred bool
	vodDocs  []string // VOD 문서 「세션:키」(세션 순)
}

// ovSweep 은 한 방송의 장부 걸음을 만료 루프와 같은 순서로 돈다. 미루면 아무것도 쓰지 않는다.
func ovSweep(t *testing.T, pool *pgxpool.Pool, q ExpiryQuery) ovOutcome {
	t.Helper()
	ctx := context.Background()
	settled, err := ExpiryRoundsSettled(ctx, pool, q)
	if err != nil {
		t.Fatalf("ExpiryRoundsSettled: %v", err)
	}
	if !settled {
		return ovOutcome{deferred: true}
	}
	if _, err := MarkExpired(ctx, pool, q); err != nil {
		t.Fatalf("MarkExpired: %v", err)
	}
	rows, err := ExpiryRows(ctx, pool, q)
	if err != nil {
		t.Fatalf("ExpiryRows: %v", err)
	}
	docs, err := ExpiryDocuments(ctx, pool, q)
	if err != nil {
		t.Fatalf("ExpiryDocuments: %v", err)
	}
	var seqs []int64
	for _, r := range rows {
		if r.Skip == "" {
			seqs = append(seqs, r.Seq)
		}
	}
	if _, err := DeleteExpired(ctx, pool, q.StreamID, seqs); err != nil {
		t.Fatalf("DeleteExpired: %v", err)
	}
	return ovOutcome{vodDocs: ovVODDocs(docs)}
}

func ovVODDocs(docs []ExpiryDocument) []string {
	var out []string
	for _, d := range docs {
		if d.Kind == ExpiryDocVOD {
			out = append(out, d.SessionID+":"+d.Key)
		}
	}
	return out
}

// ovMarked 는 expired_at 이 있는 세션이다(이름 순).
func ovMarked(t *testing.T, pool *pgxpool.Pool, stream string) []string {
	t.Helper()
	rows, err := pool.Query(context.Background(),
		`SELECT session_id FROM stream_sessions WHERE stream_id = $1 AND expired_at IS NOT NULL ORDER BY session_id`, stream)
	if err != nil {
		t.Fatalf("표시 세션 조회 실패: %v", err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			t.Fatalf("표시 세션 읽기 실패: %v", err)
		}
		out = append(out, id)
	}
	return out
}

// ovHoleDocs 는 「VOD 확정 ∧ 범위 결손 ∧ 표시 없음」 문서 행 수다 — state 를 보지 않는 엄격 판이다(VODDocumentHolesSQL
// 은 ended 만 보므로 ending 주인을 놓친다).
func ovHoleDocs(t *testing.T, pool *pgxpool.Pool, stream string) int {
	t.Helper()
	return expCount(t, pool, `
SELECT count(*) FROM stream_sessions s
 WHERE s.stream_id = $1 AND s.vod_state IN ('frozen', 'frozen_with_gaps', 'freeze_failed')
   AND s.vod_s3_key IS NOT NULL AND s.expired_at IS NULL
   AND (SELECT count(*) FROM stream_segments t
         WHERE t.stream_id = s.stream_id AND t.seq BETWEEN s.vod_first_seq AND s.vod_last_seq)
       < s.vod_last_seq - s.vod_first_seq + 1`, stream)
}

// ovConfirm 은 VOD 확정과 phase 4 를 흉내 낸다 — freezing 회원에 문서 키 · 범위를 쓰고 ended 로 옮긴다.
func ovConfirm(t *testing.T, pool *pgxpool.Pool, stream, key string, first, last int64, ids ...string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
UPDATE stream_sessions SET vod_state = 'frozen', vod_s3_key = $3, vod_first_seq = $4, vod_last_seq = $5,
                           state = 'ended', ended_at = now(), round_closed_at = COALESCE(round_closed_at, now())
 WHERE stream_id = $1 AND session_id = ANY($2)`, stream, ids, key, first, last); err != nil {
		t.Fatalf("확정 흉내 실패 %v: %v", ids, err)
	}
}

func ovPhase(t *testing.T, pool *pgxpool.Pool, stream, round string) PlaybackPhase {
	t.Helper()
	st, err := LookupRoundPlayback(context.Background(), pool, stream, round)
	if err != nil {
		t.Fatalf("LookupRoundPlayback(%s): %v", round, err)
	}
	return st.Phase
}

func ovWantSeqs(t *testing.T, pool *pgxpool.Pool, stream string, want ...int64) {
	t.Helper()
	if got := expSeqs(t, pool, stream); !slices.Equal(got, want) {
		t.Fatalf("남은 조각 %v, want %v", got, want)
	}
}

// T1(완료 조건 1) — 닫힌 회차 R 에 사슬 A · B · C(C1 → C2). 회차 문서는 C 회원이 든다(범위 = A 처음부터 C 끝). 구간이
// B 만이어도 C 의 회차 문서를 고르고 C 회원 전원을 표시한다. 회차는 unknown 이고 A · C 행은 그대로다.
func TestExpiryRoundDocumentSelectedWhenMiddleChainExpires(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-t1"
	round := docKey(stream, "A.round")
	ovPut(t, pool, stream,
		ovSession{id: "A", round: "R", vod: "frozen", key: docKey(stream, "A"), first: 0, last: 2},
		ovSession{id: "B", round: "R", vod: "frozen", key: docKey(stream, "B"), first: 3, last: 5, at: 1},
		ovSession{id: "C1", round: "R", vod: "frozen", key: round, first: 0, last: 8, at: 2},
		ovSession{id: "C2", round: "R", inherits: "C1", vod: "frozen", key: round, first: 0, last: 8, at: 3})
	ovSeg(t, pool, stream, "A", 0, 1, 2)
	ovSeg(t, pool, stream, "B", 3, 4, 5)
	ovSeg(t, pool, stream, "C1", 6, 7)
	ovSeg(t, pool, stream, "C2", 8)

	out := ovSweep(t, pool, ovSpan(stream, 3, 5))

	if out.deferred {
		t.Fatal("닫힌 · 확정 회차인데 미뤘다")
	}
	want := []string{"B:" + docKey(stream, "B"), "C1:" + round, "C2:" + round}
	if !slices.Equal(out.vodDocs, want) {
		t.Fatalf("VOD 문서 %v, want %v", out.vodDocs, want)
	}
	// B 는 조각이 다 지워져 세션 행까지 지워진다 — 남은 표시는 C 회원이다.
	if got := ovMarked(t, pool, stream); !slices.Equal(got, []string{"C1", "C2"}) {
		t.Fatalf("표시 세션 %v (C1 · C2 기대 — A 문서는 구간과 겹치지 않는다)", got)
	}
	if ph := ovPhase(t, pool, stream, "R"); ph != PhaseUnknown {
		t.Fatalf("회차 R 국면 %v (unknown 기대)", ph)
	}
	ovWantSeqs(t, pool, stream, 0, 1, 2, 6, 7, 8)
	if n := ovHoleDocs(t, pool, stream); n != 0 {
		t.Fatalf("결손 · 미표시 문서 %d행 (0 기대)", n)
	}
}

// T2(완료 조건 2) — 주인 C 가 문서를 확정했지만 아직 ending(phase 4 전)이다. 확정 술어로 고르므로 C 문서를 고르고 C 를
// 표시한다. 그 뒤 C 가 ended 가 되어도 결손 · 미표시 문서는 없다.
func TestExpiryOwnerSelectedWhileEndingAfterConfirm(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-t2"
	round := docKey(stream, "B.round")
	ovPut(t, pool, stream,
		ovSession{id: "B", round: "R", vod: "frozen"},
		ovSession{id: "C", round: "R", state: "ending", vod: "frozen", key: round, first: 0, last: 4, at: 1})
	ovSeg(t, pool, stream, "B", 0, 1, 2)
	ovSeg(t, pool, stream, "C", 3, 4)

	out := ovSweep(t, pool, ovSpan(stream, 0, 2))
	ovConfirm(t, pool, stream, round, 0, 4, "C") // phase 4

	if out.deferred || !slices.Equal(out.vodDocs, []string{"C:" + round}) {
		t.Fatalf("결과 %+v (미루지 않고 C 문서 기대)", out)
	}
	if got := ovMarked(t, pool, stream); !slices.Contains(got, "C") {
		t.Fatalf("표시 세션 %v (C 포함 기대)", got)
	}
	ovWantSeqs(t, pool, stream, 3, 4)
	if n := ovHoleDocs(t, pool, stream); n != 0 {
		t.Fatalf("결손 · 미표시 문서 %d행 (0 기대)", n)
	}
}

// T2b — C 가 freezing(확정 전)이면 미룬다: 표시 · 문서 · 행 삭제가 없다. C 가 확정 · ended 된 뒤 다시 하면 C 문서를 고르고
// B 를 지운다. 결손 · 미표시 문서는 없다.
func TestExpiryDefersWhileRoundCanStillConfirm(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-t2b"
	round := docKey(stream, "B.round")
	ovPut(t, pool, stream,
		ovSession{id: "B", round: "R", vod: "frozen"},
		ovSession{id: "C", round: "R", state: "ending", vod: "freezing", at: 1})
	ovSeg(t, pool, stream, "B", 0, 1, 2)
	ovSeg(t, pool, stream, "C", 3, 4)
	q := ovSpan(stream, 0, 2)

	if out := ovSweep(t, pool, q); !out.deferred {
		t.Fatalf("첫 시도 %+v (미룸 기대 — C 가 아직 확정될 수 있다)", out)
	}
	if got := ovMarked(t, pool, stream); len(got) != 0 {
		t.Fatalf("미뤘는데 표시 %v", got)
	}
	ovWantSeqs(t, pool, stream, 0, 1, 2, 3, 4)

	ovConfirm(t, pool, stream, round, 0, 4, "C")
	out := ovSweep(t, pool, q)
	if out.deferred || !slices.Equal(out.vodDocs, []string{"C:" + round}) {
		t.Fatalf("둘째 시도 %+v (C 문서 기대)", out)
	}
	ovWantSeqs(t, pool, stream, 3, 4)
	if n := ovHoleDocs(t, pool, stream); n != 0 {
		t.Fatalf("결손 · 미표시 문서 %d행 (0 기대)", n)
	}
}

// T2c — 회원이 모두 종국인데 회차가 닫히지 않았다(round_closed_at NULL 고착 흉내). 되풀이해도 미룬다.
func TestExpiryDefersWhileRoundOpen(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-t2c"
	round := docKey(stream, "B.round")
	ovPut(t, pool, stream,
		ovSession{id: "B", round: "R", vod: "frozen", open: true},
		ovSession{id: "C", round: "R", vod: "frozen", key: round, first: 0, last: 4, open: true, at: 1})
	ovSeg(t, pool, stream, "B", 0, 1, 2)
	ovSeg(t, pool, stream, "C", 3, 4)

	for i := range 2 {
		if out := ovSweep(t, pool, ovSpan(stream, 0, 2)); !out.deferred {
			t.Fatalf("시도 %d: %+v (미룸 기대 — 회차 안 닫힘)", i+1, out)
		}
	}
	ovWantSeqs(t, pool, stream, 0, 1, 2, 3, 4)
}

// T2d — 구간에 회원 조각 없이 세션 NULL 행 u 만 있다. u 를 감싼 확정 전 회차 C 가 있으니 미룬다. C 가 확정된 뒤 다시
// 하면 C 문서를 고르고 C 를 표시하고 u 를 지운다.
func TestExpiryDefersSessionlessRowInsideUnsettledRound(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-t2d"
	round := docKey(stream, "C1.round")
	ovPut(t, pool, stream,
		ovSession{id: "C1", round: "R", state: "ending", vod: "freezing"},
		ovSession{id: "C2", round: "R", inherits: "C1", state: "ending", vod: "freezing", at: 1})
	ovSeg(t, pool, stream, "C1", 0, 1, 2)
	ovSeg(t, pool, stream, "", 3)
	ovSeg(t, pool, stream, "C2", 4, 5)
	q := ovSpan(stream, 3, 3)

	if out := ovSweep(t, pool, q); !out.deferred {
		t.Fatalf("첫 시도 %+v (미룸 기대 — 회원 조각 0 이어도 C 가 u 를 품을 수 있다)", out)
	}
	ovWantSeqs(t, pool, stream, 0, 1, 2, 3, 4, 5)

	ovConfirm(t, pool, stream, round, 0, 5, "C1", "C2")
	out := ovSweep(t, pool, q)
	if out.deferred || !slices.Equal(out.vodDocs, []string{"C1:" + round, "C2:" + round}) {
		t.Fatalf("둘째 시도 %+v (C 문서 기대)", out)
	}
	if got := ovMarked(t, pool, stream); !slices.Equal(got, []string{"C1", "C2"}) {
		t.Fatalf("표시 세션 %v (C1 · C2 기대)", got)
	}
	ovWantSeqs(t, pool, stream, 0, 1, 2, 4, 5)
	if n := ovHoleDocs(t, pool, stream); n != 0 {
		t.Fatalf("결손 · 미표시 문서 %d행 (0 기대)", n)
	}
}

// T3(⑤) — 문서 범위가 B 첫 seq − 1 · 마지막 seq + 1 에 꼭 붙은 앞 방송 P · 뒤 방송 N 과 같은 스트림 다른 회차 O 는
// 표시 · 문서 · 행 · 판정이 바뀌지 않는다.
func TestExpiryLeavesAdjacentRoundsUntouched(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-t3"
	ovPut(t, pool, stream,
		ovSession{id: "P", round: "P", vod: "frozen", key: docKey(stream, "P"), first: 0, last: 2},
		ovSession{id: "B", round: "B", vod: "frozen", key: docKey(stream, "B"), first: 3, last: 5, at: 1},
		ovSession{id: "N", round: "N", vod: "frozen", key: docKey(stream, "N"), first: 6, last: 8, at: 2},
		ovSession{id: "O", round: "O", vod: "frozen", key: docKey(stream, "O"), first: 9, last: 10, at: 3})
	ovSeg(t, pool, stream, "P", 0, 1, 2)
	ovSeg(t, pool, stream, "B", 3, 4, 5)
	ovSeg(t, pool, stream, "N", 6, 7, 8)
	ovSeg(t, pool, stream, "O", 9, 10)
	before := map[string]PlaybackPhase{}
	for _, r := range []string{"P", "N", "O"} {
		before[r] = ovPhase(t, pool, stream, r)
	}

	out := ovSweep(t, pool, ovSpan(stream, 3, 5))

	if out.deferred || !slices.Equal(out.vodDocs, []string{"B:" + docKey(stream, "B")}) {
		t.Fatalf("결과 %+v (B 문서만 기대)", out)
	}
	// B 는 세션 행까지 지워진다 — 남은 표시가 없어야 한다.
	if got := ovMarked(t, pool, stream); len(got) != 0 {
		t.Fatalf("표시 세션 %v (없음 기대)", got)
	}
	for r, ph := range before {
		if got := ovPhase(t, pool, stream, r); got != ph {
			t.Errorf("회차 %s 국면 %v → %v", r, ph, got)
		}
	}
	ovWantSeqs(t, pool, stream, 0, 1, 2, 6, 7, 8, 9, 10)
}

// T3b — 지금 방송 중인 회차 L(확정 가능 · 최소 seq > 구간 꼭대기)과 회차 번호 없는 live 옛 판은 미루지 않는다.
func TestExpiryDoesNotDeferOnLiveBroadcastAboveSpan(t *testing.T) {
	for _, c := range []struct {
		name string
		live ovSession
	}{
		{"round", ovSession{id: "L", round: "L", state: "live", open: true, at: 1}},
		{"legacy", ovSession{id: "L", state: "live", at: 1}},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			stream := "ovtest-t3b"
			ovPut(t, pool, stream, ovSession{id: "B", round: "B", vod: "frozen", key: docKey(stream, "B"), first: 0, last: 2}, c.live)
			ovSeg(t, pool, stream, "B", 0, 1, 2)
			ovSeg(t, pool, stream, "L", 3, 4)

			if out := ovSweep(t, pool, ovSpan(stream, 0, 2)); out.deferred {
				t.Fatal("방송 중인 L 의 조각은 구간보다 뒤인데 미뤘다")
			}
			ovWantSeqs(t, pool, stream, 3, 4)
			if got := ovMarked(t, pool, stream); len(got) != 0 {
				t.Fatalf("표시 세션 %v (없음 기대 — L 은 표시하지 않는다)", got)
			}
		})
	}
}

// t4Case 는 T4 행렬의 한 칸이다 — 주인 상태 · vod_state · 키 유무와 기대 선택.
type t4Case struct {
	state, vod string
	key        bool
	want       bool
}

// t4Cases 는 닫힌 회차에서 성립하는 주인 조합이다. 키는 확정 문장만 쓰므로 none · freezing 에는 키가 없다. ended ∧ none
// 은 옛 판이 쓴 행(LEG 꼴)이다.
var t4Cases = []t4Case{
	{"ending", "none", false, false},
	{"ending", "freezing", false, false},
	{"ending", "frozen", true, true},
	{"ending", "frozen", false, false},
	{"ending", "frozen_with_gaps", true, true},
	{"ending", "freeze_failed", true, true},
	{"ending", "freeze_failed", false, false},
	{"ended", "none", false, false},
	{"ended", "frozen", true, true},
	{"ended", "frozen", false, false},
	{"ended", "frozen_with_gaps", true, true},
	{"ended", "freeze_failed", true, true},
	{"ended", "freeze_failed", false, false},
}

// T4(②) — 주인은 확정 술어(VOD 종국 ∧ 키)로만 고른다. state 는 보지 않는다.
func TestExpiryOwnerPredicateMatrix(t *testing.T) {
	for _, c := range t4Cases {
		t.Run(fmt.Sprintf("%s-%s-key=%v", c.state, c.vod, c.key), func(t *testing.T) {
			pool := newTestPool(t)
			stream := "ovtest-t4"
			owner := ovSession{id: "X", round: "R", state: c.state, vod: c.vod, first: 0, last: 4, at: 1}
			if c.key {
				owner.key = docKey(stream, "B.round")
			}
			ovPut(t, pool, stream, ovSession{id: "B", round: "R", vod: "frozen"}, owner)
			ovSeg(t, pool, stream, "B", 0, 1, 2)
			ovSeg(t, pool, stream, "X", 3, 4)

			docs, err := ExpiryDocuments(context.Background(), pool, ovSpan(stream, 0, 2))
			if err != nil {
				t.Fatalf("ExpiryDocuments: %v", err)
			}
			if got := slices.Contains(ovVODDocs(docs), "X:"+owner.key); got != c.want {
				t.Fatalf("주인 문서 선택 %v, want %v (문서 %v)", got, c.want, docs)
			}
		})
	}
}

// T4 K4 — ending ∧ VOD 종국(phase 4 전) 주인: 한 방송을 끝까지 돌면 문서를 고르고 주인을 표시하되 주인 세션 행은 남는다.
func TestExpiryEndingConfirmedOwnerKeepsSessionRow(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-t4k"
	round := docKey(stream, "B.round")
	ovPut(t, pool, stream,
		ovSession{id: "B", round: "R", vod: "frozen"},
		ovSession{id: "X", round: "R", state: "ending", vod: "frozen", key: round, first: 0, last: 4, at: 1})
	ovSeg(t, pool, stream, "B", 0, 1, 2)
	ovSeg(t, pool, stream, "X", 3, 4)

	out := ovSweep(t, pool, ovSpan(stream, 0, 2))

	if out.deferred || !slices.Equal(out.vodDocs, []string{"X:" + round}) {
		t.Fatalf("결과 %+v (X 문서 기대)", out)
	}
	if got := ovMarked(t, pool, stream); !slices.Contains(got, "X") {
		t.Fatalf("표시 세션 %v (X 포함 기대)", got)
	}
	if n := expCount(t, pool, `SELECT count(*) FROM stream_sessions WHERE session_id = 'X' AND state = 'ending'`); n != 1 {
		t.Fatalf("주인 세션 행 %d (남아 있어야 한다)", n)
	}
	ovWantSeqs(t, pool, stream, 3, 4)
}

// T6 — 범위 칸이 NULL 인 확정 문서는 지금처럼 사슬로 고른다: ① 구간에 직접 닿은 세션 ② 계승으로만 닿는 사슬 회원.
func TestExpiryNullRangeDocumentsFollowChain(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-t6"
	key := docKey(stream, "H")
	ovPut(t, pool, stream,
		ovSession{id: "H", vod: "frozen", key: key, noRange: true},
		ovSession{id: "T", inherits: "H", vod: "frozen", key: key, noRange: true, at: 1})
	ovSeg(t, pool, stream, "H", 0, 1)
	ovSeg(t, pool, stream, "T", 2, 3)

	out := ovSweep(t, pool, ovSpan(stream, 0, 1))

	if out.deferred || !slices.Equal(out.vodDocs, []string{"H:" + key, "T:" + key}) {
		t.Fatalf("결과 %+v (H · T 문서 기대)", out)
	}
}

// T7 — 닫힌 · 확정 회차 문서 범위 안 세션 NULL 행 하나만 구간에 든다. 문서가 그 행을 GAP 줄로 싣기 때문에 문서를
// 고르고 주인을 표시하고 그 행을 지운다.
func TestExpirySessionlessRowInsideSettledDocument(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-t7"
	round := docKey(stream, "C1.round")
	ovPut(t, pool, stream,
		ovSession{id: "C1", round: "R", vod: "frozen", key: round, first: 0, last: 4},
		ovSession{id: "C2", round: "R", inherits: "C1", vod: "frozen", key: round, first: 0, last: 4, at: 1})
	ovSeg(t, pool, stream, "C1", 0, 1)
	ovSeg(t, pool, stream, "", 2)
	ovSeg(t, pool, stream, "C2", 3, 4)

	out := ovSweep(t, pool, ovSpan(stream, 2, 2))

	if out.deferred || !slices.Equal(out.vodDocs, []string{"C1:" + round, "C2:" + round}) {
		t.Fatalf("결과 %+v (C 문서 기대)", out)
	}
	if got := ovMarked(t, pool, stream); !slices.Equal(got, []string{"C1", "C2"}) {
		t.Fatalf("표시 세션 %v (C1 · C2 기대)", got)
	}
	ovWantSeqs(t, pool, stream, 0, 1, 3, 4)
}

// T8 — 확정될 길이 없는 옛 행(ended ∧ none)은 미루지 않는다. 옛 세션 L 이 구간 꼭대기보다 앞에 있어도 B 를 처리하고,
// 이어서 L 자신의 구간도 처리한다(L 행 삭제 · 고를 문서 없음).
func TestExpiryUnconfirmableLegacySessionDoesNotDefer(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-t8"
	ovPut(t, pool, stream,
		ovSession{id: "L"},
		ovSession{id: "B", round: "B", vod: "frozen", key: docKey(stream, "B"), first: 3, last: 5, at: 1})
	ovSeg(t, pool, stream, "L", 0, 1, 2)
	ovSeg(t, pool, stream, "B", 3, 4, 5)

	if out := ovSweep(t, pool, ovSpan(stream, 3, 5)); out.deferred {
		t.Fatal("B: ended ∧ none 옛 세션 때문에 미뤘다")
	}
	out := ovSweep(t, pool, ovSpan(stream, 0, 2))
	if out.deferred || len(out.vodDocs) != 0 {
		t.Fatalf("L: %+v (미루지 않고 문서 없음 기대)", out)
	}
	ovWantSeqs(t, pool, stream)
}

// T8 K5 — (a) 닫힌 회차의 ended ∧ none 회원 · (b) 머리만 구간 꼭대기 이하인 다중 회원 옛 사슬. 확정될 길이 없으면
// 미루지 않고, 같은 옛 사슬이 아직 확정될 수 있으면(ending ∧ none) 머리의 최소 seq 로 미룬다.
func TestExpiryLegacyMembersDeferOnlyWhenConfirmable(t *testing.T) {
	for _, c := range []struct {
		name  string
		front []ovSession
		want  bool
	}{
		{"closed-round-ended-none", []ovSession{{id: "M1", round: "M"}, {id: "M2", round: "M", inherits: "M1", at: 1}}, false},
		{"legacy-chain-ended-none", []ovSession{{id: "M1"}, {id: "M2", inherits: "M1", at: 1}}, false},
		{"legacy-chain-ending-none", []ovSession{{id: "M1", state: "ending"}, {id: "M2", inherits: "M1", state: "ending", at: 1}}, true},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			stream := "ovtest-t8k"
			ovPut(t, pool, stream, c.front...)
			ovPut(t, pool, stream, ovSession{id: "B", round: "B", vod: "frozen", key: docKey(stream, "B"), first: 2, last: 5, at: 2})
			ovSeg(t, pool, stream, "M1", 0, 1)
			ovSeg(t, pool, stream, "B", 2, 3, 4, 5)
			ovSeg(t, pool, stream, "M2", 6, 7)

			if out := ovSweep(t, pool, ovSpan(stream, 2, 5)); out.deferred != c.want {
				t.Fatalf("미룸 %v, want %v", out.deferred, c.want)
			}
		})
	}
}

// M14(P3b · K2) — 선택은 행 단계와 같은 행 집합을 본다(0단계의 나이 상한 $4 를 보지 않는다). 겹침이 now − (보존 기간 −
// 여유) 보다 새 세션 NULL 행 하나뿐인 문서도 고른다 — 행 단계는 그 행을 지우기 때문이다.
func TestExpirySelectionIgnoresMarkAgeCeiling(t *testing.T) {
	pool := newTestPool(t)
	stream := "ovtest-m14"
	edge := time.Now().UTC().Add(-expiryMinAge)
	round := docKey(stream, "C1.round")
	ovPut(t, pool, stream,
		ovSession{id: "C1", round: "R", vod: "frozen", key: round, first: 0, last: 2},
		ovSession{id: "C2", round: "R", inherits: "C1", vod: "frozen", key: round, first: 0, last: 2, at: 1})
	expPutSeg(t, pool,
		expSegRow{stream: stream, seq: 0, session: "C1", start: edge.Add(10 * time.Minute)},
		expSegRow{stream: stream, seq: 1, start: edge.Add(30 * time.Minute)},
		expSegRow{stream: stream, seq: 2, session: "C2", start: edge.Add(2 * time.Hour)})
	from := edge.Add(20 * time.Minute)

	out := ovSweep(t, pool, expRange(stream, &from, edge.Add(time.Hour)))

	if out.deferred || !slices.Equal(out.vodDocs, []string{"C1:" + round, "C2:" + round}) {
		t.Fatalf("결과 %+v (C 문서 기대)", out)
	}
	ovWantSeqs(t, pool, stream, 0, 2)
	if n := ovHoleDocs(t, pool, stream); n != 0 {
		t.Fatalf("결손 · 미표시 문서 %d행 (0 기대)", n)
	}
}
