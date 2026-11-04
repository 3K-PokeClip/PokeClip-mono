package kvsstore

// KVS 운영 구현 시험(POK-525 계획 Ⓒ-5 · 설계 3-3). 실 AWS 를 부르지 않는다 — 시험 HTTP 서버를 끝점으로 주고 SDK 가
// 보내는 요청 꼴(방법 · 경로 · If-Match · 본문 · SigV4A 서명 머리)과 응답 해석을 잰다. KVS 끝점 규칙은 호스트 앞에
// 계정 번호를 붙이므로(<계정>.<끝점>) 시험 클라이언트는 호스트와 무관하게 시험 서버로 잇는다.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/cloudfrontkeyvaluestore"
	"github.com/aws/smithy-go"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds"
)

const (
	testAccount = "123456789012"
	testARN     = "arn:aws:cloudfront::" + testAccount + ":key-value-store/a1b2c3d4-5678-90ab-cdef-EXAMPLE11111"
)

// seenRequest 는 시험 서버가 받은 요청 하나다.
type seenRequest struct {
	method, uri, host, ifMatch, auth, regionSet string
	body                                        []byte
}

// kvsServer 는 KVS 데이터 평면(REST JSON) 흉내다. 요청마다 respond 가 응답을 쓴다.
type kvsServer struct {
	mu   sync.Mutex
	seen []seenRequest
}

func (s *kvsServer) requests() []seenRequest {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]seenRequest(nil), s.seen...)
}

// newStore 는 시험 서버에 붙은 Store 와 그 서버의 기록을 돌려준다.
func newStore(t *testing.T, respond http.HandlerFunc) (*Store, *kvsServer) {
	t.Helper()
	ks := &kvsServer{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		ks.mu.Lock()
		ks.seen = append(ks.seen, seenRequest{
			method: r.Method, uri: r.RequestURI, host: r.Host, ifMatch: r.Header.Get("If-Match"),
			auth: r.Header.Get("Authorization"), regionSet: r.Header.Get("X-Amz-Region-Set"), body: body,
		})
		ks.mu.Unlock()
		respond(w, r)
	}))
	t.Cleanup(srv.Close)
	addr := srv.Listener.Addr().String()
	dial := func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	}
	client := cloudfrontkeyvaluestore.New(cloudfrontkeyvaluestore.Options{
		Region:           "ap-northeast-2",
		Credentials:      credentials.NewStaticCredentialsProvider("AKIDTEST", "secret-test", ""),
		BaseEndpoint:     aws.String(srv.URL),
		HTTPClient:       &http.Client{Transport: &http.Transport{DialContext: dial}},
		RetryMaxAttempts: 1,
	})
	return New(client, testARN), ks
}

// writeJSON 은 상태 · 머리 · JSON 본문을 쓴다.
func writeJSON(w http.ResponseWriter, status int, header map[string]string, body string) {
	for k, v := range header {
		w.Header().Set(k, v)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	io.WriteString(w, body)
}

// writeError 는 REST JSON 오류 응답(오류 종류는 X-Amzn-Errortype 머리)을 쓴다.
func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]string{"X-Amzn-Errortype": code}, fmt.Sprintf(`{"Message":%q}`, message))
}

// storePath 는 SDK 가 ARN 을 경로 성분 하나로 넣은 꼴이다 — `:` · `/` 가 퍼센트 인코딩된다.
const storePath = "/key-value-stores/arn%3Aaws%3Acloudfront%3A%3A" + testAccount +
	"%3Akey-value-store%2Fa1b2c3d4-5678-90ab-cdef-EXAMPLE11111"

// 요청은 SigV4A(전역 지역 집합 *)로 서명되고, 호스트는 ARN 의 계정 번호를 앞에 붙인 끝점이다(SDK 끝점 규칙).
func assertSignedForAccount(t *testing.T, r seenRequest) {
	t.Helper()
	if !strings.HasPrefix(r.auth, "AWS4-ECDSA-P256-SHA256 ") {
		t.Errorf("Authorization = %.40q…, want SigV4A(AWS4-ECDSA-P256-SHA256)", r.auth)
	}
	if r.regionSet != "*" {
		t.Errorf("X-Amz-Region-Set = %q, want *", r.regionSet)
	}
	if !strings.HasPrefix(r.host, testAccount+".") {
		t.Errorf("Host = %q, want %s.<끝점>", r.host, testAccount)
	}
}

