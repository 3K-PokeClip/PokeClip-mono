package main

// 발행 조립의 PG 시험(체크리스트 474 B-1 7 · 판단 J74 · J85 ⑵) — run 이 조립한 발행 몫이 전용 테스트 DB 에 붙는다. 조립한 발행자
// 풀에는 추적기를 끼울 자리가 없어(운영 코드에 시험용 이음매를 두지 않는다) 문장 대신 DB 상태로 본다.
//
// PG_DSN 이 없으면 skip 이다(REQUIRE_PG=1 이면 TestMain 이 실패로 바꾼다 — session_wire_test.go).

import (
	"context"
	"net/http"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgconf"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// testPoolConfig 는 db 가 붙은 전용 테스트 DB 의 풀 설정이다 — run 과 같이 POSTGRES_* 를 pgconf 로 지나 만들고(DB 이름만 전용
// 테스트 DB), 공용 DB 로 새지 않게 이름을 다시 확인한다.
func testPoolConfig(t *testing.T, db *pgxpool.Pool) *pgxpool.Config {
	t.Helper()
	name := db.Config().ConnConfig.Database
	env := pgtest.PostgresEnv(t, name)
	s, err := pgconf.FromEnv(func(k string) string { return env[k] })
	if err != nil {
		t.Fatalf("POSTGRES_* 설정 실패: %v", err)
	}
	pc, err := pgconf.PoolConfig(t.Context(), s)
	if err != nil || pc.ConnConfig.Database != name {
		t.Fatalf("전용 테스트 DB %q 의 풀 설정을 만들지 못했다: %v", name, err)
	}
	return pc
}

// execSQL 은 픽스처 문장 하나다.
func execSQL(t *testing.T, db *pgxpool.Pool, sql string, args ...any) {
	t.Helper()
	if _, err := db.Exec(t.Context(), sql, args...); err != nil {
		t.Fatalf("픽스처 실패: %v\n%s", err, sql)
	}
}

// 종료 순서 — 조립 경로(체크리스트 474 B-1 7 · A-3 3 · 판단 J74 · 계획 커밋 순서 7 〔r52〕). run 이 조립한 발행 몫을 run 이 defer
// 로 부르는 종료(rewindLoop.stop)로 멈춘다. 차선에는 끊긴 뒤에도 50ms 더 돌다가 늦은 문장(이 프로세스 토큰으로 fence 를 다시 쥐는
// UPDATE)을 내는 작업이 떠 있다.
//
//	순서가 맞다       취소 → 늦은 문장 → 작업 반환 → 반납(끊기지 않은 ctx) — 끝에 이 토큰의 fence 가 모두 빈다
//	기다리지 않는다    반납이 늦은 문장보다 먼저라 그 회차의 fence 가 남는다
//	끊긴 ctx 로 반납  반납 문장이 나가지 않아 두 회차의 fence 가 모두 남는다
func TestRewindStopReleasesAfterLateLaneStatement(t *testing.T) {
	db := newWireTestPool(t)
	assemblyCredentialsEnv(t)
	s3 := newFakeS3(t, http.StatusOK)
	cfg := assemblyCfg(t, s3.srv.URL)
	a := newAssembly(t, cfg, &fakeStore{})
	a.db = testPoolConfig(t, db)
	a.assembleOrFatal(t, cfg)
	writer, _ := a.logs.attrs("rewind_publish_started")["writer"].(string)
	execSQL(t, db, `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, writer_fence, fence_expires_at)
		VALUES ('S-held', 'st-held', now(), 'live', $1, now() + interval '5 seconds'),
		       ('S-late', 'st-late', now(), 'live', NULL, NULL)`, writer)

	laneCtx, cancelLanes := context.WithCancel(t.Context())
	started, late := make(chan struct{}), make(chan error, 1)
	a.rewind.lanes.Start(laneCtx, "st-late", func(ctx context.Context) publish.Result {
		close(started)
		<-ctx.Done()
		time.Sleep(50 * time.Millisecond) // 끊긴 뒤에도 잠시 도는 작업
		_, err := db.Exec(context.WithoutCancel(ctx), `
			UPDATE stream_sessions SET writer_fence = $1, fence_expires_at = now() + interval '5 seconds'
			 WHERE session_id = 'S-late'`, writer)
		late <- err
		return publish.Result{Kind: publish.KindPublish}
	})
	<-started
	runCtx, stop := context.WithCancel(t.Context())
	stop() // 종료 신호로 끝난 run ctx

	a.rewind.stop(runCtx, cancelLanes)

	if err := <-late; err != nil {
		t.Fatalf("늦은 문장 실패: %v", err)
	}
	var held int
	if err := db.QueryRow(t.Context(), `SELECT count(*) FROM stream_sessions WHERE writer_fence = $1`, writer).Scan(&held); err != nil {
		t.Fatalf("fence 조회 실패: %v", err)
	}
	if held != 0 {
		t.Errorf("종료 뒤 이 토큰의 fence %d개, want 0 — 반납이 작업을 기다리지 않았거나 끊긴 ctx 로 나갔다", held)
	}
	if n := a.logs.count("rewind_fence_release_failed"); n != 0 {
		t.Errorf("rewind_fence_release_failed = %d줄, want 0: %v", n, a.logs.attrs("rewind_fence_release_failed"))
	}
}

// 판단 J85 ⑵ — 조립이 SEED_ALARM_AFTER 를 max(10분, 2 × RescanEvery) 로 둔다. 컷오프 없는 스트림 둘의 마지막 조각을 창 안 ·
// 밖에 두고 조립한 발행자의 감시가 안쪽 하나만 (c) 부재 후보로 잡는지 본다.
func TestAssembledWatchUsesSeedAlarmWindow(t *testing.T) {
	for _, c := range []struct {
		name          string
		rescanEvery   time.Duration
		inside, outer string // 마지막 조각이 몇 분 전인가(interval 문자열)
	}{
		{name: "두_배가_10분보다_길면_두_배", rescanEvery: 7 * time.Minute, inside: "12 minutes", outer: "16 minutes"},
		{name: "두_배가_짧으면_10분", rescanEvery: 3 * time.Minute, inside: "8 minutes", outer: "12 minutes"},
	} {
		t.Run(c.name, func(t *testing.T) {
			db := newWireTestPool(t)
			assemblyCredentialsEnv(t)
			s3 := newFakeS3(t, http.StatusOK)
			cfg := assemblyCfg(t, s3.srv.URL)
			cfg.Watcher.RescanEvery = c.rescanEvery
			a := newAssembly(t, cfg, &fakeStore{})
			a.db = testPoolConfig(t, db)
			a.assembleOrFatal(t, cfg)
			for stream, ago := range map[string]string{"nocut-inside": c.inside, "nocut-outer": c.outer} {
				execSQL(t, db, `
					INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key)
					VALUES ($1, 0, 0, now() - $2::interval, 4000, $1 || '/0.mp4')`, stream, ago)
			}

			out := a.rewind.work.watch(t.Context(), nil)

			if out.Err != nil {
				t.Fatalf("감시 실패: %v", out.Err)
			}
			var got []string
			for _, ab := range out.Absent {
				got = append(got, ab.StreamID)
			}
			if !slices.Equal(got, []string{"nocut-inside"}) {
				t.Errorf("RescanEvery %v 의 (c) 부재 후보 = %v, want [nocut-inside]", c.rescanEvery, got)
			}
		})
	}
}
