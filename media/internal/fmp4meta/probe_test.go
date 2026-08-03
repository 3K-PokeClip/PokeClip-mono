package fmp4meta

import (
	"path/filepath"
	"testing"
)

// testdata 3종의 출처와 독립 실측값(오라클).
//
// 채취: MediaMTX 1.19.3 이 recordPath 로 직접 떨어뜨린 원본을 재인코딩 없이 그대로 커밋했다.
// 재인코딩하면 ffmpeg 이 먹싱한 파일이 되어 검증 대상(MediaMTX 의 박스 배치)과 달라진다.
// 송출은 320x240 저비트레이트라 두 파일 모두 500KB 미만이다.
//
// 오라클은 ffprobe(호스트, 이 코드와 무관한 독립 구현) 실측값이다.
//
//	segment_4s.mp4       video 4.012411s / audio 3.994014s  → 트랙 최대 약 4012ms
//	segment_tail_2s.mp4  video 1.973844s / audio 2.042993s  → 트랙 최대 약 2043ms
//
// 정상 파일을 2종 두는 이유: 1개면 ProbeDurationMS 가 4000 을 상수로 반환해도 테스트가
// 통과한다. 길이가 확연히 다른 두 파일이 각자의 값을 내야 실제로 박스를 읽고 있다는 증거가 된다.
const (
	want4sMS    = 4000 // 설계 AC2 기준값. 허용 오차 +-100ms
	wantTailMS  = 2043 // 채취 시 ffprobe 실측값. 허용 오차 +-100ms
	toleranceMS = 100
)

func TestProbeDurationMS(t *testing.T) {
	tests := []struct {
		name   string
		file   string
		wantMS int64
	}{
		{name: "3종1_정상_4초_세그먼트", file: "segment_4s.mp4", wantMS: want4sMS},
		{name: "3종2_길이가_다른_정상_세그먼트_마지막_조각", file: "segment_tail_2s.mp4", wantMS: wantTailMS},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := ProbeDurationMS(filepath.Join("testdata", tt.file))
			if err != nil {
				t.Fatalf("예상 밖 에러: %v", err)
			}
			if diff := got - tt.wantMS; diff < -toleranceMS || diff > toleranceMS {
				t.Fatalf("ProbeDurationMS = %dms, want %dms +-%dms", got, tt.wantMS, toleranceMS)
			}
		})
	}
}

// 3종3 — malformed 파일은 패닉이 아니라 에러를 반환해야 한다.
// 잘린 파일을 조용히 0 이나 부분값으로 통과시키면 H7(폭 검증)까지 잘못된 값이 흘러간다.
func TestProbeDurationMSMalformedReturnsError(t *testing.T) {
	got, err := ProbeDurationMS(filepath.Join("testdata", "segment_malformed.mp4"))
	if err == nil {
		t.Fatalf("에러를 기대했으나 nil (got=%dms)", got)
	}
}

func TestProbeDurationMSMissingFileReturnsError(t *testing.T) {
	if _, err := ProbeDurationMS(filepath.Join("testdata", "존재하지_않는_파일.mp4")); err == nil {
		t.Fatal("에러를 기대했으나 nil")
	}
}

// 두 정상 파일이 서로 다른 값을 내야 한다 — 상수 반환 구현을 걸러 내는 장치다.
func TestProbeDurationMSDistinguishesLengths(t *testing.T) {
	full, err := ProbeDurationMS(filepath.Join("testdata", "segment_4s.mp4"))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	tail, err := ProbeDurationMS(filepath.Join("testdata", "segment_tail_2s.mp4"))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if full-tail < 1000 {
		t.Fatalf("두 파일 길이 차이가 %dms 뿐이다 — 박스를 실제로 읽지 않는 구현일 수 있다", full-tail)
	}
}

// ProbeDurationMS 가 DurationProbe 타입을 만족하는지 컴파일 시점에 고정한다.
// D2 의 교체 지점은 이 함수 본문 하나이며, 타입은 손대지 않는다.
var _ DurationProbe = ProbeDurationMS
