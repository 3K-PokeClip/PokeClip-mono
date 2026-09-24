package playback

import (
	"errors"
	"fmt"
	"io"
	"time"

	amp4 "github.com/abema/go-mp4"
	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4"
)

// 이 파일은 녹화 조각의 **값을 읽기만** 한다(머리말의 mtxi · 남긴 두 트랙의 끝 시각).
// 파일을 열지도 바꾸지도 않는다 — 호출자가 연 리더를 받는다(실시간 = fsop.ReadT 워커,
// 스위퍼 = 이미 연 Request.Input).
//
// fmp4meta 를 확장하지 않는 이유: 그 패키지의 계약은 "파일 속을 들여다보는 일 하나"(길이 프로브)이고
// 여기서 필요한 것은 녹화기 자체 상자다. 두 일을 한 패키지에 넣으면 변경 이유가 둘이 된다.

// mtxiPayloadSize 는 mtxi 상자 내용의 크기다(실측 배치 — 계획 4.2-R RE).
// 다르면 녹화기가 형식을 바꾼 것이라 읽지 않는다 — 어긋난 칸을 누적 DTS 로 쓰면 도장이 틀린다.
const mtxiPayloadSize = 44

// mtxiBox 는 MediaMTX 녹화기가 조각 머리말(moov/udta)에 넣는 자체 상자 mtxi 의 배치다.
// 상류 비공개 형식이라 실측으로 확인한 배치를 그대로 적는다:
//
//	FullBox 4B · 녹화기 표식 16B · 조각 번호 u64 · 누적 DTS i64 나노초 · NTP i64 나노초
type mtxiBox struct {
	amp4.FullBox  `mp4:"0,extend"`
	RecorderID    [16]byte `mp4:"1,size=8,len=16"`
	SegmentNumber uint64   `mp4:"2,size=64"`
	DTS           int64    `mp4:"3,size=64"`
	NTP           int64    `mp4:"4,size=64"`
}

var boxTypeMtxi = amp4.StrToBoxType("mtxi")

func (*mtxiBox) GetType() amp4.BoxType { return boxTypeMtxi }

// init 은 mtxi 상자 정의를 go-mp4 의 전역 상자 표에 등록한다 — 이 프로세스의 등록 자리는
// 여기 하나다. 등록이 없으면 mediacommon 의 머리말 해석(fmp4.Init.Unmarshal)이 udta 안의
// mtxi 에서 `box info not found` 로 실패한다. version 0 만 받는다(실물 전부 0).
func init() {
	amp4.AddBoxDef(&mtxiBox{}, 0)
}

// mtxiPath 는 머리말 안 mtxi 의 자리다.
var mtxiPath = amp4.BoxPath{amp4.BoxTypeMoov(), amp4.BoxTypeUdta(), boxTypeMtxi}

// Mtxi 는 MediaMTX 녹화기가 조각 머리말(moov/udta)에 적는 mtxi 상자의 값이다.
type Mtxi struct {
	// RecorderID 는 녹화기 인스턴스 표식이다(인스턴스마다 새 UUID). 송출자 재접속·녹화기
	// 드리프트 리셋이면 바뀐다.
	RecorderID [16]byte
	// SegmentNumber 는 그 녹화기 인스턴스 안의 조각 번호다.
	SegmentNumber uint64
	// DTS 는 조각 시작의 누적 DTS 다 — 송출자 연결마다 0 에서 시작하는 미디어 시계다.
	DTS time.Duration
	// NTP 는 조각 시작의 벽시계 시각이다.
	NTP time.Time
}

// ReadMtxi 는 녹화 조각 머리말의 mtxi 상자를 읽는다(계획 4.2-R R3 의 mtxi(k)).
//
// 리더 위치와 무관하게 처음부터 읽고, 끝나면 부르기 전 위치로 되돌린다. 상자가 없거나
// 내용이 44B 배치가 아니면 실패한다 — 호출자는 판독 실패로 다룬다(R3 규칙 ④).
func ReadMtxi(r io.ReadSeeker) (Mtxi, error) {
	return fromStart(r, readMtxi)
}

