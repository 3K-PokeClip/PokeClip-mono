package index

// PDT 판 2 장부 객체 시험(POK-196 c1 — 설계 r7 2.3 · 2.4 · 9.2 단계 2 · 9.3 · 9.4).
//
// SQL 문장만 쓴다 — 제품 Go API 는 EnsureSchema · MissingLedgerSchema 둘뿐이다. 칸 일곱 · CHECK 넷 · 불변 트리거 ·
// 쓰기자 거부 트리거 · 게이트 표와 그 트리거 · 부분 인덱스 넷 · 기동 확인 항목 · media_indexer 권한을 잰다. 래치를
// 세우는 사례가 있으므로 resetTables 가 게이트를 다시 비운다(남으면 같은 DB 의 다른 시험 INSERT 가 23514 로 걸린다).

import (
	"context"
	"errors"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// timingRow 는 PDT 칸 시험의 조각 한 행이다. nil 칸은 SQL NULL 이다.
type timingRow struct {
	stream   string
	seq      int64
	version  *int16
	wall     *time.Time
	recorder *string
	number   *int64
	first    *int64
	end      *int64
	round    *string
	pdt      *time.Time
	state    string // upload_state — 빈 값이면 'pending'
}

// timingInsertSQL 은 timingRow 하나를 넣는다.
const timingInsertSQL = `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key,
                             timing_version, timing_wall_guess, timing_recorder_id, timing_segment_number,
                             timing_video_first_ns, timing_video_end_ns, timing_round_id, playback_pdt, upload_state)
VALUES ($1, $2::bigint, 0, pg_catalog.now(), 4000, 'k/' || $2::bigint::text,
        $3::smallint, $4::timestamptz, $5::uuid, $6::bigint, $7::bigint, $8::bigint, $9::text, $10::timestamptz,
        coalesce(nullif($11::text, ''), 'pending'))`

func insertTimingRow(ctx context.Context, pool *pgxpool.Pool, r timingRow) error {
	_, err := pool.Exec(ctx, timingInsertSQL, r.stream, r.seq, r.version, r.wall, r.recorder, r.number, r.first,
		r.end, r.round, r.pdt, r.state)
	return err
}

func mustInsertTimingRow(t *testing.T, pool *pgxpool.Pool, r timingRow) {
	t.Helper()
	if err := insertTimingRow(context.Background(), pool, r); err != nil {
		t.Fatalf("고정물 행(%s · %d) INSERT 실패: %v", r.stream, r.seq, err)
	}
}

func timingPtr[T any](v T) *T { return &v }

// v2Row 는 칸이 다 찬 판 2 행이다 — 사례마다 칸 하나를 바꿔 쓴다.
func v2Row(stream string, seq int64) timingRow {
	wall := time.Date(2026, 11, 3, 9, 0, 0, 0, time.UTC)
	return timingRow{
		stream: stream, seq: seq,
		version:  timingPtr(int16(2)),
		wall:     &wall,
		recorder: timingPtr("4f1c2a7e-0b8d-4c3e-9a51-6d2e8f7b1c09"),
		number:   timingPtr(int64(11)),
		first:    timingPtr(int64(1_000_000_000)),
		end:      timingPtr(int64(5_000_000_000)),
		round:    timingPtr("round-" + stream),
		pdt:      &wall,
	}
}

// legacyRow 는 출시 이미지가 쓰는 꼴(PDT 칸 일곱이 모두 NULL)의 행이다.
func legacyRow(stream string, seq int64) timingRow {
	return timingRow{stream: stream, seq: seq}
}

// timingPgError 는 err 의 PostgreSQL 오류다(아니면 nil).
func timingPgError(err error) *pgconn.PgError {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		return pgErr
	}
	return nil
}

// timingColumnTypesSQL 은 stream_segments 의 PDT 칸 일곱의 (이름, 형, NOT NULL, 기본값 있음)이다.
const timingColumnTypesSQL = `
SELECT a.attname::text, pg_catalog.format_type(a.atttypid, a.atttypmod), a.attnotnull, a.atthasdef
  FROM pg_catalog.pg_attribute a
 WHERE a.attrelid = 'stream_segments'::regclass::oid AND a.attnum > 0 AND NOT a.attisdropped
   AND a.attname::text LIKE 'timing\_%'
 ORDER BY 1`

