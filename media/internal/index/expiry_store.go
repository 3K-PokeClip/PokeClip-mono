package index

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// 60일 만료(POK-196 M6-4)의 장부 쪽 — clip 만료 창구가 준 한 방송의 구간을 장부에서 찾고 지운다.
//
// 한 방송의 처리 순서는 만료 루프(internal/expiry)가 정한다: 0 조회 제외 영속(MarkExpired) → 1 무효 컷오프
// 검사(ExpiryCutoffValid · e1) → 2 대상 행(ExpiryRows) → 3 세션 단위 문서(ExpiryDocuments) → 4 조각 객체 →
// 5 행(DeleteExpired). 이 파일은 장부 문장만 갖고 S3 와 HTTP 는 모른다. 키는 장부 저장값 그대로 돌려준다 —
// 응답 값으로 키를 만들지 않는다(D28).

// ExpiryDeleteBatch 는 DeleteExpired 한 트랜잭션이 받는 seq 수의 상한이다(설계 7절 단계 5 — 500행 단위).
const ExpiryDeleteBatch = 500

// ExpirySessionlessAge 는 세션 NULL 행을 local_path 가 남아 있어도 대상으로 보는 나이다. 업로더 ② 선택자는
// local_path 가 필요한데, 이 나이면 MediaMTX 보존(1일)이 파일을 지운 뒤다(설계 7절 단계 2).
const ExpirySessionlessAge = 7 * 24 * time.Hour

// ExpiryRetention 은 보존 기간이다(계약-세그먼트인덱스 5-2 60일). ExpiryUntilSlack 은 clip 의 만료 판정과 이 쪽 시계의
// 차이를 받는 여유다(설계 7절 「상한 now−59일(여유 1일)」 — 엄격한 60일 최소 보존이 아니다).
const (
	ExpiryRetention  = 60 * 24 * time.Hour
	ExpiryUntilSlack = 24 * time.Hour
)

// ExpiryCeiling 은 now 기준으로 지울 수 있는 가장 늦은 상한이다(now − 보존 기간 + 여유). 만료 루프는 이보다 늦은
// segmentsUntil 을 받지 않고(fail-closed), 0단계 문장도 같은 상한을 다시 건다(보안 H1 — clip 응답 한 줄이 보존
// 기간 안 조각을 비가역으로 지우지 않게).
func ExpiryCeiling(now time.Time) time.Time { return now.Add(-ExpiryRetention + ExpiryUntilSlack) }

// ErrCutoffInvalid 는 그 스트림의 컷오프가 장부에 없는 행을 가리킨다는 뜻이다(e1 — fail-closed). 그 스트림은
// 삭제를 멈추고 다른 스트림은 계속한다(e3).
var ErrCutoffInvalid = errors.New("index: 무효 컷오프 — 이 스트림의 만료 삭제를 멈춘다")

// ExpiryQuery 는 clip 만료 창구가 준 한 방송의 장부 범위다(계약-세그먼트인덱스 6-2).
type ExpiryQuery struct {
	// StreamID 는 ingestStreamId 다. 키 성분 규칙 검사는 부른 쪽(만료 루프)이 한다 — 여기서는 WHERE 인자다.
	StreamID string
	// From 은 segmentsFrom 이다. nil 이면 하한이 없다(그 물리 키에서 이 방송보다 앞선 조각 전부 — 6-2).
	From *time.Time
	// Until 은 segmentsUntil(배타)이다. 영값이면 거부한다. From 이 Until 이상이면 빈 구간이다(안 지움 — 6-2).
	Until time.Time
}

func (q ExpiryQuery) validate() error {
	if q.StreamID == "" {
		return errors.New("만료 범위의 스트림이 비었다")
	}
	if q.Until.IsZero() {
		return fmt.Errorf("만료 범위 stream_id=%q 의 상한이 비었다", q.StreamID)
	}
	// From ≥ Until 은 오류가 아니다 — 계약 6-2 의 빈 구간(겹친 방송 · from = until)이고 범위 조건이 아무 행도
	// 고르지 않는다.
	return nil
}

// args 는 범위 인자 셋($1 스트림 · $2 하한 또는 NULL · $3 상한)이다.
func (q ExpiryQuery) args() []any {
	var from any
	if q.From != nil {
		from = q.From.UTC()
	}
	return []any{q.StreamID, from, q.Until.UTC()}
}

