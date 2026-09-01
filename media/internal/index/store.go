package index

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Store 는 DB 접근 통로 4개다. 인터페이스로 둔 이유는 테스트에서 가짜 구현으로 바꿔 끼우기 위해서다.
type Store interface {
	// LoadCursor 는 이 스트림을 어디까지 기록했나를 읽는다. 마지막 행 1개로 커서를 만든다.
	LoadCursor(ctx context.Context, streamID string) (Cursor, error)
	// ExistingPaths 는 이미 기록된 파일 경로 전부다. 매번 DB 에 묻지 않으려고 메모리에 올려 둔다.
	ExistingPaths(ctx context.Context, streamID string) (map[string]struct{}, error)
	// Insert 는 행 삽입과 컷오프 주조를 **한 문장(CTE)** 으로 수행한다(계약 6항 "행 생성과
	// 같은 트랜잭션" — 게이트 b·d2). seed 는 주조 판정 입력이고, Eligible=false 면
	// 순수 삽입과 동작이 같다(주조 WHERE 가 막는다 — 플래그 OFF 의 구 동작, c1).
	Insert(ctx context.Context, r Record, seed Seed) (InsertOutcome, SeedResult, error)
	// UpdateTail 은 duration_ms 와 bytes 를 사후 정정한다. 계약상 쓰기 주체가 1번이므로 허용된다.
	// upload_state 는 건드리지 않는다.
	UpdateTail(ctx context.Context, streamID string, seq int64, durationMS int32, bytes int64) (updated bool, err error)
}

// pgUniqueViolation 은 PostgreSQL 의 unique_violation SQLSTATE 다.
// PK 위반과 UNIQUE 인덱스 위반이 같은 코드로 오므로, 어느 제약이 걸렸는지는 제약 이름으로 가른다.
const pgUniqueViolation = "23505"

// localPathUniqueName 은 ddl.go 가 만드는 UNIQUE 인덱스 이름이다.
// 이 이름이 걸리면 "같은 파일을 또 넣으려 했다"(정상 멱등)이고,
// 그 외 23505 는 PK(stream_id, seq) 충돌이다(단일 쓰기자 전제 붕괴 신호).
const localPathUniqueName = "stream_segments_local_path_uq"

type pgStore struct {
	pool *pgxpool.Pool
}

// NewPGStore 는 PostgreSQL 구현을 돌려준다.
func NewPGStore(pool *pgxpool.Pool) Store {
	return &pgStore{pool: pool}
}

// s3_key 는 ddl.go 에서 NOT NULL 이므로 포인터로 받지 않고 곧바로 스캔한다.
// SELECT 목록 순서와 아래 Scan 인자 순서는 반드시 같아야 한다 — s3_key 와 local_path 는
// 둘 다 text 라 순서를 바꿔도 컴파일·실행이 통과한다. store_test.go 의 값 대조가 그 방어선이다.
const loadCursorSQL = `
SELECT seq, start_pts_ms, start_wall_utc, duration_ms, bytes, local_path, upload_state, s3_key
  FROM stream_segments WHERE stream_id = $1 ORDER BY seq DESC LIMIT 1`

func (s *pgStore) LoadCursor(ctx context.Context, streamID string) (Cursor, error) {
	var (
		tail      TailRow
		bytes     *int64
		localPath *string
	)
	err := s.pool.QueryRow(ctx, loadCursorSQL, streamID).Scan(
		&tail.Seq, &tail.StartPTSMS, &tail.StartWallUTC, &tail.DurationMS,
		&bytes, &localPath, &tail.UploadState, &tail.S3Key,
	)
	// 행이 없다 = 이 스트림의 첫 세그먼트다. 에러가 아니라 정상 상태다.
	if errors.Is(err, pgx.ErrNoRows) {
		return Cursor{}, nil
	}
	if err != nil {
		return Cursor{}, fmt.Errorf("커서 적재 실패 stream_id=%q: %w", streamID, err)
	}

	// bytes 와 local_path 는 NULL 을 허용하는 컬럼이라 포인터로 받아 영값으로 편다.
	if bytes != nil {
		tail.Bytes = *bytes
	}
	if localPath != nil {
		tail.LocalPath = *localPath
	}
	// 저장은 UTC 강제지만 드라이버가 로컬 위치로 실어 올 수 있어 여기서 한 번 못 박는다(D6).
	tail.StartWallUTC = tail.StartWallUTC.UTC()

	return Cursor{NextSeq: tail.Seq + 1, Tail: &tail}, nil
}

