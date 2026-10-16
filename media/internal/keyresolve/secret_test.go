package keyresolve

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

// secretOutput 은 Secret 을 찍은 출력 하나다. place 는 찍은 길이고, redacted 면 원문 대신 [REDACTED] 가 보여야 한다.
type secretOutput struct {
	place    string
	content  string
	redacted bool
}

// Secret 은 문자열 동사 · %#v · slog 의 두 처리기에서 원문 대신 [REDACTED] 를 보인다(streamkey.Passphrase 와 같은 꼴 —
// 설계 6절 누설 규칙). 값과 맞지 않는 동사 · encoding/json 으로 찍어도 원문은 없다. 실패 문구에는 원문을 싣지 않는다.
func TestSecretPrintsRedacted(t *testing.T) {
	s := NewSecret(testResolveToken)
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
			t.Errorf("json.Marshal(%s) returned error: %v", arg.name, err)
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
	raw := []string{testResolveToken, hex.EncodeToString([]byte(testResolveToken))}
	for _, o := range outputs {
		for _, r := range raw {
			if n := strings.Count(o.content, r); n > 0 {
				t.Errorf("%s carries the raw token %d time(s), want 0 (output length %d)", o.place, n, len(o.content))
			}
		}
		if o.redacted && !strings.Contains(o.content, "[REDACTED]") {
			t.Errorf("%s does not show [REDACTED] (output length %d)", o.place, len(o.content))
		}
	}
}