// expiryRangeFilter 는 조각 t 가 범위 안인가다($1 · $2 · $3 — ExpiryQuery.args). stream_segments_wall_idx 를 탄다.
const expiryRangeFilter = `t.stream_id = $1 AND t.start_wall_utc < $3::timestamptz
   AND ($2::timestamptz IS NULL OR t.start_wall_utc >= $2::timestamptz)`

// markExpiredSQL 은 0단계다 — 범위에 닿은 ended 세션에 expired_at 을 쓴다(이미 있으면 그대로). 이후 상태 읽기는 그
// 회차를 Unknown 으로 본다(계약 5-2 2단계 조회 제외). $4 는 보존 기간 − 여유(초)다 — 상한을 LEAST($3, now() − $4)
// 로 다시 건다(ExpiryCeiling 과 같은 값 · 만료 루프의 거부와 이중 방어 — 보안 H1). ended 가 아닌 세션은 발행
// 중이라 표시하지 않는다.
const markExpiredSQL = `
UPDATE stream_sessions s SET expired_at = pg_catalog.now()
 WHERE s.stream_id = $1 AND s.expired_at IS NULL AND s.state = 'ended'
   AND s.session_id IN (SELECT t.session_id FROM stream_segments t
                         WHERE ` + expiryRangeFilter + ` AND t.session_id IS NOT NULL
                           AND t.start_wall_utc < pg_catalog.now() - pg_catalog.make_interval(secs => $4::double precision))`

// expiryMinAge 는 markExpiredSQL $4 다 — 지울 수 있는 조각의 최소 나이(ExpiryCeiling 과 같은 원천).
const expiryMinAge = ExpiryRetention - ExpiryUntilSlack

// MarkExpired 는 범위에 닿은 세션의 expired_at 을 한 트랜잭션(한 문장)으로 쓰고 새로 쓴 세션 수를 돌려준다.
// 부른 쪽은 커밋 뒤 PlaybackChanged(스트림)를 부른다 — 뒤 단계가 실패해도 조회 제외는 남는다(D16).
func MarkExpired(ctx context.Context, pool *pgxpool.Pool, q ExpiryQuery) (int64, error) {
	if err := q.validate(); err != nil {
		return 0, err
	}
	tag, err := pool.Exec(ctx, markExpiredSQL, append(q.args(), expiryMinAge.Seconds())...)
	if err != nil {
		return 0, fmt.Errorf("만료 조회 제외 표시 실패 stream_id=%q: %w", q.StreamID, err)
	}
	return tag.RowsAffected(), nil
}

// expiryCutoffValidSQL 은 그 스트림에 무효 컷오프가 없는가다 — 컷오프가 없거나 가리키는 행이 있으면 참. janitor 의
// 삭제 정지와 같은 조각(cutoffRowExists)을 쓴다(D09 — 원본이 하나).
const expiryCutoffValidSQL = `
SELECT NOT EXISTS (SELECT 1 FROM stream_cutoffs cut
                    WHERE cut.stream_id = $1 AND NOT ` + cutoffRowExists + `)`

// ExpiryCutoffValid 는 1단계(e1) 검사다. 거짓이면 그 스트림은 객체를 지우지 않는다.
func ExpiryCutoffValid(ctx context.Context, pool *pgxpool.Pool, streamID string) (bool, error) {
	var ok bool
	if err := pool.QueryRow(ctx, expiryCutoffValidSQL, streamID).Scan(&ok); err != nil {
		return false, fmt.Errorf("만료 컷오프 유효성 조회 실패 stream_id=%q: %w", streamID, err)
	}
	return ok, nil
}

// 대상 행에서 건너뛰는 사유다. 빈 값이면 대상이다.
const (
	// ExpirySkipSessionOpen — 세션이 ended 가 아니다(업로더 ③ 선택자 `state <> 'ended'` 와 서로 배타 — D17).
	// 세션 행이 장부에 없는 귀속도 여기다.
	ExpirySkipSessionOpen = "session_open"
	// ExpirySkipSessionlessLocal — 세션 NULL 행인데 local_path 가 남았고 ExpirySessionlessAge 가 안 됐다.
	ExpirySkipSessionlessLocal = "sessionless_local"
)

