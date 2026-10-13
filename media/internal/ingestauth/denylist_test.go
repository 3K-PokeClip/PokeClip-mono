package ingestauth

import (
	"maps"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

// newTestDenyList 는 임시 디렉터리의 거부 목록 경로와, content 로 채운 그 파일을 읽는 DenyList 를 만든다.
func newTestDenyList(t *testing.T, content string) (string, *DenyList, *logRecorder) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "deny-tokens")
	writeFile(t, path, content)
	log, rec := newLogRecorder()
	return path, NewDenyList(path, log), rec
}

// unionOf 는 d 가 지금까지 읽은 토큰(합집합)을 바이트 순으로 돌려준다.
func unionOf(d *DenyList) []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Sorted(maps.Keys(d.union))
}

// 한 번 읽은 토큰은 파일을 지운 뒤에도, 잘라 쓰는 중(빈 파일)에도 거부한다(설계 2-4 — 합집합).
// 운영자의 편집이 파일을 잠깐 비우거나 없애도 차단이 풀리지 않는다.
func TestDenyListKeepsTokensOnceRead(t *testing.T) {
	tests := []struct {
		name string
		edit func(t *testing.T, path string)
	}{
		{name: "파일을_지움", edit: func(t *testing.T, path string) { removeFile(t, path) }},
		{name: "빈_파일로_잘라_씀", edit: func(t *testing.T, path string) { writeFile(t, path, "") }},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			path, d, _ := newTestDenyList(t, testTokenA+"\n")
			if got, err := d.Contains(testTokenA); err != nil || !got {
				t.Fatalf("Setup: Contains(%q) = %v, %v, want true, nil", testTokenA, got, err)
			}
			tc.edit(t, path)
			got, err := d.Contains(testTokenA)
			if err != nil {
				t.Fatalf("Contains(%q) after the edit returned error: %v", testTokenA, err)
			}
			if !got {
				t.Errorf("Contains(%q) after the edit = false, want true (한 번 읽은 토큰은 지우지 않는다)", testTokenA)
			}
		})
	}
}

// 파일이 처음부터 없으면 빈 집합이다 — 허용하고, 온전하다.
func TestDenyListMissingFileIsEmpty(t *testing.T) {
	d := NewDenyList(filepath.Join(t.TempDir(), "deny-tokens"), discardLogger())
	got, err := d.Contains(testTokenA)
	if err != nil {
		t.Fatalf("Contains(%q) with no file returned error: %v", testTokenA, err)
	}
	if got {
		t.Errorf("Contains(%q) with no file = true, want false", testTokenA)
	}
	if err := d.Check(); err != nil {
		t.Errorf("Check() with no file = %v, want nil", err)
	}
}

// 새 DenyList(응답기 재시작)는 파일 내용부터 다시 쌓는다 — 앞 값의 합집합을 물려받지 않는다.
func TestNewDenyListStartsFromFile(t *testing.T) {
	path, before, _ := newTestDenyList(t, testTokenA+"\n")
	if got, err := before.Contains(testTokenA); err != nil || !got {
		t.Fatalf("Setup: Contains(%q) = %v, %v, want true, nil", testTokenA, got, err)
	}
	writeFile(t, path, testTokenB+"\n")

	after := NewDenyList(path, discardLogger())
	for _, tc := range []struct {
		token string
		want  bool
	}{
		{token: testTokenA, want: false},
		{token: testTokenB, want: true},
	} {
		got, err := after.Contains(tc.token)
		if err != nil {
			t.Errorf("new DenyList: Contains(%q) returned error: %v", tc.token, err)
			continue
		}
		if got != tc.want {
			t.Errorf("new DenyList: Contains(%q) = %v, want %v", tc.token, got, tc.want)
		}
	}
}

// Check 는 파일을 지금 다시 읽는다. publish 요청(Contains)이 없어도 권한을 빼면 에러이고, 되돌리면 nil 이다.
// 못 읽는 동안에는 Contains 도 에러다 — 처리기는 그 요청을 거부한다.
func TestDenyListCheckRereadsFile(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	path, d, _ := newTestDenyList(t, testTokenA+"\n")
	if err := d.Check(); err != nil {
		t.Fatalf("Setup: Check() = %v, want nil", err)
	}

	chmod(t, path, 0o000)
	if err := d.Check(); err == nil {
		t.Errorf("Check() on an unreadable file = nil, want error")
	}
	if _, err := d.Contains(testTokenA); err == nil {
		t.Errorf("Contains(%q) on an unreadable file returned nil error, want error", testTokenA)
	}

	chmod(t, path, 0o644)
	if err := d.Check(); err != nil {
		t.Errorf("Check() after restoring the permission = %v, want nil", err)
	}
}

