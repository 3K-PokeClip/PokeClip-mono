package main

// 되감기 발행 몫의 조립 시험(체크리스트 474 B-1 4 · 5 · 7b 검수 cc D · 판단 J69 · J75 · J77). run 이 업로더 기동 앞에서 부르는
// assemblePublishing 을 그대로 부르고, 종료는 run 이 defer 로 부르는 rewindLoop.stop 이다. 부팅 버킷 확인은 루프백 가짜 S3
// (HeadBucket 만 흉내)에 실제 SDK 전송(서명 · TLS)으로 닿는다 — 시험 서버의 인증서는 AWS_CA_BUNDLE 로 믿게 해 운영 코드에
// 시험용 이음매를 두지 않는다. 발행자 풀은 닿지 않는 DSN 이다(pgxpool.New 는 접속하지 않는다 — PG 0). PG 반쪽은
// publish_assembly_pg_test.go 다.

import (
	"bytes"
	"context"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/s3errbody"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// assemblyBucket 은 조립 시험의 버킷이다 — 가짜 S3 는 이 버킷의 HEAD 만 받는다.
const assemblyBucket = "pokeclip-assembly-test"

// 시험용 정적 자격증명이다. 조립이 남긴 로그 어디에도 실리면 안 된다(G14 — provider 이름만).
const (
	assemblyAccessKey    = "AKIDASSEMBLYTESTKEY"
	assemblySecretKey    = "assembly-secret-must-not-be-logged"
	assemblySessionToken = "assembly-token-must-not-be-logged"
)

// fakeS3 는 HeadBucket 만 흉내 내는 루프백 서버다 — status 가 HEAD /{버킷} 의 응답 코드이고 heads 가 받은 HEAD 수다.
type fakeS3 struct {
	srv    *httptest.Server
	status atomic.Int32
	heads  atomic.Int32
}

// newFakeS3 는 status 로 답하는 TLS 가짜 S3 를 띄우고 SDK 기본 HTTP 클라이언트가 그 인증서를 믿게 한다(AWS_CA_BUNDLE).
func newFakeS3(t *testing.T, status int) *fakeS3 {
	t.Helper()
	f := startFakeS3(t, status, httptest.NewTLSServer)
	trustFakeS3Cert(t, f.srv)
	return f
}

// trustFakeS3Cert 는 SDK 기본 HTTP 클라이언트가 TLS 서버 srv 의 인증서를 믿게 한다(AWS_CA_BUNDLE).
func trustFakeS3Cert(t *testing.T, srv *httptest.Server) {
	t.Helper()
	ca := filepath.Join(t.TempDir(), "fake-s3-ca.pem")
	block := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: srv.Certificate().Raw})
	if err := os.WriteFile(ca, block, 0o600); err != nil {
		t.Fatalf("가짜 S3 인증서 쓰기 실패: %v", err)
	}
	t.Setenv("AWS_CA_BUNDLE", ca)
}

// startFakeS3 는 serve(httptest.NewServer 면 평문 · NewTLSServer 면 TLS)로 가짜 S3 를 띄운다.
func startFakeS3(t *testing.T, status int, serve func(http.Handler) *httptest.Server) *fakeS3 {
	t.Helper()
	f := &fakeS3{}
	f.status.Store(int32(status))
	f.srv = serve(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodHead || strings.TrimSuffix(r.URL.Path, "/") != "/"+assemblyBucket {
			w.WriteHeader(http.StatusNotImplemented)
			return
		}
		f.heads.Add(1)
		w.WriteHeader(int(f.status.Load()))
	}))
	t.Cleanup(f.srv.Close)
	return f
}

// assemblyCredentialsEnv 는 SDK 가 시험용 정적 자격증명만 보게 한다 — 자격증명 파일 · IMDS · 프로필은 noCredentialsEnv 가 막는다.
func assemblyCredentialsEnv(t *testing.T) {
	t.Helper()
	noCredentialsEnv(t)
	t.Setenv("AWS_ACCESS_KEY_ID", assemblyAccessKey)
	t.Setenv("AWS_SECRET_ACCESS_KEY", assemblySecretKey)
	t.Setenv("AWS_SESSION_TOKEN", assemblySessionToken)
}

