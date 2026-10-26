package index

// 60일 만료의 장부 쪽(POK-196 M6-4 c1) PG 통합 시험 — 조회 제외 영속(0단계) · 대상 행 · 세션 단위 문서 ·
// 행 삭제 트랜잭션(GAP · 하한 · 컷오프 전진 · 빈 세션 · outbox). PG_DSN 이 없으면 skip(REQUIRE_PG=1 이면 실패).
//
// 이름의 e1–e4 는 POK-196 완료 조건(무효 컷오프 게이트)이다 — e1 삭제 축 정지 · e3 다른 스트림 계속 ·
// e4 만료로 지운 방송은 무효 컷오프를 남기지 않는다. e2(경보 한 건)는 만료 루프(internal/expiry)의 몫이다.

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// expBase 는 만료 픽스처의 기준 벽시계다 — 70일 전이라 7일 규칙(세션 NULL 행)에도 늘 걸린다.
var expBase = time.Now().UTC().Add(-70 * 24 * time.Hour).Truncate(time.Second)

// expAt 은 기준에서 seq 번째 조각의 시작이다(4초 간격).
func expAt(seq int64) time.Time { return expBase.Add(time.Duration(seq) * 4 * time.Second) }

// expSessionRow 는 만료 시험의 세션 한 행이다.
type expSessionRow struct {
	id, stream string
	state      string // 빈 값 = ended
	inherits   string // 빈 값 = NULL
	round      string // 빈 값 = NULL
	dvrKey     string // 빈 값 = NULL
	vodKey     string // 빈 값 = NULL
	initKey    string // 빈 값 = NULL
}

func expNullable(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func expPutSession(t *testing.T, pool *pgxpool.Pool, s expSessionRow) {
	t.Helper()
	state := s.state
	if state == "" {
		state = "ended"
	}
	var endedAt any
	if state == "ended" {
		endedAt = expBase.Add(time.Hour)
	}
	_, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ended_at, inherits_session, round_id,
                             dvr_s3_key, vod_s3_key, init_s3_key)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		s.id, s.stream, expBase, state, endedAt, expNullable(s.inherits), expNullable(s.round),
		expNullable(s.dvrKey), expNullable(s.vodKey), expNullable(s.initKey))
	if err != nil {
		t.Fatalf("세션 픽스처 삽입 실패 %s: %v", s.id, err)
	}
}

// expSegRow 는 만료 시험의 조각 한 행이다. 세션이 있으면 ③ 세 열(세션 · PDT · 키)을 채워 컷오프 자격 행이 된다.
type expSegRow struct {
	stream  string
	seq     int64
	session string    // 빈 값 = NULL(③ 세 열도 NULL)
	start   time.Time // 영값 = expAt(seq)
	local   bool      // local_path 를 남긴다
}

// expExecer 는 풀과 트랜잭션 둘 다다 — 끼어드는 트랜잭션(D58)이 같은 삽입을 쓴다.
type expExecer interface {
	Exec(context.Context, string, ...any) (pgconn.CommandTag, error)
}

func expPutSeg(t *testing.T, db expExecer, rows ...expSegRow) {
	t.Helper()
	for _, r := range rows {
		if err := expInsertSeg(context.Background(), db, r); err != nil {
			t.Fatalf("조각 픽스처 삽입 실패 %s/%d: %v", r.stream, r.seq, err)
		}
	}
}

func expInsertSeg(ctx context.Context, db expExecer, r expSegRow) error {
	if r.start.IsZero() {
		r.start = expAt(r.seq)
	}
	var session, pdt, pbKey, path any
	if r.session != "" {
		session, pdt, pbKey = r.session, r.start, fmt.Sprintf("dvr/%s/seg/%06d.m4s", r.stream, r.seq)
	}
	if r.local {
		path = fmt.Sprintf("/recordings/%s/seg_%06d.mp4", r.stream, r.seq)
	}
	_, err := db.Exec(ctx, `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, uploaded_at, bytes,
     session_id, playback_pdt, playback_s3_key, playback_upload_state, playback_uploaded_at)
VALUES ($1, $2, $3, $4, 4000, $5, $6, 'uploaded', $4, 1000, $7, $8, $9, 'uploaded', $4)`,
		r.stream, r.seq, r.seq*4000, r.start, S3Key(r.stream, r.seq, r.start), path, session, pdt, pbKey)
	return err
}

