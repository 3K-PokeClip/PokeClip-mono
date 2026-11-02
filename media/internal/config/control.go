package config

import (
	"fmt"
	"net"
	"net/url"
	"strings"
)

// defaultControlStreamRPS 는 스트림당 초당 답하는 상태 요청 수의 기본값이다(설계 M5 3.4 · D17 7.3).
const defaultControlStreamRPS = 10

// ControlOptions 는 되감기 제어면 서버의 설정이다(설계 M5 3.4 · 3.5 · 3.7). 서버 조립은 cmd 의 startControl 이 한다.
type ControlOptions struct {
	// Listen 은 CONTROL_LISTEN 이다 — host:port. 빈 값이면 서버를 띄우지 않는다(롤백 스위치 — withDefault 를 쓰지 않는다).
	Listen string
	// AllowOrigin 은 CONTROL_CORS_ORIGIN 이다 — 로컬 전용 Origin 하나(scheme://host[:port]). prod 는 엣지 응답 머리
	// 정책이 CORS 를 덮어 빈 값이다.
	AllowOrigin string
	// StreamRPS 는 CONTROL_STREAM_RPS 다 — 두 경로가 나눠 쓰는 스트림별 초당 한도.
	StreamRPS int
}

// loadControl 은 제어면 설정 셋을 읽고 검사한다. 잘못된 값은 기동 거부다 — 조용히 서버를 끄거나 엉뚱한 Origin 에
// CORS 를 열지 않는다.
func loadControl(env func(string) string) (ControlOptions, error) {
	listen := strings.TrimSpace(env("CONTROL_LISTEN"))
	if err := validateControlListen(listen); err != nil {
		return ControlOptions{}, err
	}
	origin := strings.TrimSpace(env("CONTROL_CORS_ORIGIN"))
	if err := validateCORSOrigin(origin); err != nil {
		return ControlOptions{}, err
	}
	rps, err := positiveInt(env, "CONTROL_STREAM_RPS", defaultControlStreamRPS)
	if err != nil {
		return ControlOptions{}, err
	}
	return ControlOptions{Listen: listen, AllowOrigin: origin, StreamRPS: rps}, nil
}

// validateControlListen 은 듣기 주소가 host:port 이고 포트가 1–65535 인가다. 빈 값(끔)은 통과다. 포트 0(임의 포트)은
// nginx · ALB 가 가리킬 수 없어 거부한다.
func validateControlListen(listen string) error {
	if listen == "" {
		return nil
	}
	_, port, err := net.SplitHostPort(listen)
	if err != nil || port == "" || !validPort(port) {
		return fmt.Errorf("CONTROL_LISTEN 은 host:port(포트 1–65535)여야 한다: %q", listen)
	}
	return nil
}

// validateCORSOrigin 은 값이 Origin 하나(http · https scheme://host[:port] — 경로 · 질의 · 자격증명 없음)인가다. 처리기는
// 요청 Origin 과 글자 그대로 비교하므로 끝 '/' 하나도 어긋남이다. 빈 값(CORS 머리 없음)은 통과다.
func validateCORSOrigin(origin string) error {
	if origin == "" {
		return nil
	}
	u, err := url.Parse(origin)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil ||
		u.Path != "" || u.RawQuery != "" || u.Fragment != "" || u.ForceQuery || !validPort(u.Port()) {
		return fmt.Errorf("CONTROL_CORS_ORIGIN 은 Origin 하나(http(s)://호스트[:포트] — 경로 · 끝 '/' · 질의 없음)여야 한다: %q", origin)
	}
	return nil
}
