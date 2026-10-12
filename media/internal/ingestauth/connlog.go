package ingestauth

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"time"
)

// 허용 연결 기록의 두 세대 파일 이름이다. 둘 다 기록 디렉터리 바로 아래에 있다.
const (
	connLogName     = "connlog.jsonl"    // 현재 세대 — Append 가 덧붙인다
	connLogPrevName = connLogName + ".1" // 직전 세대 — 회전이 현재 세대를 이 이름으로 넘긴다
)

// connLogFileMode 는 세대 파일의 모드다. 쓰는 응답기와 읽는 cut 이 같은 계정(UID 10003)이고, 상태
// 디렉터리도 그 계정만 연다(10003:10003 0700 — 설계 2-8).
const connLogFileMode = 0o600

// ConnLog 는 응답기가 허용한 송출 연결의 추가 전용 기록이다(JSON Lines — at · path · id, 비밀 없음).
// 현재 세대가 maxBytes(응답기는 1 MiB)에 이르면 직전 세대(.1)로 돌린다. 두 세대만 남는다. 회전은
// Append 안에서만 일어난다. 세대 파일을 새로 만들 때(빈 디렉터리에서 처음 열 때 · 회전할 때) 첫 줄로
// 시작 표시(at 만)를 쓴다. cut 은 이 값을 열지 않고 LoadAllowed 로 읽기만 한다.
//
// 여러 고루틴이 함께 써도 된다. Close 는 없다 — 응답기 프로세스가 사는 동안 현재 세대 파일을 연다.
// 회전은 ingestauth_connlog_rotated(INFO — bytes)를 slog.Default() 로 낸다. OpenConnLog 가 로거를 받지
// 않기 때문이다(설계 2-4 시그니처) — 응답기는 OpenConnLog 앞에서 기본 로거를 정한다.
type ConnLog struct {
	dir      string
	maxBytes int64
	now      func() time.Time
	log      *slog.Logger

	mu   sync.Mutex // 아래 셋과 파일 쓰기 · 회전을 지킨다
	f    *os.File   // 현재 세대. 회전이 새 세대를 만들지 못했으면 nil 이고, 다음 Append 가 다시 만든다
	size int64      // f 의 크기(바이트)
	torn bool       // f 의 끝이 줄바꿈이 아닐 수 있다 — 다음 줄은 줄바꿈을 앞세운다
}

// OpenConnLog 는 dir 의 기록을 이어 쓰려고 연다. 현재 세대가 없거나 비어 있으면 시작 표시와 함께 새로
// 만든다. 쓸 수 없으면 에러다(기동 실패). maxBytes 는 0 보다 커야 한다. now 가 nil 이면 time.Now 다.
func OpenConnLog(dir string, maxBytes int64, now func() time.Time) (*ConnLog, error) {
	if dir == "" {
		return nil, errors.New("ingestauth: 허용 연결 기록의 디렉터리가 비었다")
	}
	if maxBytes <= 0 {
		return nil, fmt.Errorf("ingestauth: 허용 연결 기록의 maxBytes 는 0 보다 커야 한다 — %d", maxBytes)
	}
	if now == nil {
		now = time.Now
	}
	c := &ConnLog{dir: dir, maxBytes: maxBytes, now: now, log: slog.Default()}
	if err := c.open(); err != nil {
		return nil, err
	}
	return c, nil
}

// open 은 현재 세대를 이어 쓰려고 연다. 없거나 비어 있으면(시작 표시를 쓰기 전에 끝난 세대) 새 세대를
// 만든다. 앞 프로세스가 줄 가운데서 끝났으면 다음 줄이 줄바꿈을 앞세우게 한다.
func (c *ConnLog) open() error {
	f, err := os.OpenFile(c.path(connLogName), os.O_RDWR|os.O_APPEND, 0)
	if errors.Is(err, fs.ErrNotExist) {
		return c.startGeneration()
	}
	if err != nil {
		return fmt.Errorf("ingestauth: 허용 연결 기록을 열지 못했다: %w", err)
	}
	size, torn, err := fileEnd(f)
	if err != nil {
		return errors.Join(fmt.Errorf("ingestauth: 허용 연결 기록의 끝을 보지 못했다: %w", err), f.Close())
	}
	if size == 0 {
		if err := f.Close(); err != nil {
			return fmt.Errorf("ingestauth: 빈 현재 세대를 닫지 못했다: %w", err)
		}
		return c.startGeneration()
	}
	c.f, c.size, c.torn = f, size, torn
	return nil
}

