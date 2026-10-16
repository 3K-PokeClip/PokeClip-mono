package ingestauth

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxctl"
)

// 누설 시험의 패키지 쪽이다(설계 6절 누설 규칙 · 누설 시험). 가짜 auth 가 고정 passphrase 를 내는 상태에서 판정표의 모든
// 행 · 예산 거절 · 차례 거절(칸 · 양보) · 기억 해제(전체 · 토큰) · 절단 세 갈래(기록 · 목록 · 기록 밖) · 기록 회전(시작
// 표시) · 게이지 전이(auth · 거부 목록 · 기록 — set · clear)를 돌리고, slog 출력 · 기록 파일(세대 모두) · 절단 출력(보고 ·
// 에러) 어디에도 비밀 카나리아가 없음을 본다. 실행 파일의 출력 자리(stdout · stderr · healthz)는 cmd/ingest-auth 의 누설
// 시험이 본다.
//
// 시나리오마다 카나리아가 실제로 입력에 들어갔음을 먼저 확인한다(가짜 auth 호출 · 허용 본문 · api 행의 답 · 그 행의
// 이벤트) — 한 번도 흐르지 않은 카나리아의 0 은 증거가 아니다. 공개 식별자(토큰 · 연결 id)와 MediaMTX 에 돌려주는 허용
// 200 본문은 단언 대상이 아니다. 실패 문구에는 카나리아 이름 · 출력 자리 · 횟수만 싣는다 — 출력 내용과 카나리아 값은
// 싣지 않는다.

