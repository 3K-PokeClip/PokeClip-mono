// Command ingest-auth 는 MediaMTX 의 HTTP 인증에 답하는 응답기다(설계 2-6). 하위 명령은 셋이다.
//
//	ingest-auth [run]        <A_auth>:8080 에서 POST /mediamtx/auth 에 답하고 127.0.0.1:8081/healthz 를 연다
//	ingest-auth healthcheck  /healthz 가 200 이면 0, 아니면 1 — compose healthcheck 가 부른다
//	ingest-auth cut <토큰>    거부 목록에 적은 토큰의 연결을 끊고 확인한다 — 출력은 한 줄
//
// 판정 · 예산 · 거부 목록 · 기록 · 절단은 internal/ingestauth 에, 4C 클라이언트는 internal/keyresolve 에, Control API
// 클라이언트는 internal/mtxctl 에 있다. 이 패키지는 설정을 읽어 그것들을 조립한다.
//
// 종료 코드는 셋이다. 0 은 할 일을 다 했다. 1 은 하다가 실패했다 — 원인을 고치고 다시 돌린다. 2 는 시작하지 않았다
// — 사용법 · 설정이 틀렸거나 cut 의 토큰이 거부 목록에 없다. healthcheck 의 실패는 늘 1 이다(docker 는 2 를 예약해
// 둔다).
//
// 비밀(4C 전용 출입증 · Control API 비번 · passphrase)은 로그 · 에러 · 출력 어디에도 싣지 않는다(설계 6절 누설 규칙).
// 비밀은 환경 변수에서 생성자로 곧바로 넘긴다. 이 패키지가 쥐는 값에는 출입증이 클라이언트의 머리 싣는 함수 안에만
// 있어, 그 값을 어느 동사 · slog · json 으로 찍어도 비밀이 나오지 않는다. 설정 에러에는 변수 이름만 싣는다 — 자리를
// 잘못 넣으면 다른 이름의 자리에 비밀이 들어갈 수 있다.
package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"time"
)

// 이미지 안의 고정 자리와 cut 의 시한이다(설계 2-6 · 2-8). 환경 변수 · 플래그로 열지 않는다 — cut 이 run 과 다른 기록
// 디렉터리를 읽으면 Since 가 과거로 잡혀 판독 중 연결이 오류 없이 빠진다(설계 3-4).
const (
	denyListFile     = "/etc/ingest-auth/deny-tokens"
	connLogDirectory = "/var/lib/ingest-auth"
	healthListen     = "127.0.0.1:8081"

	// cutDeadline 은 cut 한 번의 전체 시한이다. MediaMTX 의 SRT 서버 루프가 멈추면 Control API 가 끝없이 기다리므로
	// (포크 srt/server.go 287–300) 시한이 있어야 cut 이 끝난다. 크기는 기록 두 세대의 줄 상한(약 19,070 — 세대 1 MiB ÷
	// 줄 110 바이트 이상)을 모두 kick 하고(kick 마다 MediaMTX 가 응답기에 api 인증을 묻는다) 두 판독의 확인(2초씩)과
	// 목록을 더해도 넘지 않게 잡았다 — kick 한 번이 약 6ms 이하면 된다. kick 시간은 재지 않았다 — 실측 거리다.
	cutDeadline = 2 * time.Minute
)

// 종료 코드다(패키지 문서).
const (
	exitOK      = 0
	exitFailure = 1
	exitUsage   = 2
)

// usage 는 사용법 오류 때 stderr 에 쓰는 글이다. 받은 인자는 싣지 않는다.
const usage = "사용법: ingest-auth [run] | ingest-auth healthcheck | ingest-auth cut <토큰>"

// site 는 하위 명령이 기대는 고정 자리와 시한이다. 운영 값은 main 이 위 상수와 net.Listen 으로 채운다. 시험만 임시
// 디렉터리 · 임의 포트 · 짧은 시한을 주고, 연 리스너를 쥐어 서버가 스스로 멈춘 경우를 만든다.
type site struct {
	denyList   string        // 거부 목록 파일 — run 의 처리기 · healthz 와 cut 이 읽는다
	connLogDir string        // 허용 연결 기록 디렉터리 — run 이 쓰고 cut 이 읽는다
	healthAddr string        // healthz 리스너(루프백)
	cutTimeout time.Duration // cut 한 번의 전체 시한
	// listen 은 run 의 두 리스너를 연다.
	listen func(network, address string) (net.Listener, error)
}

func main() {
	deployed := site{denyList: denyListFile, connLogDir: connLogDirectory, healthAddr: healthListen, cutTimeout: cutDeadline, listen: net.Listen}
	os.Exit(run(context.Background(), deployed, os.Args[1:], os.Getenv, os.Stdout, os.Stderr))
}

// run 은 args 의 하위 명령을 돌리고 종료 코드를 돌려준다. 하위 명령이 없으면 run 이다. getenv 는 환경 변수를 읽는다
// (os.Getenv). 로그와 cut 의 한 줄은 stdout 에, 사람이 볼 까닭은 stderr 에 쓴다.
func run(ctx context.Context, s site, args []string, getenv func(string) string, stdout, stderr io.Writer) int {
	name, rest := "run", args
	if len(args) > 0 {
		name, rest = args[0], args[1:]
	}
	switch {
	case name == "run" && len(rest) == 0:
		return serve(ctx, s, getenv, stdout, stderr)
	case name == "healthcheck" && len(rest) == 0:
		return checkHealth(ctx, s.healthAddr, stderr)
	case name == "cut" && len(rest) == 1:
		return cut(ctx, s, rest[0], getenv, stdout, stderr)
	}
	fmt.Fprintln(stderr, usage)
	return exitUsage
}

// exitWith 는 err 를 stderr 에 한 번 쓰고 code 를 돌려준다. err 에는 비밀이 없어야 한다 — 이 패키지 · 조립하는 패키지의
// 에러는 값을 싣지 않는다.
func exitWith(stderr io.Writer, code int, err error) int {
	fmt.Fprintf(stderr, "ingest-auth: %v\n", err)
	return code
}
