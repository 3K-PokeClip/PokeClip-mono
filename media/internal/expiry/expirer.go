// Package expiry 는 60일 만료(POK-196 M6-4)다 — clip 만료 창구를 폴링해 받은 방송의 구간을 S3 와 장부에서 지운다.
//
// DB 끄기 스위치(stream_expiry_switch 한 행 · 기본 끔 — POK-551)가 켜져 있을 때만 돈다. 회차 시작과 방송마다 다시 읽어,
// 끄면 재기동 없이 방송 하나 안에 멈춘다. 읽지 못하면 끔이다(fail-closed).
//
// 사이드카 안 고루틴 하나다(K7 — 장부 표 단일 쓰기자 · S3 · 풀이 이미 있다). 한 방송의 순서(설계 M6 7절)는
// 미룸 판정 → 0 조회 제외 영속 → 상태 표지 → 1 무효 컷오프 검사 → 2 대상 행 → 3 세션 단위 문서 → 4 조각 객체 → 5 행이다.
// 걸음이 실패하면 그 방송을 재시도 집합(메모리)에 넣고 다음 방송으로 간다 — 한 방송이 뒤 방송을 막지 않는다(D22).
// 캐시는 만지지 않는다. 장부를 바꾼 뒤 상태 표지(index.PlaybackChanged)만 넣는다(보내는 길은 하나).
package expiry

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// Options 는 만료기의 설정이다. 영값 칸은 New 가 거부하거나 DefaultOptions 로 채운다.
type Options struct {
	// ClipURL 은 clip 내부 주소다(CLIP_INTERNAL_URL — 끝 '/' 없음). 비면 New 가 거부한다 — 꺼짐은 조립이 정한다.
	ClipURL string
	// Token 은 만료 창구 전용 토큰이다.
	Token Token
	// Changed 는 장부를 바꾼 뒤 넣는 상태 표지다(session.PlaybackInbox.Changed). nil 이면 New 가 거부한다.
	Changed index.PlaybackChanged
	Log     *slog.Logger

	Interval       time.Duration // 폴링 주기(10분)
	FullScanEvery  time.Duration // 표시 없이 처음부터 훑는 주기(하루 — 부팅 회차도 처음부터)
	RequestTimeout time.Duration // 창구 요청 시한(10초)
	BodyLimit      int64         // 창구 응답 본문 상한(1MiB)
	RunCap         int           // 한 회차에 처리하는 새 방송 수 상한(2000) — 재시도도 따로 같은 상한

	// Now 는 시계다(시험 주입). nil 이면 time.Now.
	Now func() time.Time
}

// DefaultOptions 는 설계 7절의 수치다.
func DefaultOptions() Options {
	return Options{
		Interval:       10 * time.Minute,
		FullScanEvery:  24 * time.Hour,
		RequestTimeout: 10 * time.Second,
		BodyLimit:      1 << 20,
		RunCap:         2000,
	}
}

// Expirer 는 만료기 한 실행이다. 표시 · 재시도 집합 · 게이지 상태는 메모리 전용이다 — 재기동하면 부팅 회차의
// 처음부터 훑기가 거둔다(D21). 한 고루틴(Run)만 쓴다.
type Expirer struct {
	ledger  Ledger
	objects Objects
	clip    *clipClient
	opt     Options
	log     *slog.Logger
	now     func() time.Time

	cursor   *string              // 다 처리한 마지막 장의 nextCursor
	lastFull time.Time            // 마지막 처음부터 훑기를 시작한 시각(영값 = 아직 — 부팅 회차)
	retry    map[string]Broadcast // 실패한 방송(방송 식별자 streamId → 마지막으로 받은 줄)
	fails    int                  // 연속 폴링 실패 수
	stalled  bool                 // expiry_stalled 게이지
	invalid  map[string]bool      // cutoff_invalid axis=expiry 게이지(켜진 스트림)
	deferred map[string]bool      // expiry_deferred 게이지(미뤄진 방송 — 방송 식별자 streamId)

	switchKnown  bool // 스위치 값을 읽은 적이 있다(첫 읽기는 값 줄을 낸다)
	switchOn     bool // 마지막으로 읽은 스위치 값(expiry_switch 게이지)
	switchFailed bool // expiry_switch_read_failed 게이지
}

