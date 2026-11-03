package index

// 판 2 섞기 식(설계 r7 2.5 · 2.6 · 2.7). 순수 함수만 있다 — DB · 파일 · 시계를 모른다. 입출력 타입은
// pdt_mix_types.go 에 있다.
//
//	갈래                         PDT     start_pts_ms                이름
//	하한 거부                    w       q.start(q 없으면 0)         round_first(floor_rejected)
//	R 없음                       w       q.start + q.dur(〃)         round_first(no_round)
//	R 새 회차                    w       〃                          round_first(new_round · stream_first)
//	R 있음 ∧ ¬새 회차
//	  ∧ (p 없음 ∨ p.판 ≠ 2)      w       〃                          round_first + timing_round_broken
//	p 있음 ∧ p.판 = 2:  E = PDT(p) + dur(p) · S = PDT(p)
//	  ¬comparable                E       p.start + PDT − PDT(p)      continue
//	  |Δ| ≤ θ   (Δ = w − E)      E       〃                          join
//	  Δ > θ                      w       〃                          new_chapter_gap
//	  Δ < −θ                     max(w, E − 999, E − dur(k) + 1, S + 1)
//	                                     〃                          new_chapter_overlap
//
// 겹침 갈래는 K1 = (나) 1초 미만 부분 겹침이다(kty 2026-11-03 · 나중 조각 우선 · B8 세 조건). 바뀔 축은
// θ 값과 겹침 갈래 둘뿐이고 둘 다 이 파일 안에 있다(설계 3절 · D1 · D4). 인터페이스 · 전략
// 객체는 두지 않는다.

import (
	"math"
	"time"
)

// MixThreshold 는 섞기 문턱 θ 다. 판 2 규약의 일부이고 운영 설정이 아니다 — 스위치 2 뒤에 바꾸면 열린
// 판 2 회차가 규칙을 바꾸게 되므로 판을 올린다(설계 2.9 · D4).
//
// K3 — 스위치 2 전 실측으로 확정. 지금 값은 시험 초기값 140ms 다(설계 2.9 조건 셋 · 실측 1 θ 후보표).
const MixThreshold = 140 * time.Millisecond

// maxOverlap 은 겹침 갈래의 한 번 당김 상한이다 — HLS bis-22 6.2.1 「1초 미만」. K1-c = 999ms 확정
// (kty 2026-11-03 — θ 로 줄이면 VideoToolbox 송출의 자연 겹침 212–512ms 가 한 번에 흡수되지 않는다).
const maxOverlap = 999 * time.Millisecond

// versionV2 는 이 식을 쓰는 판(timing_version)이다.
const versionV2 int16 = 2

// 갈래 이름 밖의 신호 값(TimingV2 칸 주석의 목록).
const (
	resetStreamFirst   = "stream_first"
	resetNewRound      = "new_round"
	resetNoRound       = "no_round"
	resetFloorRejected = "floor_rejected"

	brokenPrevMissing     = "prev_missing"
	brokenVersionMismatch = "version_mismatch"

	limitedByWall       = "w"
	limitedByOverlapCap = "overlap_cap"
	limitedByEndLead    = "end_lead"
	limitedByStartLead  = "start_lead"
)

// MixV2 는 판 2 행 k 의 PDT · 위치 · 길이와 갈래를 정한다(설계 2.5 · 2.6). v 는 k 를 쓰는 트랜잭션이
// 장부에서 읽은 것이고, in 은 지금 조각 파일의 재료다. in.Facts.Wall(짐작 w)은 부르는 쪽이 늘 채운다
// (판 2 행의 timing_wall_guess 는 CHECK 넷째로 NOT NULL).
func MixV2(v LedgerView, in TimingInput) TimingV2 {
	return mixV2(v, in, MixThreshold)
}

// mixV2 는 MixV2 의 몸이다. 문턱을 인자로 받아 실측 1(문턱 재생)이 θ 후보를 같은 식으로 돌린다.
func mixV2(v LedgerView, in TimingInput, theta time.Duration) TimingV2 {
	w := in.Facts.Wall.UnixMilli()
	if out, ok := roundFirst(v, in, w); ok {
		return out
	}
	p := v.Prev
	dur, videoKnown := mixDuration(p, in)
	s := *p.PDTMS
	e := s + int64(p.DurationMS)
	out := TimingV2{DurationMS: dur, SameInstance: SameInstance(p.Facts, in.Facts)}
	out.Compared = in.HeadVideo || !out.SameInstance || !videoKnown
	out.PDTMS, out.Branch = e, BranchContinue
	if out.Compared {
		out.DeltaMS = w - e
		out.PDTMS, out.Branch, out.LimitedBy = compare(out.DeltaMS, theta.Milliseconds(), w, s, e, dur)
	}
	// 위치 보폭 = PDT 보폭이라 기준점 PDT − start 가 회차 안 상수다(설계 2.11). 앞 끝 하한은 두지
	// 않는다(예측표 P12).
	out.StartPTSMS = p.StartPTSMS + out.PDTMS - s
	return out
}

