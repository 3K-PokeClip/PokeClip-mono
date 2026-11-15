package credrelay

import (
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"fmt"
	"os"
	"regexp"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/secretfile"
)

// tokenDirEnv 는 토큰 파일 에러에 싣는 설정 이름이다(중계 env — 계획 E21).
const tokenDirEnv = "CRED_RELAY_CALLER_DIR"

// ErrTokenFile 은 토큰 디렉터리 · 파일을 받을 수 없다는 기동 거부다(cred_relay_failed reason=token_file).
var ErrTokenFile = errors.New("credrelay: 호출자 토큰 파일을 받을 수 없다")

// ErrCallers 는 호출자 목록이 꼴 밖이라는 기동 거부다(비었음 · 이름 꼴 · 겹침).
var ErrCallers = errors.New("credrelay: 호출자 목록이 꼴 밖이다")

// callerName 은 호출자 이름의 꼴이다. 토큰 디렉터리 안 파일 이름이 되므로 경로 요소 하나여야 한다.
var callerName = regexp.MustCompile(`^[a-z][a-z0-9]*$`)

// Tokens 는 호출자마다 토큰 하나의 SHA-256 이다. 원문은 들고 있지 않는다.
type Tokens struct {
	callers []string
	sums    [][sha256.Size]byte
}

// LoadTokens 는 dir 안의 호출자 이름 파일마다 토큰 한 줄을 읽는다. dir 은 os.Root 로 열고 이름마다
// secretfile.ReadIn 으로 읽는다 — 루트 밖으로 나가는 이름 · 링크 이름은 거부하고, 파일에는 secretfile 의 규칙
// (일반 파일 · other 비트 · group 쓰기 · 4096 바이트 · 한 줄)을 건다. 하나라도 받을 수 없으면 ErrTokenFile,
// 호출자 목록이 꼴 밖이면(파일을 보기 전에) ErrCallers 다. 에러에는 토큰 내용을 싣지 않는다.
func LoadTokens(dir string, callers []string) (Tokens, error) {
	if err := checkCallers(callers); err != nil {
		return Tokens{}, err
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		return Tokens{}, fmt.Errorf("%w: %s 를 열지 못했다: %w", ErrTokenFile, tokenDirEnv, err)
	}
	defer root.Close()

	tok := Tokens{callers: append([]string(nil), callers...), sums: make([][sha256.Size]byte, len(callers))}
	for i, name := range callers {
		v, err := secretfile.ReadIn(root, tokenDirEnv, name, secretfile.Options{ShowPath: true})
		if err != nil {
			return Tokens{}, fmt.Errorf("%w: 호출자 %s: %w", ErrTokenFile, name, err)
		}
		tok.sums[i] = sha256.Sum256([]byte(v))
	}
	return tok, nil
}

// checkCallers 는 호출자 목록이 비지 않고, 이름마다 꼴이 맞고, 겹치지 않는지 본다.
func checkCallers(callers []string) error {
	if len(callers) == 0 {
		return fmt.Errorf("%w: 비었다", ErrCallers)
	}
	seen := make(map[string]bool, len(callers))
	for _, name := range callers {
		if !callerName.MatchString(name) {
			return fmt.Errorf("%w: 이름 %q 가 %s 꼴이 아니다", ErrCallers, name, callerName)
		}
		if seen[name] {
			return fmt.Errorf("%w: 이름 %q 가 겹친다", ErrCallers, name)
		}
		seen[name] = true
	}
	return nil
}

// Match 는 authorization(요청의 Authorization 머리 원문 — SDK 는 토큰 파일 내용을 그대로 싣는다)이 어느 호출자의
// 토큰인지 돌려준다. 다이제스트끼리 상수 시간으로 견주고 모든 토큰을 끝까지 돈다(조기 종료 · 길이 누설 없음).
func (t Tokens) Match(authorization string) (caller string, ok bool) {
	sum := sha256.Sum256([]byte(authorization))
	found := -1
	for i := range t.sums {
		if subtle.ConstantTimeCompare(sum[:], t.sums[i][:]) == 1 {
			found = i
		}
	}
	if found < 0 {
		return "", false
	}
	return t.callers[found], true
}
