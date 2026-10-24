// Command echo 는 앞단 프록시 계약 시험(infra/media-host/test/proxy_contract.sh)이 쓰는
// 시험용 상류 · 역할 도구다. 한 이미지에서 하위 명령으로 역할을 고른다.
//
//	echo serve -bind 127.0.0.1 -listen_ports 8888,9996,9997 -admin_port 28199
//	echo sidecar -owner_addr 127.0.0.1:8888 -exit_delay 4s
//	echo probe -timeout 2s mediamtx:8888 mediamtx:9996
//	echo fetch -method POST 'http://127.0.0.1:28199/exit?code=1'
//	echo authstub -listen 127.0.0.1:8080 -admin 127.0.0.1:28199 -passphrase <P>
//	echo resolvestub -listen :28082 -resolve_token <T> -stream_token <토큰> -passphrase <P>
//
// serve 는 MediaMTX 자리(망 칸 주인)를 흉내 낸다. 받은 요청의 경로 · 쿼리 원문 · 메서드 ·
// 본문 · 헤더를 JSON 으로 그대로 돌려주고, 포트별 호출 수를 센다. 관리 포트는 호출 수
// (GET /counts) · 고정 200 헬스(GET /healthz) · 지정한 종료 코드로 끝내기(POST /exit?code=N)를
// 낸다. 바인드 주소의 기본은 127.0.0.1 이다 — prod MediaMTX 가 8888 · 9996 · 9997 을 루프백에만
// 여는 것과 같아야 「다른 망에서는 닿지 않는다」 사례가 뜻을 갖는다.
//
// sidecar 는 망 칸에 붙는 비부착 서비스(사이드카)를 흉내 낸다. 시작할 때마다 표식 줄과 주인
// 루프백 접속 결과 줄을 내고, 지정한 지연 뒤 종료 코드 1 로 끝난다(엔진 재시작 관찰용).
// SIGTERM 을 받으면 실제 사이드카처럼 0 으로 끝난다.
//
// probe 는 TCP 접속만 시도해 주소마다 OPEN 또는 CLOSED 한 줄을 낸다. fetch 는 HTTP 요청 하나를
// 보내 응답 본문을 표준 출력에 낸다 — 셸도 curl 도 없는 scratch 이미지 안에서
// `docker compose exec` 로 관리 포트를 부르기 위한 것이다.
//
// authstub · resolvestub 은 송출 인증 계약 시험(infra/media-host/test/ingest_auth_contract.sh)의 가짜
// 응답기 · 가짜 4C 다(authstub.go · resolvestub.go).
//
// 요청 내용(헤더 값 · 본문)은 표준 출력 · 오류 어디에도 남기지 않는다. 시험이 로그에서 시험
// 토큰을 셀 때 이 도구의 로그가 같은 토큰을 섞어 대조를 오염시키지 않게 하기 위해서다.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

const (
	// maxBodyBytes 는 돌려줄 요청 본문의 상한이다. 시험 본문은 수백 바이트라 넉넉하다.
	maxBodyBytes = 1 << 20
	// readHeaderTimeout · writeTimeout 은 시험 서버의 머리 · 쓰기 상한이다. 블로킹 리로드를
	// 흉내 내지 않으므로 짧아도 된다.
	readHeaderTimeout = 5 * time.Second
	writeTimeout      = 10 * time.Second
	// shutdownGrace 는 종료 신호 · 종료 요청 뒤 진행 중인 요청을 기다리는 상한이다.
	shutdownGrace = 2 * time.Second
	// ownerDialTimeout 은 sidecar 가 주인 루프백 접속 한 번을 기다리는 상한이다.
	ownerDialTimeout = 2 * time.Second
	// ownerRetryWindow 는 접속이 거부될 때 다시 시도하는 전체 창이다. 주인이 막
	// 떠서 아직 듣지 않는 순간(첫 기동)의 거부를 망 칸 합류 실패로 읽지 않게 한다.
	ownerRetryWindow = 5 * time.Second
	// ownerRetryInterval 은 거부 뒤 다시 시도하기 전 쉬는 시간이다.
	ownerRetryInterval = 200 * time.Millisecond
	// fetchTimeout 은 fetch 요청 하나의 상한이다.
	fetchTimeout = 5 * time.Second
)

