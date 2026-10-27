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
	"time"

	"github.com/jackc/pgx/v5"
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
                                        'broadcast_lifecycle_outbox_due_idx', 'stream_segments_session_id_idx',
                                        'stream_published_gaps_session_idx', 'stream_sessions_inherits_idx',
                                        'stream_segments_uploaded_session_idx'])
    UNION ALL
    SELECT 'trigger', t.tgname::text, t.oid FROM pg_trigger t
     WHERE t.tgrelid = 'stream_segments'::regclass::oid
       AND t.tgname::text = ANY (ARRAY['stream_segments_seq_floor_raise', 'stream_segments_seq_floor_guard'])
) o ORDER BY 1`

// ledgerSchemaObjectCount 는 위 그림의 객체 수다 — 열 넷 · 표 둘 · 인덱스 여섯 · 트리거 둘.
const ledgerSchemaObjectCount = 14

// dropLedgerSchemaSQL 은 M6-0 앞 판(develop) 스키마로 되돌린다 — 그 판의 DB 에는 이 객체가 하나도 없다.
const dropLedgerSchemaSQL = `
DROP TRIGGER IF EXISTS stream_segments_seq_floor_raise ON stream_segments;
DROP TRIGGER IF EXISTS stream_segments_seq_floor_guard ON stream_segments;
DROP FUNCTION IF EXISTS stream_segments_raise_seq_floor();
DROP FUNCTION IF EXISTS stream_segments_check_seq_floor();
DROP TABLE IF EXISTS stream_seq_floors, broadcast_lifecycle_outbox;
DROP INDEX IF EXISTS stream_sessions_round_open_idx, stream_segments_session_id_idx, stream_published_gaps_session_idx,
    stream_sessions_inherits_idx, stream_segments_uploaded_session_idx;
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
	if missing, err := MissingLedgerSchema(ctx, pool); err != nil || len(missing) != 0 {
		t.Errorf("1회차 뒤 기동 스키마 확인 = (%q, %v), want (빈 목록, nil)", missing, err)
	}
	if !slices.Equal(first, second) {
		t.Errorf("2회차가 객체를 바꿨다:\n1회차 %q\n2회차 %q", first, second)
	}
}

