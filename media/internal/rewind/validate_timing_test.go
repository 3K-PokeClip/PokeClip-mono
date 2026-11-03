package rewind_test

// 발행 전 검사 S5 「본문 = 목록 값」의 판 태그 조항(POK-196 PDT 생산자 c7a — 계약3 7-6 「판 태그」 · r6 700–701 승계).
// 태그 줄은 조각마다 개수 0 또는 1 · 값 = 행의 판(판 NULL 은 0) · 자리 = MAP 줄 뒤 · PDT 줄 앞이고, 목록 행이 바라는
// 자리(첫 조각이 판 NULL 이 아닐 때 · 앞 행과 판이 다른 조각)에만 선다. 어긋난 본문은 발행 중단이다 — 플레이어는 조각마다
// 마지막으로 본 태그의 판으로 환산식을 고르므로, 태그 한 줄이 빠지거나 옮겨 가면 그 뒤 조각 전부의 시각이 틀린다.

import (
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

const (
	timingTag2 = "#EXT-X-POKECLIP-TIMING:2\n"
	timingTag0 = "#EXT-X-POKECLIP-TIMING:0\n"
	mapV       = `#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/V.mp4"` + "\n"
	pdtV10     = "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:00.000Z\n"
	pdtV11     = "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:04.000Z\n"
)

func TestValidateTimingTagBody(t *testing.T) {
	v2 := mustRender(t, soloTiming(timingVersion2))
	null := mustRender(t, soloTiming(0))
	tests := []struct {
		name string
		null bool
		body string
		want string
	}{
		{"판_2_렌더_그대로", false, v2, ""},
		{"판_NULL_렌더_그대로", true, null, ""},
		{"태그_삭제", false, strings.Replace(v2, timingTag2, "", 1), "S5"},
		{"태그_값_바꿈", false, strings.Replace(v2, timingTag2, timingTag0, 1), "S5"},
		{"태그_중복", false, strings.Replace(v2, timingTag2, timingTag2+timingTag2, 1), "S5"},
		{"태그_MAP_앞으로_이동", false, strings.Replace(v2, mapV+timingTag2, timingTag2+mapV, 1), "S5"},
		{"태그_PDT_뒤로_이동", false, strings.Replace(v2, timingTag2+pdtV10, pdtV10+timingTag2, 1), "S5"},
		{"판이_같은_다음_조각에_태그", false, strings.Replace(v2, pdtV11, timingTag2+pdtV11, 1), "S5"},
		{"마지막_조각_뒤에_태그", false, v2 + timingTag2, "S5"},
		{"판_NULL_목록에_태그", true, strings.Replace(null, mapV, mapV+timingTag0, 1), "S5"},
		{"태그_꼬리_글자", false, strings.Replace(v2, timingTag2, "#EXT-X-POKECLIP-TIMING:2 \n", 1), "S5"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p, rendered := soloTiming(timingVersion2), v2
			if tt.null {
				p, rendered = soloTiming(0), null
			}
			if tt.want != "" && tt.body == rendered {
				t.Fatalf("변조가 본문을 바꾸지 못했다 — 픽스처 문자열이 렌더와 다르다")
			}
			wantVerdict(t, validate0(p, tt.body), tt.want)
		})
	}
}

// 판이 목록 안에서 바뀌는 목록(계승 접두 · GAP 행 포함)도 렌더 그대로면 통과하고, 바뀌는 조각의 태그를 지우면 멈춘다.
// 태그를 GAP 줄 뒤(PDT 줄 앞)로 옮긴 본문은 계약 자리(MAP 뒤 · PDT 앞) 안이라 통과한다 — 렌더는 GAP 앞에 쓴다.
func TestValidateTimingTagOnMixedList(t *testing.T) {
	body := mustRender(t, mixedTiming())
	gapTag := timingTag2 + "#EXT-X-GAP\n"
	if !strings.Contains(body, gapTag) || !strings.Contains(body, timingTag0) {
		t.Fatalf("픽스처 본문에 바뀌는 조각의 태그가 없다:\n%s", body)
	}

	wantVerdict(t, validate0(mixedTiming(), body), "")
	wantVerdict(t, validate0(mixedTiming(), strings.Replace(body, timingTag0, "", 1)), "S5")
	wantVerdict(t, validate0(mixedTiming(), strings.Replace(body, gapTag, "#EXT-X-GAP\n"+timingTag2, 1)), "")
	wantVerdict(t, validate0(mixedTiming(), strings.Replace(body, gapTag, "#EXT-X-GAP\n", 1)), "S5")
}

// validate0 은 처음 발행하는 목록(세대 1 · 직전 발행 없음)으로 body 를 검사한다.
func validate0(p rewind.Playlist, body string) error {
	return rewind.Validate(p, []byte(body), 1, nil)
}
