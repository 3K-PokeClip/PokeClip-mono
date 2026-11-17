package index

import (
	"errors"
	"fmt"
	"os"
	"regexp"
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
// TestDockerfileTimingLabelEqualsReadableVersions 가 한다.
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

// 판 목록 글자(이미지 라벨 com.pokeclip.timing.readable · 기동 전 검사의 SIDECAR_TIMING_READABLE)는 쉼표로 이은 판이고
// null 은 판 NULL 이다. 빈 글자 · 빈 칸 · 모르는 글자 · 범위 밖 판은 거부한다 — 잘못 읽은 목록으로 검사를 통과시키지
// 않는다(fail-closed).
func TestParseTimingVersions(t *testing.T) {
	two := int16(2)
	ok := []struct {
		in   string
		want []*int16
	}{
		{"null,2", []*int16{nil, &two}},
		{"null", []*int16{nil}},
		{"2", []*int16{&two}},
	}
	for _, c := range ok {
		got, err := ParseTimingVersions(c.in)
		if err != nil || !sameVersions(got, c.want) {
			t.Errorf("ParseTimingVersions(%q) = (%v, %v), want %v", c.in, versionsText(got), err, versionsText(c.want))
		}
	}
	for _, in := range []string{"", ",", "null,", " null", "NULL", "two", "0", "-2", "32768", "2,null x"} {
		if got, err := ParseTimingVersions(in); err == nil {
			t.Errorf("ParseTimingVersions(%q) = %v, want 오류", in, versionsText(got))
		}
	}
}

// 이미지 라벨 com.pokeclip.timing.readable(media/Dockerfile)은 이 이미지의 판 목록 readableVersions 와 같다(설계 r7 9.4
// 기동 전 검사 1 · 10.4 단위). 라벨이 판 2 를 빼먹으면 c0 이후 이미지가 래치 2 에서 기동 전 검사에 막히고, 판 2 를 지운
// 앞날 이미지에 라벨만 남으면 기동 전 검사가 DB 를 보지 않고 통과시킨다.
func TestDockerfileTimingLabelEqualsReadableVersions(t *testing.T) {
	src, err := os.ReadFile("../../Dockerfile")
	if err != nil {
		t.Fatalf("media/Dockerfile 을 읽지 못했다: %v", err)
	}
	m := regexp.MustCompile(`(?m)^LABEL com\.pokeclip\.timing\.readable="([^"]*)"$`).FindAllStringSubmatch(string(src), -1)
	if len(m) != 1 {
		t.Fatalf("LABEL com.pokeclip.timing.readable 줄 = %d개, want 1", len(m))
	}
	label, err := ParseTimingVersions(m[0][1])
	if err != nil {
		t.Fatalf("라벨 %q 를 읽지 못했다: %v", m[0][1], err)
	}
	if !sameVersions(label, readableVersions) {
		t.Errorf("라벨 = %v, readableVersions = %v — 같아야 한다", versionsText(label), versionsText(readableVersions))
	}
}

// 기동 검사가 거부할 때의 오류는 ErrTimingDowngrade 이고, 게이트 값과 이미지 판 목록을 싣는다(신호 timing_downgrade_refused
// 의 재료). 띄워도 되면 nil 이다.
func TestRefuseUnreadableNamesGateAndReadable(t *testing.T) {
	two := int16(2)
	legacy := []*int16{nil}
	if err := RefuseUnreadable(TimingGate{}, legacy); err != nil {
		t.Errorf("게이트 NULL · 판 목록 NULL = %v, want nil", err)
	}
	err := RefuseUnreadable(TimingGate{Highest: &two}, legacy)
	var refused *TimingDowngradeError
	if !errors.Is(err, ErrTimingDowngrade) || !errors.As(err, &refused) {
		t.Fatalf("래치 2 · 판 목록 NULL = %v, want *TimingDowngradeError(ErrTimingDowngrade)", err)
	}
	if refused.Gate.Highest == nil || *refused.Gate.Highest != 2 || refused.Readable != "null" {
		t.Errorf("거부 값 = (래치 %s, 판 목록 %q), want (2, \"null\")", verText(refused.Gate.Highest), refused.Readable)
	}
}

// sameVersions 는 두 판 목록이 같은 판 집합인가다(nil = 판 NULL).
func sameVersions(a, b []*int16) bool {
	if len(a) != len(b) {
		return false
	}
	for _, v := range a {
		if !hasVersion(b, v) {
			return false
		}
	}
	return true
}

// versionsText 는 판 목록을 읽기 좋은 글자로 편다.
func versionsText(list []*int16) []string {
	out := make([]string, len(list))
	for i, v := range list {
		out[i] = verText(v)
	}
	return out
}
