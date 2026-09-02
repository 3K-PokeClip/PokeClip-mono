package index

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// UploadTarget 은 "올릴 대상 1건"이다. 실시간 경로(인덱서)와 재개 경로(스위퍼)가
// 같은 타입을 쓴다 — 두 경로에 서로 다른 어휘를 두면 크기 재확인 같은 공통 규칙이
// 한쪽에서만 지켜지는 사고가 난다.
type UploadTarget struct {
	StreamID string
	// Axis 는 이 대상이 어느 업로드 축인가다(설계 5.5.4 #4). 영값은 미판정이며
	// 업로더가 그 자리에서 거부한다 — 축을 빠뜨린 대상이 조용히 ② 로 접히면 안 된다.
	Axis Axis
	// Seq 는 ②·③ 축의 대상 식별자다. **init 축에서는 쓰지 않는다**(설계 5.5.2) —
	// 그 축의 유일성은 SessionID 가 준다. 키 검사의 seq 대조도 축으로 갈린다.
	Seq int64
	// SessionID 는 init 축의 대상 식별자다. ②·③ 축에서는 비어 있다.
	SessionID string
	S3Key     string // INSERT 때 예약된 키 그대로. 재계산하지 않는다(키 형상 = 계약)
	LocalPath string
	// Bytes 는 장부에 적힌 크기다. 꼬리 대조·CAS 기대값·failed 확정 판정·PUT 후 비교에 쓴다.
	// NULL 인 행은 애초에 조회되지 않는다(결정 14).
	Bytes int64
	// IsTail 은 조회 시점에 그 스트림의 마지막 seq 였는가다.
	IsTail bool
}

// SweepCursor 는 스위퍼 키셋 페이징의 위치이며, 뜻은 정확히 "여기까지 검사를 마쳤다"이다.
// 검사 완료의 기준은 접수 결과가 정한다 — 접수됐거나 거부된 행은 판정이 끝난 것이고,
// 큐가 차서 판정조차 못 한 행은 미검사다(결정 5⁵).
//
// 필드 순서가 곧 정렬 키 순서다. pendingUploadsSQL 의 ORDER BY 와 반드시 같아야 한다.
type SweepCursor struct {
	IsFailed  bool
	StartWall time.Time
	StreamID  string
	Seq       int64
}

// IsZero 는 "처음부터"를 뜻한다. 순환 판정과 1단계 결과 재사용이 이 값에 달렸다.
func (c SweepCursor) IsZero() bool {
	return !c.IsFailed && c.StartWall.IsZero() && c.StreamID == "" && c.Seq == 0
}

// UploadStore 는 업로드 결과를 장부에 반영하는 통로다.
//
// 계약: 모든 메서드는 ctx 취소 시 즉시 반환해야 한다 — 조회뿐 아니라 마킹도 그렇다.
// 이 계약이 깨지면 Shutdown 6단계의 무조건 join 이 무기한 대기가 된다(결정 17″).
// pgx 는 이 계약을 지킨다.
//
// 기존 Store 에 메서드를 얹지 않은 이유: Store 의 가짜 구현이 안 쓰는 메서드를 떠안게 되고
// Store 의 변경 이유가 둘이 된다(결정 2).
type UploadStore interface {
	MarkUploaded(ctx context.Context, streamID string, seq int64, expectBytes int64) (marked bool, err error)
	// MarkFailed 도 같은 CAS 를 쓴다. 호출자는 반드시 err 를 먼저 보고, 그 다음 marked 를 본다 —
	// (false, err) 를 CAS 거부로 오분류하면 DB 오류가 조용히 삼켜진다(CX-2 ⑥).
	MarkFailed(ctx context.Context, streamID string, seq int64, expectBytes int64) (marked bool, err error)
	// MarkInitUploaded 는 세션 init(MAP) 바이트의 성공 CAS 다(설계 5.5.5 셋째 문장).
	//
	// 세 조건이 전부 맞을 때만 1행이다: 그 세션이 있고, 아직 확정되지 않았고(init_uploaded_at
	// IS NULL), **올린 바이트의 해시가 장부에 적힌 해시와 같다**. 셋째 조건이 5.3ⓑ(init
	// 동일성 = 바이트 동등성)를 지키는 앵커이므로, sha256 은 반드시 **올린 쪽이 계산한 값**을
	// 넘겨야 한다 — 행에서 읽어 되넘기면 자기 비교라 보증이 통째로 사라진다.
	//
	// 실패 쪽 CAS 는 없다. 5.5.5 가 init 에 성공 문장 하나만 정의하며, 실패 상태 컬럼을
	// 새로 만들지 않는다(회수 형상은 M4).
	//
	// 호출자는 다른 마킹과 같은 규율을 따른다 — err 를 먼저 보고 그 다음 marked 를 본다.
	// **M3 의 호출자는 픽스처뿐이다**: 그 값을 만드는 유일한 생산자가 Producer.Init 인데
	// 그것이 M4 라, 워커 호출부 배선도 M4 다.
	MarkInitUploaded(ctx context.Context, sessionID string, sha256 []byte) (marked bool, err error)
	// PendingUploads 는 after 다음 페이지를 집어 온다. 정렬 첫 키가 가변이므로 at-least-once 다.
	// 둘째 반환값은 이 페이지의 마지막 행을 가리키는 커서이며, 행이 0건이면 after 를 그대로
	// 돌려준다 — 되감김을 만들지 않기 위해서다.
	PendingUploads(ctx context.Context, tailGraceSecs float64, limit int, after SweepCursor) ([]UploadTarget, SweepCursor, error)
	// CountBacklog 의 세 값 관계: pending 과 failed 는 서로 배타이고,
	// bytesNull 은 그 둘의 부분집합이다(= 처리 불가로 조회에서 제외된 개수). 중복 계수다.
	CountBacklog(ctx context.Context) (pending, failed, bytesNull int64, err error)
}

