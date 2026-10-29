package config

// 사이드카 4C resolve 설정(POK-472 설계 2절 「설정 짝 규칙」 · 7절 P14 — STREAM_KEY_RESOLVE_URL ·
// STREAM_KEY_RESOLVE_TOKEN_FILE)의 시험이다. 둘 다 빈 값이 4D 수신부를 끄는 기본값이고, 하나만 있으면 기동 실패다.

import (
	"strings"
	"testing"
)

func TestLoadResolvePairRule(t *testing.T) {
	const url, file = "http://10.0.1.5:8082", "/run/secrets/stream_key_resolve_token"
	tests := []struct {
		name      string
		url, file string
		wantErr   bool
	}{
		{name: "빈_빈", url: "", file: ""},
		{name: "값_값", url: url, file: file},
		{name: "값_빈", url: url, file: "", wantErr: true},
		{name: "빈_값", url: "", file: file, wantErr: true},
		{name: "상대_경로", url: url, file: "run/secrets/t", wantErr: true},
		{name: "공백뿐_빈", url: "   ", file: ""},
	}
	for _, tc := range tests {
		env := minimalEnv()
		env["STREAM_KEY_RESOLVE_URL"] = tc.url
		env["STREAM_KEY_RESOLVE_TOKEN_FILE"] = tc.file
		cfg, err := Load(envOf(env))
		if tc.wantErr {
			if err == nil {
				t.Errorf("%s: 에러가 없다 — 반쪽 설정이 조용히 꺼짐으로 접혔다", tc.name)
				continue
			}
			if strings.Contains(err.Error(), url) || strings.Contains(err.Error(), "secrets") {
				t.Errorf("%s: 에러에 값이 실렸다", tc.name)
			}
			continue
		}
		if err != nil {
			t.Errorf("%s: 예상 밖 에러: %v", tc.name, err)
			continue
		}
		if cfg.ResolveURL != strings.TrimSpace(tc.url) || cfg.ResolveTokenFile != tc.file {
			t.Errorf("%s: ResolveURL=%q ResolveTokenFile=%q", tc.name, cfg.ResolveURL, cfg.ResolveTokenFile)
		}
	}
}

// 값 변수 STREAM_KEY_RESOLVE_TOKEN 은 읽지 않는다 — 사이드카 비밀은 파일로만 받는다(compose.yml.tmpl:60). 그 값만
// 있으면 꺼짐이고, 주소와 함께면 토큰 파일이 없는 반쪽이라 기동 실패다.
func TestLoadResolveIgnoresTokenValue(t *testing.T) {
	env := minimalEnv()
	env["STREAM_KEY_RESOLVE_TOKEN"] = "tok-canary-9b1c"
	cfg, err := Load(envOf(env))
	if err != nil || cfg.ResolveURL != "" || cfg.ResolveTokenFile != "" {
		t.Fatalf("값 변수만 있으면 꺼짐이어야 한다: err=%v url=%q file=%q", err, cfg.ResolveURL, cfg.ResolveTokenFile)
	}
	env["STREAM_KEY_RESOLVE_URL"] = "http://10.0.1.5:8082"
	if _, err := Load(envOf(env)); err == nil {
		t.Fatal("주소 + 값 변수가 기동했다 — 값 변수를 읽고 있다")
	} else if strings.Contains(err.Error(), "tok-canary-9b1c") {
		t.Fatal("에러에 토큰 값이 실렸다")
	}
}
