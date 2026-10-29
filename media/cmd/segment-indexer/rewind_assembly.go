package main

// 되감기 발행 몫의 조립과 종료 — run 이 업로더 기동 앞에서 한 번 조립하고(체크리스트 474 A-3 · 판단 J69 · J75–J77 · J85 ⑵), 루프가
// 돌아온 뒤 defer 로 멈춘다(A-3 3 · 판단 J74). 흐름은 rewind_loop.go, 결과 반영은 rewind_publish.go 다.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/s3errbody"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// assemblePublishing 은 r 에 되감기 발행 몫을 채우고 발행자 풀을 돌려준다 — run 이 업로더 기동(up.Start) 앞에서 한 번 부르고 풀을
// 닫는다(종료 순서의 「발행자 풀 Close」). 발행 몫이 없으면 nil 이고 r 의 발행 몫(캐시 · 발행자 · 작업 몸체)은 비어 있다. 차선은
// run 이 조립 밖에서 채우므로 그때도 정산 · 상태 전달 차선과 완료 case 는 돈다(M6 3.2 — 루프 보조 타입의 「발행 몫 없음」).
//
//	S3_BUCKET 이 빔  조립하지 않는다 · INFO rewind_publish_disabled(판단 J69 — 팀 공용 compose 의 기본 형상)
//	조립 실패        발행 몫 전체를 버린다 · ERROR rewind_publish_degraded(판단 J77 · ADR-063 결정 1 — 업로드 · 인덱싱은 그대로).
//	                 캐시도 끼우지 않는다 — 차선 없는 캐시는 스스로 연 적재 요구를 영구히 쌓는다
//	조립 성공        캐시를 인덱서와 r 두 자리에 끼우고(같은 캐시) 업로더에 Dirty 집합을 붙인 뒤 차선 · 작업 몸체 · 손잡이 · 주기를
//	                 r 에 채운다
//
// 순서가 계약이다. SetRewindCache 는 루프가 돌기 전 이 고루틴에서 한 번이다 — 루프가 도는 중 다른 고루틴에서 부르면 인덱서의 캐시
// 칸이 경합한다. AttachDirty 는 업로더 워커가 뜨기(Start) 전이다 — 뒤에 부르면 조용히 아무것도 하지 않아 ③ · init 결과가 루프에
// 닿지 않는다. forgetSession(끝난 회차의 업로더 메모리 — 판단 J86)도 발행 몫과 함께 채운다: 버킷이 비면 업로더가 비활성이라
// 어차피 할 일이 없다.
func assemblePublishing(ctx context.Context, cfg config.Config, db *pgxpool.Config, up *upload.Uploader, r *rewindLoop) *pgxpool.Pool {
	if cfg.S3.Bucket == "" {
		r.log.Info("rewind_publish_disabled", "note", "S3_BUCKET 미설정 — 되감기 목록을 발행하지 않는다. 4.1 종료 전이는 그대로 돈다")
		return nil
	}
	opt := publishOptions(cfg, r.log)
	pub, pool, endpoint, err := newPublisher(ctx, cfg, db, opt)
	if err != nil {
		r.log.Error("rewind_publish_degraded", "bucket", cfg.S3.Bucket, "region", cfg.S3.Region,
			"endpoint", endpoint, "err", err,
			"note", "되감기 발행 몫만 강등된다 — 업로드 · 인덱싱은 계속되고 회복은 재기동이다")
		return nil
	}
	c := &cache.Cache{} // 튜너블은 영값 = 기본값이다(판단 J87 ⑶)
	r.ix.SetRewindCache(c)
	up.AttachDirty()
	dirty := up.Dirty()
	r.cache, r.pub = c, pub // 차선은 run 이 조립 밖에서 채운다(M6 3.2 — 꺼짐 · 강등에서도 정산 차선이 돈다)
	r.work = laneWork{tick: pub.Tick, renew: pub.RenewFence, load: pub.LoadLedger, watch: pub.Watch, seal: pub.Endlist}
	r.peekDirty, r.ackDirty = dirty.Peek, dirty.Ack
	r.expedite, r.forgetSession = up.Expedite, up.ForgetSession
	r.reconnectWindow, r.watchEvery, r.holdEvery = cfg.ReconnectWindow, cfg.RewindWatchEvery, cfg.Indexer.HoldTick
	r.lazyRenewAfter = opt.LazyRenewAfter
	return pool
}

