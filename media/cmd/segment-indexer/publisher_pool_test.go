package main

// 되감기 발행자 풀 상한(kty 결정 C2 · POK-196 M6-8 ④).

import (
	"net/http"
	"testing"
)

// C2(kty 결정) — 발행자 풀은 설정 상한(기본 4)으로 연다. 상자 CPU 수를 따르는 pgx 기본값이면 큰 상자에서 교체 창의 두
// 사이드카 합이 역할 연결 상한을 넘는다. 원본(인덱서 풀 설정)은 그대로다.
func TestAssemblePublishingCapsPublisherPool(t *testing.T) {
	assemblyCredentialsEnv(t)
	s3 := newFakeS3(t, http.StatusOK)
	cfg := assemblyCfg(t, s3.srv.URL)
	cfg.RewindPublishPoolMaxConns = 3
	a := newAssembly(t, cfg, &fakeStore{})
	base := a.db.MaxConns

	pool := a.assembleOrFatal(t, cfg)

	if got := pool.Config().MaxConns; got != 3 {
		t.Errorf("발행자 풀 MaxConns = %d, want 3(설정)", got)
	}
	if a.db.MaxConns != base {
		t.Errorf("원본 MaxConns = %d, want %d 그대로", a.db.MaxConns, base)
	}
}
