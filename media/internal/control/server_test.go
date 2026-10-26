package control

// 서버 수명(설계 M5 3.6 한도 · 3.7 기동 · 종료 · 8절 control_server_*)의 시험이다. Shutdown 은 Serve 고루틴과 청소
// 고루틴이 끝난 뒤에 돌아온다(R9 — 조립의 LIFO 가 「서버가 먼저 멈추고 풀이 뒤에 닫힌다」에 기댄다). 느린 클라이언트는
// 머리 시한이 끊는다(R10).

import (
	"bufio"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

// 3.6절 한도 표가 운영 서버에 그대로 실린다.
func TestStartUsesServerLimits(t *testing.T) {
	f := newFixture(10)
	srv, err := Start(f.opt, RewindRoute(f.holder, f.opt))
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Shutdown()
	h := srv.srv
	if h.ReadHeaderTimeout != 5*time.Second || h.ReadTimeout != 5*time.Second || h.WriteTimeout != 5*time.Second ||
		h.IdleTimeout != 60*time.Second || h.MaxHeaderBytes != 8<<10 {
		t.Errorf("한도 = 머리 %v · 읽기 %v · 쓰기 %v · 유휴 %v · 머리 크기 %d, want 5s · 5s · 5s · 60s · 8192",
			h.ReadHeaderTimeout, h.ReadTimeout, h.WriteTimeout, h.IdleTimeout, h.MaxHeaderBytes)
	}
	if started := f.logs.events("control_server_started"); len(started) != 1 || started[0]["listen"] != srv.Addr().String() {
		t.Errorf("control_server_started = %v, want listen=%s 한 줄", started, srv.Addr())
	}
}

// R10: 머리를 반만 보낸 연결은 머리 시한에 닫힌다(시험은 짧은 시한으로 같은 조립 함수를 쓴다).
func TestHalfHeaderConnectionIsClosedByHeaderTimeout(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	limits := defaultServerLimits
	limits.readHeader = 200 * time.Millisecond
	srv := newHTTPServer(http.NotFoundHandler(), limits, newFixture(1).opt.Log)
	go srv.Serve(ln)
	defer srv.Close()

	conn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte("GET /api/streams/s/rewind HTTP/1.1\r\nHost: x\r\n")); err != nil {
		t.Fatal(err)
	}
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	began := time.Now()
	_, err = bufio.NewReader(conn).ReadByte()
	if err == nil {
		// 서버가 408 을 쓰고 닫을 수 있다 — 닫힘까지 읽는다.
		for err == nil {
			_, err = bufio.NewReader(conn).ReadByte()
		}
	}
	if ne, ok := err.(net.Error); ok && ne.Timeout() {
		t.Fatalf("반쪽 머리 연결이 3초 안에 닫히지 않았다(%v 경과)", time.Since(began))
	}
}

// R9: Shutdown 은 Serve 가 돌아온 뒤에 돌아온다 — 그 뒤에는 새 요청이 닿지 않는다.
func TestShutdownWaitsForServe(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"vod": vodEntry("R1")})
	srv, err := Start(f.opt, RewindRoute(f.holder, f.opt))
	if err != nil {
		t.Fatal(err)
	}
	addr := srv.Addr().String()
	if err := srv.Shutdown(); err != nil {
		t.Fatalf("Shutdown = %v", err)
	}
	select {
	case <-srv.served:
	default:
		t.Fatal("Shutdown 이 Serve 반환 전에 돌아왔다")
	}
	select {
	case <-srv.swept:
	default:
		t.Fatal("Shutdown 이 청소 고루틴이 끝나기 전에 돌아왔다")
	}
	if _, err := net.DialTimeout("tcp", addr, time.Second); err == nil {
		t.Error("Shutdown 뒤에도 연결을 받는다")
	}
	if stopped := f.logs.events("control_server_stopped"); len(stopped) != 1 {
		t.Errorf("control_server_stopped = %v, want 한 줄", stopped)
	}
	if err := srv.Shutdown(); err != nil {
		t.Errorf("두 번째 Shutdown = %v, want nil", err)
	}
}

// 점유 포트면 Start 가 오류를 돌려준다 — 강등 처분(run 계속)은 조립(startControl)이 정한다.
func TestStartFailsOnBusyPort(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	f := newFixture(10)
	f.opt.Listen = ln.Addr().String()
	if srv, err := Start(f.opt, RewindRoute(f.holder, f.opt)); err == nil {
		srv.Shutdown()
		t.Fatal("점유 포트에서 Start 가 성공했다")
	}
}

// Serve 가 스스로 멈추면 ERROR control_server_failed stage=serve 한 줄이다.
func TestServeFailureIsLogged(t *testing.T) {
	f := newFixture(10)
	srv, err := Start(f.opt, RewindRoute(f.holder, f.opt))
	if err != nil {
		t.Fatal(err)
	}
	srv.ln.Close()
	<-srv.served
	srv.Shutdown()
	if failed := f.logs.events("control_server_failed"); len(failed) != 1 || failed[0]["stage"] != "serve" || failed[0]["level"] != "ERROR" {
		t.Errorf("control_server_failed = %v, want stage=serve ERROR 한 줄", failed)
	}
}

// R9: Shutdown 은 도는 중인 청소가 끝난 뒤에 돌아온다 — 청소가 칸을 만지는 동안 조립이 풀을 닫지 않게. 청소 주기를
// 짧게 주입하고 청소 하나를 막아 두면, 그 청소를 풀기 전에는 Shutdown 이 돌아오지 않는다.
func TestShutdownWaitsForRunningSweep(t *testing.T) {
	f := newFixture(10)
	f.opt.sweepEvery = time.Millisecond
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	blocking := Route{Pattern: "GET /block", Handler: http.NotFoundHandler(), bind: func(sh *shared) {
		sh.sweeps = append(sh.sweeps, func(time.Time) {
			once.Do(func() {
				close(entered)
				<-release
			})
		})
	}}
	srv, err := Start(f.opt, blocking)
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	done := make(chan struct{})
	go func() {
		srv.Shutdown()
		close(done)
	}()
	select {
	case <-done:
		t.Fatal("Shutdown 이 도는 중인 청소를 기다리지 않고 돌아왔다")
	case <-time.After(200 * time.Millisecond):
	}
	close(release)
	<-done
}
