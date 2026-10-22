package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/ingestauth"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
)

// connLogMaxBytes 는 허용 연결 기록 한 세대의 크기 상한이다(설계 3-4 — 두 세대, 각 1 MiB).
const connLogMaxBytes = 1 << 20

// 두 리스너의 경로다. 처리기는 이 경로 · 메서드에만 있다 — 다른 요청은 ServeMux 가 404 · 405 로 답하고 인증 처리기의
// 예산 · 출발지 검사에 닿지 않는다.
const (
	authRoute   = "POST /mediamtx/auth" // MediaMTX 의 authHTTPAddress(설계 2-8)
	healthRoute = "GET /healthz"        // healthcheck 가 묻는다(설계 2-6)
)

// 두 리스너의 http.Server 한도다. MediaMTX 는 인증을 물을 때마다 새 연결로 머리와 본문을 한 번에 보내고 답을 받으면
// 닫는다(포크 auth/manager.go 227–237). 그래서 한도는 그 밖의 연결을 묶는 몫이다. 두 서버는 답 하나 뒤 연결을 닫으므로
// (newServer — keep-alive 끔) 다음 요청을 기다리는 한도(IdleTimeout)는 두지 않는다.
const (
	// readHeaderTimeout 은 머리를 다 받기까지의 상한이다. 머리 단계는 처리기의 출발지 검사 앞이라 누구든 연결을
	// 붙잡을 수 있다 — Go 의 기본은 시한이 없다.
	readHeaderTimeout = 1 * time.Second
	// readTimeout 은 머리와 본문을 다 받기까지의 상한이다. 바깥 가드(MaxDeadline + 50ms = 1.175초 — 설계 2-4)보다 길게
	// 둔다. Go 1.27 은 본문을 다 읽은 뒤 읽기 시한을 지우지만(net/http server.go 741), 툴체인이 바뀌어도 처리기 도중에
	// 요청 ctx 가 끊기지 않게 한다.
	readTimeout = 2 * time.Second
	// writeTimeout 은 머리를 읽은 뒤 응답을 다 쓰기까지의 상한이다. 가드보다 길어야 시한 근처의 200 이 끊기지 않는다.
	// 답을 읽지 않는 상대가 쓰기를 붙잡는 것을 묶는다.
	writeTimeout = 3 * time.Second
	// maxHeaderBytes 는 머리 크기의 상한이다. MediaMTX 의 머리는 수백 바이트다 — 기본 1 MiB 대신 묶는다.
	maxHeaderBytes = 8 << 10
)

// shutdownGrace 는 종료 신호 뒤 처리 중 요청을 기다리는 상한이다. 인증 요청은 가드가 1.175초에 끝내고, docker 는 기본
// 10초 뒤 SIGKILL 을 보낸다. 넘기면 남은 연결을 끊는다.
const shutdownGrace = 3 * time.Second

