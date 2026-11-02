package session

// 4D 적용의 겹 방어 시험(POK-196 M6-3a PR 직전 소커밋 — 보안 m63a M1 · db m63a L1). 수신부 범위 검사(POK-472 P10)가
// 놓친 값이 와도 ApplyIntentionalEnd 가 스스로 거부하는지, 잠금 경합 · 시한 실패를 모두 contended 로 접는지 잰다.

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

// 시각이 말이 안 되는 4D 는 쓰기 전에 bad_time 으로 거부한다 — 멈춘 시각이 PG 시계 + 대조 여유(8초)보다 미래이거나
// 연결 시작이 멈춘 시각보다 늦으면. 여유 안의 시계 어긋남(3초)은 받아들인다. 잡는 결함: 미래 표식이 COALESCE 로 첫
// 표식에 고착돼 그 뒤 조각이 모두 옛 세션에 붙음 / 거꾸로 된 연결 창으로 후보를 고름. 변이 = 검사 제거 → 미래 갈래가
// marked 라 FAIL · 여유 0 → 3초 갈래가 bad_time 이라 FAIL.
func TestApplyIntentionalEndRejectsImplausibleTimes(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Microsecond)
	for _, c := range []struct {
		name       string
		stop       time.Time
		connStart  time.Time
		wantEffect string
		wantSkip   string
	}{
		{"멈춘_시각이_한_시간_뒤", now.Add(time.Hour), now, "", skipBadTime},
		{"연결_시작이_멈춘_시각보다_늦음", now.Add(-2 * time.Second), now.Add(time.Hour), "", skipBadTime},
		{"여유_안의_시계_어긋남", now.Add(3 * time.Second), now.Add(-time.Hour), effectMarked, ""},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-X", startedAt: now.Add(-2 * time.Hour)})
			seedSegment(t, pool, "demo", "S-X", 1, now.Add(-5*time.Second))
			slots := &intentSlots{}

			res := apply(t, pool, IntentionalEnd{StreamID: "demo", StoppedAt: c.stop, ConnStartedAt: c.connStart}, slots.options())

			if res.Effect != c.wantEffect || res.Skip != c.wantSkip {
				t.Fatalf("결과 = %+v, want Effect %q · Skip %q", res, c.wantEffect, c.wantSkip)
			}
			if m := markOf(t, pool, "S-X"); c.wantSkip != "" && m != nil {
				t.Errorf("거부했는데 표식을 썼다: %v", *m)
			}
			if len(slots.closed)+len(slots.started) != 0 {
				t.Errorf("함수 칸을 불렀다: close %v · started %v", slots.closed, slots.started)
			}
		})
	}
}

// 부른 쪽 ctx 시한이 위상 잠금 대기 중에 끝나도 오류가 아니라 contended 다(db m63a L1). 변이 = 분류에서
// context.DeadlineExceeded 제거 → 오류로 올라와 FAIL.
func TestApplyIntentionalEndReportsContendedOnCallerDeadline(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	seedLedger(t, pool, ledgerRow{id: "S-X", streamID: "demo", state: "live", roundID: "S-X", startedAt: stopAt.Add(-time.Hour)})
	seedSegment(t, pool, "demo", "S-X", 1, stopAt.Add(-5*time.Second))
	holder, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("경쟁 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = holder.Rollback(ctx) }()
	if _, err := holder.Exec(ctx, topologyLockSQL, "demo"); err != nil {
		t.Fatalf("경쟁 위상 잠금 실패: %v", err)
	}
	dctx, cancel := context.WithTimeout(ctx, 500*time.Millisecond)
	defer cancel()

	res, err := ApplyIntentionalEnd(dctx, pool, endAt("demo"), (&intentSlots{}).options())

	if err != nil || res.Skip != skipContended || res.Effect != "" {
		t.Fatalf("결과 = (%+v, %v), want Skip contended · 오류 없음", res, err)
	}
	if m := markOf(t, pool, "S-X"); m != nil {
		t.Errorf("contended 인데 표식을 썼다: %v", *m)
	}
}

// 분류 한 곳 — 잠금 상한(55P03) · 문장 시한(57014) · 교착 희생(40P01) · ctx 시한 · 소속 재확인 초과는 contended, 그 밖은
// 오류다. index.isContention 과 같은 집합이다. 부른 쪽 취소(Canceled)는 경합이 아니다.
func TestIsContention(t *testing.T) {
	for _, c := range []struct {
		name string
		err  error
		want bool
	}{
		{"55P03", &pgconn.PgError{Code: "55P03"}, true},
		{"57014", &pgconn.PgError{Code: "57014"}, true},
		{"40P01", &pgconn.PgError{Code: "40P01"}, true},
		{"감싼_40P01", fmt.Errorf("회차 행 잠금 실패: %w", &pgconn.PgError{Code: "40P01"}), true},
		{"ctx_시한", fmt.Errorf("위상 잠금 실패: %w", context.DeadlineExceeded), true},
		{"소속_재확인_초과", errMembershipContended, true},
		{"부른_쪽_취소", context.Canceled, false},
		{"유일성_위반", &pgconn.PgError{Code: "23505"}, false},
		{"보통_오류", errors.New("boom"), false},
		{"nil", nil, false},
	} {
		if got := isContention(c.err); got != c.want {
			t.Errorf("isContention(%s) = %v, want %v", c.name, got, c.want)
		}
	}
}

// 재시도 가능 판정 — contended 만 참이다. 수신부가 이것으로 503(플러그인 재시도)을 고른다. bad_time 등 나머지 미적용은
// 다시 보내도 같은 답이라 거짓이고, 효과가 있으면 거짓이다. 변이 = 판정을 Skip != "" 로 → bad_time 갈래 FAIL.
func TestIntentionalResultRetryable(t *testing.T) {
	for _, c := range []struct {
		res  IntentionalResult
		want bool
	}{
		{IntentionalResult{Skip: skipContended}, true},
		{IntentionalResult{Skip: skipBadTime}, false},
		{IntentionalResult{Skip: skipNoSession}, false},
		{IntentionalResult{Skip: skipRoundSplitBlocked}, false},
		{IntentionalResult{Effect: effectMarked}, false},
	} {
		if got := c.res.Retryable(); got != c.want {
			t.Errorf("%+v.Retryable() = %v, want %v", c.res, got, c.want)
		}
	}
}
