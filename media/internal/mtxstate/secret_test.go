package mtxstate

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"testing"
)

// 시험 자격은 모두 합성이다 — 어떤 계정의 값도 아니다. 비밀번호에 ':' 와 공백을 섞은 이유:
// Basic 은 첫 ':' 에서만 가르므로 비밀번호의 ':' 는 그대로 가야 하고, 앞뒤 공백도 걷지 않는다.
// 시험 실패 문구는 비밀번호를 찍지 않는다(ADR-018) — 실렸는지만 센다.
const (
	testUser = "sidecar"
	testPass = "syn:thetic obs-pass-0001"
)

// secretOutput 은 Secret 을 찍은 출력 하나다. place 는 찍은 길이고, redacted 면 원문 대신
// [REDACTED] 가 보여야 한다.
type secretOutput struct {
	place    string
	content  string
	redacted bool
}

// Secret 은 문자열 동사 · %#v · slog 의 두 처리기에서 원문 대신 [REDACTED] 를 보인다(mtxctl.Secret 과
// 같은 꼴). 값과 맞지 않는 동사 · encoding/json 으로 찍어도 원문은 없다.
func TestSecretPrintsRedacted(t *testing.T) {
	s := NewSecret(testPass)
	var outputs []secretOutput
	for _, arg := range []struct {
		name  string
		value any
	}{{name: "Secret", value: s}, {name: "*Secret", value: &s}} {
		for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q"} {
			outputs = append(outputs, secretOutput{place: "fmt " + verb + " of " + arg.name, content: fmt.Sprintf(verb, arg.value), redacted: true})
		}
		for _, verb := range []string{"%t", "%e", "%c", "%U", "%x", "%d", "%p", "%w"} {
			outputs = append(outputs, secretOutput{place: "fmt " + verb + " of " + arg.name, content: fmt.Sprintf(verb, arg.value)})
		}
		b, err := json.Marshal(arg.value)
		if err != nil {
			t.Errorf("json.Marshal(%s) 에러: %v", arg.name, err)
		}
		outputs = append(outputs, secretOutput{place: "json.Marshal of " + arg.name, content: string(b)})
		for _, h := range []struct {
			name       string
			newHandler func(io.Writer) slog.Handler
		}{
			{name: "slog Text", newHandler: func(w io.Writer) slog.Handler { return slog.NewTextHandler(w, nil) }},
			{name: "slog JSON", newHandler: func(w io.Writer) slog.Handler { return slog.NewJSONHandler(w, nil) }},
		} {
			var buf bytes.Buffer
			slog.New(h.newHandler(&buf)).Info("probe", "secret", arg.value)
			outputs = append(outputs, secretOutput{place: h.name + " of " + arg.name, content: buf.String(), redacted: true})
		}
	}

	// %x 는 문자열을 16진으로 찍는다 — 원문만 보면 놓친다.
	raw := []string{testPass, hex.EncodeToString([]byte(testPass))}
	for _, o := range outputs {
		for i, r := range raw {
			if n := strings.Count(o.content, r); n > 0 {
				t.Errorf("%s 에 카나리아 발견(표기 %d, %d회) — 0회여야 한다(출력 길이 %d)", o.place, i, n, len(o.content))
			}
		}
		if o.redacted && !strings.Contains(o.content, "[REDACTED]") {
			t.Errorf("%s 에 [REDACTED] 가 없다(출력 길이 %d)", o.place, len(o.content))
		}
	}
}
