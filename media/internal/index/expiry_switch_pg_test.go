package index

// 만료 끄기 스위치(POK-551 c2 · 계획 T5 · 결함 예측 P5) PG 통합 시험 — 씨앗 행은 끔이고, 손 SQL 로 켜고 끄며, 스키마
// 재실행은 켠 값을 되돌리지 않는다. 읽지 못하면(행 없음 · 표 없음 · 권한 없음 · 시간 초과) 끔과 오류다.
// PG_DSN 이 없으면 skip(REQUIRE_PG=1 이면 실패).

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// newSwitchPool 은 스위치 시험 전용 DB 의 풀이다. 표를 지우는 사례가 있어 전용 DB 를 쓰고, 사례마다 표를 지운 뒤
// EnsureSchema 로 다시 만든다 — 씨앗 행은 DDL 이 넣은 그대로다.
func newSwitchPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "expswitch", EnsureSchema, func(ctx context.Context, pool *pgxpool.Pool) error {
		if _, err := pool.Exec(ctx, "DROP TABLE IF EXISTS stream_expiry_switch"); err != nil {
			return err
		}
		return EnsureSchema(ctx, pool)
	})
}

func switchExec(t *testing.T, pool *pgxpool.Pool, sql string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), sql); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
}

func switchRows(t *testing.T, pool *pgxpool.Pool) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(), "SELECT count(*) FROM stream_expiry_switch").Scan(&n); err != nil {
		t.Fatalf("스위치 행 수: %v", err)
	}
	return n
}

// 씨앗 행은 끔이다 — 배포만으로는 아무것도 지워지지 않는다.
func TestReadExpirySwitchSeedRowIsOff(t *testing.T) {
	pool := newSwitchPool(t)

	on, err := ReadExpirySwitch(context.Background(), pool)

	if on || err != nil {
		t.Fatalf("ReadExpirySwitch = (%t, %v), want (false, nil)", on, err)
	}
	if n := switchRows(t, pool); n != 1 {
		t.Fatalf("씨앗 행 %d (1 기대)", n)
	}
}

// 손 SQL 로 켜고 끄면 재기동 없이 다음 읽기가 그 값이다.
func TestReadExpirySwitchFollowsManualSQL(t *testing.T) {
	pool := newSwitchPool(t)
	ctx := context.Background()
	for _, want := range []bool{true, false} {
		if _, err := pool.Exec(ctx, "UPDATE stream_expiry_switch SET enabled = $1, changed_at = now(), changed_by = 'test'", want); err != nil {
			t.Fatalf("스위치 UPDATE: %v", err)
		}
		if on, err := ReadExpirySwitch(ctx, pool); on != want || err != nil {
			t.Fatalf("UPDATE %t 뒤 ReadExpirySwitch = (%t, %v)", want, on, err)
		}
	}
}

// 스키마 재실행(기동 EnsureSchema · 운영 SQL 재실행)은 켠 값을 되돌리지 않고 행을 늘리지 않는다. 한 행 CHECK 가 둘째
// 행을 거부한다.
func TestExpirySwitchSchemaRerunKeepsValueAndOneRow(t *testing.T) {
	pool := newSwitchPool(t)
	ctx := context.Background()
	switchExec(t, pool, "UPDATE stream_expiry_switch SET enabled = true")

	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("EnsureSchema 재실행: %v", err)
	}

	if on, err := ReadExpirySwitch(ctx, pool); !on || err != nil {
		t.Fatalf("재실행 뒤 ReadExpirySwitch = (%t, %v), want (true, nil)", on, err)
	}
	if n := switchRows(t, pool); n != 1 {
		t.Fatalf("재실행 뒤 행 %d (1 기대)", n)
	}
	if _, err := pool.Exec(ctx, "INSERT INTO stream_expiry_switch (id) VALUES (false)"); !isSQLState(err, "23514") {
		t.Fatalf("둘째 행 INSERT 오류 = %v, want 23514(한 행 CHECK)", err)
	}
}