// publishOptions 는 발행자 설정이다 — 설계값(publish.DefaultOptions)에 설정에서 오는 것을 얹는다.
func publishOptions(cfg config.Config, log *slog.Logger) publish.Options {
	opt := publish.DefaultOptions(newWriterToken(), cfg.RewindPublicBaseURL)
	opt.Log = log
	// SEED_ALARM_AFTER 는 재스캔 주기의 두 배 이상이어야 한다(점멸 방지 — 판단 J85 ⑵ · ArmFallback 산식 선례). 재스캔 주기는 env 로
	// 바뀌고 설계값(10분)은 기본 재스캔 5분의 두 배다.
	opt.SeedAlarmAfter = max(opt.SeedAlarmAfter, 2*cfg.Watcher.RescanEvery)
	// L2′ 봉인 문턱은 스위치가 켜질 때만 D_act 다(REWIND_STALL_SEAL — kty K5 기본 끔). 0 이면 사다리가 봉인을 내지 않는다.
	if cfg.RewindStallSeal {
		opt.StallSealAfter = opt.DAct()
	}
	return opt
}

// newPublisher 는 opt 로 도는 발행자와 그 풀을 만든다(판단 J76 · J77) — S3 클라이언트(업로더와 따로) → https 검사(실효
// 엔드포인트) → 부팅 버킷 확인 → 발행자 풀(인덱서 풀과 따로) → 발행자. 실패하면 만든 풀을 닫고 오류를 돌려준다(재시도 없음 —
// 재기동이 회복한다). S3 클라이언트에는 닫는 손잡이가 없다 — 버킷 확인이 남긴 유휴 연결은 SDK 전송의 유휴 시한이 닫는다. 성공하면
// INFO rewind_publish_started 한 줄이다: 자격증명은 provider 이름만 싣고(G14), 베이스 URL 을 함께 싣는다 — 루프백 기본값이
// 운영에 실리면 시청자 브라우저가 자기 컴퓨터를 부르므로 기동 로그로 바로 보이게 한다. writer 는 이 프로세스의 fence 토큰이다
// (DB 의 writer_fence 와 맞춰 볼 수 있게). 셋째 반환값은 켜짐 · 강등 줄의 endpoint 칸이다 — 클라이언트의 실효 엔드포인트에서
// userinfo 를 뗀 값이고(7c 검수 C7c-1), 클라이언트를 만들기 전에 실패하면 설정값이다.
func newPublisher(ctx context.Context, cfg config.Config, db *pgxpool.Config, opt publish.Options) (*publish.Publisher, *pgxpool.Pool, string, error) {
	awsCfg, client, err := newPublishS3Client(ctx, cfg)
	if err != nil {
		return nil, nil, cfg.S3.Endpoint, err
	}
	ep := aws.ToString(client.Options().BaseEndpoint)
	endpoint := endpointForLog(ep)
	if err := requireHTTPS(ep); err != nil {
		return nil, nil, endpoint, err
	}
	provider, err := checkBucket(ctx, awsCfg, client, cfg.S3.Bucket, opt.PublishTimeout)
	if err != nil {
		return nil, nil, endpoint, err
	}
	// 발행자 풀은 인덱서 풀과 따로다(판단 J76) — 문장마다 락을 최대 10초 기다리는 동안 연결을 잡으므로 인덱싱 · 4.1 전이와 연결을
	// 다투지 않게 한다. pgx 기본값(MaxConns · Deadline 처리기 · CancelRequest)은 그대로다 — 되돌림 뒤 잠금 해제가 거기에 기댄다.
	pool, err := pgxpool.NewWithConfig(ctx, db)
	if err != nil {
		return nil, nil, endpoint, fmt.Errorf("발행자 DB 연결 풀 생성 실패: %w", err)
	}
	pub, err := publish.New(pool, publish.NewS3Store(client, cfg.S3.Bucket), opt)
	if err != nil {
		pool.Close()
		return nil, nil, endpoint, err
	}
	opt.Log.Info("rewind_publish_started", "bucket", cfg.S3.Bucket, "region", cfg.S3.Region,
		"endpoint", endpoint, "provider", provider, "base_url", opt.BaseURL, "writer", opt.Writer)
	return pub, pool, endpoint, nil
}