// 열 넷 가운데 하나만 빠진 DB(손으로 지운 열 · 중간에 멈춘 적용)도 EnsureSchema 한 번이면 기동 스키마 확인을
// 통과한다 — 열마다 따로 확인하므로 남은 열이 빠진 열의 추가를 막지 않는다.
func TestEnsureSchemaRestoresSingleMissingLedgerColumn(t *testing.T) {
	pool := pgtest.Pool(t, "ledgerschema", EnsureSchema, resetTables)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, "ALTER TABLE stream_sessions DROP COLUMN stop_marked_at"); err != nil {
		t.Fatalf("열 지우기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if err := EnsureSchema(context.Background(), pool); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})

	err := EnsureSchema(ctx, pool)
	missing, missErr := MissingLedgerSchema(ctx, pool)

	if err != nil || missErr != nil || len(missing) != 0 {
		t.Errorf("열 하나 빠진 DB 의 EnsureSchema = %v · 기동 확인 = (%q, %v), want nil · (빈 목록, nil)", err, missing, missErr)
	}
}

// 다른 스키마의 같은 이름 표가 열 확인을 속이지 않는다 — 열 확인은 표를 만든 스키마(current_schema)만 본다. 속으면
// 빠진 열을 「있다」로 읽고 ALTER 를 건너뛰어 기동 스키마 확인이 실패한다.
func TestEnsureSchemaIgnoresSameNamedTableInOtherSchema(t *testing.T) {
	pool := pgtest.Pool(t, "ledgerschema", EnsureSchema, resetTables)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, `
		ALTER TABLE stream_sessions DROP COLUMN stop_marked_at;
		CREATE SCHEMA ledger_decoy;
		CREATE TABLE ledger_decoy.stream_sessions (stop_marked_at timestamptz)`); err != nil {
		t.Fatalf("미끼 스키마 만들기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		ctx := context.Background()
		if _, err := pool.Exec(ctx, "DROP SCHEMA IF EXISTS ledger_decoy CASCADE"); err != nil {
			t.Errorf("미끼 스키마 지우기 실패: %v", err)
		}
		if err := EnsureSchema(ctx, pool); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})

	err := EnsureSchema(ctx, pool)
	missing, missErr := MissingLedgerSchema(ctx, pool)

	if err != nil || missErr != nil || len(missing) != 0 {
		t.Errorf("미끼 스키마가 있는 DB 의 EnsureSchema = %v · 기동 확인 = (%q, %v), want nil · (빈 목록, nil)", err, missing, missErr)
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

// D50 스키마 몫 — 기동 스키마 확인은 M6 장부 객체 가운데 없는 것만 이름으로 돌려준다. 사이드카 계정
// (media_indexer)으로 카탈로그를 읽고, 앱 사용자가 public 에 심은 덫을 부르지 않는다.
func TestMissingLedgerSchemaNamesAbsentObjects(t *testing.T) {
	admin := pgtest.Pool(t, "ledgercheck", EnsureSchema, resetTables)
	ctx := context.Background()
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)

	full, fullErr := MissingLedgerSchema(ctx, role.Pool)
	if _, err := admin.Exec(ctx, `
		DROP TRIGGER stream_segments_seq_floor_guard ON stream_segments;
		DROP TABLE broadcast_lifecycle_outbox;
		ALTER TABLE stream_sessions DROP COLUMN stop_marked_at`); err != nil {
		t.Fatalf("객체 지우기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if err := EnsureSchema(context.Background(), admin); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})
	partial, partialErr := MissingLedgerSchema(ctx, role.Pool)

	if fullErr != nil || len(full) != 0 {
		t.Errorf("다 있는 DB 의 MissingLedgerSchema = (%q, %v), want (빈 목록, nil)", full, fullErr)
	}
	want := []string{"broadcast_lifecycle_outbox", "stream_segments_seq_floor_guard", "stream_sessions.stop_marked_at"}
	if partialErr != nil || !slices.Equal(partial, want) {
		t.Errorf("지운 DB 의 MissingLedgerSchema = (%q, %v), want (%q, nil)", partial, partialErr, want)
	}
	traps.RequireNotCalled(t)
}

// raiseFloorFunc 는 하한 갱신 정의자 함수의 서명이다(has_function_privilege · CREATE TRIGGER 인자).
const raiseFloorFunc = "public.stream_segments_raise_seq_floor()"

// 하한 표 권한이 없는 로그인 역할은 자기 임시 표에 하한 갱신 함수를 트리거로 붙이지 못한다(42501). 붙일 수 있으면
// 그 표에서 (아무 스트림, 아주 큰 seq) 행을 지워 정의자(표 소유자) 신분으로 남의 하한을 올린다 — 색인 정지.
// CREATE TRIGGER 는 함수 EXECUTE 를 보므로 PUBLIC 에서 회수한 것이 이 거부의 근거다.
func TestFloorRaiseFunctionCannotBeAttachedByOtherRole(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	role := pgtest.NewLoginRole(t, admin)
	cfg := admin.Config()
	cfg.ConnConfig.User, cfg.ConnConfig.Password = role.Name, role.Password
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("시험 역할 풀 생성 실패: %v", err)
	}
	t.Cleanup(pool.Close)

	// 문장 여럿을 한 번에 보내 같은 연결(임시 표의 수명)에서 돈다.
	_, err = pool.Exec(ctx, `
		CREATE TEMP TABLE floor_decoy (stream_id text, seq bigint);
		CREATE TRIGGER floor_decoy_raise AFTER DELETE ON floor_decoy
			REFERENCING OLD TABLE AS deleted_rows
			FOR EACH STATEMENT EXECUTE FUNCTION `+raiseFloorFunc)

	if !pgtest.IsInsufficientPrivilege(err) {
		t.Errorf("다른 역할의 하한 갱신 트리거 부착 오류 = %v, want 42501", err)
	}
}

// 회수 전에 붙은 트리거(또는 superuser 가 붙인 것)가 남아 있어도 함수가 stream_segments 밖에서는 멈춘다 — 하한은
// 그대로다. 겹 방어다: EXECUTE 회수는 새 부착만 막는다.
func TestFloorRaiseFunctionRefusesForeignTable(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	const s = "floor-decoy"
	if _, err := admin.Exec(ctx, `
		CREATE TABLE seq_floor_decoy (stream_id text, seq bigint);
		CREATE TRIGGER seq_floor_decoy_raise AFTER DELETE ON seq_floor_decoy
			REFERENCING OLD TABLE AS deleted_rows
			FOR EACH STATEMENT EXECUTE FUNCTION `+raiseFloorFunc+`;
		INSERT INTO seq_floor_decoy VALUES ('`+s+`', 9223372036854775000)`); err != nil {
		t.Fatalf("미끼 표 만들기 실패: %v", err)
	}
	t.Cleanup(func() {
		if _, err := admin.Exec(context.Background(), "DROP TABLE IF EXISTS seq_floor_decoy"); err != nil {
			t.Errorf("미끼 표 지우기 실패: %v", err)
		}
	})

	_, err := admin.Exec(ctx, "DELETE FROM seq_floor_decoy")

	var pgErr *pgconn.PgError
	const wantMsg = "seq_floor_raise_foreign_table public.seq_floor_decoy"
	if !errors.As(err, &pgErr) || pgErr.Code != "42501" || pgErr.Message != wantMsg {
		t.Errorf("미끼 표 DELETE 오류 = %v, want 42501 %q", err, wantMsg)
	}
	if got := floorOf(t, admin, s); got != -1 {
		t.Errorf("미끼 표 DELETE 뒤 하한 = %d, want -1(행 없음)", got)
	}
}

// 회귀 방지 — 트리거 발화는 함수 EXECUTE 를 보지 않는다. EXECUTE 가 없는 삭제 계정(app_clip 권한 역할)이
// stream_segments 를 지워도 하한이 오른다.
func TestFloorRaiseFiresForDeleterWithoutExecute(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	const s = "floor-noexec"
	for seq := range int64(3) {
		lcPut(t, admin, lcRow{stream: s, seq: seq})
	}
	clip := clipRolePool(t, admin)
	var canExecute bool
	if err := clip.QueryRow(ctx, `SELECT pg_catalog.has_function_privilege($1, 'EXECUTE')`, raiseFloorFunc).Scan(&canExecute); err != nil {
		t.Fatalf("EXECUTE 조회 실패: %v", err)
	}

	n, err := lcClipPurge(ctx, clip, s, 2)

	if canExecute {
		t.Errorf("삭제 계정의 하한 갱신 함수 EXECUTE = true, want false(PUBLIC 회수)")
	}
	if err != nil || n != 1 {
		t.Fatalf("seq 2 정리 = (%d, %v), want (1, nil)", n, err)
	}
	if got := floorOf(t, admin, s); got != 3 {
		t.Errorf("정리 뒤 하한 = %d, want 3", got)
	}
}

// 음성 대조(M6-0 c4) — 하한 트리거 · 회차 인덱스가 「있지만 일하지 않는」 상태를 40 이 그 단언으로만 잡는다.
// replica 전용('R') 트리거는 일반 연결의 DELETE 에서 돌지 않아 하한이 빠진다. 끈 트리거 · 다른 시점 · 다른 전이
// 표 이름 · 무효 인덱스 · 술어나 열이 다른 인덱스 · 소유자 밖 EXECUTE 도 같다.
func TestProdSQLVerifyCatchesIdleLedgerObjects(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	const (
		raise = "stream_segments_seq_floor_raise"
		guard = "stream_segments_seq_floor_guard"
		idx   = "stream_sessions_round_open_idx"
	)
	tests := []struct {
		name   string
		tamper string
		want   string
	}{
		{"raise disabled", "ALTER TABLE stream_segments DISABLE TRIGGER " + raise, "trigger:" + raise},
		{"guard disabled", "ALTER TABLE stream_segments DISABLE TRIGGER " + guard, "trigger:" + guard},
		{"raise replica only", "ALTER TABLE stream_segments ENABLE REPLICA TRIGGER " + raise, "trigger:" + raise},
		{"guard replica only", "ALTER TABLE stream_segments ENABLE REPLICA TRIGGER " + guard, "trigger:" + guard},
		{
			name: "raise per row",
			tamper: "DROP TRIGGER " + raise + " ON stream_segments; CREATE TRIGGER " + raise +
				" AFTER DELETE ON stream_segments REFERENCING OLD TABLE AS deleted_rows FOR EACH ROW" +
				" EXECUTE FUNCTION stream_segments_raise_seq_floor()",
			want: "trigger:" + raise,
		},
		{
			name: "raise other transition table",
			tamper: "DROP TRIGGER " + raise + " ON stream_segments; CREATE TRIGGER " + raise +
				" AFTER DELETE ON stream_segments REFERENCING OLD TABLE AS gone_rows FOR EACH STATEMENT" +
				" EXECUTE FUNCTION stream_segments_raise_seq_floor()",
			want: "trigger:" + raise,
		},
		{
			name: "guard after insert",
			tamper: "DROP TRIGGER " + guard + " ON stream_segments; CREATE TRIGGER " + guard +
				" AFTER INSERT ON stream_segments FOR EACH ROW EXECUTE FUNCTION stream_segments_check_seq_floor()",
			want: "trigger:" + guard,
		},
		{
			name:   "raise function executable by public",
			tamper: "GRANT EXECUTE ON FUNCTION stream_segments_raise_seq_floor() TO PUBLIC",
			want:   "function_execute:stream_segments_raise_seq_floor",
		},
		{
			// PUBLIC 만 보면 놓친다 — 특정 계정에 준 EXECUTE 도 그 계정의 트리거 부착을 연다.
			name:   "raise function executable by app account",
			tamper: "GRANT EXECUTE ON FUNCTION stream_segments_raise_seq_floor() TO app_clip",
			want:   "function_execute:stream_segments_raise_seq_floor",
		},
		{
			name:   "raise function executable by indexer",
			tamper: "GRANT EXECUTE ON FUNCTION stream_segments_raise_seq_floor() TO media_indexer",
			want:   "function_execute:stream_segments_raise_seq_floor",
		},
		{
			name:   "round index invalid",
			tamper: "UPDATE pg_index SET indisvalid = false WHERE indexrelid = '" + idx + "'::regclass",
			want:   "index:" + idx,
		},
		{
			name:   "round index not ready",
			tamper: "UPDATE pg_index SET indisready = false WHERE indexrelid = '" + idx + "'::regclass",
			want:   "index:" + idx,
		},
		{
			name: "round index other predicate",
			tamper: "DROP INDEX " + idx + "; CREATE INDEX " + idx +
				" ON stream_sessions (round_id, started_at DESC) WHERE round_closed_at IS NULL",
			want: "index:" + idx,
		},
		{
			// 술어는 같고 정렬만 다르다 — 열 정의까지 봐야 잡는다.
			name: "round index other columns",
			tamper: "DROP INDEX " + idx + "; CREATE INDEX " + idx +
				" ON stream_sessions (round_id, started_at) WHERE round_closed_at IS NULL AND round_id IS NOT NULL",
			want: "index:" + idx,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := env.verifyFailuresAfter(t, tt.tamper); !slices.Equal(got, []string{tt.want}) {
				t.Errorf("%s 뒤 40_verify.sql 실패 단언 = %q, want [%s]", tt.tamper, got, tt.want)
			}
		})
	}
}

// 함수가 없으면 EXECUTE 단언도 실패한다 — 「소유자 밖 EXECUTE 0」 은 함수가 없어도 참이라 존재를 함께 본다.
func TestProdSQLVerifyFailsExecuteCheckWithoutFloorRaiseFunction(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)

	got := env.verifyFailuresAfter(t, "DROP FUNCTION stream_segments_raise_seq_floor() CASCADE")

	if !slices.Contains(got, "function_execute:stream_segments_raise_seq_floor") {
		t.Errorf("함수를 지운 뒤 40_verify.sql 실패 단언 = %q, want function_execute:stream_segments_raise_seq_floor 포함", got)
	}
}

