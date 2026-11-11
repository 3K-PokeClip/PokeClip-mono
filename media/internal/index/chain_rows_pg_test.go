package index

import (
	"context"
	"fmt"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// ③ 사슬 복원이 기댈 장부 조회 둘(LoadPlaybackHoles · LoadPlaybackChainRows)과 재수거 세 조회에 덧붙인 두 열(POK-196 PDT 계획 4절
// c10a · 설계 r6 2.10 「입력 꼴」 · 「구멍은 DB 에서 열거한다」). 복원 배선은 c10b 몫이라 여기서는 조회 결과만 잰다.

// setPlaybackState 는 행의 ③ 상태를 바꾼다(픽스처).
func setPlaybackState(t *testing.T, pool *pgxpool.Pool, streamID string, seq int64, state UploadState) {
	t.Helper()
	if _, err := pool.Exec(context.Background(),
		`UPDATE stream_segments SET playback_upload_state = $3 WHERE stream_id = $1 AND seq = $2`,
		streamID, seq, string(state)); err != nil {
		t.Fatalf("③ 상태 픽스처 실패 %s/%d: %v", streamID, seq, err)
	}
}

// 재수거 세 조회에 덧붙인 두 열은 ③ 축에서만 그 행의 ② 키 · ② 상태이고, ② · init 축에서는 빈 값이다. 이미 있던 칸의
// 스캔 값은 그대로다 — 세 조회가 한 Scan 을 쓰므로 열 자리가 하나라도 어긋나면 다른 칸에 값이 들어간다(잡는 결함).
func TestPendingUploadsCarryArchiveColumnsOnPlaybackAxisOnly(t *testing.T) {
	pool := newTestPool(t)
	st := NewUploadStore(pool)
	ctx := context.Background()
	openLiveSession(t, pool, "S-arc", "arcstream")
	cutoffAt(t, pool, "arcstream", 0)
	seedSessionSegment(t, pool, "arcstream", "S-arc", 0)
	seedSessionSegment(t, pool, "arcstream", "S-arc", 1)
	if _, err := pool.Exec(ctx, `UPDATE stream_segments SET upload_state = 'failed' WHERE stream_id = 'arcstream' AND seq = 0`); err != nil {
		t.Fatalf("② 상태 픽스처 실패: %v", err)
	}
	var archiveKey string
	if err := pool.QueryRow(ctx, `SELECT s3_key FROM stream_segments WHERE stream_id = 'arcstream' AND seq = 0`).Scan(&archiveKey); err != nil {
		t.Fatalf("② 키 조회 실패: %v", err)
	}

	playbackRows, _, err := st.PendingUploads(ctx, AxisPlayback, 120, 10, SweepCursor{})
	if err != nil || len(playbackRows) != 2 {
		t.Fatalf("PendingUploads(③) = (%d행, %v), want (2행, nil)", len(playbackRows), err)
	}
	want := UploadTarget{
		StreamID: "arcstream", Axis: AxisPlayback, Seq: 0, SessionID: "S-arc", S3Key: "dvr/arcstream/seg/000000.m4s",
		LocalPath: "/recordings/arcstream/seg_000000.mp4", Bytes: 1000,
		ArchiveS3Key: archiveKey, ArchiveUploadState: UploadStateFailed,
	}
	if got := playbackRows[0]; !reflect.DeepEqual(got, want) {
		t.Errorf("③ 대상 = %+v\nwant %+v", got, want)
	}

	archiveRows, _, err := st.PendingUploads(ctx, AxisArchive, 120, 10, SweepCursor{})
	if err != nil || len(archiveRows) != 1 {
		t.Fatalf("PendingUploads(②) = (%d행, %v), want (1행, nil)", len(archiveRows), err)
	}
	wantArchive := UploadTarget{
		StreamID: "arcstream", Axis: AxisArchive, Seq: 0, S3Key: archiveKey,
		LocalPath: "/recordings/arcstream/seg_000000.mp4", Bytes: 1000,
	}
	if got := archiveRows[0]; !reflect.DeepEqual(got, wantArchive) {
		t.Errorf("② 대상 = %+v\nwant %+v", got, wantArchive)
	}

	initRows, _, err := st.PendingUploads(ctx, AxisInit, 120, 10, SweepCursor{})
	if err != nil || len(initRows) != 1 {
		t.Fatalf("PendingUploads(init) = (%d행, %v), want (1행, nil)", len(initRows), err)
	}
	if got := initRows[0]; got.ArchiveS3Key != "" || got.ArchiveUploadState != "" || got.LocalPath != "/recordings/arcstream/seg_000001.mp4" {
		t.Errorf("init 대상 = %+v, want 덧붙인 두 칸 빈 값 · 원천 = 최신 조각", got)
	}
}

// 구멍 열거는 그 회차의 ③ pending · failed 행 전부를 seq 순으로 내고, 행마다 같은 회차에서 그보다 앞선 ③ uploaded 최신
// 행(닻)을 싣는다 — uploaded 행으로 갈린 구멍은 닻이 달라 따로 다뤄진다(설계 r6 2.10 cx-N1: seq 9 uploaded → 10 failed →
// 11 uploaded → 12 pending 에서 10 이 빠지지 않는다). 닻이 없으면 nil 이다(회차 첫 행부터). 재수거 조회와 같은 조건이라
// 컷오프 아래 행 · 다른 회차 행은 나오지 않는다.
func TestLoadPlaybackHolesListsHolesWithTheirAnchors(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	openLiveSession(t, pool, "S-h", "holestream")
	openLiveSession(t, pool, "S-other", "otherstream")
	cutoffAt(t, pool, "holestream", 8)
	cutoffAt(t, pool, "otherstream", 0)
	for seq := int64(7); seq <= 13; seq++ {
		seedSessionSegment(t, pool, "holestream", "S-h", seq)
	}
	seedSessionSegment(t, pool, "otherstream", "S-other", 0)
	for seq, state := range map[int64]UploadState{9: UploadStateUploaded, 10: UploadStateFailed, 11: UploadStateUploaded} {
		setPlaybackState(t, pool, "holestream", seq, state)
	}

	got, err := LoadPlaybackHoles(ctx, pool, "S-h")

	if err != nil {
		t.Fatalf("LoadPlaybackHoles 오류 = %v", err)
	}
	nine, eleven := int64(9), int64(11)
	want := []PlaybackHole{
		{Seq: 8, State: UploadStatePending},
		{Seq: 10, State: UploadStateFailed, AnchorSeq: &nine},
		{Seq: 12, State: UploadStatePending, AnchorSeq: &eleven},
		{Seq: 13, State: UploadStatePending, AnchorSeq: &eleven},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("구멍 = %s\nwant %s", holesText(got), holesText(want))
	}
}

// 끝난 회차의 구멍은 열거하지 않는다 — 재수거 조회의 sess.state <> 'ended' 그대로다(설계 r6 2.10 「끝난 회차」).
func TestLoadPlaybackHolesSkipsEndedSession(t *testing.T) {
	pool := newTestPool(t)
	openLiveSession(t, pool, "S-end", "endstream")
	cutoffAt(t, pool, "endstream", 0)
	seedSessionSegment(t, pool, "endstream", "S-end", 0)
	setSessionState(t, pool, "S-end", "ended")

	got, err := LoadPlaybackHoles(context.Background(), pool, "S-end")

	if err != nil || len(got) != 0 {
		t.Errorf("LoadPlaybackHoles = (%s, %v), want (없음, nil)", holesText(got), err)
	}
}

// 사슬 행 조회는 seq 구간의 장부 행을 비귀속 행까지 seq 순으로 읽는다 — 인접 판정 재료다. 원래 사실 칸 · 장부 길이도
// 싣는다: 실시간 사슬은 장부 칸(교정 뒤 길이 · 영상 끝)으로 잇기 때문에 복원도 같은 칸을 읽어야 같은 도장이 난다.
func TestLoadPlaybackChainRowsReadsLedgerRangeWithUnattributedRows(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	openLiveSession(t, pool, "S-r", "rowstream")
	seedSessionSegment(t, pool, "rowstream", "S-r", 0)
	rec := [16]byte{0xaa, 1}
	// 사실 칸은 INSERT 뒤 바꿀 수 없다(c1 트리거) — 칸을 실은 행을 바로 넣는다.
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, is_discontinuity,
     session_id, playback_pdt, playback_s3_key, playback_upload_state,
     timing_wall_guess, timing_recorder_id, timing_segment_number, timing_video_first_ns, timing_video_end_ns)
VALUES ('rowstream', 1, 4000, now() - interval '10 minutes', 3990, $1, '/recordings/rowstream/seg_000001.mp4',
        'uploaded', 1000, false, 'S-r', now() - interval '10 minutes', 'dvr/rowstream/seg/000001.m4s', 'uploaded',
        now(), $2, 6, 14000000000, 18000000000)`,
		S3Key("rowstream", 1, time.Now().UTC()), rec); err != nil {
		t.Fatalf("사실 칸 행 픽스처 실패: %v", err)
	}
	seed(t, pool, seedRow{"rowstream", 2, old(10), UploadStatePending, nil, true}) // 비귀속 · local_path · bytes NULL
	seedSessionSegment(t, pool, "rowstream", "S-r", 3)

	got, err := LoadPlaybackChainRows(ctx, pool, "rowstream", 1, 3)

	if err != nil {
		t.Fatalf("LoadPlaybackChainRows 오류 = %v", err)
	}
	if seqs := playbackChainRowSeqs(got); !reflect.DeepEqual(seqs, []int64{1, 2, 3}) {
		t.Fatalf("행 seq = %v, want [1 2 3] — 구간 밖 행 없이 비귀속 행 포함", seqs)
	}
	first, gap := got[0], got[1]
	if first.SessionID != "S-r" || first.LocalPath != "/recordings/rowstream/seg_000001.mp4" || first.Bytes == nil || *first.Bytes != 1000 ||
		first.DurationMS != 3990 || first.UploadState != UploadStateUploaded || first.PlaybackS3Key != "dvr/rowstream/seg/000001.m4s" ||
		first.PlaybackUploadState != UploadStateUploaded || first.S3Key == "" {
		t.Errorf("seq 1 = %+v, want 회차 · 경로 · 1000B · 3990ms · ② uploaded · ③ 키 · ③ uploaded", first)
	}
	f := first.Timing
	if f.Recorder == nil || *f.Recorder != rec || f.Number == nil || *f.Number != 6 || f.VideoFirst == nil ||
		*f.VideoFirst != 14*time.Second || f.VideoEnd == nil || *f.VideoEnd != 18*time.Second || f.Wall.IsZero() {
		t.Errorf("seq 1 사실 칸 = %+v, want 표식 · 번호 6 · 영상 14s–18s · w", f)
	}
	if gap.SessionID != "" || gap.LocalPath != "" || gap.Bytes != nil || gap.PlaybackS3Key != "" || gap.Timing.Recorder != nil {
		t.Errorf("seq 2(비귀속) = %+v, want 빈 회차 · 경로 · bytes nil · ③ 키 빈 값 · 칸 nil", gap)
	}
}

func playbackChainRowSeqs(rows []PlaybackChainRow) []int64 {
	out := make([]int64, 0, len(rows))
	for _, r := range rows {
		out = append(out, r.Seq)
	}
	return out
}

// holesText 는 구멍 목록을 읽을 수 있게 편다(닻 포인터를 값으로).
func holesText(hs []PlaybackHole) string {
	var out []any
	for _, h := range hs {
		anchor := any(nil)
		if h.AnchorSeq != nil {
			anchor = *h.AnchorSeq
		}
		out = append(out, []any{h.Seq, h.State, anchor})
	}
	return fmt.Sprint(out)
}