// newPublishS3Client 는 발행용 S3 클라이언트(업로더와 따로)와 그 AWS 설정을 만든다 — 설정은 부팅 버킷 확인이 자격증명 provider
// 이름을 읽는 데 쓴다. newPublisher 에서 뗀 것은 시험이 이 조립점의 클라이언트를 직접 부르게 하려서다(부팅 때 닿는 요청은 본문
// 없는 HEAD 뿐이다).
func newPublishS3Client(ctx context.Context, cfg config.Config) (aws.Config, *s3.Client, error) {
	loadOpts := []func(*awsconfig.LoadOptions) error{
		awsconfig.WithRegion(cfg.S3.Region),
		awsconfig.WithRetryMaxAttempts(1), // 버킷 확인은 한 번이다 — 목록 요청의 시도 수는 S3Store 가 요청마다 고정한다
	}
	if cfg.S3.Endpoint != "" {
		loadOpts = append(loadOpts, awsconfig.WithBaseEndpoint(cfg.S3.Endpoint))
	}
	awsCfg, err := awsconfig.LoadDefaultConfig(ctx, loadOpts...)
	if err != nil {
		return aws.Config{}, nil, fmt.Errorf("AWS 설정 적재 실패: %w", err)
	}
	// 오류 응답 본문은 상한까지만 읽는다(s3errbody — 업로더와 같다).
	client := s3.NewFromConfig(awsCfg, func(o *s3.Options) { o.UsePathStyle = cfg.S3.ForcePathStyle }, s3errbody.Limit)
	return awsCfg, client, nil
}

// requireHTTPS 는 발행용 S3 클라이언트의 실효 엔드포인트(client.Options().BaseEndpoint)가 https 인지 본다(판단 J77 ⑵ · 계획
// 커밋 순서 7 〔r50〕 ② · 7c 검수 C7c-1). 실효 값은 S3_ENDPOINT 만이 아니라 SDK 표준 설정(AWS_ENDPOINT_URL_S3 ·
// AWS_ENDPOINT_URL · 공유 설정 endpoint_url)에서도 온다 — 설정값만 보면 그 길로 평문이 들어온다. 비었으면 SDK 기본(AWS S3 ·
// https)이라 통과다. 설정은 http 도 받는다(업로더의 MinIO 리그) — 목록 쓰기만 막는다: 쓰기 울타리가 서버의 조건부 쓰기에만
// 기대므로 목록 요청을 평문 경로에 두지 않는다. userinfo 가 든 값도 막는다 — S3_ENDPOINT 는 config 가 거르지만 SDK 표준
// 설정은 거르지 않는다(7a B-9 규칙 — 오류 문구에 원문을 싣지 않는다). 호스트가 없는 값도 막는다 — `//` 가 빠진 꼴
// (`https:user:pw@host`)은 url.Parse 가 User 를 비워 userinfo 검사를 비켜 간다(종결 라운드 1 CL1-1 · 보안 L-1).
func requireHTTPS(endpoint string) error {
	if endpoint == "" {
		return nil
	}
	u, err := url.Parse(endpoint)
	if err != nil || u.Scheme != "https" {
		return errors.New("발행용 S3 엔드포인트가 https 가 아니다 — 발행용 S3 클라이언트는 https 로만 조립한다")
	}
	if u.Hostname() == "" {
		return errors.New("발행용 S3 엔드포인트에 호스트가 없다 — https://호스트 꼴이어야 한다")
	}
	if u.User != nil {
		return errors.New("발행용 S3 엔드포인트에 자격증명(user:pass@)이 들어 있다 — 자격증명은 AWS_ACCESS_KEY_ID 등 env 로 전달하라")
	}
	return nil
}

