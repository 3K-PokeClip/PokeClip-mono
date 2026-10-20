// Package fsop 은 취소를 받지 않는 개별 파일시스템 호출(os.Stat, 미디어 프로브 등)을
// 짧은 워커로 감싸 시간 상한을 주는 층이다. 멈춘 파일시스템이 이벤트 루프를
// 무징후로 세우는 경로를 여기서 끊는다(POK-168 r15a · ADR-063).
//
// 계약 4항(설계 3.2 "fsop 의 계약 4" — 형상은 M1 확정, F-41):
//  1. 취소를 받지 않는 개별 FS 호출을 짧은 워커로 감싸 상한을 준다.
//     상한 = FS_OP_TIMEOUT(권고 5초). 상한 초과 시 워커는 버려지고 늦은 결과는 폐기된다 —
//     버려진 워커의 종료 시각은 무보장이며(F-38), 상한은 비율 상한이지 총량 상한이 아니다.
//  2. 자원 핸들을 시간 경계 너머로 돌려주지 않는다 — 파일을 여는 작업(ProbeT·ReadT)은
//     열고·읽고·닫기를 워커 안에서 통째로 끝낸다. 늦은 성공의 FD 는 워커 자신이 닫는다.
//  3. 타임아웃은 정상 실패와 구분돼 호출자에게 도달한다: errors.Is(err, ErrStalled).
//     호출자(indexer)가 그 구분으로 fs_op_stalled 신호와 Latch.Trip 을 잇는다(수용 기준 f6m ⓓ).
//  4. Latch 는 loop 단일 goroutine 이 소유한다 — 고루틴 안전하지 않다(락 없음이 계약이다).
package fsop

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// DefaultOpTimeout 은 개별 FS 호출 상한의 기본값이다(계약 1항 권고).
const DefaultOpTimeout = 5 * time.Second

// ErrStalled 는 개별 FS 호출이 상한을 넘겨 버려졌음을 뜻한다.
// 정상 실패(파일 없음 등)와 반드시 구분돼 호출자에게 도달해야 한다(계약 3항).
var ErrStalled = errors.New("fsop: FS 호출이 상한을 넘겼다")

// StatT 는 os.Stat 을 워커로 감싸 timeout 상한을 준다.
// 타임아웃이면 (nil, ErrStalled 래핑)을 돌려주고 워커는 버려진다.
func StatT(path string, timeout time.Duration) (os.FileInfo, error) {
	type result struct {
		fi  os.FileInfo
		err error
	}
	// 버퍼 1 이라 버려진 워커의 늦은 송신이 막히지 않는다 — 워커는 반드시 끝까지 간다.
	ch := make(chan result, 1)
	go func() {
		fi, err := os.Stat(path)
		ch <- result{fi, err}
	}()

	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case r := <-ch:
		return r.fi, r.err
	case <-t.C:
		return nil, fmt.Errorf("%w: op=stat path=%q timeout=%s", ErrStalled, path, timeout)
	}
}

// ProbeT 는 "열고·읽고·닫는" 프로브 함수 전체를 워커로 감싼다(계약 2항).
// probe 는 자기 파일을 자기가 닫아야 한다 — 그래야 늦은 성공의 FD 를 워커가 회수한다.
func ProbeT(path string, timeout time.Duration, probe func(string) (int64, error)) (int64, error) {
	type result struct {
		d   int64
		err error
	}
	ch := make(chan result, 1)
	go func() {
		d, err := probe(path)
		ch <- result{d, err}
	}()

	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case r := <-ch:
		return r.d, r.err
	case <-t.C:
		return 0, fmt.Errorf("%w: op=probe path=%q timeout=%s", ErrStalled, path, timeout)
	}
}

// ErrSymlinkInPath 는 지울 경로의 디렉터리 요소가 심링크라 지우지 않았음을 뜻한다(RemoveT).
var ErrSymlinkInPath = errors.New("fsop: 경로의 디렉터리 요소가 심링크다")