func expSeqs(t *testing.T, pool *pgxpool.Pool, stream string) []int64 {
	t.Helper()
	rows, err := pool.Query(context.Background(), `SELECT seq FROM stream_segments WHERE stream_id = $1 ORDER BY seq`, stream)
	if err != nil {
		t.Fatalf("조각 조회 실패: %v", err)
	}
	defer rows.Close()
	var out []int64
	for rows.Next() {
		var s int64
		if err := rows.Scan(&s); err != nil {
			t.Fatalf("조각 행 읽기 실패: %v", err)
		}
		out = append(out, s)
	}
	return out
}

func expCount(t *testing.T, pool *pgxpool.Pool, sql string, args ...any) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(), sql, args...).Scan(&n); err != nil {
		t.Fatalf("행 수 조회 실패(%s): %v", sql, err)
	}
	return n
}

func expRange(stream string, from *time.Time, until time.Time) ExpiryQuery {
	return ExpiryQuery{StreamID: stream, From: from, Until: until}
}

// 0단계 — 범위에 닿은 세션에 expired_at 을 한 번만 쓴다. 이미 있으면 그대로이고 닿지 않은 세션은 건드리지 않는다.
func TestMarkExpiredStampsTouchedSessionsOnce(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-mark"
	expPutSession(t, pool, expSessionRow{id: "mk-a", stream: stream})
	expPutSession(t, pool, expSessionRow{id: "mk-b", stream: stream})
	expPutSeg(t, pool, expSegRow{stream: stream, seq: 0, session: "mk-a"}, expSegRow{stream: stream, seq: 1, session: "mk-a"},
		expSegRow{stream: stream, seq: 2, session: "mk-b"})

	n, err := MarkExpired(ctx, pool, expRange(stream, nil, expAt(2)))
	if err != nil || n != 1 {
		t.Fatalf("첫 표시: n=%d err=%v (1 기대 — mk-a 만 범위에 닿음)", n, err)
	}
	var first time.Time
	if err := pool.QueryRow(ctx, `SELECT expired_at FROM stream_sessions WHERE session_id = 'mk-a'`).Scan(&first); err != nil {
		t.Fatalf("expired_at 읽기 실패: %v", err)
	}
	if expCount(t, pool, `SELECT count(*) FROM stream_sessions WHERE session_id = 'mk-b' AND expired_at IS NOT NULL`) != 0 {
		t.Fatal("범위에 닿지 않은 세션까지 표시됐다")
	}

	n, err = MarkExpired(ctx, pool, expRange(stream, nil, expAt(2)))
	if err != nil || n != 0 {
		t.Fatalf("재표시: n=%d err=%v (0 기대 — 이미 있으면 그대로)", n, err)
	}
	var again time.Time
	if err := pool.QueryRow(ctx, `SELECT expired_at FROM stream_sessions WHERE session_id = 'mk-a'`).Scan(&again); err != nil {
		t.Fatalf("expired_at 다시 읽기 실패: %v", err)
	}
	if !again.Equal(first) {
		t.Fatalf("expired_at 이 다시 써졌다: %v → %v", first, again)
	}
}

