package playback

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	amp4 "github.com/abema/go-mp4"
	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4"
	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4/seekablebuffer"
)

func TestNoProducerRefusesInsteadOfProducingEmptyBytes(t *testing.T) {
	_, err := NoProducer{}.Produce(context.Background(), Request{})

	if !errors.Is(err, ErrNoProducer) {
		t.Fatalf("err = %v, want ErrNoProducer — 배선 누락이 빈 ③ 로 새면 안 된다", err)
	}
}

// TestTicksNoOverflowBeyond28h 는 pos 를 트랙 시간 단위로 바꾸는 산식이 긴 방송에서도
// 정확한지 본다(뮤테이션 48). 한 곱셈 ns × timescale 은 int64 에서 ns > 1.02×10^14
// (90000 기준 28.47시간)에 넘친다 — 30시간 지점은 그 곱셈이면 틀린 값이 나오는 입력이다.
// 60시간 지점은 uint64 한 곱셈(1.8×10^19)도 넘는 입력이다.
//
// want 는 정수 산술로 손으로 검산한 값이다(30h = 108000초 × 90000 = 9,720,000,000).
func TestTicksNoOverflowBeyond28h(t *testing.T) {
	tests := []struct {
		name      string
		pos       time.Duration
		timescale uint32
		want      uint64
	}{
		{"30시간 영상", 30 * time.Hour, 90000, 9_720_000_000},
		{"30시간+0.123456789초 영상", 30*time.Hour + 123456789, 90000, 9_720_011_111},
		{"60시간 영상", 60 * time.Hour, 90000, 19_440_000_000},
		{"30시간 소리", 30 * time.Hour, 44100, 4_762_800_000},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := ticks(tt.pos, tt.timescale); got != tt.want {
				t.Errorf("ticks(%v, %d) = %d, want %d", tt.pos, tt.timescale, got, tt.want)
			}
		})
	}
}

// TestTicksRoundsToNearestTick 는 틱 미만 나머지를 반올림하는지 본다 — 버리면 조각마다
// 최대 1틱씩 한쪽으로 치우친다.
func TestTicksRoundsToNearestTick(t *testing.T) {
	tests := []struct {
		name      string
		pos       time.Duration
		timescale uint32
		want      uint64
	}{
		{"정확히 반 틱은 올린다", 250 * time.Millisecond, 2, 1},
		{"반 틱 미만은 버린다", 249_999_999, 2, 0},
		{"12.345678901초 영상(1,111,111.1)", 12_345_678_901, 90000, 1_111_111},
		{"12.345678901초 소리(544,444.4)", 12_345_678_901, 44100, 544_444},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := ticks(tt.pos, tt.timescale); got != tt.want {
				t.Errorf("ticks(%v, %d) = %d, want %d", tt.pos, tt.timescale, got, tt.want)
			}
		})
	}
}

// sizedReader 는 크기를 말하는 값과 실제로 내주는 바이트 수를 따로 정할 수 있는 리더다.
// 내용은 전부 0 이고, Read 호출 횟수를 센다 — "읽기 전에 거부했는가"를 관측하는 장치다.
type sizedReader struct {
	size  int64 // Seek(0, io.SeekEnd) 가 알리는 크기(호출자가 stat 한 값의 대역)
	data  int64 // Read 가 실제로 내주는 바이트 수(잰 뒤 자란 파일이면 size 보다 크다)
	pos   int64
	reads int
}

func (r *sizedReader) Read(p []byte) (int, error) {
	r.reads++
	if r.pos >= r.data {
		return 0, io.EOF
	}
	n := int(min(int64(len(p)), r.data-r.pos))
	clear(p[:n])
	r.pos += int64(n)
	return n, nil
}

func (r *sizedReader) Seek(offset int64, whence int) (int64, error) {
	switch whence {
	case io.SeekStart:
		r.pos = offset
	case io.SeekCurrent:
		r.pos += offset
	case io.SeekEnd:
		r.pos = r.size + offset
	}
	return r.pos, nil
}

