package index

import (
	"bytes"
	"context"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

// 축별 스위퍼 조회(설계 5.5.4 #2)와 축 라벨 backlog(#7).
//
// 한 조회로 세 축을 덮으려던 M3 의 형상을 여기서 가른다 — 축마다 상태 열도, 식별자도,
// 자격 술어도 다르다. 라벨만 갈고 같은 SQL 을 쓰면 ③ 의 잔량이 ② 의 수치로 보고된다.

// cutoffAt 은 그 스트림의 활성화 컷오프를 박는다. ③ 축은 컷오프-인지 조회다.
func cutoffAt(t *testing.T, pool *pgxpool.Pool, streamID string, seq int64) {
	t.Helper()
	_, err := pool.Exec(context.Background(),
		`INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
		 VALUES ($1, $2, 'live_ingress', 'watcher')`, streamID, seq)
	if err != nil {
		t.Fatalf("컷오프 픽스처 실패 %s: %v", streamID, err)
	}
}

func setSessionState(t *testing.T, pool *pgxpool.Pool, sessionID, state string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(),
		`UPDATE stream_sessions SET state=$2 WHERE session_id=$1`, sessionID, state); err != nil {
		t.Fatalf("세션 상태 변경 실패: %v", err)
	}
}

func pickedSeqs(rows []UploadTarget) []int64 {
	out := make([]int64, 0, len(rows))
	for _, r := range rows {
		out = append(out, r.Seq)
	}
	return out
}

func TestPendingUploadsPlaybackAxisStampsItsOwnKeyAndSession(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-ax", "axstream")
	cutoffAt(t, pool, "axstream", 0)
	seedSessionSegment(t, pool, "axstream", "S-ax", 0)
	seedSessionSegment(t, pool, "axstream", "S-ax", 1) // 꼬리 예외를 피하려면 후속 행이 있어야 한다

	rows, _, err := st.PendingUploads(context.Background(), AxisPlayback, 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}

	if len(rows) == 0 {
		t.Fatal("③ 대상이 0건이다")
	}
	got := rows[0]
	if got.Axis != AxisPlayback {
		t.Errorf("Axis = %v, want %v", got.Axis, AxisPlayback)
	}
	if got.S3Key != "dvr/axstream/seg/000000.m4s" {
		t.Errorf("S3Key = %q — ③ 은 playback_s3_key 를 쓴다(② 키가 아니다)", got.S3Key)
	}
	if got.SessionID != "S-ax" {
		t.Errorf("SessionID = %q, want S-ax — 워커가 세션 기대값을 알 통로가 이것뿐이다", got.SessionID)
	}
}

func TestPendingUploadsPlaybackAxisCarriesSessionExpectedInitHash(t *testing.T) {
	// 조회 메서드 신설 0 — 워커가 "이 조각이 어느 init 을 기대하는가"를 알 유일한 통로다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-sha", "shastream")
	cutoffAt(t, pool, "shastream", 0)
	seedSessionSegment(t, pool, "shastream", "S-sha", 0)
	seedSessionSegment(t, pool, "shastream", "S-sha", 1)
	want := sha32(0x5c)
	if _, err := st.MarkInitUploaded(context.Background(), "S-sha", want, "k", 10, false); err != nil {
		t.Fatalf("init 확정 실패: %v", err)
	}

	rows, _, err := st.PendingUploads(context.Background(), AxisPlayback, 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}

	if len(rows) == 0 {
		t.Fatal("③ 대상이 0건이다")
	}
	if !bytes.Equal(rows[0].ExpectedInitSHA, want) {
		t.Errorf("ExpectedInitSHA = %x, want %x", rows[0].ExpectedInitSHA, want)
	}
}

