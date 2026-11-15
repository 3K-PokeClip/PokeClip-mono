package credrelay

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
)

// 응답 Expiration 은 응답할 때마다 min(실제 만료 E, 응답 시각 + 60분)이다(P6 핸들러 층). 원천은 운영과 같은
// NewIMDSRoleSource + 실제 imds 클라이언트다. ④ 는 ① 뒤 20분(원천 호출 억제 상태 — 30분 재조회 · 15분 창
// 밖)에 다시 물어 값이 20분 늘었는지 본다 — 받을 때 한 번 계산해 저장하는 구현이면 늘지 않는다.
func TestServedExpirationIsMinOfSourceAndCap(t *testing.T) {
	for _, c := range []struct {
		name      string
		expiresIn time.Duration
		want      func(start, e time.Time) time.Time
	}{
		{name: "far_source_capped", expiresIn: 6 * time.Hour, want: func(s, _ time.Time) time.Time { return s.Add(consumerRefreshCap) }},
		{name: "near_source_20m", expiresIn: 20 * time.Minute, want: func(_, e time.Time) time.Time { return e }},
		{name: "near_source_3m", expiresIn: 3 * time.Minute, want: func(_, e time.Time) time.Time { return e }},
	} {
		t.Run(c.name, func(t *testing.T) {
			r := newRelay(t, c.expiresIn)
			e := r.clock.start.Add(c.expiresIn)

			got := mustServe(t, r.get(canaryIndexerToken), c.name)
			if want := c.want(r.clock.start, e); !got.Expiration.Equal(want) {
				t.Errorf("%s: Expiration %v, want %v", c.name, got.Expiration, want)
			}
			if got.Expiration.After(e) {
				t.Errorf("%s: Expiration %v 이 원천 만료 %v 보다 늦다", c.name, got.Expiration, e)
			}
		})
	}

	t.Run("recomputed_per_response", func(t *testing.T) {
		r := newRelay(t, 6*time.Hour)
		mustServe(t, r.get(canaryIndexerToken), "첫 요청")
		r.clock.advance(20 * time.Minute)

		got := mustServe(t, r.get(canaryJanitorToken), "20분 뒤")
		if want := r.clock.now().Add(consumerRefreshCap); !got.Expiration.Equal(want) {
			t.Errorf("20분 뒤 Expiration %v, want 새 응답 시각 + 60분 %v", got.Expiration, want)
		}
		if n := len(r.src.callTimes()); n != 1 {
			t.Errorf("원천 호출 %d번, want 1 (20분 뒤는 캐시)", n)
		}
		served := r.logs.events(t, "cred_served")
		if len(served) != 2 || served[0]["from"] != "imds" || served[0]["refresh"] != "initial" || served[1]["from"] != "cache" {
			t.Errorf("cred_served 줄 = %v, want [from=imds refresh=initial, from=cache]", served)
		}
		if got := served[1]["expires_in_s"]; got != float64(3600) {
			t.Errorf("두 번째 cred_served expires_in_s = %v, want 3600", got)
		}
		if got := served[1]["source_expires_in_s"]; got != float64(6*3600-20*60) {
			t.Errorf("두 번째 cred_served source_expires_in_s = %v, want %d", got, 6*3600-20*60)
		}
	})
}

// 원천이 실패해도 마지막 유효분을 실제 만료 1분 전까지 준다(P7). 70분 시점은 첫 자격 뒤 1시간이 지난 자리라,
// 원천이 만료를 1시간으로 자르는 구현(ec2rolecreds)이면 503 이다. 저하 게이지는 켜질 때 한 번, 회복할 때 한 번
// 낸다.
func TestServesLastValidWhileSourceFails(t *testing.T) {
	r := newRelay(t, 6*time.Hour)
	mustServe(t, r.get(canaryIndexerToken), "첫 요청")
	r.imds.update(func(f *fakeIMDS) { f.credsStatus = http.StatusInternalServerError })
	r.clock.advance(70 * time.Minute)

	got := mustServe(t, r.get(canaryIndexerToken), "원천 실패 중 70분")
	if want := r.clock.now().Add(consumerRefreshCap); !got.Expiration.Equal(want) {
		t.Errorf("원천 실패 중 Expiration %v, want 응답 시각 + 60분 %v", got.Expiration, want)
	}
	if got := r.logs.events(t, "cred_source_degraded"); len(got) != 1 || got[0]["value"] != float64(1) {
		t.Errorf("cred_source_degraded = %v, want value=1 한 줄", got)
	}
	if got := r.logs.events(t, "cred_source_failed"); len(got) != 1 || got[0]["err_type"] == "" {
		t.Errorf("cred_source_failed = %v, want err_type 이 있는 한 줄", got)
	}

	r.imds.update(func(f *fakeIMDS) { f.credsStatus = 0 })
	r.clock.advance(minSourceInterval)
	mustServe(t, r.get(canaryIndexerToken), "회복 뒤")
	got2 := r.logs.events(t, "cred_source_degraded")
	if len(got2) != 2 || got2[1]["value"] != float64(0) || got2[1]["reason"] != "recovered" {
		t.Errorf("cred_source_degraded = %v, want 두 번째 줄 value=0 reason=recovered", got2)
	}
}