// TestRemuxRejectsOversizeBeforeRead 는 무결성 ⓓ(크기 상한 16MiB)를 본다 — 뮤테이션 17d.
// 조각을 통째로 메모리에 올리므로, 상한을 넘는 입력은 **한 바이트도 읽기 전에** 거부해야
// 워커 수 × 입력 크기의 메모리 점유가 상한 안에 묶인다(리스크 A1).
func TestRemuxRejectsOversizeBeforeRead(t *testing.T) {
	const limit = 16 << 20 // kty 확정값(2026-09-23) — 상수 maxInputBytes 와 독립으로 적는다

	t.Run("상한 초과는 읽기 전에 거부", func(t *testing.T) {
		in := &sizedReader{size: limit + 1, data: limit + 1}

		_, err := Remuxer{}.Produce(context.Background(), Request{Input: in})

		if !errors.Is(err, ErrInputTooLarge) {
			t.Errorf("err = %v, want ErrInputTooLarge", err)
		}
		if in.reads != 0 {
			t.Errorf("Read 호출 %d회, want 0 — 상한 초과 입력을 읽기 시작했다", in.reads)
		}
	})
	t.Run("상한 정확히는 크기로 거부하지 않는다", func(t *testing.T) {
		in := &sizedReader{size: limit, data: limit}

		_, err := Remuxer{}.Produce(context.Background(), Request{Input: in})

		if errors.Is(err, ErrInputTooLarge) {
			t.Errorf("err = %v — 상한과 같은 크기는 허용이다", err)
		}
		if in.reads == 0 {
			t.Error("상한과 같은 크기의 입력을 읽지 않았다")
		}
	})
	t.Run("잰 뒤 자란 입력은 상한+1 바이트에서 끊고 거부", func(t *testing.T) {
		in := &sizedReader{size: 1024, data: limit + 1<<20} // stat 뒤 1MiB 넘게 더 자랐다

		_, err := Remuxer{}.Produce(context.Background(), Request{Input: in})

		if !errors.Is(err, ErrInputTooLarge) {
			t.Errorf("err = %v, want ErrInputTooLarge", err)
		}
		if in.pos > limit+1 {
			t.Errorf("읽은 바이트 %d > 상한+1(%d) — 자란 입력을 상한 너머까지 메모리로 읽었다", in.pos, limit+1)
		}
	})
}

// TestRemuxRejectsTruncatedInput 는 무결성 ⓐ(입력 해석 실패 — 절단)를 본다 — 뮤테이션 17a.
// 잘린 녹화 조각을 재포장해 올리면 되감기 목록이 잘린 실물을 영구히 가리킨다(리스크 A6).
//
// 절단 자리는 segment_4s.mp4 의 상자 지도(ftyp 0 · moov 32 · moof 1219/39973/75198/112095 ·
// 마지막 mdat 113071..143007)에서 골랐다. 파트 경계에서 정확히 자른 파일은 파트가 적은
// 온전한 조각이라 여기서 가려낼 수 없다 — 자라는 파일은 워커의 꼬리 성장 판정이 막는다.
func TestRemuxRejectsTruncatedInput(t *testing.T) {
	raw := readFixture(t, fixture4s)
	tests := []struct {
		name string
		cut  int
	}{
		{"머리말(moov) 한가운데", 600},
		{"첫 moof 자리 — 머리말만 있고 파트가 없다(녹화기가 init 만 쓴 순간)", 1219},
		{"셋째 moof 의 상자 머리 4바이트만 남음", 75198 + 4},
		{"마지막 mdat 한가운데", len(raw) - 1000},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			in := bytes.NewReader(raw[:tt.cut])

			_, err := Remuxer{}.Produce(context.Background(), Request{Input: in})

			if !errors.Is(err, ErrMalformedInput) {
				t.Errorf("err = %v, want ErrMalformedInput", err)
			}
		})
	}
}

