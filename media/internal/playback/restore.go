package playback

// 이 파일은 ③ 시간 도장 사슬의 한 걸음이다(계획 4.2-R R3 · POK-196 PDT 계획 4절 c10a · 설계 r6 2.10 「순수 함수」).
// 실시간 사슬(indexer/chain.go)과 복원이 같은 함수를 불러, 같은 입력이면 같은 도장을 낸다. DB · 파일 · 시계를 모른다 —
// 앞 행 파일을 다시 읽는 이음 끝(StitchRule)과 같은 녹화기 이어짐 판정(index.SameInstance)은 부르는 쪽이 준다. 이
// 패키지는 index 를 import 하지 않는다 — index 의 패키지 안 시험이 이 패키지를 import 해 순환이 생긴다.
//
// 조각 k 의 도장은 pos(k) = mtxi(k) + offset 이다. 평소 offset 은 0 이고, 회차 안에서 리셋(같은 녹화기 이어짐이 아님 ∨
// 역행)이나 같은 녹화기 안 구멍이 나면 직전 조각 끝에 이어 붙이도록 바뀐다.

import (
	"bytes"
	"errors"
	"fmt"
	"math"
	"math/big"
	"time"

	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4"
)

// mtxiGapTolerance 는 같은 녹화기 안에서 구멍으로 보지 않는 이웃 차 d 의 상한이다(kty 결정 G).
// d 는 직전 조각의 끝(영상 축이면 영상 끝, 아니면 mtxi + 장부 길이)부터 이 조각 시작까지다 — 이 값을
// 넘게 늦게 시작하면 그 유실 구간을 압축해 잇는다. 정확히 이 값은 구멍이 아니다 — 같은 연결 안 조각
// 경계의 어긋남은 영상 ±1틱(11µs)이다.
const mtxiGapTolerance = 50 * time.Millisecond

// videoRewindLimit 은 영상 축 이웃 차 d 를 역행(리셋)으로 보는 문턱이다 — d ≤ −1ms 면 리셋이다(ADR-073
// 결정 2 개정 「리셋 = d(k) ≤ −1ms」). 1ms 미만의 음수는 같은 연결 안 경계 어긋남이다.
const videoRewindLimit = time.Millisecond

// SourceTiming 은 사슬이 행 하나에서 쓰는 재료다 — ② 판독 값(mtxi)과 장부 칸(회차 · 원래 사실 칸 · 길이).
type SourceTiming struct {
	Seq int64
	// SessionID 는 행의 회차다. 빈 값이면 비귀속 행이다 — 사슬을 움직이지 않고 ③ 도 없다(규칙 ⑥).
	SessionID string
	// Mtxi 는 머리말의 녹화기 상자다. nil 이면 도장을 기대값에 고정한다(머리말에 mtxi 없음 · 판독 포기 — 규칙 ④ⓐ).
	Mtxi *Mtxi
	// Recorder · Number 는 장부의 녹화기 표식 · 번호 칸이다(nil: mtxi 없음 · 번호 넘침).
	Recorder *[16]byte
	Number   *int64
	// VideoFirst · VideoEnd 는 장부의 영상 두 칸(mtxi 누적 DTS 축)이다. nil 이면 영상 칸이 없다.
	VideoFirst, VideoEnd *time.Duration
	// Dur 은 장부 길이(duration_ms)다.
	Dur time.Duration
}

// Link 는 사슬이 도장을 찍은 회차 귀속 행 하나의 보관값이다 — 다음 행의 술어 재료와 그 행의 도장.
type Link struct {
	Seq       int64
	SessionID string
	// Recorder · Number 는 이어짐 판정 재료다. 고정 행이면 기대값(앞 행과 같은 녹화기의 다음 번호)이다.
	Recorder *[16]byte
	Number   *int64
	// VideoEnd 는 영상 축 이웃 차의 재료(b)다. 고정 행이면 nil 이다 — 다음 행은 mtxi 축으로 잰다.
	VideoEnd *time.Duration
	// Mtxi 는 누적 DTS 다. 고정 행이면 기대값(직전 mtxi + 장부 길이)이다.
	Mtxi time.Duration
	// Dur 은 장부 길이다. 실시간 사슬은 그 행이 꼬리에서 물러날 때 교정 뒤 값으로 덮는다.
	Dur    time.Duration
	Pos    time.Duration
	Offset time.Duration
	// Pinned 는 mtxi 가 없어 Pos 를 기대값(직전 pos + 장부 길이)으로 고정했는가다.
	Pinned bool
}

