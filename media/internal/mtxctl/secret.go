package mtxctl

import "log/slog"

// redacted 는 Secret 을 어떤 길로 찍어도 원문 대신 나오는 문자열이다(설계 6절 누설 규칙).
const redacted = "[REDACTED]"

// Secret 은 Control API 의 Basic 비번 원문을 감싼다. 원문은 이 패키지가 요청에 Basic 자격을 실을 때만 꺼낸다. fmt(값과
// 맞지 않는 동사 포함) · slog · encoding/json 어느 쪽으로 찍어도 원문이 나오지 않는다 — 그래서 Options 를 통째로 찍어도,
// 다른 값의 비공개 필드로 담아 찍어도 비번이 없다.
type Secret struct {
	// raw 를 포인터 뒤에 두는 이유: fmt 는 동사가 값과 맞지 않으면(구조체에 %p · Sprintf 의 %w 등) Format · String 같은
	// 메서드를 하나도 부르지 않고 값을 반사로 다시 찍는다(fmt/print.go badVerb · handleMethods). 문자열 필드는 그때
	// 그대로 나오지만, 문자열을 가리키는 포인터는 주소로만 찍힌다(streamkey.Passphrase 와 같은 꼴).
	raw *string
}

// NewSecret 은 raw 를 Secret 으로 감싼다. 빈 값은 NewClient 가 거부한다.
func NewSecret(raw string) Secret {
	return Secret{raw: &raw}
}

// reveal 은 원문이다. 영값이면 빈 문자열이다.
func (s Secret) reveal() string {
	if s.raw == nil {
		return ""
	}
	return *s.raw
}

// String 은 원문 대신 "[REDACTED]" 를 돌려준다.
func (Secret) String() string {
	return redacted
}

// GoString 은 %#v 에도 "[REDACTED]" 를 돌려준다.
func (Secret) GoString() string {
	return redacted
}

// LogValue 는 slog 에도 "[REDACTED]" 를 낸다.
func (Secret) LogValue() slog.Value {
	return slog.StringValue(redacted)
}