// TestRemuxKeepsFirstVideoAndFirstAudio 는 트랙 선택 규칙을 본다 — ADR-057 의 `0:v:0`·`0:a:0`
// (0번 소리 = 종합 믹스). **ID 순서가 아니라 파일 속 순서**로 첫 영상·첫 소리를 고른다.
func TestRemuxKeepsFirstVideoAndFirstAudio(t *testing.T) {
	raw := readFixture(t, fixture1v6a) // 트랙 7개: 1 = 영상, 2..7 = 소리(서로 다른 사인파)
	reordered := withHeader(t, raw, func(ini *fmp4.Init) {
		// 파일 속 순서를 [소리 3, 영상 1, 소리 2, 4..7] 로 바꾼다 — 첫 소리는 이제 트랙 3 이다.
		tr := ini.Tracks
		ini.Tracks = append([]*fmp4.InitTrack{tr[2], tr[0], tr[1]}, tr[3:]...)
	})
	_, inBody := splitRecording(t, raw)
	inParts := parsedParts(t, inBody)

	tests := []struct {
		name      string
		input     []byte
		wantIDs   []int // 산출 init 의 트랙 ID(파일 속 순서)
		wantAudio int   // 소리 샘플을 가져와야 하는 입력 트랙
	}{
		{"녹화기 원본(영상 1 + 소리 6)", raw, []int{1, 2}, 2},
		{"머리말 트랙 순서가 소리 3 먼저", reordered, []int{3, 1}, 3},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ini, parts := parseOutput(t, produce(t, tt.input, 0))

			var gotIDs []int
			for _, tr := range ini.Tracks {
				gotIDs = append(gotIDs, tr.ID)
			}
			if !slices.Equal(gotIDs, tt.wantIDs) {
				t.Fatalf("산출 init 트랙 = %v, want %v", gotIDs, tt.wantIDs)
			}
			for _, p := range parts {
				for _, tr := range p.Tracks {
					if tr.ID != 1 && tr.ID != tt.wantAudio {
						t.Errorf("산출 파트에 남기지 않은 트랙 %d 가 실렸다", tr.ID)
					}
				}
			}
			for _, id := range []int{1, tt.wantAudio} {
				got, want := payloadsOf(parts, id), payloadsOf(inParts, id)
				if len(want) == 0 {
					t.Fatalf("픽스처 전제 실패 — 입력 트랙 %d 에 샘플이 없다", id)
				}
				if !slices.EqualFunc(got, want, bytes.Equal) {
					t.Errorf("산출 트랙 %d 의 샘플 %d개가 입력 트랙 %d 의 샘플 %d개와 다르다", id, len(got), id, len(want))
				}
			}
		})
	}
}

// TestRemuxRejectsMissingSelectedTracks 는 무결성 ⓑ(남긴 트랙 ≠ 영상 1 + 소리 1)를 본다 —
// 뮤테이션 17b. 한쪽만 남은 ③ 은 되감기에서 소리 없는(또는 화면 없는) 구간이 된다.
func TestRemuxRejectsMissingSelectedTracks(t *testing.T) {
	raw := readFixture(t, fixture4s) // 트랙 1 = 영상, 2 = 소리
	tests := []struct {
		name  string
		input []byte
	}{
		{"영상만 있는 머리말", withHeader(t, raw, func(ini *fmp4.Init) { ini.Tracks = ini.Tracks[:1] })},
		{"소리만 있는 머리말", withHeader(t, raw, func(ini *fmp4.Init) { ini.Tracks = ini.Tracks[1:] })},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := Remuxer{}.Produce(context.Background(), Request{Input: bytes.NewReader(tt.input)})

			if !errors.Is(err, ErrMissingTracks) {
				t.Errorf("err = %v, want ErrMissingTracks", err)
			}
		})
	}
}

