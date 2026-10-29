package main

// 계약9 발행기의 기동 조립(POK-196 M6-8 커밋 ④ — 계획 4.18 ④ · 설계 M6 15.5 「발행 켜기 게이트」 · 15.7 · 7절 활성화
// 선행 조건). 게이트 하나를 만들어 돌려주고, 켜지면 발행 고루틴을 띄운다. 게이트는 레지스트리 개시 · 4D round_split 의
// started 칸과 완결 평가(ended) 칸이 같은 값을 쓴다.

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"sync"

	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/sns"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/broadcast"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
)

// startLifecycle 은 발행 게이트를 만들고, 켜지면 발행기를 띄운 뒤 (게이트, 정지 함수)를 돌려준다. 게이트가 켜지는 것은
// 주제 ARN ∧ 허용 목록 ∧ outbox 권한 ∧ 발행기 조립이 다 될 때뿐이고, 하나라도 빠지면 lifecycle_disabled 한 줄을 남기고
// 영값 게이트(꺼짐 — outbox 문장 0 · D45)다. 실패는 강등이다 — 발행만 꺼지고 인덱싱 · 업로드는 그대로 돈다(ADR-063
// 결정 1).
//
// 발행기는 인덱서 풀(pool)을 쓴다 — 문장 하나씩 차례로 돌아 연결은 많아야 하나다(풀 산식 밖 소비자 · indexer_pool.go).
// resolver 는 POK-472 조립의 4C 클라이언트 하나다(계획 4.18 커밋 ④). nil 이면 started 를 낼 수 없어 발행기가 그 회차를
// 미발행 + ERROR 로 버린다(K10) — 그래서 게이트를 켜는 것은 응답기 모드 뒤다(D42).
//
// 돌려준 정지 함수는 발행기의 ctx 를 끝내고 Run 이 돌아오기를 기다린다. run 은 이것을 풀 Close 보다 뒤에 defer 해 먼저
// 부른다 — 끊긴 발행의 기록 문장이 닫힌 풀을 쓰지 않는다(D47).
func startLifecycle(ctx context.Context, cfg config.Config, pool *pgxpool.Pool, resolver *keyresolve.Client, log *slog.Logger) (broadcast.Gate, func()) {
	off := func() {}
	lc := cfg.Lifecycle
	if lc.TopicARN == "" {
		log.Info("lifecycle_disabled", "reason", "no_topic")
		return broadcast.Gate{}, off
	}
	gate := broadcast.NewGate(lc.Streams, log)
	if !gate.Enabled() {
		log.Info("lifecycle_disabled", "reason", "no_streams")
		return broadcast.Gate{}, off
	}
	cctx, cancel := context.WithTimeout(ctx, dbStepTimeout) // 기동 DB 단계 하나 — 답하지 않는 DB 에 기동이 걸리지 않는다
	missing, err := broadcast.MissingPrivileges(cctx, pool)
	cancel()
	if err != nil {
		log.Error("lifecycle_disabled", "reason", "privilege_check", "err", err)
		return broadcast.Gate{}, off
	}
	if len(missing) > 0 {
		log.Error("lifecycle_disabled", "reason", "privilege", "missing", strings.Join(missing, ","),
			"note", "운영 권한(30_grants.sql)이 적용된 뒤에만 발행을 켠다 — 재기동이 다시 본다")
		return broadcast.Gate{}, off
	}
	publish, err := newSNSPublish(ctx, lc)
	if err != nil {
		log.Error("lifecycle_disabled", "reason", "sns_client", "err", err)
		return broadcast.Gate{}, off
	}
	o := broadcast.PublisherOptions{Publish: publish, Log: log}
	if resolver != nil {
		o.Resolve = resolver.Resolve // nil 포인터의 메서드 값은 nil 이 아니라서 갈라 넣는다
	}
	stop, err := runLifecyclePublisher(ctx, pool, o)
	if err != nil {
		log.Error("lifecycle_disabled", "reason", "assembly", "err", err)
		return broadcast.Gate{}, off
	}
	log.Info("lifecycle_started", "topic", lc.TopicARN, "streams", len(lc.Streams), "resolve", resolver != nil)
	return gate, stop
}

// newSNSPublish 는 주제 지역의 SNS 클라이언트로 발행 칸을 만든다. 자격증명은 SDK 기본 체인이다(업로더와 같다).
func newSNSPublish(ctx context.Context, lc config.LifecycleOptions) (func(context.Context, broadcast.Message) error, error) {
	awsCfg, err := awsconfig.LoadDefaultConfig(ctx, awsconfig.WithRegion(lc.TopicRegion))
	if err != nil {
		return nil, fmt.Errorf("AWS 설정 적재 실패: %w", err)
	}
	return broadcast.NewSNSPublish(sns.NewFromConfig(awsCfg), lc.TopicARN), nil
}

// runLifecyclePublisher 는 발행기를 만들어 고루틴으로 띄우고 정지 함수를 돌려준다. 정지 함수는 ctx 를 끝내고 Run 이
// 돌아오기를 기다린다 — 진행 중 Publish 는 ctx 로 끊기고 그 행은 pending 그대로다(D35 · D47).
func runLifecyclePublisher(ctx context.Context, pool *pgxpool.Pool, o broadcast.PublisherOptions) (func(), error) {
	p, err := broadcast.NewPublisher(pool, o)
	if err != nil {
		return nil, err
	}
	runCtx, cancel := context.WithCancel(ctx)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		_ = p.Run(runCtx) // 돌려주는 값은 ctx 의 오류뿐이다
	}()
	return func() {
		cancel()
		wg.Wait()
	}, nil
}
