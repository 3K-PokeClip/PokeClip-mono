package cache_test

// 부팅 재구성을 실 PG 로 잰다(계획 PR ⓑ 검증 「PG 통합 — 부팅 재구성」 · 6.3 #34). 장부에 남은 상태를
// index.LoadRewindLedger 로 읽어 Reload 한 캐시가 세션 축(init 게이트 · state · TD 와 나머지 다섯 열)을
// 되찾고 목록을 고를 수 있는지 본다. SQL 자체의 행·열 대조는 index 의 rewind_read_pg_test.go 가 잰다.
//
// PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"fmt"
	"os"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
)

// TestMain 은 릴리스 게이트용 스위치다(index/testdb_test.go 와 같은 이디엄). PG 케이스는 PG_DSN 이
// 없으면 skip 되는데 skip 은 성공으로 집계된다 — REQUIRE_PG=1 이면 그 상황을 실패로 바꾼다.
func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr,
			"REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — 되감기 캐시 PG 통합 케이스가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

// newReloadPool 은 이 패키지 전용 테스트 DB 에 붙은 풀이다. 접미 rewindcache 로 가른다 — 패키지 테스트
// 바이너리끼리 병렬로 돌아도 서로의 행을 비우지 않는다(pgtest.Pool).
func newReloadPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "rewindcache", index.EnsureSchema, func(ctx context.Context, pool *pgxpool.Pool) error {
		_, err := pool.Exec(ctx, "TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions")
		return err
	})
}

// exec 는 픽스처 SQL 한 문장이다.
func exec(t *testing.T, pool *pgxpool.Pool, sql string, args ...any) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), sql, args...); err != nil {
		t.Fatalf("픽스처 실패: %v\n%s", err, sql)
	}
}

// cache_reload_restores_session_axis(계획 6.3 #34) — 재기동한 프로세스의 캐시는 부팅 재구성만으로 세션 축을
// 되찾는다: init 게이트(P 확정 · S 미확정) · state(P ended · S live) · TD(P 6 · S 7)와 종료 사유 · base ·
// 계승 · first_pdt · MinSeq. 세션 축을 싣지 않으면 발행 층이 init 게이트를 캐시에서만 읽으므로(조회 0)
// 미확정으로 두면 재발행이 멈추고 확정으로 두면 G5 가 근거 없이 통과한다(계획 2.3 ⑸ⓕ⑵).
//
// 장부 이력: 회차 O 는 seq 19 의 7.6초 조각으로 TD 분할이 열었다(base 는 직전 회차 복사 · TD 8). O 가
// 끝나고 2분 안에 P 가 O 를 계승해 seq 20 에서 열려 컷오프를 주조했고 20..22 를 쓴 뒤 오프라인으로
// 끝났다. 120초 뒤 S 가 P 를 계승해 23 에서 열렸다(첫 조각 6.6초 → TD 7). O 의 행은 컷오프 아래라 O 는
// 싣지 않는다 — 계승(inherits_session)은 참조가 아니다. base 는 셋 다 0 이다 — 컷오프 전에는 목록이
// 없어 O 와 그 앞 회차의 base 가 오른 적이 없고, P 의 목록은 S 가 열릴 때까지 표시를 내보낸 적이 없다.
// 그래서 이 테스트는 base 칸을 가르지 않는다(0 이 아닌 base 는 TestCacheReceivesInheritsOnOpen 이 잰다).
func TestCacheReloadRestoresSessionAxis(t *testing.T) {
	pool := newReloadPool(t)
	const sessionSQL = `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, init_uploaded_at,
		                             discontinuity_base, first_pdt, inherits_session, target_duration)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $3, $8, $9)`
	exec(t, pool, sessionSQL, "O", stream, at(-2*time.Minute), "ended", "offline", at(-2*time.Minute), 0, nil, 8)
	exec(t, pool, sessionSQL, "P", stream, at(0), "ended", "offline", at(time.Second), 0, "O", 6)
	exec(t, pool, sessionSQL, "S", stream, at(132*time.Second), "live", nil, nil, 0, "P", 7)
	for _, r := range []struct {
		seq     int64
		wall    time.Duration
		dur     int32
		session string
	}{
		{19, -2 * time.Minute, 7600, "O"},
		{20, 0, 4000, "P"}, {21, 4 * time.Second, 4000, "P"}, {22, 8 * time.Second, 4000, "P"},
		{23, 132 * time.Second, 6600, "S"}, {24, 138600 * time.Millisecond, 4000, "S"},
	} {
		exec(t, pool, `
			INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
			                             bytes, session_id, playback_pdt, playback_s3_key,
			                             playback_upload_state, playback_uploaded_at, playback_bytes)
			VALUES ($1, $2, $9, $3, $4, $5, $6, 1000, $7, $3, $8, 'uploaded', now(), 900)`,
			stream, r.seq, at(r.wall), r.dur, "s3/"+key(r.seq), "/recordings/"+key(r.seq), r.session, key(r.seq), r.seq*4000)
	}
	exec(t, pool, `INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
	               VALUES ($1, 20, 'live_ingress', 'watcher')`, stream)

	ledger, err := index.LoadRewindLedger(context.Background(), pool, stream)
	if err != nil {
		t.Fatalf("LoadRewindLedger 실패: %v", err)
	}
	c := &cache.Cache{}
	c.Reload(stream, ledger)

	for _, want := range []cache.Session{
		{MinSeq: 20, RewindSession: index.RewindSession{SessionID: "P", State: "ended", EndReason: "offline", InitUploaded: true,
			FirstPDT: at(0), InheritsSession: "O", TargetDuration: 6}},
		{MinSeq: 23, RewindSession: index.RewindSession{SessionID: "S", State: "live",
			FirstPDT: at(132 * time.Second), InheritsSession: "P", TargetDuration: 7}},
	} {
		if got, ok := c.Session(stream, want.SessionID); !ok || !reflect.DeepEqual(got, want) {
			t.Errorf("Session(%s) = (%+v, %v), want (%+v, true)", want.SessionID, got, ok, want)
		}
	}
	if _, ok := c.Session(stream, "O"); ok {
		t.Error("적재한 행이 참조하지 않는 회차 O 가 실렸다")
	}
	pl, ok := c.Playlist(stream, "S", window(t, c))
	if want := []string{"P:20", "P:21", "P:22", "S:23", "S:24"}; !ok || !reflect.DeepEqual(playlistRows(pl), want) {
		t.Errorf("재구성 뒤 Playlist(S) = (%v, %v), want (%v, true)", playlistRows(pl), ok, want)
	}
}