// withoutTrack 은 실물 조각의 본문에서 한 트랙의 샘플을 모두 뺀 조각을 만든다(머리말은 원본 그대로).
func withoutTrack(t *testing.T, raw []byte, trackID int) []byte {
	t.Helper()
	head, body := splitRecording(t, raw)
	parts := parsedParts(t, body)
	var edited fmp4.Parts
	for _, p := range parts {
		kept := &fmp4.Part{SequenceNumber: p.SequenceNumber}
		for _, tr := range p.Tracks {
			if tr.ID != trackID {
				kept.Tracks = append(kept.Tracks, tr)
			}
		}
		edited = append(edited, kept)
	}
	var buf seekablebuffer.Buffer
	if err := edited.Marshal(&buf); err != nil {
		t.Fatalf("픽스처 본문 쓰기 실패: %v", err)
	}
	return bytes.Join([][]byte{head, buf.Bytes()}, nil)
}

// TestRemuxRejectsZeroSampleTrack 는 무결성 ⓒ(남긴 두 트랙 중 하나라도 샘플 0)를 본다 —
// 뮤테이션 17c. 첫 소리가 비었을 때 **다음 소리로 넘어가지 않는다** — 선택은 머리말이 정하고,
// 빈 트랙을 가진 ③ 은 그 자체로 발행하지 않는다.
func TestRemuxRejectsZeroSampleTrack(t *testing.T) {
	raw := readFixture(t, fixture1v6a) // 트랙 1 = 영상, 2..7 = 소리
	tests := []struct {
		name  string
		input []byte
	}{
		{"첫 소리(트랙 2)만 비었다 — 소리 3..7 은 샘플이 있다", withoutTrack(t, raw, 2)},
		{"영상(트랙 1)이 비었다", withoutTrack(t, raw, 1)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := Remuxer{}.Produce(context.Background(), Request{Input: bytes.NewReader(tt.input)})

			if !errors.Is(err, ErrEmptyTrack) {
				t.Errorf("err = %v, want ErrEmptyTrack", err)
			}
		})
	}
}

// TestRemuxStampsPosAndPreservesAVPhase 는 시간 도장을 본다(계획 4.2-R RE·R3) — 뮤테이션 15·19.
//
// 두 트랙의 BaseTime 에 **같은 pos** 를 **각자의 시간 단위로** 더해야 파일 안 A/V 위상이
// 그대로 남는다(녹화기는 모든 트랙을 공통 segmentStartDTS 기준으로 쓴다 — 포크
// format_fmp4_part.go:71). 파트를 다시 자르지 않으므로 파트 수·순번도 입력 그대로다.
//
// 입력 tfdt 는 segment_4s.mp4 의 상자 지도, 더할 값은 손 계산이다:
// 12.345678901초 × 90000 = 1,111,111.1 → 1,111,111 · × 44100 = 544,444.4 → 544,444.
func TestRemuxStampsPosAndPreservesAVPhase(t *testing.T) {
	const video, audio = 1, 2
	want := []struct {
		seq          uint32
		video, audio uint64 // 산출 BaseTime(틱)
	}{
		{0, 1099 + 1_111_111, 0 + 544_444},
		{1, 91126 + 1_111_111, 44055 + 544_444},
		{2, 181107 + 1_111_111, 88068 + 544_444},
		{3, 271105 + 1_111_111, 132080 + 544_444},
	}
	raw := readFixture(t, fixture4s)
	_, inBody := splitRecording(t, raw)
	inParts := parsedParts(t, inBody)

	_, parts := parseOutput(t, produce(t, raw, 12_345_678_901))

	if len(parts) != len(want) {
		t.Fatalf("산출 파트 %d개, want %d — 파트를 다시 잘랐다", len(parts), len(want))
	}
	for i, w := range want {
		if parts[i].SequenceNumber != w.seq {
			t.Errorf("파트 %d 순번 = %d, want %d", i, parts[i].SequenceNumber, w.seq)
		}
		if got := baseOf(t, parts[i], video); got != w.video {
			t.Errorf("파트 %d 영상 BaseTime = %d, want %d", i, got, w.video)
		}
		if got := baseOf(t, parts[i], audio); got != w.audio {
			t.Errorf("파트 %d 소리 BaseTime = %d, want %d", i, got, w.audio)
		}
		// 위상 = 영상 시작 − 소리 시작(ns). 반올림 오차 1틱(소리 1틱 ≈ 22.7µs) 안에서 같아야 한다.
		inPhase := phaseNS(baseOf(t, inParts[i], video), baseOf(t, inParts[i], audio))
		outPhase := phaseNS(baseOf(t, parts[i], video), baseOf(t, parts[i], audio))
		if d := outPhase - inPhase; d < -audioTickNS || d > audioTickNS {
			t.Errorf("파트 %d A/V 위상이 %dns 움직였다(허용 ±%dns)", i, d, audioTickNS)
		}
	}
}

