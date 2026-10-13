package ingestauth

import (
	"context"
	"encoding/json"
	"errors"
	"maps"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// requestFailsBaseURL 은 keyresolve.NewClient 를 지나고 Resolve 의 요청 만들기에서 실패하는 BaseURL 이다(영역 id 의
// '[' — 다시 해석하면 invalid IP-literal). Resolve 가 「그 밖의 에러」를 돌려준다(그 문구는 고정 문구라 끝점이 없다).
const requestFailsBaseURL = "http://[fe80::1%25%20%5b]:8082"

// lastDenied 는 마지막 publish_denied 다. 없으면 영값과 거짓이다.
func lastDenied(t *testing.T, logs *logRecorder) (handlerEvent, bool) {
	t.Helper()
	es := handlerEvents(t, logs, "ingestauth_publish_denied")
	if len(es) == 0 {
		return handlerEvent{}, false
	}
	return es[len(es)-1], true
}

// allowedConns 는 허용 연결 기록의 연결 id → 토큰이다.
func allowedConns(t *testing.T, dir string) map[string]string {
	t.Helper()
	a, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	return a.Tokens
}

// 형식 행(판정표 — publish 가 아님 · protocol≠srt · 토큰 꼴 아님 · id 없음)은 401 · 본문 없음 · publish_denied 이고
// resolve 를 부르지 않는다. path 는 토큰 꼴일 때만 싣는다 — 토큰 꼴이 아닌 값은 잘못 붙인 비밀일 수 있다. 형식 행은
// 거부 목록 · 예산 · 차례보다 앞이라 기억(도착 · 칸)을 바꾸지 않고, 토큰 꼴이 아닌 값은 기억의 키가 되지 않는다(설계
// 2-4 판정표 순서).
func TestHandlerPublishFormRows(t *testing.T) {
	junk := "live/" + strings.Repeat("x", 200)
	tests := []struct {
		name       string
		edit       func(*mtxAuthBody)
		wantReason string
		wantPath   string // 비면 path 키가 없어야 한다
		wantConnID bool
	}{
		{name: "playback", edit: func(b *mtxAuthBody) { b.Action = "playback" }, wantReason: "not_publish", wantPath: testTokenA, wantConnID: true},
		{name: "action_빈_값_토큰_아닌_경로", edit: func(b *mtxAuthBody) { b.Action, b.Path = "", junk }, wantReason: "not_publish", wantConnID: true},
		{name: "rtmp", edit: func(b *mtxAuthBody) { b.Protocol = "rtmp" }, wantReason: "not_srt", wantPath: testTokenA, wantConnID: true},
		{name: "protocol_빈_값", edit: func(b *mtxAuthBody) { b.Protocol = "" }, wantReason: "not_srt", wantPath: testTokenA, wantConnID: true},
		{name: "토큰_꼴_아닌_경로", edit: func(b *mtxAuthBody) { b.Path = junk }, wantReason: "not_token", wantConnID: true},
		{name: "소문자_토큰", edit: func(b *mtxAuthBody) { b.Path = strings.ToLower(testTokenA) }, wantReason: "not_token", wantConnID: true},
		{name: "id_null", edit: func(b *mtxAuthBody) { b.ID = nil }, wantReason: "no_conn_id", wantPath: testTokenA},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHandlerFixture(t, fixtureOptions{})
			f.h.failed.record(testTokenA, outcomeUndecided) // 기억된 토큰 — 형식 행이 도착 · 칸을 건드리지 않는지 본다
			before, _ := entryOf(f.h.failed, testTokenA)
			body := publishBody(testTokenA, testConnID(1))
			tc.edit(&body)

			rr := f.serve(newAuthRequest(t, body))

			if rr.Code != http.StatusUnauthorized || rr.Body.Len() != 0 {
				t.Errorf("publish %s = %d with a %d-byte body, want 401 without a body", tc.name, rr.Code, rr.Body.Len())
			}
			e, ok := lastDenied(t, f.logs)
			if !ok || e.Level != "WARN" || e.Reason != tc.wantReason {
				t.Fatalf("publish %s: publish_denied = %+v (found %t), want WARN with reason %q", tc.name, e, ok, tc.wantReason)
			}
			if e.has("path") != (tc.wantPath != "") || e.Path != tc.wantPath {
				t.Errorf("publish %s: publish_denied path = %s (present %t), want %q", tc.name, shownIf(e.Path, streamkey.IsToken), e.has("path"), tc.wantPath)
			}
			if tc.wantConnID != (e.ConnID == testConnID(1)) || tc.wantConnID != e.has("conn_id") {
				t.Errorf("publish %s: publish_denied conn_id = %s (present %t), want present %t", tc.name, shownIf(e.ConnID, connIDPattern.MatchString), e.has("conn_id"), tc.wantConnID)
			}
			if e.ResolveStatus != "" || e.ResolveMS != 0 || e.has("wait") {
				t.Errorf("publish %s: resolve_status %s · resolve_ms %d · wait present %t, want empty · 0 · absent", tc.name, shownIf(e.ResolveStatus, closedWord.MatchString), e.ResolveMS, e.has("wait"))
			}
			if n := f.auth.callCount(); n != 0 {
				t.Errorf("publish %s: fake auth calls = %d, want 0", tc.name, n)
			}
			if after, _ := entryOf(f.h.failed, testTokenA); after != before || !lastRecheckOf(f.h.failed).IsZero() || memorySize(f.h.failed) != 1 {
				t.Errorf("publish %s changed the failed-token memory: entry %+v → %+v, last recheck %v, size %d", tc.name, before, after, lastRecheckOf(f.h.failed), memorySize(f.h.failed))
			}
		})
	}
}