// assemblyCfg 는 조립 시험의 설정이다. 조립이 루프 보조 타입으로 옮겨 담는 주기 셋(감시 · 보류 틱 · 재접속 창)은 운영 기본값과
// 다른 값이다 — 옮겨 담지 않고 상수를 쓰면 드러난다.
func assemblyCfg(t *testing.T, endpoint string) config.Config {
	t.Helper()
	dir := t.TempDir()
	idx := indexer.DefaultOptions()
	idx.SegmentRoot = dir
	idx.HoldTick = 250 * time.Millisecond
	idx.Settle = recording.SettleOptions{
		PollInterval: 5 * time.Millisecond, SettleWait: 10 * time.Millisecond, MaxSettle: 60 * time.Millisecond,
	}
	up := upload.DefaultOptions(nil, dir)
	up.SweepEvery = time.Hour // 스위퍼가 끼어들지 않게 한다
	return config.Config{
		PGDSN:       "postgres://user@127.0.0.1:1/none", // 닿지 않는다 — 접속하는 문장은 접속 단계에서 실패한다
		SegmentRoot: dir,
		Indexer:     idx,
		Watcher:     recording.DefaultWatcherOptions(dir, nil),
		S3: upload.S3Options{
			Bucket: assemblyBucket, Region: "ap-northeast-2", Endpoint: endpoint, ForcePathStyle: true,
		},
		Upload:              up,
		RewindPublicBaseURL: "https://media.example.com/base",
		RewindWatchEvery:    17 * time.Second,
		ReconnectWindow:     123 * time.Second,
	}
}

// assembly 는 조립 시험 한 판이다 — run 이 조립 앞에 세워 두는 것(인덱서 · 기동 전 업로더 · 4.1 몫만 든 루프 보조 타입)이다.
type assembly struct {
	logs   *logCapture
	ix     *indexer.Indexer
	up     *upload.Uploader
	rewind *rewindLoop
}

// newAssembly 는 cfg 로 조립 앞 형상을 세운다 — 인덱서의 장부는 store 다.
func newAssembly(t *testing.T, cfg config.Config, store index.Store) *assembly {
	t.Helper()
	a := &assembly{logs: &logCapture{}}
	log := slog.New(a.logs)
	a.ix = indexer.New(store, func(string) (int64, error) { return 4000, nil }, noopAdopter{}, nil, nil, cfg.Indexer, log)
	root, err := os.OpenRoot(cfg.SegmentRoot)
	if err != nil {
		t.Fatalf("세그먼트 루트 열기 실패: %v", err)
	}
	t.Cleanup(func() { _ = root.Close() })
	if a.up, err = newUploader(t.Context(), cfg, root, &stubStore{}, log); err != nil {
		t.Fatalf("업로더 조립 실패: %v", err)
	}
	t.Cleanup(a.up.Shutdown)
	a.rewind = &rewindLoop{ix: a.ix, root: cfg.SegmentRoot, log: log, endLive: (&fakeEndLive{}).endLive}
	return a
}

// assemble 은 run 처럼 발행 몫을 조립한다. 돌려받은 발행자 풀은 시험이 끝나면 닫는다.
func (a *assembly) assemble(t *testing.T, cfg config.Config) *pgxpool.Pool {
	t.Helper()
	pool := assemblePublishing(t.Context(), cfg, a.up, a.rewind)
	if pool != nil {
		t.Cleanup(pool.Close)
	}
	return pool
}

