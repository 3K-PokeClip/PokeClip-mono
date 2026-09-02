package upload

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// targetKey 는 작업 하나를 가리키는 키다. 게이트 3종의 맵 키가 전부 이것이다.
//
// 축이 키에 든 이유(설계 5.5.2 · 5.5.4 #1): ②·③·init 은 같은 seq 를 서로 다른 목적으로
// 올리므로, 축이 없으면 세 작업이 in-flight·백오프·격리에서 한 자리를 다툰다.
// 격리는 프로세스 수명 동안 되돌릴 수 없어서 그 충돌은 관측조차 되지 않는다.
type targetKey struct {
	streamID  string
	axis      index.Axis
	seq       int64
	sessionID string
}

// targetKeyOf 는 대상에서 작업 키를 만드는 **유일한 경로**다(설계 5.5.2 "생성 경로는 하나").
//
// sessionID 는 init 축에서만 키에 든다 — ②·③ 는 (streamID, axis, seq) 만으로 이미
// 유일하므로, 대상이 세션을 함께 실어 오더라도 그 값으로 게이트가 갈리면 안 된다.
// 갈리면 같은 행이 두 자리를 차지해 백오프·격리가 반쪽만 걸린다(회귀 T9).
func targetKeyOf(t index.UploadTarget) targetKey {
	k := targetKey{streamID: t.StreamID, axis: t.Axis, seq: t.Seq}
	if t.Axis == index.AxisInit {
		k.sessionID = t.SessionID
	}
	return k
}

// backoffEntry 는 한 행의 재시도 간격 상태다.
//
// failures 와 deferredStreak 을 나눠 든 이유: 전자는 "판정이 달라질 증거가 없는 실패"의
// 횟수라 지수로 벌리는 근거이고, 후자는 "크기가 변했다는 양의 증거를 본" 연속 보류 횟수라
// 간격을 벌리면 안 된다(결정 13″ M-1 표).
type backoffEntry struct {
	failures       int
	nextAt         time.Time
	deferredStreak int
}

// gates 는 접수 게이트 4종 중 상태를 가진 셋이다(격리·백오프·in-flight).
// 나머지 하나인 브레이커는 전역 상태라 breaker 가 따로 소유한다.
//
// 스위퍼 고루틴·메인 고루틴(RequestUpload)·워커 고루틴이 함께 만지므로 뮤텍스로 보호한다.
type gates struct {
	mu          sync.Mutex
	quarantine_ map[targetKey]struct{}
	backoff     map[targetKey]backoffEntry
	inflight    map[targetKey]struct{}

	sweepEvery time.Duration
	backoffMax time.Duration
	now        func() time.Time
}

func newGates(opt Options, now func() time.Time) *gates {
	return &gates{
		quarantine_: map[targetKey]struct{}{},
		backoff:     map[targetKey]backoffEntry{},
		inflight:    map[targetKey]struct{}{},
		sweepEvery:  opt.SweepEvery,
		backoffMax:  opt.BackoffMax,
		now:         now,
	}
}

// quarantine 은 이 행을 프로세스 수명 동안 건너뛴다.
// 되돌릴 수 없는 조치이므로 판정이 확정된 뒤에만 부른다(결정 13″ · CX6-1).
func (g *gates) quarantine(k targetKey) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.quarantine_[k] = struct{}{}
}

func (g *gates) quarantined(k targetKey) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	_, ok := g.quarantine_[k]
	return ok
}

// registerFailure 는 지수 백오프를 등록한다. failures 를 먼저 올리고 그 값으로 계산하므로
// 1회차가 SweepEvery·2 다 — 1회차가 SweepEvery 그대로면 registerDeferred 의
// "1회차 고정"과 구분이 사라진다.
func (g *gates) registerFailure(k targetKey) {
	g.mu.Lock()
	defer g.mu.Unlock()

	e := g.backoff[k]
	e.failures++
	e.deferredStreak = 0 // 다른 분기가 끼면 "연속"이 끊긴다
	e.nextAt = g.now().Add(g.exponential(e.failures))
	g.backoff[k] = e
}

// exponential 은 min(SweepEvery·2^failures, BackoffMax) 다.
// 시프트는 failures 가 커지면 넘치므로 상한에 먼저 걸리는 구간만 계산한다.
func (g *gates) exponential(failures int) time.Duration {
	d := g.sweepEvery
	for i := 0; i < failures; i++ {
		d *= 2
		if d >= g.backoffMax {
			return g.backoffMax
		}
	}
	return d
}

// registerDeferred 는 크기 불일치로 판정을 미룬 경우다.
// 간격은 1회차 고정(now + SweepEvery)이고 failures 는 올리지 않는다 —
// UpdateTail 의 bytes 교정이 다음 회차 판정을 실제로 바꾸기 때문이다(M-1).
// 돌려주는 값은 연속 횟수이며, 임계 초과는 새 로그가 아니라 이 값으로만 드러난다(N-3).
func (g *gates) registerDeferred(k targetKey) int {
	g.mu.Lock()
	defer g.mu.Unlock()

	e := g.backoff[k]
	e.deferredStreak++
	e.nextAt = g.now().Add(g.sweepEvery)
	g.backoff[k] = e
	return e.deferredStreak
}

