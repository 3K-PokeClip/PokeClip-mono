package main

import (
	"context"
	"log/slog"
	"strings"
	"sync"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/expiry"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/secretfile"
)

// expiryGate 는 설정 · 발행 몫만으로 정하는 꺼짐 사유다(빈 값 = 다음 확인으로). 주소가 비면 롤백 스위치, 버킷이 없거나
// 발행 몫이 강등이면 만료도 켜지 않는다(설계 M6 3.2 — 만료는 꺼짐 · 강등 모두 켜지 않는다).
func expiryGate(cfg config.Config, publishing bool) string {
	switch {
	case cfg.Expiry.ClipURL == "":
		return "no_url"
	case cfg.S3.Bucket == "":
		return "no_bucket"
	case !publishing:
		return "publish_degraded"
	}
	return ""
}

// expiryToken 은 만료 창구 토큰이다 — 값(CLIP_EXPIRY_TOKEN)이 있으면 그것, 파일이 있으면 secretfile 규칙으로 읽은 한 줄,
// 둘 다 없으면 영값(clip 이 401 로 답하고 expiry_stalled 가 드러낸다). 둘 다인 설정은 config 가 이미 거부했다.
func expiryToken(cfg config.Config) (expiry.Token, error) {
	if cfg.Expiry.HasToken {
		return cfg.Expiry.Token, nil
	}
	if cfg.Expiry.TokenFile == "" {
		return expiry.Token{}, nil
	}
	raw, err := secretfile.Read("CLIP_EXPIRY_TOKEN_FILE", cfg.Expiry.TokenFile, secretfile.Options{ShowPath: true})
	if err != nil {
		return expiry.Token{}, err
	}
	return expiry.NewToken(raw), nil
}

// startExpiry 는 60일 만료 고루틴을 조립해 띄우고 멈추는 함수를 돌려준다. 켜지 않으면 사유 한 줄만 내고 아무것도 띄우지
// 않는다 — 실패는 강등이다(만료만 꺼지고 인덱싱 · 업로드는 그대로 — ADR-063 결정 1).
//
// 확인 순서: 주소 · 버킷 · 발행 몫(expiryGate) → 운영 권한(index.MissingExpiryPrivileges — 하나라도 없으면 ERROR · 끔,
// DELETE 42501 로 반쯤 지운 상태를 막는 fail-closed · D50) → 토큰 → S3 클라이언트. changed 는 상태 표지 받은편지함의
// Changed 다(session.PlaybackInbox — 보내는 길은 하나).
//
// 돌려준 함수는 고루틴의 ctx 를 끝내고 Run 이 돌아오기를 기다린다. run 은 이것을 풀 Close 보다 뒤에 defer 해 먼저
// 부른다 — 진행 중인 행 삭제가 닫힌 풀을 쓰지 않는다(D24).
func startExpiry(ctx context.Context, cfg config.Config, pool *pgxpool.Pool, publishing bool, changed index.PlaybackChanged, log *slog.Logger) func() {
	off := func() {}
	if reason := expiryGate(cfg, publishing); reason != "" {
		log.Info("expiry_disabled", "reason", reason)
		return off
	}
	missing, err := index.MissingExpiryPrivileges(ctx, pool)
	if err != nil {
		log.Error("expiry_disabled", "reason", "privilege_check", "err", err)
		return off
	}
	if len(missing) > 0 {
		log.Error("expiry_disabled", "reason", "privilege", "missing", strings.Join(missing, ","),
			"note", "운영 권한(30_grants.sql)이 적용된 뒤에만 만료를 켠다 — 재기동이 다시 본다")
		return off
	}
	token, err := expiryToken(cfg)
	if err != nil {
		log.Error("expiry_disabled", "reason", "token_file", "err", err)
		return off
	}
	_, client, err := newPublishS3Client(ctx, cfg)
	if err != nil {
		log.Error("expiry_disabled", "reason", "s3_client", "err", err)
		return off
	}
	opt := expiry.DefaultOptions()
	opt.ClipURL, opt.Token, opt.Changed, opt.Log = cfg.Expiry.ClipURL, token, changed, log
	e, err := expiry.New(expiry.NewPGLedger(pool), expiry.NewS3Objects(client, cfg.S3.Bucket), opt)
	if err != nil {
		log.Error("expiry_disabled", "reason", "assembly", "err", err)
		return off
	}
	runCtx, cancel := context.WithCancel(ctx)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		_ = e.Run(runCtx) // 돌려주는 값은 ctx 의 오류뿐이다
	}()
	log.Info("expiry_started", "clip", cfg.Expiry.ClipURL, "bucket", cfg.S3.Bucket, "interval", opt.Interval)
	return func() {
		cancel()
		wg.Wait()
	}
}