// 대상 행 — 범위([from, until) · from 이 없으면 하한 없음) 안만 돌려주고, 건너뛸 행은 사유를 단다(D17).
// ended 세션 행은 local_path 와 무관하게 대상이다. 세션 NULL 행은 local_path 가 없거나 7일 넘었을 때만 대상이다.
func TestExpiryRowsSelectsRangeAndMarksSkips(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-rows"
	expPutSession(t, pool, expSessionRow{id: "rw-ended", stream: stream})
	expPutSession(t, pool, expSessionRow{id: "rw-ending", stream: stream, state: "ending"})
	recent := time.Now().UTC().Add(-time.Hour)
	expPutSeg(t, pool,
		expSegRow{stream: stream, seq: 0, session: "rw-ended", local: true},
		expSegRow{stream: stream, seq: 1, session: "rw-ending"},
		expSegRow{stream: stream, seq: 2},                                               // 세션 NULL · local_path 없음 → 대상
		expSegRow{stream: stream, seq: 3, local: true},                                  // 세션 NULL · 70일 전 → 대상(7일 규칙)
		expSegRow{stream: stream, seq: 4, local: true, start: recent.Add(-time.Minute)}, // 세션 NULL · 최근 · 파일 있음
		expSegRow{stream: stream, seq: 5, session: "rw-ended", start: recent},           // 범위 밖
	)

	got, err := ExpiryRows(ctx, pool, expRange(stream, nil, recent))
	if err != nil {
		t.Fatalf("ExpiryRows: %v", err)
	}
	want := map[int64]string{0: "", 1: ExpirySkipSessionOpen, 2: "", 3: "", 4: ExpirySkipSessionlessLocal}
	if len(got) != len(want) {
		t.Fatalf("행 수 %d (%d 기대): %+v", len(got), len(want), got)
	}
	for _, r := range got {
		skip, ok := want[r.Seq]
		if !ok || r.Skip != skip {
			t.Fatalf("seq=%d skip=%q (기대 %q, 있음=%v)", r.Seq, r.Skip, skip, ok)
		}
	}
	if got[0].S3Key == "" || got[0].PlaybackS3Key == "" || got[0].SessionID != "rw-ended" {
		t.Fatalf("장부 저장값이 비었다: %+v", got[0])
	}
	if got[2].PlaybackS3Key != "" || got[2].SessionID != "" {
		t.Fatalf("세션 NULL 행의 ③ 키 · 세션은 빈 값이어야 한다: %+v", got[2])
	}

	from := expAt(2)
	got, err = ExpiryRows(ctx, pool, expRange(stream, &from, expAt(4)))
	if err != nil {
		t.Fatalf("ExpiryRows(하한): %v", err)
	}
	if seqs := []int64{got[0].Seq, got[len(got)-1].Seq}; len(got) != 2 || seqs[0] != 2 || seqs[1] != 3 {
		t.Fatalf("하한 · 상한 범위가 틀렸다: %+v", got)
	}
}

// 세션 단위 문서 — dvr(범위에 닿은 ended 세션) → 사슬 vod(앞 · 뒤 계승 전부) → init(조각이 전부 범위 안인 세션) 순이다(K3).
func TestExpiryDocumentsOrderAndSelection(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-docs"
	expPutSession(t, pool, expSessionRow{id: "dc-a", stream: stream, dvrKey: "dvr/a", initKey: "init/a"})
	expPutSession(t, pool, expSessionRow{id: "dc-b", stream: stream, inherits: "dc-a", dvrKey: "dvr/b", initKey: "init/b"})
	expPutSession(t, pool, expSessionRow{id: "dc-c", stream: stream, inherits: "dc-b", vodKey: "vod/c", dvrKey: "dvr/c"})
	expPutSession(t, pool, expSessionRow{id: "dc-live", stream: stream, state: "live", dvrKey: "dvr/live", initKey: "init/live"})
	expPutSession(t, pool, expSessionRow{id: "dc-other", stream: stream, vodKey: "vod/other"})
	expPutSeg(t, pool,
		expSegRow{stream: stream, seq: 0, session: "dc-a"}, expSegRow{stream: stream, seq: 1, session: "dc-a"},
		expSegRow{stream: stream, seq: 2, session: "dc-b"}, expSegRow{stream: stream, seq: 3, session: "dc-b"},
		expSegRow{stream: stream, seq: 4, session: "dc-c"},
		expSegRow{stream: stream, seq: 5, session: "dc-live"},
		expSegRow{stream: stream, seq: 6, session: "dc-other"},
	)

	// 범위 = seq 0..2 · 그리고 live 세션 조각 5 를 따로 덮지 않는다.
	got, err := ExpiryDocuments(ctx, pool, expRange(stream, nil, expAt(3)))
	if err != nil {
		t.Fatalf("ExpiryDocuments: %v", err)
	}
	want := []ExpiryDocument{
		{Kind: ExpiryDocDVR, SessionID: "dc-a", Key: "dvr/a"},
		{Kind: ExpiryDocDVR, SessionID: "dc-b", Key: "dvr/b"},
		{Kind: ExpiryDocVOD, SessionID: "dc-c", Key: "vod/c"},
		{Kind: ExpiryDocInit, SessionID: "dc-a", Key: "init/a"},
	}
	if !slices.Equal(got, want) {
		t.Fatalf("문서 목록\n got  %+v\n want %+v", got, want)
	}
}

