package main

import (
	"context"
	"fmt"
	"log/slog"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// requireLedgerSchema 는 기동 스키마 확인이다. M6 장부 객체(회차 열 넷 · 하한 표 · outbox 표 · 하한 트리거 둘)가
// 하나라도 없으면 ERROR schema_not_ready missing=… 를 남기고 오류를 돌려준다 — run 이 그 오류로 끝나 main 이 종료
// 코드 1 로 나간다(fail-fast). 운영(ENSURE_SCHEMA=false)은 운영 SQL 적용 · 40_verify.sql 통과가 이 판 배포의 선행
// 조건이다. 문자열 schema_not_ready 는 운영 런북(media-swap.md start-check)이 사이드카 로그에서 세는 계약이다.
func requireLedgerSchema(ctx context.Context, pool *pgxpool.Pool, log *slog.Logger) error {
	missing, err := index.MissingLedgerSchema(ctx, pool)
	if err != nil {
		return err
	}
	if len(missing) == 0 {
		return nil
	}
	joined := strings.Join(missing, ",")
	log.Error("schema_not_ready", "missing", joined, "note", "운영 SQL(stream_schema.sql) 적용 · 40_verify 통과 뒤 기동한다")
	return fmt.Errorf("schema_not_ready missing=%s", joined)
}