func TestPendingUploadsPlaybackAxisExemptsSegmentsBelowCutoff(t *testing.T) {
	// T13 — 컷오프 미만은 되감기 목록에 실릴 수 없으므로 ③ 를 만들 이유가 없다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-cut", "cutstream")
	cutoffAt(t, pool, "cutstream", 2)
	for seq := int64(0); seq <= 3; seq++ {
		seedSessionSegment(t, pool, "cutstream", "S-cut", seq)
	}

	rows, _, err := st.PendingUploads(context.Background(), AxisPlayback, 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}

	// 0·1 은 컷오프 미만이라 빠지고 2·3 만 남는다(꼬리 3 은 tailGrace 를 넘겨 집힌다).
	if got := pickedSeqs(rows); len(got) != 2 || got[0] != 2 || got[1] != 3 {
		t.Errorf("집힌 seq = %v, want [2 3] — 컷오프 미만이 새어 들어왔다", got)
	}
}

func TestPendingUploadsPlaybackAxisSkipsStreamWithoutCutoff(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-noc", "nocstream")
	seedSessionSegment(t, pool, "nocstream", "S-noc", 0)
	seedSessionSegment(t, pool, "nocstream", "S-noc", 1)

	rows, _, err := st.PendingUploads(context.Background(), AxisPlayback, 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}

	if len(rows) != 0 {
		t.Errorf("집힌 행 = %v, want 0건 — 컷오프 없는 스트림은 되감기 창 자체가 없다", pickedSeqs(rows))
	}
}

func TestPendingUploadsPlaybackAxisIncludesEndingButExcludesEnded(t *testing.T) {
	// T7 — 설계 5.5.6 라벨. ending 은 아직 정산 창 안이라 계속 집는다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	for _, c := range []struct{ session, stream, state string }{
		{"S-ing", "ingstream", "ending"},
		{"S-end", "endstream", "ended"},
	} {
		openLiveSession(t, pool, c.session, c.stream)
		cutoffAt(t, pool, c.stream, 0)
		seedSessionSegment(t, pool, c.stream, c.session, 0)
		seedSessionSegment(t, pool, c.stream, c.session, 1)
		setSessionState(t, pool, c.session, c.state)
	}

	rows, _, err := st.PendingUploads(ctx, AxisPlayback, 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}

	for _, r := range rows {
		if r.StreamID == "endstream" {
			t.Error("ended 세션의 조각이 집혔다 — 정산이 끝난 회차다")
		}
	}
	found := false
	for _, r := range rows {
		if r.StreamID == "ingstream" {
			found = true
		}
	}
	if !found {
		t.Error("ending 세션의 조각이 안 집혔다 — 그 창에서 ③ 이 멈추면 목록이 미완으로 굳는다")
	}
}

func TestPendingUploadsPlaybackAxisSkipsSegmentsWithoutSession(t *testing.T) {
	// f19 — session_id 가 NULL 인 조각은 `settled` 가 거짓이라 목록에 실릴 수 없다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	cutoffAt(t, pool, "orphan", 0)
	seed(t, pool, seedRow{"orphan", 0, old(10), UploadStatePending, bytesOf(1000), false})
	seed(t, pool, seedRow{"orphan", 1, old(10), UploadStatePending, bytesOf(1000), false})

	rows, _, err := st.PendingUploads(context.Background(), AxisPlayback, 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}

	if len(rows) != 0 {
		t.Errorf("집힌 행 = %v, want 0건 — 세션 없는 조각은 ③ 대상이 아니다", pickedSeqs(rows))
	}
}

