package publish

// 동결 DVR · VOD 행의 판(POK-196 PDT 생산자 c7a — 계획 c7a 시험 2 · 변이 m-R4). 동결은 캐시가 아니라 장부(RewindRow)에서
// 렌더 입력을 읽고 boundaryRow 로 경계 행을 만든다 — 이름 붙은 필드 리터럴이라 판을 빼먹어도 컴파일된다. 빼먹으면 동결 ·
// VOD 본문에 판 태그가 없어 라이브 발행본과 판이 갈린다. VOD 의 비귀속 행은 fillGap 을 지나 GAP 줄로 실리는데(계약 r24
// 6-6-3 「VOD 목록에는 사슬 범위 안이면 GAP 줄로 실린다」) 그 행의 판도 남아야 한다 — fillGap 은 값 복사라 판을 그대로 둔다.

import (
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// ledgerRowV 는 판 v 인 장부 행이다(ledgerRow 에 판만 얹는다).
func ledgerRowV(session string, seq int64, v int16) index.RewindRow {
	r := ledgerRow(session, seq)
	r.TimingVersion = v
	return r
}

// 동결 DVR 의 두 입구 — 목록 행(selectOwner)과 창을 재는 경계 입력(ledgerSnapshot) — 이 판을 옮긴다. 렌더한 닫힌 판에는
// 첫 조각 MAP 뒤 판 태그 「:2」 한 줄이 선다.
func TestFreezeDVRRowsCarryTimingVersion(t *testing.T) {
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "S", TargetDuration: 6, InitUploaded: true, MinSeq: 0},
	}}
	for seq := int64(0); seq <= 3; seq++ {
		l.Rows = append(l.Rows, ledgerRowV("S", seq, 2))
	}

	pl, ok := ledgerPlaylist(l, "S", nil)

	if !ok || len(pl.Rows) != 4 {
		t.Fatalf("목록 = %v(ok %t), want seq 0..3", seqsOf(pl.Rows), ok)
	}
	for _, r := range pl.Rows {
		if r.TimingVersion != 2 {
			t.Errorf("목록 행 seq %d 판 = %d, want 2", r.Seq, r.TimingVersion)
		}
	}
	for _, r := range (ledgerSnapshot{l}).RowsFrom(0) {
		if r.TimingVersion != 2 {
			t.Errorf("경계 입력 행 seq %d 판 = %d, want 2", r.Seq, r.TimingVersion)
		}
	}
	pl.StreamID, pl.BaseURL, pl.Ended = fxStream, fxBaseURL, true
	if body := string(mustRender(t, pl)); strings.Count(body, "#EXT-X-POKECLIP-TIMING:") != 1 ||
		!strings.Contains(body, ".mp4\"\n#EXT-X-POKECLIP-TIMING:2\n") {
		t.Errorf("닫힌 판 본문의 판 태그가 첫 조각 MAP 뒤 「:2」 한 줄이 아니다:\n%s", body)
	}
}

// VOD plan 의 행 — 사슬 행은 판을 옮기고, fillGap 을 지난 비귀속 행(판 2 · 판 NULL)도 제 판이 남는다. 판 2 사슬 안의 판
// NULL 비귀속 행은 그 조각에 「:0」, 다음 판 2 조각에 「:2」 태그를 받는다.
func TestVODPlanKeepsTimingVersionThroughFillGap(t *testing.T) {
	chain := index.Chain{StreamID: fxStream, Head: "S", Tail: "S", SessionIDs: []string{"S"}}
	v := &vodJob{chain: chain}
	l := index.ChainLedger{Chain: chain, HasCutoff: true, Rows: []index.RewindRow{
		ledgerRowV("S", 0, 2), ledgerRowV("", 1, 2), ledgerRowV("", 2, 0), ledgerRowV("S", 3, 2),
	}}

	plan, out, ok := v.plan(l, false)

	if !ok || len(plan.rows) != 4 {
		t.Fatalf("plan = %+v(%+v · ok %t), want 행 넷", plan.rows, out, ok)
	}
	for i, want := range []int16{2, 2, 0, 2} {
		if r := plan.rows[i]; r.TimingVersion != want || (i == 1 || i == 2) != r.IsGap {
			t.Errorf("plan 행 seq %d = 판 %d · GAP %t, want 판 %d · GAP %t", r.Seq, r.TimingVersion, r.IsGap, want, i == 1 || i == 2)
		}
	}
	pl := rewind.Playlist{StreamID: fxStream, BaseURL: fxBaseURL, Owner: "S", Kind: rewind.KindVOD, Ended: true,
		Sessions: []rewind.Session{{ID: "S", TargetDuration: 6, InitUploaded: true}}, Rows: plan.rows}
	body := string(mustRender(t, pl))
	if got := timingLines(body); strings.Join(got, ",") != "#EXT-X-POKECLIP-TIMING:2,#EXT-X-POKECLIP-TIMING:0,#EXT-X-POKECLIP-TIMING:2" {
		t.Errorf("VOD 본문의 판 태그 = %q, want 첫 조각 :2 · seq 2 :0 · seq 3 :2\n%s", got, body)
	}
}

// timingLines 는 본문의 판 태그 줄들이다(본문 순서).
func timingLines(body string) []string {
	var out []string
	for _, line := range strings.Split(body, "\n") {
		if strings.HasPrefix(line, "#EXT-X-POKECLIP-TIMING") {
			out = append(out, line)
		}
	}
	return out
}
