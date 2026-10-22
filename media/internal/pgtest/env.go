package pgtest

import (
	"errors"
	"net/url"
	"os"
	"testing"
)

// PostgresEnv 는 PG_DSN 을 사이드카 · janitor 가 읽는 POSTGRES_* 환경 변수 묶음(password 모드)으로 편다.
// 기동 경로(pgconf.FromEnv)를 그대로 지나 실 PG 에 붙는 시험이 쓴다. database 가 비지 않으면 DB 이름을 그것으로
// 바꾼다(전용 테스트 DB). PG_DSN 이 없으면 skip 이다. 실패 문구에 DSN 을 싣지 않는다(비밀번호가 있다).
func PostgresEnv(t *testing.T, database string) map[string]string {
	t.Helper()
	dsn := os.Getenv("PG_DSN")
	if dsn == "" {
		t.Skip("PG_DSN 미설정 — DB 통합 테스트를 건너뛴다")
	}
	env, err := postgresEnv(dsn, database)
	if err != nil {
		t.Fatalf("PG_DSN 을 POSTGRES_* 로 펴지 못했다(값은 싣지 않는다): %v", err)
	}
	return env
}

// postgresEnv 는 URL 꼴 DSN 을 POSTGRES_* 묶음으로 편다. sslmode 는 DSN 에 있을 때만 싣는다(없으면 기동 기본값).
func postgresEnv(dsn, database string) (map[string]string, error) {
	u, err := url.Parse(dsn)
	if err != nil || u.Scheme != "postgres" && u.Scheme != "postgresql" {
		return nil, errors.New("postgres:// URL 꼴이 아니다")
	}
	pass, _ := u.User.Password()
	env := map[string]string{
		"POSTGRES_USER":     u.User.Username(),
		"POSTGRES_PASSWORD": pass,
		"POSTGRES_HOST":     u.Hostname(),
		"POSTGRES_PORT":     u.Port(),
		"POSTGRES_DB":       u.Path[min(1, len(u.Path)):],
	}
	if database != "" {
		env["POSTGRES_DB"] = database
	}
	if mode := u.Query().Get("sslmode"); mode != "" {
		env["POSTGRES_SSLMODE"] = mode
	}
	return env, nil
}