// StitchRule 은 리셋 때 이을 앞 행의 끝(③ 축)이다 — 배포 판의 이음 끝 규칙 하나다(지금은 결정 F: 먼저 끝난 트랙의
// 끝). 실시간과 복원이 같은 규칙을 넘긴다. 리셋일 때만 불린다 — 앞 행 파일을 다시 읽는 비용이 그때만 든다.
type StitchRule func(prev Link) time.Duration

// Continues 는 cur 가 prev 와 같은 녹화기의 잇단 조각인가다 — 부르는 쪽이 index.SameInstance 로 정한다(정의는 한 자리).
type Continues func(prev Link, cur SourceTiming) bool

// StartLink 는 회차의 첫 도장이다 — offset 0, 도장 = 자기 mtxi(규칙 ⑥ · 결정 B′). mtxi 가 없으면 도장을 지어내지
// 않는다(④ⓑ): ok 가 거짓이고, 다음 정상 판독 행이 사슬을 연다.
func StartLink(cur SourceTiming) (link Link, ok bool) {
	if cur.Mtxi == nil {
		return Link{}, false
	}
	return Link{
		Seq: cur.Seq, SessionID: cur.SessionID, Recorder: cur.Recorder, Number: cur.Number, VideoEnd: cur.VideoEnd,
		Mtxi: cur.Mtxi.DTS, Dur: cur.Dur, Pos: cur.Mtxi.DTS,
	}, true
}

// NextLink 는 같은 회차 안 다음 행의 보관값이다. continues 는 prev 가 장부의 직전 행이고 같은 녹화기 이어짐인가다(부르는
// 쪽이 정한다 — 끼어든 비귀속 행이 있으면 거짓).
//
// mtxi 가 없으면 도장을 막지 않고 기대값에 고정한다(④ⓐ): pos = pos(k−1) + dur(k−1), 사슬은 기대 mtxi · 표식으로
// 잇는다 — 그 자리에 실제 리셋 · 구멍이 있었다면 다음 행의 술어가 잡으므로 도장은 뒤로 가지 않는다.
func NextLink(prev Link, continues bool, cur SourceTiming, rule StitchRule) Link {
	next := Link{Seq: cur.Seq, SessionID: cur.SessionID, Dur: cur.Dur}
	if cur.Mtxi == nil {
		next.Recorder, next.Number = expectedInstance(prev)
		next.Mtxi, next.Offset = prev.Mtxi+prev.Dur, prev.Offset
		next.Pos, next.Pinned = prev.Pos+prev.Dur, true
		return next
	}
	next.Recorder, next.Number, next.VideoEnd = cur.Recorder, cur.Number, cur.VideoEnd
	next.Mtxi = cur.Mtxi.DTS
	next.Offset = stitchOffset(prev, continues, cur, rule)
	next.Pos = next.Mtxi + next.Offset
	return next
}

// stitchOffset 은 같은 회차 안 다음 행 k 의 보정값이다(R3 산식 · POK-196 PDT 계획 4절 c8). prev 는 k−1 의 링크다.
//
//	리셋(¬같은 녹화기 이어짐 ∨ 역행)             offset := end(k−1) − mtxi(k)
//	  또는 사이에 비귀속 행이 끼었다(⑥)
//	구멍(같은 녹화기 이어짐 ∧ d > 허용치)         offset := pos(k−1) + dur(k−1) − mtxi(k)
//	그 밖                                        직전 보정값 그대로
//
// 술어는 prev 가 장부의 직전 행일 때만 뜻이 있다 — 끼어든 행이 있으면 구멍 · 연속을 판정할 기준이 없으므로 저장된
// 마지막 회차 행의 끝에 잇는다(끼어든 미디어는 회차 밖이라 압축된다). 역행 · d 는 neighborGap 이 잰다.
func stitchOffset(prev Link, continues bool, cur SourceTiming, rule StitchRule) time.Duration {
	if !continues {
		return rule(prev) - cur.Mtxi.DTS
	}
	d, rewound := neighborGap(prev, cur)
	switch {
	case rewound:
		return rule(prev) - cur.Mtxi.DTS
	case d > mtxiGapTolerance:
		return prev.Pos + prev.Dur - cur.Mtxi.DTS
	}
	return prev.Offset
}