// serve 는 run 하위 명령이다 — 설정을 읽어 응답기를 조립하고, 종료 신호(SIGTERM · SIGINT)나 ctx 가 끝날 때까지 두
// 리스너에서 답한다. 정상 종료는 0, 설정 오류는 2, 기동 · 서버 실패는 1 이다.
//
// 로그 등급 → JSON 로거 → slog.SetDefault 가 맨 앞이다 — 기록의 회전 로그(ingestauth_connlog_rotated)는 OpenConnLog 가
// 그때의 기본 로거로 낸다(ConnLog 문서).
func serve(ctx context.Context, s site, getenv func(string) string, stdout, stderr io.Writer) int {
	level, err := logLevel(getenv(envLogLevel))
	if err != nil {
		return exitWith(stderr, exitUsage, err)
	}
	log := slog.New(slog.NewJSONHandler(stdout, &slog.HandlerOptions{Level: level}))
	slog.SetDefault(log)
	cfg, err := loadServeConfig(getenv)
	if err != nil {
		return exitWith(stderr, exitUsage, err)
	}
	r, err := openResponder(s, cfg, getenv, log)
	if err != nil {
		return exitWith(stderr, exitFailure, err)
	}
	log.Info("ingestauth_started",
		"listen", r.auth.ln.Addr().String(),
		"caller_ip", cfg.callerIP.String(),
		"caller_auth", cfg.callerAuth(),
		"auth_host_kind", cfg.auth.hostKind,
		"auth_port", cfg.auth.port,
		"auth_channel", cfg.auth.channel.String(),
		"deadline_ms", ingestauth.MaxDeadline.Milliseconds(),
		"health", r.health.ln.Addr().String())
	if cfg.caller.off {
		// dev-media 처럼 제어망이 따로 있는 배치의 의도된 상태다. prod 에서 보이면 설정 누락이다.
		log.Warn("ingestauth_caller_basic_off")
	}
	ctx, stop := signal.NotifyContext(ctx, syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	if err := serveUntilDone(ctx, r.serving, r.auth, r.health); err != nil {
		return exitWith(stderr, exitFailure, err)
	}
	log.Info("ingestauth_stopped")
	return exitOK
}

// serveConfig 는 run 의 설정 가운데 조립에 쓰는 것이다. 찍을 수 있는 자리에 비밀이 없다 — 출입증은 resolver 의 머리
// 싣는 함수 안에만 있고 API 비번은 secret 이라, serveConfig 를 어느 동사 · slog · json 으로 찍어도 나오지 않는다
// (assembly_leak_test).
type serveConfig struct {
	listen   netip.AddrPort // 인증 리스너 — 이 IP 가 SelfIP 다
	callerIP netip.Addr
	apiPass  secret      // MTX_API_PASS — 처리기의 api 행 자격(loadSecrets 가 한 번만 읽은 값)
	caller   callerBasic // 호출자 Basic 자격 또는 명시 끔
	resolver *keyresolve.Client
	auth     authTarget // 기동 로그의 auth_host_kind · auth_port — 주소는 없다
}

// loadServeConfig 는 run 의 환경 변수를 모두 검사한다(설계 2-6 — 필수값이 비면 기동 실패). cut 만 쓰는 MTX_API_URL 도
// 여기서 클라이언트를 만들어 본다 — 틀린 값이 긴급 절단 때 처음 드러나지 않게 한다. 에러에는 변수 이름만 싣는다.
func loadServeConfig(getenv func(string) string) (serveConfig, error) {
	err := requireSet(getenv, envListen, envCallerIP, envMTXAPIURL, envMTXAPIUser, envResolveURL)
	if err != nil {
		return serveConfig{}, err
	}
	listen, err := listenAddr(getenv(envListen))
	if err != nil {
		return serveConfig{}, err
	}
	callerIP, err := callerAddr(getenv(envCallerIP))
	if err != nil {
		return serveConfig{}, err
	}
	sec, err := loadSecrets(getenv)
	if err != nil {
		return serveConfig{}, err
	}
	if _, err := newControlClient(getenv, sec.apiPass); err != nil {
		return serveConfig{}, err
	}
	resolver, auth, err := newResolver(getenv, sec.resolveToken)
	if err != nil {
		return serveConfig{}, err
	}
	return serveConfig{listen: listen, callerIP: callerIP, apiPass: sec.apiPass, caller: sec.caller, resolver: resolver, auth: auth}, nil
}

// callerAuth 는 기동 로그의 caller_auth 다 — basic(호출자 Basic 검사) 또는 off(명시 끔).
func (c serveConfig) callerAuth() string {
	if c.caller.off {
		return callerBasicOffValue
	}
	return "basic"
}

// listening 은 리스너 하나와 그 위에서 돌릴 서버다.
type listening struct {
	srv *http.Server
	ln  net.Listener
}

// responder 는 run 이 띄울 두 서버와 healthz 의 「수신 중」 표시다.
type responder struct {
	auth, health listening
	serving      *atomic.Bool // 인증 서버가 받는 동안만 참이다
}

// openResponder 는 거부 목록 · 기록 · 처리기 · 두 리스너를 연다. 거부 목록과 기록은 하나씩만 만들어 처리기와 healthz 가
// 함께 쓴다 — healthz 가 읽은 토큰도 처리기의 합집합에 들고(설계 3-4 1), healthz 는 처리기가 쓰는 그 기록을 본다.
// 인증 리스너는 cfg.listen 의 IP 에만 묶는다(설계 8-1).
func openResponder(s site, cfg serveConfig, getenv func(string) string, log *slog.Logger) (responder, error) {
	deny := ingestauth.NewDenyList(s.denyList, log)
	conns, err := ingestauth.OpenConnLog(s.connLogDir, connLogMaxBytes, nil)
	if err != nil {
		return responder{}, err
	}
	handler, err := ingestauth.NewHandler(cfg.resolver, deny, conns, ingestauth.Options{
		CallerIP: cfg.callerIP,
		SelfIP:   cfg.listen.Addr(),
		APIUser:  getenv(envMTXAPIUser),
		APIPass:  ingestauth.NewSecret(cfg.apiPass.reveal()),
		Log:      log,

		CallerUser:     cfg.caller.user,
		CallerPass:     callerPass(cfg.caller),
		CallerBasicOff: cfg.caller.off,
	})
	if err != nil {
		return responder{}, err
	}
	authLn, healthLn, err := listenBoth(s.listen, cfg.listen.String(), s.healthAddr)
	if err != nil {
		return responder{}, err
	}
	serving := new(atomic.Bool)
	return responder{
		auth:    listening{srv: newServer(routed(authRoute, handler), log), ln: authLn},
		health:  listening{srv: newServer(routed(healthRoute, healthz(serving, deny, conns)), log), ln: healthLn},
		serving: serving,
	}, nil
}

// callerPass 는 c 의 비번을 처리기에 넘기는 꼴이다. 끔이면 영값이다 — 처리기는 빈 비번을 자격 없음으로 본다.
func callerPass(c callerBasic) ingestauth.Secret {
	if c.off {
		return ingestauth.Secret{}
	}
	return ingestauth.NewSecret(c.pass.reveal())
}

// listenBoth 는 listen(운영은 net.Listen)으로 인증 리스너와 healthz 리스너를 연다. 둘째가 실패하면 첫째를 닫는다.
func listenBoth(listen func(network, address string) (net.Listener, error), authAddr, healthAddr string) (auth, health net.Listener, err error) {
	auth, err = listen("tcp", authAddr)
	if err != nil {
		return nil, nil, fmt.Errorf("인증 리스너를 열지 못했다: %w", err)
	}
	health, err = listen("tcp", healthAddr)
	if err != nil {
		return nil, nil, errors.Join(fmt.Errorf("healthz 리스너를 열지 못했다: %w", err), auth.Close())
	}
	return auth, health, nil
}

// routed 는 h 를 pattern 하나에만 둔 ServeMux 다. 다른 경로 · 메서드는 404 · 405 다.
func routed(pattern string, h http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.Handle(pattern, h)
	return mux
}

// newServer 는 h 를 두 리스너 공통의 한도(위 상수)로 싼 http.Server 다. 서버 자신의 로그(처리기 패닉 · Accept 실패
// 등)는 log 의 ERROR 로 낸다. ErrorLog 가 nil 이면 SetDefault 를 거쳐 INFO 로 나간다 — warn · error 에서도 남게 ERROR 로
// 낸다.
//
// keep-alive 를 끈다 — 답에 Connection: close 를 싣고 답을 쓴 뒤 곧바로 닫아 응답기가 먼저 닫게 한다. TCP 는 먼저
// 닫는 쪽에 TIME_WAIT(리눅스 60초)을 남긴다. 켜 두면 연결을 다시 쓰지 않는 MediaMTX 가 먼저 닫아 TIME_WAIT 이
// MediaMTX 쪽 임시 포트에 쌓이고, 60초 안의 인증 요청(큰 cut 의 kick · SRT 포트로 쏟아지는 송출 시도)이 그 범위를
// 채우면 MediaMTX 가 응답기에 인증을 묻지 못한다 — api 는 401(cut 의 api_auth)이고 모든 계정의 새 송출이 거부된다.
// 응답기가 먼저 닫은 연결의 TIME_WAIT 은 응답기의 고정 포트 쪽에 남아 상대의 임시 포트를 잡지 않는다. 다만 두 쪽이
// 거의 함께 닫으면 MediaMTX 쪽에도 남아 0 이 되지는 않는다 — 실험대(리눅스 · MediaMTX `v1.21.1-pokeclip.1` — `.2`
// 는 재지 않았다)에서 두 세대를 채운 cut 하나가 MediaMTX 쪽에 남긴 TIME_WAIT 은 약 1만 7천에서 약 3천 4백(api 인증
// 요청의 약 5분의 1)으로 줄었고, 받아들인 publish 인증은 요청의 약 3분의 1 이 남았다(401 로 거절하는 꼴은 재지
// 않았다). 1초 뒤 다시 돌린 같은 cut 은 끝났지만, 쏟아지는 송출 시도에 대한 고갈 문턱은 재지 않았다. MediaMTX 는
// 연결을 다시 쓰지 않으므로 잃는 것이 없다.
func newServer(h http.Handler, log *slog.Logger) *http.Server {
	srv := &http.Server{
		Handler:           h,
		ReadHeaderTimeout: readHeaderTimeout,
		ReadTimeout:       readTimeout,
		WriteTimeout:      writeTimeout,
		MaxHeaderBytes:    maxHeaderBytes,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelError),
	}
	srv.SetKeepAlivesEnabled(false)
	return srv
}

