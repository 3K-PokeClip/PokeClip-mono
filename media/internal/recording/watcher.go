package recording

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
)

// WatcherOptions 는 폴더 감시자의 설정값이다.
type WatcherOptions struct {
	Root string
	// IdleTimeout 만큼 아무 이벤트가 없으면 방송이 멈췄다고 보고 pending 파일을 확정한다.
	IdleTimeout time.Duration
	// RescanEvery 는 안전망 재스캔 주기다. 타이머의 소유자는 main 루프 하나이며(D10),
	// 워처는 이 값을 읽지 않는다. 같은 일이 두 곳에서 예약되면 재스캔이 이중으로 돈다.
	RescanEvery time.Duration
	// DeferMaxCycles 는 되돌려받은 세그먼트를 몇 사이클까지 붙잡아 둘지의 상한이다.
	// 한 사이클은 IdleTimeout 이다. 이 상한을 넘으면 방출 장벽을 풀고 그대로 내보낸다 -
	// 끝내 안정되지 않는 병적 파일 하나가 그 스트림 전체를 영구 정지시키지 않게 하는 안전장치다.
	DeferMaxCycles int
	// MaxWatchDirs 는 감시 디렉토리 수 상한이다. 초과하면 ERROR 를 한 번 남기고 신규 등록을 무시한다.
	// inotify watch 는 커널 자원이라 무한정 늘릴 수 없다.
	MaxWatchDirs int
	Settle       SettleOptions
	// FIFOWarnLen 을 넘으면 WARN, FIFOMaxLen 을 넘으면 ERROR 후 워처를 종료한다.
	// 내부 FIFO 의 소유자가 Watcher 이므로 이 설정도 여기 있다.
	FIFOWarnLen int
	FIFOMaxLen  int
	Log         *slog.Logger
}

