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
	if err := loop(ctx, w, ix, cfg.SegmentRoot, cfg.Watcher.RescanEvery); err != nil {
		return err
	}
	// 정상 종료도 흔적을 남긴다. 로그가 그냥 끊기면 죽은 것인지 끝난 것인지 구분할 수 없다.
	log.Info("shutdown", "reason", "종료 신호를 받아 정상 종료한다")
	return nil
}

// loop 은 종료·워처사망·완성세그먼트·재스캔요청 네 신호를 한 곳에서 받아 차례로 처리한다.
//
// 주석 ⓑ: Scan·Handle 의 호출자는 이 루프 하나뿐이다. 그래서 Indexer 에 락이 없다(D10).
// select 는 한 번에 하나의 case 만 실행하므로 두 함수가 동시에 불릴 수 없다.
// 이 규약을 깨고 다른 고루틴에서 Scan 이나 Handle 을 부르면 즉시 seq 중복이 난다.
func loop(ctx context.Context, w *recording.Watcher, ix *indexer.Indexer, root string, rescanEvery time.Duration) error {
	ticker := time.NewTicker(rescanEvery)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil

		// 주석 ⓒ: 워처 사망 감독이 왜 필요한가.
		// 감시자가 죽었는데 프로세스가 살아 있으면 실시간 INSERT 만 조용히 멈춘다.
		// 에러도 로그도 없이 데이터가 사라지는 최악의 실패다. Done() 을 감시해
		// 즉시 프로세스를 끝내고, compose 가 재기동하면 Scan 이 밀린 것을 따라잡는다(D8).
		case <-w.Done():
			if err := w.Wait(); err != nil {
				return fmt.Errorf("감시자가 멈췄다: %w", err)
			}
			return nil

		case seg := <-w.Completed():
			if err := ix.Handle(ctx, seg); err != nil {
				return err
			}

		case <-w.Rescans():
			if err := ix.Scan(ctx, root); err != nil {
				return err
			}

		case <-ticker.C:
			// 아무 일이 없어도 주기마다 전수 점검한다. 놓친 게 있으면 여기서 복구된다.
			if err := ix.Scan(ctx, root); err != nil {
				return err
			}
		}
	}
}
