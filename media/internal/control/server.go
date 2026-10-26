// Package control 은 되감기 제어면 HTTP 서버다 — 스트림 경로 · 회차 경로 처리기, 두 경로가 나눠 쓰는 스트림별 한도,
// 회차 조회의 자원 상한, CORS · 머리, 우아한 종료(설계 M5 2.1 · 3절).
//
// 처리기는 status.Holder 의 스냅숏만 읽는다 — 루프의 캐시 · 종료 항목을 만지지 않는다. DB 에 닿는 길은 회차 경로의
// RoundLookup(M6 가 채운다) 하나이고, 그 길은 전역 예산 · singleflight · 시한 · 1초 캐시가 묶는다.
package control

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"
)

// 한도 상수(3.6 · 3.4절).
const (
	// staleAfter 는 스냅숏 낡음 기준이다 — 3 × index.TxnDeadline(10s). 루프는 holdTick 안에서 EndLive 를 동기로 부르고,
	// 그 한 번이 되돌림 포함 최대 약 20초다. 그보다 짧으면 DB 가 느린 틱 하나에 전 스트림이 503 으로 깜박인다(R3).
	staleAfter = 30 * time.Second
	// shutdownGrace 는 종료 때 처리 중 요청을 기다리는 상한이다(ingest-auth 선례 · docker 기본 10s 안).
	shutdownGrace = 3 * time.Second
	// sweepEvery 는 쓰지 않은 계수기 칸 · 캐시 칸을 치우고 게이지를 내리는 주기다.
	sweepEvery = 60 * time.Second
	// DefaultLimiterCells 는 Limiter 칸 상한이다 — 칸 하나 수백 바이트라 수 MiB 이하다(R54).
	DefaultLimiterCells = 4096
)

// serverLimits 는 http.Server 의 시한 · 머리 크기다.
type serverLimits struct {
	readHeader, read, write, idle time.Duration
	maxHeaderBytes                int
}

// defaultServerLimits 는 3.6절 표다. ReadTimeout 은 POK-472 의 POST 본문 몫이다. 짧은 JSON 이라 합성 레이어의
// WriteTimeout ≥ 15s 불변식은 대상이 아니다(블로킹 리로드가 없다).
var defaultServerLimits = serverLimits{
	readHeader: 5 * time.Second, read: 5 * time.Second, write: 5 * time.Second, idle: 60 * time.Second,
	maxHeaderBytes: 8 << 10,
}

// Options 는 서버와 두 처리기의 설정이다. startControl 이 한 벌을 만들어 RewindRoute · RoundRoute · Start 에 같이 준다.
type Options struct {
	Listen      string      // CONTROL_LISTEN. 빈 값이면 조립하지 않는다(조립의 몫)
	AllowOrigin string      // CONTROL_CORS_ORIGIN. 로컬 전용 — 빈 값이면 CORS 머리를 싣지 않는다
	Limiter     *Limiter    // 두 처리기가 나눠 쓰는 스트림별 한도. startControl 이 채운다
	RoundLimits RoundLimits // 회차 경로 상한 — 영값이면 DefaultRoundLimits()
	// StaleAfter 는 시험 주입용이다. 영값이면 운영 값(30s)이다.
	StaleAfter time.Duration
	Log        *slog.Logger     // nil 이면 버린다
	Now        func() time.Time // nil 이면 time.Now
	// sweepEvery 는 청소 주기의 시험 주입용이다. 영값이면 운영 값(60s)이다.
	sweepEvery time.Duration
}

// withDefaults 는 영값 칸을 운영 값으로 채운 사본이다.
func (o Options) withDefaults() Options {
	if o.StaleAfter == 0 {
		o.StaleAfter = staleAfter
	}
	if o.sweepEvery == 0 {
		o.sweepEvery = sweepEvery
	}
	if o.RoundLimits == (RoundLimits{}) {
		o.RoundLimits = DefaultRoundLimits()
	}
	if o.Log == nil {
		o.Log = slog.New(slog.DiscardHandler)
	}
	if o.Now == nil {
		o.Now = time.Now
	}
	return o
}

// requireLimiter 는 처리기 생성자의 조립 검사다 — 두 처리기는 같은 Limiter 를 나눠 써야 한도가 합산된다(확정 4).
// 없으면 조립 결함이라 기동 때 멈춘다.
func requireLimiter(o Options) {
	if o.Limiter == nil {
		panic("control: Options.Limiter 가 없다 — startControl 이 하나를 만들어 두 처리기에 준다")
	}
}

// Route 는 서버에 등록할 처리기 하나다. 밖에서 만든 라우트(POK-472 end-signal 등)는 Pattern · Handler 만 채운다.
type Route struct {
	Pattern string
	Handler http.Handler
	// bind 는 Start 가 서버 공유 상태(낡음 게이지 · 청소 등록)를 넘기는 자리다. RewindRoute · RoundRoute 만 채운다.
	bind func(*shared)
}

// shared 는 한 서버의 처리기들이 나눠 쓰는 상태다.
type shared struct {
	log *slog.Logger
	// stale 은 control_status_stale 게이지다 — 서버에 하나다. 상태를 바꾼 처리기가 줄을 쓴다(CompareAndSwap).
	stale atomic.Bool
	// sweeps 는 청소 고루틴이 부르는 칸 청소다(회차 경로의 1초 캐시 · 예산 게이지).
	sweeps []func(time.Time)
}