// stalledAfter 는 expiry_stalled 를 켜는 연속 폴링 실패 수다(설계 8절).
const stalledAfter = 3

// New 는 만료기를 만든다.
func New(ledger Ledger, objects Objects, o Options) (*Expirer, error) {
	switch {
	case ledger == nil || objects == nil || o.Log == nil:
		return nil, errors.New("expiry: ledger · objects · Log 는 nil 일 수 없다")
	case o.Changed == nil:
		return nil, errors.New("expiry: Changed(상태 표지)는 nil 일 수 없다 — 조회 제외가 상태 전달에 닿지 않는다")
	case o.ClipURL == "":
		return nil, errors.New("expiry: ClipURL 이 비었다 — 만료를 끄려면 만들지 않는다")
	case o.Interval <= 0 || o.FullScanEvery <= 0 || o.RequestTimeout <= 0 || o.BodyLimit <= 0 || o.RunCap <= 0:
		return nil, errors.New("expiry: Interval · FullScanEvery · RequestTimeout · BodyLimit · RunCap 은 양수여야 한다")
	}
	now := o.Now
	if now == nil {
		now = time.Now
	}
	return &Expirer{
		ledger:   ledger,
		objects:  objects,
		clip:     newClipClient(o.ClipURL, o.Token, o.RequestTimeout, o.BodyLimit),
		opt:      o,
		log:      o.Log,
		now:      now,
		retry:    map[string]Broadcast{},
		invalid:  map[string]bool{},
		deferred: map[string]bool{},
	}, nil
}

// Run 은 ctx 가 끝날 때까지 Interval 마다 한 회차를 돈다. 첫 회차는 곧바로 돈다(처음부터 훑기). 돌려주는 값은 ctx 의
// 오류다. 부르는 쪽은 풀을 닫기 전에 ctx 를 끝내고 Run 이 돌아오기를 기다린다(D24 — defer LIFO).
func (e *Expirer) Run(ctx context.Context) error {
	t := time.NewTimer(0)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.C:
		}
		e.sweep(ctx)
		t.Reset(e.opt.Interval)
	}
}

// sweep 은 한 회차다 — 재시도 집합을 먼저 다시 보고, 표시에서 이어(또는 처음부터) 장을 받아 처리한다. 처음부터
// 훑는 회차는 재시도 집합을 비운다. 스위치가 꺼져 있으면 창구도 부르지 않는다. 방송마다 스위치를 다시 읽어, 꺼지면 그
// 회차를 멈추고 표시를 넘기지 않는다 — 다음 회차가 그 장을 다시 받는다(처리한 방송은 멱등).
func (e *Expirer) sweep(ctx context.Context) {
	if !e.switchAllows(ctx) {
		return // 표시 · 재시도 집합은 그대로 둔다 — 켜면 이어서 한다
	}
	now := e.now()
	if e.lastFull.IsZero() || now.Sub(e.lastFull) >= e.opt.FullScanEvery {
		e.cursor, e.lastFull = nil, now
		clear(e.retry) // 처음부터 훑기가 그 방송을 다시 받는다 — 영구 실패가 하루를 넘겨 쌓이지 않는다
		e.log.Info("expiry_full_scan", "note", "표시 없이 처음부터 훑는다(부팅 · 하루 한 번)")
	}
	if !e.retryFailed(ctx) {
		return
	}
	done := 0
	cursor := e.cursor
	for done < e.opt.RunCap && ctx.Err() == nil {
		page, err := e.clip.list(ctx, now, cursor)
		if err != nil {
			e.pollFailed(ctx, err)
			return
		}
		e.pollSucceeded()
		for _, b := range page.Broadcasts {
			if ctx.Err() != nil || !e.switchAllows(ctx) {
				return
			}
			e.process(ctx, b)
			done++
		}
		// 장을 다 처리했다 — 실패한 방송은 재시도 집합에 있으니 표시를 넘겨도 잃지 않는다.
		if page.NextCursor != nil {
			cursor = page.NextCursor
		}
		e.cursor = cursor
		if !page.HasMore {
			return
		}
	}
}

