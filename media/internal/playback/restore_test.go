package playback

import (
	"bytes"
	"reflect"
	"testing"
	"time"

	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4"
)

// 이 파일은 ③ 사슬의 한 걸음(NextLink)과 그 접기(RestoreChain)를 본다(POK-196 PDT 계획 4절 c10a · 설계 r6 2.10
// 「순수 함수」). 실시간 사슬과 같은 결과인지는 indexer 의 사슬 시험이 접기 대조로 본다(chain_test.go).

// stitchAt 은 앞 행의 끝을 at 으로 주는 이음 규칙이다 — 부른 앞 행을 calls 에 모은다(리셋 때만 불려야 한다).
func stitchAt(at time.Duration, calls *[]int64) StitchRule {
	return func(prev Link) time.Duration {
		*calls = append(*calls, prev.Seq)
		return at
	}
}

// sameRecorder 는 시험용 이어짐 술어다 — 표식이 같고 번호 +1 이다(index.SameInstance 와 같은 뜻을 시험 안에서만 쓴다).
func sameRecorder(prev Link, cur SourceTiming) bool {
	if prev.Recorder == nil || prev.Number == nil || cur.Recorder == nil || cur.Number == nil {
		return false
	}
	return *prev.Recorder == *cur.Recorder && *cur.Number == *prev.Number+1
}

func durPtr(d time.Duration) *time.Duration { return &d }

func int64Ptr(n int64) *int64 { return &n }

// source 는 표식 rec · 번호 n · mtxi dts 로 읽힌 귀속 행이다(영상 칸 없음 · 장부 길이 4초).
func source(seq int64, rec [16]byte, n int64, dts time.Duration) SourceTiming {
	return SourceTiming{Seq: seq, SessionID: "S1", Mtxi: &Mtxi{RecorderID: rec, DTS: dts}, Recorder: &rec,
		Number: int64Ptr(n), Dur: 4 * time.Second}
}

// NextLink 의 갈래(계획 4.2-R R3 · POK-196 PDT 계획 4절 c8): 같은 녹화기 이어짐이면 직전 보정값 그대로, 50ms 넘는 구멍은
// 앞 pos + 장부 길이에, 이어짐이 아니거나 역행이면 이음 규칙의 끝에 잇는다. 영상 칸이 두 행에 다 있으면 영상 축으로 잰다.
func TestNextLinkBranches(t *testing.T) {
	recA, recB := [16]byte{0xaa}, [16]byte{0xbb}
	prev := Link{Seq: 0, SessionID: "S1", Recorder: &recA, Number: int64Ptr(1), Mtxi: 10 * time.Second,
		Dur: 4 * time.Second, Pos: 10 * time.Second}
	videoPrev := prev
	videoPrev.VideoEnd = durPtr(14_226 * time.Millisecond)
	withVideo := func(s SourceTiming, first time.Duration) SourceTiming {
		s.VideoFirst = durPtr(first)
		return s
	}
	tests := []struct {
		name       string
		prev       Link
		continues  bool
		cur        SourceTiming
		wantPos    time.Duration
		wantRuleOn bool
	}{
		{"이어짐은_보정값_그대로", prev, true, source(1, recA, 2, 14_050*time.Millisecond), 14_050 * time.Millisecond, false},
		{"50ms_넘는_구멍은_앞_끝에", prev, true, source(1, recA, 2, 14_050*time.Millisecond+1), 14 * time.Second, false},
		{"mtxi_역행은_리셋", prev, true, source(1, recA, 2, 9*time.Second), 13_900 * time.Millisecond, true},
		{"이어짐이_아니면_리셋", prev, false, source(1, recB, 1, 0), 13_900 * time.Millisecond, true},
		{"영상_축_맞닿음은_잇는다", videoPrev, true, withVideo(source(1, recA, 2, 14_226*time.Millisecond), 14_226*time.Millisecond), 14_226 * time.Millisecond, false},
		{"영상_축_1ms_역행은_리셋", videoPrev, true, withVideo(source(1, recA, 2, 14_225*time.Millisecond), 14_225*time.Millisecond), 13_900 * time.Millisecond, true},
		// 영상 칸이 한쪽에만 있으면 mtxi 축이다(c8 검수 cc L2 — 영상 끝 없는 고정 행 다음 영상 행 · 소리만 송출 행).
		{"앞만_영상_칸은_mtxi_축", videoPrev, true, source(1, recA, 2, 14_050*time.Millisecond), 14_050 * time.Millisecond, false},
		{"뒤만_영상_칸은_mtxi_축", prev, true, withVideo(source(1, recA, 2, 14_050*time.Millisecond), 14_226*time.Millisecond), 14_050 * time.Millisecond, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var calls []int64

			got := NextLink(tt.prev, tt.continues, tt.cur, stitchAt(13_900*time.Millisecond, &calls))

			if got.Pos != tt.wantPos || got.Offset != tt.wantPos-tt.cur.Mtxi.DTS || got.Pinned {
				t.Errorf("링크 = pos %v · offset %v · 고정 %v, want pos %v · offset %v · 고정 false",
					got.Pos, got.Offset, got.Pinned, tt.wantPos, tt.wantPos-tt.cur.Mtxi.DTS)
			}
			if ruled := len(calls) > 0; ruled != tt.wantRuleOn {
				t.Errorf("이음 규칙 호출 = %v, want %v — 앞 행 파일은 리셋 때만 다시 읽는다", calls, tt.wantRuleOn)
			}
		})
	}
}