// 저하 게이지의 set · clear 는 같은 등급이다 — WARN 이상만 받는 로그에서도 회복 줄이 걸러지지 않는다.
func TestDegradedGaugeClearSurvivesWarnLevel(t *testing.T) {
	clock := newManualClock()
	fake := newFakeIMDS(t, clock.start.Add(6*time.Hour))
	logs := &logBuf{}
	warnOnly := slog.New(slog.NewJSONHandler(logs, &slog.HandlerOptions{Level: slog.LevelWarn}))
	h := NewHandler(fake.source(clock.now), loadTestTokens(t), clock.now, warnOnly)
	r := &relay{clock: clock, imds: fake, logs: logs, h: h}

	mustServe(t, r.get(canaryIndexerToken), "첫 요청")
	r.imds.update(func(f *fakeIMDS) { f.credsStatus = http.StatusInternalServerError })
	r.clock.advance(periodicReread)
	mustServe(t, r.get(canaryIndexerToken), "원천 실패 중")
	r.imds.update(func(f *fakeIMDS) { f.credsStatus = 0 })
	r.clock.advance(minSourceInterval)
	mustServe(t, r.get(canaryIndexerToken), "회복 뒤")

	got := logs.events(t, "cred_source_degraded")
	if len(got) != 2 || got[0]["value"] != float64(1) || got[1]["value"] != float64(0) {
		t.Errorf("WARN 이상 로그의 cred_source_degraded = %v, want value=1 · value=0 두 줄", got)
	}
}

// 마지막 성공 뒤 30분이면 요청 시각에 다시 읽는다(P27a). 요청이 10분 간격이고 원천 만료는 멀리 고정이라,
// 원천 호출은 {0, 30, 60, 90분} 이고 그 사이 요청에서는 없다. 다시 읽은 줄은 refresh=periodic 이다.
func TestPeriodicRereadOnRequest(t *testing.T) {
	r := newRelay(t, 6*time.Hour)
	for offset := time.Duration(0); offset <= 90*time.Minute; offset += 10 * time.Minute {
		r.clock.moveTo(offset)
		mustServe(t, r.get(canaryIndexerToken), offset.String())
	}

	assertCallOffsets(t, r, []time.Duration{0, 30 * time.Minute, 60 * time.Minute, 90 * time.Minute})
	var refresh []any
	for _, ev := range r.logs.events(t, "cred_served") {
		if ev["from"] == "imds" {
			refresh = append(refresh, ev["refresh"])
		}
	}
	if len(refresh) != 4 || refresh[0] != "initial" || refresh[1] != "periodic" || refresh[3] != "periodic" {
		t.Errorf("from=imds 줄의 refresh = %v, want [initial periodic periodic periodic]", refresh)
	}
}

// 첫 자격을 만료 직전까지 붙들지 않는다(P27b · 계획 E32). t0 에 C1(만료 t0+6h), t0+3h 부터 서버는 C2(만료
// t0+9h), t0+5h40m 부터 500. 요청은 55분 간격에 t0+6h05m 을 더한다. 30분 재조회가 C2 를 미리 받아 두므로
// t0+6h05m 요청은 C2 로 200 이다 — 재조회가 없으면 그때 C1 이 지나 503 이다. 원천 호출은 요청 시각에만 있다.
func TestPeriodicRereadPicksUpRotatedCredentials(t *testing.T) {
	const c2Key = canaryAccessKey + "C2"
	r := newRelay(t, 6*time.Hour)
	var offsets []time.Duration
	for m := 0; m <= 330; m += 55 {
		offsets = append(offsets, time.Duration(m)*time.Minute)
	}
	offsets = append(offsets, 6*time.Hour+5*time.Minute)

	var last servedCreds
	for _, off := range offsets {
		r.clock.moveTo(off)
		r.imds.update(func(f *fakeIMDS) {
			if off >= 3*time.Hour {
				f.creds.AccessKeyID = c2Key
				f.creds.Expiration = r.clock.start.Add(9 * time.Hour)
			}
			if off >= 5*time.Hour+40*time.Minute {
				f.credsStatus = http.StatusInternalServerError
			}
		})
		last = mustServe(t, r.get(canaryIndexerToken), off.String())
	}

	if last.AccessKeyID != c2Key {
		t.Error("t0+6h05m 요청이 C2 가 아닌 자격을 받았다")
	}
	assertCallOffsets(t, r, offsets)
}

// 15분 창 안에서 같은 자격이 돌아와도 원천 호출 사이는 10초 이상이다 — 성공 · 실패와 무관하다(P28).
func TestMinSourceIntervalHoldsOnSuccess(t *testing.T) {
	r := newRelay(t, 10*time.Minute)
	for i := 0; i < 100; i++ {
		mustServe(t, r.get(canaryIndexerToken), "창 안 요청")
		r.clock.advance(99 * time.Millisecond)
	}
	if n := len(r.src.callTimes()); n != 1 {
		t.Errorf("10초 안 100번 요청에 원천 호출 %d번, want 1", n)
	}
}