const audioTickNS = int64(time.Second) / 44100

// phaseNS 는 영상 시작(90kHz 틱)과 소리 시작(44.1kHz 틱)의 차이를 나노초로 돌려준다.
func phaseNS(videoTicks, audioTicks uint64) int64 {
	return int64(videoTicks)*int64(time.Second)/90000 - int64(audioTicks)*int64(time.Second)/44100
}

// baseOf 는 파트 안 한 트랙의 BaseTime 이다.
func baseOf(t *testing.T, p *fmp4.Part, trackID int) uint64 {
	t.Helper()
	for _, tr := range p.Tracks {
		if tr.ID == trackID {
			return tr.BaseTime
		}
	}
	t.Fatalf("파트 %d 에 트랙 %d 가 없다", p.SequenceNumber, trackID)
	return 0
}

// laterSegment 는 같은 머리말에 앞 파트 두 개를 뺀 본문을 붙인 조각이다 — 같은 연결의 다른 조각 대역.
func laterSegment(t *testing.T, raw []byte) []byte {
	t.Helper()
	head, body := splitRecording(t, raw)
	var buf seekablebuffer.Buffer
	if err := parsedParts(t, body)[2:].Marshal(&buf); err != nil {
		t.Fatalf("픽스처 본문 쓰기 실패: %v", err)
	}
	return bytes.Join([][]byte{head, buf.Bytes()}, nil)
}

// otherConnection 은 트랙 파라미터는 그대로 두고 연결마다 달라지는 값만 바꾼 조각이다 —
// mtxi(녹화기 표식·조각 번호·누적 DTS 0·NTP) · udta(상자 하나 더) · mvhd(녹화기가 적은 길이 대신 0).
func otherConnection(t *testing.T, raw []byte) []byte {
	t.Helper()
	return withHeader(t, raw, func(ini *fmp4.Init) {
		m, ok := ini.UserData[0].(*mtxiBox)
		if !ok {
			t.Fatalf("픽스처 udta 첫 상자가 mtxi 가 아니다(%T)", ini.UserData[0])
		}
		edited := *m
		edited.RecorderID = [16]byte{0xaa, 0xbb, 0xcc}
		edited.SegmentNumber = 7
		edited.DTS = 0
		edited.NTP += int64(10 * time.Minute)
		ini.UserData = []amp4.IBox{&edited, &amp4.Free{Data: []byte("pokeclip")}}
	})
}