// 거부 목록에 있는 토큰은 resolve 전에 401(denylisted)이다 — resolve 호출 0 · 기억 불변(판정표 · 설계 2-4 판정 순서
// 「거부 목록 → 예산 → 요청 ctx → 차례」). 다른 토큰은 지난다.
func TestHandlerPublishDenylistedBeforeResolve(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	f.writeDenyList(t, testTokenA+"\n")
	f.h.failed.record(testTokenA, outcomeUndecided)
	before, _ := entryOf(f.h.failed, testTokenA)

	rr := f.publish(t, testTokenA, testConnID(1))

	if rr.Code != http.StatusUnauthorized || rr.Body.Len() != 0 {
		t.Errorf("publish of a denylisted token = %d with a %d-byte body, want 401 without a body", rr.Code, rr.Body.Len())
	}
	if e, ok := lastDenied(t, f.logs); !ok || e.Reason != "denylisted" || e.Path != testTokenA || e.ResolveMS != 0 {
		t.Errorf("publish_denied for a denylisted token = %+v (found %t), want reason denylisted with path and resolve_ms 0", e, ok)
	}
	if n := f.auth.callCount(); n != 0 {
		t.Errorf("fake auth calls for a denylisted token = %d, want 0", n)
	}
	if after, _ := entryOf(f.h.failed, testTokenA); after != before {
		t.Errorf("failed-token entry of a denylisted token changed: %+v → %+v", before, after)
	}
	if rr := f.publish(t, testTokenB, testConnID(2)); rr.Code != http.StatusOK {
		t.Errorf("publish of a token not on the deny list = %d, want 200", rr.Code)
	}
}

// C2 읽기 실패 단언(설계 착수 체크리스트 207행): 거부 목록 파일 권한을 빼면 publish 는 401(denylist_unreadable)이고
// resolve 를 부르지 않는다. 권한을 되돌리면 다시 허용한다(읽기 실패를 직전 집합으로 보면 허용된다).
func TestHandlerPublishDenylistUnreadable(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	f := newHandlerFixture(t, fixtureOptions{})
	f.writeDenyList(t, "# 빈 목록\n")
	chmod(t, f.denyPath, 0o000)

	rr := f.publish(t, testTokenA, testConnID(1))

	if rr.Code != http.StatusUnauthorized || rr.Body.Len() != 0 {
		t.Errorf("publish while the deny list is unreadable = %d with a %d-byte body, want 401 without a body", rr.Code, rr.Body.Len())
	}
	if e, ok := lastDenied(t, f.logs); !ok || e.Reason != "denylist_unreadable" || e.ResolveMS != 0 {
		t.Errorf("publish_denied while the deny list is unreadable = %+v (found %t), want reason denylist_unreadable", e, ok)
	}
	if n := f.auth.callCount(); n != 0 {
		t.Errorf("fake auth calls while the deny list is unreadable = %d, want 0", n)
	}

	chmod(t, f.denyPath, 0o644)
	if rr := f.publish(t, testTokenA, testConnID(2)); rr.Code != http.StatusOK {
		t.Errorf("publish after restoring the deny list permission = %d, want 200", rr.Code)
	}
}

