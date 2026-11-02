package index

// 로컬 정리 판정(POK-369 · T1)의 PG 통합 시험 — 판정 행렬 · 페이징 · CAS · SchemaReady ·
// RemoveUnderRowLock 의 결과 다섯. PG_DSN 이 없으면 skip(REQUIRE_PG=1 이면 실패).

import (
	"context"
	"errors"
	"fmt"
	"net"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// lcRow 는 판정 시험의 한 행이다. 영값이 「지울 수 있는 행」에 가깝게 lcPut 이 기본값을 편다:
// ② uploaded · ③ pending · 1시간 전 시작 · 1시간 전 업로드 · bytes 1000 · 세션 없음.
type lcRow struct {
	stream  string
	seq     int64
	session string // 빈 값 = NULL

	start              time.Time // 영값 = 1시간 전
	durationMS         int32     // 0 = 4000
	upload             string    // 빈 값 = uploaded
	playback           string    // 빈 값 = pending
	uploadedAt         time.Time // 영값 = 1시간 전(upload 가 uploaded 일 때만)
	playbackUploadedAt time.Time // 영값 = NULL
	nullBytes          bool
	nullPath           bool
}

func lcPath(stream string, seq int64) string {
	return fmt.Sprintf("/recordings/%s/seg_%06d.mp4", stream, seq)
}

func lcPut(t *testing.T, pool *pgxpool.Pool, rows ...lcRow) {
	t.Helper()
	hourAgo := time.Now().UTC().Add(-time.Hour)
	for _, r := range rows {
		if r.start.IsZero() {
			r.start = hourAgo
		}
		if r.durationMS == 0 {
			r.durationMS = 4000
		}
		if r.upload == "" {
			r.upload = "uploaded"
		}
		if r.playback == "" {
			r.playback = "pending"
		}
		var uploadedAt, playbackAt, session, path, bytes any
		if r.upload == "uploaded" {
			uploadedAt = hourAgo
			if !r.uploadedAt.IsZero() {
				uploadedAt = r.uploadedAt
			}
		}
		if !r.playbackUploadedAt.IsZero() {
			playbackAt = r.playbackUploadedAt
		}
		if r.session != "" {
			session = r.session
		}
		if !r.nullPath {
			path = lcPath(r.stream, r.seq)
		}
		if !r.nullBytes {
			bytes = int64(1000)
		}
		_, err := pool.Exec(context.Background(), `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
     upload_state, uploaded_at, bytes, is_discontinuity, session_id,
     playback_upload_state, playback_uploaded_at)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, false, $11, $12, $13)`,
			r.stream, r.seq, r.seq*4000, r.start, r.durationMS, S3Key(r.stream, r.seq, r.start),
			path, r.upload, uploadedAt, bytes, session, r.playback, playbackAt)
		if err != nil {
			t.Fatalf("판정 픽스처 삽입 실패 %s/%d: %v", r.stream, r.seq, err)
		}
	}
}

// lcSessionRow 는 판정 시험의 회차 한 행이다.
type lcSessionRow struct {
	id, stream   string
	state        string    // 빈 값 = live
	endingAt     time.Time // 영값 = NULL
	endedAt      time.Time // 영값 = NULL
	inherits     string    // 빈 값 = NULL
	initUploaded bool
}

func lcPutSession(t *testing.T, pool *pgxpool.Pool, s lcSessionRow) {
	t.Helper()
	state := s.state
	if state == "" {
		state = "live"
	}
	nullable := func(v time.Time) any {
		if v.IsZero() {
			return nil
		}
		return v
	}
	var inherits, initAt any
	if s.inherits != "" {
		inherits = s.inherits
	}
	if s.initUploaded {
		initAt = time.Now().UTC().Add(-time.Hour)
	}
	_, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, ending_at, ended_at,
                             inherits_session, init_uploaded_at, first_pdt)
VALUES ($1, $2, now() - interval '2 hours', $3, $4, $5, $6, $7, now() - interval '2 hours')`,
		s.id, s.stream, state, nullable(s.endingAt), nullable(s.endedAt), inherits, initAt)
	if err != nil {
		t.Fatalf("회차 픽스처 삽입 실패 %s: %v", s.id, err)
	}
}

func lcPutCutoff(t *testing.T, pool *pgxpool.Pool, stream string, cutoffSeq int64) {
	t.Helper()
	_, err := pool.Exec(context.Background(),
		`INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
		 VALUES ($1, $2, 'live_ingress', 'watcher')`, stream, cutoffSeq)
	if err != nil {
		t.Fatalf("컷오프 픽스처 삽입 실패 %s: %v", stream, err)
	}
}

// lcPolicy 는 janitor 기본값과 같은 정책이다(설계 2-5 — 그레이스 8m · 창 300s · 24h − 1h).
func lcPolicy() LocalCleanupPolicy {
	return LocalCleanupPolicy{Grace: 8 * time.Minute, ReconnectWindow: 300 * time.Second, ExternalDeleteAge: 23 * time.Hour}
}

// lcCandidates 는 정책 p 로 표 전체를 끝까지 훑어 stream 의 후보를 seq → 행으로 돌려준다.
func lcCandidates(t *testing.T, store *LocalCleanupStore, p LocalCleanupPolicy, stream string) map[int64]LocalSegment {
	t.Helper()
	out := map[int64]LocalSegment{}
	cur := LocalCursor{}
	for range 100 {
		page, next, err := store.DeletableSegments(context.Background(), p, 50, cur)
		if err != nil {
			t.Fatalf("DeletableSegments 실패: %v", err)
		}
		for _, s := range page {
			if s.StreamID == stream {
				out[s.Seq] = s
			}
		}
		if len(page) == 0 {
			return out
		}
		cur = next
	}
	t.Fatal("DeletableSegments 가 100 페이지 안에 끝나지 않았다")
	return nil
}

func lcSeqs(m map[int64]LocalSegment) []int64 {
	seqs := make([]int64, 0, len(m))
	for s := range m {
		seqs = append(seqs, s)
	}
	slices.Sort(seqs)
	return seqs
}

func lcLocalPath(t *testing.T, pool *pgxpool.Pool, stream string, seq int64) *string {
	t.Helper()
	var p *string
	if err := pool.QueryRow(context.Background(),
		`SELECT local_path FROM stream_segments WHERE stream_id=$1 AND seq=$2`, stream, seq).Scan(&p); err != nil {
		t.Fatalf("local_path 조회 실패 %s/%d: %v", stream, seq, err)
	}
	return p
}

// D08 · 완료 조건 3 — 컷오프가 없는 스트림은 ③ 과 무관하게 ② uploaded 만 본다(면제).
// pending · failed · 이미 NULL 인 행은 후보가 아니다.
func TestLocalCleanupExemptStreamNeedsOnlyArchiveUploaded(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	const s = "lc-exempt"
	lcPut(t, pool,
		lcRow{stream: s, seq: 0},
		lcRow{stream: s, seq: 1, upload: "pending"},
		lcRow{stream: s, seq: 2, upload: "failed"},
		lcRow{stream: s, seq: 3, nullPath: true},
		lcRow{stream: s, seq: 4, playback: "failed"},
	)

	got := lcSeqs(lcCandidates(t, store, lcPolicy(), s))

	if want := []int64{0, 4}; !slices.Equal(got, want) {
		t.Fatalf("후보 seq = %v, want %v", got, want)
	}
}

// D07 · 완료 조건 2 · 4 — seq < 컷오프는 면제, seq ≥ 컷오프는 ③ uploaded 필수.
// seq = 컷오프를 면제로 보면 컷오프 행이 영구 홀이 된다(되감기 · VOD 동결).
func TestLocalCleanupCutoffAppliesFromCutoffSeq(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	const s = "lc-cutoff"
	lcPutCutoff(t, pool, s, 5)
	old := time.Now().UTC().Add(-time.Hour)
	lcPut(t, pool,
		lcRow{stream: s, seq: 4},
		lcRow{stream: s, seq: 5},
		lcRow{stream: s, seq: 6, playback: "uploaded", playbackUploadedAt: old},
		lcRow{stream: s, seq: 7, playback: "failed"},
	)

	got := lcSeqs(lcCandidates(t, store, lcPolicy(), s))

	if want := []int64{4, 6}; !slices.Equal(got, want) {
		t.Fatalf("후보 seq = %v, want %v (4 면제 · 5 = 컷오프 ③ pending · 6 ③ uploaded · 7 ③ failed)", got, want)
	}
}

// D09 — 컷오프가 가리키는 행이 없는 스트림(무효 컷오프)은 삭제 축 전체를 멈추고 신호를 낸다.
// 다른 스트림은 영향받지 않는다.
func TestLocalCleanupInvalidCutoffStopsStreamAndIsReported(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	lcPutCutoff(t, pool, "lc-invalid", 9)
	lcPutCutoff(t, pool, "lc-valid", 1)
	lcPut(t, pool,
		lcRow{stream: "lc-invalid", seq: 0},
		lcRow{stream: "lc-invalid", seq: 1},
		lcRow{stream: "lc-valid", seq: 0},
		lcRow{stream: "lc-valid", seq: 1},
	)

	if got := lcSeqs(lcCandidates(t, store, lcPolicy(), "lc-invalid")); len(got) != 0 {
		t.Errorf("무효 컷오프 스트림 후보 = %v, want 없음", got)
	}
	if got := lcSeqs(lcCandidates(t, store, lcPolicy(), "lc-valid")); !slices.Equal(got, []int64{0}) {
		t.Errorf("유효 컷오프 스트림 후보 = %v, want [0]", got)
	}
	invalid, err := store.InvalidCutoffs(context.Background())
	if err != nil {
		t.Fatalf("InvalidCutoffs 실패: %v", err)
	}
	if want := []InvalidCutoff{{StreamID: "lc-invalid", CutoffSeq: 9}}; !slices.Equal(invalid, want) {
		t.Fatalf("InvalidCutoffs = %+v, want %+v", invalid, want)
	}
}

// D12 — 그레이스 기준은 셋(② 확정 · ③ 확정 · 조각 끝) 중 가장 늦은 시각이다. 늦게 업로드된
// 행을 업로드 직후 지우면 늦은 판독자(스위퍼 재작업 · 꼬리 재성장 탐지)의 여유가 사라진다.
func TestLocalCleanupGraceStartsFromLatestOfThreeClocks(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	const s = "lc-grace"
	now := time.Now().UTC().Truncate(time.Microsecond)
	old := now.Add(-time.Hour)
	recent := now.Add(-2 * time.Minute)
	lcPut(t, pool,
		lcRow{stream: s, seq: 0, start: old, uploadedAt: old.Add(time.Minute)},
		lcRow{stream: s, seq: 1, start: old, uploadedAt: recent},
		lcRow{stream: s, seq: 2, start: old, playback: "uploaded", playbackUploadedAt: recent},
		lcRow{stream: s, seq: 3, start: recent, uploadedAt: old},
	)

	got := lcCandidates(t, store, lcPolicy(), s)

	if seqs := lcSeqs(got); !slices.Equal(seqs, []int64{0}) {
		t.Fatalf("후보 seq = %v, want [0] (1 ② 최근 · 2 ③ 최근 · 3 조각 최근)", seqs)
	}
	if want := old.Add(time.Minute); !got[0].GraceBasis.Equal(want) {
		t.Errorf("GraceBasis = %v, want %v(② 확정 — 셋 중 최댓값)", got[0].GraceBasis, want)
	}
}

// E1 · D37 — 외부 삭제 나이는 조각 시작부터 잰다. 시작은 나이를 넘겼고 끝은 아직인 조각이
// 「외부 삭제 가능」이어야 한다(MediaMTX 정리기와 같은 축).
func TestLocalCleanupExternalDeleteDueCountsFromSegmentStart(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	const s = "lc-extdue"
	p := lcPolicy()
	p.ExternalDeleteAge = time.Hour
	now := time.Now().UTC()
	lcPut(t, pool,
		// 시작은 나이 + 30s 전, 끝(시작 + 60s)은 나이 − 30s 전이다.
		lcRow{stream: s, seq: 0, start: now.Add(-time.Hour - 30*time.Second), durationMS: 60000},
		lcRow{stream: s, seq: 1, start: now.Add(-time.Hour + 30*time.Second), durationMS: 4000},
	)

	got := lcCandidates(t, store, p, s)

	if len(got) != 2 {
		t.Fatalf("후보 seq = %v, want [0 1]", lcSeqs(got))
	}
	if !got[0].ExternalDeleteDue {
		t.Error("seq 0 ExternalDeleteDue = false, want true — 시작 기준이면 나이를 넘겼다")
	}
	if got[1].ExternalDeleteDue {
		t.Error("seq 1 ExternalDeleteDue = true, want false — 시작이 아직 나이 안이다")
	}
	if got[0].LocalPath != lcPath(s, 0) {
		t.Errorf("LocalPath = %q, want %q", got[0].LocalPath, lcPath(s, 0))
	}
}

// D10 — 보호 ① : init 미확정 · 미종료 회차의 최신 조각(local_path · bytes 둘 다 있는)은
// init 재수거의 원천이라 지우지 않는다. 그 행은 PendingUploads(AxisInit) 가 읽는 행과 같다.
func TestLocalCleanupProtectsInitSourceSegment(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	const s, sess = "lc-prot1", "lc-prot1-s"
	lcPutSession(t, pool, lcSessionRow{id: sess, stream: s})
	lcPut(t, pool,
		lcRow{stream: s, seq: 0, session: sess}, // 회차 첫 조각 — 보호 ②(live)
		lcRow{stream: s, seq: 1, session: sess},
		lcRow{stream: s, seq: 2, session: sess}, // init 원천(bytes 있는 최신)
		lcRow{stream: s, seq: 3, session: sess, nullBytes: true},
	)

	got := lcSeqs(lcCandidates(t, store, lcPolicy(), s))

	if want := []int64{1, 3}; !slices.Equal(got, want) {
		t.Fatalf("후보 seq = %v, want %v (0 보호 ② · 2 보호 ①)", got, want)
	}
	targets, _, err := NewUploadStore(pool).PendingUploads(context.Background(), AxisInit, 0, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads(AxisInit) 실패: %v", err)
	}
	if len(targets) != 1 || targets[0].LocalPath != lcPath(s, 2) {
		t.Fatalf("init 재수거 원천 = %+v, want %s 한 건 — 보호 ① 선택자와 갈렸다", targets, lcPath(s, 2))
	}

	if _, err := pool.Exec(context.Background(),
		`UPDATE stream_sessions SET init_uploaded_at = now() WHERE session_id = $1`, sess); err != nil {
		t.Fatalf("init 확정 픽스처 실패: %v", err)
	}
	if got := lcSeqs(lcCandidates(t, store, lcPolicy(), s)); !slices.Equal(got, []int64{1, 2, 3}) {
		t.Fatalf("init 확정 뒤 후보 = %v, want [1 2 3] — 보호 ① 은 init 확정으로 풀린다", got)
	}
}

// D11 · K1 · K2 — 보호 ② : 각 회차의 첫 조각(seq 최소, local_path 무관)은 회차가 live 이거나,
// 종료 + 재접속 창 + 그레이스 안이거나, 그 회차를 계승한 회차의 init 이 미확정 · 미종료인
// 동안 보존한다.
func TestLocalCleanupProtectsFirstSegmentOfSession(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	now := time.Now().UTC()
	cases := []struct {
		name      string
		session   lcSessionRow
		heir      *lcSessionRow // 이 회차를 계승한 회차
		firstNull bool          // 첫 조각이 이미 지워졌다(local_path NULL)
		want      []int64
	}{
		{name: "live 면 보호", session: lcSessionRow{}, want: []int64{1}},
		{name: "종료 + 창 + 그레이스 지나면 해제", session: lcSessionRow{state: "ended", endedAt: now.Add(-20 * time.Minute)}, want: []int64{0, 1}},
		{name: "창은 지났어도 그레이스 여유 안이면 보호", session: lcSessionRow{state: "ended", endedAt: now.Add(-10 * time.Minute)}, want: []int64{1}},
		{name: "ending 도 종료 시각으로 잰다", session: lcSessionRow{state: "ending", endingAt: now.Add(-10 * time.Minute)}, want: []int64{1}},
		{name: "종료 시각이 없으면 마지막 조각 끝으로 잰다", session: lcSessionRow{state: "ended"}, want: []int64{0, 1}},
		{
			name:    "계승 회차 init 미확정이면 오래 지나도 보호",
			session: lcSessionRow{state: "ended", endedAt: now.Add(-time.Hour)},
			heir:    &lcSessionRow{}, want: []int64{1},
		},
		{
			name:    "계승 회차 init 확정이면 해제",
			session: lcSessionRow{state: "ended", endedAt: now.Add(-time.Hour)},
			heir:    &lcSessionRow{initUploaded: true}, want: []int64{0, 1},
		},
		{name: "첫 조각이 이미 지워졌으면 다음 조각은 첫 조각이 아니다", session: lcSessionRow{}, firstNull: true, want: []int64{1}},
	}
	for i, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			s := fmt.Sprintf("lc-prot2-%d", i)
			sess := c.session
			sess.id, sess.stream, sess.initUploaded = s+"-s", s, true // init 확정 — 보호 ① 을 끈다
			lcPutSession(t, pool, sess)
			if c.heir != nil {
				h := *c.heir
				h.id, h.stream, h.inherits = s+"-h", s, sess.id
				lcPutSession(t, pool, h)
			}
			lcPut(t, pool,
				lcRow{stream: s, seq: 0, session: sess.id, nullPath: c.firstNull},
				lcRow{stream: s, seq: 1, session: sess.id},
			)

			got := lcSeqs(lcCandidates(t, store, lcPolicy(), s))

			if !slices.Equal(got, c.want) {
				t.Fatalf("후보 seq = %v, want %v", got, c.want)
			}
		})
	}
}

// 페이징 — (stream_id, seq) 키셋. 다음 커서 = 페이지 마지막 행, 빈 페이지면 받은 커서 그대로.
func TestLocalCleanupPagesByStreamAndSeq(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	lcPut(t, pool,
		lcRow{stream: "lc-page-b", seq: 1},
		lcRow{stream: "lc-page-a", seq: 2},
		lcRow{stream: "lc-page-b", seq: 0},
		lcRow{stream: "lc-page-a", seq: 0},
		lcRow{stream: "lc-page-a", seq: 1},
	)
	want := [][]LocalCursor{
		{{"lc-page-a", 0}, {"lc-page-a", 1}},
		{{"lc-page-a", 2}, {"lc-page-b", 0}},
		{{"lc-page-b", 1}},
		{},
	}

	cur := LocalCursor{}
	for i, w := range want {
		page, next, err := store.DeletableSegments(context.Background(), lcPolicy(), 2, cur)
		if err != nil {
			t.Fatalf("페이지 %d 실패: %v", i, err)
		}
		got := make([]LocalCursor, 0, len(page))
		for _, s := range page {
			got = append(got, LocalCursor{s.StreamID, s.Seq})
		}
		if !slices.Equal(got, w) {
			t.Fatalf("페이지 %d = %v, want %v", i, got, w)
		}
		wantNext := cur
		if len(w) > 0 {
			wantNext = w[len(w)-1]
		}
		if next != wantNext {
			t.Fatalf("페이지 %d 다음 커서 = %v, want %v", i, next, wantNext)
		}
		cur = next
	}
}

// D06 — ClearLocalPath 는 기대 경로가 같을 때만 NULL 로 바꾼다.
func TestLocalCleanupClearLocalPathComparesExpectedPath(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	ctx := context.Background()
	const s = "lc-clear"
	lcPut(t, pool, lcRow{stream: s, seq: 0})

	if ok, err := store.ClearLocalPath(ctx, s, 0, "/recordings/lc-clear/other.mp4"); err != nil || ok {
		t.Fatalf("다른 경로 ClearLocalPath = (%v, %v), want (false, nil)", ok, err)
	}
	if p := lcLocalPath(t, pool, s, 0); p == nil {
		t.Fatal("다른 경로로 NULL 이 됐다")
	}
	if ok, err := store.ClearLocalPath(ctx, s, 0, lcPath(s, 0)); err != nil || !ok {
		t.Fatalf("같은 경로 ClearLocalPath = (%v, %v), want (true, nil)", ok, err)
	}
	if p := lcLocalPath(t, pool, s, 0); p != nil {
		t.Fatalf("local_path = %q, want NULL", *p)
	}
	if ok, err := store.ClearLocalPath(ctx, s, 0, lcPath(s, 0)); err != nil || ok {
		t.Fatalf("두 번째 ClearLocalPath = (%v, %v), want (false, nil) — 멱등", ok, err)
	}
	if ok, err := store.ClearLocalPath(ctx, s, 99, lcPath(s, 99)); err != nil || ok {
		t.Fatalf("없는 행 ClearLocalPath = (%v, %v), want (false, nil)", ok, err)
	}
}

// D25 — janitor 둘이 같은 행을 동시에 지워도 장부 갱신은 한 번이다.
func TestLocalCleanupConcurrentClearsSucceedOnce(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	const s = "lc-clear2"
	lcPut(t, pool, lcRow{stream: s, seq: 0})

	const n = 8
	var wg sync.WaitGroup
	results := make([]bool, n)
	errs := make([]error, n)
	for i := range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results[i], errs[i] = store.ClearLocalPath(context.Background(), s, 0, lcPath(s, 0))
		}()
	}
	wg.Wait()

	wins := 0
	for i := range n {
		if errs[i] != nil {
			t.Fatalf("ClearLocalPath[%d] 오류: %v", i, errs[i])
		}
		if results[i] {
			wins++
		}
	}
	if wins != 1 {
		t.Fatalf("성공 수 = %d, want 1", wins)
	}
}

// lcEmptyPool 은 표가 하나도 없는 전용 DB 다(새 DB · EnsureSchema 전 국면).
func lcEmptyPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	mustBeEmpty := func(ctx context.Context, pool *pgxpool.Pool) error {
		var exists bool
		if err := pool.QueryRow(ctx, `SELECT to_regclass('stream_segments') IS NOT NULL`).Scan(&exists); err != nil {
			return err
		}
		if exists {
			return errors.New("빈 DB 여야 할 전용 DB 에 stream_segments 가 있다 — DROP DATABASE 후 다시 돌려라")
		}
		return nil
	}
	noop := func(context.Context, *pgxpool.Pool) error { return nil }
	return pgtest.Pool(t, "lcempty", mustBeEmpty, noop)
}

// lcPartialSchemaPool 은 판정 표 셋 가운데 stream_cutoffs 만 없는 전용 DB 다(부분 준비 국면).
// 표는 열 없이 이름만 있다 — 준비 확인은 존재만 본다.
func lcPartialSchemaPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	twoOfThree := func(ctx context.Context, pool *pgxpool.Pool) error {
		_, err := pool.Exec(ctx, `
CREATE TABLE IF NOT EXISTS stream_segments ();
CREATE TABLE IF NOT EXISTS stream_sessions ();
DROP TABLE IF EXISTS stream_cutoffs`)
		return err
	}
	noop := func(context.Context, *pgxpool.Pool) error { return nil }
	return pgtest.Pool(t, "lcpartial", twoOfThree, noop)
}

// m1 · D42 — 판정 표 셋이 보이기 전에는 조회하지 않게 하는 준비 확인이다. 셋 가운데 하나라도
// 없으면 준비 전이다.
func TestLocalCleanupSchemaReady(t *testing.T) {
	ctx := context.Background()

	ready, err := NewLocalCleanupStore(newTestPool(t)).SchemaReady(ctx)
	if err != nil || !ready {
		t.Errorf("스키마 있는 DB SchemaReady = (%v, %v), want (true, nil)", ready, err)
	}
	ready, err = NewLocalCleanupStore(lcEmptyPool(t)).SchemaReady(ctx)
	if err != nil || ready {
		t.Errorf("빈 DB SchemaReady = (%v, %v), want (false, nil)", ready, err)
	}
	ready, err = NewLocalCleanupStore(lcPartialSchemaPool(t)).SchemaReady(ctx)
	if err != nil || ready {
		t.Errorf("stream_cutoffs 만 없는 DB SchemaReady = (%v, %v), want (false, nil)", ready, err)
	}
}

// RemoveUnderRowLock 의 결과 다섯. remove 는 같은 값의 행을 잠갔을 때만, 잠금을 쥔 채 불린다.
func TestRowLockOutcomes(t *testing.T) {
	pool := newTestPool(t)
	store := NewLocalCleanupStore(pool)
	ctx := context.Background()

	t.Run("clear 면 Cleared · NULL", func(t *testing.T) {
		lcPut(t, pool, lcRow{stream: "lc-out-c", seq: 0})
		calls := 0
		res, err := store.RemoveUnderRowLock(ctx, "lc-out-c", 0, lcPath("lc-out-c", 0), func() bool { calls++; return true })
		if err != nil || res != (RowLockResult{Outcome: RowLockCleared, Acted: true, Cleared: true}) || calls != 1 {
			t.Fatalf("RemoveUnderRowLock = (%+v, %v) · remove %d회, want (Cleared · Acted · Cleared, nil) · 1회", res, err, calls)
		}
		if p := lcLocalPath(t, pool, "lc-out-c", 0); p != nil {
			t.Fatalf("local_path = %q, want NULL", *p)
		}
	})
	t.Run("clear 가 아니면 Kept · 그대로", func(t *testing.T) {
		lcPut(t, pool, lcRow{stream: "lc-out-k", seq: 0})
		res, err := store.RemoveUnderRowLock(ctx, "lc-out-k", 0, lcPath("lc-out-k", 0), func() bool { return false })
		if err != nil || res != (RowLockResult{Outcome: RowLockKept, Acted: true}) {
			t.Fatalf("RemoveUnderRowLock = (%+v, %v), want (Kept · Acted, nil)", res, err)
		}
		if p := lcLocalPath(t, pool, "lc-out-k", 0); p == nil {
			t.Fatal("Kept 인데 local_path 가 NULL 이 됐다")
		}
	})
	t.Run("행이 없으면 Gone · remove 미호출", func(t *testing.T) {
		res, err := store.RemoveUnderRowLock(ctx, "lc-out-g", 0, lcPath("lc-out-g", 0), lcMustNotRemove(t))
		if err != nil || res != (RowLockResult{Outcome: RowLockGone}) {
			t.Fatalf("RemoveUnderRowLock = (%+v, %v), want (Gone, nil)", res, err)
		}
	})
	t.Run("다른 값이면 PathChanged · remove 미호출", func(t *testing.T) {
		lcPut(t, pool, lcRow{stream: "lc-out-p", seq: 0}, lcRow{stream: "lc-out-p", seq: 1, nullPath: true})
		for _, seq := range []int64{0, 1} { // 0 = 다른 경로, 1 = NULL
			res, err := store.RemoveUnderRowLock(ctx, "lc-out-p", seq, "/recordings/lc-out-p/other.mp4", lcMustNotRemove(t))
			if err != nil || res != (RowLockResult{Outcome: RowLockPathChanged}) {
				t.Fatalf("seq %d RemoveUnderRowLock = (%+v, %v), want (PathChanged, nil)", seq, res, err)
			}
		}
	})
	t.Run("remove 동안 행 잠금을 쥔다", func(t *testing.T) {
		lcPut(t, pool, lcRow{stream: "lc-out-h", seq: 0})
		var probe error
		_, err := store.RemoveUnderRowLock(ctx, "lc-out-h", 0, lcPath("lc-out-h", 0), func() bool {
			probe = lcTryLockRow(ctx, pool, "lc-out-h", 0)
			return false
		})
		if err != nil {
			t.Fatalf("RemoveUnderRowLock 오류: %v", err)
		}
		if !IsLockTimeout(probe) {
			t.Fatalf("remove 안에서 다른 세션의 NOWAIT = %v, want 55P03 — 잠금 없이 remove 가 불렸다", probe)
		}
	})
}

// 설계 3-3 StageCommit · 연결 끊김 — remove(파일 삭제) 뒤 DB 연결이 실제로 끊기면 UPDATE 가 서버에
// 닿지 않는다. 결과는 StageCommit · Acted · Cleared 오류(T2 가 부채 deleted 로 받는다)이고 장부의
// local_path 는 그대로다. 클라이언트 기한(ctx)이 아니라 프록시가 연결을 닫아 만든다.
func TestRowLockConnectionLossAfterRemoveKeepsLedger(t *testing.T) {
	pool := newTestPool(t)
	const s = "lc-out-cut"
	lcPut(t, pool, lcRow{stream: s, seq: 0})
	cfg := pool.Config().ConnConfig
	if strings.HasPrefix(cfg.Host, "/") {
		t.Skip("유닉스 소켓 DSN — TCP 차단 프록시를 쓸 수 없다")
	}
	bh := pgtest.StartBlackhole(t, net.JoinHostPort(cfg.Host, strconv.Itoa(int(cfg.Port))))
	store := NewLocalCleanupStore(lcJanitorPool(t, pool, lcJanitorOpts{via: bh.Addr(), maxConns: 1}))
	ctx, cancel := context.WithTimeout(context.Background(), lcHoldDeadline)
	defer cancel()

	res, err := store.RemoveUnderRowLock(ctx, s, 0, lcPath(s, 0), func() bool {
		bh.Sever() // 파일은 지웠고, 그 직후 연결이 끊긴다
		return true
	})

	t.Logf("기록: 연결 끊김 janitor 오류 = %v", err)
	if err == nil || lcSQLState(err) != "(PgError 아님)" || ctx.Err() != nil {
		t.Errorf("janitor 오류 = %v (SQLSTATE %s · ctx %v), want 연결 오류(PgError 아님 · 기한 전)", err, lcSQLState(err), ctx.Err())
	}
	if res != (RowLockResult{Acted: true, Cleared: true, Stage: StageCommit}) {
		t.Errorf("janitor 결과 = %+v, want Acted · Cleared · StageCommit", res)
	}
	if p := lcLocalPath(t, pool, s, 0); p == nil || *p != lcPath(s, 0) {
		t.Errorf("local_path = %v, want %q — 끊긴 연결의 UPDATE 가 반영됐다", p, lcPath(s, 0))
	}
}

// lcMustNotRemove 는 불리면 시험을 실패시키는 remove 다(파일 무접촉 단언).
func lcMustNotRemove(t *testing.T) func() bool {
	return func() bool {
		t.Error("remove 가 불렸다 — 이 갈래는 파일을 건드리면 안 된다")
		return false
	}
}

// lcTryLockRow 는 다른 세션에서 그 행을 NOWAIT 로 잠가 보고 곧바로 되돌린다.
func lcTryLockRow(ctx context.Context, pool *pgxpool.Pool, stream string, seq int64) error {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	_, err = tx.Exec(ctx, `SELECT 1 FROM stream_segments WHERE stream_id=$1 AND seq=$2 FOR UPDATE NOWAIT`, stream, seq)
	return err
}
