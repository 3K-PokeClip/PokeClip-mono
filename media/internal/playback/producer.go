package playback

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4"
	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4/seekablebuffer"
)

// ③(재생 렌디션) 바이트의 생산자.
//
// 설계 3.2 가 못 박은 네 경계 중 하나다. **바뀔 축은 "바이트를 무엇이 만드는가" 하나**이며
// (Go 재포장 ↔ 실패 주입) 그래서 인터페이스가 하나이고 메서드도 하나다.

// ErrNoProducer 는 생산자가 배선되지 않았다는 신호다.
var ErrNoProducer = errors.New("playback: ③ 생산자가 배선되지 않았다")

// 재포장 실패의 종류 — 무결성 4항(계획 4.2-R RE)이 하나씩이다. 어느 것이든 산출은 없고
// (부분 산출 0 — 리스크 A6 "잘린 실물 고착" 방어), 호출자는 errors.Is 로 가른다: ⓐ 는 쓰는 중에
// 잘린 입력일 수 있어 재시도 사다리를 태우고, ⓑ·ⓒ·ⓓ 는 같은 입력이면 늘 같아 첫 시도에서 끝낸다.
var (
	// ErrMalformedInput 은 ⓐ 입력을 fMP4 녹화 조각으로 해석할 수 없다(절단·손상)는 뜻이다.
	ErrMalformedInput = errors.New("playback: 입력을 해석할 수 없다")
	// ErrMissingTracks 는 ⓑ 영상 트랙이나 소리 트랙이 없어 영상 1 + 소리 1 을 남길 수 없다는 뜻이다.
	ErrMissingTracks = errors.New("playback: 영상 1 + 소리 1 을 남길 수 없다")
	// ErrEmptyTrack 은 ⓒ 남긴 두 트랙 중 하나에 샘플이 하나도 없다는 뜻이다.
	ErrEmptyTrack = errors.New("playback: 남긴 트랙에 샘플이 없다")
	// ErrInputTooLarge 는 ⓓ 입력이 크기 상한을 넘는다는 뜻이다 — 읽기 전에 거른다.
	ErrInputTooLarge = errors.New("playback: 입력이 크기 상한을 넘는다")
)

// maxInputBytes 는 입력 크기 상한이다(kty 확정 2026-09-23). 조각을 통째로 메모리에 올리므로
// 점유 = 동시 워커 수 × 입력의 약 4.5배다. 근거: 영상 8Mbps + 소리 6트랙의 6초 조각이
// 6.5MiB 이고 그 2.4배다(계획 4.2-R 「상수」, 71_ ④).
const maxInputBytes = 16 << 20

// Request 는 조각 하나의 재포장 입력이다.
type Request struct {
	// Input 은 녹화 조각(② 실물)의 읽기 핸들이다. 재포장은 이것만 읽는다 —
	// **호출자가 이미 루트 안전하게 연 fd 를 그대로 넘긴다**(이 패키지는 파일을 열지 않는다).
	Input io.ReadSeeker
	// PlaybackPos 는 이 조각의 시간 도장 위치다 — pos = mtxi + offset(계획 4.2-R R3).
	// 평소에는 조각 자기 mtxi 값 그대로이고(offset 0), 회차 안 리셋·구멍 뒤에만 offset 이 붙는다.
	PlaybackPos time.Duration
}

// Output 은 재포장 1회의 산출이다 — 한 번의 호출로 머리와 조각을 함께 얻는다.
//
// 둘을 함께 얻는 것이 설계 5.3ⓑ 의 전제다: init 과 조각이 같은 재포장 산출이라
// "매 조각의 산출 init 을 세션 init 과 대조" 가 sha256 1회 비용으로 성립한다.
type Output struct {
	// Init 은 `ftyp+moov` 다(세션의 MAP).
	Init []byte
	// Seg 는 `moof+mdat…` 다(③ 조각).
	Seg []byte
	// InitSHA256 은 Init 의 해시다 — 설계 5.3ⓑ 의 init 동일성 축이다.
	InitSHA256 [32]byte
}

// Producer 는 ③ 바이트를 만드는 경계다(설계 3.2).
type Producer interface {
	Produce(ctx context.Context, req Request) (Output, error)
}

// NoProducer 는 배선이 없을 때 끼우는 널 오브젝트다.
//
// 언제나 거절한다 — ③ 축의 안전한 방향은 "만들지 않는다" 이고, 빈 바이트를 돌려주면
// 0바이트 객체가 되감기 목록에 실린다(index.NewPGStore 의 널 오브젝트와 같은 규율).
type NoProducer struct{}

