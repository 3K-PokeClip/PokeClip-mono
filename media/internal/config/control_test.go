package config

// 제어면 설정 셋(설계 M5 3.4 · 3.5 · 3.7 — CONTROL_LISTEN · CONTROL_CORS_ORIGIN · CONTROL_STREAM_RPS)의 시험이다. 빈
// CONTROL_LISTEN 은 서버를 띄우지 않는 롤백 스위치라 기본값이 없다.

import "testing"

func TestLoadControlDefaults(t *testing.T) {
	cfg, err := Load(envOf(minimalEnv()))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if cfg.Control.Listen != "" || cfg.Control.AllowOrigin != "" || cfg.Control.StreamRPS != 10 {
		t.Errorf("Control = %+v, want 빈 듣기 · 빈 Origin · 초당 10", cfg.Control)
	}
}

func TestLoadControlOverrides(t *testing.T) {
	env := minimalEnv()
	env["CONTROL_LISTEN"] = " 0.0.0.0:8086 "
	env["CONTROL_CORS_ORIGIN"] = "http://localhost:3000"
	env["CONTROL_STREAM_RPS"] = "25"
	cfg, err := Load(envOf(env))
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	want := ControlOptions{Listen: "0.0.0.0:8086", AllowOrigin: "http://localhost:3000", StreamRPS: 25}
	if cfg.Control != want {
		t.Errorf("Control = %+v, want %+v", cfg.Control, want)
	}
}

// 잘못된 값은 기동 거부다 — 조용히 서버를 끄거나 엉뚱한 Origin 에 CORS 를 열지 않는다.
func TestLoadControlRejectsMalformedValues(t *testing.T) {
	for _, tt := range []struct{ key, val string }{
		{"CONTROL_LISTEN", "8086"},
		{"CONTROL_LISTEN", "127.0.0.1:0"},
		{"CONTROL_LISTEN", "127.0.0.1:70000"},
		{"CONTROL_LISTEN", "127.0.0.1:http"},
		{"CONTROL_CORS_ORIGIN", "localhost:3000"},
		{"CONTROL_CORS_ORIGIN", "ftp://localhost:3000"},
		{"CONTROL_CORS_ORIGIN", "http://localhost:3000/"},
		{"CONTROL_CORS_ORIGIN", "http://localhost:3000/app"},
		{"CONTROL_CORS_ORIGIN", "http://user:pw@localhost:3000"},
		{"CONTROL_CORS_ORIGIN", "http://localhost:3000?x=1"},
		{"CONTROL_CORS_ORIGIN", "*"},
		{"CONTROL_STREAM_RPS", "0"},
		{"CONTROL_STREAM_RPS", "열"},
	} {
		env := minimalEnv()
		env[tt.key] = tt.val
		if _, err := Load(envOf(env)); err == nil {
			t.Errorf("%s=%q 인데 에러가 없다", tt.key, tt.val)
		}
	}
}
