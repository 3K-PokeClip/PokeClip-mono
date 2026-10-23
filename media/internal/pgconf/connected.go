package pgconf

import (
	"context"
	"crypto/tls"
	"fmt"
	"log/slog"

	"github.com/jackc/pgx/v5/pgxpool"
)

// ConnectedEvent 는 DB 연결 성공 줄의 메시지다. 배포 확인(엣지 O7e)이 이 문자열로 줄을 찾는다 — 바꾸면 그쪽도 바꾼다.
const ConnectedEvent = "db_connected"

// ConfirmConnection 은 풀에서 연결 하나를 잡아 Ping 하고, 성공하면 ConnectedEvent 줄 하나를 INFO 로 남긴다.
// 실패하면 줄 없이 오류를 돌려준다. 줄의 tls 는 설정값이 아니라 실제 연결이 TLS 인가다 — prefer 는 평문으로
// 내려갈 수 있다. 비밀번호 · 토큰 · DSN 은 싣지 않는다.
func ConfirmConnection(ctx context.Context, pool *pgxpool.Pool, s Settings, log *slog.Logger, poolName string) error {
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return fmt.Errorf("DB 연결 실패(pool=%s): %w", poolName, err)
	}
	defer conn.Release()
	if err := conn.Ping(ctx); err != nil {
		return fmt.Errorf("DB Ping 실패(pool=%s): %w", poolName, err)
	}
	_, isTLS := conn.Conn().PgConn().Conn().(*tls.Conn)
	log.InfoContext(ctx, ConnectedEvent,
		"pool", poolName,
		"auth", string(s.Auth),
		"sslmode", s.SSLMode,
		"tls", isTLS,
		"user", s.User,
		"host", s.Host,
		"port", s.Port,
		"db", s.DB,
	)
	return nil
}