// TestRemuxInitIdenticalAcrossSegmentsAndConnections 는 산출 init 이 트랙 파라미터만의
// 순수 함수인지 본다 — 설계 5.3ⓑ init 동일성, 리스크 A2, 뮤테이션 14.
//
// 녹화기는 파일마다 mvhd 길이와 mtxi 를 새로 적는다. 그 값이 산출 init 에 실리면 같은 회차의
// 조각마다 init 이 달라져 init CAS 가 영구 실패하고 매 조각이 init_mismatch 로 회차를 끊는다.
func TestRemuxInitIdenticalAcrossSegmentsAndConnections(t *testing.T) {
	raw := readFixture(t, fixture4s)
	inputs := []struct {
		name  string
		input []byte
	}{
		{"조각 A(연결 1)", raw},
		{"조각 B(연결 1 — 같은 머리말·다른 본문)", laterSegment(t, raw)},
		{"조각 C(연결 2 — mvhd·mtxi·udta 가 다르다)", otherConnection(t, raw)},
	}
	headA, _ := splitRecording(t, raw)
	headC, _ := splitRecording(t, inputs[2].input)
	if bytes.Equal(headA, headC) {
		t.Fatal("픽스처 전제 실패 — 연결 2 의 머리말이 연결 1 과 같다")
	}

	var first Output
	for i, in := range inputs {
		out := produce(t, in.input, 8*time.Second)
		if out.InitSHA256 != sha256.Sum256(out.Init) {
			t.Errorf("%s: InitSHA256 이 실제 init 바이트의 해시가 아니다", in.name)
		}
		if i == 0 {
			first = out
			continue
		}
		if !bytes.Equal(out.Init, first.Init) {
			t.Errorf("%s: 산출 init 이 조각 A 와 다르다(sha %x ≠ %x)", in.name, out.InitSHA256, first.InitSHA256)
		}
	}
}

// TestRemuxInitDiffersWhenTrackParamsChange 는 위 동일성 검사의 음성 대조군이다 — init 이
// 트랙 파라미터를 실제로 담는지 본다. 담지 않으면 동일성은 공허하게 참이 되고, 송출자가
// 인코더 설정을 바꿔 재접속해도 init_mismatch(설계 5.3ⓒ)가 영영 발화하지 않는다.
func TestRemuxInitDiffersWhenTrackParamsChange(t *testing.T) {
	raw := readFixture(t, fixture4s)
	base := produce(t, raw, 0)
	tests := []struct {
		name  string
		input []byte
	}{
		// 실물: 해상도·코덱은 같고 avcC(SPS/PPS 뒷부분)만 다른 두 녹화 — 인코더 세부 설정 차이.
		{"다른 녹화(avcC 상이)", readFixture(t, fixtureTail)},
		{"소리 시간 단위 44100 → 48000", withHeader(t, raw, func(ini *fmp4.Init) { ini.Tracks[1].TimeScale = 48000 })},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			out := produce(t, tt.input, 0)

			if out.InitSHA256 == base.InitSHA256 {
				t.Errorf("트랙 파라미터가 다른데 산출 init 이 같다(sha %x)", out.InitSHA256)
			}
		})
	}
}

// corruptFirstTrunDataOffset 는 첫 trun 의 data_offset 을 파일 밖으로 돌린 조각을 만든다.
// 상자 크기는 그대로라 최상위 상자 검사는 통과하고, 파트 해석만 이것을 가려낼 수 있다.
//
//	trun: [크기 4][형식 4][version·flags 4][sample_count 4][data_offset 4]...
func corruptFirstTrunDataOffset(t *testing.T, raw []byte) []byte {
	t.Helper()
	head, _ := splitRecording(t, raw)
	i := bytes.Index(raw[len(head):], []byte("trun"))
	if i < 0 {
		t.Fatal("픽스처에 trun 이 없다")
	}
	out := bytes.Clone(raw)
	binary.BigEndian.PutUint32(out[len(head)+i+12:], 0x7fffffff)
	return out
}

// TestRemuxRejectsCorruptedInput 은 무결성 ⓐ 의 손상 갈래다 — 상자 경계는 멀쩡한데 내용이
// 해석되지 않는 입력. 3자 파서의 오류를 삼키면 잘린 샘플이나 빈 트랙이 조용히 실린다.
func TestRemuxRejectsCorruptedInput(t *testing.T) {
	raw := readFixture(t, fixture4s)
	tests := []struct {
		name  string
		input []byte
	}{
		{"머리말: 영상 mdhd 시간 단위 0", withHeader(t, raw, func(ini *fmp4.Init) { ini.Tracks[0].TimeScale = 0 })},
		{"본문: 첫 trun 의 data_offset 이 파일 밖", corruptFirstTrunDataOffset(t, raw)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := Remuxer{}.Produce(context.Background(), Request{Input: bytes.NewReader(tt.input)})

			if !errors.Is(err, ErrMalformedInput) {
				t.Errorf("err = %v, want ErrMalformedInput", err)
			}
		})
	}
}

