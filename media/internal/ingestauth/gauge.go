package ingestauth

import (
	"log/slog"
	"sync"
	"time"
)

// authGauge 는 auth 판정 불가 게이지다(설계 6절 게이지 표 — 「auth 가 판정을 돌려주는가」). 처음 상태는 판정됨(clear)
// 이다 — 그래서 기동 직후 auth 가 이미 죽어 있으면 첫 판정 불가에서 set 이 난다. 판정 불가면 set(ingestauth_auth_degraded
// — reason · status), 그 뒤 첫 판정 결과(valid 참 · 거짓 · passphrase 형식 밖)면 clear(ingestauth_auth_recovered —
// down_for · denied_during · budget_denied · recheck_denied)다. 둘 다 ERROR 다 — clear 만 등급이 낮으면
// LOG_LEVEL=warn 에서 회복이 걸러진다. 예산 · 차례 거절은 resolve 를 부르지 않으므로 게이지를 바꾸지 않는다.
//
// denied_during 은 게이지가 선 동안(set 을 낸 요청 포함) ingestauth_publish_denied 로 끝난 publish 수다(사유 무관).
// budget_denied · recheck_denied 는 그 가운데 예산 · 차례 거절 수다. down_for 는 set 에서 clear 까지다(Options.Now).
// 상태 · 셈 · 로그(게이지 로그와 거절 로그)는 잠금 하나 안에서 바꾸고 낸다 — 그래서 set · clear 로그가 전이의 차례대로
// 나고, recovered 의 denied_during 은 그 앞의 degraded 뒤에 난 거절 줄 수와 같다. 여러 고루틴이 함께 불러도 된다.
type authGauge struct {
	now func() time.Time
	log *slog.Logger

	mu            sync.Mutex // 아래 다섯을 지키고, 로그를 전이 차례대로 낸다
	degraded      bool
	since         time.Time // set 한 시각
	deniedDuring  int
	budgetDenied  int
	recheckDenied int
}

// undecidedCause 는 판정 불가로 끝난 거절의 까닭이다 — keyresolve.UndecidedError 의 Kind · Status 이고, 게이지 set 의
// reason · status 로 싣는다.
type undecidedCause struct {
	failure string
	status  int
}

// denied 는 publish 거절 하나를 적는다. cause 가 nil 이 아니면 그 거절은 판정 불가로 끝난 것이라, 판정된 상태였으면
// 먼저 set 한다(ingestauth_auth_degraded). 게이지가 선 동안이면 그 거절을 센다 — set 을 낸 거절도 든다. emit 은 그
// 거절의 로그(ingestauth_publish_denied)를 내는 함수이고 같은 잠금 안에서 부른다 — 전이 · 셈 · 거절 로그 사이에 다른
// 요청의 clear 가 끼지 않는다. emit 은 게이지의 메서드를 부르지 않는다(같은 잠금이라 멈춘다).
func (g *authGauge) denied(reason string, cause *undecidedCause, emit func()) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if cause != nil && !g.degraded {
		g.degraded, g.since = true, g.now()
		g.deniedDuring, g.budgetDenied, g.recheckDenied = 0, 0, 0
		g.log.Error("ingestauth_auth_degraded", "reason", cause.failure, "status", cause.status)
	}
	if g.degraded {
		g.deniedDuring++
		switch reason {
		case reasonBudgetExhausted:
			g.budgetDenied++
		case reasonRecheckWait:
			g.recheckDenied++
		}
	}
	emit()
}

// decided 는 판정 결과 하나를 적는다. 판정 불가 상태였으면 clear 한다.
func (g *authGauge) decided() {
	g.mu.Lock()
	defer g.mu.Unlock()
	if !g.degraded {
		return
	}
	g.degraded = false
	g.log.Error("ingestauth_auth_recovered",
		"down_for", max(g.now().Sub(g.since), 0),
		"denied_during", g.deniedDuring,
		"budget_denied", g.budgetDenied,
		"recheck_denied", g.recheckDenied)
}

// connLogGauge 는 허용 연결 기록 게이지다(설계 6절 게이지 표). 쓰기가 실패하면 set(ingestauth_connlog_error — 이동안 새
// 연결은 거부된다. 디스크 부족은 여기로만 드러난다), 그 뒤 첫 쓰기 성공에 clear(ingestauth_connlog_recovered)다. 둘 다
// ERROR 다. 여러 고루틴이 함께 불러도 된다.
type connLogGauge struct {
	log *slog.Logger

	// mu 는 failing 을 지키고 쓰기와 게이지 전이를 한 차례로 묶는다 — 게이지에는 늘 마지막 쓰기의 결과가 남는다.
	// 기록의 Append 도 자기 잠금으로 줄을 세우므로 이 잠금이 더하는 기다림은 없다.
	mu      sync.Mutex
	failing bool
}

// append 는 c 에 token · connID 의 줄을 쓰고 그 결과로 게이지를 맞춘다. 쓰기의 에러를 그대로 돌려준다. 에러 문구는
// 기록 경로와 시스템 호출의 이유뿐이라(토큰 · 연결 id 는 공개 식별자) 게이지 로그에 싣는다.
func (g *connLogGauge) append(c *ConnLog, token, connID string) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	err := c.Append(token, connID)
	switch {
	case err != nil && !g.failing:
		g.failing = true
		g.log.Error("ingestauth_connlog_error", "error", err.Error())
	case err == nil && g.failing:
		g.failing = false
		g.log.Error("ingestauth_connlog_recovered")
	}
	return err
}