// 거부 목록을 읽지 못해도 Contains 는 에러와 함께 지금까지 읽은 토큰(합집합)의 판정을 돌려준다 — 이미 막은 토큰은 읽기
// 실패에서도 참이라, 거부가 호출자의 에러 처리 하나에만 기대지 않는다. 읽은 적 없는 토큰은 거짓이다.
func TestDenyListContainsReportsUnionOnReadError(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	path, d, _ := newTestDenyList(t, testTokenA+"\n")
	if got, err := d.Contains(testTokenA); err != nil || !got {
		t.Fatalf("Setup: Contains(%q) = %v, %v, want true, nil", testTokenA, got, err)
	}
	chmod(t, path, 0o000)

	for _, tc := range []struct {
		token string
		want  bool
	}{
		{token: testTokenA, want: true},  // 합집합에 있다
		{token: testTokenB, want: false}, // 읽은 적 없다
	} {
		got, err := d.Contains(tc.token)
		if err == nil {
			t.Errorf("Contains(%q) on an unreadable file returned nil error, want error", tc.token)
		}
		if got != tc.want {
			t.Errorf("Contains(%q) on an unreadable file = %v, want %v (합집합의 판정)", tc.token, got, tc.want)
		}
	}
}

// Check 가 읽은 토큰도 합집합에 든다. 파일에 적고 Check 만 부른 뒤 파일을 지워도 그 토큰은 거부된다.
func TestDenyListCheckAddsToUnion(t *testing.T) {
	path, d, _ := newTestDenyList(t, testTokenA+"\n")
	if err := d.Check(); err != nil {
		t.Fatalf("Setup: Check() = %v, want nil", err)
	}
	removeFile(t, path)

	got, err := d.Contains(testTokenA)
	if err != nil {
		t.Fatalf("Contains(%q) returned error: %v", testTokenA, err)
	}
	if !got {
		t.Errorf("Contains(%q) after Check and removal = false, want true", testTokenA)
	}
}

// 줄 문법(설계 2-4 — 유효 토큰 집합): 줄마다 '#' 뒤를 버리고 앞뒤 공백을 걷는다. 빈 줄은 건너뛰고,
// 같은 토큰은 하나다. 토큰 꼴이 아닌 값은 틀린 줄이다 — Contains 는 그 줄만 건너뛰고 Check 는 에러다.
// 경계 행(CR · VT · FF · 유니코드 공백 · NUL · 끝 줄바꿈 없음)은 공유 입력 벡터가 맡는다.
func TestDenyListLineGrammar(t *testing.T) {
	tests := []struct {
		name         string
		content      string
		token        string
		want         bool
		wantCheckErr bool
	}{
		{name: "토큰_한_줄", content: testTokenA + "\n", token: testTokenA, want: true},
		{name: "앞뒤_스페이스와_탭", content: "  " + testTokenA + " \t\n", token: testTokenA, want: true},
		{name: "토큰_뒤_주석", content: testTokenA + " # 절단 메모\n", token: testTokenA, want: true},
		{name: "주석_줄의_토큰", content: "# " + testTokenA + "\n", token: testTokenA, want: false},
		{name: "빈_줄과_공백_줄", content: "\n  \t \n" + testTokenA + "\n", token: testTokenA, want: true},
		{name: "중복", content: testTokenA + "\n" + testTokenA + "\n", token: testTokenA, want: true},
		{name: "소문자_값", content: strings.ToLower(testTokenA) + "\n", token: testTokenA, want: false, wantCheckErr: true},
		{name: "25자_값", content: testTokenA[:25] + "\n", token: testTokenA, want: false, wantCheckErr: true},
		{name: "안쪽_공백", content: testTokenA[:13] + " " + testTokenA[13:] + "\n", token: testTokenA, want: false, wantCheckErr: true},
		// 틀린 줄은 그 줄만 건너뛴다 — 같은 파일의 맞는 줄은 그대로 거부한다.
		{name: "틀린_줄_옆의_토큰", content: "not-a-token\n" + testTokenB + "\n", token: testTokenB, want: true, wantCheckErr: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, d, _ := newTestDenyList(t, tc.content)
			got, err := d.Contains(tc.token)
			if err != nil {
				t.Fatalf("Contains(%q) on %q returned error: %v", tc.token, tc.content, err)
			}
			if got != tc.want {
				t.Errorf("Contains(%q) on %q = %v, want %v", tc.token, tc.content, got, tc.want)
			}
			if err := d.Check(); (err != nil) != tc.wantCheckErr {
				t.Errorf("Check() on %q = %v, want error presence = %v", tc.content, err, tc.wantCheckErr)
			}
		})
	}
}

