package playback_test

import (
	"os"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fmp4meta"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// TestMtxiRegistrationKeepsExistingBoxParsing 는 mtxi 상자 정의의 전역 등록(abema/go-mp4 의
// 프로세스 전역 상자 표)이 같은 프로세스의 기존 판독을 깨지 않는지 본다(계획 2.1 mp4box 행).
//
// 등록은 프로세스 전역이다 — 인덱서의 길이 프로브(fmp4meta)도 같은 사이드카 프로세스에서 같은
// 표를 쓴다. 표에 잘못 올라간 정의(예: 기존 상자 이름으로 등록)는 그쪽 판독을 조용히 바꾼다.
// 외부 테스트 패키지로 두는 이유: playback 은 내부 패키지를 임포트하지 않는다(key.go 패키지 주석) —
// 두 패키지를 한 바이너리에 올리는 일은 이 테스트 패키지가 한다.
func TestMtxiRegistrationKeepsExistingBoxParsing(t *testing.T) {
	tests := []struct {
		file   string
		wantMS int64 // 녹화기가 mvhd 에 적은 길이(0x0fac · 0x07fa)
	}{
		{"testdata/segment_4s.mp4", 4012},
		{"testdata/segment_tail_2s.mp4", 2042},
	}
	for _, tt := range tests {
		t.Run(tt.file, func(t *testing.T) {
			f, err := os.Open(tt.file)
			if err != nil {
				t.Fatalf("실물 조각 열기 실패: %v", err)
			}
			defer f.Close()
			if _, err := playback.ReadMtxi(f); err != nil {
				t.Fatalf("등록이 이 바이너리에서 살아 있지 않다 — ReadMtxi 실패: %v", err)
			}

			got, err := fmp4meta.ProbeDurationMS(tt.file)

			if err != nil || got != tt.wantMS {
				t.Errorf("fmp4meta.ProbeDurationMS(%q) = %d, %v; want %d, nil", tt.file, got, err, tt.wantMS)
			}
		})
	}
}

// TestReadersRunInsideFsopReadT 는 두 판독 함수가 fsop.ReadT 에 그대로 끼워지는지 본다 — 인덱서의
// mtxiT·trackEndsT 가 이 모양으로 부른다(계획 4.2-R 전진 규칙 ③: 열기는 fsop 워커 안).
// 기대값은 segment_4s.mp4 의 mtxi 누적 DTS 와 두 트랙 끝(mp4box_test.go 의 실물 값과 같다).
func TestReadersRunInsideFsopReadT(t *testing.T) {
	const file = "testdata/segment_4s.mp4"

	m, err := fsop.ReadT(file, time.Second, playback.ReadMtxi)
	if err != nil || m.DTS != 5_987_981_859 {
		t.Errorf("fsop.ReadT(ReadMtxi) = DTS %v, %v; want 5.987981859s, nil", m.DTS, err)
	}

	ends, err := fsop.ReadT(file, time.Second, playback.TrackEnds)
	if err != nil || ends.Video != 4_012_411_111 || ends.Audio != 3_994_013_605 {
		t.Errorf("fsop.ReadT(TrackEnds) = %+v, %v; want {4.012411111s 3.994013605s}, nil", ends, err)
	}
}