// 칸 일곱은 설계 2.3 표 · 9.2 단계 2 의 형이고 모두 NULL 허용 · 기본값 없음이다 — 출시 이미지는 이 칸을 모르고
// INSERT 하므로 NOT NULL · 기본값이 있으면 옛 행과 새 행을 가를 수 없다(쓰기자 거부의 판별 = wall NULL).
func TestTimingColumnsAreNullableWithoutDefault(t *testing.T) {
	pool := newTestPool(t)
	rows, err := pool.Query(context.Background(), timingColumnTypesSQL)
	if err != nil {
		t.Fatalf("칸 조회 실패: %v", err)
	}
	type column struct {
		name, typ           string
		notNull, hasDefault bool
	}
	var got []column
	for rows.Next() {
		var c column
		if err := rows.Scan(&c.name, &c.typ, &c.notNull, &c.hasDefault); err != nil {
			t.Fatalf("칸 행 읽기 실패: %v", err)
		}
		got = append(got, c)
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("칸 조회 실패: %v", err)
	}

	want := []column{
		{name: "timing_recorder_id", typ: "uuid"},
		{name: "timing_round_id", typ: "text"},
		{name: "timing_segment_number", typ: "bigint"},
		{name: "timing_version", typ: "smallint"},
		{name: "timing_video_end_ns", typ: "bigint"},
		{name: "timing_video_first_ns", typ: "bigint"},
		{name: "timing_wall_guess", typ: "timestamp with time zone"},
	}
	if !slices.Equal(got, want) {
		t.Errorf("PDT 칸 = %+v, want %+v", got, want)
	}
}

// CHECK 넷은 NULL 짝을 강제하고 위반은 23514 · 그 제약 이름이다. 번호 넘침 꼴(표식 · 번호 · 영상 두 칸 NULL · w
// 있음)과 출시 이미지 꼴(일곱 NULL)은 들어간다(P18 · R10 — 막히면 상자 전체 사이드카가 fateFatal 로 멈춘다).
func TestTimingChecksEnforceNullPairs(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	overflow := v2Row("chk", 1)
	overflow.recorder, overflow.number, overflow.first, overflow.end = nil, nil, nil, nil
	noVideo := v2Row("chk", 2)
	noVideo.first, noVideo.end = nil, nil
	valid := []struct {
		name string
		row  timingRow
	}{
		{"full v2", v2Row("chk", 0)},
		{"number overflow", overflow},
		{"no video track", noVideo},
		{"legacy writer", legacyRow("chk", 3)},
	}
	for _, tt := range valid {
		if err := insertTimingRow(ctx, pool, tt.row); err != nil {
			t.Errorf("%s INSERT 오류 = %v, want nil", tt.name, err)
		}
	}

	tests := []struct {
		name   string
		mutate func(*timingRow)
		want   string
	}{
		{"recorder without number", func(r *timingRow) { r.number = nil }, "stream_segments_timing_marker_chk"},
		{"number without recorder", func(r *timingRow) { r.recorder = nil }, "stream_segments_timing_marker_chk"},
		{"video first without end", func(r *timingRow) { r.end = nil }, "stream_segments_timing_video_chk"},
		{"video end without first", func(r *timingRow) { r.first = nil }, "stream_segments_timing_video_chk"},
		{"video without marker", func(r *timingRow) { r.recorder, r.number = nil, nil }, "stream_segments_timing_video_marker_chk"},
		{"v2 without wall", func(r *timingRow) { r.wall = nil }, "stream_segments_timing_v2_chk"},
		{"v2 without pdt", func(r *timingRow) { r.pdt = nil }, "stream_segments_timing_v2_chk"},
	}
	for i, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := v2Row("chk", int64(10+i))
			tt.mutate(&r)
			err := insertTimingRow(ctx, pool, r)
			if e := timingPgError(err); e == nil || e.Code != "23514" || e.ConstraintName != tt.want {
				t.Errorf("INSERT 오류 = %v, want 23514 %s", err, tt.want)
			}
		})
	}
}

// timingUpdate 는 불변 트리거 사례 하나다 — set 은 UPDATE 의 SET 절이다.
type timingUpdate struct {
	name string
	set  string
}

// 칸 여섯(영상 끝 제외)과 timing_version 은 INSERT 뒤 어떤 변경도 거부한다 — NULL → 값 포함(설계 2.3 표). 다른 칸의
// 갱신(꼬리 길이 · 업로드 상태)은 그대로 된다.
func TestTimingFactsAreImmutableAfterInsert(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	mustInsertTimingRow(t, pool, v2Row("imm", 0))
	mustInsertTimingRow(t, pool, legacyRow("imm", 1))
	refused := []struct {
		seq int64
		timingUpdate
	}{
		{0, timingUpdate{"version value", "timing_version = NULL"}},
		{0, timingUpdate{"wall value", "timing_wall_guess = timing_wall_guess + interval '1 ms'"}},
		{0, timingUpdate{"recorder value", "timing_recorder_id = '00000000-0000-4000-8000-000000000000'"}},
		{0, timingUpdate{"number value", "timing_segment_number = timing_segment_number + 1"}},
		{0, timingUpdate{"video first value", "timing_video_first_ns = timing_video_first_ns + 1"}},
		{0, timingUpdate{"round value", "timing_round_id = 'other'"}},
		{1, timingUpdate{"version from null", "timing_version = 2, timing_wall_guess = now(), playback_pdt = now()"}},
		{1, timingUpdate{"wall from null", "timing_wall_guess = now()"}},
		{1, timingUpdate{"round from null", "timing_round_id = 'late'"}},
		{1, timingUpdate{"marker from null", "timing_recorder_id = gen_random_uuid(), timing_segment_number = 1"}},
	}
	for _, tt := range refused {
		t.Run(tt.name, func(t *testing.T) {
			_, err := pool.Exec(ctx, "UPDATE stream_segments SET "+tt.set+" WHERE stream_id = 'imm' AND seq = $1", tt.seq)
			if e := timingPgError(err); e == nil || e.Code != "P0001" {
				t.Errorf("UPDATE %s 오류 = %v, want P0001(불변 트리거)", tt.set, err)
			}
		})
	}

	_, err := pool.Exec(ctx, "UPDATE stream_segments SET duration_ms = 4100, upload_state = 'uploaded' WHERE stream_id = 'imm'")
	if err != nil {
		t.Errorf("PDT 칸 밖 갱신 오류 = %v, want nil", err)
	}
}

