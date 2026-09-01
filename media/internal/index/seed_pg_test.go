package index

// 컷오프 주조의 PG 통합 검증(POK-168 M2 — 설계 9.1 n1a~c · d1·d4·d5 · t1·t2 · m1a~c · c1).
// PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// seedStream 은 테스트별 유일한 스트림 이름을 만든다 — 전용 DB 라도 케이스 간 간섭을 없앤다.
func seedStream(name string) string { return "seedtest-" + name }

// openSession 은 stream_sessions 에 세션 행을 만든다 — FK(NOT VALID)가 새 행에는 강제되므로
// carrier 에 실을 session_id 는 실재해야 한다.
func openSession(t *testing.T, pool *pgxpool.Pool, sessionID, streamID string) {
	t.Helper()
	_, err := pool.Exec(context.Background(),
		`INSERT INTO stream_sessions (session_id, stream_id, started_at) VALUES ($1, $2, now())`,
		sessionID, streamID)
	if err != nil {
		t.Fatalf("세션 개설 실패: %v", err)
	}
}

// qualified 는 ⓒ(시작점 자격)를 충족하는 carrier 3종을 record 에 싣는다.
func qualified(rec Record, sessionID string) Record {
	pdt := rec.StartWallUTC
	pkey := "playback/" + rec.S3Key
	rec.SessionID = &sessionID
	rec.PlaybackPDT = &pdt
	rec.PlaybackS3Key = &pkey
	return rec
}

func eligibleSeed(anchor time.Time) Seed {
	return Seed{
		Eligible:  true,
		Reason:    SeedReasonLiveIngress,
		Channel:   SeedChannelWatcher,
		AnchorUTC: anchor,
		Freshness: 60 * time.Second,
	}
}

func cutoffRow(t *testing.T, pool *pgxpool.Pool, streamID string) (seq int64, reason, channel string, ok bool) {
	t.Helper()
	err := pool.QueryRow(context.Background(),
		`SELECT cutoff_seq, seed_reason, seed_channel FROM stream_cutoffs WHERE stream_id = $1`,
		streamID).Scan(&seq, &reason, &channel)
	if err != nil {
		if strings.Contains(err.Error(), "no rows") {
			return 0, "", "", false
		}
		t.Fatalf("컷오프 조회 실패: %v", err)
	}
	return seq, reason, channel, true
}

// n1a·n1b — carrier 전부 nil 인 INSERT 가 성공하고(23503 이 나지 않는다), 다시 읽으면
// 세 열이 전부 IS NULL 이다.
func TestCarrierNilInsertNullReadback(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := seedStream("n1ab")
	store := NewPGStore(pool)

	out, res, err := store.Insert(ctx, sampleRecord(stream, 0, "/recordings/"+stream+"/0.mp4"), Seed{})
	if err != nil || out != InsertInserted {
		t.Fatalf("nil carrier INSERT 실패: out=%v err=%v", out, err)
	}
	if res.Seeded || res.Decline != DeclineNoCorroboration {
		t.Fatalf("비적격 seed 귀속이 틀렸다: %+v", res)
	}

	var allNull bool
	err = pool.QueryRow(ctx, `SELECT session_id IS NULL AND playback_pdt IS NULL AND playback_s3_key IS NULL
	                            FROM stream_segments WHERE stream_id=$1 AND seq=0`, stream).Scan(&allNull)
	if err != nil || !allNull {
		t.Fatalf("carrier 열이 NULL 이 아니다: allNull=%v err=%v", allNull, err)
	}
}

// n1c — 대조군: session_id=” 를 직접 SQL 로 넣으면 NOT VALID FK 가 새 행에 강제돼 23503 이다.
// carrier 가 포인터인 이유가 이 함정이다(빈 문자열이 드라이버에 도달할 경로 차단).
func TestCarrierEmptyStringRejected(t *testing.T) {
	pool := newTestPool(t)
	stream := seedStream("n1c")

	_, err := pool.Exec(context.Background(), `
		INSERT INTO stream_segments
			(stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, upload_state, session_id)
		VALUES ($1, 0, 0, now(), 4000, 'k', 'pending', '')`, stream)
	if err == nil {
		t.Fatal("session_id='' INSERT 가 통과했다 — FK 강제가 없다(23503 기대)")
	}
	if !isSQLState(err, "23503") {
		t.Fatalf("기대한 23503 이 아니라 %v", err)
	}
}