// RemoveT 는 root 안의 name 을 지우고 timeout 상한을 준다(StatT 와 같은 워커 · 버퍼 1 형상). 상한을
// 넘기면 ErrStalled 래핑이고 워커는 버려진다 — 버려진 워커가 뒤늦게 지울 수 있으므로 호출자는 그
// 경로를 「지워졌을 수 있음」으로 다뤄야 한다. 파일 없음(부모 디렉터리 부재 포함)은 fs.ErrNotExist
// 그대로 돌려준다 — 판정은 호출자 몫이다.
//
// 디렉터리 요소는 심링크를 따라가지 않는다. os.Root 는 루트 밖으로 가는 심링크만 거부하고 루트 안을
// 가리키는 심링크는 따라간다(t2 r1 실측 — /recordings/s1 -> s2 면 s1/x 삭제가 s2/x 를 지운다). 그래서
// 요소마다 Lstat 으로 심링크를 거부(ErrSymlinkInPath)하고 하위 Root 를 열어 내려가며, 마지막 요소는
// 그 하위 Root 안에서 이름만으로 지운다. 하위 Root 는 연 디렉터리를 손잡이로 쥐므로, 연 뒤에 이름이
// 바뀌어도 삭제가 다른 디렉터리로 새지 않는다.
func RemoveT(root *os.Root, name string, timeout time.Duration) error {
	return removeWith(func(n string) error { return removeNoFollow(root, n) }, name, timeout)
}

// removeNoFollow 는 RemoveT 워커의 본문이다 — name 의 디렉터리 요소를 심링크 없이 내려가 마지막
// 요소를 지운다. ".." · 절대 경로는 os.Root 가 거부한다.
func removeNoFollow(root *os.Root, name string) error {
	parts := strings.Split(filepath.Clean(name), string(filepath.Separator))
	dir := root
	for _, p := range parts[:len(parts)-1] {
		sub, err := openDirNoFollow(dir, p)
		if err != nil {
			return err
		}
		defer sub.Close() // 디렉터리 손잡이라 닫기 실패가 삭제 결과를 바꾸지 않는다
		dir = sub
	}
	return dir.Remove(parts[len(parts)-1])
}

// afterLstat 은 시험 전용 고리다 — openDirNoFollow 의 Lstat 과 OpenRoot 사이에서 불린다. 제품 경로에서는
// nil 이고 아무도 쓰지 않는다. 시험이 그 사이 바꿔치기를 결정적으로 일으켜 SameFile 대조를 판별한다(C16).
var afterLstat func(name string)

// openDirNoFollow 는 parent 안의 디렉터리 name 을 하위 Root 로 연다. name 이 심링크면 거부한다.
// Lstat 과 OpenRoot 사이에 name 이 심링크로 바뀌면 연 디렉터리가 Lstat 이 본 것과 달라지므로, 연
// 디렉터리를 Lstat 결과와 대조해(os.SameFile) 그 경쟁도 거부한다.
func openDirNoFollow(parent *os.Root, name string) (*os.Root, error) {
	fi, err := parent.Lstat(name)
	if err != nil {
		return nil, err
	}
	if fi.Mode()&fs.ModeSymlink != 0 {
		return nil, fmt.Errorf("%w: %q", ErrSymlinkInPath, name)
	}
	if afterLstat != nil {
		afterLstat(name)
	}
	sub, err := parent.OpenRoot(name)
	if err != nil {
		return nil, err
	}
	opened, err := sub.Stat(".")
	if err == nil && !os.SameFile(fi, opened) {
		err = fmt.Errorf("%w(검사 뒤 바뀜): %q", ErrSymlinkInPath, name)
	}
	if err != nil {
		sub.Close()
		return nil, err
	}
	return sub, nil
}

// removeWith 는 RemoveT 의 본문이다. 시험이 멈추는 삭제를 끼우려고 삭제 함수를 받는다.
func removeWith(remove func(string) error, name string, timeout time.Duration) error {
	// 버퍼 1 이라 버려진 워커의 늦은 송신이 막히지 않는다 — 워커는 반드시 끝까지 간다.
	ch := make(chan error, 1)
	go func() { ch <- remove(name) }()

	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case err := <-ch:
		return err
	case <-t.C:
		return fmt.Errorf("%w: op=remove path=%q timeout=%s", ErrStalled, name, timeout)
	}
}

