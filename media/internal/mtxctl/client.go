// Package mtxctl 은 MediaMTX Control API 가운데 긴급 절단(ingestauth.Cut)이 쓰는 SRT 연결 목록 · 조회 · kick 의
// 클라이언트다(설계 2-5). 경로 설정은 쓰지 않는다. 경로 상태 폴러(mtxstate)와는 목적이 다르다.
//
// 이 패키지의 에러에는 응답 본문도 자격도 실리지 않는다 — 요청 갈래 · 실패 갈래 · HTTP 상태만 싣는다(설계 2-5 ·
// 6절 누설 규칙). 로거도 두지 않는다 — 무엇을 기록할지는 부르는 쪽이 정한다.
package mtxctl

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Control API 의 SRT 연결 경로다(포크 v1.21.1-pokeclip.2 internal/api/api.go 101 · 218–220행 — 그룹 접두 /v3).
const (
	listPath = "/v3/srtconns/list"
	getPath  = "/v3/srtconns/get/"
	kickPath = "/v3/srtconns/kick/"
)

// listItemsPerPage 는 목록 요청의 itemsPerPage 다. 원본은 31비트까지 받으므로(api/paginate.go 35행) 그 최댓값이면
// 어떤 목록도 한 페이지다.
const listItemsPerPage = 1<<31 - 1

// maxListBytes 는 읽어 들이는 목록 본문의 상한이다 — 보장하는 것은 이 상한뿐이다. 항목 하나(APISRTConn 61필드)는 측정
// 샘플로 약 2.4 KB 라 약 2만 7천 연결 몫이지만, query 같은 가변 문자열이 길면 항목이 커져 그보다 적다. 넘으면 목록이
// 실패한다(Kind parse).
const maxListBytes = 64 << 20

// connectTimeout 은 연결 준비 하나 — TCP 다이얼 · TLS 악수 — 의 상한이다. Go 전송은 연결 준비를 요청 ctx 의 취소에서 떼어
// 둔다(net/http Transport.getConn). 상한이 없으면 Control API 가 SYN 이나 TLS 를 붙잡을 때 요청은 부르는 쪽 ctx(cut 은 전체
// 시한 2분)가 끝나야 돌아오고, 연결 준비는 그 뒤에도 남는다. 제어망 안이라 정상 다이얼은 밀리초 안이다 — 2초면 cut 전체
// 시한의 작은 몫으로 닿지 않는 Control API 를 unreachable 로 가른다.
const connectTimeout = 2 * time.Second

// maxDrainBytes 는 조회 · kick 응답 본문을 비울 때 읽는 상한이다. 비워야 전송이 TCP 연결을 다시 쓴다 — 절단의 확인은
// 같은 id 를 여러 번 조회한다.
const maxDrainBytes = 64 << 10

// connIDPattern 은 MediaMTX 연결 id 의 꼴이다 — uuid.UUID 의 JSON(소문자 16진 8-4-4-4-12).
var connIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// Options 는 NewClient 의 설정이다. Pass 는 Secret 이라 Options 를 fmt(값과 맞지 않는 동사 포함) · slog · encoding/json
// 어디로 찍어도 — 다른 값의 비공개 필드로 담아 찍어도 — 비번이 나오지 않는다.
type Options struct {
	APIURL string // http://<A_mtx>:9997 — IP 리터럴. 경로 · 사용자 정보 · 질의 · 조각은 받지 않는다
	User   string // Control API 의 Basic 자격 — MediaMTX 가 응답기의 action=api 행에 묻는다. ':' 를 담을 수 없다
	Pass   Secret `json:"-"`
}

// Format 은 o 를 Pass 없이 쓴다. 동사와 무관하게 같은 꼴이다.
func (o Options) Format(f fmt.State, _ rune) {
	fmt.Fprintf(f, "mtxctl.Options{APIURL:%q, User:%q, Pass:[REDACTED]}", o.APIURL, o.User)
}