const (
	exitOK    = 0
	exitFail  = 1
	exitUsage = 2
)

// echoReply 는 에코가 돌려주는 JSON 이다. 시험이 jq 로 이 이름들을 읽는다.
type echoReply struct {
	Listener   string              `json:"listener"`
	RemoteAddr string              `json:"remote_addr"`
	Method     string              `json:"method"`
	RequestURI string              `json:"request_uri"`
	Path       string              `json:"path"`
	RawQuery   string              `json:"raw_query"`
	Host       string              `json:"host"`
	Headers    map[string][]string `json:"headers"`
	Body       string              `json:"body"`
}

func main() {
	log.SetFlags(0)
	log.SetPrefix("echo: ")
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	if len(args) == 0 {
		log.Print("하위 명령이 없다: serve · sidecar · probe · fetch · authstub · resolvestub 가운데 하나")
		return exitUsage
	}
	switch args[0] {
	case "serve":
		return runServe(args[1:])
	case "sidecar":
		return runSidecar(args[1:])
	case "probe":
		return runProbe(args[1:])
	case "fetch":
		return runFetch(args[1:])
	case "authstub":
		return runAuthstub(args[1:])
	case "resolvestub":
		return runResolvestub(args[1:])
	}
	log.Printf("모르는 하위 명령 %q", args[0])
	return exitUsage
}

// runServe 는 에코 포트들과 관리 포트를 띄우고, 종료 신호 · 종료 요청 · 서버 오류 가운데
// 먼저 온 것으로 끝난다.
func runServe(args []string) int {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	bind := fs.String("bind", "127.0.0.1", "address every port listens on")
	listenPorts := fs.String("listen_ports", "", "comma-separated echo ports")
	adminPort := fs.Int("admin_port", 0, "admin port (/counts, /healthz, /exit)")
	if err := fs.Parse(args); err != nil {
		return exitUsage
	}
	ports, err := parsePorts(*listenPorts)
	if err != nil {
		log.Printf("serve: -listen_ports: %v", err)
		return exitUsage
	}
	if err := validatePort(*adminPort); err != nil {
		log.Printf("serve: -admin_port: %v", err)
		return exitUsage
	}

	// counts 는 여기서 한 번 채운 뒤 바꾸지 않는다 — 요청 처리기들은 읽기만 하므로 잠금이 없다.
	counts := make(map[string]*atomic.Int64, len(ports))
	servers := make([]*http.Server, 0, len(ports)+1)
	for _, port := range ports {
		addr := net.JoinHostPort(*bind, strconv.Itoa(port))
		calls := new(atomic.Int64)
		counts[addr] = calls
		servers = append(servers, newServer(addr, echoHandler(addr, calls)))
	}
	exitReq := make(chan int, 1)
	adminAddr := net.JoinHostPort(*bind, strconv.Itoa(*adminPort))
	servers = append(servers, newServer(adminAddr, adminHandler(counts, exitReq)))
	return serveUntilDone(servers, exitReq)
}

// serveUntilDone 은 모든 포트를 먼저 연 뒤(하나라도 못 열면 바로 실패) 서비스를 시작하고,
// 끝낼 이유가 생기면 서버를 모두 내리고 종료 코드를 돌려준다.
func serveUntilDone(servers []*http.Server, exitReq <-chan int) int {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	listeners := make([]net.Listener, 0, len(servers))
	for _, s := range servers {
		ln, err := net.Listen("tcp", s.Addr)
		if err != nil {
			closeListeners(listeners)
			log.Printf("serve: %v", err)
			return exitFail
		}
		listeners = append(listeners, ln)
	}
	serveErr := make(chan error, len(servers))
	for i, s := range servers {
		log.Printf("serve: listening on %s", s.Addr)
		go func() { serveErr <- s.Serve(listeners[i]) }()
	}

	code := exitOK
	select {
	case <-ctx.Done():
		log.Print("serve: 종료 신호를 받아 0 으로 끝낸다")
	case code = <-exitReq:
		log.Printf("serve: 종료 요청을 받아 %d 로 끝낸다", code)
	case err := <-serveErr:
		log.Printf("serve: %v", err)
		code = exitFail
	}
	shutdownServers(servers)
	return code
}