// fileEnd 는 f 의 크기와, 끝이 줄바꿈이 아닌지(이어 쓰면 앞 조각에 붙는지)를 돌려준다.
func fileEnd(f *os.File) (size int64, torn bool, err error) {
	fi, err := f.Stat()
	if err != nil {
		return 0, false, err
	}
	if fi.Size() == 0 {
		return 0, false, nil
	}
	last := make([]byte, 1)
	if _, err := f.ReadAt(last, fi.Size()-1); err != nil {
		return 0, false, err
	}
	return fi.Size(), last[0] != '\n', nil
}

// Append 는 잠금을 잡은 뒤 지금 시각을 at 으로 찍어 한 줄을 쓰기 한 번으로 덧붙인다(O_APPEND,
// 사용자 공간 버퍼 없음). 그래서 파일의 줄 순서와 at 의 순서가 같다(벽시계가 뒤로 가지 않으면 —
// 8-4 23). 반환 뒤에는 다른 프로세스(cut)가 곧바로 그 줄을 읽는다. fsync 하지 않는다 — 경로 관리자의
// 한 줄 처리 안에서 연결마다 디스크 동기화를 기다리지 않으려는 것이다. 응답기 프로세스가 죽어도
// 쓴 줄은 페이지 캐시에 남아 재기동 뒤 cut 이 읽는다. 상자가 죽으면 연결도 함께 사라진다.
//
// 현재 세대가 maxBytes 에 이르렀으면 줄을 쓰기 전에 직전 세대로 돌린다. 쓰기가 줄 가운데서 끊기면
// (디스크 부족 등) 에러이고, 다음 줄은 줄바꿈을 앞세워 그 조각에 붙지 않게 쓴다. token · connID 가
// 비면 에러다 — 그 줄은 시작 표시와 구별되지 않거나 id 대응이 없다.
func (c *ConnLog) Append(token, connID string) error {
	if token == "" || connID == "" {
		return errors.New("ingestauth: 토큰과 연결 id 가 모두 있어야 기록한다")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.rotateIfFull(); err != nil {
		return err
	}
	line, err := encodeConnLine(connLine{At: c.now(), Path: token, ID: connID})
	if err != nil {
		return err
	}
	return c.write(line)
}

// rotateIfFull 은 현재 세대가 maxBytes 에 이르렀으면 직전 세대로 넘기고 새 세대를 연다. 앞선 회전이 새
// 세대를 만들지 못했으면(c.f 가 nil) 새 세대 만들기부터 다시 한다. c.mu 를 잡은 채로 부른다.
func (c *ConnLog) rotateIfFull() error {
	if c.f == nil {
		return c.startGeneration()
	}
	if c.size < c.maxBytes {
		return nil
	}
	if err := os.Rename(c.path(connLogName), c.path(connLogPrevName)); err != nil {
		return fmt.Errorf("ingestauth: 허용 연결 기록을 직전 세대로 넘기지 못했다: %w", err)
	}
	// 이름이 넘어갔으니 열린 파일은 이제 직전 세대다. 더 쓰지 않는다.
	retired, size := c.f, c.size
	c.f, c.size, c.torn = nil, 0, false
	c.log.Info("ingestauth_connlog_rotated", "bytes", size)
	if err := retired.Close(); err != nil {
		return fmt.Errorf("ingestauth: 직전 세대로 넘긴 기록을 닫지 못했다: %w", err)
	}
	return c.startGeneration()
}

// startGeneration 은 현재 세대 파일을 새로 만들고 첫 줄로 시작 표시를 쓴다. 이 이름에 파일이 있으면
// 비우고 다시 쓴다 — 시작 표시를 다 쓰지 못하고 남은, 이 기록 자신의 빈 세대다. c.mu 를 잡은 채로
// (OpenConnLog 에서는 값을 내주기 전에) 부른다.
func (c *ConnLog) startGeneration() error {
	f, err := os.OpenFile(c.path(connLogName), os.O_WRONLY|os.O_CREATE|os.O_TRUNC|os.O_APPEND, connLogFileMode)
	if err != nil {
		return fmt.Errorf("ingestauth: 허용 연결 기록의 새 세대를 만들지 못했다: %w", err)
	}
	mark, err := encodeConnLine(connLine{At: c.now()})
	if err != nil {
		return errors.Join(err, f.Close())
	}
	if _, err := f.Write(mark); err != nil {
		return errors.Join(fmt.Errorf("ingestauth: 새 세대에 시작 표시를 쓰지 못했다: %w", err), f.Close())
	}
	c.f, c.size, c.torn = f, int64(len(mark)), false
	return nil
}

// write 는 line 을 현재 세대에 쓰기 한 번으로 덧붙인다. 앞선 쓰기가 줄 가운데서 끊겼을 수 있으면 줄바꿈을
// 앞세워 그 조각을 제 줄로 가둔다 — 그러지 않으면 이 줄이 조각 뒤에 붙어 LoadAllowed 가 읽지 못하고,
// cut 이 허용된 연결을 놓친다. c.mu 를 잡은 채로 부른다.
func (c *ConnLog) write(line []byte) error {
	if c.torn {
		line = append([]byte{'\n'}, line...)
	}
	n, err := c.f.Write(line)
	c.size += int64(n)
	c.torn = n < len(line)
	if err != nil {
		return fmt.Errorf("ingestauth: 허용 연결 기록에 쓰지 못했다: %w", err)
	}
	return nil
}

// Check 는 기록을 지금도 쓸 수 있는지 다시 본다 — 현재 세대 경로가 연 파일과 같은지, 그 디렉터리에
// 다음 세대를 만들 수 있는지(쓰기 권한 · 읽기 전용 마운트). healthz 가 부른다. 마지막 Append 의
// 결과를 쓰지 않는다. 디스크가 찬 것은 잡지 못한다 — Append 실패(기록 게이지)로 드러난다.
func (c *ConnLog) Check() error {
	if err := c.checkCurrent(); err != nil {
		return err
	}
	return checkCanCreate(c.dir)
}

// checkCurrent 는 현재 세대 경로가 연 파일과 같은지 본다. 누가 바꿔 끼우거나 지우면 다르다 — 그대로 쓰면
// cut 이 읽지 않는 파일에 쓰게 된다(설계 3-4). 회전과 겹치지 않게 c.mu 안에서 본다.
func (c *ConnLog) checkCurrent() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.f == nil {
		return errors.New("ingestauth: 허용 연결 기록에 현재 세대가 없다 — 회전이 새 세대를 만들지 못했다")
	}
	opened, err := c.f.Stat()
	if err != nil {
		return fmt.Errorf("ingestauth: 연 기록 파일을 보지 못했다: %w", err)
	}
	named, err := os.Stat(c.path(connLogName))
	if err != nil {
		return fmt.Errorf("ingestauth: 현재 세대 경로를 보지 못했다: %w", err)
	}
	if !os.SameFile(opened, named) {
		return errors.New("ingestauth: 현재 세대 경로가 연 파일과 다르다 — 누가 바꿔 끼웠다")
	}
	return nil
}