// Client 는 Control API 의 SRT 연결 목록 · 조회 · kick 을 부른다. 여러 고루틴이 함께 써도 된다. 자격은 요청에 싣는
// 함수 안에만 있어서 Client 를 어떻게 찍어도 나오지 않는다.
type Client struct {
	base       url.URL             // Scheme · Host 만 있다. 요청마다 Path · RawQuery 를 채운 사본을 쓴다
	authorize  func(*http.Request) // 요청에 Basic 자격을 싣는다
	httpClient *http.Client
}

// NewClient 는 opt 를 검사해 Client 를 만든다. User · Pass 가 비었거나 User 에 ':' 가 있으면(Basic 은 첫 ':' 에서
// 사용자와 비번을 가른다 — RFC 7617 2절), APIURL 이 IP 리터럴 호스트의 http · https URL 이 아니거나 경로 · 사용자
// 정보 · 질의 · 조각이 있으면 에러다. 호스트 이름을 받지 않는 것은 Basic 자격이 DNS 가 가리키는 곳으로 가지 않게
// 하려는 것이다. 에러에는 설정값을 싣지 않는다 — 자리를 잘못 넣으면 APIURL 자리에 비번이 들어갈 수 있다.
func NewClient(opt Options) (*Client, error) {
	switch {
	case opt.User == "" || opt.Pass.reveal() == "":
		return nil, errors.New("mtxctl: Control API 자격(User · Pass)이 비었다")
	case strings.Contains(opt.User, ":"):
		return nil, errors.New("mtxctl: User 에 ':' 가 있다 — Basic 자격은 첫 ':' 에서 사용자와 비번을 가른다")
	}
	base, err := parseAPIURL(opt.APIURL)
	if err != nil {
		return nil, err
	}
	user, pass := opt.User, opt.Pass.reveal()
	return &Client{
		base:      base,
		authorize: func(r *http.Request) { r.SetBasicAuth(user, pass) },
		// Timeout 을 두지 않는다 — 시한은 부르는 쪽 ctx 하나다.
		httpClient: &http.Client{
			// 이 전송은 Proxy 가 nil 이라 환경 변수의 프록시를 타지 않는다 — Basic 자격이 프록시를 거치지
			// 않고, 응답기의 api 행이 판정하는 출발지(SelfIP)도 바뀌지 않는다. 연결은 다시 쓴다. 연결 준비에는
			// connectTimeout 상한을 둔다.
			Transport: &http.Transport{
				DialContext:         (&net.Dialer{Timeout: connectTimeout}).DialContext,
				TLSHandshakeTimeout: connectTimeout,
			},
			// 리다이렉트를 따르지 않는다. 따라가면 Go 는 같은 호스트에 Authorization 을 다시 싣는다. 3xx 는 그 상태의
			// 에러(Kind status)가 된다.
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}, nil
}

// parseAPIURL 은 APIURL 을 검사해 Scheme · Host 만 남긴 URL 을 돌려준다. 그 URL 로 만든 끝점을 다시 해석하지
// 못하면(IPv6 영역 id 의 이상한 글자) 에러다.
func parseAPIURL(raw string) (url.URL, error) {
	if raw == "" {
		return url.URL{}, errors.New("mtxctl: APIURL 이 비었다")
	}
	errForm := errors.New("mtxctl: APIURL 은 IP 리터럴 호스트의 http · https URL 이어야 한다(경로 · 사용자 정보 · 질의 · 조각 없음)")
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Opaque != "" || u.User != nil ||
		(u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
		return url.URL{}, errForm
	}
	if _, err := netip.ParseAddr(u.Hostname()); err != nil {
		return url.URL{}, errForm
	}
	base := url.URL{Scheme: u.Scheme, Host: u.Host}
	if _, err := url.Parse(endpoint(base, listPath, "")); err != nil {
		return url.URL{}, errForm
	}
	return base, nil
}

// endpoint 는 base 에 path · rawQuery 를 채운 URL 이다. path 를 걷지 않는다 — url.JoinPath 처럼 '..' 을 걷으면 다른
// 엔드포인트가 된다.
func endpoint(base url.URL, path, rawQuery string) string {
	base.Path, base.RawQuery = path, rawQuery
	return base.String()
}

// SRTConn 은 SRT 연결 하나다 — Control API 의 APISRTConn(defs/api_srt.go) 가운데 절단이 보는 넷이다.
type SRTConn struct {
	ID      string    `json:"id"`
	Created time.Time `json:"created"` // 연결 객체가 생긴 시각 — 인증 전부터 있다(srt/conn.go 75행)
	Path    string    `json:"path"`    // 등록 전(인증 대기 · 판독 중) 연결은 빈 값(srt/conn.go 254–256행 — state 와 함께 정한다)
	State   string    `json:"state"`   // idle · read · publish
}

// ErrNotFound 는 KickSRTConn 이 kick 하려던 연결이 없다는 것이다(본문이 Control API 의 에러 꼴인 404) — 이미 닫혔거나
// kick 됐다. 경로가 없는 404 는 ErrNotFound 가 아니라 *Error(Kind status · Status 404)다.
var ErrNotFound = errors.New("mtxctl: 그 연결이 없다")

// errInvalidID 는 조회 · kick 의 id 가 MediaMTX 연결 id 꼴이 아니라는 것이다. 요청을 내지 않는다.
var errInvalidID = errors.New("mtxctl: 연결 id 가 MediaMTX 의 꼴(소문자 UUID)이 아니다 — 요청을 내지 않았다")

// Error 는 Control API 요청 하나가 기대한 답으로 끝나지 않은 것이다. 이 패키지의 요청 메서드가 돌려주는 에러는
// ErrNotFound 와 id 꼴 에러를 빼면 모두 *Error 다. Status 404 는 경로가 없다는 것이다 — 조회 · kick 의 연결 없음
// 404(본문이 Control API 의 에러 꼴)는 *Error 가 아니다(SRTConnExists 는 거짓, KickSRTConn 은 ErrNotFound).
type Error struct {
	Op     string // list · get · kick
	Kind   string // timeout · unreachable · status · parse · pages
	Status int    // Kind 가 status 일 때 — 기대 밖 HTTP 상태. 401 은 Control API 인증 실패(응답기 부재 · 자격 불일치)다
}

// Error 는 Op · Kind · Status 만 싣는다. 응답 본문도, 원인 에러의 문구(요청 URL 을 담는다)도 싣지 않는다.
func (e *Error) Error() string {
	if e.Kind == kindStatus {
		return fmt.Sprintf("mtxctl: %s — HTTP %d", e.Op, e.Status)
	}
	return "mtxctl: " + e.Op + " — " + e.Kind
}

// Error.Op 의 값이다.
const (
	opList = "list"
	opGet  = "get"
	opKick = "kick"
)

// Error.Kind 의 값이다. 다섯뿐이다.
const (
	kindTimeout     = "timeout"     // ctx 가 끝나 답을 못 받았다(시한 · 취소)
	kindUnreachable = "unreachable" // ctx 가 살아 있는데 전송이 실패했다(연결 거부 등)
	kindStatus      = "status"      // 기대 밖 HTTP 상태
	kindParse       = "parse"       // 목록 본문이 계약대로 해석되지 않는다(크기 상한 포함)
	kindPages       = "pages"       // 목록이 여러 페이지다 — 한 순간의 연결 집합이 아니다
)

// listResponse 는 GET /v3/srtconns/list 의 응답(APISRTConnList)이다. 한 페이지인지는 pageCount 로 판정한다(mtxstate 와
// 같은 판단). itemCount 는 원본이 페이지를 나누기 전의 전체 수다(api_srt.go 21행) — items 의 수와 같아야 잘리지 않은
// 목록이다.
type listResponse struct {
	ItemCount int       `json:"itemCount"`
	PageCount int       `json:"pageCount"`
	Items     []SRTConn `json:"items"`
}

// SRTConns 는 GET /v3/srtconns/list 로 SRT 연결 목록을 한 번에 받는다. itemsPerPage 를 받을 수 있는 가장 큰 값으로
// 실어 한 페이지로 받는다 — 목록은 created 순이라(srt/server.go 217–219행) 여러 페이지로 받으면 페이지 사이에 사라진
// 연결 때문에 뒤 항목이 앞으로 밀려 하나가 건너뛰어질 수 있다. 원본은 목록을 SRT 서버 루프 안에서 한 번에 만들므로
// (srt/server.go 208–221행) 한 페이지는 그 순간의 연결 집합 그대로다.
//
// 돌아온 pageCount 가 1 보다 크면 에러(Kind pages)다. items 가 없거나, itemCount 가 items 의 수와 다르거나, 항목이 계약
// 꼴(validConn)이 아니면 에러(Kind parse)다 — 판 올림으로 꼴이 바뀐 목록을 그대로 쓰면 절단의 갈래가 연결을 빠뜨린다.
func (c *Client) SRTConns(ctx context.Context) ([]SRTConn, error) {
	resp, err := c.do(ctx, opList, http.MethodGet, listPath, "itemsPerPage="+strconv.Itoa(listItemsPerPage))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, &Error{Op: opList, Kind: kindStatus, Status: resp.StatusCode}
	}
	// 상한보다 한 바이트 더 읽어야 넘쳤는지 알 수 있다.
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxListBytes+1))
	if err != nil {
		return nil, &Error{Op: opList, Kind: transportKind(ctx)}
	}
	if len(raw) > maxListBytes {
		return nil, &Error{Op: opList, Kind: kindParse}
	}
	return decodeList(raw)
}