// 쓸 수 있는 값이면 200 이다 — 본문은 {"srtPassphrase":"<원문>"} 하나(설계 2-1 A2) · Cache-Control: no-store. 기록에 그
// 연결의 줄이 있고 publish_allowed(path · conn_id · user_id · resolve_ms)가 난다. passphrase 원문은 시험 실패 문구에도
// 찍지 않는다.
func TestHandlerPublishAllowed(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	f.auth.answerAll(answerUsable(37 * time.Millisecond))

	rr := f.publish(t, testTokenA, testConnID(1))

	if rr.Code != http.StatusOK {
		t.Fatalf("publish of a usable key = %d, want 200", rr.Code)
	}
	if got := rr.Header().Get("Cache-Control"); got != "no-store" {
		t.Errorf("Cache-Control of the 200 = %q, want no-store", got)
	}
	if got := rr.Header().Get("Content-Type"); got != "application/json" {
		t.Errorf("Content-Type of the 200 = %q, want application/json", got)
	}
	var body map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &body); err != nil {
		t.Fatalf("200 body is not a JSON object (%d bytes)", rr.Body.Len())
	}
	if keys := slices.Sorted(maps.Keys(body)); !slices.Equal(keys, []string{"srtPassphrase"}) {
		t.Errorf("200 body keys = %v, want only srtPassphrase", keys)
	}
	if got, _ := body["srtPassphrase"].(string); got != testPassphrase {
		t.Errorf("200 body srtPassphrase is not the passphrase auth returned (lengths %d, %d)", len(got), len(testPassphrase))
	}
	allowed := handlerEvents(t, f.logs, "ingestauth_publish_allowed")
	want := handlerEvent{Level: "INFO", Msg: "ingestauth_publish_allowed", Path: testTokenA, ConnID: testConnID(1), UserID: testUserID, ResolveMS: 37}
	if len(allowed) != 1 || !sameAllowed(allowed[0], want) {
		t.Errorf("publish_allowed events = %+v, want one %+v", allowed, want)
	}
	if denied := handlerEvents(t, f.logs, "ingestauth_publish_denied"); len(denied) != 0 {
		t.Errorf("publish_denied events for an allowed publish = %+v, want none", denied)
	}
	if got := allowedConns(t, f.connDir); got[testConnID(1)] != testTokenA {
		t.Errorf("allowed connections = %v, want %s → %s", got, testConnID(1), testTokenA)
	}
}

// sameAllowed 는 publish_allowed 의 필드가 같은지 본다(Keys 는 보지 않는다).
func sameAllowed(got, want handlerEvent) bool {
	return got.Level == want.Level && got.Path == want.Path && got.ConnID == want.ConnID && got.UserID == want.UserID && got.ResolveMS == want.ResolveMS
}

// resolve 행(판정표): 판정 불가는 401 · reason=undecided · resolve_status 이고 그 토큰을 기억하며 auth 게이지를 세운다.
// valid:true 인데 userId 가 없으면 keyresolve 가 판정 불가(parse)로 돌려준다. valid:false 는 401 이고
// reason 은 닫힌 집합이다 — 모르는 이유 · 빈 이유 · 긴 이유는 rejected 로 싣고 원문을 싣지 않는다. passphrase
// 형식 밖은 publish_denied 대신 passphrase_invalid(ERROR — path)다. 어느 행도 기록에 줄을 쓰지 않는다.
func TestHandlerPublishResolveRows(t *testing.T) {
	longReason := strings.Repeat("Z", 3000)
	tests := []struct {
		name           string
		answer         authAnswer
		wantReason     string // publish_denied 의 reason. 비면 publish_denied 가 없어야 한다
		wantStatus     string
		wantRemembered bool
		wantDegraded   bool
		wantInvalid    bool // passphrase_invalid
	}{
		{name: "판정_불가_500", answer: answerUndecided(120 * time.Millisecond), wantReason: "undecided", wantStatus: "500", wantRemembered: true, wantDegraded: true},
		{name: "판정_불가_503", answer: authAnswer{took: 5 * time.Millisecond, status: http.StatusServiceUnavailable}, wantReason: "undecided", wantStatus: "503", wantRemembered: true, wantDegraded: true},
		{name: "userId_없는_허락", answer: authAnswer{took: 40 * time.Millisecond, body: `{"valid":true,"passphrase":"` + testPassphrase + `"}`}, wantReason: "undecided", wantStatus: "parse", wantRemembered: true, wantDegraded: true},
		{name: "본문_해석_불가", answer: authAnswer{took: 40 * time.Millisecond, body: "not json"}, wantReason: "undecided", wantStatus: "parse", wantRemembered: true, wantDegraded: true},
		{name: "MALFORMED", answer: answerRejected("MALFORMED", 10*time.Millisecond), wantReason: "malformed", wantStatus: "200"},
		{name: "NOT_FOUND", answer: answerRejected("NOT_FOUND", 10*time.Millisecond), wantReason: "not_found", wantStatus: "200"},
		{name: "REVOKED", answer: answerRejected("REVOKED", 10*time.Millisecond), wantReason: "revoked", wantStatus: "200"},
		{name: "모르는_이유", answer: answerRejected("SOMETHING_NEW", 10*time.Millisecond), wantReason: "rejected", wantStatus: "200"},
		{name: "이유_없음", answer: authAnswer{took: 10 * time.Millisecond, body: `{"valid":false}`}, wantReason: "rejected", wantStatus: "200"},
		{name: "긴_이유", answer: answerRejected(longReason, 10*time.Millisecond), wantReason: "rejected", wantStatus: "200"},
		{name: "형식_밖_passphrase", answer: answerOutOfFormat(10 * time.Millisecond), wantInvalid: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHandlerFixture(t, fixtureOptions{})
			f.auth.answerAll(tc.answer)

			rr := f.publish(t, testTokenA, testConnID(1))

			if rr.Code != http.StatusUnauthorized || rr.Body.Len() != 0 {
				t.Errorf("publish answered %s = %d with a %d-byte body, want 401 without a body", tc.name, rr.Code, rr.Body.Len())
			}
			denied := handlerEvents(t, f.logs, "ingestauth_publish_denied")
			switch {
			case tc.wantReason == "" && len(denied) != 0:
				t.Errorf("publish answered %s: publish_denied = %+v, want none", tc.name, denied)
			case tc.wantReason != "" && (len(denied) != 1 || denied[0].Reason != tc.wantReason || denied[0].ResolveStatus != tc.wantStatus || denied[0].ResolveMS != tc.answer.took.Milliseconds()):
				t.Errorf("publish answered %s: publish_denied = %+v, want one with reason %q, resolve_status %q, resolve_ms %d",
					tc.name, denied, tc.wantReason, tc.wantStatus, tc.answer.took.Milliseconds())
			}
			invalid := handlerEvents(t, f.logs, "ingestauth_passphrase_invalid")
			wantInvalid := 0
			if tc.wantInvalid {
				wantInvalid = 1
			}
			if len(invalid) != wantInvalid || (tc.wantInvalid && (invalid[0].Level != "ERROR" || invalid[0].Path != testTokenA || !slices.Equal(invalid[0].Keys, []string{"level", "msg", "path", "time"}))) {
				t.Errorf("publish answered %s: passphrase_invalid = %+v, want %d ERROR with only path %q", tc.name, invalid, wantInvalid, testTokenA)
			}
			if _, remembered := entryOf(f.h.failed, testTokenA); remembered != tc.wantRemembered {
				t.Errorf("publish answered %s: token remembered = %t, want %t", tc.name, remembered, tc.wantRemembered)
			}
			if degraded := len(handlerEvents(t, f.logs, "ingestauth_auth_degraded")); degraded != boolToInt(tc.wantDegraded) {
				t.Errorf("publish answered %s: auth_degraded events = %d, want %d", tc.name, degraded, boolToInt(tc.wantDegraded))
			}
			if got := allowedConns(t, f.connDir); len(got) != 0 {
				t.Errorf("publish answered %s: allowed connections = %v, want none", tc.name, got)
			}
			if strings.Contains(f.logs.String(), longReason) {
				t.Errorf("publish answered %s: logs carry the raw auth reason", tc.name)
			}
		})
	}
}