const existingPathsSQL = `
SELECT local_path FROM stream_segments WHERE stream_id = $1 AND local_path IS NOT NULL`

func (s *pgStore) ExistingPaths(ctx context.Context, streamID string) (map[string]struct{}, error) {
	rows, err := s.pool.Query(ctx, existingPathsSQL, streamID)
	if err != nil {
		return nil, fmt.Errorf("기록된 경로 조회 실패 stream_id=%q: %w", streamID, err)
	}
	defer rows.Close()

	paths := make(map[string]struct{})
	for rows.Next() {
		var p string
		if err := rows.Scan(&p); err != nil {
			return nil, fmt.Errorf("경로 스캔 실패 stream_id=%q: %w", streamID, err)
		}
		paths[p] = struct{}{}
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("경로 조회 중 오류 stream_id=%q: %w", streamID, err)
	}
	return paths, nil
}

// TxnDeadline 은 주조 트랜잭션의 클라이언트 상한이다(begin → commit — 설계 6.5.5).
// statement_timeout 은 명령별이라 COMMIT 까지 묶지 못하므로 ctx 데드라인이 담당한다.
// 공시(계약 6-3): 판정 창 90초 + 이 값 = 커밋 상한 100초. in-doubt 창(클라이언트 포기 후
// 서버 커밋 완료)은 이 상한 밖이다.
const TxnDeadline = 10 * time.Second

// ErrLockContended 는 55P03(락 대기 상한)이 즉시 1회 재시도 후에도 계속됨을 뜻한다(m1b).
// 호출자(indexer)는 이 조각 하나만 건너뛴다 — 파일은 다음 Scan 이 회수한다(유실 0).
var ErrLockContended = errors.New("index: 컷오프 경합 락 대기 상한(55P03) — 즉시 재시도도 실패")

// pgLockNotAvailable 은 lock_timeout 초과의 SQLSTATE 다.
const pgLockNotAvailable = "55P03"

// insertSeedSQL — 행 삽입과 컷오프 주조의 한 문장(설계 6.5.5 · ADR-062).
//
// 구조가 곧 단정이다:
//   - cutoff_seq 자리에 바인드 파라미터가 없다(게이트 d2) — 값은 ins 의 RETURNING 에서만
//     온다. 과대·과소 기록이 표현 불가능하다.
//   - ⓒ(시작점 자격)는 SQL 이 방금 쓴 세 열을 직접 본다 — Go 주입이 아니다.
//   - ⓐ 의 시간 항은 clock_timestamp() 로 락 대기 뒤에 재검한다(m1a).
//   - 기존 컷오프는 ON CONFLICT DO NOTHING 으로 승계한다(기존 승리 — d5).
//   - 세그먼트 INSERT 의 23505 는 예외 → 롤백 → 컷오프도 남지 않는다(d4).
const insertSeedSQL = `
WITH ins AS (
    INSERT INTO stream_segments
        (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key,
         local_path, upload_state, bytes, is_discontinuity,
         session_id, playback_pdt, playback_s3_key)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
    RETURNING stream_id, seq, session_id, playback_pdt, playback_s3_key
), seed AS (
    INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
    SELECT i.stream_id, i.seq, $14, $15
      FROM ins i
     WHERE $16::boolean
       AND i.session_id      IS NOT NULL
       AND i.playback_pdt    IS NOT NULL
       AND i.playback_s3_key IS NOT NULL
       AND clock_timestamp() - $17::timestamptz <= $18::interval
    ON CONFLICT (stream_id) DO NOTHING
    RETURNING 1
)
SELECT (SELECT count(*) FROM ins)  AS inserted,
       (SELECT count(*) FROM seed) AS seeded`