// D17 — DeleteExpired 는 넘겨받은 seq 라도 ended 가 아닌 세션 행을 지우지 않는다(업로더 ③ 선택자와 서로 배타).
func TestDeleteExpiredKeepsOpenSessionRows(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-open"
	expPutSession(t, pool, expSessionRow{id: "op-live", stream: stream, state: "live"})
	expPutSession(t, pool, expSessionRow{id: "op-ending", stream: stream, state: "ending"})
	expPutSeg(t, pool, expSegRow{stream: stream, seq: 0, session: "op-live"}, expSegRow{stream: stream, seq: 1, session: "op-ending"},
		expSegRow{stream: stream, seq: 2, local: true, start: time.Now().UTC().Add(-time.Hour)})

	res, err := DeleteExpired(ctx, pool, stream, []int64{0, 1, 2})
	if err != nil {
		t.Fatalf("DeleteExpired: %v", err)
	}
	if res.RowsDeleted != 0 || !slices.Equal(expSeqs(t, pool, stream), []int64{0, 1, 2}) {
		t.Fatalf("열린 세션 · 최근 세션 NULL 행이 지워졌다: %+v 남은 행 %v", res, expSeqs(t, pool, stream))
	}
}

// D13(만료 경로) — 스트림 꼬리까지 지워도 하한이 남아 LoadCursor 가 seq 를 다시 쓰지 않는다(불변식 4).
func TestDeleteExpiredTailKeepsSeqFloor(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-floor"
	expPutSession(t, pool, expSessionRow{id: "fl-a", stream: stream})
	expPutSeg(t, pool, expSegRow{stream: stream, seq: 0, session: "fl-a"}, expSegRow{stream: stream, seq: 1, session: "fl-a"},
		expSegRow{stream: stream, seq: 2, session: "fl-a"})

	if _, err := DeleteExpired(ctx, pool, stream, []int64{0, 1, 2}); err != nil {
		t.Fatalf("DeleteExpired: %v", err)
	}
	if got := floorOf(t, pool, stream); got != 3 {
		t.Fatalf("하한 %d (3 기대)", got)
	}
	cur, err := NewPGStore(pool, nil, nil).LoadCursor(ctx, stream)
	if err != nil || cur.NextSeq != 3 || cur.Tail != nil {
		t.Fatalf("LoadCursor = %+v err=%v (NextSeq 3 · 꼬리 없음 기대)", cur, err)
	}
}

// e4 · D14 · D15 — 만료가 컷오프 행을 지우면 같은 트랜잭션에서 뒤 방송의 첫 자격 행으로 옮긴다. 무효 컷오프가 남지
// 않으므로 janitor 로컬 삭제가 멈추지 않고, 뒤 방송의 되감기 시작점도 살아 있다.
func TestExpiryE4ExpiredBroadcastAdvancesCutoff(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-adv"
	expPutSession(t, pool, expSessionRow{id: "ad-old", stream: stream})
	expPutSession(t, pool, expSessionRow{id: "ad-new", stream: stream})
	expPutSeg(t, pool, expSegRow{stream: stream, seq: 0, session: "ad-old"}, expSegRow{stream: stream, seq: 1, session: "ad-old"},
		expSegRow{stream: stream, seq: 2}, // 사이의 자격 없는 행(세션 NULL) — 건너뛴다
		expSegRow{stream: stream, seq: 3, session: "ad-new"}, expSegRow{stream: stream, seq: 4, session: "ad-new"})
	lcPutCutoff(t, pool, stream, 0)

	res, err := DeleteExpired(ctx, pool, stream, []int64{0, 1})
	if err != nil {
		t.Fatalf("DeleteExpired: %v", err)
	}
	if res.Cutoff != CutoffAdvanced || res.CutoffSeq != 3 {
		t.Fatalf("컷오프 처리 %+v (전진 · 3 기대)", res)
	}
	if seq, _, _, ok := cutoffRow(t, pool, stream); !ok || seq != 3 {
		t.Fatalf("컷오프 행 seq=%d ok=%v (3 기대)", seq, ok)
	}
	cuts, err := NewLocalCleanupStore(pool).InvalidCutoffs(ctx)
	if err != nil || len(cuts) != 0 {
		t.Fatalf("무효 컷오프가 남았다: %+v err=%v", cuts, err)
	}
}

