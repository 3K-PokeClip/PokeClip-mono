package pgtest

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// dialTimeout 은 프록시가 대상(DB)에 접속하는 상한이다. 정리(close)는 이 상한과 무관하게
// 진행 중인 접속을 곧바로 취소한다.
const dialTimeout = 5 * time.Second

// dialFunc 는 프록시가 대상에 붙는 방법이다 — 시험이 「답하지 않는 대상」을 만들 때만 바꾼다.
type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

// Blackhole 은 시험용 TCP 전달 프록시다. 열려 있으면 바이트를 양방향으로 그대로 옮기고,
// 막히면(SetBlocked(true)) 양방향 바이트를 버린다 — 패킷이 사라지는 망 단절의 흉내다.
//
// 막힘은 연결을 닫지 않는다(FIN 도 RST 도 보내지 않는다). 닫으면 클라이언트가 곧바로 오류를
// 받아 「응답 없는 DB」가 아니라 「끊긴 DB」를 재게 된다. 같은 이유로 막힘 중에 한쪽이
// 닫혀도 그 닫힘을 반대쪽에 전하지 않는다 — 남은 연결은 시험 종료(t.Cleanup)가 닫는다.
//
// 쓰는 곳: DB 서버 쪽 상한(예: transaction_timeout)이 클라이언트 상태와 무관하게 잠금을
// 푸는지 재는 시험. 클라이언트가 COMMIT 을 보냈는데 서버에 닿지 않는 국면을 만든다.
// 「끊긴 DB」(연결 종료)가 필요하면 Sever 를 쓴다.
type Blackhole struct {
	ln      net.Listener
	target  string
	dial    dialFunc
	blocked atomic.Bool
	dropped atomic.Int64
	// cancel 은 acceptLoop 의 ctx 를 취소한다 — close 가 진행 중인 대상 접속을 끝낸다.
	cancel context.CancelFunc

	mu     sync.Mutex
	conns  []net.Conn
	closed bool
	wg     sync.WaitGroup
}

// StartBlackhole 은 127.0.0.1 의 빈 포트에서 target 으로 전달하는 프록시를 띄운다.
// 프록시와 그 연결은 t.Cleanup 이 닫는다.
func StartBlackhole(t *testing.T, target string) *Blackhole {
	t.Helper()
	return startBlackhole(t, target, (&net.Dialer{Timeout: dialTimeout}).DialContext)
}

func startBlackhole(t *testing.T, target string, dial dialFunc) *Blackhole {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("차단 프록시 기동 실패: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	b := &Blackhole{ln: ln, target: target, dial: dial, cancel: cancel}
	b.wg.Add(1)
	go b.acceptLoop(ctx)
	t.Cleanup(b.close)
	return b
}

// Addr 은 클라이언트가 붙을 주소(host:port)다.
func (b *Blackhole) Addr() string { return b.ln.Addr().String() }

// SetBlocked 는 막힘을 켜고 끈다. 켜진 동안 오간 바이트는 버려지며, 풀어도 되살아나지 않는다.
func (b *Blackhole) SetBlocked(blocked bool) { b.blocked.Store(blocked) }

// Dropped 는 지금까지 막힘 중에 버린 바이트 수다(양방향 합). 시험이 「버려졌다」를 확인한 뒤
// 다음 단계로 가는 신호다.
func (b *Blackhole) Dropped() int64 { return b.dropped.Load() }

// Sever 는 지금 열린 연결을 모두 양쪽에서 닫는다 — 막힘(응답 없음)과 달리 「끊긴 DB」다.
// 클라이언트는 다음 읽기 · 쓰기에서 곧바로 오류를 받고, 서버는 클라이언트가 떠난 것을 안다.
// 프록시는 계속 새 연결을 받는다.
func (b *Blackhole) Sever() {
	b.mu.Lock()
	conns := b.conns
	b.conns = nil
	b.mu.Unlock()
	for _, c := range conns {
		_ = c.Close()
	}
}

func (b *Blackhole) acceptLoop(ctx context.Context) {
	defer b.wg.Done()
	for {
		client, err := b.ln.Accept()
		if err != nil {
			return // 리스너가 닫혔다 — close 가 부른 정상 종료다.
		}
		server, err := b.dial(ctx, "tcp", b.target)
		if err != nil {
			_ = client.Close()
			continue
		}
		if !b.track(client, server) {
			return
		}
		b.wg.Add(2)
		go b.pipe(server, client)
		go b.pipe(client, server)
	}
}

// track 은 연결을 정리 목록에 올린다. 이미 닫히는 중이면 둘 다 닫고 거짓을 돌려준다.
func (b *Blackhole) track(conns ...net.Conn) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		for _, c := range conns {
			_ = c.Close()
		}
		return false
	}
	b.conns = append(b.conns, conns...)
	return true
}

// pipe 는 src 에서 읽어 dst 로 옮긴다. 막힘 중이면 읽은 바이트를 버린다.
func (b *Blackhole) pipe(dst, src net.Conn) {
	defer b.wg.Done()
	buf := make([]byte, 32*1024)
	for {
		n, err := src.Read(buf)
		switch {
		case n > 0 && b.blocked.Load():
			b.dropped.Add(int64(n))
		case n > 0:
			if _, werr := dst.Write(buf[:n]); werr != nil {
				return
			}
		}
		if err == nil {
			continue
		}
		if errors.Is(err, io.EOF) && !b.blocked.Load() {
			_ = dst.Close() // 열린 동안의 닫힘은 그대로 전한다.
		}
		return
	}
}

func (b *Blackhole) close() {
	b.mu.Lock()
	b.closed = true
	conns := b.conns
	b.conns = nil
	b.mu.Unlock()

	b.cancel()
	_ = b.ln.Close()
	for _, c := range conns {
		_ = c.Close()
	}
	b.wg.Wait()
}
