package index

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// ③(재생 렌디션) 축의 CAS 2문장과 init 불일치 결속 — 설계 5.5.5 · 5.3ⓒ.

// openLiveSession 은 live 세션 1행을 연다. seed_pg_test.go 의 openSession 과 달리
// **state·first_pdt 를 명시**한다 — ③ 축의 판정은 state 에 걸려 있다(first_pdt 는 ③ 판정에서
// 빠졌고, 실제 개시 행처럼 채워 둘 뿐이다).
func openLiveSession(t *testing.T, pool *pgxpool.Pool, sessionID, streamID string) {
	t.Helper()
	_, err := pool.Exec(context.Background(),
		`INSERT INTO stream_sessions (session_id, stream_id, started_at, state, first_pdt)
		 VALUES ($1, $2, now(), 'live', now())`, sessionID, streamID)
	if err != nil {
		t.Fatalf("세션 개시 픽스처 실패 %s: %v", sessionID, err)
	}
}

// seedSessionSegment 는 세션에 귀속된 조각 1행을 넣는다.
// init_mismatch 결속은 session_id 를 앵커로 쓰고 ③ 조회는 세션을 조인하므로 ② 픽스처(seed)로는 잴 수 없다.
func seedSessionSegment(t *testing.T, pool *pgxpool.Pool, streamID, sessionID string, seq int64) {
	t.Helper()
	_, err := pool.Exec(context.Background(), `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
     upload_state, bytes, is_discontinuity, session_id, playback_pdt, playback_s3_key)
VALUES ($1, $2, $3, now() - interval '10 minutes', 4000, $4, $5,
        'uploaded', 1000, false, $6, now() - interval '10 minutes', $7)`,
		streamID, seq, seq*4000,
		S3Key(streamID, seq, time.Now().UTC()),
		fmt.Sprintf("/recordings/%s/seg_%06d.mp4", streamID, seq), sessionID,
		fmt.Sprintf("dvr/%s/seg/%06d.m4s", streamID, seq))
	if err != nil {
		t.Fatalf("세션 귀속 조각 삽입 실패 %s/%d: %v", streamID, seq, err)
	}
}

func playbackStateOf(t *testing.T, pool *pgxpool.Pool, streamID string, seq int64) (state string, uploadedAt bool, bytes *int64) {
	t.Helper()
	var at *time.Time
	err := pool.QueryRow(context.Background(),
		`SELECT playback_upload_state, playback_uploaded_at, playback_bytes
		   FROM stream_segments WHERE stream_id=$1 AND seq=$2`, streamID, seq).
		Scan(&state, &at, &bytes)
	if err != nil {
		t.Fatalf("③ 상태 조회 실패: %v", err)
	}
	return state, at != nil, bytes
}

func sessionStateOf(t *testing.T, pool *pgxpool.Pool, sessionID string) (state string, reason *string) {
	t.Helper()
	err := pool.QueryRow(context.Background(),
		`SELECT state, end_reason FROM stream_sessions WHERE session_id=$1`, sessionID).
		Scan(&state, &reason)
	if err != nil {
		t.Fatalf("세션 상태 조회 실패: %v", err)
	}
	return state, reason
}

func TestMarkPlaybackUploadedWritesBytesAsSetNotAnchor(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	openLiveSession(t, pool, "S-pb", "pbstream")
	seedSessionSegment(t, pool, "pbstream", "S-pb", 0)

	// 계약 ⓒ — ② 는 bytes 를 WHERE 앵커로 쓰지만 ③ 은 SET 이다.
	// 앵커로 쓰면 ③ 산출 크기가 장부의 ② 크기와 달라 CAS 가 영원히 실패한다.
	marked, err := st.MarkPlaybackUploaded(ctx, "pbstream", 0, 777)
	if err != nil {
		t.Fatalf("MarkPlaybackUploaded 실패: %v", err)
	}

	if !marked {
		t.Fatal("marked = false, want true")
	}
	state, at, bytes := playbackStateOf(t, pool, "pbstream", 0)
	if state != "uploaded" || !at {
		t.Errorf("(state, uploaded_at) = (%q, %v), want (uploaded, true)", state, at)
	}
	if bytes == nil || *bytes != 777 {
		t.Errorf("playback_bytes = %v, want 777", bytes)
	}
}

