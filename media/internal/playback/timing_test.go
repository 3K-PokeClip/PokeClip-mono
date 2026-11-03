package playback

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4"
	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4/seekablebuffer"
)

// 이 파일은 ReadTiming(조각 하나의 시각 사실 판독 — 설계 r7 2.2 · 3절)을 본다.
//
// 실물 둘을 더 둔다 — 둘 다 r6 실측(t9vm, 포크 v1.21.1-pokeclip.2 녹화 2026-11-02)의 대표 조각을
// 이름만 바꿔 그대로 옮긴 것이다(`60_pdt_r6_measure/t9vm/raw/keep/index.txt`):
//
//	t9slowP2_c806cfd1_n0_audio_only.mp4    영상 트랙은 있으나 표본이 없는 인스턴스(소리 225ms)
//	t9slow96_a627ef82_n0_short_instance.mp4 재시작 6초 만에 다시 재시작한 짧은 인스턴스(영상 400ms)
const (
	fixtureAudioOnly     = "testdata/t9slowP2_c806cfd1_n0_audio_only.mp4"
	fixtureShortInstance = "testdata/t9slow96_a627ef82_n0_short_instance.mp4"
)

// TestReadTimingReadsRecorderFixtures 는 녹화기 실물에서 트랙별 첫 표본 시작 · 마지막 표본 끝 · 머리
// 트랙 · 머리 앞섬을 읽는지 본다.
//
// 기대값은 상자 지도의 틱을 손으로 나눈 값이다(괄호 = 틱 · 영상 90000 · 소리 44100 또는 48000). 1v6a 는
// 소리 여섯이 모두 0 에서 시작한다 — 동률이면 init 순서가 뒤인 트랙(7)이 머리다(상류
// format_fmp4_track.go 의 `dts <= oldestDTS`). 잡는 결함: 동률을 ID 작은 쪽으로 풀면 머리가 2 가 된다.
func TestReadTimingReadsRecorderFixtures(t *testing.T) {
	tests := []struct {
		file     string
		video    *TrackSpan
		audio    TrackSpan
		head     Track
		headLead time.Duration
	}{
		{
			file:     fixture4s,
			video:    &TrackSpan{Start: 12_211_111, End: 4_012_411_111}, // 1099 · 361117
			audio:    TrackSpan{Start: 0, End: 3_994_013_605},           // 176136
			head:     Track{ID: 2},
			headLead: 12_211_111,
		},
		{
			file:     fixtureTail,
			video:    &TrackSpan{Start: 7_233_333, End: 1_973_844_444}, // 651 · 177646
			audio:    TrackSpan{Start: 0, End: 2_042_993_197},          // 90096
			head:     Track{ID: 2},
			headLead: 7_233_333,
		},
		{
			file:     fixture1v6a,
			video:    &TrackSpan{Start: 90_400_000, End: 257_066_667}, // 8136 · 23136
			audio:    TrackSpan{Start: 0, End: 139_319_728},           // 6144(첫 소리 = 트랙 2)
			head:     Track{ID: 7},
			headLead: 90_400_000,
		},
		{
			// 영상 트랙 1 은 머리말에 있으나 표본이 0 개다.
			file:     fixtureAudioOnly,
			video:    nil,
			audio:    TrackSpan{Start: 0, End: 225_250_000}, // 10812
			head:     Track{ID: 2},
			headLead: 0,
		},
		{
			file:     fixtureShortInstance,
			video:    &TrackSpan{Start: 460_788_889, End: 860_800_000}, // 41471 · 77472
			audio:    TrackSpan{Start: 0, End: 675_833_333},            // 32440
			head:     Track{ID: 2},
			headLead: 460_788_889,
		},
	}
	for _, tt := range tests {
		t.Run(tt.file, func(t *testing.T) {
			raw := readFixture(t, tt.file)

			got, err := ReadTiming(bytes.NewReader(raw))
			if err != nil {
				t.Fatalf("ReadTiming 실패: %v", err)
			}

			want, err := ReadMtxi(bytes.NewReader(raw))
			if err != nil {
				t.Fatalf("픽스처 전제 실패 — mtxi 가 없다: %v", err)
			}
			if got.Mtxi == nil {
				t.Fatal("Mtxi = nil — 머리말에 mtxi 가 있다")
			}
			if got.Mtxi.RecorderID != want.RecorderID || got.Mtxi.SegmentNumber != want.SegmentNumber ||
				got.Mtxi.DTS != want.DTS || !got.Mtxi.NTP.Equal(want.NTP) {
				t.Errorf("Mtxi = %+v, want %+v(ReadMtxi)", *got.Mtxi, want)
			}
			checkVideo(t, got.Video, tt.video)
			if got.Audio == nil || *got.Audio != tt.audio {
				t.Errorf("Audio = %v, want %+v", got.Audio, tt.audio)
			}
			if got.Head != tt.head {
				t.Errorf("Head = %+v, want %+v", got.Head, tt.head)
			}
			if got.HeadLead != tt.headLead {
				t.Errorf("HeadLead = %v, want %v", got.HeadLead, tt.headLead)
			}
		})
	}
}

