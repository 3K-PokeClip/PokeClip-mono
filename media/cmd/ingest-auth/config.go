package main

import (
	"errors"
	"fmt"
	"log/slog"
	"net/netip"
	"net/url"
	"strings"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxctl"
)

// 환경 변수 이름이다(설계 2-6 변수표 — LOG_LEVEL 만 선택이고 표 밖이다). 값은 어떤 에러 · 로그에도 싣지 않는다.
const (
	envListen       = "INGEST_AUTH_LISTEN"
	envCallerIP     = "INGEST_AUTH_CALLER_IP"
	envMTXAPIURL    = "MTX_API_URL"
	envMTXAPIUser   = "MTX_API_USER"
	envMTXAPIPass   = "MTX_API_PASS"
	envResolveURL   = "STREAM_KEY_RESOLVE_URL"
	envResolveToken = "STREAM_KEY_RESOLVE_TOKEN"
	envLogLevel     = "LOG_LEVEL"
)

// requireSet 은 names 가운데 빈 환경 변수의 이름을 모두 모아 에러로 돌려준다. 모두 있으면 nil 이다.
func requireSet(getenv func(string) string, names ...string) error {
	var missing []string
	for _, name := range names {
		if getenv(name) == "" {
			missing = append(missing, name)
		}
	}
	if len(missing) > 0 {
		return fmt.Errorf("환경 변수가 비었다: %s", strings.Join(missing, ", "))
	}
	return nil
}

// listenAddr 는 INGEST_AUTH_LISTEN(IP 리터럴:포트)을 읽는다. 이 IP 가 처리기의 SelfIP(cut 의 출발지)이기도 하다(설계
// 2-6). 미지정 주소(0.0.0.0 · :: — IPv4-mapped 포함)는 받지 않는다 — 제어망 밖에서도 닿고(설계 8-1), SelfIP 로 쓰면
// api 행이 모두 401 이다. 포트 0 은 받는다(임의 포트 — 실제 포트는 ingestauth_started 의 listen 에 남는다).
func listenAddr(raw string) (netip.AddrPort, error) {
	ap, err := netip.ParseAddrPort(raw)
	if err != nil {
		return netip.AddrPort{}, fmt.Errorf("%s 는 <IP>:<포트> 꼴이어야 한다(호스트 이름은 받지 않는다)", envListen)
	}
	addr, err := specified(envListen, ap.Addr())
	if err != nil {
		return netip.AddrPort{}, err
	}
	return netip.AddrPortFrom(addr, ap.Port()), nil
}

// callerAddr 는 INGEST_AUTH_CALLER_IP(MediaMTX 의 제어망 주소 — IP 리터럴)를 읽는다. 미지정 주소면 모든 요청이 403
// 이므로 받지 않는다.
func callerAddr(raw string) (netip.Addr, error) {
	addr, err := netip.ParseAddr(raw)
	if err != nil {
		return netip.Addr{}, fmt.Errorf("%s 는 IP 주소여야 한다(호스트 이름은 받지 않는다)", envCallerIP)
	}
	return specified(envCallerIP, addr)
}

// specified 는 IPv4-mapped 를 푼 addr 이 미지정 주소가 아니면 그것을 돌려준다. name 은 에러에 싣는 변수 이름이다.
func specified(name string, addr netip.Addr) (netip.Addr, error) {
	addr = addr.Unmap()
	if addr.IsUnspecified() {
		return netip.Addr{}, fmt.Errorf("%s 의 IP 는 미지정 주소(0.0.0.0 · ::)일 수 없다", name)
	}
	return addr, nil
}

// logLevel 은 LOG_LEVEL(선택 — 비면 info)을 읽는다. 사이드카(internal/config)와 같은 규칙이다 — 앞뒤 공백을 걷고
// slog.Level 의 글 꼴(debug · info · warn · error)을 받는다. 틀리면 기동 실패다 — 오타가 조용히 info 가 되지 않게.
func logLevel(raw string) (slog.Level, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return slog.LevelInfo, nil
	}
	var level slog.Level
	if err := level.UnmarshalText([]byte(raw)); err != nil {
		return 0, fmt.Errorf("%s 는 debug · info · warn · error 가운데 하나여야 한다", envLogLevel)
	}
	return level, nil
}

// newControlClient 는 MTX_API_URL · MTX_API_USER · MTX_API_PASS 로 Control API 클라이언트를 만든다. MTX_API_URL 은 http
// 만 받는다 — 제어망 평문이 설계 값이고(설계 0절 내부 구간 · 2-6), mtxctl 의 전송은 TLS 악수 시한을 두지 않는다. 그
// 밖의 검사(IP 리터럴 호스트 · 경로 · 사용자 정보 없음 · 자격)는 mtxctl.NewClient 가 한다.
func newControlClient(getenv func(string) string) (*mtxctl.Client, error) {
	if err := requireSet(getenv, envMTXAPIURL, envMTXAPIUser, envMTXAPIPass); err != nil {
		return nil, err
	}
	raw := getenv(envMTXAPIURL)
	if u, err := url.Parse(raw); err != nil || u.Scheme != "http" {
		return nil, fmt.Errorf("%s 는 http URL 이어야 한다", envMTXAPIURL)
	}
	c, err := mtxctl.NewClient(mtxctl.Options{APIURL: raw, User: getenv(envMTXAPIUser), Pass: getenv(envMTXAPIPass)})
	if err != nil {
		return nil, fmt.Errorf("%s · %s · %s: %w", envMTXAPIURL, envMTXAPIUser, envMTXAPIPass, err)
	}
	return c, nil
}

// newResolver 는 STREAM_KEY_RESOLVE_URL · STREAM_KEY_RESOLVE_TOKEN 으로 4C 클라이언트를 만든다. 기동 로그에 실을 auth
// 의 호스트:포트도 돌려준다 — URL 의 사용자 정보 · 경로 · 질의는 싣지 않는다.
func newResolver(getenv func(string) string) (*keyresolve.Client, string, error) {
	raw := getenv(envResolveURL)
	c, err := keyresolve.NewClient(keyresolve.ClientOptions{BaseURL: raw, Token: getenv(envResolveToken)})
	if err != nil {
		return nil, "", fmt.Errorf("%s · %s: %w", envResolveURL, envResolveToken, err)
	}
	u, err := url.Parse(raw)
	if err != nil {
		// NewClient 가 이미 해석했으므로 오지 않는다. 해석 에러의 문구는 원문을 담으므로 싣지 않는다.
		return nil, "", errors.New(envResolveURL + " 를 해석하지 못했다")
	}
	return c, u.Host, nil
}
