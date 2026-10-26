package index

// M6 장부 스키마 · seq 하한 시험(POK-196 M6-0 c1).
//
// 하한은 둘이 함께 지킨다(불변식 4 — seq 재사용 금지). stream_segments 의 AFTER DELETE 문장 트리거가 지워진 행의
// 스트림별 max(seq)+1 을 stream_seq_floors 에 올리고(어느 계정이 지우든 — 정의자 권한), BEFORE INSERT 행 트리거가
// 하한 아래 seq 의 INSERT 를 23514 seq_below_floor 로 거부한다(하한을 모르는 옛 판 사이드카의 fail-closed).

import (
	"context"
	"errors"
	"slices"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// ledgerSchemaObjectsSQL 은 M6 장부 스키마 객체의 카탈로그 그림이다(종류:이름:oid). 두 번째 EnsureSchema 가 아무것도
// 다시 만들지 않았는지(no-op)를 oid 까지 같은가로 본다.
const ledgerSchemaObjectsSQL = `
SELECT kind || ':' || name || ':' || oid::text FROM (
    SELECT 'column' AS kind, a.attname::text AS name, a.attrelid AS oid
      FROM pg_attribute a
     WHERE a.attrelid = 'stream_sessions'::regclass::oid AND NOT a.attisdropped
       AND a.attname::text = ANY (ARRAY['round_id', 'round_closed_at', 'expired_at', 'stop_marked_at'])
    UNION ALL
    SELECT 'table', c.relname::text, c.oid FROM pg_class c
     WHERE c.relname::text = ANY (ARRAY['stream_seq_floors', 'broadcast_lifecycle_outbox', 'stream_sessions_round_open_idx',
                                        'broadcast_lifecycle_outbox_due_idx'])
    UNION ALL
    SELECT 'trigger', t.tgname::text, t.oid FROM pg_trigger t
     WHERE t.tgrelid = 'stream_segments'::regclass::oid
       AND t.tgname::text = ANY (ARRAY['stream_segments_seq_floor_raise', 'stream_segments_seq_floor_guard'])
) o ORDER BY 1`

// ledgerSchemaObjectCount 는 위 그림의 객체 수다 — 열 넷 · 표 둘 · 인덱스 둘 · 트리거 둘.
const ledgerSchemaObjectCount = 10

// dropLedgerSchemaSQL 은 M6-0 앞 판(develop) 스키마로 되돌린다 — 그 판의 DB 에는 이 객체가 하나도 없다.
const dropLedgerSchemaSQL = `
DROP TRIGGER IF EXISTS stream_segments_seq_floor_raise ON stream_segments;
DROP TRIGGER IF EXISTS stream_segments_seq_floor_guard ON stream_segments;
DROP FUNCTION IF EXISTS stream_segments_raise_seq_floor();
DROP FUNCTION IF EXISTS stream_segments_check_seq_floor();
DROP TABLE IF EXISTS stream_seq_floors, broadcast_lifecycle_outbox;
DROP INDEX IF EXISTS stream_sessions_round_open_idx;
ALTER TABLE stream_sessions
    DROP COLUMN IF EXISTS round_id, DROP COLUMN IF EXISTS round_closed_at,
    DROP COLUMN IF EXISTS expired_at, DROP COLUMN IF EXISTS stop_marked_at`

func ledgerSchemaObjects(t *testing.T, pool *pgxpool.Pool) []string {
	t.Helper()
	rows, err := pool.Query(context.Background(), ledgerSchemaObjectsSQL)
	if err != nil {
		t.Fatalf("카탈로그 조회 실패: %v", err)
	}
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			t.Fatalf("카탈로그 행 읽기 실패: %v", err)
		}
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("카탈로그 조회 실패: %v", err)
	}
	return out
}

// 옛 볼륨(로컬 ENSURE_SCHEMA=true)에도 M6 객체가 붙어야 한다 — CREATE TABLE IF NOT EXISTS 는 있는 표에 열을 붙이지
// 않으므로, 이 시험이 실패하면 카탈로그 확인 DO 블록 전파가 빠진 것이다. 두 번째 실행은 오류 없이 아무것도 다시
// 만들지 않는다(ALTER 를 늘 실행하면 여기서 42701 로 깨진다).
func TestEnsureSchemaPropagatesLedgerSchemaToOldDB(t *testing.T) {
	pool := pgtest.Pool(t, "ledgerschema", EnsureSchema, resetTables)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, dropLedgerSchemaSQL); err != nil {
		t.Fatalf("앞 판 스키마 만들기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if err := EnsureSchema(context.Background(), pool); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})

	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("앞 판 DB 의 EnsureSchema 1회차 오류 = %v, want nil", err)
	}
	first := ledgerSchemaObjects(t, pool)
	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("EnsureSchema 2회차 오류 = %v, want nil", err)
	}
	second := ledgerSchemaObjects(t, pool)

	if len(first) != ledgerSchemaObjectCount {
		t.Fatalf("1회차 뒤 M6 객체 = %q (%d개), want %d개", first, len(first), ledgerSchemaObjectCount)
	}
	if !slices.Equal(first, second) {
		t.Errorf("2회차가 객체를 바꿨다:\n1회차 %q\n2회차 %q", first, second)
	}
}