// neighborGap 은 같은 녹화기 이어짐인 prev 와 다음 행 사이의 이웃 차 d 와, 그것이 역행(리셋)인가다.
//
//	영상 축(두 행 모두 영상 칸 있음)  d = a(k) − b(k−1)               역행 = d ≤ −1ms
//	mtxi 축(그 밖)                   d = mtxi(k) − (mtxi(k−1) + dur(k−1))  역행 = mtxi(k) < mtxi(k−1)
//
// 영상 축이 먼저다 — 장부 길이가 영상 길이(판 2)면 mtxi(조각 시작 = 머리 트랙 시작) + 장부 길이는 소리 머리
// 조각의 머리 앞섬만큼 영상 끝보다 이르러 거짓 구멍(실측 최대 226ms)을 낸다(ADR-073 결정 2 개정 「이웃 술어는
// 영상 축이다」). 영상 칸이 없는 행(소리만 송출 · 기대값에 고정한 앞 행)은 mtxi 축으로 잰다.
func neighborGap(prev Link, cur SourceTiming) (d time.Duration, rewound bool) {
	if prev.VideoEnd != nil && cur.VideoFirst != nil {
		d = *cur.VideoFirst - *prev.VideoEnd
		return d, d <= -videoRewindLimit
	}
	return cur.Mtxi.DTS - (prev.Mtxi + prev.Dur), cur.Mtxi.DTS < prev.Mtxi
}

// expectedInstance 는 고정 행이 드는 기대 표식이다 — 앞 행과 같은 녹화기의 다음 번호로 둬, 그 자리에 실제 리셋이
// 없었으면 다음 행이 같은 녹화기 이어짐으로 잇는다. 앞 행의 표식이 nil 이면 기대값도 nil 이라 다음 행은 리셋이다.
func expectedInstance(prev Link) (*[16]byte, *int64) {
	if prev.Recorder == nil || prev.Number == nil {
		return nil, nil
	}
	next := *prev.Number + 1
	return prev.Recorder, &next
}

// RestoreChain 은 NextLink 를 seq 순으로 접는다 — 실시간 사슬의 뼈대(규칙 ① · ⑥)와 같다. 같은 입력이면 같은 출력이다.
//
// rows 는 장부에서 잇단 행들이다(비귀속 행 포함 — 인접 판정 재료). anchor 가 있으면 rows[0] 의 바로 앞 장부 행이
// anchor 의 행이다. 돌려주는 것은 도장을 찍은 행의 링크뿐이다(비귀속 행 · 사슬 없이 mtxi 를 못 읽은 행은 없다).
// rows 의 Dur · VideoEnd 는 교정이 끝난 장부 값이라고 본다 — 실시간 사슬이 꼬리에서 물러날 때 덮는 값과 같다.
func RestoreChain(anchor *Link, rows []SourceTiming, rule StitchRule, same Continues) []Link {
	fold := NewChainFold(anchor, rule, same)
	var links []Link
	for _, cur := range rows {
		if l, ok := fold.Step(cur); ok {
			links = append(links, l)
		}
	}
	return links
}

// ChainFold 는 RestoreChain 의 한 걸음씩 판이다(POK-196 PDT 계획 4절 c10b) — 복원이 행마다 원천을 읽어 가며 접고, 이미 있는 ③
// 객체를 채택하면 그 행의 링크를 객체의 도장으로 바꿔(Replace) 다음 행을 잇는다.
type ChainFold struct {
	rule     StitchRule
	same     Continues
	chain    Link
	hasChain bool
	prevSeq  int64
}

// NewChainFold 는 anchor 뒤에서 접기 시작한다. anchor 가 nil 이면 첫 귀속 행이 사슬을 연다.
func NewChainFold(anchor *Link, rule StitchRule, same Continues) *ChainFold {
	f := &ChainFold{rule: rule, same: same}
	if anchor != nil {
		f.chain, f.hasChain, f.prevSeq = *anchor, true, anchor.Seq
	}
	return f
}