// 판정표의 모든 행(설계 2-4)을 한 번씩 돈다 — 행마다 새 고정물이다. 요청 본문의 비밀 필드와 api 행의 자격이 그 행의
// 입력이고, resolve 를 부르는 행은 가짜 auth 가 출입증을 확인한 4C 요청에 그 행의 답(passphrase · 형식 밖 값 · 4C 이유)을
// 돌려준다. 행마다 그 행의 이벤트(양성 대조)를 본 뒤 출력 자리를 훑는다. 차례 거절의 양보 갈래는
// TestNoSecretsInOutputsAcrossTurnDenials 가 돈다.
func TestNoSecretsInOutputsAcrossJudgmentRows(t *testing.T) {
	// publishWith 는 edit 로 고친 publish 를 보내는 행이다.
	publishWith := func(edit func(b *mtxAuthBody)) func(*testing.T, *leakFixture) *httptest.ResponseRecorder {
		return func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
			b := leakPublishBody(testTokenA, testConnID(1))
			edit(&b)
			return f.send(newAuthRequest(t, b))
		}
	}
	// answering 은 가짜 auth 가 ans 로 답하는 상태에서 publish 하나를 보내는 행이다.
	answering := func(ans authAnswer) func(*testing.T, *leakFixture) *httptest.ResponseRecorder {
		return func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
			f.auth.answerAll(ans)
			return f.publishLeaky(t, testTokenA, testConnID(1))
		}
	}
	tests := []struct {
		name       string
		opts       fixtureOptions
		chmods     bool // 권한을 빼는 행 — root 는 권한 검사를 우회해 건너뛴다
		send       func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder
		wantStatus int
		wantEvent  leakEvent // 그 행의 이벤트. Msg 가 비면 아무 이벤트도 없어야 한다(read 행)
		resolves   bool      // 그 행이 4C 를 부른다 — 가짜 auth 는 출입증 머리가 맞는지 보고 답한다
	}{
		{
			name: "출발지_밖",
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				r := newAuthRequest(t, leakPublishBody(testTokenA, testConnID(1)))
				r.RemoteAddr = "192.0.2.11:40000"
				return f.send(r)
			},
			wantStatus: http.StatusForbidden,
			wantEvent:  leakEvent{Msg: "ingestauth_caller_denied"},
		},
		{
			name:       "본문_해석_실패_ip",
			send:       publishWith(func(b *mtxAuthBody) { b.IP = "not-an-ip" }),
			wantStatus: http.StatusBadRequest,
			wantEvent:  leakEvent{Msg: "ingestauth_request_invalid", Reason: "bad_ip"},
		},
		{
			name: "본문_해석_실패_JSON_아님",
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				body := "user=" + leakRequestUser + "&password=" + leakRequestPassword + "&token=" + leakRequestToken + "&query=" + leakRequestQuery
				return f.send(newAuthRequest(t, body))
			},
			wantStatus: http.StatusBadRequest,
			wantEvent:  leakEvent{Msg: "ingestauth_request_invalid", Reason: "malformed"},
		},
		{
			name:       "본문_해석_실패_크기", // 카나리아는 상한 안쪽(본문 앞)에 있고 채움이 상한을 넘긴다
			send:       publishWith(func(b *mtxAuthBody) { b.UserAgent = strings.Repeat("x", maxRequestBytes) }),
			wantStatus: http.StatusBadRequest,
			wantEvent:  leakEvent{Msg: "ingestauth_request_invalid", Reason: "too_large"},
		},
		{
			name: "본문_해석_실패_연결_id",
			send: publishWith(func(b *mtxAuthBody) {
				upper := strings.ToUpper(testConnID(0xabc)) // 16진 글자가 있어야 대문자 꼴이 소문자 꼴과 다르다
				b.ID = &upper
			}),
			wantStatus: http.StatusBadRequest,
			wantEvent:  leakEvent{Msg: "ingestauth_request_invalid", Reason: "bad_conn_id"},
		},
		{
			name: "본문_해석_실패_읽기", // 카나리아 본문을 내준 뒤 읽기가 실패한다
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				r := newAuthRequest(t, "")
				r.Body = &readFailsAfter{data: mustJSON(t, leakPublishBody(testTokenA, testConnID(1)))}
				return f.send(r)
			},
			wantStatus: http.StatusBadRequest,
			wantEvent:  leakEvent{Msg: "ingestauth_request_invalid", Reason: "read_error"},
		},
		{
			name: "api_허용",
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				return f.send(newAuthRequest(t, leakAPIBody(testSelfIP.String(), leakAPIUser, testAPIPass)))
			},
			wantStatus: http.StatusOK,
			wantEvent:  leakEvent{Msg: "ingestauth_api_allowed", Level: "DEBUG"},
		},
		{
			name: "api_거절_자격",
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				return f.send(newAuthRequest(t, leakAPIBody(testSelfIP.String(), leakRequestUser, leakRequestPassword)))
			},
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_api_denied"},
		},
		{
			name: "api_거절_출발지",
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				return f.send(newAuthRequest(t, leakAPIBody("192.0.2.30", leakAPIUser, testAPIPass)))
			},
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_api_denied"},
		},
		{
			name:       "read",
			send:       publishWith(func(b *mtxAuthBody) { b.Action = "read" }),
			wantStatus: http.StatusOK,
		},
		{
			name:       "publish_아님",
			send:       publishWith(func(b *mtxAuthBody) { b.Action = "playback" }),
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "not_publish"},
		},
		{
			name:       "srt_아님",
			send:       publishWith(func(b *mtxAuthBody) { b.Protocol = "rtmp" }),
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "not_srt"},
		},
		{
			name:       "토큰_꼴_아님",
			send:       publishWith(func(b *mtxAuthBody) { b.Path = "live/not-a-token" }),
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "not_token"},
		},
		{
			name:       "연결_id_없음",
			send:       publishWith(func(b *mtxAuthBody) { b.ID = nil }),
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "no_conn_id"},
		},
		{
			name:   "거부_목록_못_읽음_resolve_전",
			chmods: true,
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				f.writeDenyList(t, "")
				chmod(t, f.denyPath, 0o000)
				return f.publishLeaky(t, testTokenA, testConnID(1))
			},
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "denylist_unreadable"},
		},
		{
			name: "거부_목록에_있음_resolve_전",
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				f.writeDenyList(t, testTokenA+"\n")
				return f.publishLeaky(t, testTokenA, testConnID(1))
			},
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "denylisted"},
		},
		{
			name: "예산_바닥",
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				f.auth.answerAll(answerUsable(3 * time.Second))
				f.publishLeaky(t, testTokenB, testConnID(1)) // 200 — 잔액 1.375 + 1.5 − 3 = −0.125초
				return f.publishLeaky(t, testTokenA, testConnID(2))
			},
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "budget_exhausted"},
			resolves:   true,
		},
		{
			name: "요청_ctx_끝남",
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				ctx, cancel := context.WithCancel(t.Context())
				cancel()
				return f.sendInner(newAuthRequest(t, leakPublishBody(testTokenA, testConnID(1))).WithContext(ctx))
			},
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_late", Level: "DEBUG"},
		},
		{
			name: "차례_칸_닫힘",
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				f.auth.answerAll(answerUndecided(100 * time.Millisecond))
				f.h.failed.record(testTokenA, outcomeUndecided)
				f.h.failed.record(testTokenB, outcomeUndecided)
				f.publishLeaky(t, testTokenA, testConnID(1)) // 가장 오래 기다린 A 의 재확인 — 칸을 쓴다
				return f.publishLeaky(t, testTokenB, testConnID(2))
			},
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "recheck_wait", Wait: "gap"},
			resolves:   true,
		},
		{
			name:       "판정_불가",
			send:       answering(answerUndecided(120 * time.Millisecond)),
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "undecided"},
			resolves:   true,
		},
		{
			name:       "valid_false",
			send:       answering(answerRejected(leakAuthReason, 10*time.Millisecond)),
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "rejected"},
			resolves:   true,
		},
		{
			name: "passphrase_형식_밖",
			send: answering(authAnswer{
				took: 10 * time.Millisecond,
				body: fmt.Sprintf(`{"valid":true,"userId":%d,"passphrase":%q}`, testUserID, leakBadPassphrase),
			}),
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_passphrase_invalid", Level: "ERROR"},
			resolves:   true,
		},
		{
			name:   "기록_쓰기_실패",
			opts:   fixtureOptions{connMaxBytes: 1}, // Append 마다 회전 — 디렉터리 쓰기 권한이 없으면 이름 바꾸기가 실패한다
			chmods: true,
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				chmod(t, f.connDir, 0o500)
				return f.publishLeaky(t, testTokenA, testConnID(1))
			},
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "connlog_unwritable"},
			resolves:   true,
		},
		{
			name:   "마지막_확인_못_읽음",
			chmods: true,
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				f.writeDenyList(t, "")
				t.Cleanup(func() { os.Chmod(f.denyPath, 0o644) }) // 권한은 가짜 auth 의 고루틴이 뺀다 — 되돌림은 시험이 맡는다
				ans := answerUsable(10 * time.Millisecond)
				ans.before = func() {
					if err := os.Chmod(f.denyPath, 0o000); err != nil {
						t.Errorf("fake auth: removing the deny list permission returned error: %v", err)
					}
				}
				f.auth.answerAll(ans)
				return f.publishLeaky(t, testTokenA, testConnID(1))
			},
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "denylist_unreadable"},
			resolves:   true,
		},
		{
			name: "마지막_확인_거부",
			send: func(t *testing.T, f *leakFixture) *httptest.ResponseRecorder {
				ans := answerUsable(10 * time.Millisecond)
				ans.before = func() {
					if err := os.WriteFile(f.denyPath, []byte(testTokenA+"\n"), 0o644); err != nil {
						t.Errorf("fake auth: writing the deny list returned error: %v", err)
					}
				}
				f.auth.answerAll(ans)
				return f.publishLeaky(t, testTokenA, testConnID(1))
			},
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "denylisted"},
			resolves:   true,
		},
		{
			name:       "허용",
			send:       answering(answerUsable(37 * time.Millisecond)),
			wantStatus: http.StatusOK,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_allowed"},
			resolves:   true,
		},
		{
			name:       "resolve_그_밖의_에러",
			opts:       fixtureOptions{resolver: newTestResolver(t, requestFailsBaseURL)},
			send:       answering(answerUsable(0)),
			wantStatus: http.StatusUnauthorized,
			wantEvent:  leakEvent{Msg: "ingestauth_publish_denied", Reason: "internal_error"},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if tc.chmods && os.Geteuid() == 0 {
				t.Skip("root 는 권한 검사를 우회한다")
			}
			f := newLeakFixture(t, tc.opts)

			rr := tc.send(t, f)

			if rr.Code != tc.wantStatus {
				t.Errorf("auth request of the %s row = %d, want %d", tc.name, rr.Code, tc.wantStatus)
			}
			events := f.events(t)
			switch {
			case tc.wantEvent.Msg == "" && len(events) != 0:
				t.Errorf("the %s row logged %d events, want none", tc.name, len(events))
			case tc.wantEvent.Msg != "" && !hasEvent(events, tc.wantEvent):
				t.Errorf("the %s row did not log %+v — it did not take that row", tc.name, tc.wantEvent)
			}
			if tc.resolves && f.auth.callCount() == 0 {
				t.Errorf("the %s row made no resolve call — the resolve token and auth's answer never flowed", tc.name)
			}
			if tc.resolves && tc.wantStatus == http.StatusOK && !strings.Contains(rr.Body.String(), testPassphrase) {
				t.Errorf("the allowed response does not carry the passphrase the fake auth returned — the canary never flowed")
			}
			for _, p := range leakProblems(f.outputs(t), f.responses()) {
				t.Error(p)
			}
		})
	}
}

