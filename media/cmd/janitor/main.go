// Command janitor 는 업로드가 끝난 ② 로컬 조각 파일을 지우고 장부의 local_path 를 NULL 로 만드는
// 정리기다(POK-170). 사이드카와 같은 이미지의 다른 명령이고, recordings 를 쓰기로 붙이는 것은 이
// 컨테이너뿐이다(사이드카는 :ro).
//
// 이 파일은 설정을 읽어 조립하고 기동 순서를 지킨다(설계 2-4). 정책은 internal/janitor 에, 판정 SQL 과
// 행 잠금 트랜잭션은 internal/index 에 있다. DDL 은 실행하지 않는다 — 표는 사이드카가 만든다.
//
// 종료 코드: 0 = 신호로 정상 종료(꺼짐 대기 포함), 1 = 설정 · 기동 실패(PG 17 미만 포함).
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/janitor"
)

const (
	exitOK      = 0
	exitFailure = 1
)

// minServerVersion 은 transaction_timeout 이 있는 PostgreSQL 17 이다. 그 미만이면 잠금 보유의 서버 측
// 상한이 없어 사이드카 기한(MarkTimeout 10s · 인덱서 5s × 2)과의 결속이 깨진다(E3′ · D43).
const minServerVersion = 170000

// versionQueryTimeout 은 기동 때 버전 조회의 상한이다(조회 기한과 같다).
const versionQueryTimeout = 20 * time.Second

// poolMaxConns 는 janitor 풀 크기다 — 한 번에 한 행 · 조회 하나라 둘이면 된다.
const poolMaxConns = 2

// deps 는 기동 순서의 바깥 호출이다. 운영 값은 main 이 채우고 시험만 바꾼다.
type deps struct {
	newPool       func(ctx context.Context, cfg *pgxpool.Config) (*pgxpool.Pool, error)
	serverVersion func(ctx context.Context, pool *pgxpool.Pool) (int, error)
	openRoot      func(dir string) (*os.Root, error)
	runJanitor    func(ctx context.Context, j *janitor.Janitor) error
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	deployed := deps{
		newPool:       pgxpool.NewWithConfig,
		serverVersion: queryServerVersion,
		openRoot:      os.OpenRoot,
		runJanitor:    func(ctx context.Context, j *janitor.Janitor) error { return j.Run(ctx) },
	}
	code := run(ctx, os.Getenv, os.Stdout, os.Stderr, deployed)
	stop()
	os.Exit(code)
}

// run 은 기동 순서를 돌고 종료 코드를 돌려준다. 로그는 stdout(JSON), 로거를 만들기 전 오류는 stderr 다.
func run(ctx context.Context, getenv func(string) string, stdout, stderr io.Writer, d deps) int {
	cfg, err := loadConfig(getenv)
	if err != nil {
		fmt.Fprintf(stderr, "janitor 설정 오류: %v\n", err)
		return exitFailure
	}
	log := slog.New(slog.NewJSONHandler(stdout, &slog.HandlerOptions{Level: cfg.logLevel}))
	if !cfg.enabled {
		log.Warn("janitor_disabled", "note", "JANITOR_ENABLED 가 비었거나 거짓이다 — DB · 파일을 건드리지 않고 대기한다")
		<-ctx.Done()
		return exitOK
	}
	runID := strconv.FormatInt(time.Now().UnixNano(), 10)
	base := log
	log = log.With("run_id", runID)
	log.Info("janitor_starting", "root", cfg.segmentRoot)
	if err := start(ctx, cfg, runID, base, d); err != nil {
		if errors.Is(err, context.Canceled) {
			log.Info("janitor_stopped")
			return exitOK
		}
		log.Error("janitor_failed", "err", err)
		return exitFailure
	}
	return exitOK
}