// TestReadTimingReportsDeterministicAbsenceAsValues 는 머리말에서 정해지는 부재(mtxi 없음 · 영상 트랙
// 없음)가 오류가 아니라 값(nil)인지 본다 — 설계 r6 2.2 「결정적」. 잡는 결함: 부재를 오류로 내면 인덱서가
// 일시 실패로 알고 같은 조각을 끝없이 다시 읽는다.
//
// 셋째 하위 시험(첫 영상 트랙이 빔)은 결정적 부재가 아니라 읽은 시점의 값이다 — 트랙은 머리말에 있고
// 본문에 표본이 0 개라 파일이 자라면 바뀔 수 있다(설계 r7 2.14). 같은 값(nil)으로 돌려준다는 것만 본다.
func TestReadTimingReportsDeterministicAbsenceAsValues(t *testing.T) {
	raw := readFixture(t, fixture4s) // 트랙 1 = 영상, 2 = 소리

	t.Run("mtxi 없음", func(t *testing.T) {
		in := withHeader(t, raw, func(ini *fmp4.Init) { ini.UserData = nil })

		got, err := ReadTiming(bytes.NewReader(in))

		if err != nil {
			t.Fatalf("err = %v, want nil — mtxi 없음은 결정적 사실이다", err)
		}
		if got.Mtxi != nil {
			t.Errorf("Mtxi = %+v, want nil", *got.Mtxi)
		}
		checkVideo(t, got.Video, &TrackSpan{Start: 12_211_111, End: 4_012_411_111})
	})
	t.Run("영상 트랙 없음", func(t *testing.T) {
		in := withHeader(t, raw, func(ini *fmp4.Init) { ini.Tracks = ini.Tracks[1:] })

		got, err := ReadTiming(bytes.NewReader(in))

		if err != nil {
			t.Fatalf("err = %v, want nil — 영상 트랙 없음은 결정적 사실이다", err)
		}
		checkVideo(t, got.Video, nil)
		if got.Head != (Track{ID: 2}) || got.HeadLead != 0 {
			t.Errorf("Head = %+v · HeadLead = %v, want 트랙 2 · 0", got.Head, got.HeadLead)
		}
	})
	t.Run("첫 영상 트랙이 비면 다음 영상 트랙으로 넘어가지 않는다", func(t *testing.T) {
		// 표본 없는 영상 트랙 9 를 맨 앞에 둔다 — ③ 이 남기는 영상(Produce 의 첫 영상)과 같은 트랙을 본다.
		in := withHeader(t, raw, func(ini *fmp4.Init) {
			empty := *ini.Tracks[0]
			empty.ID = 9
			ini.Tracks = append([]*fmp4.InitTrack{&empty}, ini.Tracks...)
		})

		got, err := ReadTiming(bytes.NewReader(in))

		if err != nil {
			t.Fatalf("ReadTiming 실패: %v", err)
		}
		checkVideo(t, got.Video, nil)
	})
}

