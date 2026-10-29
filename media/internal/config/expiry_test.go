package config

// 60일 만료 설정(POK-196 M6-4 · 설계 M6 7절 prod 연결 — CLIP_INTERNAL_URL · CLIP_EXPIRY_TOKEN ·
// CLIP_EXPIRY_TOKEN_FILE)의 시험이다. 빈 주소가 만료를 끄는 롤백 스위치라 기본값이 없다.

import (
	"fmt"
	"strings"
	"testing"
)

func TestLoadExpiryDefaultsOff(t *testing.T) {
	cfg, err := Load(envOf(minimalEnv()))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.Expiry.ClipURL != "" || cfg.Expiry.TokenFile != "" || cfg.Expiry.HasToken {
		t.Errorf("Expiry = %+v, want 꺼짐(빈 주소 · 토큰 없음)", cfg.Expiry)
	}
}

func TestLoadExpiryValues(t *testing.T) {
	env := minimalEnv()
	env["CLIP_INTERNAL_URL"] = " http://clip.pokeclip.internal:8081 "
	env["CLIP_EXPIRY_TOKEN_FILE"] = "/run/secrets/clip-expiry-token"
	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.Expiry.ClipURL != "http://clip.pokeclip.internal:8081" || cfg.Expiry.TokenFile != "/run/secrets/clip-expiry-token" || cfg.Expiry.HasToken {
		t.Errorf("Expiry = %+v", cfg.Expiry)
	}

	env = minimalEnv()
	env["CLIP_INTERNAL_URL"] = "https://clip.example"
	env["CLIP_EXPIRY_TOKEN"] = "tok-canary-7f3a"
	cfg, err = Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if !cfg.Expiry.HasToken || cfg.Expiry.Token.String() != "[REDACTED]" {
		t.Errorf("값 토큰이 실리지 않았다: %+v", cfg.Expiry)
	}
	if s := fmt.Sprintf("%v %+v %#v", cfg, cfg, cfg); strings.Contains(s, "tok-canary-7f3a") {
		t.Fatal("설정을 찍으면 토큰 원문이 나온다")
	}
}

// 잘못된 값은 기동 거부다 — 에러에 값(토큰 · 경로)을 싣지 않는다.
func TestLoadExpiryRejectsMalformedValues(t *testing.T) {
	for _, tt := range []map[string]string{
		{"CLIP_INTERNAL_URL": "clip:8081"},
		{"CLIP_INTERNAL_URL": "ftp://clip"},
		{"CLIP_INTERNAL_URL": "http://clip/"},
		{"CLIP_INTERNAL_URL": "http://clip/api"},
		{"CLIP_INTERNAL_URL": "http://u:p@clip"},
		{"CLIP_INTERNAL_URL": "http://clip?x=1"},
		{"CLIP_EXPIRY_TOKEN_FILE": "relative/path"},
		{"CLIP_EXPIRY_TOKEN": "tok-canary-7f3a", "CLIP_EXPIRY_TOKEN_FILE": "/run/secrets/t"},
	} {
		env := minimalEnv()
		for k, v := range tt {
			env[k] = v
		}
		_, err := Load(envOf(env))
		if err == nil {
			t.Errorf("%v 인데 에러가 없다", tt)
			continue
		}
		if strings.Contains(err.Error(), "tok-canary-7f3a") || strings.Contains(err.Error(), "/run/secrets") {
			t.Errorf("%v 의 에러에 값이 실렸다: %v", tt, err)
		}
	}
}