// Step 은 장부 행 cur 하나만큼 접는다. ok 가 거짓이면 그 행에는 도장이 없다(비귀속 행 · 사슬 없이 mtxi 를 못 읽은 행).
func (f *ChainFold) Step(cur SourceTiming) (Link, bool) {
	// 인접 = 바로 앞 장부 행이 사슬 행이다(실시간의 prev.Seq == c.seq).
	adjacent := f.hasChain && f.prevSeq == f.chain.Seq
	f.prevSeq = cur.Seq
	switch {
	case cur.SessionID == "":
		return Link{}, false
	case !f.hasChain || f.chain.SessionID != cur.SessionID:
		l, ok := StartLink(cur)
		if ok {
			f.chain, f.hasChain = l, true
		}
		return l, ok
	}
	f.chain = NextLink(f.chain, adjacent && f.same(f.chain, cur), cur, f.rule)
	return f.chain, true
}

// Replace 는 방금 접은 행의 링크를 l 로 바꾼다 — 다음 행은 l 에서 잇는다.
func (f *ChainFold) Replace(l Link) {
	f.chain, f.hasChain = l, true
}

// ErrStampMismatch 는 ③ 객체가 그 ② 조각을 어떤 도장 위치로 재포장해도 나올 수 없는 바이트라는 뜻이다(다른 조각 · 다른 판본의
// 입력으로 만든 객체).
var ErrStampMismatch = errors.New("playback: ③ 객체가 이 ② 조각의 재포장이 아니다")

// StampWindow 는 ③ 객체의 도장 위치 범위 [Lo, Hi] 다 — 이 안의 어느 위치로 재포장해도 객체와 같은 바이트다(트랙마다 위치를
// 그 트랙의 틱으로 반올림해 더하므로 한 점으로 되짚을 수 없다 — 폭은 영상 90kHz 반 틱 안쪽이다).
type StampWindow struct{ Lo, Hi time.Duration }

// Contains 는 p 가 범위 안인가다.
func (w StampWindow) Contains(p time.Duration) bool { return w.Lo <= p && p <= w.Hi }

// Pick 은 범위 안의 첫 힌트를, 없으면 범위 가운데를 고른다. 힌트는 보정값 후보로 만든 위치다(보정값 0 · 표의 줄) — 범위
// 안의 어느 값이든 이 행의 바이트는 같지만, 다음 행의 보정값(pos − mtxi)이 실시간과 같으려면 실제 값을 골라야 한다.
//
// exact 는 고른 값을 실제 값으로 볼 수 있는가다 — 힌트가 범위 안에 들었다. 범위 폭(수 µs)보다 작은 보정값 차는 리셋 · 구멍
// 이음(ms 단위)에서 나오지 않으므로 힌트가 들면 그것이 실제 값이다. 가운데를 고른 값은 실제 값과 범위 폭만큼 다를 수 있다.
func (w StampWindow) Pick(hints ...time.Duration) (p time.Duration, exact bool) {
	for _, h := range hints {
		if w.Contains(h) {
			return h, true
		}
	}
	return w.Lo + (w.Hi-w.Lo)/2, w.Lo == w.Hi
}

// StampOf 는 ③ 객체 obj(moof+mdat)가 ② 조각 src 를 어느 도장 위치로 재포장한 것인지의 범위다(설계 r6 2.10 「닻 되살리기」).
// 남긴 두 트랙(첫 영상 · 첫 소리 — Produce 와 같은 선택)마다 첫 표본 파트의 BaseTime 차를 그 트랙의 틱으로 읽고, 그 틱으로
// 반올림되는 위치 범위들의 교집합을 낸다. 녹화기 인스턴스 첫 조각은 첫 파트에 영상이 없을 수 있어(소리가 먼저 쌓인다) 트랙마다
// 표본이 있는 첫 파트를 본다. 표본 열이 다르거나 교집합이 비면 ErrStampMismatch 다.
func StampOf(src, obj []byte) (StampWindow, error) {
	rec, err := parseRecording(src)
	if err != nil {
		return StampWindow{}, err
	}
	var parts fmp4.Parts
	if err := parts.Unmarshal(obj); err != nil {
		return StampWindow{}, fmt.Errorf("%w: 객체 본문: %v", ErrStampMismatch, err)
	}
	w := StampWindow{Lo: 0, Hi: math.MaxInt64}
	for _, tr := range rec.tracks {
		shift, err := trackShift(rec.parts, parts, tr.ID)
		if err != nil {
			return StampWindow{}, err
		}
		lo, hi := tickWindow(shift, tr.TimeScale)
		w.Lo, w.Hi = max(w.Lo, lo), min(w.Hi, hi)
	}
	if w.Lo > w.Hi {
		return StampWindow{}, fmt.Errorf("%w: 트랙별 도장 범위가 겹치지 않는다", ErrStampMismatch)
	}
	return w, nil
}

