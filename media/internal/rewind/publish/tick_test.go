package publish

// 절대식 순수 함수(계획 4.5 A1 결정 1 · 4 · 체크리스트 A-9) — PG 없이 돈다.

import (
	"errors"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// A1 결정 1 — 다음 DISC-SEQ = P 의 DISC-SEQ + P 의 행에서 빠지는 끊김 표시 수. P = 사슬 픽스처의 창 100..999
// (DISC-SEQ 5). 표시는 P 의 첫 조각 100 과 S 의 첫 조각 600 앞에 선다.
//
//	다음 MSN 100   빠지는 행 없음                     5
//	다음 MSN 101   100 (표시)                          6
//	다음 MSN 600   100..599 — 표시는 100 하나          6
//	다음 MSN 601   100..600 — 표시 100 · 600          7
//
// 축출이 없는 틱(다음 MSN = P 의 MSN)은 DISC-SEQ 를 바꾸지 않는다(계획 4.5 F 6.4 음성 대조 · r40 판).
func TestNextDiscontinuitySequenceIsAbsolute(t *testing.T) {
	f := chainFixture(1600)
	prev := rewind.Published{Gen: 7, MediaSequence: 100, PublishedSeq: 999, SegmentCount: 900, DiscontinuitySequence: 5}
	rows := f.window(100, 999)
	tests := []struct {
		nextMSN int64
		want    int64
	}{
		{100, 5},
		{101, 6},
		{600, 6},
		{601, 7},
	}
	for _, tt := range tests {
		got, err := NextDiscontinuitySequence(prev, rows, tt.nextMSN)
		if err != nil || got != tt.want {
			t.Errorf("NextDiscontinuitySequence(P 100..999 · D 5, MSN %d) = (%d, %v), want (%d, nil)", tt.nextMSN, got, err, tt.want)
		}
	}
}

// reconstructed_prev_mismatch_halts_and_requests_reload(계획 4.5 A1 결정 4 · 판단 J3) — P 의 행은 따로 들지 않고
// 캐시에서 다시 뽑는다. 뽑은 행이 P 의 자기기술과 맞지 않으면(첫 행 seq = pc-msn · 행 수 = pc-seg-count · 끝 행
// seq = pc-pub-seq) 세지 않고 멈춤을 돌려준다 — 힌트 P.MSN 으로 적재를 요구하게. 뷰 하한이 P.MSN 보다 뒤인
// 캐시(첫 경우)에서 세면 100 의 표시를 빠뜨려 7 이 아니라 6 을 낸다 — 덜 센 DISC-SEQ 는 남은 조각의 번호를
// 바꾼다. 루프 종단(캐시 추출 · 적재 요구 · prev_mismatch 로그)은 커밋 7 이다.
func TestReconstructedPrevMismatchHaltsAndRequestsReload(t *testing.T) {
	f := chainFixture(1600)
	prev := rewind.Published{Gen: 7, MediaSequence: 100, PublishedSeq: 999, SegmentCount: 900, DiscontinuitySequence: 5}
	gapped := f.window(100, 999)
	gapped.Rows = append(without(gapped, 999).Rows, fxRow("S", 1000)) // 100..998 + 1000 — 첫 행 · 행 수는 맞다
	early := f.window(100, 999)
	early.Rows = append([]boundary.Row{fxRow("P", 99)}, without(early, 100).Rows...) // 99 + 101..999 — 행 수 · 끝 행은 맞다
	tests := []struct {
		name string
		rows rewind.Playlist
	}{
		{"뷰_하한이_P_MSN_뒤", f.window(101, 999)},
		{"첫_행만_다름", early},
		{"행을_놓침", without(f.window(100, 999), 500)},
		{"끝_행이_다름", gapped},
		{"행_없음", rewind.Playlist{StreamID: fxStream, Owner: "S"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := NextDiscontinuitySequence(prev, tt.rows, 601)

			var mismatch *PrevMismatchError
			if !errors.As(err, &mismatch) {
				t.Fatalf("NextDiscontinuitySequence = (%d, %v), want *PrevMismatchError(발행 멈춤)", got, err)
			}
			if mismatch.LoadHint != 100 {
				t.Errorf("적재 힌트 = %d, want P.MSN 100", mismatch.LoadHint)
			}
		})
	}
}

// 대조를 통과한 행이라도 빠지는 행의 회차를 모르면 셀 수 없다 — 표시 여부를 모르는 행을 건너뛰면 덜 센다.
// 이것은 재구성 불일치가 아니라 입력 결함이다(rewind.EvictedDiscontinuityTags 의 오류를 그대로 돌려준다).
func TestNextDiscontinuitySequenceRejectsUnknownSession(t *testing.T) {
	f := chainFixture(1600)
	prev := rewind.Published{Gen: 7, MediaSequence: 100, PublishedSeq: 999, SegmentCount: 900, DiscontinuitySequence: 5}
	rows := f.window(100, 999)
	rows.Sessions = rows.Sessions[1:] // P 를 뺀다

	got, err := NextDiscontinuitySequence(prev, rows, 601)

	var mismatch *PrevMismatchError
	if err == nil || errors.As(err, &mismatch) {
		t.Errorf("NextDiscontinuitySequence = (%d, %v), want 재구성 불일치가 아닌 오류", got, err)
	}
}
