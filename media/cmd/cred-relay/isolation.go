package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"
)

// isolation-check 의 IMDS 판정이다(설계 2.5 · 8절 — 판정 줄 CRED ISOLATION imds_token=… relay=…).
const (
	verdictBlocked   = "blocked"   // 토큰 PUT 의 답(머리)이 기다림 상한 안에 오지 않았다(홉 1 로 응답이 버려짐)
	verdictReachable = "reachable" // 토큰을 받았다 — 이 칸은 IMDS 에 닿는다
	verdictError     = "error"     // 판정 못 함(연결 거부 · 토큰이 아닌 답 · 200 뒤 본문 실패 등) — relay 쪽은 답이 없음
)

// relayDenied 는 창구가 토큰 없는 요청에 내야 하는 상태다.
var relayDenied = strconv.Itoa(http.StatusUnauthorized)

// IMDSv2 토큰 PUT 꼴이다. TTL 은 허용 최솟값(1초)이다 — 받은 토큰은 읽고 버리지만 오래 살 까닭이 없다.
const (
	imdsTokenPath      = "/latest/api/token"
	imdsTokenTTLHeader = "X-aws-ec2-metadata-token-ttl-seconds"
	imdsTokenTTL       = "1"
)

// maxProbeBody 는 판정 때 읽는 본문의 상한이다. 본문은 판정에만 쓰고 출력에 싣지 않는다(IMDS 토큰 · 자격).
const maxProbeBody = 4 << 10

// isolation 은 isolation-check 한 번의 판정이다. relay 는 창구 답의 상태 코드(예 401) 또는 verdictError 다.
type isolation struct {
	imdsToken string
	relay     string
}

// exitCode 는 판정의 종료 코드다. 하나라도 「격리 안 됨」 이 확정이면(IMDS 토큰을 받음 · 창구가 401 이 아닌 답)
// 1, 그렇지 않고 판정 못 한 것이 있으면 2, 둘 다 기대대로면 0 이다.
func (r isolation) exitCode() int {
	switch {
	case r.imdsToken == verdictReachable || (r.relay != verdictError && r.relay != relayDenied):
		return exitFailure
	case r.imdsToken == verdictError || r.relay == verdictError:
		return exitUndetermined
	}
	return exitOK
}

// checkIsolation 은 isolation-check 하위 명령이다 — 이 망 칸에서 ① IMDS 토큰 PUT 이 s.isolationWait 안에 토큰을
// 내지 않고 ② 창구가 토큰 없는 자격 요청을 401 로 거부하는지 보고, 판정 줄 하나를 stdout 에 쓴다.
func checkIsolation(ctx context.Context, s site, stdout io.Writer) int {
	r := isolation{
		imdsToken: probeIMDSToken(ctx, s.imdsEndpoint, s.isolationWait),
		relay:     probeRelay(ctx, s.relayURL, s.isolationWait),
	}
	fmt.Fprintf(stdout, "CRED ISOLATION imds_token=%s relay=%s\n", r.imdsToken, r.relay)
	return r.exitCode()
}

// probeIMDSToken 은 endpoint 에 IMDSv2 토큰 PUT 을 보내 wait 안에 토큰(200 · 빈 본문 아님)이 오면 reachable,
// 머리가 오지 않으면(연결 · 머리에서 상한 초과) blocked, 그 밖(연결 거부 · 다른 상태 · 빈 본문 · 200 뒤 본문
// 실패)은 error 다. 상태는 본문보다 먼저 본다 — 머리를 받았으면 홉이 응답을 버리지 않은 것이라, 본문이 늦거나
// 끊겨도 막힌 것이 아니다.
func probeIMDSToken(ctx context.Context, endpoint string, wait time.Duration) string {
	ctx, cancel := context.WithTimeout(ctx, wait)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, endpoint+imdsTokenPath, nil)
	if err != nil {
		return verdictError
	}
	req.Header.Set(imdsTokenTTLHeader, imdsTokenTTL)
	resp, err := directClient().Do(req)
	if err != nil {
		return timedOutOr(ctx)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return verdictError
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxProbeBody))
	if err != nil {
		return verdictError
	}
	if len(bytes.TrimSpace(body)) == 0 {
		return verdictError
	}
	return verdictReachable
}

// timedOutOr 는 실패가 기다림 상한 초과면 blocked, 아니면 error 다.
func timedOutOr(ctx context.Context) string {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return verdictBlocked
	}
	return verdictError
}

// probeRelay 는 url 에 Authorization 없이 GET 을 보내 답의 상태 코드를 돌려준다. 답이 없으면 error 다. 본문은
// 읽어 버린다 — 200 이면 자격이 들어 있다.
func probeRelay(ctx context.Context, url string, wait time.Duration) string {
	ctx, cancel := context.WithTimeout(ctx, wait)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return verdictError
	}
	resp, err := directClient().Do(req)
	if err != nil {
		return verdictError
	}
	defer resp.Body.Close()
	// 본문은 연결을 깨끗이 닫으려고만 읽는다 — 읽지 못해도 판정(상태 코드)은 같다.
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, maxProbeBody))
	return strconv.Itoa(resp.StatusCode)
}
