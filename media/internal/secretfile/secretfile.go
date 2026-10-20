// Package secretfile 은 비밀 값 한 줄이 든 파일(*_FILE 입력)을 읽는다. 사이드카(segment-indexer)와
// 응답기(ingest-auth)가 같은 검사 규칙을 쓰게 하려고 둘이 함께 부른다.
package secretfile

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"strings"
	"syscall"
)

// maxBytes 는 비밀번호 파일 크기의 상한이다. 비밀번호 한 줄에는 넉넉하고, 엉뚱한 파일(로그 ·
// 바이너리 · /dev/zero 같은 장치는 그 앞에서 거부된다)을 가리켰을 때 메모리를 먹지 않게 한다.
const maxBytes = 4096

// ErrNotRegularFile 은 비밀번호 파일 자리에 일반 파일이 아닌 것(디렉터리 · FIFO · 장치)이 놓였다는
// 거부다. 이 갈래를 이름으로 두는 이유: 디렉터리 · 쓰는 쪽 없는 FIFO 는 이 검사가 없어도 읽기 에러나
// 빈 내용 거부가 대신 걸려, 「거부됐다」 만으로는 검사가 살아 있는지 가려지지 않는다.
var ErrNotRegularFile = errors.New("일반 파일이어야 한다 — 디렉터리 · FIFO · 장치는 받지 않는다")

// Options 는 Read 의 선택지다.
type Options struct {
	// ShowPath 가 참이면 파일을 연 뒤의 거부(종류 · 권한 · 크기 · 내용 · 상태와 읽기 실패)에 경로를
	// 싣는다. 거짓이면 어느 갈래에서도 경로를 싣지 않고 env 이름과 원인만 싣는다 — 경로 자리에 비밀
	// 값을 잘못 넣을 수 있는 쪽(응답기 — 계약 3-2)이 쓴다. 열기 실패는 둘 다 경로를 싣지 않는다.
	ShowPath bool
}

// Read 는 path 의 비밀번호 한 줄을 읽는다(POK-373). envName 은 에러에 싣는 설정 이름이다.
//
// 규칙(표준이 아니라 선택이다 — media/README.md 에 같은 문장이 있다):
//   - 일반 파일만 받는다(대상이 일반 파일인 심볼릭 링크 포함). FIFO · 장치 · 디렉터리는 거부한다.
//   - other 권한 비트가 하나라도 있거나 group 쓰기 비트가 있으면 거부한다. group 읽기는 받는다
//     (root:10001 0440 배치 허용 — libpq 의 .pgpass 는 group 비트를 전부 거부하지만 그러면 이 배치를
//     쓸 수 없다). group 쓰기를 막는 것은 같은 그룹의 계정이 비밀번호를 바꿔 끼우지 못하게 하려는 것이다.
//   - 파일 크기는 4096 바이트 이하.
//   - 끝의 줄바꿈 하나(\n 또는 \r\n)만 걷는다. 앞뒤 공백 · ':' 는 비밀번호의 일부라 그대로 둔다
//     (postgres 공식 이미지의 *_FILE 은 명령 치환이라 끝 줄바꿈을 전부 걷는다 — 우리는 하나만).
//   - 걷은 뒤 비었거나 \r · \n · NUL 이 남으면 거부한다(여러 줄 · 바이너리).
//
// 에러에는 env 이름 · 경로 · 모드만 싣는다 — 내용 · 길이 · 위치는 싣지 않는다(ADR-018). 단 열기 실패는
// 경로도 빼고 원인만 싣는다 — 비밀번호를 이 자리에 잘못 넣으면 그 값이 곧 경로다. 열린 뒤의 갈래는
// 실제로 있는 파일의 경로라 opts.ShowPath 가 참일 때만 싣는다.
func Read(envName, path string, opts Options) (string, error) {
	// 사전 Stat 없이 비차단으로 연다. 쓰는 쪽이 없는 FIFO 를 막는 열기로 열면 기동이 영원히 멈추고,
	// 확인(Stat)과 열기를 나누면 그 사이에 경로가 FIFO 로 바뀌는 창이 생긴다. 열린 파일 자체를 fstat
	// 으로 가르면 창이 없다. 일반 파일에서 O_NONBLOCK 은 읽기에 영향이 없다.
	f, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return "", fmt.Errorf("%s 의 파일을 열지 못했다(경로는 싣지 않는다): %w", envName, withoutPath(err))
	}
	defer f.Close()

	// where 는 열린 뒤 갈래의 에러 머리다. ShowPath 가 참이면 「이름(경로)」 — 사이드카의 기존 문구와
	// 바이트 단위로 같다.
	where := envName
	if opts.ShowPath {
		where = envName + "(" + path + ")"
	}
	info, err := f.Stat()
	if err != nil {
		return "", fmt.Errorf("%s의 상태를 읽지 못했다: %w", where, opts.cause(err))
	}
	mode := info.Mode()
	if !mode.IsRegular() {
		return "", fmt.Errorf("%s는 %w(모드 %v)", where, ErrNotRegularFile, mode)
	}
	if mode.Perm()&0o007 != 0 {
		return "", fmt.Errorf("%s에 other 권한 비트가 있다(모드 %#o) — 0400 또는 0440 으로 둔다", where, mode.Perm())
	}
	if mode.Perm()&0o020 != 0 {
		return "", fmt.Errorf("%s에 group 쓰기 권한 비트가 있다(모드 %#o) — 0400 또는 0440 으로 둔다", where, mode.Perm())
	}

	b, err := io.ReadAll(io.LimitReader(f, maxBytes+1))
	if err != nil {
		return "", fmt.Errorf("%s를 읽지 못했다: %w", where, opts.cause(err))
	}
	if len(b) > maxBytes {
		return "", fmt.Errorf("%s가 %d 바이트를 넘는다 — 비밀번호 한 줄만 둔다", where, maxBytes)
	}
	pass, err := secretLine(string(b))
	if err != nil {
		return "", fmt.Errorf("%s: %w", where, err)
	}
	return pass, nil
}

// cause 는 열린 뒤 갈래의 시스템 에러를 에러 문구에 실을 꼴로 돌려준다. ShowPath 가 거짓이면 경로를
// 담은 *fs.PathError 를 풀어 원인만 남긴다.
func (o Options) cause(err error) error {
	if o.ShowPath {
		return err
	}
	return withoutPath(err)
}

// withoutPath 는 *fs.PathError 의 원인(errno)만 꺼낸다 — 경로는 버리고 errors.Is 판별은 살린다.
func withoutPath(err error) error {
	var pe *fs.PathError
	if errors.As(err, &pe) {
		return pe.Err
	}
	return err
}

// secretLine 은 파일 내용에서 끝 줄바꿈 하나를 걷고 비밀번호 한 줄인지 본다. 에러에는 내용을 싣지 않는다.
func secretLine(content string) (string, error) {
	switch {
	case strings.HasSuffix(content, "\r\n"):
		content = strings.TrimSuffix(content, "\r\n")
	case strings.HasSuffix(content, "\n"):
		content = strings.TrimSuffix(content, "\n")
	}
	if content == "" {
		return "", errors.New("비밀번호가 비었다")
	}
	if strings.ContainsAny(content, "\r\n\x00") {
		return "", errors.New("비밀번호 한 줄만 둔다 — 끝 줄바꿈 하나 말고는 줄바꿈 · CR · NUL 을 넣을 수 없다")
	}
	return content, nil
}