// d1·t1 — 기존 행(seq=90)이 있어도 컷오프는 새 행(seq=100)으로 주조되고,
// 주조된 행은 세 열이 전부 NOT NULL 이며 reason·channel 이 그대로 기록된다.
func TestSeedMintsOnNewRow(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := seedStream("d1t1")
	store := NewPGStore(pool)
	sess := stream + "-s1"
	openSession(t, pool, sess, stream)

	// 기존 행 — 비적격(과거 잔존물 재현).
	if _, _, err := store.Insert(ctx, sampleRecord(stream, 90, "/recordings/"+stream+"/90.mp4"), Seed{}); err != nil {
		t.Fatal(err)
	}

	rec := qualified(sampleRecord(stream, 100, "/recordings/"+stream+"/100.mp4"), sess)
	rec.StartWallUTC = time.Now().UTC()
	_, res, err := store.Insert(ctx, rec, eligibleSeed(rec.StartWallUTC))
	if err != nil {
		t.Fatal(err)
	}
	if !res.Seeded {
		t.Fatalf("자격 전부 참인데 주조되지 않았다: %+v", res)
	}

	seq, reason, channel, ok := cutoffRow(t, pool, stream)
	if !ok || seq != 100 {
		t.Fatalf("컷오프가 새 행이 아니다: seq=%d ok=%v (100 기대 — 기존 90 이 아니라)", seq, ok)
	}
	if reason != "live_ingress" || channel != "watcher" {
		t.Fatalf("reason/channel 기록이 틀렸다: %s/%s", reason, channel)
	}

	var settleable bool
	err = pool.QueryRow(ctx, `SELECT session_id IS NOT NULL AND playback_pdt IS NOT NULL AND playback_s3_key IS NOT NULL
	                            FROM stream_segments WHERE stream_id=$1 AND seq=100`, stream).Scan(&settleable)
	if err != nil || !settleable {
		t.Fatalf("주조 행이 시작점 자격(t1)을 잃었다: %v %v", settleable, err)
	}
}

// t2 — carrier 가 비면 ⓐ 가 참이어도 주조하지 않고 not_settleable 로 귀속된다.
func TestSeedDeclinesNotSettleable(t *testing.T) {
	pool := newTestPool(t)
	stream := seedStream("t2")
	store := NewPGStore(pool)

	rec := sampleRecord(stream, 0, "/recordings/"+stream+"/0.mp4")
	rec.StartWallUTC = time.Now().UTC()
	_, res, err := store.Insert(context.Background(), rec, eligibleSeed(rec.StartWallUTC))
	if err != nil {
		t.Fatal(err)
	}
	if res.Seeded || res.Decline != DeclineNotSettleable {
		t.Fatalf("not_settleable 귀속 실패: %+v", res)
	}
	if _, _, _, ok := cutoffRow(t, pool, stream); ok {
		t.Fatal("carrier 없는 행이 주조됐다")
	}
}

// d5 — 기존 컷오프가 있으면 후발 자격 행은 승계(Seeded=false·existing_cutoff)이고 예외가 아니다.
func TestSeedExistingCutoffWins(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := seedStream("d5")
	store := NewPGStore(pool)
	sess := stream + "-s1"
	openSession(t, pool, sess, stream)

	first := qualified(sampleRecord(stream, 0, "/recordings/"+stream+"/0.mp4"), sess)
	first.StartWallUTC = time.Now().UTC()
	if _, res, err := store.Insert(ctx, first, eligibleSeed(first.StartWallUTC)); err != nil || !res.Seeded {
		t.Fatalf("선행 주조 실패: %+v %v", res, err)
	}

	second := qualified(sampleRecord(stream, 1, "/recordings/"+stream+"/1.mp4"), sess)
	second.StartWallUTC = time.Now().UTC()
	_, res, err := store.Insert(ctx, second, eligibleSeed(second.StartWallUTC))
	if err != nil {
		t.Fatalf("승계 경로가 에러다(예외가 아니어야 한다): %v", err)
	}
	if res.Seeded || res.Decline != DeclineSkipped {
		t.Fatalf("기존 승리 귀속 실패: %+v", res)
	}
	if seq, _, _, _ := cutoffRow(t, pool, stream); seq != 0 {
		t.Fatalf("기존 컷오프가 밀렸다: seq=%d (0 기대)", seq)
	}
}

