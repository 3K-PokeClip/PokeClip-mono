package index

import (
	"context"
	"errors"
	"fmt"

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
	Insert(ctx context.Context, r Record) (InsertOutcome, error)
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

const loadCursorSQL = `
SELECT seq, start_pts_ms, start_wall_utc, duration_ms, bytes, local_path, upload_state
  FROM stream_segments WHERE stream_id = $1 ORDER BY seq DESC LIMIT 1`

func (s *pgStore) LoadCursor(ctx context.Context, streamID string) (Cursor, error) {
	var (
		tail      TailRow
		bytes     *int64
		localPath *string
	)
	err := s.pool.QueryRow(ctx, loadCursorSQL, streamID).Scan(
		&tail.Seq, &tail.StartPTSMS, &tail.StartWallUTC, &tail.DurationMS,
		&bytes, &localPath, &tail.UploadState,
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

const insertSQL = `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key,
     local_path, upload_state, bytes, is_discontinuity)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`

func (s *pgStore) Insert(ctx context.Context, r Record) (InsertOutcome, error) {
	_, err := s.pool.Exec(ctx, insertSQL,
		r.StreamID, r.Seq, r.StartPTSMS, r.StartWallUTC.UTC(), r.DurationMS, r.S3Key,
		r.LocalPath, string(r.UploadState), r.Bytes, r.IsDiscontinuity,
	)
	if err == nil {
		return InsertInserted, nil
	}

	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == pgUniqueViolation {
		if pgErr.ConstraintName == localPathUniqueName {
			return InsertDuplicatePath, nil
		}
		return InsertSeqConflict, nil
	}
	return InsertInserted, fmt.Errorf("행 삽입 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
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
