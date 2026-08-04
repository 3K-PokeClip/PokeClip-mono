// 이 파일만 package main 이다(나머지 테스트는 외부 package main_test).
//
// 왜 나누는가: 여기서 검증하는 것은 **상수와 순수 함수가 곧 계약**인 항목들이다.
// 스풀 경로 기본값·1줄 상한·잠금 대기 상한은 프로덕션에서 아무도 인자로 넘기지 않고
// 상수 그대로 쓰이므로, 그 값이 조용히 바뀌는 것을 바깥에서 exec 로 잡아내기 어렵다.
package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fixedAt 은 자릿수가 고정된 시각이다(19자리). 줄 길이를 바이트 단위로 계산하려면
// at_unix_nano 의 자릿수가 흔들리면 안 된다.
var fixedAt = time.Unix(0, 1784000000000000000)

// buildLineWithPathLen 은 MTX_PATH 를 n 바이트로 채운 줄을 만든다.
// 'a' 는 JSON 이스케이프가 없는 문자라 문자열 길이가 곧 바이트 길이다.
func buildLineWithPathLen(n int) ([]byte, error) {
	path := strings.Repeat("a", n)
	return buildLine("online", fixedAt, func(key string) string {
		if key == "MTX_PATH" {
			return path
		}
		return ""
	})
}

// pathLenForLineSize 는 완성된 줄이 정확히 want 바이트가 되는 MTX_PATH 길이를 구한다.
func pathLenForLineSize(t *testing.T, want int) int {
	t.Helper()
	base, err := buildLineWithPathLen(0)
	if err != nil {
		t.Fatalf("기준 줄 생성 실패: %v", err)
	}
	pad := want - len(base)
	if pad < 0 {
		t.Fatalf("목표 %dB 가 기준 줄 %dB 보다 짧다", want, len(base))
	}
	return pad
}

// ---------------------------------------------------------------------------
// 1줄 상한 — 설계 D1 계약은 "1줄 < 4096B(개행 포함)"다
// ---------------------------------------------------------------------------

// 상한은 **미만**이다. `>` 로 비교하면 정확히 4096B 인 줄이 통과해 계약과 1바이트 어긋나고,
// 그 1바이트가 Reader 쪽 상한(8192)과의 층 구분을 흐린다.
func TestLineExactlyAtLimitIsRejectedAndOneBelowIsAccepted(t *testing.T) {
	accepted, err := buildLineWithPathLen(pathLenForLineSize(t, maxLineBytes-1))
	if err != nil {
		t.Fatalf("%dB 줄을 거부했다: %v", maxLineBytes-1, err)
	}
	if len(accepted) != maxLineBytes-1 {
		t.Fatalf("만들어진 줄 = %dB, 기대 %dB", len(accepted), maxLineBytes-1)
	}
	if accepted[len(accepted)-1] != '\n' {
		t.Error("줄이 개행으로 끝나지 않는다")
	}

	if line, err := buildLineWithPathLen(pathLenForLineSize(t, maxLineBytes)); err == nil {
		t.Errorf("정확히 %dB 인 줄을 받아들였다(계약은 미만이다): %dB", maxLineBytes, len(line))
	}
}

// 상한값 자체가 계약이다. 늘리면 Reader 의 손상 줄 폐기 상한(8192)과의 2배 관계가 깨진다.
func TestMaxLineBytesIsFourKiB(t *testing.T) {
	if maxLineBytes != 4096 {
		t.Errorf("maxLineBytes = %d, 기대 4096", maxLineBytes)
	}
}

// ---------------------------------------------------------------------------
// 스풀 경로 — 프로덕션은 상수 하나에 전적으로 기댄다
// ---------------------------------------------------------------------------

