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
	// Insert 는 세션 결정·PDT 산출·행 삽입·컷오프 주조를 **한 트랜잭션**으로 수행하고,
	// 그중 행 삽입과 주조는 **한 문장(CTE)** 이다(계약 6항 "행 생성과 같은 트랜잭션" —
	// 게이트 b·d2). 입력이 둘로 갈린 것은 판정 축이 둘이기 때문이다:
	// seed 는 주조 판정 입력이고(Eligible=false 면 주조 WHERE 가 막아 구 동작과 같다 — c1),
	// src 는 세션 결정 입력이다(유입 연산 + 관측 스냅샷).
	//
	// **Record 의 carrier 3열은 입력이 아니라 이 트랜잭션의 산출이다** — 실어 보낸 값은
	// 쓰이지 않는다. 값을 정하는 자리가 둘이면 어느 쪽이 이겼는지 장부만 보고 알 수 없다.
	Insert(ctx context.Context, r Record, seed Seed, src SessionSource) (InsertOutcome, SeedResult, error)
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

// sessionOneLiveUniqueName 은 ddl.go 가 만드는 부분 UNIQUE 인덱스 이름이다(스트림당 live 1).
// 이 이름이 걸리면 "다른 시도가 같은 스트림의 세션을 먼저 열었다" = 정상 경합이다.
const sessionOneLiveUniqueName = "stream_sessions_one_live_uq"

// uniqueViolation 은 23505 의 제약 이름을 돌려준다(ok=false 면 23505 가 아니다).
//
// 세 갈래(경로 중복·seq 충돌·세션 경합)가 같은 SQLSTATE 로 오고 이름으로만 갈리므로,
// 이름을 꺼내는 자리를 하나로 둔다 — 갈래가 늘 때 분기를 빠뜨릴 자리가 없어진다.
func uniqueViolation(err error) (constraint string, ok bool) {
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) || pgErr.Code != pgUniqueViolation {
		return "", false
	}
	return pgErr.ConstraintName, true
}

type pgStore struct {
	pool        *pgxpool.Pool
	decider     SessionDecider
	playbackKey PlaybackKeyFunc
}