func TestDescribeGetsStoreAndReadsETagHeaderAndSize(t *testing.T) {
	st, ks := newStore(t, func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"ETag": "ETAG1"}, fmt.Sprintf(
			`{"KvsARN":%q,"Created":1.7e9,"ItemCount":3,"TotalSizeInBytes":120,"Status":"READY"}`, testARN))
	})

	got, err := st.Describe(context.Background())

	if err != nil {
		t.Fatalf("Describe = %v, want nil", err)
	}
	if want := (edgebounds.Stat{ETag: "ETAG1", Items: 3, Bytes: 120}); got != want {
		t.Errorf("Describe = %+v, want %+v", got, want)
	}
	reqs := ks.requests()
	if len(reqs) != 1 {
		t.Fatalf("요청 %d개, want 1", len(reqs))
	}
	if r := reqs[0]; r.method != http.MethodGet || r.uri != storePath {
		t.Errorf("요청 = %s %s, want GET %s", r.method, r.uri, storePath)
	}
	assertSignedForAccount(t, reqs[0])
}

// 판이 없는 응답은 오류다 — 빈 판을 If-Match 로 보내면 쓰기가 판 결속을 잃는다.
func TestDescribeRejectsResponseWithoutETag(t *testing.T) {
	st, _ := newStore(t, func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, nil, `{"ItemCount":0,"TotalSizeInBytes":0}`)
	})

	if _, err := st.Describe(context.Background()); err == nil {
		t.Error("Describe = nil, want ETag 없음 오류")
	}
}

// updateBody 는 UpdateKeys 요청 본문이다.
type updateBody struct {
	Puts    []struct{ Key, Value string }
	Deletes []struct{ Key string }
}

func TestUpdateSendsIfMatchPutsAndDeletesAndReturnsNewETag(t *testing.T) {
	st, ks := newStore(t, func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"ETag": "ETAG2"}, `{"ItemCount":2,"TotalSizeInBytes":40}`)
	})
	puts := []edgebounds.Entry{{Key: "S1/R1", Value: "17"}, {Key: edgebounds.ReleaseMarkerKey, Value: "w-1.42"}}

	etag, err := st.Update(context.Background(), "ETAG1", puts, []string{"S2/R9"})

	if err != nil || etag != "ETAG2" {
		t.Fatalf("Update = (%q, %v), want (ETAG2, nil)", etag, err)
	}
	reqs := ks.requests()
	if len(reqs) != 1 {
		t.Fatalf("요청 %d개, want 1", len(reqs))
	}
	r := reqs[0]
	if want := storePath + "/keys"; r.method != http.MethodPost || r.uri != want {
		t.Errorf("요청 = %s %s, want POST %s", r.method, r.uri, want)
	}
	if r.ifMatch != "ETAG1" {
		t.Errorf("If-Match = %q, want ETAG1", r.ifMatch)
	}
	assertSignedForAccount(t, r)
	var body updateBody
	if err := json.Unmarshal(r.body, &body); err != nil {
		t.Fatalf("본문 %q 를 읽지 못했다: %v", r.body, err)
	}
	if len(body.Puts) != 2 || body.Puts[0].Key != "S1/R1" || body.Puts[0].Value != "17" ||
		body.Puts[1].Key != edgebounds.ReleaseMarkerKey || body.Puts[1].Value != "w-1.42" {
		t.Errorf("Puts = %+v, want 보낸 둘 그대로", body.Puts)
	}
	if len(body.Deletes) != 1 || body.Deletes[0].Key != "S2/R9" {
		t.Errorf("Deletes = %+v, want [S2/R9]", body.Deletes)
	}
}

// keys 는 n 개의 서로 다른 키다.
func keys(prefix string, n int) []string {
	out := make([]string, n)
	for i := range out {
		out[i] = fmt.Sprintf("%s%d/R", prefix, i)
	}
	return out
}

func entries(n int) []edgebounds.Entry {
	out := make([]edgebounds.Entry, n)
	for i, k := range keys("P", n) {
		out[i] = edgebounds.Entry{Key: k, Value: "1"}
	}
	return out
}