func TestMarkPlaybackUploadedDoesNotTouchArchiveColumns(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-iso", "isostream")
	seedSessionSegment(t, pool, "isostream", "S-iso", 0)

	if _, err := st.MarkPlaybackUploaded(context.Background(), "isostream", 0, 777); err != nil {
		t.Fatalf("MarkPlaybackUploaded 실패: %v", err)
	}

	// ② 열은 픽스처가 넣은 값 그대로여야 한다 — ③ 결과가 아카이브 열로 새면 3번의 소비자가 깨진다.
	var uploadState string
	var bytes int64
	if err := pool.QueryRow(context.Background(),
		`SELECT upload_state, bytes FROM stream_segments WHERE stream_id=$1 AND seq=0`,
		"isostream").Scan(&uploadState, &bytes); err != nil {
		t.Fatalf("② 열 조회 실패: %v", err)
	}
	if uploadState != "uploaded" || bytes != 1000 {
		t.Errorf("(upload_state, bytes) = (%q, %d), want (uploaded, 1000) — ③ 이 ② 열을 바꿨다",
			uploadState, bytes)
	}
}

func TestMarkPlaybackUploadedRefusesAlreadyUploadedRow(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	openLiveSession(t, pool, "S-dup", "dupstream")
	seedSessionSegment(t, pool, "dupstream", "S-dup", 0)

	if _, err := st.MarkPlaybackUploaded(ctx, "dupstream", 0, 777); err != nil {
		t.Fatalf("첫 확정 실패: %v", err)
	}
	marked, err := st.MarkPlaybackUploaded(ctx, "dupstream", 0, 888)
	if err != nil {
		t.Fatalf("둘째 확정 실패: %v", err)
	}

	if marked {
		t.Fatal("marked = true, want false — IN ('pending','failed') 가드가 되돌림을 막는다")
	}
}

func TestMarkPlaybackUploadedConfirmsRowThatFailedBefore(t *testing.T) {
	// failed 는 종국 상태가 아니다 — 스위퍼가 다시 집어 성공하면 uploaded 로 확정돼야 한다.
	// 가드가 pending 만 받으면 한 번 실패한 조각은 영구히 GAP 이 된다(되감기 접두가 거기서 멈춘다).
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	openLiveSession(t, pool, "S-retry", "retrystream")
	seedSessionSegment(t, pool, "retrystream", "S-retry", 0)
	if _, err := st.MarkPlaybackFailed(ctx, "retrystream", 0, "S-retry", "put_failed"); err != nil {
		t.Fatalf("실패 확정 실패: %v", err)
	}

	marked, err := st.MarkPlaybackUploaded(ctx, "retrystream", 0, 555)
	if err != nil {
		t.Fatalf("MarkPlaybackUploaded 실패: %v", err)
	}

	if !marked {
		t.Fatal("marked = false, want true — failed 에서 재시도 성공이 확정되지 않았다")
	}
	if state, at, bytes := playbackStateOf(t, pool, "retrystream", 0); state != "uploaded" || !at || bytes == nil || *bytes != 555 {
		t.Errorf("(state, uploaded_at, playback_bytes) = (%q, %v, %v), want (uploaded, true, 555)", state, at, bytes)
	}
}

func TestMarkPlaybackFailedKeepsSessionLiveForOrdinaryFailure(t *testing.T) {
	// 음성 대조 playback_put_failure_keeps_session_live —
	// PUT 일시 실패·브레이커·타임아웃은 세션을 끝내지 않는다. 끝내면 순단마다 방송이 쪼개진다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-put", "putstream")
	seedSessionSegment(t, pool, "putstream", "S-put", 0)

	marked, err := st.MarkPlaybackFailed(context.Background(), "putstream", 0, "S-put", "put_failed")
	if err != nil {
		t.Fatalf("MarkPlaybackFailed 실패: %v", err)
	}

	if !marked {
		t.Fatal("marked = false, want true")
	}
	if state, _, _ := playbackStateOf(t, pool, "putstream", 0); state != "failed" {
		t.Errorf("③ state = %q, want failed", state)
	}
	if state, reason := sessionStateOf(t, pool, "S-put"); state != "live" || reason != nil {
		t.Errorf("세션 = (%q, %v), want (live, nil) — 일시 실패가 세션을 끝냈다", state, reason)
	}
}

