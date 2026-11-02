package pgtest

import (
	"maps"
	"testing"
)

// CI 의 PG_DSN 꼴(URL · sslmode=disable)을 기동 경로가 읽는 POSTGRES_* 로 편다. 인코딩된 비밀번호는 풀어서 싣는다.
func TestPostgresEnvSplitsURL(t *testing.T) {
	got, err := postgresEnv("postgres://postgres:p%40ss@127.0.0.1:5432/pokeclip_ci?sslmode=disable", "")
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]string{
		"POSTGRES_USER": "postgres", "POSTGRES_PASSWORD": "p@ss", "POSTGRES_HOST": "127.0.0.1",
		"POSTGRES_PORT": "5432", "POSTGRES_DB": "pokeclip_ci", "POSTGRES_SSLMODE": "disable",
	}
	if !maps.Equal(got, want) {
		t.Errorf("postgresEnv = %v, want %v", got, want)
	}
}

// database 를 주면 DB 이름만 바뀌고, sslmode 가 없으면 싣지 않는다(기동 기본값).
func TestPostgresEnvOverridesDatabase(t *testing.T) {
	got, err := postgresEnv("postgres://u:p@db:6543/admin", "pokeclip_citest_cmd")
	if err != nil {
		t.Fatal(err)
	}
	if got["POSTGRES_DB"] != "pokeclip_citest_cmd" {
		t.Errorf("POSTGRES_DB = %q, want pokeclip_citest_cmd", got["POSTGRES_DB"])
	}
	if _, ok := got["POSTGRES_SSLMODE"]; ok {
		t.Errorf("sslmode 가 없는 DSN 인데 POSTGRES_SSLMODE = %q", got["POSTGRES_SSLMODE"])
	}
}

// 키워드 꼴 DSN 은 거부한다 — 펼 수 없는 값을 빈 값으로 넘기면 엉뚱한 곳에 붙는다.
func TestPostgresEnvRejectsKeywordDSN(t *testing.T) {
	if _, err := postgresEnv("host=db user=u password=p dbname=d", ""); err == nil {
		t.Error("키워드 꼴 DSN 을 받았다, want 거부")
	}
}
