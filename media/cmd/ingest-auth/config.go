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

// 환경 변수 이름이다(설계 2-6 변수표 — LOG_LEVEL 만 선택이고 표 밖이다). 값은 어떤 에러 · 로그에도 싣지 않는다. 비밀
// (MTX_API_PASS · STREAM_KEY_RESOLVE_TOKEN · INGEST_AUTH_BASIC_PASS)은 이름 뒤에 _FILE 을 붙인 변수로 파일 경로를 줘도
// 된다(resolveSecret).
const (
	envListen          = "INGEST_AUTH_LISTEN"
	envCallerIP        = "INGEST_AUTH_CALLER_IP"
	envMTXAPIURL       = "MTX_API_URL"
	envMTXAPIUser      = "MTX_API_USER"
	envMTXAPIPass      = "MTX_API_PASS"
	envResolveURL      = "STREAM_KEY_RESOLVE_URL"
	envResolveToken    = "STREAM_KEY_RESOLVE_TOKEN"
	envLogLevel        = "LOG_LEVEL"
	envCallerBasicUser = "INGEST_AUTH_BASIC_USER"   // 호출자(MediaMTX) Basic 사용자 — authHTTPAddress URL 자격
	envCallerBasicPass = "INGEST_AUTH_BASIC_PASS"   // 호출자 Basic 비번
	envCallerBasic     = "INGEST_AUTH_CALLER_BASIC" // off 면 호출자 Basic 검사를 끈다(명시 끔) — 그 밖의 값은 거부
	// 4C 창구 — 비면 resolve-token(전용), internal-token 이면 공용(resolveChannel).
	envResolveChannel = "STREAM_KEY_RESOLVE_CHANNEL"
)

// minCallerBasicPassLen 은 호출자 Basic 비번의 최소 길이다(kty 120차). unreserved 문자만 받으므로 바이트 수가 곧 글자
// 수다. 같은 netns 의 프로세스가 짧은 비번을 맞혀 보지 못하게 한다.
const minCallerBasicPassLen = 24

// callerBasicOffValue 는 INGEST_AUTH_CALLER_BASIC 이 받는 단 하나의 값이다.
const callerBasicOffValue = "off"

// callerBasic 은 호출자 Basic 검사의 설정이다. off 가 참이면 user · pass 는 비어 있다.
type callerBasic struct {
	user string
	pass secret
	off  bool
}

// loadCallerBasic 은 호출자 Basic 설정을 읽는다. 자격(INGEST_AUTH_BASIC_USER · INGEST_AUTH_BASIC_PASS 또는 _FILE) 둘
// 다이거나 명시 끔(INGEST_AUTH_CALLER_BASIC=off) 하나여야 한다 — 아무것도 없으면 기동을 거부한다(prod 에서 설정이
// 빠지면 같은 netns 의 누구나 passphrase 를 받는다 — 계약 D2). 사용자 이름과 비번은 unreserved 문자만 받는다 — 둘 다
// authHTTPAddress URL 에 렌더된다. 비번은 24자 이상이어야 한다. 에러에는 변수 이름과 규칙만 싣는다.
func loadCallerBasic(getenv func(string) string) (callerBasic, error) {
	mode := getenv(envCallerBasic)
	if mode != "" && mode != callerBasicOffValue {
		return callerBasic{}, fmt.Errorf("%s 는 %s 만 받는다(비우면 호출자 Basic 검사를 켠다)", envCallerBasic, callerBasicOffValue)
	}
	user := getenv(envCallerBasicUser)
	hasPass := getenv(envCallerBasicPass) != "" || getenv(envCallerBasicPass+fileSuffix) != ""
	switch {
	case mode == callerBasicOffValue && (user != "" || hasPass):
		return callerBasic{}, fmt.Errorf("%s=%s 와 호출자 Basic 자격(%s · %s)을 함께 줄 수 없다", envCallerBasic, callerBasicOffValue, envCallerBasicUser, envCallerBasicPass)
	case mode == callerBasicOffValue:
		return callerBasic{off: true}, nil
	case user == "" && !hasPass:
		return callerBasic{}, fmt.Errorf("환경 변수가 비었다: %s · %s(또는 %s) — 호출자 Basic 검사를 끄려면 %s=%s 를 준다",
			envCallerBasicUser, envCallerBasicPass, envCallerBasicPass+fileSuffix, envCallerBasic, callerBasicOffValue)
	case user == "":
		return callerBasic{}, fmt.Errorf("환경 변수가 비었다: %s — %s 와 함께 준다", envCallerBasicUser, envCallerBasicPass)
	case !unreserved(user):
		return callerBasic{}, fmt.Errorf("%s 에 허용 밖 문자가 있다 — URL 의 unreserved 문자(A-Z a-z 0-9 - . _ ~)만 받는다", envCallerBasicUser)
	}
	pass, err := resolveSecret(getenv, envCallerBasicPass)
	if err != nil {
		return callerBasic{}, err
	}
	if !unreserved(pass.reveal()) {
		return callerBasic{}, fmt.Errorf("%s 에 허용 밖 문자가 있다 — URL 의 unreserved 문자(A-Z a-z 0-9 - . _ ~)만 받는다", envCallerBasicPass)
	}
	if len(pass.reveal()) < minCallerBasicPassLen {
		return callerBasic{}, fmt.Errorf("%s 는 %d자 이상이어야 한다", envCallerBasicPass, minCallerBasicPassLen)
	}
	return callerBasic{user: user, pass: pass}, nil
}