// serveUntilDone 은 두 서버를 돌리다가 ctx 가 끝나거나 어느 서버가 스스로 멈추면 둘 다 우아하게 멈춘다. 두 Serve
// 고루틴이 모두 끝난 뒤에 돌아온다. serving 은 인증 서버가 받는 동안만 참이다(healthz 의 「수신 중」). ctx 로 멈췄고
// 우아하게 끝났으면 nil 이다.
func serveUntilDone(ctx context.Context, serving *atomic.Bool, auth, health listening) error {
	stopped := make(chan error, 2) // 두 Serve 가 기다리지 않고 보내게 칸을 둘 둔다
	var wg sync.WaitGroup
	serving.Store(true)
	wg.Go(func() {
		err := auth.srv.Serve(auth.ln)
		serving.Store(false)
		stopped <- fmt.Errorf("인증 서버가 멈췄다: %w", err)
	})
	wg.Go(func() {
		stopped <- fmt.Errorf("healthz 서버가 멈췄다: %w", health.srv.Serve(health.ln))
	})
	var cause error
	select {
	case <-ctx.Done():
	case cause = <-stopped:
	}
	serving.Store(false)
	err := shutdown(ctx, auth.srv, health.srv)
	wg.Wait()
	return errors.Join(cause, err)
}

// shutdown 은 servers 를 차례로 우아하게 멈춘다 — 리스너를 닫고 처리 중 요청을 shutdownGrace 까지 기다린다. 넘기면
// 남은 연결을 끊고 에러다. ctx 는 이미 끝났을 수 있어 취소를 떼고 시한만 건다.
func shutdown(ctx context.Context, servers ...*http.Server) error {
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), shutdownGrace)
	defer cancel()
	var errs []error
	for _, srv := range servers {
		if err := srv.Shutdown(ctx); err != nil {
			errs = append(errs, fmt.Errorf("우아한 종료가 %v 안에 끝나지 않아 연결을 끊었다: %w", shutdownGrace, errors.Join(err, srv.Close())))
		}
	}
	return errors.Join(errs...)
}