// expiryRowsSQL ($1 · $2 · $3 범위 · $4 세션 NULL 행 나이 초) — 범위 안 행 전부와 건너뛸 사유.
//
// 사유 판정은 DeleteExpired 의 삭제 조건(expiryDeleteSQL)과 같은 뜻이다 — 여기서 대상이 아닌 행은 거기서도 지우지
// 않는다. 두 곳에 두는 까닭: 이쪽은 객체 키를 고르고(4단계) 저쪽은 행을 지운다(5단계). 행 단계가 늦게 열린
// 세션을 다시 확인해야 객체 단계 뒤에 상태가 바뀐 행을 지우지 않는다.
const expiryRowsSQL = `
SELECT t.seq, t.s3_key, t.playback_s3_key, t.session_id,
       CASE WHEN t.session_id IS NOT NULL THEN
                 CASE WHEN EXISTS (SELECT 1 FROM stream_sessions s
                                    WHERE s.session_id = t.session_id AND s.state = 'ended')
                      THEN '' ELSE '` + ExpirySkipSessionOpen + `' END
            WHEN t.local_path IS NULL
              OR t.start_wall_utc < pg_catalog.now() - pg_catalog.make_interval(secs => $4::double precision) THEN ''
            ELSE '` + ExpirySkipSessionlessLocal + `' END
  FROM stream_segments t
 WHERE ` + expiryRangeFilter + `
 ORDER BY t.seq`

// ExpiryRow 는 범위 안 조각 한 행이다.
type ExpiryRow struct {
	Seq int64
	// S3Key · PlaybackS3Key 는 장부 저장값 그대로다(③ 키가 없으면 빈 값).
	S3Key         string
	PlaybackS3Key string
	// SessionID 는 귀속 세션이다(세션 NULL 행이면 빈 값).
	SessionID string
	// Skip 이 비지 않으면 대상이 아니다 — 객체도 행도 지우지 않는다.
	Skip string
}

// ExpiryRows 는 2단계다 — 범위 안 행을 seq 순으로 돌려준다(건너뛸 행은 Skip 사유와 함께).
func ExpiryRows(ctx context.Context, pool *pgxpool.Pool, q ExpiryQuery) ([]ExpiryRow, error) {
	if err := q.validate(); err != nil {
		return nil, err
	}
	rows, err := pool.Query(ctx, expiryRowsSQL, append(q.args(), ExpirySessionlessAge.Seconds())...)
	if err != nil {
		return nil, fmt.Errorf("만료 대상 행 조회 실패 stream_id=%q: %w", q.StreamID, err)
	}
	out, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (ExpiryRow, error) {
		var r ExpiryRow
		var pbKey, session *string
		if err := row.Scan(&r.Seq, &r.S3Key, &pbKey, &session, &r.Skip); err != nil {
			return r, err
		}
		if pbKey != nil {
			r.PlaybackS3Key = *pbKey
		}
		if session != nil {
			r.SessionID = *session
		}
		return r, nil
	})
	if err != nil {
		return nil, fmt.Errorf("만료 대상 행 스캔 실패 stream_id=%q: %w", q.StreamID, err)
	}
	return out, nil
}

// ExpiryDocKind 는 세션 단위 문서의 종류다. 값 순서가 지우는 순서다(dvr → vod → init — 설계 7절 단계 3).
type ExpiryDocKind int

const (
	ExpiryDocDVR  ExpiryDocKind = iota + 1 // 범위에 닿은 ended 세션의 dvr 문서
	ExpiryDocVOD                           // 그 세션들 사슬(앞 · 뒤 계승)의 vod 문서
	ExpiryDocInit                          // 조각 전부가 범위 안인 ended 세션의 init(K3)
)

// ExpiryDocument 는 지울 문서 하나다. Key 는 장부 저장값 그대로다.
type ExpiryDocument struct {
	Kind      ExpiryDocKind
	SessionID string
	Key       string
}