// 영상 끝은 꼬리 교정만 허용한다 — pending ∧ 옛 값 있음 ∧ 늘림(설계 2.3 · 2.14 · P21). 옛 값 NULL 꼬리 · uploaded
// 행 · 줄이기 · NULL 로 비우기는 거부한다. 거부는 불변 트리거의 P0001 이다 — 아무 PG 오류나 받으면 트리거의 NULL 방어를
// 빼도 NULL 짝 CHECK 의 23514 로 통과한다(c1 cx M · cc L).
func TestTimingVideoEndGrowsOnlyOnPendingTail(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	mustInsertTimingRow(t, pool, v2Row("vend", 0))
	uploaded := v2Row("vend", 1)
	uploaded.state = "uploaded"
	mustInsertTimingRow(t, pool, uploaded)
	noVideo := v2Row("vend", 2)
	noVideo.first, noVideo.end = nil, nil
	mustInsertTimingRow(t, pool, noVideo)
	const grow = "timing_video_end_ns = 6000000000"
	tests := []struct {
		name   string
		seq    int64
		set    string
		wantOK bool
	}{
		{"pending tail grows", 0, grow, true},
		{"pending tail shrinks", 0, "timing_video_end_ns = 5500000000", false},
		{"pending tail cleared", 0, "timing_video_end_ns = NULL", false},
		{"uploaded row grows", 1, grow, false},
		{"null tail filled", 2, "timing_video_first_ns = 1, " + grow, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := pool.Exec(ctx, "UPDATE stream_segments SET "+tt.set+" WHERE stream_id = 'vend' AND seq = $1", tt.seq)
			if tt.wantOK && err != nil {
				t.Errorf("UPDATE %s 오류 = %v, want nil", tt.set, err)
			}
			if e := timingPgError(err); !tt.wantOK && (e == nil || e.Code != "P0001") {
				t.Errorf("UPDATE %s 오류 = %v, want P0001(불변 트리거)", tt.set, err)
			}
		})
	}
}

// timingGateState 는 게이트 행의 (스위치, 래치)다. NULL 은 0 이다.
func timingGateState(t *testing.T, pool *pgxpool.Pool) (switchVersion, latch int16) {
	t.Helper()
	err := pool.QueryRow(context.Background(),
		`SELECT coalesce(new_round_version, 0), coalesce(highest_version, 0) FROM stream_timing_gate`).Scan(&switchVersion, &latch)
	if err != nil {
		t.Fatalf("게이트 조회 실패: %v", err)
	}
	return switchVersion, latch
}

func mustTimingExec(t *testing.T, pool *pgxpool.Pool, sql string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), sql); err != nil {
		t.Fatalf("%s 실패: %v", sql, err)
	}
}