// start 는 풀 → 버전 확인 → 루트 → 정리기 순으로 조립하고 돌린다. base 는 run_id 를 싣기 전 로거다 —
// 정리기가 같은 run_id 를 스스로 싣는다.
func start(ctx context.Context, cfg config, runID string, base *slog.Logger, d deps) error {
	log := base.With("run_id", runID)
	pc, err := poolConfig(cfg)
	if err != nil {
		return err
	}
	pool, err := d.newPool(ctx, pc)
	if err != nil {
		// DSN 에는 비밀번호가 있으므로 원문 오류의 값 부분이 실리지 않게 감싸기만 한다.
		return fmt.Errorf("DB 연결 풀 생성 실패: %w", err)
	}
	defer pool.Close()
	if err := requirePG17(ctx, pool, log, d); err != nil {
		return err
	}
	root, err := d.openRoot(cfg.segmentRoot) // 기한 없음 — 한계 L20
	if err != nil {
		return fmt.Errorf("세그먼트 루트 열기 실패 root=%q: %w", cfg.segmentRoot, err)
	}
	defer root.Close()
	j, err := janitor.New(janitor.Options{
		Root: cfg.segmentRoot, Interval: cfg.interval, Policy: cfg.policy(), Batch: cfg.batch,
		MaxPerCycle: cfg.maxPerCycle, FSOpTimeout: cfg.fsOpTimeout, RunID: runID, Log: base,
	}, index.NewLocalCleanupStore(pool), root)
	if err != nil {
		return err
	}
	log.Info("janitor_started", "interval", cfg.interval, "grace", cfg.grace, "reconnect_window", cfg.reconnectWindow,
		"record_delete_after", cfg.recordDeleteAfter, "batch", cfg.batch, "max_per_cycle", cfg.maxPerCycle,
		"fs_op_timeout", cfg.fsOpTimeout, "server_version_min", minServerVersion)
	return d.runJanitor(ctx, j)
}

// requirePG17 은 PostgreSQL 17 미만이면 거부 사유를 ERROR 한 줄로 남기고 오류를 돌려준다.
func requirePG17(ctx context.Context, pool *pgxpool.Pool, log *slog.Logger, d deps) error {
	vctx, cancel := context.WithTimeout(ctx, versionQueryTimeout)
	defer cancel()
	v, err := d.serverVersion(vctx, pool)
	if err != nil {
		return fmt.Errorf("server_version_num 조회 실패: %w", err)
	}
	if v < minServerVersion {
		log.Error("janitor_pg_version_unsupported", "server_version_num", v, "required", minServerVersion,
			"note", "transaction_timeout 이 없어 잠금 보유 상한을 보장할 수 없다")
		return fmt.Errorf("PostgreSQL server_version_num %d < %d", v, minServerVersion)
	}
	return nil
}

// poolConfig 는 janitor 풀 설정이다 — 세션 설정을 연결마다 건다(역할 기본값에 기대지 않는다).
func poolConfig(cfg config) (*pgxpool.Config, error) {
	pc, err := pgxpool.ParseConfig(cfg.pgDSN)
	if err != nil {
		return nil, errors.New("POSTGRES_* 로 만든 DSN 해석 실패") // 원문에 비밀번호가 있다
	}
	applySessionLimits(pc)
	return pc, nil
}

// applySessionLimits 는 서버 쪽 대기 상한과 풀 크기를 건다(설계 2-4 · 3-3): 조회 15s · 표 잠금 대기 2s ·
// 트랜잭션 안 유휴 30s. 잠금 트랜잭션의 전체 상한(8s)은 그 트랜잭션이 SET LOCAL 로 따로 건다.
// 연결 풀러(PgBouncer · RDS Proxy)를 거치면 시작 메시지 설정이 무시될 수 있다 — 그때는 역할 기본값으로 옮긴다.
func applySessionLimits(pc *pgxpool.Config) {
	pc.MaxConns = poolMaxConns
	rp := pc.ConnConfig.RuntimeParams
	rp["statement_timeout"] = "15s"
	rp["lock_timeout"] = "2s"
	rp["idle_in_transaction_session_timeout"] = "30s"
	rp["application_name"] = "janitor"
}

// queryServerVersion 은 서버의 server_version_num 이다.
func queryServerVersion(ctx context.Context, pool *pgxpool.Pool) (int, error) {
	var raw string
	if err := pool.QueryRow(ctx, "SHOW server_version_num").Scan(&raw); err != nil {
		return 0, err
	}
	return strconv.Atoi(raw)
}