func TestMarkPlaybackFailedWithInitMismatchEndsSessionInOneTransaction(t *testing.T) {
	// 설계 5.3ⓒ — 조각 failed 와 세션 ending 이 한 tx 다. 메모리 이벤트에 의존하지 않으므로
	// 크래시·ⓐ 단독 배포에서도 상태가 유지된다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-mis", "misstream")
	seedSessionSegment(t, pool, "misstream", "S-mis", 3)

	marked, err := st.MarkPlaybackFailed(context.Background(), "misstream", 3, "S-mis", "init_mismatch")
	if err != nil {
		t.Fatalf("MarkPlaybackFailed 실패: %v", err)
	}

	if !marked {
		t.Fatal("marked = false, want true")
	}
	if state, _, _ := playbackStateOf(t, pool, "misstream", 3); state != "failed" {
		t.Errorf("③ state = %q, want failed", state)
	}
	state, reason := sessionStateOf(t, pool, "S-mis")
	if state != "ending" || reason == nil || *reason != "init_mismatch" {
		t.Errorf("세션 = (%q, %v), want (ending, init_mismatch)", state, reason)
	}
}

func TestMarkPlaybackFailedLeavesSessionAloneWhenSegmentCASFinds0Rows(t *testing.T) {
	// 관계 결속 — 조각 CAS 가 0행이면 세션도 건드리지 않는다.
	// 풀면 남의 세션(또는 이미 정산된 세션)이 불일치 하나로 끝난다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-zero", "zerostream")
	seedSessionSegment(t, pool, "zerostream", "S-zero", 0)

	// 다른 세션 id 로 부른다 → 앵커 불일치 → 0행.
	marked, err := st.MarkPlaybackFailed(context.Background(), "zerostream", 0, "S-남의세션", "init_mismatch")
	if err != nil {
		t.Fatalf("MarkPlaybackFailed 실패: %v", err)
	}

	if marked {
		t.Fatal("marked = true, want false")
	}
	if state, _ := sessionStateOf(t, pool, "S-zero"); state != "live" {
		t.Errorf("세션 state = %q, want live — 0행인데 세션이 전이했다", state)
	}
}

func TestMarkPlaybackFailedForOrdinaryReasonAnchorsOnSegmentOnly(t *testing.T) {
	// 설계 5.5.5 둘째 문장 — 일시 실패의 앵커는 (stream_id, seq)·상태 가드뿐이다. 세션 인자는
	// init_mismatch 결속에서만 앵커이며, 일시 실패 경로(브레이커·타임아웃)는 그것 없이도 기록된다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-ord", "ordstream")
	seedSessionSegment(t, pool, "ordstream", "S-ord", 0)

	marked, err := st.MarkPlaybackFailed(context.Background(), "ordstream", 0, "", "timeout")
	if err != nil {
		t.Fatalf("MarkPlaybackFailed 실패: %v", err)
	}

	if !marked {
		t.Fatal("marked = false, want true")
	}
	if state, _, _ := playbackStateOf(t, pool, "ordstream", 0); state != "failed" {
		t.Errorf("③ state = %q, want failed", state)
	}
}

func TestMarkPlaybackFailedWithInitMismatchRejectsEmptySession(t *testing.T) {
	// init_mismatch 는 조각이 속한 그 세션을 끝낸다 — 세션 앵커가 없으면 결속이 성립하지 않는다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-emp", "empstream")
	seedSessionSegment(t, pool, "empstream", "S-emp", 0)

	_, err := st.MarkPlaybackFailed(context.Background(), "empstream", 0, "", "init_mismatch")

	if err == nil {
		t.Fatal("세션 없는 init_mismatch 가 통과했다 — 앵커 없는 결속은 끝낼 세션을 모른다")
	}
	if state, _, _ := playbackStateOf(t, pool, "empstream", 0); state != "pending" {
		t.Errorf("③ state = %q, want pending — 거부된 호출이 장부를 바꿨다", state)
	}
	if state, _ := sessionStateOf(t, pool, "S-emp"); state != "live" {
		t.Errorf("세션 state = %q, want live", state)
	}
}