func readMtxi(r io.ReadSeeker) (Mtxi, error) {
	boxes, err := amp4.ExtractBoxWithPayload(r, nil, mtxiPath)
	if err != nil {
		return Mtxi{}, fmt.Errorf("playback: mtxi 판독 실패: %w", err)
	}
	if len(boxes) == 0 {
		return Mtxi{}, errors.New("playback: 머리말에 mtxi 상자가 없다")
	}
	b := boxes[0]
	if size := b.Info.Size - b.Info.HeaderSize; size != mtxiPayloadSize {
		return Mtxi{}, fmt.Errorf("playback: mtxi 내용이 %dB 다(%dB 여야 한다) — 녹화기 형식이 바뀌었다", size, mtxiPayloadSize)
	}
	m, ok := b.Payload.(*mtxiBox)
	if !ok {
		return Mtxi{}, fmt.Errorf("playback: mtxi 상자 형식이 예상과 다르다(%T)", b.Payload)
	}
	return Mtxi{
		RecorderID:    m.RecorderID,
		SegmentNumber: m.SegmentNumber,
		DTS:           time.Duration(m.DTS),
		NTP:           time.Unix(0, m.NTP),
	}, nil
}

// Ends 는 조각 안에서 남긴 두 트랙이 각각 끝나는 시각이다 — 조각 자기 도장 축(pos 를 더하기 전).
type Ends struct {
	Video time.Duration
	Audio time.Duration
}

// TrackEnds 는 녹화 조각에서 남긴 두 트랙(파일 속 순서의 첫 영상·첫 소리 — Produce 와 같은
// 선택) 각각의 조각 안 끝 시각을 읽는다. 끝 = 그 트랙 마지막 샘플의 끝이다.
//
// 회차 안 리셋 때 직전 조각을 이을 자리를 정하는 재료다 — 계획 4.2-R R3
// end(k−1) = pos(k−1) + min(두 끝). 조각 전체를 메모리로 읽고 Produce 와 같은 무결성 4항을
// 거친다 — 잘린 조각의 "끝"은 실제 끝이 아니다. 리더 위치는 ReadMtxi 와 같이 되돌린다.
func TrackEnds(r io.ReadSeeker) (Ends, error) {
	return fromStart(r, trackEnds)
}

func trackEnds(r io.ReadSeeker) (Ends, error) {
	buf, err := readInput(r)
	if err != nil {
		return Ends{}, err
	}
	rec, err := parseRecording(buf)
	if err != nil {
		return Ends{}, err
	}
	var ends Ends
	for _, tr := range rec.tracks {
		end := durationOf(lastSampleEnd(rec.parts, tr.ID), tr.TimeScale)
		if tr.Codec.IsVideo() {
			ends.Video = end
		} else {
			ends.Audio = end
		}
	}
	return ends, nil
}

// lastSampleEnd 는 파트들에서 한 트랙의 마지막 샘플이 끝나는 틱이다(파트는 파일 순서 = 시간 순서).
func lastSampleEnd(parts fmp4.Parts, trackID int) uint64 {
	var end uint64
	for _, p := range parts {
		for _, tr := range p.Tracks {
			if tr.ID != trackID || len(tr.Samples) == 0 {
				continue
			}
			end = tr.BaseTime
			for _, smp := range tr.Samples {
				end += uint64(smp.Duration)
			}
		}
	}
	return end
}

// durationOf 는 틱을 시간으로 바꾼다(반올림) — ticks 의 역이며, 같은 이유로 초와 나머지를 갈라 곱한다.
func durationOf(t uint64, timescale uint32) time.Duration {
	perSecond, ts := uint64(time.Second), uint64(timescale)
	whole := t / ts * perSecond
	frac := (t%ts*perSecond + ts/2) / ts
	return time.Duration(whole + frac)
}

// fromStart 는 r 을 처음으로 되감아 read 를 부르고, 끝나면 r 을 부르기 전 위치로 되돌린다 —
// 스위퍼가 같은 리더(Request.Input)를 이어서 Produce 에 넘길 수 있게 한다.
func fromStart[T any](r io.ReadSeeker, read func(io.ReadSeeker) (T, error)) (T, error) {
	var zero T
	pos, err := r.Seek(0, io.SeekCurrent)
	if err != nil {
		return zero, fmt.Errorf("playback: 리더 위치 확인 실패: %w", err)
	}
	if _, err := r.Seek(0, io.SeekStart); err != nil {
		return zero, fmt.Errorf("playback: 리더 되감기 실패: %w", err)
	}
	v, readErr := read(r)
	if _, err := r.Seek(pos, io.SeekStart); err != nil {
		return zero, errors.Join(readErr, fmt.Errorf("playback: 리더 위치 복원 실패: %w", err))
	}
	return v, readErr
}
