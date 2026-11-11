package index

// 목록 읽기의 판(POK-196 PDT 생산자 c7a — 계획 c7a 시험 2 의 장부 몫). 되감기 적재(LoadRewindLedger)와 사슬 장부
// (LoadChainLedger)가 행마다 timing_version 을 싣는다 — 판 2 행은 2, 판 NULL 행은 0 이다. 장부 행은 SQL 로 심는다(판 2
// 행은 CHECK stream_segments_timing_v2_chk 대로 timing_wall_guess · playback_pdt 를 갖는다). PG_DSN 미설정이면 skip 된다.

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// putTimingRow 는 회차 sessionID 의 ③ 확정 행 seq 를 판 version(nil = 판 NULL)으로 심는다.
func putTimingRow(t *testing.T, pool *pgxpool.Pool, stream, sessionID string, seq int64, at time.Time, version *int16) {
	t.Helper()
	_, err := pool.Exec(context.Background(), `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, session_id,
		                             playback_pdt, playback_s3_key, playback_upload_state, playback_uploaded_at, playback_bytes,
		                             timing_version, timing_wall_guess)
		VALUES ($1, $2, $8, $3, 4000, $4, $5, $3, $6, 'uploaded', $3, 900, $7, $3)`,
		stream, seq, at, fmt.Sprintf("archive/%s/%06d.mp4", stream, seq), sessionID,
		fmt.Sprintf("dvr/%s/seg/%06d.m4s", stream, seq), version, seq*4000)
	if err != nil {
		t.Fatalf("판 픽스처 행 seq %d 삽입 실패: %v", seq, err)
	}
}

// timingLedgerFixture 는 회차 T(ending) seq 0..3 을 심는다 — 0·1 은 판 NULL, 2·3 은 판 2 다(한 회차 안에서 판이 바뀌는
// 일은 평시에 없다 — 읽기가 행마다 판을 옮기는지만 본다). 컷오프는 0 이다.
func timingLedgerFixture(t *testing.T, pool *pgxpool.Pool) string {
	t.Helper()
	const stream = "rwtest-timing"
	putRewindSession(t, pool, rewindSessionRow{RewindSession{SessionID: "tm-T", State: "ending", EndReason: "offline",
		InitUploaded: true, FirstPDT: rewindT0, TargetDuration: 6}, stream, rewindT0})
	v := int16(2)
	for seq := int64(0); seq <= 3; seq++ {
		var version *int16
		if seq >= 2 {
			version = &v
		}
		putTimingRow(t, pool, stream, "tm-T", seq, rewindT0.Add(time.Duration(seq)*4*time.Second), version)
	}
	cutoffAt(t, pool, stream, 0)
	return stream
}

func TestListReadsCarryTimingVersion(t *testing.T) {
	pool := newTestPool(t)
	stream := timingLedgerFixture(t, pool)
	ctx := context.Background()

	rewindL, err := LoadRewindLedger(ctx, pool, stream, ledgerBounds)
	if err != nil {
		t.Fatalf("LoadRewindLedger 실패: %v", err)
	}
	chainL, err := LoadChainLedger(ctx, pool, Chain{StreamID: stream, Tail: "tm-T"}, chainTestMaxRows)
	if err != nil {
		t.Fatalf("LoadChainLedger 실패: %v", err)
	}

	want := []int16{0, 0, 2, 2}
	for name, rows := range map[string][]RewindRow{"되감기 적재": rewindL.Rows, "사슬 장부": chainL.Rows} {
		if len(rows) != len(want) {
			t.Fatalf("%s 행 %d개, want %d개", name, len(rows), len(want))
		}
		for i, r := range rows {
			if r.TimingVersion != want[i] {
				t.Errorf("%s seq %d 판 = %d, want %d", name, r.Seq, r.TimingVersion, want[i])
			}
		}
	}
}
