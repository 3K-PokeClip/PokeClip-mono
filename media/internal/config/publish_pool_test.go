package config

// 되감기 발행자 풀 상한(kty 결정 C2 · POK-196 M6-8 ④ — REWIND_PUBLISH_POOL_MAX_CONNS)의 시험이다.

import (
	"strings"
	"testing"
)

// C2(kty 결정) — 되감기 발행자 풀 상한은 4 고정이고 REWIND_PUBLISH_POOL_MAX_CONNS 로 조정한다. 발행자 풀은 버킷이 있을
// 때만 있으므로 버킷 블록 안에서만 읽는다(버킷이 비면 틀린 값도 인덱싱을 죽이지 않는다).
func TestLoadRewindPublishPoolMaxConns(t *testing.T) {
	withBucket := func(v string) map[string]string {
		env := minimalEnv()
		env["S3_BUCKET"] = "pokeclip-test"
		env["REWIND_PUBLIC_BASE_URL"] = "https://media.example.com"
		env["REWIND_PUBLISH_POOL_MAX_CONNS"] = v
		return env
	}
	for _, c := range []struct {
		v    string
		want int32
	}{{"", 4}, {"2", 2}} {
		cfg, err := Load(envOf(withBucket(c.v)))
		if err != nil || cfg.RewindPublishPoolMaxConns != c.want {
			t.Errorf("%q → (%d, %v), want %d", c.v, cfg.RewindPublishPoolMaxConns, err, c.want)
		}
	}
	for _, v := range []string{"0", "-1", "x", "2147483648"} {
		if _, err := Load(envOf(withBucket(v))); err == nil || !strings.Contains(err.Error(), "REWIND_PUBLISH_POOL_MAX_CONNS") {
			t.Errorf("%q → %v, want 기동 거부", v, err)
		}
	}
	env := minimalEnv()
	env["REWIND_PUBLISH_POOL_MAX_CONNS"] = "x"
	if cfg, err := Load(envOf(env)); err != nil || cfg.RewindPublishPoolMaxConns != 0 {
		t.Errorf("버킷 없음 → (%d, %v), want 읽지 않음(0, nil)", cfg.RewindPublishPoolMaxConns, err)
	}
}
