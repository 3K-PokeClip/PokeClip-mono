package main

import (
	"fmt"
	"log/slog"
	"path/filepath"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/secretfile"
)

// fileSuffix 는 비밀 환경 변수의 파일 입력 꼴이다 — NAME 대신 NAME_FILE 에 그 값이 든 파일의 절대 경로를 준다(계약 3-2).
// 환경 변수 값은 docker inspect 에 남는다.
const fileSuffix = "_FILE"

// redacted 는 secret 을 어떤 길로 찍어도 원문 대신 나오는 문자열이다(설계 6절 누설 규칙).
const redacted = "[REDACTED]"

// secret 은 환경 변수나 파일에서 읽은 비밀 원문을 감싼다. 원문은 생성자에 넘길 때만 꺼낸다. fmt(값과 맞지 않는 동사
// 포함) · slog · encoding/json 어느 쪽으로 찍어도 원문이 나오지 않는다(ingestauth.Secret 과 같은 꼴) — 그래서
// serveConfig 를 통째로 찍어도 비밀이 없다.
type secret struct {
	// raw 를 포인터 뒤에 두는 이유: fmt 는 동사가 값과 맞지 않으면 String 같은 메서드를 부르지 않고 값을 반사로 다시
	// 찍는다(fmt/print.go badVerb). 문자열 필드는 그때 그대로 나오지만, 문자열을 가리키는 포인터는 주소로만 찍힌다.
	raw *string
}

// newSecret 은 raw 를 secret 으로 감싼다.
func newSecret(raw string) secret {
	return secret{raw: &raw}
}

// reveal 은 원문이다. 영값이면 빈 문자열이다.
func (s secret) reveal() string {
	if s.raw == nil {
		return ""
	}
	return *s.raw
}

// String 은 원문 대신 "[REDACTED]" 를 돌려준다.
func (secret) String() string {
	return redacted
}

// GoString 은 %#v 에도 "[REDACTED]" 를 돌려준다.
func (secret) GoString() string {
	return redacted
}

// LogValue 는 slog 에도 "[REDACTED]" 를 낸다.
func (secret) LogValue() slog.Value {
	return slog.StringValue(redacted)
}

// resolveSecret 은 비밀 name 을 NAME(값)이나 NAME_FILE(그 값이 든 파일의 절대 경로)에서 읽는다. 빈 문자열은 없는 것으로
// 본다(requireSet 과 같은 규칙). 둘 다 있으면 거부한다 — 한쪽이 조용히 이기면 교체한 뒤에도 옛 값을 쓴다. 파일 검사
// 규칙은 secretfile.Read 의 것이다.
//
// 에러에는 변수 이름과 안전한 원인만 싣는다 — 값도 경로도 싣지 않는다(계약 3-2 「경로 대신 변수 이름」). 비밀을 _FILE
// 자리에 잘못 넣으면 그 값이 곧 경로다.
func resolveSecret(getenv func(string) string, name string) (secret, error) {
	fileName := name + fileSuffix
	value, path := getenv(name), getenv(fileName)
	switch {
	case value != "" && path != "":
		return secret{}, fmt.Errorf("%s 와 %s 를 함께 줄 수 없다 — 하나만 준다", name, fileName)
	case value != "":
		return newSecret(value), nil
	case path == "":
		return secret{}, fmt.Errorf("환경 변수가 비었다: %s 또는 %s", name, fileName)
	case !filepath.IsAbs(path):
		return secret{}, fmt.Errorf("%s 는 절대 경로여야 한다", fileName)
	}
	raw, err := secretfile.Read(fileName, path, secretfile.Options{ShowPath: false})
	if err != nil {
		return secret{}, err
	}
	return newSecret(raw), nil
}

// secrets 는 run 이 쓰는 비밀이다(호출자 Basic 은 사용자 이름 · 끔과 함께). loadSecrets 가 한 번만 읽는다 — 같은
// 변수를 두 번 읽어 두 값이 갈리지 않게 한다.
type secrets struct {
	apiPass      secret      // MTX_API_PASS — Control API 비번(api 행 검사 · cut 의 Basic)
	resolveToken secret      // STREAM_KEY_RESOLVE_TOKEN — 4C 출입증
	caller       callerBasic // INGEST_AUTH_BASIC_USER · INGEST_AUTH_BASIC_PASS 또는 INGEST_AUTH_CALLER_BASIC=off
}

// loadSecrets 는 run 의 비밀을 모두 읽는다. 첫 에러에서 멈춘다.
func loadSecrets(getenv func(string) string) (secrets, error) {
	apiPass, err := resolveSecret(getenv, envMTXAPIPass)
	if err != nil {
		return secrets{}, err
	}
	resolveToken, err := resolveSecret(getenv, envResolveToken)
	if err != nil {
		return secrets{}, err
	}
	caller, err := loadCallerBasic(getenv)
	if err != nil {
		return secrets{}, err
	}
	return secrets{apiPass: apiPass, resolveToken: resolveToken, caller: caller}, nil
}