// TestRemuxRejectsInvalidRequest 는 요청 경계 검사를 본다. 입력 핸들이 없으면 워커 goroutine 이
// 패닉으로 죽고, 음수 pos 는 정수 나눗셈 절삭으로 도장을 틀리게 만든다(계획 4.2-R RE: ns ≥ 0 전제).
func TestRemuxRejectsInvalidRequest(t *testing.T) {
	raw := readFixture(t, fixture4s)
	tests := []struct {
		name string
		req  Request
	}{
		{"입력 핸들 없음", Request{}},
		{"음수 pos", Request{Input: bytes.NewReader(raw), PlaybackPos: -time.Nanosecond}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			out, err := Remuxer{}.Produce(context.Background(), tt.req)

			if err == nil {
				t.Errorf("Produce(%+v) = %d+%d바이트, want 오류", tt.req, len(out.Init), len(out.Seg))
			}
		})
	}
}

// cancelOnRead 는 첫 Read 에서 요청을 취소하는 리더다 — "읽는 사이에 취소됐다"의 대역.
type cancelOnRead struct {
	io.ReadSeeker
	cancel context.CancelFunc
}

func (r cancelOnRead) Read(p []byte) (int, error) {
	r.cancel()
	return r.ReadSeeker.Read(p)
}

// TestRemuxStopsOnCanceledContext 는 취소된 요청이 산출을 내지 않는지 본다 — 자식 프로세스가
// 없으므로 취소는 단계 사이에서 ctx 를 확인해 받는다(계획 4.2-R RE 「상수」): 읽기 전과,
// 유일한 I/O 인 읽기 뒤. 읽기 전 갈래는 Read 가 0회임을 함께 단언한다 — 읽은 뒤의 검사만으로도
// 같은 오류가 나오므로, 읽기 전 검사가 사라져 16MiB 를 다 읽고서야 거절하는 회귀는 이것만 잡는다.
func TestRemuxStopsOnCanceledContext(t *testing.T) {
	raw := readFixture(t, fixture4s)
	tests := []struct {
		name  string
		input func(cancel context.CancelFunc) io.ReadSeeker
		early bool // 읽기 전에 이미 취소됐는가
	}{
		{"읽기 전에 취소", func(context.CancelFunc) io.ReadSeeker {
			return &sizedReader{size: int64(len(raw)), data: int64(len(raw))}
		}, true},
		{"읽는 사이에 취소", func(cancel context.CancelFunc) io.ReadSeeker {
			return cancelOnRead{ReadSeeker: bytes.NewReader(raw), cancel: cancel}
		}, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if tt.early {
				cancel()
			}

			in := tt.input(cancel)
			_, err := Remuxer{}.Produce(ctx, Request{Input: in})

			if !errors.Is(err, context.Canceled) {
				t.Errorf("err = %v, want context.Canceled", err)
			}
			if sr, ok := in.(*sizedReader); ok && sr.reads != 0 {
				t.Errorf("읽기 전에 취소됐는데 Read 가 %d회 불렸다 — 읽기 전 ctx 검사가 없다", sr.reads)
			}
		})
	}
}

