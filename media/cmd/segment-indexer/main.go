// Command segment-indexer 는 MediaMTX 가 떨어뜨린 세그먼트를 감지해
// stream_segments 표에 한 줄씩 기록하는 사이드카다.
//
// 이 파일은 조립과 기동 순서만 담당한다. 정책은 internal/indexer 에, 파일 감지는
// internal/recording 에, DB 는 internal/index 에 있다.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fmp4meta"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxhook"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

func main() {
	err := run()
	// SIGTERM 으로 끝난 것은 정상 종료다. 이것을 실패로 보고하면 오케스트레이터가
	// 멀쩡한 종료를 장애로 오인하고 재기동 루프에 넣는다.
	if errors.Is(err, context.Canceled) {
		return
	}
	if err != nil {
		// 설정 오류는 로거를 만들기 전에도 날 수 있으므로 표준 에러로 직접 적는다.
		fmt.Fprintf(os.Stderr, "segment-indexer 종료: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		return err
	}

	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel}))
	cfg.Watcher.Log = log

	// SIGTERM 을 받으면 ctx 가 취소되고, 대기 중인 모든 함수가 즉시 빠져나온다.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	// --- 1. 기동: 설정 -> DB 연결 -> (개발 환경이면) 표 생성 ---
	pool, err := pgxpool.New(ctx, cfg.PGDSN)
	if err != nil {
		// DSN 에는 비밀번호가 들어 있으므로 에러에 싣지 않는다.
		return fmt.Errorf("DB 연결 풀 생성 실패: %w", err)
	}
	defer pool.Close()

	if err := pool.Ping(ctx); err != nil {
		return fmt.Errorf("DB 접속 확인 실패: %w", err)
	}
	if cfg.EnsureSchema {
		// EnsureSchema 는 Store 인터페이스 밖의 자유 함수다(D7).
		// 정본 DDL(1번 소유 — ddl.go)을 부팅 시 보장한다. 마이그레이션 도구 도입 시 이 3줄과 ddl.go 만 바꾸면 된다(G7).
		if err := index.EnsureSchema(ctx, pool); err != nil {
			return err
		}
		log.Info("schema_ensured", "note", "정본 DDL(1번 소유). 컬럼 변경은 3번 승인 — 계약-세그먼트인덱스 4절")
	}

	// 장부에 협력자 둘을 끼운다(계획 4.6): 세션 결정자와 ③ 키 파생.
	// 세션 판정 정책·키 형상은 각각 자기 패키지에 있고, 여기서 이어 붙이는 것이 전부다.
	store := index.NewPGStore(pool,
		newSessionDecider(cfg.SessionFloorSlack, cfg.ObsFresh, log), playback.SegKey)

	// 업로더가 파일을 여는 유일한 손잡이다. 루트를 못 열면 기동 실패다 —
	// 그 상태로 진행하면 모든 PUT 이 열기 단계에서 실패한다.
	root, err := os.OpenRoot(cfg.SegmentRoot)
	if err != nil {
		return fmt.Errorf("세그먼트 루트 열기 실패 root=%q: %w", cfg.SegmentRoot, err)
	}
	defer root.Close()

	up, err := newUploader(ctx, cfg, root, index.NewUploadStore(pool), log)
	if err != nil {
		return err
	}
	// ★ 등록 순서가 계약이다. defer 는 LIFO 이므로 pool.Close(위)보다 **뒤에** 등록된
	//   이 defer 가 **먼저** 실행된다. Shutdown 은 반환 후 UploadStore 를 쓰지 않으므로
	//   풀이 닫히기 전에 반환되어야 한다. 코드 배치가 그 순서를 보장한다(결정 17″).
	defer up.Shutdown()

	// --- 2. 워처 조립·기동 — 실패는 강등이다 ---
	//
	// 주석 ⓐ: 워처 기동이 초기 수집 발사보다 먼저인 이유.
	// Start 는 동기라서 반환 시점에는 감시 등록이 이미 끝나 있다. 순서를 뒤집으면
	// "훑기는 끝났는데 감시는 아직 안 켜진" 공백 구간이 생기고, 그 사이에 만들어진
	// 파일은 훑기에도 안 잡히고 알림도 오지 않아 영구 미아가 된다(설계 3절 2번).
	//
	// 실패 처분(ADR-063 결정 1): 조립·기동 단계 실패 = **강등**(프로세스 유지 + 주기
	// 재시도). 재기동이 상태를 바꿀 수도 있으나 바뀌지 않는 경우가 실재하고, 그때
	// 프로세스 종료는 다른 유입(훅·스캔)까지 죽인다 — 훅 판정(아래 2-1)의 일반화다.
	// 기동 **후** 사망(Done 닫힘)은 반대로 종료다: loop 의 watcherDone case 가 그대로
	// 담당하고 새 프로세스가 실제로 회복시킨다.
	w, wErr := assembleWatcher(ctx, cfg.Watcher)
	if wErr != nil {
		log.Error("watcher_degraded", "value", 1, "err", wErr,
			"note", "워처만 강등된다. 훅·스캔 유입은 계속되고 재장착은 재스캔 주기가 시도한다")
	}
	// 겹 1 — 호출부의 인터페이스 변수 선언. *recording.Watcher 타입 그대로 넘기면
	// typed-nil 이 indexer.New 의 nil 가드(겹 2)를 통과한다.
	var adopt indexer.Adopter
	if w != nil {
		adopt = w
	}
	ix := indexer.New(store, fmp4meta.ProbeDurationMS, adopt, up, cfg.Indexer, log)

	// --- 2-1. 훅 어댑터 (선택) ---
	//
	// HOOK_SPOOL_PATH 가 비어 있으면 Reader 를 **아예 만들지 않는다**. 그러면 아래 두 채널이
	// nil 로 남고 select 의 해당 case 는 영구 비활성이 된다 — 이것이 즉시 롤백 스위치다.
	// 워처(w.Start) 다음에 두는 이유는 훅 조립이 실패해도 파일 감시는 이미 켜져 있게 하기 위해서다.
	// 조립 실패도 **강등이지 사망이 아니다**. 훅은 1차 신호일 뿐이고 벽시계·파일 감시·주기
	// 재스캔이 안전망으로 남으므로, 여기서 return err 하면 훅 설정 오타 하나가 인덱싱 전체를
	// 멈춘다(GH4 위반). 채널을 nil 로 둔 채 계속 간다.
	var hookEvents <-chan mtxhook.Event
	var hookDone <-chan struct{}
	var hookErr func() error
	switch {
	case cfg.HookSpoolPath == "":
		log.Info("hook_reader_disabled", "note", "HOOK_SPOOL_PATH 가 비어 있다. 현행 판정만 쓴다")
	default:
		reader, hookSetupErr := mtxhook.NewReader(mtxhook.ReaderOptions{
			SpoolPath:    cfg.HookSpoolPath,
			PollInterval: cfg.HookPollInterval,
			Log:          log,
		})
		if hookSetupErr == nil {
			// Start 는 동기다. 반환 시점에 시작 오프셋이 확정돼 있다.
			hookSetupErr = reader.Start(ctx)
		}
		if hookSetupErr != nil {
			log.Error("hook_reader_setup_failed", "spool", cfg.HookSpoolPath, "err", hookSetupErr,
				"note", "훅 채널만 강등된다. 인덱싱은 계속된다")
			break
		}
		hookEvents, hookDone, hookErr = reader.Events(), reader.Done(), reader.Wait
		log.Info("hook_reader_started", "spool", cfg.HookSpoolPath, "poll", cfg.HookPollInterval)
	}

	// --- 3. 업로더 기동 + 초기 수집 발사 ---
	//
	// 부수 효과 있는 컴포넌트의 시작은 미루지 않는다(ADR-063 결정 6): 업로더는 boot 에서
	// 켠다 — 초기 수집이 hung FS 에 잡혀도 훅 유입 조각의 업로드 접수가 살아 있어야
	// 한다(f6p). 구 코드가 "초기 Scan 뒤 Start"로 막던 스위퍼 선점은 이제 arm 대기가
	// 막는다: 스위퍼는 첫 완주 수집(loop 의 armSweeper 호출) 또는 폴백 경과까지 안 돈다.
	up.Start(ctx)

	// 초기 수집은 발사만 한다 — 결과는 loop 의 CollectDone case 가 받는다(수집 대기 점유 0).
	// 2번과 이 발사 사이에 생성된 파일이 walk 와 워처 FIFO 양쪽에 잡힐 수 있다.
	// 이는 설계상 허용이며 H2(경로 중복 확인)와 DB UNIQUE 가 흡수한다.
	ix.StartCollect(ctx, cfg.SegmentRoot)

	log.Info("watching", "root", cfg.SegmentRoot,
		"idle_timeout", cfg.Watcher.IdleTimeout, "rescan_every", cfg.Watcher.RescanEvery,
		"settle_wait", cfg.Watcher.Settle.SettleWait, "local_time", time.Now().Format(time.RFC3339))

	// 보류 틱은 초 단위여야 한다. 30s 급이면 마지막 조각의 총 지연이 48초까지 늘어
	// AC1 의 재현성이 깨진다. 티커를 루프 밖에서 만드는 이유는 워처·훅과 같다 —
	// 루프는 신호를 **채널로만** 받고, 그 신호원을 누가 소유하는지는 모른다.
	holdTicker := time.NewTicker(cfg.Indexer.HoldTick)
	defer holdTicker.Stop()

	// --- 4. 메인 루프 ---
	//
	// 결과를 즉시 return 하지 않는다. 정상·오류가 같은 출구로 나가야 defer 로 등록한
	// 종료 절차가 어느 경로에서도 같은 순서로 돈다.
	deps := loopDeps{
		ix: ix, root: cfg.SegmentRoot, rescanEvery: cfg.Watcher.RescanEvery, log: log,
		hookEvents: hookEvents, hookDone: hookDone, hookErr: hookErr,
		uploadResults: up.Results(), holdTicks: holdTicker.C,
		armSweeper: up.ArmSweeper, stallFactor: collectStallFactor,
		reattachWatcher: func() (*recording.Watcher, error) {
			return assembleWatcher(ctx, cfg.Watcher)
		},
	}
	if w != nil {
		deps.watcherDone, deps.watcherErr = w.Done(), w.Wait
		deps.completed, deps.rescans = w.Completed(), w.Rescans()
	} else {
		// 강등 국면 — 네 채널이 nil 로 남고 그 case 들은 영구 비활성이다(훅 채널과 같은
		// 성질). 재장착은 loop 의 재스캔 주기가 시도한다.
		deps.watcherDegraded = true
	}
	loopErr := loop(ctx, deps)
	if loopErr != nil {
		return loopErr
	}
	// 정상 종료도 흔적을 남긴다. 로그가 그냥 끊기면 죽은 것인지 끝난 것인지 구분할 수 없다.
	log.Info("shutdown", "reason", "종료 신호를 받아 정상 종료한다")
	return nil
}