// 차례 거절(칸 · 양보)과 기억 해제(토큰 · 전체)의 출력에도 비밀이 없다. 판정 불가로 X · Y · Z 를 차례로 기억시킨 뒤, X 의
// 재확인이 valid:false(이유는 카나리아)로 끝나 X 만 지우고(scope=token), Y 는 칸이 닫혀 gap, 5초 뒤 Z 는 더 오래 기다린
// Y 에게 양보하고(yield), Y 의 재확인이 쓸 수 있는 값으로 끝나 모두 지운다(scope=all — 200 이라 passphrase 가 흐른다).
func TestNoSecretsInOutputsAcrossTurnDenials(t *testing.T) {
	f := newLeakFixture(t, fixtureOptions{})
	tokX, tokY, tokZ := waveToken(1), waveToken(2), waveToken(3)
	f.auth.answerAll(answerUndecided(100 * time.Millisecond))
	for i, token := range []string{tokX, tokY, tokZ} {
		f.publishLeaky(t, token, testConnID(i))
	}
	f.auth.answerAll(answerRejected(leakAuthReason, 10*time.Millisecond))
	f.publishLeaky(t, tokX, testConnID(3))
	f.publishLeaky(t, tokY, testConnID(4))
	f.clock.advance(5 * time.Second)
	f.publishLeaky(t, tokZ, testConnID(5))
	f.auth.answerAll(answerUsable(10 * time.Millisecond))

	rr := f.publishLeaky(t, tokY, testConnID(6))

	if rr.Code != http.StatusOK || !strings.Contains(rr.Body.String(), testPassphrase) {
		t.Errorf("Y's recheck with a usable value = %d (carries the passphrase: %t), want 200 carrying it", rr.Code, strings.Contains(rr.Body.String(), testPassphrase))
	}
	if n := f.auth.callCount(); n != 5 {
		t.Errorf("resolve calls = %d, want 5 (X · Y · Z undecided, X rejected, Y usable)", n)
	}
	events := f.events(t)
	for _, want := range []leakEvent{
		{Msg: "ingestauth_failed_cleared", Scope: "token"},
		{Msg: "ingestauth_publish_denied", Reason: "recheck_wait", Wait: "gap"},
		{Msg: "ingestauth_publish_denied", Reason: "recheck_wait", Wait: "yield"},
		{Msg: "ingestauth_failed_cleared", Scope: "all"},
		{Msg: "ingestauth_publish_allowed"},
	} {
		if !hasEvent(events, want) {
			t.Errorf("slog lacks %+v — the scenario did not take that path", want)
		}
	}
	for _, p := range leakProblems(f.outputs(t), f.responses()) {
		t.Error(p)
	}
}

