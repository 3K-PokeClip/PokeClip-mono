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
	Insert(ctx context.Context, r Record, seed Seed, src SessionSource) (InsertOutcome, InsertCommit, error)
	// UpdateTail 은 duration_ms 와 bytes 를 사후 정정한다. 계약상 쓰기 주체가 1번이므로 허용된다.
	// upload_state 는 건드리지 않는다. videoEndNS 는 꼬리의 영상 끝(timing_video_end_ns)이다 — nil 이면 그 칸을 건드리지
	// 않고, 옛 값이 NULL 인 꼬리도 그 칸을 넘기지 않는다(설계 r7 2.14). 줄이는 값은 불변 트리거가 거부해 오류다.
	UpdateTail(ctx context.Context, streamID string, seq int64, durationMS int32, bytes int64, videoEndNS *int64) (updated bool, err error)
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

// loadCursorSQL 은 다음 seq 와 꼬리 행을 한 문장으로 읽는다. 다음 seq = GREATEST(하한, max(seq)+1) — GREATEST 는
// NULL 을 건너뛰므로 둘 중 있는 값, 둘 다 없으면 NULL(첫 세그먼트)이다. 하한(stream_seq_floors)은 행을 지울 때
// 트리거가 올린 값이라, 만료가 마지막 행까지 지운 스트림도 seq 를 다시 쓰지 않는다(불변식 4).
// 바깥 한 행(one)에 두 LATERAL 을 붙여 행이 없어도 결과는 늘 한 행이다.
//
// 꼬리 열은 행이 없으면 NULL 이라 포인터로 받는다. SELECT 목록 순서와 아래 Scan 인자 순서는 반드시 같아야 한다 —
// s3_key 와 local_path 는 둘 다 text 라 순서를 바꿔도 컴파일·실행이 통과한다. store_test.go 의 값 대조가 그 방어선이다.
// 원래 사실 칸 다섯(설계 r7 2.3)도 꼬리에 싣는다 — 재기동 · 재스캔 뒤에도 꼬리와의 이어짐을 같은 답으로 정하는 재료다.
// 두 ns 칸도 bigint 라 순서를 바꿔도 통과한다 — timing_store_pg_test.go 의 값 대조가 막는다.
const loadCursorSQL = `
SELECT GREATEST(f.next_seq, t.seq + 1::bigint),
       t.seq, t.start_pts_ms, t.start_wall_utc, t.duration_ms, t.bytes, t.local_path, t.upload_state, t.s3_key,
       t.timing_wall_guess, t.timing_recorder_id, t.timing_segment_number, t.timing_video_first_ns, t.timing_video_end_ns
  FROM (SELECT 1) one
  LEFT JOIN LATERAL (SELECT next_seq FROM stream_seq_floors WHERE stream_id = $1) f ON true
  LEFT JOIN LATERAL (SELECT seq, start_pts_ms, start_wall_utc, duration_ms, bytes, local_path, upload_state, s3_key,
                            timing_wall_guess, timing_recorder_id, timing_segment_number, timing_video_first_ns,
                            timing_video_end_ns
                       FROM stream_segments WHERE stream_id = $1 ORDER BY seq DESC LIMIT 1) t ON true`

// cursorRow 는 loadCursorSQL 한 행이다 — 꼬리 열은 행이 없으면 NULL 이다.
type cursorRow struct {
	nextSeq, seq, startPTSMS, bytes *int64
	startWallUTC                    *time.Time
	durationMS                      *int32
	localPath, s3Key                *string
	uploadState                     *UploadState
	facts                           factColumns
}

