package endsignal

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/control"
)

// P15 — 실제 control 서버(Start — 운영 시한 · 머리 크기)에 Route 를 넣고, 머리 뒤 본문을 2초에 걸쳐 보내는 원시 연결은
// 처리기 진입 뒤 1.1초 안에 503 body_timeout 을 받는다.
func TestSlowBodyIs503ThroughControlServerP15(t *testing.T) {
	f := newFixture(t, nil)
	srv, err := control.Start(control.Options{Listen: "127.0.0.1:0"}, f.recv.Route())
	if err != nil {
		t.Fatalf("control.Start: %v", err)
	}
	t.Cleanup(func() { _ = srv.Shutdown() })

	conn, err := net.Dial("tcp", srv.Addr().String())
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}
	defer conn.Close()
	body := signalBody(testToken, 420, 1000)
	head := "POST /api/ingest/end-signal HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n" +
		"Authorization: Bearer " + testBearer + "\r\nContent-Length: " + strconv.Itoa(len(body)) + "\r\n\r\n"
	if _, err := io.WriteString(conn, head); err != nil {
		t.Fatalf("머리 쓰기: %v", err)
	}
	start := time.Now()
	go func() {
		for i := 0; i < len(body); i++ {
			if _, err := conn.Write([]byte{body[i]}); err != nil {
				return
			}
			time.Sleep(2 * time.Second / time.Duration(len(body)))
		}
	}()
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatalf("응답 읽기: %v", err)
	}
	resp.Body.Close()
	if el := time.Since(start); el > 1100*time.Millisecond {
		t.Fatalf("응답까지 %v, want ≤ 1.1s", el)
	}
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("상태 = %d, want 503", resp.StatusCode)
	}
	if l := f.lastEndSignal(); l.Reason != "body_timeout" {
		t.Fatalf("end_signal 줄 = %+v", l)
	}
	if f.resolveCalls.Load() != 0 {
		t.Fatalf("본문 시한에 걸린 요청이 resolve 를 불렀다")
	}
}

// plainWriter 는 Unwrap 이 없는 감싼 ResponseWriter 다 — 본문 읽기 마감을 걸 수 없다.
type plainWriter struct{ http.ResponseWriter }

// countingReader 는 읽힌 횟수를 센다.
type countingReader struct {
	r     io.Reader
	reads atomic.Int32
}

func (c *countingReader) Read(p []byte) (int, error) {
	c.reads.Add(1)
	return c.r.Read(p)
}

// P15 덧붙임 — 본문 마감을 걸 수 없으면(ErrNotSupported) 본문을 읽지 않고 503 · ERROR body_deadline_unsupported 다.
func TestDeadlineUnsupportedIs503WithoutReadingP15(t *testing.T) {
	f := newFixture(t, nil)
	body := &countingReader{r: strings.NewReader(signalBody(testToken, 420, 1000))}
	req := httptest.NewRequestWithContext(context.Background(), http.MethodPost, "/api/ingest/end-signal", body)
	req.Header.Set("Authorization", "Bearer "+testBearer)
	rec := httptest.NewRecorder()
	f.recv.Route().Handler.ServeHTTP(plainWriter{rec}, req)
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("상태 = %d, want 503", rec.Code)
	}
	if n := body.reads.Load(); n != 0 {
		t.Fatalf("본문 읽기 = %d, want 0", n)
	}
	if l := f.lastEndSignal(); l.Reason != "body_deadline_unsupported" || l.Level != "ERROR" {
		t.Fatalf("end_signal 줄 = %+v", l)
	}
}
