package playback

import (
	"bytes"
	"errors"
	"io"
	"testing"
	"time"
)

// TestMtxiLayoutContract 는 mtxi 상자 배치(실측 44B)를 못 박는다 — 계획 4.2-R RE.
//
//	FullBox 4B · 녹화기 표식 16B · 조각 번호 u64 · 누적 DTS i64 나노초 · NTP i64 나노초
//
// 상류 비공개 형식이라 문서가 없다. 필드마다 서로 다른 바이트를 넣어 두면, 순서·크기가
// 하나만 어긋나도 값이 바뀌어 잡힌다. 크기가 44B 가 아니면 형식이 바뀐 것이라 실패다.
func TestMtxiLayoutContract(t *testing.T) {
	recorder := []byte{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f}
	payload := bytes.Join([][]byte{
		{0, 0, 0, 0}, // version 0 · flags 0
		recorder,
		be64(0x1011121314151617),  // 조각 번호
		be64(6_000_000_123),       // 누적 DTS(ns)
		be64(1785564965619581782), // NTP(ns)
	}, nil)
	header := func(p []byte) []byte { return box("moov", box("udta", box("mtxi", p))) }

	got, err := ReadMtxi(bytes.NewReader(header(payload)))
	if err != nil {
		t.Fatalf("ReadMtxi 실패: %v", err)
	}
	want := Mtxi{
		RecorderID:    [16]byte(recorder),
		SegmentNumber: 0x1011121314151617,
		DTS:           6_000_000_123 * time.Nanosecond,
		NTP:           time.Unix(0, 1785564965619581782),
	}
	if got.RecorderID != want.RecorderID || got.SegmentNumber != want.SegmentNumber ||
		got.DTS != want.DTS || !got.NTP.Equal(want.NTP) {
		t.Errorf("ReadMtxi = %+v, want %+v", got, want)
	}

	for _, n := range []int{43, 45} {
		p := make([]byte, n)
		if _, err := ReadMtxi(bytes.NewReader(header(p))); err == nil {
			t.Errorf("mtxi 내용 %dB 가 통과했다 — 44B 배치가 아니면 형식 변경이다", n)
		}
	}
}

// TestReadMtxiReadsRecorderValues 는 녹화기 실물의 값을 읽는지 본다. 기대값은 이 패키지와
// 무관한 1회성 판독 프로그램(계획 67_ 실측 프로그램과 같은 상자 정의)으로 뽑았다.
func TestReadMtxiReadsRecorderValues(t *testing.T) {
	tests := []struct {
		file       string
		recorderID string
		segment    uint64
		dts        time.Duration
		ntp        int64
	}{
		{fixture4s, "5204c7b4d7234bb0a751a18ac619856c", 1, 5_987_981_859, 1785564965619581782},
		{fixtureTail, "cf6d2201dc9543b9a580e6cd1b8a26f5", 4, 17_992_993_197, 1785564871899441660},
		{fixture1v6a, "bcde804ea09b4dac8c141d9917b6c255", 1, 5_804_988_662, 1790155256803838571},
	}
	for _, tt := range tests {
		t.Run(tt.file, func(t *testing.T) {
			got, err := ReadMtxi(bytes.NewReader(readFixture(t, tt.file)))
			if err != nil {
				t.Fatalf("ReadMtxi 실패: %v", err)
			}
			if want := mustHex(t, tt.recorderID); got.RecorderID != want {
				t.Errorf("RecorderID = %x, want %x", got.RecorderID, want)
			}
			if got.SegmentNumber != tt.segment {
				t.Errorf("SegmentNumber = %d, want %d", got.SegmentNumber, tt.segment)
			}
			if got.DTS != tt.dts {
				t.Errorf("DTS = %v, want %v", got.DTS, tt.dts)
			}
			if want := time.Unix(0, tt.ntp); !got.NTP.Equal(want) {
				t.Errorf("NTP = %v, want %v", got.NTP, want)
			}
		})
	}
}

func TestReadMtxiFailsWithoutMtxiBox(t *testing.T) {
	noMtxi := box("moov", box("udta", box("free", []byte("x"))))

	if _, err := ReadMtxi(bytes.NewReader(noMtxi)); err == nil {
		t.Fatal("mtxi 가 없는 머리말에서 오류가 없다 — 0 값이 누적 DTS 로 쓰이면 도장이 0 으로 돌아간다")
	}
}