func (s *pgStore) LoadCursor(ctx context.Context, streamID string) (Cursor, error) {
	var r cursorRow
	err := s.pool.QueryRow(ctx, loadCursorSQL, streamID).Scan(
		&r.nextSeq, &r.seq, &r.startPTSMS, &r.startWallUTC, &r.durationMS,
		&r.bytes, &r.localPath, &r.uploadState, &r.s3Key,
		&r.facts.wall, &r.facts.recorder, &r.facts.number, &r.facts.videoFirst, &r.facts.videoEnd,
	)
	if err != nil {
		return Cursor{}, fmt.Errorf("커서 적재 실패 stream_id=%q: %w", streamID, err)
	}
	// 하한도 행도 없다 = 이 스트림의 첫 세그먼트다. 에러가 아니라 정상 상태다.
	if r.nextSeq == nil {
		return Cursor{}, nil
	}
	cur := Cursor{NextSeq: *r.nextSeq}
	// 행이 없고 하한만 있다 = 만료가 꼬리까지 지운 스트림이다. 꼬리 없이 하한부터 쓴다.
	if r.seq == nil {
		return cur, nil
	}
	tail := TailRow{
		Seq: *r.seq, StartPTSMS: *r.startPTSMS, DurationMS: *r.durationMS,
		UploadState: *r.uploadState, S3Key: *r.s3Key,
		// 저장은 UTC 강제지만 드라이버가 로컬 위치로 실어 올 수 있어 여기서 한 번 못 박는다(D6).
		StartWallUTC: r.startWallUTC.UTC(),
		Timing:       r.facts.toFacts(),
	}
	// bytes 와 local_path 는 NULL 을 허용하는 컬럼이라 영값으로 편다.
	if r.bytes != nil {
		tail.Bytes = *r.bytes
	}
	if r.localPath != nil {
		tail.LocalPath = *r.localPath
	}
	cur.Tail = &tail
	return cur, nil
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
//   - PDT 판 칸 일곱($14~$20 — 설계 r7 2.3 · 2.4)은 판 · 원래 사실 · 이어 계산 회차다. 쓰기자 거부 트리거와 CHECK 넷이
//     이 칸을 본다.
const insertSeedSQL = `
WITH ins AS (
    INSERT INTO stream_segments
        (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key,
         local_path, upload_state, bytes, is_discontinuity,
         session_id, playback_pdt, playback_s3_key,
         timing_version, timing_wall_guess, timing_recorder_id, timing_segment_number,
         timing_video_first_ns, timing_video_end_ns, timing_round_id)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13,
            $14, $15, $16, $17, $18, $19, $20)
    RETURNING stream_id, seq, session_id, playback_pdt, playback_s3_key
), seed AS (
    INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
    SELECT i.stream_id, i.seq, $21, $22
      FROM ins i
     WHERE $23::boolean
       AND i.session_id      IS NOT NULL
       AND i.playback_pdt    IS NOT NULL
       AND i.playback_s3_key IS NOT NULL
       AND pg_catalog.clock_timestamp() - $24::timestamptz <= $25::interval
    ON CONFLICT (stream_id) DO NOTHING
    RETURNING 1
)
SELECT (SELECT pg_catalog.count(*) FROM ins)  AS inserted,
       (SELECT pg_catalog.count(*) FROM seed) AS seeded`

// setTxnLimitsSQL 은 트랜잭션 지역 상한이자 **판정 시각의 채취 지점**이다.
// lock_timeout 5s 는 OBS_FRESH(30초)보다 충분히 작아 공시가 성립하고,
// statement_timeout 10s 는 개별 문장 상한이다(COMMIT 은 TxnDeadline).
//
// 시각을 같은 문장에 실어 왕복을 늘리지 않는다. FROM 절의 하위 질의가 먼저 평가되므로
// 상한이 걸린 **뒤**의 시각인 것이 문장 구조로 보장된다.
const setTxnLimitsSQL = `
SELECT pg_catalog.clock_timestamp()
  FROM (SELECT pg_catalog.set_config('lock_timeout','5s',true),
               pg_catalog.set_config('statement_timeout','10s',true)) AS _limits`

// lockCutoffStreamSQL 은 주조 직렬화 락이자 **락 대기 뒤 판정 시각**의 채취다.
// 마찬가지로 FROM 절이 먼저 평가되므로 시각은 언제나 락 획득 이후다.
// hashtext 는 integer 를 내고 pg_catalog 의 잠금 함수는 bigint 판뿐이라 ::bigint 로 정확히 맞춘다 — 맞추지 않으면
// public 의 integer 판(앱 사용자가 만들 수 있다)이 먼저 골린다. 값은 암묵 변환과 같아 잠금 열쇠는 그대로다.
const lockCutoffStreamSQL = `
SELECT pg_catalog.clock_timestamp()
  FROM (SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('pc_cutoff_' || $1::text)::bigint)) AS _lock`

const cutoffExistsSQL = `SELECT EXISTS (SELECT 1 FROM stream_cutoffs WHERE stream_id = $1)`

func (s *pgStore) Insert(ctx context.Context, r Record, seed Seed, src SessionSource) (InsertOutcome, InsertCommit, error) {
	out, res, err := s.insertOnce(ctx, r, seed, src)
	if err != nil && isLockTimeout(err) {
		// 55P03 → 즉시 1회 재시도(설계 6.5.5 · m1b). 재시도도 락이면 ErrLockContended 로
		// 올린다 — 처분(일반 재시도 → 소진 시 D8)은 호출자 몫이다. 원 오류도 함께 감싼다 — 어느
		// 잠금(컷오프 · 판 게이트)에서 끊겼는지가 그 문구에 있다(c4a 보안 L2).
		out, res, err = s.insertOnce(ctx, r, seed, src)
		if err != nil && isLockTimeout(err) {
			return out, res, fmt.Errorf("%w: stream_id=%q seq=%d: %w", ErrLockContended, r.StreamID, r.Seq, err)
		}
	}
	return out, res, err
}

// insertOnce 는 실패한 트랜잭션의 ROLLBACK 에도 되돌리는 순간부터 부른 쪽 취소와 무관하게
// TxnDeadline 을 따로 씌운다 — 실패한 트랜잭션 하나는 최악 TxnDeadline 의 두 배가 걸린다(커밋하지
// 않는 경로라 커밋 상한과 무관).
func (s *pgStore) insertOnce(ctx context.Context, r Record, seed Seed, src SessionSource) (InsertOutcome, InsertCommit, error) {
	txctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()

	// 격리 수준을 READ COMMITTED 로 못 박는다 — 잠금을 기다린 뒤의 문장이 그 사이 커밋된 값을 보는 것은 이 수준에서만이다
	// (게이트 공유 잠금 뒤 스위치 읽기 · 세션 소속 잠금 뒤 재판정 · 컷오프 직렬화 락 뒤 시각). DB · 역할의 기본값
	// (default_transaction_isolation)이 repeatable read 면 첫 문장의 스냅숏으로 낡은 스위치를 읽어 래치 없는 장부에 판 2 행을
	// 쓴다(broadcast/outbox_rows.go abandon 과 같은 처방).
	tx, err := s.pool.BeginTx(txctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return InsertInserted, InsertCommit{}, fmt.Errorf("주조 트랜잭션 시작 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}
	// 되돌림 ctx 는 되돌리는 순간에 만든다 — 부른 쪽 ctx 에서 취소와 시한을 떼고
	// TxnDeadline 을 새로 씌운다. 끝난 ctx 로 부르면 pgx 는 ROLLBACK 을 보내지 못하고
	// 연결을 버리는데, 트랜잭션이 상한을 다 쓴 갈래에서 txctx 는 끝나 있고 미리 만들어 둔
	// ctx 도 거의 같이 끝난다. Commit 뒤(성공 · 실패 모두)의 Rollback 은 문장을 보내지 않는
	// no-op 이다.
	defer func() {
		rctx, rcancel := context.WithTimeout(context.WithoutCancel(ctx), TxnDeadline)
		defer rcancel()
		_ = tx.Rollback(rctx)
	}()

	// ⑴ 상한 + 판정 시각. 비적격 INSERT 는 아래 직렬화 대기가 없으므로 이 자리의 시각이
	// 곧 판정 시각이고, 적격 INSERT 는 대기가 끝난 뒤로 갱신된다.
	var now time.Time
	if err := tx.QueryRow(txctx, setTxnLimitsSQL).Scan(&now); err != nil {
		return InsertInserted, InsertCommit{}, fmt.Errorf("트랜잭션 상한 설정 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}

	// ⑴ 주조 시도끼리는 스트림 스코프 advisory 락으로 **시간 판정보다 앞에서** 직렬화한다.
	// CTE 의 clock_timestamp() 는 소스 행 생성 시점에 평가되므로, stream_cutoffs UNIQUE
	// 검사에서 미커밋 경합을 만나면 그 대기는 시간 판정 **뒤**가 되어 — 경합이 롤백되면
	// 낡은 방증으로 주조될 수 있다(cx 리뷰 차단 2). 대기를 여기(판정 앞)로 끌어오면
	// "락 대기 뒤 재검"(m1a) 의미론이 복원된다. advisory 대기도 lock_timeout 을 따르므로
	// 55P03 경로(m1b)와 일관된다. 비적격 INSERT 는 컷오프 경쟁이 없으므로 직렬화하지 않는다. 한계(c4b 보안 L1): 그래서
	// 같은 스트림에 쓰기자가 둘이면(롤링 배포가 단일 쓰기자 전제 D10 을 깰 때) 두 비적격 행이 같은 스냅숏의 앞 행(p · q)을
	// 읽어 위치 · PDT 를 겹쳐 쓸 수 있다 — c4a 부터 그대로이고, 단일 쓰기자에서는 생기지 않는다.
	//
	// 세션 결정의 관측 신선도도 **이 대기 뒤의 시각**으로 재야 한다(계획 4.1 ⑶):
	// 대기 앞에서 굳히면 3초 대기 뒤 32초짜리 관측으로 세션이 열린다.
	if seed.Eligible {
		if err := tx.QueryRow(txctx, lockCutoffStreamSQL, r.StreamID).Scan(&now); err != nil {
			return InsertInserted, InsertCommit{}, fmt.Errorf("주조 직렬화 락 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
		}
	}

	// ⑵~⑹ 세션 결정 → 판 정하기 → 기저 행 → PDT → 세션 쓰기 → 위치 → 키. carrier 3열 · 위치 · 판 칸이 여기서 정해진다.
	var st settlement
	r, st, err = s.settleCarrier(txctx, tx, r, src, now.UTC())
	if err != nil {
		return InsertInserted, InsertCommit{}, err
	}
	timing := st.timing

	// ⓐ 가 거짓이어도 $21~$25 는 유효한 값으로 내려간다 — seed 의 WHERE 가 막으므로
	// CHECK 에 닿을 행 자체가 없지만, 드라이버 층에서 놀랄 일을 만들지 않는다.
	reason, channel, anchor, fresh := seedArgs(seed)
	facts := factColumnsOf(r.Timing.Facts)

	// inserted 는 CTE 결과 형상(두 열) 유지용이다 — 성공 경로에선 항상 1이라 소비하지 않는다.
	var inserted, seeded int
	err = tx.QueryRow(txctx, insertSeedSQL,
		r.StreamID, r.Seq, r.StartPTSMS, r.StartWallUTC.UTC(), r.DurationMS, r.S3Key,
		r.LocalPath, string(r.UploadState), r.Bytes, r.IsDiscontinuity,
		r.SessionID, r.PlaybackPDT, r.PlaybackS3Key, // carrier — nil ⇒ NULL(5.4.2)
		timing.version, facts.wall, facts.recorder, facts.number, facts.videoFirst, facts.videoEnd, nullIfEmpty(timing.roundID),
		reason, channel, seed.Eligible, anchor, fresh,
	).Scan(&inserted, &seeded)
	if err != nil {
		if name, ok := uniqueViolation(err); ok {
			// 롤백(defer)으로 컷오프도, 방금 연 세션도 남지 않는다(d4).
			if name == localPathUniqueName {
				return InsertDuplicatePath, InsertCommit{}, nil
			}
			return InsertSeqConflict, InsertCommit{}, nil
		}
		return InsertInserted, InsertCommit{}, fmt.Errorf("행 삽입 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}
	if err := tx.Commit(txctx); err != nil {
		return InsertInserted, InsertCommit{}, fmt.Errorf("주조 트랜잭션 커밋 실패 stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}

	// 여기서부터는 삽입이 **이미 커밋됐다** — 귀속 진단의 실패를 Insert 의 에러로 올리면
	// 성공한 삽입이 실패로 오보고돼 재시도 → 23505 → (진단 실패 지속 시) 크래시루프가
	// 된다(cc 리뷰 차단 2, 라이브 재현). 진단 실패는 InsertCommit.DiagErr 로만 나른다.
	return InsertInserted, withCommitted(s.resolveSeed(ctx, r, seed, seeded), r, st), nil
}

// settlement 는 settleCarrier 가 정한 것 가운데 장부 행 칸 밖의 결과다 — 세션 개시 · 판 정하기 · 판 2 섞기.
type settlement struct {
	opening sessionOpening
	timing  timingChoice
	// mix 는 판 2 행의 섞기 결과다. 판 NULL 행이면 nil 이다.
	mix *v2Mix
	// legacyU 는 판 NULL 귀속 행의 PDT 식이 더한 U 다(InsertCommit.LegacyUnattributedMS).
	legacyU int64
}

// withCommitted 는 커밋된 carrier · 위치 · 판 칸과 세션 개시 결과를 주조 결과에 얹는다.
//
// 커밋 뒤 이 한 자리에서만 부른다 — 롤백된 트랜잭션의 개시가 결과로 새어 나가지 않게
// 하는 구조가 이것이다(앞선 반환은 전부 영값 InsertCommit 이다).
func withCommitted(res InsertCommit, r Record, st settlement) InsertCommit {
	o, tc := st.opening, st.timing
	res = withMix(res, st.mix)
	res.SessionOpened = o.opened
	res.DiscontinuityBase = o.discontinuityBase
	res.TargetDuration = o.targetDuration
	res.InheritsSession = o.inheritsSession
	res.EndingSessionID = o.endingSessionID
	res.PrevFirstLocalPath = o.prevFirstLocalPath
	res.DurationMS = r.DurationMS
	res.StartPTSMS = r.StartPTSMS
	res.TimingVersion, res.TimingRoundID, res.TimingGateRead = tc.version, tc.roundID, tc.gate
	res.LegacyUnattributedMS = st.legacyU
	if r.SessionID != nil {
		res.SessionID = *r.SessionID
	}
	if r.PlaybackPDT != nil {
		res.PlaybackPDT = *r.PlaybackPDT
	}
	if r.PlaybackS3Key != nil {
		res.PlaybackS3Key = *r.PlaybackS3Key
	}
	return res
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

// settleCarrier 는 계획 4.1 의 ⑵~⑹ 이다 — 한 트랜잭션 안에서 세션 · 판 · PDT · 위치 · 키를 정해 Record 의 carrier 3열과
// start_pts_ms 를 채운다. 순서가 곧 계약이다.
//
// **carrier 와 위치의 값은 여기서만 정해진다**: 호출자가 Record 에 실어 보낸 값은 지우고 시작한다
// (Record 는 값으로 받으므로 호출자의 사본은 그대로다). 값을 정하는 자리가 둘이면
// 장부만 보고 어느 쪽이 이겼는지 알 수 없다.
//
// 비귀속·비개시(정책 거부 또는 CurrentOnly 인데 현 세션 부재)면 ⑸ 를 건너뛰고 session_id · ③ 키는 nil 로 남는다 — ⓒ 가
// 주조를 막아 not_settleable 로 귀속된다(설계 6.5.2 · M2 와 결과 동일). PDT 는 판 NULL 이면 nil, 판 2 면 섞기 값이다(설계
// r7 2.5 — 판 2 회차 안 비귀속 행도 회차의 PDT 를 잇는다).
//
// 개시 갈래면 둘째 반환값의 opening 이 새 세션 행에 쓰인 값을 싣는다(InsertCommit 의 개시 통로). timing 은 판 정하기, mix 는
// 판 2 섞기의 결과다.
func (s *pgStore) settleCarrier(ctx context.Context, tx pgx.Tx, r Record, src SessionSource, now time.Time) (Record, settlement, error) {
	r.SessionID, r.PlaybackPDT, r.PlaybackS3Key = nil, nil, nil
	r.StartPTSMS = 0

	// ⑵⑶ 갈래와 기저 세션. DB 오류는 삼키지 않는다 — "정책상 안 열었다"와 구분되지 않으면
	// 재시도가 엉뚱한 방향으로 간다(계획 4.3 ⒉).
	dec, err := s.decider.Decide(ctx, tx, SessionInput{
		StreamID: r.StreamID, Seq: r.Seq, StartWallUTC: r.StartWallUTC.UTC(),
		DurationMS: r.DurationMS, SessionSource: src,
	}, now)
	if err != nil {
		return r, settlement{}, sessionError("세션 결정 실패", err, r)
	}
	q, err := readLastRow(ctx, tx, r.StreamID, r.Seq)
	if err != nil {
		return r, settlement{}, err
	}
	if !dec.Opens && dec.SessionID == "" {
		st, err := settleUnattributed(ctx, tx, &r, dec, q)
		return r, st, err
	}
	return s.settleAttributed(ctx, tx, r, dec, q)
}

// settleUnattributed 는 비귀속 행의 판 · 회차와 PDT · 위치를 정한다. 판 NULL 이면 PDT 는 NULL 이고 위치는 q 끝이다. 판 2 면
// 귀속과 같은 섞기로 PDT · 위치 · 길이를 쓰고(설계 2.5 「판 2 비귀속 행」) 세션 · ③ 키만 nil 로 둔다 — 시작점 자격 ⓒ 가
// 주조를 막아 not_settleable 이고, ③ 업로드 조회는 세션 결합이라 그 행을 집지 않는다.
func settleUnattributed(ctx context.Context, tx pgx.Tx, r *Record, dec SessionDecision, q *lastRow) (settlement, error) {
	tc, err := unattributedTiming(ctx, tx, *r, dec.FloorRejected)
	if err != nil {
		return settlement{}, err
	}
	if err := checkWritableVersion(tc.version); err != nil {
		return settlement{}, fmt.Errorf("stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}
	if !isV2(tc.version) {
		r.StartPTSMS = legacyStart(q, nil, nil)
		return settlement{timing: tc}, nil
	}
	mix, err := settleV2(ctx, tx, r, tc.roundID, q, false, dec.FloorRejected)
	return settlement{timing: tc, mix: mix}, err
}

// settleAttributed 는 ⑷~⑹ 이다 — 기저 행 → 판 정하기 → PDT → 세션 쓰기 → 위치 → 키.
func (s *pgStore) settleAttributed(ctx context.Context, tx pgx.Tx, r Record, dec SessionDecision, q *lastRow) (Record, settlement, error) {
	// ⑷ 기저 행 1행 → 판 → PDT. 개시 갈래는 이 값이 곧 새 세션의 first_pdt 라 ⑸ 보다 앞이다.
	base, err := readBaseRow(ctx, tx, r, dec.BaseSessionID)
	if err != nil {
		return r, settlement{}, err
	}
	tc, err := attributedTiming(ctx, tx, dec, base)
	if err != nil {
		return r, settlement{}, err
	}
	if err := checkWritableVersion(tc.version); err != nil {
		return r, settlement{}, fmt.Errorf("stream_id=%q seq=%d: %w", r.StreamID, r.Seq, err)
	}
	pdt, mix, err := attributedPDT(ctx, tx, &r, dec, base, q, tc)
	if err != nil {
		return r, settlement{}, err
	}

	// ⑸ 개시 갈래만 세션 표를 쓴다(계속 갈래는 세션 표를 건드리지 않는다).
	sessionID := dec.SessionID
	var opening sessionOpening
	if dec.Opens {
		if sessionID, err = s.decider.Open(ctx, tx, dec, pdt); err != nil {
			return r, settlement{}, sessionError("세션 개시 실패", err, r)
		}
		if opening, err = readOpening(ctx, tx, sessionID); err != nil {
			return r, settlement{}, err
		}
		// 행에 쓰이지 않는 두 값은 결정에서 옮긴다 — Open 이 성공한 뒤라 개시가 실제로 쓴 결정의 값이다.
		opening.endingSessionID, opening.prevFirstLocalPath = dec.EndingSessionID, dec.PrevFirstLocalPath
	}
	if dec.Opens && dec.NewRound != (dec.RoundID == sessionID) {
		// 새 회차의 id 는 그 머리 세션의 session_id 다(M6 15.2) — 개시에서는 NewRound ⇔ RoundID == 개시가 연 session_id 다.
		// 판 · 이어 계산 회차를 Open 앞에서 NewRound · RoundID 로 이미 정했으므로, 한쪽으로든 갈리면 배선 결함이라 트랜잭션을
		// 되돌린다 — 회차가 다르면 행의 timing_round_id 가 세션의 회차와 다르게, NewRound 를 잃으면 새 회차가 게이트가 아닌
		// 기저 행의 판으로 저장된다(cm cc D1 · 보안 M-1).
		return r, settlement{}, fmt.Errorf("index: 새 회차 판별이 어긋난다 — NewRound=%t · 결정의 RoundID %q · 개시가 연 session_id %q stream_id=%q seq=%d — 배선 결함",
			dec.NewRound, dec.RoundID, sessionID, r.StreamID, r.Seq)
	}
	r.SessionID, r.PlaybackPDT = &sessionID, &pdt

	// 판 NULL 위치(I23) — PDT 가 정해진 뒤라 ⑷ 뒤다. q 에 PDT 가 없으면 앵커를 한 줄 더 읽는다. 판 2 위치는 섞기가 정했다.
	if mix == nil {
		anchor, err := readAnchor(ctx, tx, r.StreamID, r.Seq, q)
		if err != nil {
			return r, settlement{}, err
		}
		r.StartPTSMS = legacyStart(q, anchor, &pdt)
	}

	// ⑹ ③ 키 파생. **실패는 오류가 아니라 playback_s3_key NULL 이다**(설계 5.2):
	// ⓒ 가 주조를 막고 다음 조각이 재시도한다. 세션·PDT 까지 함께 버리면 그 조각이
	// 되감기 목록에서 통째로 사라지므로, 잃는 것을 키 하나로 좁힌다.
	// 회차는 결정의 RoundID 다 — 개시 갈래면 Open 이 같은 값을 대조해 썼으므로 세션의 round_id 와 같다(POK-525 E31).
	if key, keyErr := s.playbackKey(r.StreamID, dec.RoundID, r.Seq); keyErr == nil {
		r.PlaybackS3Key = &key
	}
	st := settlement{opening: opening, timing: tc, mix: mix}
	if mix == nil && base != nil && base.PDT != nil {
		st.legacyU = base.UnattributedMS // legacyPDT 가 더한 항 그대로다
	}
	return r, st, nil
}

// sessionOpening 은 이 트랜잭션이 연 세션 행에 쓰인 값과, 행에 쓰이지 않고 결정이 실어 온 개시 결과 두 값
// (TD 분할이 끝낸 옛 회차 · 계승 개시의 직전 회차 첫 조각 경로)이다. 개시가 아니면 영값이다.
type sessionOpening struct {
	opened             bool
	discontinuityBase  int64
	targetDuration     int32
	inheritsSession    string
	endingSessionID    string
	prevFirstLocalPath string
}

// openedSessionSQL 은 방금 연 세션 행에서 개시가 정한 세 열을 되읽는다.
//
// 되읽는 이유: 새 세션 행에 무엇을 쓸지는 결정자의 계획(SessionPlan)이 정하고, index 는
// 그 계획을 열지 않는다(session.go 경계). 그래서 쓰인 값을 아는 길은 행 자신뿐이다.
// 같은 트랜잭션이라 커밋될 값 그대로이며, 개시 때만 돌므로 조각마다 왕복이 늘지 않는다.
const openedSessionSQL = `
SELECT discontinuity_base, target_duration, inherits_session FROM stream_sessions WHERE session_id = $1`

func readOpening(ctx context.Context, tx pgx.Tx, sessionID string) (sessionOpening, error) {
	o := sessionOpening{opened: true}
	var inherits *string
	if err := tx.QueryRow(ctx, openedSessionSQL, sessionID).Scan(&o.discontinuityBase, &o.targetDuration, &inherits); err != nil {
		return sessionOpening{}, fmt.Errorf("개시한 세션 되읽기 실패 session_id=%q: %w", sessionID, err)
	}
	if inherits != nil {
		o.inheritsSession = *inherits
	}
	return o, nil
}

// sessionError 는 세션 결정·개시의 실패를 호출자가 처분할 수 있는 형태로 바꾼다.
// 정상 경합만 재시도 가능한 sentinel 로 갈라내고, 나머지는 원인을 그대로 실어 올린다.
func sessionError(what string, err error, r Record) error {
	if name, ok := uniqueViolation(err); ok && name == sessionOneLiveUniqueName {
		return fmt.Errorf("%w: stream_id=%q seq=%d: %w", ErrSessionContended, r.StreamID, r.Seq, err)
	}
	return fmt.Errorf("%s stream_id=%q seq=%d: %w", what, r.StreamID, r.Seq, err)
}

// basePDTSQL 은 PDT 재귀식의 k−1 항이다 — **기저 세션에 귀속된** 마지막 행 1행(j*)과, 그 뒤 k 앞까지 비귀속 행의 길이 합
// U(판 NULL 식 — placement.go)다. 그 행의 판도 함께 읽는다 — 이어 가는 회차의 판(판 정하기 1항)이 이 값이다.
//
// stream_segments_session_idx 는 3열 (stream_id, session_id, seq) 이므로 선행 컬럼
// stream_id 를 술어에 두지 않으면 인덱스를 벗어나 표 전체를 훑는다.
// playback_pdt 술어를 두지 않는 것도 의도다 — 그 컬럼은 어떤 인덱스에도 없어
// NULL 꼬리를 행마다 힙 페치로 훑게 되고, 배포 경계처럼 꼬리가 길면 문장 상한을 넘긴다.
// U 는 PK (stream_id, seq) 의 (j*.seq, k) 범위 훑기다 — 평시(j* = 바로 앞 행)에는 빈 범위다. duration_ms(integer)의 합은
// bigint 다.
const basePDTSQL = `
SELECT b.seq, b.playback_pdt, b.duration_ms, b.timing_version,
       (SELECT COALESCE(pg_catalog.sum(u.duration_ms), 0::bigint)
          FROM stream_segments u
         WHERE u.stream_id = $1 AND u.seq > b.seq AND u.seq < $3 AND u.session_id IS NULL)
  FROM (SELECT seq, playback_pdt, duration_ms, timing_version
          FROM stream_segments
         WHERE stream_id = $1 AND session_id = $2 AND seq < $3
         ORDER BY seq DESC
         LIMIT 1) b`

// readBaseRow 는 기저 세션 baseSessionID 의 마지막 귀속 행과 U 를 읽는다. 기저 세션이 없거나(""), 그 세션의 앞 행이
// 없으면 nil 이다 — PDT 는 벽시계가 그대로 값이 되고(세션 첫 조각의 first_pdt), 판은 판 정하기가 게이트에서 고른다.
func readBaseRow(ctx context.Context, tx pgx.Tx, r Record, baseSessionID string) (*baseRow, error) {
	if baseSessionID == "" {
		return nil, nil
	}
	var b baseRow
	err := tx.QueryRow(ctx, basePDTSQL, r.StreamID, baseSessionID, r.Seq).
		Scan(&b.Seq, &b.PDT, &b.DurationMS, &b.Version, &b.UnattributedMS)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("PDT 기저 행 조회 실패 stream_id=%q seq=%d base_session_id=%q: %w",
			r.StreamID, r.Seq, baseSessionID, err)
	}
	return &b, nil
}

// resolveSeed 는 Decline 귀속(설계 6.5.5)이다. 커밋 뒤에만 부르며, 어떤 실패도
// 에러로 반환하지 않는다 — 귀속은 관측 신호이지 결과가 아니다.
func (s *pgStore) resolveSeed(ctx context.Context, r Record, seed Seed, seeded int) InsertCommit {
	if seeded == 1 {
		return InsertCommit{Seeded: true}
	}
	if !seed.Eligible {
		return InsertCommit{Decline: DeclineNoCorroboration}
	}
	if r.SessionID == nil || r.PlaybackPDT == nil || r.PlaybackS3Key == nil {
		return InsertCommit{Decline: DeclineNotSettleable}
	}
	// 자격 전부 참인데 seeded=0 — 기존 컷오프 승계(정상)인지 시간 항 탈락인지 가른다.
	// 진단은 관측용이라 서비스 수명 ctx 로 매달리면 안 된다 — 짧은 상한을 준다.
	dctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	var exists bool
	if err := s.pool.QueryRow(dctx, cutoffExistsSQL, r.StreamID).Scan(&exists); err != nil {
		return InsertCommit{
			Decline: DeclineStaleCorroboration,
			DiagErr: fmt.Errorf("컷오프 존재 확인 실패 stream_id=%q: %w", r.StreamID, err),
		}
	}
	if exists {
		return InsertCommit{Decline: DeclineSkipped}
	}
	return InsertCommit{Decline: DeclineStaleCorroboration}
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
//
// 영상 끝($5 — 설계 r7 2.14 · P21)은 옛 값이 있고 넘긴 값이 있을 때만 바꾼다. 옛 값이 NULL 인 꼬리는 그 칸을 넘기지
// 않는다 — 불변 트리거가 NULL → 값을 거부하므로 넘기면 길이 교정까지 함께 실패한다. 줄이는 값은 트리거가 거부한다.
const updateTailSQL = `
UPDATE stream_segments
   SET duration_ms = $3, bytes = $4,
       timing_video_end_ns = CASE WHEN timing_video_end_ns IS NULL OR $5::bigint IS NULL THEN timing_video_end_ns
                                  ELSE $5::bigint END
 WHERE stream_id = $1
   AND seq = $2
   AND seq = (SELECT pg_catalog.max(seq) FROM stream_segments WHERE stream_id = $1)
   AND upload_state = 'pending'`

func (s *pgStore) UpdateTail(ctx context.Context, streamID string, seq int64, durationMS int32, bytes int64, videoEndNS *int64) (bool, error) {
	tag, err := s.pool.Exec(ctx, updateTailSQL, streamID, seq, durationMS, bytes, videoEndNS)
	if err != nil {
		return false, fmt.Errorf("꼬리 정정 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
	}
	return tag.RowsAffected() == 1, nil
}

// endLiveSQL 은 4.1 종료 전이다 — 스트림의 live 회차를 ending 으로 보낸다(계획 4.1 전이 · 사유는 문장의 글자다). 술어가 회차가
// 아니라 스트림인 것이 계약이다: stream_sessions_one_live_uq 가 스트림당 live 1행을 보증하고, 부르는 쪽(루프 — D10)이 판정과
// 이 문장 사이에 INSERT 를 끼우지 않으므로 판정한 그 회차를 닫는다. state='live' 술어는 이미 끝난 회차의 사유 · 시각을 덮지
// 않게 한다 — session 의 endSessionSQL · 이 패키지의 init 불일치 결속 CTE 와 같은 술어이고, 세 문장은
// session/end_session_pg_test.go 가 대조한다(계획 부기 21).
//
// 사유는 멈춘 시각 표식(4D marked)이 있으면 intentional, 없으면 offline 이다(POK-196 M6 3.4 「오프라인 전이와의 결속」 — 송출이
// 돌아오지 않은 의도적 종료). 표식 여부와 회차를 함께 돌려받아 같은 트랜잭션에서 회차 닫힘을 평가한다.
const endLiveSQL = liveEndingSet + `'offline' END
 WHERE stream_id = $1 AND state = 'live'` + liveEndingReturning

// endGapRunSQL 은 L3 종료다(POK-196 M6 3.1 · 6절 L3 행) — 소유 회차에서 L2 GAP 이 seq 로 이어서 셋 선 회차 $2 를, 그 회차가
// 아직 스트림 $1 의 live 일 때만 ending(사유 gap_run)으로 보낸다. 회차 술어를 더한 것은 루프의 셈과 이 문장 사이에 live 가
// 바뀌었을 수 있어서다(TD 분할 · init 불일치 분리) — 그때 새 live 를 끝내면 셈과 무관한 회차가 끊긴다. 표식 · 반환 열은
// endLiveSQL 과 같은 조각이다.
const endGapRunSQL = liveEndingSet + `'gap_run' END
 WHERE stream_id = $1 AND state = 'live' AND session_id = $2` + liveEndingReturning

// endStallSealSQL 은 L2′ 종료다(POK-196 M6-3b 커밋 ④b · kty 2026-10-27 결정 (나)) — 루프가 목록을 봉인한(Endlist) 회차
// $2 를, 그 회차가 아직 스트림 $1 의 live 일 때만 ending(사유 stall_seal)으로 보낸다. 회차 술어의 까닭은 endGapRunSQL 과
// 같다(봉인과 이 문장 사이에 live 가 바뀌었을 수 있다). 표식 · 반환 열은 endLiveSQL 과 같은 조각이다.
const endStallSealSQL = liveEndingSet + `'stall_seal' END
 WHERE stream_id = $1 AND state = 'live' AND session_id = $2` + liveEndingReturning

// liveEndingSet · liveEndingReturning 은 live 회차 종료 문장 셋(4.1 · L3 · L2′)이 함께 쓰는 SET 머리와 RETURNING 이다 — 표식이
// 있으면 사유 intentional 이고(3.4), 회차 · 표식 여부를 돌려받아 같은 트랜잭션에서 회차 닫힘을 평가한다. 사유 글자는 각
// 문장이 ELSE 자리에 잇는다.
const (
	liveEndingSet = `
UPDATE stream_sessions
   SET state = 'ending', ending_at = pg_catalog.now(),
       end_reason = CASE WHEN stop_marked_at IS NOT NULL THEN 'intentional' ELSE `
	liveEndingReturning = `
RETURNING session_id, COALESCE(round_id, ''), stop_marked_at IS NOT NULL`
)

// EndLive 는 streamID 의 live 회차를 ending 으로 보내고 그 회차 ID 를 돌려준다(계획 4.1 전이). live 회차가 없으면 "" 이고
// 오류가 아니다. 표식이 있었으면 사유 intentional 이고 같은 트랜잭션 끝에서 TryCloseRound(o · enqueue — 닫힘 조건 평가 + 완결
// 평가)를 부른다.
//
// 트랜잭션은 이 함수가 BEGIN 부터 끝까지 소유한다. 첫 문장이 트랜잭션 지역 상한(lock_timeout 5초 · statement_timeout
// 10초 — setTxnLimitsSQL)이고 **바로 다음 문장이 위상 잠금**이다(POK-196 M6 3.3 · D70 · D74) — 4D · 사슬 · 회차 닫힘과 같은
// 순서(위상 → 행)라 교착하지 않고, 위상 잠금 대기도 lock_timeout 에서 끊긴다. ctx 시한은 트랜잭션 하나에 TxnDeadline 하나다.
// 실패 갈래의 되돌림은 새 시한(같은 길이)을 받으므로, 루프가 부르는 전이 한 번의 루프 점유는 되돌림 포함 최대 약
// 2 × TxnDeadline 이다. 오류면 서버가 커밋했는지 모른다(부르는 쪽이 결과 모름으로 다룬다). 잠금 경합이면 WARN
// topology_lock_timeout op=end_live 를 남긴다.
func EndLive(ctx context.Context, pool *pgxpool.Pool, streamID string, o CloseOptions, enqueue EndedEnqueuer) (sessionID string, err error) {
	sessionID, err = endLive(ctx, pool, streamID, TxnDeadline, o, enqueue)
	if err != nil && isContention(err) {
		logger(o.Log).Warn("topology_lock_timeout", "op", "end_live", "stream_id", streamID, "err", err)
	}
	return sessionID, err
}

// EndGapRun 은 L3 다 — 회차 sessionID 가 아직 streamID 의 live 면 ending(사유 gap_run · 표식이 있으면 intentional)으로 보내고
// 그 ID 를 돌려준다. 그 회차가 live 가 아니면 "" 이고 오류가 아니다. 트랜잭션 · 시한 · 잠금 순서 · 표식 갈래의 회차 닫힘은
// EndLive 와 같다(같은 몸체). 잠금 경합이면 WARN topology_lock_timeout op=end_gap_run 을 남긴다.
func EndGapRun(ctx context.Context, pool *pgxpool.Pool, streamID, sessionID string, o CloseOptions, enqueue EndedEnqueuer) (string, error) {
	ended, err := endLiveWith(ctx, pool, streamID, TxnDeadline, o, enqueue, endGapRunSQL, streamID, sessionID)
	if err != nil && isContention(err) {
		logger(o.Log).Warn("topology_lock_timeout", "op", "end_gap_run", "stream_id", streamID, "session_id", sessionID, "err", err)
	}
	return ended, err
}

// EndStallSeal 은 L2′ 다 — 목록을 봉인한 회차 sessionID 가 아직 streamID 의 live 면 ending(사유 stall_seal · 표식이 있으면
// intentional)으로 보내고 그 ID 를 돌려준다. 그 회차가 live 가 아니면 "" 이고 오류가 아니다. 트랜잭션 · 시한 · 잠금 순서 ·
// 표식 갈래의 회차 닫힘은 EndLive 와 같다(같은 몸체). 잠금 경합이면 WARN topology_lock_timeout op=end_stall_seal 을 남긴다.
func EndStallSeal(ctx context.Context, pool *pgxpool.Pool, streamID, sessionID string, o CloseOptions, enqueue EndedEnqueuer) (string, error) {
	ended, err := endLiveWith(ctx, pool, streamID, TxnDeadline, o, enqueue, endStallSealSQL, streamID, sessionID)
	if err != nil && isContention(err) {
		logger(o.Log).Warn("topology_lock_timeout", "op", "end_stall_seal", "stream_id", streamID, "session_id", sessionID, "err", err)
	}
	return ended, err
}

// endLive 는 EndLive 의 몸체다 — 시한 deadline 을 받는 것은 같은 패키지의 시험이 문장 시한과 되돌림 시한을 함께
// 줄이기 위해서다(loadRewindLedger 와 같은 형).
func endLive(ctx context.Context, pool *pgxpool.Pool, streamID string, deadline time.Duration, o CloseOptions, enqueue EndedEnqueuer) (string, error) {
	return endLiveWith(ctx, pool, streamID, deadline, o, enqueue, endLiveSQL, streamID)
}

// endLiveWith 는 live 회차 종료 문장 sql(인자 args — endLiveSQL · endGapRunSQL · endStallSealSQL)을 EndLive 의 트랜잭션 규약으로 보낸다.
//
// 실패하면 되돌린다. 되돌림 ctx 는 되돌리는 순간에 부른 쪽 ctx 에서 취소를 떼고 같은 시한을 새로 씌워 만든다 — 끝난
// ctx 로 되돌리면 pgx 가 연결을 닫아 풀 연결이 버려지고, 미리 만들어 두면 시한을 다 쓴 갈래에서 그 ctx 도 끝나 있다.
// COMMIT 오류 뒤에는 되돌리지 않는다 — pgx 의 Commit 은 결과와 상관없이 트랜잭션을 닫는다(미뤄 둔 되돌림은 문장
// 없이 끝난다).
func endLiveWith(ctx context.Context, pool *pgxpool.Pool, streamID string, deadline time.Duration, o CloseOptions,
	enqueue EndedEnqueuer, sql string, args ...any) (string, error) {
	tctx, cancel := context.WithTimeout(ctx, deadline)
	defer cancel()
	tx, err := pool.Begin(tctx)
	if err != nil {
		return "", fmt.Errorf("종료 전이 트랜잭션 시작 실패 stream_id=%q: %w", streamID, err)
	}
	defer func() {
		rctx, rcancel := context.WithTimeout(context.WithoutCancel(ctx), deadline)
		defer rcancel()
		_ = tx.Rollback(rctx)
	}()
	if _, err := tx.Exec(tctx, setTxnLimitsSQL); err != nil {
		return "", fmt.Errorf("종료 전이 상한 설정 실패 stream_id=%q: %w", streamID, err)
	}
	if _, err := tx.Exec(tctx, topologyLockSQL, streamID); err != nil {
		return "", fmt.Errorf("종료 전이 위상 잠금 실패 stream_id=%q: %w", streamID, err)
	}
	var (
		sessionID, round string
		marked           bool
	)
	err = tx.QueryRow(tctx, sql, args...).Scan(&sessionID, &round, &marked)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil // live 회차가 없다 — 바꾼 것이 없으니 미뤄 둔 되돌림으로 끝낸다
	}
	if err != nil {
		return "", fmt.Errorf("종료 전이 실패 stream_id=%q: %w", streamID, err)
	}
	if marked && round != "" {
		if _, err := TryCloseRound(tctx, tx, round, o, enqueue); err != nil {
			return "", fmt.Errorf("종료 전이의 회차 닫힘 실패 stream_id=%q round_id=%q: %w", streamID, round, err)
		}
	}
	if err := tx.Commit(tctx); err != nil {
		return "", fmt.Errorf("종료 전이 확정 실패 stream_id=%q: %w", streamID, err)
	}
	return sessionID, nil
}