// decodeList 는 목록 본문을 해석하고 한 페이지인지 · 잘리지 않았는지 · 항목이 계약 꼴인지 본다.
func decodeList(raw []byte) ([]SRTConn, error) {
	var list *listResponse
	if err := json.Unmarshal(raw, &list); err != nil || list == nil || list.Items == nil {
		return nil, &Error{Op: opList, Kind: kindParse}
	}
	if list.PageCount > 1 {
		return nil, &Error{Op: opList, Kind: kindPages}
	}
	if list.ItemCount != len(list.Items) {
		return nil, &Error{Op: opList, Kind: kindParse}
	}
	for _, conn := range list.Items {
		if !validConn(conn) {
			return nil, &Error{Op: opList, Kind: kindParse}
		}
	}
	return list.Items, nil
}

// validConn 은 목록 항목 c 가 계약 꼴인지 본다 — id(소문자 UUID) · created 가 있고, state 가 idle · read · publish 가운데
// 하나이며, idle 일 때만 path 가 빈다(srt/conn.go 72–77 · 254–259 · 323–329행 — 원본은 state 와 path 를 함께 바꾼다).
func validConn(c SRTConn) bool {
	if !connIDPattern.MatchString(c.ID) || c.Created.IsZero() {
		return false
	}
	switch c.State {
	case "idle":
		return c.Path == ""
	case "read", "publish":
		return c.Path != ""
	}
	return false
}

