package expiry

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// Ledger 는 만료가 쓰는 장부 걸음이다(소비자 정의). 문장 · 트랜잭션은 index/expiry_store.go 가 갖고, 이 패키지는
// 순서와 오류 갈래만 갖는다.
type Ledger interface {
	// SwitchOn 은 DB 만료 스위치(stream_expiry_switch)가 켜져 있는가다. 오류면 끔이다(index.ReadExpirySwitch).
	SwitchOn(ctx context.Context) (bool, error)
	// RoundsSettled 는 미룸 판정이다 — 거짓이면 구간을 앞으로 품을 수 있는 회차(round_id) 또는 사슬(round_id 없는 옛
	// 세션)이 아직 확정될 수 있다(index.ExpiryRoundsSettled).
	RoundsSettled(ctx context.Context, q index.ExpiryQuery) (bool, error)
	MarkExpired(ctx context.Context, q index.ExpiryQuery) (int64, error)
	CutoffValid(ctx context.Context, streamID string) (bool, error)
	Rows(ctx context.Context, q index.ExpiryQuery) ([]index.ExpiryRow, error)
	Documents(ctx context.Context, q index.ExpiryQuery) ([]index.ExpiryDocument, error)
	DeleteRows(ctx context.Context, streamID string, seqs []int64) (index.DeleteResult, error)
}

// pgLedger 는 Ledger 의 PostgreSQL 구현이다 — index 함수에 풀을 붙인 것뿐이다.
type pgLedger struct {
	pool *pgxpool.Pool
}

// NewPGLedger 는 pool 로 도는 Ledger 다. 사이드카(media_indexer) 풀을 준다.
func NewPGLedger(pool *pgxpool.Pool) Ledger { return pgLedger{pool: pool} }

func (l pgLedger) SwitchOn(ctx context.Context) (bool, error) {
	return index.ReadExpirySwitch(ctx, l.pool)
}

func (l pgLedger) RoundsSettled(ctx context.Context, q index.ExpiryQuery) (bool, error) {
	return index.ExpiryRoundsSettled(ctx, l.pool, q)
}

func (l pgLedger) MarkExpired(ctx context.Context, q index.ExpiryQuery) (int64, error) {
	return index.MarkExpired(ctx, l.pool, q)
}

func (l pgLedger) CutoffValid(ctx context.Context, streamID string) (bool, error) {
	return index.ExpiryCutoffValid(ctx, l.pool, streamID)
}

func (l pgLedger) Rows(ctx context.Context, q index.ExpiryQuery) ([]index.ExpiryRow, error) {
	return index.ExpiryRows(ctx, l.pool, q)
}

func (l pgLedger) Documents(ctx context.Context, q index.ExpiryQuery) ([]index.ExpiryDocument, error) {
	return index.ExpiryDocuments(ctx, l.pool, q)
}

func (l pgLedger) DeleteRows(ctx context.Context, streamID string, seqs []int64) (index.DeleteResult, error) {
	return index.DeleteExpired(ctx, l.pool, streamID, seqs)
}