// Produce 는 언제나 ErrNoProducer 로 거절한다 — 산출도 부작용도 없다.
func (NoProducer) Produce(context.Context, Request) (Output, error) {
	return Output{}, ErrNoProducer
}

// Remuxer 는 Producer 의 Go 재포장 구현이다(kty 결정 E — 계획 4.2-R RE).
//
// 녹화기(MediaMTX)가 조각을 쓸 때 쓴 바로 그 라이브러리(mediacommon/v2 fmp4, 같은 판)로
// 머리말과 본문을 다시 쓴다. 상태가 없어 영값 그대로 쓰고, 여러 워커가 동시에 불러도 된다.
type Remuxer struct{}

// Produce 는 녹화 조각 하나(영상 1 + 소리 N 합본)를 재생 렌디션(첫 영상 + 첫 소리)으로 재포장한다.
//
// 순서가 계약이다: 요청 검사 → 입력 전체 읽기(크기 상한) → 해석·무결성 → 시간 도장 → 쓰기.
// 파트를 다시 자르지 않고(입력 파트 수 = 산출 파트 수) mfra 도 붙이지 않는다. 디스크에 쓰지도
// 자식 프로세스를 띄우지도 않는다(G4).
func (Remuxer) Produce(ctx context.Context, req Request) (Output, error) {
	if req.Input == nil {
		return Output{}, errors.New("playback: 입력 핸들이 없다")
	}
	if req.PlaybackPos < 0 {
		return Output{}, fmt.Errorf("playback: pos 가 음수다(%v) — 도장은 뒤로 가지 않는다", req.PlaybackPos)
	}
	if err := ctx.Err(); err != nil {
		return Output{}, err
	}
	buf, err := readInput(req.Input)
	if err != nil {
		return Output{}, err
	}
	// 읽기가 유일한 I/O 다 — 그사이 취소된 요청에는 해석·쓰기를 하지 않는다.
	if err := ctx.Err(); err != nil {
		return Output{}, err
	}
	rec, err := parseRecording(buf)
	if err != nil {
		return Output{}, err
	}
	return rec.stamped(req.PlaybackPos).marshal()
}

// readInput 은 입력 전체를 메모리로 읽는다. 현재 위치와 무관하게 처음부터 읽는다.
//
// 크기는 두 번 묶는다(무결성 ⓓ): 읽기 전에 리더의 끝 위치(파일이면 fstat 크기와 같은 값)로
// 거부하고, 실제 읽기도 상한 + 1 바이트에서 끊어 잰 뒤에 자란 파일이 상한을 넘는지 본다.
func readInput(r io.ReadSeeker) ([]byte, error) {
	size, err := r.Seek(0, io.SeekEnd)
	if err != nil {
		return nil, fmt.Errorf("playback: 입력 크기 측정 실패: %w", err)
	}
	if size > maxInputBytes {
		return nil, fmt.Errorf("%w: %d바이트 > %d바이트", ErrInputTooLarge, size, maxInputBytes)
	}
	if _, err := r.Seek(0, io.SeekStart); err != nil {
		return nil, fmt.Errorf("playback: 입력 되감기 실패: %w", err)
	}
	// 끝을 확인하는 마지막 읽기까지 재할당 없이 받도록 잰 크기 + MinRead 로 잡는다.
	buf := bytes.NewBuffer(make([]byte, 0, size+bytes.MinRead))
	if _, err := buf.ReadFrom(io.LimitReader(r, maxInputBytes+1)); err != nil {
		return nil, fmt.Errorf("playback: 입력 읽기 실패: %w", err)
	}
	if buf.Len() > maxInputBytes {
		return nil, fmt.Errorf("%w: 읽는 사이 %d바이트를 넘게 자랐다", ErrInputTooLarge, maxInputBytes)
	}
	return buf.Bytes(), nil
}

// recording 은 해석을 마친 녹화 조각 한 벌이다 — 남긴 두 트랙과, 그 둘만 담은 파트들.
type recording struct {
	tracks []*fmp4.InitTrack // 파일 속 순서의 첫 영상·첫 소리
	parts  fmp4.Parts        // 파트마다 남긴 트랙만 담는다(입력 파트 수 그대로)
}