// 만료가 없는 원천 자격은 L 로 받지 않는다(원천 실패 · 종류 expiry) — 소비자가 영원히 캐시하게 두지 않는다.
// 남은 수명이 1분 이하인 L 도 주지 않는다(503) — 응답 시각에 정확히 1분 남은 경계에서 본다.
func TestRejectsNonExpiringAndNearlyExpired(t *testing.T) {
	clock := newManualClock()
	creds := aws.Credentials{AccessKeyID: canaryAccessKey, SecretAccessKey: canarySecretKey, SessionToken: canarySessionToken}
	src := aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) { return creds, nil })
	logs := &logBuf{}
	h := NewHandler(src, loadTestTokens(t), clock.now, newTestLogger(logs))
	r := &relay{clock: clock, logs: logs, h: h}

	if rec := r.get(canaryIndexerToken); rec.Code != http.StatusServiceUnavailable {
		t.Errorf("만료 없는 원천 자격: 상태 %d, want 503", rec.Code)
	}
	if got := logs.events(t, "cred_source_failed"); len(got) != 1 || got[0]["err_type"] != kindExpiry {
		t.Errorf("cred_source_failed = %v, want err_type=%s 한 줄", got, kindExpiry)
	}

	creds.CanExpire, creds.Expires = true, clock.now().Add(minSourceInterval+serveFloor)
	clock.advance(minSourceInterval)
	if rec := r.get(canaryIndexerToken); rec.Code != http.StatusServiceUnavailable {
		t.Errorf("남은 수명 정확히 1분 자격: 상태 %d, want 503", rec.Code)
	}
	if got := logs.events(t, "cred_unavailable"); len(got) != 2 {
		t.Errorf("cred_unavailable %d줄, want 2", len(got))
	}
}

// 실제 만료까지 정확히 15분 남으면 다시 읽는다(창은 15분 이하 — 설계 2.5). 원천 만료가 시작 + 20분이라 5분
// 시점이 경계다. 마지막 성공 뒤 5분이라 30분 재조회와 겹치지 않는다.
func TestRefreshWindowIncludesBoundary(t *testing.T) {
	r := newRelay(t, refreshWindow+5*time.Minute)
	mustServe(t, r.get(canaryIndexerToken), "첫 요청")
	r.clock.moveTo(5 * time.Minute)
	mustServe(t, r.get(canaryIndexerToken), "남은 수명 정확히 15분")

	assertCallOffsets(t, r, []time.Duration{0, 5 * time.Minute})
	if served := r.logs.events(t, "cred_served"); len(served) != 2 || served[1]["refresh"] != "window" {
		t.Errorf("cred_served = %v, want 두 번째 줄 refresh=window", served)
	}
}

// 소비자가 요청을 먼저 끊어도 원천 호출은 그 취소를 물려받지 않는다(설계 2.5 — 원천 호출의 성패는 IMDS 의
// 것이다). 이미 끊긴 요청으로 물어도 원천은 살아 있는 ctx(상한 sourceDeadline)를 받고 자격을 캐시에 남긴다.
func TestSourceCallIgnoresConsumerCancel(t *testing.T) {
	clock := newManualClock()
	creds := aws.Credentials{AccessKeyID: canaryAccessKey, SecretAccessKey: canarySecretKey, SessionToken: canarySessionToken, CanExpire: true, Expires: clock.now().Add(6 * time.Hour)}
	src := aws.CredentialsProviderFunc(func(ctx context.Context) (aws.Credentials, error) {
		if err := ctx.Err(); err != nil {
			return aws.Credentials{}, err
		}
		if _, ok := ctx.Deadline(); !ok {
			return aws.Credentials{}, errors.New("원천 호출에 상한이 없다")
		}
		return creds, nil
	})
	h := NewHandler(src, loadTestTokens(t), clock.now, newTestLogger(&logBuf{}))
	ctx, cancel := context.WithCancel(t.Context())
	cancel()

	req := httptest.NewRequestWithContext(ctx, http.MethodGet, CredentialsPath, nil)
	req.Header.Set("Authorization", canaryIndexerToken)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	mustServe(t, rec, "끊긴 요청")
}

// assertCallOffsets 는 원천 호출 시각이 시계 시작에서 want 만큼 떨어진 시각들과 같은지 본다.
func assertCallOffsets(t *testing.T, r *relay, want []time.Duration) {
	t.Helper()
	calls := r.src.callTimes()
	got := make([]time.Duration, len(calls))
	for i, c := range calls {
		got[i] = c.Sub(r.clock.start)
	}
	if len(got) != len(want) {
		t.Fatalf("원천 호출 시각 %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("원천 호출 시각 %v, want %v", got, want)
		}
	}
}