// assembleOrFatal 은 조립이 서야 하는 시험의 assemble 이다.
func (a *assembly) assembleOrFatal(t *testing.T, cfg config.Config) *pgxpool.Pool {
	t.Helper()
	pool := a.assemble(t, cfg)
	if pool == nil {
		t.Fatalf("assemblePublishing = nil — 조립이 강등됐다: %v", a.logs.attrs("rewind_publish_degraded"))
	}
	return pool
}

// assertDegraded 는 발행 몫이 통째로 강등된 형상을 본다(판단 J77 · 7b 검수 cc C) — 풀 · 캐시 · 차선 · Dirty 가 없고 ERROR 한
// 줄이며 켜짐 줄은 없다. 업로더는 그대로다(강등은 발행 몫만이다 — 통지 채널이 살아 있다). 강등 줄에도 자격증명이 실리지 않는다
// (7c 보안 참고 2 — 강등 갈래 전부가 회귀 그물이 된다).
func assertDegraded(t *testing.T, a *assembly, pool *pgxpool.Pool) {
	t.Helper()
	r := a.rewind
	if pool != nil || r.cache != nil || r.publishing() || r.done() != nil {
		t.Errorf("강등 뒤 풀 %v · 캐시 %v · 발행 몫 %v · 완료 채널 %v, want 모두 없음",
			pool != nil, r.cache != nil, r.publishing(), r.done() != nil)
	}
	if a.up.Dirty() != nil {
		t.Error("강등인데 업로더에 Dirty 집합이 붙었다 — 비우는 소비자 없이 원소가 쌓인다")
	}
	if a.up.Results() == nil {
		t.Error("업로더 통지 채널이 없다 — 강등이 업로더까지 번졌다")
	}
	if n := a.logs.countLevel(slog.LevelError, "rewind_publish_degraded"); n != 1 {
		t.Errorf("ERROR rewind_publish_degraded = %d줄, want 1", n)
	}
	if n := a.logs.count("rewind_publish_started"); n != 0 {
		t.Errorf("rewind_publish_started = %d줄, want 0", n)
	}
	assertNoSecretsLogged(t, a.logs)
}

// assertNoSecretsLogged 는 남은 로그의 어느 속성에도 시험용 자격증명 값이 없는지 본다(G14 — provider 이름만 싣는다).
func assertNoSecretsLogged(t *testing.T, logs *logCapture) {
	t.Helper()
	logs.mu.Lock()
	defer logs.mu.Unlock()
	for _, rec := range logs.records {
		rec.Attrs(func(a slog.Attr) bool {
			v := a.Value.String()
			for _, secret := range []string{assemblyAccessKey, assemblySecretKey, assemblySessionToken} {
				if strings.Contains(v, secret) {
					t.Errorf("로그 %s 의 %s 에 자격증명이 실렸다", rec.Message, a.Key)
				}
			}
			return true
		})
	}
}

// 판단 J69 — S3_BUCKET 이 비면 발행 몫을 조립하지 않는다: 풀 · 캐시 · 차선 · Dirty · forgetSession 이 없고 완료 case 는 nil
// 채널이다(팀 공용 compose 의 기본 형상 — 루프는 4.1 종료 전이만 돈다). INFO 한 줄이고, 종료는 차선 ctx 를 끊기만 한다.
func TestAssemblePublishingWithoutBucketBuildsNothing(t *testing.T) {
	noCredentialsEnv(t)
	cfg := assemblyCfg(t, "")
	// 엔드포인트는 닫힌 포트다 — 조립이 버킷 검사를 건너뛰는 변이에서도 실제 S3 로 나가지 않게 한다.
	cfg.S3 = upload.S3Options{Region: "ap-northeast-2", Endpoint: "https://127.0.0.1:1"}
	a := newAssembly(t, cfg, &fakeStore{})

	pool := a.assemble(t, cfg)

	r := a.rewind
	if pool != nil || r.cache != nil || r.publishing() || r.done() != nil {
		t.Errorf("assemblePublishing = 풀 %v · 캐시 %v · 발행 몫 %v · 완료 채널 %v, want 모두 없음",
			pool != nil, r.cache != nil, r.publishing(), r.done() != nil)
	}
	if r.peekDirty != nil || r.forgetSession != nil || r.expedite != nil {
		t.Error("발행 몫이 없는데 Dirty · 업로더 손잡이가 붙었다")
	}
	if n := a.logs.countLevel(slog.LevelInfo, "rewind_publish_disabled"); n != 1 {
		t.Errorf("INFO rewind_publish_disabled = %d줄, want 1", n)
	}
	if n := a.logs.count("rewind_publish_started") + a.logs.count("rewind_publish_degraded"); n != 0 {
		t.Errorf("켜짐 · 강등 줄 = %d, want 0", n)
	}
	laneCtx, cancelLanes := context.WithCancel(t.Context())
	r.stop(t.Context(), cancelLanes)
	if failed := a.logs.count("rewind_fence_release_failed"); laneCtx.Err() == nil || failed != 0 {
		t.Errorf("종료 = 차선 ctx 끊김 %v · 반납 실패 %d줄, want 끊김 · 0", laneCtx.Err() != nil, failed)
	}
}