// expiryDocumentsSQL ($1 · $2 · $3 범위) — 문서를 종류 · 세션 순으로 고른다.
//
// 사슬은 범위에 닿은 세션에서 계승 간선을 앞(자식 → 부모) · 뒤(부모 → 자식)로 따라간다. 간선은 chainEdgeSQL 한
// 벌이다(사슬 닫힘 · 후속 판정과 같은 규칙) — 늦은 회차 분리가 만든 회차를 가로지르는 간선은 잇지 않는다. 재귀 항은
// 간선 표(edge)를 한 번 읽고 어느 끝이 사슬 안이든 반대 끝을 더한다. UNION 이 중복을 지우므로 끝난다. 문서는 ended
// 세션 것만 고른다 — 열린 세션의 문서는 발행 중이다.
const expiryDocumentsSQL = `
WITH RECURSIVE touched AS (
    SELECT DISTINCT t.session_id FROM stream_segments t
     WHERE ` + expiryRangeFilter + ` AND t.session_id IS NOT NULL
), edge(child, parent) AS (
    SELECT c.session_id, p.session_id
      FROM stream_sessions c JOIN stream_sessions p ON ` + chainEdgeSQL + `
     WHERE c.stream_id = $1
), chain(session_id) AS (
    SELECT session_id FROM touched
    UNION
    SELECT CASE WHEN e.child = k.session_id THEN e.parent ELSE e.child END
      FROM chain k JOIN edge e ON k.session_id IN (e.child, e.parent)
)
SELECT d.kind, d.session_id, d.key FROM (
    SELECT 1 AS kind, s.session_id, s.dvr_s3_key AS key
      FROM stream_sessions s JOIN touched USING (session_id)
     WHERE s.state = 'ended' AND s.dvr_s3_key IS NOT NULL
    UNION ALL
    SELECT 2, s.session_id, s.vod_s3_key
      FROM stream_sessions s JOIN chain USING (session_id)
     WHERE s.state = 'ended' AND s.vod_s3_key IS NOT NULL
    UNION ALL
    SELECT 3, s.session_id, s.init_s3_key
      FROM stream_sessions s JOIN touched USING (session_id)
     WHERE s.state = 'ended' AND s.init_s3_key IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM stream_segments o
                        WHERE o.stream_id = s.stream_id AND o.session_id = s.session_id
                          AND (o.start_wall_utc >= $3::timestamptz
                               OR ($2::timestamptz IS NOT NULL AND o.start_wall_utc < $2::timestamptz)))
) d
 ORDER BY d.kind, d.session_id`

// ExpiryDocuments 는 3단계의 문서 목록이다(지우는 순서대로).
func ExpiryDocuments(ctx context.Context, pool *pgxpool.Pool, q ExpiryQuery) ([]ExpiryDocument, error) {
	if err := q.validate(); err != nil {
		return nil, err
	}
	rows, err := pool.Query(ctx, expiryDocumentsSQL, q.args()...)
	if err != nil {
		return nil, fmt.Errorf("만료 문서 조회 실패 stream_id=%q: %w", q.StreamID, err)
	}
	out, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (ExpiryDocument, error) {
		var d ExpiryDocument
		err := row.Scan(&d.Kind, &d.SessionID, &d.Key)
		return d, err
	})
	if err != nil {
		return nil, fmt.Errorf("만료 문서 스캔 실패 stream_id=%q: %w", q.StreamID, err)
	}
	return out, nil
}

// expiryPrivileges 는 만료가 쓰는 표 권한이다(설계 7절 활성화 선행 조건 · cc L8ⓑ) — 읽기 권한은 핵심 문장이 이미
// 요구하므로 여기 없다. 사이드카 권한 목록(pgtest.MediaIndexerGrantSQL)의 부분 집합임을 시험이 단언한다.
// 원천이 둘이다 — 만료 권한을 바꾸면 pgtest/role.go 와 같이 고친다. 시험은 부분 집합과 개수만 보므로 만료 문장이
// 새로 쓰는 권한을 여기서 빠뜨린 것은 잡지 못한다.
var expiryPrivileges = [...]struct{ table, privilege string }{
	{"broadcast_lifecycle_outbox", "DELETE"},
	{"stream_cutoffs", "DELETE"},
	{"stream_cutoffs", "UPDATE"},
	{"stream_published_gaps", "DELETE"},
	{"stream_segments", "DELETE"},
	{"stream_sessions", "DELETE"},
}

// ExpiryPrivileges 는 만료 기동 검사가 보는 권한을 「표:권한」 꼴로 돌려준다(이름 순).
func ExpiryPrivileges() []string {
	out := make([]string, 0, len(expiryPrivileges))
	for _, p := range expiryPrivileges {
		out = append(out, p.table+":"+p.privilege)
	}
	return out
}

