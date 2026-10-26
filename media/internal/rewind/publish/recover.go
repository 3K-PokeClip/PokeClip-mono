package publish

// 좌초 회수와 freeze_failed 확정 — M6 설계 2.1 RecoverOrphan(D17 4.1(d)) · 3.1 의 3단 · 좌초 포기(POK-196 M6-2 커밋 ②).
//
// 둘 다 한 번 시도하고 결과를 값으로 돌려준다(로그는 판정부 몫 — freeze.go 머리 주석). 대상 고르기(freezing 60초 초과)와
// freeze_orphan 게이지도 판정부(M6-3b 정산 스캔)가 한다.

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// freezeOrphanHard 는 좌초 회수의 포기 시한이다(D17 4.1(d) FREEZE_ORPHAN_HARD — 10분). dvr_freezing_at(첫 예약 시각)부터
// DB 시계로 잰다.
const freezeOrphanHard = 10 * time.Minute

// freezeOrphanDeadline 은 좌초 포기 사유다 — RecoverOrphan 이 freeze_failed 를 썼다.
const freezeOrphanDeadline = "orphan_deadline"

// failDVRSQL 은 freeze_failed 확정이다 — fence 를 쥐었고 아직 닫히지 않았으며 동결 시작부터 $3 이상 지났을 때만 쓴다(3단은
// $3 = 0 · 좌초 포기는 FREEZE_ORPHAN_HARD). 동결을 시작하지 않은 회차(open — 예약 전에 실패)는 시작 시각이 없어 시한
// 0 에만 맞는다.
const failDVRSQL = `
UPDATE stream_sessions
   SET dvr_state = 'freeze_failed'
 WHERE session_id = $1 AND stream_id = $4 AND writer_fence = $2 AND fence_expires_at > pg_catalog.now()
   AND dvr_state IN ('open', 'freezing')
   AND pg_catalog.now() - COALESCE(dvr_freezing_at, pg_catalog.now()) >= $3::interval
RETURNING 1`

// RecoverOrphan 은 죽은 writer 가 freezing 에 둔 회차를 fence 획득 뒤 Endlist 로 닫는다(4.1(d)).
//
// 봉인이 서지 않으면 FREEZE_ORPHAN_HARD(동결 시작부터 10분) 전에는 재시도다 — 다음 감시 주기에 다시 한다. 넘었으면
// 장부를 freeze_failed 로 쓰고 실패를 돌려준다(Reason orphan_deadline — 판정부가 다음 단계로 가지 않는 종국이다). 다른
// writer 가 lease 안에서 fence 를 쥐고 있으면 그 writer 가 닫는 중이라 시한이 넘었어도 덮지 않는다(재시도).
func (p *Publisher) RecoverOrphan(ctx context.Context, streamID, sessionID string) FreezeOutcome {
	out := p.Endlist(ctx, streamID, sessionID)
	if out.Result == ResultFrozen || out.Result == ResultEmpty {
		return out
	}
	switch failed := p.failDVR(ctx, streamID, sessionID, freezeOrphanHard); {
	case failed.Result == ResultFailed:
		failed.Reason, failed.Err = freezeOrphanDeadline, out.Err
		return failed
	case failed.Result == ResultFrozen:
		return failed
	}
	out.Result, out.Retry = ResultRetry, true
	return out
}

// FailDVR 는 3단이다 — 봉인(2단)도 서지 않은 회차를 freeze_failed 로 확정한다(D17 4.5.3 실패 탈출 3단 · 설계 3.1). fence
// 를 얻어 아직 닫히지 않은 회차만 쓴다. 성공이면 Result failed(장부가 freeze_failed)다. 이미 frozen 이면 그 결과(덮지
// 않는다), fence 를 얻지 못하면 재시도다.
func (p *Publisher) FailDVR(ctx context.Context, streamID, sessionID string) FreezeOutcome {
	return p.failDVR(ctx, streamID, sessionID, 0)
}

// failDVR 는 동결 시작부터 minAge 이상 지난 회차를 freeze_failed 로 쓴다 — 0행이면 까닭을 가른다(reserve 와 같은 판별).
func (p *Publisher) failDVR(ctx context.Context, streamID, sessionID string, minAge time.Duration) FreezeOutcome {
	if ok, err := p.acquire(ctx, sessionID); err != nil || !ok {
		return dvrRetry(freezeFence, err)
	}
	f := &freezeJob{p: p, stream: streamID, session: sessionID}
	var one int
	err := f.queryRow(ctx, failDVRSQL, sessionID, p.opt.Writer, minAge, streamID).Scan(&one)
	switch {
	case err == nil:
		return dvrFailed(freezeStateFailed, nil)
	case !errors.Is(err, pgx.ErrNoRows):
		return dbOutcome(err)
	}
	var state string
	if err := f.queryRow(ctx, dvrStateSQL, sessionID, streamID).Scan(&state); err != nil {
		return dbOutcome(err)
	}
	switch state {
	case "frozen":
		return FreezeOutcome{Axis: AxisDVR, Result: ResultFrozen}
	case "freeze_failed":
		return dvrFailed(freezeStateFailed, nil)
	}
	return dvrRetry(freezeOrphanDeadline, nil) // 시한 전이거나 fence 를 그 사이 잃었다
}
