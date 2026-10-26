package main

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgconf"
)

// dbStepTimeout 은 기동 때 DB 단계 하나(풀 설정 조립 · 연결 확인 — 각각)의 상한이다. 조립(CA 파일 확인 · iam 이면 AWS
// 설정 적재)은 망을 타지 않지만 무엇을 읽든 기동이 끝없이 걸리지 않게 묶는다(C1 보안 검수 L3). janitor 의 기동 단계 상한과
// 같은 값이다.
const dbStepTimeout = 20 * time.Second

// indexerPoolMaxConns 는 인덱서 풀의 연결 상한이다(kty 결정 ⑤). 산식 = 핫 경로 3(조각 INSERT · 틱 장부 — M5 근거
// 「둘 이상」에 여유 하나) + 회차 조회 동시 2(제어면) + 4D 1(종료 신호 적용 — 설계 4절). pgx 기본값은 max(4, CPU 수)라
// 상자 크기에 따라 달라지므로 못 박는다.
//
// 산식 밖의 소비자도 이 풀을 쓴다 — 60일 만료(index.DeleteExpired · 한 번에 하나), 업로드 확정(index.NewUploadStore),
// 상태판 적재(재기동 때 한 번 · newPlaybackSeed). 3 · 2 · 1 은 몫별 예약이 아니라 합의 상한이다 — 만료가 연결 하나를
// 잡고 있으면 핫 경로가 쓸 수 있는 것은 2 다. 최대 동시 수요는 7(산식 6 + 만료 1)이라 상한 6 을 하나 넘을 수 있다.
// 넘친 요청은 풀 획득을 기다린다 — 회차 조회는 2초 시한(control.DefaultRoundLimits) 안에 못 잡으면 503 이고, 교착은
// 아니다(잡은 연결은 각자 끝나면 돌려준다).
//
// 발행자 풀은 따로이고 pgx 기본값 그대로다 — 둘의 합이 media_indexer 역할의 CONNECTION LIMIT 20 안이어야 한다
// (infra/terraform/prod/runtime/sql/10_roles.sql). 상한을 30 으로 올리는 것(kty 결정 C1)은 M6-6b 의 10_roles.sql 몫이고,
// 발행자 풀을 4 로 고정하고 env 로 조정하게 하는 것(kty 결정 C2)은 M6-8 배선 ④ 몫이다(media/README.md 「사이드카 DB
// 연결 풀」).
const indexerPoolMaxConns = 6

// openIndexerPool 은 인덱서 풀을 만들고 연결 하나로 Ping 해 db_connected 한 줄을 남긴다(배포 확인의 판정 줄). 풀 설정
// 조립과 연결 확인에는 각각 stepTimeout 상한이 선다(운영 값 dbStepTimeout — 시험은 짧게 준다). 둘째 반환값은
// 풀 설정 원본이다 — 발행자 풀은 이것의 Copy 로 만든다(같은 사용자 · 같은 토큰 함수 · AWS 설정 적재 한 번). 연결 확인이
// 실패하면 만든 풀을 닫고 오류다 — 사이드카는 기동 실패로 끝나고 재기동이 회복한다. 오류에 비밀번호 · DSN 은 싣지 않는다.
func openIndexerPool(ctx context.Context, s pgconf.Settings, log *slog.Logger, stepTimeout time.Duration) (*pgxpool.Pool, *pgxpool.Config, error) {
	cctx, cancel := context.WithTimeout(ctx, stepTimeout)
	pc, err := pgconf.PoolConfig(cctx, s)
	cancel()
	if err != nil {
		return nil, nil, err
	}
	pool, err := pgxpool.NewWithConfig(ctx, indexerPoolConfig(pc))
	if err != nil {
		return nil, nil, fmt.Errorf("DB 연결 풀 생성 실패: %w", err)
	}
	cctx, cancel = context.WithTimeout(ctx, stepTimeout)
	err = pgconf.ConfirmConnection(cctx, pool, s, log, "indexer")
	cancel()
	if err != nil {
		pool.Close()
		return nil, nil, err
	}
	return pool, pc, nil
}

// indexerPoolConfig 는 pc 의 Copy 에 인덱서 풀 상한을 건다. pc(발행자 풀 원본)는 그대로 둔다.
func indexerPoolConfig(pc *pgxpool.Config) *pgxpool.Config {
	ic := pc.Copy()
	ic.MaxConns = indexerPoolMaxConns
	return ic
}