// NewPGStore 는 PostgreSQL 구현을 돌려준다.
//
// 협력자 둘은 **생성자 축**이다: 조각마다 바뀌는 값이 아니라 조립 때 한 번 정해지는
// 정책이다(조각마다 바뀌는 것은 Insert 의 seed·src 다). 각각 nil 이면 널 오브젝트로 편다 —
// 배선을 빠뜨린 프로세스는 세션을 열지 않고 키를 만들지 않으며, 그 방향은 언제나
// 안전(비주조)하다(설계 S3).
func NewPGStore(pool *pgxpool.Pool, decider SessionDecider, playbackKey PlaybackKeyFunc) Store {
	if decider == nil {
		decider = noSessionDecider{}
	}
	if playbackKey == nil {
		playbackKey = noPlaybackKey
	}
	return &pgStore{pool: pool, decider: decider, playbackKey: playbackKey}
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
// 호출자(indexer)는 이것을 단일 쓰기자 전제(D10)가 흔들린다는 신호로 별도 기록하되,
// 처분은 H9 의 일반 백오프 재시도에 맡긴다 — 단일 호출자가 이 조각을 물고 있는 동안은
// 후속 조각이 seq 를 선점할 수 없고(순서 보전이 구조적으로 보장되는 유일한 구간),
// 상한 소진 시의 종료·재기동(D8)은 기존 처분 그대로다.
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

// setTxnLimitsSQL 은 트랜잭션 지역 상한이자 **판정 시각의 채취 지점**이다.
// lock_timeout 5s 는 OBS_FRESH(30초)보다 충분히 작아 공시가 성립하고,
// statement_timeout 10s 는 개별 문장 상한이다(COMMIT 은 TxnDeadline).
//
// 시각을 같은 문장에 실어 왕복을 늘리지 않는다. FROM 절의 하위 질의가 먼저 평가되므로
// 상한이 걸린 **뒤**의 시각인 것이 문장 구조로 보장된다.
const setTxnLimitsSQL = `
SELECT clock_timestamp()
  FROM (SELECT set_config('lock_timeout','5s',true),
               set_config('statement_timeout','10s',true)) AS _limits`

// lockCutoffStreamSQL 은 주조 직렬화 락이자 **락 대기 뒤 판정 시각**의 채취다.
// 마찬가지로 FROM 절이 먼저 평가되므로 시각은 언제나 락 획득 이후다.
const lockCutoffStreamSQL = `
SELECT clock_timestamp()
  FROM (SELECT pg_advisory_xact_lock(hashtext('pc_cutoff_' || $1::text))) AS _lock`

const cutoffExistsSQL = `SELECT EXISTS (SELECT 1 FROM stream_cutoffs WHERE stream_id = $1)`

func (s *pgStore) Insert(ctx context.Context, r Record, seed Seed, src SessionSource) (InsertOutcome, SeedResult, error) {
	out, res, err := s.insertOnce(ctx, r, seed, src)
	if err != nil && isLockTimeout(err) {
		// 55P03 → 즉시 1회 재시도(설계 6.5.5 · m1b). 재시도도 락이면 ErrLockContended 로
		// 올린다 — 처분(일반 재시도 → 소진 시 D8)은 호출자 몫이다.
		out, res, err = s.insertOnce(ctx, r, seed, src)
		if err != nil && isLockTimeout(err) {
			return out, res, fmt.Errorf("%w: stream_id=%q seq=%d", ErrLockContended, r.StreamID, r.Seq)
		}
	}
	return out, res, err
}

func (s *pgStore) insertOnce(ctx context.Context, r Record, seed Seed, src SessionSource) (InsertOutcome, SeedResult, error) {
	txctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()

	tx, err := s.pool.Begin(txctx)
	if err != nil {
		return InsertInserted, SeedResult{}, fmt.Errorf("주조 트랜잭션 시작 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}
	// Commit 성공 후의 Rollback 은 무해한 no-op 이다.
	defer func() { _ = tx.Rollback(txctx) }()

	// ⑴ 상한 + 판정 시각. 비적격 INSERT 는 아래 직렬화 대기가 없으므로 이 자리의 시각이
	// 곧 판정 시각이고, 적격 INSERT 는 대기가 끝난 뒤로 갱신된다.
	var now time.Time
	if err := tx.QueryRow(txctx, setTxnLimitsSQL).Scan(&now); err != nil {
		return InsertInserted, SeedResult{}, fmt.Errorf("트랜잭션 상한 설정 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}

	// ⑴ 주조 시도끼리는 스트림 스코프 advisory 락으로 **시간 판정보다 앞에서** 직렬화한다.
	// CTE 의 clock_timestamp() 는 소스 행 생성 시점에 평가되므로, stream_cutoffs UNIQUE
	// 검사에서 미커밋 경합을 만나면 그 대기는 시간 판정 **뒤**가 되어 — 경합이 롤백되면
	// 낡은 방증으로 주조될 수 있다(cx 리뷰 차단 2). 대기를 여기(판정 앞)로 끌어오면
	// "락 대기 뒤 재검"(m1a) 의미론이 복원된다. advisory 대기도 lock_timeout 을 따르므로
	// 55P03 경로(m1b)와 일관된다. 비적격 INSERT 는 컷오프 경쟁이 없으므로 직렬화하지 않는다.
	//
	// 세션 결정의 관측 신선도도 **이 대기 뒤의 시각**으로 재야 한다(계획 4.1 ⑶):
	// 대기 앞에서 굳히면 3초 대기 뒤 32초짜리 관측으로 세션이 열린다.
	if seed.Eligible {
		if err := tx.QueryRow(txctx, lockCutoffStreamSQL, r.StreamID).Scan(&now); err != nil {
			return InsertInserted, SeedResult{}, fmt.Errorf("주조 직렬화 락 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
		}
	}

	// ⑵~⑹ 세션 결정 → 기저 행 → PDT → 세션 쓰기 → 키. carrier 3열이 여기서 정해진다.
	r, err = s.settleCarrier(txctx, tx, r, src, now.UTC())
	if err != nil {
		return InsertInserted, SeedResult{}, err
	}

	// ⓐ 가 거짓이어도 $14~$18 은 유효한 값으로 내려간다 — seed 의 WHERE 가 막으므로
	// CHECK 에 닿을 행 자체가 없지만, 드라이버 층에서 놀랄 일을 만들지 않는다.
	reason, channel, anchor, fresh := seedArgs(seed)

	// inserted 는 CTE 결과 형상(두 열) 유지용이다 — 성공 경로에선 항상 1이라 소비하지 않는다.
	var inserted, seeded int
	err = tx.QueryRow(txctx, insertSeedSQL,
		r.StreamID, r.Seq, r.StartPTSMS, r.StartWallUTC.UTC(), r.DurationMS, r.S3Key,
		r.LocalPath, string(r.UploadState), r.Bytes, r.IsDiscontinuity,
		r.SessionID, r.PlaybackPDT, r.PlaybackS3Key, // carrier — nil ⇒ NULL(5.4.2)
		reason, channel, seed.Eligible, anchor, fresh,
	).Scan(&inserted, &seeded)
	if err != nil {
		if name, ok := uniqueViolation(err); ok {
			// 롤백(defer)으로 컷오프도, 방금 연 세션도 남지 않는다(d4).
			if name == localPathUniqueName {
				return InsertDuplicatePath, SeedResult{}, nil
			}
			return InsertSeqConflict, SeedResult{}, nil
		}
		return InsertInserted, SeedResult{}, fmt.Errorf("행 삽입 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}
	if err := tx.Commit(txctx); err != nil {
		return InsertInserted, SeedResult{}, fmt.Errorf("주조 트랜잭션 커밋 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}

	// 여기서부터는 삽입이 **이미 커밋됐다** — 귀속 진단의 실패를 Insert 의 에러로 올리면
	// 성공한 삽입이 실패로 오보고돼 재시도 → 23505 → (진단 실패 지속 시) 크래시루프가
	// 된다(cc 리뷰 차단 2, 라이브 재현). 진단 실패는 SeedResult.DiagErr 로만 나른다.
	return InsertInserted, s.resolveSeed(ctx, r, seed, seeded), nil
}

// ErrSessionContended 는 세션 개시가 one_live_uq 경합으로 밀렸음을 뜻한다.
//
// 처분은 ErrLockContended 와 같다 — H9 의 일반 백오프 재시도다. 재시도하면 앞선 시도가
// 만든 live 세션이 보여 귀속 갈래로 접히므로 경합은 스스로 풀린다.
//
// **stream_sessions_pkey 충돌은 여기 합류시키지 않는다**(계획 4.3 ⒏): 그것은 경합이 아니라
// 세션 id 파생 규칙의 결함이고, 파생이 결정적이면 재시도가 같은 id 를 다시 만들어
// 재시도 상한 소진 → 재기동 → 같은 충돌의 크래시루프가 된다. 그 갈래는 기존 무결성 오류
// 경로(fatal)로 가서 사람을 부른다.
var ErrSessionContended = errors.New("index: 세션 개시 경합(one_live_uq) — 다른 시도가 먼저 열었다")

// settleCarrier 는 계획 4.1 의 ⑵~⑹ 이다 — 한 트랜잭션 안에서 세션·PDT·키를 정해
// Record 의 carrier 3열을 채운다. 순서가 곧 계약이다.
//
// **carrier 의 값은 여기서만 정해진다**: 호출자가 Record 에 실어 보낸 값은 지우고 시작한다
// (Record 는 값으로 받으므로 호출자의 사본은 그대로다). 값을 정하는 자리가 둘이면
// 장부만 보고 어느 쪽이 이겼는지 알 수 없다.
//
// 비귀속·비개시(정책 거부 또는 CurrentOnly 인데 현 세션 부재)면 ⑷⑸ 를 건너뛰고 셋 다
// nil 로 남는다 — ⓒ 가 주조를 막아 not_settleable 로 귀속된다(설계 6.5.2 · M2 와 결과 동일).
func (s *pgStore) settleCarrier(ctx context.Context, tx pgx.Tx, r Record, src SessionSource, now time.Time) (Record, error) {
	r.SessionID, r.PlaybackPDT, r.PlaybackS3Key = nil, nil, nil

	// ⑵⑶ 갈래와 기저 세션. DB 오류는 삼키지 않는다 — "정책상 안 열었다"와 구분되지 않으면
	// 재시도가 엉뚱한 방향으로 간다(계획 4.3 ⒉).
	dec, err := s.decider.Decide(ctx, tx, SessionInput{
		StreamID: r.StreamID, Seq: r.Seq, StartWallUTC: r.StartWallUTC.UTC(),
		DurationMS: r.DurationMS, SessionSource: src,
	}, now)
	if err != nil {
		return r, sessionError("세션 결정 실패", err, r)
	}
	if !dec.Opens && dec.SessionID == "" {
		return r, nil
	}

	// ⑷ 기저 행 1행 → PDT. 개시 갈래는 이 값이 곧 새 세션의 first_pdt 라 ⑸ 보다 앞이다.
	pdt, err := s.playbackPDT(ctx, tx, r, dec.BaseSessionID)
	if err != nil {
		return r, err
	}

	// ⑸ 개시 갈래만 세션 표를 쓴다(계속 갈래는 세션 표를 건드리지 않는다).
	sessionID := dec.SessionID
	if dec.Opens {
		if sessionID, err = s.decider.Open(ctx, tx, dec, pdt); err != nil {
			return r, sessionError("세션 개시 실패", err, r)
		}
	}
	r.SessionID, r.PlaybackPDT = &sessionID, &pdt

	// ⑹ ③ 키 파생. **실패는 오류가 아니라 playback_s3_key NULL 이다**(설계 5.2):
	// ⓒ 가 주조를 막고 다음 조각이 재시도한다. 세션·PDT 까지 함께 버리면 그 조각이
	// 되감기 목록에서 통째로 사라지므로, 잃는 것을 키 하나로 좁힌다.
	if key, keyErr := s.playbackKey(r.StreamID, r.Seq); keyErr == nil {
		r.PlaybackS3Key = &key
	}
	return r, nil
}

// sessionError 는 세션 결정·개시의 실패를 호출자가 처분할 수 있는 형태로 바꾼다.
// 정상 경합만 재시도 가능한 sentinel 로 갈라내고, 나머지는 원인을 그대로 실어 올린다.
func sessionError(what string, err error, r Record) error {
	if name, ok := uniqueViolation(err); ok && name == sessionOneLiveUniqueName {
		return fmt.Errorf("%w: stream_id=%q seq=%d: %w", ErrSessionContended, r.StreamID, r.Seq, err)
	}
	return fmt.Errorf("%s stream_id=%q seq=%d: %w", what, r.StreamID, r.Seq, err)
}

// basePDTSQL 은 PDT 재귀식의 k−1 항이다 — **기저 세션에 귀속된** 마지막 행 1행.
//
// stream_segments_session_idx 는 3열 (stream_id, session_id, seq) 이므로 선행 컬럼
// stream_id 를 술어에 두지 않으면 인덱스를 벗어나 표 전체를 훑는다.
// playback_pdt 술어를 두지 않는 것도 의도다 — 그 컬럼은 어떤 인덱스에도 없어
// NULL 꼬리를 행마다 힙 페치로 훑게 되고, 배포 경계처럼 꼬리가 길면 문장 상한을 넘긴다.
const basePDTSQL = `
SELECT playback_pdt, duration_ms
  FROM stream_segments
 WHERE stream_id = $1 AND session_id = $2 AND seq < $3
 ORDER BY seq DESC
 LIMIT 1`

// playbackPDT 는 설계 5.1.1 의 장부 재귀식이다.
//
//	playback_pdt(k) = max( playback_pdt(k−1) + duration_ms(k−1), start_wall_utc(k) )
//
// 누적항이 하한이라 벽시계가 역행해도 단조가 유지되고(S7), 순단이면 벽시계가 이겨
// 공백만큼 전진한다. 기저항이 없으면(기저 세션 부재 · 그 세션의 앞 행 없음 · 그 행의
// pdt NULL) 벽시계가 그대로 값이 된다 — 세션 첫 조각의 first_pdt 가 이 값이다.
func (s *pgStore) playbackPDT(ctx context.Context, tx pgx.Tx, r Record, baseSessionID string) (time.Time, error) {
	wall := r.StartWallUTC.UTC()
	if baseSessionID == "" {
		return wall, nil
	}

	var basePDT *time.Time
	var baseDurationMS int32
	err := tx.QueryRow(ctx, basePDTSQL, r.StreamID, baseSessionID, r.Seq).Scan(&basePDT, &baseDurationMS)
	if errors.Is(err, pgx.ErrNoRows) {
		return wall, nil
	}
	if err != nil {
		return time.Time{}, fmt.Errorf("PDT 기저 행 조회 실패 stream_id=%q seq=%d base_session_id=%q: %w",
			r.StreamID, r.Seq, baseSessionID, err)
	}
	// pdt NULL 방어 — "session_id 가 있으면 playback_pdt 도 있다"는 M3 쓰기 경로의 성질이지
	// DB 가 강제하는 제약이 아니다(불변 트리거는 컬럼별 독립). 그런 행은 기저항 부재로 본다.
	if basePDT == nil {
		return wall, nil
	}

	// 누적은 밀리초 그대로 더한다 — 정수 초 나눗셈은 999ms 까지 잘라 그 손실을 다음 항에
	// 이월시키고, 조각마다 쌓여 재귀식이 조용히 퇴화한다.
	if acc := basePDT.UTC().Add(time.Duration(baseDurationMS) * time.Millisecond); acc.After(wall) {
		return acc, nil
	}
	return wall, nil
}

// resolveSeed 는 Decline 귀속(설계 6.5.5)이다. 커밋 뒤에만 부르며, 어떤 실패도
// 에러로 반환하지 않는다 — 귀속은 관측 신호이지 결과가 아니다.
func (s *pgStore) resolveSeed(ctx context.Context, r Record, seed Seed, seeded int) SeedResult {
	if seeded == 1 {
		return SeedResult{Seeded: true}
	}
	if !seed.Eligible {
		return SeedResult{Decline: DeclineNoCorroboration}
	}
	if r.SessionID == nil || r.PlaybackPDT == nil || r.PlaybackS3Key == nil {
		return SeedResult{Decline: DeclineNotSettleable}
	}
	// 자격 전부 참인데 seeded=0 — 기존 컷오프 승계(정상)인지 시간 항 탈락인지 가른다.
	// 진단은 관측용이라 서비스 수명 ctx 로 매달리면 안 된다 — 짧은 상한을 준다.
	dctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	var exists bool
	if err := s.pool.QueryRow(dctx, cutoffExistsSQL, r.StreamID).Scan(&exists); err != nil {
		return SeedResult{
			Decline: DeclineStaleCorroboration,
			DiagErr: fmt.Errorf("컷오프 존재 확인 실패 stream_id=%q: %w", r.StreamID, err),
		}
	}
	if exists {
		return SeedResult{Decline: DeclineSkipped}
	}
	return SeedResult{Decline: DeclineStaleCorroboration}
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