func TestMarkPlaybackFailedNeverRevertsUploadedRow(t *testing.T) {
	// IN ('pending','failed') 가드 — 이미 uploaded 인 ③ 이 늦게 도착한 실패로 되돌아가면
	// 되감기 목록의 연속 접두에 구멍이 생긴다(인덱스 불변식 1). 결속 갈래도 같은 가드다.
	for _, reason := range []string{"put_failed", "init_mismatch"} {
		t.Run(reason, func(t *testing.T) {
			pool := newTestPool(t)
			st := NewUploadStore(pool)
			ctx := context.Background()
			openLiveSession(t, pool, "S-up", "upstream")
			seedSessionSegment(t, pool, "upstream", "S-up", 0)
			if _, err := st.MarkPlaybackUploaded(ctx, "upstream", 0, 777); err != nil {
				t.Fatalf("③ 확정 실패: %v", err)
			}

			marked, err := st.MarkPlaybackFailed(ctx, "upstream", 0, "S-up", reason)
			if err != nil {
				t.Fatalf("MarkPlaybackFailed(%s) 실패: %v", reason, err)
			}

			if marked {
				t.Errorf("MarkPlaybackFailed(%s) marked = true, want false", reason)
			}
			if state, _, _ := playbackStateOf(t, pool, "upstream", 0); state != "uploaded" {
				t.Errorf("③ state = %q, want uploaded — 확정된 행이 되돌아갔다", state)
			}
			if state, _ := sessionStateOf(t, pool, "S-up"); state != "live" {
				t.Errorf("세션 state = %q, want live — 0행 CAS 가 세션을 끝냈다", state)
			}
		})
	}
}

// rejectInitMismatchEnding 은 세션 전이 절만 실패하게 만드는 테스트 전용 제약을 건다.
//
// 조각 CAS 는 통과하고 세션 UPDATE 만 23514 로 막히는 국면을 만드는 유일한 방법이다.
// 이름이 고정이라 중단된 이전 실행의 잔재도 먼저 지우고, 끝나면 반드시 지운다 —
// 남기면 이 DB 를 쓰는 다른 init_mismatch 케이스가 전부 깨진다.
func rejectInitMismatchEnding(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	ctx := context.Background()
	const drop = `ALTER TABLE stream_sessions DROP CONSTRAINT IF EXISTS itest_reject_init_mismatch_ending`
	if _, err := pool.Exec(ctx, drop); err != nil {
		t.Fatalf("잔재 제약 제거 실패: %v", err)
	}
	t.Cleanup(func() {
		if _, err := pool.Exec(context.Background(), drop); err != nil {
			t.Errorf("테스트 전용 제약 제거 실패 — 수동으로 지워야 한다: %v", err)
		}
	})
	if _, err := pool.Exec(ctx, `
ALTER TABLE stream_sessions ADD CONSTRAINT itest_reject_init_mismatch_ending
    CHECK (end_reason IS DISTINCT FROM 'init_mismatch') NOT VALID`); err != nil {
		t.Fatalf("테스트 전용 제약 추가 실패: %v", err)
	}
}

func TestMarkPlaybackFailedWithInitMismatchRollsBackSegmentWhenSessionTransitionFails(t *testing.T) {
	// 결속 = 한 트랜잭션 — 세션 전이가 실패하면 조각 CAS 도 남지 않는다. 둘이 따로 커밋되면
	// "③ 은 failed 인데 세션은 live" 가 장부에 굳어 다음 조각이 옛 MAP 세션에 계속 붙는다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-rb", "rbstream")
	seedSessionSegment(t, pool, "rbstream", "S-rb", 0)
	rejectInitMismatchEnding(t, pool)

	_, err := st.MarkPlaybackFailed(context.Background(), "rbstream", 0, "S-rb", "init_mismatch")

	if err == nil {
		t.Fatal("세션 전이가 막혔는데 오류가 없다")
	}
	if state, _, _ := playbackStateOf(t, pool, "rbstream", 0); state != "pending" {
		t.Errorf("③ state = %q, want pending — 세션 전이 실패인데 조각 CAS 만 남았다", state)
	}
	if state, reason := sessionStateOf(t, pool, "S-rb"); state != "live" || reason != nil {
		t.Errorf("세션 = (%q, %v), want (live, nil)", state, reason)
	}
}