// e4 — 뒤에 자격 행이 없으면 컷오프 행을 지운다(무효로 남기지 않는다). 컷오프보다 작은 seq 를 지우면 건드리지 않는다.
func TestExpiryE4CutoffRemovedOrKept(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()

	gone := "exptest-cut-gone"
	expPutSession(t, pool, expSessionRow{id: "cg-a", stream: gone})
	expPutSeg(t, pool, expSegRow{stream: gone, seq: 0, session: "cg-a"}, expSegRow{stream: gone, seq: 1})
	lcPutCutoff(t, pool, gone, 0)
	res, err := DeleteExpired(ctx, pool, gone, []int64{0})
	if err != nil || res.Cutoff != CutoffRemoved {
		t.Fatalf("자격 생존 행 없음: %+v err=%v (제거 기대)", res, err)
	}
	if _, _, _, ok := cutoffRow(t, pool, gone); ok {
		t.Fatal("컷오프 행이 남았다")
	}

	kept := "exptest-cut-kept"
	expPutSession(t, pool, expSessionRow{id: "ck-a", stream: kept})
	expPutSeg(t, pool, expSegRow{stream: kept, seq: 0}, expSegRow{stream: kept, seq: 1, session: "ck-a"})
	lcPutCutoff(t, pool, kept, 1)
	res, err = DeleteExpired(ctx, pool, kept, []int64{0})
	if err != nil || res.Cutoff != CutoffUnchanged {
		t.Fatalf("컷오프 아래 행만 지움: %+v err=%v (그대로 기대)", res, err)
	}
	if seq, _, _, ok := cutoffRow(t, pool, kept); !ok || seq != 1 {
		t.Fatalf("컷오프가 바뀌었다: seq=%d ok=%v", seq, ok)
	}
}

// e1 — 무효 컷오프 스트림은 행을 하나도 지우지 않고 ErrCutoffInvalid 로 멈춘다(fail-closed). 객체 삭제 앞의 검사
// (ExpiryCutoffValid)도 같은 조각으로 거짓을 낸다.
func TestExpiryE1DeleteAxesStopOnInvalidCutoff(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-e1"
	expPutSession(t, pool, expSessionRow{id: "e1-a", stream: stream})
	expPutSeg(t, pool, expSegRow{stream: stream, seq: 1, session: "e1-a"}, expSegRow{stream: stream, seq: 2, session: "e1-a"})
	lcPutCutoff(t, pool, stream, 0) // seq 0 행이 없다 = 무효

	ok, err := ExpiryCutoffValid(ctx, pool, stream)
	if err != nil || ok {
		t.Fatalf("ExpiryCutoffValid = %v err=%v (거짓 기대)", ok, err)
	}
	_, err = DeleteExpired(ctx, pool, stream, []int64{1, 2})
	if !errors.Is(err, ErrCutoffInvalid) {
		t.Fatalf("DeleteExpired err=%v (ErrCutoffInvalid 기대)", err)
	}
	if !slices.Equal(expSeqs(t, pool, stream), []int64{1, 2}) {
		t.Fatalf("무효 컷오프 스트림의 행이 지워졌다: %v", expSeqs(t, pool, stream))
	}
}

// e3 — 한 스트림의 무효 컷오프가 다른 스트림 삭제를 막지 않는다. 컷오프가 없는 스트림은 유효로 본다.
func TestExpiryE3OtherStreamsDeleteWhileOneInvalid(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	bad, good := "exptest-e3-bad", "exptest-e3-good"
	expPutSession(t, pool, expSessionRow{id: "e3-bad", stream: bad})
	expPutSession(t, pool, expSessionRow{id: "e3-good", stream: good})
	expPutSeg(t, pool, expSegRow{stream: bad, seq: 1, session: "e3-bad"}, expSegRow{stream: good, seq: 0, session: "e3-good"})
	lcPutCutoff(t, pool, bad, 0)

	if _, err := DeleteExpired(ctx, pool, bad, []int64{1}); !errors.Is(err, ErrCutoffInvalid) {
		t.Fatalf("무효 스트림 err=%v", err)
	}
	ok, err := ExpiryCutoffValid(ctx, pool, good)
	if err != nil || !ok {
		t.Fatalf("컷오프 없는 스트림 ExpiryCutoffValid = %v err=%v (참 기대)", ok, err)
	}
	res, err := DeleteExpired(ctx, pool, good, []int64{0})
	if err != nil || res.RowsDeleted != 1 {
		t.Fatalf("정상 스트림 삭제: %+v err=%v", res, err)
	}
}