// TestRemuxOutputBytesGolden 은 산출 바이트 계약이다 — 리스크 A2·A4(r17, cx 확인 #4).
//
// 라이브러리 회귀(재포장 형상·상자 배치가 바뀌는 것)를 런타임이 아니라 출고 전에 잡는다.
// 입력 = testdata 실물 조각, pos = 그 조각 자기 mtxi(평소 경로 — offset 0).
// 고정값은 이 구현으로 한 번 계산해 박았고, 스파이크 실측 프로그램(계획 68_ 원문, 2026-09-23
// 71_ ② 실행)이 같은 파일·같은 pos 로 따로 낸 산출과 바이트 단위로 같다(init·조각 sha256 일치).
// 골든만으로는 버전 변경을 못 잡는다(v2.9.3 ≡ v2.9.4 산출 실측) — 아래 핀 검사가 짝이다.
func TestRemuxOutputBytesGolden(t *testing.T) {
	const (
		wantInitLen = 1159
		wantInitSHA = "83e663c55de687079f35d58c91889928dd6a86716bfa5f1cfbf11f932cf5e0cc"
		wantSegLen  = 141788
		wantSegSHA  = "061b4f1084743a7980edb6757344dc48101dbabbfc90cce9aa16c6f313e92f9c"
	)

	out := produce(t, readFixture(t, fixture4s), 5_987_981_859)

	if len(out.Init) != wantInitLen || hexSHA(out.Init) != wantInitSHA {
		t.Errorf("init = %d바이트 sha %s, want %d바이트 sha %s", len(out.Init), hexSHA(out.Init), wantInitLen, wantInitSHA)
	}
	if len(out.Seg) != wantSegLen || hexSHA(out.Seg) != wantSegSHA {
		t.Errorf("조각 = %d바이트 sha %s, want %d바이트 sha %s", len(out.Seg), hexSHA(out.Seg), wantSegLen, wantSegSHA)
	}
}

func hexSHA(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// TestRemuxMediacommonVersionMatchesRecorderPin 은 재포장 라이브러리가 녹화기와 같은 판인지
// 본다 — 리스크 A4, 뮤테이션 17e. 녹화기(포크 v1.20.1-pokeclip.2 실행 파일, `go version -m`)가
// 녹화 조각을 쓸 때 쓴 mediacommon/v2 가 v2.9.3 이다. 판이 갈리면 상자 배치 해석이 갈릴 수 있고,
// 산출 골든은 그 변화를 못 잡는다(v2.9.3 ≡ v2.9.4 산출 바이트 동일 실측 — 계획 68_).
// 포크 이미지 태그를 올릴 때 이 값과 go.mod 를 함께 고친다(계획 7절 9).
//
// 판은 빌드 정보가 아니라 go.mod 에서 읽는다 — 라이브러리 패키지의 테스트 바이너리는 빌드 정보에
// 의존 모듈을 싣지 않는다(debug.ReadBuildInfo().Deps 가 비어 있다; package main 의 테스트
// 바이너리만 싣는다 — go1.26.5 실측). go.mod 의 판이 실제 선택 판과 다르면 go 명령이 기본
// -mod=readonly 에서 빌드를 거부하므로("updates to go.mod needed" — 실측), 이 테스트가 돌고
// 있다면 go.mod 의 판이 곧 링크된 판이다.
func TestRemuxMediacommonVersionMatchesRecorderPin(t *testing.T) {
	const (
		module        = "github.com/bluenviron/mediacommon/v2"
		recorderBuilt = "v2.9.3"
	)
	var required string
	for _, line := range strings.Split(string(readGoMod(t)), "\n") {
		if strings.Contains(line, module) && strings.Contains(line, "=>") {
			t.Fatalf("go.mod 가 %s 를 replace 한다 — 녹화기 판과의 일치를 보장할 수 없다: %q", module, strings.TrimSpace(line))
		}
		fields := strings.Fields(strings.TrimPrefix(strings.TrimSpace(line), "require "))
		if len(fields) >= 2 && fields[0] == module {
			required = fields[1]
		}
	}
	if required != recorderBuilt {
		t.Errorf("go.mod 의 %s = %q, want %s(녹화기 내장 판)", module, required, recorderBuilt)
	}
}

// readGoMod 는 작업 디렉터리에서 위로 올라가며 처음 만나는 go.mod 를 읽는다(이 모듈의 go.mod).
func readGoMod(t *testing.T) []byte {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatalf("작업 디렉터리 확인 실패: %v", err)
	}
	for {
		b, err := os.ReadFile(filepath.Join(dir, "go.mod"))
		if err == nil {
			return b
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatal("go.mod 를 찾지 못했다")
		}
		dir = parent
	}
}