func newServer(addr string, h http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           h,
		ReadHeaderTimeout: readHeaderTimeout,
		WriteTimeout:      writeTimeout,
	}
}

func closeListeners(listeners []net.Listener) {
	for _, ln := range listeners {
		if err := ln.Close(); err != nil {
			log.Printf("serve: close %s: %v", ln.Addr(), err)
		}
	}
}

func shutdownServers(servers []*http.Server) {
	ctx, cancel := context.WithTimeout(context.Background(), shutdownGrace)
	defer cancel()
	for _, s := range servers {
		if err := s.Shutdown(ctx); err != nil {
			log.Printf("serve: shutdown %s: %v", s.Addr, err)
		}
	}
}

// echoHandler 는 요청을 그대로 JSON 으로 돌려주고 호출 수를 하나 올린다. 요청 내용은 응답에만
// 싣고 로그에는 남기지 않는다(패키지 설명).
func echoHandler(listener string, calls *atomic.Int64) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
		if err != nil {
			http.Error(w, "request body unreadable or too large", http.StatusRequestEntityTooLarge)
			return
		}
		reply := echoReply{
			Listener:   listener,
			RemoteAddr: r.RemoteAddr,
			Method:     r.Method,
			RequestURI: r.RequestURI,
			Path:       r.URL.EscapedPath(),
			RawQuery:   r.URL.RawQuery,
			Host:       r.Host,
			Headers:    r.Header,
			Body:       string(body),
		}
		w.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(w).Encode(reply); err != nil {
			log.Printf("echo %s: 응답 쓰기 실패: %v", listener, err)
		}
	})
}

// adminHandler 는 시험이 `docker compose exec … fetch` 로 부르는 관리 경로를 낸다.
func adminHandler(counts map[string]*atomic.Int64, exitReq chan<- int) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /counts", func(w http.ResponseWriter, _ *http.Request) {
		snapshot := make(map[string]int64, len(counts))
		for addr, calls := range counts {
			snapshot[addr] = calls.Load()
		}
		w.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(w).Encode(snapshot); err != nil {
			log.Printf("admin: /counts 쓰기 실패: %v", err)
		}
	})
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		if _, err := io.WriteString(w, "ok\n"); err != nil {
			log.Printf("admin: /healthz 쓰기 실패: %v", err)
		}
	})
	mux.HandleFunc("POST /exit", func(w http.ResponseWriter, r *http.Request) {
		code, err := strconv.Atoi(r.URL.Query().Get("code"))
		if err != nil || code < 0 || code > 255 {
			http.Error(w, "code must be an integer in 0..255", http.StatusBadRequest)
			return
		}
		select {
		case exitReq <- code:
			w.WriteHeader(http.StatusAccepted)
			if _, err := fmt.Fprintf(w, "exiting with %d\n", code); err != nil {
				log.Printf("admin: /exit 쓰기 실패: %v", err)
			}
		default:
			http.Error(w, "exit already requested", http.StatusConflict)
		}
	})
	return mux
}

// runSidecar 는 사이드카 역할이다. 시작 표식 줄은 시험이 재시작 횟수를 세는 단위이고
// (시작 한 번 = 한 줄), 주인 접속 결과 줄은 다시 띄워진 컨테이너가 주인 망 칸에 붙었는지 보인다.
func runSidecar(args []string) int {
	fs := flag.NewFlagSet("sidecar", flag.ContinueOnError)
	ownerAddr := fs.String("owner_addr", "127.0.0.1:8888", "owner loopback address to dial")
	exitDelay := fs.Duration("exit_delay", 0, "time to stay up before exiting with 1")
	if err := fs.Parse(args); err != nil {
		return exitUsage
	}
	if *exitDelay < 0 {
		log.Printf("sidecar: -exit_delay 가 음수다: %v", *exitDelay)
		return exitUsage
	}
	// 신호 처리를 표식보다 먼저 건다 — 표식을 낸 직후의 SIGTERM 을 놓치지 않게.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	fmt.Printf("SIDECAR START at=%s\n", time.Now().UTC().Format(time.RFC3339Nano))
	reportOwnerDial(*ownerAddr)

	select {
	case <-ctx.Done():
		fmt.Println("SIDECAR STOP signal")
		return exitOK
	case <-time.After(*exitDelay):
		fmt.Println("SIDECAR EXIT 1")
		return exitFail
	}
}

