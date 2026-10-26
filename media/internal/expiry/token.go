package expiry

import (
	"encoding/json"
	"log/slog"
)

// redacted 는 Token 을 어떤 길로 찍어도 원문 대신 나오는 문자열이다.
const redacted = "[REDACTED]"

// Token 은 clip 만료 창구 전용 토큰(X-Expired-Broadcasts-Token)의 원문을 감싼다. 원문은 이 패키지가 요청 머리에 실을
// 때만 꺼낸다. fmt(값과 맞지 않는 동사 포함) · slog · encoding/json 어느 쪽으로 찍어도 원문이 나오지 않는다
// (keyresolve.Secret 과 같은 꼴 — 원문을 포인터 뒤에 두어 반사 출력에도 주소만 찍힌다).
type Token struct {
	raw *string
}

// NewToken 은 raw 를 Token 으로 감싼다.
func NewToken(raw string) Token {
	return Token{raw: &raw}
}

// reveal 은 원문이다. 영값이면 빈 문자열이다.
func (t Token) reveal() string {
	if t.raw == nil {
		return ""
	}
	return *t.raw
}

// String 은 원문 대신 "[REDACTED]" 를 돌려준다.
func (Token) String() string { return redacted }

// GoString 은 %#v 에도 "[REDACTED]" 를 돌려준다.
func (Token) GoString() string { return redacted }

// LogValue 는 slog 에도 "[REDACTED]" 를 낸다.
func (Token) LogValue() slog.Value { return slog.StringValue(redacted) }

// MarshalJSON 은 JSON 에도 "[REDACTED]" 를 낸다.
func (Token) MarshalJSON() ([]byte, error) { return json.Marshal(redacted) }
