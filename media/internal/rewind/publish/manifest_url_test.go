package publish

// ManifestURL(제어면이 상태 응답의 url 로 쓰는 공개 함수 — 설계 M5 2.1 · 7절 publisher 행)의 단위 검증이다
// — POK-200 ②r. URL 의 경로가 곧 발행 객체 키(manifestKey)라 키 검사를 그대로 거친다.

import "testing"

// URL 은 base + "/" + 발행 객체 키다 — 목록 URL /dvr/{stream}/{session}/index.m3u8 그대로다.
func TestManifestURLJoinsBaseAndManifestKey(t *testing.T) {
	got, err := ManifestURL("https://media.pokeclip.com", "str", "S-20260831-0152")
	if want := "https://media.pokeclip.com/dvr/str/S-20260831-0152/index.m3u8"; err != nil || got != want {
		t.Errorf("ManifestURL = (%q, %v), want (%q, nil)", got, err, want)
	}
}

// 키에 쓸 수 없는 성분은 manifestKey 와 같이 거부한다 — 상위 · 현재 경로 참조 · 구분자 · 빈 값.
func TestManifestURLRejectsUnsafeComponents(t *testing.T) {
	for _, v := range []string{"", ".", "..", "a/b", "a b"} {
		if got, err := ManifestURL("https://media.pokeclip.com", v, "S"); err == nil {
			t.Errorf("ManifestURL(base, %q, \"S\") = %q, want 오류(stream 성분 거부)", v, got)
		}
		if got, err := ManifestURL("https://media.pokeclip.com", "str", v); err == nil {
			t.Errorf("ManifestURL(base, \"str\", %q) = %q, want 오류(session 성분 거부)", v, got)
		}
	}
}

// base 는 목록 렌더의 BaseURL 과 같은 규칙이다 — 비었거나 '/' 로 끝나면 이중 구분자 · 상대 URL 이 되므로 오류다.
func TestManifestURLRejectsUnusableBase(t *testing.T) {
	for _, base := range []string{"", "https://media.pokeclip.com/"} {
		if got, err := ManifestURL(base, "str", "S"); err == nil {
			t.Errorf("ManifestURL(%q, \"str\", \"S\") = %q, want 오류(base 거부)", base, got)
		}
	}
}
