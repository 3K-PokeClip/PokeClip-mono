package main

// 계약9 발행기의 기동 조립(POK-196 M6-8 커밋 ④ — 계획 4.18 ④ · 설계 M6 15.5 「발행 켜기 게이트」 · 15.7 · 7절 활성화
// 선행 조건). 게이트는 발행 설정 의도(주제 ARN ∧ 허용 목록 ∧ resolver)로 기동 때 한 번 정해지고, 발행기 준비(outbox
// 권한 표 · SNS 클라이언트 · 조립)가 실패해도 켜진 채다 — 준비 실패는 강등이고 30초마다 다시 준비한다(sgd 설계 2-2 ·
// 4-6 · kty-Q1 = 안 Ⅰ). 게이트는 레지스트리 개시의 started 칸과 완결 평가(ended) 칸이 같은 값을 쓴다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/sns"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/broadcast"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
)

// lifecyclePrepareRetryEvery 는 강등 중 발행기 준비를 다시 해 보는 주기다 — 단일 비행 · 주기당 1회(워처 재장착과 같은
// 유계 재시도 — ADR-063 결정 1).
const lifecyclePrepareRetryEvery = 30 * time.Second

// resolveFunc 는 계약4 4C 다(keyresolve.Client.Resolve).
type resolveFunc = func(ctx context.Context, token string) (keyresolve.Verdict, error)

// publisherSteps 는 발행기 준비의 바깥 걸음 셋과 재시도 주기다. 운영은 defaultSteps 가 채우고, 시험은 가짜 걸음을 끼운다.
type publisherSteps struct {
	privileges   func(ctx context.Context) ([]string, error)                                       // 모자란 outbox 권한
	snsPublish   func(ctx context.Context) (func(context.Context, broadcast.Message) error, error) // SNS 발행 칸
	runPublisher func(ctx context.Context, o broadcast.PublisherOptions) (func(), error)           // 발행기 기동
	every        time.Duration                                                                     // 재시도 주기
}

// startLifecycle 은 발행 게이트를 만들고 발행기를 준비한 뒤 (게이트, 정지 함수)를 돌려준다. 주제 ARN · 허용 목록 ·
// resolver 가운데 하나라도 없으면 설정으로 꺼진 것이다 — lifecycle_disabled 한 줄을 남기고 영값 게이트(outbox 문장 0 ·
// D45)를 돌려주며 준비 · 재시도를 하지 않는다. 셋이 다 있으면 startLifecycleWith 가 맡는다.
//
// 발행기는 인덱서 풀(pool)을 쓴다 — 문장 하나씩 차례로 돌아 연결은 많아야 하나다(풀 산식 밖 소비자 · indexer_pool.go).
// resolver 는 POK-472 조립의 4C 클라이언트 하나다(계획 4.18 커밋 ④). nil 이면 started 를 낼 수 없어 발행기가 모든 새
// 회차를 미발행 + ERROR 로 버리고(K10) 같은 회차 ended 도 막히므로, 게이트를 켜지 않는다(보안 A3-2). resolver 는 기동 때
// env 로 만들고 다시 읽지 않으므로 재시도로 회복하지 않는다.
//
// 돌려준 정지 함수는 재시도와 발행기를 끝내고 기다린다. run 은 이것을 풀 Close 보다 뒤에 defer 해 먼저 부른다 — 끊긴
// 발행 · 준비의 문장이 닫힌 풀을 쓰지 않는다(D47).
func startLifecycle(ctx context.Context, cfg config.Config, pool *pgxpool.Pool, resolver *keyresolve.Client, log *slog.Logger) (broadcast.Gate, func()) {
	off := func() {}
	lc := cfg.Lifecycle
	if lc.TopicARN == "" {
		log.Info("lifecycle_disabled", "reason", "no_topic")
		return broadcast.Gate{}, off
	}
	if !broadcast.NewGate(lc.Streams, nil).Enabled() {
		log.Info("lifecycle_disabled", "reason", "no_streams")
		return broadcast.Gate{}, off
	}
	if resolver == nil {
		log.Error("lifecycle_disabled", "reason", "no_resolver",
			"note", "STREAM_KEY_RESOLVE_URL · STREAM_KEY_RESOLVE_TOKEN_FILE 이 있어야 started 의 streamerId 를 얻는다")
		return broadcast.Gate{}, off
	}
	return startLifecycleWith(ctx, lc, resolver.Resolve, defaultSteps(pool, lc), log)
}