// puts + deletes 가 50개를 넘으면 보내지 않고 오류다(UpdateKeys 상한 — Store 계약). 50개까지는 보낸다.
func TestUpdateSendsAtMostFiftyItems(t *testing.T) {
	for _, tt := range []struct {
		puts, deletes int
		wantSent      bool
	}{
		{50, 0, true},
		{0, 50, true},
		{1, 49, true},
		{51, 0, false},
		{0, 51, false},
		{2, 49, false},
	} {
		t.Run(fmt.Sprintf("puts%d_deletes%d", tt.puts, tt.deletes), func(t *testing.T) {
			st, ks := newStore(t, func(w http.ResponseWriter, _ *http.Request) {
				writeJSON(w, http.StatusOK, map[string]string{"ETag": "ETAG2"}, `{"ItemCount":0,"TotalSizeInBytes":0}`)
			})

			_, err := st.Update(context.Background(), "ETAG1", entries(tt.puts), keys("D", tt.deletes))

			sent := len(ks.requests())
			if tt.wantSent && (err != nil || sent != 1) {
				t.Errorf("Update = %v · 요청 %d개, want nil · 1개", err, sent)
			}
			if !tt.wantSent && (err == nil || sent != 0) {
				t.Errorf("Update = %v · 요청 %d개, want 상한 오류 · 0개", err, sent)
			}
		})
	}
}

// 빈 판으로는 보내지 않는다 — 판 결속 없는 쓰기는 해제된 키를 되살릴 수 있다(설계 3-3 Writer 계약 3).
func TestUpdateRejectsEmptyIfMatchWithoutSending(t *testing.T) {
	st, ks := newStore(t, func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"ETag": "ETAG2"}, `{"ItemCount":0,"TotalSizeInBytes":0}`)
	})

	_, err := st.Update(context.Background(), "", entries(1), nil)

	if err == nil || len(ks.requests()) != 0 {
		t.Errorf("Update(빈 판) = %v · 요청 %d개, want 오류 · 0개", err, len(ks.requests()))
	}
}

// 선행 조건 실패는 실측상 ValidationException · HTTP 400 이다(83 C1). Store 는 분류하지 않고 SDK 오류를 감싸 그대로
// 돌려준다 — 분류는 Writer 가 Describe 판 대조로 한다(설계 3-3 계약 4 · E20). 다시 보내지도 않는다.
func TestUpdatePassesPreconditionFailureThroughUnclassified(t *testing.T) {
	const msg = "Pre-Condition failed during update of Key-Value-Store"
	st, ks := newStore(t, func(w http.ResponseWriter, _ *http.Request) {
		writeError(w, http.StatusBadRequest, "ValidationException", msg)
	})

	_, err := st.Update(context.Background(), "ETAG0", entries(1), nil)

	var api smithy.APIError
	if !errors.As(err, &api) || api.ErrorCode() != "ValidationException" || api.ErrorMessage() != msg {
		t.Errorf("Update = %v, want ValidationException %q 를 감싼 오류", err, msg)
	}
	if n := len(ks.requests()); n != 1 {
		t.Errorf("요청 %d개, want 1(Store 는 다시 보내지 않는다)", n)
	}
}

func TestFetchGetsKeyAsSinglePathSegment(t *testing.T) {
	st, ks := newStore(t, func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, nil, `{"Key":"S1/R1","Value":"17","ItemCount":1,"TotalSizeInBytes":7}`)
	})

	value, found, err := st.Fetch(context.Background(), "S1/R1")

	if err != nil || !found || value != "17" {
		t.Fatalf("Fetch = (%q, %v, %v), want (17, true, nil)", value, found, err)
	}
	reqs := ks.requests()
	if len(reqs) != 1 {
		t.Fatalf("요청 %d개, want 1", len(reqs))
	}
	if want := storePath + "/keys/S1%2FR1"; reqs[0].method != http.MethodGet || reqs[0].uri != want {
		t.Errorf("요청 = %s %s, want GET %s", reqs[0].method, reqs[0].uri, want)
	}
	assertSignedForAccount(t, reqs[0])
}

// 키가 없으면(ResourceNotFoundException) 오류가 아니라 found=false 다. 다른 오류는 그대로 돌려준다.
func TestFetchReportsMissingKeyAsNotFoundAndOtherErrorsAsErrors(t *testing.T) {
	for _, tt := range []struct {
		status    int
		code      string
		wantFound bool
		wantErr   bool
	}{
		{http.StatusNotFound, "ResourceNotFoundException", false, false},
		{http.StatusForbidden, "AccessDeniedException", false, true},
		{http.StatusInternalServerError, "InternalServerException", false, true},
	} {
		t.Run(tt.code, func(t *testing.T) {
			st, _ := newStore(t, func(w http.ResponseWriter, _ *http.Request) {
				writeError(w, tt.status, tt.code, "x")
			})

			value, found, err := st.Fetch(context.Background(), "S1/R1")

			if found != tt.wantFound || value != "" || (err != nil) != tt.wantErr {
				t.Errorf("Fetch = (%q, %v, %v), want (\"\", %v, 오류 %v)", value, found, err, tt.wantFound, tt.wantErr)
			}
		})
	}
}
