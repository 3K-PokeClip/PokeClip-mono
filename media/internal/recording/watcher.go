package recording

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
)

// WatcherOptions 는 폴더 감시자의 설정값이다.
type WatcherOptions struct {
	Root string
	// IdleTimeout 만큼 아무 이벤트가 없으면 방송이 멈췄다고 보고 pending 파일을 확정한다.
	IdleTimeout time.Duration
	// RescanEvery 는 안전망이다. 이벤트를 놓쳤어도 이 주기로 전수 점검을 요청한다.
	RescanEvery time.Duration
	Settle      SettleOptions
	// FIFOWarnLen 을 넘으면 WARN, FIFOMaxLen 을 넘으면 ERROR 후 워처를 종료한다.
	// 내부 FIFO 의 소유자가 Watcher 이므로 이 설정도 여기 있다.
	FIFOWarnLen int
	FIFOMaxLen  int
	Log         *slog.Logger
}

// DefaultWatcherOptions 는 설계 2.1절의 기본값이다.
func DefaultWatcherOptions(root string, log *slog.Logger) WatcherOptions {
	return WatcherOptions{
		Root:        root,
		IdleTimeout: 10 * time.Second,
		RescanEvery: 5 * time.Minute,
		Settle:      DefaultSettleOptions(),
		FIFOWarnLen: 256,
		FIFOMaxLen:  4096,
		Log:         log,
	}
}

// adoptBuffer 는 Adopt 요청을 담는 버퍼 크기다. eventLoop 이 I/O 대기를 하지 않으므로
// 이 정도면 넘칠 일이 없고, 넘치더라도 주기 재스캔이 복구한다.
const adoptBuffer = 256

// Watcher 는 녹화 폴더를 지켜보다 완성된 세그먼트만 밖으로 내보낸다.
type Watcher struct {
	opt WatcherOptions
	log *slog.Logger

	fsw       *fsnotify.Watcher
	completed chan Segment
	rescans   chan struct{}
	adopts    chan Segment
	done      chan struct{}
	fifo      *segmentFIFO

	// cancel 은 두 고루틴을 함께 끝내기 위한 내부 취소 손잡이다.
	// 한쪽이 죽었는데 다른 쪽이 계속 살아 있으면 Done() 이 영원히 닫히지 않아
	// 메인 루프가 워처 사망을 알아채지 못한다(감독 장치가 무력화된다).
	cancel    context.CancelFunc
	closeOnce sync.Once
	waitErr   error
	wg        sync.WaitGroup
}

// NewWatcher 는 감시자를 만든다. 이 시점에는 아직 어떤 폴더도 감시하지 않는다.
func NewWatcher(opt WatcherOptions) (*Watcher, error) {
	if opt.Log == nil {
		return nil, errors.New("WatcherOptions.Log 가 비어 있다")
	}
	fsw, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, fmt.Errorf("fsnotify 감시자 생성 실패: %w", err)
	}
	return &Watcher{
		opt:       opt,
		log:       opt.Log,
		fsw:       fsw,
		completed: make(chan Segment),
		rescans:   make(chan struct{}, 1),
		adopts:    make(chan Segment, adoptBuffer),
		done:      make(chan struct{}),
		fifo:      newSegmentFIFO(),
	}, nil
}

// Start 는 동기다. 반환된 시점에는 감시 등록이 이미 끝나 있으므로,
// 이후 생성되는 파일은 유실되지 않는다. "walk 종료 - watch 등록" 공백이 구조적으로 없다.
func (w *Watcher) Start(ctx context.Context) error {
	if err := w.watchTree(w.opt.Root); err != nil {
		return err
	}

	inner, cancel := context.WithCancel(ctx)
	w.cancel = cancel

	w.wg.Add(2)
	go w.eventLoop(inner)
	go w.emitLoop(inner)

	go func() {
		w.wg.Wait()
		cancel()
		close(w.done)
	}()
	return nil
}

