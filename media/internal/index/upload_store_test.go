package index

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 계획 4절 1단계의 PG 통합 8케이스다. PG_DSN 이 없으면 전부 skip 된다.

// seedRow 는 한 행을 그대로 넣는다. bytes·local_path 의 NULL 을 표현해야 해서
// Store.Insert 대신 직접 쓴다(Insert 는 두 컬럼을 항상 채운다).
type seedRow struct {
	streamID  string
	seq       int64
	startWall time.Time
	state     UploadState
	bytes     *int64
	nullPath  bool
}

func seed(t *testing.T, pool *pgxpool.Pool, rows ...seedRow) {
	t.Helper()
	ctx := context.Background()
	for _, r := range rows {
		var localPath any = fmt.Sprintf("/recordings/%s/%d.mp4", r.streamID, r.seq)
		if r.nullPath {
			localPath = nil
		}
		var b any
		if r.bytes != nil {
			b = *r.bytes
		}
		_, err := pool.Exec(ctx, `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key,
     local_path, upload_state, bytes, is_discontinuity)
VALUES ($1, $2, $3, $4, 4000, $5, $6, $7, $8, false)`,
			r.streamID, r.seq, r.seq*4000, r.startWall.UTC(),
			S3Key(r.streamID, r.seq, r.startWall), localPath, string(r.state), b)
		if err != nil {
			t.Fatalf("시드 삽입 실패 %s/%d: %v", r.streamID, r.seq, err)
		}
	}
}

func bytesOf(n int64) *int64 { return &n }

// old 는 tailGrace 를 확실히 넘긴 과거 시각이다. 꼬리 예외에 걸리지 않게 한다.
func old(minutes int) time.Time {
	return time.Now().UTC().Add(-time.Duration(minutes) * time.Minute)
}

func stateOf(t *testing.T, pool *pgxpool.Pool, streamID string, seq int64) (UploadState, bool) {
	t.Helper()
	var state string
	var uploadedAt *time.Time
	err := pool.QueryRow(context.Background(),
		`SELECT upload_state, uploaded_at FROM stream_segments WHERE stream_id=$1 AND seq=$2`,
		streamID, seq).Scan(&state, &uploadedAt)
	if err != nil {
		t.Fatalf("상태 조회 실패: %v", err)
	}
	return UploadState(state), uploadedAt != nil
}

// 케이스1 — MarkUploaded 는 bytes 가 일치하는 비-uploaded 행만 확정한다(CAS).
func TestMarkUploadedIsCompareAndSwap(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	sid := "cas-uploaded"

	seed(t, pool, seedRow{sid, 0, old(10), UploadStatePending, bytesOf(1000), false})

	t.Run("기대_bytes_가_다르면_거부", func(t *testing.T) {
		marked, err := st.MarkUploaded(ctx, sid, 0, 999)
		if err != nil {
			t.Fatalf("MarkUploaded 실패: %v", err)
		}
		if marked {
			t.Fatal("marked = true, want false — bytes 가 어긋난 행을 확정하면 안 된다")
		}
	})

	t.Run("기대_bytes_가_같으면_확정하고_uploaded_at_을_채운다", func(t *testing.T) {
		marked, err := st.MarkUploaded(ctx, sid, 0, 1000)
		if err != nil {
			t.Fatalf("MarkUploaded 실패: %v", err)
		}
		if !marked {
			t.Fatal("marked = false, want true")
		}
		state, hasUploadedAt := stateOf(t, pool, sid, 0)
		if state != UploadStateUploaded {
			t.Errorf("upload_state = %q, want uploaded", state)
		}
		if !hasUploadedAt {
			t.Error("uploaded_at 이 비어 있다")
		}
	})

	t.Run("이미_uploaded_면_두_번째_호출은_거부", func(t *testing.T) {
		marked, err := st.MarkUploaded(ctx, sid, 0, 1000)
		if err != nil {
			t.Fatalf("MarkUploaded 실패: %v", err)
		}
		if marked {
			t.Fatal("marked = true, want false — uploaded 는 종착이다")
		}
	})
}