// trackShift 는 한 트랙의 객체 BaseTime − 원천 BaseTime 이다(표본이 있는 첫 파트끼리). 표본 열(개수 · 길이 · 내용)이 다르면
// 같은 원천의 재포장이 아니다.
func trackShift(src, obj fmp4.Parts, trackID int) (uint64, error) {
	a, b := firstSampled(src, trackID), firstSampled(obj, trackID)
	if a == nil || b == nil || b.BaseTime < a.BaseTime || !sameSamples(src, obj, trackID) {
		return 0, fmt.Errorf("%w: 트랙 %d", ErrStampMismatch, trackID)
	}
	return b.BaseTime - a.BaseTime, nil
}

// firstSampled 는 표본이 있는 그 트랙의 첫 파트 트랙이다.
func firstSampled(parts fmp4.Parts, trackID int) *fmp4.PartTrack {
	for _, p := range parts {
		for _, tr := range p.Tracks {
			if tr.ID == trackID && len(tr.Samples) > 0 {
				return tr
			}
		}
	}
	return nil
}

// sameSamples 는 두 파트 열에서 한 트랙의 표본(길이 · 내용)이 순서대로 같은가다.
func sameSamples(a, b fmp4.Parts, trackID int) bool {
	sa, sb := samplesOf(a, trackID), samplesOf(b, trackID)
	if len(sa) != len(sb) {
		return false
	}
	for i := range sa {
		if sa[i].Duration != sb[i].Duration || !bytes.Equal(sa[i].Payload, sb[i].Payload) {
			return false
		}
	}
	return true
}

func samplesOf(parts fmp4.Parts, trackID int) []*fmp4.Sample {
	var out []*fmp4.Sample
	for _, p := range parts {
		for _, tr := range p.Tracks {
			if tr.ID == trackID {
				out = append(out, tr.Samples...)
			}
		}
	}
	return out
}

// tickWindow 는 ticks(p, ts) = t 인 위치 p 의 범위 [lo, hi] 다 — ticks 가 ⌊(p·ts + 10^9/2) ÷ 10^9⌋ 이므로
//
//	lo = ⌈(t·10^9 − 10^9/2) ÷ ts⌉ · hi = ⌈((t+1)·10^9 − 10^9/2) ÷ ts⌉ − 1
//
// 곱이 int64 를 넘을 수 있어(t·10^9 — 90kHz 에서 약 28시간) 큰 수로 계산한다. 음수 위치는 없다(Produce 가 막는다).
func tickWindow(t uint64, ts uint32) (lo, hi time.Duration) {
	half, sec, scale := big.NewInt(int64(time.Second/2)), big.NewInt(int64(time.Second)), big.NewInt(int64(ts))
	at := func(n uint64) *big.Int { // ⌈(n·10^9 − 10^9/2) ÷ ts⌉
		v := new(big.Int).Mul(new(big.Int).SetUint64(n), sec)
		v.Sub(v, half)
		v.Add(v, new(big.Int).Sub(scale, big.NewInt(1)))
		return v.Div(v, scale) // 제수가 양수라 Div(유클리드)는 내림이다 — 음수(n = 0)는 아래에서 0 으로 자른다
	}
	l, h := at(t), at(t+1)
	h.Sub(h, big.NewInt(1))
	return time.Duration(max(l.Int64(), 0)), time.Duration(h.Int64())
}
