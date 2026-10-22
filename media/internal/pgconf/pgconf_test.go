package pgconf

// 값 규칙(계획 2-2) · DSN 동일성(2-4) · CA 파일 검증(2-5) · 비밀 미노출(D11) 시험. 값은 모두 합성이다.
// 누설 시험의 실패 문구에는 비밀을 싣지 않는다 — 「카나리아 발견」 만 적는다.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"path/filepath"
	"strings"
	"testing"
)

// canary 는 비밀 자리에 넣는 표지다. 오류 · 로그 · 형식 출력에 나타나면 누설이다.
const canary = "CANARY-7f3e-secret"

func envOf(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

// passwordEnv 는 password 모드의 최소 env 다(로컬 compose 꼴).
func passwordEnv() map[string]string {
	return map[string]string{"POSTGRES_USER": "pokeclip", "POSTGRES_PASSWORD": "pw", "POSTGRES_DB": "pokeclip"}
}

// iamEnv 는 iam 모드의 최소 env 다(엣지 c3 의 prod env 꼴).
func iamEnv() map[string]string {
	return map[string]string{
		"POSTGRES_AUTH":        "iam",
		"POSTGRES_USER":        "media_indexer",
		"POSTGRES_DB":          "pokeclip",
		"POSTGRES_HOST":        "db.example.internal",
		"POSTGRES_SSLROOTCERT": "/etc/pokeclip/rds-ca/global-bundle.pem",
	}
}

func with(m map[string]string, kv ...string) map[string]string {
	out := make(map[string]string, len(m)+len(kv)/2)
	for k, v := range m {
		out[k] = v
	}
	for i := 0; i+1 < len(kv); i += 2 {
		out[kv[i]] = kv[i+1]
	}
	return out
}

// 기본값 — 빈 AUTH 는 password(지금과 같음), iam 은 sslmode 기본 verify-full · 리전 기본 ap-northeast-2.
func TestFromEnvDefaults(t *testing.T) {
	for _, c := range []struct {
		name string
		env  map[string]string
		want Settings
	}{
		{
			name: "빈 AUTH = password",
			env:  passwordEnv(),
			want: Settings{Auth: AuthPassword, User: "pokeclip", Host: "postgres", Port: 5432, DB: "pokeclip", SSLMode: "prefer", Region: "ap-northeast-2", password: "pw"},
		},
		{
			name: "AUTH=password 명시",
			env:  with(passwordEnv(), "POSTGRES_AUTH", "password", "POSTGRES_SSLMODE", "require", "POSTGRES_PORT", "6543", "POSTGRES_HOST", "db"),
			want: Settings{Auth: AuthPassword, User: "pokeclip", Host: "db", Port: 6543, DB: "pokeclip", SSLMode: "require", Region: "ap-northeast-2", password: "pw"},
		},
		{
			name: "iam 기본 verify-full",
			env:  iamEnv(),
			want: Settings{Auth: AuthIAM, User: "media_indexer", Host: "db.example.internal", Port: 5432, DB: "pokeclip", SSLMode: "verify-full", SSLRootCert: "/etc/pokeclip/rds-ca/global-bundle.pem", Region: "ap-northeast-2"},
		},
		{
			name: "iam 앞뒤 공백 · verify-full 명시 · 리전 지정",
			env:  with(iamEnv(), "POSTGRES_AUTH", " iam ", "POSTGRES_SSLMODE", "verify-full", "AWS_REGION", "us-east-1"),
			want: Settings{Auth: AuthIAM, User: "media_indexer", Host: "db.example.internal", Port: 5432, DB: "pokeclip", SSLMode: "verify-full", SSLRootCert: "/etc/pokeclip/rds-ca/global-bundle.pem", Region: "us-east-1"},
		},
		{
			name: "PGSSLROOTCERT 가 있어도 POSTGRES_SSLROOTCERT 가 있으면 그것이 산다",
			env:  with(iamEnv(), "PGSSLROOTCERT", "/elsewhere.pem"),
			want: Settings{Auth: AuthIAM, User: "media_indexer", Host: "db.example.internal", Port: 5432, DB: "pokeclip", SSLMode: "verify-full", SSLRootCert: "/etc/pokeclip/rds-ca/global-bundle.pem", Region: "ap-northeast-2"},
		},
	} {
		t.Run(c.name, func(t *testing.T) {
			got, err := FromEnv(envOf(c.env))
			if err != nil {
				t.Fatalf("FromEnv 에러 %v, want nil", err)
			}
			if got != c.want {
				t.Errorf("FromEnv = %v, want %v", got, c.want)
			}
			if got.password != c.want.password {
				t.Errorf("비밀번호 필드가 기대와 다르다(길이 %d, want 길이 %d)", len(got.password), len(c.want.password))
			}
		})
	}
}

// 거부 행 — 변수 이름을 싣고, 비밀번호 값은 싣지 않는다.
func TestFromEnvRejects(t *testing.T) {
	cases := []struct {
		name    string
		env     map[string]string
		wantSub string
	}{
		{"password 모드 비밀번호 없음", with(passwordEnv(), "POSTGRES_PASSWORD", " "), "POSTGRES_PASSWORD"},
		{"iam + 비밀번호", with(iamEnv(), "POSTGRES_PASSWORD", canary), "POSTGRES_PASSWORD"},
		{"iam 에 SSLROOTCERT 없음", with(iamEnv(), "POSTGRES_SSLROOTCERT", ""), "POSTGRES_SSLROOTCERT"},
		{"PGSSLROOTCERT 만 있음(iam)", with(iamEnv(), "POSTGRES_SSLROOTCERT", "", "PGSSLROOTCERT", "/x.pem"), "PGSSLROOTCERT"},
		{"PGSSLROOTCERT 만 있음(password)", with(passwordEnv(), "PGSSLROOTCERT", "/x.pem"), "PGSSLROOTCERT"},
		{"SSLROOTCERT 상대 경로", with(passwordEnv(), "POSTGRES_SSLROOTCERT", "certs/ca.pem"), "POSTGRES_SSLROOTCERT"},
		{"모르는 AUTH", with(passwordEnv(), "POSTGRES_AUTH", "md5"), "POSTGRES_AUTH"},
		{"대소문자 다른 AUTH(IAM)", with(iamEnv(), "POSTGRES_AUTH", "IAM"), "POSTGRES_AUTH"},
		{"대소문자 다른 AUTH(Iam)", with(iamEnv(), "POSTGRES_AUTH", "Iam"), "POSTGRES_AUTH"},
		{"USER 없음", with(passwordEnv(), "POSTGRES_USER", ""), "POSTGRES_USER"},
		{"DB 없음", with(passwordEnv(), "POSTGRES_DB", ""), "POSTGRES_DB"},
		{"PORT 숫자 아님", with(passwordEnv(), "POSTGRES_PORT", "x"), "POSTGRES_PORT"},
		{"PORT 0", with(passwordEnv(), "POSTGRES_PORT", "0"), "POSTGRES_PORT"},
	}
	for _, mode := range []string{"disable", "allow", "prefer", "require", "verify-ca", "VERIFY-FULL", "bogus"} {
		cases = append(cases, struct {
			name    string
			env     map[string]string
			wantSub string
		}{"iam 약한 sslmode " + mode, with(iamEnv(), "POSTGRES_SSLMODE", mode), "POSTGRES_SSLMODE"})
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := FromEnv(envOf(c.env))
			if err == nil {
				t.Fatal("FromEnv 에러 nil, want 에러")
			}
			if !strings.Contains(err.Error(), c.wantSub) {
				t.Errorf("에러 %q 에 %q 가 없다", err, c.wantSub)
			}
			if strings.Contains(err.Error(), canary) {
				t.Error("에러에 카나리아 발견 — 비밀번호 값이 실렸다")
			}
		})
	}
}