// startLifecycleWith 는 발행 의도가 켜진 설정 lc 로 게이트(NewGate(목록) — 준비 결과와 무관)를 만들고 준비를 한 번 해
// 본다. 의도 켜진 기동은 기동마다 ERROR lifecycle_degraded 기동 줄을 정확히 하나 남긴다 — 성공이면 value=0
// reason=ready, 실패면 value=1 reason=<사유>(설계 6절 계약). 실패는 강등이다: 게이트는 켜진 채 started · ended 를
// 적재하고(적재마다 권한 판정), retryPublisher 가 주기마다 다시 준비한다. 인덱싱 · 업로드는 그대로 돈다(ADR-063 결정 1).
func startLifecycleWith(ctx context.Context, lc config.LifecycleOptions, resolve resolveFunc, steps publisherSteps, log *slog.Logger) (broadcast.Gate, func()) {
	gate := broadcast.NewGate(lc.Streams, log)
	stop, reason, err := preparePublisher(ctx, steps, resolve, log)
	if err != nil {
		log.Error("lifecycle_degraded", append([]any{"value", 1, "reason", reason}, failureAttrs(err)...)...)
		return gate, retryPublisher(ctx, lc, steps, resolve, log)
	}
	log.Info("lifecycle_started", "topic", lc.TopicARN, "streams", len(lc.Streams))
	log.Error("lifecycle_degraded", "value", 0, "reason", "ready")
	return gate, stop
}

// missingPrivilegesError 는 준비의 권한 표가 모자란 outbox 권한을 찾았다는 실패다(사유 privilege).
type missingPrivilegesError struct{ missing []string }

func (e missingPrivilegesError) Error() string {
	return "outbox 권한이 모자란다: " + strings.Join(e.missing, ",")
}

// failureAttrs 는 준비 실패를 로그 진단 필드로 바꾼다 — 모자란 권한이면 missing(운영 권한 30_grants.sql 이 적용되면
// 재시도가 회복한다), 그 밖은 err 다. 진단 필드는 계약 밖이다(설계 6절).
func failureAttrs(err error) []any {
	var m missingPrivilegesError
	if errors.As(err, &m) {
		return []any{"missing", strings.Join(m.missing, ","),
			"note", "운영 권한(30_grants.sql)이 적용되면 재시도가 발행기를 띄운다"}
	}
	return []any{"err", err}
}

// preparePublisher 는 발행기 준비를 한 번 해 본다 — 권한 표 → SNS 클라이언트 → 발행기 기동. 실패면 사유
// (privilege_check · privilege · sns_client · assembly)와 오류를, 성공이면 발행기 정지 함수를 돌려준다. 발행기는 ctx 를
// 그대로 받는다 — 부르는 쪽은 시도 시한이 아니라 발행기 수명의 ctx 를 준다(P4).
func preparePublisher(ctx context.Context, steps publisherSteps, resolve resolveFunc, log *slog.Logger) (func(), string, error) {
	missing, err := steps.privileges(ctx)
	if err != nil {
		return nil, "privilege_check", err
	}
	if len(missing) > 0 {
		return nil, "privilege", missingPrivilegesError{missing}
	}
	publish, err := steps.snsPublish(ctx)
	if err != nil {
		return nil, "sns_client", err
	}
	stop, err := steps.runPublisher(ctx, broadcast.PublisherOptions{Publish: publish, Resolve: resolve, Log: log})
	if err != nil {
		return nil, "assembly", err
	}
	return stop, "", nil
}

