package ingestauth

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"strings"
	"sync"
	"syscall"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// denyListSpace 는 줄 문법이 줄 앞뒤에서 걷는 공백이다 — C 로캘 [[:space:]] 의 여섯(스페이스 · 탭 · LF ·
// VT · FF · CR). strings.TrimSpace 는 NBSP 같은 유니코드 공백까지 걷어, 같은 파일을 LC_ALL=C 로 세는
// RUNBOOK 의 셸 계수와 다른 집합을 낸다(설계 2-4 줄 문법).
const denyListSpace = " \t\n\v\f\r"

// denyListMaxBytes 는 거부 목록 파일의 크기 상한이다. 넘으면 읽기 실패로 다룬다(새 연결 거부 · 게이지
// unreadable). 토큰 한 줄은 27 바이트라 주석을 붙여도 수백 줄이 넘게 든다 — 긴급 절단마다 한 줄씩 느는 이
// 파일의 실제 크기(수십 줄)보다 훨씬 크다. 상한은 처리기가 publish 하나에 이 파일을 두 번 읽는 비용(경로
// 관리자를 붙잡는 시간 — 예산이 뺀다)과 메모리를 묶는다. 상한 크기가 모두 토큰 줄이어도 한 번 읽기가 1ms
// 안쪽이다(로컬 측정 샘플 약 0.6ms — 1 MiB 면 약 10ms).
const denyListMaxBytes = 64 << 10

// DenyList 는 운영자가 적는 거부 목록 파일이다. 한 줄에 토큰 하나, '#' 뒤는 주석, 빈 줄 허용.
// 줄의 앞뒤 공백은 걷고, 같은 토큰이 여러 줄이면 하나로 센다(유효 토큰 집합 — RUNBOOK 해제 절이 같은
// 규칙으로 센다). 공백은 C 로캘 [[:space:]] 의 여섯(스페이스 · 탭 · LF · VT · FF · CR)이다 —
// strings.TrimSpace 처럼 유니코드 공백을 걷지 않는다. 그 밖의 공백 문자(NBSP 등)는 토큰의 일부라 그 줄은
// 틀린 줄이 된다(셸 계수와 같은 집합). NUL 바이트가 든 줄은 NUL 이 어디에 있든 틀린
// 줄이다 — bash 의 read 는 NUL 을 버리므로 셸 계수도 그 줄을 따로 틀린 줄로 센다. 끝 줄바꿈이 없는
// 마지막 줄도 한 줄이다. 토큰 꼴(streamkey.IsToken)이 아닌 값은 틀린 줄이다.
//
// 여러 고루틴이 함께 써도 된다. 이 값이 내는 신호는 셋이다(설계 6절). 파일의 유효 토큰 집합이 바뀌면
// ingestauth_denylist_changed(INFO), 못 읽거나 틀린 줄이 있으면 게이지 ingestauth_denylist_error,
// 다시 온전히 읽으면(파일 없음 포함) ingestauth_denylist_recovered 다. 게이지는 둘 다 ERROR 다. 게이지가 선
// 채 사유(못 읽음 ↔ 틀린 줄)가 바뀌면 ingestauth_denylist_error 를 다시 낸다. 어느 신호에도 줄 내용은 싣지 않는다.
type DenyList struct {
	path string
	log  *slog.Logger

	// mu 는 아래 셋을 지키고, 파일 읽기를 부른 차례대로 세운다. 파일이 작아 읽기를 잠금 안에 둔다 —
	// 읽기와 반영의 차례가 뒤집히면 changed · 게이지가 옛 내용으로 되돌아가며 떨린다.
	mu      sync.Mutex
	union   map[string]struct{} // 한 번이라도 읽은 토큰. 이 값의 수명 동안 지우지 않는다
	inFile  map[string]struct{} // 마지막으로 읽은 파일의 유효 토큰 집합 — changed 의 셈
	failure string              // 거부 목록 게이지가 선 사유(unreadable · invalid_lines). 비었으면 서 있지 않다
}

// NewDenyList 는 path 의 거부 목록을 판정할 DenyList 를 만든다. 파일은 첫 Contains · Check 가 읽는다.
// 합집합은 비어서 시작한다 — 응답기가 다시 뜨면 파일 내용부터 다시 쌓는다. log 가 nil 이면
// slog.Default() 다.
func NewDenyList(path string, log *slog.Logger) *DenyList {
	if log == nil {
		log = slog.Default()
	}
	return &DenyList{
		path:   path,
		log:    log,
		union:  make(map[string]struct{}),
		inFile: make(map[string]struct{}),
	}
}

// Contains 는 부를 때마다 파일을 다시 읽어 token 이 있는지 판정한다. 캐시를 두지 않는 것은
// 마지막 거부 확인이 그 앞에 끝난 쓰기를 반드시 보게 하려는 것이다(설계 2-4 허용 순서).
// 한 번 읽은 토큰은 이 값의 수명 동안 지우지 않는다. 편집 중 파일이 잠깐 비거나 사라져도
// 차단이 풀리지 않는다. 파일이 없으면 지금까지 읽은 토큰만 본다. 틀린 줄은 건너뛴다(줄 내용은
// 기록하지 않는다). 파일을 읽지 못하면 에러를 돌려준다 — 처리기는 그 요청을 거부한다. 에러여도 첫
// 반환값은 지금까지 읽은 토큰(합집합)의 판정이다 — 이미 막은 토큰은 읽기 실패에서도 참이다.
func (d *DenyList) Contains(token string) (bool, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	_, err := d.reload()
	_, ok := d.union[token]
	return ok, err
}