// 게이지 전이(설계 6절 게이지 표 — set · clear)의 출력에도 비밀이 없다. 거부 목록 게이지는 운영자가 토큰 자리에 passphrase 를
// 잘못 붙인 틀린 줄로도 선다 — 게이지 로그와 Check 에러(healthz 까닭의 재료)에는 줄 수만 실린다.
func TestNoSecretsInOutputsAcrossGaugeTransitions(t *testing.T) {
	t.Run("auth", func(t *testing.T) {
		f := newLeakFixture(t, fixtureOptions{})
		f.auth.answerAll(answerUndecided(100 * time.Millisecond))
		f.publishLeaky(t, testTokenA, testConnID(1)) // 첫 판정 불가 — set
		f.auth.answerAll(answerUsable(10 * time.Millisecond))

		rr := f.publishLeaky(t, testTokenB, testConnID(2)) // 첫 판정 결과 — clear

		if rr.Code != http.StatusOK {
			t.Errorf("publish after auth recovered = %d, want 200", rr.Code)
		}
		events := f.events(t)
		for _, want := range []leakEvent{{Msg: "ingestauth_auth_degraded"}, {Msg: "ingestauth_auth_recovered"}} {
			if !hasEvent(events, want) {
				t.Errorf("slog lacks %+v — the gauge did not move", want)
			}
		}
		for _, p := range leakProblems(f.outputs(t), f.responses()) {
			t.Error(p)
		}
	})
	t.Run("거부_목록_틀린_줄", func(t *testing.T) {
		f := newLeakFixture(t, fixtureOptions{})
		f.writeDenyList(t, testTokenB+"\n"+testPassphrase+"\n")
		f.publishLeaky(t, testTokenA, testConnID(1)) // 다시 읽음 — 틀린 줄 하나라 set
		checkErr := f.h.deny.Check()
		f.writeDenyList(t, testTokenB+"\n")

		rr := f.publishLeaky(t, testTokenA, testConnID(2)) // 온전히 읽음 — clear

		if checkErr == nil || rr.Code != http.StatusOK {
			t.Errorf("Check with a pasted passphrase is nil = %t and the next publish = %d, want an error and 200", checkErr == nil, rr.Code)
		}
		events := f.events(t)
		for _, want := range []leakEvent{{Msg: "ingestauth_denylist_error", Reason: "invalid_lines"}, {Msg: "ingestauth_denylist_recovered"}} {
			if !hasEvent(events, want) {
				t.Errorf("slog lacks %+v — the gauge did not move", want)
			}
		}
		outputs := append(f.outputs(t), leakOutput{place: "거부 목록 Check 에러", content: fmt.Sprint(checkErr)})
		for _, p := range leakProblems(outputs, f.responses()) {
			t.Error(p)
		}
	})
	t.Run("거부_목록_못_읽음", func(t *testing.T) {
		if os.Geteuid() == 0 {
			t.Skip("root 는 권한 검사를 우회한다")
		}
		f := newLeakFixture(t, fixtureOptions{})
		f.writeDenyList(t, testTokenB+"\n")
		chmod(t, f.denyPath, 0o000)
		f.publishLeaky(t, testTokenA, testConnID(1)) // 못 읽음 — set
		checkErr := f.h.deny.Check()
		chmod(t, f.denyPath, 0o644)

		rr := f.publishLeaky(t, testTokenA, testConnID(2)) // 온전히 읽음 — clear

		if checkErr == nil || rr.Code != http.StatusOK {
			t.Errorf("Check without permission is nil = %t and the next publish = %d, want an error and 200", checkErr == nil, rr.Code)
		}
		events := f.events(t)
		for _, want := range []leakEvent{{Msg: "ingestauth_denylist_error", Reason: "unreadable"}, {Msg: "ingestauth_denylist_recovered"}} {
			if !hasEvent(events, want) {
				t.Errorf("slog lacks %+v — the gauge did not move", want)
			}
		}
		outputs := append(f.outputs(t), leakOutput{place: "거부 목록 Check 에러", content: fmt.Sprint(checkErr)})
		for _, p := range leakProblems(outputs, f.responses()) {
			t.Error(p)
		}
	})
	t.Run("기록", func(t *testing.T) {
		if os.Geteuid() == 0 {
			t.Skip("root 는 권한 검사를 우회한다")
		}
		f := newLeakFixture(t, fixtureOptions{connMaxBytes: 1}) // Append 마다 회전
		chmod(t, f.connDir, 0o500)
		f.publishLeaky(t, testTokenA, testConnID(1)) // 회전의 이름 바꾸기가 실패 — set
		chmod(t, f.connDir, 0o755)

		rr := f.publishLeaky(t, testTokenA, testConnID(2)) // 첫 쓰기 성공 — clear

		if rr.Code != http.StatusOK {
			t.Errorf("publish after restoring the log directory = %d, want 200", rr.Code)
		}
		events := f.events(t)
		for _, want := range []leakEvent{{Msg: "ingestauth_connlog_error"}, {Msg: "ingestauth_connlog_recovered"}} {
			if !hasEvent(events, want) {
				t.Errorf("slog lacks %+v — the gauge did not move", want)
			}
		}
		for _, p := range leakProblems(f.outputs(t), f.responses()) {
			t.Error(p)
		}
	})
}

