package streamkey

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"testing"
)

// 시험 값은 모두 합성이다. auth 가 실제로 만드는 꼴(Base64url 32자)과 문자셋만 같다.
const syntheticRaw = "Synthetic_Pass-phrase_0123456789"

// 토큰 꼴은 26자 Crockford 대문자다. MediaMTX 정규식 경로 ~^[0-9A-HJKMNP-TV-Z]{26}$ 와
// 같은 집합이어야 한다 — 처리기는 이 판정을 통과한 경로 이름만 auth 에 묻는다(설계 2-4 판정표).
func TestIsToken(t *testing.T) {
	tests := []struct {
		name string
		s    string
		want bool
	}{
		{name: "26자", s: "01ARZ3NDEKTSV4RRFFQ69G5FAV", want: true},
		// 아래 두 줄이 문자셋 32자를 모두 한 번 이상 쓴다.
		{name: "문자셋_앞쪽", s: "0123456789ABCDEFGHJKMNPQRS", want: true},
		{name: "문자셋_뒤쪽", s: "TVWXYZ0123456789ABCDEFGHJK", want: true},

		{name: "25자", s: "01ARZ3NDEKTSV4RRFFQ69G5FA", want: false},
		{name: "27자", s: "01ARZ3NDEKTSV4RRFFQ69G5FAVX", want: false},
		{name: "빈_값", s: "", want: false},
		{name: "금지_글자_I", s: "01ARZ3NDEKTSV4RRFFQ69G5FAI", want: false},
		{name: "금지_글자_L", s: "01ARZ3NDEKTSV4RRFFQ69G5FAL", want: false},
		{name: "금지_글자_O", s: "01ARZ3NDEKTSV4RRFFQ69G5FAO", want: false},
		{name: "금지_글자_U", s: "01ARZ3NDEKTSV4RRFFQ69G5FAU", want: false},
		{name: "소문자_전부", s: "01arz3ndektsv4rrffq69g5fav", want: false},
		{name: "소문자_한_자", s: "01ARZ3NDEKTSV4RRFFQ69G5FAv", want: false},
		{name: "앞_공백", s: " 1ARZ3NDEKTSV4RRFFQ69G5FAV", want: false},
		{name: "가운데_공백", s: "01ARZ3NDEKTS 4RRFFQ69G5FAV", want: false},
		// 25자 뒤 줄바꿈 — 바이트로는 26이다.
		{name: "25자_끝_줄바꿈", s: "01ARZ3NDEKTSV4RRFFQ69G5FA\n", want: false},
		// 26자 뒤 줄바꿈 — $ 가 끝 줄바꿈 앞에서도 맞으면((?m) 이나 그렇게 도는 엔진) 여기서 샌다. Go 의 $ 는 텍스트 끝에만
		// 맞는다(regexp/syntax).
		{name: "26자_끝_줄바꿈", s: "01ARZ3NDEKTSV4RRFFQ69G5FAV\n", want: false},
		{name: "하이픈", s: "01ARZ3NDEKTSV4RRFFQ69G5FA-", want: false},
		// 전각 Ａ(U+FF21)로 바꾼 26글자 — 글자 수는 맞지만 ASCII 가 아니다.
		{name: "전각_글자", s: "01ARZ3NDEKTSV4RRFFQ69G5FＡV", want: false},
		// 2바이트 글자 하나로 바이트 수를 26에 맞춘 값.
		{name: "비_ASCII_26바이트", s: "01ARZ3NDEKTSV4RRFFQ69G5FÉ", want: false},
	}
	for _, tc := range tests {
		if got := IsToken(tc.s); got != tc.want {
			t.Errorf("%s: IsToken(%q) = %v, want %v", tc.name, tc.s, got, tc.want)
		}
	}
}

// 길이 경계는 10–64자다. 32자 고정은 auth 의 형식이라 media 에 박지 않는다(설계 2-2). 실패 문구에는 값 대신
// 행 이름과 길이를 싣는다 — 합성이어도 passphrase 꼴 값이다.
func TestNewPassphraseAcceptsInFormat(t *testing.T) {
	for _, tc := range []struct{ name, raw string }{
		{name: "10자_하한", raw: "Syn-10char"},
		{name: "64자_상한", raw: strings.Repeat("Ab-_", 16)},
		{name: "32자_auth_꼴", raw: syntheticRaw},
		{name: "문자셋_네_갈래", raw: "0123456789abcdefABCDEF_-xyzZ"},
	} {
		p, err := NewPassphrase(tc.raw)
		if err != nil {
			t.Errorf("NewPassphrase(%s — %d bytes) returned error: %v", tc.name, len(tc.raw), err)
			continue
		}
		if got := p.Reveal(); got != tc.raw {
			t.Errorf("NewPassphrase(%s).Reveal() returned %d bytes that differ from the raw value (%d bytes)", tc.name, len(got), len(tc.raw))
		}
		if p.IsZero() {
			t.Errorf("NewPassphrase(%s).IsZero() = true, want false", tc.name)
		}
	}
}