// 게이지가 선 상태(다른 토큰의 판정 불가)에서 온 resolve 행: 판정 결과(valid:false 의 모든 이유 · passphrase 형식 밖)는
// clear — auth_recovered 하나이고 그 셈에는 게이지를 세운 거절만 든다(clear 를 낸 요청은 게이지가 내려간 뒤의
// 거절이다). 판정 불가는 게이지를 그대로 두고, 그 밖의 에러(처리기 · 설정 결함 — auth 의 판정이 아니다)도 그대로다(설계
// 2-4 판정표 · 6절 게이지 표 「판정 결과에는 valid:false · 형식 밖도 들어간다」).
func TestHandlerPublishResolveRowsWhileDegraded(t *testing.T) {
	tests := []struct {
		name          string
		opts          fixtureOptions
		answer        authAnswer
		wantRecovered bool
	}{
		{name: "판정_불가", answer: answerUndecided(10 * time.Millisecond)},
		{name: "MALFORMED", answer: answerRejected("MALFORMED", 10*time.Millisecond), wantRecovered: true},
		{name: "NOT_FOUND", answer: answerRejected("NOT_FOUND", 10*time.Millisecond), wantRecovered: true},
		{name: "REVOKED", answer: answerRejected("REVOKED", 10*time.Millisecond), wantRecovered: true},
		{name: "모르는_이유", answer: answerRejected("SOMETHING_NEW", 10*time.Millisecond), wantRecovered: true},
		{name: "이유_없음", answer: authAnswer{took: 10 * time.Millisecond, body: `{"valid":false}`}, wantRecovered: true},
		{name: "형식_밖_passphrase", answer: answerOutOfFormat(10 * time.Millisecond), wantRecovered: true},
		{name: "그_밖의_에러", opts: fixtureOptions{resolver: newTestResolver(t, requestFailsBaseURL)}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newHandlerFixture(t, tc.opts)
			f.h.authGauge.denied(reasonUndecided, &undecidedCause{failure: "status", status: http.StatusInternalServerError}, func() {})
			f.auth.answerAll(tc.answer)

			rr := f.publish(t, testTokenA, testConnID(1))

			if rr.Code != http.StatusUnauthorized {
				t.Errorf("publish answered %s while degraded = %d, want 401", tc.name, rr.Code)
			}
			recovered := handlerEvents(t, f.logs, "ingestauth_auth_recovered")
			switch {
			case !tc.wantRecovered && len(recovered) != 0:
				t.Errorf("publish answered %s while degraded: auth_recovered events = %d, want 0 (게이지 그대로)", tc.name, len(recovered))
			case tc.wantRecovered && (len(recovered) != 1 || recovered[0].Level != "ERROR" || recovered[0].DeniedDuring != 1):
				t.Errorf("publish answered %s while degraded: auth_recovered = %v, want one ERROR with denied_during 1", tc.name, recovered)
			}
		})
	}
}

