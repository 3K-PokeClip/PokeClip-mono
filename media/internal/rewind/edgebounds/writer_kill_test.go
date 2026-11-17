package edgebounds

// 프로세스가 죽은 뒤의 해제 마무리(POK-525 설계 2-3 · 8-2 E2 · 계획 Ⓒ-4). 해제를 원장 상태로 몰므로, KVS 키를 지운 뒤
// MarkReleased 전에 SIGKILL 을 받아도 새 프로세스가 원장만 읽고 마무리한다. 원장 · KVS 가짜는 파일로 상태를 넘긴다.

import (
	"bytes"
	"context"
	"flag"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"testing"
	"time"
)

// releaseChildEnv 가 비지 않은 프로세스는 이 시험의 자식이다 — 값은 단계(kill · finish), releaseDirEnv 는 상태 파일
// 디렉터리다.
const (
	releaseChildEnv = "EDGEBOUNDS_RELEASE_CHILD"
	releaseDirEnv   = "EDGEBOUNDS_RELEASE_DIR"
)

// releaseChildTimeout 은 자식 하나의 시한이다 — 멈추면 이 시한에 고루틴 덤프를 남기고 끝난다.
const releaseChildTimeout = time.Minute

// 상태 파일 · 표지 파일 이름.
const (
	ledgerFile  = "ledger.json"
	storeFile   = "store.json"
	deletedFile = "deleted" // kill 단계 자식이 KVS 지우기를 반영하고 MarkReleased 에 들어섰다는 표지
)

// 키 지움 뒤 · MarkReleased 전 SIGKILL → 새 프로세스가 원장을 읽어 마무리한다(E2). 잡는 결함: 해제할 목록을 프로세스
// 메모리(관측 이력 · 앞 틱 결과)에서 얻는 것 — 새 프로세스는 그 회차를 모르므로 원장 행이 영영 미해제로 남아 VOD 가
// 끝남 대기에 묶인다.
func TestReleaseFinishesInNewProcessAfterKillBeforeMarkReleased(t *testing.T) {
	if stage := os.Getenv(releaseChildEnv); stage != "" {
		runReleaseChild(t, stage, os.Getenv(releaseDirEnv))
		return
	}
	dir := t.TempDir()
	seedReleaseFiles(t, dir)

	kill := releaseChild(t, "kill", dir)
	var out bytes.Buffer
	kill.Stdout, kill.Stderr = &out, &out
	if err := kill.Start(); err != nil {
		t.Fatalf("Setup: kill 단계 자식 시작 실패: %v", err)
	}
	if !waitFile(filepath.Join(dir, deletedFile), 30*time.Second) {
		_ = kill.Process.Kill()
		_ = kill.Wait()
		t.Fatalf("kill 단계 자식이 30초 안에 KVS 지우기에 닿지 않았다:\n%s", out.String())
	}
	if err := kill.Process.Kill(); err != nil { // 유닉스에서 SIGKILL
		t.Fatalf("SIGKILL 실패: %v", err)
	}
	_ = kill.Wait()

	l, st := loadReleaseFiles(t, dir)
	if _, found := st.value("s/r"); found {
		t.Fatal("Setup: SIGKILL 전에 KVS 키가 지워지지 않았다")
	}
	if row, _ := l.row("s", "r"); row.ReleasedAt != nil {
		t.Fatal("Setup: SIGKILL 전에 원장 행이 해제됐다 — MarkReleased 전에 죽이지 못했다")
	}

	finish := releaseChild(t, "finish", dir)
	if got, err := finish.CombinedOutput(); err != nil || !bytes.Contains(got, []byte("--- PASS: "+t.Name()+" (")) {
		t.Fatalf("finish 단계 자식 실패(%v):\n%s", err, got)
	}
	l, st = loadReleaseFiles(t, dir)
	if _, found := st.value("s/r"); found {
		t.Error("새 프로세스 뒤에도 KVS 키가 있다")
	}
	if row, ok := l.row("s", "r"); !ok || row.ReleasedAt == nil {
		t.Errorf("새 프로세스가 원장 행을 해제하지 않았다: %+v(있음 %v)", row, ok)
	}
}

