// Command mtxhookwrite 는 MediaMTX 컨테이너 안에서 훅이 발화할 때마다 exec 되는 기록 도구다.
//
// 하는 일은 하나뿐이다: 훅이 넘겨준 MTX_* 환경변수를 JSON 한 줄로 만들어 공유 볼륨의
// 스풀 파일에 덧붙인다. 판정도, 네트워크도, 재시도도 없다 — 사이드카(internal/mtxhook)가
// 그 파일을 tail 해서 읽는다.
//
// 왜 별도 바이너리인가: MediaMTX 표준 이미지는 scratch 라 셸도 curl 도 없다. 훅이 부를 수
// 있는 것은 컨테이너 안에 놓인 정적 바이너리뿐이므로 Dockerfile.mtxhook 이 이 도구를 한 층 얹는다.
//
// 실패는 조용히 넘기지 않는다. 기록을 포기할 때는 stderr 에 한 줄을 남기고 0 이 아닌 코드로
// 끝낸다 — 세션 훅이면 그 줄이 MediaMTX 서버 로그에 남아 사람이 볼 수 있는 유일한 흔적이 된다.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"os"
	"syscall"
	"time"
)

const (
	// maxLineBytes 는 개행을 포함한 1줄의 상한이다. Reader 쪽 손상 줄 폐기 상한(8192)의
	// 절반이며, 층이 다르다 — 여기가 "정상 줄이 이보다 길 수 없다"는 생산 측 계약이다.
	maxLineBytes = 4096
	// lockWaitLimit 은 flock 대기 상한이다. 초과하면 기록을 포기한다.
	//
	// 기다리지 않는 이유: 훅 프로세스가 잠금을 기다리며 체류하면 MediaMTX 안에 프로세스가
	// 쌓인다(세션 훅에는 SIGKILL 승격이 없다). 이벤트 1건을 잃는 쪽이 프로세스 누적보다 낫다.
	lockWaitLimit = 200 * time.Millisecond
	// lockRetryInterval 은 잠금 재시도 간격이다. 200ms 안에 100번 본다.
	lockRetryInterval = 2 * time.Millisecond
	// spoolFileMode 는 스풀 파일의 계약 모드다.
	//
	// 0644 여야 하는 이유: 스풀을 쓰는 것은 MediaMTX 컨테이너(root)이고 읽는 것은
	// 사이드카 컨테이너의 비특권 계정(UID 10001, media/Dockerfile)이다. umask 가 이 값을
	// 깎아 0600 이 되면 :ro 로 붙여도 Reader 가 열지 못하고 훅 채널이 무징후로 강등된다.
	spoolFileMode = 0o644
	// defaultSpoolPath 는 compose 의 hooks 볼륨 마운트 지점이다.
	defaultSpoolPath = "/hooks/events.jsonl"
	// spoolPathEnv 는 사이드카가 쓰는 것과 같은 이름이다 — 양쪽이 다른 이름을 쓰면
	// 한쪽만 옮겼을 때 조용히 어긋난다.
	spoolPathEnv = "HOOK_SPOOL_PATH"
)

// allowedKinds 는 켜 둔 훅 3종이다(설계 D2).
// 구명칭 runOnReady 는 어떤 형태로도 쓰지 않는다 — 그것은 runOnAvailable 로 매핑되며
// "읽기 가능" 축이지 세션 축이 아니다.
var allowedKinds = map[string]bool{
	"online":      true,
	"offline":     true,
	"segcomplete": true,
}

func main() {
	if err := run(os.Args[1:], os.Stderr); err != nil {
		fmt.Fprintf(os.Stderr, "mtxhookwrite: %v\n", err)
		os.Exit(1)
	}
}

func run(args []string, stderr io.Writer) error {
	// 변수명이 flags 인 것은 io/fs 를 가리지 않기 위해서다.
	flags := flag.NewFlagSet("mtxhookwrite", flag.ContinueOnError)
	flags.SetOutput(stderr)
	kind := flags.String("kind", "", "훅 종류: online | offline | segcomplete")
	spool := flags.String("spool", "", "스풀 파일 경로. 비면 $"+spoolPathEnv+", 그것도 비면 "+defaultSpoolPath)
	if err := flags.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return nil // -h 는 사용법 출력이 목적이다. 이미지에 바이너리가 실렸는지 확인하는 수단이기도 하다.
		}
		return err
	}

	line, err := buildLine(*kind, time.Now(), os.Getenv)
	if err != nil {
		return err
	}
	return appendLine(spoolPath(*spool, os.Getenv), line)
}

// spoolPath 는 플래그 → 환경변수 → 기본값 순으로 스풀 경로를 고른다.
func spoolPath(flagValue string, getenv func(string) string) string {
	if flagValue != "" {
		return flagValue
	}
	if fromEnv := getenv(spoolPathEnv); fromEnv != "" {
		return fromEnv
	}
	return defaultSpoolPath
}

