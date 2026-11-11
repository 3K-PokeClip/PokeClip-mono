package index

// 원래 사실 칸과 그 칸 위의 술어(설계 2.1 · 2.3 · 3절). 순수 함수만 있다 — DB ·
// 파일 · 시계를 모른다.
//
// 「같은 녹화기 이어짐」 의 정의는 여기 하나다. 판 2 섞기(store) · ③ 사슬
// (indexer/chain.go · 복원 upload/restore.go) · is_discontinuity(indexer
// buildRecord)가 모두 SameInstance 를 부른다. 정의가 두 벌이면 한쪽만 고쳐져 같은
// 행을 두 곳이 다르게 잇는다.

import "time"

// contiguousSlack 은 맞닿음으로 보는 영상 경계 차의 상한(미포함)이다 — 설계 2.1 의
// |a(k) − b(p)| < 1ms. 같은 연결 안 조각 경계의 어긋남은 영상 ±1틱(11µs)이다
// (playback/restore.go mtxiGapTolerance 주석).
const contiguousSlack = time.Millisecond

// TimingFacts 는 장부 행의 원래 사실 칸이다. 생산자 기록이고 소비자 계약이
// 아니다. 출시 이미지가 쓴 행은 Wall 이 영값이고 나머지가 nil 이다.
//
// 포인터 칸의 nil 은 SQL NULL 이다. NULL 짝(표식 · 번호, 영상 두 칸)은 장부
// CHECK 가 강제하고, 번호가 2^63 이상이면 표식 · 번호 · 영상 두 칸을 함께
// nil 로 둔다(설계 2.3).
type TimingFacts struct {
	Wall       time.Time      // timing_wall_guess(ms 내림)
	Recorder   *[16]byte      // timing_recorder_id · nil: mtxi 없음 · 번호 넘침
	Number     *int64         // timing_segment_number · Recorder 와 함께 nil
	VideoFirst *time.Duration // timing_video_first_ns
	VideoEnd   *time.Duration // timing_video_end_ns · VideoFirst 와 함께 nil
}

// SameInstance 는 prev 와 cur 가 같은 녹화기 인스턴스의 잇단 조각인가다 — 표식이
// 둘 다 있고 같으며 cur 의 번호가 prev 의 번호 + 1 이다. ③ 사슬 술어
// (indexer/chain.go)도 이것을 부른다 — 정의는 여기 하나다.
//
// 표식이나 번호가 한쪽이라도 nil 이면 거짓이다. 모르는 것끼리를 같다고 보면
// mtxi 없는 두 조각을 잇게 된다(변이 m21).
func SameInstance(prev, cur TimingFacts) bool {
	if prev.Recorder == nil || cur.Recorder == nil || prev.Number == nil || cur.Number == nil {
		return false
	}
	return *prev.Recorder == *cur.Recorder && *cur.Number == *prev.Number+1
}

// Contiguous 는 같은 녹화기 이어짐(SameInstance)이면서 cur 의 영상 첫 DTS 와
// prev 의 영상 끝의 차가 1ms 미만인가다. 판 2 길이(설계 2.6 — pdt_mix.go)에만
// 쓴다. ③ 사슬은 SameInstance 위에서 이웃 차를 따로 잰다(playback.neighborGap —
// 문턱이 다르다).
//
// 영상 칸이 한쪽이라도 nil 이면 거짓이다 — 맞닿았는지 알 재료가 없다.
func Contiguous(prev, cur TimingFacts) bool {
	if !SameInstance(prev, cur) || prev.VideoEnd == nil || cur.VideoFirst == nil {
		return false
	}
	gap := *cur.VideoFirst - *prev.VideoEnd
	return -contiguousSlack < gap && gap < contiguousSlack
}
