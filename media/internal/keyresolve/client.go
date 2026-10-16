// Package keyresolve 는 계약4 4C(POST /internal/stream-keys/resolve)의 클라이언트다.
//
// 이 패키지의 에러에는 응답 본문 · 출입증 토큰 · passphrase · 끝점(BaseURL)이 실리지 않는다(설계 6절 누설 규칙).
// 로거도 두지 않는다 — 무엇을 기록할지는 부르는 쪽이 정한다.
package keyresolve

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// Header 는 4C 전용 출입증 헤더다(4C 개정 — 공용 X-Internal-Token 이 아니다). auth 쪽
// StreamKeyResolveSecurityConfig.HEADER 와 같은 이름이어야 한다.
const Header = "X-Stream-Key-Resolve-Token"

// resolvePath 는 4C 의 경로다(auth 쪽 StreamKeyResolveController).
const resolvePath = "/internal/stream-keys/resolve"

// ClientOptions 는 NewClient 의 설정이다. Token 은 Secret 이라 ClientOptions 를 어떻게 찍어도 출입증이 나오지 않는다.
type ClientOptions struct {
	BaseURL string // http://<dev EC2 사설 주소>:8082 — 값은 .env 에만. 비면 NewClient 가 거부
	Token   Secret // auth 의 STREAM_KEY_RESOLVE_TOKEN 과 같은 값(NewSecret 으로 감싼다). 비면 거부
}

// Client 는 4C 를 부르는 클라이언트다. 여러 고루틴이 함께 써도 된다. 출입증은 요청 머리에 싣는 함수 안에만 있어서
// Client 를 어떻게 찍어도 나오지 않는다.
type Client struct {
	endpoint   string
	authorize  func(*http.Request) // 요청에 전용 출입증 머리를 싣는다
	httpClient *http.Client
}