// 케이스2 — MarkFailed 도 같은 CAS 를 쓰되 uploaded 행은 절대 뒤집지 않는다.
func TestMarkFailedIsCompareAndSwapAndNeverOverridesUploaded(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	sid := "cas-failed"

	seed(t, pool,
		seedRow{sid, 0, old(10), UploadStatePending, bytesOf(1000), false},
		seedRow{sid, 1, old(9), UploadStateUploaded, bytesOf(2000), false},
	)

	marked, err := st.MarkFailed(ctx, sid, 0, 1000)
	if err != nil {
		t.Fatalf("MarkFailed 실패: %v", err)
	}
	if !marked {
		t.Fatal("marked = false, want true")
	}
	if state, hasUploadedAt := stateOf(t, pool, sid, 0); state != UploadStateFailed || hasUploadedAt {
		t.Errorf("상태 = %q uploaded_at 유무 = %v, want failed / false", state, hasUploadedAt)
	}

	// failed → failed 재확정은 허용된다(upload_state <> 'uploaded' 를 통과한다).
	// 이것이 계약이다 — 재시도가 다시 실패해도 같은 결론을 다시 적을 수 있어야 한다.
	if marked, err := st.MarkFailed(ctx, sid, 0, 1000); err != nil || !marked {
		t.Errorf("failed 재확정 = (%v, %v), want (true, nil)", marked, err)
	}

	if marked, err := st.MarkFailed(ctx, sid, 1, 2000); err != nil || marked {
		t.Errorf("uploaded 행 MarkFailed = (%v, %v), want (false, nil)", marked, err)
	}
	if state, _ := stateOf(t, pool, sid, 1); state != UploadStateUploaded {
		t.Errorf("uploaded 행이 %q 로 바뀌었다", state)
	}
}

// 케이스3 — DB 오류는 CAS 거부로 뭉개지 않고 err 로 나온다.
// (false, err) 를 거부로 오분류하면 DB 오류가 조용히 삼켜진다(CX-2 ⑥).
func TestMarkReturnsErrorInsteadOfSilentRejection(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()

	seed(t, pool, seedRow{"err-first", 0, old(10), UploadStatePending, bytesOf(1000), false})
	pool.Close() // 이후 모든 질의가 실패한다

	for _, c := range []struct {
		name string
		call func() (bool, error)
	}{
		{"MarkUploaded", func() (bool, error) { return st.MarkUploaded(ctx, "err-first", 0, 1000) }},
		{"MarkFailed", func() (bool, error) { return st.MarkFailed(ctx, "err-first", 0, 1000) }},
	} {
		marked, err := c.call()
		if err == nil {
			t.Errorf("%s: err = nil, want 오류", c.name)
		}
		if marked {
			t.Errorf("%s: marked = true, want false", c.name)
		}
	}
}

// 케이스4 — 마킹은 ctx 취소 시 즉시 반환한다.
// 이 계약이 깨지면 Shutdown 6단계의 무조건 join 이 무기한 대기가 된다(결정 17″).
func TestMarkRespectsContextCancellation(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	seed(t, pool, seedRow{"ctx-cancel", 0, old(10), UploadStatePending, bytesOf(1000), false})

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	start := time.Now()
	marked, err := st.MarkUploaded(ctx, "ctx-cancel", 0, 1000)
	elapsed := time.Since(start)

	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled 계열", err)
	}
	if marked {
		t.Error("marked = true, want false")
	}
	if elapsed > time.Second {
		t.Errorf("반환까지 %v 걸렸다 — 즉시 반환 계약 위반", elapsed)
	}
	if state, _ := stateOf(t, pool, "ctx-cancel", 0); state != UploadStatePending {
		t.Errorf("취소된 호출이 상태를 %q 로 바꿨다", state)
	}
}

