package broadcast

// 발행 고루틴(설계 M6 15.5 · 15.6 · 15.8). outbox 의 커밋된 pending 행을 2초마다 골라 resolve(started 만) → 봉투 →
// SNS 로 낸다. 바깥 호출 둘(resolve · publish)은 함수 칸이다 — 조립(cmd)이 keyresolve.Client.Resolve 와 SNS 어댑터를
// 끼운다. resolve 는 어떤 트랜잭션 밖에서도 부른다(D46).

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// 발행기 수치(15.5).
const (
	defaultInterval  = 2 * time.Second  // 폴링 주기
	batchSize        = 20               // 한 번에 고르는 행 수
	retryBase        = time.Second      // 백오프 첫 값(1–2–4초 …)
	retryCap         = 60 * time.Second // 백오프 상한(무한 재시도)
	backlogAge       = 60 * time.Second // 가장 오래된 pending 이 이보다 늙으면 lifecycle_backlog
	settledAge       = 7 * 24 * time.Hour
	cleanupEvery     = time.Hour
	resolveTimeout   = 5 * time.Second  // resolve 한 번의 시한 — keyresolve 는 ctx 하나로만 시한을 건다
	publishTimeout   = 10 * time.Second // SNS Publish 한 번의 시한
	reasonUnwired    = "resolve_unwired"
	reasonInvalid    = "streamer_invalid"
	reasonNoStreamer = "streamer_missing"
)

// Message 는 SNS FIFO 로 내는 편지 하나다(계약9 2절 — MessageGroupId = streamId · MessageDeduplicationId = eventId).
type Message struct {
	GroupID         string
	DeduplicationID string
	Body            []byte
}

// PublisherOptions 는 NewPublisher 의 설정이다.
type PublisherOptions struct {
	// Resolve 는 계약4 4C 다(keyresolve.Client.Resolve) — 토큰(stream_id)으로 userId 를 얻는다. nil 이면(resolve 설정이
	// 없는 판) started 를 낼 수 없어 미발행 + ERROR 로 버린다(K10).
	Resolve func(ctx context.Context, token string) (keyresolve.Verdict, error)
	// Publish 는 SNS 로 편지 하나를 낸다. 필수다. 오류면 결과 모름으로 보고 같은 eventId 로 다시 낸다(D35).
	Publish func(ctx context.Context, m Message) error
	// Log 는 신호(15.8)를 받는다. nil 이면 버린다.
	Log *slog.Logger
	// Interval 은 폴링 주기다. 0 이면 2초.
	Interval time.Duration
}

// Publisher 는 outbox 를 비우는 발행 고루틴이다. Run 하나만 돌린다(내부 상태는 그 고루틴 전용이다).
type Publisher struct {
	pool        *pgxpool.Pool
	opt         PublisherOptions
	log         *slog.Logger
	warned      map[string]bool // lifecycle_publish_retry 를 이미 찍은 eventId(같은 행 연속은 한 줄)
	backlogOn   bool
	lastCleanup time.Time
}

// NewPublisher 는 풀과 발행 칸이 없으면 거부한다.
func NewPublisher(pool *pgxpool.Pool, o PublisherOptions) (*Publisher, error) {
	if pool == nil {
		return nil, errors.New("broadcast: NewPublisher 에 풀이 없다")
	}
	if o.Publish == nil {
		return nil, errors.New("broadcast: NewPublisher 에 발행 칸(Publish)이 없다")
	}
	if o.Interval <= 0 {
		o.Interval = defaultInterval
	}
	log := o.Log
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	return &Publisher{pool: pool, opt: o, log: log, warned: map[string]bool{}}, nil
}

// Run 은 ctx 가 끝날 때까지 Interval 마다 outbox 를 비운다. 첫 회차는 곧바로 돈다. 돌려주는 값은 ctx 의 오류다. 진행
// 중 Publish 가 ctx 로 끊기면 그 행은 pending 그대로다(다음 기동이 같은 eventId 로 다시 낸다 — D35 · D47). 부르는
// 쪽은 풀을 닫기 전에 ctx 를 끝내고 Run 이 돌아오기를 기다린다.
func (p *Publisher) Run(ctx context.Context) error {
	t := time.NewTimer(0)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.C:
		}
		p.tick(ctx)
		t.Reset(p.opt.Interval)
	}
}

// tick 은 한 회차다 — 대상 행을 차례로 내고, 적체 게이지와 정리를 돈다.
func (p *Publisher) tick(ctx context.Context) {
	rows, err := selectDue(ctx, p.pool, batchSize)
	if err != nil {
		p.storeFailed(ctx, "select", err)
		return
	}
	for _, r := range rows {
		if ctx.Err() != nil {
			return
		}
		p.handle(ctx, r)
	}
	p.checkBacklog(ctx)
	p.cleanup(ctx)
}