// 고정 행(mtxi 없음 — 결정적 부재)은 같은 바이트를 다시 넣으면 같은 링크다(설계 r6 2.9 「복원이 같은 바이트에서 같은 고정을
// 다시 낸다」). 실물 조각의 머리말에서 mtxi 만 걷어 ReadTiming 으로 두 번 읽는다. 고정 링크는 앞 pos + 앞 장부 길이에 서고,
// 기대 표식(앞 행과 같은 녹화기의 다음 번호)을 든다 — 영상 칸은 비운다.
func TestNextLinkPinsRowWithoutMtxiDeterministically(t *testing.T) {
	in := withHeader(t, readFixture(t, fixture4s), func(ini *fmp4.Init) { ini.UserData = nil })
	rec := [16]byte{0xaa}
	prev := Link{Seq: 4, SessionID: "S1", Recorder: &rec, Number: int64Ptr(7), VideoEnd: durPtr(14 * time.Second),
		Mtxi: 10 * time.Second, Dur: 4 * time.Second, Pos: 12 * time.Second, Offset: 2 * time.Second}
	var links []Link
	for range 2 {
		st, err := ReadTiming(bytes.NewReader(in))
		if err != nil || st.Mtxi != nil {
			t.Fatalf("ReadTiming = (mtxi %v, %v), want (nil, nil) — 전제", st.Mtxi, err)
		}
		var calls []int64
		links = append(links, NextLink(prev, false, SourceTiming{Seq: 5, SessionID: "S1", Mtxi: st.Mtxi, Dur: 3 * time.Second},
			stitchAt(0, &calls)))
		if len(calls) != 0 {
			t.Errorf("고정 행이 이음 규칙을 불렀다: %v", calls)
		}
	}

	if !reflect.DeepEqual(links[0], links[1]) {
		t.Fatalf("같은 바이트 두 번 = %+v · %+v, want 같은 링크", links[0], links[1])
	}
	got := links[0]
	if got.Pos != 16*time.Second || got.Offset != 2*time.Second || got.Mtxi != 14*time.Second || !got.Pinned {
		t.Errorf("고정 링크 = pos %v · offset %v · mtxi %v · 고정 %v, want 16s · 2s · 14s(기대값) · 고정",
			got.Pos, got.Offset, got.Mtxi, got.Pinned)
	}
	if got.Recorder == nil || *got.Recorder != rec || got.Number == nil || *got.Number != 8 || got.VideoEnd == nil ||
		*got.VideoEnd != 17*time.Second || got.Dur != 3*time.Second {
		t.Errorf("고정 링크 칸 = 표식 %v · 번호 %v · 영상 끝 %v · 길이 %v, want 같은 표식 · 8 · 17s(앞 영상 끝 + 길이) · 3s",
			got.Recorder, got.Number, got.VideoEnd, got.Dur)
	}
}

// 앞 행에 영상 끝이 없으면 고정 행의 기대 영상 끝도 없다 — 다음 행은 mtxi 축으로 잰다(지어낸 영상 끝으로 재지 않는다).
func TestNextLinkPinnedRowAfterRowWithoutVideoHasNoVideoEnd(t *testing.T) {
	rec := [16]byte{0xaa}
	prev := Link{Seq: 0, SessionID: "S1", Recorder: &rec, Number: int64Ptr(1), Mtxi: 10 * time.Second, Dur: 4 * time.Second,
		Pos: 10 * time.Second}

	got := NextLink(prev, false, SourceTiming{Seq: 1, SessionID: "S1", Dur: 4 * time.Second}, stitchAt(0, new([]int64)))

	if got.VideoEnd != nil || !got.Pinned {
		t.Errorf("고정 링크 영상 끝 = %v · 고정 %v, want nil · 고정", got.VideoEnd, got.Pinned)
	}
}