// backoffBlocked 는 지금 이 행을 건드리면 안 되는지와 다음 시도 시각을 돌려준다.
func (g *gates) backoffBlocked(k targetKey) (time.Time, bool) {
	g.mu.Lock()
	defer g.mu.Unlock()

	e, ok := g.backoff[k]
	if !ok {
		return time.Time{}, false
	}
	return e.nextAt, g.now().Before(e.nextAt)
}

// clearBackoff 는 마킹까지 성공(marked == true)했을 때만 부른다.
// PUT 만 성공하고 마킹이 실패한 행은 해제하지 않고 기존 failures 를 승계한다(결정 6‴).
func (g *gates) clearBackoff(k targetKey) {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.backoff, k)
}

// failuresOf 는 관측용이다(로그 필드·테스트).
func (g *gates) failuresOf(k targetKey) int {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.backoff[k].failures
}

// acquireInflight 는 이 행의 처리권을 잡는다. 큐 삽입 **전**에 부르고,
// 접수가 QueueFull 로 끝나면 반드시 되돌려야 한다(rev6 N1).
func (g *gates) acquireInflight(k targetKey) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if _, ok := g.inflight[k]; ok {
		return false
	}
	g.inflight[k] = struct{}{}
	return true
}

func (g *gates) releaseInflight(k targetKey) {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.inflight, k)
}

// circuitState 는 브레이커의 세 상태다.
type circuitState int

const (
	circuitClosed circuitState = iota
	circuitOpen
	circuitHalfOpen
)

func (s circuitState) String() string {
	switch s {
	case circuitClosed:
		return "closed"
	case circuitOpen:
		return "open"
	case circuitHalfOpen:
		return "half_open"
	default:
		return "unknown"
	}
}

// breaker 는 "설정이 통째로 틀렸을 때 전역 차단"이다.
//
// 소유 규칙(설계 3.4절): 접수 게이트는 Open 이면 거부만 본다. 반열림 probe 판정은
// 워커가 소유한다. 시간 전이는 lazy 평가다 — 요청이 없는 정적 구간에서는 Open 으로
// 남아 있어도 무해하므로 타이머를 두지 않는다.
type breaker struct {
	mu sync.Mutex

	max       int
	cooldown  time.Duration
	blockLog  time.Duration
	now       func() time.Time
	log       *slog.Logger
	state     circuitState
	streak    int
	openedAt  time.Time
	probeUsed bool

	// epoch 는 열린 횟수다. 요약 로그의 첫 건만 WARN 으로 올리는 기준이 된다.
	epoch        int64
	blockedTotal int64
	lastBlockLog time.Time
	// blockLogged 는 이번 epoch 에서 요약을 이미 냈는가다. 첫 건만 WARN 으로 올린다.
	blockLogged bool
	lastErr     string
}

func newBreaker(opt Options, log *slog.Logger, now func() time.Time) *breaker {
	return &breaker{
		max:      opt.CircuitMax,
		cooldown: opt.CircuitCooldown,
		blockLog: opt.CircuitBlockLog,
		now:      now,
		log:      log,
	}
}

// disabled 는 1행이다 — CircuitMax == 0 이면 브레이커가 통째로 없는 것과 같다.
func (b *breaker) disabled() bool { return b.max <= 0 }

// current 는 lazy 전이를 평가한 뒤의 상태다. 상태를 묻는 모든 경로가 이것을 지난다.
func (b *breaker) current() circuitState {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.evaluate()
}

// evaluate 는 9행(쿨다운 경과 → HalfOpen)을 그 자리에서 적용한다. 락을 잡은 채로 부른다.
func (b *breaker) evaluate() circuitState {
	if b.disabled() {
		return circuitClosed
	}
	if b.state == circuitOpen && !b.now().Before(b.openedAt.Add(b.cooldown)) {
		b.state = circuitHalfOpen
		b.probeUsed = false
		b.log.Info("circuit_half_open", "epoch", b.epoch, "cooldown", b.cooldown.String())
	}
	return b.state
}

// allowEnqueue 는 접수 게이트다(2·6·8·10행). Open 이면 거부하고 차단을 센다.
func (b *breaker) allowEnqueue() bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.evaluate() != circuitOpen {
		return true
	}
	b.blockedTotal++
	now := b.now()
	// 8행 — 차단이 CircuitBlockLog 만큼 이어질 때마다 요약 1건. 매 건 찍으면 소음이다.
	if !now.Before(b.lastBlockLog.Add(b.blockLog)) {
		// epoch 안의 첫 요약만 WARN 이다. 이후는 같은 사실의 반복이라 INFO 로 내린다.
		level := slog.LevelInfo
		if !b.blockLogged {
			level = slog.LevelWarn
		}
		b.blockLogged = true
		b.lastBlockLog = now
		b.log.Log(context.Background(), level, "circuit_blocking",
			"blocked_total", b.blockedTotal,
			"epoch", b.epoch,
			"since", now.Sub(b.openedAt).String(),
			"cooldown_left", b.openedAt.Add(b.cooldown).Sub(now).String())
	}
	return false
}