// buildLine 은 훅 환경변수를 스풀 1줄(개행 포함)로 만든다. 순수 함수다.
//
// 키 이름은 MediaMTX 가 훅 프로세스에 넘기는 환경변수 이름을 그대로 쓴다 —
// 바꾸면 스풀만 보고 무엇이 원본이었는지 알 수 없게 된다. 이 형상의 반대편은
// internal/mtxhook 의 spoolLine 이다.
func buildLine(kind string, at time.Time, getenv func(string) string) ([]byte, error) {
	if !allowedKinds[kind] {
		return nil, fmt.Errorf("알 수 없는 훅 종류다: %q (online|offline|segcomplete)", kind)
	}

	// 시각은 epoch 나노초 **정수**다. 문자열 시각을 쓰면 컨테이너 TZ 설정 하나로 값이 어긋난다.
	payload := map[string]any{
		"kind":          kind,
		"at_unix_nano":  at.UnixNano(),
		"MTX_PATH":      getenv("MTX_PATH"),
		"MTX_SOURCE_ID": getenv("MTX_SOURCE_ID"),
	}
	// 세그먼트 필드는 segcomplete 에만 있다. 없는 키를 빈 값으로 채우면 줄만 길어진다.
	if kind == "segcomplete" {
		payload["MTX_SEGMENT_PATH"] = getenv("MTX_SEGMENT_PATH")
		payload["MTX_SEGMENT_DURATION"] = getenv("MTX_SEGMENT_DURATION")
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("훅 이벤트 직렬화 실패 kind=%s: %w", kind, err)
	}
	line := append(body, '\n')
	if len(line) > maxLineBytes {
		// 잘라서 쓰지 않는다 — 잘린 줄은 Reader 가 버리는 손상 줄이 되고, 그 사이에 낀
		// 정상 줄까지 함께 오염시킨다. 통째로 포기하고 흔적만 남긴다.
		return nil, fmt.Errorf("훅 이벤트 1줄이 상한을 넘었다: %dB > %dB kind=%s path=%q",
			len(line), maxLineBytes, kind, truncate(getenv("MTX_PATH")))
	}
	return line, nil
}

// appendLine 은 flock 을 잡고 한 줄을 끝까지 쓴 뒤 놓는다.
//
// O_APPEND 만으로는 부족하다 — 그 원자성은 파이프의 PIPE_BUF 규격에서 온 관행이지
// 정규 파일에 대한 표준 보증이 아니다. 다중 스트림이 동시에 발화할 때의 인터리브 방어는
// flock 이 담당한다.
func appendLine(path string, line []byte) error {
	f, err := openSpool(path)
	if err != nil {
		return err
	}
	defer f.Close()

	if err := lockExclusive(f, lockWaitLimit); err != nil {
		return err
	}
	defer syscall.Flock(int(f.Fd()), syscall.LOCK_UN)

	return writeAll(f, line)
}

// openSpool 은 스풀을 append 모드로 연다. 새로 만들었을 때만 계약 모드를 명시적으로 맞춘다.
//
// O_EXCL 로 생성 여부를 가르는 이유: 이미 있는 파일의 권한은 운영자의 것이므로 건드리지
// 않고, 우리가 만든 파일만 umask 의 영향을 되돌린다.
func openSpool(path string) (*os.File, error) {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND|os.O_CREATE|os.O_EXCL, spoolFileMode)
	switch {
	case err == nil:
		if err := f.Chmod(spoolFileMode); err != nil {
			f.Close()
			return nil, fmt.Errorf("스풀 모드 설정 실패 path=%s: %w", path, err)
		}
		return f, nil
	case errors.Is(err, fs.ErrExist):
		f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, spoolFileMode)
		if err != nil {
			return nil, fmt.Errorf("스풀 열기 실패 path=%s: %w", path, err)
		}
		return f, nil
	default:
		return nil, fmt.Errorf("스풀 생성 실패 path=%s: %w", path, err)
	}
}

// lockExclusive 는 상한 안에서 배타 잠금을 잡는다. 못 잡으면 에러다(대기하지 않는다).
func lockExclusive(f *os.File, limit time.Duration) error {
	deadline := time.Now().Add(limit)
	for {
		err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
		if err == nil {
			return nil
		}
		if !errors.Is(err, syscall.EWOULDBLOCK) {
			return fmt.Errorf("스풀 잠금 실패 path=%s: %w", f.Name(), err)
		}
		if !time.Now().Before(deadline) {
			return fmt.Errorf("스풀 잠금을 %v 안에 잡지 못해 기록을 포기한다 path=%s", limit, f.Name())
		}
		time.Sleep(lockRetryInterval)
	}
}

// writeAll 은 short write 를 스스로 이어 쓴다.
// os.File.Write 는 부분 기록을 io.ErrShortWrite 로 알려 줄 뿐 이어 쓰지 않는다 —
// 여기서 멈추면 스풀에 반쪽 줄이 남는다.
func writeAll(f *os.File, line []byte) error {
	for written := 0; written < len(line); {
		n, err := f.Write(line[written:])
		written += n
		if err == nil {
			continue
		}
		if errors.Is(err, io.ErrShortWrite) && n > 0 {
			continue // 진전이 있었으니 남은 만큼 이어 쓴다
		}
		return fmt.Errorf("스풀 기록 실패 path=%s written=%d/%d: %w", f.Name(), written, len(line), err)
	}
	return nil
}

// truncate 는 에러 메시지에 실을 값의 길이를 줄인다. 상한을 넘긴 값이 그대로 실려
// 에러 메시지가 다시 거대해지는 것을 막는다.
func truncate(s string) string {
	const limit = 64
	if len(s) <= limit {
		return s
	}
	return s[:limit] + "…"
}