// 앞 행의 표식이 없으면 고정 행의 기대 표식도 없다 — 다음 행은 리셋이다(모르는 것끼리를 같다고 보지 않는다).
func TestNextLinkPinnedRowAfterUnknownInstanceHasNoInstance(t *testing.T) {
	prev := Link{Seq: 0, SessionID: "S1", Mtxi: 10 * time.Second, Dur: 4 * time.Second, Pos: 10 * time.Second}

	got := NextLink(prev, false, SourceTiming{Seq: 1, SessionID: "S1", Dur: 4 * time.Second}, stitchAt(0, new([]int64)))

	if got.Recorder != nil || got.Number != nil || !got.Pinned {
		t.Errorf("고정 링크 표식 = %v · 번호 %v · 고정 %v, want nil · nil · 고정", got.Recorder, got.Number, got.Pinned)
	}
}

// RestoreChain 은 NextLink 를 seq 순으로 접는다 — 실시간 사슬의 뼈대(계획 4.2-R 규칙 ① · ⑥)와 같다.
//
//	닻(seq 3) 바로 뒤 행은 인접이다 · 비귀속 행은 링크가 없고 다음 행을 비인접으로 만든다(이음 규칙의 끝에 잇는다)
//	회차가 바뀌면 그 행 자기 mtxi 에서 새로 연다 · 회차 첫 행을 못 읽었으면 링크를 짓지 않는다
//
// seq 6 의 번호(5)는 사슬 행 seq 4 의 번호 + 1 이다 — 이어짐 술어로는 잇기라, 비인접 판정만이 리셋을 가른다(비귀속 행의
// seq 를 건너뛰고 인접을 재면 4s 구멍 갈래로 18s 에 잇는다).
func TestRestoreChainFoldsLikeLiveChain(t *testing.T) {
	recA, recB := [16]byte{0xaa}, [16]byte{0xbb}
	anchor := Link{Seq: 3, SessionID: "S1", Recorder: &recA, Number: int64Ptr(3), Mtxi: 10 * time.Second,
		Dur: 4 * time.Second, Pos: 10 * time.Second}
	unattributed := SourceTiming{Seq: 5, Mtxi: &Mtxi{RecorderID: recA, DTS: 18 * time.Second}, Dur: 4 * time.Second}
	unreadOpening := SourceTiming{Seq: 7, SessionID: "S2", Dur: 4 * time.Second}
	opening := source(8, recB, 1, 30*time.Second)
	opening.SessionID = "S2"
	rows := []SourceTiming{source(4, recA, 4, 14*time.Second), unattributed, source(6, recA, 5, 22*time.Second), unreadOpening, opening}
	var calls []int64

	got := RestoreChain(&anchor, rows, stitchAt(17_500*time.Millisecond, &calls), sameRecorder)

	type stamp struct {
		seq         int64
		pos, offset time.Duration
		session     string
		pinned      bool
	}
	var stamps []stamp
	for _, l := range got {
		stamps = append(stamps, stamp{l.Seq, l.Pos, l.Offset, l.SessionID, l.Pinned})
	}
	want := []stamp{
		{4, 14 * time.Second, 0, "S1", false},
		{6, 17_500 * time.Millisecond, -4_500 * time.Millisecond, "S1", false},
		{8, 30 * time.Second, 0, "S2", false},
	}
	if !reflect.DeepEqual(stamps, want) {
		t.Errorf("접기 = %+v\nwant %+v", stamps, want)
	}
	if !reflect.DeepEqual(calls, []int64{4}) {
		t.Errorf("이음 규칙 호출 = %v, want [4] — 비귀속 행 건너 이음 한 번", calls)
	}
}

