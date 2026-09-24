package playback

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/hex"
	"os"
	"testing"
	"time"

	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4"
	"github.com/bluenviron/mediacommon/v2/pkg/formats/fmp4/seekablebuffer"
)

// 이 파일은 여러 테스트가 함께 쓰는 픽스처와 조립 도구다. 한 테스트만 쓰는 도구는 그 테스트 옆에 둔다.

// testdata 실물 3종 — 전부 MediaMTX 녹화기가 recordPath 로 떨어뜨린 원본 그대로다
// (재인코딩하면 검증 대상인 녹화기의 상자 배치가 사라진다). 출처는 파일마다 다르다:
//
//	segment_4s.mp4       320x240 영상 1 + 소리 1, 파트 4개 — 상류 MediaMTX 1.19.3 녹화(2026-08-01, fmp4meta/testdata 와 같은 blob)
//	segment_tail_2s.mp4  320x240 영상 1 + 소리 1, 꼬리 조각 — 상류 1.19.3, 위와 다른 녹화(avcC 가 다르다)
//	segment_1v6a.mp4     1080p 영상 1 + 소리 6, 파트 1개 — 포크 v1.20.1-pokeclip.2 녹화(2026-09-23, 71_ ④) — 운영 형상(1v+6a) 실물
//
// 포크 태그를 올릴 때는 새 포크로 녹화한 조각으로 segment_1v6a 를 교체하고 실물 판독 테스트를
// 다시 돌린다(계획 7절 9) — 녹화기 쪽 mtxi 형식 변경은 그것으로만 잡힌다.
const (
	fixture4s   = "testdata/segment_4s.mp4"
	fixtureTail = "testdata/segment_tail_2s.mp4"
	fixture1v6a = "testdata/segment_1v6a.mp4"
)

// readFixture 는 testdata 실물 조각을 통째로 읽는다.
func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(name)
	if err != nil {
		t.Fatalf("실물 조각 읽기 실패: %v", err)
	}
	return b
}

// box 는 ISO BMFF 상자 하나를 손으로 짠다(32비트 크기 머리 + 형식 + 내용).
// 상자 배치를 검사하는 테스트가 검사 대상 라이브러리로 입력을 만들면 같은 실수를 두 번
// 하게 되므로, 기대값 쪽 바이트는 이 함수로만 만든다.
func box(typ string, payload ...[]byte) []byte {
	body := bytes.Join(payload, nil)
	out := binary.BigEndian.AppendUint32(nil, uint32(8+len(body)))
	return append(append(out, typ...), body...)
}

func be64(v uint64) []byte { return binary.BigEndian.AppendUint64(nil, v) }

func mustHex(t *testing.T, s string) [16]byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != 16 {
		t.Fatalf("표식 16진 %q 해석 실패: %v", s, err)
	}
	return [16]byte(b)
}

// splitRecording 은 실물 조각을 머리말(첫 moof 앞)과 본문으로 가른다 — 픽스처 조립용.
// 검사 대상의 splitAtFirstMoof 를 쓰지 않고 따로 짠다(기대값을 대상 코드로 만들지 않는다).
func splitRecording(t *testing.T, raw []byte) (head, body []byte) {
	t.Helper()
	for off := 0; off+8 <= len(raw); {
		if string(raw[off+4:off+8]) == "moof" {
			return raw[:off], raw[off:]
		}
		size := int(binary.BigEndian.Uint32(raw[off:]))
		if size < 8 {
			break
		}
		off += size
	}
	t.Fatal("픽스처에 moof 가 없다")
	return nil, nil
}

// withHeader 는 실물 조각의 머리말을 edit 로 고쳐 다시 쓴 조각을 만든다(본문은 그대로).
//
// 다시 쓴 머리말은 mediacommon 이 쓰므로 mvhd 도 원본(녹화기가 길이를 적은 값)과 달라진다.
func withHeader(t *testing.T, raw []byte, edit func(*fmp4.Init)) []byte {
	t.Helper()
	head, body := splitRecording(t, raw)
	var ini fmp4.Init
	if err := ini.Unmarshal(bytes.NewReader(head)); err != nil {
		t.Fatalf("픽스처 머리말 해석 실패: %v", err)
	}
	edit(&ini)
	var buf seekablebuffer.Buffer
	if err := ini.Marshal(&buf); err != nil {
		t.Fatalf("픽스처 머리말 쓰기 실패: %v", err)
	}
	return bytes.Join([][]byte{buf.Bytes(), body}, nil)
}

// parsedParts 는 실물 조각(또는 산출 조각)의 본문 파트를 읽는다.
func parsedParts(t *testing.T, body []byte) fmp4.Parts {
	t.Helper()
	var parts fmp4.Parts
	if err := parts.Unmarshal(body); err != nil {
		t.Fatalf("파트 해석 실패: %v", err)
	}
	return parts
}

// parseOutput 은 산출물을 다시 읽는다 — 산출이 해석 가능한 fMP4 인지도 함께 확인된다.
func parseOutput(t *testing.T, out Output) (fmp4.Init, fmp4.Parts) {
	t.Helper()
	var ini fmp4.Init
	if err := ini.Unmarshal(bytes.NewReader(out.Init)); err != nil {
		t.Fatalf("산출 init 해석 실패: %v", err)
	}
	return ini, parsedParts(t, out.Seg)
}

// payloadsOf 는 파트들에서 한 트랙의 샘플 내용을 순서대로 모은다.
func payloadsOf(parts fmp4.Parts, trackID int) [][]byte {
	var out [][]byte
	for _, p := range parts {
		for _, tr := range p.Tracks {
			if tr.ID != trackID {
				continue
			}
			for _, smp := range tr.Samples {
				out = append(out, smp.Payload)
			}
		}
	}
	return out
}

// produce 는 실물(또는 조립한) 조각을 pos 로 재포장한다 — 실패하면 그 자리에서 테스트를 멈춘다.
func produce(t *testing.T, raw []byte, pos time.Duration) Output {
	t.Helper()
	out, err := Remuxer{}.Produce(context.Background(), Request{Input: bytes.NewReader(raw), PlaybackPos: pos})
	if err != nil {
		t.Fatalf("Produce 실패: %v", err)
	}
	return out
}
