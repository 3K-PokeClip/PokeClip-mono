package expiry

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

// TokenHeader 는 clip 만료 창구 전용 토큰 머리다(clip ExpiredBroadcastSecurityConfig — 공용 토큰과 다른 체인).
const TokenHeader = "X-Expired-Broadcasts-Token"

// expiredPath 는 clip 만료 창구 경로다(계약-세그먼트인덱스 6-2).
const expiredPath = "/internal/broadcasts/expired"

// Broadcast 는 만료된 방송 한 줄이다 — 만료가 쓰는 칸만 받는다(계약 6-2 칸 일곱 가운데 넷).
type Broadcast struct {
	StreamID       string     `json:"streamId"`
	IngestStreamID string     `json:"ingestStreamId"`
	SegmentsFrom   *time.Time `json:"segmentsFrom"`
	SegmentsUntil  *time.Time `json:"segmentsUntil"`
}

// expiredPage 는 창구 응답 한 장이다.
type expiredPage struct {
	Broadcasts []Broadcast `json:"broadcasts"`
	NextCursor *string     `json:"nextCursor"`
	HasMore    bool        `json:"hasMore"`
}

// pollError 는 창구 요청 실패다. status 는 HTTP 상태(응답 전 실패면 "timeout" · "transport" · "body")다.
type pollError struct {
	status string
	err    error
}

func (e *pollError) Error() string {
	return fmt.Sprintf("만료 창구 요청 실패 status=%s: %v", e.status, e.err)
}
func (e *pollError) Unwrap() error { return e.err }

// clipClient 는 clip 만료 창구 클라이언트다. 리다이렉트를 따르지 않고(3xx = 실패) · 요청마다 시한 · 본문 상한을 건다(D20).
type clipClient struct {
	base      string
	token     Token
	http      *http.Client
	bodyLimit int64
}

func newClipClient(base string, token Token, timeout time.Duration, bodyLimit int64) *clipClient {
	return &clipClient{
		base:  base,
		token: token,
		http: &http.Client{
			Timeout: timeout,
			// 기본 전송은 HTTP_PROXY 같은 환경변수의 프록시를 탄다. 이 전송은 Proxy 가 nil 이라 clip 에 바로 붙는다 —
			// 전용 토큰 머리가 프록시를 거치지 않는다(보안 LOW-1).
			Transport: &http.Transport{},
			// 토큰을 다른 곳으로 옮겨 싣지 않는다 — 3xx 는 그대로 받아 실패로 다룬다.
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		bodyLimit: bodyLimit,
	}
}

// list 는 before(UTC) 기준 한 장을 받는다. cursor 가 nil 이면 처음부터다.
func (c *clipClient) list(ctx context.Context, before time.Time, cursor *string) (expiredPage, error) {
	q := url.Values{"before": {before.UTC().Format(time.RFC3339)}}
	if cursor != nil {
		q.Set("cursor", *cursor)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+expiredPath+"?"+q.Encode(), nil)
	if err != nil {
		return expiredPage{}, &pollError{status: "request", err: err}
	}
	req.Header.Set(TokenHeader, c.token.reveal())
	resp, err := c.http.Do(req)
	if err != nil {
		status := "transport"
		var ue *url.Error
		if errors.As(err, &ue) && ue.Timeout() {
			status = "timeout"
		}
		return expiredPage{}, &pollError{status: status, err: err}
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return expiredPage{}, &pollError{status: strconv.Itoa(resp.StatusCode), err: errors.New(resp.Status)}
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, c.bodyLimit+1))
	if err != nil {
		return expiredPage{}, &pollError{status: "body", err: err}
	}
	if int64(len(body)) > c.bodyLimit {
		return expiredPage{}, &pollError{status: "body", err: fmt.Errorf("본문이 상한 %d 바이트를 넘는다", c.bodyLimit)}
	}
	var page expiredPage
	if err := json.Unmarshal(body, &page); err != nil {
		return expiredPage{}, &pollError{status: "body", err: fmt.Errorf("본문 해석 실패: %w", err)}
	}
	if err := page.checkAdvance(cursor); err != nil {
		return expiredPage{}, &pollError{status: "body", err: err}
	}
	return page, nil
}

// checkAdvance 는 hasMore 인 장이 앞으로 나아가는가다 — 빈 장이거나 표시가 없거나 보낸 표시 그대로면 같은 장을
// 끝없이 다시 부르게 된다. 그런 응답은 그 회차의 실패다.
func (p expiredPage) checkAdvance(sent *string) error {
	switch {
	case !p.HasMore:
		return nil
	case len(p.Broadcasts) == 0:
		return errors.New("hasMore 인데 빈 장이다")
	case p.NextCursor == nil || (sent != nil && *p.NextCursor == *sent):
		return errors.New("hasMore 인데 표시가 나아가지 않는다")
	}
	return nil
}