// handle 은 행 하나를 낸다 — streamerId 확보 → 봉투 → 발행 → 기록.
func (p *Publisher) handle(ctx context.Context, r outboxRow) {
	if r.streamerID == "" {
		var ok bool
		if r.streamerID, ok = p.streamerFor(ctx, r); !ok {
			return
		}
	}
	body, err := Envelope{
		SchemaVersion: SchemaVersion, EventID: r.eventID, EventType: r.eventType, OccurredAt: r.occurredAt,
		StreamID: r.round, StreamerID: r.streamerID, IngestStreamID: r.ingest, Sequence: r.sequence, Payload: r.payload,
	}.Encode()
	if err != nil {
		p.abandon(ctx, r, abandonReason(err))
		return
	}
	pctx, cancel := context.WithTimeout(ctx, publishTimeout)
	err = p.opt.Publish(pctx, Message{GroupID: r.round, DeduplicationID: r.eventID, Body: body})
	cancel()
	if ctx.Err() != nil {
		return // 끊긴 발행은 행을 그대로 둔다(D47)
	}
	if err != nil {
		p.retry(ctx, r, snsErrorKind(err))
		return
	}
	if err := markPublished(ctx, p.pool, r.eventID); err != nil {
		p.storeFailed(ctx, "published", err)
		return
	}
	delete(p.warned, r.eventID)
	p.log.Info("lifecycle_published", "event_id", r.eventID, "attempts", r.attempts+1,
		"lag_ms", time.Since(r.occurredAt).Milliseconds())
}

// streamerFor 는 streamerId 가 빈 행의 값을 정한다. started 는 resolve 로 얻어 행에 남기고, ended 는 published started
// 의 값이어야 하므로(D62) 비었으면 버린다. 못 얻으면 거짓이다 — 버렸거나 재시도로 미뤘다.
func (p *Publisher) streamerFor(ctx context.Context, r outboxRow) (string, bool) {
	if r.sequence != 1 {
		p.abandon(ctx, r, reasonNoStreamer)
		return "", false
	}
	if p.opt.Resolve == nil {
		p.abandon(ctx, r, reasonUnwired)
		return "", false
	}
	if !streamkey.IsToken(r.ingest) {
		p.abandon(ctx, r, reasonInvalid) // auth 가 답할 수 없는 stream_id 다
		return "", false
	}
	rctx, cancel := context.WithTimeout(ctx, resolveTimeout)
	v, err := p.opt.Resolve(rctx, r.ingest)
	cancel()
	if ctx.Err() != nil {
		return "", false
	}
	if err != nil {
		p.resolveFailed(ctx, r, err)
		return "", false
	}
	if !v.Valid {
		p.abandon(ctx, r, reasonInvalid) // REVOKED · NOT_FOUND · MALFORMED — 추측 · 캐시하지 않는다(K10)
		return "", false
	}
	streamer := strconv.FormatInt(v.UserID, 10)
	if err := recordStreamer(ctx, p.pool, r.eventID, streamer); err != nil {
		p.storeFailed(ctx, "streamer", err)
		return "", false
	}
	return streamer, true
}

// resolveFailed 는 판단 불가(비-200 · 시한 · 연결 · 해석)다 — WARN 뒤 백오프 재시도. 에러 문구는 keyresolve 가 이미
// 본문 · 토큰 · 끝점을 뺀 것이지만, 여기서는 종류와 상태 코드만 남긴다.
func (p *Publisher) resolveFailed(ctx context.Context, r outboxRow, err error) {
	kind, status := "unknown", 0
	var u *keyresolve.UndecidedError
	switch {
	case errors.As(err, &u):
		kind, status = u.Kind, u.Status
	case errors.Is(err, keyresolve.ErrInvalidPassphrase):
		kind = "invalid_passphrase"
	}
	p.log.Warn("lifecycle_resolve_failed", "event_id", r.eventID, "kind", kind, "status", status)
	lastErr := "resolve: " + kind
	if status != 0 {
		lastErr = fmt.Sprintf("resolve: %s %d", kind, status)
	}
	if err := markRetry(ctx, p.pool, r.eventID, retryDelay(r.attempts), lastErr); err != nil {
		p.storeFailed(ctx, "retry", err)
	}
}

// retry 는 발행 실패 · 불명이다 — 같은 eventId 로 백오프 재시도한다. WARN 은 그 행의 첫 실패에만 찍는다.
func (p *Publisher) retry(ctx context.Context, r outboxRow, kind string) {
	if !p.warned[r.eventID] {
		p.warned[r.eventID] = true
		p.log.Warn("lifecycle_publish_retry", "event_id", r.eventID, "attempt", r.attempts+1, "err", kind)
	}
	if err := markRetry(ctx, p.pool, r.eventID, retryDelay(r.attempts), kind); err != nil {
		p.storeFailed(ctx, "retry", err)
	}
}

// abandon 은 행을 버리고(started 면 같은 회차 pending 도) 그 트랜잭션이 커밋된 뒤에 ERROR 를 남긴다.
func (p *Publisher) abandon(ctx context.Context, r outboxRow, reason string) {
	if _, err := abandon(ctx, p.pool, r, reason); err != nil {
		p.storeFailed(ctx, "abandon", err)
		return
	}
	delete(p.warned, r.eventID)
	p.log.Error("lifecycle_abandoned", "event_id", r.eventID, "reason", reason)
}

// storeFailed 는 outbox 문장 실패다 — 행은 그대로라 다음 회차가 다시 고른다. ctx 가 끝나서 난 실패는 찍지 않는다.
func (p *Publisher) storeFailed(ctx context.Context, stage string, err error) {
	if ctx.Err() != nil {
		return
	}
	p.log.Warn("lifecycle_store_failed", "stage", stage, "err", err)
}

// retryDelay 는 시도 횟수에 따른 백오프다 — 1 · 2 · 4초 … 상한 60초.
func retryDelay(attempts int) time.Duration {
	if attempts >= 6 {
		return retryCap
	}
	return min(retryBase<<attempts, retryCap)
}
