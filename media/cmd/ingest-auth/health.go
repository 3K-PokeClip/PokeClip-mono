package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/ingestauth"
)

// healthCheckTimeout 은 healthcheck 가 /healthz 의 답을 기다리는 상한이다. compose healthcheck 의 기본 시한(30초)보다
// 짧게 둬, 막힌 까닭을 이 명령의 문구로 남긴다.
const healthCheckTimeout = 5 * time.Second

// maxHealthBody 는 healthcheck 가 실패 까닭으로 stderr 에 옮기는 본문의 상한이다.
const maxHealthBody = 4 << 10

// healthz 는 /healthz 처리기다(설계 2-6). 200 조건은 셋이다 — 수신 중(serving) ∧ 거부 목록을 지금 다시 읽어
// 온전함(DenyList.Check — 마지막 읽기의 상태가 아니다) ∧ 기록을 지금 다시 확인해 쓸 수 있음(ConnLog.Check — 마지막
// Append 의 상태가 아니다). auth 상태 · 예산 · 실패 토큰 기억은 넣지 않는다 — 스스로 다시 차거나 풀린다. 셋을 늘 모두
// 본다 — 거부 목록 읽기가 합집합을 키우기 때문이다(설계 3-4 1). 실패하면 503 과 까닭(한 줄에 하나)을 쓴다. 까닭은
// 경로와 시스템 호출의 이유 · 틀린 줄 수뿐이다(줄 내용은 DenyList 가 싣지 않는다).
func healthz(serving *atomic.Bool, deny *ingestauth.DenyList, conns *ingestauth.ConnLog) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		var problems []string
		if !serving.Load() {
			problems = append(problems, "인증 리스너가 받고 있지 않다")
		}
		if err := deny.Check(); err != nil {
			problems = append(problems, err.Error())
		}
		if err := conns.Check(); err != nil {
			problems = append(problems, err.Error())
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		body := "ok\n"
		if len(problems) > 0 {
			w.WriteHeader(http.StatusServiceUnavailable)
			body = strings.Join(problems, "\n") + "\n"
		}
		// 쓰기 실패는 상대가 끊은 것이다 — 이미 정한 상태를 바꿀 길이 없다.
		_, _ = io.WriteString(w, body)
	})
}

// checkHealth 는 healthcheck 하위 명령이다 — addr 의 /healthz 가 200 이면 0, 그 밖은 1 이다(docker 는 2 를 예약해
// 둔다). 실패 까닭은 stderr 에 쓴다 — compose 가 healthcheck 출력을 상태 기록에 남긴다.
func checkHealth(ctx context.Context, addr string, stderr io.Writer) int {
	ctx, cancel := context.WithTimeout(ctx, healthCheckTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+addr+"/healthz", nil)
	if err != nil {
		return exitWith(stderr, exitFailure, err)
	}
	// 영값 Transport 는 Proxy 가 nil 이라 환경 변수의 프록시를 타지 않는다.
	resp, err := (&http.Client{Transport: &http.Transport{}}).Do(req)
	if err != nil {
		return exitWith(stderr, exitFailure, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return exitOK
	}
	// 본문은 까닭을 옮기려고만 읽는다 — 읽지 못해도 판정(200 이 아님)은 같다.
	body, _ := io.ReadAll(io.LimitReader(resp.Body, maxHealthBody))
	fmt.Fprintf(stderr, "ingest-auth: /healthz 의 답이 200 이 아니다 — HTTP %d\n%s", resp.StatusCode, body)
	return exitFailure
}