// 절단 세 갈래(기록 · 목록 · 기록 밖)와 기록 회전(시작 표시)의 출력에도 비밀이 없다. 처리기가 허용한 연결이 기록에 쓰이고
// (maxBytes 1 — 허용마다 회전해 앞 줄이 밀려난다), 절단이 그 기록과 가짜 MediaMTX 의 목록으로 끊는다. 가짜 MediaMTX 는
// Control API 요청의 Basic 자격을 응답기의 api 행에 묻는다(설계 3-4 2) — API 비번이 api 행을 지난다. 틀린 자격의 절단과
// Control API 가 목록 · kick · 조회에 500 으로 답하는 절단은 에러로 끝난다 — 그 에러 문구와 보고도 절단 출력이다. 세
// 요청의 실패는 Control API 클라이언트의 서로 다른 반환 자리라 하나씩 지난다 — 어느 자리가 요청 머리(Basic 자격)를 에러에
// 실어도 드러나게.
func TestNoSecretsInOutputsAcrossCut(t *testing.T) {
	f := newLeakFixture(t, fixtureOptions{connMaxBytes: 1})
	m := newFakeMediaMTX(t)
	m.set(func(m *fakeMediaMTX) { m.authorize = f.authorizeAPI })
	listed, other, logged, unmapped := testConnID(1), testConnID(2), testConnID(3), testConnID(4)
	for _, p := range []struct{ token, connID string }{{testTokenA, listed}, {testTokenB, other}, {testTokenA, logged}} {
		if rr := f.publishLeaky(t, p.token, p.connID); rr.Code != http.StatusOK {
			t.Fatalf("Setup: publish of %s = %d, want 200", p.connID, rr.Code)
		}
	}
	allowed, err := LoadAllowed(f.connDir)
	if err != nil {
		t.Fatalf("Setup: LoadAllowed returned error: %v", err)
	}
	// 기록에는 other · logged 의 줄만 남았다 — listed 의 줄은 두 번의 회전으로 밀려났다.
	m.add(fakeConn{id: listed, created: allowed.Since.Add(-time.Minute), state: statePublish, path: testTokenA})
	m.add(fakeConn{id: other, created: allowed.Since, state: statePublish, path: testTokenB})
	m.add(fakeConn{id: logged, created: allowed.Since, state: statePublish, path: testTokenA})
	m.add(fakeConn{id: unmapped, created: allowed.Since.Add(-time.Minute)}) // 판독 중 · 기록 밖 · Since 앞
	f.writeDenyList(t, testTokenA+"\n")
	listedAt := allowed.Since.Add(time.Minute) // 기록 밖 연결은 목록을 받은 때 생긴 지 2분
	c := cutter{now: func() time.Time { return listedAt }, verifyWithin: cutVerifyWithinInTests, verifyEvery: 5 * time.Millisecond}
	cut := func(client *mtxctl.Client) (CutReport, error) {
		return c.cut(t.Context(), testTokenA, NewDenyList(f.denyPath, f.h.log), f.connDir, client)
	}
	wrong, err := mtxctl.NewClient(mtxctl.Options{APIURL: m.srv.URL, User: leakRequestUser, Pass: mtxctl.NewSecret(leakRequestPassword)})
	if err != nil {
		t.Fatalf("Setup: mtxctl.NewClient with wrong credentials returned %T, want a client", err)
	}

	rep, cutErr := cut(newLeakMTXClient(t, m.srv.URL))
	repWrong, errWrong := cut(wrong)
	m.set(func(m *fakeMediaMTX) { m.fail["list"] = http.StatusInternalServerError })
	rep500, err500 := cut(newLeakMTXClient(t, m.srv.URL))
	// kick 실패 — 기록 갈래의 logged 를 kick 한다(연결은 첫 절단에 끊겼어도 kick 은 나간다).
	m.set(func(m *fakeMediaMTX) { delete(m.fail, "list"); m.fail["kick"] = http.StatusInternalServerError })
	repKick, errKick := cut(newLeakMTXClient(t, m.srv.URL))
	// 조회 실패 — 끊긴 listed 를 다시 둬 kick 이 200 으로 끝나고 확인 조회가 나가게 한다.
	m.add(fakeConn{id: listed, created: allowed.Since.Add(-time.Minute), state: statePublish, path: testTokenA})
	m.set(func(m *fakeMediaMTX) { delete(m.fail, "kick"); m.fail["get"] = http.StatusInternalServerError })
	repGet, errGet := cut(newLeakMTXClient(t, m.srv.URL))

	// 보고의 id 는 찍지 않는다 — 기록 줄에 비밀이 실리면 id 자리로 따라 나온다. 셈만 싣는다.
	if cutErr != nil || rep.FromLog != 1 || rep.FromList != 1 || rep.FromUnmapped != 1 || rep.Kicked != 3 {
		t.Errorf("Cut = from_log %d · from_list %d · from_unmapped %d · kicked %d · remaining %d (error: %t), want 1 · 1 · 1 · 3 · 0 and no error",
			rep.FromLog, rep.FromList, rep.FromUnmapped, rep.Kicked, len(rep.Remaining), cutErr != nil)
	}
	for _, tc := range []struct {
		name   string
		err    error
		op     string
		status int
	}{
		{name: "wrong credentials", err: errWrong, op: "list", status: http.StatusUnauthorized},
		{name: "Control API 500", err: err500, op: "list", status: http.StatusInternalServerError},
		{name: "kick 500", err: errKick, op: "kick", status: http.StatusInternalServerError},
		{name: "lookup 500", err: errGet, op: "get", status: http.StatusInternalServerError},
	} {
		if apiErr := (*mtxctl.Error)(nil); !errors.As(tc.err, &apiErr) || apiErr.Op != tc.op || apiErr.Status != tc.status {
			t.Errorf("Cut with %s did not end with Control API %s status %d", tc.name, tc.op, tc.status)
		}
	}
	events := f.events(t)
	for _, want := range []leakEvent{
		{Msg: "ingestauth_connlog_rotated"},
		{Msg: "ingestauth_api_allowed"},
		{Msg: "ingestauth_api_denied"},
	} {
		if !hasEvent(events, want) {
			t.Errorf("slog lacks %+v — the scenario did not take that path", want)
		}
	}
	outputs := f.outputs(t)
	generations := 0
	for _, o := range outputs {
		if strings.HasPrefix(o.place, "기록 파일 ") {
			generations++
			if first, _, _ := strings.Cut(o.content, "\n"); !isStartMarkLine(first) {
				t.Errorf("%s does not begin with a start mark", o.place)
			}
		}
	}
	if generations != 2 {
		t.Errorf("connection log files = %d, want 2 generations", generations)
	}
	outputs = append(outputs,
		leakOutput{place: "Cut 보고", content: fmt.Sprintf("%+v\n%+v\n%+v\n%+v\n%+v", rep, repWrong, rep500, repKick, repGet)},
		leakOutput{place: "Cut 에러", content: fmt.Sprint(cutErr, errWrong, err500, errKick, errGet)},
	)
	for _, p := range leakProblems(outputs, f.responses()) {
		t.Error(p)
	}
}

