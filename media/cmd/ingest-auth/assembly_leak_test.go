package main

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/netip"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/ingestauth"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxctl"
)

// 누설 시험 「동사 × 조립 값」이다. leak_test 가 실행 파일의 출력 자리를 훑는다면, 여기서는 이 패키지가 조립해 쥐는 값과
// 그 재료를 통째로 찍는다 — 앞으로 누가 설정을 설명하려고 그 값을 로그 · 에러에 넘겨도 비밀이 나오지 않아야 한다. fmt 는
// 동사가 값과 맞지 않으면(포인터에 %s · 구조체에 %p · Sprintf 의 %w) String · Format 같은 메서드를 부르지 않고 값을 반사로
// 다시 찍는다(fmt/print.go badVerb) — 그래서 동사 표 전체를 돈다.

// printVerbs 는 찍어 보는 fmt 동사다. 값에 맞는 동사와 맞지 않는 동사를 함께 둔다.
var printVerbs = []string{"%v", "%+v", "%#v", "%s", "%q", "%t", "%e", "%c", "%U", "%x", "%d", "%p", "%w"}

// printedForms 는 value 를 printVerbs 의 동사마다 · slog 의 Text · JSON 처리기 · json.Marshal 로 찍은 출력이다. place 는
// 찍은 길이다.
func printedForms(t *testing.T, name string, value any) []leakOutput {
	t.Helper()
	var outputs []leakOutput
	for _, verb := range printVerbs {
		outputs = append(outputs, leakOutput{place: verb, content: fmt.Sprintf(verb, value)})
	}
	for _, h := range []struct {
		place      string
		newHandler func(io.Writer) slog.Handler
	}{
		{place: "slog Text", newHandler: func(w io.Writer) slog.Handler { return slog.NewTextHandler(w, nil) }},
		{place: "slog JSON", newHandler: func(w io.Writer) slog.Handler { return slog.NewJSONHandler(w, nil) }},
	} {
		var buf bytes.Buffer
		slog.New(h.newHandler(&buf)).Info("probe", "value", value)
		outputs = append(outputs, leakOutput{place: h.place, content: buf.String()})
	}
	b, err := json.Marshal(value)
	var unsupported *json.UnsupportedTypeError
	switch {
	case errors.As(err, &unsupported):
		// json 이 찍을 수 없는 타입의 필드(ingestauth.Options.Now 같은 함수)가 있으면 에러 문구가 그 값의 출력이다.
		b = []byte(err.Error())
	case err != nil:
		t.Errorf("json.Marshal(%s) returned error: %v", name, err)
	}
	return append(outputs, leakOutput{place: "json.Marshal", content: string(b)})
}

// serveConfig · 4C 클라이언트 · Control API 클라이언트와 세 설정 구조체(값 · 포인터 · 다른 값의 비공개 필드 — 처리기
// Options 포함)를 어느 동사 · slog · json 으로 찍어도 출입증 · API 비번 · 호출자 Basic 비번이 나오지 않는다(설계 6절
// 누설 규칙 2). 클라이언트와 serveConfig 는 run · cut 이 쓰는 생성자로 만든다.
func TestAssembledValuesHideSecrets(t *testing.T) {
	getenv := getenvFrom(configEnv())
	cfg, err := loadServeConfig(getenv)
	if err != nil {
		t.Fatalf("Setup: loadServeConfig returned error: %v", err)
	}
	mtx, err := newControlClient(getenv, newSecret(testAPIPass))
	if err != nil {
		t.Fatalf("Setup: newControlClient returned error: %v", err)
	}
	resolveOpt := keyresolve.ClientOptions{BaseURL: "http://192.0.2.30:8082", Token: keyresolve.NewSecret(testResolveToken)}
	mtxOpt := mtxctl.Options{APIURL: "http://192.0.2.10:9997", User: testAPIUser, Pass: mtxctl.NewSecret(testAPIPass)}
	handlerOpt := ingestauth.Options{
		CallerIP:   netip.MustParseAddr("192.0.2.10"),
		SelfIP:     netip.MustParseAddr("192.0.2.20"),
		APIUser:    testAPIUser,
		APIPass:    ingestauth.NewSecret(testAPIPass),
		CallerUser: testCallerUser,
		CallerPass: ingestauth.NewSecret(testCallerPass),
	}
	held := struct {
		resolve keyresolve.ClientOptions
		mtx     mtxctl.Options
		handler ingestauth.Options
	}{resolveOpt, mtxOpt, handlerOpt}
	values := []struct {
		name  string
		value any
	}{
		{name: "serveConfig", value: cfg},
		{name: "*serveConfig", value: &cfg},
		{name: "*keyresolve.Client", value: cfg.resolver},
		{name: "keyresolve.ClientOptions", value: resolveOpt},
		{name: "*keyresolve.ClientOptions", value: &resolveOpt},
		{name: "mtxctl.Options", value: mtxOpt},
		{name: "*mtxctl.Options", value: &mtxOpt},
		{name: "*mtxctl.Client", value: mtx},
		{name: "ingestauth.Options", value: handlerOpt},
		{name: "*ingestauth.Options", value: &handlerOpt},
		{name: "세 설정을 비공개 필드로 든 값", value: held},
		{name: "세 설정을 비공개 필드로 든 값의 포인터", value: &held},
	}
	// %x 는 문자열을 16진으로, Basic 자격은 머리에 base64 로 실린다 — 원문만 보면 그 둘을 놓친다.
	secrets := []leakCanary{
		{name: "resolve 출입증", value: testResolveToken},
		{name: "resolve 출입증(16진)", value: hex.EncodeToString([]byte(testResolveToken))},
		{name: "API 비번", value: testAPIPass},
		{name: "API 비번(16진)", value: hex.EncodeToString([]byte(testAPIPass))},
		{name: "Basic 자격", value: base64.StdEncoding.EncodeToString([]byte(testAPIUser + ":" + testAPIPass))},
		{name: "호출자 Basic 비번", value: testCallerPass},
		{name: "호출자 Basic 비번(16진)", value: hex.EncodeToString([]byte(testCallerPass))},
		{name: "호출자 Basic 자격", value: base64.StdEncoding.EncodeToString([]byte(testCallerUser + ":" + testCallerPass))},
	}

	for _, v := range values {
		for _, out := range printedForms(t, v.name, v.value) {
			for _, s := range secrets {
				if n := strings.Count(out.content, s.value); n > 0 {
					t.Errorf("%s %s carries the %s canary %d time(s), want 0 (output length %d)", v.name, out.place, s.name, n, len(out.content))
				}
			}
		}
	}
}
