package broadcast

// 발행기의 주기 일 둘 — 적체 게이지(15.8 lifecycle_backlog)와 정리(15.5 규칙 5).

import (
	"context"
	"time"
)

// backlogSQL 은 pending 행 수와 가장 오래된 pending 의 나이(밀리초)다.
const backlogSQL = `
SELECT pg_catalog.count(*),
       COALESCE(pg_catalog.max(EXTRACT(EPOCH FROM pg_catalog.now() - created_at) * 1000), 0)::bigint
  FROM broadcast_lifecycle_outbox WHERE state = 'pending'`

// checkBacklog 는 가장 오래된 pending 이 60초를 넘으면 lifecycle_backlog 를 켜고(value=n), pending 이 0건이 되면
// 끈다(value=0). 켜고 끌 때만 찍는다.
func (p *Publisher) checkBacklog(ctx context.Context) {
	var n, oldestMS int64
	if err := p.pool.QueryRow(ctx, backlogSQL).Scan(&n, &oldestMS); err != nil {
		p.storeFailed(ctx, "backlog", err)
		return
	}
	switch {
	case !p.backlogOn && time.Duration(oldestMS)*time.Millisecond > backlogAge:
		p.backlogOn = true
		p.log.Error("lifecycle_backlog", "value", n)
	case p.backlogOn && n == 0:
		p.backlogOn = false
		p.log.Error("lifecycle_backlog", "value", 0)
	}
}

// cleanup 은 한 시간에 한 번(기동 뒤 첫 회차 포함) 정리 문장을 돈다.
func (p *Publisher) cleanup(ctx context.Context) {
	now := time.Now()
	if !p.lastCleanup.IsZero() && now.Sub(p.lastCleanup) < cleanupEvery {
		return
	}
	p.lastCleanup = now
	if _, err := deleteSettled(ctx, p.pool, settledAge); err != nil {
		p.storeFailed(ctx, "cleanup", err)
	}
}