// 다시 읽어 파일의 유효 토큰 집합이 바뀌면 ingestauth_denylist_changed(INFO)를 낸다(설계 6절).
// added · removed 는 파일의 유효 토큰 집합끼리 견준 수이고(중복 · 틀린 줄은 세지 않는다), denied 는
// 지금 거부하는 토큰 수 — 합집합의 크기다. 파일에서 빠진 토큰도 계속 거부하기 때문이다.
// 바뀐 것이 없으면 내지 않는다.
func TestDenyListLogsChanges(t *testing.T) {
	path, d, rec := newTestDenyList(t, "")
	type counts struct{ denied, added, removed int }
	steps := []struct {
		name    string
		content string // 빈 값이면 파일을 지운다
		want    []counts
	}{
		{name: "중복과_틀린_줄", content: testTokenA + "\n" + testTokenA + "\nnot-a-token\n", want: []counts{{1, 1, 0}}},
		{name: "같은_내용", content: testTokenA + "\n", want: []counts{{1, 1, 0}}},
		{name: "바꿔_씀", content: testTokenB + "\n", want: []counts{{1, 1, 0}, {2, 1, 1}}},
		{name: "지움", want: []counts{{1, 1, 0}, {2, 1, 1}, {2, 0, 1}}},
	}
	for _, step := range steps {
		if step.content == "" {
			removeFile(t, path)
		} else {
			writeFile(t, path, step.content)
		}
		if _, err := d.Contains(testTokenA); err != nil {
			t.Fatalf("%s: Contains(%q) returned error: %v", step.name, testTokenA, err)
		}
		var got []counts
		for _, e := range rec.events(t, "ingestauth_denylist_changed") {
			if e.Level != "INFO" {
				t.Errorf("%s: ingestauth_denylist_changed level = %s, want INFO", step.name, e.Level)
			}
			got = append(got, counts{e.Denied, e.Added, e.Removed})
		}
		if !slices.Equal(got, step.want) {
			t.Errorf("%s: ingestauth_denylist_changed (denied, added, removed) = %v, want %v", step.name, got, step.want)
		}
	}
}

// pastedSecret 은 운영자가 거부 목록에 passphrase 를 잘못 붙여 넣은 상황의 합성 값이다.
const pastedSecret = "Synthetic_Pass-phrase_0123456789"

// 거부 목록 게이지(설계 6절): 틀린 줄이 있으면 ingestauth_denylist_error(reason · lines), 온전히 읽으면
// (파일 없음 포함) ingestauth_denylist_recovered 다. 상태가 바뀔 때만 내고 둘 다 ERROR 다. 줄 내용은
// 싣지 않는다 — 잘못 붙여 넣은 비밀이 로그 · 에러로 새지 않게.
func TestDenyListLogsInvalidLinesGauge(t *testing.T) {
	path, d, rec := newTestDenyList(t, pastedSecret+"\n"+testTokenA+"\n")
	err := d.Check()
	if err == nil {
		t.Fatalf("Check() with an invalid line = nil, want error")
	}
	if n := strings.Count(err.Error(), pastedSecret); n > 0 {
		t.Errorf("Check() error carries the invalid line %d time(s), want 0 (error length %d)", n, len(err.Error()))
	}
	if err := d.Check(); err == nil {
		t.Errorf("second Check() with the same invalid line = nil, want error")
	}
	writeFile(t, path, testTokenA+"\n")
	if err := d.Check(); err != nil {
		t.Errorf("Check() after fixing the file = %v, want nil", err)
	}
	removeFile(t, path)
	if err := d.Check(); err != nil {
		t.Errorf("Check() after removing the file = %v, want nil", err)
	}

	errs := rec.events(t, "ingestauth_denylist_error")
	if len(errs) != 1 {
		t.Fatalf("ingestauth_denylist_error count = %d, want 1 (상태가 바뀔 때만 낸다)", len(errs))
	}
	if got, want := errs[0], (logEvent{Level: "ERROR", Msg: "ingestauth_denylist_error", Reason: "invalid_lines", Lines: 1}); got != want {
		t.Errorf("ingestauth_denylist_error = %+v, want %+v", got, want)
	}
	recovered := rec.events(t, "ingestauth_denylist_recovered")
	if len(recovered) != 1 || recovered[0].Level != "ERROR" {
		t.Errorf("ingestauth_denylist_recovered = %+v, want one event at ERROR", recovered)
	}
	if out := rec.String(); strings.Contains(out, pastedSecret) {
		t.Errorf("slog output carries the invalid line %d time(s), want 0 (output length %d)", strings.Count(out, pastedSecret), len(out))
	}
}