// allowWork 는 워커의 dequeue 게이트다(7·11·12행). probe 소비의 소유자가 여기다.
func (b *breaker) allowWork(k targetKey) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	switch b.evaluate() {
	case circuitOpen:
		b.log.Debug("upload_skipped_circuit", "stream_id", k.streamID, "seq", k.seq,
			"next_attempt_at", b.openedAt.Add(b.cooldown).UTC().Format(time.RFC3339))
		return false
	case circuitHalfOpen:
		if b.probeUsed {
			b.log.Debug("upload_skipped_circuit", "stream_id", k.streamID, "seq", k.seq,
				"reason", "probe_used")
			return false
		}
		b.probeUsed = true
		return true
	default:
		return true
	}
}

// record 는 행 하나의 종결을 반영한다(3·4·5·13·14·15행).
func (b *breaker) record(o outcome, lastErr error) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.disabled() {
		return // 1행 — streak 조차 세지 않는다
	}
	if lastErr != nil {
		b.lastErr = lastErr.Error()
	}

	if b.state == circuitHalfOpen {
		switch o {
		case outcomeSuccess: // 13행
			b.state = circuitClosed
			b.streak = 0
			b.log.Info("circuit_closed", "epoch", b.epoch)
		case outcomeHard, outcomeSoft: // 14행 — 보수적으로 둘 다 다시 연다
			b.openLocked("probe_failed")
		default: // 15행 — 판정 재료가 아니다. probe 를 돌려주지 않으면 영영 고착한다
			b.probeUsed = false
		}
		return
	}

	switch o {
	case outcomeSuccess, outcomeSoft: // 3행 — 설정 문제가 아니라는 증거
		b.streak = 0
	case outcomeHard: // 4행
		b.streak++
		if b.streak >= b.max && b.state == circuitClosed {
			b.openLocked("hard_streak")
		}
	default: // 5행 — neutral·shutdown 은 판정 재료가 아니다
	}
}

// open 은 기동 시 자격증명이 없을 때처럼 밖에서 여는 경로다.
func (b *breaker) open(reason string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.disabled() {
		return
	}
	b.openLocked(reason)
}

func (b *breaker) openLocked(reason string) {
	b.state = circuitOpen
	b.openedAt = b.now()
	b.probeUsed = false
	b.epoch++
	// 새 epoch 의 첫 요약을 다시 WARN 으로 내되, 주기는 열린 시각부터 센다.
	b.lastBlockLog = b.openedAt
	b.blockLogged = false
	b.log.Error("circuit_open",
		"streak", b.streak, "limit", b.max, "cooldown", b.cooldown.String(),
		"last_err", b.lastErr, "reason", reason, "epoch", b.epoch)
}

// axisAll 은 브레이커를 세울 축 전부다. 5.5.3 "축별 3개"의 유일한 열거 자리다.
var axisAll = [...]index.Axis{index.AxisArchive, index.AxisPlayback, index.AxisInit}

// breakers 는 축별 브레이커 셋이다(설계 5.5.3 확정 — 축별 3개).
//
// 하나로 두면 ② 의 실패 폭풍이 되감기를 세우고 ③ 의 폭풍이 클립 소재 업로드를 세운다.
// init 실패는 그 세션 전체가 재생 불가라는 뜻이라 성격이 또 달라 ③ 와도 공유하지 않는다.
//
// 만든 뒤에는 쓰지 않으므로(생성자에서 한 번 채운다) 맵 자체에 잠금이 필요 없다 —
// 잠금은 각 breaker 가 자기 상태에 대해 이미 쥐고 있다.
type breakers struct {
	byAxis map[index.Axis]*breaker
}

// newBreakers 는 축마다 브레이커를 하나씩 세우고 **로거에 axis 라벨을 미리 묶는다**
// (설계 5.5.4 #8) — 그래야 브레이커가 내는 모든 로그가 어느 축이 아픈지 말한다.
func newBreakers(opt Options, log *slog.Logger, now func() time.Time) *breakers {
	byAxis := make(map[index.Axis]*breaker, len(axisAll))
	for _, a := range axisAll {
		byAxis[a] = newBreaker(opt, log.With("axis", a.String()), now)
	}
	return &breakers{byAxis: byAxis}
}

// of 는 축의 브레이커다. **미판정 축은 nil 이다** — 호출자가 그 자리에서 거부한다.
// 임의의 축에 기본 브레이커를 주면 모르는 축이 조용히 ② 의 판정에 섞인다.
func (b *breakers) of(a index.Axis) *breaker { return b.byAxis[a] }

// openAll 은 축과 무관한 사정(기동 시 자격증명 부재)으로 세 축을 전부 연다.
// 축별 분리가 사는 자리는 실패 누적으로 여는 경로이지 밖에서 거는 전역 차단이 아니다.
func (b *breakers) openAll(reason string) {
	for _, brk := range b.byAxis {
		brk.open(reason)
	}
}
