package credrelay

import (
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
)

// 오류 응답 본문이다 — 고정 문구라 원천 오류 원문 · 경로 · 토큰이 실리지 않는다. 우리 판 SDK 는 Content-Type 이
// 정확히 application/json 일 때만 이 본문을 {code,message} 로 읽는다(endpointcreds client/middleware.go).
const (
	bodyUnauthorized     = `{"code":"Unauthorized","message":"invalid authorization token"}`
	bodyUnavailable      = `{"code":"CredentialsUnavailable","message":"instance role credentials unavailable"}`
	bodyNotFound         = `{"code":"NotFound","message":"not found"}`
	bodyMethodNotAllowed = `{"code":"MethodNotAllowed","message":"method not allowed"}`
)

// 거부의 까닭이다 — cred_denied 의 reason. 닫힌 집합이다.
const (
	denyMissing  = "missing"  // Authorization 머리가 없거나 비었다
	denyMismatch = "mismatch" // 어느 호출자의 토큰도 아니다
)

// handler 는 중계의 HTTP 계약이다(설계 2.5).
type handler struct {
	tok    Tokens
	cache  *credCache
	now    func() time.Time
	log    *slog.Logger
	denied denyLimiter
}

// NewHandler 는 중계의 HTTP 처리기를 만든다. 자격 원천은 src 하나다(운영 = NewIMDSRoleSource). 바꿀 축이 원천
// 하나뿐이라 새 인터페이스 없이 SDK 의 aws.CredentialsProvider 를 이음매로 쓴다. 시계 now 는 주입한다.
//
//   - GET /v1/credentials: 인증(원천을 부르기 전) → 맞으면 200 {AccessKeyId,SecretAccessKey,Token,Expiration} ·
//     Cache-Control no-store. Expiration 은 응답할 때마다 min(실제 만료, 응답 시각 + 60분)이다. 토큰이 없거나
//     틀리면 401, 줄 자격이 없으면 503.
//   - GET /healthz: 204(인증 · 본문 · Content-Type · 로그 없음).
//   - 그 밖 경로 404 · 다른 메서드 405.
//
// 본문이 있는 응답은 모두 Content-Type 이 정확히 application/json 이다(매개변수 없음).
func NewHandler(src aws.CredentialsProvider, tok Tokens, now func() time.Time, log *slog.Logger) http.Handler {
	return &handler{
		tok:   tok,
		cache: &credCache{src: src, now: now, log: log},
		now:   now,
		log:   log,
	}
}

func (h *handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	switch r.URL.Path {
	case HealthPath:
		if r.Method != http.MethodGet {
			writeMethodNotAllowed(w)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	case CredentialsPath:
		if r.Method != http.MethodGet {
			writeMethodNotAllowed(w)
			return
		}
		h.serveCredentials(w, r)
	default:
		writeJSON(w, http.StatusNotFound, []byte(bodyNotFound))
	}
}

// serveCredentials 는 인증한 뒤 캐시 규칙에 따라 자격을 준다.
func (h *handler) serveCredentials(w http.ResponseWriter, r *http.Request) {
	remote := remoteIP(r.RemoteAddr)
	caller, reason := h.authenticate(r)
	if reason != "" {
		if count, emit := h.denied.note(h.now()); emit {
			h.log.Warn("cred_denied", "reason", reason, "remote", remote, "count", count)
		}
		writeJSON(w, http.StatusUnauthorized, []byte(bodyUnauthorized))
		return
	}

	creds, have, refresh := h.cache.credentials(r.Context())
	t := h.now()
	if !have || creds.Expires.Sub(t) <= serveFloor {
		h.log.Error("cred_unavailable", "caller", caller, "remote", remote)
		writeJSON(w, http.StatusServiceUnavailable, []byte(bodyUnavailable))
		return
	}
	expires := creds.Expires
	if limit := t.Add(consumerRefreshCap); limit.Before(expires) {
		expires = limit
	}
	body, err := json.Marshal(servedBody{
		AccessKeyID:     creds.AccessKeyID,
		SecretAccessKey: creds.SecretAccessKey,
		Token:           creds.SessionToken,
		Expiration:      expires.UTC(),
	})
	if err != nil {
		h.log.Error("cred_unavailable", "caller", caller, "remote", remote)
		writeJSON(w, http.StatusServiceUnavailable, []byte(bodyUnavailable))
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, body)
	h.logServed(caller, remote, refresh, expires.Sub(t), creds.Expires.Sub(t))
}

// servedBody 는 200 본문이다(SDK endpointcreds 의 응답 꼴).
type servedBody struct {
	AccessKeyID     string    `json:"AccessKeyId"`
	SecretAccessKey string    `json:"SecretAccessKey"`
	Token           string    `json:"Token"`
	Expiration      time.Time `json:"Expiration"`
}

// authenticate 는 요청의 호출자를 돌려주고, 아니면 거부 까닭을 돌려준다.
func (h *handler) authenticate(r *http.Request) (caller, reason string) {
	auth := r.Header.Get("Authorization")
	if auth == "" {
		return "", denyMissing
	}
	caller, ok := h.tok.Match(auth)
	if !ok {
		return "", denyMismatch
	}
	return caller, ""
}

// logServed 는 cred_served 를 낸다 — 자격 필드는 싣지 않는다. expires_in_s 는 보낸 Expiration 까지(≤ 3600),
// source_expires_in_s 는 실제 남은 수명이다. 이 요청이 원천을 다시 읽어 받았으면 from=imds 와 그 까닭 refresh 를,
// 아니면 from=cache 를 싣는다.
func (h *handler) logServed(caller, remote, refresh string, sent, source time.Duration) {
	attrs := []any{"caller", caller, "expires_in_s", int64(sent / time.Second), "source_expires_in_s", int64(source / time.Second)}
	if refresh != "" {
		attrs = append(attrs, "from", "imds", "refresh", refresh)
	} else {
		attrs = append(attrs, "from", "cache")
	}
	h.log.Info("cred_served", append(attrs, "remote", remote)...)
}

// writeJSON 은 본문이 있는 응답을 쓰는 하나뿐인 길이다. Content-Type 을 정확히 application/json 으로 둔다.
func writeJSON(w http.ResponseWriter, status int, body []byte) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func writeMethodNotAllowed(w http.ResponseWriter) {
	w.Header().Set("Allow", http.MethodGet)
	writeJSON(w, http.StatusMethodNotAllowed, []byte(bodyMethodNotAllowed))
}

// remoteIP 는 RemoteAddr 의 주소 부분이다(꼴 밖이면 그대로).
func remoteIP(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	return host
}
