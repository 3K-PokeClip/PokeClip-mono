// Package streamkey 는 스트림키의 두 값 — 토큰(공개 식별자)과 passphrase(비밀) — 을 다룬다.
package streamkey

import (
	"errors"
	"log/slog"
	"regexp"
	"strings"
)

// tokenPattern 은 MediaMTX 설정의 정규식 경로(설계 2-8)와 같은 식이다. MediaMTX 도 Go regexp 로
// 경로 이름을 맞추므로 끝 줄바꿈 같은 경계에서도 두 판정이 갈리지 않는다($ 는 텍스트 끝에만 맞는다).
var tokenPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// passphrasePattern 은 media 가 받아 주는 passphrase 의 꼴이다. 길이 32 고정은 auth 의 형식이라
// 여기 박지 않는다(설계 2-2).
var passphrasePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{10,64}$`)

// IsToken 은 s 가 토큰 꼴(26자 Crockford 대문자 — I · L · O · U 없음)인지 본다.
// 토큰은 MediaMTX 의 경로 이름이기도 하다.
func IsToken(s string) bool {
	return tokenPattern.MatchString(s)
}

// 정규형 streamid 의 앞뒤 고정 글자다(계약4 2절 — #!::r=<토큰>,m=publish).
const (
	streamIDPrefix = "#!::r="
	streamIDSuffix = ",m=publish"
)

// ParseStreamID 는 계약4 2절 정규형 streamid(#!::r=<26자 토큰>,m=publish) 하나만 받아 토큰을 꺼낸다.
// auth StreamId.parse 보다 엄격하다 — auth 는 필드 순서를 허용하고 r 이 둘이면 마지막 값을 읽는다
// (services/auth/.../StreamId.java:29 · keyresolve/client.go:204-205). 플러그인은 정규형만 보내므로(계약4 3-1)
// 정상 요청은 두 쪽을 다 지난다. 비정규형을 여기서 거부해 auth 가 다른 r 을 읽는 길을 닫는다.
func ParseStreamID(s string) (token string, ok bool) {
	rest, found := strings.CutPrefix(s, streamIDPrefix)
	if !found {
		return "", false
	}
	token, found = strings.CutSuffix(rest, streamIDSuffix)
	if !found || !IsToken(token) {
		return "", false
	}
	return token, true
}

// redacted 는 Passphrase 를 어떤 길로 찍어도 원문 대신 나오는 문자열이다(설계 6절 누설 규칙 1).
const redacted = "[REDACTED]"

// Passphrase 는 송출 연결의 SRT passphrase 다. 원문은 Reveal 로만 꺼낸다.
// fmt · slog · encoding/json 어느 쪽으로 찍어도 원문이 나가지 않는다.
type Passphrase struct {
	// raw 를 포인터 뒤에 두는 이유: fmt 는 비공개 필드에 든 값의 메서드(String 등)를 부르지 못해
	// 안쪽을 그대로 찍는다. 포인터는 그때 주소로만 찍히므로, Passphrase 를 비공개 필드로 든
	// 구조체를 통째로 찍어도 원문이 없다.
	raw *string
}

// NewPassphrase 는 raw 가 10–64자의 [A-Za-z0-9_-] 일 때만 Passphrase 로 만든다.
// 거부 에러에는 raw 를 싣지 않는다.
func NewPassphrase(raw string) (Passphrase, error) {
	if !passphrasePattern.MatchString(raw) {
		return Passphrase{}, errors.New("streamkey: passphrase 는 10–64자의 [A-Za-z0-9_-] 여야 한다")
	}
	return Passphrase{raw: &raw}, nil
}

// Reveal 은 원문을 돌려준다. 부르는 곳은 둘뿐이다 — 인증 응답 본문 조립(ingestauth)과 4D Bearer 파생 값의 HMAC 키
// (endsignal). 영값이면 빈 문자열이다.
func (p Passphrase) Reveal() string {
	if p.raw == nil {
		return ""
	}
	return *p.raw
}

// IsZero 는 p 가 영값(NewPassphrase 를 거치지 않은 값)인지 본다.
func (p Passphrase) IsZero() bool {
	return p.raw == nil
}

// String 은 원문 대신 "[REDACTED]" 를 돌려준다.
func (Passphrase) String() string {
	return redacted
}

// GoString 은 %#v 에도 "[REDACTED]" 를 돌려준다.
func (Passphrase) GoString() string {
	return redacted
}

// LogValue 는 slog 에도 "[REDACTED]" 를 낸다.
func (Passphrase) LogValue() slog.Value {
	return slog.StringValue(redacted)
}
