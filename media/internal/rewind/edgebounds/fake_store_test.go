package edgebounds

// 판 의미를 흉내 낸 가짜 KVS(설계 3-3 Store · 실측 83_kvs_delete_bench H3-1–H3-5 · C1 · ISB (1) 2026-11-04).

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sync"
)

// updateKeysLimit 는 UpdateKeys 한 요청의 puts + deletes 상한이다(AWS 할당량 · 계약 Store.Update).
const updateKeysLimit = 50

// 실측에서 받은 오류 문구 그대로다 — 둘 다 ValidationException · HTTP 400 이다(83 C1 · H3-5).
const (
	preconditionMessage  = "Pre-Condition failed during update of Key-Value-Store"
	duplicateKeysMessage = "Payload contains multiple updates for the same key."
)

// fakeAPIError 는 SDK 가 돌려주는 API 오류의 모양을 흉내 낸다 — 코드 · HTTP 상태 · 문구. Writer 는 이것을 보고 경합을
// 가르면 안 된다(계약 4) — 상태 코드를 꺼낼 수 있게 둔 것은 그 변이(409 로 분기)를 쓸 수 있게 하려는 것이다.
type fakeAPIError struct {
	code    string
	status  int
	message string
}

func (e *fakeAPIError) Error() string {
	return fmt.Sprintf("operation error CloudFront KeyValueStore: UpdateKeys, https response error StatusCode: %d, api error %s: %s",
		e.status, e.code, e.message)
}

// HTTPStatusCode 는 smithy-go 응답 오류의 같은 이름 메서드를 흉내 낸다.
func (e *fakeAPIError) HTTPStatusCode() int { return e.status }

// errResponseLost 는 반영된 요청의 응답을 잃은 것을 흉내 낸 망 오류다.
var errResponseLost = errors.New("read tcp: connection reset by peer")

// storeState 는 가짜 KVS 의 상태다 — 자식 프로세스 시험이 파일로 넘긴다.
type storeState struct {
	ETag  int64             `json:"etag"`
	Items map[string]string `json:"items"`
}

// updateRequest 는 가짜가 받은 Update 하나다(거절된 것 포함).
type updateRequest struct {
	ifMatch string
	puts    []Entry
	deletes []string
	err     error
}

// fakeStore 는 판 의미를 흉내 낸 KVS 다. 바뀐 것이 있는 요청만 판을 바꾼다 — put 은 값이 같아도 언제나 바꾸고(ISB (1)
// 3/3), 없는 키 지우기는 바꾸지 않는다(H3-1 · H3-3). If-Match 는 무변경 요청에도 검사한다(H3-4 앞 단계). 선행 조건
// 실패와 같은 키 중복은 ValidationException 400 이다. 여러 고루틴이 함께 불러도 된다.
type fakeStore struct {
	mu   sync.Mutex
	st   storeState
	path string // 비지 않으면 판이 바뀔 때마다 상태를 이 파일에 쓴다

	deny          bool  // 참이면 쓰기를 거부한다(IAM 거부 — 403)
	loseResponses int   // 남은 수만큼 반영한 요청의 응답을 잃는다
	describeErr   error // 비지 않으면 Describe 가 이 오류다

	// beforeUpdate 는 Update 가 판정하기 전에 잠금 밖에서 부른다 — 끼어드는 다른 쓰기를 흉내 낼 때 쓴다.
	beforeUpdate func()

	requests []updateRequest
	history  map[string][]string // 반영된 put 의 키별 값 차례
}

func newFakeStore() *fakeStore {
	return &fakeStore{st: storeState{ETag: 1, Items: map[string]string{}}, history: map[string][]string{}}
}

// loadFakeStore 는 path 에 저장된 상태로 가짜를 만든다 — 바뀔 때마다 같은 파일에 다시 쓴다.
func loadFakeStore(path string) (*fakeStore, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	s := newFakeStore()
	if err := json.Unmarshal(data, &s.st); err != nil {
		return nil, err
	}
	if s.st.Items == nil {
		s.st.Items = map[string]string{}
	}
	s.path = path
	return s, nil
}

func (s *fakeStore) etag() string { return fmt.Sprintf("ETAG%d", s.st.ETag) }

