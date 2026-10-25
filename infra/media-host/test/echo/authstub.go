package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"sync"
)

// authstub 은 송출 인증 계약 시험(ingest_auth_contract.sh) 층 A 의 가짜 응답기다. MediaMTX 의 HTTP 인증
// 요청(POST /mediamtx/auth)에 정해 둔 꼴로만 답하고, 받은 요청의 판정 재료(action · protocol · path ·
// ip · Authorization · 길이)를 기억해 관리 포트로 내준다. 판정 규칙은 응답기 계약
// (media/cmd/ingest-auth/CONTRACT.md 1-2)의 겉모습만 흉내 낸다:
//
//	publish  allow       → 200 {"srtPassphrase":"<P>"}
//	         allow-empty → 200 빈 본문(passphrase 없는 허용 — MediaMTX 가 경로 잠금값으로 떨어지는지 본다)
//	         deny        → 401
//	read     deny 면 401, 아니면 200 빈 본문(응답기의 SRT read 와 같은 꼴 — 읽기 잠금으로 떨어진다)
//	api      늘 200(층 A 는 관리 API 판정자를 시험하지 않는다 — 층 B 의 실물 응답기 몫)
//	그 밖    401
//
// 관리 포트: GET /records(기억한 요청 — JSON 배열) · POST /mode?m=<allow|allow-empty|deny> · GET /healthz.

const (
	authStubPath = "/mediamtx/auth"
	// maxAuthStubRecords 는 기억할 요청 수의 상한이다. 넘으면 오래된 것부터 버린다.
	maxAuthStubRecords = 512
)

// 응답 모드다. 관리 포트 POST /mode 가 바꾼다.
const (
	modeAllow      = "allow"
	modeAllowEmpty = "allow-empty"
	modeDeny       = "deny"
)

// authRequest 는 MediaMTX 가 보내는 인증 본문 가운데 판정 · 기록에 쓰는 필드다(포크 internal/auth/manager.go
// authenticateHTTP 의 이름).
type authRequest struct {
	IP        string `json:"ip"`
	Action    string `json:"action"`
	Path      string `json:"path"`
	Protocol  string `json:"protocol"`
	Query     string `json:"query"`
	UserAgent string `json:"userAgent"`
}

// authRecord 는 기억한 요청 하나다. 시험이 jq 로 이 이름들을 읽는다. 본문 원문은 싣지 않고 길이만 싣는다.
type authRecord struct {
	Action         string `json:"action"`
	Protocol       string `json:"protocol"`
	Path           string `json:"path"`
	IP             string `json:"ip"`
	Authorization  string `json:"authorization"`
	BodyBytes      int    `json:"body_bytes"`
	QueryBytes     int    `json:"query_bytes"`
	UserAgentBytes int    `json:"user_agent_bytes"`
	Status         int    `json:"status"`
}

// authStub 은 모드와 기억한 요청을 쥔다. 요청 처리기와 관리 포트가 함께 쓰므로 잠금으로 묶는다.
type authStub struct {
	passphrase string

	mu      sync.Mutex
	mode    string
	records []authRecord
}

// runAuthstub 은 인증 포트와 관리 포트를 띄우고 종료 신호로 끝난다.
func runAuthstub(args []string) int {
	fs := flag.NewFlagSet("authstub", flag.ContinueOnError)
	listen := fs.String("listen", "127.0.0.1:8080", "auth address (host:port)")
	admin := fs.String("admin", "127.0.0.1:28199", "admin address (host:port)")
	passphrase := fs.String("passphrase", "", "srtPassphrase returned in allow mode")
	mode := fs.String("mode", modeAllow, "initial mode: allow, allow-empty or deny")
	if err := fs.Parse(args); err != nil {
		return exitUsage
	}
	if err := validateAddrs(*listen, *admin); err != nil {
		log.Printf("authstub: %v", err)
		return exitUsage
	}
	if *passphrase == "" || !validMode(*mode) {
		log.Print("authstub: -passphrase 가 비었거나 -mode 가 allow · allow-empty · deny 가 아니다")
		return exitUsage
	}
	stub := &authStub{passphrase: *passphrase, mode: *mode}
	servers := []*http.Server{
		newServer(*listen, stub.authHandler()),
		newServer(*admin, stub.adminHandler()),
	}
	return serveUntilDone(servers, nil)
}

