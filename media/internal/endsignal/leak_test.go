package endsignal

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// P6 — 판정 갈래 전부를 지난 뒤 DEBUG 까지 받은 로그에 Authorization 값 · 파생 값 · passphrase · 본문 카나리아가 0 번이다.
// 카나리아가 실제로 입력에 들어갔음(가짜 resolve 가 카나리아 passphrase 를 돌려줌 · 맞는 파생 값이 202)을 먼저 확인한다.
func TestNoSecretsInLogsAcrossBranchesP6(t *testing.T) {
	// canaryBearer 는 꼴만 맞는 틀린 파생 값이다. 맞는 파생 값은 deriveForTest 로 따로 계산한다.
	const (
		canaryPassphrase = "CANARY-passphrase-4d-leak-test-0001"
		canaryBearer     = "6eb2d1d46e3d8a6c22ad0f0ce1dba7b9b0aa1a5ba0d9ae49c8a3f8a3b2e2cdb0"
		canaryBody       = "CANARYBODYVALUE"
	)
	derived := deriveForTest(t, canaryPassphrase)
	branches := []struct {
		name    string
		body    string
		auth    string
		verdict func(t *testing.T) (keyresolve.Verdict, error)
		apply   error
	}{
		{name: "accepted", auth: "Bearer " + derived},
		{name: "accepted_대문자", auth: "Bearer " + strings.ToUpper(derived)},
		{name: "bad_request", body: `{"streamid":"` + canaryBody + `"}`, auth: "Bearer " + derived},
		{name: "bad_auth_form", auth: "Basic " + derived},
		{name: "mismatch", auth: "Bearer " + canaryBearer},
		{name: "key_rejected", auth: "Bearer " + derived, verdict: func(*testing.T) (keyresolve.Verdict, error) {
			return keyresolve.Verdict{Reason: "REVOKED"}, nil
		}},
		{name: "resolve_undecided", auth: "Bearer " + derived, verdict: func(*testing.T) (keyresolve.Verdict, error) {
			return keyresolve.Verdict{}, &keyresolve.UndecidedError{Kind: "status", Status: 500}
		}},
		{name: "apply_failed", auth: "Bearer " + derived, apply: errors.New("pg 오류")},
	}
	var logs strings.Builder
	accepted := 0
	for _, b := range branches {
		f := newFixture(t, nil)
		f.resolve = func(context.Context, string) (keyresolve.Verdict, error) {
			if b.verdict != nil {
				return b.verdict(t)
			}
			return validVerdict(t, canaryPassphrase), nil
		}
		f.apply = func(context.Context, session.IntentionalEnd) (session.IntentionalResult, error) {
			return session.IntentionalResult{Effect: "marked"}, b.apply
		}
		body := b.body
		if body == "" {
			body = `{"note":"` + canaryBody + `","streamid":"#!::r=` + testToken + `,m=publish","elapsedSinceStopMs":1,"connectionDurationMs":5000}`
		}
		if f.post(body, b.auth).status == 202 {
			accepted++
		}
		logs.WriteString(f.log.String())
	}
	if accepted != 2 {
		t.Fatalf("카나리아 파생 값 202 = %d, want 2 — 카나리아가 흐르지 않았다", accepted)
	}
	out := logs.String()
	for name, canary := range map[string]string{
		"passphrase": canaryPassphrase, "파생값": derived, "파생값_대문자": strings.ToUpper(derived),
		"틀린_Bearer": canaryBearer, "본문": canaryBody, "Authorization": "Bearer ",
	} {
		if n := strings.Count(out, canary); n != 0 {
			t.Errorf("로그에 %s 카나리아 %d 번", name, n)
		}
	}
}

// leakBranch 는 P6 나머지 갈래 하나다. want 는 그 갈래를 지났다는 증거 줄("msg:reason")이다.
type leakBranch struct {
	name string
	want string
	edit func(*Options)
	send func(t *testing.T, f *fixture, body, auth string)
}

