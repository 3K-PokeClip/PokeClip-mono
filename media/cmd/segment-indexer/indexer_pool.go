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

// indexerPoolMaxConns 는 인덱서 풀의 연결 상한이다(kty 결정 ⑤). 산식 = 핫 경로 3 + 회차 조회 동시 2(제어면) + 4D 1(종료
// 신호 적용 — 설계 4절). 핫 경로 3 은 연결을 쥐는 고루틴 셋이다 — 메인 루프 1(조각 INSERT · 틱 장부 · 회차 종료 전이
// endLive · endGapRun · endStallSeal 을 차례로), 업로드 워커 1(업로드 확정 — index.NewUploadStore), 스위퍼 1
// (PendingUploads · CountBacklog). pgx 기본값은 max(4, CPU 수)라 상자 크기에 따라 달라지므로 못 박는다.
//
// 산식은 몫별 예약이 아니라 합의 상한이다. 산식 밖에서도 이 풀을 쓰는 쪽이 있다.
//
//   - 60일 만료(M6-4) — 장부 걸음(MarkExpired · ExpiryRows · ExpiryDocuments · ExpiryCutoffValid · DeleteExpired)을 차례로
//     돌아 동시 1 이다.
//   - 정산 차선(M6-3b) — 정산 스캔 1, 회차 정산 걸음(finalize.DefaultLimits — 전체 2 · 같은 스트림 1 · VOD 동결 1), 상태
//     전달 읽기(스트림마다 하나)가 차선 고루틴에서 돈다. 정산 걸음 전체 2 는 kty 2026-10-27 결정 (가)다 — 상한 6 을 두고
//     걸음 쪽을 줄였다. 같은 스트림 걸음끼리 잠금을 다투면 진 쪽이 연결을 쥔 채 기다려 핫 경로가 굶었다(M6-3b c3 db M3
//     실측 — 핫 INSERT 4.71초 대기). 이 풀을 쓰는 걸음은 빈 동결 · 사슬 닫힘 · VOD 실패 확정 · ended 표시라 정산 걸음의
//     연결은 많아야 둘이다. DVR · VOD 동결(publish.FreezeDVR · FreezeVOD 등)은 발행자 풀에서 연결을 쥔다.
//   - 기동 걸음 — 스키마 보장 · 확인(EnsureSchema · requireLedgerSchema · MissingExpiryPrivileges)과 상태판 적재
//     (newPlaybackSeed)는 업로더 · 만료 · 제어면이 뜨기 전에 동기로 끝나 수요가 겹치지 않는다.
//
// 그래서 동시 수요가 6 을 넘을 수 있다. 넘친 요청은 풀 획득을 기다린다 — 교착은 아니다(잡은 연결은 각자 끝나면 돌려준다).
// 회차 조회는 획득 대기와 질의를 합친 2초 시한(control.DefaultRoundLimits)을, 4D 는 Apply 시한 1.5초를 다 쓰면 503 이다.
// 예: 회차 조회 2 · 4D 1 · 만료 1 이 모두 연결을 쥐고 있으면 핫 경로 셋이 나눠 쓸 수 있는 것은 2 다. 정산 걸음 2 와 정산 스캔
// 1 까지 겹치면 핫 경로 몫은 0 이라 핫 경로도 획득을 기다린다(최악).
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