// 빈 세션 — 조각 0 이 된 세션은 GAP 를 지우고 후속의 계승을 끊은 뒤 지운다. 조각이 남은 세션은 그대로다. 지운 seq 의
// GAP 행도 함께 지운다.
func TestDeleteExpiredRemovesGapsAndEmptiedSessions(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-sess"
	expPutSession(t, pool, expSessionRow{id: "ss-a", stream: stream})
	expPutSession(t, pool, expSessionRow{id: "ss-b", stream: stream, inherits: "ss-a"})
	expPutSeg(t, pool, expSegRow{stream: stream, seq: 0, session: "ss-a"}, expSegRow{stream: stream, seq: 2, session: "ss-b"},
		expSegRow{stream: stream, seq: 3, session: "ss-b"})
	for _, g := range []struct {
		seq     int64
		session string
	}{{0, "ss-a"}, {1, "ss-a"}, {2, "ss-b"}, {4, "ss-b"}} { // seq 1 · 4 는 조각 행이 없는 GAP
		if _, err := pool.Exec(ctx, `INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason) VALUES ($1, $2, $3, 'upload_stall')`,
			stream, g.seq, g.session); err != nil {
			t.Fatalf("GAP 픽스처: %v", err)
		}
	}

	res, err := DeleteExpired(ctx, pool, stream, []int64{0, 2})
	if err != nil {
		t.Fatalf("DeleteExpired: %v", err)
	}
	if !slices.Equal(res.SessionsDeleted, []string{"ss-a"}) || res.GapsDeleted != 3 || res.RowsDeleted != 2 {
		t.Fatalf("결과 %+v (세션 ss-a · GAP 3 · 행 2 기대)", res)
	}
	if n := expCount(t, pool, `SELECT count(*) FROM stream_published_gaps WHERE stream_id = $1 AND seq = 4`, stream); n != 1 {
		t.Fatalf("남은 세션의 범위 밖 GAP 가 지워졌다(%d)", n)
	}
	if n := expCount(t, pool, `SELECT count(*) FROM stream_sessions WHERE session_id = 'ss-b' AND inherits_session IS NULL`); n != 1 {
		t.Fatal("지운 세션을 가리키는 계승이 남았다")
	}
}

// D51 — 세션이 다 사라진 회차의 outbox 행을 같은 트랜잭션에서 지운다. 회차에 세션이 남으면 그대로다.
func TestDeleteExpiredRemovesOrphanOutbox(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-outbox"
	expPutSession(t, pool, expSessionRow{id: "ob-r1", stream: stream, round: "ob-r1"})
	expPutSession(t, pool, expSessionRow{id: "ob-r2a", stream: stream, round: "ob-r2a"})
	expPutSession(t, pool, expSessionRow{id: "ob-r2b", stream: stream, round: "ob-r2a"})
	expPutSeg(t, pool, expSegRow{stream: stream, seq: 0, session: "ob-r1"}, expSegRow{stream: stream, seq: 1, session: "ob-r2a"},
		expSegRow{stream: stream, seq: 2, session: "ob-r2b"})
	for _, ev := range []struct{ id, round string }{{"e-r1-1", "ob-r1"}, {"e-r1-2", "ob-r1"}, {"e-r2-1", "ob-r2a"}} {
		if _, err := pool.Exec(ctx, `
INSERT INTO broadcast_lifecycle_outbox (event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id)
VALUES ($1, $2, 'started', (SELECT count(*) FROM broadcast_lifecycle_outbox WHERE round_id = $2), now(), $3)`,
			ev.id, ev.round, stream); err != nil {
			t.Fatalf("outbox 픽스처: %v", err)
		}
	}

	res, err := DeleteExpired(ctx, pool, stream, []int64{0, 1})
	if err != nil {
		t.Fatalf("DeleteExpired: %v", err)
	}
	if res.OutboxDeleted != 2 {
		t.Fatalf("outbox 삭제 %d (2 기대 — 회차 ob-r1 만)", res.OutboxDeleted)
	}
	if n := expCount(t, pool, `SELECT count(*) FROM broadcast_lifecycle_outbox WHERE round_id = 'ob-r1'`); n != 0 {
		t.Fatalf("지워진 회차의 outbox 행이 %d 남았다", n)
	}
	if n := expCount(t, pool, `SELECT count(*) FROM broadcast_lifecycle_outbox WHERE round_id = 'ob-r2a'`); n != 1 {
		t.Fatalf("세션이 남은 회차의 outbox 행이 지워졌다(%d)", n)
	}
}