// SRTConnExists 는 GET /v3/srtconns/get/{id} 로 그 연결이 아직 있는지 본다 — 200 이면 참, 연결 없음 404(본문이 Control
// API 의 에러 꼴 — isAPIError)면 거짓이다. 그 밖의 상태는 경로가 없는 404 를 포함해 에러(Kind status)다. id 가 MediaMTX
// 연결 id 꼴(소문자 UUID)이 아니면 요청 없이 에러다.
func (c *Client) SRTConnExists(ctx context.Context, id string) (bool, error) {
	if !connIDPattern.MatchString(id) {
		return false, errInvalidID
	}
	resp, err := c.do(ctx, opGet, http.MethodGet, getPath+id, "")
	if err != nil {
		return false, err
	}
	defer drainClose(resp.Body)
	switch resp.StatusCode {
	case http.StatusOK:
		return true, nil
	case http.StatusNotFound:
		if isAPIError(resp.Body) {
			return false, nil
		}
	}
	return false, &Error{Op: opGet, Kind: kindStatus, Status: resp.StatusCode}
}

// KickSRTConn 은 POST /v3/srtconns/kick/{id} 로 그 연결을 끊는다. 원본은 목록에서 곧바로 지우고 연결의 ctx 를
// 끊는다(srt/server.go 232–241행). 연결 없음 404(본문이 Control API 의 에러 꼴 — isAPIError)면 ErrNotFound 다. 그 밖의
// 상태는 경로가 없는 404 를 포함해 에러(Kind status)다. id 가 MediaMTX 연결 id 꼴(소문자 UUID)이 아니면 요청 없이
// 에러다 — ErrNotFound 가 아니다.
func (c *Client) KickSRTConn(ctx context.Context, id string) error {
	if !connIDPattern.MatchString(id) {
		return errInvalidID
	}
	resp, err := c.do(ctx, opKick, http.MethodPost, kickPath+id, "")
	if err != nil {
		return err
	}
	defer drainClose(resp.Body)
	switch resp.StatusCode {
	case http.StatusOK:
		return nil
	case http.StatusNotFound:
		if isAPIError(resp.Body) {
			return ErrNotFound
		}
	}
	return &Error{Op: opKick, Kind: kindStatus, Status: resp.StatusCode}
}