// TestReadTimingKeepsSubMillisecondVideoSpan 은 영상 표본 하나가 45틱(0.5ms)인 합성 조각에서 그 길이를
// 그대로 읽는지 본다 — 예측표 P7 의 판독 몫. 1ms 하한은 판독이 아니라 섞기(MixV2)의 일이다. 잡는 결함:
// 판독이 ms 로 내리면 영상 길이가 0 이 되어 「영상 앎」 판정의 재료가 사라진다.
//
// 머리 트랙 규칙도 함께 본다 — 영상이 먼저 시작하면 영상이 머리이고, 영상과 소리가 같은 틱에서
// 시작하면 init 순서가 뒤인 소리가 머리다.
func TestReadTimingKeepsSubMillisecondVideoSpan(t *testing.T) {
	tests := []struct {
		name       string
		audioStart uint64 // 소리(44100) 첫 표본의 BaseTime
		head       Track
	}{
		{"영상이 먼저 시작 — 머리 = 영상", 44, Track{ID: 1, Video: true}},
		{"같은 시각 시작 — 머리 = init 순서가 뒤인 소리", 0, Track{ID: 2}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			in := withParts(t, readFixture(t, fixture4s), fmp4.Parts{{
				SequenceNumber: 1,
				Tracks: []*fmp4.PartTrack{
					{ID: 1, BaseTime: 0, Samples: []*fmp4.Sample{{Duration: 45, Payload: []byte{1}}}},
					{ID: 2, BaseTime: tt.audioStart, Samples: []*fmp4.Sample{{Duration: 1024, Payload: []byte{2}}}},
				},
			}})

			got, err := ReadTiming(bytes.NewReader(in))
			if err != nil {
				t.Fatalf("ReadTiming 실패: %v", err)
			}

			checkVideo(t, got.Video, &TrackSpan{Start: 0, End: 500 * time.Microsecond})
			if got.Head != tt.head || got.HeadLead != 0 {
				t.Errorf("Head = %+v · HeadLead = %v, want %+v · 0", got.Head, got.HeadLead, tt.head)
			}
		})
	}
}

// TestReadTimingReportsTransientFailures 는 본문 해석 실패 · I/O 오류 · 읽는 사이 크기 변화가
// ErrTransientRead 인지 본다 — 설계 r7 2.14. 본문은 파일이 자라면 바뀌므로 다시 읽으면 다른 답이 날 수
// 있다. 잡는 결함(변이 m-R1): 일시 실패를 결정적 부재로 접으면 잘린 조각의 끝이 장부 사실로 굳는다.
//
// 절단 자리는 segment_4s.mp4 의 상자 지도(moov 32 · moof 1219/39973/75198/112095)에서 골랐다.
func TestReadTimingReportsTransientFailures(t *testing.T) {
	raw := readFixture(t, fixture4s)
	tests := []struct {
		name string
		in   io.ReadSeeker
	}{
		{"머리말(moov) 한가운데에서 잘림", bytes.NewReader(raw[:600])},
		{"머리말만 있고 파트가 없음(녹화기가 init 만 쓴 순간)", bytes.NewReader(raw[:1219])},
		{"셋째 moof 의 상자 머리 4바이트만 남음", bytes.NewReader(raw[:75198+4])},
		{"마지막 mdat 한가운데에서 잘림", bytes.NewReader(raw[:len(raw)-1000])},
		// 상자 경계는 멀쩡해 최상위 상자 검사를 지나고, 머리말 · 파트 해석만 가려낼 수 있는 손상이다.
		{"머리말 해석 실패(영상 mdhd 시간 단위 0)", bytes.NewReader(withHeader(t, raw, func(ini *fmp4.Init) { ini.Tracks[0].TimeScale = 0 }))},
		{"본문 해석 실패(첫 trun 의 data_offset 이 파일 밖)", bytes.NewReader(corruptFirstTrunDataOffset(t, raw))},
		{"표본이 하나도 없는 본문", bytes.NewReader(withParts(t, raw, fmp4.Parts{{
			SequenceNumber: 1,
			Tracks:         []*fmp4.PartTrack{{ID: 1}, {ID: 2}},
		}}))},
		// 머리말의 사실이지만 「상자 없음」 이 아니다 — 형식 변경을 mtxi 없음으로 접으면 신호 없이 칸이 빈다.
		{"mtxi 내용이 43B(녹화기 형식 변경)", bytes.NewReader(resizedMtxi(t, raw, -1))},
		{"mtxi 내용이 45B(녹화기 형식 변경)", bytes.NewReader(resizedMtxi(t, raw, +1))},
		{"읽기 I/O 오류", &failingReader{Reader: bytes.NewReader(raw), failAt: 50_000}},
		{"잰 크기보다 많이 읽힘(읽는 사이 자람)", &resizingReader{data: raw, endBefore: int64(len(raw) - 4096)}},
		{"다 읽은 뒤 크기가 바뀜", &resizingReader{data: raw, endBefore: int64(len(raw)), endAfter: int64(len(raw) + 4096)}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := ReadTiming(tt.in)

			if !errors.Is(err, ErrTransientRead) {
				t.Errorf("err = %v, want ErrTransientRead", err)
			}
		})
	}
}