// 양성 대조 — 'A'(언제나 켜짐)는 일반 연결에서도 도므로 40 이 통과시킨다. 'O' 만 허용하면 이 상태를 고장으로 읽는다.
func TestProdSQLVerifyAcceptsAlwaysEnabledFloorTrigger(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	script := "BEGIN;\nALTER TABLE stream_segments ENABLE ALWAYS TRIGGER stream_segments_seq_floor_raise;\n" +
		"SET ROLE " + env.masterName + ";\n" + expandProdSQL(t, "40_verify.sql") + "\nROLLBACK;"

	results, err := execScript(t, env.super, script)

	if err != nil || !hasRow(results, verifyOK) {
		t.Errorf("ENABLE ALWAYS 뒤 40_verify.sql = (VERIFY OK 행 %t, %v), want (true, nil)", hasRow(results, verifyOK), err)
	}
}

// ledgerTables 는 장부 표 여섯이다(재실행 잠금 시험의 대상).
const ledgerTables = "'stream_segments', 'stream_sessions', 'stream_cutoffs', 'stream_published_gaps', " +
	"'stream_seq_floors', 'broadcast_lifecycle_outbox'"

// 한 창 재실행의 교착 방지 — 수렴된 DB 에 20 을 다시 돌리는 트랜잭션은 장부 표를 잠그지 않는다(ACCESS SHARE
// 밖). 표 잠금이 없으면 사이드카 문장이 어느 순서로 표를 잡든 재실행과 서로 기다리지 않는다. CREATE INDEX IF NOT
// EXISTS 는 이름을 확인하기 전에 표를 SHARE 로 잡으므로 사본이 존재 확인 DO 블록으로 감싼다.
func TestProdSQLSchemaRerunTakesNoLedgerTableLocks(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	script := "BEGIN;\n" + expandProdSQL(t, "20_schema.sql") + `
SELECT 'ledger locks: ' || coalesce(pg_catalog.string_agg(c.relname || '=' || l.mode, ',' ORDER BY c.relname, l.mode),
                                    'none')
  FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
 WHERE l.pid = pg_catalog.pg_backend_pid() AND c.relname IN (` + ledgerTables + `)
   AND l.mode <> 'AccessShareLock';
ROLLBACK;`

	results, err := execScript(t, env.master, script)

	const want = "ledger locks: none"
	if err != nil || !hasRow(results, want) {
		t.Errorf("20 재실행 중 쥔 장부 표 잠금 = (%v, %v), want 행 %q", firstColumns(results), err, want)
	}
}

