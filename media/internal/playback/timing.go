package playback

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"time"

	amp4 "github.com/abema/go-mp4"
	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4"
)

// 이 파일은 녹화 조각 하나의 **시각 사실**을 한 번에 읽는다(설계 r7 2.2 · 3절 — 장부의 원래 사실 칸 ·
// 섞기 재료). 값을 해석하지 않는다 — 짐작 w · 1ms 하한 · 이어짐 판정은 부르는 쪽(index · indexer)의
// 일이다. DB · 시계를 모르고, 파일도 열지 않는다(호출자가 연 리더를 받는다 — mp4box.go 와 같다).
//
// 오류는 두 갈래다(설계 r6 2.2).
//   - 결정적: 머리말(ftyp + moov)에서 정해지는 부재 — mtxi 상자 없음 · 영상 트랙 없음. 녹화기는 머리말을
//     파일을 열 때 한 번 쓰므로 파일이 자라도 바뀌지 않는다. 오류가 아니라 값(nil)으로 돌려준다.
//   - 읽은 시점의 값: 트랙은 머리말에 있으나 본문에 그 트랙 표본이 0 개(다른 트랙에는 표본이 있음). 이것도
//     오류가 아니라 값(nil)으로 돌려주지만 본문 사실이라 결정적이 아니다 — 파일이 자라면 표본이 생길 수
//     있다(설계 r7 2.14 「옛 영상 끝이 NULL 인 꼬리」).
//   - 일시(ErrTransientRead): 그 밖 전부 — I/O 오류 · 읽는 사이 크기 변화 · 머리말 · 본문 해석 실패 ·
//     표본이 하나도 없는 본문. 본문은 파일이 자라면 바뀌므로 다시 읽으면 다른 답이 날 수 있다.

// ErrTransientRead 는 본문 해석 실패 · I/O 오류 · 읽는 사이 크기 변화다. ReadTiming 의 오류는 모두
// 이것으로 감싼다(머리말 해석 실패 · 크기 상한 초과 포함 — 원인은 사슬에 남는다). 부르는 쪽은 그 조각을 커밋하지 않고 같은 자리에서 다시 읽는다(설계 r7 2.14).
var ErrTransientRead = errors.New("playback: 조각 판독 일시 실패")

// SegmentTiming 은 녹화 조각 하나의 시각 사실이다(머리말 mtxi · 트랙별 첫 표본 시작 · 마지막 표본 끝 —
// 조각 시작 기준).
type SegmentTiming struct {
	// Mtxi 는 머리말의 녹화기 상자다. nil 이면 머리말에 mtxi 가 없다(결정적).
	Mtxi *Mtxi
	// Video 는 파일 속 순서로 첫 영상 트랙의 구간이다. nil 이면 영상 트랙이 없거나(결정적) 트랙은 있으나
	// 표본이 0 개다(읽은 시점의 값 — 파일이 자라면 바뀔 수 있다, 설계 r7 2.14).
	Video *TrackSpan
	// Audio 는 파일 속 순서로 첫 소리 트랙의 구간이다(Produce 와 같은 선택). nil 이면 소리 트랙이
	// 없거나(결정적) 표본이 0 개다(읽은 시점의 값 — Video 와 같다).
	Audio *TrackSpan
	// Head 는 첫 표본 시작이 가장 이른 트랙이다(모든 트랙 중). 같으면 init 순서가 뒤인 트랙이다 —
	// 상류 녹화기가 조각 시작을 고르는 규칙(format_fmp4_track.go 의 `dts <= oldestDTS`)과 같다.
	Head Track
	// HeadLead 는 머리 앞섬 m = 영상 시작 − 머리 트랙 시작이다. 영상이 없으면 0 이다.
	HeadLead time.Duration
}

// TrackSpan 은 트랙 하나의 첫 표본 시작과 마지막 표본 끝이다(조각 시작 = segmentStartDTS 기준).
type TrackSpan struct{ Start, End time.Duration }

// Track 은 조각 안 트랙 하나를 가리킨다.
type Track struct {
	ID    int  // 머리말의 트랙 ID
	Video bool // 영상 트랙인가
}

// ReadTiming 은 녹화 조각 하나를 한 번 읽어 시각 사실을 돌려준다.
//
// 조각 전체를 메모리로 읽는다(readInput 의 16MiB 상한 그대로). 리더 위치와 무관하게 처음부터 읽고,
// 끝나면 부르기 전 위치로 되돌린다(ReadMtxi · TrackEnds 와 같다). 오류는 모두 ErrTransientRead 다.
func ReadTiming(r io.ReadSeeker) (SegmentTiming, error) {
	st, err := fromStart(r, readTiming)
	if err != nil {
		return SegmentTiming{}, fmt.Errorf("%w: %w", ErrTransientRead, err)
	}
	return st, nil
}