// unreserved 는 s 가 URL 표준(RFC 3986 2.3)의 unreserved 문자로만 되어 있는지 본다. 이 밖의 문자는 URL 안에서 경계로
// 읽히거나(@ : / ? #) 퍼센트 디코딩되어(%41 → A) 렌더한 값과 MediaMTX 가 싣는 값이 달라진다.
func unreserved(s string) bool {
	for _, c := range []byte(s) {
		switch {
		case 'A' <= c && c <= 'Z', 'a' <= c && c <= 'z', '0' <= c && c <= '9', c == '-', c == '.', c == '_', c == '~':
		default:
			return false
		}
	}
	return true
}

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
// zone 이 붙은 주소는 받지 않는다 — 처리기(ingestauth)는 zone 붙은 주소를 zone 없는 주소와 다르게 보는데, Unmap 은
// IPv4-mapped 의 zone 을 지워 다른 주소를 만든다. 제어망은 IPv4 리터럴이라(설계 2-8) zone 이 쓰일 자리가 없다.
func specified(name string, addr netip.Addr) (netip.Addr, error) {
	if addr.Zone() != "" {
		return netip.Addr{}, fmt.Errorf("%s 의 IP 에는 zone(%%…)을 붙일 수 없다", name)
	}
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

// newControlClient 는 MTX_API_URL · MTX_API_USER 와 이미 읽은 Control API 비번 pass(MTX_API_PASS — resolveSecret)로 Control
// API 클라이언트를 만든다. MTX_API_URL 은 http 만 받는다 — 제어망 평문이 설계 값이다(설계 0절 내부 구간 · 2-6). 그 밖의
// 검사(IP 리터럴 호스트 · 경로 · 사용자 정보 없음 · 자격)는 mtxctl.NewClient 가 한다.
func newControlClient(getenv func(string) string, pass secret) (*mtxctl.Client, error) {
	if err := requireSet(getenv, envMTXAPIURL, envMTXAPIUser); err != nil {
		return nil, err
	}
	raw := getenv(envMTXAPIURL)
	if u, err := url.Parse(raw); err != nil || u.Scheme != "http" {
		return nil, fmt.Errorf("%s 는 http URL 이어야 한다", envMTXAPIURL)
	}
	c, err := mtxctl.NewClient(mtxctl.Options{APIURL: raw, User: getenv(envMTXAPIUser), Pass: mtxctl.NewSecret(pass.reveal())})
	if err != nil {
		return nil, fmt.Errorf("%s · %s · %s: %w", envMTXAPIURL, envMTXAPIUser, envMTXAPIPass, err)
	}
	return c, nil
}

// resolveChannel 은 STREAM_KEY_RESOLVE_CHANNEL 을 읽는다 — 비면 전용 창구(resolve-token)다. 닫힌 집합 밖의 값은 기동
// 실패다 — 오타가 조용히 기본 창구가 되지 않게.
func resolveChannel(raw string) (keyresolve.Channel, error) {
	for _, c := range []keyresolve.Channel{keyresolve.ChannelResolveToken, keyresolve.ChannelInternalToken} {
		if raw == c.String() {
			return c, nil
		}
	}
	if raw == "" {
		return keyresolve.ChannelResolveToken, nil
	}
	return 0, fmt.Errorf("%s 는 %v · %v 가운데 하나여야 한다(비우면 %v)", envResolveChannel,
		keyresolve.ChannelResolveToken, keyresolve.ChannelInternalToken, keyresolve.ChannelResolveToken)
}

// newResolver 는 STREAM_KEY_RESOLVE_URL · STREAM_KEY_RESOLVE_CHANNEL 과 이미 읽은 출입증 token(STREAM_KEY_RESOLVE_TOKEN —
// resolveSecret)으로 4C 클라이언트를 만든다. 기동 로그에 실을 4C 끝점의 모습(authTarget)도 돌려준다.
func newResolver(getenv func(string) string, token secret) (*keyresolve.Client, authTarget, error) {
	raw := getenv(envResolveURL)
	channel, err := resolveChannel(getenv(envResolveChannel))
	if err != nil {
		return nil, authTarget{}, err
	}
	c, err := keyresolve.NewClient(keyresolve.ClientOptions{BaseURL: raw, Token: keyresolve.NewSecret(token.reveal()), Channel: channel})
	if err != nil {
		return nil, authTarget{}, fmt.Errorf("%s · %s: %w", envResolveURL, envResolveToken, err)
	}
	u, err := url.Parse(raw)
	if err != nil {
		// NewClient 가 이미 해석했으므로 오지 않는다. 해석 에러의 문구는 원문을 담으므로 싣지 않는다.
		return nil, authTarget{}, errors.New(envResolveURL + " 를 해석하지 못했다")
	}
	return c, authTargetOf(u, channel), nil
}

// authTarget 은 기동 로그에 싣는 4C 끝점의 모습이다 — 호스트의 부류와 포트뿐이다. 주소는 싣지 않는다 — 기동 로그는 이슈 ·
// 문서 · 리뷰로 옮겨지는데 dev 상자의 사설 주소가 따라가지 않게 한다(설계 6절). 부류로 설계 2-6 의 「공인 주소면 403」을
// 가른다.
type authTarget struct {
	hostKind string             // hostPrivate · hostPublic · hostName · hostOther
	port     string             // URL 의 포트 — 없으면 스킴의 기본 포트(전송이 다이얼하는 포트)
	channel  keyresolve.Channel // 출입증을 싣는 창구 — 기동 로그의 auth_channel
}

// authTarget.hostKind 의 값이다.
const (
	hostPrivate = "private" // 사설 주소(RFC 1918 · RFC 4193) — 설계 값(dev 상자의 사설 주소)
	hostPublic  = "public"  // 그 밖의 전역 유니캐스트 — auth 의 출발지 잠금에 걸려 403 이다
	hostName    = "name"    // IP 리터럴이 아니다 — DNS 가 푼다
	hostOther   = "other"   // 루프백 · 링크 로컬 · 미지정 · 멀티캐스트 — 설계가 쓰는 꼴이 아니다
)

// authTargetOf 는 u · channel 의 authTarget 이다. u 는 keyresolve.NewClient 가 받은 URL 이라 스킴은 http · https 이고
// 호스트가 있다.
func authTargetOf(u *url.URL, channel keyresolve.Channel) authTarget {
	port := u.Port()
	if port == "" {
		port = "80"
		if u.Scheme == "https" {
			port = "443"
		}
	}
	return authTarget{hostKind: hostKindOf(u.Hostname()), port: port, channel: channel}
}

// hostKindOf 는 host(URL 의 Hostname — 괄호 · 포트 없음)의 부류다. 사설을 먼저 본다 — IsGlobalUnicast 는 사설 주소에도
// 참이다.
func hostKindOf(host string) string {
	addr, err := netip.ParseAddr(host)
	switch {
	case err != nil:
		return hostName
	case addr.IsPrivate():
		return hostPrivate
	case addr.IsGlobalUnicast():
		return hostPublic
	}
	return hostOther
}