// missingPrivilegesSQL ($1 표 배열 · $2 권한 배열 — 같은 길이) 는 접속 계정에 없는 권한을 고른다.
const missingPrivilegesSQL = `
SELECT p.tbl, p.priv FROM ROWS FROM (pg_catalog.unnest($1::text[]), pg_catalog.unnest($2::text[])) AS p(tbl, priv)
 WHERE NOT pg_catalog.has_table_privilege(p.tbl, p.priv)
 ORDER BY p.tbl, p.priv`

// MissingExpiryPrivileges 는 기동 권한 검사다 — 접속 계정에 없는 만료 권한을 「표:권한」 꼴로 돌려준다(이름 순 · 다
// 있으면 빈 목록). 부르는 쪽은 하나라도 있으면 만료를 켜지 않는다(ERROR expiry_disabled reason=privilege — D50).
// 운영 권한(30_grants.sql)이 아직 없는 DB 에서 DELETE 가 42501 로 반쯤 지운 상태를 남기지 않게 하는 fail-closed 다.
func MissingExpiryPrivileges(ctx context.Context, pool *pgxpool.Pool) ([]string, error) {
	tables := make([]string, 0, len(expiryPrivileges))
	privs := make([]string, 0, len(expiryPrivileges))
	for _, p := range expiryPrivileges {
		tables, privs = append(tables, p.table), append(privs, p.privilege)
	}
	rows, err := pool.Query(ctx, missingPrivilegesSQL, tables, privs)
	if err != nil {
		return nil, fmt.Errorf("만료 권한 확인 조회 실패: %w", err)
	}
	missing, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (string, error) {
		var table, privilege string
		err := row.Scan(&table, &privilege)
		return table + ":" + privilege, err
	})
	if err != nil {
		return nil, fmt.Errorf("만료 권한 확인 스캔 실패: %w", err)
	}
	return missing, nil
}

// CutoffChange 는 DeleteExpired 가 컷오프 행에 한 일이다.
type CutoffChange int

const (
	CutoffUnchanged CutoffChange = iota // 컷오프가 없거나 그 행을 지우지 않았다
	CutoffAdvanced                      // 뒤의 첫 자격 행으로 옮겼다(CutoffSeq)
	CutoffRemoved                       // 뒤에 자격 행이 없어 지웠다
)

// DeleteResult 는 DeleteExpired 한 트랜잭션의 결과다.
type DeleteResult struct {
	RowsDeleted int64
	GapsDeleted int64
	Cutoff      CutoffChange
	CutoffSeq   int64 // Cutoff 가 CutoffAdvanced 일 때 새 컷오프
	// SessionsDeleted 는 조각 0 이 되어 지운 세션이다(이름 순).
	SessionsDeleted []string
	OutboxDeleted   int64
}

// expiryLockCutoffSQL 은 컷오프 행을 잠그고 유효성을 함께 읽는다. 행이 없으면 0행이다. FOR UPDATE 는 cut 하나만
// 잠근다(EXISTS 안의 조각 행은 잠그지 않는다).
const expiryLockCutoffSQL = `
SELECT cut.cutoff_seq, ` + cutoffRowExists + `
  FROM stream_cutoffs cut WHERE cut.stream_id = $1 FOR UPDATE OF cut`

// expiryDeleteSQL ($1 스트림 · $2 seq 배열 · $3 세션 NULL 행 나이 초) — 넘겨받은 seq 가운데 대상 조건을 다시 채운
// 행만 지운다(expiryRowsSQL 의 사유가 빈 행과 같은 뜻). 하한은 AFTER DELETE 문장 트리거가 같은 트랜잭션에서
// 올린다(D13). janitor 가 잠근 행이면 lock_timeout(5초)에 55P03 이다(D18).
const expiryDeleteSQL = `
DELETE FROM stream_segments t
 WHERE t.stream_id = $1 AND t.seq = ANY ($2::bigint[])
   AND ( EXISTS (SELECT 1 FROM stream_sessions s WHERE s.session_id = t.session_id AND s.state = 'ended')
         OR ( t.session_id IS NULL
              AND ( t.local_path IS NULL
                    OR t.start_wall_utc < pg_catalog.now() - pg_catalog.make_interval(secs => $3::double precision) ) ) )
RETURNING t.seq, t.session_id`

// expiryEmptiedSessionsSQL 은 지운 행의 세션 가운데 조각이 하나도 남지 않은 것을 잠그고 고른다.
const expiryEmptiedSessionsSQL = `
SELECT s.session_id FROM stream_sessions s
 WHERE s.session_id = ANY ($2::text[]) AND s.stream_id = $1
   AND NOT EXISTS (SELECT 1 FROM stream_segments g WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id)
 ORDER BY s.session_id
   FOR UPDATE`

