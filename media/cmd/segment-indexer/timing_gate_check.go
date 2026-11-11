package main

// 기동 전 검사 명령 timing-gate-check(POK-196 PDT 설계 r7 9.4) — Media 상자 시작 스크립트가 사이드카 이미지 라벨에 판 2 가
// 없을 때(라벨 없는 출시 이미지 포함) compose 서비스 timing-preflight 로 한 번 돌린다. 사이드카와 같은 DB 환경(POSTGRES_*)으로
// 게이트를 읽어, 그 사이드카의 판 목록(SIDECAR_TIMING_READABLE)으로 띄워도 되는지 종료 코드로 답한다. 장부에는 쓰지 않는다.

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os/signal"
	"syscall"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgconf"
)

// timingGateCheckCommand 는 기동 전 검사 하위 명령 이름이다 — compose 서비스 timing-preflight 의 entrypoint 둘째 낱말이다.
// 이 이름을 모르는 이미지(c0 하한 아래)는 인자를 보지 않고 사이드카로 기동하려다 녹화 루트가 없어 0 아닌 코드로 끝난다
// (timing-preflight 는 볼륨을 붙이지 않는다) — 시작 스크립트는 그것도 막힘으로 읽는다(fail-closed).
const timingGateCheckCommand = "timing-gate-check"

// timingReadableEnv 는 검사할 사이드카 이미지의 판 목록이다(그 이미지 라벨 com.pokeclip.timing.readable 의 값 — 라벨이
// 없으면 시작 스크립트가 null 을 넘긴다). 꼴은 index.ParseTimingVersions 다.
const timingReadableEnv = "SIDECAR_TIMING_READABLE"

// 검사 명령의 종료 코드(시작 스크립트가 0 만 통과로 읽는다).
const (
	// gateCheckPass 는 게이트의 래치 · 스위치가 모두 사이드카 판 목록 안이라는 뜻이다.
	gateCheckPass = 0
	// gateCheckRefused 는 게이트가 사이드카가 읽지 못하는 판을 요구한다는 뜻이다(timing_downgrade_refused).
	gateCheckRefused = 3
	// gateCheckUnchecked 는 검사를 끝내지 못했다는 뜻이다 — 인자 · 판 목록 꼴 밖, DB 설정 · 연결 · 읽기 실패(42501 포함).
	// 이 갈래는 하한 아래 이미지에만 오므로 DB 장애 때 막히는 것도 하한 아래 상자뿐이다(설계 9.4 기동 전 검사 3).
	gateCheckUnchecked = 4
)

// runTimingGateCheck 는 검사 명령 본체다. args 는 하위 명령 이름 뒤의 인자(없어야 한다)다. 판정 줄은 out 에 JSON 으로 낸다.
func runTimingGateCheck(args []string, getenv func(string) string, out io.Writer) int {
	log := slog.New(slog.NewJSONHandler(out, nil))
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	if len(args) != 0 {
		log.Error("timing_gate_check_failed", "reason", "usage", "note", "인자를 받지 않는다")
		return gateCheckUnchecked
	}
	readable, err := index.ParseTimingVersions(getenv(timingReadableEnv))
	if err != nil {
		log.Error("timing_gate_check_failed", "reason", "readable", "err", err)
		return gateCheckUnchecked
	}
	settings, err := pgconf.FromEnv(getenv)
	if err != nil {
		log.Error("timing_gate_check_failed", "reason", "settings", "err", err)
		return gateCheckUnchecked
	}
	pool, _, err := openIndexerPool(ctx, settings, log, dbStepTimeout)
	if err != nil {
		log.Error("timing_gate_check_failed", "reason", "connect", "err", err)
		return gateCheckUnchecked
	}
	defer pool.Close()
	return checkGateCode(ctx, pool, readable, log)
}

// checkGateCode 는 게이트를 한 번 읽어 판 목록 readable 의 사이드카를 띄워도 되는지 종료 코드로 낸다. 읽기는 dbStepTimeout
// 안에서 끝난다(답하지 않는 DB 에 시작 스크립트가 걸리지 않는다).
func checkGateCode(ctx context.Context, pool *pgxpool.Pool, readable []*int16, log *slog.Logger) int {
	cctx, cancel := context.WithTimeout(ctx, dbStepTimeout)
	g, err := index.ReadTimingGate(cctx, pool)
	cancel()
	if err != nil {
		log.Error("timing_gate_check_failed", "reason", "read", "err", err)
		return gateCheckUnchecked
	}
	if err := reportTimingGate(ctx, log, index.RefuseUnreadable(g, readable)); err != nil {
		return gateCheckRefused
	}
	return gateCheckPass
}

// reportTimingGate 는 기동 검사 결과 err 를 처분한다 — 거부(*index.TimingDowngradeError)면 ERROR timing_downgrade_refused 를
// 남기고 그대로 돌려준다. 읽기 실패는 줄 없이 돌려준다. 문자열 timing_downgrade_refused 는 런북(media-swap.md 롤백 하한)이
// 사이드카 로그에서 세는 계약이다.
func reportTimingGate(ctx context.Context, log *slog.Logger, err error) error {
	var refused *index.TimingDowngradeError
	if errors.As(err, &refused) {
		log.ErrorContext(ctx, "timing_downgrade_refused",
			"highest_version", index.FormatTimingVersions([]*int16{refused.Gate.Highest}),
			"new_round_version", index.FormatTimingVersions([]*int16{refused.Gate.NewRoundVersion}),
			"readable", refused.Readable,
			"note", "게이트가 이 이미지가 읽지 못하는 판을 요구한다 — 롤백 하한(media-swap.md) 이상 이미지로 띄운다")
	}
	return err
}
