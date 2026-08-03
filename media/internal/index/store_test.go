package index

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// 통합 테스트 8케이스(계획 4단계). PG_DSN 이 없으면 전부 skip 된다 —
// DB 없는 환경에서도 나머지 테스트가 돌아야 하기 때문이다.
//
// DSN 예:
//
//	set -a; . ./.env; set +a
//	export PG_DSN="postgres://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
//
// TestMain 은 릴리스 게이트용 스위치다.
//
// 이 패키지의 SQL 통합 케이스는 PG_DSN 이 없으면 전부 skip 되는데, skip 은 go test 에서
// 성공으로 집계된다 — DB 를 안 띄운 실행이 "녹색"으로 보이고 SQL 은 한 줄도 검증되지 않는다.
// REQUIRE_PG=1 이면 그 상황을 실패로 바꾼다. 기본값(미설정)은 종전대로 skip 이라
// DB 없는 개발 머신에서도 나머지 테스트가 그대로 돈다.
func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr,
			"REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — SQL 통합 케이스가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

func newTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()

	dsn := os.Getenv("PG_DSN")
	if dsn == "" {
		t.Skip("PG_DSN 미설정 — DB 통합 테스트를 건너뛴다")
	}

	ctx := context.Background()
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatalf("pgxpool 생성 실패: %v", err)
	}
	t.Cleanup(pool.Close)

	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("EnsureSchema 실패: %v", err)
	}
	return pool
}

// 실행마다 고유한 stream_id 를 쓴다 — 잔존 데이터가 결과를 오염시키지 않게 한다.
func uniqueStreamID(t *testing.T) string {
	t.Helper()
	return fmt.Sprintf("itest-%d-%s", time.Now().UnixNano(), t.Name())
}

func sampleRecord(streamID string, seq int64, path string) Record {
	return Record{
		StreamID:     streamID,
		Seq:          seq,
		StartPTSMS:   seq * 4000,
		StartWallUTC: time.Date(2026, 7, 25, 10, 0, 0, 0, time.UTC).Add(time.Duration(seq) * 4 * time.Second),
		DurationMS:   4000,
		S3Key:        S3Key(streamID, seq, time.Date(2026, 7, 25, 10, 0, 0, 0, time.UTC)),
		LocalPath:    path,
		UploadState:  UploadStatePending,
		Bytes:        1000 + seq,
	}
}

// 케이스1 — 같은 local_path 2회 INSERT 시 2번째가 InsertDuplicatePath.
// 인메모리 중복 방지는 크래시를 관통하지 못한다. DB UNIQUE 만이 재기동을 관통한다(G3, D5).
func TestInsertSameLocalPathTwiceIsDuplicate(t *testing.T) {
	ctx := context.Background()
	store := NewPGStore(newTestPool(t))
	streamID := uniqueStreamID(t)

	first, err := store.Insert(ctx, sampleRecord(streamID, 0, "/recordings/a/1.mp4"))
	if err != nil {
		t.Fatalf("1회차 Insert 실패: %v", err)
	}
	if first != InsertInserted {
		t.Fatalf("1회차 outcome = %v, want InsertInserted", first)
	}

	// seq 만 다르고 local_path 는 같다 → UNIQUE(stream_id, local_path) 위반
	second, err := store.Insert(ctx, sampleRecord(streamID, 1, "/recordings/a/1.mp4"))
	if err != nil {
		t.Fatalf("2회차 Insert 실패: %v", err)
	}
	if second != InsertDuplicatePath {
		t.Fatalf("2회차 outcome = %v, want InsertDuplicatePath", second)
	}
}

// 케이스6 — 같은 (stream_id, seq) + 다른 local_path 는 InsertSeqConflict 로 분류된다.
// PK 위반과 UNIQUE 위반을 서로 다른 outcome 으로 갈라내는 것이 D5 의 핵심이다.
func TestInsertSameSeqDifferentPathIsSeqConflict(t *testing.T) {
	ctx := context.Background()
	store := NewPGStore(newTestPool(t))
	streamID := uniqueStreamID(t)

	if _, err := store.Insert(ctx, sampleRecord(streamID, 0, "/recordings/a/1.mp4")); err != nil {
		t.Fatalf("1회차 Insert 실패: %v", err)
	}

	got, err := store.Insert(ctx, sampleRecord(streamID, 0, "/recordings/a/2.mp4"))
	if err != nil {
		t.Fatalf("2회차 Insert 실패: %v", err)
	}
	if got != InsertSeqConflict {
		t.Fatalf("outcome = %v, want InsertSeqConflict", got)
	}
}