// 한 창 재실행과 송출 중 사이드카가 교착하지 않는다 — 두 잠금 순서 모두. 사이드카 문장 하나가 두 표를 잡는 순서를
// 두 문장으로 펼쳐, 첫 표를 쥔 사이에 재실행을 끼운다. 재실행이 표를 잠그면 한쪽 순서에서 40P01 이 난다 —
// sessions → segments 를 먼저 잡던 c4 판은 init 불일치 순서에서, 잠금 없이 사본만 돌던 판은 insertOnce 순서에서.
func TestProdSQLSchemaRerunDoesNotDeadlockWithSidecar(t *testing.T) {
	const probe = "'rerun-probe'"
	tests := []struct {
		name          string
		first, second string
	}{
		{
			// upload_store.go markPlaybackInitMismatchSQL — seg CTE(segments) 뒤 sess CTE(sessions).
			name:   "init mismatch segments then sessions",
			first:  "UPDATE stream_segments SET playback_upload_state = 'failed' WHERE stream_id = " + probe,
			second: "UPDATE stream_sessions SET state = 'ending' WHERE session_id = " + probe,
		},
		{
			// store.go insertOnce — 세션 개시(sessions) 뒤 조각 INSERT(segments).
			name:   "insert once sessions then segments",
			first:  "UPDATE stream_sessions SET state = 'live' WHERE session_id = " + probe,
			second: "UPDATE stream_segments SET local_path = local_path WHERE stream_id = " + probe,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			env := newProdSQLEnv(t)
			env.mustApplyAll(t)
			ctx, cancel := context.WithTimeout(context.Background(), rerunWaitLimit)
			defer cancel()
			rerunPID := env.master.PgConn().PID()
			sidecar := env.super
			if _, err := sidecar.Exec(ctx, "BEGIN; "+tt.first); err != nil {
				t.Fatalf("사이드카 첫 문장 실패: %v", err)
			}
			rerun := make(chan error, 1)
			body := "BEGIN;\n" + expandProdSQL(t, "20_schema.sql") + "\nCOMMIT;"
			go func() {
				_, err := env.master.PgConn().Exec(ctx, body).ReadAll()
				rerun <- err
			}()
			waitRerunDoneOrBlocked(ctx, t, sidecar, rerunPID, rerun)

			_, secondErr := sidecar.Exec(ctx, tt.second)
			endSidecar(t, sidecar, secondErr)
			var rerunErr error
			select {
			case rerunErr = <-rerun:
			case <-ctx.Done():
				t.Fatalf("재실행이 %v 안에 끝나지 않았다", rerunWaitLimit)
			}

			if secondErr != nil || rerunErr != nil {
				t.Errorf("(사이드카 둘째 문장, 재실행) 오류 = (%v, %v), want (nil, nil)", secondErr, rerunErr)
			}
		})
	}
}

