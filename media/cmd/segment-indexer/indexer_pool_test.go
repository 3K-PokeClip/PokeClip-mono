package main

// 인덱서 풀 기동(openIndexerPool)의 시험(POK-311) — run 이 DB 에 붙는 첫 단계다. 성공하면 db_connected 한 줄(배포 확인의 판정
// 줄)을 남기고, 발행자 풀이 Copy 해 쓸 풀 설정을 돌려준다. 실패하면 줄 없이 오류다(사이드카 기동 실패 → 재기동이 회복).

import (
	"log/slog"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgconf"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// settingsOf 는 env 를 기동 경로(pgconf.FromEnv)로 지난 DB 설정이다.
func settingsOf(t *testing.T, env map[string]string) pgconf.Settings {
	t.Helper()
	s, err := pgconf.FromEnv(func(k string) string { return env[k] })
	if err != nil {
		t.Fatalf("POSTGRES_* 설정 실패: %v", err)
	}
	return s
}

// 닿지 않는 DB 면 db_connected 없이 오류다. 오류에 비밀번호가 실리지 않는다.
func TestOpenIndexerPoolFailsWithoutConnectedLine(t *testing.T) {
	const secret = "c2-canary-pw"
	s := settingsOf(t, map[string]string{
		"POSTGRES_USER": "u", "POSTGRES_PASSWORD": secret, "POSTGRES_DB": "d",
		"POSTGRES_HOST": "127.0.0.1", "POSTGRES_PORT": "1", "POSTGRES_SSLMODE": "disable",
	})
	logs := &logCapture{}

	pool, base, err := openIndexerPool(t.Context(), s, slog.New(logs), dbStepTimeout)

	if err == nil || pool != nil || base != nil {
		t.Fatalf("openIndexerPool = 풀 %v · 설정 %v · 오류 %v, want 오류만", pool, base, err)
	}
	if n := logs.count(pgconf.ConnectedEvent); n != 0 {
		t.Errorf("%s %d줄, want 0 — 줄은 Ping 성공 뒤에만 남는다", pgconf.ConnectedEvent, n)
	}
	if strings.Contains(err.Error(), secret) {
		t.Error("오류에 비밀번호가 실렸다")
	}
}

// 실 PG — 인덱서 풀이 붙으면 db_connected 한 줄(pool=indexer · 접속 값)을 남기고, 돌려준 풀 설정의 Copy 로 만든 두 번째 풀
// (발행자 풀 자리)도 같은 DB 에 붙는다.
func TestOpenIndexerPoolLogsDBConnected(t *testing.T) {
	db := newWireTestPool(t)
	env := pgtest.PostgresEnv(t, db.Config().ConnConfig.Database)
	s := settingsOf(t, env)
	logs := &logCapture{}

	pool, base, err := openIndexerPool(t.Context(), s, slog.New(logs), dbStepTimeout)
	if err != nil {
		t.Fatalf("openIndexerPool: %v", err)
	}
	t.Cleanup(pool.Close)

	if n := logs.count(pgconf.ConnectedEvent); n != 1 {
		t.Fatalf("%s %d줄, want 1", pgconf.ConnectedEvent, n)
	}
	got := logs.attrs(pgconf.ConnectedEvent)
	port, _ := strconv.Atoi(env["POSTGRES_PORT"])
	want := map[string]any{
		"pool": "indexer", "auth": "password", "sslmode": s.SSLMode,
		"user": env["POSTGRES_USER"], "host": env["POSTGRES_HOST"], "port": int64(port), "db": env["POSTGRES_DB"],
	}
	if s.SSLMode == "disable" {
		want["tls"] = false
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s[%s] = %v(%T), want %v(%T)", pgconf.ConnectedEvent, k, got[k], got[k], v, v)
		}
	}
	if base == nil || base.ConnConfig.Database != env["POSTGRES_DB"] {
		t.Fatalf("돌려준 풀 설정 = %v, want 전용 테스트 DB 설정", base)
	}
	second, err := pgxpool.NewWithConfig(t.Context(), base.Copy())
	if err != nil {
		t.Fatalf("돌려준 풀 설정의 Copy 로 풀 생성 실패: %v", err)
	}
	t.Cleanup(second.Close)
	if err := second.Ping(t.Context()); err != nil {
		t.Errorf("돌려준 풀 설정의 Copy 로 만든 풀(발행자 풀 자리)이 붙지 못했다: %v", err)
	}
}

// silentListener 는 TCP 연결을 받기만 하고 한 바이트도 답하지 않는 서버다 — 붙었지만 응답하지 않는 DB 자리다.
// 받은 연결은 시험이 끝날 때 닫는다(멈춘 클라이언트가 그때 풀린다). 정리는 Accept 고루틴이 끝나기를 기다린 뒤
// 연결 목록을 회수한다 — 닫기 직전에 받은 연결이 목록을 다 돈 뒤에 덧붙어 닫히지 않고 남는 경쟁이 없다.
func silentListener(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var conns []net.Conn
	acceptDone := make(chan struct{})
	go func() {
		defer close(acceptDone)
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			conns = append(conns, c)
		}
	}()
	t.Cleanup(func() {
		ln.Close()
		<-acceptDone
		for _, c := range conns {
			c.Close()
		}
	})
	return ln.Addr().(*net.TCPAddr).AddrPort().String()
}

// 연결 확인에도 상한이 선다(C2 보안 검수 L — janitor 와 같은 단계 상한). 붙었지만 답하지 않는 DB 에서 부모 ctx 에
// 기한이 없어도 상한 뒤 db_connected 없이 오류로 돌아온다 — 기동이 끝없이 걸리지 않고 재기동이 회복한다.
func TestOpenIndexerPoolBoundsConnectionCheck(t *testing.T) {
	host, port, _ := strings.Cut(silentListener(t), ":")
	s := settingsOf(t, map[string]string{
		"POSTGRES_USER": "u", "POSTGRES_PASSWORD": "pw", "POSTGRES_DB": "d",
		"POSTGRES_HOST": host, "POSTGRES_PORT": port, "POSTGRES_SSLMODE": "disable",
	})
	logs := &logCapture{}
	const step = 200 * time.Millisecond
	done := make(chan error, 1)

	go func() {
		_, _, err := openIndexerPool(t.Context(), s, slog.New(logs), step)
		done <- err
	}()

	select {
	case err := <-done:
		if err == nil {
			t.Fatal("openIndexerPool 오류 nil, want 연결 확인 시간 초과")
		}
	case <-time.After(10 * time.Second):
		t.Fatalf("openIndexerPool 이 단계 상한 %v 의 50배가 지나도 돌아오지 않았다 — 연결 확인에 상한이 없다", step)
	}
	if n := logs.count(pgconf.ConnectedEvent); n != 0 {
		t.Errorf("%s %d줄, want 0", pgconf.ConnectedEvent, n)
	}
}