// boolToInt 는 b 가 참이면 1 이다.
func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// resolve ctx 에는 시한(Deadline)이 있다. 요청 ctx 가 끝날 때까지 붙잡는 가짜 auth 에서도 처리기가 시한에 스스로
// resolve 를 끝내 판정 불가(timeout)로 거절한다 — 그 시한은 Deadline 보다 이르지 않다. 처리기의 401 이 가드의 503 보다
// 먼저인지는 실제 시간의 경주라 여기서 가르지 않는다 — resolve 시한이 가드보다 guardMargin 앞이라는 것은
// TestHandlerPublishResolveEndsBeforeGuard 가 결정적으로 보고, Deadline 0 의 기본값(MaxDeadline)은
// TestNewHandlerAppliesDefaults 가 본다.
func TestHandlerPublishResolveHasDeadline(t *testing.T) {
	const deadline = 30 * time.Millisecond
	f := newHandlerFixture(t, fixtureOptions{deadline: deadline})
	f.auth.answerAll(authAnswer{hang: true})
	start := time.Now()

	f.publish(t, testTokenA, testConnID(1))
	end := waitForInnerEnd(t, f.logs)

	if elapsed := time.Since(start); elapsed < deadline {
		t.Errorf("publish against a hanging auth ended after %v, want at least the Deadline %v", elapsed, deadline)
	}
	if end != "ingestauth_publish_denied" {
		t.Errorf("publish against a hanging auth ended with %s, want ingestauth_publish_denied", end)
	}
	if e, ok := lastDenied(t, f.logs); !ok || e.Reason != "undecided" || e.ResolveStatus != "timeout" {
		t.Errorf("publish_denied against a hanging auth = %v (found %t), want reason undecided with resolve_status timeout", e, ok)
	}
	if n := f.auth.callCount(); n != 1 {
		t.Errorf("fake auth calls against a hanging auth = %d, want 1", n)
	}
}

// resolve 의 시한은 요청의 시한(바깥 가드 — 바깥 층이 도착 시각에서 한 번 잰다)보다 guardMargin 앞이다(설계 3-7 「요청
// 도착부터 응답까지」 · 바깥 가드 +50ms). 그래서 안쪽 처리기가 늦게 깨도 resolve 와 가드가 같은 도착 시각에서 재고,
// resolve 가 시한에 끝나면 처리기가 가드보다 먼저 401 을 쓴다. 남은 시간이 guardMargin 안인 요청은 resolve 를 부르지
// 않고(auth 호출 0) 늦은 요청으로 끝낸다(publish_late) — 부르면 곧바로 판정 불가(timeout)가 나 헛된 auth_degraded 와 그
// 토큰의 기억이 생긴다. 요청 ctx 에 guardMargin 보다 가까운 시한을 달아, 실제로 기다리지 않고 시한의 유도를 가른다.
func TestHandlerPublishResolveEndsBeforeGuard(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	f.auth.answerAll(answerUsable(10 * time.Millisecond))
	ctx, cancel := context.WithDeadline(t.Context(), time.Now().Add(guardMargin-time.Millisecond))
	defer cancel()

	rr := f.serve(newAuthRequest(t, publishBody(testTokenA, testConnID(1))).WithContext(ctx))
	end := waitForInnerEnd(t, f.logs)

	if rr.Code == http.StatusOK {
		t.Errorf("publish with less than guardMargin left = 200, want no 200 (resolve 시한이 이미 지났다)")
	}
	if n := f.auth.callCount(); n != 0 {
		t.Errorf("fake auth calls for a publish with less than guardMargin left = %d, want 0", n)
	}
	if end != "ingestauth_publish_late" {
		t.Errorf("publish with less than guardMargin left ended with %s, want ingestauth_publish_late", end)
	}
	if got := gaugeNames(t, f.logs, "ingestauth_auth_degraded", "ingestauth_auth_recovered"); len(got) != 0 {
		t.Errorf("auth gauge events for a publish with less than guardMargin left = %v, want none", got)
	}
	if n := memorySize(f.h.failed); n != 0 {
		t.Errorf("failed-token memory after a publish with less than guardMargin left has %d entries, want 0", n)
	}
}