func TestPendingUploadsPlaybackAxisLeavesPlaybackPosToWorker(t *testing.T) {
	// 계획 4.2-R R3 — 시간 도장 위치(pos = mtxi + offset)는 DB 통로가 아니다. 실시간 작업은
	// 루프가 싣고, 스위퍼 작업은 워커가 보정값 표로 정한다. 장부의 PDT 차(옛 seg_offset_ns)를
	// pos 로 실으면 결정 B′ 가 폐기한 벽시계 도장이 되살아난다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-off", "offstream")
	cutoffAt(t, pool, "offstream", 0)
	seedSessionSegment(t, pool, "offstream", "S-off", 0)
	seedSessionSegment(t, pool, "offstream", "S-off", 1)
	// 옛 조회가 8초를 싣던 픽스처다. playback_pdt 는 불변 트리거가 지키므로 세션 앵커를 옮긴다.
	if _, err := pool.Exec(context.Background(), `
UPDATE stream_sessions
   SET first_pdt = (SELECT playback_pdt - interval '8 seconds'
                      FROM stream_segments WHERE stream_id='offstream' AND seq=0)
 WHERE session_id='S-off'`); err != nil {
		t.Fatalf("세션 앵커 조정 실패: %v", err)
	}

	rows, _, err := st.PendingUploads(context.Background(), AxisPlayback, 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}

	if len(rows) == 0 {
		t.Fatal("③ 대상이 0건이다")
	}
	if got := rows[0]; got.PlaybackPos != 0 || got.StitchOffset != 0 || got.PosPinned {
		t.Errorf("(PlaybackPos, StitchOffset, PosPinned) = (%v, %v, %v), want (0, 0, false) — 조회는 도장 위치를 정하지 않는다",
			got.PlaybackPos, got.StitchOffset, got.PosPinned)
	}
}

func TestPendingUploadsInitAxisPicksSessionsWithoutConfirmedInit(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	openLiveSession(t, pool, "S-i1", "i1stream")
	seedSessionSegment(t, pool, "i1stream", "S-i1", 0)
	seedSessionSegment(t, pool, "i1stream", "S-i1", 1)
	openLiveSession(t, pool, "S-i2", "i2stream")
	seedSessionSegment(t, pool, "i2stream", "S-i2", 0)
	if _, err := st.MarkInitUploaded(ctx, "S-i2", sha32(2), "k", 10, false); err != nil {
		t.Fatalf("init 확정 실패: %v", err)
	}

	rows, _, err := st.PendingUploads(ctx, AxisInit, 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}

	if len(rows) != 1 {
		t.Fatalf("집힌 행 = %d건, want 1건(미확정 세션만)", len(rows))
	}
	got := rows[0]
	if got.Axis != AxisInit || got.SessionID != "S-i1" {
		t.Errorf("(Axis, SessionID) = (%v, %q), want (init, S-i1)", got.Axis, got.SessionID)
	}
	// 원천은 세션의 최신 조각이다 — 재포장 init 은 같은 송출 설정이면 회차 안 어느 조각이든 같은 바이트라, 첫 조각만
	// 결정적으로 실패하는 회차도 다음 조각으로 회복한다(Phase 3 r4 cc #2).
	if want := "/recordings/i1stream/seg_000001.mp4"; got.LocalPath != want {
		t.Errorf("LocalPath = %q, want %q(세션 최신 조각)", got.LocalPath, want)
	}
	if got.S3Key != "" {
		t.Errorf("S3Key = %q, want 빈 값 — init 키는 장부의 예약값이 아니라 파생이다", got.S3Key)
	}
}

func TestPendingUploadsInitAxisSkipsEndedSessions(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	openLiveSession(t, pool, "S-ie", "iestream")
	seedSessionSegment(t, pool, "iestream", "S-ie", 0)
	setSessionState(t, pool, "S-ie", "ended")

	rows, _, err := st.PendingUploads(context.Background(), AxisInit, 120, 10, SweepCursor{})
	if err != nil {
		t.Fatalf("PendingUploads 실패: %v", err)
	}

	if len(rows) != 0 {
		t.Errorf("집힌 행 = %d건, want 0건 — ended 세션의 init 은 이제 만들 이유가 없다", len(rows))
	}
}

