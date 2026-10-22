package main

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgconf"
)

// poolConfigTimeout 은 기동 때 풀 설정 조립(CA 파일 확인 · iam 이면 AWS 설정 적재)의 상한이다. 조립은 망을 타지 않지만
// AWS 설정 적재가 무엇을 읽든 기동이 끝없이 걸리지 않게 묶는다(C1 보안 검수 L3). janitor 의 기동 왕복 상한과 같은 값이다.
const poolConfigTimeout = 20 * time.Second

// openIndexerPool 은 인덱서 풀을 만들고 연결 하나로 Ping 해 db_connected 한 줄을 남긴다(배포 확인의 판정 줄). 둘째 반환값은
// 풀 설정 원본이다 — 발행자 풀은 이것의 Copy 로 만든다(같은 사용자 · 같은 토큰 함수 · AWS 설정 적재 한 번). 연결 확인이
// 실패하면 만든 풀을 닫고 오류다 — 사이드카는 기동 실패로 끝나고 재기동이 회복한다. 오류에 비밀번호 · DSN 은 싣지 않는다.
func openIndexerPool(ctx context.Context, s pgconf.Settings, log *slog.Logger) (*pgxpool.Pool, *pgxpool.Config, error) {
	cctx, cancel := context.WithTimeout(ctx, poolConfigTimeout)
	pc, err := pgconf.PoolConfig(cctx, s)
	cancel()
	if err != nil {
		return nil, nil, err
	}
	pool, err := pgxpool.NewWithConfig(ctx, pc.Copy())
	if err != nil {
		return nil, nil, fmt.Errorf("DB 연결 풀 생성 실패: %w", err)
	}
	if err := pgconf.ConfirmConnection(ctx, pool, s, log, "indexer"); err != nil {
		pool.Close()
		return nil, nil, err
	}
	return pool, pc, nil
}