// DSN 동일성 — 옛 config.dsn · janitor.dsnFrom 시험의 기대 문자열 그대로(계획 2-4).
func TestDSNMatchesLegacyPasswordForm(t *testing.T) {
	env := map[string]string{"POSTGRES_USER": "u", "POSTGRES_PASSWORD": "p@ss word", "POSTGRES_DB": "d", "POSTGRES_HOST": "db", "POSTGRES_PORT": "6543", "POSTGRES_SSLMODE": "require"}
	s, err := FromEnv(envOf(env))
	if err != nil {
		t.Fatal(err)
	}
	if got, want := dsn(s), "postgres://u:p%40ss%20word@db:6543/d?sslmode=require"; got != want {
		t.Errorf("dsn = %q, want %q", got, want)
	}
	s, err = FromEnv(envOf(passwordEnv()))
	if err != nil {
		t.Fatal(err)
	}
	if got, want := dsn(s), "postgres://pokeclip:pw@postgres:5432/pokeclip?sslmode=prefer"; got != want {
		t.Errorf("dsn = %q, want %q", got, want)
	}
}

// iam DSN 에는 비밀번호 자리가 없고 sslrootcert 가 명시된다(PGSSLROOTCERT 가 끼어들 틈이 없다).
func TestDSNIAMHasNoPasswordAndNamesRootCert(t *testing.T) {
	s, err := FromEnv(envOf(iamEnv()))
	if err != nil {
		t.Fatal(err)
	}
	want := "postgres://media_indexer@db.example.internal:5432/pokeclip?sslmode=verify-full&sslrootcert=%2Fetc%2Fpokeclip%2Frds-ca%2Fglobal-bundle.pem"
	if got := dsn(s); got != want {
		t.Errorf("dsn = %q, want %q", got, want)
	}
}