// 거부 에러 문구에는 원문이 없어야 한다 — 에러는 로그로 흘러간다(설계 6절 누설 규칙). 실패 문구에는 값 대신 행
// 이름과 길이를 싣는다.
func TestNewPassphraseRejectsOutOfFormat(t *testing.T) {
	tests := []struct {
		name string
		raw  string
	}{
		{name: "9자", raw: "Syn-9char"},
		{name: "65자", raw: strings.Repeat("Ab-_", 16) + "Z"},
		{name: "빈_값", raw: ""},
		{name: "점", raw: "Synthetic.pass_01"},
		{name: "공백", raw: "Synthetic pass_01"},
		{name: "더하기", raw: "Synthetic+pass_01"},
		{name: "슬래시", raw: "Synthetic/pass_01"},
		{name: "등호", raw: "Synthetic_pass_01="},
		{name: "비_ASCII", raw: "Synthetic_pass_é1"},
		{name: "전각_숫자", raw: "Synthetic_pass_０1"},
		{name: "끝_줄바꿈", raw: "Synthetic_pass_01\n"},
	}
	for _, tc := range tests {
		p, err := NewPassphrase(tc.raw)
		if err == nil {
			t.Errorf("NewPassphrase(%s — %d bytes) returned nil error, want error", tc.name, len(tc.raw))
			continue
		}
		if tc.raw != "" && strings.Contains(err.Error(), tc.raw) {
			t.Errorf("NewPassphrase(%s) error carries the raw value (error length %d)", tc.name, len(err.Error()))
		}
		if !p.IsZero() {
			t.Errorf("NewPassphrase(%s) returned a non-zero Passphrase with the error", tc.name)
		}
	}
}

func TestZeroPassphrase(t *testing.T) {
	var p Passphrase
	if !p.IsZero() {
		t.Error("Passphrase{}.IsZero() = false, want true")
	}
	if got := p.Reveal(); got != "" {
		t.Errorf("Passphrase{}.Reveal() returned %d bytes, want empty", len(got))
	}
}

const redactedMark = "[REDACTED]"

// holder 는 Passphrase 를 공개 필드와 비공개 필드에 하나씩 든다. fmt 는 비공개 필드에 든 값의
// 메서드(String 등)를 부르지 못해 안쪽 표현을 그대로 찍는다 — 그 길로도 원문이 나가면 안 된다.
type holder struct {
	Exported   Passphrase
	unexported Passphrase
}

func mustPassphrase(t *testing.T, raw string) Passphrase {
	t.Helper()
	p, err := NewPassphrase(raw)
	if err != nil {
		t.Fatalf("Setup: NewPassphrase(<%d bytes>) returned error: %v", len(raw), err)
	}
	return p
}

// 값을 직접 찍으면 원문 대신 [REDACTED] 가 나온다(설계 6절 누설 규칙 1).
func TestPassphraseFormatsRedacted(t *testing.T) {
	p := mustPassphrase(t, syntheticRaw)
	for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q"} {
		for _, arg := range []any{p, &p} {
			got := fmt.Sprintf(verb, arg)
			if strings.Contains(got, syntheticRaw) {
				t.Errorf("fmt.Sprintf(%q, %T) carries the raw value (output length %d)", verb, arg, len(got))
			}
			if !strings.Contains(got, redactedMark) {
				t.Errorf("fmt.Sprintf(%q, %T) does not show %q (output length %d)", verb, arg, redactedMark, len(got))
			}
		}
	}
	// %d · %x 는 String 을 거치지 않거나 바이트를 바꿔 찍는다. 원문만 없으면 된다.
	for _, verb := range []string{"%d", "%x"} {
		if got := fmt.Sprintf(verb, p); strings.Contains(got, syntheticRaw) {
			t.Errorf("fmt.Sprintf(%q, Passphrase) carries the raw value (output length %d)", verb, len(got))
		}
	}
}

// 구조체를 통째로 찍어도 원문이 없다 — 비공개 필드 갈래가 핵심이다.
func TestPassphraseInStructFormatsWithoutRaw(t *testing.T) {
	p := mustPassphrase(t, syntheticRaw)
	h := holder{Exported: p, unexported: p}
	for _, verb := range []string{"%v", "%+v", "%#v", "%d"} {
		for _, arg := range []any{h, &h} {
			if got := fmt.Sprintf(verb, arg); strings.Contains(got, syntheticRaw) {
				t.Errorf("fmt.Sprintf(%q, %T) carries the raw value (output length %d)", verb, arg, len(got))
			}
		}
	}
}