// 케이스2 — UpdateTail 은 꼬리이고 pending 일 때 성공한다.
func TestUpdateTailSucceedsOnPendingTail(t *testing.T) {
	ctx := context.Background()
	store := NewPGStore(newTestPool(t))
	streamID := uniqueStreamID(t)

	if _, err := store.Insert(ctx, sampleRecord(streamID, 0, "/recordings/a/1.mp4")); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	ok, err := store.UpdateTail(ctx, streamID, 0, 6000, 9999)
	if err != nil {
		t.Fatalf("UpdateTail 실패: %v", err)
	}
	if !ok {
		t.Fatal("UpdateTail = false, want true")
	}

	cur, err := store.LoadCursor(ctx, streamID)
	if err != nil {
		t.Fatalf("LoadCursor 실패: %v", err)
	}
	if cur.Tail.DurationMS != 6000 || cur.Tail.Bytes != 9999 {
		t.Fatalf("꼬리 = duration %d bytes %d, want 6000 / 9999", cur.Tail.DurationMS, cur.Tail.Bytes)
	}
}

// 케이스3 — 후속 행이 있으면 UpdateTail 이 거부된다.
// 꼬리가 아닌 행을 고치면 뒤 행들의 PTS 가 전부 어긋난다.
func TestUpdateTailRejectedWhenNewerRowExists(t *testing.T) {
	ctx := context.Background()
	store := NewPGStore(newTestPool(t))
	streamID := uniqueStreamID(t)

	for seq, p := range []string{"/recordings/a/1.mp4", "/recordings/a/2.mp4"} {
		if _, err := store.Insert(ctx, sampleRecord(streamID, int64(seq), p)); err != nil {
			t.Fatalf("Insert 실패: %v", err)
		}
	}

	ok, err := store.UpdateTail(ctx, streamID, 0, 6000, 9999)
	if err != nil {
		t.Fatalf("UpdateTail 실패: %v", err)
	}
	if ok {
		t.Fatal("UpdateTail = true, want false — seq 0 은 더 이상 꼬리가 아니다")
	}
}

// 케이스4 — 이미 uploaded 인 행은 UpdateTail 이 거부한다.
// 이미 올린 파일의 메타를 고치면 실물과 기록이 불일치한다.
func TestUpdateTailRejectedWhenUploaded(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	store := NewPGStore(pool)
	streamID := uniqueStreamID(t)

	if _, err := store.Insert(ctx, sampleRecord(streamID, 0, "/recordings/a/1.mp4")); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}
	// 이번 범위에 uploaded 로 바꾸는 코드는 없다(G6). POK-30 상황을 테스트가 직접 만든다.
	if _, err := pool.Exec(ctx,
		`UPDATE stream_segments SET upload_state = 'uploaded' WHERE stream_id = $1 AND seq = 0`,
		streamID); err != nil {
		t.Fatalf("업로드 상태 준비 실패: %v", err)
	}

	ok, err := store.UpdateTail(ctx, streamID, 0, 6000, 9999)
	if err != nil {
		t.Fatalf("UpdateTail 실패: %v", err)
	}
	if ok {
		t.Fatal("UpdateTail = true, want false — uploaded 행은 고칠 수 없다")
	}
}