// 케이스5 — pending 이 failed 보다 항상 먼저 나온다(클래스 간 기아 방지).
// 시각은 failed 쪽이 훨씬 오래됐는데도 그렇다.
func TestPendingUploadsOrdersPendingBeforeFailed(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)

	seed(t, pool,
		seedRow{"s-failed", 0, old(600), UploadStateFailed, bytesOf(1000), false},
		seedRow{"s-pending", 0, old(10), UploadStatePending, bytesOf(1000), false},
	)

	rows, _, err := st.PendingUploads(context.Background(), 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}
	if len(rows) != 2 {
		t.Fatalf("행 수 = %d, want 2 (%+v)", len(rows), rows)
	}
	if rows[0].StreamID != "s-pending" || rows[1].StreamID != "s-failed" {
		t.Errorf("정렬 = %q, %q, want s-pending, s-failed", rows[0].StreamID, rows[1].StreamID)
	}
	if rows[0].S3Key == "" || rows[0].LocalPath == "" || rows[0].Bytes != 1000 {
		t.Errorf("행 내용이 비었다: %+v", rows[0])
	}
}

// 케이스6 — 꼬리 판정과 꼬리 예외.
// EXISTS(더 큰 seq) 가 IsTail 을 정하고, 꼬리는 tailGrace 를 넘겨야 조회된다.
func TestPendingUploadsTailDetectionAndGrace(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()

	seed(t, pool,
		// 후속이 있는 행(비꼬리) + 방금 만들어진 꼬리
		seedRow{"live", 0, old(10), UploadStatePending, bytesOf(1000), false},
		seedRow{"live", 1, time.Now().UTC(), UploadStatePending, bytesOf(1001), false},
		// 후속이 없는 오래된 꼬리 — 유예를 넘겼으므로 조회된다
		seedRow{"ended", 7, old(30), UploadStatePending, bytesOf(1002), false},
	)

	rows, _, err := st.PendingUploads(ctx, 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}
	got := map[string]UploadTarget{}
	for _, r := range rows {
		got[fmt.Sprintf("%s/%d", r.StreamID, r.Seq)] = r
	}
	if len(got) != 2 {
		t.Fatalf("행 수 = %d, want 2 — 유예 안쪽 꼬리 live/1 은 빠져야 한다 (%+v)", len(got), rows)
	}
	if r, ok := got["live/0"]; !ok || r.IsTail {
		t.Errorf("live/0 = %+v (있음=%v), want IsTail=false", r, ok)
	}
	if r, ok := got["ended/7"]; !ok || !r.IsTail {
		t.Errorf("ended/7 = %+v (있음=%v), want IsTail=true", r, ok)
	}
	if _, ok := got["live/1"]; ok {
		t.Error("유예 안쪽 꼬리 live/1 이 조회됐다 — 결정 4⁵ 의 보류가 무력화된다")
	}
}

// 케이스7 — 키셋 페이징이 누락도 중복도 내지 않는다.
// 커서는 "여기까지 검사를 마쳤다"이므로 다음 페이지는 그 다음 행부터다.
func TestPendingUploadsKeysetPagingHasNoGapOrDuplicate(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()

	const total = 5
	var rows []seedRow
	for i := 0; i < total; i++ {
		// 스트림을 섞어 두 번째·세 번째 정렬 키(stream_id, seq)도 실제로 쓰이게 한다.
		sid := fmt.Sprintf("page-%d", i%2)
		rows = append(rows, seedRow{sid, int64(i), old(100 - i), UploadStatePending, bytesOf(int64(1000 + i)), false})
	}
	// 마지막 행이 각 스트림의 꼬리라 유예에 걸리지 않도록 전부 100분 이상 과거다.
	seed(t, pool, rows...)

	seen := map[string]int{}
	cursor := SweepCursor{}
	pages := 0
	for {
		page, next, err := st.PendingUploads(ctx, 120, 2, cursor)
		if err != nil {
			t.Fatalf("PendingUploads 실패: %v", err)
		}
		pages++
		if pages > total+2 {
			t.Fatal("페이지가 끝나지 않는다 — 커서가 전진하지 않는다")
		}
		for _, r := range page {
			seen[fmt.Sprintf("%s/%d", r.StreamID, r.Seq)]++
		}
		if len(page) < 2 {
			break
		}
		cursor = next
	}

	if len(seen) != total {
		t.Errorf("본 행 수 = %d, want %d (%v)", len(seen), total, seen)
	}
	for key, n := range seen {
		if n != 1 {
			t.Errorf("%s 를 %d 번 봤다, want 1", key, n)
		}
	}
	if pages != 3 {
		t.Errorf("페이지 수 = %d, want 3 (5행 / limit 2)", pages)
	}
}