// roundFirst 는 같은 회차 판 2 앞 행이 없는 갈래다 — PDT = w(설계 2.5 위 네 줄). ok 가 거짓이면 p 가
// 있고 판 2 라 섞기로 간다.
func roundFirst(v LedgerView, in TimingInput, w int64) (TimingV2, bool) {
	out := TimingV2{PDTMS: w, Branch: BranchRoundFirst}
	var qStart, qEnd int64
	if v.Last != nil {
		qStart = v.Last.StartPTSMS
		qEnd = qStart + int64(v.Last.DurationMS)
	}
	// 회차 첫 행은 앞 행과 잇지 않으므로 길이도 자기 영상 길이다.
	out.DurationMS, _ = mixDuration(nil, in)
	out.StartPTSMS = qEnd
	switch {
	case v.FloorRejected:
		// 하한 거부 행은 위치를 나아가지 않는다 — q.dur 만큼 나아가면 다음 회차 행이 겹침일 때 seq
		// 순 start 단조(I10)가 깨진다(설계 2.4).
		out.ResetReason, out.StartPTSMS = resetFloorRejected, qStart
	case v.Round == nil:
		out.ResetReason = resetNoRound
	case v.NewRound && v.Last == nil:
		out.ResetReason = resetStreamFirst
	case v.NewRound:
		out.ResetReason = resetNewRound
	case v.Prev == nil:
		out.Broken = brokenPrevMissing
	case v.Prev.Version == nil || *v.Prev.Version != versionV2 || v.Prev.PDTMS == nil:
		// 판 2 행은 CHECK 넷째로 PDT 를 갖는다 — PDT 없는 앞 행은 판 2 행으로 볼 수 없어 판 불일치로
		// 접는다.
		out.Broken = brokenVersionMismatch
	default:
		return TimingV2{}, false
	}
	return out, true
}

// compare 는 견줄 조각의 갈래다 — Δ = w − E 를 θ(ms)와 견준다.
func compare(delta, theta, w, s, e int64, dur int32) (int64, Branch, string) {
	switch {
	case delta > theta:
		return w, BranchNewChapterGap, ""
	case delta >= -theta:
		return e, BranchJoin, ""
	}
	pdt, by := overlapPDT(w, s, e, dur)
	return pdt, BranchNewChapterOverlap, by
}

// overlapPDT 는 겹침 갈래의 식 max(w, E − 999, E − dur(k) + 1, S + 1) 과 이긴 항이다. 네 항은 모두 E
// 이하라 B8 세 조건(시작 · 1초 · 끝이 앞선다)을 함께 만족한다(설계 2.7). 같으면 앞 항이 이긴다.
func overlapPDT(w, s, e int64, dur int32) (int64, string) {
	pdt, by := w, limitedByWall
	for _, term := range []struct {
		v  int64
		by string
	}{
		{e - maxOverlap.Milliseconds(), limitedByOverlapCap},
		{e - int64(dur) + 1, limitedByEndLead},
		{s + 1, limitedByStartLead},
	} {
		if term.v > pdt {
			pdt, by = term.v, term.by
		}
	}
	return pdt, by
}

// mixDuration 은 판 2 행의 길이와 영상 앎(videoKnown)이다(설계 2.6). 맞닿음이면 B(k) − B(p), 아니면
// B(k) − A(k)(B · A = ns 칸의 ms 내림, mtxi 가 없으면 파일 안 영상 길이). 고른 영상 길이가 1ms
// 미만이거나 int32 ms 를 넘으면 mvhd 를 쓰고 videoKnown 은 거짓이다 — 상한은 인덱서가 칸을 비우는
// video_span_invalid 와 같은 경계다(조각 하나의 영상이 24일을 넘을 수 없다 · 좁히면 부호가 뒤집힌다).
// p 가 nil 이면 맞닿음을 보지 않는다.
func mixDuration(p *PrevRow, in TimingInput) (int32, bool) {
	f := in.Facts
	own := int64(in.VideoOwnMS)
	switch {
	case p != nil && Contiguous(p.Facts, f):
		own = floorMS(*f.VideoEnd) - floorMS(*p.Facts.VideoEnd)
	case f.VideoFirst != nil:
		own = floorMS(*f.VideoEnd) - floorMS(*f.VideoFirst)
	}
	if own >= 1 && own <= math.MaxInt32 {
		return int32(own), true
	}
	// H7(indexer measure — mvhd 길이 0 이하면 행을 쓰지 않음)이 1 이상을 보장한다. 어긴 입력도 B8 첫
	// 조건(dur ≥ 1)을 지키도록 1 로 올린다.
	return max(in.LegacyDurationMS, 1), false
}

// floorMS 는 d 를 ms 로 내린다(음수도 아래로).
func floorMS(d time.Duration) int64 {
	ms := d.Milliseconds()
	if d < 0 && d%time.Millisecond != 0 {
		ms--
	}
	return ms
}
