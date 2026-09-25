package rewind_test

// 목록 닫기(설계 4.6.4 E3 — SealEndlist)의 단위 검증 — POK-195 M4 PR ⓑ 커밋 ③.
//
// 봉인은 Render·Validate 를 타지 않는 유일한 특권 경로다. 그래서 발행된 본문 바이트를 한 글자도
// 바꾸지 않고 끝에 EXT-X-ENDLIST 한 줄만 더하는지, 이미 닫힌 본문과 목록이 아닌 입력을 거르는지를
// 잰다. 기대값은 골든(testdata/g6_f2.m3u8)에 손으로 적은 한 줄을 붙인 것이다.

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// readGolden 은 설계 4.8.2 실물 골든(g6_f2 900조각 전문)이다.
func readGolden(t *testing.T) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "g6_f2.m3u8"))
	if err != nil {
		t.Fatalf("골든 읽기 실패: %v", err)
	}
	return b
}

// 발행된 목록을 닫는다 — 본문 끝에 "#EXT-X-ENDLIST\n" 한 줄만 붙는다. 발행된 줄은 바꿀 수 없고 허용된
// 변경 가운데 하나가 ENDLIST 추가다(RFC 8216bis-22 6.2.1).
func TestSealEndlistAppendsOneEndlistLine(t *testing.T) {
	golden := readGolden(t)

	got, err := rewind.SealEndlist(golden)

	if err != nil {
		t.Fatalf("SealEndlist(골든) 오류: %v", err)
	}
	want := string(golden) + "#EXT-X-ENDLIST\n"
	if string(got) != want {
		t.Errorf("SealEndlist(골든) — %s", firstDiff(string(got), want))
	}
}

// 이미 닫힌 본문은 ErrAlreadySealed 다 — 호출자(설계 4.6.4 E2)가 「이미 닫힘 = 멱등 성공」으로 읽는다.
// 두 번째 ENDLIST 를 붙이면 RFC 8216bis-22 4.4.3(Media Playlist 태그는 종류마다 하나)을 어긴다.
// ENDLIST 는 본문 어디에나 올 수 있으므로(4.4.3.4) 끝 줄이 아니어도 닫힌 본문이다.
func TestSealEndlistRefusesSealedBody(t *testing.T) {
	golden := readGolden(t)
	sealedAtEnd := string(golden) + "#EXT-X-ENDLIST\n"
	sealedInMiddle := strings.Replace(string(golden), "#EXTM3U\n", "#EXTM3U\n#EXT-X-ENDLIST\n", 1)
	for name, body := range map[string]string{"끝에_ENDLIST": sealedAtEnd, "중간에_ENDLIST": sealedInMiddle} {
		t.Run(name, func(t *testing.T) {
			got, err := rewind.SealEndlist([]byte(body))

			if !errors.Is(err, rewind.ErrAlreadySealed) || got != nil {
				t.Errorf("SealEndlist(%s) = %d바이트, %v; want nil, ErrAlreadySealed", name, len(got), err)
			}
		})
	}
}

// 목록 본문이 아니면 닫지 않는다 — 첫 줄이 #EXTM3U 가 아니거나(빈 본문 포함) 줄바꿈으로 끝나지 않는
// 본문. 줄바꿈이 없으면 ENDLIST 가 마지막 URI 줄에 붙어 그 URI 가 깨진다. 이미 닫힌 것과 다른 오류다.
func TestSealEndlistRejectsNonPlaylistBody(t *testing.T) {
	golden := readGolden(t)
	tests := []struct {
		name string
		body []byte
	}{
		{"빈_본문", nil},
		{"목록이_아님", []byte("<html>not found</html>\n")},
		{"끝_줄바꿈_없음", bytes.TrimSuffix(golden, []byte("\n"))},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := rewind.SealEndlist(tt.body)

			if err == nil || errors.Is(err, rewind.ErrAlreadySealed) || got != nil {
				t.Errorf("SealEndlist(%s) = %d바이트, %v; want nil, 목록 아님 오류", tt.name, len(got), err)
			}
		})
	}
}

// 입력 본문은 건드리지 않는다 — 캐시가 쥔 마지막 발행 본문(설계 4.6.4 E1)을 그대로 넘겨도 된다.
// 입력 슬라이스에 여유 용량이 있어도 결과는 새 바이트다(결과를 고쳐도 입력이 안 바뀐다).
func TestSealEndlistLeavesInputUntouched(t *testing.T) {
	golden := readGolden(t)
	in := make([]byte, len(golden), len(golden)+64)
	copy(in, golden)

	got, err := rewind.SealEndlist(in)
	if err != nil {
		t.Fatalf("SealEndlist 오류: %v", err)
	}
	got[0] = 'X'

	if !bytes.Equal(in, golden) || in[:cap(in)][len(in)] != 0 {
		t.Errorf("SealEndlist 가 입력 본문을 바꿨다 — 결과가 입력과 바이트를 나눠 쓴다")
	}
}