// d4 — 세그먼트 INSERT 가 23505 로 실패하면 롤백으로 컷오프도 남지 않는다.
func TestSeedRollsBackWithConflict(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := seedStream("d4")
	store := NewPGStore(pool)
	sess := stream + "-s1"
	openSession(t, pool, sess, stream)

	if _, _, err := store.Insert(ctx, sampleRecord(stream, 0, "/recordings/"+stream+"/0.mp4"), Seed{}); err != nil {
		t.Fatal(err)
	}
	dup := qualified(sampleRecord(stream, 0, "/recordings/"+stream+"/other.mp4"), sess)
	dup.StartWallUTC = time.Now().UTC()
	out, res, err := store.Insert(ctx, dup, eligibleSeed(dup.StartWallUTC))
	if err != nil || out != InsertSeqConflict {
		t.Fatalf("PK 충돌 판정 실패: out=%v err=%v", out, err)
	}
	if res.Seeded {
		t.Fatal("충돌 트랜잭션의 컷오프가 살아남았다(롤백 실패)")
	}
	if _, _, _, ok := cutoffRow(t, pool, stream); ok {
		t.Fatal("롤백됐어야 할 컷오프 행이 존재한다(d4 위반)")
	}
}

// m1a(시간 항 단독형) — 방증이 낡으면(anchor 가 Freshness 를 넘김) 자격이 전부 참이어도
// clock_timestamp() 재검이 막고 stale_corroboration 으로 귀속된다.
func TestSeedStaleAnchorDeclines(t *testing.T) {
	pool := newTestPool(t)
	stream := seedStream("m1a-stale")
	store := NewPGStore(pool)
	sess := stream + "-s1"
	openSession(t, pool, sess, stream)

	rec := qualified(sampleRecord(stream, 0, "/recordings/"+stream+"/0.mp4"), sess)
	rec.StartWallUTC = time.Now().UTC()
	_, res, err := store.Insert(context.Background(), rec, eligibleSeed(time.Now().UTC().Add(-2*time.Minute)))
	if err != nil {
		t.Fatal(err)
	}
	if res.Seeded || res.Decline != DeclineStaleCorroboration {
		t.Fatalf("낡은 방증 귀속 실패: %+v", res)
	}
}

// c1 — ③ 체제 플래그 OFF(Eligible=false)의 구 동작: 자격 값이 전부 실려 있어도 주조가 없다.
func TestSeedFlagOffOldBehavior(t *testing.T) {
	pool := newTestPool(t)
	stream := seedStream("c1")
	store := NewPGStore(pool)
	sess := stream + "-s1"
	openSession(t, pool, sess, stream)

	rec := qualified(sampleRecord(stream, 0, "/recordings/"+stream+"/0.mp4"), sess)
	rec.StartWallUTC = time.Now().UTC()
	_, res, err := store.Insert(context.Background(), rec, Seed{Eligible: false})
	if err != nil {
		t.Fatal(err)
	}
	if res.Seeded || res.Decline != DeclineNoCorroboration {
		t.Fatalf("OFF 구 동작 위반: %+v", res)
	}
	if _, _, _, ok := cutoffRow(t, pool, stream); ok {
		t.Fatal("OFF 인데 주조됐다(c1 위반)")
	}
}

// 6-1 (d) 불변 트리거 — 발행 축은 NULL→값 1회만 허용하고 재변경을 막는다.
func TestImmutableAxesTrigger(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := seedStream("immutable")
	store := NewPGStore(pool)
	sess := stream + "-s1"
	openSession(t, pool, sess, stream)

	if _, _, err := store.Insert(ctx, sampleRecord(stream, 0, "/recordings/"+stream+"/0.mp4"), Seed{}); err != nil {
		t.Fatal(err)
	}
	// NULL → 값 1회 채움은 허용.
	if _, err := pool.Exec(ctx,
		`UPDATE stream_segments SET session_id=$2 WHERE stream_id=$1 AND seq=0`, stream, sess); err != nil {
		t.Fatalf("최초 1회 채움이 막혔다: %v", err)
	}
	// 값 → 다른 값은 금지. 두 번째 세션은 ended 로 연다 — one_live_uq(스트림당 live 1)가
	// 두 번째 live 를 막는 것 자체가 설계 의도라 이 테스트의 관심사가 아니다.
	other := sess + "-other"
	if _, err := pool.Exec(ctx, `INSERT INTO stream_sessions
			(session_id, stream_id, started_at, state, ended_at)
			VALUES ($1, $2, now(), 'ended', now())`, other, stream); err != nil {
		t.Fatalf("ended 세션 개설 실패: %v", err)
	}
	if _, err := pool.Exec(ctx,
		`UPDATE stream_segments SET session_id=$2 WHERE stream_id=$1 AND seq=0`, stream, other); err == nil {
		t.Fatal("session_id 재변경이 통과했다 — 불변 트리거 부재")
	}
}

