package indexer

// 조각 판독값(playback.SegmentTiming)을 장부 재료(index.TimingInput)로 옮긴다(POK-196 PDT 설계 r7 2.2 w 식 · 2.3 NULL 규칙 ·
// 2.6 영상 길이 · 8절 신호). 해석(섞기 · 이어짐 판정)은 하지 않는다 — 그것은 store(index.MixV2)와 끊김 판정의 일이다.

import (
	"errors"
	"math"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// headLeadWarn 은 머리 앞섬 m 의 경고 문턱이다(설계 8절 head_lead_over_1s — m > 1000ms).
const headLeadWarn = time.Second

// w 의 출처(timing_facts_partial 의 w_source)다.
const (
	wallFromMtxi     = "mtxi"
	wallFromFilename = "filename"
)

// timingInputOf 는 조각 seg 의 판독 결과 read 와 mvhd 길이 durationMS 로 장부 재료를 만든다. source 는 w 의 출처이고, reasons
// 는 칸이 빈 까닭이다(timing_facts_partial — 순서는 판독 포기 · mtxi 없음 · 번호 넘침 · 영상 없음 · 영상 1ms 미만 · 영상
// 구간 이상).
//
//	w      mtxi 있음: ⌊mtxi.NTP + 영상 시작⌋ms(영상 없으면 ⌊mtxi.NTP⌋) · mtxi 없음: ⌊파일명 시각 + m⌋ms(영상 없으면 m 없음)
//	칸     표식 · 번호 = mtxi · 영상 두 칸 = mtxi.DTS + 영상 시작 · 끝. 번호가 2^63 이상이면 넷을 함께 NULL(CHECK 1–3)
//	길이   영상 끝 · 시작 각각의 ms 내림 차 · 1ms 미만이면 0(영상 길이 모름)
func timingInputOf(seg recording.Segment, read timingRead, durationMS int64) (in index.TimingInput, source string, reasons []string) {
	in.LegacyDurationMS = int32(durationMS)
	if read.st == nil {
		in.Facts.Wall = floorMS(seg.StartWall)
		return in, wallFromFilename, []string{read.failure}
	}
	st := read.st
	in.HeadVideo = st.Head.Video

	var base time.Duration // 영상 두 칸 · 길이의 축 — mtxi 가 있으면 녹화기 누적 DTS, 없으면 파일 안
	switch {
	case st.Mtxi == nil:
		source, reasons = wallFromFilename, append(reasons, "no_mtxi")
		in.Facts.Wall = seg.StartWall
		if st.Video != nil {
			in.Facts.Wall = in.Facts.Wall.Add(st.HeadLead)
		}
	default:
		source, base = wallFromMtxi, st.Mtxi.DTS
		in.Facts.Wall = st.Mtxi.NTP
		if st.Video != nil {
			in.Facts.Wall = in.Facts.Wall.Add(st.Video.Start)
		}
		if st.Mtxi.SegmentNumber > math.MaxInt64 {
			reasons = append(reasons, "number_overflow")
			break
		}
		recorder, number := st.Mtxi.RecorderID, int64(st.Mtxi.SegmentNumber)
		in.Facts.Recorder, in.Facts.Number = &recorder, &number
		if st.Video != nil {
			first, end := base+st.Video.Start, base+st.Video.End
			in.Facts.VideoFirst, in.Facts.VideoEnd = &first, &end
		}
	}
	in.Facts.Wall = floorMS(in.Facts.Wall)

	if st.Video == nil {
		return in, source, append(reasons, "no_video")
	}
	own := floorDivMS(base+st.Video.End) - floorDivMS(base+st.Video.Start)
	switch {
	case own < 1:
		return in, source, append(reasons, "under_1ms")
	case own > math.MaxInt32:
		// 파일 내용 검증 — 조각 하나의 영상이 24일을 넘을 수 없다. int32 로 좁히면 부호가 뒤집힌다.
		return in, source, append(reasons, "video_span_invalid")
	}
	in.VideoOwnMS = int32(own)
	return in, source, reasons
}

// timingInput 은 commit 이 부르는 자리다 — 재료를 만들고 칸이 빈 까닭 · 머리 앞섬 신호를 조각마다 한 번 남긴다(설계 8절
// timing_facts_partial · head_lead_over_1s). cur 의 꼬리(q)는 「인스턴스 첫 조각 여부」 의 비교 대상이다.
func (ix *Indexer) timingInput(cur *index.Cursor, seg recording.Segment, read timingRead, durationMS int64) index.TimingInput {
	in, source, reasons := timingInputOf(seg, read, durationMS)
	for _, reason := range reasons {
		attrs := []any{"stream_id", seg.StreamID, "path", seg.Path, "reason", reason, "w_source", source}
		if read.err != nil {
			attrs = append(attrs, "err", read.err)
		}
		ix.log.Warn("timing_facts_partial", attrs...)
	}
	if read.st != nil && read.st.HeadLead > headLeadWarn {
		first := cur.Tail == nil || !sameRecorder(cur.Tail.Timing, in.Facts)
		ix.log.Warn("head_lead_over_1s", "stream_id", seg.StreamID, "path", seg.Path,
			"head_lead_ms", read.st.HeadLead.Milliseconds(), "instance_first", first)
	}
	return in
}

// sameRecorder 는 q 와 k 가 같은 녹화기 인스턴스(표식)의 조각인가다 — 번호는 보지 않는다. head_lead_over_1s 의
// instance_first 재료다: 같은 녹화기에서 번호가 건너뛴 조각(가운데 조각이 장부에 못 들었다)은 인스턴스 첫 조각이 아니다(c5
// 검수 cc L2). 표식이 한쪽이라도 없으면 모름이라 거짓이다(첫 조각으로 본다 — 옛 판정과 같다).
func sameRecorder(q, k index.TimingFacts) bool {
	return q.Recorder != nil && k.Recorder != nil && *q.Recorder == *k.Recorder
}

// timingAttrs 는 segment_indexed 의 판 · 이어 계산 속성이다(설계 8절). prev 는 이 행 앞의 스트림 꼬리 q 다(없으면 nil).
// comparable · same_instance 는 판과 무관하게 싣는다(설계 8절 「조각마다(판 무관)」) — 판 2 행은 섞기가 정한 값(회차 첫 행은
// 거짓)이고, 판 NULL 행은 legacyComparable 이다. 견준 차 delta_ms 는 판 2 섞기가 견줬을 때만 싣는다.
func timingAttrs(prev *index.TailRow, in index.TimingInput, read timingRead, res index.InsertCommit) []any {
	head, lead := headTrack(read)
	attrs := []any{
		"timing_version", versionText(res.TimingVersion), "timing_branch", string(res.TimingBranch),
		"round_id", res.TimingRoundID, "head_track", head, "head_lead_ms", lead,
		"wall_ms", in.Facts.Wall.UnixMilli(), "attributed", res.SessionID != "",
	}
	if !isV2(res) {
		same, comparable := legacyComparable(prev, in)
		return append(attrs, "comparable", comparable, "same_instance", same)
	}
	attrs = append(attrs, "comparable", res.TimingCompared, "same_instance", res.TimingSameInstance)
	if res.TimingCompared {
		attrs = append(attrs, "delta_ms", res.DeltaMS)
	}
	return attrs
}

// legacyComparable 은 판 NULL 행의 견줄 조각 술어 comparable(k) = 머리 = 영상 ∨ ¬sameInstance(q, k) ∨ ¬videoKnown(k) 와
// 그 sameInstance 다(설계 2.1 — 판 2 섞기의 p 자리에 스트림 꼬리 q 를 둔다). 섞기 · 장부 쓰기와 무관한 관측값이다 — c0 가
// 판 NULL 로 도는 길들이기 동안 소리 머리 이어짐(K4 (A) 의 전제)을 prod 에서 보는 재료다(설계 2.16).
//
// 영상 앎은 자기 영상 길이(VideoOwnMS ≥ 1ms)로 본다. 판 2 섞기는 맞닿은 조각이면 길이를 앞 행 영상 끝부터 재지만
// (index.mixDuration), 맞닿음은 두 경계 차가 1ms 미만이라 두 길이는 1ms 안에서만 다르다.
func legacyComparable(prev *index.TailRow, in index.TimingInput) (same, comparable bool) {
	same = prev != nil && index.SameInstance(prev.Timing, in.Facts)
	return same, in.HeadVideo || !same || in.VideoOwnMS < 1
}

// headTrack 은 머리 트랙 이름과 머리 앞섬(ms)이다. 판독이 없으면 unknown · 0 이다.
func headTrack(read timingRead) (string, int64) {
	if read.st == nil {
		return "unknown", 0
	}
	if read.st.Head.Video {
		return "video", read.st.HeadLead.Milliseconds()
	}
	return "audio", read.st.HeadLead.Milliseconds()
}

// grownVideoEnd 는 꼬리 교정이 넘길 영상 끝(ns)이다 — 자란 조각을 한 번 다시 읽는다(설계 2.14). nil 이면 그 칸을 넘기지
// 않는다: 옛 값이 NULL(불변 트리거가 NULL → 값을 거부해 길이 교정까지 잃는다) · 새 값을 모름(번호 넘침 · 영상 없음 · 판독
// 실패) · 줄어든 값(트리거가 거부한다). ok 가 거짓이면 판독이 시간 초과라 교정을 그만둔다(래치 경로).
func (ix *Indexer) grownVideoEnd(seg recording.Segment, tail *index.TailRow, durationMS int64) (ns *int64, ok bool) {
	if tail.Timing.VideoEnd == nil {
		return nil, true
	}
	read, ok := ix.readTail(seg)
	if !ok {
		return nil, false
	}
	in, _, _ := timingInputOf(seg, read, durationMS)
	if in.Facts.VideoEnd == nil || *in.Facts.VideoEnd < *tail.Timing.VideoEnd {
		return nil, true
	}
	end := int64(*in.Facts.VideoEnd)
	return &end, true
}

// readTail 은 꼬리 교정의 판독이다. 일시 실패는 막힘이 아니라 「영상 끝 모름」 이다 — 꼬리는 이미 장부에 있으므로 순서 문제가
// 없고, 길이 교정은 그대로 한다.
func (ix *Indexer) readTail(seg recording.Segment) (timingRead, bool) {
	st, err := ix.timingT(seg.Path)
	switch {
	case err == nil:
		return timingRead{st: &st}, true
	case errors.Is(err, fsop.ErrStalled):
		return timingRead{}, false
	}
	ix.log.Warn("tail_timing_read_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err,
		"note", "영상 끝을 넘기지 않고 길이만 고친다")
	return timingRead{failure: partialReadFailed, err: err}, true
}

// floorMS 는 시각을 ms 로 내린다(UTC) — 장부 칸이 ms 내림 값이라 메모리 꼬리와 재적재 꼬리가 같은 값을 든다.
func floorMS(t time.Time) time.Time { return t.UTC().Truncate(time.Millisecond) }

// floorDivMS 는 ns 를 ms 로 내린다(음수도 내림 — 설계 2.6 「ns 칸의 ms 내림」).
func floorDivMS(d time.Duration) int64 {
	ms := int64(d / time.Millisecond)
	if d%time.Millisecond < 0 {
		ms--
	}
	return ms
}