// 판단 J77 ⑵ — S3_ENDPOINT 가 http 면 발행 몫만 강등한다(설정은 업로더 · MinIO 리그를 위해 http 를 받는다). 엔드포인트가 평문으로
// 버킷 확인에 답하는 서버여도 요청을 보내지 않는다.
func TestAssemblePublishingDegradesOnPlainHTTPEndpoint(t *testing.T) {
	assemblyCredentialsEnv(t)
	s3 := startFakeS3(t, http.StatusOK, httptest.NewServer)
	cfg := assemblyCfg(t, s3.srv.URL)
	a := newAssembly(t, cfg, &fakeStore{})

	pool := a.assemble(t, cfg)

	assertDegraded(t, a, pool)
	if n := s3.heads.Load(); n != 0 {
		t.Errorf("평문 엔드포인트로 HEAD %d번, want 0", n)
	}
}

// 7c 검수 C7c-1 · 보안 L-1 — https 검사는 설정값이 아니라 조립된 클라이언트의 실효 엔드포인트를 본다. S3_ENDPOINT 가 비어도
// SDK 표준 설정(AWS_ENDPOINT_URL_S3 등)이 엔드포인트를 고른다. 평문이면 버킷 확인을 보내지 않고 강등한다. userinfo 가 든 값도
// 강등하고 원문을 로그에 싣지 않는다(S3_ENDPOINT · 7a B-9 규칙 — 행의 userinfo 는 시험용 자격증명이라 assertNoSecretsLogged 가
// 잡는다). 켜짐 · 강등 줄의 endpoint 는 userinfo 를 뗀 실효 값이다.
func TestAssemblePublishingChecksEffectiveEndpoint(t *testing.T) {
	for _, c := range []struct {
		name          string
		tls           bool
		userinfo      bool
		wantAssembled bool
		wantHeads     int32
	}{
		{name: "평문", wantHeads: 0},
		{name: "https_userinfo", tls: true, userinfo: true, wantHeads: 0},
		{name: "https", tls: true, wantAssembled: true, wantHeads: 1},
	} {
		t.Run(c.name, func(t *testing.T) {
			assemblyCredentialsEnv(t)
			s3 := startFakeS3(t, http.StatusOK, httptest.NewServer)
			if c.tls {
				s3 = newFakeS3(t, http.StatusOK)
			}
			endpoint := s3.srv.URL
			if c.userinfo {
				endpoint = strings.Replace(endpoint, "https://", "https://"+assemblyAccessKey+":"+assemblySecretKey+"@", 1)
			}
			t.Setenv("AWS_ENDPOINT_URL_S3", endpoint)
			cfg := assemblyCfg(t, "")
			a := newAssembly(t, cfg, &fakeStore{})

			pool := a.assemble(t, cfg)

			msg := "rewind_publish_degraded"
			if c.wantAssembled {
				msg = "rewind_publish_started"
				if pool == nil {
					t.Fatalf("assemblePublishing = nil — 조립이 강등됐다: %v", a.logs.attrs("rewind_publish_degraded"))
				}
			} else {
				assertDegraded(t, a, pool)
			}
			if got, _ := a.logs.attrs(msg)["endpoint"].(string); got != s3.srv.URL {
				t.Errorf("%s endpoint = %q, want %q", msg, got, s3.srv.URL)
			}
			if n := s3.heads.Load(); n != c.wantHeads {
				t.Errorf("HEAD %d번, want %d", n, c.wantHeads)
			}
			assertNoSecretsLogged(t, a.logs)
		})
	}
}

