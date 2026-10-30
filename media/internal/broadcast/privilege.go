package broadcast

// 발행 게이트의 권한 확인(설계 M6 7절 활성화 선행 조건 · D50) — 기동 조립이 게이트를 켜기 전에 부른다.

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// outboxTable 은 계약9 outbox 표 이름이다.
const outboxTable = "broadcast_lifecycle_outbox"

// outboxPrivileges 는 outbox 에 쓰는 권한이다(이름 순) — 개시 · 4D 의 started INSERT, 완결 평가의 started 행 FOR SHARE
// (UPDATE), 발행기의 SELECT · UPDATE · 정리 DELETE. 사이드카 권한 목록(pgtest.MediaIndexerGrantSQL)의 outbox 몫과
// 같음을 시험이 단언한다.
var outboxPrivileges = [...]string{"DELETE", "INSERT", "SELECT", "UPDATE"}

// missingPrivilegesSQL ($1 권한 배열)은 접속 계정에 없는 outbox 권한을 고른다.
const missingPrivilegesSQL = `
SELECT p FROM pg_catalog.unnest($1::text[]) AS p
 WHERE NOT pg_catalog.has_table_privilege('broadcast_lifecycle_outbox', p)
 ORDER BY p`

// MissingPrivileges 는 접속 계정에 없는 outbox 권한을 「표:권한」 꼴로 돌려준다(이름 순 · 다 있으면 빈 목록). 부르는
// 쪽은 하나라도 있으면 게이트를 끈다(ERROR lifecycle_disabled reason=privilege — D50). 그러면 EnqueueStarted ·
// EnqueueEndedIfComplete 가 문장을 보내지 않아, 운영 권한(30_grants.sql)이 아직 없는 DB 에서도 개시 · phase 4 가
// 42501 로 막히지 않는다.
func MissingPrivileges(ctx context.Context, pool *pgxpool.Pool) ([]string, error) {
	rows, err := pool.Query(ctx, missingPrivilegesSQL, outboxPrivileges[:])
	if err != nil {
		return nil, fmt.Errorf("broadcast: outbox 권한 확인 조회: %w", err)
	}
	missing, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (string, error) {
		var p string
		err := row.Scan(&p)
		return outboxTable + ":" + p, err
	})
	if err != nil {
		return nil, fmt.Errorf("broadcast: outbox 권한 확인 읽기: %w", err)
	}
	return missing, nil
}