// endpointForLog 는 실효 엔드포인트를 로그 칸에 싣는 꼴이다 — userinfo 를 뗀다(S3_ENDPOINT · 7a B-9 규칙). URL 로 읽지
// 못하는 값과 호스트가 없는 값(opaque 꼴 — userinfo 를 뗄 수 없다)은 원문 대신 표식이다. 빈 값(SDK 기본)은 빈 칸이다.
func endpointForLog(endpoint string) string {
	if endpoint == "" {
		return ""
	}
	u, err := url.Parse(endpoint)
	if err != nil {
		return "(URL 로 읽지 못함)"
	}
	if u.Host == "" {
		return "(호스트 없는 URL)"
	}
	u.User = nil
	return u.String()
}

// checkBucket 은 부팅 때 버킷을 한 번 확인하고 자격증명 provider 이름을 돌려준다(판단 J77 ⑶ · R4-9 처방 3). 시한은 timeout 하나다
// (PublishTimeout — 저장소 요청 한 번의 예산 · 새 값 0). HeadBucket 은 s3:ListBucket 권한이 필요하다.
func checkBucket(ctx context.Context, awsCfg aws.Config, client *s3.Client, bucket string, timeout time.Duration) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	creds, err := awsCfg.Credentials.Retrieve(ctx)
	if err != nil {
		return "", fmt.Errorf("자격증명을 얻지 못했다: %w", err)
	}
	if _, err := client.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: aws.String(bucket)}); err != nil {
		return "", fmt.Errorf("버킷 확인(HeadBucket) 실패: %w", err)
	}
	return creds.Source, nil
}

// newWriterToken 은 이 프로세스의 fence 토큰이다(판단 J75 · 계획 커밋 순서 7 〔r52〕) — crypto/rand 16바이트의 16진 32자이고 부팅마다
// 새로 만든다. 호스트 · 파드 이름은 쓰지 않는다 — 재기동한 같은 파드가 같은 토큰을 쓰면 fence 가 두 프로세스를 가르지 못한다.
func newWriterToken() string {
	b := make([]byte, 16)
	rand.Read(b) // 오류를 돌려주지 않는다 — 난수원이 실패하면 프로세스가 끝난다(crypto/rand 문서).
	return hex.EncodeToString(b)
}

// stop 은 루프가 돌아온 뒤 발행 몫을 멈춘다 — run 이 발행자 풀 Close 보다 뒤에 defer 로 등록한다(LIFO 라 먼저 돈다). 차선 ctx 를
// 끊고 · 떠 있는 작업을 기다린 뒤 · fence 를 반납한다(stopPublishing). 반납이 실패하면 ERROR rewind_fence_release_failed 한 줄을
// 남긴다 — 후임 writer 는 lease 가 끝날 때까지 그 회차 목록을 쓰지 못한다. 발행 몫이 없으면 반납할 fence 가 없어 차선을 끊고
// 떠 있는 정산 작업이 끝나기를 기다리기만 한다(D24 — 풀이 닫히기 전에 정산 문장이 끝난다).
func (r *rewindLoop) stop(ctx context.Context, cancelLanes context.CancelFunc) {
	var release func(context.Context) error
	if r.publishing() {
		release = r.pub.Release
	}
	if err := stopPublishing(ctx, cancelLanes, r.lanes, release); err != nil {
		r.log.Error("rewind_fence_release_failed", "err", err,
			"note", "fence 를 반납하지 못했다 — 후임 writer 는 lease 가 끝난 뒤 그 회차 목록을 이어 쓴다")
	}
}