// checkCanCreate 는 dir 에 파일을 만들고 지울 수 있는지 본다. 회전의 이름 바꾸기와 새 세대 만들기가 같은
// 권한을 쓴다.
func checkCanCreate(dir string) error {
	f, err := os.CreateTemp(dir, ".connlog-check-*")
	if err != nil {
		return fmt.Errorf("ingestauth: 기록 디렉터리에 다음 세대를 만들 수 없다: %w", err)
	}
	if err := errors.Join(f.Close(), os.Remove(f.Name())); err != nil {
		return fmt.Errorf("ingestauth: 기록 디렉터리의 확인 파일을 치우지 못했다: %w", err)
	}
	return nil
}

// path 는 기록 디렉터리 안의 세대 파일 경로다.
func (c *ConnLog) path(name string) string {
	return filepath.Join(c.dir, name)
}

// connLine 은 기록 한 줄이다. Path · ID 가 없는 줄은 시작 표시다.
type connLine struct {
	At   time.Time `json:"at"`
	Path string    `json:"path,omitempty"`
	ID   string    `json:"id,omitempty"`
}

// valid 는 l 이 이 패키지가 쓰는 꼴 — 시작 표시, 또는 at · path · id 가 모두 있는 줄 — 인지 본다.
func (l connLine) valid() bool {
	return !l.At.IsZero() && (l.Path == "") == (l.ID == "")
}