// NewClient 는 opt 를 검사해 Client 를 만든다. BaseURL · Token 이 비었거나, BaseURL 이 http · https 의 절대 URL(호스트
// 포함)이 아니거나 사용자 정보 · 질의 · 조각이 있으면 에러다. Token 에 제어 문자가 있거나 앞뒤가 공백이어도 에러다 —
// 머리 값으로 그대로 실을 수 없다.
//
// 에러에는 BaseURL · Token 의 값도, url.Parse 의 에러 문구(입력 원문을 담는다)도 싣지 않는다 —
// 설정을 잘못 넣으면 BaseURL 자리에 토큰이 들어갈 수 있다.
func NewClient(opt ClientOptions) (*Client, error) {
	if opt.BaseURL == "" {
		return nil, errors.New("keyresolve: BaseURL 이 비었다")
	}
	token := opt.Token.reveal()
	if token == "" {
		return nil, errors.New("keyresolve: Token 이 비었다")
	}
	if !headerSafe(token) {
		return nil, errors.New("keyresolve: Token 에 제어 문자가 있거나 앞뒤가 공백이다 — 머리 값으로 그대로 실을 수 없다")
	}
	base, err := url.Parse(opt.BaseURL)
	if err != nil || (base.Scheme != "http" && base.Scheme != "https") || base.Hostname() == "" {
		return nil, errors.New("keyresolve: BaseURL 은 http 또는 https 의 절대 URL(호스트 포함)이어야 한다")
	}
	// 사용자 정보는 요청마다 Basic 머리로 auth 에 가고, 질의는 auth 까지 간다. 둘 다 끝점 문자열에도 남는다.
	if base.User != nil || base.RawQuery != "" || base.ForceQuery || base.Fragment != "" {
		return nil, errors.New("keyresolve: BaseURL 에 사용자 정보 · 질의 · 조각이 있다")
	}
	return &Client{
		endpoint:  base.JoinPath(resolvePath).String(),
		authorize: func(r *http.Request) { r.Header.Set(Header, token) },
		// Timeout 을 두지 않는다 — 시한은 Resolve 의 ctx 하나다.
		httpClient: &http.Client{
			// 기본 전송(http.DefaultTransport)은 HTTP_PROXY 같은 환경변수의 프록시를 탄다. 이 전송은
			// Proxy 가 nil 이라 auth 에 바로 붙는다 — 전용 토큰 헤더가 프록시를 거치지 않는다. 연결은
			// 다시 쓴다(keep-alive 는 기본으로 켜져 있다). 연결 준비에는 connectTimeout 상한을 둔다.
			Transport: &http.Transport{
				DialContext:         (&net.Dialer{Timeout: connectTimeout}).DialContext,
				TLSHandshakeTimeout: connectTimeout,
			},
			// 리다이렉트를 따르지 않는다. 따라가면 Go 는 사용자 정의 헤더(전용 토큰)를 새 주소에도
			// 싣는다. 3xx 는 그대로 돌아와 비-200(Kind "status")이 된다.
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}, nil
}

// headerSafe 는 s 를 머리 값으로 그대로 실을 수 있는지 본다 — 제어 문자(줄바꿈 · NUL · 탭 · DEL 등)가 없고 앞뒤가
// 공백이 아니어야 한다. Go 전송은 제어 문자가 든 머리 값을 보내지 않고(그 실패는 auth 에 닿지 못한 것처럼 보인다),
// 앞뒤 공백은 받는 쪽이 걷어 다른 값이 된다(RFC 9110 5.5).
func headerSafe(s string) bool {
	return !strings.ContainsFunc(s, unicode.IsControl) && !strings.HasPrefix(s, " ") && !strings.HasSuffix(s, " ")
}

// Verdict 는 auth 가 판정한 결과다.
type Verdict struct {
	Valid      bool
	UserID     int64                // Valid 일 때만
	Passphrase streamkey.Passphrase // Valid 일 때만
	Reason     string               // 거짓일 때 MALFORMED · NOT_FOUND · REVOKED(그 밖의 값도 거절로 본다)
}

// UndecidedError 는 「판단 불가」(계약4 2절)다 — 비-200 · 시간 초과 · 연결 실패 · 해석 실패.
type UndecidedError struct {
	Kind   string // timeout · unreachable · status · parse
	Status int    // Kind == "status" 일 때
}

// Error 는 Kind 와 Status 만 싣는다. 응답 본문도, 원인 에러의 문구(요청 URL · 본문 조각을 담는다)도
// 싣지 않는다.
func (e *UndecidedError) Error() string {
	if e.Kind == kindStatus {
		return fmt.Sprintf("keyresolve: 판단 불가 — %s %d", e.Kind, e.Status)
	}
	return "keyresolve: 판단 불가 — " + e.Kind
}

// ErrInvalidPassphrase 는 auth 가 valid:true 로 답했지만 passphrase 가 형식 밖인 경우다.
var ErrInvalidPassphrase = errors.New("keyresolve: auth 가 형식 밖의 passphrase 를 돌려줬다")

// UndecidedError.Kind 의 값. 넷뿐이다.
const (
	kindTimeout     = "timeout"     // ctx 가 끝났다(시한 · 취소)
	kindUnreachable = "unreachable" // ctx 가 살아 있는데 전송이 실패했다(연결 거부 등)
	kindStatus      = "status"      // 200 이 아니다
	kindParse       = "parse"       // 200 인데 본문이 계약대로 해석되지 않는다
)

// maxBodyBytes 는 읽어 들이는 응답 본문의 상한이다(설계 2-3). 정상 응답은 100바이트 안팎이다.
const maxBodyBytes = 4 << 10

// connectTimeout 은 연결 준비 하나 — TCP 다이얼 · TLS 악수 — 의 상한이다. Go 전송은 연결 준비를 요청 ctx 의 취소에서 떼어
// 둔다(다음 요청이 그 연결을 쓸 수 있게 — net/http Transport.getConn). 그래서 auth 가 SYN 이나 TLS 를 붙잡으면 Resolve 는
// ctx 시한에 돌아와도 다이얼 고루틴과 소켓이 남고 요청마다 쌓인다 — 이 상한이 그것을 묶는다. 처리기 시한(ingestauth
// MaxDeadline 1.125초)과 바깥 가드(1.175초)보다 길게 둬, 요청의 결과(시한 안에 못 닿으면 timeout)는 그대로 ctx 가 정한다.
const connectTimeout = 2 * time.Second

// resolveRequest 는 4C 요청 본문(ResolveRequest)이다.
type resolveRequest struct {
	StreamID string `json:"streamid"`
}

// resolveResponse 는 4C 응답 본문(ResolveResponse)이다. auth 는 NON_NULL 이라 거절이면 userId ·
// passphrase 가, 허락이면 reason 이 아예 없다. 포인터 필드는 있어야 할 값이 빠졌는지 알아보려는 것이다.
type resolveResponse struct {
	Valid      *bool  `json:"valid"`
	UserID     *int64 `json:"userId"`
	Passphrase string `json:"passphrase"`
	Reason     string `json:"reason"`
}

// Resolve 는 token 으로 정규형 streamid(#!::r=<token>,m=publish)를 다시 만들어 4C 에 묻는다.
// 시한은 ctx 다. 재시도하지 않는다. 응답 본문은 로그 · 에러 어디에도 싣지 않는다.
//
// auth 가 판정을 돌려주지 못하면 에러는 *UndecidedError 다. ctx 가 끝나 답을 못 받으면 — 시한이든
// 취소든 — Kind 는 timeout 이다. auth 가 허락했지만 passphrase 가 형식 밖이면 ErrInvalidPassphrase 다.
// token 이 토큰 꼴이 아니거나 요청을 만들지 못하면(nil ctx · 다시 해석하지 못하는 끝점) auth 에 묻지 않고 그 밖의 에러를
// 돌려준다 — 그 문구에도 끝점은 없다.
func (c *Client) Resolve(ctx context.Context, token string) (Verdict, error) {
	// token 으로 streamid 를 다시 만드므로 토큰 꼴만 받는다. 쉼표가 든 값은 auth 가 다른 r 값을
	// 읽게 만들 수 있다(auth 의 StreamId.parse 는 마지막 r 을 읽는다).
	if !streamkey.IsToken(token) {
		return Verdict{}, errors.New("keyresolve: 토큰 꼴이 아닌 값은 묻지 않는다")
	}
	// 문자열 필드 하나뿐인 구조체라 Marshal 은 실패하지 않는다.
	body, _ := json.Marshal(resolveRequest{StreamID: "#!::r=" + token + ",m=publish"})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, bytes.NewReader(body))
	if err != nil {
		// NewClient 를 지나도 끝점을 다시 해석하지 못할 수 있다(IPv6 영역 id 의 이상한 글자). nil ctx 도 여기로 온다. 원인
		// 에러의 문구에는 끝점이 실리므로 싣지 않는다.
		return Verdict{}, errors.New("keyresolve: 요청을 만들지 못했다 — 끝점을 다시 해석하지 못했거나 ctx 가 nil 이다")
	}
	req.Header.Set("Content-Type", "application/json")
	c.authorize(req)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return Verdict{}, &UndecidedError{Kind: transportKind(ctx)}
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return Verdict{}, &UndecidedError{Kind: kindStatus, Status: resp.StatusCode}
	}
	// 상한보다 한 바이트 더 읽어야 넘쳤는지 알 수 있다.
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes+1))
	if err != nil {
		return Verdict{}, &UndecidedError{Kind: transportKind(ctx)}
	}
	if len(raw) > maxBodyBytes {
		return Verdict{}, &UndecidedError{Kind: kindParse}
	}
	return decodeVerdict(raw)
}

// transportKind 는 전송 실패의 Kind 다. ctx 가 끝났으면 timeout, 아니면 unreachable 이다.
func transportKind(ctx context.Context) string {
	if ctx.Err() != nil {
		return kindTimeout
	}
	return kindUnreachable
}

// decodeVerdict 는 200 응답 본문을 Verdict 로 바꾼다.
func decodeVerdict(raw []byte) (Verdict, error) {
	var r resolveResponse
	if err := json.Unmarshal(raw, &r); err != nil || r.Valid == nil {
		return Verdict{}, &UndecidedError{Kind: kindParse}
	}
	if !*r.Valid {
		return Verdict{Reason: r.Reason}, nil
	}
	// auth 는 허락에 userId 를 늘 싣는다(ResolveResult.of). 없으면 계약대로 해석되지 않은 것이다.
	if r.UserID == nil {
		return Verdict{}, &UndecidedError{Kind: kindParse}
	}
	// 없거나 null 이면 빈 문자열이라 여기서 함께 걸린다.
	p, err := streamkey.NewPassphrase(r.Passphrase)
	if err != nil {
		return Verdict{}, ErrInvalidPassphrase
	}
	return Verdict{Valid: true, UserID: *r.UserID, Passphrase: p}, nil
}