func validMode(m string) bool {
	return m == modeAllow || m == modeAllowEmpty || m == modeDeny
}

// validateAddrs 는 host:port 주소들이 모두 읽히고 포트가 범위 안인지 본다.
func validateAddrs(addrs ...string) error {
	for _, addr := range addrs {
		_, port, err := net.SplitHostPort(addr)
		if err != nil {
			return fmt.Errorf("주소 %q 를 읽지 못했다: %w", addr, err)
		}
		if _, err := parsePorts(port); err != nil {
			return fmt.Errorf("주소 %q: %w", addr, err)
		}
	}
	return nil
}

// authHandler 는 인증 요청에 모드대로 답하고 요청을 기억한다. 요청 내용은 로그에 남기지 않는다.
func (s *authStub) authHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST "+authStubPath, func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
		if err != nil {
			w.WriteHeader(http.StatusRequestEntityTooLarge)
			return
		}
		var req authRequest
		if err := json.Unmarshal(body, &req); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		status, reply := s.decide(req.Action)
		s.remember(authRecord{
			Action:         req.Action,
			Protocol:       req.Protocol,
			Path:           req.Path,
			IP:             req.IP,
			Authorization:  r.Header.Get("Authorization"),
			BodyBytes:      len(body),
			QueryBytes:     len(req.Query),
			UserAgentBytes: len(req.UserAgent),
			Status:         status,
		})
		writeAuthReply(w, status, reply)
	})
	return mux
}

// decide 는 지금 모드에서 action 의 답(상태 코드 · 본문)을 고른다. 본문이 nil 이면 빈 본문이다.
func (s *authStub) decide(action string) (int, []byte) {
	s.mu.Lock()
	mode := s.mode
	s.mu.Unlock()
	switch action {
	case "api":
		return http.StatusOK, nil
	case "read":
		if mode == modeDeny {
			return http.StatusUnauthorized, nil
		}
		return http.StatusOK, nil
	case "publish":
		return s.decidePublish(mode)
	}
	return http.StatusUnauthorized, nil
}

func (s *authStub) decidePublish(mode string) (int, []byte) {
	switch mode {
	case modeAllow:
		reply, err := json.Marshal(map[string]string{"srtPassphrase": s.passphrase})
		if err != nil {
			return http.StatusInternalServerError, nil
		}
		return http.StatusOK, reply
	case modeAllowEmpty:
		return http.StatusOK, nil
	}
	return http.StatusUnauthorized, nil
}

func writeAuthReply(w http.ResponseWriter, status int, reply []byte) {
	if reply == nil {
		w.WriteHeader(status)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if _, err := w.Write(reply); err != nil {
		log.Printf("authstub: 응답 쓰기 실패: %v", err)
	}
}

func (s *authStub) remember(rec authRecord) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.records) == maxAuthStubRecords {
		s.records = append(s.records[:0:0], s.records[1:]...)
	}
	s.records = append(s.records, rec)
}

// adminHandler 는 시험이 `docker compose exec … fetch` 로 부르는 관리 경로를 낸다.
func (s *authStub) adminHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /records", func(w http.ResponseWriter, _ *http.Request) {
		s.mu.Lock()
		snapshot := append([]authRecord{}, s.records...)
		s.mu.Unlock()
		writeJSON(w, snapshot)
	})
	mux.HandleFunc("POST /mode", func(w http.ResponseWriter, r *http.Request) {
		m := r.URL.Query().Get("m")
		if !validMode(m) {
			http.Error(w, "m must be allow, allow-empty or deny", http.StatusBadRequest)
			return
		}
		s.mu.Lock()
		s.mode = m
		s.mu.Unlock()
		if _, err := fmt.Fprintf(w, "mode=%s\n", m); err != nil {
			log.Printf("authstub: /mode 쓰기 실패: %v", err)
		}
	})
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		if _, err := io.WriteString(w, "ok\n"); err != nil {
			log.Printf("authstub: /healthz 쓰기 실패: %v", err)
		}
	})
	return mux
}

// writeJSON 은 v 를 JSON 응답으로 쓴다.
func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("JSON 쓰기 실패: %v", err)
	}
}