// retryFailed 는 재시도 집합의 방송을 RunCap 개까지 꺼내 다시 처리한다. 처리 수는 새 방송의 RunCap 에 세지 않는다
// — 영구 실패가 쌓여도 새 방송은 매 회차 진행된다(D22). 꺼내지 않은 재시도는 다음 회차에 본다. 먼저 꺼내 두므로
// 이번에 다시 실패해 들어간 방송을 같은 회차에 또 보지 않는다. 종료 신호가 오거나 스위치가 꺼지면 꺼냈지만 처리하지
// 않은 방송을 집합에 되돌리고 거짓을 돌려준다 — 그 회차는 거기서 멈춘다.
func (e *Expirer) retryFailed(ctx context.Context) bool {
	batch := make([]Broadcast, 0, min(len(e.retry), e.opt.RunCap))
	for key, b := range e.retry {
		if len(batch) == e.opt.RunCap {
			break
		}
		batch = append(batch, b)
		delete(e.retry, key)
	}
	for i, b := range batch {
		if ctx.Err() != nil || !e.switchAllows(ctx) {
			for _, rest := range batch[i:] {
				e.retry[rest.StreamID] = rest
			}
			return false
		}
		e.process(ctx, b)
	}
	return true
}

// switchAllows 는 DB 만료 스위치를 읽어 켜져 있는가다. 읽기 실패는 끔이다(fail-closed). 줄은 전환 때만 낸다 — 값이 바뀔
// 때(첫 읽기 포함) WARN expiry_switch 한 줄, 읽기가 실패하기 시작할 때와 다시 될 때 ERROR expiry_switch_read_failed 한
// 줄씩이다(signalCutoff 꼴). 종료 신호로 끊긴 읽기는 실패로 치지 않는다.
func (e *Expirer) switchAllows(ctx context.Context) bool {
	on, err := e.ledger.SwitchOn(ctx)
	if err != nil {
		if ctx.Err() == nil && !e.switchFailed {
			e.switchFailed = true
			e.log.Error("expiry_switch_read_failed", "value", 1, "err", err,
				"note", "만료 스위치를 읽지 못했다 — 끔으로 보고 만료를 멈춘다")
		}
		return false
	}
	if e.switchFailed {
		e.switchFailed = false
		e.log.Error("expiry_switch_read_failed", "value", 0)
	}
	if !e.switchKnown || on != e.switchOn {
		e.switchKnown, e.switchOn = true, on
		e.log.Warn("expiry_switch", "value", boolValue(on))
	}
	return on
}

// boolValue 는 게이지 값(1 · 0)이다.
func boolValue(b bool) int {
	if b {
		return 1
	}
	return 0
}

// pollFailed 는 창구 요청 실패다 — ERROR 한 줄 · 연속 실패가 stalledAfter 에 닿으면 게이지를 켠다. 종료 신호로
// 끊긴 요청은 실패로 세지 않는다.
func (e *Expirer) pollFailed(ctx context.Context, err error) {
	if ctx.Err() != nil {
		return
	}
	status := "unknown"
	var pe *pollError
	if errors.As(err, &pe) {
		status = pe.status
	}
	e.log.Error("expiry_poll", "status", status, "err", err)
	e.fails++
	if e.fails >= stalledAfter && !e.stalled {
		e.stalled = true
		e.log.Error("expiry_stalled", "value", 1, "failures", e.fails)
	}
}

func (e *Expirer) pollSucceeded() {
	e.fails = 0
	if e.stalled {
		e.stalled = false
		e.log.Error("expiry_stalled", "value", 0)
	}
}