// 프로덕션 훅 명령(infra/compose/mediamtx.yml)에는 -spool 이 없고 media 서비스에는
// HOOK_SPOOL_PATH 도 없다. 즉 **실제로 쓰이는 분기는 기본값 하나뿐**이라, 이 상수가
// 사이드카가 읽는 경로와 어긋나면 훅 채널 전체가 무징후로 죽는다.
// 사이드카 쪽 값은 docker-compose.yml 에 있으므로 그 파일을 직접 읽어 대조한다.
func TestDefaultSpoolPathMatchesComposeHookSpoolPath(t *testing.T) {
	const composeRel = "../../../docker-compose.yml"
	raw, err := os.ReadFile(composeRel)
	if err != nil {
		// skip 하지 않는다 — 파일을 못 찾으면 이 계약이 아무도 안 보는 상태가 된다.
		abs, _ := filepath.Abs(composeRel)
		t.Fatalf("docker-compose.yml 을 읽지 못했다(%s): %v", abs, err)
	}

	want := hookSpoolPathFromCompose(t, string(raw))
	if defaultSpoolPath != want {
		t.Errorf("defaultSpoolPath = %q, docker-compose.yml 의 HOOK_SPOOL_PATH = %q — "+
			"둘이 어긋나면 훅이 쓰는 곳과 사이드카가 읽는 곳이 달라진다", defaultSpoolPath, want)
	}
}

// hookSpoolPathFromCompose 는 compose 파일에서 HOOK_SPOOL_PATH 값을 뽑는다.
// YAML 파서를 끌어오지 않는 이유: 이 값은 주석 없는 한 줄 스칼라이고, 의존을 하나 더
// 늘리는 대가가 이득보다 크다. 형태가 바뀌면 여기서 실패하고 사람이 본다.
func hookSpoolPathFromCompose(t *testing.T, compose string) string {
	t.Helper()
	const key = "HOOK_SPOOL_PATH:"
	for line := range strings.SplitSeq(compose, "\n") {
		trimmed := strings.TrimSpace(line)
		if !strings.HasPrefix(trimmed, key) {
			continue
		}
		value := strings.TrimSpace(strings.TrimPrefix(trimmed, key))
		if value == "" {
			t.Fatalf("docker-compose.yml 의 HOOK_SPOOL_PATH 가 비어 있다(줄=%q)", trimmed)
		}
		return strings.Trim(value, `"'`)
	}
	t.Fatal("docker-compose.yml 에 HOOK_SPOOL_PATH 가 없다 — 훅 어댑터가 기동하지 않는 설정이다")
	return ""
}

// spoolPath 의 우선순위는 플래그 > 환경변수 > 기본값이다.
// 순서가 뒤집히면 롤백 스위치(HOOK_SPOOL_PATH="")가 기본값에 덮여 무력해진다.
func TestSpoolPathPrefersFlagThenEnvThenDefault(t *testing.T) {
	env := func(value string) func(string) string {
		return func(key string) string {
			if key == spoolPathEnv {
				return value
			}
			return ""
		}
	}

	tests := []struct {
		name   string
		flag   string
		getenv func(string) string
		want   string
	}{
		{"플래그가 최우선", "/flag.jsonl", env("/env.jsonl"), "/flag.jsonl"},
		{"플래그가 비면 환경변수", "", env("/env.jsonl"), "/env.jsonl"},
		{"둘 다 비면 기본값", "", env(""), defaultSpoolPath},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := spoolPath(tt.flag, tt.getenv); got != tt.want {
				t.Errorf("spoolPath(%q, ...) = %q, 기대 %q", tt.flag, got, tt.want)
			}
		})
	}
}

// ---------------------------------------------------------------------------
// 잠금 대기 상한
// ---------------------------------------------------------------------------

// 200ms 는 "훅 프로세스가 MediaMTX 안에 체류하는 시간의 상한"이라는 계약이다.
// 늘리면 다중 스트림 동시 발화에서 프로세스가 쌓이기 시작한다.
func TestLockWaitLimitIsTwoHundredMillis(t *testing.T) {
	if lockWaitLimit != 200*time.Millisecond {
		t.Errorf("lockWaitLimit = %v, 기대 200ms", lockWaitLimit)
	}
}