// DefaultWatcherOptions 는 설계 2.1절의 기본값이다.
func DefaultWatcherOptions(root string, log *slog.Logger) WatcherOptions {
	return WatcherOptions{
		Root:           root,
		IdleTimeout:    10 * time.Second,
		RescanEvery:    5 * time.Minute,
		Settle:         DefaultSettleOptions(),
		FIFOWarnLen:    256,
		FIFOMaxLen:     4096,
		DeferMaxCycles: 3,
		MaxWatchDirs:   1024,
		Log:            log,
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

	// watchedMu 가 지키는 것들. 등록은 Start(초기 walk)와 eventLoop 이,
	// 조회는 테스트가 한다.
	watchedMu   sync.Mutex
	watched     map[string]struct{}
	limitWarned bool
	// regrowInFlight 는 같은 파일의 재성장 후보가 FIFO 에 중복으로 쌓이는 것을 막는다.
	// eventLoop 이 세우고 emitLoop 이 내보낸 뒤 지운다.
	regrowMu       sync.Mutex
	regrowInFlight map[string]struct{}

	// emitSize 는 경로별로 마지막에 내보낼 때의 크기다. 재성장 후보를 거를 때 쓴다.
	//
	// TODO(C3): indexed 맵과 같은 이유로 정리 정책이 없다. 트리거도 같다(단일 프로세스 장기 실행).
	emitSizeMu sync.Mutex
	emitSize   map[string]int64

	// barriers 는 스트림별 방출 장벽이다. 값은 그 스트림에서 보류 중인 가장 이른 StartWall 이며,
	// emitLoop 은 그보다 늦은 같은 스트림 항목을 방출하지 않는다.
	//
	// 왜 필요한가: 보류(deferred) 항목은 FIFO 밖에 있으므로 FIFO 의 정렬로는 순서를 지킬 수 없다.
	// 보류 중인 O 를 기다리는 사이 더 늦은 P 가 큐를 떠나 인덱싱되면 커서가 P 의 시각으로
	// 전진하고, 뒤늦게 풀려난 O 는 늦은 세그먼트로 영구 폐기된다.
	// 장벽은 스트림 단위라 다른 스트림의 방출은 막지 않는다.
	barrierMu sync.Mutex
	barriers  map[string]time.Time
}

// NewWatcher 는 감시자를 만든다. 이 시점에는 아직 어떤 폴더도 감시하지 않는다.
func NewWatcher(opt WatcherOptions) (*Watcher, error) {
	if opt.Log == nil {
		return nil, errors.New("WatcherOptions.Log 가 비어 있다")
	}
	// 0을 그대로 두면 상한 검사가 항상 걸려 감시가 통째로 죽는다. 조용히 기본값으로 때우지 않는다.
	if opt.MaxWatchDirs <= 0 {
		return nil, fmt.Errorf("WatcherOptions.MaxWatchDirs 는 1 이상이어야 한다: %d", opt.MaxWatchDirs)
	}
	// 0이면 되돌리자마자 포기하게 되어 스로틀이 무력해진다.
	if opt.DeferMaxCycles <= 0 {
		return nil, fmt.Errorf("WatcherOptions.DeferMaxCycles 는 1 이상이어야 한다: %d", opt.DeferMaxCycles)
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

		watched:        map[string]struct{}{},
		regrowInFlight: map[string]struct{}{},
		emitSize:       map[string]int64{},
		barriers:       map[string]time.Time{},
	}, nil
}

// Close 는 fsnotify 핸들을 닫는다. **Start 가 실패한 뒤의 정리 전용**이다(f6i — 강등이
// fd·inotify 인스턴스를 누수하면 재시도가 곧 자원 고갈이다). Start 가 성공한 뒤에는
// 부르지 않는다 — 그때의 소유자는 eventLoop(defer fsw.Close)다.
func (w *Watcher) Close() error {
	return w.fsw.Close()
}

// Start 는 동기다. 반환된 시점에는 감시 등록이 이미 끝나 있으므로,
// 이후 생성되는 파일은 유실되지 않는다. "walk 종료 - watch 등록" 공백이 구조적으로 없다.
func (w *Watcher) Start(ctx context.Context) error {
	// 루트 자체를 못 붙이면 감시할 대상이 없다는 뜻이라 그때만 실패시킨다.
	// 개별 하위 디렉토리 실패는 WARN 으로 강등하고 주기 재스캔에 맡긴다(아래 addWatchTree).
	if err := w.addWatch(w.opt.Root); err != nil {
		return fmt.Errorf("루트 감시 등록 실패 %q: %w", w.opt.Root, err)
	}
	w.addWatchTree(w.opt.Root)

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
		// 무손실 계약을 지킬 수 없는 상황이다. 조용히 버리지 않고 ERROR 로 남기고
		// 재스캔 신호를 올려 파일 자체는 반드시 다시 발견되게 한다.
		w.log.Error("adopt_dropped",
			"stream_id", seg.StreamID, "path", seg.Path,
			"note", "재스캔 신호로 복구를 시도한다")
		w.signalRescan()
	}
}

// addWatchTree 는 주어진 경로 아래의 디렉토리를 재귀적으로 감시 등록한다.
//
// 개별 실패는 WARN 으로 강등하고 계속 간다. 하드 에러로 두면 디렉토리 하나가
// 프로세스 전체를 crash loop 에 빠뜨리고, 그동안 나머지 스트림도 통째로 멈춘다.
// 놓친 디렉토리는 주기 재스캔이 결국 따라잡는다.
//
// 주어진 경로 자신도 등록 대상이다. 신규 디렉토리 이벤트에서 이 함수를 쓰는데,
// live/kr/demo 처럼 깊은 트리가 한꺼번에 생기면 최상위 CREATE 이벤트 하나만 오므로
// 그 디렉토리와 안쪽을 함께 훑어 등록해야 한다. 이미 등록된 경로는 addWatch 가 걸러 낸다.
func (w *Watcher) addWatchTree(root string) {
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			w.log.Warn("watch_walk_failed", "path", path, "err", err)
			// 읽을 수 없는 디렉토리는 건너뛰고 형제 디렉토리는 계속 훑는다.
			if d != nil && d.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if !d.IsDir() {
			return nil
		}
		if err := w.addWatch(path); err != nil {
			w.log.Warn("watch_add_failed", "path", path, "err", err)
		}
		return nil
	})
	if err != nil {
		w.log.Warn("watch_tree_failed", "root", root, "err", err)
	}
}

