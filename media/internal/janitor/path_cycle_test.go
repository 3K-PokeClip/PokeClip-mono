package janitor

// 경로 보안이 주기 안에서 지켜지는지(D14a · D14b · D14c) — 거부 행은 파일 무접촉 · 1h 보류 ·
// path_rejected ERROR · delete_failing, 디렉터리 심링크(루트 안 · 밖)는 삭제 통로(fsop.RemoveT)가 거부한다.

import (
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// D14a · D14b — 루트 밖 · 다른 스트림 디렉터리를 가리키는 장부 값은 지우지 않는다.
func TestCycleRejectsEscapingAndCrossStreamPaths(t *testing.T) {
	r := newRig(t, nil)
	r.row("s1", 1, false, false)
	r.ledger.setPath(rowKey{"s1", 1}, ptr(testPath("s2", 1))) // 행은 s1, 경로는 s2 디렉터리
	r.fs.put(testRel("s2", 1))
	r.row("s1", 2, false, false)
	r.ledger.setPath(rowKey{"s1", 2}, ptr("/recordings/s1/../../etc/passwd"))

	r.cycle(t)

	if r.fs.callCount() != 0 || !r.fs.exists(testRel("s2", 1)) {
		t.Fatalf("거부해야 할 경로로 remove 를 불렀다(호출 %d · 변이 — 스트림 대조 제거)", r.fs.callCount())
	}
	rec := r.log.last(t, "janitor_path_rejected")
	if rec.level != slog.LevelError || rec.attrs["count"] != "2" {
		t.Errorf("janitor_path_rejected = %v, want ERROR count=2", rec)
	}
	if got := r.log.last(t, "janitor_delete_failing").attrs["value"]; got != "1" {
		t.Errorf("delete_failing value = %q, want 1", got)
	}
	r.clock.advance(rejectedHold - time.Second)
	r.cycle(t)
	if n := len(r.log.all("janitor_path_rejected")); n != 1 {
		t.Errorf("1h 보류 안에 다시 거부 = %d줄, want 1(첫 주기만)", n)
	}
}

func ptr(s string) *string { return &s }

// D14c — 스트림 디렉터리가 루트 밖을 가리키는 심링크면 삭제 통로가 거부한다 — 바깥 파일은 무사하고
// 장부는 그대로다(변이 — os.Remove(절대 경로)로 바꾸면 바깥 파일이 사라진다). t2 r1 Q1 뒤로는 루트 밖이든
// 안이든 디렉터리 심링크는 fsop 가 먼저 거부하므로 사유는 stream_symlink 다.
func TestCycleWithRealRootRefusesSymlinkedStreamDirectory(t *testing.T) {
	r, dir := newRealRootRig(t)
	const name = "2026-10-19_00-00-00-000001.mp4"
	outside := t.TempDir()
	victim := filepath.Join(outside, name)
	if err := os.WriteFile(victim, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(dir, "s1")); err != nil {
		t.Fatal(err)
	}
	k := r.realRow(dir, "s1", 1, name, true)

	r.cycle(t)

	if _, err := os.Stat(victim); err != nil {
		t.Fatalf("루트 밖 파일이 사라졌다: %v", err)
	}
	if _, ok := r.ledger.pathOf(k); !ok {
		t.Error("이탈을 거부했는데 장부를 NULL 했다")
	}
	if got := r.log.last(t, "janitor_path_rejected").attrs["reasons"]; !strings.Contains(got, rejectStreamSymlink) {
		t.Errorf("janitor_path_rejected reasons = %q, want %s", got, rejectStreamSymlink)
	}
}

// newRealRootRig 은 New 가 만든 실제 삭제 통로(fsop 경유 *os.Root)를 그대로 쓰는 janitor 다 — 시계만
// 가짜다. 반환 dir 이 SEGMENT_ROOT 다.
func newRealRootRig(t *testing.T) (*rig, string) {
	t.Helper()
	dir := t.TempDir()
	root, err := os.OpenRoot(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { root.Close() })
	ops := &opLog{}
	sink := newLogSink(slog.LevelDebug)
	opt := Options{Root: dir, Interval: 30 * time.Second, Batch: 200, MaxPerCycle: 2000,
		FSOpTimeout: 5 * time.Second, RunID: "run-1", Log: slog.New(sink)}
	ledger := newFakeLedger(ops)
	j, err := New(opt, ledger, root)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	clock := newFakeClock()
	j.now = clock.now
	return &rig{j: j, ledger: ledger, fs: newFakeFS(ops), clock: clock, log: sink, ops: ops}, dir
}

// realRow 는 dir 아래 stream 행을 장부에 넣는다(파일은 만들지 않는다).
func (r *rig) realRow(dir, stream string, seq int64, name string, due bool) rowKey {
	k := rowKey{stream, seq}
	p := filepath.Join(dir, stream, name)
	r.ledger.add(stream, seq, due)
	r.ledger.setPath(k, &p)
	r.ledger.rows[k].seg.LocalPath = p
	return k
}

// Q1(t2 r1) — 스트림 디렉터리가 같은 루트의 다른 스트림 디렉터리를 가리키는 심링크면(s1 → s2) 어휘
// 검증도 os.Root 도 통과한다. 그 행은 지우지 않고 거부한다 — s2 의 파일은 무사하고, s1 장부는 그대로 ·
// path_rejected(stream_symlink) ERROR · 1h 보류(변이 — 하위 Root · Lstat 검사 제거 → s2 파일이 사라진다).
func TestCycleRefusesStreamDirectorySymlinkToAnotherStream(t *testing.T) {
	r, dir := newRealRootRig(t)
	const name = "2026-10-19_00-00-00-000001.mp4"
	if err := os.MkdirAll(filepath.Join(dir, "s2"), 0o755); err != nil {
		t.Fatal(err)
	}
	victim := filepath.Join(dir, "s2", name)
	if err := os.WriteFile(victim, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("s2", filepath.Join(dir, "s1")); err != nil {
		t.Fatal(err)
	}
	k := r.realRow(dir, "s1", 1, name, true)

	r.cycle(t)

	if _, err := os.Stat(victim); err != nil {
		t.Fatalf("s1 → s2 심링크를 따라가 다른 스트림(s2) 파일을 지웠다: %v", err)
	}
	if _, ok := r.ledger.pathOf(k); !ok {
		t.Error("거부해야 할 행의 장부를 NULL 했다")
	}
	rec := r.log.last(t, "janitor_path_rejected")
	if rec.level != slog.LevelError || rec.attrs["count"] != "1" || !strings.Contains(rec.attrs["reasons"], "stream_symlink") {
		t.Errorf("janitor_path_rejected = %v, want ERROR count=1 · stream_symlink", rec)
	}
	if !r.j.skip.held(k, r.clock.now().Add(rejectedHold-time.Second)) {
		t.Error("거부 행이 1h 보류에 들지 않았다")
	}
}