func TestMarkPlaybackFailedWithInitMismatchKeepsReasonOfSessionAlreadyEnding(t *testing.T) {
	// state='live' 술어 — 이미 다른 사유로 끝나는 세션의 기록을 덮지 않는다(TD 분할의 종료 문장과
	// 같은 술어, 부기 21). 조각은 여전히 failed 로 확정된다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	openLiveSession(t, pool, "S-td", "tdendstream")
	seedSessionSegment(t, pool, "tdendstream", "S-td", 0)
	if _, err := pool.Exec(ctx, `
UPDATE stream_sessions SET state = 'ending', ending_at = now(), end_reason = 'td_exceeded'
 WHERE session_id = 'S-td'`); err != nil {
		t.Fatalf("세션 종료 픽스처 실패: %v", err)
	}

	marked, err := st.MarkPlaybackFailed(ctx, "tdendstream", 0, "S-td", "init_mismatch")
	if err != nil {
		t.Fatalf("MarkPlaybackFailed 실패: %v", err)
	}

	if !marked {
		t.Error("marked = false, want true — 조각 CAS 는 세션 상태와 무관하다")
	}
	if state, reason := sessionStateOf(t, pool, "S-td"); state != "ending" || reason == nil || *reason != "td_exceeded" {
		t.Errorf("세션 = (%q, %v), want (ending, td_exceeded) — 남의 종료 사유를 덮었다", state, reason)
	}
}

func TestInitMismatchEndsSessionAndNextSegmentOpensFreshSession(t *testing.T) {
	// 계획 테스트 init_mismatch_ends_session — 불일치 조각 1개가 GAP 이 되고(4초), 다음 유입이
	// 새 세션을 연다. 새 세션의 init 은 아직 없다(새 MAP 이 필요하다). 전이는 워커 트랜잭션이
	// 장부에 이미 영속했으므로 메모리 이벤트 없이도(ⓐ 단독·재기동 뒤) 같은 결과다.
	pool := newTestPool(t)
	store := newSessionStore(pool)
	up := NewUploadStore(pool)
	ctx := context.Background()
	stream := sessionStream("mismatch-next")
	_, first, err := store.Insert(ctx, segAt(stream, 0, sessionBase, 4000), Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("첫 Insert 실패: %v", err)
	}
	if _, err := up.MarkInitUploaded(ctx, first.SessionID, sha32(0x11), "k", 10, false); err != nil {
		t.Fatalf("첫 세션 init 확정 실패: %v", err)
	}
	if _, _, err := store.Insert(ctx, segAt(stream, 1, sessionBase.Add(4*time.Second), 4000), Seed{}, liveIngress()); err != nil {
		t.Fatalf("둘째 Insert 실패: %v", err)
	}

	if _, err := up.MarkPlaybackFailed(ctx, stream, 1, first.SessionID, "init_mismatch"); err != nil {
		t.Fatalf("MarkPlaybackFailed 실패: %v", err)
	}
	_, next, err := store.Insert(ctx, segAt(stream, 2, sessionBase.Add(8*time.Second), 4000), Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("셋째 Insert 실패: %v", err)
	}

	if !next.SessionOpened || next.SessionID == "" || next.SessionID == first.SessionID {
		t.Fatalf("(SessionOpened, SessionID) = (%v, %q), want (true, %q 가 아닌 새 세션)",
			next.SessionOpened, next.SessionID, first.SessionID)
	}
	if key, sha, size, at := initColumnsOf(t, pool, next.SessionID); key != nil || sha != nil || size != nil || at != nil {
		t.Errorf("새 세션 init 열 = (%v, %x, %v, %v), want 전부 NULL — 새 세션은 새 init 을 받아야 한다", key, sha, size, at)
	}
	if state, reason := sessionStateOf(t, pool, first.SessionID); state != "ending" || reason == nil || *reason != "init_mismatch" {
		t.Errorf("옛 세션 = (%q, %v), want (ending, init_mismatch)", state, reason)
	}
}