// ReadT 는 "열고·읽고·닫는" 판독 전체를 워커로 감싼다(계약 2항 — ProbeT 와 같은 형상).
//
// 파일을 여닫는 것은 이 함수이고 read 는 열린 리더만 받는다 — 그래서 호출자 소스에 파일을
// 여는 코드가 생기지 않는다(m2, .golangci.yml). 타임아웃이면 (영값, ErrStalled 래핑)을 돌려주고
// 워커는 버려진다. 버려진 워커는 read 가 끝난 뒤 스스로 파일을 닫는다 — read 는 받은 리더를
// 반환 뒤에 붙잡아 쓰면 안 된다.
func ReadT[T any](path string, timeout time.Duration, read func(io.ReadSeeker) (T, error)) (T, error) {
	type result struct {
		v   T
		err error
	}
	// 버퍼 1 이라 버려진 워커의 늦은 송신이 막히지 않는다 — 워커는 반드시 끝까지 가서 파일을 닫는다.
	ch := make(chan result, 1)
	go func() {
		v, err := openAndRead(path, read)
		ch <- result{v, err}
	}()

	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case r := <-ch:
		return r.v, r.err
	case <-t.C:
		var zero T
		return zero, fmt.Errorf("%w: op=read path=%q timeout=%s", ErrStalled, path, timeout)
	}
}

// openAndRead 는 ReadT 워커의 본문이다 — 열기·판독·닫기가 여기서 통째로 끝난다.
func openAndRead[T any](path string, read func(io.ReadSeeker) (T, error)) (T, error) {
	f, err := os.Open(path)
	if err != nil {
		var zero T
		return zero, err
	}
	defer f.Close() // 읽기 전용 파일이라 닫기 실패가 판독 결과를 바꾸지 않는다
	return read(f)
}

// Latch 는 FS 열화 래치다(계약 4항). loop 단일 goroutine 만 만진다.
//
// 트립되면 처리 경로가 새 FS 워커를 만들지 않게 하는 것이 목적이다 —
// "한 주기 안의 hang op 개수"를 비율로 묶는 장치이며, 해제는 주기 머리의
// root 프로브 1회(Reset)가 한다.
type Latch struct {
	log     *slog.Logger
	tripped bool
	path    string
	site    string
}

// NewLatch 는 래치를 만든다. log 는 fs_degraded 게이지 신호를 싣는다.
func NewLatch(log *slog.Logger) *Latch {
	return &Latch{log: log}
}

// Trip 은 래치를 세운다. 멱등이다 — 이미 트립된 상태의 재트립은 최초 좌표를 유지한다.
func (l *Latch) Trip(path, site string) {
	if l.tripped {
		return
	}
	l.tripped, l.path, l.site = true, path, site
	l.log.Error("fs_degraded", "value", 1, "path", path, "site", site)
}

// Tripped 는 래치 상태다.
func (l *Latch) Tripped() bool {
	return l.tripped
}

// Reset 은 주기 머리에서 root 를 1회 프로브해 해제를 시도한다.
// 프로브가 다시 상한을 넘기면 트립을 유지한다(false). root 부재 같은 정상 실패는
// hang 이 아니므로 해제한다 — 그 실패는 정상 경로의 에러 처리가 받는다.
func (l *Latch) Reset(root string, timeout time.Duration) bool {
	if !l.tripped {
		return true
	}
	if _, err := StatT(root, timeout); errors.Is(err, ErrStalled) {
		l.log.Error("fs_op_stalled", "op", "stat", "site", "latch_reset_probe", "path", root)
		return false
	}
	l.log.Info("fs_degraded", "value", 0, "path", l.path, "site", l.site)
	l.tripped, l.path, l.site = false, "", ""
	return true
}