// 게이트는 씨앗 한 행이고 둘째 행을 받지 않는다(id CHECK · PK).
func TestTimingGateHoldsOneSeedRow(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM stream_timing_gate WHERE id`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("게이트 씨앗 행 = (%d, %v), want (1, nil)", n, err)
	}
	if s, l := timingGateState(t, pool); s != 0 || l != 0 {
		t.Errorf("씨앗 행 (스위치, 래치) = (%d, %d), want (NULL, NULL)", s, l)
	}

	_, dup := pool.Exec(ctx, `INSERT INTO stream_timing_gate (id) VALUES (true)`)
	_, other := pool.Exec(ctx, `INSERT INTO stream_timing_gate (id) VALUES (false)`)
	_, version := pool.Exec(ctx, `UPDATE stream_timing_gate SET new_round_version = 3`)

	if e := timingPgError(dup); e == nil || e.Code != "23505" {
		t.Errorf("둘째 씨앗 INSERT 오류 = %v, want 23505", dup)
	}
	if e := timingPgError(other); e == nil || e.Code != "23514" {
		t.Errorf("id false INSERT 오류 = %v, want 23514", other)
	}
	if e := timingPgError(version); e == nil || e.Code != "23514" {
		t.Errorf("모르는 판 스위치 오류 = %v, want 23514", version)
	}
}

// 게이트 트리거(설계 9.3 · P19 · P20 · m20): DELETE 거부 · 스위치 2 면 래치가 같이 선다 · 스위치 2 → NULL 은 늘
// 된다 · 판 2 행이 있으면 래치 낮추기 거부 · 판 2 행 0 · 스위치 NULL 이면 낮추기 허용 · 스위치를 올린 채 낮추기는
// 래치를 다시 세운다.
func TestTimingGateTriggerLatchesAndRefusesUnsafeLowering(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()

	_, delErr := pool.Exec(ctx, `DELETE FROM stream_timing_gate`)
	mustTimingExec(t, pool, `UPDATE stream_timing_gate SET new_round_version = 2`)
	s1, l1 := timingGateState(t, pool)
	mustInsertTimingRow(t, pool, v2Row("gate", 0))
	mustTimingExec(t, pool, `UPDATE stream_timing_gate SET new_round_version = NULL`)
	s2, l2 := timingGateState(t, pool)
	_, lowerWithRow := pool.Exec(ctx, `UPDATE stream_timing_gate SET highest_version = NULL`)
	mustTimingExec(t, pool, `DELETE FROM stream_segments WHERE stream_id = 'gate'`)
	mustTimingExec(t, pool, `UPDATE stream_timing_gate SET new_round_version = 2, highest_version = NULL`)
	s3, l3 := timingGateState(t, pool)
	mustTimingExec(t, pool, `UPDATE stream_timing_gate SET new_round_version = NULL`)
	_, lowerEmpty := pool.Exec(ctx, `UPDATE stream_timing_gate SET highest_version = NULL`)
	s4, l4 := timingGateState(t, pool)

	if e := timingPgError(delErr); e == nil || e.Code != "23514" || e.Message != "timing_gate_delete_refused" {
		t.Errorf("게이트 DELETE 오류 = %v, want 23514 timing_gate_delete_refused", delErr)
	}
	if s1 != 2 || l1 != 2 || s2 != 0 || l2 != 2 || s3 != 2 || l3 != 2 {
		t.Errorf("(스위치, 래치) 스위치 2 뒤 (%d, %d) · 스위치 NULL 뒤 (%d, %d) · 올린 채 낮추기 뒤 (%d, %d), want (2, 2) · (0, 2) · (2, 2)",
			s1, l1, s2, l2, s3, l3)
	}
	if e := timingPgError(lowerWithRow); e == nil || e.Code != "23514" || e.Message != "timing_latch_lower_refused" {
		t.Errorf("판 2 행이 있는 래치 낮추기 오류 = %v, want 23514 timing_latch_lower_refused", lowerWithRow)
	}
	if lowerEmpty != nil || s4 != 0 || l4 != 0 {
		t.Errorf("판 2 행 0 · 스위치 NULL 래치 낮추기 = (%v, 스위치 %d, 래치 %d), want (nil, 0, 0)", lowerEmpty, s4, l4)
	}
}

// timingGateSharedLockSQL 은 회차 개시가 게이트를 읽기 전에 잡는 공유 잠금이다(설계 9.3 — c4 가 같은 열쇠를 쓴다).
const timingGateSharedLockSQL = `SELECT pg_catalog.pg_advisory_xact_lock_shared(pg_catalog.hashtext('pc_timing_gate')::bigint)`

// 게이트 트리거는 맨 앞에서 같은 열쇠의 배타 잠금을 잡는다 — 공유 잠금을 쥔 개시가 끝나기 전에는 스위치 · 래치를
// 바꾸지 못하고(lock_timeout 55P03), 놓으면 바꾼다(P19 의 직렬화 전제).
func TestTimingGateTriggerWaitsForSharedGateLock(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	opener, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("개시 트랜잭션 시작 실패: %v", err)
	}
	defer opener.Rollback(ctx)
	if _, err := opener.Exec(ctx, timingGateSharedLockSQL); err != nil {
		t.Fatalf("공유 잠금 실패: %v", err)
	}

	switcher, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("스위치 트랜잭션 시작 실패: %v", err)
	}
	defer switcher.Rollback(ctx)
	if _, err := switcher.Exec(ctx, `SET LOCAL lock_timeout = '200ms'`); err != nil {
		t.Fatalf("lock_timeout 설정 실패: %v", err)
	}
	_, blocked := switcher.Exec(ctx, `UPDATE stream_timing_gate SET new_round_version = 2`)
	// 스위치 트랜잭션을 먼저 끝낸다 — 잠금 없이 갱신이 지나간 판(변이)에서 그 행 잠금이 아래 갱신을 영원히 세우지 않게.
	if err := switcher.Rollback(ctx); err != nil {
		t.Fatalf("스위치 트랜잭션 끝내기 실패: %v", err)
	}
	if err := opener.Rollback(ctx); err != nil {
		t.Fatalf("개시 트랜잭션 끝내기 실패: %v", err)
	}
	_, after := pool.Exec(ctx, `UPDATE stream_timing_gate SET new_round_version = 2`)

	if e := timingPgError(blocked); e == nil || e.Code != "55P03" {
		t.Errorf("공유 잠금 아래 스위치 변경 오류 = %v, want 55P03(잠금 대기 상한)", blocked)
	}
	if after != nil {
		t.Errorf("공유 잠금을 놓은 뒤 스위치 변경 오류 = %v, want nil", after)
	}
}

// 래치 낮추기는 READ COMMITTED 에서만 된다(c1 · c4b 보안 M1). 트랜잭션 스냅숏을 한 번 잡는 격리(REPEATABLE READ ·
// SERIALIZABLE)에서는 판 2 행 셈이 그 스냅숏으로 돌아, 스냅숏 뒤 커밋된 판 2 행을 못 보고 래치를 내린다 — 판 2 행이 있는데
// 래치 NULL 이면 쓰기자 거부가 꺼진다. 그래서 그 격리의 낮추기는 판 2 행과 무관하게 23514 로 거부한다. READ COMMITTED
// 대조군은 같은 순서에서 그 행을 보고 timing_latch_lower_refused 로 거부한다. 잡는 결함: 격리 검사를 빼면 REPEATABLE
// READ · SERIALIZABLE 낮추기가 지나가 래치가 NULL 이 된다.
func TestTimingLatchLoweringRefusedOutsideReadCommitted(t *testing.T) {
	tests := []struct {
		level   string
		wantMsg string
	}{
		{"repeatable read", "timing_latch_lower_needs_read_committed"},
		{"serializable", "timing_latch_lower_needs_read_committed"},
		{"read committed", "timing_latch_lower_refused"},
	}
	for _, tt := range tests {
		t.Run(tt.level, func(t *testing.T) {
			pool := newTestPool(t)
			ctx := context.Background()
			mustTimingExec(t, pool, `UPDATE stream_timing_gate SET new_round_version = 2`)
			mustTimingExec(t, pool, `UPDATE stream_timing_gate SET new_round_version = NULL`)

			lowerer, err := pool.Begin(ctx)
			if err != nil {
				t.Fatalf("낮추기 트랜잭션 시작 실패: %v", err)
			}
			defer lowerer.Rollback(ctx)
			if _, err := lowerer.Exec(ctx, "SET TRANSACTION ISOLATION LEVEL "+tt.level); err != nil {
				t.Fatalf("격리 수준 설정 실패: %v", err)
			}
			// 첫 질의 — REPEATABLE READ · SERIALIZABLE 은 여기서 스냅숏을 고정한다.
			if _, err := lowerer.Exec(ctx, `SELECT 1 FROM stream_timing_gate`); err != nil {
				t.Fatalf("스냅숏 질의 실패: %v", err)
			}
			mustInsertTimingRow(t, pool, v2Row("rr", 0))
			_, lowered := lowerer.Exec(ctx, `UPDATE stream_timing_gate SET highest_version = NULL`)
			if err := lowerer.Rollback(ctx); err != nil {
				t.Fatalf("낮추기 트랜잭션 끝내기 실패: %v", err)
			}

			if e := timingPgError(lowered); e == nil || e.Code != "23514" || e.Message != tt.wantMsg {
				t.Errorf("%s 래치 낮추기 오류 = %v, want 23514 %s", tt.level, lowered, tt.wantMsg)
			}
		})
	}
}

// 쓰기자 거부(설계 9.4 · P20 · m16): 래치 NULL 이면 칸 없는 INSERT(출시 이미지 꼴)가 들어가고, 래치 2 면 23514
// legacy_writer_refused 다. 칸이 있는 INSERT 는 래치와 무관하게 들어간다.
func TestLegacyWriterRefusedOnceLatched(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()

	before := insertTimingRow(ctx, pool, legacyRow("lw", 0))
	mustTimingExec(t, pool, `UPDATE stream_timing_gate SET new_round_version = 2`)
	mustTimingExec(t, pool, `UPDATE stream_timing_gate SET new_round_version = NULL`)
	latched := insertTimingRow(ctx, pool, legacyRow("lw", 1))
	nullVersion := v2Row("lw", 2)
	nullVersion.version = nil
	newWriter := insertTimingRow(ctx, pool, nullVersion)

	if before != nil {
		t.Errorf("래치 NULL 의 출시 이미지 INSERT 오류 = %v, want nil", before)
	}
	if e := timingPgError(latched); e == nil || e.Code != "23514" || e.Message != "legacy_writer_refused" {
		t.Errorf("래치 2 의 출시 이미지 INSERT 오류 = %v, want 23514 legacy_writer_refused", latched)
	}
	if newWriter != nil {
		t.Errorf("래치 2 의 새 이미지 판 NULL INSERT 오류 = %v, want nil", newWriter)
	}
}

// 사이드카 계정(media_indexer)은 게이트를 읽기만 한다 — SELECT 는 되고 UPDATE · DELETE · INSERT 는 42501 이다.
// 쓰기자 거부 트리거는 부른 쪽 권한으로 게이트를 읽으므로 칸 없는 INSERT 가 그 SELECT 에 기대고(회수하면 42501),
// 회차 개시의 공유 잠금은 PUBLIC 기본으로 된다(설계 9.3).
func TestMediaIndexerReadsTimingGateOnly(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	role := pgtest.NewMediaIndexerRole(t, admin)
	const (
		readGate   = `SELECT new_round_version, highest_version FROM stream_timing_gate`
		writeGate  = `UPDATE stream_timing_gate SET new_round_version = 2`
		deleteGate = `DELETE FROM stream_timing_gate`
		insertGate = `INSERT INTO stream_timing_gate (id) VALUES (true)`
	)
	var sv, hv *int16
	if err := role.Pool.QueryRow(ctx, readGate).Scan(&sv, &hv); err != nil {
		t.Errorf("media_indexer 게이트 SELECT 오류 = %v, want nil", err)
	}
	if _, err := role.Pool.Exec(ctx, "BEGIN; "+timingGateSharedLockSQL+"; COMMIT"); err != nil {
		t.Errorf("media_indexer 공유 잠금 오류 = %v, want nil", err)
	}
	legacyErr := insertTimingRow(ctx, role.Pool, legacyRow("role-gate", 0))
	for _, sql := range []string{writeGate, deleteGate, insertGate} {
		_, _ = role.Pool.Exec(ctx, sql)
	}
	role.Revoke(t, "SELECT", "stream_timing_gate")
	revokedErr := insertTimingRow(ctx, role.Pool, legacyRow("role-gate", 1))

	if legacyErr != nil {
		t.Errorf("media_indexer 칸 없는 INSERT(래치 NULL) 오류 = %v, want nil", legacyErr)
	}
	role.RequireDenied(t, "게이트 UPDATE", writeGate)
	role.RequireDenied(t, "게이트 DELETE", deleteGate)
	role.RequireDenied(t, "게이트 INSERT", insertGate)
	if !pgtest.IsInsufficientPrivilege(revokedErr) {
		t.Errorf("게이트 SELECT 회수 뒤 칸 없는 INSERT 오류 = %v, want 42501", revokedErr)
	}
}

// timingSchemaObjectsSQL 은 PDT 장부 객체의 카탈로그 그림이다(종류:이름:oid) — 두 번째 EnsureSchema 가 아무것도 다시
// 만들지 않았는지를 oid 까지 같은가로 본다.
const timingSchemaObjectsSQL = `
SELECT kind || ':' || name || ':' || oid::text FROM (
    SELECT 'column' AS kind, a.attname::text AS name, a.attrelid AS oid
      FROM pg_attribute a
     WHERE a.attrelid = 'stream_segments'::regclass::oid AND NOT a.attisdropped AND a.attname::text LIKE 'timing\_%'
    UNION ALL
    SELECT 'relation', c.relname::text, c.oid FROM pg_class c
     WHERE c.relname::text = ANY (ARRAY['stream_timing_gate', 'stream_segments_v2_seq_idx',
                                        'stream_segments_legacy_wall_idx', 'stream_segments_timing_round_idx',
                                        'stream_segments_v2_pdt_idx'])
    UNION ALL
    SELECT 'constraint', c.conname::text, c.oid FROM pg_constraint c
     WHERE c.conrelid = 'stream_segments'::regclass::oid AND c.conname::text LIKE 'stream\_segments\_timing\_%'
    UNION ALL
    SELECT 'trigger', t.tgname::text, t.oid FROM pg_trigger t
     WHERE t.tgname::text = ANY (ARRAY['stream_segments_legacy_writer_guard', 'stream_timing_gate_guard_tg'])
) o ORDER BY 1`

// timingSchemaObjectCount 는 위 그림의 객체 수다 — 칸 일곱 · 표 하나 · 인덱스 넷 · CHECK 넷 · 트리거 둘.
const timingSchemaObjectCount = 18

// dropTimingSchemaSQL 은 c1 앞 판(develop) 스키마로 되돌린다.
const dropTimingSchemaSQL = `
DROP TRIGGER IF EXISTS stream_segments_legacy_writer_guard ON stream_segments;
DROP FUNCTION IF EXISTS stream_segments_refuse_legacy_writer();
DROP TABLE IF EXISTS stream_timing_gate;
DROP FUNCTION IF EXISTS stream_timing_gate_guard();
ALTER TABLE stream_segments
    DROP COLUMN IF EXISTS timing_version, DROP COLUMN IF EXISTS timing_wall_guess,
    DROP COLUMN IF EXISTS timing_recorder_id, DROP COLUMN IF EXISTS timing_segment_number,
    DROP COLUMN IF EXISTS timing_video_first_ns, DROP COLUMN IF EXISTS timing_video_end_ns,
    DROP COLUMN IF EXISTS timing_round_id`

func timingSchemaObjects(t *testing.T, pool *pgxpool.Pool) []string {
	t.Helper()
	rows, err := pool.Query(context.Background(), timingSchemaObjectsSQL)
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

// 앞 판 DB(옛 볼륨 · dev)에도 PDT 객체가 다 붙고, 두 번째 실행은 오류 없이 아무것도 다시 만들지 않는다(멱등).
func TestEnsureSchemaAddsTimingObjectsOnceToOldDB(t *testing.T) {
	pool := pgtest.Pool(t, "timingschema", EnsureSchema, resetTables)
	ctx := context.Background()
	mustTimingExec(t, pool, dropTimingSchemaSQL)
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if err := EnsureSchema(context.Background(), pool); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})

	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("앞 판 DB 의 EnsureSchema 1회차 오류 = %v, want nil", err)
	}
	first := timingSchemaObjects(t, pool)
	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("EnsureSchema 2회차 오류 = %v, want nil", err)
	}
	second := timingSchemaObjects(t, pool)

	if len(first) != timingSchemaObjectCount {
		t.Fatalf("1회차 뒤 PDT 객체 = %q (%d개), want %d개", first, len(first), timingSchemaObjectCount)
	}
	if !slices.Equal(first, second) {
		t.Errorf("2회차가 객체를 바꿨다:\n1회차 %q\n2회차 %q", first, second)
	}
	if s, l := timingGateState(t, pool); s != 0 || l != 0 {
		t.Errorf("새로 만든 게이트 (스위치, 래치) = (%d, %d), want (NULL, NULL)", s, l)
	}
}

// 칸 하나만 빠진 DB 도 EnsureSchema 한 번이면 그 칸을 되살린다 — 칸마다 따로 확인한다.
func TestEnsureSchemaRestoresSingleMissingTimingColumn(t *testing.T) {
	pool := pgtest.Pool(t, "timingschema", EnsureSchema, resetTables)
	ctx := context.Background()
	mustTimingExec(t, pool, "ALTER TABLE stream_segments DROP COLUMN timing_round_id")
	t.Cleanup(func() {
		if err := EnsureSchema(context.Background(), pool); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})

	err := EnsureSchema(ctx, pool)
	got := timingSchemaObjects(t, pool)

	if err != nil || len(got) != timingSchemaObjectCount {
		t.Errorf("칸 하나 빠진 DB 의 EnsureSchema = %v · 객체 %d개, want nil · %d개", err, len(got), timingSchemaObjectCount)
	}
}

// 기동 확인은 PDT 항목(칸 일곱 · 게이트 표 · 트리거 둘 · 회차 인덱스) 가운데 없는 것을 이름으로 낸다. 칸을 지우면
// 그 칸을 쓰는 인덱스도 함께 지워지고, 표를 지우면 그 표의 트리거도 함께 지워진다.
func TestMissingLedgerSchemaNamesAbsentTimingObjects(t *testing.T) {
	tests := []struct {
		name string
		drop string
		want []string
	}{
		{"version column", "ALTER TABLE stream_segments DROP COLUMN timing_version",
			[]string{"stream_segments.timing_version"}},
		{"wall column", "ALTER TABLE stream_segments DROP COLUMN timing_wall_guess",
			[]string{"stream_segments.timing_wall_guess"}},
		{"recorder column", "ALTER TABLE stream_segments DROP COLUMN timing_recorder_id",
			[]string{"stream_segments.timing_recorder_id"}},
		{"number column", "ALTER TABLE stream_segments DROP COLUMN timing_segment_number",
			[]string{"stream_segments.timing_segment_number"}},
		{"video first column", "ALTER TABLE stream_segments DROP COLUMN timing_video_first_ns",
			[]string{"stream_segments.timing_video_first_ns"}},
		{"video end column", "ALTER TABLE stream_segments DROP COLUMN timing_video_end_ns",
			[]string{"stream_segments.timing_video_end_ns"}},
		{"round column", "ALTER TABLE stream_segments DROP COLUMN timing_round_id",
			[]string{"stream_segments.timing_round_id", "stream_segments_timing_round_idx"}},
		{"round index", "DROP INDEX stream_segments_timing_round_idx",
			[]string{"stream_segments_timing_round_idx"}},
		{"gate table", "DROP TABLE stream_timing_gate",
			[]string{"stream_timing_gate", "stream_timing_gate_guard_tg"}},
		{"gate trigger", "DROP TRIGGER stream_timing_gate_guard_tg ON stream_timing_gate",
			[]string{"stream_timing_gate_guard_tg"}},
		{"writer guard", "DROP TRIGGER stream_segments_legacy_writer_guard ON stream_segments",
			[]string{"stream_segments_legacy_writer_guard"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			admin := pgtest.Pool(t, "ledgercheck", EnsureSchema, resetTables)
			ctx := context.Background()
			role := pgtest.NewMediaIndexerRole(t, admin)
			mustTimingExec(t, admin, tt.drop)
			t.Cleanup(func() {
				if err := EnsureSchema(context.Background(), admin); err != nil {
					t.Errorf("스키마 되돌리기 실패: %v", err)
				}
			})

			got, err := MissingLedgerSchema(ctx, role.Pool)

			if err != nil || !slices.Equal(got, tt.want) {
				t.Errorf("%s 뒤 MissingLedgerSchema = (%q, %v), want (%q, nil)", tt.drop, got, err, tt.want)
			}
		})
	}
}

// 음성 대조 — PDT 장부 객체 · 권한이 어긋나면 40 이 바로 그 단언으로만 잡는다(설계 9.2 단계 2 · 9.3).
func TestProdSQLVerifyCatchesTimingSchemaDrift(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	tests := []struct {
		name   string
		tamper string
		want   []string
	}{
		// 판 칸을 지우면 그 칸을 술어로 쓰는 인덱스 셋(floor 둘 · 판 2 PDT)과 판 2 CHECK 도 함께 지워진다(w 칸도 그 CHECK 를
		// 함께 지운다).
		{"version column dropped", "ALTER TABLE stream_segments DROP COLUMN timing_version",
			[]string{"check:stream_segments_timing_v2_chk", "index:stream_segments_legacy_wall_idx",
				"index:stream_segments_v2_pdt_idx", "index:stream_segments_v2_seq_idx",
				"ledger_column:stream_segments.timing_version"}},
		{"wall column dropped", "ALTER TABLE stream_segments DROP COLUMN timing_wall_guess",
			[]string{"check:stream_segments_timing_v2_chk", "ledger_column:stream_segments.timing_wall_guess"}},
		// PDT 칸을 지우면 그 칸을 열로 쓰는 판 2 PDT 인덱스와 그 칸을 술어로 쓰는 판 2 CHECK 도 함께 지워진다. playback_pdt
		// 는 40 의 ledger_column 목록(M6 · PDT 칸)에 없어, 40 은 이 둘로 잡는다.
		{"playback pdt column dropped", "ALTER TABLE stream_segments DROP COLUMN playback_pdt",
			[]string{"check:stream_segments_timing_v2_chk", "index:stream_segments_v2_pdt_idx"}},
		{"round index dropped", "DROP INDEX stream_segments_timing_round_idx",
			[]string{"index:stream_segments_timing_round_idx"}},
		{"v2 floor index dropped", "DROP INDEX stream_segments_v2_seq_idx",
			[]string{"index:stream_segments_v2_seq_idx"}},
		{"legacy floor index dropped", "DROP INDEX stream_segments_legacy_wall_idx",
			[]string{"index:stream_segments_legacy_wall_idx"}},
		{"v2 pdt index dropped", "DROP INDEX stream_segments_v2_pdt_idx",
			[]string{"index:stream_segments_v2_pdt_idx"}},
		{"writer guard dropped", "DROP TRIGGER stream_segments_legacy_writer_guard ON stream_segments",
			[]string{"trigger:stream_segments_legacy_writer_guard"}},
		{"writer guard disabled", "ALTER TABLE stream_segments DISABLE TRIGGER stream_segments_legacy_writer_guard",
			[]string{"trigger:stream_segments_legacy_writer_guard"}},
		{"gate trigger dropped", "DROP TRIGGER stream_timing_gate_guard_tg ON stream_timing_gate",
			[]string{"trigger:stream_timing_gate_guard_tg"}},
		{"gate function search_path reset", "ALTER FUNCTION stream_timing_gate_guard() RESET search_path",
			[]string{"function:stream_timing_gate_guard"}},
		{"gate select revoked", "REVOKE SELECT ON stream_timing_gate FROM media_indexer",
			[]string{"privilege:media_indexer:stream_timing_gate:SELECT"}},
		{"gate update granted", "GRANT UPDATE ON stream_timing_gate TO media_indexer",
			[]string{"privilege:media_indexer:stream_timing_gate:UPDATE"}},
		{"shared lock revoked", "REVOKE EXECUTE ON FUNCTION pg_advisory_xact_lock_shared(bigint) FROM PUBLIC",
			[]string{"advisory_xact_lock_shared_media_indexer"}},
		// CHECK 는 정의 본문이 아니라 이름 · 대상 표 · 종류로 본다(c1 cx M) — 지우면 그 단언 하나다.
		{"v2 check dropped", "ALTER TABLE stream_segments DROP CONSTRAINT stream_segments_timing_v2_chk",
			[]string{"check:stream_segments_timing_v2_chk"}},
		{"marker check dropped", "ALTER TABLE stream_segments DROP CONSTRAINT stream_segments_timing_marker_chk",
			[]string{"check:stream_segments_timing_marker_chk"}},
		{"gate switch check dropped", "ALTER TABLE stream_timing_gate DROP CONSTRAINT stream_timing_gate_switch_chk",
			[]string{"check:stream_timing_gate_switch_chk"}},
		// 불변 트리거 함수도 search_path 를 고정한다(c1 보안 L4).
		{"immutable axes search_path reset", "ALTER FUNCTION stream_segments_immutable_axes() RESET search_path",
			[]string{"function:stream_segments_immutable_axes"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := env.verifyFailuresAfter(t, tt.tamper); !slices.Equal(got, tt.want) {
				t.Errorf("%s 뒤 40_verify.sql 실패 단언 = %q, want %q", tt.tamper, got, tt.want)
			}
		})
	}
}
