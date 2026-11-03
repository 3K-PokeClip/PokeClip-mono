package index

import (
	"errors"
	"fmt"
	"testing"
)

// verText 는 판 포인터를 읽기 좋은 글자로 편다(nil = 판 NULL).
func verText(v *int16) string {
	if v == nil {
		return "NULL"
	}
	return fmt.Sprint(*v)
}

// AdmitsImage 는 래치와 스위치가 둘 다 이미지의 판 목록 안일 때만 참이다(설계 3절 · 9.3 기동 검사).
// 판 목록에 NULL 이 없으면 게이트가 NULL 이어도 띄우지 않는다 — 판 NULL 회차를 이어 쓸 길이 없는 이미지다.
func TestAdmitsImageRequiresLatchAndSwitchInReadable(t *testing.T) {
	two, three := int16(2), int16(3)
	both := []*int16{nil, &two}
	onlyTwo := []*int16{&two}
	legacy := []*int16{nil}
	cases := []struct {
		name     string
		gate     TimingGate
		readable []*int16
		want     bool
	}{
		{"게이트 NULL · 판 목록 NULL·2", TimingGate{}, both, true},
		{"스위치 2 · 래치 2 · 판 목록 NULL·2", TimingGate{NewRoundVersion: &two, Highest: &two}, both, true},
		{"스위치 NULL · 래치 2 · 판 목록 NULL·2", TimingGate{Highest: &two}, both, true},
		{"래치 2 · 판 목록 NULL 만(출시 이미지)", TimingGate{Highest: &two}, legacy, false},
		{"스위치 2 · 래치 NULL · 판 목록 NULL 만", TimingGate{NewRoundVersion: &two}, legacy, false},
		{"게이트 NULL · 판 목록 NULL 만", TimingGate{}, legacy, true},
		{"게이트 NULL · 판 목록 2 만", TimingGate{}, onlyTwo, false},
		{"래치 3 · 판 목록 NULL·2", TimingGate{Highest: &three}, both, false},
		{"스위치 3 · 래치 3 · 판 목록 NULL·2", TimingGate{NewRoundVersion: &three, Highest: &three}, both, false},
		{"빈 판 목록", TimingGate{}, nil, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := AdmitsImage(c.gate, c.readable); got != c.want {
				t.Errorf("AdmitsImage(스위치 %s · 래치 %s) = %t, want %t",
					verText(c.gate.NewRoundVersion), verText(c.gate.Highest), got, c.want)
			}
		})
	}
}

// 이 이미지의 판 목록은 NULL 과 2 둘이다 — 이미지 라벨 com.pokeclip.timing.readable 이 이 목록과 같다는 대조는
// 라벨을 두는 c11 이 더한다.
func TestReadableVersionsAreNullAndTwo(t *testing.T) {
	if len(readableVersions) != 2 || readableVersions[0] != nil || readableVersions[1] == nil || *readableVersions[1] != 2 {
		got := make([]string, len(readableVersions))
		for i, v := range readableVersions {
			got[i] = verText(v)
		}
		t.Fatalf("readableVersions = %v, want [NULL 2]", got)
	}
}

// 쓰기 시점 판 검사 — 이 이미지는 readableVersions(판 NULL · 2)를 쓴다. 그 밖의 판 요구는 ErrTimingVersionUnsupported 다.
func TestCheckWritableVersionAdmitsReadableVersionsOnly(t *testing.T) {
	two, three := int16(2), int16(3)
	for _, v := range []*int16{nil, &two} {
		if err := checkWritableVersion(v); err != nil {
			t.Errorf("checkWritableVersion(%v) = %v, want nil", v, err)
		}
	}
	if err := checkWritableVersion(&three); !errors.Is(err, ErrTimingVersionUnsupported) {
		t.Errorf("checkWritableVersion(3) = %v, want ErrTimingVersionUnsupported", err)
	}
}