// m1a·m1b·m1c — 락 경합 3형제. blocker 가 같은 (stream_id, seq) 를 먼저 잡고 있는 동안의
// 판정을 잰다: 신선하면 정상 주조(m1c), 대기 중 방증이 낡으면 seeded=0(m1a),
// 5초 상한 초과는 55P03 → 즉시 1회 재시도 → 그래도 락이면 ErrLockContended(m1b).
func TestSeedLockContention(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	store := NewPGStore(pool)

	// holdRow 는 별도 트랜잭션이 (stream, seq=0) PK 를 hold 동안 잡았다 ROLLBACK 한다.
	holdRow := func(t *testing.T, stream string, hold time.Duration) *sync.WaitGroup {
		t.Helper()
		var wg sync.WaitGroup
		held := make(chan struct{})
		wg.Add(1)
		go func() {
			defer wg.Done()
			tx, err := pool.Begin(ctx)
			if err != nil {
				t.Errorf("blocker 시작 실패: %v", err)
				close(held)
				return
			}
			defer func() { _ = tx.Rollback(ctx) }()
			if _, err := tx.Exec(ctx, `
				INSERT INTO stream_segments
					(stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, upload_state)
				VALUES ($1, 0, 0, now(), 4000, $2, 'pending')`,
				stream, fmt.Sprintf("blocker/%s", stream)); err != nil {
				t.Errorf("blocker INSERT 실패: %v", err)
			}
			close(held)
			time.Sleep(hold)
		}()
		<-held
		return &wg
	}

	t.Run("m1c_fresh_passes", func(t *testing.T) {
		stream := seedStream("m1c")
		sess := stream + "-s1"
		openSession(t, pool, sess, stream)
		wg := holdRow(t, stream, 1500*time.Millisecond)
		defer wg.Wait()

		rec := qualified(sampleRecord(stream, 0, "/recordings/"+stream+"/0.mp4"), sess)
		rec.StartWallUTC = time.Now().UTC()
		_, res, err := store.Insert(ctx, rec, eligibleSeed(time.Now().UTC()))
		if err != nil {
			t.Fatalf("신선 경합 경로가 에러다: %v", err)
		}
		if !res.Seeded {
			t.Fatalf("신선한데 주조되지 않았다(오발동): %+v", res)
		}
	})

	t.Run("m1a_lock_wait_stales", func(t *testing.T) {
		stream := seedStream("m1a")
		sess := stream + "-s1"
		openSession(t, pool, sess, stream)
		wg := holdRow(t, stream, 3200*time.Millisecond)
		defer wg.Wait()

		// 발사 시점엔 신선(58초 경과 < 60초)이지만 3.2초 락 대기 뒤에는 낡는다 —
		// clock_timestamp() 재검이 그 차이를 잡는다.
		rec := qualified(sampleRecord(stream, 0, "/recordings/"+stream+"/0.mp4"), sess)
		rec.StartWallUTC = time.Now().UTC()
		_, res, err := store.Insert(ctx, rec, eligibleSeed(time.Now().UTC().Add(-58*time.Second)))
		if err != nil {
			t.Fatalf("INSERT 는 성공해야 한다(주조만 거절): %v", err)
		}
		if res.Seeded {
			t.Fatal("락 대기로 낡은 방증이 주조됐다(m1a 위반)")
		}
		if res.Decline != DeclineStaleCorroboration {
			t.Fatalf("귀속이 stale_corroboration 이 아니다: %+v", res)
		}
	})

	t.Run("m1b_lock_timeout", func(t *testing.T) {
		stream := seedStream("m1b")
		sess := stream + "-s1"
		openSession(t, pool, sess, stream)
		wg := holdRow(t, stream, 12*time.Second)
		defer wg.Wait()

		rec := qualified(sampleRecord(stream, 0, "/recordings/"+stream+"/0.mp4"), sess)
		rec.StartWallUTC = time.Now().UTC()
		_, _, err := store.Insert(ctx, rec, eligibleSeed(time.Now().UTC()))
		if !errors.Is(err, ErrLockContended) {
			t.Fatalf("55P03 이중 초과가 ErrLockContended 가 아니다: %v", err)
		}
		if _, _, _, ok := cutoffRow(t, pool, stream); ok {
			t.Fatal("경합 실패 경로에 컷오프가 남았다")
		}
	})
}