// Completed 는 완성된 세그먼트가 하나씩 흘러나오는 통로다.
// 무버퍼지만 내부 FIFO 가 받쳐 주므로 유실이 없다. 소비자는 하나뿐이어야 한다.
func (w *Watcher) Completed() <-chan Segment { return w.completed }

// Rescans 는 "전수 재점검이 필요하다"는 신호다. 여러 번 울려도 1개로 합쳐 보낸다.
func (w *Watcher) Rescans() <-chan struct{} { return w.rescans }

// Done 은 내부 고루틴이 모두 끝나면 닫힌다. 메인 루프가 감시자 사망을 즉시 알아채는 장치다.
func (w *Watcher) Done() <-chan struct{} { return w.done }

// Wait 는 종료 사유를 돌려준다. nil 이면 ctx 취소에 의한 정상 종료다.
func (w *Watcher) Wait() error { return w.waitErr }

// Adopt 는 처리를 미루고 감시자에게 파일을 되돌려 주는 입구다. 고루틴 안전하다.
func (w *Watcher) Adopt(seg Segment) {
	select {
	case w.adopts <- seg:
	default:
		// 버퍼가 찼다. 잃어도 주기 재스캔이 복구하므로 워처를 멈추지는 않는다.
		w.log.Warn("adopt_dropped", "stream_id", seg.StreamID, "path", seg.Path)
	}
}

// watchTree 는 루트와 기존 하위 디렉토리를 재귀 등록한다.
func (w *Watcher) watchTree(root string) error {
	return filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			w.log.Warn("watch_walk_failed", "path", path, "err", err)
			return nil
		}
		if !d.IsDir() {
			return nil
		}
		if err := w.fsw.Add(path); err != nil {
			return fmt.Errorf("감시 등록 실패 %q: %w", path, err)
		}
		return nil
	})
}

// streamState 는 스트림(디렉토리) 하나의 감시 상태다. 스트림별로 완전히 독립이다.
type streamState struct {
	// pending 은 아직 완성 판정을 받지 않은, 지금 쓰이고 있을 파일이다.
	pending *Segment
	// confirmed 는 직전에 확정한 파일이다. 여기에 WRITE 로 크기가 늘면 재성장이다.
	confirmed     *Segment
	confirmedSize int64
	lastEvent     time.Time
}

// eventLoop 은 fsnotify 이벤트를 상태 머신에 넣고 확정 후보를 FIFO 에 push 만 한다.
//
// 이 고루틴은 절대 I/O 대기를 하면 안 된다. 여기서 멈추면 커널의 inotify 큐가 넘쳐
// 알림이 조용히 버려진다(D10). 시간이 걸리는 크기 안정 대기는 emitLoop 이 맡는다.
// (os.Stat 은 대기가 아니라 즉시 반환하는 시스템 호출이라 여기서 써도 된다.)
//
// 컴파일러가 이 규칙을 잡아 주지 않으므로 주석과 리뷰로만 지킨다.
func (w *Watcher) eventLoop(ctx context.Context) {
	defer w.wg.Done()
	defer w.cancel() // 이 루프가 죽으면 emitLoop 도 함께 끝낸다
	defer w.fsw.Close()

	states := map[string]*streamState{}

	idleTick := time.NewTicker(maxDuration(w.opt.IdleTimeout/2, 10*time.Millisecond))
	defer idleTick.Stop()
	rescanTick := time.NewTicker(w.opt.RescanEvery)
	defer rescanTick.Stop()

	for {
		select {
		case <-ctx.Done():
			return

		case ev, ok := <-w.fsw.Events:
			if !ok {
				w.fail(errors.New("fsnotify 이벤트 채널이 닫혔다"))
				return
			}
			if err := w.onEvent(states, ev); err != nil {
				w.fail(err)
				return
			}

		case err, ok := <-w.fsw.Errors:
			if !ok {
				w.fail(errors.New("fsnotify 에러 채널이 닫혔다"))
				return
			}
			// 알림이 유실됐을 수 있다. 눈으로 직접 확인하는 전수 점검으로 되돌린다.
			// U10(inotify 큐 오버플로 노출 방식)이 여기로 드러나며, 드러나지 않더라도
			// RescanEvery 주기 재스캔이 결국 복구한다.
			w.log.Error("fsnotify_error", "err", err)
			w.signalRescan()

		case seg := <-w.adopts:
			st := stateOf(states, seg.StreamID)
			adopted := seg
			st.pending = &adopted
			st.lastEvent = time.Now()

		case <-idleTick.C:
			if err := w.onIdleTick(states); err != nil {
				w.fail(err)
				return
			}

		case <-rescanTick.C:
			w.signalRescan()
		}
	}
}

