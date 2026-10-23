package main

import (
	"crypto/subtle"
	"encoding/json"
	"flag"
	"io"
	"log"
	"net/http"
	"strings"
	"sync/atomic"
)

// resolvestub 은 송출 인증 계약 시험 층 B 의 가짜 4C(auth 서비스의 POST /internal/stream-keys/resolve)다.
// 실물 응답기가 prod 창구(STREAM_KEY_RESOLVE_CHANNEL=internal-token)로 부르는 꼴을 받는다:
//
//	X-Internal-Token 이 정한 값과 다름 → 401(본문 없음)
//	본문 {"streamid":"#!::r=<토큰>,m=publish"} 의 토큰이 정한 토큰 → 200 {"valid":true,"userId":1,"passphrase":"<P>"}
//	그 밖의 토큰                                                → 200 {"valid":false,"reason":"NOT_FOUND"}
//	본문을 읽지 못함                                            → 400
//
// 관리 포트 GET /counts 는 결과별 호출 수(unauthorized · valid · not_found · bad_request)를 낸다.
// 출입증 · passphrase · 토큰은 로그에 남기지 않는다.

const resolvePath = "/internal/stream-keys/resolve"

// resolveCounts 는 결과별 호출 수다. 처리기와 관리 포트가 함께 읽고 쓴다.
type resolveCounts struct {
	Unauthorized atomic.Int64
	Valid        atomic.Int64
	NotFound     atomic.Int64
	BadRequest   atomic.Int64
}

// resolveStub 은 대조할 출입증과 토큰 하나, 그 토큰의 passphrase 를 쥔다.
type resolveStub struct {
	internalToken string
	streamToken   string
	passphrase    string
	counts        resolveCounts
}

// runResolvestub 은 4C 포트와 관리 포트를 띄우고 종료 신호로 끝난다.
func runResolvestub(args []string) int {
	fs := flag.NewFlagSet("resolvestub", flag.ContinueOnError)
	listen := fs.String("listen", ":28082", "4C address (host:port)")
	admin := fs.String("admin", "127.0.0.1:28199", "admin address (host:port)")
	internalToken := fs.String("internal_token", "", "expected X-Internal-Token value")
	streamToken := fs.String("stream_token", "", "the one stream token that resolves as valid")
	passphrase := fs.String("passphrase", "", "passphrase returned for the stream token")
	if err := fs.Parse(args); err != nil {
		return exitUsage
	}
	if err := validateAddrs(*listen, *admin); err != nil {
		log.Printf("resolvestub: %v", err)
		return exitUsage
	}
	if *internalToken == "" || *streamToken == "" || *passphrase == "" {
		log.Print("resolvestub: -internal_token · -stream_token · -passphrase 가 모두 있어야 한다")
		return exitUsage
	}
	stub := &resolveStub{
		internalToken: *internalToken,
		streamToken:   *streamToken,
		passphrase:    *passphrase,
	}
	servers := []*http.Server{
		newServer(*listen, stub.resolveHandler()),
		newServer(*admin, stub.adminHandler()),
	}
	return serveUntilDone(servers, nil)
}

// resolveHandler 는 4C 요청에 답한다.
func (s *resolveStub) resolveHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST "+resolvePath, func(w http.ResponseWriter, r *http.Request) {
		got := r.Header.Get("X-Internal-Token")
		if subtle.ConstantTimeCompare([]byte(got), []byte(s.internalToken)) != 1 {
			s.counts.Unauthorized.Add(1)
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		token, ok := readResolveToken(w, r)
		if !ok {
			s.counts.BadRequest.Add(1)
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if token != s.streamToken {
			s.counts.NotFound.Add(1)
			writeJSON(w, map[string]any{"valid": false, "reason": "NOT_FOUND"})
			return
		}
		s.counts.Valid.Add(1)
		writeJSON(w, map[string]any{"valid": true, "userId": 1, "passphrase": s.passphrase})
	})
	return mux
}

// readResolveToken 은 본문의 streamid(#!::r=<토큰>,m=publish)에서 토큰을 꺼낸다.
func readResolveToken(w http.ResponseWriter, r *http.Request) (string, bool) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err != nil {
		return "", false
	}
	var req struct {
		StreamID string `json:"streamid"`
	}
	if err := json.Unmarshal(body, &req); err != nil {
		return "", false
	}
	rest, ok := strings.CutPrefix(req.StreamID, "#!::r=")
	if !ok {
		return "", false
	}
	token, mode, ok := strings.Cut(rest, ",")
	if !ok || mode != "m=publish" {
		return "", false
	}
	return token, true
}

// adminHandler 는 결과별 호출 수를 낸다.
func (s *resolveStub) adminHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /counts", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, map[string]int64{
			"unauthorized": s.counts.Unauthorized.Load(),
			"valid":        s.counts.Valid.Load(),
			"not_found":    s.counts.NotFound.Load(),
			"bad_request":  s.counts.BadRequest.Load(),
		})
	})
	return mux
}