// P5 — 읽지 못하면 끔과 오류다. 켜 둔 상태에서 실패를 일으켜 「켬으로 읽지 않는다」 를 본다(행 없음은 값이 없다).
func TestReadExpirySwitchFailsClosed(t *testing.T) {
	ctx := context.Background()
	tests := []struct {
		name string
		read func(t *testing.T, pool *pgxpool.Pool) (bool, error)
		want func(err error) bool
	}{
		{"행 없음", func(t *testing.T, pool *pgxpool.Pool) (bool, error) {
			switchExec(t, pool, "DELETE FROM stream_expiry_switch")
			return ReadExpirySwitch(ctx, pool)
		}, func(err error) bool { return errors.Is(err, errExpirySwitchMissing) }},
		{"표 없음 42P01", func(t *testing.T, pool *pgxpool.Pool) (bool, error) {
			switchExec(t, pool, "DROP TABLE stream_expiry_switch")
			return ReadExpirySwitch(ctx, pool)
		}, func(err error) bool { return isSQLState(err, "42P01") }},
		{"권한 없음 42501", func(t *testing.T, pool *pgxpool.Pool) (bool, error) {
			role := pgtest.NewMediaIndexerRole(t, pool)
			role.Revoke(t, "SELECT", "stream_expiry_switch")
			on, err := ReadExpirySwitch(ctx, role.Pool)
			role.RequireDenied(t, "stream_expiry_switch SELECT", expirySwitchSQL)
			return on, err
		}, pgtest.IsInsufficientPrivilege},
		{"시간 초과", func(t *testing.T, pool *pgxpool.Pool) (bool, error) {
			lock, err := pool.Begin(ctx)
			if err != nil {
				t.Fatalf("잠금 트랜잭션: %v", err)
			}
			defer func() { _ = lock.Rollback(ctx) }()
			if _, err := lock.Exec(ctx, "LOCK TABLE stream_expiry_switch IN ACCESS EXCLUSIVE MODE"); err != nil {
				t.Fatalf("LOCK: %v", err)
			}
			short, cancel := context.WithTimeout(ctx, 300*time.Millisecond)
			defer cancel()
			start := time.Now()
			on, err := ReadExpirySwitch(short, pool)
			if waited := time.Since(start); waited > 5*time.Second {
				t.Fatalf("시한 300ms 인데 %v 기다렸다", waited)
			}
			return on, err
		}, func(err error) bool { return errors.Is(err, context.DeadlineExceeded) }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newSwitchPool(t)
			switchExec(t, pool, "UPDATE stream_expiry_switch SET enabled = true")

			on, err := tt.read(t, pool)

			if on || !tt.want(err) {
				t.Fatalf("ReadExpirySwitch = (%t, %v), want (false, 그 갈래 오류)", on, err)
			}
		})
	}
}

// 사이드카 계정(media_indexer 목록)으로 읽힌다 — 30_grants.sql 의 SELECT 하나로 충분하다.
func TestReadExpirySwitchRunsUnderMediaIndexerRole(t *testing.T) {
	pool := newSwitchPool(t)
	role := pgtest.NewMediaIndexerRole(t, pool)
	switchExec(t, pool, "UPDATE stream_expiry_switch SET enabled = true")

	on, err := ReadExpirySwitch(context.Background(), role.Pool)

	if !on || err != nil {
		t.Fatalf("역할 풀 ReadExpirySwitch = (%t, %v), want (true, nil)", on, err)
	}
	role.RequireSent(t, map[string]string{"expirySwitchSQL": expirySwitchSQL})
	if _, err := role.Pool.Exec(context.Background(), "UPDATE stream_expiry_switch SET enabled = false"); !pgtest.IsInsufficientPrivilege(err) {
		t.Fatalf("역할 풀 UPDATE 오류 = %v, want 42501(사이드카는 스위치를 바꾸지 못한다)", err)
	}
}