// assembleWatcher 는 워처를 만들고 기동한다. 두 실패점(NewWatcher·Start)을 한 값으로
// 합쳐 강등 판정에 쓴다. Start 실패 시 fsnotify 핸들을 닫는다(f6i) — 강등 상태의
// 주기 재시도가 fd·inotify 인스턴스를 누수하면 재시도 자체가 자원 고갈이 된다.
func assembleWatcher(ctx context.Context, opt recording.WatcherOptions) (*recording.Watcher, error) {
	w, err := recording.NewWatcher(opt)
	if err != nil {
		return nil, fmt.Errorf("워처 조립 실패: %w", err)
	}
	if err := w.Start(ctx); err != nil {
		if closeErr := w.Close(); closeErr != nil {
			// 1차 에러(기동 실패)가 정본이고 이것은 정리 실패의 흔적이다 — 삼키지 않는다.
			opt.Log.Warn("watcher_close_failed", "err", closeErr)
		}
		return nil, fmt.Errorf("워처 기동 실패: %w", err)
	}
	return w, nil
}

// newUploader 는 설정에 따라 업로더를 고른다. 분기는 셋이고 기동을 거부하는 것은 하나뿐이다.
//
//	S3_BUCKET 이 빔      -> Disabled. 팀 공용 환경(2·3번)의 기본 상태이며 인덱싱만 한다(G15).
//	자격증명을 못 얻음    -> 기동은 진행하고 브레이커를 연 채로 시작한다. 자격증명은 나중에
//	                        갱신될 수 있고, 그동안의 차단은 브레이커가 맡는다(결정 12⁴).
//	설정 자체가 틀림      -> 기동 실패.
func newUploader(ctx context.Context, cfg config.Config, root *os.Root,
	st index.UploadStore, log *slog.Logger) (*upload.Uploader, error) {
	if cfg.S3.Bucket == "" {
		return upload.Disabled(log), nil
	}

	put, credsOK, err := upload.NewS3Putter(ctx, cfg.S3, log)
	if err != nil {
		return nil, err
	}
	opt := cfg.Upload
	opt.Root = root
	opt.SegmentRoot = cfg.SegmentRoot

	up := upload.New(st, put, opt, log)
	if !credsOK {
		up.OpenCircuit("credentials_unavailable")
	}
	return up, nil
}