// password 모드 PoolConfig — BeforeConnect 없음 · 비밀번호 = env 값.
func TestPoolConfigPasswordMode(t *testing.T) {
	s, err := FromEnv(envOf(with(passwordEnv(), "POSTGRES_PASSWORD", canary, "POSTGRES_SSLMODE", "disable")))
	if err != nil {
		t.Fatal(err)
	}
	pc, err := PoolConfig(context.Background(), s)
	if err != nil {
		t.Fatalf("PoolConfig 에러 %v", err)
	}
	if pc.BeforeConnect != nil {
		t.Error("password 모드에 BeforeConnect 가 걸렸다")
	}
	if pc.ConnConfig.Password != canary || pc.ConnConfig.User != "pokeclip" || pc.ConnConfig.Database != "pokeclip" {
		t.Errorf("ConnConfig 가 env 와 다르다(user %q · db %q)", pc.ConnConfig.User, pc.ConnConfig.Database)
	}
}

// pgx 가 DSN 을 못 읽으면 고정 문구로 바꾼다 — pgx 오류 원문에는 DSN(비밀번호)이 실린다.
func TestPoolConfigHidesDSNOnParseError(t *testing.T) {
	s, err := FromEnv(envOf(with(passwordEnv(), "POSTGRES_PASSWORD", canary, "POSTGRES_SSLMODE", "bogus")))
	if err != nil {
		t.Fatal(err)
	}
	_, err = PoolConfig(context.Background(), s)
	if err == nil {
		t.Fatal("PoolConfig 에러 nil, want 에러(잘못된 sslmode)")
	}
	if strings.Contains(err.Error(), canary) {
		t.Error("에러에 카나리아 발견 — DSN 원문이 실렸다")
	}
	if !strings.Contains(err.Error(), "POSTGRES_") {
		t.Errorf("에러 %q 에 변수 이름 안내가 없다", err)
	}
}

// 손으로 만든 Settings 도 iam 규칙을 지나야 한다 — FromEnv 를 거치지 않은 호출이 평문으로 내려가지 않게.
func TestPoolConfigRevalidatesIAMRules(t *testing.T) {
	s, err := FromEnv(envOf(iamEnv()))
	if err != nil {
		t.Fatal(err)
	}
	s.SSLMode = "require"
	if _, err := PoolConfig(context.Background(), s, WithTokenFunc(staticToken("t"))); err == nil || !strings.Contains(err.Error(), "POSTGRES_SSLMODE") {
		t.Errorf("PoolConfig 에러 = %v, want POSTGRES_SSLMODE 거부", err)
	}
}