// D18 — janitor 가 행을 잠근 동안 만료는 lock_timeout 5초에 55P03 으로 물러난다(행은 그대로 · 다음 회차 재시도).
func TestDeleteExpiredLockTimeoutWhileRowHeld(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-lock"
	expPutSession(t, pool, expSessionRow{id: "lk-a", stream: stream})
	expPutSeg(t, pool, expSegRow{stream: stream, seq: 0, session: "lk-a", local: true})

	holder, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("잠금 트랜잭션 시작: %v", err)
	}
	defer func() { _ = holder.Rollback(ctx) }()
	if _, err := holder.Exec(ctx, `SELECT 1 FROM stream_segments WHERE stream_id = $1 AND seq = 0 FOR UPDATE`, stream); err != nil {
		t.Fatalf("행 잠금: %v", err)
	}

	dctx, cancel := context.WithTimeout(ctx, 7*time.Second) // janitor 상한(8초)보다 짧게 — 서버 상한이 먼저 와야 한다
	defer cancel()
	start := time.Now()
	_, err = DeleteExpired(dctx, pool, stream, []int64{0})
	if !IsLockTimeout(err) {
		t.Fatalf("err=%v (55P03 기대)", err)
	}
	if took := time.Since(start); took < 4*time.Second {
		t.Fatalf("lock_timeout 전에 끝났다(%v)", took)
	}
	_ = holder.Rollback(ctx)
	if !slices.Equal(expSeqs(t, pool, stream), []int64{0}) {
		t.Fatal("잠금 실패인데 행이 지워졌다")
	}
}

// D58 — 컷오프 제거 문장의 스냅숏 뒤에 새 방송 첫 조각이 끼어 컷오프 없이 남는 창(한계). 다음 조각이 주조한다.
func TestExpiryD58RemovalWindowNextSegmentSeeds(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-d58"
	expPutSession(t, pool, expSessionRow{id: "d58-old", stream: stream})
	expPutSeg(t, pool, expSegRow{stream: stream, seq: 0, session: "d58-old"}, expSegRow{stream: stream, seq: 1, session: "d58-old"})
	lcPutCutoff(t, pool, stream, 0)
	wall := time.Now().UTC().Truncate(time.Millisecond)
	openSession(t, pool, "d58-new", stream, wall)

	// 창: 새 방송 첫 조각(seq 2)의 INSERT 가 커밋 전이다 — 컷오프가 있어 advisory 잠금을 잡지 않은 경로와 같다.
	late, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("끼어드는 트랜잭션: %v", err)
	}
	defer func() { _ = late.Rollback(ctx) }()
	if err := expInsertSeg(ctx, late, expSegRow{stream: stream, seq: 2, session: "d58-new", start: wall}); err != nil {
		t.Fatalf("끼어드는 조각: %v", err)
	}

	res, err := DeleteExpired(ctx, pool, stream, []int64{0, 1})
	if err != nil || res.Cutoff != CutoffRemoved {
		t.Fatalf("창 재현 실패: %+v err=%v (제거 기대 — 커밋 전 행은 안 보인다)", res, err)
	}
	if err := late.Commit(ctx); err != nil {
		t.Fatalf("끼어드는 조각 커밋: %v", err)
	}

	rec := segAt(stream, 3, wall.Add(4*time.Second), 4000)
	_, sr, err := newSessionStore(pool).Insert(ctx, rec, eligibleSeed(rec.StartWallUTC), liveIngress())
	if err != nil || !sr.Seeded {
		t.Fatalf("다음 조각이 주조하지 않았다: %+v err=%v", sr, err)
	}
	if seq, _, _, ok := cutoffRow(t, pool, stream); !ok || seq != 3 {
		t.Fatalf("컷오프 seq=%d ok=%v (3 기대)", seq, ok)
	}
}

// 한 트랜잭션 상한(500행)을 넘는 묶음은 거부하고, 빈 묶음은 문장 없이 끝난다.
func TestDeleteExpiredBatchBounds(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	if res, err := DeleteExpired(ctx, pool, "exptest-batch", nil); err != nil || res.RowsDeleted != 0 {
		t.Fatalf("빈 묶음: %+v err=%v", res, err)
	}
	big := make([]int64, ExpiryDeleteBatch+1)
	for i := range big {
		big[i] = int64(i)
	}
	if _, err := DeleteExpired(ctx, pool, "exptest-batch", big); err == nil {
		t.Fatal("상한을 넘는 묶음이 통과했다")
	}
	if _, err := DeleteExpired(ctx, pool, "", []int64{0}); err == nil {
		t.Fatal("빈 스트림이 통과했다")
	}
	if _, err := ExpiryRows(ctx, pool, ExpiryQuery{StreamID: "exptest-batch"}); err == nil {
		t.Fatal("상한 없는 범위가 통과했다")
	}
}