func (w *Watcher) onEvent(states map[string]*streamState, ev fsnotify.Event) error {
	if ev.Has(fsnotify.Create) {
		if fi, err := os.Stat(ev.Name); err == nil && fi.IsDir() {
			// 새 방송이 시작돼 폴더가 생겼다. 감시 대상에 넣고 전수 점검을 요청한다.
			if err := w.fsw.Add(ev.Name); err != nil {
				w.log.Warn("watch_add_failed", "path", ev.Name, "err", err)
			}
			w.signalRescan()
			return nil
		}
	}

	seg, err := ParseSegmentPath(w.opt.Root, ev.Name)
	if err != nil {
		return nil // 세그먼트 파일이 아니다
	}
	st := stateOf(states, seg.StreamID)
	st.lastEvent = time.Now()

	switch {
	case ev.Has(fsnotify.Create):
		return w.onCreate(st, seg)
	case ev.Has(fsnotify.Write):
		return w.onWrite(st, seg)
	}
	return nil
}

// onCreate 는 "다음 파일이 생겼다 = 이전 파일은 다 쓴 것"을 처리한다.
// 가장 신뢰도 높은 완성 신호이며 이 경우만 4000ms 를 기대할 수 있다.
func (w *Watcher) onCreate(st *streamState, seg Segment) error {
	if st.pending != nil && st.pending.Path == seg.Path {
		return nil
	}
	if st.pending != nil {
		if err := w.confirm(st, *st.pending, ReasonNextFile); err != nil {
			return err
		}
	}
	// 같은 디렉토리에 새 파일이 생겼으므로 직전 확정 파일의 재성장 감시는 종료한다.
	if st.pending == nil {
		st.confirmed = nil
	}
	created := seg
	st.pending = &created
	return nil
}

// onWrite 는 확정된 파일이 다시 자라는 경우를 처리한다(ReasonRegrown).
func (w *Watcher) onWrite(st *streamState, seg Segment) error {
	if st.confirmed == nil || st.confirmed.Path != seg.Path {
		return nil
	}
	fi, err := os.Stat(seg.Path)
	if err != nil || fi.Size() <= st.confirmedSize {
		return nil
	}
	st.confirmedSize = fi.Size()

	regrown := seg
	regrown.Reason = ReasonRegrown
	return w.push(regrown)
}

// onIdleTick 은 한참 조용한 스트림의 pending 파일을 확정한다.
// 마지막 조각은 후속 파일이 영영 안 생기므로 이 장치가 없으면 영영 기록되지 않는다.
func (w *Watcher) onIdleTick(states map[string]*streamState) error {
	for _, st := range states {
		if st.pending == nil {
			continue
		}
		if time.Since(st.lastEvent) < w.opt.IdleTimeout {
			continue
		}
		if err := w.confirm(st, *st.pending, ReasonIdle); err != nil {
			return err
		}
		st.pending = nil
	}
	return nil
}

// confirm 은 확정 후보를 FIFO 에 넣고 재성장 감시 대상으로 등록한다.
func (w *Watcher) confirm(st *streamState, seg Segment, reason CompletionReason) error {
	done := seg
	done.Reason = reason

	confirmed := seg
	st.confirmed = &confirmed
	st.confirmedSize = 0
	if fi, err := os.Stat(seg.Path); err == nil {
		st.confirmedSize = fi.Size()
	}
	return w.push(done)
}