// loopDeps 는 메인 루프가 지켜보는 신호원과 그 처리에 필요한 것 전부다.
//
// 워처 구조체가 아니라 **채널로 받는** 이유: 루프가 하는 일은 "여러 신호를 한 select 에서
// 차례로 처리한다"이지 "워처를 안다"가 아니다. 채널로 끊어 두면 그 규약을 워처나 fsnotify
// 없이 그대로 검증할 수 있다.
type loopDeps struct {
	ix          *indexer.Indexer
	root        string
	rescanEvery time.Duration
	log         *slog.Logger

	// watcherDone 이 닫히면 감시자가 멈춘 것이다. 사유는 watcherErr 로 읽는다.
	watcherDone <-chan struct{}
	watcherErr  func() error
	completed   <-chan recording.Segment
	rescans     <-chan struct{}

	// hookEvents·hookDone 은 훅 어댑터가 없으면 **nil 채널**이다.
	// Go 에서 nil 채널 case 는 영구 비활성이라 패닉도 busy loop 도 나지 않는다 —
	// 이 성질이 HOOK_SPOOL_PATH="" 롤백 스위치를 안전하게 만든다.
	hookEvents <-chan mtxhook.Event
	hookDone   <-chan struct{}
	// hookErr 는 훅 사망 사유를 읽는다(워처의 watcherErr 와 대칭).
	// nil 을 돌려주면 ctx 취소에 의한 정상 종료라는 뜻이다.
	hookErr func() error

	// uploadResults 는 업로더의 판정(uploaded·failed)이 오는 채널이다.
	// 업로더가 꺼져 있으면 Results() 가 nil 을 주므로 그 case 는 영구 비활성이다 —
	// 훅 채널과 정확히 같은 규약이다.
	uploadResults <-chan upload.Result
	// holdTicks 는 보류 중인 꼬리를 살펴보는 주기다. 티커의 소유자는 run 이다.
	holdTicks <-chan time.Time

	// armSweeper 는 첫 완주 수집에서 스위퍼를 여는 손잡이다(up.ArmSweeper).
	// arm 판정의 소유자가 loop 인 이유: Indexer 는 업로더 생애주기를 모른다(원칙 4).
	armSweeper func()
	// stallFactor 는 scan_collect_stalled 판정 계수 k 다(경과 > ScanCollectBudget × k).
	stallFactor float64

	// watcherDegraded 는 워처 부재 국면 표식이다. 참이면 재스캔 주기마다 재장착을
	// 시도한다 — 유계 재시도(주기당 1회)가 회복을 담당한다(ADR-063 결정 1).
	watcherDegraded bool
	// reattachWatcher 는 재장착 손잡이다(assembleWatcher). 성공 시 loop 이 채널 넷을
	// 갈아 끼우고 SetAdopter 를 함께 부른다 — H4·H5 되돌림이 실제 워처로 복귀한다(f6f).
	reattachWatcher func() (*recording.Watcher, error)
}