// TestReadTimingRestoresInputPosition 은 ReadTiming 이 리더 위치와 무관하게 처음부터 읽고, 끝나면 부르기
// 전 위치로 되돌리는지 본다 — 같은 파일 판독 함수(ReadMtxi · TrackEnds)와 같은 규약이다.
func TestReadTimingRestoresInputPosition(t *testing.T) {
	const start = 777 // 아무 상자 경계도 아닌 자리
	r := bytes.NewReader(readFixture(t, fixture4s))
	if _, err := r.Seek(start, io.SeekStart); err != nil {
		t.Fatal(err)
	}

	if _, err := ReadTiming(r); err != nil {
		t.Fatalf("ReadTiming 이 중간 위치의 리더에서 실패했다: %v", err)
	}

	if pos, _ := r.Seek(0, io.SeekCurrent); pos != start {
		t.Errorf("ReadTiming 뒤 리더 위치 = %d, want %d", pos, start)
	}
}

// checkVideo 는 영상 구간이 기대와 같은지 본다(nil 포함).
func checkVideo(t *testing.T, got, want *TrackSpan) {
	t.Helper()
	switch {
	case want == nil && got != nil:
		t.Errorf("Video = %+v, want nil", *got)
	case want != nil && got == nil:
		t.Errorf("Video = nil, want %+v", *want)
	case want != nil && *got != *want:
		t.Errorf("Video = %+v, want %+v", *got, *want)
	}
}

// withParts 는 실물 조각의 머리말에 parts 를 본문으로 붙인 합성 조각이다.
func withParts(t *testing.T, raw []byte, parts fmp4.Parts) []byte {
	t.Helper()
	head, _ := splitRecording(t, raw)
	var buf seekablebuffer.Buffer
	if err := parts.Marshal(&buf); err != nil {
		t.Fatalf("합성 본문 쓰기 실패: %v", err)
	}
	return bytes.Join([][]byte{head, buf.Bytes()}, nil)
}

// resizedMtxi 는 실물 조각의 mtxi 내용을 끝에서 delta 바이트(1 또는 −1)만큼 늘이거나 줄인 조각이다.
// 담는 상자(moov · udta · mtxi)의 크기 칸도 함께 고친다 — withHeader 는 mtxi 를 정의대로 다시 써서
// 크기를 바꿀 수 없다.
func resizedMtxi(t *testing.T, raw []byte, delta int) []byte {
	t.Helper()
	head, body := splitRecording(t, raw)
	out := bytes.Clone(head)
	var mtxiEnd int
	for _, typ := range []string{"moov", "udta", "mtxi"} {
		at := bytes.Index(out, []byte(typ)) - 4
		if at < 0 {
			t.Fatalf("픽스처 머리말에 %s 가 없다", typ)
		}
		size := int(binary.BigEndian.Uint32(out[at:]))
		binary.BigEndian.PutUint32(out[at:], uint32(size+delta))
		mtxiEnd = at + size
	}
	if delta < 0 {
		return bytes.Join([][]byte{out[:mtxiEnd+delta], out[mtxiEnd:], body}, nil)
	}
	return bytes.Join([][]byte{out[:mtxiEnd], make([]byte, delta), out[mtxiEnd:], body}, nil)
}

// failingReader 는 failAt 바이트째부터 읽기 오류를 내는 리더다(디스크 I/O 오류의 대역).
type failingReader struct {
	*bytes.Reader
	failAt int64
}

func (r *failingReader) Read(p []byte) (int, error) {
	pos, _ := r.Seek(0, io.SeekCurrent)
	if pos >= r.failAt {
		return 0, errors.New("입출력 오류")
	}
	if room := r.failAt - pos; int64(len(p)) > room {
		p = p[:room]
	}
	return r.Reader.Read(p)
}

// resizingReader 는 data 를 내주되, 끝 위치(파일이면 fstat 크기)를 다 읽기 전에는 endBefore 로, 끝까지
// 읽은 뒤에는 endAfter(0 이면 endBefore)로 알리는 리더다 — 녹화기가 덧붙이는 중인 파일의 대역.
type resizingReader struct {
	data                []byte
	endBefore, endAfter int64
	pos                 int64
	drained             bool
}

func (r *resizingReader) Read(p []byte) (int, error) {
	if r.pos >= int64(len(r.data)) {
		r.drained = true
		return 0, io.EOF
	}
	n := copy(p, r.data[r.pos:])
	r.pos += int64(n)
	return n, nil
}

func (r *resizingReader) Seek(offset int64, whence int) (int64, error) {
	switch whence {
	case io.SeekStart:
		r.pos = offset
	case io.SeekCurrent:
		r.pos += offset
	case io.SeekEnd:
		end := r.endBefore
		if r.drained && r.endAfter != 0 {
			end = r.endAfter
		}
		r.pos = end + offset
	}
	return r.pos, nil
}