// addWatch 는 디렉토리 하나를 감시 등록한다. 상한을 넘으면 조용히 무시하되 ERROR 를 한 번 남긴다.
func (w *Watcher) addWatch(dir string) error {
	w.watchedMu.Lock()
	if _, ok := w.watched[dir]; ok {
		w.watchedMu.Unlock()
		return nil
	}
	if len(w.watched) >= w.opt.MaxWatchDirs {
		warn := !w.limitWarned
		w.limitWarned = true
		w.watchedMu.Unlock()
		if warn {
			w.log.Error("watch_dir_limit_exceeded",
				"limit", w.opt.MaxWatchDirs, "path", dir,
				"note", "신규 디렉토리 감시를 무시한다. 주기 재스캔이 파일은 계속 따라잡는다")
		}
		return nil
	}
	w.watchedMu.Unlock()

	if err := w.fsw.Add(dir); err != nil {
		return err
	}

	w.watchedMu.Lock()
	w.watched[dir] = struct{}{}
	w.watchedMu.Unlock()
	return nil
}

// removeWatchTree 는 사라진 경로와 그 하위를 감시 맵에서 지운다.
// fsnotify 는 삭제된 디렉토리의 watch 를 스스로 정리하지만 우리 맵과 카운터는 따로 관리하므로
// 여기서 맞춰 준다. 맵이 새면 MaxWatchDirs 상한이 실제보다 빨리 차 버린다.
func (w *Watcher) removeWatchTree(path string) {
	prefix := path + string(filepath.Separator)

	w.watchedMu.Lock()
	removed := make([]string, 0, 1)
	for dir := range w.watched {
		if dir == path || strings.HasPrefix(dir, prefix) {
			delete(w.watched, dir)
			removed = append(removed, dir)
		}
	}
	w.watchedMu.Unlock()

	for _, dir := range removed {
		// fsnotify 가 이미 정리했을 수 있으므로 실패는 무시한다.
		_ = w.fsw.Remove(dir)
	}
	if len(removed) > 0 {
		w.log.Debug("watch_removed", "path", path, "count", len(removed))
	}
}

// isDir 은 경로가 디렉토리인지 즉시 확인한다. 사라졌으면 false 다.
func isDir(path string) bool {
	fi, err := os.Stat(path)
	return err == nil && fi.IsDir()
}

// isWatched / watchedCount 는 테스트가 감시 등록 상태를 직접 확인하는 데 쓴다.
func (w *Watcher) isWatched(dir string) bool {
	w.watchedMu.Lock()
	defer w.watchedMu.Unlock()
	_, ok := w.watched[dir]
	return ok
}

func (w *Watcher) watchedCount() int {
	w.watchedMu.Lock()
	defer w.watchedMu.Unlock()
	return len(w.watched)
}

// streamState 는 스트림(디렉토리) 하나의 감시 상태다. 스트림별로 완전히 독립이다.
type streamState struct {
	// pending 은 아직 완성 판정을 받지 않은, 지금 쓰이고 있을 파일이다.
	pending *Segment
	// confirmed 는 직전에 확정한 파일이다. 여기에 WRITE 가 오면 재성장 후보다.
	confirmed *Segment
	lastEvent time.Time
	// deferred 는 되돌려받았으나 아직 확정하면 안 되는 세그먼트들이다.
	// pending 보다 오래된 파일을 그 자리에서 다시 확정하면, 아직 자라는 파일이
	// 곧바로 emitLoop 의 크기 안정 대기를 붙잡고 되돌아오기를 반복한다.
	deferred []deferredSegment
	// deferCycles 는 경로별로 몇 번이나 보류됐는지다. DeferMaxCycles 를 넘으면 포기한다.
	//
	// TODO(C3): indexed·emitSize 맵과 같은 이유로 정리 정책이 없다. 트리거도 같다.
	deferCycles map[string]int
}