// collectStallFactor 는 수집 정지 판정 계수다. soft 예산 45초 × 2 = 90초 —
// 예산 초과(절단으로 회복)와 진짜 정지(결과 자체가 없음)를 가르는 여유다.
const collectStallFactor = 2.0

// loop 은 종료·워처사망·완성세그먼트·재스캔요청·훅이벤트·훅사망·업로드결과·보류틱을
// 한 곳에서 받아 차례로 처리한다.
//
// 주석 ⓑ: Scan·Handle·HandleHook·ApplyUploadResult·ReleaseHeldTails 의 호출자는 이 루프
// 하나뿐이다. 그래서 Indexer 에 락이 없다(D10). select 는 한 번에 하나의 case 만 실행하므로
// 이들이 동시에 불릴 수 없다. 이 규약을 깨고 다른 고루틴에서 부르면 즉시 seq 중복이 난다.
func loop(ctx context.Context, d loopDeps) error {
	ticker := time.NewTicker(d.rescanEvery)
	defer ticker.Stop()

	// 재장착은 워커로 발사하고 결과만 select 로 받는다(단일 비행). 동기로 부르면
	// assembleWatcher 의 Start→addWatchTree(무상한 FS walk)가 hung FS 에서 loop 자체를
	// 세운다 — 격리 원칙의 자기위반이다(cx 리뷰 차단 1). hung 상태의 버려진 재장착
	// 워커는 단일 비행이라 최대 1개다(fsnotify 핸들 점유도 1개로 유계).
	type reattachResult struct {
		w   *recording.Watcher
		err error
	}
	var reattachPending chan reattachResult // nil = 비행 없음(그 case 는 영구 비활성)

	for {
		select {
		case <-ctx.Done():
			return nil

		// 주석 ⓒ: 워처 사망 감독이 왜 필요한가.
		// 감시자가 죽었는데 프로세스가 살아 있으면 실시간 INSERT 만 조용히 멈춘다.
		// 에러도 로그도 없이 데이터가 사라지는 최악의 실패다. Done() 을 감시해
		// 즉시 프로세스를 끝내고, compose 가 재기동하면 Scan 이 밀린 것을 따라잡는다(D8).
		case <-d.watcherDone:
			if err := d.watcherErr(); err != nil {
				return fmt.Errorf("감시자가 멈췄다: %w", err)
			}
			return nil

		case seg := <-d.completed:
			if err := d.ix.Handle(ctx, seg); err != nil {
				return err
			}

		case <-d.rescans:
			d.ix.StartCollect(ctx, d.root)

		case ev, ok := <-d.hookEvents:
			if !ok {
				// Reader 가 정상 종료했다. 그 case 만 끄고 계속 간다 —
				// 끄지 않으면 닫힌 채널이 언제나 수신 가능해 select 가 무한히 돈다.
				d.hookEvents = nil
				continue
			}
			if err := d.ix.HandleHook(ctx, ev); err != nil {
				return err
			}

		case <-d.hookDone:
			// 훅 사망은 워처 사망과 **정반대 규약**이다. 워처가 죽으면 실시간 INSERT 가
			// 통째로 멈추지만, 훅은 1차 신호일 뿐이고 벽시계 드리프트·파일 감시·주기
			// 재스캔이 안전망으로 남는다. 여기서 프로세스를 끝내면 훅 고장이 곧 서비스
			// 중단이 되어 GH4(훅이 죽어도 현행 동작 유지)를 정면으로 어긴다.
			//
			// 두 채널을 nil 로 만드는 것이 핵심이다. 닫힌 채널은 언제나 수신 가능하므로
			// 그대로 두면 이 case 가 쉬지 않고 뽑혀 CPU 를 태운다.
			d.logReaderStopped()
			d.hookEvents = nil
			d.hookDone = nil

		case <-ticker.C:
			// 워처 강등 국면이면 재장착 워커를 발사한다(비행 중이면 건너뜀 = 유계).
			if d.watcherDegraded && d.reattachWatcher != nil && reattachPending == nil {
				ch := make(chan reattachResult, 1)
				reattachPending = ch
				go func() {
					w, err := d.reattachWatcher()
					ch <- reattachResult{w: w, err: err}
				}()
			}
			// 아무 일이 없어도 주기마다 전수 점검을 발사한다. 놓친 게 있으면 여기서 복구된다.
			// 발사만 하고 결과는 CollectDone case 가 받는다 — 수집이 루프를 세우지 않는다.
			d.ix.StartCollect(ctx, d.root)

		// 재장착 결과 — 성공이면 채널 넷을 갈아 끼우고 SetAdopter 를 함께 부른다(f6f).
		case r := <-reattachPending:
			reattachPending = nil
			if r.err != nil {
				d.log.Warn("watcher_reattach_failed", "err", r.err,
					"note", "다음 재스캔 주기에 다시 시도한다. 훅·스캔 유입은 계속된다")
				continue
			}
			d.watcherDone, d.watcherErr = r.w.Done(), r.w.Wait
			d.completed, d.rescans = r.w.Completed(), r.w.Rescans()
			d.ix.SetAdopter(r.w)
			d.watcherDegraded = false
			d.log.Info("watcher_recovered", "note", "재장착 완료 — 되돌림(H4·H5)이 실제 워처로 복귀한다")
			d.log.Info("watcher_degraded", "value", 0)

		// 수집 워커의 결과가 도착했다. 처리(스트림 루프)는 여기 — 즉 loop 고루틴에서 돈다(D10).
		case res := <-d.ix.CollectDone():
			first, err := d.ix.ApplyCollect(ctx, d.root, res)
			if err != nil {
				return err
			}
			if first {
				// 첫 완주 — 커서가 채워졌으므로 스위퍼를 열어도 선점이 없다.
				d.armSweeper()
			}

		// 업로드 판정을 커서에 반영한다. 비활성 업로더의 Results() 는 nil 이라
		// 이 케이스는 절대 선택되지 않는다.
		case res := <-d.uploadResults:
			d.ix.ApplyUploadResult(res.StreamID, res.Seq, res.State)

		// 보류 중인 꼬리를 살펴본다. 예산 안에서만 stat 한다.
		// 수집 정지 판정을 겸행한다 — 결과가 안 온 채 예산 × k 를 넘기면 한 시도에 한 번 ERROR.
		case <-d.holdTicks:
			if d.ix.CollectOverdue(d.stallFactor) {
				d.log.Error("scan_collect_stalled", "root", d.root, "factor", d.stallFactor,
					"note", "수집 결과가 오지 않고 있다. FS 정지 의심 — 프로세스는 계속 돈다")
			}
			d.ix.ReleaseHeldTails()
		}
	}
}

// logReaderStopped 는 훅 채널이 멈춘 사실을 남긴다.
//
// 사유가 있을 때만 ERROR 다. ctx 취소로 끝난 경우(Wait()==nil)까지 ERROR 로 올리면
// select 가 ctx.Done 대신 hookDone 을 뽑는 것만으로 **정상 종료마다 가짜 장애 신호**가
// 남는다 — 둘은 종료 시점에 동시에 준비되므로 어느 쪽이 뽑힐지는 정해져 있지 않다.
func (d loopDeps) logReaderStopped() {
	var err error
	if d.hookErr != nil {
		err = d.hookErr()
	}
	const note = "훅 채널이 멈췄다. 인덱싱은 계속되며 재접속 검출만 현행 수준으로 강등된다"
	if err != nil {
		d.log.Error("hook_reader_stopped", "err", err, "note", note)
		return
	}
	d.log.Info("hook_reader_stopped", "note", note, "cause", "정상 종료(ctx 취소)")
}
