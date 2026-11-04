package edgebounds

// KVS 키 · 해제 표지 키 · 패키지 의존의 단위 검증(POK-525 설계 2-3 · 3-3 · 계획 Ⓒ-1).
// 키는 엣지 함수와 공유 골든으로 맞추고, 골든 줄 규약은 두 골든(key · value)이 같은 읽기 함수로 못 박는다.

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf8"
)

// 공유 골든 testdata/key.golden.txt — Go 의 FloorKey 와 엣지 함수(dvr-floor.js) 시험이 같은 파일을 읽는다. 줄 문법:
// `#` 줄과 빈 줄은 건너뛰고, 나머지 줄은 탭 두 개로 나뉜 세 열이다 — 스트림 · 회차 · 키. 잡는 결함: 구분자(`/`) ·
// 성분 순서가 바뀌거나 성분을 고쳐 쓰면(대소문자 · 점 · 잘라 내기) 그 행이 갈린다 — 엣지가 다른 키를 물어 하한이
// 서지 않는다.
func TestFloorKeyMatchesSharedGolden(t *testing.T) {
	rows := readGolden(t, "key.golden.txt")
	if len(rows) == 0 {
		t.Fatal("골든에 행이 없다")
	}
	for _, r := range rows {
		stream, round, want := r.cols[0], r.cols[1], r.cols[2]
		if got := FloorKey(stream, round); got != want {
			t.Errorf("줄 %d: FloorKey(%.40q…, %.40q…) = %.40q…, want %.40q…", r.line, stream, round, got, want)
		}
	}
}

// 해제 표지 키는 하한 키와 겹치지 않는다(설계 2-3 · 계약 3) — 하한 키에는 `/` 가 꼭 하나 있고 표지 키에는 없다.
// 잡는 결함: 표지 키 이름이 `/` 를 품게 바뀌면 어떤 회차의 하한 키와 같아져, 해제 요청의 표지 put 이 그 회차 하한을
// 덮거나 엣지가 표지 값을 하한으로 읽는다.
func TestReleaseMarkerKeyNeverCollidesWithFloorKey(t *testing.T) {
	if ReleaseMarkerKey != "_release" {
		t.Errorf("ReleaseMarkerKey = %q, want %q(설계 2-3)", ReleaseMarkerKey, "_release")
	}
	if strings.Contains(ReleaseMarkerKey, "/") {
		t.Fatalf("ReleaseMarkerKey %q 에 `/` 가 있다 — 하한 키와 겹칠 수 있다", ReleaseMarkerKey)
	}
	for _, r := range readGolden(t, "key.golden.txt") {
		key := r.cols[2]
		if n := strings.Count(key, "/"); n != 1 {
			t.Errorf("줄 %d: 키 %.40q… 의 `/` 가 %d개, want 1", r.line, key, n)
		}
		if key == ReleaseMarkerKey {
			t.Errorf("줄 %d: 키가 해제 표지 키와 같다", r.line)
		}
	}
}

// 골든의 줄 규약(머리말)을 어긴 파일은 읽기 오류다 — 두 시험(Go · node)이 같은 바이트를 같은 행으로 읽어야 하기 때문이다.
// 잡는 결함: BOM · CR · NUL · UTF-8 아닌 바이트 · 열 수 · 빈 열 · 열 앞뒤 공백 가운데 하나라도 그냥 지나가면 node 의 줄
// 나누기와 Go 의 줄 나누기가 다른 행을 볼 수 있다(CRLF 파일이면 node 쪽 마지막 열 끝에 \r 이 남는다).
func TestParseGoldenRejectsLineRuleViolations(t *testing.T) {
	const ok = "s\tr\ts/r\n"
	if rows, err := parseGolden([]byte("# 머리\n\n" + ok)); err != nil || len(rows) != 1 {
		t.Fatalf("규약을 지킨 골든 = (%d행, %v), want (1행, nil)", len(rows), err)
	}
	for _, c := range []struct {
		name string
		data string
	}{
		{"BOM", "\uFEFF" + ok},
		{"CRLF", strings.TrimSuffix(ok, "\n") + "\r\n"},
		{"CR_머리줄", "# 머리\r\n" + ok},
		{"NUL", "s\x00\tr\ts/r\n"},
		{"UTF-8_아님", "s\tr\t\xff\n"},
		{"열_둘", "s\ts/r\n"},
		{"열_넷", "s\tr\ts/r\tx\n"},
		{"빈_첫_열", "\tr\ts/r\n"},
		{"빈_가운데_열", "s\t\ts/r\n"},
		{"빈_끝_열", "s\tr\t\n"},
		{"앞_공백", " s\tr\ts/r\n"},
		{"뒤_공백", "s\tr \ts/r\n"},
	} {
		if _, err := parseGolden([]byte(c.data)); err == nil {
			t.Errorf("%s: 규약을 어긴 골든이 읽혔다", c.name)
		}
	}
}