// 종결 라운드 1 CL1-1 · 보안 L-1 — `//` 가 빠진 꼴(opaque)과 빈 호스트는 url.Parse 가 User 를 비워 userinfo 검사를 비켜
// 간다. https 검사는 호스트가 없으면 거부하고, 오류 문구와 로그 칸(endpointForLog)에 원문을 싣지 않는다 — 원문의 `pw` 가
// 어디에도 남지 않는다. 빈 값(SDK 기본)은 그대로 통과 · 빈 칸이다.
func TestRequireHTTPSRejectsHostlessEndpoint(t *testing.T) {
	for _, raw := range []string{"https:user:pw@host", "https:host", "https:///x"} {
		t.Run(raw, func(t *testing.T) {
			err := requireHTTPS(raw)

			if err == nil {
				t.Fatalf("requireHTTPS(%q) = nil, want 거부(호스트 없음)", raw)
			}
			if strings.Contains(err.Error(), "pw") || strings.Contains(err.Error(), raw) {
				t.Errorf("requireHTTPS(%q) 오류 = %q, want 원문 없음", raw, err)
			}
			if got := endpointForLog(raw); strings.Contains(got, "pw") || strings.Contains(got, "host") {
				t.Errorf("endpointForLog(%q) = %q, want 원문 대신 표식", raw, got)
			}
		})
	}
	if err, got := requireHTTPS(""), endpointForLog(""); err != nil || got != "" {
		t.Errorf("빈 엔드포인트 = (%v, %q), want (nil, \"\") — SDK 기본이다", err, got)
	}
}

// 판단 J77 ⑶ — 부팅 버킷 확인(HeadBucket 한 번 · 시한 PublishTimeout)이 실패하면 발행 몫만 강등한다. 다시 시도하지 않는다(재기동이
// 회복한다) — SDK 가 재시도하는 503 에도 HEAD 는 한 번이다. 자격증명을 얻지 못하면 HEAD 를 보내지 않는다.
func TestAssemblePublishingDegradesWhenBucketCheckFails(t *testing.T) {
	for _, c := range []struct {
		name      string
		status    int
		noCreds   bool
		wantHeads int32
	}{
		{name: "버킷_없음_404", status: http.StatusNotFound, wantHeads: 1},
		{name: "권한_없음_403", status: http.StatusForbidden, wantHeads: 1},
		{name: "일시_장애_503", status: http.StatusServiceUnavailable, wantHeads: 1},
		{name: "자격증명_없음", status: http.StatusOK, noCreds: true, wantHeads: 0},
	} {
		t.Run(c.name, func(t *testing.T) {
			assemblyCredentialsEnv(t)
			if c.noCreds {
				noCredentialsEnv(t)
			}
			s3 := newFakeS3(t, c.status)
			cfg := assemblyCfg(t, s3.srv.URL)
			a := newAssembly(t, cfg, &fakeStore{})

			pool := a.assemble(t, cfg)

			assertDegraded(t, a, pool)
			if n := s3.heads.Load(); n != c.wantHeads {
				t.Errorf("HEAD %d번, want %d", n, c.wantHeads)
			}
		})
	}
}

