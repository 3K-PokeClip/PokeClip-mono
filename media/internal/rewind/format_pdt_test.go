package rewind_test

// FormatPDT(EXT-X-PROGRAM-DATE-TIME 서식의 공개 함수 — 설계 M5 2.1 · 7절 render 행)의 단위 검증이다 —
// POK-200 ②r. 제어면이 상태 응답의 firstPdt · headPdt 를 이 함수로 쓰므로, 목록 본문의 PDT 줄과 한
// 글자도 갈리면 안 된다. 그래서 서식 자체와 「목록 본문의 PDT 줄 = FormatPDT 출력」을 따로 못 박는다.
// 목록 바이트 불변은 기존 골든(TestG6F2RenderMatchesGolden)이 맡는다.

import (
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// 서식은 UTC · 밀리초 세 자리 · 끝 Z 다. 밀리초 아래는 버린다(반올림하지 않는다). 다른 시간대의 시각도
// UTC 로 옮겨 쓴다.
func TestFormatPDTIsUTCMillisecondsWithZ(t *testing.T) {
	seoul := time.FixedZone("KST", 9*60*60)
	for _, c := range []struct {
		name string
		in   time.Time
		want string
	}{
		{"정각", time.Date(2026, 9, 24, 0, 0, 4, 0, time.UTC), "2026-09-24T00:00:04.000Z"},
		{"밀리초", time.Date(2026, 9, 24, 0, 0, 11, 600_000_000, time.UTC), "2026-09-24T00:00:11.600Z"},
		{"밀리초_아래_버림", time.Date(2026, 9, 24, 0, 0, 11, 600_999_999, time.UTC), "2026-09-24T00:00:11.600Z"},
		{"다른_시간대", time.Date(2026, 9, 24, 9, 0, 4, 0, seoul), "2026-09-24T00:00:04.000Z"},
	} {
		if got := rewind.FormatPDT(c.in); got != c.want {
			t.Errorf("%s: FormatPDT(%v) = %q, want %q", c.name, c.in, got, c.want)
		}
	}
}

// 목록 본문의 PDT 줄은 FormatPDT 출력 그대로다 — 제어면과 목록이 같은 함수를 쓴다는 계약의 렌더 쪽이다.
func TestRenderPDTLineIsFormatPDT(t *testing.T) {
	pdt := time.Date(2026, 9, 24, 0, 0, 11, 600_999_999, time.FixedZone("KST", 9*60*60))
	p := rewind.Playlist{
		StreamID: "str", BaseURL: baseURL, Owner: "S1",
		Sessions: []rewind.Session{{ID: "S1", TargetDuration: 4, MinSeq: 7}},
		Rows:     append(run("S1", 7, 7, day), row("S1", 8, pdt, 4000)),
	}
	body, err := rewind.Render(p)
	if err != nil {
		t.Fatalf("Render = %v, want 오류 없음", err)
	}
	want := "#EXT-X-PROGRAM-DATE-TIME:" + rewind.FormatPDT(pdt) + "\n"
	if !strings.Contains(string(body), want) {
		t.Errorf("Render 본문에 %q 가 없다:\n%s", want, body)
	}
}
