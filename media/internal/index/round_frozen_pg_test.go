package index

// 회차 불변 트리거 시험(POK-525 설계 r9.3 2-2 · 8-2 E9 · E27 · 계획 Ⓑ1-3).
//
// stream_sessions.round_id 는 INSERT 뒤 바뀌지 않는다. 새 판 코드에는 round_id 를 바꾸는 문장이 없고
// (round_frozen_static_test.go), 트리거는 다른 길(옛 이미지 · 손 SQL · 앞날 코드)을 막는 울타리다. 다른 값으로 바꾸는
// UPDATE 만 23514 로 거부하고, 같은 값 · 다른 열 UPDATE 는 지나간다(UPDATE OF round_id + WHEN). PG_DSN 미설정이면
// 전량 skip 된다.

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// roundFrozenTrigger 는 회차 불변 트리거의 이름이다(롤백 짝 DROP 이 겨누는 이름).
const roundFrozenTrigger = "stream_sessions_round_frozen"

// roundFrozenMessage 는 트리거가 거부할 때 내는 오류 문구다.
const roundFrozenMessage = "stream_sessions.round_id is frozen"

// putFrozenSession 은 round_id 를 INSERT 때 정한 live 세션 한 행을 심는다("" = NULL — M6 전 세션).
func putFrozenSession(t *testing.T, pool *pgxpool.Pool, id, round string) {
	t.Helper()
	var v any
	if round != "" {
		v = round
	}
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, round_id)
VALUES ($1, 'frozen-' || $1, $2, 'live', $3)`, id, time.Now().Add(-time.Hour), v); err != nil {
		t.Fatalf("세션 픽스처 실패 %s: %v", id, err)
	}
}

// isRoundFrozen 은 err 가 회차 불변 트리거의 거부(23514 · 정해진 문구)인가다.
func isRoundFrozen(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23514" && pgErr.Message == roundFrozenMessage
}

// E9 — round_id 를 다른 값으로 바꾸는 UPDATE 는 사이드카 계정(media_indexer)으로도 23514 로 거부된다. NULL 에서 값 ·
// 값에서 NULL 도 바꾸는 것이다. 변이: 트리거 제거 → 거부 단언 FAIL.
func TestRoundFrozenRejectsRoundChange(t *testing.T) {
	admin := newTestPool(t)
	role := pgtest.NewMediaIndexerRole(t, admin)
	putFrozenSession(t, admin, "S-A", "R1")
	putFrozenSession(t, admin, "S-N", "")

	for _, tt := range []struct {
		name, session string
		to            any
	}{
		{"value_to_other_value", "S-A", "R2"},
		{"value_to_null", "S-A", nil},
		{"null_to_value", "S-N", "R1"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			_, err := role.Pool.Exec(context.Background(),
				`UPDATE stream_sessions SET round_id = $2 WHERE session_id = $1`, tt.session, tt.to)
			if !isRoundFrozen(err) {
				t.Errorf("%s 의 round_id → %v 오류 = %v, want 23514 %q", tt.session, tt.to, err, roundFrozenMessage)
			}
		})
	}
}

// E27 — 정당한 세션 UPDATE 는 트리거에 걸리지 않는다. round_id 를 같은 값으로 다시 쓰는 문장(NULL 그대로 포함)과
// 다른 열만 바꾸는 상태 전이 · 표식 · 동결 · 회차 닫힘 · 발행 진행 문장이 모두 지나간다. 변이: WHEN 제거 → 같은
// 값 사례 FAIL.
func TestRoundFrozenAllowsSameRoundAndOtherColumns(t *testing.T) {
	admin := newTestPool(t)
	role := pgtest.NewMediaIndexerRole(t, admin)
	putFrozenSession(t, admin, "S-A", "R1")
	putFrozenSession(t, admin, "S-N", "")

	for _, tt := range []struct {
		name, sql string
	}{
		{"same_value_literal", `UPDATE stream_sessions SET round_id = 'R1' WHERE session_id = 'S-A'`},
		{"same_value_self", `UPDATE stream_sessions SET round_id = round_id WHERE session_id = 'S-A'`},
		{"null_stays_null", `UPDATE stream_sessions SET round_id = NULL WHERE session_id = 'S-N'`},
		{"state_ending", `UPDATE stream_sessions SET state = 'ending', ending_at = now(), end_reason = 'offline' WHERE session_id = 'S-A'`},
		{"stop_mark", `UPDATE stream_sessions SET stop_marked_at = COALESCE(stop_marked_at, now()) WHERE session_id = 'S-A'`},
		{"dvr_freeze", `UPDATE stream_sessions SET dvr_state = 'freezing', dvr_freezing_at = now() WHERE session_id = 'S-A'`},
		{"vod_freeze", `UPDATE stream_sessions SET vod_state = 'frozen' WHERE session_id = 'S-A'`},
		{"round_close", `UPDATE stream_sessions SET round_closed_at = now() WHERE round_id = 'R1' AND round_closed_at IS NULL`},
		{"publish_progress", `UPDATE stream_sessions SET published_seq = 3, manifest_gen = manifest_gen + 1 WHERE session_id = 'S-A'`},
		{"state_ended", `UPDATE stream_sessions SET state = 'ended', ended_at = now() WHERE session_id = 'S-A'`},
	} {
		t.Run(tt.name, func(t *testing.T) {
			if _, err := role.Pool.Exec(context.Background(), tt.sql); err != nil {
				t.Errorf("%s 오류 = %v, want nil", tt.sql, err)
			}
		})
	}
}

// roundFrozenTriggerOID 는 회차 불변 트리거의 oid 다(없으면 0).
func roundFrozenTriggerOID(t *testing.T, pool *pgxpool.Pool) uint32 {
	t.Helper()
	var oid uint32
	err := pool.QueryRow(context.Background(), `
