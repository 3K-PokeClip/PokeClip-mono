package main

import (
	"context"
	"errors"
	"io"
	"log"
	"log/slog"
	"net"
	"net/http"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/credrelay"
)

// cred_relay_failed 의 reason 이다(설계 8절). 닫힌 집합이다.
const (
	reasonConfig    = "config"     // 환경 변수 · 호출자 목록이 꼴 밖이다(env 에 변수 이름)
	reasonTokenFile = "token_file" // 토큰 디렉터리 · 파일을 받을 수 없다
	reasonListen    = "listen"     // 리스너를 열지 못했거나 서버가 스스로 멈췄다(addr 에 주소)
)

// cred_relay_server_error 의 kind 다 — http.Server 자신의 에러(ErrorLog) 원문을 버리고 남기는 종류다.
const (
	serverErrorPanic  = "panic"  // 처리기 패닉 — 원문에 패닉 값 · 스택이 있다
	serverErrorAccept = "accept" // Accept 일시 오류(서버가 다시 시도한다)
	serverErrorOther  = "other"
)

// newLogger 는 serve 의 JSON 로거다(INFO 이상 — 중계의 줄은 모두 INFO 이상이다).
func newLogger(w io.Writer) *slog.Logger {
	return slog.New(slog.NewJSONHandler(w, nil))
}

// serve 는 기본 하위 명령이다 — 설정 → 토큰 → 리스너 순으로 조립하고, 종료 신호(SIGTERM · SIGINT)나 ctx 가
// 끝날 때까지 답한다. 정상 종료는 0, 기동 거부와 서버가 스스로 멈춤은 1 이다. 거부 줄(cred_relay_failed)에는
// 원천 에러의 원문을 싣지 않는다(패키지 문서).
func serve(ctx context.Context, s site, getenv func(string) string, stdout io.Writer) int {
	logger := newLogger(stdout)
	cfg, err := loadConfig(getenv)
	if err != nil {
		var ce *configError
		if !errors.As(err, &ce) {
			return failed(logger, reasonConfig)
		}
		return failed(logger, reasonConfig, "env", ce.env)
	}
	tok, err := credrelay.LoadTokens(cfg.callerDir, cfg.callers)
	if errors.Is(err, credrelay.ErrCallers) {
		return failed(logger, reasonConfig, "env", envCallers)
	}
	if err != nil {
		return failed(logger, reasonTokenFile, "env", envCallerDir)
	}

	// 신호를 리스너보다 먼저 잡는다 — 듣기 시작한 뒤의 TERM 은 늘 정상 종료다.
	ctx, stop := signal.NotifyContext(ctx, syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	addr := cfg.listen.String()
	ln, err := s.listen("tcp", addr)
	if err != nil {
		return failed(logger, reasonListen, "addr", addr)
	}
	src := credrelay.NewIMDSRoleSource(credrelay.NewIMDSClient(s.imdsEndpoint), time.Now)
	srv := newServer(addr, credrelay.NewHandler(src, tok, time.Now, logger), logger)
	logger.Info("cred_relay_started", "addr", ln.Addr().String(), "callers", strings.Join(cfg.callers, ","))
	return serveUntilDone(ctx, srv, ln, logger)
}

// failed 는 기동 거부 줄을 내고 종료 1 을 돌려준다. attrs 는 설정 이름 · 주소뿐이다(비밀 · 원문 없음).
func failed(logger *slog.Logger, reason string, attrs ...any) int {
	logger.Error("cred_relay_failed", append([]any{"reason", reason}, attrs...)...)
	return exitFailure
}

// newServer 는 credrelay 의 서버 상한에 서버 자신의 에러 기록기를 단다. ErrorLog 가 nil 이면 http.Server 는 원문
// (처리기 패닉 값 · 스택, Accept 오류)을 기본 log 로 stderr 에 쓴다 — 원문 대신 닫힌 종류만 logger 에 낸다.
func newServer(addr string, h http.Handler, logger *slog.Logger) *http.Server {
	srv := credrelay.NewServer(addr, h)
	srv.ErrorLog = log.New(serverErrorLog{logger: logger}, "", 0)
	return srv
}

// serverErrorLog 는 http.Server 의 에러 한 건을 cred_relay_server_error kind=<종류> 한 줄로 바꾼다.
type serverErrorLog struct {
	logger *slog.Logger
}

func (w serverErrorLog) Write(p []byte) (int, error) {
	w.logger.Error("cred_relay_server_error", "kind", serverErrorKind(string(p)))
	return len(p), nil
}

// serverErrorKind 는 http.Server 의 에러 문구(net/http server.go 의 logf 꼴)를 종류로 가른다.
func serverErrorKind(msg string) string {
	switch {
	case strings.HasPrefix(msg, "http: panic serving"):
		return serverErrorPanic
	case strings.HasPrefix(msg, "http: Accept error"):
		return serverErrorAccept
	}
	return serverErrorOther
}

// serveUntilDone 은 ln 에서 srv 를 돌리다가 ctx 가 끝나면 우아하게 멈추고(ShutdownGrace — 넘기면 남은 연결을
// 끊는다) 0, 서버가 스스로 멈추면(리스너 실패) 1 을 돌려준다. Serve 고루틴이 끝난 뒤에 돌아온다.
func serveUntilDone(ctx context.Context, srv *http.Server, ln net.Listener, logger *slog.Logger) int {
	served := make(chan struct{})
	go func() {
		defer close(served)
		// Serve 는 Shutdown · Close 전에는 리스너가 실패할 때만 돌아온다 — 그 에러 원문은 싣지 않는다.
		_ = srv.Serve(ln)
	}()
	select {
	case <-served:
		return failed(logger, reasonListen, "addr", ln.Addr().String())
	case <-ctx.Done():
	}
	sctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), credrelay.ShutdownGrace)
	defer cancel()
	graceful := srv.Shutdown(sctx) == nil
	if !graceful {
		// 유예를 넘겼다 — 남은 연결을 끊는다. 닫기 에러는 graceful=false 로 이미 드러난다.
		_ = srv.Close()
	}
	<-served
	logger.Info("cred_relay_stopped", "graceful", graceful)
	return exitOK
}
