// Package fmp4meta 는 fMP4 파일 속을 들여다보는 일 하나만 한다.
// 파일에 적힌 실제 길이(ms)를 읽어 오며, 라이브러리를 갈아 끼워야 할 때 손댈 지점을
// ProbeDurationMS 본문 하나로 좁혔다(D2).
package fmp4meta

import (
	"fmt"
	"math"
	"os"

	mp4 "github.com/abema/go-mp4"
)

// DurationProbe 는 "경로를 주면 길이(ms)를 돌려주는 함수"라는 모양의 이름표다.
// 테스트에서 진짜 파일 대신 가짜 함수를 끼워 넣을 수 있다.
type DurationProbe func(path string) (durationMS int64, err error)

// ProbeDurationMS 는 fMP4 파일의 박스를 읽어 길이를 실측한다. wall 추정은 하지 않는다(G2).
//
// 구현 근거 — U4 해소 실측(abema/go-mp4 v1.7.1):
//
//	mp4.Probe(io.ReadSeeker) (*ProbeInfo, error) 의 ProbeInfo.Duration/Timescale 을 쓴다.
//	이 값은 moov/mvhd 에서 오며 MediaMTX 1.19.3·1.20.1 기준 "트랙 중 최대 길이"와 일치한다(1.20.1 실산출물 mvhd 4.117s = max track 재확인)
//	(ffprobe 독립 실측 대조: segment_4s 4012ms = 비디오 트랙, tail_2s 2042ms = 오디오 트랙).
//	ProbeInfo.Segments 의 Duration 합은 실제 길이의 절반만 나와 쓸 수 없었다.
//
// 아직 닫히지 않은(녹화 중인) 파일은 mvhd 의 duration 이 0 이며, 이 경우 에러를 돌려준다.
// 이는 실측 확인된 동작이다 — 쓰는 중에 복사한 파일은 duration=0, 세그먼트가 닫힌 뒤에는
// 실제 값이 기록된다. "0을 길이로 채택"하는 것보다 실패를 명시하는 편이 안전하다.
func ProbeDurationMS(path string) (int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return 0, fmt.Errorf("세그먼트 파일 열기 실패 %q: %w", path, err)
	}
	defer f.Close()

	info, err := mp4.Probe(f)
	if err != nil {
		return 0, fmt.Errorf("fMP4 박스 해석 실패 %q: %w", path, err)
	}
	if info.Timescale == 0 {
		return 0, fmt.Errorf("timescale 이 0 이다 %q", path)
	}
	if info.Duration == 0 {
		return 0, fmt.Errorf("박스에 길이가 아직 기록되지 않았다(파일이 닫히지 않았을 수 있다) %q", path)
	}
	// duration*1000 이 uint64 를 넘지 않도록 축소 전에 막는다.
	if info.Duration > math.MaxInt64/1000 {
		return 0, fmt.Errorf("duration 이 계산 범위를 넘는다 %q: %d", path, info.Duration)
	}

	return int64(info.Duration) * 1000 / int64(info.Timescale), nil
}