// deferredSegment 는 notBefore 이후에야 확정 후보로 내보낼 세그먼트다.
type deferredSegment struct {
	seg       Segment
	notBefore time.Time
}

// eventLoop 은 fsnotify 이벤트를 상태 머신에 넣고 확정 후보를 FIFO 에 push 만 한다.
//
// 이 고루틴은 절대 파일 I/O 를 하지 않는다. 여기서 멈추면 커널의 inotify 큐가 넘쳐
// 알림이 조용히 버려진다(D10). 크기 확인(stat)과 안정 대기는 전부 emitLoop 과
// 인덱서 쪽으로 밀어냈다 — 판정에 크기가 필요한 곳은 이미 그쪽에 다 있다.
// 예외는 감시 등록(디렉토리 walk)뿐이며, 이는 무유실을 위해 여기서만 할 수 있는 일이다.
//
// 컴파일러가 이 규칙을 잡아 주지 않으므로 주석과 리뷰로만 지킨다.
func (w *Watcher) eventLoop(ctx context.Context) {
	defer w.wg.Done()
	defer w.cancel() // 이 루프가 죽으면 emitLoop 도 함께 끝낸다
	defer w.fsw.Close()

	states := map[string]*streamState{}

	idleTick := time.NewTicker(maxDuration(w.opt.IdleTimeout/2, 10*time.Millisecond))
	defer idleTick.Stop()
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
			if err := w.onAdopt(states, seg); err != nil {
				w.fail(err)
				return
			}

		case <-idleTick.C:
			if err := w.onIdleTick(states); err != nil {
				w.fail(err)
				return
			}

		}
	}
}

