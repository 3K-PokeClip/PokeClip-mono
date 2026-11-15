// Command cred-relay 는 Media 상자의 IMDS 대리 창구다(POK-574). 사이드카 이미지에 함께 들고, 하위 명령은 셋이다.
//
//	cred-relay [serve]          CRED_RELAY_LISTEN 에서 창구 토큰을 가진 호출자에게만 인스턴스 역할 자격을 준다
//	cred-relay healthcheck      같은 주소의 /healthz 가 2xx 면 0, 아니면 1 — compose healthcheck 가 부른다
//	cred-relay isolation-check  이 망 칸에서 IMDS 토큰을 못 받고 창구가 토큰 없는 요청을 401 로 거부하는지 본다
//
// 토큰 · 원천 · 캐시 · HTTP 계약 · 서버 상한은 internal/credrelay 에 있다. 이 패키지는 env 를 읽고 신호와 하위
// 명령을 배선한다.
//
// 종료 코드: serve 는 종료 신호(SIGTERM · SIGINT)로 멈추면 0, 기동 거부(설정 · 토큰 파일 · listen)와 서버가
// 스스로 멈춤은 1 이다. healthcheck 의 실패는 늘 1 이다(docker 는 2 를 예약해 둔다). isolation-check 는 격리됨 0 ·
// 격리 안 됨 1 · 판정 못 함 2 다. 사용법 오류는 2 다.
//
// 로그(stdout JSON)에는 비밀을 싣지 않는다 — 역할 자격 · IMDS 토큰 · 호출자 토큰. 기동 거부 · 서버 자신의 에러도
// 원문을 싣지 않고 닫힌 종류와 설정 이름만 싣는다(원문에 IMDS 응답 · 경로가 섞일 수 있다).
package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/credrelay"
)

// 운영 주소다. 환경 변수로 열지 않는다 — isolation-check 는 소비자 칸에서 돌아 중계의 env 가 없고, 주소가
// 바뀌면 판정이 엉뚱한 곳을 본다. relayCredentialsURL 은 compose 렌더의 CRED_RELAY_LISTEN 과 같은 자리다.
const (
	imdsEndpoint        = "http://169.254.169.254"
	relayCredentialsURL = "http://169.254.170.2:9911" + credrelay.CredentialsPath
	// isolationWait 는 isolation-check 가 IMDS 토큰 PUT · 창구의 답을 각각 기다리는 상한이다(설계 2.5 — 2초 안에
	// 토큰이 안 오면 막힘).
	isolationWait = 2 * time.Second
)

// 종료 코드다(패키지 문서).
const (
	exitOK           = 0
	exitFailure      = 1
	exitUsage        = 2
	exitUndetermined = 2 // isolation-check 가 판정하지 못했다
)

// usage 는 사용법 오류 때 stderr 에 쓰는 글이다. 받은 인자는 싣지 않는다.
const usage = "사용법: cred-relay [serve] | cred-relay healthcheck | cred-relay isolation-check"

// site 는 하위 명령이 기대는 고정 주소와 상한이다. 운영 값은 deployedSite 다. 시험만 가짜 IMDS · 창구 주소 ·
// 짧은 상한 · 미리 연 리스너를 준다.
type site struct {
	imdsEndpoint  string        // IMDS 기준 주소 — serve 의 원천과 isolation-check 가 함께 쓴다
	relayURL      string        // isolation-check 가 토큰 없이 묻는 창구 자격 경로
	isolationWait time.Duration // isolation-check 의 기다림 상한(묻는 곳마다)
	// listen 은 serve 의 리스너를 연다(운영은 net.Listen).
	listen func(network, address string) (net.Listener, error)
}

func deployedSite() site {
	return site{imdsEndpoint: imdsEndpoint, relayURL: relayCredentialsURL, isolationWait: isolationWait, listen: net.Listen}
}

func main() {
	os.Exit(run(context.Background(), deployedSite(), os.Args[1:], os.Getenv, os.Stdout, os.Stderr))
}

// run 은 args 의 하위 명령을 돌리고 종료 코드를 돌려준다. 하위 명령이 없으면 serve 다. getenv 는 환경 변수를
// 읽는다(os.Getenv). serve 의 로그와 isolation-check 의 판정 줄은 stdout 에, 사람이 볼 까닭은 stderr 에 쓴다.
func run(ctx context.Context, s site, args []string, getenv func(string) string, stdout, stderr io.Writer) int {
	name, rest := "serve", args
	if len(args) > 0 {
		name, rest = args[0], args[1:]
	}
	if len(rest) == 0 {
		switch name {
		case "serve":
			return serve(ctx, s, getenv, stdout)
		case "healthcheck":
			return checkHealth(ctx, getenv, stderr)
		case "isolation-check":
			return checkIsolation(ctx, s, stdout)
		}
	}
	fmt.Fprintln(stderr, usage)
	return exitUsage
}