// observeStale 은 처리기가 본 스냅숏 나이로 낡음 게이지를 옮긴다. 공유 상태가 없으면(Start 없이 쓴 처리기) 하지 않는다.
func (s *shared) observeStale(age time.Duration, ok bool, limit time.Duration) {
	if s == nil || !ok {
		return
	}
	if age > limit {
		if s.stale.CompareAndSwap(false, true) {
			s.log.Warn("control_status_stale", "value", 1, "age_ms", age.Milliseconds())
		}
		return
	}
	if s.stale.CompareAndSwap(true, false) {
		s.log.Info("control_status_stale", "value", 0, "age_ms", age.Milliseconds())
	}
}

// Server 는 떠 있는 제어면 서버다.
type Server struct {
	srv     *http.Server
	ln      net.Listener
	log     *slog.Logger
	limiter *Limiter
	sh      *shared
	now     func() time.Time
	every   time.Duration // 청소 주기

	served    chan struct{} // Serve 가 돌아오면 닫힌다
	stopSweep chan struct{}
	swept     chan struct{} // 청소 고루틴이 끝나면 닫힌다

	once sync.Once
	err  error
}

// Start 는 opt.Listen 에서 듣고 routes 를 등록해 서버를 띄운다. 듣기에 실패하면 오류다 — 강등 처분은 조립이 정한다.
// 고루틴 둘(Serve · 60초 청소)을 띄우고, 둘 다 Shutdown 이 멈추고 기다린다.
func Start(opt Options, routes ...Route) (*Server, error) {
	opt = opt.withDefaults()
	ln, err := net.Listen("tcp", opt.Listen)
	if err != nil {
		return nil, fmt.Errorf("control: %s 에서 듣지 못했다: %w", opt.Listen, err)
	}
	sh := &shared{log: opt.Log}
	mux := http.NewServeMux()
	for _, r := range routes {
		if r.bind != nil {
			r.bind(sh)
		}
		mux.Handle(r.Pattern, r.Handler)
	}
	s := &Server{
		srv: newHTTPServer(mux, defaultServerLimits, opt.Log), ln: ln, log: opt.Log, limiter: opt.Limiter, sh: sh,
		now: opt.Now, every: opt.sweepEvery, served: make(chan struct{}), stopSweep: make(chan struct{}), swept: make(chan struct{}),
	}
	go s.serve()
	go s.sweepLoop()
	opt.Log.Info("control_server_started", "listen", ln.Addr().String())
	return s, nil
}

// newHTTPServer 는 h 를 한도 l 로 싼 http.Server 다. 서버 자신의 로그(처리기 패닉 · Accept 실패)는 ERROR 로 낸다.
func newHTTPServer(h http.Handler, l serverLimits, log *slog.Logger) *http.Server {
	return &http.Server{
		Handler:           h,
		ReadHeaderTimeout: l.readHeader,
		ReadTimeout:       l.read,
		WriteTimeout:      l.write,
		IdleTimeout:       l.idle,
		MaxHeaderBytes:    l.maxHeaderBytes,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelError),
	}
}

// Addr 는 듣는 주소다.
func (s *Server) Addr() net.Addr { return s.ln.Addr() }

// serve 는 Serve 가 돌아올 때까지 돈다. Shutdown 밖에서 멈추면(리스너 오류) ERROR 한 줄이다 — 내림은 없고 회복은
// 재기동이다(3.7절 · ADR-063 결정 1).
func (s *Server) serve() {
	defer close(s.served)
	if err := s.srv.Serve(s.ln); !errors.Is(err, http.ErrServerClosed) {
		s.log.Error("control_server_failed", "listen", s.ln.Addr().String(), "stage", "serve", "err", err)
	}
}

// sweepLoop 은 청소 주기(운영 sweepEvery)마다 계수기 칸 · 회차 경로 칸을 치운다. Shutdown 이 멈춘다.
func (s *Server) sweepLoop() {
	defer close(s.swept)
	t := time.NewTicker(s.every)
	defer t.Stop()
	for {
		select {
		case <-s.stopSweep:
			return
		case <-t.C:
			now := s.now()
			s.limiter.sweep(now)
			for _, sweep := range s.sh.sweeps {
				sweep(now)
			}
		}
	}
}

// Shutdown 은 서버를 우아하게 멈춘다 — 리스너를 닫고 처리 중 요청을 shutdownGrace 까지 기다리고(넘기면 남은 연결을
// 끊는다), 청소 고루틴을 멈춘 뒤, 두 고루틴이 끝나면 돌아온다. 두 번째 부름부터는 첫 결과를 돌려준다.
func (s *Server) Shutdown() error {
	s.once.Do(func() {
		ctx, cancel := context.WithTimeout(context.Background(), shutdownGrace)
		defer cancel()
		if err := s.srv.Shutdown(ctx); err != nil {
			s.err = fmt.Errorf("control: 우아한 종료가 %v 안에 끝나지 않아 연결을 끊었다: %w", shutdownGrace, errors.Join(err, s.srv.Close()))
		}
		close(s.stopSweep)
		<-s.served
		<-s.swept
		if s.err != nil {
			s.log.Info("control_server_stopped", "err", s.err)
			return
		}
		s.log.Info("control_server_stopped")
	})
	return s.err
}