// expiryDeleteGapsSQL 은 지운 seq 의 GAP 행과 빈 세션의 GAP 행(조각 행이 없는 seq 포함)을 지운다 — 세션 행보다
// 먼저다(FK).
const expiryDeleteGapsSQL = `
DELETE FROM stream_published_gaps g
 WHERE g.stream_id = $1 AND (g.seq = ANY ($2::bigint[]) OR g.session_id = ANY ($3::text[]))`

// 컷오프 전진 · 제거(설계 7절 단계 5 M4). 값은 장부에서만 온다 — 대상은 6-3 ⓒ 자격 행(세션 · PDT · ③ 키)이다.
// 제거 문장은 자격 생존 행이 없음을 스스로 다시 확인한다. 그 스냅숏 뒤에 커밋된 새 방송 첫 조각은 컷오프 없이
// 남는다 — 다음 조각이 주조하고 그 사이는 면제다(D58 한계).
const (
	expiryAdvanceCutoffSQL = `
UPDATE stream_cutoffs c SET cutoff_seq = n.seq
  FROM (SELECT pg_catalog.min(s.seq) AS seq FROM stream_segments s
         WHERE s.stream_id = $1 AND s.seq > $2
           AND s.session_id IS NOT NULL AND s.playback_pdt IS NOT NULL AND s.playback_s3_key IS NOT NULL) n
 WHERE c.stream_id = $1 AND c.cutoff_seq = $2 AND n.seq IS NOT NULL
RETURNING c.cutoff_seq`
	expiryRemoveCutoffSQL = `
DELETE FROM stream_cutoffs c
 WHERE c.stream_id = $1 AND c.cutoff_seq = $2
   AND NOT EXISTS (SELECT 1 FROM stream_segments s
                    WHERE s.stream_id = $1 AND s.seq > $2
                      AND s.session_id IS NOT NULL AND s.playback_pdt IS NOT NULL AND s.playback_s3_key IS NOT NULL)`
)

// 빈 세션 정리 — 후속 계승을 끊고(FK) 세션을 지운 뒤, 세션이 하나도 남지 않은 회차의 outbox 행을 지운다(D51).
// outbox 의 남은 세션 확인은 스트림 조건으로 stream_sessions_stream_idx 를 탄다 — 닫힌 회차에는 round_id 부분
// 인덱스를 쓸 수 없다(db m68 M3). 회차는 한 스트림 안에만 있다(insertEndedSQL 의 members 조인과 같은 전제).
const (
	expiryUnlinkSuccessorsSQL = `UPDATE stream_sessions SET inherits_session = NULL WHERE inherits_session = ANY ($1::text[])`
	expiryDeleteSessionsSQL   = `DELETE FROM stream_sessions WHERE session_id = ANY ($1::text[]) RETURNING round_id`
	expiryDeleteOutboxSQL     = `
DELETE FROM broadcast_lifecycle_outbox o
 WHERE o.round_id = ANY ($1::text[])
   AND NOT EXISTS (SELECT 1 FROM stream_sessions s WHERE s.stream_id = o.ingest_stream_id AND s.round_id = o.round_id)`
)