// 못 읽음도 같은 게이지다 — Contains 가 못 읽으면 ingestauth_denylist_error(reason=unreadable)를 내고,
// 다시 읽히면 ingestauth_denylist_recovered 를 낸다.
func TestDenyListLogsUnreadableGauge(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	path, d, rec := newTestDenyList(t, testTokenA+"\n")
	chmod(t, path, 0o000)
	if _, err := d.Contains(testTokenA); err == nil {
		t.Fatalf("Contains(%q) on an unreadable file returned nil error, want error", testTokenA)
	}
	chmod(t, path, 0o644)
	if _, err := d.Contains(testTokenA); err != nil {
		t.Fatalf("Contains(%q) after restoring the permission returned error: %v", testTokenA, err)
	}

	errs := rec.events(t, "ingestauth_denylist_error")
	if len(errs) != 1 || errs[0].Level != "ERROR" || errs[0].Reason != "unreadable" {
		t.Errorf("ingestauth_denylist_error = %+v, want one event at ERROR with reason unreadable", errs)
	}
	recovered := rec.events(t, "ingestauth_denylist_recovered")
	if len(recovered) != 1 || recovered[0].Level != "ERROR" {
		t.Errorf("ingestauth_denylist_recovered = %+v, want one event at ERROR", recovered)
	}
}

// 게이지가 선 채 사유가 바뀌면(틀린 줄 ↔ 못 읽음) ingestauth_denylist_error 를 같은 등급으로 다시 낸다 — 마지막 신호가
// 지금의 원인이라 운영자가 원인을 거꾸로 읽지 않는다. 같은 사유가 이어지면(틀린 줄 수만 바뀌어도) 내지 않는다.
func TestDenyListGaugeReemitsWhenReasonChanges(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	path, d, rec := newTestDenyList(t, "not-a-token\n"+testTokenA+"\n")
	steps := []struct {
		name string
		edit func()
	}{
		{name: "틀린_줄", edit: func() {}},
		{name: "틀린_줄_수만_바뀜", edit: func() { writeFile(t, path, "not-a-token\nstill-not\n"+testTokenA+"\n") }},
		{name: "못_읽음", edit: func() { chmod(t, path, 0o000) }},
		{name: "못_읽음_그대로", edit: func() {}},
		{name: "권한만_되돌림", edit: func() { chmod(t, path, 0o644) }}, // 틀린 줄은 남았다
		{name: "고침", edit: func() { writeFile(t, path, testTokenA+"\n") }},
	}
	for _, step := range steps {
		step.edit()
		_ = d.Check() // 결과는 다른 시험이 본다 — 이 시험은 신호만 본다
	}

	var reasons []string
	for _, e := range rec.events(t, "ingestauth_denylist_error") {
		if e.Level != "ERROR" {
			t.Errorf("ingestauth_denylist_error level = %s, want ERROR", e.Level)
		}
		reasons = append(reasons, e.Reason)
	}
	if want := []string{"invalid_lines", "unreadable", "invalid_lines"}; !slices.Equal(reasons, want) {
		t.Errorf("ingestauth_denylist_error reasons = %v, want %v (사유가 바뀔 때마다 한 번)", reasons, want)
	}
	if recovered := rec.events(t, "ingestauth_denylist_recovered"); len(recovered) != 1 {
		t.Errorf("ingestauth_denylist_recovered count = %d, want 1", len(recovered))
	}
}