func (s *fakeStore) Describe(context.Context) (Stat, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.describeErr != nil {
		return Stat{}, s.describeErr
	}
	var bytes int64
	for k, v := range s.st.Items {
		bytes += int64(len(k) + len(v))
	}
	return Stat{ETag: s.etag(), Items: int64(len(s.st.Items)), Bytes: bytes}, nil
}

func (s *fakeStore) Update(_ context.Context, ifMatch string, puts []Entry, deletes []string) (string, error) {
	if f := s.beforeUpdate; f != nil {
		f()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	req := updateRequest{ifMatch: ifMatch, puts: append([]Entry(nil), puts...), deletes: append([]string(nil), deletes...)}
	req.err = s.check(ifMatch, puts, deletes)
	s.requests = append(s.requests, req)
	if req.err != nil {
		return "", req.err
	}
	changed := len(puts) > 0
	for _, p := range puts {
		s.st.Items[p.Key] = p.Value
		s.history[p.Key] = append(s.history[p.Key], p.Value)
	}
	for _, k := range deletes {
		if _, ok := s.st.Items[k]; ok {
			delete(s.st.Items, k)
			changed = true
		}
	}
	if changed {
		s.st.ETag++
		if err := s.save(); err != nil {
			return "", err
		}
	}
	if s.loseResponses > 0 {
		s.loseResponses--
		s.requests[len(s.requests)-1].err = errResponseLost
		return "", errResponseLost
	}
	return s.etag(), nil
}

// check 는 요청을 실제 KVS 의 차례대로 판정한다 — 권한 · 요청 꼴 · 선행 조건.
func (s *fakeStore) check(ifMatch string, puts []Entry, deletes []string) error {
	if s.deny {
		return &fakeAPIError{code: "AccessDeniedException", status: 403, message: "not authorized to perform: cloudfront-keyvaluestore:UpdateKeys"}
	}
	if n := len(puts) + len(deletes); n == 0 || n > updateKeysLimit {
		return &fakeAPIError{code: "ValidationException", status: 400, message: fmt.Sprintf("payload has %d updates", n)}
	}
	seen := map[string]bool{}
	for _, k := range keysOf(puts, deletes) {
		if seen[k] {
			return &fakeAPIError{code: "ValidationException", status: 400, message: duplicateKeysMessage}
		}
		seen[k] = true
	}
	if ifMatch != s.etag() {
		return &fakeAPIError{code: "ValidationException", status: 400, message: preconditionMessage}
	}
	return nil
}

func keysOf(puts []Entry, deletes []string) []string {
	keys := append([]string(nil), deletes...)
	for _, p := range puts {
		keys = append(keys, p.Key)
	}
	return keys
}

func (s *fakeStore) Fetch(_ context.Context, key string) (string, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, ok := s.st.Items[key]
	return v, ok, nil
}

// foreignPut 은 다른 쓰기 주체(다른 상자 · 사람)의 put 하나를 판 검사 없이 반영한다 — 판이 바뀐다.
func (s *fakeStore) foreignPut(key, value string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.st.Items[key] = value
	s.st.ETag++
}

// seed 는 시험 준비용 키를 판을 바꾸지 않고 넣는다.
func (s *fakeStore) seed(key, value string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.st.Items[key] = value
}

// drop 은 시험 준비용 키를 판을 바꾸지 않고 뺀다.
func (s *fakeStore) drop(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.st.Items, key)
}

func (s *fakeStore) value(key string) (string, bool) {
	v, ok, _ := s.Fetch(context.Background(), key)
	return v, ok
}

func (s *fakeStore) currentETag() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.etag()
}

func (s *fakeStore) setDeny(v bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deny = v
}

// updates 는 받은 Update 의 사본이다.
func (s *fakeStore) updates() []updateRequest {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]updateRequest(nil), s.requests...)
}

func (s *fakeStore) putHistory(key string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.history[key]...)
}

// save 는 상태를 파일에 원자적으로 쓴다 — 자식 프로세스가 쓰는 중에 죽어도 앞 상태나 새 상태 가운데 하나만 남는다.
func (s *fakeStore) save() error {
	if s.path == "" {
		return nil
	}
	return writeJSONAtomic(s.path, s.st)
}

func writeJSONAtomic(path string, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