// isAPIError 는 응답 본문이 Control API 의 에러 꼴({"status":"error","error":…} — api.go 284–300행 writeError)인지
// 본다. 원본이 연결 없음에 내는 404 는 이 꼴이고(api_srt.go 41–42 · 61–62행), 경로가 없거나 메서드가 다를 때 gin 이
// 내는 기본 404(text/plain 「404 page not found」 — gin.go 759행)는 아니다. 그래서 404 를 「연결 없음」으로 읽는 것은
// 이 꼴일 때뿐이다 — 판 올림으로 조회 · kick 경로가 사라진 것을 「없음」으로 읽으면 끊지 못한 연결이 남는데 절단이
// 끝까지 끊었다고 본다. 본문은 maxDrainBytes 까지만 읽고(넘으면 꼴이 아니다), 에러 문구는 보지도 싣지도 않는다.
func isAPIError(body io.Reader) bool {
	raw, err := io.ReadAll(io.LimitReader(body, maxDrainBytes))
	if err != nil {
		return false
	}
	var e struct {
		Status string `json:"status"`
	}
	return json.Unmarshal(raw, &e) == nil && e.Status == "error"
}

// do 는 Basic 자격을 실은 요청 하나를 보내고 응답을 돌려준다. 전송이 실패하면 *Error(timeout · unreachable)다.
func (c *Client) do(ctx context.Context, op, method, path, rawQuery string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, endpoint(c.base, path, rawQuery), nil)
	if err != nil {
		// NewClient 가 끝점을 다시 해석해 봤으므로 남는 것은 nil ctx 같은 부르는 쪽 결함이다. 문구에 URL 이
		// 실리므로 갈래만 남긴다.
		return nil, &Error{Op: op, Kind: kindUnreachable}
	}
	c.authorize(req)
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, &Error{Op: op, Kind: transportKind(ctx)}
	}
	return resp, nil
}

// transportKind 는 전송 실패의 Kind 다. ctx 가 끝났으면 timeout, 아니면 unreachable 이다.
func transportKind(ctx context.Context) string {
	if ctx.Err() != nil {
		return kindTimeout
	}
	return kindUnreachable
}

// drainClose 는 본문을 maxDrainBytes 까지 비우고 닫는다. 비우기 · 닫기가 실패하면 그 연결을 다시 쓰지 못할 뿐이고
// 답은 이미 상태 코드로 났다 — 그래서 에러를 버린다.
func drainClose(body io.ReadCloser) {
	_, _ = io.Copy(io.Discard, io.LimitReader(body, maxDrainBytes))
	_ = body.Close()
}