// markUploadedSQL ($3 = 기대 bytes)
//
// bytes 를 WHERE 에서 읽기만 한다. SET 목록에 bytes 가 없다 —
// 결정 9‴(bytes 의 쓰기 소유자는 UpdateTail 하나)와 모순되지 않는 이유가 이것이다.
// upload_state <> 'uploaded' 가드는 확정된 행을 되돌리지 못하게 한다.
const markUploadedSQL = `
UPDATE stream_segments SET upload_state = 'uploaded', uploaded_at = now()
 WHERE stream_id = $1 AND seq = $2 AND upload_state <> 'uploaded' AND bytes = $3`

// markFailedSQL — 같은 CAS 다. uploaded_at 은 건드리지 않는다.
const markFailedSQL = `
UPDATE stream_segments SET upload_state = 'failed'
 WHERE stream_id = $1 AND seq = $2 AND upload_state <> 'uploaded' AND bytes = $3`

// markInitUploadedSQL ($2 = 올린 바이트의 sha256) — 설계 5.5.5 셋째 문장 그대로다.
//
// 앵커가 bytes 가 아니라 sha256 인 것이 ②·③ 와의 차이다: init 은 "같은 MAP 인가"가
// 세션 전체의 재생 가능성을 가르므로 크기가 아니라 내용으로 대조한다(5.3ⓑ).
// init_uploaded_at IS NULL 가드는 확정 시각을 개시 1회로 굳힌다.
const markInitUploadedSQL = `
UPDATE stream_sessions SET init_uploaded_at = now()
 WHERE session_id = $1 AND init_uploaded_at IS NULL AND init_sha256 = $2`

// pendingUploadsSQL ($1 = tailGrace 초, $2 = limit, $3..$6 = 키셋 커서)
//
// 꼬리 예외 OR 조건이 핵심이다 — 후속 행이 없는 마지막 조각은 아직 자라는 중일 수 있어
// tailGrace 를 넘긴 뒤에야 집는다. 무조건 제외하면 재기동으로 보류가 증발한 꼬리가
// 영구 pending 으로 남는다(결정 5⁵).
//
// ORDER BY 의 첫 항이 클래스 간 기아를 막고(pending 우선), 뒤 세 항은 키셋 페이징의
// 전순서 요건이다. WHERE 의 행 비교는 이 ORDER BY 와 반드시 같은 순서여야 한다.
//
// 정렬 키 두 개(start_wall_utc·클래스)를 결과에 함께 실어 온다. 커서를 나중에 되읽으면
// 그 사이에 행의 상태가 전이했을 때 커서가 엉뚱한 클래스로 건너뛰어 남은 pending 을
// 통째로 건너뛴다. 같은 스냅샷에서 읽는 것이 유일하게 안전한 방법이다.
const pendingUploadsSQL = `
SELECT t.stream_id, t.seq, t.s3_key, t.local_path, t.bytes,
       NOT EXISTS (SELECT 1 FROM stream_segments s
                    WHERE s.stream_id = t.stream_id AND s.seq > t.seq) AS is_tail,
       (t.upload_state <> 'pending') AS is_failed, t.start_wall_utc
  FROM stream_segments t
 WHERE t.upload_state IN ('pending','failed')
   AND t.local_path IS NOT NULL
   AND t.bytes IS NOT NULL
   AND ( EXISTS (SELECT 1 FROM stream_segments s
                  WHERE s.stream_id = t.stream_id AND s.seq > t.seq)
         OR t.start_wall_utc < now() - make_interval(secs => $1::double precision) )
   AND ( (t.upload_state <> 'pending'), t.start_wall_utc, t.stream_id, t.seq )
       > ( $3::boolean, $4::timestamptz, $5::text, $6::bigint )
 ORDER BY (t.upload_state <> 'pending'), t.start_wall_utc, t.stream_id, t.seq
 LIMIT $2`