// 닻의 보정값이 0 이 아니면 닻 바로 뒤 이어짐 행은 그 보정값을 잇는다 — 닻에서 연다(c10a 검수 cc M1). 잡는 결함: 닻을
// 무시하고 회차 첫 행처럼 열면(StartLink — offset 0) 도장이 닻 보정값(2초)만큼 앞선다. 닻 Offset 0 인 시험은 두 구현이
// 같은 값을 내 이 결함을 가르지 못한다.
func TestRestoreChainContinuesAnchorOffset(t *testing.T) {
	rec := [16]byte{0xaa}
	anchor := Link{Seq: 3, SessionID: "S1", Recorder: &rec, Number: int64Ptr(3), Mtxi: 10 * time.Second,
		Dur: 4 * time.Second, Pos: 12 * time.Second, Offset: 2 * time.Second}

	got := RestoreChain(&anchor, []SourceTiming{source(4, rec, 4, 14*time.Second)}, stitchAt(0, new([]int64)), sameRecorder)

	if len(got) != 1 || got[0].Pos != 16*time.Second || got[0].Offset != 2*time.Second {
		t.Errorf("접기 = %+v, want seq 4 · pos 16s · offset 2s 하나(닻 보정값을 잇는다)", got)
	}
}

// 닻이 없으면 회차 첫 정상 판독 행이 offset 0 으로 연다(startChain 과 같다 — 결정 B′).
func TestRestoreChainWithoutAnchorOpensAtFirstReadableRow(t *testing.T) {
	rec := [16]byte{0xaa}
	rows := []SourceTiming{{Seq: 0, SessionID: "S1", Dur: 4 * time.Second}, source(1, rec, 2, 604*time.Second)}

	got := RestoreChain(nil, rows, stitchAt(0, new([]int64)), sameRecorder)

	if len(got) != 1 || got[0].Seq != 1 || got[0].Pos != 604*time.Second || got[0].Offset != 0 {
		t.Errorf("접기 = %+v, want seq 1 · pos 604s · offset 0 하나", got)
	}
}

// 회차 첫 행의 mtxi 가 음수면(새 연결에서 소리가 영상보다 먼저 시작 — prod 실측 −44ms · POK-550) 첫 도장을 0 에 두는
// 보정값 −mtxi 로 연다. 보관 mtxi 는 원값이다 — 다음 행의 구멍 술어가 실제 미디어 끝을 기준으로 선다. 실시간 사슬
// (indexer startChain)과 복원 접기(ChainFold.Step)가 이 한 함수를 부른다. 잡는 결함: 음수 도장을 그대로 내면 생산자가
// ErrNegativePos 로 거부해 그 행의 ③ 이 첫 시도에 failed 로 끝난다 / 보관 mtxi 에 pos 를 넣으면 다음 행의 구멍을 놓친다.
func TestStartLinkOpensNegativeMtxiAtZero(t *testing.T) {
	rec := [16]byte{0xaa}
	for _, tt := range []struct {
		name        string
		dts         time.Duration
		pos, offset time.Duration
	}{
		{"음수_mtxi_는_0_에_연다", -44 * time.Millisecond, 0, 44 * time.Millisecond},
		{"1ns_음수도_0_에_연다", -1, 0, 1},
		{"0_은_그대로", 0, 0, 0},
		{"양수는_자기_mtxi", 604 * time.Second, 604 * time.Second, 0},
	} {
		t.Run(tt.name, func(t *testing.T) {
			l, ok := StartLink(source(1, rec, 1, tt.dts))

			if !ok || l.Pos != tt.pos || l.Offset != tt.offset || l.Mtxi != tt.dts {
				t.Errorf("StartLink = (pos %v · offset %v · mtxi %v, %v), want (pos %v · offset %v · mtxi %v 원값, true)",
					l.Pos, l.Offset, l.Mtxi, ok, tt.pos, tt.offset, tt.dts)
			}
		})
	}
}

// 닻 없이 회차 첫 행부터 접는 복원도 음수 첫 mtxi 를 0 에 열고, 그 보정값을 연속 행이 잇는다 — 실시간 사슬과 같은 도장이다
// (POK-550 · 복원 경로). 잡는 결함: 실시간 사슬에만 하한을 두면 재기동 뒤 복원 행이 음수 도장으로 생산자에서 거부된다.
func TestRestoreChainOpensNegativeFirstMtxiAtZero(t *testing.T) {
	rec := [16]byte{0xaa}
	rows := []SourceTiming{source(0, rec, 1, -44*time.Millisecond), source(1, rec, 2, -44*time.Millisecond+4*time.Second)}

	got := RestoreChain(nil, rows, stitchAt(0, new([]int64)), sameRecorder)

	if len(got) != 2 || got[0].Pos != 0 || got[0].Offset != 44*time.Millisecond ||
		got[1].Pos != 4*time.Second || got[1].Offset != 44*time.Millisecond {
		t.Errorf("접기 = %+v, want seq 0 · pos 0 · offset 44ms → seq 1 · pos 4s · offset 44ms", got)
	}
}