SELECT coalesce((SELECT oid FROM pg_trigger
                  WHERE tgrelid = 'stream_sessions'::regclass::oid AND tgname = $1), 0)`, roundFrozenTrigger).Scan(&oid)
	if err != nil {
		t.Fatalf("트리거 조회 실패: %v", err)
	}
	return oid
}

// E32 · 로컬 몫 — EnsureSchema 는 schemaDDL 뒤에 회차 불변 트리거도 깐다. 트리거가 없는 DB(이 판 앞의 로컬 볼륨)에는
// 붙이고, 다시 돌려도 오류 없이 같은 트리거(oid)를 그대로 둔다.
func TestEnsureSchemaInstallsRoundFrozenTriggerOnce(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	if _, err := pool.Exec(ctx, "DROP TRIGGER IF EXISTS "+roundFrozenTrigger+" ON stream_sessions"); err != nil {
		t.Fatalf("트리거 지우기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if err := EnsureSchema(context.Background(), pool); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})

	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("트리거 없는 DB 의 EnsureSchema 오류 = %v, want nil", err)
	}
	first := roundFrozenTriggerOID(t, pool)
	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("EnsureSchema 재실행 오류 = %v, want nil", err)
	}
	second := roundFrozenTriggerOID(t, pool)

	if first == 0 {
		t.Fatal("EnsureSchema 뒤 회차 불변 트리거가 없다")
	}
	if second != first {
		t.Errorf("재실행이 트리거를 다시 만들었다: oid %d → %d", first, second)
	}
}

// 기동 신호 재료(설계 2-2 (다) · 계획 Ⓑ1-3) — RoundFrozenTriggerPresent 는 트리거가 있으면 참 · 없으면 거짓이다. 사이드카
// 계정(media_indexer)으로 카탈로그를 읽고, 앱 사용자가 public 에 심은 덫을 부르지 않는다. 기동 필수 목록
// (MissingLedgerSchema)은 트리거와 무관하다 — 트리거가 없어도 빈 목록이다.
func TestRoundFrozenTriggerPresentReadsCatalog(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	traps := pgtest.PlantSearchPathTraps(t, admin)
	role := pgtest.NewMediaIndexerRole(t, admin)

	present, presentErr := RoundFrozenTriggerPresent(ctx, role.Pool)
	if _, err := admin.Exec(ctx, "DROP TRIGGER "+roundFrozenTrigger+" ON stream_sessions"); err != nil {
		t.Fatalf("트리거 지우기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if err := EnsureSchema(context.Background(), admin); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})
	absent, absentErr := RoundFrozenTriggerPresent(ctx, role.Pool)
	missing, missingErr := MissingLedgerSchema(ctx, role.Pool)

	if presentErr != nil || !present {
		t.Errorf("트리거 있는 DB 의 RoundFrozenTriggerPresent = (%v, %v), want (true, nil)", present, presentErr)
	}
	if absentErr != nil || absent {
		t.Errorf("트리거 없는 DB 의 RoundFrozenTriggerPresent = (%v, %v), want (false, nil)", absent, absentErr)
	}
	if missingErr != nil || len(missing) != 0 {
		t.Errorf("트리거 없는 DB 의 MissingLedgerSchema = (%q, %v), want (빈 목록, nil) — 트리거는 기동 필수가 아니다", missing, missingErr)
	}
	traps.RequireNotCalled(t)
}
