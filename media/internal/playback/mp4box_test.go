package playback

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"slices"
	"testing"
	"time"

	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4"
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
		// 워커가 init 재포장에 넘길 같은 fd 에서 지문을 뜬다(판단 J63).
		{"StsdFingerprint", func(r io.ReadSeeker) error { _, err := StsdFingerprint(r); return err }},
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

// TestStsdFingerprintSeparatesCodecParameters 는 지문이 코덱 사양(stsd)을 가르는지 본다(ADR-044 RC3-28 — 재접속
// 계승의 호환 게이트). 같은 조각이면 같은 값이고, 코덱 파라미터가 다른 녹화(segment_4s · segment_tail_2s — 해상도는
// 같고 avcC 가 다르다 · stsd 크기도 같다)나 트랙 구성이 다른 녹화(1v+6a)는 다른 값이다. 잡는 결함: 지문이 크기나
// 트랙 수만 보면 avcC 가 다른 재접속을 호환으로 읽어, 옛 MAP 조각과 새 조각의 코덱 사양이 어긋난 목록이 나간다.
func TestStsdFingerprintSeparatesCodecParameters(t *testing.T) {
	fingerprint := func(t *testing.T, name string) [32]byte {
		t.Helper()
		fp, err := StsdFingerprint(bytes.NewReader(readFixture(t, name)))
		if err != nil {
			t.Fatalf("StsdFingerprint(%s) 실패: %v", name, err)
		}
		return fp
	}
	tests := []struct {
		name string
		a, b string
		same bool
	}{
		{"같은_조각", fixture4s, fixture4s, true},
		{"avcC_가_다른_녹화", fixture4s, fixtureTail, false},
		{"트랙_구성이_다른_녹화", fixture4s, fixture1v6a, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := fingerprint(t, tt.a) == fingerprint(t, tt.b); got != tt.same {
				t.Errorf("지문 같음 = %v, want %v", got, tt.same)
			}
		})
	}
}

// TestStsdFingerprintIgnoresMoovOutsideStsd 는 코덱 사양 밖의 머리말 차이를 지문이 보지 않는지 본다 — 재접속 계승은
// 회차 경계에서 어차피 새 MAP 으로 바꾸므로 코덱 사양만 같으면 된다(ADR-044 2026-08-31 갱신 3항). mvhd 가 다른
// 머리말(reconnect_same_stsd_different_moov_inherits 의 재료)과 트랙 순서만 다른 머리말은 같은 값이다(트랙 순서
// 정규화). 잡는 결함: 머리말 전체를 해시하면 같은 송출 설정의 재접속이 계승을 잃고, 순서를 정규화하지 않으면
// 트랙을 다른 순서로 쓴 같은 송출이 비호환이 된다.
//
// 두 변형 모두 mediacommon 이 머리말을 다시 쓴 조각이다 — mvhd 가 원본(녹화기가 길이를 적은 값)과 다르다
// (withHeader). 녹화기가 쓴 원본과 값이 같은지(stsd 바이트 보존)도 함께 본다.
func TestStsdFingerprintIgnoresMoovOutsideStsd(t *testing.T) {
	raw := readFixture(t, fixture4s)
	want, err := StsdFingerprint(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("원본 지문 실패: %v", err)
	}
	rawHead, _ := splitRecording(t, raw)
	variants := []struct {
		name string
		edit func(*fmp4.Init)
	}{
		{"머리말만_다시_씀", func(*fmp4.Init) {}},
		{"트랙_순서만_다름", func(ini *fmp4.Init) { slices.Reverse(ini.Tracks) }},
	}
	for _, v := range variants {
		t.Run(v.name, func(t *testing.T) {
			rewritten := withHeader(t, raw, v.edit)
			if head, _ := splitRecording(t, rewritten); bytes.Equal(head, rawHead) {
				t.Fatal("다시 쓴 머리말이 원본과 같다 — 준비가 어긋났다")
			}

			got, err := StsdFingerprint(bytes.NewReader(rewritten))

			if err != nil || got != want {
				t.Errorf("StsdFingerprint = %x, %v; want %x(원본과 같다), nil", got, err, want)
			}
		})
	}
}