// parseRecording 은 메모리 위 녹화 조각을 해석해 남길 트랙만 추린다 — 무결성 ⓐ·ⓑ·ⓒ.
func parseRecording(buf []byte) (recording, error) {
	head, body, err := splitAtFirstMoof(buf)
	if err != nil {
		return recording{}, err
	}
	var ini fmp4.Init
	if err := ini.Unmarshal(bytes.NewReader(head)); err != nil {
		return recording{}, fmt.Errorf("%w: 머리말: %v", ErrMalformedInput, err)
	}
	var parts fmp4.Parts
	if err := parts.Unmarshal(body); err != nil {
		return recording{}, fmt.Errorf("%w: 본문: %v", ErrMalformedInput, err)
	}
	tracks, err := firstVideoAndAudio(ini.Tracks)
	if err != nil {
		return recording{}, err
	}
	kept := onlyTracks(parts, tracks)
	if err := requireSamples(kept, tracks); err != nil {
		return recording{}, err
	}
	return recording{tracks: tracks, parts: kept}, nil
}

// splitAtFirstMoof 는 입력을 머리말(첫 moof 앞 = ftyp+moov)과 본문(moof+mdat 반복)으로 가른다.
//
// 최상위 상자들이 입력을 빈틈없이 정확히 덮는지도 본다 — 3자 파서는 끝에 남은 반쪽 상자
// 머리를 조용히 건너뛰므로, 파트 도중에 잘린 조각을 가려내는 자리가 여기다.
func splitAtFirstMoof(buf []byte) (head, body []byte, err error) {
	moof := -1
	for off := 0; off < len(buf); {
		size, typ, boxErr := topLevelBox(buf[off:])
		if boxErr != nil {
			return nil, nil, fmt.Errorf("%w: 최상위 상자(오프셋 %d): %v", ErrMalformedInput, off, boxErr)
		}
		if typ == "moof" && moof < 0 {
			moof = off
		}
		off += size
	}
	if moof < 0 {
		return nil, nil, fmt.Errorf("%w: moof 가 없다 — fMP4 조각이 아니다", ErrMalformedInput)
	}
	return buf[:moof], buf[moof:], nil
}

// topLevelBox 는 b 머리에 놓인 상자 하나의 전체 크기와 형식을 읽는다.
//
// 녹화기는 32비트 크기만 쓴다 — 0(파일 끝까지)·1(64비트 크기)은 받지 않는다(조각 ≤ 16MiB).
func topLevelBox(b []byte) (size int, typ string, err error) {
	const headerSize = 8
	if len(b) < headerSize {
		return 0, "", fmt.Errorf("상자 머리가 %d바이트뿐이다", len(b))
	}
	n := uint64(binary.BigEndian.Uint32(b))
	typ = string(b[4:8])
	if n < headerSize {
		return 0, "", fmt.Errorf("%q 상자 크기 %d 는 지원하지 않는다", typ, n)
	}
	if n > uint64(len(b)) {
		return 0, "", fmt.Errorf("%q 상자(%d바이트)가 입력 끝을 넘는다(%d바이트 남음)", typ, n, len(b))
	}
	return int(n), typ, nil
}

// firstVideoAndAudio 는 파일 속 순서로 첫 영상 트랙과 첫 소리 트랙을 고른다 —
// ADR-057 의 `0:v:0`·`0:a:0` 과 같은 선택이다(0번 소리 = 종합 믹스). 둘 중 하나라도
// 없으면 실패다(무결성 ⓑ) — 한쪽만 남은 ③ 은 소리 없는(또는 화면 없는) 되감기가 된다.
func firstVideoAndAudio(tracks []*fmp4.InitTrack) ([]*fmp4.InitTrack, error) {
	var kept []*fmp4.InitTrack
	var haveVideo, haveAudio bool
	for _, tr := range tracks {
		video := tr.Codec.IsVideo()
		if (video && haveVideo) || (!video && haveAudio) {
			continue
		}
		haveVideo = haveVideo || video
		haveAudio = haveAudio || !video
		kept = append(kept, tr)
	}
	if !haveVideo || !haveAudio {
		return nil, fmt.Errorf("%w: 영상 %t · 소리 %t", ErrMissingTracks, haveVideo, haveAudio)
	}
	return kept, nil
}

// onlyTracks 는 파트마다 남긴 트랙만 담은 새 파트들을 만든다. 파트를 다시 자르지 않는다.
func onlyTracks(parts fmp4.Parts, tracks []*fmp4.InitTrack) fmp4.Parts {
	keep := make(map[int]bool, len(tracks))
	for _, tr := range tracks {
		keep[tr.ID] = true
	}
	out := make(fmp4.Parts, 0, len(parts))
	for _, p := range parts {
		kept := &fmp4.Part{SequenceNumber: p.SequenceNumber}
		for _, tr := range p.Tracks {
			if keep[tr.ID] {
				kept.Tracks = append(kept.Tracks, tr)
			}
		}
		out = append(out, kept)
	}
	return out
}