// CA 파일 검증 — 일반 파일이고 PEM 인증서가 1개 이상이어야 한다. 사유와 경로만 싣고 내용은 싣지 않는다.
func TestPoolConfigChecksRootCertFile(t *testing.T) {
	dir := t.TempDir()
	ca := newTestCA(t, "pgconf-test-ca")
	good := writeTestFile(t, dir, "good.pem", ca.pem)
	cases := map[string]string{
		"없는 파일":      filepath.Join(dir, "missing.pem"),
		"디렉터리":       dir,
		"빈 파일":       writeTestFile(t, dir, "empty.pem", nil),
		"쓰레기 바이트":    writeTestFile(t, dir, "junk.pem", []byte(canary)),
		"인증서 아닌 PEM": writeTestFile(t, dir, "key.pem", []byte("-----BEGIN PRIVATE KEY-----\nQ0FOQVJZ\n-----END PRIVATE KEY-----\n")),
		"깨진 인증서 PEM": writeTestFile(t, dir, "broken.pem", []byte("-----BEGIN CERTIFICATE-----\nQ0FOQVJZ\n-----END CERTIFICATE-----\n")),
	}
	for name, path := range cases {
		t.Run(name, func(t *testing.T) {
			s, err := FromEnv(envOf(with(iamEnv(), "POSTGRES_SSLROOTCERT", path)))
			if err != nil {
				t.Fatal(err)
			}
			_, err = PoolConfig(context.Background(), s, WithTokenFunc(staticToken("t")))
			if err == nil {
				t.Fatal("PoolConfig 에러 nil, want CA 파일 거부")
			}
			if !strings.Contains(err.Error(), "POSTGRES_SSLROOTCERT") || !strings.Contains(err.Error(), path) {
				t.Errorf("에러 %q 에 변수 이름 · 경로가 없다", err)
			}
			if strings.Contains(err.Error(), canary) || strings.Contains(err.Error(), "Q0FOQVJZ") {
				t.Error("에러에 파일 내용 발견")
			}
		})
	}
	t.Run("정상", func(t *testing.T) {
		s, err := FromEnv(envOf(with(iamEnv(), "POSTGRES_SSLROOTCERT", good)))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := PoolConfig(context.Background(), s, WithTokenFunc(staticToken("t"))); err != nil {
			t.Errorf("PoolConfig 에러 %v, want nil", err)
		}
	})
}

// Settings 는 어떤 형식 · 로그 · JSON 출력에도 비밀번호를 내놓지 않는다(D11 — fmt.Formatter · slog.LogValuer 계약).
func TestSettingsNeverRevealsPassword(t *testing.T) {
	s, err := FromEnv(envOf(with(passwordEnv(), "POSTGRES_PASSWORD", canary)))
	if err != nil {
		t.Fatal(err)
	}
	var outs []string
	for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q", "%d", "%x", "%X", "%T"} {
		outs = append(outs, fmt.Sprintf(verb, s), fmt.Sprintf(verb, &s))
	}
	var buf bytes.Buffer
	slog.New(slog.NewJSONHandler(&buf, nil)).Info("x", "pg", s, "pgp", &s)
	slog.New(slog.NewTextHandler(&buf, nil)).Info("x", "pg", s)
	outs = append(outs, buf.String())
	j, err := json.Marshal(s)
	if err != nil {
		t.Fatal(err)
	}
	outs = append(outs, string(j))
	for i, out := range outs {
		if strings.Contains(out, canary) {
			t.Errorf("출력 %d 에 카나리아 발견", i)
		}
	}
	if v := fmt.Sprintf("%v", s); !strings.Contains(v, "pokeclip") || !strings.Contains(v, "password") {
		t.Errorf("%%v 출력 %q 에 비밀 아닌 필드(user · auth)가 없다", v)
	}
	if !strings.Contains(buf.String(), `"user":"pokeclip"`) {
		t.Errorf("slog 출력 %q 에 user 필드가 없다", buf.String())
	}
}