// TestStsdFingerprintFollowsAudioTrackID 는 알려진 한계를 고정한다(kty 회부 대상). 녹화기와 mediacommon 은 소리 트랙
// stsd(mp4a › esds)의 ES_Descriptor 에 ES_ID = 트랙 ID 를 쓴다 — segment_4s 의 소리 stsd 70번째 바이트가 트랙 ID
// 2 다. 그래서 트랙 ID 만 다른 재접속도 지문이 달라져 비호환(계승 해제 — fail-closed)이 된다. ADR-044 2026-08-31
// 갱신 3항의 「트랙 ID 가 달라져도 stsd 는 같게 나온다」와 다르다. ES_ID 를 지문에서 뺄지는 설계 결정이라 여기서
// 바꾸지 않는다 — 바꾸면 이 시험이 뒤집힌다.
func TestStsdFingerprintFollowsAudioTrackID(t *testing.T) {
	raw := readFixture(t, fixture4s)
	want, err := StsdFingerprint(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("원본 지문 실패: %v", err)
	}
	shifted := withHeader(t, raw, func(ini *fmp4.Init) {
		for _, tr := range ini.Tracks {
			tr.ID += 10
		}
	})

	got, err := StsdFingerprint(bytes.NewReader(shifted))

	if err != nil || got == want {
		t.Errorf("트랙 ID 만 바꾼 머리말의 StsdFingerprint = %x, %v; want 원본(%x)과 다른 값 — ES_ID 가 트랙 ID 를 따른다", got, err, want)
	}
}

// TestStsdFingerprintRejectsUnreadableHeaders 는 stsd 를 읽을 수 없는 머리말에서 실패하는지 본다 — 빈 지문이나
// 잘린 바이트의 지문을 값으로 내면 stsd 를 못 읽은 두 조각이 서로 「호환」이 된다. 호출자는 판독 실패를 비호환으로
// 다룬다(fail-closed). 크기 칸이 입력 상한을 넘거나 파일 끝까지인 상자는 읽기 전에 거부한다 — 손상된 크기 칸이
// 거대한 할당을 부르지 않게 한다. 상자 구조를 다 걸은 뒤의 이동 · 읽기 오류(디스크 오류)도 지문이 아니라 오류다.
func TestStsdFingerprintRejectsUnreadableHeaders(t *testing.T) {
	// withStsd 는 트랙 하나짜리 머리말 moov › trak › mdia › minf › stbl 안에 stsd 자리 바이트를 그대로 넣는다 —
	// 앞 다섯 상자 머리(8바이트씩) 뒤라 stsd 는 오프셋 40 에서 시작한다.
	withStsd := func(stsd []byte) []byte {
		return box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd)))))
	}
	const stsdOffset = 40
	// stsdHeader 는 크기 칸이 size 인 stsd 상자 머리 8바이트다(내용은 붙이지 않는다).
	stsdHeader := func(size uint32) []byte {
		return append(binary.BigEndian.AppendUint32(nil, size), "stsd"...)
	}
	whole := withStsd(box("stsd", make([]byte, 56)))
	tests := []struct {
		name  string
		input io.ReadSeeker
	}{
		{"stsd_없음", bytes.NewReader(box("moov", box("udta", box("free", []byte("x")))))},
		{"stsd_가_부모_상자를_넘음", bytes.NewReader(withStsd(append(stsdHeader(64), make([]byte, 8)...)))},
		{"stsd_가_입력_상한보다_큼", bytes.NewReader(withStsd(box("stsd", make([]byte, maxInputBytes))))},
		{"파일_끝까지인_stsd", bytes.NewReader(withStsd(append(stsdHeader(0), make([]byte, 8)...)))},
		{"stsd_내용_읽기_오류", &faultyReader{Reader: bytes.NewReader(whole), failAt: stsdOffset + 8, failRead: true}},
		{"stsd_위치_이동_오류", &faultyReader{Reader: bytes.NewReader(whole), failAt: stsdOffset, failSeek: true}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if fp, err := StsdFingerprint(tt.input); err == nil {
				t.Errorf("StsdFingerprint = %x, nil — stsd 를 읽을 수 없는데 오류가 없다", fp)
			}
		})
	}
}

// faultyReader 는 머리말 걸음이 끝난 뒤(입력 끝까지 한 번 읽은 뒤)의 디스크 오류를 흉내 내는 리더다 — 그 뒤로
// failRead 면 failAt 을 넘는 읽기가, failSeek 면 failAt 으로의 SeekStart 이동이 실패한다.
type faultyReader struct {
	*bytes.Reader
	failAt             int64
	failRead, failSeek bool
	walked             bool
}

func (f *faultyReader) Read(p []byte) (int, error) {
	pos, _ := f.Reader.Seek(0, io.SeekCurrent) // 메모리 리더라 현재 위치 확인은 실패하지 않는다
	if f.walked && f.failRead && pos+int64(len(p)) > f.failAt {
		return 0, errors.New("읽기 오류(시험)")
	}
	n, err := f.Reader.Read(p)
	if errors.Is(err, io.EOF) {
		f.walked = true
	}
	return n, err
}

func (f *faultyReader) Seek(offset int64, whence int) (int64, error) {
	if f.walked && f.failSeek && whence == io.SeekStart && offset == f.failAt {
		return 0, errors.New("이동 오류(시험)")
	}
	return f.Reader.Seek(offset, whence)
}
