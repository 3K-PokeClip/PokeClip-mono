package publish

// 판 2 장부 행의 동결 DVR · VOD · 감사 재렌더(POK-196 PDT 생산자 c7a — 계획 c7a 시험 2 · 5 · 9절 음성 대조 「장부 행을 SQL
// 로 넣고 기존 함수만 부르는 목록 시험」). 장부 행은 SQL 로 심고(판 2 행은 CHECK stream_segments_timing_v2_chk 대로
// timing_wall_guess · playback_pdt 를 갖는다) FreezeDVR · FreezeVOD · FrozenAuditor 만 부른다 — 시험 코드가 판 칸의 Go 필드를
// 쓰지 않아 판을 모르는 부모 트리에서도 컴파일되고, 그 트리에서는 본문에 판 태그가 없어 FAIL 한다.

import (
	"bytes"
	"fmt"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// insertV2LedgerRow 는 픽스처 행 r 을 판 2 · ③ 상태 state 로 장부에 넣는다. 짐작 벽시계(timing_wall_guess)는 PDT 와 같게
// 둔다 — 목록 읽기는 그 칸을 읽지 않는다.
func insertV2LedgerRow(t *testing.T, pool *pgxpool.Pool, r boundary.Row, state string) {
	t.Helper()
	exec(t, pool, `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key,
		                             session_id, playback_pdt, playback_s3_key, playback_upload_state,
		                             timing_version, timing_wall_guess)
		VALUES ($1, $2, 0, now(), $3, $4, NULLIF($5, ''), $6, NULLIF($7, ''), $8, 2, $6)`,
		fxStream, r.Seq, r.DurationMS, fmt.Sprintf("archive/%s/%06d.mp4", fxStream, r.Seq),
		r.SessionID, r.PlaybackPDT, r.PlaybackS3Key, state)
}

// timingTagLines 는 본문의 판 태그 줄과 그 앞 줄이다(「앞 줄 → 태그 줄」 꼴 · 본문 순서).
func timingTagLines(body []byte) []string {
	lines := strings.Split(string(body), "\n")
	var out []string
	for i, line := range lines {
		if strings.HasPrefix(line, "#EXT-X-POKECLIP-TIMING") && i > 0 {
			out = append(out, lines[i-1]+" → "+line)
		}
	}
	return out
}

// 판 2 회차의 DVR 을 장부에서 닫으면 닫힌 판의 첫 조각 MAP 뒤에 「:2」 한 줄만 선다. 감사기가 덜 닫힌 판을 장부로 다시
// 렌더한 본문의 sha 는 동결이 장부에 적은 sha 와 같다 — 재렌더도 판을 실어 고친다(복구 1).
func TestFreezeDVRAndAuditCarryTimingTag(t *testing.T) {
	l, store, _ := newSoloLoop(t, 3)
	for _, r := range l.fx.rows {
		insertV2LedgerRow(t, l.pub.pool, r, "uploaded")
	}
	insertCutoff(t, l.pub.pool, 0)

	wantOutcome(t, l.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)

	closed, _ := stored(store, soloKey)
	wantMap := `#EXT-X-MAP:URI="` + fxBaseURL + `/dvr/str/init/S.mp4" → #EXT-X-POKECLIP-TIMING:2`
	if got := timingTagLines(closed); len(got) != 1 || got[0] != wantMap {
		t.Fatalf("닫힌 판의 판 태그 = %q, want [%s]\n%s", got, wantMap, closed)
	}
	wantFrozen(t, l, store, "S", soloKey)

	exec(t, l.pub.pool, `UPDATE stream_sessions SET dvr_etag = '"불투명"', manifest_etag = '"불투명"' WHERE session_id = 'S'`)
	truncateDVR(t, store, closed, 2)
	out := NewFrozenAuditor(l.pub).Audit(t.Context())

	if body, _ := stored(store, soloKey); out.Repaired != 1 || out.Failed != 0 || !bytes.Equal(body, closed) {
		t.Errorf("감사 = %+v · 고친 본문 같음 %t, want 복구 1 · 실패 0 · 닫힌 판 그대로(재렌더 sha = 동결 sha)", out, bytes.Equal(body, closed))
	}
}

// 판 2 사슬의 VOD 를 닫으면 첫 조각 MAP 뒤에 「:2」 한 줄만 선다. 사슬 범위 안의 판 2 비귀속 행(seq 5 — 계약 r24 6-6-3
// 「VOD 목록에는 사슬 범위 안이면 GAP 줄로 실린다」)은 fillGap 을 지나도 판이 남아 그 조각 앞에 「:0」 이, 다음 조각 앞에
// 「:2」 가 서지 않는다.
func TestFreezeVODCarriesTimingTagThroughFillGap(t *testing.T) {
	pool := newPool(t)
	chain := []rewind.Session{{ID: "P", TargetDuration: 6}, {ID: "S", InheritsSession: "P", TargetDuration: 7}}
	ids := []string{"P", "S"}
	for _, s := range chain {
		insertSession(t, pool, fxStream, s, "ending")
	}
	exec(t, pool, `UPDATE stream_sessions SET vod_state = 'freezing', dvr_state = 'frozen' WHERE session_id = ANY($1)`, ids)
	for seq := int64(0); seq <= 9; seq++ {
		session, state := "P", "uploaded"
		switch {
		case seq == 5:
			session, state = "", "pending"
		case seq > 5:
			session = "S"
		}
		r := fxRow(session, seq)
		if session == "" {
			r.PlaybackS3Key = ""
		}
		insertV2LedgerRow(t, pool, r, state)
	}
	insertCutoff(t, pool, 0)
	store := newFakeStore(t)
	p := newPublisher(t, pool, store, "w-me", &logRecorder{})

	wantOutcome(t, p.FreezeVOD(t.Context(), index.Chain{StreamID: fxStream, Head: "P", Tail: "S", SessionIDs: ids}, false),
		AxisVOD, ResultFrozenWithGaps, false)

	body, _ := stored(store, vodChainKey)
	wantMap := `#EXT-X-MAP:URI="` + fxBaseURL + `/dvr/str/init/P.mp4" → #EXT-X-POKECLIP-TIMING:2`
	if got := timingTagLines(body); len(got) != 1 || got[0] != wantMap {
		t.Errorf("VOD 본문의 판 태그 = %q, want [%s](비귀속 GAP 행도 판 2)\n%s", got, wantMap, body)
	}
	if lines := vodLines(body); len(lines) != 10 || !strings.HasPrefix(lines[5], "#EXT-X-GAP ") {
		t.Errorf("VOD 조각 줄 = %q, want seq 5 가 GAP 줄", lines)
	}
}
