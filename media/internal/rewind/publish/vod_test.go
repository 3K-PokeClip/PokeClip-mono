package publish

// VOD plan 의 적재 상한 시험이다(POK-196 M6-2 · 보안 sec-m61-c4 M1 — 사슬 행 적재 상한 · too_large 조기 판정).

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
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

// vod_chain_too_large(보안 78acee38 L-1) — 적재가 상한에서 끊은 사슬(index.ErrChainTooLarge)은 재시도가 아니라
// too_large 실패다. 변이 = 갈래 삭제 → 장부 읽기 재시도(freezeLedger)로 FAIL.
func TestVODJudgeFailsChainTooLarge(t *testing.T) {
	v := &vodJob{chain: index.Chain{StreamID: fxStream, Head: "S", Tail: "S", SessionIDs: []string{"S"}}}

	_, _, out, ok := v.judge(index.ChainLedger{}, fmt.Errorf("적재: %w", index.ErrChainTooLarge), false)
	if ok || out.Result != ResultFailed || out.Reason != vodTooLarge {
		t.Errorf("judge(ErrChainTooLarge) = (%+v, %t), want too_large 실패", out, ok)
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

// gapKeyLedger 는 사슬 P ← S(회차 P — 회차 id 는 머리 세션 id)의 장부다. P 의 행 0 · 1 · S 의 행 3 · 5 는 settled 이고
// 행 2 · 4 는 비귀속(③ 키 없음)이다. 행 seq 의 ③ 키는 key(seq)다 — 꼴은 부르는 쪽이 정한다.
func gapKeyLedger(key func(seq int64) string) index.ChainLedger {
	l := index.ChainLedger{
		Chain:     index.Chain{StreamID: fxStream, Head: "P", Tail: "S", SessionIDs: []string{"P", "S"}},
		HasCutoff: true,
		Sessions: []index.RewindSession{
			{SessionID: "P", TargetDuration: 6, InitUploaded: true, MinSeq: 0},
			{SessionID: "S", InheritsSession: "P", TargetDuration: 6, InitUploaded: true, MinSeq: 3},
		},
	}
	for seq, session := range []string{"P", "P", "", "S", "", "S"} {
		r := ledgerRow(session, int64(seq))
		if session != "" {
			r.PlaybackS3Key = key(int64(seq))
		}
		l.Rows = append(l.Rows, r)
	}
	return l
}

// legacyKey · roundKey 는 픽스처 스트림의 옛 꼴 · 회차 P 꼴 조각 키다.
func legacyKey(seq int64) string { return fmt.Sprintf("dvr/%s/seg/%06d.m4s", fxStream, seq) }
func roundKey(seq int64) string  { return fmt.Sprintf("dvr/%s/P/seg/%06d.m4s", fxStream, seq) }

// e12_gap_key_follows_prev_row(POK-525 설계 2-1 · E12) — 비귀속 행의 GAP 키는 앞 행 키의 꼴을 따른다: 앞 행이 회차 꼴이면
// 그 회차의 회차 꼴, 옛 꼴이면 옛 꼴이다. 목록은 렌더 그대로 발행 전 검사(S5)를 지나고, 본문은 손으로 적은 골든
// (rewind/testdata/vod_gap_key_*.m3u8)과 바이트로 같다. 섞인 목록은 켬 전(옛 꼴 0 · 1) · 뒤(회차 꼴 3 · 5) 행이 한 회차에
// 있다 — 행 2 는 옛 꼴, 행 4 는 회차 꼴 GAP 이다. 행 4 의 회차는 S 이지만 키의 회차는 앞 행 키의 P 다(회차 id ≠ 세션 id).
// 변이 = 사슬 회차(머리 P)로 회차 꼴을 지어냄 → 옛 꼴 · 섞인 목록의 행 2 가 회차 꼴이 되어 FAIL.
func TestVODGapKeyFollowsPrevRowForm(t *testing.T) {
	tests := []struct {
		name   string
		key    func(int64) string
		golden string
	}{
		{name: "옛_꼴", key: legacyKey, golden: "vod_gap_key_legacy.m3u8"},
		{name: "회차_꼴", key: roundKey, golden: "vod_gap_key_round.m3u8"},
		{name: "섞임", key: func(seq int64) string {
			if seq < 3 {
				return legacyKey(seq)
			}
			return roundKey(seq)
		}, golden: "vod_gap_key_mixed.m3u8"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			l := gapKeyLedger(tt.key)
			v := &vodJob{p: &Publisher{opt: Options{BaseURL: fxBaseURL}}, chain: l.Chain}
			want, err := os.ReadFile(filepath.Join("..", "testdata", tt.golden))
			if err != nil {
				t.Fatalf("골든 읽기 실패: %v", err)
			}

			plan, out, ok := v.plan(l, false)
			if !ok {
				t.Fatalf("plan = %+v, want 통과", out)
			}
			pl := v.playlist(l, plan.rows)
			body := mustRender(t, pl)

			if err := rewind.Validate(pl, body, 1, nil); err != nil {
				t.Errorf("발행 전 검사(S5) 실패: %v", err)
			}
			if !bytes.Equal(body, want) {
				t.Errorf("VOD 본문이 골든 %s 와 다르다 — got:\n%s", tt.golden, body)
			}
		})
	}
}

// e12_settle_deadline_null_key_is_legacy(POK-525 설계 2-1 · E12 예외) — 정산 기한 GAP 은 앞 행이 아니라 자기 행을 본다
// (fillGap(r, r)). 그래서 회차 꼴 목록에서 ③ 키가 비어(NULL) 미착지로 정산 기한을 맞은 행은 옛 꼴 GAP 이 되고, 그 뒤
// 비귀속 행도 그 GAP 을 앞 행으로 따라 옛 꼴이 된다 — 목록에 두 꼴이 섞이지만 발행 전 검사(S5)는 스트림만 보므로 지난다.
// 픽스처: 회차 P 꼴 행 0 · 1 · 4(settled) · P 의 행 2(키 NULL · 미착지) · 비귀속 행 3, 정산 창 끝(abandon).
// 변이 = 정산 기한 GAP 이 앞 행(plan 의 마지막 행)을 봄 → 행 2 · 3 이 회차 꼴이 되어 FAIL.
func TestVODSettleDeadlineGapWithNullKeyIsLegacyForm(t *testing.T) {
	l := index.ChainLedger{
		Chain:     index.Chain{StreamID: fxStream, Head: "P", Tail: "P", SessionIDs: []string{"P"}},
		HasCutoff: true,
		Sessions:  []index.RewindSession{{SessionID: "P", TargetDuration: 6, InitUploaded: true, MinSeq: 0}},
	}
	for seq, session := range []string{"P", "P", "P", "", "P"} {
		r := ledgerRow(session, int64(seq))
		if session != "" {
			r.PlaybackS3Key = roundKey(int64(seq))
		}
		if seq == 2 {
			r.PlaybackS3Key, r.PlaybackUploaded = "", false
		}
		l.Rows = append(l.Rows, r)
	}
	v := &vodJob{p: &Publisher{opt: Options{BaseURL: fxBaseURL}}, chain: l.Chain}

	plan, out, ok := v.plan(l, true)

	if !ok {
		t.Fatalf("plan = %+v, want 통과", out)
	}
	want := []string{roundKey(0), roundKey(1), legacyKey(2), legacyKey(3), roundKey(4)}
	for i, r := range plan.rows {
		if i >= len(want) || r.PlaybackS3Key != want[i] {
			t.Errorf("행 %d 키 = %q, want %q", i, r.PlaybackS3Key, want[min(i, len(want)-1)])
		}
	}
	if len(plan.rows) != len(want) {
		t.Fatalf("행 수 = %d, want %d", len(plan.rows), len(want))
	}
	wantMarks := []vodMark{{seq: 2, session: "P", reason: "settle_deadline"}, {seq: 3, session: "P", reason: "mixed_boundary"}}
	if fmt.Sprint(plan.marks) != fmt.Sprint(wantMarks) {
		t.Errorf("GAP 원장 = %+v, want %+v", plan.marks, wantMarks)
	}
	if err := rewind.Validate(v.playlist(l, plan.rows), mustRender(t, v.playlist(l, plan.rows)), 1, nil); err != nil {
		t.Errorf("발행 전 검사(S5) 실패: %v", err)
	}
}

// gapKey 는 키를 지을 수 없으면(스트림 성분이 키 문법 밖) 빈 키를 돌려준다 — 지어낸 키 대신 빈 키라 발행 전 검사(S5)가
// 그 목록을 막는다. 변이 = 오류를 버리고 fmt 로 키를 지어냄 → FAIL.
func TestVODGapKeyIsEmptyWhenKeyCannotBeDerived(t *testing.T) {
	v := &vodJob{chain: index.Chain{StreamID: "a/b"}}

	for _, prev := range []string{"", "dvr/s/seg/000001.m4s", "dvr/s/P/seg/000001.m4s"} {
		if got := v.gapKey(2, prev); got != "" {
			t.Errorf("gapKey(2, %q) = %q, want 빈 키", prev, got)
		}
	}
}