// setTxnLimitsSQL 은 트랜잭션 지역 상한이다. lock_timeout 5s 는 OBS_FRESH(30초)보다 충분히
// 작아 공시가 성립하고, statement_timeout 10s 는 개별 문장 상한이다(COMMIT 은 TxnDeadline).
const setTxnLimitsSQL = `SELECT set_config('lock_timeout','5s',true), set_config('statement_timeout','10s',true)`

const cutoffExistsSQL = `SELECT EXISTS (SELECT 1 FROM stream_cutoffs WHERE stream_id = $1)`

func (s *pgStore) Insert(ctx context.Context, r Record, seed Seed) (InsertOutcome, SeedResult, error) {
	out, res, err := s.insertOnce(ctx, r, seed)
	if err != nil && isLockTimeout(err) {
		// 55P03 → 즉시 1회 재시도(설계 6.5.5 · m1b). 재시도도 락이면 이 조각만 포기한다 —
		// 백오프로 붙잡으면 D10 루프가 락 경합에 30초씩 볼모가 된다.
		out, res, err = s.insertOnce(ctx, r, seed)
		if err != nil && isLockTimeout(err) {
			return out, res, fmt.Errorf("%w: stream_id=%q seq=%d", ErrLockContended, r.StreamID, r.Seq)
		}
	}
	return out, res, err
}

