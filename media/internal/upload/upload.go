package upload

import (
	"context"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"sync/atomic"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// Origin 은 이 작업이 어느 경로에서 왔는가다. 업로더가 내는 모든 로그에 실린다.
type Origin uint8

const (
	OriginUnknown Origin = 0
	OriginLive    Origin = 1
	OriginSweep   Origin = 2
)

func (o Origin) String() string {
	switch o {
	case OriginLive:
		return "live"
	case OriginSweep:
		return "sweep"
	default:
		return "unknown"
	}
}

// EnqueueOutcome 은 접수 시도의 결과를 세 갈래로 가른다.
// 이 세 값이 스위퍼 커서 계약의 전부다 — bool 하나로는 "판정을 내렸다"와
// "판정조차 못 했다"를 구분할 수 없어 커서가 영구 정지한다(B-1).
type EnqueueOutcome int

const (
	// EnqueueAdmitted — 큐에 들어갔다. 워커가 언젠가 처리한다. 검사 완료.
	EnqueueAdmitted EnqueueOutcome = iota
	// EnqueueRejected — 게이트가 판정을 마쳤다(격리·백오프·in-flight·브레이커·비활성).
	// "지금 이 행에 할 일이 없다"는 결론이므로 검사 완료다. 커서가 전진한다.
	EnqueueRejected
	// EnqueueQueueFull — 큐에 자리가 없어 판정 자체를 못 했다. 미검사다.
	// 스위퍼는 이 행 직전에서 커서를 멈추고 회차를 중단한다.
	EnqueueQueueFull
)

func (e EnqueueOutcome) String() string {
	switch e {
	case EnqueueAdmitted:
		return "admitted"
	case EnqueueRejected:
		return "rejected"
	case EnqueueQueueFull:
		return "queue_full"
	default:
		return "unknown"
	}
}

// Result 는 "장부가 실제로 바뀌었다"는 통지다.
// 보류·거부·CAS 실패·결과 불명은 보내지 않는다 — 보내면 메모리 커서가 DB 와 갈린다.
type Result struct {
	StreamID string
	Seq      int64
	State    index.UploadState // uploaded 또는 failed 만
}

// Putter 는 S3 PUT 한 번이다. 인터페이스로 둔 이유는 오직 테스트다.
//
// 구현 계약 셋 — 하나라도 어기면 종료 상한(G18″)이 무너진다:
//  1. ctx 취소를 반드시 준수한다. 취소되면 즉시 ctx.Err() 계열 오류로 반환한다.
//  2. body 를 처음부터 정확히 size 바이트만 읽는다(되감기하지 않는다).
//  3. Content-Type 은 구현이 상수로 고정한다(계약이므로 호출자가 매번 넘길 이유가 없다).
type Putter interface {
	Put(ctx context.Context, key string, body io.Reader, size int64) error
}

// job 은 큐에 실리는 항목이다.
//
// sweepRound 를 값으로 새겨 넘기는 것이 계약이다 — 워커가 Uploader 의 공유 필드를
// 직접 읽으면 데이터 레이스이고, 값이 출력 시점 회차로 오염돼 AC4 의 회차 계수가
// 조용히 틀린다(D-3).
type job struct {
	target     index.UploadTarget
	origin     Origin
	sweepRound uint64
}

func (j job) key() targetKey { return targetKey{j.target.StreamID, j.target.Seq} }

// lifecycle 은 Shutdown 이 어떤 순서로 불려도 안전하게 만드는 상태다.
type lifecycle int32

const (
	lifecycleNew     lifecycle = 0
	lifecycleStarted lifecycle = 1
	lifecycleStopped lifecycle = 2
)

// Uploader 는 큐 하나와 고루틴 둘(워커·스위퍼)을 소유한다.
//
// Start 와 Shutdown 은 메인 고루틴에서만, 서로 겹치지 않게 부른다 — D10 과 같은 형태의
// 직렬 호출 계약이다. Start 는 손잡이를 전부 만들고 고루틴을 띄운 **뒤에** state 를
// Started 로 publish 하므로, Shutdown 이 Started 를 본 시점에는 모든 손잡이가 유효하다.
type Uploader struct {
	st   index.UploadStore
	put  Putter
	opt  Options
	log  *slog.Logger
	now  func() time.Time
	off  bool // Disabled 로 만들어졌는가
	gate *gates
	brk  *breaker

	state     atomic.Int32 // lifecycle
	accepting atomic.Bool

	queue   chan job
	results chan Result

	putCtx      context.Context    // = WithCancel(WithoutCancel(startCtx)). PUT 의 뿌리
	putCancel   context.CancelFunc // Shutdown 5단계에서만 부른다
	markRoot    context.Context    // 마킹의 뿌리(PUT 의 형제)
	markCancel  context.CancelFunc // Shutdown 6단계에서만 부른다
	sweepCancel context.CancelFunc // 스위퍼의 ticker·DB 대기를 즉시 깨운다
	workerStop  chan struct{}      // close 로 워커의 큐 receive 와 백오프 대기를 깨운다
	sweepDone   chan struct{}
	workerDone  chan struct{}

	// sweepRound 는 스위프 회차 ID 다(1부터 단조 증가, 리셋 없음).
	// 쓰기는 스위퍼 고루틴만, 읽기는 enqueue 의 OriginSweep 경로만 한다 —
	// enqueue 를 부르는 것이 스위퍼 고루틴이므로 둘은 같은 고루틴이고 경합이 없다(D-3).
	sweepRound uint64

	// lastAttemptErr 는 브레이커에 실을 마지막 오류다. 워커 고루틴 하나만 만진다.
	lastAttemptErr error

	// statFile 은 열린 파일의 크기를 재는 손잡이다. 프로덕션에서는 언제나 (*os.File).Stat 이다.
	//
	// 필드로 둔 이유는 오직 테스트다. 유효한 fd 의 fstat 은 EBADF 말고 실패할 길이 거의
	// 없는데, [2](b)의 PUT 전 측정은 Open 과 Stat 사이에 테스트가 낄 틈이 없고 [3] 꼬리
	// 재측정의 fd 는 밖으로 노출되지 않는다. 오류 분기 셋을 죽은 코드로 두는 것보다
	// 이 한 줄을 두는 편이 낫다 — 열기 손잡이(Options.Root)와 달리 어떤 시그니처도
	// 인터페이스로 번지지 않는다.
	statFile func(*os.File) (fs.FileInfo, error)
}

// New 는 업로더를 만든다. 고루틴은 아직 뜨지 않는다.
func New(st index.UploadStore, put Putter, opt Options, log *slog.Logger) *Uploader {
	return newWithClock(st, put, opt, log, time.Now)
}

// newWithClock 은 시계를 갈아 끼울 수 있는 내부 생성자다.
// 브레이커의 lazy 전이와 백오프는 전부 시각 비교라, 실제 시간을 기다리는 테스트는
// 느리고 흔들린다. 공개 시그니처는 그대로 둔 채 이음매를 여기 하나만 낸다.
func newWithClock(st index.UploadStore, put Putter, opt Options, log *slog.Logger, now func() time.Time) *Uploader {
	return &Uploader{
		st:       st,
		put:      put,
		opt:      opt,
		log:      log,
		now:      now,
		gate:     newGates(opt, now),
		brk:      newBreaker(opt, log, now),
		queue:    make(chan job, opt.QueueLen),
		results:  make(chan Result, opt.ResultBufLen),
		statFile: (*os.File).Stat,
	}
}

// Disabled 는 아무 일도 하지 않는 업로더다. S3_BUCKET 이 비었을 때 끼운다.
//
// enqueue 는 언제나 EnqueueRejected 를 돌려준다 — "검사는 끝났고 할 일이 없다"는 뜻이라
// 스위퍼 커서도 정상 전진한다. state 는 New 로 남아 Shutdown 도 no-op 이다.
func Disabled(log *slog.Logger) *Uploader {
	log.Info("uploader_disabled", "note", "S3_BUCKET 미설정 — 인덱싱만 수행한다")
	return &Uploader{off: true, log: log, opt: DefaultOptions(nil, ""), now: time.Now}
}

// Start 는 손잡이를 전부 만들고 고루틴을 띄운 뒤에 state 를 publish 한다. 순서가 계약이다.
func (u *Uploader) Start(ctx context.Context) {
	if u.off || u.state.Load() != int32(lifecycleNew) {
		return
	}

	u.putCtx, u.putCancel = context.WithCancel(context.WithoutCancel(ctx))
	u.markRoot, u.markCancel = context.WithCancel(context.WithoutCancel(ctx))
	sweepCtx, sweepCancel := context.WithCancel(ctx)
	u.sweepCancel = sweepCancel
	u.workerStop = make(chan struct{})
	u.sweepDone = make(chan struct{})
	u.workerDone = make(chan struct{})
	u.accepting.Store(true)

	go u.worker()
	go u.sweeper(sweepCtx)

	u.state.Store(int32(lifecycleStarted))
}

// OpenCircuit 은 기동 시 자격증명을 얻지 못했을 때처럼 밖에서 전역 차단을 거는 경로다.
func (u *Uploader) OpenCircuit(reason string) {
	if u.off {
		return
	}
	u.brk.open(reason)
}

// Results 는 장부가 바뀌었다는 통지 채널이다. 비활성이면 nil 이라 select 에서 절대 선택되지 않는다.
func (u *Uploader) Results() <-chan Result {
	if u.off {
		return nil
	}
	return u.results
}

// RequestUpload 는 실시간 경로(인덱서)의 유일한 진입점이다. 논블로킹이며 고루틴 안전하다.
//
// 반환이 bool 인 이유: 인덱서가 알아야 할 것은 "큐에 들어갔는가" 하나뿐이며,
// Rejected 와 QueueFull 의 구분은 스위퍼의 커서 계산에만 쓸모가 있다.
// false 는 "아무 일도 없었다"이며 호출자는 어떤 상태도 기록하면 안 된다.
func (u *Uploader) RequestUpload(t index.UploadTarget) bool {
	return u.enqueue(t, OriginLive) == EnqueueAdmitted
}

// enqueue 는 비공개 공용 입구다. 게이트 순서는 결정 6‴ 표 그대로다.
func (u *Uploader) enqueue(t index.UploadTarget, o Origin) EnqueueOutcome {
	if u.off || u.state.Load() != int32(lifecycleStarted) || !u.accepting.Load() {
		return EnqueueRejected
	}

	k := targetKey{t.StreamID, t.Seq}
	lg := u.log.With("stream_id", t.StreamID, "seq", t.Seq, "origin", o.String())

	if !u.brk.allowEnqueue() {
		lg.Debug("upload_skipped_circuit")
		return EnqueueRejected
	}
	if u.gate.quarantined(k) {
		lg.Debug("upload_skipped_missing")
		return EnqueueRejected
	}
	if nextAt, blocked := u.gate.backoffBlocked(k); blocked {
		lg.Debug("upload_skipped_backoff", "next_attempt_at", nextAt.UTC().Format(time.RFC3339))
		return EnqueueRejected
	}
	if !u.gate.acquireInflight(k) {
		lg.Debug("upload_duplicate_skipped")
		return EnqueueRejected
	}

	// in-flight 는 큐 삽입 **전**에 잡는다. QueueFull 로 끝나면 반드시 되돌린다 —
	// 되돌리지 않으면 작업이 없는데도 in-flight 로 남아 그 행이 조용히 영구 미업로드된다(rev6 N1).
	admitted := false
	defer func() {
		if !admitted {
			u.gate.releaseInflight(k)
		}
	}()

	j := job{target: t, origin: o}
	if o == OriginSweep {
		j.sweepRound = u.sweepRound // 접수 시점 값을 새겨 넘긴다
	}

	select {
	case u.queue <- j:
		admitted = true
		return EnqueueAdmitted
	default:
		lg.Warn("upload_queue_full", "queue_len", len(u.queue), "sweep_round", j.sweepRound)
		return EnqueueQueueFull
	}
}

// Shutdown 은 6단계를 거쳐 반드시 두 고루틴 join 후 반환한다(결정 17″).
// state 가 Started 가 아니면(미기동 또는 이미 종료) 즉시 no-op 으로 반환한다.
// 반환 후에는 UploadStore 를 절대 쓰지 않으므로 pool.Close 보다 먼저 반환되어야 한다.
func (u *Uploader) Shutdown() {
	if u.off {
		return
	}
	if !u.state.CompareAndSwap(int32(lifecycleStarted), int32(lifecycleStopped)) {
		return
	}
	// 어느 경로로 나가든 컨텍스트를 남기지 않는다. 두 취소는 멱등이다.
	defer u.markCancel()
	defer u.putCancel()

	// 1) 접수를 닫는다. 채널을 close 하지 않는다 — 스위퍼가 send 중이면 panic 이고,
	//    close 된 buffered 채널의 range 는 "폐기"가 아니라 계속 처리다.
	u.accepting.Store(false)

	// 2) 스위퍼를 깨워 join 한다.
	u.sweepCancel()
	<-u.sweepDone

	// 3) 큐를 비운다. 조용히 버리지 않고 몇 건인지 남긴다(행은 DB 에 있으므로 유실이 아니다).
	if n := u.drainQueue(); n > 0 {
		u.log.Warn("upload_shutdown_dropped", "count", n)
	}

	// 4) 진행 중 PUT 의 완주를 ShutdownGrace 만큼 봐준다.
	close(u.workerStop)
	if u.joined(u.opt.ShutdownGrace) {
		return
	}
	// 5) PUT 을 취소한다. 마킹은 형제 컨텍스트라 이미 200 을 받은 건은 완주한다.
	u.putCancel()
	if u.joined(u.opt.ShutdownMarkGrace) {
		return
	}
	// 6) 마킹까지 취소하고 무조건 join 한다. 이 join 에는 타임아웃이 없다 —
	//    상한은 Putter·UploadStore 가 ctx 계약을 지킬 때만 성립한다(G18″).
	u.markCancel()
	<-u.workerDone
}

// joined 는 워커가 유예 안에 끝났는지 본다.
func (u *Uploader) joined(grace time.Duration) bool {
	timer := time.NewTimer(grace)
	defer timer.Stop()
	select {
	case <-u.workerDone:
		return true
	case <-timer.C:
		return false
	}
}

// drainQueue 는 남은 작업을 폐기하고 in-flight 를 되돌린다.
func (u *Uploader) drainQueue() int {
	n := 0
	for {
		select {
		case j := <-u.queue:
			u.gate.releaseInflight(j.key())
			n++
		default:
			return n
		}
	}
}

// sendResult 는 논블로킹 통지다. 채널이 가득 차면 판정을 버린다 —
// 커서 동기화는 최선 노력이며 5분 주기 Scan 이 치유한다(L7).
func (u *Uploader) sendResult(r Result) {
	select {
	case u.results <- r:
	default:
		u.log.Debug("upload_mark_skipped", "stream_id", r.StreamID, "seq", r.Seq)
	}
}
