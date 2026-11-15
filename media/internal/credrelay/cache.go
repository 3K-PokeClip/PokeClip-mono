package credrelay

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
)

// 원천을 다시 읽은 까닭이다 — from=imds 인 cred_served 의 refresh. 닫힌 집합이다.
const (
	reasonInitial  = "initial"  // 마지막 유효분 L 이 없다(첫 호출 · 실패 뒤 재시도)
	reasonWindow   = "window"   // 실제 만료까지 refreshWindow 이하
	reasonPeriodic = "periodic" // 마지막 성공 뒤 periodicReread 이상
)

// credCache 는 요청이 몰고 가는 자격 캐시다(배경 고루틴 없음 — 설계 2.5). 상태는 마지막 성공 자격 L(실제 만료
// E · 성공 시각 S) · 마지막 원천 호출 시각 A · 저하 여부다. 상태는 뮤텍스 안에서만 읽고 쓰고, 원천 호출은
// 뮤텍스 밖에서 한다. 겹친 요청은 A 를 먼저 적은 쪽만 원천을 부르고, 나머지는 그때의 L 로 답한다.
type credCache struct {
	src aws.CredentialsProvider
	now func() time.Time
	log *slog.Logger

	mu       sync.Mutex
	last     aws.Credentials // L
	have     bool            // L 이 있다
	lastOK   time.Time       // S
	lastCall time.Time       // A
	called   bool            // 원천을 한 번이라도 불렀다
	degraded bool            // 마지막 원천 호출이 실패했고 L 이 있다(다음 성공까지)
}

// credentials 는 지금의 L 과, 이 요청이 원천을 다시 읽어 성공했으면 그 까닭(refresh)을 돌려준다. 다시 읽어야
// 하고(L 없음 · 만료까지 15분 이하 · 마지막 성공 뒤 30분 이상) 마지막 원천 호출 뒤 10초가 지났으면 원천을
// 부른다. ctx 의 취소는 원천 호출에 옮기지 않는다 — 소비자가 먼저 끊어도 원천 호출의 성패는 IMDS 의 것이다.
func (c *credCache) credentials(ctx context.Context) (creds aws.Credentials, have bool, refresh string) {
	t := c.now()
	c.mu.Lock()
	reason := c.refreshReason(t)
	call := reason != "" && (!c.called || t.Sub(c.lastCall) >= minSourceInterval)
	if call {
		c.called, c.lastCall = true, t
	}
	c.mu.Unlock()

	if call && c.fetch(ctx, t) {
		refresh = reason
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.last, c.have, refresh
}

// refreshReason 은 시각 t 에 원천을 다시 읽어야 하는 까닭이고, 아니면 빈 값이다. c.mu 를 잡고 부른다.
func (c *credCache) refreshReason(t time.Time) string {
	switch {
	case !c.have:
		return reasonInitial
	case c.last.Expires.Sub(t) <= refreshWindow:
		return reasonWindow
	case t.Sub(c.lastOK) >= periodicReread:
		return reasonPeriodic
	}
	return ""
}

// fetch 는 원천을 sourceDeadline 안에서 한 번 부르고 상태를 고친다. 성공이면 L 을 새 값으로(같은 자격이어도)
// · S = t · 저하 끝. 실패면 L 은 그대로 두고, L 이 있으면 저하로 둔다(다음 성공까지). 만료가 없는 자격은
// 실패로 친다.
func (c *credCache) fetch(ctx context.Context, t time.Time) bool {
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), sourceDeadline)
	defer cancel()
	creds, err := c.src.Retrieve(ctx)
	if err == nil && (!creds.CanExpire || creds.Expires.IsZero()) {
		err = &sourceError{kind: kindExpiry, err: errors.New("원천 자격에 만료가 없다")}
	}

	c.mu.Lock()
	wasDegraded := c.degraded
	if err == nil {
		c.last, c.have, c.lastOK, c.degraded = creds, true, t, false
	} else {
		c.degraded = c.have
	}
	nowDegraded := c.degraded
	c.mu.Unlock()

	if err != nil {
		c.log.Error("cred_source_failed", "err_type", sourceErrorKind(err))
	}
	switch {
	case nowDegraded && !wasDegraded:
		c.log.Warn("cred_source_degraded", "value", 1)
	case !nowDegraded && wasDegraded:
		c.log.Info("cred_source_degraded", "value", 0, "reason", "recovered")
	}
	return err == nil
}