// 케이스8 — bytes 가 NULL 인 행은 조회 창에서 빠진다(결정 14).
// coalesce 로 펴면 꼬리 대조와 CAS 가 영원히 실패해 창만 낭비한다.
func TestPendingUploadsExcludesNullBytesAndNullPath(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)

	seed(t, pool,
		seedRow{"nb", 0, old(10), UploadStatePending, nil, false},
		seedRow{"np", 0, old(10), UploadStatePending, bytesOf(1000), true},
		seedRow{"ok", 0, old(10), UploadStatePending, bytesOf(1000), false},
	)

	rows, _, err := st.PendingUploads(context.Background(), 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}
	if len(rows) != 1 || rows[0].StreamID != "ok" {
		t.Fatalf("조회 결과 = %+v, want ok/0 하나", rows)
	}
}

// 케이스9 — CountBacklog 세 값의 의미.
// pending 과 failed 는 배타이고, bytesNull 은 그 둘의 부분집합이다(중복 계수).
func TestCountBacklogThreeValues(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)

	seed(t, pool,
		seedRow{"cb", 0, old(10), UploadStatePending, bytesOf(1000), false},
		seedRow{"cb", 1, old(10), UploadStatePending, nil, false},
		seedRow{"cb", 2, old(10), UploadStateFailed, nil, false},
		seedRow{"cb", 3, old(10), UploadStateUploaded, bytesOf(1000), false},
		// local_path 가 없는 행은 어느 값에도 세지 않는다 — 올릴 실물이 없다.
		seedRow{"cb", 4, old(10), UploadStatePending, bytesOf(1000), true},
	)

	pending, failed, bytesNull, err := st.CountBacklog(context.Background())
	if err != nil {
		t.Fatalf("CountBacklog 실패: %v", err)
	}
	if pending != 2 || failed != 1 || bytesNull != 2 {
		t.Errorf("(pending, failed, bytesNull) = (%d, %d, %d), want (2, 1, 2)", pending, failed, bytesNull)
	}
}

// 케이스10 — 스위퍼가 집어 오는 대상은 ② 아카이브 축이다(설계 5.5.4 #4).
//
// 잡는 결함: 축을 안 찍으면 영값(미판정)으로 나가고 업로더가 fail-closed 로 전부 거부한다 —
// 재개 경로가 통째로 죽는다. pendingUploadsSQL 은 ② 의 열(upload_state)만 보는 조회이므로
// 이 조회의 산출은 정의상 아카이브 축이다(축별 조회는 M4).
func TestPendingUploadsStampsArchiveAxis(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)

	seed(t, pool, seedRow{"axis", 0, old(10), UploadStatePending, bytesOf(1000), false})

	rows, _, err := st.PendingUploads(context.Background(), 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}
	if len(rows) != 1 {
		t.Fatalf("행 수 = %d, want 1", len(rows))
	}
	if rows[0].Axis != AxisArchive {
		t.Errorf("Axis = %v, want %v — 축이 없으면 업로더가 거부한다", rows[0].Axis, AxisArchive)
	}
	if rows[0].SessionID != "" {
		t.Errorf("SessionID = %q, want 빈 문자열 — ②·③ 의 대상 식별자는 seq 다", rows[0].SessionID)
	}
}

