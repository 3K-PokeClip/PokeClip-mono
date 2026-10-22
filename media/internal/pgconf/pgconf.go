// Package pgconf 는 사이드카와 janitor 가 함께 쓰는 DB 접속 설정이다.
//
// POSTGRES_* · AWS_REGION 을 검증해 Settings 로 만들고(FromEnv), 그것으로 pgx 풀 설정을 조립한다(PoolConfig).
// 로그인 방식은 둘이다.
//   - password(POSTGRES_AUTH 빈 값 · "password"): POSTGRES_PASSWORD 로 로그인한다. 로컬 compose 의 방식이며
//     이 패키지가 생기기 전과 같은 DSN 을 만든다.
//   - iam("iam"): 연결마다 RDS IAM 인증 토큰(15분 유효 서명 URL)을 새로 만들어 비밀번호 자리에 넣는다.
//     TLS 는 verify-full 만 받고, 서버 인증서는 POSTGRES_SSLROOTCERT 의 CA 번들로 검증한다.
//
// AWS 에 기대는 코드는 iam.go 한 파일뿐이다(설계 3-3-1 「AWS 종속은 DB 로그인 함수 한 곳」).
// 인식하는 환경 변수 전수 목록은 media/README.md 에 있다.
package pgconf

import (
	"context"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Auth 는 DB 로그인 방식이다.
type Auth string

// 로그인 방식 둘. POSTGRES_AUTH 의 빈 값은 AuthPassword 다.
const (
	AuthPassword Auth = "password"
	AuthIAM      Auth = "iam"
)

const (
	defaultHost   = "postgres"
	defaultPort   = 5432
	defaultRegion = "ap-northeast-2"
	// prefer = TLS 를 시도하되 서버가 못 하면 평문으로 내려간다. 로컬 compose 기본에 맞춘 password 모드 값이다.
	defaultPasswordSSLMode = "prefer"
	// iamSSLMode 는 iam 모드가 받는 유일한 sslmode 다 — 토큰은 공식 유효 15분이지만 서버가 그 뒤에도 얼마간 받는(실험 RDS 실측 약 20분) 재사용
	// 가능한 자격이라 중간자에게 내줄 수 없다(kty 결정 질문 2).
	iamSSLMode = "verify-full"
	// tokenTimeout 은 토큰 생성(자격 증명 조회 포함) 한 번의 상한이다. 풀의 보충 연결은 context.Background 로
	// BeforeConnect 를 부르므로(pgx#2545) 이 상한이 없으면 IMDS 가 멈출 때 연결 생성이 끝없이 걸린다.
	// IMDS 재시도 여유를 두고 pgx 연결 상한 기본값(2분)보다 짧게 잡았다.
	tokenTimeout = 10 * time.Second
	// maxRootCertBytes 는 CA 번들 파일 크기 상한이다. RDS 통합 번들은 수백 KB 다.
	maxRootCertBytes = 4 << 20
)

// Settings 는 POSTGRES_* · AWS_REGION 을 검증한 값이다.
//
// 비밀번호는 비공개 필드라 JSON 에 실리지 않고, fmt 의 모든 동사(Format)와 slog(LogValue)에서도 빠진다.
// 그래도 Settings 를 오류 문구에 통째로 싣지 않는다 — 필드를 골라 싣는다.
type Settings struct {
	Auth    Auth
	User    string
	Host    string
	Port    int
	DB      string
	SSLMode string
	// SSLRootCert 는 서버 인증서를 검증할 CA 번들의 절대 경로다. 빈 값 = 지정 없음(password 모드에서만 허용).
	SSLRootCert string
	// Region 은 IAM 토큰을 서명할 리전이다(AWS_REGION). iam 에서만 쓴다.
	Region   string
	password string
}

// FromEnv 는 환경 변수를 읽어 Settings 를 만든다. getenv 는 os.Getenv 를 그대로 넘기면 된다.
// 파일은 열지 않는다 — CA 파일 확인은 PoolConfig 가 한다. 오류 문구에는 변수 이름을 싣고 비밀 값은 싣지 않는다.
func FromEnv(getenv func(string) string) (Settings, error) {
	auth, err := parseAuth(getenv("POSTGRES_AUTH"))
	if err != nil {
		return Settings{}, err
	}
	user, err := required(getenv, "POSTGRES_USER")
	if err != nil {
		return Settings{}, err
	}
	db, err := required(getenv, "POSTGRES_DB")
	if err != nil {
		return Settings{}, err
	}
	port, err := positiveInt(getenv, "POSTGRES_PORT", defaultPort)
	if err != nil {
		return Settings{}, err
	}
	s := Settings{
		Auth:        auth,
		User:        user,
		Host:        withDefault(getenv, "POSTGRES_HOST", defaultHost),
		Port:        port,
		DB:          db,
		SSLMode:     withDefault(getenv, "POSTGRES_SSLMODE", defaultSSLMode(auth)),
		SSLRootCert: strings.TrimSpace(getenv("POSTGRES_SSLROOTCERT")),
		Region:      withDefault(getenv, "AWS_REGION", defaultRegion),
		password:    strings.TrimSpace(getenv("POSTGRES_PASSWORD")),
	}
	// pgx 는 PG* 환경 변수를 기본값으로 읽는다. 경로를 비워 두면 PGSSLROOTCERT 가 몰래 쓰이므로 이름을 하나로 못 박는다.
	if s.SSLRootCert == "" && strings.TrimSpace(getenv("PGSSLROOTCERT")) != "" {
		return Settings{}, errors.New("PGSSLROOTCERT 는 쓰지 않는다 — CA 번들 경로는 POSTGRES_SSLROOTCERT 로 준다")
	}
	if err := s.validate(); err != nil {
		return Settings{}, err
	}
	return s, nil
}

func parseAuth(raw string) (Auth, error) {
	switch v := strings.TrimSpace(raw); v {
	case "", string(AuthPassword):
		return AuthPassword, nil
	case string(AuthIAM):
		return AuthIAM, nil
	default:
		return "", fmt.Errorf("POSTGRES_AUTH(%q)는 iam · password · 빈 값(= password) 가운데 하나여야 한다(대소문자 구분)", v)
	}
}

func defaultSSLMode(a Auth) string {
	if a == AuthIAM {
		return iamSSLMode
	}
	return defaultPasswordSSLMode
}

// validate 는 필드 사이의 규칙을 본다. FromEnv 를 거치지 않고 만든 Settings 도 PoolConfig 에서 같은 규칙을 지난다.
func (s Settings) validate() error {
	switch s.Auth {
	case AuthPassword:
		if s.password == "" {
			return errors.New("필수 환경 변수 POSTGRES_PASSWORD 가 비어 있다(password 모드)")
		}
		// CA 번들을 주면 검증 강도를 값으로 밝힌 sslmode 만 받는다(kty 결정 — C1 · C2 보안 검수 M).
		//   - verify-ca: 운영자가 체인 검증만을 명시적으로 고른 경우라 허용한다. 호스트 이름은 보지 않는다
		//     — README 에 공개하고, 운영은 verify-full 이다.
		//   - require · prefer · allow: 값이 검증 강도를 밝히지 않아 거부한다. prefer · allow 는 CA 를 주어도
		//     인증서를 검증하지 않고 평문으로 내려갈 수 있다. require 는 CA 가 있을 때만 pgx 가 암묵적으로
		//     체인을 검증해(verify-ca 와 같다) CA 가 빠지면 조용히 무검증이 된다.
		if s.SSLRootCert != "" && s.SSLMode != "verify-ca" && s.SSLMode != iamSSLMode {
			return fmt.Errorf("POSTGRES_SSLROOTCERT 를 주면 POSTGRES_SSLMODE 가 verify-ca · verify-full 이어야 한다(받은 값 %q)", s.SSLMode)
		}
	case AuthIAM:
		if s.password != "" {
			return errors.New("iam 에서는 POSTGRES_PASSWORD 를 비워야 한다 — 비밀번호 대신 연결마다 RDS IAM 토큰으로 로그인한다")
		}
		if s.SSLMode != iamSSLMode {
			return fmt.Errorf("iam 에서는 POSTGRES_SSLMODE 가 %s 이어야 한다(받은 값 %q)", iamSSLMode, s.SSLMode)
		}
		if s.SSLRootCert == "" {
			return errors.New("iam 에서는 POSTGRES_SSLROOTCERT(RDS CA 번들의 절대 경로)가 필요하다")
		}
		if err := checkIAMHost(s.Host); err != nil {
			return err
		}
	default:
		return fmt.Errorf("POSTGRES_AUTH(%q)는 iam · password 가운데 하나여야 한다", string(s.Auth))
	}
	if s.SSLRootCert != "" && !filepath.IsAbs(s.SSLRootCert) {
		return fmt.Errorf("POSTGRES_SSLROOTCERT(%s)는 절대 경로여야 한다", s.SSLRootCert)
	}
	return nil
}

// checkIAMHost 는 iam 호스트가 TCP 호스트 하나인가를 본다. pgx 는 / 가 든 값을 유닉스 소켓으로, 쉼표를 여러
// 호스트로 읽는다. 대괄호 없는 IPv6 는 "host:port" 를 깨뜨려 토큰을 서명한 대상과 실제 접속 대상이 갈라진다.
// [ 로 시작해 ] 로 끝나는 임의 문자열(예: "[a]b]")은 여기서 통과하고 ParseConfig 에서 기동 오류가 된다(안전 실패).
func checkIAMHost(host string) error {
	bracketed := strings.HasPrefix(host, "[") && strings.HasSuffix(host, "]")
	if strings.ContainsAny(host, "/,") || strings.IndexFunc(host, unicode.IsSpace) >= 0 ||
		(strings.Contains(host, ":") && !bracketed) {
		return fmt.Errorf("iam 에서는 POSTGRES_HOST(%q)가 TCP 호스트 하나여야 한다 — / · 쉼표 · 공백 불가, IPv6 는 대괄호로 감싼다", host)
	}
	return nil
}

// Format 은 fmt 의 모든 동사에서 비밀번호를 뺀 꼴만 쓴다(%v · %+v · %#v · %d 등 — 기본 구조체 출력은 비공개 필드도 찍는다).
func (s Settings) Format(f fmt.State, _ rune) {
	fmt.Fprintf(f, "pgconf.Settings{auth=%s user=%s host=%s port=%d db=%s sslmode=%s sslrootcert=%s region=%s}",
		s.Auth, s.User, s.Host, s.Port, s.DB, s.SSLMode, s.SSLRootCert, s.Region)
}

// LogValue 는 slog 에 비밀 없는 필드만 내놓는다.
func (s Settings) LogValue() slog.Value {
	return slog.GroupValue(
		slog.String("auth", string(s.Auth)),
		slog.String("user", s.User),
		slog.String("host", s.Host),
		slog.Int("port", s.Port),
		slog.String("db", s.DB),
		slog.String("sslmode", s.SSLMode),
		slog.String("sslrootcert", s.SSLRootCert),
		slog.String("region", s.Region),
	)
}

// TokenFunc 는 RDS IAM 토큰을 만든다. endpoint = "host:port".
type TokenFunc func(ctx context.Context, endpoint, region, user string) (string, error)

// Option 은 PoolConfig 의 선택 사항이다.
type Option func(*options)

type options struct {
	tokenFunc    TokenFunc
	tokenTimeout time.Duration
}

// WithTokenFunc 는 토큰 생성 함수를 바꾼다(시험용 주입 지점). 주지 않으면 AWS 기본 자격 증명 사슬을 쓴다.
func WithTokenFunc(f TokenFunc) Option { return func(o *options) { o.tokenFunc = f } }

// WithTokenTimeout 은 토큰 생성 한 번의 상한을 바꾼다(시험용 — 기본 10초).
func WithTokenTimeout(d time.Duration) Option { return func(o *options) { o.tokenTimeout = d } }

// PoolConfig 는 CA 파일을 확인하고 pgx 풀 설정을 만든다. iam 이면 연결마다 새 토큰을 넣는 BeforeConnect 를 건다.
// 호출 쪽은 결과에 자기 설정(풀 크기 · 세션 상한 등)을 덧붙여 pgxpool.NewWithConfig 로 넘긴다.
func PoolConfig(ctx context.Context, s Settings, opts ...Option) (*pgxpool.Config, error) {
	if err := s.validate(); err != nil {
		return nil, err
	}
	o := options{tokenTimeout: tokenTimeout}
	for _, opt := range opts {
		opt(&o)
	}
	if s.SSLRootCert != "" {
		if err := checkRootCert(s.SSLRootCert); err != nil {
			return nil, err
		}
	}
	pc, err := pgxpool.ParseConfig(dsn(s))
	if err != nil {
		// 원문에 DSN(비밀번호)이 실리므로 감싸지 않는다.
		return nil, errors.New("POSTGRES_* 로 만든 DSN 해석 실패 — POSTGRES_SSLMODE · POSTGRES_HOST 등의 값을 확인하라")
	}
	if s.Auth != AuthIAM {
		return pc, nil
	}
	tf := o.tokenFunc
	if tf == nil {
		if tf, err = newTokenFunc(ctx, s.Region); err != nil {
			return nil, err
		}
	}
	pc.BeforeConnect = iamBeforeConnect(tf, s, o.tokenTimeout)
	return pc, nil
}

// iamBeforeConnect 는 연결마다 토큰을 새로 만들어 그 연결 설정의 비밀번호 자리에 넣는다. 캐시하지 않는다 —
// 토큰의 공식 유효 시간은 15분이지만 RDS 가 거부하기 시작하는 시점은 그보다 늦다 — 2026-10-22 실험 RDS(서울 1대 ·
// 1회)에서 서명 뒤 19분 30초는 받고 20분 30초부터 PAM 인증 실패였다. 보장 값이 아니므로 캐시하면 언젠가 새 연결이
// 실패한다는 것만 기대고, 실패 시점은 기대지 않는다.
func iamBeforeConnect(tf TokenFunc, s Settings, timeout time.Duration) func(context.Context, *pgx.ConnConfig) error {
	endpoint := fmt.Sprintf("%s:%d", s.Host, s.Port)
	region, user := s.Region, s.User
	return func(ctx context.Context, cc *pgx.ConnConfig) error {
		// ctx 가 Background(풀 보충)여도 상한이 선다. ctx 의 값에는 기대지 않는다(pgx#2545).
		tctx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		token, err := tf(tctx, endpoint, region, user)
		if err != nil {
			return tokenError(ctx, tctx, err, endpoint, region, user, timeout)
		}
		if token == "" {
			return fmt.Errorf("RDS IAM 토큰 생성 결과가 비었다(region=%s user=%s endpoint=%s)", region, user, endpoint)
		}
		cc.Password = token
		return nil
	}
}

// tokenError 는 토큰 생성 실패를 고정 문구로 바꾼다. 원인 오류는 감싸지도 옮겨 적지도 않는다 — 자격 증명 도구
// (credential_process)는 실패하면 출력 원문을 SDK 오류에 넣으므로(D1) 그 원문이 로그로 샐 수 있다.
// 진단에는 원인 오류의 타입 이름만 싣는다.
func tokenError(ctx, tctx context.Context, cause error, endpoint, region, user string, timeout time.Duration) error {
	where := fmt.Sprintf("region=%s user=%s endpoint=%s", region, user, endpoint)
	switch {
	case ctx.Err() != nil:
		return fmt.Errorf("RDS IAM 토큰 생성 취소(%s)", where)
	case errors.Is(tctx.Err(), context.DeadlineExceeded):
		return fmt.Errorf("RDS IAM 토큰 생성 시간 초과(%v, %s) — 자격 증명 조회(IMDS 등)가 응답하지 않는다", timeout, where)
	default:
		return fmt.Errorf("RDS IAM 토큰 생성 실패(%s, 원인 종류 %T) — 인스턴스 역할 · AWS_REGION 을 확인하라", where, cause)
	}
}

// dsn 은 Settings 를 pgx 연결 문자열로 만든다. 사용자명 · 비밀번호는 URL 인코딩한다(특수문자가 든 비밀번호도
// 깨지지 않는다). iam 은 비밀번호 자리를 비운다 — BeforeConnect 가 채운다. sslrootcert 는 값이 있을 때만 싣는다.
func dsn(s Settings) string {
	userinfo := url.User(s.User)
	if s.Auth == AuthPassword {
		userinfo = url.UserPassword(s.User, s.password)
	}
	q := url.Values{"sslmode": {s.SSLMode}}
	if s.SSLRootCert != "" {
		q.Set("sslrootcert", s.SSLRootCert)
	}
	u := url.URL{
		Scheme:   "postgres",
		User:     userinfo,
		Host:     fmt.Sprintf("%s:%d", s.Host, s.Port),
		Path:     "/" + s.DB,
		RawQuery: q.Encode(),
	}
	return u.String()
}

// checkRootCert 는 CA 번들 파일이 일반 파일이고 PEM 인증서를 하나 이상 담았는지 본다. 틀리면 verify-full 이
// 원인을 x509 오류로만 보이므로 기동 때 사유를 밝힌다. 오류에는 경로만 싣고 내용은 싣지 않는다.
func checkRootCert(path string) error {
	fail := func(reason string) error { return fmt.Errorf("POSTGRES_SSLROOTCERT(%s): %s", path, reason) }
	fi, err := os.Stat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return fail("파일이 없다")
		}
		return fail(fmt.Sprintf("확인 실패(%T)", err))
	}
	if !fi.Mode().IsRegular() {
		return fail("일반 파일이 아니다")
	}
	if fi.Size() > maxRootCertBytes {
		return fail(fmt.Sprintf("%d 바이트를 넘는다", maxRootCertBytes))
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return fail(fmt.Sprintf("읽기 실패(%T)", err))
	}
	n := 0
	for block, rest := pem.Decode(data); block != nil; block, rest = pem.Decode(rest) {
		if block.Type != "CERTIFICATE" {
			continue
		}
		if _, err := x509.ParseCertificate(block.Bytes); err != nil {
			return fail(fmt.Sprintf("%d 번째 인증서를 해석하지 못했다", n+1))
		}
		n++
	}
	if n == 0 {
		return fail("PEM 인증서가 하나도 없다")
	}
	return nil
}

func required(getenv func(string) string, key string) (string, error) {
	v := strings.TrimSpace(getenv(key))
	if v == "" {
		return "", fmt.Errorf("필수 환경 변수 %s 가 비어 있다", key)
	}
	return v, nil
}

func withDefault(getenv func(string) string, key, fallback string) string {
	if v := strings.TrimSpace(getenv(key)); v != "" {
		return v
	}
	return fallback
}

func positiveInt(getenv func(string) string, key string, fallback int) (int, error) {
	raw := strings.TrimSpace(getenv(key))
	if raw == "" {
		return fallback, nil
	}
	v, err := strconv.Atoi(raw)
	if err != nil || v <= 0 {
		return 0, fmt.Errorf("%s(%q)는 양의 정수여야 한다", key, raw)
	}
	return v, nil
}