// reportOwnerDial 은 주인 루프백 주소에 TCP 접속만 해 보고 결과 한 줄을 낸다. HTTP 요청을
// 보내지 않으므로 serve 의 호출 수를 바꾸지 않는다. 실패하면 ownerRetryWindow 안에서 다시
// 시도하고, 창이 끝날 때까지 못 붙으면 마지막 오류로 FAIL 을 낸다.
func reportOwnerDial(addr string) {
	deadline := time.Now().Add(ownerRetryWindow)
	conn, err := net.DialTimeout("tcp", addr, ownerDialTimeout)
	for err != nil && time.Now().Before(deadline) {
		time.Sleep(ownerRetryInterval)
		conn, err = net.DialTimeout("tcp", addr, ownerDialTimeout)
	}
	if err != nil {
		fmt.Printf("SIDECAR OWNER FAIL %s: %v\n", addr, err)
		return
	}
	fmt.Printf("SIDECAR OWNER OK %s\n", addr)
	if err := conn.Close(); err != nil {
		log.Printf("sidecar: close %s: %v", addr, err)
	}
}

// runProbe 는 주소마다 TCP 접속을 시도해 OPEN 또는 CLOSED 한 줄을 낸다. 접속 결과는 판정이
// 아니라 자료이므로, 모든 주소를 시도했으면 0 으로 끝난다.
func runProbe(args []string) int {
	fs := flag.NewFlagSet("probe", flag.ContinueOnError)
	timeout := fs.Duration("timeout", 2*time.Second, "dial timeout per address")
	if err := fs.Parse(args); err != nil {
		return exitUsage
	}
	if fs.NArg() == 0 {
		log.Print("probe: 주소가 없다")
		return exitUsage
	}
	for _, addr := range fs.Args() {
		conn, err := net.DialTimeout("tcp", addr, *timeout)
		if err != nil {
			fmt.Printf("CLOSED %s: %v\n", addr, err)
			continue
		}
		fmt.Printf("OPEN %s\n", addr)
		if err := conn.Close(); err != nil {
			log.Printf("probe: close %s: %v", addr, err)
		}
	}
	return exitOK
}

// runFetch 는 HTTP 요청 하나를 보내 응답 본문을 표준 출력에 낸다. 2xx 가 아니면 1 이다.
func runFetch(args []string) int {
	fs := flag.NewFlagSet("fetch", flag.ContinueOnError)
	method := fs.String("method", http.MethodGet, "HTTP method")
	if err := fs.Parse(args); err != nil {
		return exitUsage
	}
	if fs.NArg() != 1 {
		log.Print("fetch: URL 하나가 필요하다")
		return exitUsage
	}
	ctx, cancel := context.WithTimeout(context.Background(), fetchTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, *method, fs.Arg(0), nil)
	if err != nil {
		log.Printf("fetch: %v", err)
		return exitUsage
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		log.Printf("fetch: %v", err)
		return exitFail
	}
	defer resp.Body.Close()
	if _, err := io.Copy(os.Stdout, resp.Body); err != nil {
		log.Printf("fetch: 본문 읽기 실패: %v", err)
		return exitFail
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		log.Printf("fetch: %s", resp.Status)
		return exitFail
	}
	return exitOK
}

// parsePorts 는 쉼표로 나눈 포트 목록을 읽는다.
func parsePorts(s string) ([]int, error) {
	if s == "" {
		return nil, errors.New("포트가 없다")
	}
	fields := strings.Split(s, ",")
	ports := make([]int, 0, len(fields))
	for _, field := range fields {
		port, err := strconv.Atoi(strings.TrimSpace(field))
		if err != nil {
			return nil, fmt.Errorf("포트 %q 를 읽지 못했다: %w", field, err)
		}
		if err := validatePort(port); err != nil {
			return nil, err
		}
		ports = append(ports, port)
	}
	return ports, nil
}

func validatePort(port int) error {
	if port < 1 || port > 65535 {
		return fmt.Errorf("포트 %d 가 범위(1..65535) 밖이다", port)
	}
	return nil
}