// Contains(처리기 여럿)와 Check(healthz)가 동시에 돌아도 합집합 · 게이지가 경쟁하지 않는다(-race).
// 파일은 이름 바꾸기로 교체한다 — 운영자의 해제 · 틀린 줄 걷기 절차와 같은 꼴이다.
func TestDenyListConcurrentUse(t *testing.T) {
	path, d, _ := newTestDenyList(t, testTokenA+"\n")
	if err := d.Check(); err != nil {
		t.Fatalf("Setup: Check() = %v, want nil", err)
	}
	const rounds = 200
	var wg sync.WaitGroup
	wg.Go(func() {
		for i := range rounds {
			content := testTokenA + "\n"
			if i%2 == 1 {
				content += testTokenB + "\n"
			}
			if err := replaceFile(path, content); err != nil {
				t.Errorf("replaceFile(%q) returned error: %v", path, err)
				return
			}
		}
	})
	for range 4 {
		wg.Go(func() {
			for range rounds {
				if got, err := d.Contains(testTokenA); err != nil || !got {
					t.Errorf("Contains(%q) = %v, %v, want true, nil", testTokenA, got, err)
					return
				}
				if err := d.Check(); err != nil {
					t.Errorf("Check() = %v, want nil", err)
					return
				}
			}
		})
	}
	wg.Wait()
}

// replaceFile 은 content 를 같은 디렉터리의 임시 파일에 쓴 뒤 이름 바꾸기로 path 에 끼운다.
// 고루틴 안에서 부르므로 t 를 받지 않고 에러를 돌려준다.
func replaceFile(path, content string) error {
	tmp := path + ".new"
	if err := os.WriteFile(tmp, []byte(content), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// 착수 체크리스트 C2 재읽기 단언: 거부 목록을 같은 크기 · 같은 수정 시각으로 바꿔 끼운 직후의 Contains 도
// 새 내용으로 판정한다. 크기 · 수정 시각(또는 inode)으로 캐시하면 여기서 걸린다.
func TestDenyListRereadsSameSizeAndModTime(t *testing.T) {
	tests := []struct {
		name string
		swap func(path, content string) error
	}{
		{name: "이름_바꾸기로_교체", swap: replaceFile},
		{name: "제자리_덮어쓰기", swap: func(path, content string) error {
			return os.WriteFile(path, []byte(content), 0o644)
		}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			path, d, _ := newTestDenyList(t, testTokenA+"\n")
			before, err := os.Stat(path)
			if err != nil {
				t.Fatalf("Setup: os.Stat(%q) returned error: %v", path, err)
			}
			if got, err := d.Contains(testTokenB); err != nil || got {
				t.Fatalf("Setup: Contains(%q) = %v, %v, want false, nil", testTokenB, got, err)
			}

			if err := tc.swap(path, testTokenB+"\n"); err != nil {
				t.Fatalf("Setup: swap returned error: %v", err)
			}
			if err := os.Chtimes(path, before.ModTime(), before.ModTime()); err != nil {
				t.Fatalf("Setup: os.Chtimes(%q) returned error: %v", path, err)
			}
			after, err := os.Stat(path)
			if err != nil {
				t.Fatalf("Setup: os.Stat(%q) returned error: %v", path, err)
			}
			if after.Size() != before.Size() || !after.ModTime().Equal(before.ModTime()) {
				t.Fatalf("Setup: size, mtime = %d, %v, want %d, %v", after.Size(), after.ModTime(), before.Size(), before.ModTime())
			}

			got, err := d.Contains(testTokenB)
			if err != nil {
				t.Fatalf("Contains(%q) after the swap returned error: %v", testTokenB, err)
			}
			if !got {
				t.Errorf("Contains(%q) after a same-size, same-mtime swap = false, want true (부를 때마다 다시 읽는다)", testTokenB)
			}
		})
	}
}

// fifoOpenBound 는 FIFO 시험이 Contains 의 반환을 기다리는 상한이다. 통과하는 갈래는 곧바로 끝나고, 열기에서 멈추는
// 회귀일 때만 이만큼 기다린다.
const fifoOpenBound = 5 * time.Second

// 거부 목록 자리에 일반 파일이 아닌 것이 놓이면 읽기 실패다(새 연결 거부 — 게이지 unreadable). 쓰는 쪽 없는 FIFO 는
// 열기에서 멈추지 않고 곧바로 에러다 — 멈추면 잠금을 쥔 채라 처리기 · healthz · cut 의 첫 확인이 모두 멈춘다. 링크는
// 따르되 그 끝이 일반 파일이어야 한다 — 장치로 가는 링크는 에러다.
func TestDenyListRejectsNonRegularFile(t *testing.T) {
	t.Run("FIFO", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "deny-tokens")
		if err := syscall.Mkfifo(path, 0o600); err != nil {
			t.Fatalf("Setup: syscall.Mkfifo(%q) returned error: %v", path, err)
		}
		d := NewDenyList(path, discardLogger())
		done := make(chan error, 1)
		go func() {
			_, err := d.Contains(testTokenA)
			done <- err
		}()
		select {
		case err := <-done:
			if err == nil {
				t.Errorf("Contains(%q) on a FIFO returned nil error, want error", testTokenA)
			}
		case <-time.After(fifoOpenBound):
			t.Errorf("Contains(%q) on a FIFO did not return within %v — 열기에서 멈췄다", testTokenA, fifoOpenBound)
			// 쓰는 쪽으로 한 번 열고 닫아 멈춘 열기를 풀고, 그 고루틴이 끝나기를 기다린다. 읽는 쪽이 없으면 이 열기는
			// 멈추지 않고 곧바로 에러다(O_NONBLOCK).
			if f, err := os.OpenFile(path, os.O_WRONLY|syscall.O_NONBLOCK, 0); err == nil {
				f.Close() // 쓰는 쪽을 닫으면 읽는 쪽은 EOF 를 받는다 — 닫기 실패가 판정을 바꾸지 않는다
				<-done
			}
		}
	})
	t.Run("장치로_가는_링크", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "deny-tokens")
		symlink(t, os.DevNull, path)
		d := NewDenyList(path, discardLogger())
		if _, err := d.Contains(testTokenA); err == nil {
			t.Errorf("Contains(%q) on a symlink to %s returned nil error, want error", testTokenA, os.DevNull)
		}
		if err := d.Check(); err == nil {
			t.Errorf("Check() on a symlink to %s = nil, want error", os.DevNull)
		}
	})
}

