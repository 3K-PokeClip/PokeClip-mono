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
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
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

	store := index.NewPGStore(pool)

	w, err := recording.NewWatcher(cfg.Watcher)
	if err != nil {
		return err
	}
	ix := indexer.New(store, fmp4meta.ProbeDurationMS, w, cfg.Indexer, log)

	// --- 2. watch 등록 (동기) ---
	//
	// 주석 ⓐ: w.Start() 가 ix.Scan() 보다 먼저인 이유.
	// Start 는 동기라서 반환 시점에는 감시 등록이 이미 끝나 있다. 순서를 뒤집어 Scan 을
	// 먼저 하면 "훑기는 끝났는데 감시는 아직 안 켜진" 공백 구간이 생기고, 그 사이에
	// 만들어진 파일은 훑기에도 안 잡히고 알림도 오지 않아 영구 미아가 된다(설계 3절 2번).
	if err := w.Start(ctx); err != nil {
		return err
	}

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

	// --- 3. 초기 스캔: 꺼져 있는 동안 쌓인 파일을 따라잡는다 ---
	//
	// 2번과 3번 사이에 생성된 파일이 walk 와 워처 FIFO 양쪽에 잡힐 수 있다.
	// 이는 설계상 허용이며 H2(경로 중복 확인)와 DB UNIQUE 가 흡수한다.
	if err := ix.Scan(ctx, cfg.SegmentRoot); err != nil {
		return err
	}

	log.Info("watching", "root", cfg.SegmentRoot,
		"idle_timeout", cfg.Watcher.IdleTimeout, "rescan_every", cfg.Watcher.RescanEvery,
		"settle_wait", cfg.Watcher.Settle.SettleWait, "local_time", time.Now().Format(time.RFC3339))

	// --- 4. 메인 루프 ---
	if err := loop(ctx, loopDeps{
		ix: ix, root: cfg.SegmentRoot, rescanEvery: cfg.Watcher.RescanEvery, log: log,
		watcherDone: w.Done(), watcherErr: w.Wait,
		completed: w.Completed(), rescans: w.Rescans(),
		hookEvents: hookEvents, hookDone: hookDone, hookErr: hookErr,
	}); err != nil {
		return err
	}
	// 정상 종료도 흔적을 남긴다. 로그가 그냥 끊기면 죽은 것인지 끝난 것인지 구분할 수 없다.
	log.Info("shutdown", "reason", "종료 신호를 받아 정상 종료한다")
	return nil
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
}

// loop 은 종료·워처사망·완성세그먼트·재스캔요청·훅이벤트·훅사망 신호를
// 한 곳에서 받아 차례로 처리한다.
//
// 주석 ⓑ: Scan·Handle·HandleHook 의 호출자는 이 루프 하나뿐이다. 그래서 Indexer 에
// 락이 없다(D10). select 는 한 번에 하나의 case 만 실행하므로 세 함수가 동시에 불릴 수 없다.
// 이 규약을 깨고 다른 고루틴에서 부르면 즉시 seq 중복이 난다.
func loop(ctx context.Context, d loopDeps) error {
	ticker := time.NewTicker(d.rescanEvery)
	defer ticker.Stop()

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
			if err := d.ix.Scan(ctx, d.root); err != nil {
				return err
			}

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
			// 아무 일이 없어도 주기마다 전수 점검한다. 놓친 게 있으면 여기서 복구된다.
			if err := d.ix.Scan(ctx, d.root); err != nil {
				return err
			}
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
