package main

// 실제 프로세스로 serve 를 띄우는 시험 도우미(c3 cx). 시험 바이너리를 자식 프로세스로 다시 띄워 serve 를 돌리면,
// 무엇이든 프로세스의 stderr(기본 log · 런타임 · os.Stderr 직접 쓰기)에 쓴 것을 부모가 그대로 받는다 — run 에
// 넘긴 버퍼만 보면 그 밖으로 새는 쓰기를 놓친다.

import (
	"context"
	"net"
	"os"
	"os/exec"
	"syscall"
	"testing"
	"time"
)

// 자식 프로세스 표지와 자식이 받는 시험 전용 env 다. 리스너는 fd 3 으로 넘긴다.
const (
	childEnv     = "CRED_RELAY_TEST_CHILD"
	childIMDSEnv = "CRED_RELAY_TEST_IMDS"
	childFD      = 3
)

func TestMain(m *testing.M) {
	if os.Getenv(childEnv) == "1" {
		os.Exit(runChild())
	}
	os.Exit(m.Run())
}

// runChild 는 자식 프로세스의 serve 다 — 부모가 넘긴 리스너(첫 Accept 가 카나리아를 실은 일시 오류)와 가짜
// IMDS 로 run 을 돌리고, 출력은 프로세스의 stdout · stderr 그대로다.
func runChild() int {
	ln, err := net.FileListener(os.NewFile(childFD, "listener"))
	if err != nil {
		return 3
	}
	s := site{
		imdsEndpoint: os.Getenv(childIMDSEnv),
		listen: func(string, string) (net.Listener, error) {
			return &flakyListener{Listener: ln}, nil
		},
	}
	return run(context.Background(), s, nil, os.Getenv, os.Stdout, os.Stderr)
}

// serveProcess 는 serve 를 자식 프로세스로 띄워 do 를 부르고 SIGTERM 으로 멈춘다. 자식의 종료 코드가 0 인지 보고
// 프로세스의 stdout · stderr 를 out · errOut 에 덧붙인다.
func serveProcess(t *testing.T, imdsURL string, out, errOut *lockedBuffer, do func(addr string)) {
	t.Helper()
	ln := localListener(t)
	f, err := ln.(*net.TCPListener).File()
	if err != nil {
		t.Fatalf("리스너 fd 꺼내기 실패: %v", err)
	}
	defer f.Close()

	cmd := exec.Command(os.Args[0])
	env := relayEnv("169.254.170.2:9911", defaultCallerDir(t))
	cmd.Env = append(os.Environ(), childEnv+"=1", childIMDSEnv+"="+imdsURL, "GOCOVERDIR="+t.TempDir())
	for k, v := range env {
		cmd.Env = append(cmd.Env, k+"="+v)
	}
	cmd.ExtraFiles = []*os.File{f} // fd 3
	cmd.Stdout, cmd.Stderr = out, errOut
	if err := cmd.Start(); err != nil {
		t.Fatalf("자식 프로세스 시작 실패: %v", err)
	}
	waited := make(chan error, 1)
	go func() { waited <- cmd.Wait() }()
	t.Cleanup(func() { _ = cmd.Process.Kill() })

	addr := ln.Addr().String()
	waitHealthy(t, addr)
	do(addr)
	if err := cmd.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatalf("SIGTERM 보내기 실패: %v", err)
	}
	select {
	case err := <-waited:
		if err != nil {
			t.Errorf("자식 serve 종료 = %v, want 0", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("자식 serve 가 SIGTERM 뒤 10초 안에 끝나지 않았다")
	}
}