// requireSamples 는 남긴 트랙마다 샘플이 하나 이상 있는지 본다(무결성 ⓒ) — 빈 트랙을
// 가진 ③ 은 되감기에서 그 구간 소리(또는 화면)가 통째로 빈다.
func requireSamples(parts fmp4.Parts, tracks []*fmp4.InitTrack) error {
	count := make(map[int]int, len(tracks))
	for _, p := range parts {
		for _, tr := range p.Tracks {
			count[tr.ID] += len(tr.Samples)
		}
	}
	for _, tr := range tracks {
		if count[tr.ID] == 0 {
			return fmt.Errorf("%w: 트랙 %d", ErrEmptyTrack, tr.ID)
		}
	}
	return nil
}

// stamped 는 파트마다 남긴 트랙의 BaseTime 에 pos 를 그 트랙의 시간 단위로 더한 새 한 벌이다.
//
// 두 트랙에 같은 pos 를 더하므로 파일 안 A/V 위상이 보존된다 — 위상은 입력 BaseTime 에 이미
// 들어 있다(녹화기는 모든 트랙을 공통 segmentStartDTS 기준으로 쓴다, 포크 format_fmp4_part.go:71).
// 트랙마다 첫 파트 값을 빼지 않는 것도 같은 이유다 — 빼면 그 위상이 지워진다.
func (rec recording) stamped(pos time.Duration) recording {
	shift := make(map[int]uint64, len(rec.tracks))
	for _, tr := range rec.tracks {
		shift[tr.ID] = ticks(pos, tr.TimeScale)
	}
	parts := make(fmp4.Parts, 0, len(rec.parts))
	for _, p := range rec.parts {
		moved := &fmp4.Part{SequenceNumber: p.SequenceNumber}
		for _, tr := range p.Tracks {
			moved.Tracks = append(moved.Tracks, &fmp4.PartTrack{
				ID:       tr.ID,
				BaseTime: tr.BaseTime + shift[tr.ID],
				Samples:  tr.Samples,
			})
		}
		parts = append(parts, moved)
	}
	return recording{tracks: rec.tracks, parts: parts}
}

// ticks 는 시간 오프셋을 그 트랙의 시간 단위로 바꾼다(반올림).
//
//	ticks(ns, ts) = (ns ÷ 10^9) × ts + ((ns mod 10^9) × ts + 5×10^8) ÷ 10^9
//
// **초와 나노초를 갈라 곱한다** — 한 곱셈 ns × ts 는 int64 에서 ns > 1.02×10^14
// (90000 기준 28.47시간)에 넘친다(계획 4.2-R RE). d 는 음수가 아니어야 한다 —
// Go 정수 나눗셈은 0 쪽으로 잘라 음수면 반올림이 틀린다(Produce 가 경계에서 막는다).
func ticks(d time.Duration, timescale uint32) uint64 {
	const perSecond = int64(time.Second)
	ns, ts := int64(d), int64(timescale)
	whole := ns / perSecond * ts
	frac := (ns%perSecond*ts + perSecond/2) / perSecond
	return uint64(whole + frac)
}

// marshal 은 남긴 두 트랙의 init 과 조각을 쓴다.
//
// init 에는 UserData(mtxi)·파일별 mvhd 값을 싣지 않는다 — 트랙 파라미터만의 순수 함수여야
// 조각·송출자 연결이 바뀌어도 init 바이트가 같다(설계 5.3ⓑ init 동일성). 쓰기 대상이 메모리
// 버퍼라 쓰기 실패는 입력의 트랙 파라미터를 다시 쓸 수 없다는 뜻이다 — ⓐ 로 분류한다.
func (rec recording) marshal() (Output, error) {
	var initBuf seekablebuffer.Buffer
	if err := (fmp4.Init{Tracks: rec.tracks}).Marshal(&initBuf); err != nil {
		return Output{}, fmt.Errorf("%w: init 쓰기: %v", ErrMalformedInput, err)
	}
	var segBuf seekablebuffer.Buffer
	if err := rec.parts.Marshal(&segBuf); err != nil {
		return Output{}, fmt.Errorf("%w: 조각 쓰기: %v", ErrMalformedInput, err)
	}
	initBytes := initBuf.Bytes()
	return Output{Init: initBytes, Seg: segBuf.Bytes(), InitSHA256: sha256.Sum256(initBytes)}, nil
}