// slog 의 두 처리기 모두 — 값 자체와 그 값을 담은 구조체 — 에서 원문이 없다.
func TestPassphraseLogsRedacted(t *testing.T) {
	p := mustPassphrase(t, syntheticRaw)
	h := holder{Exported: p, unexported: p}
	handlers := map[string]func(io.Writer) slog.Handler{
		"text": func(w io.Writer) slog.Handler { return slog.NewTextHandler(w, nil) },
		"json": func(w io.Writer) slog.Handler { return slog.NewJSONHandler(w, nil) },
	}
	for name, newHandler := range handlers {
		var buf bytes.Buffer
		slog.New(newHandler(&buf)).Info("leak_probe",
			slog.Any("value", p), "pointer", &p, "struct", h, "struct_pointer", &h)
		got := buf.String()
		if strings.Contains(got, syntheticRaw) {
			t.Errorf("%s handler output carries the raw value (output length %d)", name, len(got))
		}
		if !strings.Contains(got, redactedMark) {
			t.Errorf("%s handler output does not show %q (output length %d)", name, redactedMark, len(got))
		}
	}
}

// json.Marshal 에도 원문이 없다. 원문은 공개 필드가 아니라서 이미 성립하지만, 원문을 내는
// MarshalJSON 이나 공개 필드가 생기면 여기서 걸린다.
func TestPassphraseMarshalsWithoutRaw(t *testing.T) {
	p := mustPassphrase(t, syntheticRaw)
	h := holder{Exported: p, unexported: p}
	for _, v := range []any{p, &p, h, &h} {
		b, err := json.Marshal(v)
		if err != nil {
			t.Errorf("json.Marshal(%T) returned error: %v", v, err)
			continue
		}
		if strings.Contains(string(b), syntheticRaw) {
			t.Errorf("json.Marshal(%T) carries the raw value (output length %d)", v, len(b))
		}
	}
}

// ParseStreamID 는 계약4 2절 정규형(#!::r=<토큰>,m=publish) 하나만 받는다. auth StreamId.parse 는 필드 순서를
// 허용하고 r 이 둘이면 마지막 값을 읽으므로, 정규형이 아닌 값은 모두 여기서 거부해야 auth 가 다른 r 을 읽는 길이
// 닫힌다(POK-472 설계 7절 P11).
func TestParseStreamID(t *testing.T) {
	const token = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
	const other = "0123456789ABCDEFGHJKMNPQRS"
	tests := []struct {
		name      string
		s         string
		wantToken string
		wantOK    bool
	}{
		{name: "정규형", s: "#!::r=" + token + ",m=publish", wantToken: token, wantOK: true},
		{name: "정규형_다른_토큰", s: "#!::r=" + other + ",m=publish", wantToken: other, wantOK: true},

		{name: "빈_값", s: ""},
		{name: "토큰만", s: token},
		{name: "m_없음", s: "#!::r=" + token},
		{name: "m_request", s: "#!::r=" + token + ",m=request"},
		{name: "필드_순서_뒤바뀜", s: "#!::m=publish,r=" + token},
		{name: "r_둘", s: "#!::r=" + other + ",r=" + token + ",m=publish"},
		{name: "r_둘_뒤에", s: "#!::r=" + token + ",m=publish,r=" + other},
		{name: "추가_필드", s: "#!::r=" + token + ",m=publish,s=x"},
		{name: "앞_공백", s: " #!::r=" + token + ",m=publish"},
		{name: "뒤_공백", s: "#!::r=" + token + ",m=publish "},
		{name: "끝_줄바꿈", s: "#!::r=" + token + ",m=publish\n"},
		{name: "토큰_소문자", s: "#!::r=01arz3ndektsv4rrffq69g5fav,m=publish"},
		{name: "토큰_25자", s: "#!::r=01ARZ3NDEKTSV4RRFFQ69G5FA,m=publish"},
		{name: "토큰_27자", s: "#!::r=01ARZ3NDEKTSV4RRFFQ69G5FAVX,m=publish"},
		{name: "토큰_금지_글자", s: "#!::r=01ARZ3NDEKTSV4RRFFQ69G5FAI,m=publish"},
		{name: "접두_대문자_R", s: "#!::R=" + token + ",m=publish"},
		{name: "접두_없음", s: "r=" + token + ",m=publish"},
		{name: "m_대문자", s: "#!::r=" + token + ",m=PUBLISH"},
		{name: "쉼표_뒤_공백", s: "#!::r=" + token + ", m=publish"},
	}
	for _, tc := range tests {
		gotToken, gotOK := ParseStreamID(tc.s)
		if gotToken != tc.wantToken || gotOK != tc.wantOK {
			t.Errorf("%s: ParseStreamID(%q) = (%q, %v), want (%q, %v)", tc.name, tc.s, gotToken, gotOK, tc.wantToken, tc.wantOK)
		}
	}
}
