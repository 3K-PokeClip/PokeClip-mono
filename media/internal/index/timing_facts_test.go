package index

import (
	"fmt"
	"testing"
	"time"
)

// recorderX · recorderY 는 서로 다른 녹화기 인스턴스 표식이다.
var (
	recorderX = [16]byte{0x58, 1}
	recorderY = [16]byte{0x59, 2}
)

// marked 는 표식 · 번호가 있는 원래 사실 칸이다. 영상 두 칸은 비운다.
func marked(recorder [16]byte, number int64) TimingFacts {
	return TimingFacts{Recorder: &recorder, Number: &number}
}

// withVideo 는 f 에 영상 첫 DTS · 끝 칸을 채운 사본이다.
func withVideo(f TimingFacts, first, end time.Duration) TimingFacts {
	f.VideoFirst, f.VideoEnd = &first, &end
	return f
}

// SameInstance 는 같은 녹화기 이어짐의 단일 정의다(설계 2.1). ③ 사슬(c8) ·
// is_discontinuity(c6) · 판 2 섞기(c4b)가 이 함수를 부르므로, 이 표가 그 셋의
// 공통 기준이다. 「nil 표식끼리」 행은 변이 m21(nil 끼리 같다고 봄)을 잡는다.
func TestSameInstance(t *testing.T) {
	var unmarked TimingFacts
	eleven := int64(11)
	numberOnly := TimingFacts{Number: &eleven}
	tests := []struct {
		name      string
		prev, cur TimingFacts
		want      bool
	}{
		{"앞만_표식_없음", unmarked, marked(recorderX, 11), false},
		{"뒤만_표식_없음", marked(recorderX, 10), unmarked, false},
		{"nil_표식끼리", unmarked, unmarked, false},
		{"같은_표식_번호_더하기_1", marked(recorderX, 10), marked(recorderX, 11), true},
		{"같은_표식_같은_번호", marked(recorderX, 10), marked(recorderX, 10), false},
		{"같은_표식_번호_건너뜀", marked(recorderX, 10), marked(recorderX, 12), false},
		{"같은_표식_번호_역행", marked(recorderX, 10), marked(recorderX, 9), false},
		{"다른_표식_번호_더하기_1", marked(recorderX, 10), marked(recorderY, 11), false},
		{"표식_없이_번호만_있음", numberOnly, marked(recorderX, 12), false},
		{"번호_0_에서_1", marked(recorderX, 0), marked(recorderX, 1), true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := SameInstance(tt.prev, tt.cur); got != tt.want {
				t.Errorf("SameInstance(%s, %s) = %v, want %v", describe(tt.prev), describe(tt.cur), got, tt.want)
			}
		})
	}
}

// Contiguous 는 같은 녹화기 이어짐에서 영상 첫 DTS a(k) 와 앞 영상 끝 b(p) 의
// 차가 1ms 미만인가다(설계 2.1). 경계 1ms 의 앞뒤와 두 방향을 본다.
func TestContiguous(t *testing.T) {
	const end = 40 * time.Second
	prev := withVideo(marked(recorderX, 10), 36*time.Second, end)
	next := func(first time.Duration) TimingFacts {
		return withVideo(marked(recorderX, 11), first, first+4*time.Second)
	}
	tests := []struct {
		name      string
		prev, cur TimingFacts
		want      bool
	}{
		{"정확히_맞닿음", prev, next(end), true},
		{"뒤로_1ms_미만", prev, next(end + time.Millisecond - time.Nanosecond), true},
		{"뒤로_1ms", prev, next(end + time.Millisecond), false},
		{"앞으로_1ms_미만", prev, next(end - time.Millisecond + time.Nanosecond), true},
		{"앞으로_1ms", prev, next(end - time.Millisecond), false},
		{"다른_표식은_맞닿아도_거짓", prev, withVideo(marked(recorderY, 11), end, end+4*time.Second), false},
		{"번호_건너뜀은_맞닿아도_거짓", prev, withVideo(marked(recorderX, 12), end, end+4*time.Second), false},
		{"nil_표식끼리는_맞닿아도_거짓", withVideo(TimingFacts{}, 0, end), withVideo(TimingFacts{}, end, end+4*time.Second), false},
		{"앞_영상_칸_없음", marked(recorderX, 10), next(end), false},
		{"뒤_영상_칸_없음", prev, marked(recorderX, 11), false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := Contiguous(tt.prev, tt.cur); got != tt.want {
				t.Errorf("Contiguous(%s, %s) = %v, want %v", describe(tt.prev), describe(tt.cur), got, tt.want)
			}
		})
	}
}

// describe 는 실패 메시지에 칸 값을 보이게 한다(포인터 주소 대신 값).
func describe(f TimingFacts) string {
	recorder, number, video := "nil", "nil", "nil"
	if f.Recorder != nil {
		recorder = fmt.Sprintf("%x", f.Recorder[:2])
	}
	if f.Number != nil {
		number = fmt.Sprint(*f.Number)
	}
	if f.VideoFirst != nil && f.VideoEnd != nil {
		video = fmt.Sprintf("%v..%v", *f.VideoFirst, *f.VideoEnd)
	}
	return fmt.Sprintf("{recorder=%s number=%s video=%s}", recorder, number, video)
}