// MediaMTX 가 resolve 도중 요청을 버려도(재시작 · 리로드 · 망 — 요청 ctx 취소) 그 취소는 판정 불가가 아니다 — resolve
// 는 요청 취소에서 떨어져 auth 의 답(또는 resolve 시한)으로 끝난다. 그래서 헛된 auth_degraded(ERROR)도 그 토큰의 기억도
// 없다(설계 2-4 「헛된 auth_degraded · auth_recovered 짝이 나지 않는다」). 버린 요청에는 가드가 이미 답했으므로
// 처리기의 쓰기는 버려지고 publish_late 로 끝난다. 취소는 늦은 요청 확인 뒤 · resolve 앞(차례 admit 의 시계 읽기)에서
// 하고, 가드가 그 취소로 답한 뒤에 resolve 로 간다.
func TestHandlerCallerCancelIsNotUndecided(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	f.auth.answerAll(answerUsable(10 * time.Millisecond))
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	r := newAuthRequest(t, publishBody(testTokenA, testConnID(1))).WithContext(ctx)
	rr := httptest.NewRecorder()
	served := make(chan struct{})
	f.clock.onRead(readAdmit, func() {
		if n := f.auth.callCount(); n != 0 {
			t.Errorf("the clock read hooked as admit came after %d resolve call(s), want before any", n)
		}
		cancel()
		<-served
	})
	go func() {
		defer close(served)
		f.srv.ServeHTTP(rr, r)
	}()

	waitClosed(t, served, "the guard's answer to the dropped request")
	end := waitForInnerEnd(t, f.logs)

	if end != "ingestauth_publish_late" {
		t.Errorf("publish dropped by MediaMTX before the resolve ended with %s, want ingestauth_publish_late", end)
	}
	if n := f.auth.callCount(); n != 1 {
		t.Errorf("fake auth calls for a publish dropped before the resolve = %d, want 1 (resolve 는 요청 취소에서 떨어져 있다)", n)
	}
	if got := gaugeNames(t, f.logs, "ingestauth_auth_degraded", "ingestauth_auth_recovered"); len(got) != 0 {
		t.Errorf("auth gauge events for a publish dropped by MediaMTX = %v, want none", got)
	}
	if _, remembered := entryOf(f.h.failed, testTokenA); remembered {
		t.Errorf("token of a publish dropped by MediaMTX is remembered, want not (판정 불가가 아니다)")
	}
	if n := countDenied(t, f.logs, "undecided"); n != 0 {
		t.Errorf("undecided denials for a publish dropped by MediaMTX = %d, want 0", n)
	}
}

// 기록 쓰기가 실패하면 401(connlog_unwritable)이다(판정표). 디렉터리 쓰기 권한을 빼면 회전의 이름 바꾸기가 실패한다
// (maxBytes 1 — Append 하나가 회전 하나). 권한을 되돌리면 다음 publish 가 허용된다.
func TestHandlerPublishConnLogUnwritable(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	f := newHandlerFixture(t, fixtureOptions{connMaxBytes: 1})
	chmod(t, f.connDir, 0o500)

	rr := f.publish(t, testTokenA, testConnID(1))

	if rr.Code != http.StatusUnauthorized || rr.Body.Len() != 0 {
		t.Errorf("publish while the log directory is read-only = %d with a %d-byte body, want 401 without a body", rr.Code, rr.Body.Len())
	}
	if e, ok := lastDenied(t, f.logs); !ok || e.Reason != "connlog_unwritable" || e.ResolveStatus != "200" {
		t.Errorf("publish_denied while the log is unwritable = %+v (found %t), want reason connlog_unwritable with resolve_status 200", e, ok)
	}
	if allowed := handlerEvents(t, f.logs, "ingestauth_publish_allowed"); len(allowed) != 0 {
		t.Errorf("publish_allowed while the log is unwritable = %+v, want none", allowed)
	}

	chmod(t, f.connDir, 0o755)
	if rr := f.publish(t, testTokenA, testConnID(2)); rr.Code != http.StatusOK {
		t.Errorf("publish after restoring the log directory permission = %d, want 200", rr.Code)
	}
	if got := allowedConns(t, f.connDir); got[testConnID(2)] != testTokenA || got[testConnID(1)] != "" {
		t.Errorf("allowed connections = %v, want only %s", got, testConnID(2))
	}
}

// C2 순서 단언(설계 착수 체크리스트 205행): 가짜 auth 가 resolve 를 처리하는 동안 거부 목록에 그 토큰을
// 적은 뒤 valid:true 를 돌려준다. 응답은 401(denylisted)이고, 허용 연결 기록에는 그 연결의 줄이 있다 — 기록 추가가
// 마지막 거부 확인보다 먼저다. 옛 순서(재확인 → 기록)면 줄이 없다.
func TestHandlerPublishRecordsBeforeFinalDenyCheck(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	ans := answerUsable(20 * time.Millisecond)
	ans.before = func() {
		if err := os.WriteFile(f.denyPath, []byte(testTokenA+"\n"), 0o644); err != nil {
			t.Errorf("fake auth: writing the deny list returned error: %v", err)
		}
	}
	f.auth.answerAll(ans)

	rr := f.publish(t, testTokenA, testConnID(1))

	if rr.Code != http.StatusUnauthorized || rr.Body.Len() != 0 {
		t.Errorf("publish denylisted during resolve = %d with a %d-byte body, want 401 without a body", rr.Code, rr.Body.Len())
	}
	if e, ok := lastDenied(t, f.logs); !ok || e.Reason != "denylisted" || e.ResolveStatus != "200" || e.ResolveMS != 20 {
		t.Errorf("publish_denied for a token denylisted during resolve = %+v (found %t), want reason denylisted after resolve", e, ok)
	}
	if got := allowedConns(t, f.connDir); got[testConnID(1)] != testTokenA {
		t.Errorf("allowed connections = %v, want %s → %s (the line is written before the final deny check)", got, testConnID(1), testTokenA)
	}
	if allowed := handlerEvents(t, f.logs, "ingestauth_publish_allowed"); len(allowed) != 0 {
		t.Errorf("publish_allowed for a token denylisted during resolve = %+v, want none", allowed)
	}
}