// initSession 은 init 3열이 채워진 세션 행을 직접 넣는다.
//
// **생산자 없이 CAS 를 재는 방법이다**(계획 단계 6 · t3 전례): 세 열(init_s3_key·init_sha256·
// init_bytes)의 유일한 생산자는 Producer.Init 이고 그것은 M4 다. 그래서 픽스처가 그 값을
// 직접 채워 CAS 의 전제(init_sha256 이 이미 있다)를 성립시킨다.
func initSession(t *testing.T, pool *pgxpool.Pool, sessionID, streamID string, s3Key string, sha []byte, bytes int64) {
	t.Helper()
	_, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions
    (session_id, stream_id, started_at, state, init_s3_key, init_sha256, init_bytes)
VALUES ($1, $2, now(), 'ended', $3, $4, $5)`,
		sessionID, streamID, s3Key, sha, bytes)
	if err != nil {
		t.Fatalf("세션 픽스처 삽입 실패 %s: %v", sessionID, err)
	}
}

// initUploadedAt 은 그 세션의 init 확정 시각이다(NULL 이면 false).
func initUploadedAt(t *testing.T, pool *pgxpool.Pool, sessionID string) (time.Time, bool) {
	t.Helper()
	var at *time.Time
	err := pool.QueryRow(context.Background(),
		`SELECT init_uploaded_at FROM stream_sessions WHERE session_id=$1`, sessionID).Scan(&at)
	if err != nil {
		t.Fatalf("init_uploaded_at 조회 실패: %v", err)
	}
	if at == nil {
		return time.Time{}, false
	}
	return *at, true
}

// T8 — init CAS 는 세 조건이 전부 맞을 때만 1행이다(설계 5.5.5 셋째 문장).
//
// 잡는 결함: 조건 하나라도 빠지면 **올라가지도 않은 init 이 확정**돼 발행 게이트
// (init_uploaded_at IS NULL → ready:false)가 열린다 — 매니페스트가 없는 MAP 을 가리킨다.
func TestMarkInitUploadedIsCompareAndSwap(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	sha := []byte("0123456789abcdef0123456789abcdef")

	initSession(t, pool, "S-cas", "casstream", "dvr/casstream/init/S-cas.mp4", sha, 720)

	t.Run("sha256_이_다르면_거부", func(t *testing.T) {
		marked, err := st.MarkInitUploaded(ctx, "S-cas", []byte("전혀 다른 바이트열입니다 ................"))
		if err != nil {
			t.Fatalf("MarkInitUploaded 실패: %v", err)
		}
		if marked {
			t.Fatal("marked = true, want false — 바이트 동등성이 CAS 의 앵커다")
		}
		if _, ok := initUploadedAt(t, pool, "S-cas"); ok {
			t.Error("init_uploaded_at 이 채워졌다 — 거부된 CAS 가 장부를 바꿨다")
		}
	})

	t.Run("없는_세션은_거부", func(t *testing.T) {
		marked, err := st.MarkInitUploaded(ctx, "S-없음", sha)
		if err != nil {
			t.Fatalf("MarkInitUploaded 실패: %v", err)
		}
		if marked {
			t.Fatal("marked = true, want false")
		}
	})

	t.Run("sha256_이_같으면_확정한다", func(t *testing.T) {
		marked, err := st.MarkInitUploaded(ctx, "S-cas", sha)
		if err != nil {
			t.Fatalf("MarkInitUploaded 실패: %v", err)
		}
		if !marked {
			t.Fatal("marked = false, want true")
		}
		if _, ok := initUploadedAt(t, pool, "S-cas"); !ok {
			t.Error("init_uploaded_at 이 비었다 — 확정이 기록되지 않았다")
		}
	})

	t.Run("이미_확정된_행은_다시_확정하지_않는다", func(t *testing.T) {
		before, _ := initUploadedAt(t, pool, "S-cas")
		marked, err := st.MarkInitUploaded(ctx, "S-cas", sha)
		if err != nil {
			t.Fatalf("MarkInitUploaded 실패: %v", err)
		}
		if marked {
			t.Fatal("marked = true, want false — IS NULL 가드가 중복 확정을 막는다")
		}
		after, _ := initUploadedAt(t, pool, "S-cas")
		if !after.Equal(before) {
			t.Errorf("init_uploaded_at 이 %v → %v 로 바뀌었다 — 확정 시각은 1회로 굳는다", before, after)
		}
	})
}

// T10 — 세션 2개의 init 은 서로 다른 키·다른 바이트이고, CAS 도 서로를 확정하지 않는다.
//
// 잡는 결함 둘: ⑴ 키가 세션 축이 아니면(f(stream_id) 파생) 두 세션이 같은 객체를 덮어써
// 옛 세션의 되감기가 새 MAP 으로 재생된다(ADR-044 금지 사항) ⑵ CAS 가 **행 자신의
// sha256 을 읽어 되넘기면** 항진식이 되어 5.3ⓑ(바이트 동등성) 보증이 통째로 사라진다 —
// 남의 해시로 부른 CAS 가 성공하면 그 구현이다.
func TestTwoSessionsHaveIndependentInitKeysAndCAS(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()

	const stream = "twosess"
	shaA := []byte("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
	shaB := []byte("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")

	keyA, err := playback.InitKey(stream, "S-twosess-1")
	if err != nil {
		t.Fatalf("InitKey 실패: %v", err)
	}
	keyB, err := playback.InitKey(stream, "S-twosess-2")
	if err != nil {
		t.Fatalf("InitKey 실패: %v", err)
	}
	if keyA == keyB {
		t.Fatalf("두 세션의 init 키가 같다 (%q) — 키는 세션 축이다", keyA)
	}
	if keyA != "dvr/twosess/init/S-twosess-1.mp4" {
		t.Errorf("키 = %q, want dvr/twosess/init/S-twosess-1.mp4 (설계 5.2 형상)", keyA)
	}

	initSession(t, pool, "S-twosess-1", stream, keyA, shaA, 700)
	initSession(t, pool, "S-twosess-2", stream, keyB, shaB, 701)

	// 자기 행 hash 재사용 금지 — 남의 해시로 부른 CAS 는 거부돼야 한다.
	for _, c := range []struct{ session, other string }{
		{"S-twosess-1", "S-twosess-2"},
		{"S-twosess-2", "S-twosess-1"},
	} {
		wrong := shaB
		if c.session == "S-twosess-2" {
			wrong = shaA
		}
		marked, err := st.MarkInitUploaded(ctx, c.session, wrong)
		if err != nil {
			t.Fatalf("MarkInitUploaded 실패: %v", err)
		}
		if marked {
			t.Fatalf("%s 를 %s 의 해시로 확정했다 — CAS 가 행 자신의 값을 되읽고 있다", c.session, c.other)
		}
	}

	// 제 해시로는 둘 다 독립으로 확정된다.
	for session, sha := range map[string][]byte{"S-twosess-1": shaA, "S-twosess-2": shaB} {
		marked, err := st.MarkInitUploaded(ctx, session, sha)
		if err != nil {
			t.Fatalf("MarkInitUploaded 실패: %v", err)
		}
		if !marked {
			t.Fatalf("%s 확정 실패 — 제 해시로는 통과해야 한다", session)
		}
		if _, ok := initUploadedAt(t, pool, session); !ok {
			t.Errorf("%s 의 init_uploaded_at 이 비었다", session)
		}
	}
}

// SweepCursor.IsZero 는 "처음부터"의 판정이다. 순환과 1단계 결과 재사용이 여기에 달렸다.
func TestSweepCursorIsZero(t *testing.T) {
	if !(SweepCursor{}).IsZero() {
		t.Error("영값 커서가 IsZero=false")
	}
	for name, c := range map[string]SweepCursor{
		"IsFailed":  {IsFailed: true},
		"StartWall": {StartWall: time.Unix(0, 1).UTC()},
		"StreamID":  {StreamID: "s"},
		"Seq":       {Seq: 1},
	} {
		if c.IsZero() {
			t.Errorf("%s 가 채워졌는데 IsZero=true", name)
		}
	}
}
