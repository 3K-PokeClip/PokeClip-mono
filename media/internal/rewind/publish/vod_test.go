package publish

// VOD plan 의 적재 상한 시험이다(POK-196 M6-2 · 보안 sec-m61-c4 M1 — 사슬 행 적재 상한 · too_large 조기 판정).

import (
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// vodLedgerOf 는 회차 S 하나에 settled 행 0..n-1 을 실은 사슬 장부다.
func vodLedgerOf(n int) index.ChainLedger {
	l := index.ChainLedger{Chain: index.Chain{StreamID: fxStream, Head: "S", Tail: "S", SessionIDs: []string{"S"}}}
	l.Rows = make([]index.RewindRow, n)
	for i := range l.Rows {
		l.Rows[i] = ledgerRow("S", int64(i))
	}
	return l
}

// vod_rows_beyond_body_limit_fail_early(보안 c4 M1) — 렌더하면 반드시 MaxVODBodyBytes 를 넘을 행 수(vodMaxRows 초과)는
// 렌더 · 등재 전에 too_large 실패다. 상한 그 자체까지는 통과한다. 변이 = 조기 판정 삭제 → 초과 장부가 통과해 FAIL.
func TestVODPlanRejectsRowsBeyondBodyLimit(t *testing.T) {
	v := &vodJob{chain: index.Chain{StreamID: fxStream, Head: "S", Tail: "S", SessionIDs: []string{"S"}}}

	if _, out, ok := v.plan(vodLedgerOf(vodMaxRows), false); !ok {
		t.Errorf("행 %d 개 plan = %+v, want 통과(상한 그 자체)", vodMaxRows, out)
	}
	_, out, ok := v.plan(vodLedgerOf(vodMaxRows+1), false)
	if ok || out.Result != ResultFailed || out.Reason != vodTooLarge {
		t.Errorf("행 %d 개 plan = (%+v, %t), want too_large 실패", vodMaxRows+1, out, ok)
	}
}

// vodMinRowBytes 는 VOD 본문에서 조각 하나가 차지하는 바이트의 하한이다 — 가장 짧은 값(1ms · 한 글자 BaseURL · 한 글자
// 키)의 조각 하나가 렌더에 더하는 바이트가 그 이상이어야 조기 판정이 정상 문서를 거부하지 않는다.
func TestVODMinRowBytesIsLowerBound(t *testing.T) {
	row := func(seq int64) boundary.Row {
		return boundary.Row{Seq: seq, SessionID: "S", DurationMS: 1, PlaybackPDT: fxDay.Add(time.Duration(seq) * time.Millisecond),
			PlaybackS3Key: "k", PlaybackUploaded: true}
	}
	render := func(n int64) int {
		pl := rewind.Playlist{StreamID: fxStream, BaseURL: "h", Owner: "S", Kind: rewind.KindVOD, Ended: true,
			Sessions: []rewind.Session{{ID: "S", TargetDuration: 1, InitUploaded: true}}}
		for seq := range n {
			pl.Rows = append(pl.Rows, row(seq))
		}
		return len(mustRender(t, pl))
	}

	if got := render(2) - render(1); got < vodMinRowBytes {
		t.Errorf("가장 짧은 조각 하나 = %d바이트, want ≥ vodMinRowBytes %d", got, vodMinRowBytes)
	}
}