// 만료 문장 전부가 사이드카 계정(media_indexer) 권한 안에서 돈다 — 운영에서 42501 로 반쯤 지운 상태를 만들지 않는다.
func TestExpiryStatementsRunUnderMediaIndexerRole(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	stream := "exptest-role"
	expPutSession(t, admin, expSessionRow{id: "rl-a", stream: stream, round: "rl-a", dvrKey: "dvr/rl", initKey: "init/rl"})
	expPutSession(t, admin, expSessionRow{id: "rl-b", stream: stream, inherits: "rl-a"})
	expPutSeg(t, admin, expSegRow{stream: stream, seq: 0, session: "rl-a"}, expSegRow{stream: stream, seq: 1, session: "rl-b"})
	lcPutCutoff(t, admin, stream, 0)
	if _, err := admin.Exec(ctx, `INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason) VALUES ($1, 0, 'rl-a', 'upload_stall')`, stream); err != nil {
		t.Fatalf("GAP 픽스처: %v", err)
	}
	if _, err := admin.Exec(ctx, `
INSERT INTO broadcast_lifecycle_outbox (event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id)
VALUES ('rl-ev', 'rl-a', 'started', 0, now(), $1)`, stream); err != nil {
		t.Fatalf("outbox 픽스처: %v", err)
	}

	gone := "exptest-role-gone" // 자격 생존 행이 없어 컷오프를 지우는 갈래
	expPutSession(t, admin, expSessionRow{id: "rl-g", stream: gone})
	expPutSeg(t, admin, expSegRow{stream: gone, seq: 0, session: "rl-g"})
	lcPutCutoff(t, admin, gone, 0)

	role := pgtest.NewMediaIndexerRole(t, admin)
	q := expRange(stream, nil, expAt(1))
	if _, err := MarkExpired(ctx, role.Pool, q); err != nil {
		t.Fatalf("MarkExpired: %v", err)
	}
	if _, err := ExpiryRows(ctx, role.Pool, q); err != nil {
		t.Fatalf("ExpiryRows: %v", err)
	}
	if _, err := ExpiryDocuments(ctx, role.Pool, q); err != nil {
		t.Fatalf("ExpiryDocuments: %v", err)
	}
	if _, err := ExpiryCutoffValid(ctx, role.Pool, stream); err != nil {
		t.Fatalf("ExpiryCutoffValid: %v", err)
	}
	res, err := DeleteExpired(ctx, role.Pool, stream, []int64{0})
	if err != nil {
		t.Fatalf("DeleteExpired: %v", err)
	}
	if res.Cutoff != CutoffAdvanced || !slices.Equal(res.SessionsDeleted, []string{"rl-a"}) || res.OutboxDeleted != 1 {
		t.Fatalf("역할 실행 결과 %+v", res)
	}
	if res, err := DeleteExpired(ctx, role.Pool, gone, []int64{0}); err != nil || res.Cutoff != CutoffRemoved {
		t.Fatalf("역할 실행 컷오프 제거 갈래: %+v err=%v", res, err)
	}
	role.RequireSent(t, map[string]string{
		"expiryRemoveCutoffSQL": expiryRemoveCutoffSQL,
		"markExpiredSQL":        markExpiredSQL, "expiryRowsSQL": expiryRowsSQL, "expiryDocumentsSQL": expiryDocumentsSQL,
		"expiryCutoffValidSQL": expiryCutoffValidSQL, "expiryLockCutoffSQL": expiryLockCutoffSQL,
		"expiryDeleteSQL": expiryDeleteSQL, "expiryEmptiedSessionsSQL": expiryEmptiedSessionsSQL,
		"expiryDeleteGapsSQL": expiryDeleteGapsSQL, "expiryAdvanceCutoffSQL": expiryAdvanceCutoffSQL,
		"expiryUnlinkSuccessorsSQL": expiryUnlinkSuccessorsSQL, "expiryDeleteSessionsSQL": expiryDeleteSessionsSQL,
		"expiryDeleteOutboxSQL": expiryDeleteOutboxSQL,
	})
	role.RequireNoneDenied(t)
}