// 정상 조립(체크리스트 474 B-1 4 · 7b 검수 cc B · 보안 참고 2 · 4 · 5) — 발행 몫이 서고 루프 보조 타입의 칸이 모두 찬다.
//
//	업로더  Dirty 집합이 붙었다(AttachDirty 를 Start 뒤에 부르면 조용히 무동작이다 — 보안 참고 5)
//	작업    네 칸이 모두 있다(하나라도 nil 이면 차선 고루틴이 패닉하고 recover 가 없다 — 보안 참고 2)
//	주기    설정에서 옮겨 담는다(watchEvery 0 이면 감시 · 부팅 목록 · forgetIdle · 재요구 30초 제한이 함께 꺼진다 — cc B)
//	로그    INFO rewind_publish_started 에 베이스 URL · 버킷 · 리전 · 엔드포인트 · provider(보안 참고 4) · 자격증명 0(G14)
func TestAssemblePublishingWiresPublishingShare(t *testing.T) {
	assemblyCredentialsEnv(t)
	s3 := newFakeS3(t, http.StatusOK)
	cfg := assemblyCfg(t, s3.srv.URL)
	a := newAssembly(t, cfg, &fakeStore{})

	a.assembleOrFatal(t, cfg)

	r := a.rewind
	if r.cache == nil || !r.publishing() || r.done() == nil || a.up.Dirty() == nil {
		t.Fatalf("발행 몫 = 캐시 %v · 발행 몫 %v · 완료 채널 %v · Dirty %v, want 모두 있음",
			r.cache != nil, r.publishing(), r.done() != nil, a.up.Dirty() != nil)
	}
	if w := r.work; w.tick == nil || w.renew == nil || w.load == nil || w.watch == nil {
		t.Error("작업 몸체에 빈 칸이 있다 — 차선 고루틴이 패닉한다")
	}
	if r.peekDirty == nil || r.ackDirty == nil || r.expedite == nil || r.forgetSession == nil {
		t.Error("Dirty · 업로더 손잡이에 빈 칸이 있다")
	}
	if r.watchEvery != 17*time.Second || r.holdEvery != 250*time.Millisecond ||
		r.reconnectWindow != 123*time.Second || r.lazyRenewAfter != 3500*time.Millisecond {
		t.Errorf("주기 = 감시 %v · 보류 틱 %v · 재접속 창 %v · lazy %v, want 17s · 250ms · 2m3s · 3.5s",
			r.watchEvery, r.holdEvery, r.reconnectWindow, r.lazyRenewAfter)
	}
	at := a.logs.attrs("rewind_publish_started")
	for k, want := range map[string]string{
		"base_url": "https://media.example.com/base", "bucket": assemblyBucket,
		"region": "ap-northeast-2", "endpoint": s3.srv.URL,
	} {
		if got, _ := at[k].(string); got != want {
			t.Errorf("rewind_publish_started %s = %q, want %q", k, got, want)
		}
	}
	if p, _ := at["provider"].(string); p == "" {
		t.Error("rewind_publish_started 에 provider 이름이 없다")
	}
	if n := s3.heads.Load(); n != 1 {
		t.Errorf("부팅 버킷 확인 HEAD %d번, want 1", n)
	}
	if n := a.logs.count("rewind_publish_degraded") + a.logs.count("rewind_publish_disabled"); n != 0 {
		t.Errorf("강등 · 비활성 줄 = %d, want 0", n)
	}
	assertNoSecretsLogged(t, a.logs)
}

// 같은 캐시가 두 자리에 든다(7a 검수 C7a-13) — 인덱서(SetRewindCache)와 루프 보조 타입이다. 인덱서가 장부에 넣은 회차 개시 행의
// push 가 루프 보조 타입의 캐시에 닿는다: 뷰 없는 스트림이라 캐시가 스스로 적재를 연다.
func TestAssemblePublishingSharesCacheWithIndexer(t *testing.T) {
	assemblyCredentialsEnv(t)
	s3 := newFakeS3(t, http.StatusOK)
	cfg := assemblyCfg(t, s3.srv.URL)
	a := newAssembly(t, cfg, &openingStore{})
	a.assembleOrFatal(t, cfg)

	handleOldSegment(t, a.ix, cfg.SegmentRoot, "s-open")

	if !a.rewind.cache.Loading("s-open") {
		t.Error("인덱서의 회차 개시 push 가 루프 보조 타입의 캐시에 닿지 않았다 — 두 자리의 캐시가 다르다")
	}
}