// seedReleaseFiles 는 닫힌 회차 s/r(하한 7 · KVS 에 키 있음) 하나의 상태 파일을 쓴다.
func seedReleaseFiles(t *testing.T, dir string) {
	t.Helper()
	l := newFakeLedger(time.Now)
	l.seed("s", "r", 7)
	st := newFakeStore()
	st.seed("s/r", "7")
	if err := writeJSONAtomic(filepath.Join(dir, ledgerFile), l.st); err != nil {
		t.Fatalf("Setup: %v", err)
	}
	if err := writeJSONAtomic(filepath.Join(dir, storeFile), st.st); err != nil {
		t.Fatalf("Setup: %v", err)
	}
}

func loadReleaseFiles(t *testing.T, dir string) (*fakeLedger, *fakeStore) {
	t.Helper()
	l, err := loadFakeLedger(filepath.Join(dir, ledgerFile), time.Now)
	if err != nil {
		t.Fatalf("원장 상태 읽기: %v", err)
	}
	st, err := loadFakeStore(filepath.Join(dir, storeFile))
	if err != nil {
		t.Fatalf("KVS 상태 읽기: %v", err)
	}
	return l, st
}

// releaseChild 는 이 시험 하나만 도는 자식 프로세스 명령이다. go test -cover 면 같은 -test.gocoverdir 를 넘긴다.
func releaseChild(t *testing.T, stage, dir string) *exec.Cmd {
	t.Helper()
	exe, err := os.Executable()
	if err != nil {
		t.Fatalf("Setup: os.Executable returned error: %v", err)
	}
	args := []string{"-test.run=^" + regexp.QuoteMeta(t.Name()) + "$", "-test.v", "-test.timeout=" + releaseChildTimeout.String()}
	if f := flag.Lookup("test.gocoverdir"); f != nil && f.Value.String() != "" {
		args = append(args, "-test.gocoverdir="+f.Value.String())
	}
	cmd := exec.Command(exe, args...)
	cmd.Env = append(os.Environ(), releaseChildEnv+"="+stage, releaseDirEnv+"="+dir)
	return cmd
}

func waitFile(path string, limit time.Duration) bool {
	for end := time.Now().Add(limit); time.Now().Before(end); time.Sleep(10 * time.Millisecond) {
		if _, err := os.Stat(path); err == nil {
			return true
		}
	}
	return false
}

// runReleaseChild 는 자식 쪽 본문이다. kill 단계는 MarkReleased 에 들어서면 표지 파일을 쓰고 멈춰 죽기를 기다리고,
// finish 단계는 원장 행이 해제될 때까지 Writer 를 돌린다. 둘 다 앞 프로세스의 메모리 없이 파일 상태만으로 시작한다.
func runReleaseChild(t *testing.T, stage, dir string) {
	l, st := loadReleaseFiles(t, dir)
	opt := testOptions(time.Now, nil)
	opt.FlushEvery, opt.ReleaseEvery = 10*time.Millisecond, 10*time.Millisecond
	switch stage {
	case "kill":
		l.beforeMark = func() {
			if err := os.WriteFile(filepath.Join(dir, deletedFile), nil, 0o600); err != nil {
				t.Errorf("표지 파일 쓰기: %v", err)
			}
			select {} // 부모의 SIGKILL 을 기다린다
		}
	case "finish":
	default:
		t.Fatalf("알 수 없는 단계 %q", stage)
	}
	w := newTestWriter(t, l, st, opt)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		w.Run(ctx)
	}()
	for ctx.Err() == nil {
		if row, _ := l.row("s", "r"); row.ReleasedAt != nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	<-done
	if row, _ := l.row("s", "r"); row.ReleasedAt == nil {
		t.Fatal("30초 안에 원장 행을 해제하지 못했다")
	}
}