// denyListOfSize 는 첫 줄이 token 이고 나머지가 주석 한 줄인, 정확히 size 바이트의 거부 목록 내용이다.
func denyListOfSize(token string, size int) string {
	head := token + "\n"
	return head + "#" + strings.Repeat("x", size-len(head)-2) + "\n"
}

// 거부 목록은 denyListMaxBytes 까지만 읽는다 — 넘는 파일은 읽기 실패다(새 연결 거부 · Check 에러). 그 파일의 토큰은
// 합집합에 넣지 않는다(잘린 내용을 판정에 쓰지 않는다). 상한과 같은 크기는 그대로 읽는다.
func TestDenyListRejectsOversizedFile(t *testing.T) {
	tests := []struct {
		name    string
		size    int
		want    bool // 그 파일의 토큰을 막는가 — 상한을 넘으면 읽지 않으므로 합집합에 없다
		wantErr bool
	}{
		{name: "상한", size: denyListMaxBytes, want: true},
		{name: "상한_넘음", size: denyListMaxBytes + 1, wantErr: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, d, _ := newTestDenyList(t, denyListOfSize(testTokenA, tc.size))
			got, err := d.Contains(testTokenA)
			if (err != nil) != tc.wantErr {
				t.Errorf("Contains(%q) on a %d-byte deny list: err = %v, want error presence = %v", testTokenA, tc.size, err, tc.wantErr)
			}
			if got != tc.want {
				t.Errorf("Contains(%q) on a %d-byte deny list = %v, want %v", testTokenA, tc.size, got, tc.want)
			}
			if err := d.Check(); (err != nil) != tc.wantErr {
				t.Errorf("Check() on a %d-byte deny list = %v, want error presence = %v", tc.size, err, tc.wantErr)
			}
		})
	}
}

