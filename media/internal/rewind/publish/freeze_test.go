package publish

import (
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// ledgerRow 는 픽스처 행 fxRow 의 장부 값이다(session "" 면 비귀속 — 회차 · PDT · ③ 키가 NULL).
func ledgerRow(session string, seq int64) index.RewindRow {
	r := fxRow(session, seq)
	if session == "" {
		return index.RewindRow{Seq: seq, DurationMS: r.DurationMS}
	}
	return index.RewindRow{Seq: r.Seq, SessionID: r.SessionID, DurationMS: r.DurationMS, PlaybackPDT: r.PlaybackPDT,
		PlaybackS3Key: r.PlaybackS3Key, PlaybackUploaded: true}
}

// 직전 판 P 의 MSN 아래로는 목록이 내려가지 않는다(설계 4.1 R1 2 · S2). 창 계산의 꼬리가 P.MSN 앞(1시간에 못 미쳐 컷오프)
// 으로 가도, 그 사이 소유 · 접두 회차 행은 앞 판들이 이미 축출한 것이다 — 평시 틱의 창(직전 꼬리부터)과 같은 목록이다.
//
// 사슬 P ← S 에서 P 의 행 0..9 · 비귀속 행 10 · S 의 행 11..20 이고, 앞 판들이 P 를 다 축출해 P.MSN = 11 이다.
func TestLedgerPlaylistNeverGoesBelowPrevMSN(t *testing.T) {
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "P", InheritsSession: "O", TargetDuration: 6, InitUploaded: true, MinSeq: 0},
		{SessionID: "S", InheritsSession: "P", TargetDuration: 6, InitUploaded: true, MinSeq: 11, DiscontinuityBase: 2},
	}}
	for seq := int64(0); seq <= 20; seq++ {
		switch {
		case seq < 10:
			l.Rows = append(l.Rows, ledgerRow("P", seq))
		case seq == 10:
			l.Rows = append(l.Rows, ledgerRow("", seq))
		default:
			l.Rows = append(l.Rows, ledgerRow("S", seq))
		}
	}
	prev := &Manifest{}
	prev.MediaSequence, prev.PublishedSeq = 11, 15

	pl, ok := ledgerPlaylist(l, "S", prev)

	if !ok || len(pl.Rows) == 0 || pl.Rows[0].Seq != 11 || pl.Rows[len(pl.Rows)-1].Seq != 20 {
		t.Fatalf("목록 = %v(ok %t), want seq 11..20", seqsOf(pl.Rows), ok)
	}
}