// countBacklogSQL
//
// bytesNull 필터를 IN ('pending','failed') 로 못 박는다. upload_state 에 CHECK 제약이 없어
// 예상 밖 값이 들어오면 "<> 'uploaded'" 만으로는 부분집합 관계가 깨진다.
const countBacklogSQL = `
SELECT count(*) FILTER (WHERE upload_state = 'pending'),
       count(*) FILTER (WHERE upload_state = 'failed'),
       count(*) FILTER (WHERE upload_state IN ('pending','failed') AND bytes IS NULL)
  FROM stream_segments WHERE local_path IS NOT NULL`

type pgUploadStore struct {
	pool *pgxpool.Pool
}

// NewUploadStore 는 PostgreSQL 구현을 돌려준다.
func NewUploadStore(pool *pgxpool.Pool) UploadStore {
	return &pgUploadStore{pool: pool}
}

func (s *pgUploadStore) MarkUploaded(ctx context.Context, streamID string, seq int64, expectBytes int64) (bool, error) {
	return s.mark(ctx, markUploadedSQL, "uploaded", streamID, seq, expectBytes)
}

func (s *pgUploadStore) MarkFailed(ctx context.Context, streamID string, seq int64, expectBytes int64) (bool, error) {
	return s.mark(ctx, markFailedSQL, "failed", streamID, seq, expectBytes)
}

// MarkInitUploaded 는 세션 표를 겨눈다 — 공통 몸통(mark)을 쓰지 않는 이유가 그것이다.
// 표도 다르고 식별자도 (stream_id, seq) 가 아니라 session_id 하나다.
func (s *pgUploadStore) MarkInitUploaded(ctx context.Context, sessionID string, sha256 []byte) (bool, error) {
	tag, err := s.pool.Exec(ctx, markInitUploadedSQL, sessionID, sha256)
	if err != nil {
		return false, fmt.Errorf("init 업로드 확정 실패 session_id=%q: %w", sessionID, err)
	}
	return tag.RowsAffected() == 1, nil
}

// mark 는 두 CAS 의 공통 몸통이다. 오류일 때 marked 를 false 로 확정해 돌려주지만,
// 그 false 는 "거부"가 아니라 "판정 못 함"이다 — 호출자가 err 를 먼저 보는 것이 계약이다.
func (s *pgUploadStore) mark(ctx context.Context, query, target, streamID string, seq, expectBytes int64) (bool, error) {
	tag, err := s.pool.Exec(ctx, query, streamID, seq, expectBytes)
	if err != nil {
		return false, fmt.Errorf("업로드 상태 확정 실패 target=%s stream_id=%q seq=%d: %w",
			target, streamID, seq, err)
	}
	return tag.RowsAffected() == 1, nil
}

func (s *pgUploadStore) PendingUploads(ctx context.Context, tailGraceSecs float64, limit int, after SweepCursor) ([]UploadTarget, SweepCursor, error) {
	rows, err := s.pool.Query(ctx, pendingUploadsSQL,
		tailGraceSecs, limit, after.IsFailed, after.StartWall.UTC(), after.StreamID, after.Seq)
	if err != nil {
		return nil, after, fmt.Errorf("업로드 대상 조회 실패: %w", err)
	}
	defer rows.Close()

	// 커서는 마지막으로 읽은 행을 가리킨다. 행이 없으면 after 그대로 — 되감기지 않는다.
	next := after
	targets := make([]UploadTarget, 0, limit)
	for rows.Next() {
		var (
			t   UploadTarget
			key SweepCursor
		)
		if err := rows.Scan(&t.StreamID, &t.Seq, &t.S3Key, &t.LocalPath, &t.Bytes, &t.IsTail,
			&key.IsFailed, &key.StartWall); err != nil {
			return nil, after, fmt.Errorf("업로드 대상 스캔 실패: %w", err)
		}
		// 이 조회는 ② 의 열(upload_state)만 보므로 산출도 정의상 아카이브 축이다.
		// 축을 비워 내보내면 업로더가 미판정으로 보고 전부 거부한다(fail-closed).
		// 축별 조회는 M4 이며, 그때 이 자리가 조회마다 갈린다.
		t.Axis = AxisArchive

		key.StreamID, key.Seq = t.StreamID, t.Seq
		// 저장은 UTC 강제지만 드라이버가 로컬 위치로 실어 올 수 있어 여기서 못 박는다(D6).
		key.StartWall = key.StartWall.UTC()

		targets = append(targets, t)
		next = key
	}
	if err := rows.Err(); err != nil {
		return nil, after, fmt.Errorf("업로드 대상 조회 중 오류: %w", err)
	}
	return targets, next, nil
}

func (s *pgUploadStore) CountBacklog(ctx context.Context) (int64, int64, int64, error) {
	var pending, failed, bytesNull int64
	err := s.pool.QueryRow(ctx, countBacklogSQL).Scan(&pending, &failed, &bytesNull)
	if err != nil {
		return 0, 0, 0, fmt.Errorf("업로드 잔량 집계 실패: %w", err)
	}
	return pending, failed, bytesNull, nil
}