// 누설 규칙 1: 4C 흐름이 돌려준 Passphrase 를 제품이 쓰는 출력 꼴(fmt 의 동사 · 에러 감싸기 · slog JSON · encoding/json)로
// 찍어도 원문 대신 [REDACTED] 다. 위 시나리오의 출력 자리에는 Passphrase 를 찍는 곳이 없어 가림이 깨져도 드러나지 않는다 —
// 그래서 흐름이 받은 값 그대로를 찍어 본다.
func TestNoSecretsInPrintedResolvedPassphrase(t *testing.T) {
	f := newLeakFixture(t, fixtureOptions{})
	v, err := f.h.resolver.Resolve(t.Context(), testTokenA)
	if err != nil {
		t.Fatalf("Setup: Resolve against the fake auth returned %T, want a verdict", err)
	}
	if v.Passphrase.Reveal() != testPassphrase {
		t.Fatalf("Setup: the verdict does not carry the passphrase the fake auth returned — the canary never flowed")
	}
	verdictJSON, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("Setup: json.Marshal(Verdict) returned error: %v", err)
	}

	f.h.log.Info("leak_probe", "passphrase", v.Passphrase, "verdict", v)

	printed := []leakOutput{
		{place: "fmt %v", content: fmt.Sprintf("%v", v.Passphrase)},
		{place: "fmt %+v", content: fmt.Sprintf("%+v", v.Passphrase)},
		{place: "fmt %s", content: fmt.Sprintf("%s", v.Passphrase)},
		{place: "fmt %q", content: fmt.Sprintf("%q", v.Passphrase)},
		{place: "fmt %#v", content: fmt.Sprintf("%#v", v.Passphrase)},
		{place: "Verdict %v", content: fmt.Sprintf("%v", v)},
		{place: "Verdict %+v", content: fmt.Sprintf("%+v", v)},
		{place: "에러 감싸기", content: fmt.Errorf("probe: %v", v.Passphrase).Error()},
		{place: "slog", content: f.logs.String()},
	}
	for _, o := range printed {
		if !strings.Contains(o.content, "[REDACTED]") {
			t.Errorf("%s does not show [REDACTED] — it did not print the passphrase", o.place)
		}
	}
	outputs := append(printed, leakOutput{place: "Verdict JSON", content: string(verdictJSON)})
	for _, p := range leakProblems(outputs, nil) {
		t.Error(p)
	}
}