// encodeConnLine 은 l 을 줄바꿈으로 끝나는 JSON 한 줄로 만든다. at 은 UTC 로 쓴다. encoding/json 이
// 문자열 안의 줄바꿈을 이스케이프하므로 어떤 값도 줄을 나누지 못한다.
func encodeConnLine(l connLine) ([]byte, error) {
	l.At = l.At.UTC()
	b, err := json.Marshal(l)
	if err != nil {
		return nil, fmt.Errorf("ingestauth: 기록 줄을 만들지 못했다: %w", err)
	}
	return append(b, '\n'), nil
}

// Allowed 는 cut 이 한 번 읽은 허용 연결 기록이다.
type Allowed struct {
	Tokens map[string]string // 연결 id → 토큰
	Since  time.Time         // 기록이 빠짐없이 담는 시작 시각. 읽은 줄(시작 표시 포함) 가운데 가장 이른 at 이다
}

// LoadAllowed 는 dir 의 기록을 현재 세대 → 직전 세대 순서로 읽는다. 쓰지 않는다. 두 읽기 사이에
// 회전이 몇 번 끼어도, Since 뒤에 쓰였고 읽기가 시작되기 전에 쓰인 줄은 하나도 빠지지 않는다(3-4절).
// 현재 세대가 회전 중이라 없으면 빈 파일로 본다. 덜 쓰인 마지막 줄은 버린다 — 그 줄의 연결은 마지막
// 확인이 읽기 뒤라 거부된다. 읽은 줄이 하나도 없으면(시작 표시도 없음) 에러다.
func LoadAllowed(dir string) (Allowed, error) {
	return loadAllowed(dir, func() {})
}

// loadAllowed 는 LoadAllowed 의 본문이다. between 은 현재 세대를 읽은 뒤, 직전 세대를 읽기 전에 부른다 —
// 시험이 그 틈에 회전을 끼운다(착수 체크리스트 C2 회전 경합).
func loadAllowed(dir string, between func()) (Allowed, error) {
	current, err := readGeneration(filepath.Join(dir, connLogName))
	if err != nil {
		return Allowed{}, err
	}
	between()
	previous, err := readGeneration(filepath.Join(dir, connLogPrevName))
	if err != nil {
		return Allowed{}, err
	}
	lines := slices.Concat(current, previous)
	if len(lines) == 0 {
		return Allowed{}, errors.New("ingestauth: 허용 연결 기록에 읽을 줄이 하나도 없다(시작 표시도 없음)")
	}
	a := Allowed{Tokens: make(map[string]string), Since: lines[0].At}
	for _, l := range lines {
		if l.At.Before(a.Since) {
			a.Since = l.At
		}
		if l.ID != "" {
			a.Tokens[l.ID] = l.Path
		}
	}
	return a, nil
}

// readGeneration 은 세대 파일 하나의 온전한 줄을 읽는다. 파일이 없으면 줄도 없다(회전 중인 현재 세대 ·
// 아직 없는 직전 세대). 줄바꿈으로 끝나지 않은 마지막 줄(덜 쓰인 줄)과 이 패키지가 쓰는 꼴이 아닌 줄은
// 버린다. 꼴이 아닌 줄은 실패한 쓰기의 조각이고(write 가 제 줄로 가둔다), 그 연결은 기록 실패로 거부됐다.
func readGeneration(path string) ([]connLine, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("ingestauth: 허용 연결 기록을 읽지 못했다: %w", err)
	}
	var lines []connLine
	for raw := range bytes.Lines(data) {
		if !bytes.HasSuffix(raw, []byte("\n")) {
			break // 덜 쓰인 마지막 줄
		}
		var l connLine
		if err := json.Unmarshal(raw, &l); err != nil || !l.valid() {
			continue
		}
		lines = append(lines, l)
	}
	return lines, nil
}
