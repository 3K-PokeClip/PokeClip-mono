package publish

// 계승 해제 작업 — 4D round_split 의 새 회차 첫 세션이 옛 회차를 계승 중일 때(IntentionalResult.RevokeSession) 루프가 그
// 스트림 차선에 낸다(POK-196 M6-3b 커밋 ③ — 설계 M6 3.4 「계승 해제」 · 9절 D55). 평시 틱 준비 단계와 같은 꼴이다:
// fence 획득 → 저장소 Head 화해 → 객체가 없을 때(R4)만 P2′.
//
// 객체가 있으면(첫 PUT 뒤 — 또는 P4 를 잃어 DB ETag 는 NULL 인데 PUT 은 선 판) 해제하지 않는다. 이미 발행한 목록의 계승을
// 끊으면 DISC-SEQ 기준이 0 으로 되돌아 시청자 목록이 역행한다. Head 가 실패해도 해제하지 않고 다시 시도하지 않는다 —
// 그 회차 되감기에 옛 회차 접두(최대 1시간)가 보이는 한계다(D55). 스트림 차선에서 돌아 같은 스트림의 발행 틱과 겹치지
// 않는다 — 틱의 PUT 이 Head 와 R4 사이에 끼면 이미 선 판의 계승을 끊게 된다.

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
)

// 해제하지 않은 사유 — RevokeOutcome.Reason 값이다(로그 속성 reason).
const (
	RevokePublished  = "published"   // 저장소에 목록 객체가 있다(첫 PUT 뒤)
	RevokeFence      = "fence"       // fence 를 얻지 못했거나 R4 에서 잃었다
	RevokeHeadFailed = "head_failed" // 저장소 Head 실패
	RevokeKey        = "key"         // 목록 키를 만들 수 없는 성분
	RevokeNoRow      = "no_row"      // P2′ 0행 — live 가 아니거나 이미 계승이 없거나 그 사이 판이 섰다
)

// RevokeOutcome 은 해제 작업 한 번의 결과다. Revoked 면 장부에서 계승이 풀렸다 — 루프가 캐시에
// ApplyInheritanceRevoked 로 넣는다. 아니면 Reason 이 사유이고 Err 는 그 원인 오류다(없을 수 있다).
type RevokeOutcome struct {
	Revoked bool
	Reason  string
	Err     error
}

// RevokeInheritance 는 회차 sessionID(스트림 streamID)의 계승을 첫 PUT 전일 때만 푼다. 한 번 시도한다.
func (p *Publisher) RevokeInheritance(ctx context.Context, streamID, sessionID string) RevokeOutcome {
	key, err := manifestKey(streamID, sessionID)
	if err != nil {
		return RevokeOutcome{Reason: RevokeKey, Err: err}
	}
	if ok, err := p.acquire(ctx, sessionID); err != nil || !ok {
		return RevokeOutcome{Reason: RevokeFence, Err: err}
	}
	hctx, cancel := context.WithTimeout(ctx, p.opt.PublishTimeout)
	stat, err := p.store.Head(hctx, key)
	cancel()
	switch {
	case err != nil:
		return RevokeOutcome{Reason: RevokeHeadFailed, Err: err}
	case stat.Exists:
		return RevokeOutcome{Reason: RevokePublished}
	}
	gen, ok, err := p.revokeRow(ctx, r4ClearETagSQL, sessionID, p.opt.Writer)
	if err != nil || !ok {
		return RevokeOutcome{Reason: RevokeFence, Err: err}
	}
	if _, ok, err := p.revokeRow(ctx, p2RevokeSQL, sessionID, p.opt.Writer, gen); err != nil || !ok {
		return RevokeOutcome{Reason: RevokeNoRow, Err: err}
	}
	return RevokeOutcome{Revoked: true}
}

// revokeRow 는 RETURNING 한 열(세대 · base)을 돌려주는 fence 조건 문장 하나다. 0행이면 ok=false 다.
func (p *Publisher) revokeRow(ctx context.Context, sql string, args ...any) (int64, bool, error) {
	dctx, cancel := p.stmtCtx(ctx)
	defer cancel()
	var v int64
	err := p.pool.QueryRow(dctx, sql, args...).Scan(&v)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, fmt.Errorf("publish: 계승 해제 문장 session=%q: %w", args[0], err)
	}
	return v, true, nil
}
