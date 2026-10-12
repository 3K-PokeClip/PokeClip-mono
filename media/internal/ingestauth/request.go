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

// ingestauth_request_invalid 의 reason 이다 — 본문을 받지 못한 까닭의 닫힌 집합.
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
// query · userAgent 는 받지 않는다. user · password 는 api 행만 견주고 publish 는 읽지 않는다(설계 2-4).
type mtxRequest struct {
	ip       netip.Addr // 인증을 청한 클라이언트의 주소(IPv4-mapped 는 IPv4 로). api 행이 SelfIP 와 견준다
	user     string
	password string
	action   string
	path     string
	protocol string
	connID   string // 연결 id. 본문에서 null 이면 비어 있다
}

// wireRequest 는 인증 요청 본문의 JSON 꼴이다. 모르는 필드는 버린다.
type wireRequest struct {
	IP       string  `json:"ip"`
	User     string  `json:"user"`
	Password string  `json:"password"`
	Action   string  `json:"action"`
	Path     string  `json:"path"`
	Protocol string  `json:"protocol"`
	ID       *string `json:"id"`
}

// readRequest 는 본문을 maxRequestBytes 까지 읽어 mtxRequest 로 만든다. 받지 못하면 ok 가 거짓이고 invalid 가 그
// 까닭이다(ingestauth_request_invalid 의 reason). 본문의 값은 까닭에 싣지 않는다.
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
	return mtxRequest{
		ip:       ip.Unmap(),
		user:     w.User,
		password: w.Password,
		action:   w.Action,
		path:     w.Path,
		protocol: w.Protocol,
		connID:   connID,
	}, "", true
}

// peerAddr 는 TCP 피어 주소(http.Request.RemoteAddr — 「IP:포트」)의 IP 다. 포트를 떼고 IPv4-mapped IPv6 는 IPv4 로
// 바꾼다. 해석하지 못하면 ok 가 거짓이다.
func peerAddr(remoteAddr string) (addr netip.Addr, ok bool) {
	ap, err := netip.ParseAddrPort(remoteAddr)
	if err != nil {
		return netip.Addr{}, false
	}
	return ap.Addr().Unmap(), true
}