// floorOf 는 stream 의 하한(next_seq)이다. 행이 없으면 -1.
func floorOf(t *testing.T, pool *pgxpool.Pool, stream string) int64 {
	t.Helper()
	var next int64 = -1
	err := pool.QueryRow(context.Background(),
		`SELECT coalesce((SELECT next_seq FROM stream_seq_floors WHERE stream_id = $1), -1)`, stream).Scan(&next)
	if err != nil {
		t.Fatalf("하한 조회 실패: %v", err)
	}
	return next
}

// clipRolePool 은 3번 목록의 app_clip 표 권한(appDBUserGrants 의 stream_* 줄)만 가진 시험 역할의 풀이다. 하한 표에는
// 아무 권한이 없다 — 하한 갱신이 트리거의 정의자 권한으로 도는지를 이 역할로 잰다.
func clipRolePool(t *testing.T, admin *pgxpool.Pool) *pgxpool.Pool {
	t.Helper()
	ctx := context.Background()
	role := pgtest.NewLoginRole(t, admin)
	t.Cleanup(func() { // NewLoginRole 의 DROP 보다 먼저 돈다 — 권한이 남으면 DROP ROLE 이 실패한다
		if _, err := admin.Exec(context.Background(), "REVOKE ALL ON stream_segments, stream_sessions FROM "+role.Name); err != nil {
			t.Errorf("시험 역할 권한 회수 실패: %v", err)
		}
	})
	for _, g := range appDBUserGrants["app_clip"] {
		if !strings.Contains(g, " ON stream_") {
			continue
		}
		s := strings.TrimSuffix(g, "app_clip") + role.Name
		if _, err := admin.Exec(ctx, s); err != nil {
			t.Fatalf("시험 역할 권한 부여 실패(%s): %v", s, err)
		}
	}
	cfg := admin.Config()
	cfg.ConnConfig.User, cfg.ConnConfig.Password = role.Name, role.Password
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("시험 역할 풀 생성 실패: %v", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

// D13 app_clip 경로 — clip 탈퇴 정리(app_clip 의 DELETE)가 스트림 꼬리까지 지워도 하한이 지워진 max(seq)+1 로
// 오르고, 앞쪽 행을 나중에 지워도 내려가지 않는다(GREATEST). app_clip 은 하한 표 권한이 없다.
func TestClipPurgeRaisesSeqFloor(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	const s = "floor-clip"
	for seq := range int64(5) {
		lcPut(t, admin, lcRow{stream: s, seq: seq})
	}
	clip := clipRolePool(t, admin)

	if n, err := lcClipPurge(ctx, clip, s, 2); err != nil || n != 1 {
		t.Fatalf("seq 2 정리 = (%d, %v), want (1, nil)", n, err)
	}
	afterMid := floorOf(t, admin, s)
	if n, err := lcClipPurge(ctx, clip, s, 4, 3); err != nil || n != 2 {
		t.Fatalf("꼬리 정리 = (%d, %v), want (2, nil)", n, err)
	}
	afterTail := floorOf(t, admin, s)
	if n, err := lcClipPurge(ctx, clip, s, 0, 1); err != nil || n != 2 {
		t.Fatalf("앞쪽 정리 = (%d, %v), want (2, nil)", n, err)
	}
	afterAll := floorOf(t, admin, s)

	if afterMid != 3 || afterTail != 5 || afterAll != 5 {
		t.Errorf("하한 (가운데 · 꼬리 · 전부 정리 뒤) = (%d, %d, %d), want (3, 5, 5)", afterMid, afterTail, afterAll)
	}
	if _, err := clip.Exec(ctx, "SELECT count(*) FROM stream_seq_floors"); !pgtest.IsInsufficientPrivilege(err) {
		t.Errorf("app_clip 의 하한 표 읽기 오류 = %v, want 42501 — 하한 갱신은 정의자 권한이어야 한다", err)
	}
}

// segBelowFloorInsertSQL 은 하한 검사만 보는 최소 조각 INSERT 다.
const segBelowFloorInsertSQL = `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key)
VALUES ($1, $2::bigint, 0, pg_catalog.now(), 4000, 'k/' || $2::bigint::text)`

// D68 — 하한 10 · 행 0 인 스트림(만료가 마지막 행까지 지움)에 하한을 모르는 옛 판이 seq 0 을 넣으면 23514
// seq_below_floor 로 거부되고, 하한 이상의 seq 는 들어간다. 사이드카 계정(media_indexer)으로 돈다.
func TestSeqFloorGuardRejectsInsertBelowFloor(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	const s = "floor-guard"
	if _, err := admin.Exec(ctx, `INSERT INTO stream_seq_floors (stream_id, next_seq) VALUES ($1, 10)`, s); err != nil {
		t.Fatalf("하한 고정물 실패: %v", err)
	}
	role := pgtest.NewMediaIndexerRole(t, admin)

	_, below := role.Pool.Exec(ctx, segBelowFloorInsertSQL, s, int64(0))
	_, at := role.Pool.Exec(ctx, segBelowFloorInsertSQL, s, int64(10))

	var pgErr *pgconn.PgError
	if !errors.As(below, &pgErr) || pgErr.Code != "23514" || pgErr.Message != "seq_below_floor" {
		t.Errorf("하한 아래 INSERT 오류 = %v, want 23514 seq_below_floor", below)
	}
	if at != nil {
		t.Errorf("하한 seq INSERT 오류 = %v, want nil", at)
	}
}

// rollbackFloorBlockedSQL 은 롤백 하한 확인 질의다(설계 10절 — 하한 아래 판이 배포되면 색인이 멈출 스트림). 운영
// 런북(media-swap.md 롤백 하한 절)은 이 문장을 그대로 싣는다.
const rollbackFloorBlockedSQL = `
SELECT f.stream_id, f.next_seq, m.max_seq
  FROM stream_seq_floors f
  LEFT JOIN LATERAL (SELECT pg_catalog.max(seq) AS max_seq FROM stream_segments s WHERE s.stream_id = f.stream_id) m ON true
 WHERE m.max_seq IS NULL OR m.max_seq + 1 < f.next_seq`

// 롤백 하한 확인 질의는 하한이 생존 꼬리보다 앞선 스트림만 고른다 — A(하한 10 · 최대 9)는 옛 판도 10 부터 쓰므로
// 걸리지 않고, B(10 · 최대 5) · C(10 · 행 없음)는 옛 판이 6 · 0 을 써서 거부된다.
func TestRollbackFloorQueryFindsBlockedStreams(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	for seq := range int64(10) {
		lcPut(t, pool, lcRow{stream: "floor-a", seq: seq})
	}
	for seq := range int64(6) {
		lcPut(t, pool, lcRow{stream: "floor-b", seq: seq})
	}
	if _, err := pool.Exec(ctx, `INSERT INTO stream_seq_floors (stream_id, next_seq)
		VALUES ('floor-a', 10), ('floor-b', 10), ('floor-c', 10)`); err != nil {
		t.Fatalf("하한 고정물 실패: %v", err)
	}

	rows, err := pool.Query(ctx, rollbackFloorBlockedSQL+" ORDER BY 1")
	if err != nil {
		t.Fatalf("확인 질의 실패: %v", err)
	}
	var got []string
	for rows.Next() {
		var stream string
		var next int64
		var maxSeq *int64
		if err := rows.Scan(&stream, &next, &maxSeq); err != nil {
			t.Fatalf("행 읽기 실패: %v", err)
		}
		got = append(got, stream)
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("확인 질의 실패: %v", err)
	}

	if want := []string{"floor-b", "floor-c"}; !slices.Equal(got, want) {
		t.Errorf("걸리는 스트림 = %q, want %q", got, want)
	}
}

// D68 LoadCursor 몫 — 다음 seq 는 하한과 max(seq)+1 중 큰 쪽이다. 만료가 마지막 행까지 지운 스트림(행 0 · 하한
// 10)은 꼬리 없이 10 부터, 하한이 생존 꼬리보다 앞서면 하한부터, 뒤서면 꼬리 다음부터 쓴다. 사이드카 계정
// (media_indexer — 하한 표 SELECT)으로 돈다.
func TestLoadCursorStartsAtSeqFloor(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	for seq := range int64(3) {
		lcPut(t, admin, lcRow{stream: "cursor-ahead", seq: seq})
		lcPut(t, admin, lcRow{stream: "cursor-behind", seq: seq + 5})
	}
	if _, err := admin.Exec(ctx, `INSERT INTO stream_seq_floors (stream_id, next_seq)
		VALUES ('cursor-empty', 10), ('cursor-ahead', 10), ('cursor-behind', 2)`); err != nil {
		t.Fatalf("하한 고정물 실패: %v", err)
	}
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	store := newSessionStore(role.Pool)

	tests := []struct {
		stream   string
		wantNext int64
		wantTail int64 // -1 = 꼬리 없음
	}{
		{stream: "cursor-empty", wantNext: 10, wantTail: -1},
		{stream: "cursor-ahead", wantNext: 10, wantTail: 2},
		{stream: "cursor-behind", wantNext: 8, wantTail: 7},
		{stream: "cursor-none", wantNext: 0, wantTail: -1},
	}
	for _, tt := range tests {
		cur, err := store.LoadCursor(ctx, tt.stream)
		if err != nil {
			t.Errorf("%s LoadCursor 오류 = %v, want nil", tt.stream, err)
			continue
		}
		tail := int64(-1)
		if cur.Tail != nil {
			tail = cur.Tail.Seq
		}
		if cur.NextSeq != tt.wantNext || tail != tt.wantTail {
			t.Errorf("%s LoadCursor = (다음 %d · 꼬리 %d), want (%d · %d)", tt.stream, cur.NextSeq, tail, tt.wantNext, tt.wantTail)
		}
	}
}