// openingStore 는 넣는 행마다 회차 개시 결과를 돌려주는 장부다.
type openingStore struct{ fakeStore }

func (s *openingStore) Insert(ctx context.Context, r index.Record, seed index.Seed, src index.SessionSource) (index.InsertOutcome, index.SeedResult, error) {
	out, _, err := s.fakeStore.Insert(ctx, r, seed, src)
	return out, index.SeedResult{SessionOpened: true, SessionID: "S-open"}, err
}

// handleOldSegment 는 한 시간 전에 시작해 끝난 조각 파일 하나를 만들어 인덱서에 넣는다(워처 FIFO 가 하는 일).
func handleOldSegment(t *testing.T, ix *indexer.Indexer, root, streamID string) {
	t.Helper()
	dir := filepath.Join(root, streamID)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("디렉터리 생성 실패: %v", err)
	}
	old := time.Now().Add(-time.Hour)
	w := old.UTC()
	path := filepath.Join(dir, fmt.Sprintf("%s-%06d.mp4", w.Format("2006-01-02_15-04-05"), w.Nanosecond()/1000))
	if err := os.WriteFile(path, make([]byte, 100), 0o600); err != nil {
		t.Fatalf("조각 파일 생성 실패: %v", err)
	}
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatalf("mtime 조정 실패: %v", err)
	}
	seg, err := recording.ParseSegmentPath(root, path)
	if err != nil {
		t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = recording.ReasonNextFile
	if err := ix.Handle(t.Context(), seg); err != nil {
		t.Fatalf("Handle 실패: %v", err)
	}
}

// 판단 J75 — writer 토큰은 crypto/rand 16바이트의 16진 32자이고 조립마다 새로 만든다. 호스트 · 파드 이름을 쓰지 않는다 — 재기동한
// 같은 파드가 같은 토큰을 쓰면 fence 가 두 프로세스를 가르지 못한다.
func TestAssemblePublishingMintsFreshWriterToken(t *testing.T) {
	assemblyCredentialsEnv(t)
	s3 := newFakeS3(t, http.StatusOK)
	cfg := assemblyCfg(t, s3.srv.URL)
	host, err := os.Hostname()
	if err != nil {
		t.Fatalf("os.Hostname 실패: %v", err)
	}
	hex32 := regexp.MustCompile(`^[0-9a-f]{32}$`)
	var tokens []string
	for range 2 {
		a := newAssembly(t, cfg, &fakeStore{})
		a.assembleOrFatal(t, cfg)
		tok, _ := a.logs.attrs("rewind_publish_started")["writer"].(string)
		if !hex32.MatchString(tok) || strings.Contains(tok, host) {
			t.Errorf("writer 토큰 %q, want 16진 32자 · 호스트 이름 %q 없음", tok, host)
		}
		tokens = append(tokens, tok)
	}
	if tokens[0] == tokens[1] {
		t.Errorf("두 조립의 writer 토큰이 같다(%q) — 부팅마다 새로 만들어야 한다", tokens[0])
	}
}

