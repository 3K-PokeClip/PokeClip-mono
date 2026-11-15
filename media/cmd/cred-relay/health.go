package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/credrelay"
)

// healthTimeout 은 healthcheck 가 /healthz 의 답을 기다리는 상한이다. 중계는 같은 호스트의 링크 로컬 주소라 ms
// 단위로 답한다 — compose healthcheck 의 기본 시한(30초)보다 짧게 둬 막힌 까닭을 이 명령의 문구로 남긴다.
const healthTimeout = 2 * time.Second

// checkHealth 는 healthcheck 하위 명령이다 — CRED_RELAY_LISTEN 의 /healthz 가 2xx 면 0, 그 밖은 1 이다(docker 는
// 2 를 예약해 둔다). 중계와 같은 컨테이너에서 돌아 같은 env 를 읽는다. 실패 까닭은 stderr 에 쓴다 — compose 가
// healthcheck 출력을 상태 기록에 남긴다. /healthz 는 인증 · 본문이 없어 까닭에 비밀이 실릴 자리가 없다.
func checkHealth(ctx context.Context, getenv func(string) string, stderr io.Writer) int {
	listen, err := listenAddr(getenv(envListen))
	if err != nil {
		fmt.Fprintf(stderr, "cred-relay healthcheck: %v\n", err)
		return exitFailure
	}
	ctx, cancel := context.WithTimeout(ctx, healthTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+listen.String()+credrelay.HealthPath, nil)
	if err != nil {
		fmt.Fprintf(stderr, "cred-relay healthcheck: %v\n", err)
		return exitFailure
	}
	resp, err := directClient().Do(req)
	if err != nil {
		fmt.Fprintf(stderr, "cred-relay healthcheck: %v\n", err)
		return exitFailure
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		fmt.Fprintf(stderr, "cred-relay healthcheck: /healthz 의 답이 2xx 가 아니다 — HTTP %d\n", resp.StatusCode)
		return exitFailure
	}
	return exitOK
}

// directClient 는 프록시를 타지 않는 클라이언트다 — 영값 Transport 는 Proxy 가 nil 이라 환경 변수의 프록시를
// 읽지 않는다. 링크 로컬 주소(창구 · IMDS)를 프록시로 돌리면 판정이 엉뚱한 곳을 본다.
func directClient() *http.Client {
	return &http.Client{Transport: &http.Transport{}}
}