func TestCountBacklogIsPerAxis(t *testing.T) {
	// #7 — 라벨만 갈고 같은 수치를 내면 ③ 의 잔량이 ② 의 것으로 보고된다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	openLiveSession(t, pool, "S-bl", "blstream")
	// ③ 집계는 컷오프-인지다 — 컷오프가 없으면 ③ 잔량은 0 이다(아래 ⒜ 회귀).
	cutoffAt(t, pool, "blstream", 0)
	seedSessionSegment(t, pool, "blstream", "S-bl", 0) // ② uploaded · ③ pending
	seedSessionSegment(t, pool, "blstream", "S-bl", 1)
	if _, err := st.MarkPlaybackUploaded(ctx, "blstream", 0, 100); err != nil {
		t.Fatalf("③ 확정 실패: %v", err)
	}

	archivePending, _, _, err := st.CountBacklog(ctx, AxisArchive)
	if err != nil {
		t.Fatalf("CountBacklog(archive) 실패: %v", err)
	}
	playbackPending, _, _, err := st.CountBacklog(ctx, AxisPlayback)
	if err != nil {
		t.Fatalf("CountBacklog(playback) 실패: %v", err)
	}
	initPending, _, _, err := st.CountBacklog(ctx, AxisInit)
	if err != nil {
		t.Fatalf("CountBacklog(init) 실패: %v", err)
	}

	if archivePending != 0 {
		t.Errorf("② pending = %d, want 0(픽스처가 둘 다 uploaded)", archivePending)
	}
	if playbackPending != 1 {
		t.Errorf("③ pending = %d, want 1", playbackPending)
	}
	if initPending != 1 {
		t.Errorf("init pending = %d, want 1(미확정 세션 1)", initPending)
	}
}

func TestCountBacklogPlaybackAxisExemptsRowsOutsideCutoff(t *testing.T) {
	// 계약 5-5 6항 회귀 ⒜ — backlog 집계는 컷오프-인지 4축 소비자 중 하나다. 면제 행(컷오프
	// 기록 없음 OR seq < 컷오프)은 ③ 재수거가 집지 않으므로, 세면 영구 적체로 보고된다.
	// ③ 조회 쪽 두 테스트(…ExemptsSegmentsBelowCutoff·…SkipsStreamWithoutCutoff)와 같은 술어다.
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	playbackPending := func() int64 {
		t.Helper()
		pending, _, _, err := st.CountBacklog(context.Background(), AxisPlayback)
		if err != nil {
			t.Fatalf("CountBacklog(playback) 실패: %v", err)
		}
		return pending
	}
	openLiveSession(t, pool, "S-bn", "bnstream") // 끝까지 컷오프가 없는 스트림
	seedSessionSegment(t, pool, "bnstream", "S-bn", 0)
	seedSessionSegment(t, pool, "bnstream", "S-bn", 1)
	openLiveSession(t, pool, "S-bc", "bcstream")
	seedSessionSegment(t, pool, "bcstream", "S-bc", 0) // 활성화 전 행 — 과거 영구 pending
	seedSessionSegment(t, pool, "bcstream", "S-bc", 1)

	if got := playbackPending(); got != 0 {
		t.Errorf("활성화 전 ③ pending = %d, want 0 — 컷오프 없는 스트림은 세지 않는다", got)
	}

	// 활성화 — 컷오프는 자기 행(seq 2)과 함께 생긴다. 미만 0·1 은 범위 밖이고, 컷오프 행
	// 자신을 포함한 2·3 만 센다.
	cutoffAt(t, pool, "bcstream", 2)
	seedSessionSegment(t, pool, "bcstream", "S-bc", 2)
	seedSessionSegment(t, pool, "bcstream", "S-bc", 3)

	if got := playbackPending(); got != 2 {
		t.Errorf("활성화 뒤 ③ pending = %d, want 2 — 세는 것은 bcstream 의 seq 2·3 뿐이다(컷오프 미만 0·1 과 컷오프 없는 bnstream 은 범위 밖)", got)
	}
}