// Check 는 파일을 지금 다시 읽어 온전한지 본다. 못 읽거나 틀린 줄이 있으면 에러다. 읽은 토큰은
// Contains 처럼 합집합에 더한다. healthz 가 부른다. 에러에는 이유와 틀린 줄 수만 싣는다.
func (d *DenyList) Check() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	invalid, err := d.reload()
	if err != nil {
		return err
	}
	if invalid > 0 {
		return fmt.Errorf("ingestauth: 거부 목록에 토큰 꼴이 아닌 줄이 %d개 있다", invalid)
	}
	return nil
}

// reload 는 파일을 지금 읽어 합집합에 더하고, 신호를 낸 뒤 틀린 줄 수를 돌려준다. d.mu 를 잡은 채로 부른다.
func (d *DenyList) reload() (invalid int, err error) {
	data, err := readDenyListFile(d.path)
	switch {
	case errors.Is(err, fs.ErrNotExist):
		data = nil // 파일이 없으면 빈 목록이다
	case err != nil:
		d.setFailing("unreadable", "error", err.Error())
		return 0, fmt.Errorf("ingestauth: 거부 목록을 읽지 못했다: %w", err)
	}
	tokens, invalid := parseDenyList(data)
	d.merge(tokens)
	if invalid > 0 {
		d.setFailing("invalid_lines", "lines", invalid)
	} else {
		d.clearFailing()
	}
	return invalid, nil
}

// readDenyListFile 은 path 의 거부 목록 파일을 읽는다. 일반 파일이 아니거나 denyListMaxBytes 를 넘으면 에러다.
// 링크는 따르되 그 끝이 일반 파일이어야 한다(연 파일을 본다). O_NONBLOCK 으로 연다 — 쓰는 쪽 없는 FIFO 는 막히는
// 열기에서 멈추고, 그 자리는 d.mu 안이다.
func readDenyListFile(path string) ([]byte, error) {
	f, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, err
	}
	defer f.Close() // 읽기만 한다 — 닫기 실패가 읽은 내용을 바꾸지 않는다
	fi, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if !fi.Mode().IsRegular() {
		return nil, fmt.Errorf("%s 가 일반 파일이 아니다(%v)", path, fi.Mode().Type())
	}
	// 상한보다 한 바이트 더 읽어야 넘쳤는지 안다.
	data, err := io.ReadAll(io.LimitReader(f, denyListMaxBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > denyListMaxBytes {
		return nil, fmt.Errorf("%s 가 상한 %d 바이트를 넘는다", path, denyListMaxBytes)
	}
	return data, nil
}

// merge 는 파일의 유효 토큰 집합 tokens 를 합집합에 더한다. 지난 읽기와 집합이 다르면 changed 를 낸다.
// denied 는 합집합의 크기다 — 파일에서 빠진 토큰도 계속 거부하기 때문이다.
func (d *DenyList) merge(tokens map[string]struct{}) {
	added, removed := 0, 0
	for t := range tokens {
		if _, ok := d.inFile[t]; !ok {
			added++
		}
		d.union[t] = struct{}{}
	}
	for t := range d.inFile {
		if _, ok := tokens[t]; !ok {
			removed++
		}
	}
	d.inFile = tokens
	if added > 0 || removed > 0 {
		d.log.Info("ingestauth_denylist_changed", "denied", len(d.union), "added", added, "removed", removed)
	}
}

// setFailing 은 거부 목록 게이지를 reason 으로 세운다. 같은 사유로 이미 서 있으면 아무것도 내지 않고, 사유가
// 바뀌었으면 같은 등급으로 다시 낸다 — 마지막 신호가 지금의 원인이다. attrs 는 reason 뒤에 싣는 속성이다.
func (d *DenyList) setFailing(reason string, attrs ...any) {
	if d.failure == reason {
		return
	}
	d.failure = reason
	d.log.Error("ingestauth_denylist_error", append([]any{"reason", reason}, attrs...)...)
}

// clearFailing 은 거부 목록 게이지를 내린다. 세울 때와 같은 ERROR 로 낸다 — 내릴 때만 등급이 낮으면
// LOG_LEVEL=warn 에서 회복이 걸러진다(설계 6절).
func (d *DenyList) clearFailing() {
	if d.failure == "" {
		return
	}
	d.failure = ""
	d.log.Error("ingestauth_denylist_recovered")
}

// parseDenyList 는 거부 목록 파일의 내용에서 유효 토큰 집합과 틀린 줄 수를 낸다(줄 문법 — DenyList). 토큰은 읽은
// 버퍼에서 떼어 둔다(strings.Clone) — 합집합은 파일에서 빠진 토큰도 쥐므로, 떼지 않으면 그 키가 읽은 파일 전체를 붙잡는다.
func parseDenyList(data []byte) (tokens map[string]struct{}, invalid int) {
	tokens = make(map[string]struct{})
	for line := range strings.Lines(string(data)) {
		if strings.IndexByte(line, 0) >= 0 {
			invalid++
			continue
		}
		value, _, _ := strings.Cut(line, "#")
		value = strings.Trim(value, denyListSpace)
		switch {
		case value == "":
		case streamkey.IsToken(value):
			tokens[strings.Clone(value)] = struct{}{}
		default:
			invalid++
		}
	}
	return tokens, invalid
}
