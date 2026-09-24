package index

import (
	"bytes"
	"context"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// 첫 init 업로드 CAS(CTE 한 문장) — 설계 5.3ⓑ·ⓓ · ADR-044 호환 게이트.
//
// 이 문장 하나가 넷을 한다: 4열 기록 · 결과 4분기 · `stsd` 비호환에 의한 계승 해제 ·
// 그 해제의 영속. 나누면 "기록은 됐는데 해제는 안 된" 중간 상태가 실재한다.

func sha32(fill byte) []byte {
	b := make([]byte, 32)
	for i := range b {
		b[i] = fill
	}
	return b
}

// inheritingSession 은 직전 세션을 계승한 세션 1행을 연다(계승 후보).
func inheritingSession(t *testing.T, pool *pgxpool.Pool, sessionID, streamID, prev string, base int64) {
	t.Helper()
	ctx := context.Background()
	if prev != "" {
		if _, err := pool.Exec(ctx,
			`INSERT INTO stream_sessions (session_id, stream_id, started_at, state)
			 VALUES ($1, $2, now() - interval '5 minutes', 'ended')`, prev, streamID); err != nil {
			t.Fatalf("직전 세션 픽스처 실패: %v", err)
		}
	}
	var inherits any
	if prev != "" {
		inherits = prev
	}
	if _, err := pool.Exec(ctx,
		`INSERT INTO stream_sessions
		     (session_id, stream_id, started_at, state, inherits_session, discontinuity_base)
		 VALUES ($1, $2, now(), 'live', $3, $4)`,
		sessionID, streamID, inherits, base); err != nil {
		t.Fatalf("계승 세션 픽스처 실패: %v", err)
	}
}

func inheritanceOf(t *testing.T, pool *pgxpool.Pool, sessionID string) (inherits *string, base int64) {
	t.Helper()
	err := pool.QueryRow(context.Background(),
		`SELECT inherits_session, discontinuity_base FROM stream_sessions WHERE session_id=$1`,
		sessionID).Scan(&inherits, &base)
	if err != nil {
		t.Fatalf("계승 열 조회 실패: %v", err)
	}
	return inherits, base
}

func initColumnsOf(t *testing.T, pool *pgxpool.Pool, sessionID string) (key *string, sha []byte, size *int64, at *time.Time) {
	t.Helper()
	err := pool.QueryRow(context.Background(),
		`SELECT init_s3_key, init_sha256, init_bytes, init_uploaded_at
		   FROM stream_sessions WHERE session_id=$1`, sessionID).Scan(&key, &sha, &size, &at)
	if err != nil {
		t.Fatalf("init 열 조회 실패: %v", err)
	}
	return key, sha, size, at
}

func TestInitCASWritesAllFourColumnsOnFirstUpload(t *testing.T) {
	// 개시 시점의 init_sha256 은 NULL 이다 — 그 값을 만드는 유일한 생산자가 이 CAS 다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-first", "firststream")
	want := sha32(0xa1)

	mark, err := st.MarkInitUploaded(context.Background(), "S-first", want,
		"dvr/firststream/init/S-first.mp4", 1219, false)
	if err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}

	if mark != InitMarkSuccess {
		t.Fatalf("mark = %v, want %v", mark, InitMarkSuccess)
	}
	key, sha, size, at := initColumnsOf(t, pool, "S-first")
	if key == nil || *key != "dvr/firststream/init/S-first.mp4" {
		t.Errorf("init_s3_key = %v", key)
	}
	if !bytes.Equal(sha, want) {
		t.Errorf("init_sha256 = %x, want %x", sha, want)
	}
	if size == nil || *size != 1219 {
		t.Errorf("init_bytes = %v, want 1219", size)
	}
	if at == nil {
		t.Error("init_uploaded_at 이 비었다 — 확정이 기록되지 않았다")
	}
}

func TestInitCASReportsAlreadySameOnRepeatWithSameBytes(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	openLiveSession(t, pool, "S-same", "samestream")
	sha := sha32(0xb2)
	if _, err := st.MarkInitUploaded(ctx, "S-same", sha, "k", 10, false); err != nil {
		t.Fatalf("1회차 실패: %v", err)
	}
	_, _, _, before := initColumnsOf(t, pool, "S-same")

	mark, err := st.MarkInitUploaded(ctx, "S-same", sha, "k", 10, false)
	if err != nil {
		t.Fatalf("2회차 실패: %v", err)
	}

	if mark != InitMarkAlreadySame {
		t.Fatalf("mark = %v, want %v — 같은 바이트 재시도는 성공도 불일치도 아니다", mark, InitMarkAlreadySame)
	}
	if _, _, _, after := initColumnsOf(t, pool, "S-same"); !after.Equal(*before) {
		t.Errorf("init_uploaded_at 이 %v → %v 로 바뀌었다 — 확정 시각은 1회로 굳는다", before, after)
	}
}

