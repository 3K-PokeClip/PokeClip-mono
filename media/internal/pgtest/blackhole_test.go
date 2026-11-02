package pgtest

import (
	"bufio"
	"context"
	"errors"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

// startEcho 는 받은 바이트를 그대로 돌려주는 TCP 서버다. received 는 서버가 실제로 받은
// 바이트 수다 — 막힘 동안 서버까지 아무것도 닿지 않았는지를 이 값으로 잰다.
func startEcho(t *testing.T) (addr string, received *atomic.Int64) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("에코 서버 기동 실패: %v", err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	received = new(atomic.Int64)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				buf := make([]byte, 1024)
				for {
					n, err := c.Read(buf)
					received.Add(int64(n))
					if n > 0 {
						if _, werr := c.Write(buf[:n]); werr != nil {
							return
						}
					}
					if err != nil {
						return
					}
				}
			}()
		}
	}()
	return ln.Addr().String(), received
}

func dialBlackhole(t *testing.T, b *Blackhole) net.Conn {
	t.Helper()
	c, err := net.DialTimeout("tcp", b.Addr(), 2*time.Second)
	if err != nil {
		t.Fatalf("프록시 접속 실패: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func TestBlackholeForwardsBothWaysWhenOpen(t *testing.T) {
	target, _ := startEcho(t)
	b := StartBlackhole(t, target)
	c := dialBlackhole(t, b)

	if _, err := c.Write([]byte("ping\n")); err != nil {
		t.Fatalf("쓰기 실패: %v", err)
	}
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	got, err := bufio.NewReader(c).ReadString('\n')

	if err != nil || got != "ping\n" {
		t.Fatalf("에코 = (%q, %v), want (\"ping\\n\", nil) — 열린 프록시는 양방향으로 전달해야 한다", got, err)
	}
}

// 막힘은 망 단절의 흉내다 — 보낸 바이트가 서버에 닿지 않고, 응답도 돌아오지 않으며,
// 연결은 닫히지 않는다(FIN 도 RST 도 없다). 닫혀 버리면 클라이언트가 곧바로 오류를 받아
// 「응답 없는 DB」가 아니라 「끊긴 DB」를 재게 된다.
func TestBlackholeDropsBytesWithoutClosingWhenBlocked(t *testing.T) {
	target, received := startEcho(t)
	b := StartBlackhole(t, target)
	c := dialBlackhole(t, b)
	if _, err := c.Write([]byte("a")); err != nil { // 연결을 서버까지 세운다
		t.Fatalf("쓰기 실패: %v", err)
	}
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(c, make([]byte, 1)); err != nil {
		t.Fatalf("막힘 전 에코 실패: %v", err)
	}

	b.SetBlocked(true)
	if _, err := c.Write([]byte("lost")); err != nil {
		t.Fatalf("막힘 중 쓰기 실패: %v — 막힘은 연결을 끊지 않는다", err)
	}
	_ = c.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	n, err := c.Read(make([]byte, 16))

	var ne net.Error
	if n != 0 || !errors.As(err, &ne) || !ne.Timeout() {
		t.Fatalf("막힘 중 읽기 = (%d, %v), want (0, 읽기 기한 초과) — 응답도 닫힘도 오면 안 된다", n, err)
	}
	if got := received.Load(); got != 1 {
		t.Fatalf("서버가 받은 바이트 = %d, want 1 — 막힘 중 바이트가 서버에 닿았다", got)
	}
}

// 막힘을 풀면 그 뒤에 보낸 바이트는 다시 전달된다(막힘 중 바이트는 이미 버려졌다).
func TestBlackholeForwardsAgainAfterUnblock(t *testing.T) {
	target, _ := startEcho(t)
	b := StartBlackhole(t, target)
	c := dialBlackhole(t, b)
	b.SetBlocked(true)
	if _, err := c.Write([]byte("lost\n")); err != nil {
		t.Fatalf("막힘 중 쓰기 실패: %v", err)
	}
	waitDropped(t, b, int64(len("lost\n"))) // 버려진 뒤에 푼다 — 먼저 풀면 lost 가 전달된다

	b.SetBlocked(false)
	if _, err := c.Write([]byte("back\n")); err != nil {
		t.Fatalf("쓰기 실패: %v", err)
	}
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	got, err := bufio.NewReader(c).ReadString('\n')

	if err != nil || got != "back\n" {
		t.Fatalf("막힘 해제 뒤 에코 = (%q, %v), want (\"back\\n\", nil)", got, err)
	}
}

// waitDropped 는 프록시가 막힘 중 바이트를 want 개 이상 버릴 때까지 기다린다(2s 상한).
func waitDropped(t *testing.T, b *Blackhole, want int64) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for b.Dropped() < want {
		if time.Now().After(deadline) {
			t.Fatalf("2s 안에 버린 바이트 = %d, want ≥ %d", b.Dropped(), want)
		}
		time.Sleep(time.Millisecond)
	}
}

// 끊음(Sever)은 막힘과 반대로 「끊긴 DB」다 — 열린 연결이 곧바로 닫혀 클라이언트가 읽기
// 기한 전에 오류를 받는다. 프록시는 계속 새 연결을 받는다.
func TestBlackholeSeverClosesOpenConnections(t *testing.T) {
	target, _ := startEcho(t)
	b := StartBlackhole(t, target)
	c := dialBlackhole(t, b)
	if _, err := c.Write([]byte("a")); err != nil {
		t.Fatalf("쓰기 실패: %v", err)
	}
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(c, make([]byte, 1)); err != nil {
		t.Fatalf("끊기 전 에코 실패: %v", err)
	}

	b.Sever()
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, err := c.Read(make([]byte, 16))

	var ne net.Error
	if err == nil || (errors.As(err, &ne) && ne.Timeout()) {
		t.Fatalf("끊은 뒤 읽기 오류 = %v, want 연결 종료(EOF · RST) — 기한 초과면 끊지 않은 것이다", err)
	}
	again := dialBlackhole(t, b)
	if _, err := again.Write([]byte("b\n")); err != nil {
		t.Fatalf("끊은 뒤 새 연결 쓰기 실패: %v", err)
	}
	_ = again.SetReadDeadline(time.Now().Add(2 * time.Second))
	if got, err := bufio.NewReader(again).ReadString('\n'); err != nil || got != "b\n" {
		t.Fatalf("끊은 뒤 새 연결 에코 = (%q, %v), want (\"b\\n\", nil)", got, err)
	}
}

// 정리(close)는 진행 중인 대상 접속을 취소한다 — 대상이 SYN 에 답하지 않아도 운영체제의 접속
// 시한까지 매달리지 않는다.
func TestBlackholeCloseCancelsPendingDial(t *testing.T) {
	dialing := make(chan struct{})
	b := startBlackhole(t, "192.0.2.1:9", func(ctx context.Context, _, _ string) (net.Conn, error) {
		close(dialing)
		<-ctx.Done() // 답하지 않는 대상 — 취소만이 이 접속을 끝낸다
		return nil, ctx.Err()
	})
	_ = dialBlackhole(t, b)
	select {
	case <-dialing:
	case <-time.After(2 * time.Second):
		t.Fatal("2s 안에 프록시가 대상 접속을 시작하지 않았다")
	}

	closed := make(chan struct{})
	go func() { b.close(); close(closed) }()

	select {
	case <-closed:
	case <-time.After(2 * time.Second):
		t.Fatal("close 가 2s 안에 끝나지 않았다 — 진행 중인 대상 접속을 취소하지 않는다")
	}
}