// 케이스5 — LoadCursor 가 bytes·local_path·upload_state·s3_key 를 실어온다.
// 앞 셋이 없으면 Scan(a) 의 꼬리 재성장 복구가 판정을 내릴 수 없고,
// s3_key 가 없으면 POK-30 의 꼬리 업로드 요청이 빈 키로 나간다.
//
// s3_key 와 local_path 를 한 테스트에서 둘 다 단언하는 것이 핵심이다 —
// 둘 다 text 컬럼이라 Scan 인자 순서를 바꿔도 컴파일과 실행이 통과한다.
// 값 대조만이 그 사고를 잡는다.
func TestLoadCursorCarriesTailFields(t *testing.T) {
	ctx := context.Background()
	store := NewPGStore(newTestPool(t))
	streamID := uniqueStreamID(t)

	want := sampleRecord(streamID, 5, "/recordings/a/6.mp4")
	if _, err := store.Insert(ctx, want); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	cur, err := store.LoadCursor(ctx, streamID)
	if err != nil {
		t.Fatalf("LoadCursor 실패: %v", err)
	}
	if cur.Tail == nil {
		t.Fatal("Tail = nil, want 값")
	}
	if cur.NextSeq != 6 {
		t.Errorf("NextSeq = %d, want 6 (= MAX(seq)+1)", cur.NextSeq)
	}
	if cur.Tail.Bytes != want.Bytes {
		t.Errorf("Bytes = %d, want %d", cur.Tail.Bytes, want.Bytes)
	}
	if cur.Tail.LocalPath != want.LocalPath {
		t.Errorf("LocalPath = %q, want %q", cur.Tail.LocalPath, want.LocalPath)
	}
	if cur.Tail.S3Key != want.S3Key {
		t.Errorf("S3Key = %q, want %q", cur.Tail.S3Key, want.S3Key)
	}
	if cur.Tail.UploadState != UploadStatePending {
		t.Errorf("UploadState = %q, want pending", cur.Tail.UploadState)
	}
	if !cur.Tail.StartWallUTC.Equal(want.StartWallUTC) {
		t.Errorf("StartWallUTC = %v, want %v", cur.Tail.StartWallUTC, want.StartWallUTC)
	}
	if cur.Tail.StartPTSMS != want.StartPTSMS || cur.Tail.DurationMS != want.DurationMS {
		t.Errorf("PTS/duration = %d/%d, want %d/%d",
			cur.Tail.StartPTSMS, cur.Tail.DurationMS, want.StartPTSMS, want.DurationMS)
	}
}

// 케이스8 — 행이 없는 스트림은 Tail == nil, NextSeq == 0.
// 이것이 "첫 세그먼트다"의 판정 근거다(H8).
func TestLoadCursorEmptyStream(t *testing.T) {
	ctx := context.Background()
	store := NewPGStore(newTestPool(t))

	cur, err := store.LoadCursor(ctx, uniqueStreamID(t))
	if err != nil {
		t.Fatalf("LoadCursor 실패: %v", err)
	}
	if cur.Tail != nil {
		t.Fatalf("Tail = %+v, want nil", cur.Tail)
	}
	if cur.NextSeq != 0 {
		t.Fatalf("NextSeq = %d, want 0", cur.NextSeq)
	}
}

// 케이스7 — ExistingPaths 는 해당 스트림의 경로 전부를 반환하고 다른 스트림 것은 섞지 않는다.
func TestExistingPathsIsScopedToStream(t *testing.T) {
	ctx := context.Background()
	store := NewPGStore(newTestPool(t))
	mine := uniqueStreamID(t) + "-mine"
	other := uniqueStreamID(t) + "-other"

	for seq, p := range []string{"/recordings/mine/1.mp4", "/recordings/mine/2.mp4"} {
		if _, err := store.Insert(ctx, sampleRecord(mine, int64(seq), p)); err != nil {
			t.Fatalf("Insert 실패: %v", err)
		}
	}
	if _, err := store.Insert(ctx, sampleRecord(other, 0, "/recordings/other/1.mp4")); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	got, err := store.ExistingPaths(ctx, mine)
	if err != nil {
		t.Fatalf("ExistingPaths 실패: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("경로 수 = %d, want 2 (got=%v)", len(got), got)
	}
	for _, want := range []string{"/recordings/mine/1.mp4", "/recordings/mine/2.mp4"} {
		if _, ok := got[want]; !ok {
			t.Errorf("%q 가 빠졌다", want)
		}
	}
	if _, ok := got["/recordings/other/1.mp4"]; ok {
		t.Error("다른 스트림의 경로가 섞였다")
	}
}