// 기록 뒤 마지막 확인에서 거부 목록을 읽지 못해도 401(denylist_unreadable)이다(판정표). 줄은 남는다(무해).
func TestHandlerPublishFinalDenyCheckUnreadable(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	f := newHandlerFixture(t, fixtureOptions{})
	f.writeDenyList(t, "")
	t.Cleanup(func() { os.Chmod(f.denyPath, 0o644) }) // 시험이 끝나면 t.TempDir 정리가 되도록 되돌린다
	ans := answerUsable(20 * time.Millisecond)
	ans.before = func() {
		if err := os.Chmod(f.denyPath, 0o000); err != nil {
			t.Errorf("fake auth: removing the deny list permission returned error: %v", err)
		}
	}
	f.auth.answerAll(ans)

	rr := f.publish(t, testTokenA, testConnID(1))

	if rr.Code != http.StatusUnauthorized {
		t.Errorf("publish whose final deny check cannot read the list = %d, want 401", rr.Code)
	}
	if e, ok := lastDenied(t, f.logs); !ok || e.Reason != "denylist_unreadable" || e.ResolveStatus != "200" {
		t.Errorf("publish_denied for an unreadable final check = %+v (found %t), want reason denylist_unreadable after resolve", e, ok)
	}
	if got := allowedConns(t, f.connDir); got[testConnID(1)] != testTokenA {
		t.Errorf("allowed connections = %v, want %s → %s", got, testConnID(1), testTokenA)
	}
}

// Resolve 의 그 밖의 에러(토큰 꼴 아님 · 요청 생성 실패 · nil ctx)는 처리기 기본 갈래로 401 이다. 게이지 · 기억은
// 건드리지 않고 에러 문구를 로그에 싣지 않는다 — 로그에 끝점 조각이 없다(keyresolve 의 그 문구도 끝점을 싣지 않는다).
// 처리기에 닿는 것은 요청 생성 실패 하나다 — 나머지 둘은 judgeResolve 시험이 실제 에러로 본다.
func TestHandlerPublishResolveRequestFailure(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{resolver: newTestResolver(t, requestFailsBaseURL)})

	rr := f.publish(t, testTokenA, testConnID(1))

	if rr.Code != http.StatusUnauthorized || rr.Body.Len() != 0 {
		t.Errorf("publish whose resolve request cannot be built = %d with a %d-byte body, want 401 without a body", rr.Code, rr.Body.Len())
	}
	if e, ok := lastDenied(t, f.logs); !ok || e.Reason != "internal_error" || e.ResolveStatus != "" {
		t.Errorf("publish_denied for a resolve request failure = %+v (found %t), want reason internal_error with no resolve_status", e, ok)
	}
	for _, fragment := range []string{"fe80", "stream-keys", "IP-literal"} {
		if strings.Contains(f.logs.String(), fragment) {
			t.Errorf("logs carry %q from the resolve error text", fragment)
		}
	}
	if es := handlerEvents(t, f.logs, "ingestauth_auth_degraded"); len(es) != 0 {
		t.Errorf("auth_degraded events for a resolve request failure = %d, want 0", len(es))
	}
	if n := memorySize(f.h.failed); n != 0 {
		t.Errorf("failed-token memory after a resolve request failure has %d entries, want 0", n)
	}
}