// DeleteExpired 는 5단계다 — 한 트랜잭션(lock_timeout 5초)에서 다음을 한다.
//
//	주조 직렬화 잠금(advisory) → 컷오프 행 FOR UPDATE(무효면 ErrCutoffInvalid · 아무것도 안 지움)
//	→ 조각 행 삭제(하한은 트리거) → GAP 행 삭제 → 컷오프 전진(없으면 제거)
//	→ 빈 세션: 후속 계승 끊기 · 세션 삭제 · 고아 outbox 삭제
//
// seqs 는 ExpiryDeleteBatch 개까지다. 비면 문장을 보내지 않는다. 대상 조건을 다시 확인하므로 열린 세션 행은
// 넘겨받아도 지우지 않는다. 소속 잠금은 잡지 않는다(계승 간선을 끊는 문장 — 설계 3.3).
func DeleteExpired(ctx context.Context, pool *pgxpool.Pool, streamID string, seqs []int64) (DeleteResult, error) {
	if streamID == "" {
		return DeleteResult{}, errors.New("만료 삭제의 스트림이 비었다")
	}
	if len(seqs) > ExpiryDeleteBatch {
		return DeleteResult{}, fmt.Errorf("만료 삭제 stream_id=%q 묶음 %d행 — 상한 %d", streamID, len(seqs), ExpiryDeleteBatch)
	}
	if len(seqs) == 0 {
		return DeleteResult{}, nil
	}
	tx, err := pool.Begin(ctx)
	if err != nil {
		return DeleteResult{}, fmt.Errorf("만료 삭제 트랜잭션 시작 실패 stream_id=%q: %w", streamID, err)
	}
	// 되돌림 ctx 는 되돌리는 순간에 만든다 — 부른 쪽 취소와 떼고 TxnDeadline 을 씌운다(insertOnce 와 같다).
	// 커밋 뒤의 Rollback 은 문장을 보내지 않는 no-op 이다.
	defer func() {
		rctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), TxnDeadline)
		defer cancel()
		_ = tx.Rollback(rctx)
	}()

	res, err := deleteExpiredTx(ctx, tx, streamID, seqs)
	if err != nil {
		return DeleteResult{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return DeleteResult{}, fmt.Errorf("만료 삭제 커밋 실패 stream_id=%q: %w", streamID, err)
	}
	return res, nil
}

// deleteExpiredTx 는 DeleteExpired 의 트랜잭션 본문이다.
func deleteExpiredTx(ctx context.Context, tx pgx.Tx, streamID string, seqs []int64) (DeleteResult, error) {
	var now time.Time
	if err := tx.QueryRow(ctx, setTxnLimitsSQL).Scan(&now); err != nil {
		return DeleteResult{}, fmt.Errorf("만료 삭제 상한 설정 실패 stream_id=%q: %w", streamID, err)
	}
	if err := tx.QueryRow(ctx, lockCutoffStreamSQL, streamID).Scan(&now); err != nil {
		return DeleteResult{}, fmt.Errorf("만료 삭제 주조 잠금 실패 stream_id=%q: %w", streamID, err)
	}
	cutoff, hasCutoff, err := lockExpiryCutoff(ctx, tx, streamID)
	if err != nil {
		return DeleteResult{}, err
	}

	deleted, sessions, err := deleteExpiryRows(ctx, tx, streamID, seqs)
	if err != nil {
		return DeleteResult{}, err
	}
	res := DeleteResult{RowsDeleted: int64(len(deleted))}
	if len(deleted) == 0 {
		return res, nil
	}
	emptied, err := collectStrings(ctx, tx, expiryEmptiedSessionsSQL, streamID, sessions)
	if err != nil {
		return DeleteResult{}, fmt.Errorf("만료 빈 세션 조회 실패 stream_id=%q: %w", streamID, err)
	}
	tag, err := tx.Exec(ctx, expiryDeleteGapsSQL, streamID, deleted, emptied)
	if err != nil {
		return DeleteResult{}, fmt.Errorf("만료 GAP 삭제 실패 stream_id=%q: %w", streamID, err)
	}
	res.GapsDeleted = tag.RowsAffected()
	if hasCutoff && slices.Contains(deleted, cutoff) {
		if res.Cutoff, res.CutoffSeq, err = moveExpiredCutoff(ctx, tx, streamID, cutoff); err != nil {
			return DeleteResult{}, err
		}
	}
	if res.OutboxDeleted, err = dropEmptiedSessions(ctx, tx, streamID, emptied); err != nil {
		return DeleteResult{}, err
	}
	res.SessionsDeleted = emptied
	return res, nil
}

// lockExpiryCutoff 는 컷오프 행을 잠근다. 무효면 ErrCutoffInvalid 다(e1).
func lockExpiryCutoff(ctx context.Context, tx pgx.Tx, streamID string) (seq int64, ok bool, err error) {
	var valid bool
	err = tx.QueryRow(ctx, expiryLockCutoffSQL, streamID).Scan(&seq, &valid)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		return 0, false, nil
	case err != nil:
		return 0, false, fmt.Errorf("만료 컷오프 잠금 실패 stream_id=%q: %w", streamID, err)
	case !valid:
		return 0, false, fmt.Errorf("%w: stream_id=%q cutoff_seq=%d", ErrCutoffInvalid, streamID, seq)
	}
	return seq, true, nil
}

