package upload

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// ③ 사슬 복원(POK-196 PDT 계획 4절 c10b · 설계 r6 2.10)이 쓰는 전체 GET · HEAD 를 잰다. 없는 객체는 일시 실패가 아니라
// ErrObjectNotFound 다 — 복원은 그것으로 「원천 부재」 와 「다시 볼 실패」 를 가른다.

// s3Getter 는 httptest 서버 앞의 실구현이다(TLS — 운영과 같은 조건).
func s3Getter(t *testing.T, h http.HandlerFunc) Getter {
	t.Helper()
	putterEnv(t)
	srv := httptest.NewTLSServer(h)
	t.Cleanup(srv.Close)
	p, _, err := newS3Putter(context.Background(), S3Options{
		Bucket: "pokeclip-test", Region: "ap-northeast-2", Endpoint: srv.URL, ForcePathStyle: true,
	}, newLogCapture().logger(), srv.Client())
	if err != nil {
		t.Fatalf("NewS3Putter 실패: %v", err)
	}
	g, ok := p.(Getter)
	if !ok {
		t.Fatalf("S3 실구현 %T 가 Getter 가 아니다", p)
	}
	return g
}

const noSuchKeyXML = `<?xml version="1.0" encoding="UTF-8"?><Error><Code>NoSuchKey</Code><Message>x</Message></Error>`

// GET 은 객체 본문 전체를 돌려주고, HEAD 는 길이와 있음을 돌려준다. 키는 경로 그대로다.
func TestGetterReadsWholeObjectAndHeadsLength(t *testing.T) {
	var paths []string
	g := s3Getter(t, func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.Path)
		w.Header().Set("Content-Length", "5")
		w.WriteHeader(http.StatusOK)
		if r.Method == http.MethodGet {
			_, _ = w.Write([]byte("hello"))
		}
	})

	body, err := g.Get(context.Background(), "dvr/demo/seg_000009.m4s")
	size, found, herr := g.Head(context.Background(), "dvr/demo/seg_000009.m4s")

	if err != nil || string(body) != "hello" {
		t.Errorf("Get = (%q, %v), want (hello, nil)", body, err)
	}
	if herr != nil || !found || size != 5 {
		t.Errorf("Head = (%d, %v, %v), want (5, 참, nil)", size, found, herr)
	}
	want := []string{"GET /pokeclip-test/dvr/demo/seg_000009.m4s", "HEAD /pokeclip-test/dvr/demo/seg_000009.m4s"}
	if strings.Join(paths, "|") != strings.Join(want, "|") {
		t.Errorf("요청 = %v, want %v", paths, want)
	}
}

// 없는 키 — GET 은 ErrObjectNotFound, HEAD 는 (없음, nil)이다. 그 밖의 실패(403 · 500)는 ErrObjectNotFound 가 아니다 — 접근
// 거부를 「원천 부재」 로 읽으면 복원이 행을 지나가 버린다(지나간 행은 다시 만들지 않는다).
func TestGetterSeparatesMissingFromFailure(t *testing.T) {
	status := http.StatusNotFound
	g := s3Getter(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/xml")
		w.WriteHeader(status)
		if r.Method == http.MethodGet && status == http.StatusNotFound {
			_, _ = w.Write([]byte(noSuchKeyXML))
		}
	})

	_, err := g.Get(context.Background(), "dvr/demo/seg_000001.m4s")
	_, found, herr := g.Head(context.Background(), "dvr/demo/seg_000001.m4s")
	if !errors.Is(err, ErrObjectNotFound) || found || herr != nil {
		t.Errorf("404: Get err %v · Head (%v, %v), want ErrObjectNotFound · (없음, nil)", err, found, herr)
	}
	for _, status = range []int{http.StatusForbidden, http.StatusInternalServerError} {
		_, err = g.Get(context.Background(), "dvr/demo/seg_000001.m4s")
		_, _, herr = g.Head(context.Background(), "dvr/demo/seg_000001.m4s")
		if err == nil || errors.Is(err, ErrObjectNotFound) || herr == nil || errors.Is(herr, ErrObjectNotFound) {
			t.Errorf("%d: Get err %v · Head err %v, want 둘 다 「부재」 아닌 오류", status, err, herr)
		}
	}
}

// 객체가 입력 상한(16MiB)을 넘으면 다 읽지 않고 오류다 — 복원은 업로더 전체 동시 1 이라 점유가 워커 하나 몫을 넘지 않는다는
// 전제(설계 r6 2.10 「메모리」)를 지킨다.
func TestGetterRefusesObjectOverInputLimit(t *testing.T) {
	g := s3Getter(t, func(w http.ResponseWriter, r *http.Request) {
		chunk := make([]byte, 1<<20)
		w.WriteHeader(http.StatusOK)
		for range 17 {
			if _, err := w.Write(chunk); err != nil {
				return
			}
		}
	})

	if _, err := g.Get(context.Background(), "dvr/demo/seg_000002.m4s"); err == nil {
		t.Error("17MiB 객체를 받아들였다")
	}
}