// 이 패키지의 의존 목록에 장부(internal/index)와 AWS SDK 가 없다(계획 Ⓒ-1 · P3 · K9).
//
// 장부가 이 패키지의 Ledger 를 구현하므로(index.EdgeBoundsLedger) 이 패키지가 장부를 끌어오면 순환이다. KVS 운영
// 구현은 SDK 를 쓰므로 하위 패키지 kvsstore 에 둔다 — 하위 패키지는 이 패키지가 import 하지 않는 한 이 목록에 들지
// 않는다. 잡는 결함: 계약 파일이나 같은 패키지의 다음 파일이 장부 · SDK 를 import 하면 FAIL.
//
// go list 는 사용자 환경을 물려받지 않게 publish 의 s3list_static_test.go 와 같은 환경 고정을 쓴다. -e 를 쓰지 않아
// import 를 찾지 못하면 오류다.
func TestPackageDependsOnNeitherIndexNorAWSSDK(t *testing.T) {
	goBin, err := exec.LookPath("go")
	if err != nil {
		t.Fatalf("Setup: go 를 찾지 못했다: %v", err)
	}
	cmd := exec.Command(goBin, "list", "-deps", ".")
	cmd.Env = append(os.Environ(), "GOWORK=off", "GOTOOLCHAIN=local", "GOENV=off", "GOFLAGS=-mod=readonly")
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("Setup: go list 실패: %v\n%s", err, strings.TrimSpace(stderr.String()))
	}
	const module = "github.com/3K-PokeClip/pokeclip-mono/media"
	deps := strings.Fields(string(out))
	sawSelf := false
	for _, d := range deps {
		switch {
		case d == module+"/internal/rewind/edgebounds":
			sawSelf = true
		case d == module+"/internal/index" || strings.HasPrefix(d, module+"/internal/index/"):
			t.Errorf("의존 목록에 장부 패키지 %s 가 있다 — index 가 이 패키지를 구현하므로 순환이다", d)
		case strings.HasPrefix(d, "github.com/aws/"):
			t.Errorf("의존 목록에 AWS SDK 패키지 %s 가 있다 — KVS 운영 구현은 하위 패키지 kvsstore 에 둔다(K9)", d)
		}
	}
	// 빈 결과로 통과하지 않게 이 패키지 자신이 목록에 있는지 본다.
	if !sawSelf {
		t.Fatalf("go list -deps 결과에 이 패키지가 없다(%d줄) — 다른 패키지를 훑었다", len(deps))
	}
}

// goldenRow 는 공유 골든의 한 행이다.
type goldenRow struct {
	line int
	cols []string
}

// readGolden 은 testdata 의 공유 골든 name 을 읽는다. 줄 규약을 어긴 파일은 시험 실패다 — 엣지 함수 시험이 같은
// 파일을 같은 문법으로 읽으므로 여기서 문법을 못 박는다.
func readGolden(t *testing.T, name string) []goldenRow {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	rows, err := parseGolden(data)
	if err != nil {
		t.Fatalf("Setup: %s: %v", name, err)
	}
	return rows
}

// goldenCols 는 두 골든(key · value)의 열 수다.
const goldenCols = 3

// parseGolden 은 골든 바이트 data 를 머리말의 줄 규약대로 행으로 푼다 — UTF-8 · BOM 없음 · LF 전용 · NUL 없음이고,
// `#` 줄과 빈 줄을 뺀 줄은 탭 두 개로 나뉜 세 열이며 열은 비지 않고 앞뒤 공백이 없다. 어기면 줄 번호를 실은 오류다.
// 열의 뜻(판정 짝 등)은 각 골든의 시험이 본다.
func parseGolden(data []byte) ([]goldenRow, error) {
	switch {
	case !utf8.Valid(data):
		return nil, errors.New("골든이 UTF-8 이 아니다")
	case bytes.HasPrefix(data, []byte("\uFEFF")):
		return nil, errors.New("골든 앞에 BOM 이 있다")
	case bytes.ContainsAny(data, "\r\x00"):
		return nil, errors.New("골든에 CR 또는 NUL 이 있다 — LF 전용 · NUL 없음")
	}
	var rows []goldenRow
	for i, line := range strings.Split(string(data), "\n") {
		n := i + 1
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		cols := strings.Split(line, "\t")
		if len(cols) != goldenCols {
			return nil, fmt.Errorf("골든 줄 %d: 열 %d개 — 탭 두 개로 나뉜 세 열이어야 한다", n, len(cols))
		}
		for j, c := range cols {
			if c == "" || strings.TrimSpace(c) != c {
				return nil, fmt.Errorf("골든 줄 %d: %d번째 열 %q — 열은 비지 않고 앞뒤 공백이 없어야 한다", n, j+1, c)
			}
		}
		rows = append(rows, goldenRow{line: n, cols: cols})
	}
	return rows, nil
}
