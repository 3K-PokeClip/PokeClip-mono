// 이 파일만 package main 이다(나머지 테스트는 외부 package main_test).
//
// 왜 나누는가: 여기서 검증하는 것은 **상수와 순수 함수가 곧 계약**인 항목들이다.
// 스풀 경로 기본값·1줄 상한·잠금 대기 상한은 프로덕션에서 아무도 인자로 넘기지 않고
// 상수 그대로 쓰이므로, 그 값이 조용히 바뀌는 것을 바깥에서 exec 로 잡아내기 어렵다.
package main

import (
	"errors"
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
//
// YAML 파서를 끌어오지 않는 이유: 이 값은 한 줄 스칼라이고, 의존을 하나 더 늘리는 대가가
// 이득보다 크다. 대신 형태가 바뀌면 조용히 넘어가지 않고 여기서 실패하게 만든다 —
// 매치가 정확히 1건이 아니면 Fatal 이다. 나중에 media 서비스에도 같은 키가 붙으면
// 첫 매치만 읽어 엉뚱한 값과 대조하게 되는데, 그때 이 테스트가 먼저 멈춘다.
func hookSpoolPathFromCompose(t *testing.T, compose string) string {
	t.Helper()
	const key = "HOOK_SPOOL_PATH:"

	var found []string
	for line := range strings.SplitSeq(compose, "\n") {
		trimmed := strings.TrimSpace(line)
		if !strings.HasPrefix(trimmed, key) {
			continue
		}
		value := strings.TrimSpace(strings.TrimPrefix(trimmed, key))
		// 줄 끝 주석을 떼어 낸다. YAML 은 " #" 부터를 주석으로 본다.
		value, _, _ = strings.Cut(value, " #")
		found = append(found, strings.Trim(strings.TrimSpace(value), `"'`))
	}

	switch {
	case len(found) == 0:
		t.Fatal("docker-compose.yml 에 HOOK_SPOOL_PATH 가 없다 — 훅 어댑터가 기동하지 않는 설정이다")
	case len(found) > 1:
		t.Fatalf("docker-compose.yml 에 HOOK_SPOOL_PATH 가 %d 곳 있다(%q) — "+
			"어느 서비스의 값과 대조해야 하는지 모호하다", len(found), found)
	case found[0] == "":
		t.Fatal("docker-compose.yml 의 HOOK_SPOOL_PATH 가 비어 있다(= 훅 어댑터 미기동 설정)")
	}
	return found[0]
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

// ---------------------------------------------------------------------------
// 부분 기록 롤백
// ---------------------------------------------------------------------------

// failingTarget 은 failAfter 바이트까지만 받고 실패하는 스풀이다. ENOSPC·EIO 를 흉내 낸다.
// 실제 파일로 "디스크 가득 참"을 결정적으로 재현할 수 없어 이음매를 하나 둔다.
type failingTarget struct {
	written   []byte
	failAfter int
	size      int64
	truncated []int64
	truncErr  error // 되돌리기까지 실패하는 상황을 주입한다
}

var errDiskFull = errors.New("디스크가 가득 찼다(모의)")

func (f *failingTarget) Write(p []byte) (int, error) {
	room := f.failAfter - len(f.written)
	if room <= 0 {
		return 0, errDiskFull
	}
	if room >= len(p) {
		f.written = append(f.written, p...)
		f.size += int64(len(p))
		return len(p), nil // 상한 안이면 정상 기록
	}
	f.written = append(f.written, p[:room]...)
	f.size += int64(room)
	return room, errDiskFull
}

func (f *failingTarget) Seek(int64, int) (int64, error) { return f.size, nil }

func (f *failingTarget) Truncate(size int64) error {
	f.truncated = append(f.truncated, size)
	if f.truncErr != nil {
		return f.truncErr
	}
	f.written = f.written[:size]
	f.size = size
	return nil
}

// 중간에 실패하면 **쓰기 전 크기로 되돌려야** 한다.
//
// 되돌리지 않으면 반쪽 줄이 스풀에 남고, 그 뒤에 붙는 멀쩡한 다음 줄이 그 반쪽과
// 한 줄로 이어져 함께 버려진다 — 실패한 이벤트 1건이 아니라 2건이 사라진다.
// 롤백은 flock 을 쥐고 있는 동안 해야 다른 writer 가 그 사이에 끼지 않는다.
func TestPartialWriteIsRolledBackToPreviousSize(t *testing.T) {
	target := &failingTarget{written: []byte("prev-ok"), size: 7, failAfter: 10}

	err := writeLine(target, "/spool", []byte(strings.Repeat("x", 50)+"\n"))
	if err == nil {
		t.Fatal("기록이 실패했는데 nil 을 돌려줬다")
	}
	if len(target.truncated) != 1 || target.truncated[0] != 7 {
		t.Fatalf("Truncate 호출 = %v, 기대 [7] — 쓰기 전 크기로 되돌려야 한다", target.truncated)
	}
	if string(target.written) != "prev-ok" {
		t.Errorf("스풀 내용 = %q, 기대 %q — 반쪽 줄이 남았다", target.written, "prev-ok")
	}
}

// 되돌리기까지 실패하면 두 실패를 **한 줄로** 올려야 한다.
//
// stderr 한 줄은 이 도구의 계약이다(패키지 doc·설계 D1). 세션 훅의 stderr 는 MediaMTX
// 서버 로그에 섞여 들어가는데, 두 줄이 되면 뒷줄이 원인 없는 고아 줄로 남는다.
// errors.Join 의 기본 문자열은 개행으로 잇기 때문에 그대로 쓰면 계약이 깨진다.
func TestRollbackFailureIsReportedOnOneLine(t *testing.T) {
	truncErr := errors.New("되돌리기 장치도 죽었다(모의)")
	target := &failingTarget{written: []byte("prev-ok"), size: 7, failAfter: 10, truncErr: truncErr}

	err := writeLine(target, "/spool", []byte(strings.Repeat("x", 50)+"\n"))
	if err == nil {
		t.Fatal("되돌리기까지 실패했는데 nil 을 돌려줬다")
	}
	if strings.Contains(err.Error(), "\n") {
		t.Errorf("에러가 여러 줄이다(stderr 한 줄 계약 위반):\n%s", err.Error())
	}
	if !errors.Is(err, errDiskFull) {
		t.Errorf("원래 기록 실패가 사슬에서 사라졌다: %v", err)
	}
	if !errors.Is(err, truncErr) {
		t.Errorf("되돌리기 실패가 사슬에서 사라졌다: %v", err)
	}
}

// 정상 경로에서는 되돌리지 않는다 — 성공했는데 Truncate 를 부르면 그게 곧 데이터 유실이다.
func TestSuccessfulWriteDoesNotTruncate(t *testing.T) {
	target := &failingTarget{failAfter: 1 << 20}

	if err := writeLine(target, "/spool", []byte("hello\n")); err != nil {
		t.Fatalf("정상 기록이 실패했다: %v", err)
	}
	if len(target.truncated) != 0 {
		t.Errorf("성공했는데 Truncate 를 불렀다: %v", target.truncated)
	}
	if string(target.written) != "hello\n" {
		t.Errorf("기록 내용 = %q, 기대 %q", target.written, "hello\n")
	}
}
