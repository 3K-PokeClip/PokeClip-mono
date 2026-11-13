package index

// 만료 끄기 스위치(POK-551 ④) — stream_expiry_switch 한 행을 읽는다.
//
// 60일 만료(internal/expiry)는 스위치가 켜져 있을 때만 돈다. 기본은 끔이고, 켜고 끄는 것은 마스터의 손 SQL 이다(재기동
// 없음 — 만료기가 회차 시작 · 장 받기 전 · 방송마다 다시 읽는다). 읽지 못하면 끔이다(fail-closed). 그래서 기동 필수 목록
// (MissingLedgerSchema)에는 넣지 않는다 — 표가 없는 DB(이 표 전의 운영 SQL)에서도 사이드카는 뜨고 만료만 꺼진다.

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// expirySwitchSQL 은 스위치 한 행을 잠금 없이 읽는다.
const expirySwitchSQL = `SELECT enabled FROM stream_expiry_switch`

// errExpirySwitchMissing 은 스위치 행이 없다는 뜻이다. 행은 DDL 씨앗이 넣으므로 손 작업(DELETE · TRUNCATE)에서만
// 생기고, 끔으로 읽는다.
var errExpirySwitchMissing = errors.New("index: 만료 스위치 행(stream_expiry_switch)이 없다")

// ReadExpirySwitch 는 만료 스위치가 켜져 있는가다. 오류면 늘 거짓(끔)과 함께 돌려준다 — 행 없음 · 표 없음(42P01) · 권한
// 없음(42501) · 시간 초과(ctx 와 TxnDeadline 가운데 이른 쪽) 모두다. 부르는 쪽은 오류를 알리고 만료를 멈춘다.
func ReadExpirySwitch(ctx context.Context, pool *pgxpool.Pool) (bool, error) {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	var on bool
	err := pool.QueryRow(qctx, expirySwitchSQL).Scan(&on)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, errExpirySwitchMissing
	}
	if err != nil {
		return false, fmt.Errorf("만료 스위치 읽기 실패: %w", err)
	}
	return on, nil
}