func readTiming(r io.ReadSeeker) (SegmentTiming, error) {
	buf, err := readWhole(r)
	if err != nil {
		return SegmentTiming{}, err
	}
	head, body, err := splitAtFirstMoof(buf)
	if err != nil {
		return SegmentTiming{}, err
	}
	var ini fmp4.Init
	if err := ini.Unmarshal(bytes.NewReader(head)); err != nil {
		return SegmentTiming{}, fmt.Errorf("머리말: %w", err)
	}
	mtxi, err := findMtxi(head)
	if err != nil {
		return SegmentTiming{}, err
	}
	var parts fmp4.Parts
	if err := parts.Unmarshal(body); err != nil {
		return SegmentTiming{}, fmt.Errorf("본문: %w", err)
	}
	st, err := timingOf(ini.Tracks, parts)
	if err != nil {
		return SegmentTiming{}, err
	}
	st.Mtxi = mtxi
	return st, nil
}

// readWhole 은 readInput 으로 전체를 읽고, 읽기 앞뒤의 끝 위치(파일이면 fstat 크기)가 읽은 바이트 수와
// 같은지 본다. 다르면 녹화기가 읽는 사이 덧붙인 것이다 — 그 바이트는 한 시점의 조각이 아니다.
func readWhole(r io.ReadSeeker) ([]byte, error) {
	before, err := r.Seek(0, io.SeekEnd)
	if err != nil {
		return nil, fmt.Errorf("입력 크기 측정 실패: %w", err)
	}
	buf, err := readInput(r)
	if err != nil {
		return nil, err
	}
	after, err := r.Seek(0, io.SeekEnd)
	if err != nil {
		return nil, fmt.Errorf("입력 크기 측정 실패: %w", err)
	}
	if n := int64(len(buf)); n != before || n != after {
		return nil, fmt.Errorf("읽는 사이 크기가 바뀌었다(앞 %d · 읽음 %d · 뒤 %d바이트)", before, n, after)
	}
	return buf, nil
}

// findMtxi 는 머리말의 mtxi 를 읽는다. 상자가 없으면 nil 이다(결정적). 상자가 있는데 읽지 못하면(44B
// 배치가 아님 등) 오류다 — 결정적 부재는 「상자 없음」 하나뿐이다(설계 r6 2.2).
func findMtxi(head []byte) (*Mtxi, error) {
	infos, err := amp4.ExtractBox(bytes.NewReader(head), nil, mtxiPath)
	if err != nil {
		return nil, fmt.Errorf("mtxi 찾기 실패: %w", err)
	}
	if len(infos) == 0 {
		return nil, nil
	}
	m, err := readMtxi(bytes.NewReader(head))
	if err != nil {
		return nil, err
	}
	return &m, nil
}

// timingOf 는 트랙마다 첫 표본 시작 · 마지막 표본 끝을 모아 영상 · 소리 구간과 머리 트랙을 정한다.
// 표본이 하나도 없으면 오류다 — 녹화기는 표본 없는 조각을 닫지 않으므로 아직 쓰는 중인 본문이다.
func timingOf(tracks []*fmp4.InitTrack, parts fmp4.Parts) (SegmentTiming, error) {
	var st SegmentTiming
	var haveVideo, haveAudio, haveHead bool
	var headStart time.Duration
	for _, tr := range tracks {
		span, ok := spanOf(parts, tr)
		video := tr.Codec.IsVideo()
		// 첫 영상 · 첫 소리의 선택은 표본 유무와 무관하다(Produce 의 firstVideoAndAudio 와 같다).
		if video && !haveVideo {
			haveVideo = true
			if ok {
				st.Video = &span
			}
		}
		if !video && !haveAudio {
			haveAudio = true
			if ok {
				st.Audio = &span
			}
		}
		if ok && (!haveHead || span.Start <= headStart) {
			haveHead, headStart = true, span.Start
			st.Head = Track{ID: tr.ID, Video: video}
		}
	}
	if !haveHead {
		return SegmentTiming{}, errors.New("본문에 표본이 하나도 없다")
	}
	if st.Video != nil {
		st.HeadLead = st.Video.Start - headStart
	}
	return st, nil
}

// spanOf 는 한 트랙의 구간이다. 표본이 없으면 ok 가 거짓이다(파트는 파일 순서 = 시간 순서).
func spanOf(parts fmp4.Parts, tr *fmp4.InitTrack) (span TrackSpan, ok bool) {
	for _, p := range parts {
		for _, pt := range p.Tracks {
			if pt.ID == tr.ID && len(pt.Samples) > 0 {
				span.Start = durationOf(pt.BaseTime, tr.TimeScale)
				span.End = durationOf(lastSampleEnd(parts, tr.ID), tr.TimeScale)
				return span, true
			}
		}
	}
	return TrackSpan{}, false
}