// 반납 오류는 버리지 않는다(7b 검수 cc D) — 루프가 돌아온 뒤의 종료(rewindLoop.stop)가 fence 반납에 실패하면 ERROR 한 줄이다.
// 발행자 풀이 닿지 않는 DSN 이라 반납 문장이 접속 단계에서 실패한다. 차선 ctx 는 끊긴다.
func TestRewindStopLogsReleaseFailure(t *testing.T) {
	assemblyCredentialsEnv(t)
	s3 := newFakeS3(t, http.StatusOK)
	cfg := assemblyCfg(t, s3.srv.URL)
	a := newAssembly(t, cfg, &fakeStore{})
	a.assembleOrFatal(t, cfg)
	laneCtx, cancelLanes := context.WithCancel(t.Context())
	runCtx, stop := context.WithCancel(t.Context())
	stop() // 종료 신호로 끝난 run ctx

	a.rewind.stop(runCtx, cancelLanes)

	if laneCtx.Err() == nil {
		t.Error("차선 ctx 가 끊기지 않았다")
	}
	if n := a.logs.countLevel(slog.LevelError, "rewind_fence_release_failed"); n != 1 {
		t.Fatalf("ERROR rewind_fence_release_failed = %d줄, want 1", n)
	}
	if at := a.logs.attrs("rewind_fence_release_failed"); at["err"] == nil {
		t.Errorf("반납 실패 줄에 err 가 없다: %v", at)
	}
}

// 발행 조립점의 S3 클라이언트(newPublishS3Client)는 오류 본문을 상한까지만 읽는다(D3 · POK-384). 가짜 S3 는 AWS_CA_BUNDLE 로 믿는
// TLS 서버이고 PUT 에 상한(s3errbody.MaxBytes)의 64배인 412 본문으로 답한다 — 설정 적재의 CA 묶음 해석이 그대로 돌고, 오류는
// ErrTooLarge 를 싣고, 상태 코드 412 가 남고, 요청은 하나다. newPublisher 가 newPublishS3Client 를 쓰는지는 이 시험이 고정하지
// 못한다(부팅 요청은 본문 없는 HEAD 뿐) — 코드 리뷰 몫이다.
func TestPublishS3ClientCapsErrorBody(t *testing.T) {
	assemblyCredentialsEnv(t)
	var puts atomic.Int32
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPut {
			w.WriteHeader(http.StatusNotImplemented)
			return
		}
		puts.Add(1)
		_, _ = io.Copy(io.Discard, r.Body)
		respondHuge(w, http.StatusPreconditionFailed)
	}))
	t.Cleanup(srv.Close)
	trustFakeS3Cert(t, srv)
	cfg := assemblyCfg(t, srv.URL)

	_, client, err := newPublishS3Client(t.Context(), cfg)
	if err != nil {
		t.Fatalf("newPublishS3Client 실패: %v", err)
	}
	_, err = client.PutObject(t.Context(), &s3.PutObjectInput{
		Bucket: aws.String(assemblyBucket), Key: aws.String("dvr/demo/sess-1/index.m3u8"), Body: strings.NewReader("x"),
	})

	if !errors.Is(err, s3errbody.ErrTooLarge) {
		t.Errorf("PutObject err = %v, want ErrTooLarge", err)
	}
	var respErr *awshttp.ResponseError
	if !errors.As(err, &respErr) || respErr.HTTPStatusCode() != http.StatusPreconditionFailed {
		t.Errorf("PutObject err = %v, want 상태 412 인 awshttp.ResponseError", err)
	}
	if n := puts.Load(); n != 1 {
		t.Errorf("PUT %d번, want 1", n)
	}
}

// hugeChunk 는 거대 오류 본문 흉내 서버가 되풀이해 쓰는 32KiB 조각이다 — 한 번만 할당한다.
var hugeChunk = bytes.Repeat([]byte("x"), 32<<10)

// respondHuge 는 status 와 hugeChunk 를 128번 이은 4MiB 본문(상한 s3errbody.MaxBytes 의 64배)으로 답한다. 클라이언트가 먼저 닫으면
// 쓰기를 멈춘다.
func respondHuge(w http.ResponseWriter, status int) {
	w.Header().Set("Content-Type", "application/xml")
	w.WriteHeader(status)
	for range 128 {
		if _, err := w.Write(hugeChunk); err != nil {
			return
		}
	}
}
