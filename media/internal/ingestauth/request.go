package ingestauth

import (
	"encoding/json"
	"io"
	"net/netip"
	"regexp"
)

// maxRequestBytes 는 읽어 들이는 인증 요청 본문의 상한이다(설계 2-4 판정표 — 16 KiB).
const maxRequestBytes = 16 << 10

// MediaMTX 인증 요청의 action · protocol 가운데 판정표가 가르는 값이다(포크 conf/auth_action.go · auth/request.go).
const (
	actionAPI     = "api"
	actionRead    = "read"
	actionPublish = "publish"
	protocolSRT   = "srt"
)

// ingestauth_request_invalid 의 reason 이다 — 본문을 받지 못한 까닭의 닫힌 집합. 등급은 too_large 만 DEBUG 이고
// 나머지는 WARN 이다(logInvalid).
const (
	invalidReadError = "read_error"  // 본문을 읽다가 실패했다
	invalidTooLarge  = "too_large"   // maxRequestBytes 를 넘는다
	invalidMalformed = "malformed"   // JSON 객체가 아니거나 필드의 JSON 꼴이 틀렸다
	invalidIP        = "bad_ip"      // ip 가 IP 주소로 해석되지 않는다
	invalidConnID    = "bad_conn_id" // id 가 null 이 아닌데 MediaMTX 의 연결 id 꼴이 아니다
)

// connIDPattern 은 MediaMTX 가 싣는 연결 id 의 꼴이다 — uuid.UUID 의 JSON(소문자 16진 8-4-4-4-12). 기록의 id 가
// cut 이 받는 연결 목록의 id 와 글자 그대로 맞아야 하므로 다른 꼴(대문자 등)은 받지 않는다.
var connIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// mtxRequest 는 MediaMTX 인증 요청(10필드 — 포크 auth/manager.go 203–225행) 가운데 처리기가 보는 것이다. token ·
// query · userAgent 는 받지 않는다. user · password 는 api 행만 견주므로 action=api 일 때만 채운다(설계 2-4 「읽지도
// 기록하지도 않는 필드」 — publish 의 user · password).
type mtxRequest struct {
	ip       netip.Addr // 인증을 청한 클라이언트의 주소(comparableAddr). api 행이 SelfIP 와 견준다
	user     string     // action=api 일 때만
	password string     // action=api 일 때만
	action   string
	path     string
	protocol string
	connID   string // 연결 id. 본문에서 null 이면 비어 있다
}

// wireRequest 는 인증 요청 본문의 JSON 꼴 가운데 모든 행이 보는 필드다. 모르는 필드는 버린다 — user · password 도
// 여기서는 해석하지 않는다(wireCredentials).
type wireRequest struct {
	IP       string  `json:"ip"`
	Action   string  `json:"action"`
	Path     string  `json:"path"`
	Protocol string  `json:"protocol"`
	ID       *string `json:"id"`
}

// wireCredentials 는 action=api 의 자격 필드다. 다른 행은 이 둘을 해석하지 않는다.
type wireCredentials struct {
	User     string `json:"user"`
	Password string `json:"password"`
}

// readRequest 는 본문을 maxRequestBytes 까지 읽어 mtxRequest 로 만든다. 받지 못하면 ok 가 거짓이고 invalid 가 그
// 까닭이다(ingestauth_request_invalid 의 reason). 본문의 값은 까닭에 싣지 않는다. 자격 필드는 action=api 일 때만 따로
// 해석한다 — 그 꼴이 틀린 api 요청은 malformed 다.
func readRequest(body io.Reader) (req mtxRequest, invalid string, ok bool) {
	// 상한보다 한 바이트 더 읽어야 넘쳤는지 알 수 있다.
	raw, err := io.ReadAll(io.LimitReader(body, maxRequestBytes+1))
	switch {
	case err != nil:
		return mtxRequest{}, invalidReadError, false
	case len(raw) > maxRequestBytes:
		return mtxRequest{}, invalidTooLarge, false
	}
	// 포인터로 받아야 JSON null 을 빈 객체와 가를 수 있다.
	var w *wireRequest
	if err := json.Unmarshal(raw, &w); err != nil || w == nil {
		return mtxRequest{}, invalidMalformed, false
	}
	ip, err := netip.ParseAddr(w.IP)
	if err != nil {
		return mtxRequest{}, invalidIP, false
	}
	var connID string
	if w.ID != nil {
		if !connIDPattern.MatchString(*w.ID) {
			return mtxRequest{}, invalidConnID, false
		}
		connID = *w.ID
	}
	req = mtxRequest{
		ip:       comparableAddr(ip),
		action:   w.Action,
		path:     w.Path,
		protocol: w.Protocol,
		connID:   connID,
	}
	if w.Action == actionAPI {
		var c wireCredentials
		if err := json.Unmarshal(raw, &c); err != nil {
			return mtxRequest{}, invalidMalformed, false
		}
		req.user, req.password = c.User, c.Password
	}
	return req, "", true
}

// peerAddr 는 TCP 피어 주소(http.Request.RemoteAddr — 「IP:포트」)의 IP 다. 포트를 떼고 comparableAddr 로 바꾼다.
// 해석하지 못하면 ok 가 거짓이다.
func peerAddr(remoteAddr string) (addr netip.Addr, ok bool) {
	ap, err := netip.ParseAddrPort(remoteAddr)
	if err != nil {
		return netip.Addr{}, false
	}
	return comparableAddr(ap.Addr()), true
}

// comparableAddr 는 a 를 견줄 꼴로 바꾼다 — IPv4-mapped IPv6 는 IPv4 로 풀되, zone 이 붙은 주소는 그대로 둔다. Unmap 은
// zone 까지 지우므로 풀면 zone 붙은 mapped 주소가 zone 없는 IPv4 와 같아진다 — zone 은 그 주소의 일부라 다른 주소로
// 본다. TCP 피어 · 본문 ip · 설정 주소(CallerIP · SelfIP)가 모두 이것을 거친다.
func comparableAddr(a netip.Addr) netip.Addr {
	if a.Zone() != "" {
		return a
	}
	return a.Unmap()
}