// 합집합의 키는 읽은 파일의 버퍼를 붙잡지 않는다 — 큰 파일에서 읽은 토큰이 파일에서 빠진 뒤에도 그 파일만큼의 메모리가 남지
// 않는다. 상한에 꽉 찬 파일을 읽힌 거부 목록 여럿에서 토큰을 빼고 다시 읽힌 뒤 살아 있는 힙을 잰다.
func TestDenyListKeysDoNotRetainTheFileBuffer(t *testing.T) {
	const lists = 64
	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)
	ds := make([]*DenyList, lists)
	for i := range ds {
		path := filepath.Join(t.TempDir(), "deny-tokens")
		writeFile(t, path, denyListOfSize(testTokenA, denyListMaxBytes))
		ds[i] = NewDenyList(path, discardLogger())
		if got, err := ds[i].Contains(testTokenA); err != nil || !got {
			t.Fatalf("Setup: Contains(%q) on a full deny list = %v, %v, want true, nil", testTokenA, got, err)
		}
		writeFile(t, path, testTokenB+"\n") // A 가 파일에서 빠진다 — 합집합에는 남는다
		if got, err := ds[i].Contains(testTokenA); err != nil || !got {
			t.Fatalf("Setup: Contains(%q) after the edit = %v, %v, want true, nil", testTokenA, got, err)
		}
	}
	runtime.GC()
	runtime.ReadMemStats(&after)
	runtime.KeepAlive(ds)

	limit := int64(lists * denyListMaxBytes / 4)
	if retained := int64(after.HeapAlloc) - int64(before.HeapAlloc); retained > limit {
		t.Errorf("%d deny lists retain %d bytes after their token left the file, want at most %d (합집합의 키가 읽은 버퍼를 붙잡는다)", lists, retained, limit)
	}
}

// 공유 입력 벡터(설계 2-4 시험 「줄 문법」): testdata 의 입력을 DenyList 로 읽은
// 유효 토큰 집합(바이트 순 정렬 · 한 줄에 하나 · 끝 줄바꿈)과 틀린 줄 수가 기대 파일과 바이트까지 같다.
// RUNBOOK 의 셸 계수(LC_ALL=C · bash)가 같은 세 파일로 같은 결과를 내야 한다 — 이 시험은 Go 쪽 절반이다.
func TestDenyListSharedVector(t *testing.T) {
	wantTokens, err := os.ReadFile(filepath.Join("testdata", "denylist_vector_tokens.txt"))
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	wantBad, err := os.ReadFile(filepath.Join("testdata", "denylist_vector_bad_lines.txt"))
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	wantLines, err := strconv.Atoi(strings.TrimSuffix(string(wantBad), "\n"))
	if err != nil {
		t.Fatalf("Setup: denylist_vector_bad_lines.txt = %q, want a decimal line: %v", wantBad, err)
	}

	log, rec := newLogRecorder()
	d := NewDenyList(filepath.Join("testdata", "denylist_vector.txt"), log)
	checkErr := d.Check()
	if checkErr == nil {
		t.Errorf("Check() on the vector = nil, want error (틀린 줄이 있다)")
	}

	tokens := unionOf(d)
	got := strings.Join(tokens, "\n")
	if len(tokens) > 0 {
		got += "\n"
	}
	if got != string(wantTokens) {
		t.Errorf("valid token set of the vector =\n%s\nwant (denylist_vector_tokens.txt)\n%s", got, wantTokens)
	}
	errs := rec.events(t, "ingestauth_denylist_error")
	if len(errs) != 1 {
		t.Fatalf("ingestauth_denylist_error count = %d, want 1", len(errs))
	}
	if errs[0].Lines != wantLines {
		t.Errorf("invalid lines of the vector = %d, want %d (denylist_vector_bad_lines.txt)", errs[0].Lines, wantLines)
	}

	// 줄 내용은 어디에도 싣지 않는다. 벡터의 값은 모두 SYNTHET1CTEST 로 시작한다(소문자 행 포함).
	for name, out := range map[string]string{"slog output": rec.String(), "Check() error": errString(checkErr)} {
		if n := strings.Count(strings.ToUpper(out), "SYNTHET1CTEST"); n > 0 {
			t.Errorf("%s carries lines of the vector %d time(s), want 0 (length %d)", name, n, len(out))
		}
	}
}

// errString 은 err 의 문구다. nil 이면 빈 문자열이다.
func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