// judgeResolve 는 Resolve 의 결과를 판정표의 갈래로 가른다. 그 밖의 에러 셋은 실제 클라이언트가 돌려준 값으로
// 본다 — 기억에 적지 않는다(outcome 없음). Valid 인데 영값 Passphrase 면 200 을 쓰지 않는 형식 밖이다(keyresolve 는 이
// 값을 내지 않아 처리기 수준으로는 닿지 않는다).
func TestJudgeResolve(t *testing.T) {
	pass, err := streamkey.NewPassphrase(testPassphrase)
	if err != nil {
		t.Fatalf("Setup: NewPassphrase returned error: %v", err)
	}
	client := newTestResolver(t, "http://auth.test:8082")
	_, notTokenErr := client.Resolve(t.Context(), "not-a-token")
	var nilCtx context.Context // nil ctx 갈래를 실제 에러로 얻는다
	_, nilCtxErr := client.Resolve(nilCtx, testTokenA)
	_, requestErr := newTestResolver(t, requestFailsBaseURL).Resolve(t.Context(), testTokenA)
	for name, err := range map[string]error{"not_token": notTokenErr, "nil_ctx": nilCtxErr, "request": requestErr} {
		var ue *keyresolve.UndecidedError
		if err == nil || errors.As(err, &ue) || errors.Is(err, keyresolve.ErrInvalidPassphrase) {
			t.Fatalf("Setup: Resolve %s returned %v, want an error outside UndecidedError and ErrInvalidPassphrase", name, err)
		}
	}
	tests := []struct {
		name        string
		v           keyresolve.Verdict
		err         error
		wantKind    judgmentKind
		wantStatus  string
		wantOutcome resolveOutcome
		wantRecord  bool
	}{
		{name: "쓸_수_있는_값", v: keyresolve.Verdict{Valid: true, UserID: 1, Passphrase: pass}, wantKind: judgedUsable, wantStatus: "200", wantOutcome: outcomeUsable, wantRecord: true},
		{name: "영값_passphrase", v: keyresolve.Verdict{Valid: true, UserID: 1}, wantKind: judgedOutOfFormat, wantStatus: "200", wantOutcome: outcomeUnusable, wantRecord: true},
		{name: "형식_밖", err: keyresolve.ErrInvalidPassphrase, wantKind: judgedOutOfFormat, wantStatus: "200", wantOutcome: outcomeUnusable, wantRecord: true},
		{name: "감싼_형식_밖", err: errors.Join(keyresolve.ErrInvalidPassphrase), wantKind: judgedOutOfFormat, wantStatus: "200", wantOutcome: outcomeUnusable, wantRecord: true},
		{name: "거절", v: keyresolve.Verdict{Reason: "REVOKED"}, wantKind: judgedRejected, wantStatus: "200", wantOutcome: outcomeUnusable, wantRecord: true},
		{name: "판정_불가_시한", err: &keyresolve.UndecidedError{Kind: "timeout"}, wantKind: judgedUndecided, wantStatus: "timeout", wantOutcome: outcomeUndecided, wantRecord: true},
		{name: "판정_불가_연결", err: &keyresolve.UndecidedError{Kind: "unreachable"}, wantKind: judgedUndecided, wantStatus: "unreachable", wantOutcome: outcomeUndecided, wantRecord: true},
		{name: "판정_불가_상태", err: &keyresolve.UndecidedError{Kind: "status", Status: 502}, wantKind: judgedUndecided, wantStatus: "502", wantOutcome: outcomeUndecided, wantRecord: true},
		{name: "판정_불가_해석", err: &keyresolve.UndecidedError{Kind: "parse"}, wantKind: judgedUndecided, wantStatus: "parse", wantOutcome: outcomeUndecided, wantRecord: true},
		{name: "토큰_꼴_아님", err: notTokenErr, wantKind: judgedBroken},
		{name: "nil_ctx", err: nilCtxErr, wantKind: judgedBroken},
		{name: "요청_생성_실패", err: requestErr, wantKind: judgedBroken},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			j := judgeResolve(tc.v, tc.err, 5*time.Millisecond)
			if j.kind != tc.wantKind || j.resolveStatus() != tc.wantStatus {
				t.Errorf("judgeResolve(%s) = kind %d, resolve_status %q, want kind %d, %q", tc.name, j.kind, j.resolveStatus(), tc.wantKind, tc.wantStatus)
			}
			o, record := j.outcome()
			if record != tc.wantRecord || (record && o != tc.wantOutcome) {
				t.Errorf("judgeResolve(%s).outcome() = %d, %t, want %d, %t", tc.name, o, record, tc.wantOutcome, tc.wantRecord)
			}
		})
	}
}

// 요청 ctx 가 이미 끝난 publish 는 resolve 를 부르지 않는다(판정표 — 바깥 가드가 먼저 끝냄). 그래도 401 을 쓴다 — 안쪽
// 처리기가 아무것도 쓰지 않고 돌아가면 TimeoutHandler 는 done 과 시한이 함께 준비됐을 때 그 응답을 200 으로 낼 수
// 있다. 안쪽 처리기를 가드 없이 곧바로 부른다.
func TestHandlerLatePublishWritesUnauthorized(t *testing.T) {
	f := newHandlerFixture(t, fixtureOptions{})
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	r := newAuthRequest(t, publishBody(testTokenA, testConnID(1))).WithContext(ctx)
	rr := httptest.NewRecorder()

	f.h.ServeHTTP(rr, r)

	if rr.Code != http.StatusUnauthorized || rr.Body.Len() != 0 {
		t.Errorf("publish with an ended ctx = %d with a %d-byte body, want 401 without a body", rr.Code, rr.Body.Len())
	}
	if n := f.auth.callCount(); n != 0 {
		t.Errorf("fake auth calls for an ended ctx = %d, want 0", n)
	}
	late := handlerEvents(t, f.logs, "ingestauth_publish_late")
	if len(late) != 1 || late[0].Level != "DEBUG" || late[0].Path != testTokenA || late[0].ConnID != testConnID(1) {
		t.Errorf("publish_late events = %+v, want one DEBUG with path and conn_id", late)
	}
	if denied := handlerEvents(t, f.logs, "ingestauth_publish_denied"); len(denied) != 0 {
		t.Errorf("publish_denied for an ended ctx = %+v, want none", denied)
	}
}
