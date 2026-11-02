package index

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

// missingLedgerSchemaSQL 은 M6 장부 객체(stream_sessions 열 넷 · 표 둘 · stream_segments 하한 트리거 둘) 가운데 없는
// 것의 이름을 고른다. 이름 꼴은 열 = 표.열 · 표 = 표 이름 · 트리거 = 트리거 이름이다.
//
// 사이드카 계정(media_indexer)으로 돈다 — 카탈로그 표는 PUBLIC 이 읽는다. 비교는 pg_catalog 에 정확한 판이 있는
// 형으로 맞춘다(oid = oid · name = name · text = text). 맞추지 않으면 앱 사용자가 public 에 심은 같은 형의 연산자가
// 먼저 골린다(search_path 가로채기, media/README.md).
const missingLedgerSchemaSQL = `
SELECT o.name
  FROM (VALUES ('stream_sessions.round_id',          'column',  'stream_sessions', 'round_id'),
               ('stream_sessions.round_closed_at',   'column',  'stream_sessions', 'round_closed_at'),
               ('stream_sessions.expired_at',        'column',  'stream_sessions', 'expired_at'),
               ('stream_sessions.stop_marked_at',    'column',  'stream_sessions', 'stop_marked_at'),
               ('stream_seq_floors',                 'table',   'stream_seq_floors', ''),
               ('broadcast_lifecycle_outbox',        'table',   'broadcast_lifecycle_outbox', ''),
               ('stream_segments_seq_floor_raise',   'trigger', 'stream_segments', 'stream_segments_seq_floor_raise'),
               ('stream_segments_seq_floor_guard',   'trigger', 'stream_segments', 'stream_segments_seq_floor_guard')
       ) AS o(name, kind, rel, sub)
 WHERE NOT CASE o.kind
           WHEN 'column' THEN EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a
                                       WHERE a.attrelid = pg_catalog.to_regclass(o.rel)::oid
                                         AND a.attname = o.sub::name AND a.attnum > 0 AND NOT a.attisdropped)
           WHEN 'table' THEN pg_catalog.to_regclass(o.rel) IS NOT NULL
           ELSE EXISTS (SELECT 1 FROM pg_catalog.pg_trigger t
                         WHERE t.tgrelid = pg_catalog.to_regclass(o.rel)::oid AND t.tgname = o.sub::name)
           END
 ORDER BY o.name`

// MissingLedgerSchema 는 M6 판 사이드카가 기동 전에 있어야 하는 장부 객체 가운데 없는 것의 이름을 돌려준다(이름
// 순 · 다 있으면 빈 목록). 운영은 ENSURE_SCHEMA=false 라 운영 SQL(stream_schema.sql)이 먼저 적용돼 있어야 한다.
//
// 새 열은 스위치와 무관한 핵심 문장(개시 INSERT · 커서 등)에 들어가므로, 하나라도 없으면 일부만 끄고 도는 길이
// 없다 — 부르는 쪽은 기동을 멈춘다.
func MissingLedgerSchema(ctx context.Context, pool *pgxpool.Pool) ([]string, error) {
	rows, err := pool.Query(ctx, missingLedgerSchemaSQL)
	if err != nil {
		return nil, fmt.Errorf("장부 스키마 확인 조회 실패: %w", err)
	}
	defer rows.Close()
	var missing []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			return nil, fmt.Errorf("장부 스키마 확인 행 읽기 실패: %w", err)
		}
		missing = append(missing, name)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("장부 스키마 확인 조회 실패: %w", err)
	}
	return missing, nil
}