// TestTrackEndsReportsEachKeptTrackEnd 는 남긴 두 트랙(첫 영상·첫 소리) 각각의 조각 안 끝
// 시각을 본다 — 계획 4.2-R R3 의 end(k−1) = pos(k−1) + min(두 끝) 재료다.
//
// 끝 = 그 트랙 마지막 샘플의 끝(BaseTime + 샘플 길이 합)이고, 조각 자기 도장 축 그대로다.
// 기대값은 상자 지도의 틱을 손으로 나눈 값이며, ffprobe 독립 실측(fmp4meta 테스트 머리 주석:
// 4.012411s/3.994014s · 1.973844s/2.042993s)과 같다. 꼬리 조각은 소리가 더 늦게 끝난다 —
// 두 끝이 서로 독립이어야 "먼저 끝난 트랙"을 고를 수 있다.
func TestTrackEndsReportsEachKeptTrackEnd(t *testing.T) {
	tests := []struct {
		file  string
		video time.Duration // 영상 끝 틱 / 90000
		audio time.Duration // 첫 소리 끝 틱 / 44100
	}{
		{fixture4s, 4_012_411_111, 3_994_013_605},   // 361117 · 176136 틱
		{fixtureTail, 1_973_844_444, 2_042_993_197}, // 177646 · 90096 틱(마지막 파트는 소리만)
		{fixture1v6a, 228_344_444, 139_319_728},     // 20551 · 6144 틱
	}
	for _, tt := range tests {
		t.Run(tt.file, func(t *testing.T) {
			got, err := TrackEnds(bytes.NewReader(readFixture(t, tt.file)))
			if err != nil {
				t.Fatalf("TrackEnds 실패: %v", err)
			}
			if got.Video != tt.video || got.Audio != tt.audio {
				t.Errorf("TrackEnds = {영상 %v, 소리 %v}, want {영상 %v, 소리 %v}", got.Video, got.Audio, tt.video, tt.audio)
			}
		})
	}
}

// TestTrackEndsRejectsMalformedInput 은 재포장과 같은 무결성 검사를 거치는지 본다 — 잘린 조각의
// "끝"은 실제 끝이 아니라서, 그 값으로 이으면 도장이 겹치거나 뜬다. 실패하면 호출자가 장부
// 길이로 잇는다(계획 4.2-R R3).
func TestTrackEndsRejectsMalformedInput(t *testing.T) {
	raw := readFixture(t, fixture4s)

	_, err := TrackEnds(bytes.NewReader(raw[:len(raw)-1000]))

	if !errors.Is(err, ErrMalformedInput) {
		t.Errorf("err = %v, want ErrMalformedInput", err)
	}
}

// TestReadersRestoreInputPosition 은 두 판독 함수가 리더 위치와 무관하게 처음부터 읽고, 끝나면
// 부르기 전 위치로 되돌리는지 본다. 스위퍼는 이미 연 입력(Request.Input)에서 mtxi 를 읽은 뒤
// 같은 리더를 Produce 에 넘긴다(계획 4.2-R 「스위퍼 재생성」).
func TestReadersRestoreInputPosition(t *testing.T) {
	const start = 777 // 아무 상자 경계도 아닌 자리
	readers := []struct {
		name string
		read func(io.ReadSeeker) error
	}{
		{"ReadMtxi", func(r io.ReadSeeker) error { _, err := ReadMtxi(r); return err }},
		{"TrackEnds", func(r io.ReadSeeker) error { _, err := TrackEnds(r); return err }},
	}
	for _, rd := range readers {
		t.Run(rd.name, func(t *testing.T) {
			r := bytes.NewReader(readFixture(t, fixture4s))
			if _, err := r.Seek(start, io.SeekStart); err != nil {
				t.Fatal(err)
			}

			if err := rd.read(r); err != nil {
				t.Fatalf("%s 가 중간 위치의 리더에서 실패했다: %v", rd.name, err)
			}

			if pos, _ := r.Seek(0, io.SeekCurrent); pos != start {
				t.Errorf("%s 뒤 리더 위치 = %d, want %d", rd.name, pos, start)
			}
		})
	}
}
