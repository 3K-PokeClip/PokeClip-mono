package publish

// GAP 이 서는 갈래의 경계(설계 4.6.3 GAP_ELIGIBLE ④ ⑤ · 판단 J18 · J23 · c4-fix3 개정 3) — 적격의 양성 쪽과 판정
// 순서다(음성 쪽은 gap_pg_test.go). fake Store + PG 통합이다(PG_DSN 이 없으면 skip). GAP 틱 픽스처(newGapLoop ·
// gapURIs · readGaps)는 gap_pg_test.go 에 있다.

import (
	"slices"
	"testing"
)

// ④ 미확정은 pending 만이 아니다(설계 4.6.3 GAP_ELIGIBLE ④) — ③ 가 failed 인 k(재시도 소진 · 파일 없음 격리 · 산출
// 불가 · init 불일치 — 정체 조각의 대표 상태)에도 GAP 이 선다. 결과에 GapSeq 를 싣고 k 를 GAP 줄로 올리며, 그 발행의
// P4 가 원장 행을 확정한다. 서지 않으면 격리된 조각이 스트림 머리를 영영 막는다.
func TestGapTickStandsOnFailedUpload(t *testing.T) {
	l, store, _ := newGapLoop(t, 20, 6, "failed")

	out := l.gapTick(t.Context(), 0, 8, 6)

	if !out.Published || out.GapSeq == nil || *out.GapSeq != 6 || out.UploadedSeq != nil {
		t.Errorf("GAP 틱 = %+v, want 발행 · GapSeq 6 · UploadedSeq 없음", out)
	}
	if body, _ := stored(store, soloKey); !slices.Equal(gapURIs(body), []string{segURI(6)}) {
		t.Errorf("본문의 GAP 줄 %v, want [%s]", gapURIs(body), segURI(6))
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{6: "put_confirmed"})
}

// ⑤ 컷오프는 k 를 포함한다(설계 4.6.3 GAP_ELIGIBLE ⑤) — 되감기 첫 조각 k = 컷오프가 정체한 스트림은 아직 목록이
// 없다. 그 GAP 틱이 원장에 k 를 넣고 첫 목록을 k 부터(GAP 줄로) 올린다. 서지 않으면 목록이 영영 만들어지지
// 않는다(사다리 쪽 경계는 TestLadderGapAtCutoff).
func TestGapTickStandsAtCutoff(t *testing.T) {
	l, store, _ := newSoloLoop(t, 20)
	l.fx.rows[6].PlaybackUploaded = false
	insertLedgerRow(t, l.pub.pool, l.fx.rows[6], "pending")
	insertCutoff(t, l.pub.pool, 6)
	in, k := l.input(6, 8), int64(6)
	in.Playlist.Cutoff, in.GapCandidate = 6, &k

	out := l.run(t.Context(), in)

	if !out.Published || out.GapSeq == nil || *out.GapSeq != 6 {
		t.Errorf("GAP 틱 = %+v, want 첫 목록 발행 · GapSeq 6", out)
	}
	if body, _ := stored(store, soloKey); !slices.Equal(gapURIs(body), []string{segURI(6)}) {
		t.Errorf("첫 목록의 GAP 줄 %v, want [%s]", gapURIs(body), segURI(6))
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{6: "put_confirmed"})
}

// 원장이 ③ 을 이긴다(판단 J18 · J23 · 장부 388 G-1) — 원장에 k 의 행이 이미 있으면(COMMIT 결과 모름 뒤 · 적재 전) DB 가
// k 를 uploaded 로 봐도 GAP 이 선 것이다. 결과에 GapSeq 를 싣고 UploadedSeq 는 싣지 않으며 k 를 GAP 줄로 올린다.
// uploaded 를 먼저 보면 루프가 k 를 uploaded 로 반영해 평시 틱이 k 를 일반 줄로 올리고, 같은 문장의 P4 가 그 원장 행을
// 확정한 뒤 적재가 is_gap 을 실어 이미 발행한 줄이 GAP 으로 바뀐다.
func TestGapTickLedgerRowOutranksUpload(t *testing.T) {
	l, store, _ := newGapLoop(t, 20, 6, "uploaded")
	insertGap(t, l.pub.pool, fxStream, 6, "recorded")

	out := l.gapTick(t.Context(), 0, 8, 6)

	if !out.Published || out.GapSeq == nil || *out.GapSeq != 6 || out.UploadedSeq != nil {
		t.Errorf("GAP 틱 = %+v, want 발행 · GapSeq 6 · UploadedSeq 없음", out)
	}
	if body, _ := stored(store, soloKey); !slices.Equal(gapURIs(body), []string{segURI(6)}) {
		t.Errorf("본문의 GAP 줄 %v, want [%s]", gapURIs(body), segURI(6))
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{6: "put_confirmed"})
}