// P6 나머지 갈래 — 0 단계(closing · rate_global) · busy · rate_key · 본문 마감 · 마감 미지원 · Apply 시한 · Apply 중 닫힘.
// 갈래마다 증거 줄이 찍혔는지 먼저 확인하고(카나리아가 그 갈래를 지났다), 모은 로그에 카나리아가 0 번이다. 갈래 끝마다
// Close 로 진행 중 처리기를 기다리고 남은 묶음 줄을 내보낸다(시계가 고정이라 묶음은 Close 가 낸다).
func TestNoSecretsInLogsAcrossLimitBranchesP6(t *testing.T) {
	const (
		canaryPassphrase = "CANARY-passphrase-4d-leak-test-0002"
		canaryBody       = "CANARYBODYVALUE2"
	)
	derived := deriveForTest(t, canaryPassphrase)
	body := `{"note":"` + canaryBody + `","streamid":"#!::r=` + testToken + `,m=publish","elapsedSinceStopMs":1,"connectionDurationMs":5000}`
	post := func(_ *testing.T, f *fixture, body, auth string) { f.post(body, auth) }
	branches := []leakBranch{
		{name: "closing_0단계", want: "end_signal_shed:closing", send: func(t *testing.T, f *fixture, body, auth string) {
			closeNow(t, f)
			f.post(body, auth)
		}},
		{name: "rate_global", want: "end_signal_shed:rate_global", send: func(t *testing.T, f *fixture, body, auth string) {
			for range 10 {
				f.recv.o.Global.Allow(globalKey)
			}
			f.post(body, auth)
		}},
		{name: "busy", want: "end_signal:busy", send: func(t *testing.T, f *fixture, body, auth string) {
			f.recv.sem <- struct{}{}
			f.post(body, auth)
			<-f.recv.sem
		}},
		{name: "rate_key", want: "end_signal:rate_key", send: func(t *testing.T, f *fixture, body, auth string) {
			f.recv.o.PerKey.Allow(testToken)
			f.post(body, auth)
		}},
		{name: "body_timeout", want: "end_signal:body_timeout", edit: func(o *Options) { o.BodyTimeout = 100 * time.Millisecond },
			send: postPartialBody},
		{name: "body_deadline_unsupported", want: "end_signal:body_deadline_unsupported", send: func(t *testing.T, f *fixture, body, auth string) {
			req := httptest.NewRequestWithContext(context.Background(), http.MethodPost, "/api/ingest/end-signal", strings.NewReader(body))
			req.Header.Set("Authorization", auth)
			f.recv.Route().Handler.ServeHTTP(plainWriter{httptest.NewRecorder()}, req)
		}},
		{name: "apply_timeout", want: "end_signal:apply_timeout", edit: func(o *Options) { o.ApplyTimeout = 50 * time.Millisecond },
			send: post},
		{name: "closing_apply", want: "end_signal:closing", send: func(t *testing.T, f *fixture, body, auth string) {
			done := make(chan struct{})
			go func() {
				defer close(done)
				f.post(body, auth)
			}()
			// 두 대기에 상한을 둔다 — 앞단 회귀로 요청이 Apply 전에 끝나거나 Close 뒤에도 끝나지 않으면 패키지 시한까지
			// 매달리지 않고 이 자리에서 실패한다.
			select {
			case <-f.entered:
			case <-done:
				t.Fatalf("closing_apply: 요청이 Apply 에 들어가기 전에 끝났다")
			case <-time.After(3 * time.Second):
				t.Fatalf("closing_apply: 3초 안에 Apply 에 들어가지 않았다")
			}
			closeNow(t, f)
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Fatalf("closing_apply: Close 뒤 3초 안에 요청이 끝나지 않았다")
			}
		}},
	}
	var logs strings.Builder
	for _, b := range branches {
		f := newFixture(t, b.edit)
		f.entered = make(chan struct{}, 1)
		f.resolve = func(context.Context, string) (keyresolve.Verdict, error) {
			return validVerdict(t, canaryPassphrase), nil
		}
		f.apply = func(ctx context.Context, _ session.IntentionalEnd) (session.IntentionalResult, error) {
			f.entered <- struct{}{}
			<-ctx.Done()
			return session.IntentionalResult{}, ctx.Err()
		}
		b.send(t, f, body, "Bearer "+derived)
		closeNow(t, f)
		if !hasLine(f.log.lines(t), b.want) {
			t.Errorf("%s: %s 줄이 없다 — 카나리아가 그 갈래를 지나지 않았다", b.name, b.want)
		}
		logs.WriteString(f.log.String())
	}
	out := logs.String()
	for name, canary := range map[string]string{
		"passphrase": canaryPassphrase, "파생값": derived, "파생값_대문자": strings.ToUpper(derived),
		"본문": canaryBody, "Authorization": "Bearer ",
	} {
		if n := strings.Count(out, canary); n != 0 {
			t.Errorf("로그에 %s 카나리아 %d 번", name, n)
		}
	}
}

// closeNow 는 1.5초 시한으로 Close 를 부른다(두 번 불러도 된다).
func closeNow(t *testing.T, f *fixture) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	if err := f.recv.Close(ctx); err != nil {
		t.Fatalf("Close: %v", err)
	}
}

// hasLine 은 "msg:reason" 줄이 있는지 본다.
func hasLine(lines []logLine, want string) bool {
	for _, l := range lines {
		if l.Msg+":"+l.Reason == want {
			return true
		}
	}
	return false
}

// postPartialBody 는 원시 연결로 머리와 본문 앞 절반만 보내고 응답을 기다린다 — 본문 마감에 걸린다.
func postPartialBody(t *testing.T, f *fixture, body, auth string) {
	t.Helper()
	conn, err := net.Dial("tcp", f.srv.Listener.Addr().String())
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}
	defer conn.Close()
	head := "POST /api/ingest/end-signal HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nAuthorization: " + auth +
		"\r\nContent-Length: " + strconv.Itoa(len(body)) + "\r\n\r\n"
	if _, err := io.WriteString(conn, head+body[:len(body)/2]); err != nil {
		t.Fatalf("쓰기: %v", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatalf("응답 읽기: %v", err)
	}
	resp.Body.Close()
}

// deriveForTest 는 시험 쪽 파생 값 계산이다 — 제품 함수와 따로 계산해 서로를 검증한다.
func deriveForTest(t *testing.T, passphrase string) string {
	t.Helper()
	return hmacHexForTest(passphrase, "pokeclip-4d-v1")
}

func hmacHexForTest(key, msg string) string {
	m := hmac.New(sha256.New, []byte(key))
	m.Write([]byte(msg))
	return hex.EncodeToString(m.Sum(nil))
}