func TestInitCASReportsMismatchWhenAnotherHashIsAlreadyFixed(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	openLiveSession(t, pool, "S-diff", "diffstream")
	first := sha32(0xc3)
	if _, err := st.MarkInitUploaded(ctx, "S-diff", first, "k", 10, false); err != nil {
		t.Fatalf("1회차 실패: %v", err)
	}

	mark, err := st.MarkInitUploaded(ctx, "S-diff", sha32(0xd4), "k2", 20, false)
	if err != nil {
		t.Fatalf("2회차 실패: %v", err)
	}

	if mark != InitMarkMismatch {
		t.Fatalf("mark = %v, want %v", mark, InitMarkMismatch)
	}
	if _, sha, _, _ := initColumnsOf(t, pool, "S-diff"); !bytes.Equal(sha, first) {
		t.Errorf("init_sha256 = %x, want %x — 거부된 CAS 가 장부를 바꿨다", sha, first)
	}
}

func TestInitCASReportsMissingForUnknownSession(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)

	mark, err := st.MarkInitUploaded(context.Background(), "S-없음", sha32(1), "k", 10, false)
	if err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}

	if mark != InitMarkMissing {
		t.Fatalf("mark = %v, want %v — 세션 부재는 불일치와 다른 사건이다", mark, InitMarkMissing)
	}
}

func TestInitCASRevokesInheritanceWhenStsdIsIncompatible(t *testing.T) {
	// 뮤테이션 29 — reconnect_incompatible_stsd_revokes.
	// 코덱 파라미터가 달라진 재접속을 계승시키면 옛 MAP 으로 새 조각을 디코드하게 된다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	inheritingSession(t, pool, "S-B", "revstream", "S-A", 3)

	mark, err := st.MarkInitUploaded(context.Background(), "S-B", sha32(0xe5), "k", 10, true)
	if err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}

	if mark != InitMarkSuccess {
		t.Fatalf("mark = %v, want %v", mark, InitMarkSuccess)
	}
	// revoke_survives_restart — DB 에서 다시 읽어 영속을 확인한다(메모리 비트가 아니다).
	inherits, base := inheritanceOf(t, pool, "S-B")
	if inherits != nil {
		t.Errorf("inherits_session = %v, want NULL", *inherits)
	}
	if base != 0 {
		t.Errorf("discontinuity_base = %d, want 0", base)
	}
}

func TestInitCASKeepsInheritanceWhenStsdIsCompatible(t *testing.T) {
	// 대조군 reconnect_same_stsd_different_moov_inherits — 해제가 상시 발동하면 안 된다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	inheritingSession(t, pool, "S-K", "keepstream", "S-J", 3)

	if _, err := st.MarkInitUploaded(context.Background(), "S-K", sha32(0xf6), "k", 10, false); err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}

	inherits, base := inheritanceOf(t, pool, "S-K")
	if inherits == nil || *inherits != "S-J" {
		t.Errorf("inherits_session = %v, want S-J", inherits)
	}
	if base != 3 {
		t.Errorf("discontinuity_base = %d, want 3", base)
	}
}

func TestInitCASKeepsBaseOfTDSplitSession(t *testing.T) {
	// 뮤테이션 30 — td_split_first_init_keeps_base.
	// TD 분할 세션은 inherits_session 이 NULL 이면서 base 를 승계한다. 해제 술어에서
	// `inherits_session IS NOT NULL` 한정을 빼면 그 base 가 0 으로 되돌아가 DISC-SEQ 가 역행한다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	inheritingSession(t, pool, "S-TD", "tdstream", "", 3)

	if _, err := st.MarkInitUploaded(context.Background(), "S-TD", sha32(0x17), "k", 10, true); err != nil {
		t.Fatalf("MarkInitUploaded 실패: %v", err)
	}

	inherits, base := inheritanceOf(t, pool, "S-TD")
	if inherits != nil {
		t.Errorf("inherits_session = %v, want NULL(픽스처 그대로)", *inherits)
	}
	if base != 3 {
		t.Errorf("discontinuity_base = %d, want 3 — TD 분할 세션은 해제 대상이 아니다", base)
	}
}

func TestInitCASDoesNotRevokeInheritanceAfterInitIsConfirmed(t *testing.T) {
	// 해제는 첫 확정 1회에서만 일어난다(init_uploaded_at IS NULL 인 동안). 확정 뒤에는 G5 가 열려
	// 계승 접두가 실린 목록이 이미 나갔을 수 있다 — 늦게 온 비호환 판정이 base 를 0 으로 되돌리면
	// DISC-SEQ 가 역행한다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	inheritingSession(t, pool, "S-late", "latestream", "S-prev", 3)
	sha := sha32(0x2a)
	if _, err := st.MarkInitUploaded(ctx, "S-late", sha, "k", 10, false); err != nil {
		t.Fatalf("첫 확정 실패: %v", err)
	}

	mark, err := st.MarkInitUploaded(ctx, "S-late", sha, "k", 10, true)
	if err != nil {
		t.Fatalf("늦은 재시도 실패: %v", err)
	}

	if mark != InitMarkAlreadySame {
		t.Fatalf("mark = %v, want %v", mark, InitMarkAlreadySame)
	}
	if inherits, base := inheritanceOf(t, pool, "S-late"); inherits == nil || *inherits != "S-prev" || base != 3 {
		t.Errorf("(inherits_session, discontinuity_base) = (%v, %d), want (S-prev, 3) — 확정 뒤 계승이 풀렸다", inherits, base)
	}
}