// deleteExpiryRows 는 조각 행을 지우고 지운 seq 와 그 행들의 세션(중복 없음)을 돌려준다.
func deleteExpiryRows(ctx context.Context, tx pgx.Tx, streamID string, seqs []int64) ([]int64, []string, error) {
	rows, err := tx.Query(ctx, expiryDeleteSQL, streamID, seqs, ExpirySessionlessAge.Seconds())
	if err != nil {
		return nil, nil, fmt.Errorf("만료 조각 행 삭제 실패 stream_id=%q: %w", streamID, err)
	}
	defer rows.Close()
	var deleted []int64
	var sessions []string
	for rows.Next() {
		var seq int64
		var session *string
		if err := rows.Scan(&seq, &session); err != nil {
			return nil, nil, fmt.Errorf("만료 조각 행 삭제 결과 읽기 실패 stream_id=%q: %w", streamID, err)
		}
		deleted = append(deleted, seq)
		if session != nil && !slices.Contains(sessions, *session) {
			sessions = append(sessions, *session)
		}
	}
	if err := rows.Err(); err != nil {
		return nil, nil, fmt.Errorf("만료 조각 행 삭제 실패 stream_id=%q: %w", streamID, err)
	}
	return deleted, sessions, nil
}

// moveExpiredCutoff 는 지워진 컷오프 행을 뒤의 첫 자격 행으로 옮기고, 없으면 컷오프 행을 지운다(e4 · D14 · D15).
func moveExpiredCutoff(ctx context.Context, tx pgx.Tx, streamID string, old int64) (CutoffChange, int64, error) {
	var next int64
	err := tx.QueryRow(ctx, expiryAdvanceCutoffSQL, streamID, old).Scan(&next)
	if err == nil {
		return CutoffAdvanced, next, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return CutoffUnchanged, 0, fmt.Errorf("만료 컷오프 전진 실패 stream_id=%q cutoff_seq=%d: %w", streamID, old, err)
	}
	tag, err := tx.Exec(ctx, expiryRemoveCutoffSQL, streamID, old)
	if err != nil {
		return CutoffUnchanged, 0, fmt.Errorf("만료 컷오프 제거 실패 stream_id=%q cutoff_seq=%d: %w", streamID, old, err)
	}
	// 잠근 행이고 자격 생존 행이 없음을 방금 봤다 — 0행이면 조용히 넘기지 않는다(무효 컷오프가 남는다).
	if n := tag.RowsAffected(); n != 1 {
		return CutoffUnchanged, 0, fmt.Errorf("만료 컷오프 제거가 %d행이다(1행이어야 한다) stream_id=%q cutoff_seq=%d", n, streamID, old)
	}
	return CutoffRemoved, 0, nil
}

// dropEmptiedSessions 는 빈 세션의 후속 계승을 끊고 세션을 지운 뒤 고아 outbox 행을 지운다. 지운 outbox 수를 돌려준다.
func dropEmptiedSessions(ctx context.Context, tx pgx.Tx, streamID string, emptied []string) (int64, error) {
	if len(emptied) == 0 {
		return 0, nil
	}
	if _, err := tx.Exec(ctx, expiryUnlinkSuccessorsSQL, emptied); err != nil {
		return 0, fmt.Errorf("만료 후속 계승 끊기 실패 stream_id=%q: %w", streamID, err)
	}
	rounds, err := collectStrings(ctx, tx, expiryDeleteSessionsSQL, emptied)
	if err != nil {
		return 0, fmt.Errorf("만료 세션 삭제 실패 stream_id=%q: %w", streamID, err)
	}
	if len(rounds) == 0 {
		return 0, nil
	}
	tag, err := tx.Exec(ctx, expiryDeleteOutboxSQL, rounds)
	if err != nil {
		return 0, fmt.Errorf("만료 outbox 삭제 실패 stream_id=%q: %w", streamID, err)
	}
	return tag.RowsAffected(), nil
}

// collectStrings 는 한 열 text 결과를 모은다. NULL 은 건너뛴다.
func collectStrings(ctx context.Context, tx pgx.Tx, sql string, args ...any) ([]string, error) {
	rows, err := tx.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	vals, err := pgx.CollectRows(rows, pgx.RowTo[*string])
	if err != nil {
		return nil, err
	}
	var out []string
	for _, v := range vals {
		if v != nil {
			out = append(out, *v)
		}
	}
	return out, nil
}