func (w *Watcher) onEvent(states map[string]*streamState, ev fsnotify.Event) error {
	// 디렉토리가 사라지면 감시 맵에서도 지운다. 남겨 두면 같은 이름으로 다시 만들어졌을 때
	// addWatch 가 "이미 등록됨"으로 건너뛰어 그 디렉토리가 영영 감시 밖에 남는다.
	if ev.Has(fsnotify.Remove) || ev.Has(fsnotify.Rename) {
		w.removeWatchTree(ev.Name)
	}

	seg, err := ParseSegmentPath(w.opt.Root, ev.Name)

	switch {
	case err == nil:
		// 세그먼트 파일이다.
	case errors.Is(err, ErrInvalidStreamID):
		// 세그먼트 파일 모양이지만 스트림 이름이 화이트리스트를 통과하지 못했다.
		// 조용히 버리면 "왜 안 들어오지"를 아무도 모르므로 스트림당 한 번은 남긴다.
		w.warnRejectedStream(states, ev.Name, err)
		return nil
	default:
		// 세그먼트 파일이 아니다. 디렉토리라면 감시 대상에 넣고 전수 점검을 요청한다.
		//
		// 여기서 stat 한 번을 쓴다. eventLoop 이 금지하는 것은 "대기"이지 즉시 반환하는
		// 시스템 호출이 아니다. stat 없이 무조건 트리를 훑으면, 녹화 폴더에 섞인 임시 파일
		// 하나가 생길 때마다 전수 walk 와 재스캔 신호가 따라붙는다(헛일이 쌓인다).
		if ev.Has(fsnotify.Create) && isDir(ev.Name) {
			w.addWatchTree(ev.Name)
			w.signalRescan()
		}
		return nil
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

// warnRejectedStream 은 거부된 스트림 디렉토리마다 WARN 을 한 번만 남긴다.
// 4초마다 같은 경고가 쏟아지면 진짜 신호가 묻힌다.
func (w *Watcher) warnRejectedStream(states map[string]*streamState, path string, cause error) {
	dir := filepath.Dir(path)
	key := rejectedStreamKey + dir
	if _, seen := states[key]; seen {
		return
	}
	states[key] = &streamState{}
	w.log.Warn("stream_id_rejected", "dir", dir, "err", cause)
}

// rejectedStreamKey 는 거부된 스트림 표시를 정상 스트림 상태와 섞이지 않게 하는 접두다.
// stream_id 화이트리스트가 슬래시를 금지하므로 이 접두는 정상 stream_id 와 절대 충돌하지 않는다.
const rejectedStreamKey = "/rejected/"

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
//
// 여기서 크기를 비교하지 않는 이유: eventLoop 은 파일 I/O 를 하지 않는다.
// 실제로 자랐는지는 correctTail 의 "변화 없으면 DB 를 건드리지 않는다" 검사가 판정한다.
// 대신 같은 파일의 후보가 FIFO 에 겹겹이 쌓이지 않도록 in-flight 표시로 한 건만 유지한다.
func (w *Watcher) onWrite(st *streamState, seg Segment) error {
	if st.confirmed == nil || st.confirmed.Path != seg.Path {
		return nil
	}
	if !w.markRegrowInFlight(seg.Path) {
		return nil
	}

	regrown := seg
	regrown.Reason = ReasonRegrown
	return w.push(regrown)
}

// markRegrowInFlight 는 이 경로의 재성장 후보가 아직 대기 중이 아니면 표시하고 true 를 돌려준다.
func (w *Watcher) markRegrowInFlight(path string) bool {
	w.regrowMu.Lock()
	defer w.regrowMu.Unlock()
	if _, ok := w.regrowInFlight[path]; ok {
		return false
	}
	w.regrowInFlight[path] = struct{}{}
	return true
}

func (w *Watcher) clearRegrowInFlight(path string) {
	w.regrowMu.Lock()
	defer w.regrowMu.Unlock()
	delete(w.regrowInFlight, path)
}

// onAdopt 는 되돌려받은 세그먼트를 pending 슬롯에 등록한다.
//
// 무조건 덮어쓰면 안 된다. 되돌아온 파일이 현재 pending 보다 오래된 것이면,
// 덮어쓰는 순간 최신 파일이 pending 에서 밀려나 어떤 완성 신호도 받지 못하고
// 결국 늦은 세그먼트로 폐기된다(영구 유실). 그래서 더 늦은 쪽만 pending 에 남기고
// 밀려나는 쪽(더 오래된 쪽)은 확정 후보로 내보낸다 — 뒤에 더 늦은 파일이 있다는 것이
// 곧 그 파일이 다 써졌다는 신호이기 때문이다.
func (w *Watcher) onAdopt(states map[string]*streamState, seg Segment) error {
	st := stateOf(states, seg.StreamID)
	st.lastEvent = time.Now()

	adopted := seg
	if st.pending == nil || st.pending.Path == seg.Path {
		st.pending = &adopted
		return nil
	}

	if adopted.StartWall.After(st.pending.StartWall) {
		displaced := *st.pending
		st.pending = &adopted
		w.log.Debug("adopt_replaced_pending",
			"stream_id", seg.StreamID, "kept", seg.Path, "flushed", displaced.Path)
		return w.confirm(st, displaced, ReasonNextFile)
	}

	// 되돌아온 쪽이 더 오래됐다. pending 은 그대로 두되, 이쪽을 그 자리에서 다시 확정하지는 않는다.
	// 아직 자라는 파일이면 확정 -> 안정 대기 -> 되돌림이 쉼 없이 돌며 처리 구간을 점유한다.
	// 설계 H5 의 "재시도 간격은 IdleTimeout 이상"을 유휴 타이머 경로로 되살린다.
	deferred, err := w.deferSegment(st, adopted)
	if err != nil {
		return err
	}
	if deferred {
		w.log.Debug("adopt_deferred",
			"stream_id", seg.StreamID, "kept_pending", st.pending.Path,
			"deferred", seg.Path, "retry_after", w.opt.IdleTimeout)
	}
	return nil
}

// deferSegment 는 같은 경로가 이미 보류 중이면 시각만 미루고, 아니면 새로 담는다.
// 상한을 넘긴 경로는 보류하지 않고 그대로 내보낸다(유한 포기). 반환값이 true 면 보류했다는 뜻이다.
func (w *Watcher) deferSegment(st *streamState, seg Segment) (bool, error) {
	if st.deferCycles == nil {
		st.deferCycles = map[string]int{}
	}
	st.deferCycles[seg.Path]++

	if st.deferCycles[seg.Path] > w.opt.DeferMaxCycles {
		// 끝내 안정되지 않는 파일이다. 더 붙잡으면 이 스트림이 영구 정지한다.
		// 장벽을 풀고 그대로 내보낸다 - 뒤이어 인덱서의 H3 가 늦은 세그먼트로 판정해
		// late_segment_skipped ERROR 를 남기며, 그것이 설계 9절 L5 의 알람 경로다
		// (자동 복구는 없고 파일은 :ro 로 보존되므로 로그를 보고 사람이 복구한다).
		w.log.Warn("deferred_give_up",
			"stream_id", seg.StreamID, "path", seg.Path,
			"cycles", st.deferCycles[seg.Path], "limit", w.opt.DeferMaxCycles,
			"note", "장벽을 풀고 그대로 방출한다. 늦은 세그먼트로 폐기될 수 있다(L5 수동 복구)")
		delete(st.deferCycles, seg.Path)
		// 큐 삽입과 장벽 해제를 한 임계구역으로 묶는다. 순서가 갈리면 그 사이에
		// emitLoop 이 깨어나 더 늦은 항목을 집어 가고, 흘려보내기로 한 이 세그먼트는
		// 순서까지 잃어 손쓸 방법이 없어진다.
		n := w.fifo.pushThen(
			[]Segment{w.markConfirmed(st, seg, ReasonNextFile)},
			func() { w.applyBarrier(st, seg.StreamID) },
		)
		return false, w.checkFIFOLen(n)
	}

	notBefore := time.Now().Add(w.opt.IdleTimeout)
	found := false
	for i := range st.deferred {
		if st.deferred[i].seg.Path == seg.Path {
			st.deferred[i].seg = seg
			st.deferred[i].notBefore = notBefore
			found = true
			break
		}
	}
	if !found {
		st.deferred = append(st.deferred, deferredSegment{seg: seg, notBefore: notBefore})
	}
	w.refreshBarrier(st, seg.StreamID)
	return true, nil
}

// refreshBarrier 는 스트림의 장벽을 보류 항목 중 가장 이른 StartWall 로 맞춘다.
// 보류가 하나도 없으면 장벽을 없애고 emitLoop 을 깨워 막혀 있던 항목을 흘려보낸다.
func (w *Watcher) refreshBarrier(st *streamState, streamID string) {
	w.applyBarrier(st, streamID)
	// 장벽이 느슨해졌을 수 있으니 emitLoop 이 다시 판정하도록 깨운다.
	w.fifo.wake()
}

// applyBarrier 는 장벽 맵만 갱신한다. 깨우지 않으므로 FIFO 락 안에서 호출해도 안전하다.
// 잠금 순서는 항상 FIFO -> 장벽이며, pop 도 같은 순서를 쓴다.
func (w *Watcher) applyBarrier(st *streamState, streamID string) {
	var earliest time.Time
	for _, d := range st.deferred {
		if earliest.IsZero() || d.seg.StartWall.Before(earliest) {
			earliest = d.seg.StartWall
		}
	}

	w.barrierMu.Lock()
	if earliest.IsZero() {
		delete(w.barriers, streamID)
	} else {
		w.barriers[streamID] = earliest
	}
	w.barrierMu.Unlock()
}

// blockedByBarrier 는 이 세그먼트가 같은 스트림의 보류 항목보다 늦어서 아직 나갈 수 없는지 본다.
// 장벽과 같은 시각이거나 그보다 이른 항목은 막지 않는다 - 보류 항목 자신이 여기 해당한다.
func (w *Watcher) blockedByBarrier(seg Segment) bool {
	w.barrierMu.Lock()
	defer w.barrierMu.Unlock()
	wall, ok := w.barriers[seg.StreamID]
	return ok && seg.StartWall.After(wall)
}

// releaseDeferred 는 대기 시간이 지난 보류 세그먼트를 확정 후보로 내보낸다.
// FIFO 가 스트림별 StartWall 순서를 지키므로, 늦게 풀려나도 순서는 어긋나지 않는다.
func (w *Watcher) releaseDeferred(st *streamState, streamID string) error {
	if len(st.deferred) == 0 {
		return nil
	}

	now := time.Now()
	kept := st.deferred[:0]
	var released []Segment
	for _, d := range st.deferred {
		if now.Before(d.notBefore) {
			kept = append(kept, d)
			continue
		}
		released = append(released, d.seg)
	}
	st.deferred = kept
	if len(released) == 0 {
		return nil
	}

	// 큐 삽입과 장벽 갱신을 한 임계구역으로 묶는다.
	//
	// 둘을 나누면 그 사이에 창이 열린다. 장벽을 먼저 풀면 emitLoop 이 깨어나 더 늦은 항목을
	// 집어 가고, 뒤늦게 들어온 이 세그먼트는 늦은 세그먼트로 영구 폐기된다.
	// pop 도 같은 FIFO 락 아래에서 대상을 고르므로, 이 구간의 중간 상태는 pop 에게 보이지 않는다.
	prepared := make([]Segment, 0, len(released))
	for _, seg := range released {
		prepared = append(prepared, w.markConfirmed(st, seg, ReasonNextFile))
	}
	return w.checkFIFOLen(w.fifo.pushThen(prepared, func() { w.applyBarrier(st, streamID) }))
}

// onIdleTick 은 한참 조용한 스트림의 pending 파일을 확정한다.
// 마지막 조각은 후속 파일이 영영 안 생기므로 이 장치가 없으면 영영 기록되지 않는다.
func (w *Watcher) onIdleTick(states map[string]*streamState) error {
	for streamID, st := range states {
		if err := w.releaseDeferred(st, streamID); err != nil {
			return err
		}
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
	return w.checkFIFOLen(w.fifo.push(w.markConfirmed(st, seg, reason)))
}

// markConfirmed 는 재성장 감시 대상을 갱신하고 확정 사유를 붙인 세그먼트를 돌려준다.
// 큐에 넣지는 않는다 - 삽입 시점을 호출자가 정해야 하는 경로가 있기 때문이다(releaseDeferred).
// 이 상태는 eventLoop 만 만지므로 락이 필요 없다.
func (w *Watcher) markConfirmed(st *streamState, seg Segment, reason CompletionReason) Segment {
	confirmed := seg
	st.confirmed = &confirmed

	done := seg
	done.Reason = reason
	return done
}

func (w *Watcher) push(seg Segment) error {
	return w.checkFIFOLen(w.fifo.push(seg))
}

// checkFIFOLen 은 넣은 뒤의 길이로 경고와 종료를 판정한다.
func (w *Watcher) checkFIFOLen(n int) error {
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
		seg, ok := w.fifo.pop(ctx, w.blockedByBarrier)
		if !ok {
			return
		}

		// 재성장 후보는 크기가 실제로 늘었을 때만 통과시킨다.
		// eventLoop 은 크기를 보지 않고 WRITE 이벤트만으로 후보를 올리므로,
		// 크기가 그대로인 WRITE(메타 갱신 등)까지 안정 대기 비용을 물면 직렬 처리가 낭비된다.
		if seg.Reason == ReasonRegrown && !w.grewSinceLastEmit(seg.Path) {
			w.clearRegrowInFlight(seg.Path)
			continue
		}

		fi, err := Settle(ctx, seg.Path, w.opt.Settle)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			// 안정되지 않았어도 내보낸다. 인덱서의 H5 가 다시 재고 판정한다.
			w.log.Warn("settle_failed", "stream_id", seg.StreamID, "path", seg.Path, "err", err)
		}
		if fi != nil {
			w.recordEmitSize(seg.Path, fi.Size())
		}

		select {
		case <-ctx.Done():
			return
		case w.completed <- seg:
		}
		if seg.Reason == ReasonRegrown {
			w.clearRegrowInFlight(seg.Path)
		}
	}
}

// grewSinceLastEmit 은 마지막으로 내보낼 때보다 파일이 커졌는지 본다.
// 여기(emitLoop)는 대기가 허용되는 자리라 stat 을 해도 inotify 를 막지 않는다.
func (w *Watcher) grewSinceLastEmit(path string) bool {
	fi, err := os.Stat(path)
	if err != nil {
		// 파일이 사라졌으면 뒤에서 판정하게 그대로 통과시킨다.
		return true
	}
	w.emitSizeMu.Lock()
	defer w.emitSizeMu.Unlock()
	last, seen := w.emitSize[path]
	return !seen || fi.Size() > last
}

func (w *Watcher) recordEmitSize(path string, size int64) {
	w.emitSizeMu.Lock()
	w.emitSize[path] = size
	w.emitSizeMu.Unlock()
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
		st = &streamState{lastEvent: time.Now(), deferCycles: map[string]int{}}
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
//
// 같은 스트림 안에서는 StartWall 오름차순을 유지하도록 끼워 넣는다.
// 밀려난 오래된 확정 후보가 뒤에 붙으면, 앞선 항목이 먼저 처리돼 인덱서의 커서가
// 전진하고 그 뒤에 나오는 오래된 항목은 전부 늦은 세그먼트로 폐기된다.
// 다른 스트림끼리는 서로 순서를 강제하지 않는다 - 스트림별로 독립한 타임라인이기 때문이다.
func (f *segmentFIFO) push(seg Segment) int {
	f.mu.Lock()
	f.items = insertByStreamWall(f.items, seg)
	n := len(f.items)
	f.mu.Unlock()

	f.wake()
	return n
}

// insertByStreamWall 은 같은 스트림에서 자신보다 늦은 첫 항목 앞에 끼워 넣는다.
// 같은 스트림의 늦은 항목이 없으면 맨 뒤에 붙는다.
func insertByStreamWall(items []Segment, seg Segment) []Segment {
	at := len(items)
	for i, it := range items {
		if it.StreamID == seg.StreamID && it.StartWall.After(seg.StartWall) {
			at = i
			break
		}
	}
	items = append(items, Segment{})
	copy(items[at+1:], items[at:])
	items[at] = seg
	return items
}

// pop 은 방출해도 되는 첫 항목을 꺼낸다. ctx 취소 시 ok=false 를 돌려준다.
//
// blocked 가 nil 이 아니면 그 술어가 true 인 항목은 건너뛴다. 전부 막혀 있으면 신호를
// 기다린다 - 장벽이 풀릴 때 refreshBarrier 가 wake 로 깨워 준다.
func (f *segmentFIFO) pop(ctx context.Context, blocked func(Segment) bool) (Segment, bool) {
	for {
		f.mu.Lock()
		for i, seg := range f.items {
			if blocked != nil && blocked(seg) {
				continue
			}
			f.items = append(f.items[:i], f.items[i+1:]...)
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

// pushThen 은 항목들을 넣고, 같은 임계구역 안에서 after 를 실행한 뒤 넣은 결과 길이를 돌려준다.
//
// pop 이 대상을 고르는 동안에도 같은 락을 잡으므로, "넣기"와 after(장벽 갱신)의 중간 상태가
// pop 에게 보이지 않는다. 이것이 순서 역전 창을 없애는 지점이다.
// after 안에서는 FIFO 를 다시 만지면 안 된다(재진입 교착).
func (f *segmentFIFO) pushThen(segs []Segment, after func()) int {
	f.mu.Lock()
	for _, seg := range segs {
		f.items = insertByStreamWall(f.items, seg)
	}
	n := len(f.items)
	if after != nil {
		after()
	}
	f.mu.Unlock()

	f.wake()
	return n
}

// wake 는 대기 중인 pop 을 한 번 깨운다. 논블로킹이다.
func (f *segmentFIFO) wake() {
	select {
	case f.signal <- struct{}{}:
	default:
	}
}