// retryPublisher 는 첫 준비가 실패한 뒤 steps.every 마다 준비를 다시 해 보는 고루틴 하나를 띄운다. 시도는 차례로 하나씩
// 이다(단일 비행). 실패마다 WARN lifecycle_prepare_retry_failed, 성공하면 INFO lifecycle_started attempt=N 과 ERROR
// lifecycle_degraded value=0 reason=recovered 를 남기고 끝난다. 게이트는 건드리지 않는다(안 Ⅰ).
//
// 고루틴은 ctx 가 끝나거나 돌려준 정지 함수가 불리거나 회복하면 끝난다. 정지 함수는 재시도를 끊고 그 귀환을 기다린 뒤,
// 회복해서 띄운 발행기가 있으면 그것도 끊고 기다린다(D47). 회복이 정지와 겹치면 띄운 발행기를 곧바로 멈추고 회복 줄을
// 내지 않는다.
func retryPublisher(ctx context.Context, lc config.LifecycleOptions, steps publisherSteps, resolve resolveFunc, log *slog.Logger) func() {
	rctx, cancel := context.WithCancel(ctx)
	var (
		wg            sync.WaitGroup
		stopPublisher func() // 고루틴이 쓰고, 정지 함수가 wg.Wait 뒤에 읽는다
	)
	wg.Add(1)
	go func() {
		defer wg.Done()
		stopPublisher = retryUntilReady(rctx, lc, steps, resolve, log)
	}()
	return func() {
		cancel()
		wg.Wait()
		if stopPublisher != nil {
			stopPublisher()
		}
	}
}

// retryUntilReady 는 ctx 가 끝날 때까지 steps.every 마다 준비를 한 번씩 해 보고, 회복하면 발행기 정지 함수를 돌려준다
// (끝나면 nil). 발행기는 ctx(재시도 고루틴의 긴 ctx)에서 딴다.
func retryUntilReady(ctx context.Context, lc config.LifecycleOptions, steps publisherSteps, resolve resolveFunc, log *slog.Logger) func() {
	t := time.NewTimer(steps.every)
	defer t.Stop()
	for attempt := 1; ; attempt++ {
		select {
		case <-ctx.Done():
			return nil
		case <-t.C:
		}
		stop, reason, err := preparePublisher(ctx, steps, resolve, log)
		if err == nil && ctx.Err() != nil {
			stop() // 정지와 겹친 회복 — 띄운 발행기를 멈추고 줄을 내지 않는다
			return nil
		}
		if err == nil {
			log.Info("lifecycle_started", "topic", lc.TopicARN, "streams", len(lc.Streams), "attempt", attempt)
			log.Error("lifecycle_degraded", "value", 0, "reason", "recovered", "attempt", attempt)
			return stop
		}
		if ctx.Err() != nil {
			return nil
		}
		log.Warn("lifecycle_prepare_retry_failed", append([]any{"reason", reason, "attempt", attempt}, failureAttrs(err)...)...)
		t.Reset(steps.every)
	}
}

// defaultSteps 는 운영 준비 걸음이다 — 권한 표(기동 DB 단계 하나의 시한 — 답하지 않는 DB 에 준비가 걸리지 않는다) ·
// SNS 클라이언트 · 인덱서 풀 위의 발행기.
func defaultSteps(pool *pgxpool.Pool, lc config.LifecycleOptions) publisherSteps {
	return publisherSteps{
		privileges: func(ctx context.Context) ([]string, error) {
			cctx, cancel := context.WithTimeout(ctx, dbStepTimeout)
			defer cancel()
			return broadcast.MissingPrivileges(cctx, pool)
		},
		snsPublish: func(ctx context.Context) (func(context.Context, broadcast.Message) error, error) {
			return newSNSPublish(ctx, lc)
		},
		runPublisher: func(ctx context.Context, o broadcast.PublisherOptions) (func(), error) {
			return runLifecyclePublisher(ctx, pool, o)
		},
		every: lifecyclePrepareRetryEvery,
	}
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
