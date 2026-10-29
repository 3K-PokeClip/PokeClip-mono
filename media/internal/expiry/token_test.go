package expiry

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"testing"
)

// 만료 창구 토큰은 어느 길로 찍어도 원문이 나오지 않는다(keyresolve.Secret 꼴 — 설계 7절 prod 연결).
func TestTokenNeverPrints(t *testing.T) {
	const canary = "tok-canary-7f3a"
	tok := NewToken(canary)
	opt := Options{Token: tok}
	var buf bytes.Buffer
	slog.New(slog.NewJSONHandler(&buf, nil)).Info("x", "token", tok, "opt", opt)
	js, _ := json.Marshal(opt)
	for _, s := range []string{fmt.Sprint(tok), fmt.Sprintf("%v %+v %#v %s %q %x", tok, tok, tok, tok, tok, tok),
		fmt.Sprintf("%v %+v %#v", opt, opt, opt), fmt.Errorf("e: %w", nil).Error() + fmt.Sprintf("%d", tok), buf.String(), string(js)} {
		if strings.Contains(s, canary) {
			t.Fatalf("원문이 찍혔다: %s", s)
		}
	}
	if tok.reveal() != canary || (Token{}).reveal() != "" {
		t.Fatal("reveal 이 원문 · 빈 값을 돌려주지 않는다")
	}
}
