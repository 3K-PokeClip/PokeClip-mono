package index

import (
	"context"
	"testing"
)

// EnsureSchema 는 기동마다 실행된다 — 멱등이 아니면 "두 번째 기동"이 죽는다.
// 이 결함은 1회 실행 테스트로는 절대 안 잡히므로(첫 실행은 항상 성공한다) 반복 실행을 계약으로 고정한다.
// newTestPool 부트스트랩이 이미 1회 실행했으므로, 여기서 두 번 더 = 총 3회다.
func TestEnsureSchemaIsIdempotent(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()

	for i := 0; i < 2; i++ {
		if err := EnsureSchema(ctx, pool); err != nil {
			t.Fatalf("EnsureSchema 재실행 %d회차 실패 — 멱등이 깨졌다(기동마다 실행되는 함수다): %v", i+2, err)
		}
	}
}

// CHECK 2종이 실물에 있어야 한다 (upload_state = 계약 5-3, playback_upload_state = 5-5).
// conname 만 세면 다른 표의 동명 제약에 속을 수 있어 conrelid·contype 까지 함께 본다 —
// schemaDDL 의 DO 블록이 같은 조건으로 존재를 검사하므로, 이 테스트는 그 검사의 거울이다.
func TestEnsureSchemaCreatesBothChecks(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()

	// 구 스키마를 명시적으로 만든다 — 부트스트랩이 이미 제약을 만들어 두므로, 이 선행 DROP 이
	// 없으면 ddl.go 에서 DO 블록을 제거해도 잔존 제약 때문에 테스트가 녹색이 된다(false-green).
	if _, err := pool.Exec(ctx, `
		ALTER TABLE stream_segments
			DROP CONSTRAINT IF EXISTS stream_segments_upload_state_chk,
			DROP CONSTRAINT IF EXISTS stream_segments_playback_upload_state_chk`); err != nil {
		t.Fatalf("선행 DROP CONSTRAINT 실패: %v", err)
	}
	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("제약 제거 상태에서 EnsureSchema 실패: %v", err)
	}

	var n int
	err := pool.QueryRow(ctx, `
		SELECT count(*) FROM pg_constraint
		WHERE conrelid = 'stream_segments'::regclass
		  AND contype  = 'c'
		  AND conname  = ANY($1)`,
		[]string{"stream_segments_upload_state_chk", "stream_segments_playback_upload_state_chk"},
	).Scan(&n)
	if err != nil {
		t.Fatalf("pg_constraint 조회 실패: %v", err)
	}
	if n != 2 {
		t.Fatalf("CHECK 제약이 %d개다 — 2개(upload_state·playback_upload_state)여야 한다. "+
			"DO 블록이 건너뛰었거나(카탈로그 검사 결함) ALTER 가 실패했다", n)
	}
}

// playback_* 4컬럼이 "기존 표"에도 붙어야 한다 — CREATE TABLE IF NOT EXISTS 는 기존 표를
// 건드리지 않으므로, 이 테스트가 실패하면 ALTER … ADD COLUMN 경로가 빠진 것이다.
func TestEnsureSchemaAddsPlaybackColumns(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()

	// "기존 표" 상태를 명시적으로 만든다 — 신규 DB 는 CREATE TABLE 이 4컬럼을 이미 갖고 태어나므로,
	// 이 선행 DROP 이 없으면 ddl.go 의 ALTER … ADD COLUMN 경로가 통째로 빠져도 테스트가 녹색이다.
	// playback_upload_state 를 지우면 그 컬럼만 참조하는 CHECK 도 함께 떨어진다(PostgreSQL 의존성 규칙)
	// — EnsureSchema 가 컬럼과 제약을 모두 되살려야 한다.
	if _, err := pool.Exec(ctx, `
		ALTER TABLE stream_segments
			DROP COLUMN IF EXISTS playback_s3_key,
			DROP COLUMN IF EXISTS playback_upload_state,
			DROP COLUMN IF EXISTS playback_uploaded_at,
			DROP COLUMN IF EXISTS playback_bytes`); err != nil {
		t.Fatalf("선행 DROP COLUMN 실패: %v", err)
	}
	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("구 스키마(컬럼 부재) 상태에서 EnsureSchema 실패 — ALTER 전파 경로가 깨졌다: %v", err)
	}

	var n int
	err := pool.QueryRow(ctx, `
		SELECT count(*) FROM information_schema.columns
		WHERE table_name = 'stream_segments'
		  AND column_name = ANY($1)`,
		[]string{"playback_s3_key", "playback_upload_state", "playback_uploaded_at", "playback_bytes"},
	).Scan(&n)
	if err != nil {
		t.Fatalf("information_schema 조회 실패: %v", err)
	}
	if n != 4 {
		t.Fatalf("playback_* 컬럼이 %d개다 — 4개여야 한다(계약-세그먼트인덱스 5-5절)", n)
	}
}
