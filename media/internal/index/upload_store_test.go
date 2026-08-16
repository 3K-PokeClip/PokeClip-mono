package index

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
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