func (s *pgStore) insertOnce(ctx context.Context, r Record, seed Seed) (InsertOutcome, SeedResult, error) {
	txctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()

	tx, err := s.pool.Begin(txctx)
	if err != nil {
		return InsertInserted, SeedResult{}, fmt.Errorf("주조 트랜잭션 시작 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}
	// Commit 성공 후의 Rollback 은 무해한 no-op 이다.
	defer func() { _ = tx.Rollback(txctx) }()

	if _, err := tx.Exec(txctx, setTxnLimitsSQL); err != nil {
		return InsertInserted, SeedResult{}, fmt.Errorf("트랜잭션 상한 설정 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}

	// 주조 시도끼리는 스트림 스코프 advisory 락으로 **시간 판정보다 앞에서** 직렬화한다.
	// CTE 의 clock_timestamp() 는 소스 행 생성 시점에 평가되므로, stream_cutoffs UNIQUE
	// 검사에서 미커밋 경합을 만나면 그 대기는 시간 판정 **뒤**가 되어 — 경합이 롤백되면
	// 낡은 방증으로 주조될 수 있다(cx 리뷰 차단 2). 대기를 여기(판정 앞)로 끌어오면
	// "락 대기 뒤 재검"(m1a) 의미론이 복원된다. advisory 대기도 lock_timeout 을 따르므로
	// 55P03 경로(m1b)와 일관된다. 비적격 INSERT 는 컷오프 경쟁이 없으므로 직렬화하지 않는다.
	if seed.Eligible {
		if _, err := tx.Exec(txctx,
			`SELECT pg_advisory_xact_lock(hashtext('pc_cutoff_' || $1::text))`, r.StreamID); err != nil {
			return InsertInserted, SeedResult{}, fmt.Errorf("주조 직렬화 락 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
		}
	}

	// ⓐ 가 거짓이어도 $14~$18 은 유효한 값으로 내려간다 — seed 의 WHERE 가 막으므로
	// CHECK 에 닿을 행 자체가 없지만, 드라이버 층에서 놀랄 일을 만들지 않는다.
	reason, channel, anchor, fresh := seedArgs(seed)

	var inserted, seeded int
	err = tx.QueryRow(txctx, insertSeedSQL,
		r.StreamID, r.Seq, r.StartPTSMS, r.StartWallUTC.UTC(), r.DurationMS, r.S3Key,
		r.LocalPath, string(r.UploadState), r.Bytes, r.IsDiscontinuity,
		r.SessionID, r.PlaybackPDT, r.PlaybackS3Key, // carrier — nil ⇒ NULL(5.4.2)
		reason, channel, seed.Eligible, anchor, fresh,
	).Scan(&inserted, &seeded)
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == pgUniqueViolation {
			// 롤백(defer)으로 컷오프도 남지 않는다(d4).
			if pgErr.ConstraintName == localPathUniqueName {
				return InsertDuplicatePath, SeedResult{}, nil
			}
			return InsertSeqConflict, SeedResult{}, nil
		}
		return InsertInserted, SeedResult{}, fmt.Errorf("행 삽입 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}
	if err := tx.Commit(txctx); err != nil {
		return InsertInserted, SeedResult{}, fmt.Errorf("주조 트랜잭션 커밋 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}

	res, err := s.resolveSeed(ctx, r, seed, seeded)
	return InsertInserted, res, err
}

// resolveSeed 는 Decline 귀속(설계 6.5.5)이다. 커밋 뒤에만 부른다.
func (s *pgStore) resolveSeed(ctx context.Context, r Record, seed Seed, seeded int) (SeedResult, error) {
	if seeded == 1 {
		return SeedResult{Seeded: true}, nil
	}
	if !seed.Eligible {
		return SeedResult{Decline: DeclineNoCorroboration}, nil
	}
	if r.SessionID == nil || r.PlaybackPDT == nil || r.PlaybackS3Key == nil {
		return SeedResult{Decline: DeclineNotSettleable}, nil
	}
	// 자격 전부 참인데 seeded=0 — 기존 컷오프 승계(정상)인지 시간 항 탈락인지 가른다.
	var exists bool
	if err := s.pool.QueryRow(ctx, cutoffExistsSQL, r.StreamID).Scan(&exists); err != nil {
		// 귀속 실패는 주조 결과를 바꾸지 않는다 — 신호 정밀도만 낮아진다.
		return SeedResult{Decline: DeclineStaleCorroboration},
			fmt.Errorf("컷오프 존재 확인 실패 stream_id=%q: %w", r.StreamID, err)
	}
	if exists {
		return SeedResult{Decline: DeclineSkipped}, nil
	}
	return SeedResult{Decline: DeclineStaleCorroboration}, nil
}

// seedArgs 는 비적격 seed 의 제로값을 드라이버에 흘리지 않는 보정이다.
func seedArgs(seed Seed) (SeedReason, SeedChannel, time.Time, time.Duration) {
	reason, channel := seed.Reason, seed.Channel
	if reason == "" {
		reason = SeedReasonLiveIngress
	}
	if channel == "" {
		channel = SeedChannelWatcher
	}
	anchor := seed.AnchorUTC
	if anchor.IsZero() {
		anchor = time.Now().UTC()
	}
	fresh := seed.Freshness
	if fresh <= 0 {
		fresh = time.Minute
	}
	return reason, channel, anchor, fresh
}

func isLockTimeout(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == pgLockNotAvailable
}

// updateTailSQL 의 가드 두 조건은 둘 다 필수다.
// 하나만 빠져도 이미 업로드된 행이나 꼬리가 아닌 행이 수정되어 뒤 행들의 PTS 가 전부 어긋난다.
// 최종 판정을 DB 가 내리게 하는 것이 요점이다 — 메모리 커서와 DB 가 어긋나 있을 수 있다.
const updateTailSQL = `
UPDATE stream_segments
   SET duration_ms = $3, bytes = $4
 WHERE stream_id = $1
   AND seq = $2
   AND seq = (SELECT max(seq) FROM stream_segments WHERE stream_id = $1)
   AND upload_state = 'pending'`

func (s *pgStore) UpdateTail(ctx context.Context, streamID string, seq int64, durationMS int32, bytes int64) (bool, error) {
	tag, err := s.pool.Exec(ctx, updateTailSQL, streamID, seq, durationMS, bytes)
	if err != nil {
		return false, fmt.Errorf("꼬리 정정 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
	}
	return tag.RowsAffected() == 1, nil
}