func (w *Watcher) push(seg Segment) error {
	n := w.fifo.push(seg)
	if n > w.opt.FIFOMaxLen {
		// 처리 속도가 입력 속도를 못 따라간다. 무한정 쌓느니 죽고 재기동해
		// Scan 으로 따라잡는 편이 낫다(9절 L2, D8 과 같은 전략).
		return fmt.Errorf("내부 FIFO 가 상한을 넘었다: len=%d max=%d", n, w.opt.FIFOMaxLen)
	}
	if n > w.opt.FIFOWarnLen {
		w.log.Warn("fifo_backlog", "len", n, "warn_len", w.opt.FIFOWarnLen, "max_len", w.opt.FIFOMaxLen)
	}
	return nil
}

// signalRescan 은 논블로킹 coalescing send 다. eventLoop 이 여기서 멈추면 안 된다.
func (w *Watcher) signalRescan() {
	select {
	case w.rescans <- struct{}{}:
	default:
	}
}

// emitLoop 은 FIFO 에서 꺼내 크기 안정을 기다린 뒤 Completed 로 내보낸다.
// 여기서는 블로킹해도 된다 — inotify 와 무관한 고루틴이기 때문이다.
//
// TODO(C1): settle 이 직렬이라 앞 파일이 막히면 뒤가 전부 기다린다(HOL 블로킹).
// 트리거는 "다중 스트림 동시 방송"이며, 그때는 스트림별 settle 워커로 나눈다.
// 이번 범위의 보호장치는 FIFO 임계(WARN/ERROR 후 종료)다.
func (w *Watcher) emitLoop(ctx context.Context) {
	defer w.wg.Done()
	defer w.cancel()

	for {
		seg, ok := w.fifo.pop(ctx)
		if !ok {
			return
		}

		if _, err := Settle(ctx, seg.Path, w.opt.Settle); err != nil {
			if ctx.Err() != nil {
				return
			}
			// 안정되지 않았어도 내보낸다. 인덱서의 H5 가 다시 재고 판정한다.
			w.log.Warn("settle_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err)
		}

		select {
		case <-ctx.Done():
			return
		case w.completed <- seg:
		}
	}
}

// fail 은 사유 있는 종료를 기록한다. 최초 1회만 기록해 원인을 덮어쓰지 않는다.
func (w *Watcher) fail(err error) {
	w.closeOnce.Do(func() {
		w.waitErr = err
		w.log.Error("watcher_stopped", "err", err)
	})
}

func stateOf(states map[string]*streamState, streamID string) *streamState {
	st, ok := states[streamID]
	if !ok {
		st = &streamState{lastEvent: time.Now()}
		states[streamID] = st
	}
	return st
}

func maxDuration(a, b time.Duration) time.Duration {
	if a > b {
		return a
	}
	return b
}

// ---------------------------------------------------------------------------
// 내부 FIFO — 먼저 넣은 것이 먼저 나오는 대기줄.
// eventLoop 이 뒷단 처리 속도에 발이 묶이지 않게 하는 완충 장치다.
// ---------------------------------------------------------------------------

type segmentFIFO struct {
	mu     sync.Mutex
	items  []Segment
	signal chan struct{}
}

func newSegmentFIFO() *segmentFIFO {
	return &segmentFIFO{signal: make(chan struct{}, 1)}
}

// push 는 넣은 뒤의 길이를 돌려준다. 절대 블로킹하지 않는다.
func (f *segmentFIFO) push(seg Segment) int {
	f.mu.Lock()
	f.items = append(f.items, seg)
	n := len(f.items)
	f.mu.Unlock()

	select {
	case f.signal <- struct{}{}:
	default:
	}
	return n
}

// pop 은 항목이 생길 때까지 기다린다. ctx 취소 시 ok=false 를 돌려준다.
func (f *segmentFIFO) pop(ctx context.Context) (Segment, bool) {
	for {
		f.mu.Lock()
		if len(f.items) > 0 {
			seg := f.items[0]
			f.items = f.items[1:]
			f.mu.Unlock()
			return seg, true
		}
		f.mu.Unlock()

		select {
		case <-ctx.Done():
			return Segment{}, false
		case <-f.signal:
		}
	}
}