// rerunWaitLimit 은 교착 시험 한 사례의 상한이다 — 교착 탐지(deadlock_timeout 1초)보다 넉넉하다.
const rerunWaitLimit = 30 * time.Second

// waitRerunDoneOrBlocked 는 재실행 백엔드(pid)가 트랜잭션을 끝냈거나 잠금을 기다리기 시작할 때까지 기다린다.
// 사이드카 연결로 pg_locks 를 본다(pg_stat_activity 와 달리 트랜잭션 안에서도 매번 새로 읽힌다).
func waitRerunDoneOrBlocked(ctx context.Context, t *testing.T, sidecar *pgx.Conn, pid uint32, rerun chan error) {
	t.Helper()
	for {
		select {
		case err := <-rerun:
			rerun <- err // 결과는 사례 본문이 읽는다
			return
		default:
		}
		var blocked bool
		if err := sidecar.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = $1 AND NOT granted)`,
			int32(pid)).Scan(&blocked); err != nil {
			t.Fatalf("재실행 잠금 대기 조회 실패: %v", err)
		}
		if blocked {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// endSidecar 는 사이드카 트랜잭션을 끝낸다 — 둘째 문장이 성공했으면 COMMIT, 실패했으면 ROLLBACK.
func endSidecar(t *testing.T, sidecar *pgx.Conn, secondErr error) {
	t.Helper()
	end := "COMMIT"
	if secondErr != nil {
		end = "ROLLBACK"
	}
	if _, err := sidecar.Exec(context.Background(), end); err != nil {
		t.Fatalf("사이드카 %s 실패: %v", end, err)
	}
}

// firstColumns 는 결과 전체의 행마다 첫 열이다(실패 문구용).
func firstColumns(results []*pgconn.Result) []string {
	var out []string
	for _, r := range results {
		for _, row := range r.Rows {
			if len(row) > 0 {
				out = append(out, string(row[0]))
			}
		}
	}
	return out
}
