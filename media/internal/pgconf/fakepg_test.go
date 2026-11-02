package pgconf

// 시험 안의 최소 PostgreSQL 서버와 자체 서명 인증서 도우미. 실제 PG 없이 BeforeConnect 배선 ·
// TLS 검증 · 비밀 누설을 잰다(계획 4-2 · 4-3). 모든 값은 합성이다.

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgproto3"
)

// fakePG 는 평문 비밀번호 인증만 하는 가짜 서버다. 받은 비밀번호를 도착 순서대로 기록하고,
// 질의에는 빈 응답을 준다(Ping 용). tlsConf 가 nil 이면 SSLRequest 에 'N' 으로 답한다.
type fakePG struct {
	t         *testing.T
	ln        net.Listener
	tlsConf   *tls.Config
	accept    func(password string) bool
	failQuery bool // 참이면 인증 뒤 질의에 오류로 답한다(연결은 되지만 Ping 이 실패하는 갈래)

	mu        sync.Mutex
	passwords []string
	conns     []net.Conn
	wg        sync.WaitGroup
}

type fakePGOptions struct {
	tlsConf   *tls.Config
	accept    func(password string) bool // nil = 모두 받음
	failQuery bool
}

func startFakePG(t *testing.T, o fakePGOptions) *fakePG {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("가짜 PG 수신 실패: %v", err)
	}
	f := &fakePG{t: t, ln: ln, tlsConf: o.tlsConf, accept: o.accept, failQuery: o.failQuery}
	if f.accept == nil {
		f.accept = func(string) bool { return true }
	}
	f.wg.Add(1)
	go f.serve()
	t.Cleanup(f.stop)
	return f
}

func (f *fakePG) port() int { return f.ln.Addr().(*net.TCPAddr).Port }

func (f *fakePG) portString() string { return strconv.Itoa(f.port()) }

// received 는 지금까지 받은 비밀번호의 사본이다.
func (f *fakePG) received() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.passwords...)
}

func (f *fakePG) stop() {
	f.ln.Close()
	f.mu.Lock()
	for _, c := range f.conns {
		c.Close()
	}
	f.mu.Unlock()
	f.wg.Wait()
}

func (f *fakePG) serve() {
	defer f.wg.Done()
	for {
		c, err := f.ln.Accept()
		if err != nil {
			return
		}
		f.mu.Lock()
		f.conns = append(f.conns, c)
		f.mu.Unlock()
		f.wg.Add(1)
		go func() {
			defer f.wg.Done()
			defer c.Close()
			f.handle(c)
		}()
	}
}

func (f *fakePG) handle(c net.Conn) {
	be := pgproto3.NewBackend(c, c)
	msg, err := be.ReceiveStartupMessage()
	if err != nil {
		return
	}
	if _, ok := msg.(*pgproto3.SSLRequest); ok {
		if f.tlsConf == nil {
			if _, err := c.Write([]byte{'N'}); err != nil {
				return
			}
		} else {
			if _, err := c.Write([]byte{'S'}); err != nil {
				return
			}
			tc := tls.Server(c, f.tlsConf)
			if err := tc.Handshake(); err != nil {
				return
			}
			f.mu.Lock()
			f.conns = append(f.conns, tc)
			f.mu.Unlock()
			be = pgproto3.NewBackend(tc, tc)
		}
		if msg, err = be.ReceiveStartupMessage(); err != nil {
			return
		}
	}
	if _, ok := msg.(*pgproto3.StartupMessage); !ok {
		return
	}
	if !f.authenticate(be) {
		return
	}
	f.answerQueries(be)
}

// authenticate 는 평문 비밀번호를 받아 기록하고 accept 판정대로 Ok 또는 28P01 로 답한다.
func (f *fakePG) authenticate(be *pgproto3.Backend) bool {
	be.Send(&pgproto3.AuthenticationCleartextPassword{})
	if err := be.Flush(); err != nil {
		return false
	}
	if err := be.SetAuthType(pgproto3.AuthTypeCleartextPassword); err != nil {
		return false
	}
	m, err := be.Receive()
	if err != nil {
		return false
	}
	pw, ok := m.(*pgproto3.PasswordMessage)
	if !ok {
		return false
	}
	f.mu.Lock()
	f.passwords = append(f.passwords, pw.Password)
	f.mu.Unlock()
	if !f.accept(pw.Password) {
		be.Send(&pgproto3.ErrorResponse{Severity: "FATAL", Code: "28P01", Message: "password authentication failed"})
		_ = be.Flush()
		return false
	}
	be.Send(&pgproto3.AuthenticationOk{})
	be.Send(&pgproto3.ReadyForQuery{TxStatus: 'I'})
	return be.Flush() == nil
}

func (f *fakePG) answerQueries(be *pgproto3.Backend) {
	for {
		m, err := be.Receive()
		if err != nil {
			return
		}
		switch m.(type) {
		case *pgproto3.Query:
			if f.failQuery {
				be.Send(&pgproto3.ErrorResponse{Severity: "ERROR", Code: "XX000", Message: "synthetic query failure"})
			} else {
				be.Send(&pgproto3.EmptyQueryResponse{})
			}
			be.Send(&pgproto3.ReadyForQuery{TxStatus: 'I'})
			if err := be.Flush(); err != nil {
				return
			}
		case *pgproto3.Terminate:
			return
		}
	}
}

// testCA 는 시험이 만든 자체 서명 CA 다.
type testCA struct {
	cert *x509.Certificate
	key  *ecdsa.PrivateKey
	pem  []byte
}

func newTestCA(t *testing.T, name string) testCA {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: name},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  true,
		KeyUsage:              x509.KeyUsageCertSign,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return testCA{cert: cert, key: key, pem: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})}
}

// serverTLS 는 이 CA 가 서명한 서버 인증서(SAN = dnsNames 만, IP SAN 없음)로 TLS 설정을 만든다.
func (ca testCA) serverTLS(t *testing.T, dnsNames ...string) *tls.Config {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: dnsNames[0]},
		DNSNames:     dnsNames,
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca.cert, &key.PublicKey, ca.key)
	if err != nil {
		t.Fatal(err)
	}
	return &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}, MinVersion: tls.VersionTLS12}
}

// writeTestFile 은 dir 아래에 data 를 담은 파일을 만들고 절대 경로를 돌려준다.
func writeTestFile(t *testing.T, dir, name string, data []byte) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatalf("시험 파일 생성 실패: %v", err)
	}
	return path
}
