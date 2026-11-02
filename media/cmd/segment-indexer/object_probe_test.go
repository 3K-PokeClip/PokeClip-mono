package main

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"maps"
	"os"
	"slices"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// 객체 탐침(계획 ①-9 · 설계 r7 8-2 판정 D)의 시험이다. 탐침 키는 설계의 예 그대로다.
const (
	testProbeSrc = "dvr/pcprobe-pok525/seg/000000.m4s"
	testProbeDst = "vod/pcprobe-pok525/seg/000000.m4s"
)

// probeFakeObject 는 가짜 저장소에 든 객체 하나다.
type probeFakeObject struct {
	body []byte
	meta map[string]string
}

// probeFakeStore 는 객체 탐침 시험용 publish.Store 다 — 연산을 기록하고, 연산 이름별로 실패를 주입한다.
type probeFakeStore struct {
	objects map[string]probeFakeObject
	// fail 은 연산 이름(put · copy · head · delete)별로 돌려줄 오류다 — 그 연산은 아무것도 바꾸지 않는다.
	fail map[string]error
	// keepOnDelete 가 참이면 Delete 가 성공을 돌려주고도 객체를 남긴다(지운 뒤 Head 가 객체를 보는 경우).
	keepOnDelete bool
	// copyWithoutMeta 가 참이면 Copy 가 본문만 옮기고 사용자 메타를 버린다(사본 머리가 원본과 다른 경우).
	copyWithoutMeta bool
	calls           []string
	deleted         []string
}

func newProbeFakeStore() *probeFakeStore {
	return &probeFakeStore{objects: map[string]probeFakeObject{}, fail: map[string]error{}}
}

func (s *probeFakeStore) Put(_ context.Context, key string, _ publish.Precondition, body []byte, meta map[string]string) (string, error) {
	s.calls = append(s.calls, "put "+key)
	if err := s.fail["put"]; err != nil {
		return "", err
	}
	if _, ok := s.objects[key]; ok { // 탐침은 IfAbsent 로만 쓴다
		return "", publish.ErrPreconditionFailed
	}
	s.objects[key] = probeFakeObject{body: slices.Clone(body), meta: maps.Clone(meta)}
	return `"etag"`, nil
}

func (s *probeFakeStore) Head(_ context.Context, key string) (publish.Stat, error) {
	s.calls = append(s.calls, "head "+key)
	if err := s.fail["head"]; err != nil {
		return publish.Stat{}, err
	}
	o, ok := s.objects[key]
	if !ok {
		return publish.Stat{}, nil
	}
	return publish.Stat{Exists: true, ETag: `"etag"`, Meta: maps.Clone(o.meta)}, nil
}

func (s *probeFakeStore) Get(context.Context, string) ([]byte, string, error) {
	return nil, "", errors.New("탐침은 Get 을 쓰지 않는다")
}

func (s *probeFakeStore) Copy(_ context.Context, src, dst string) error {
	s.calls = append(s.calls, "copy "+src+" "+dst)
	if err := s.fail["copy"]; err != nil {
		return err
	}
	o, ok := s.objects[src]
	if !ok {
		return publish.ErrNotFound
	}
	if s.copyWithoutMeta {
		o.meta = nil
	}
	s.objects[dst] = o
	return nil
}

func (s *probeFakeStore) Delete(_ context.Context, keys []string) error {
	s.calls = append(s.calls, "delete "+strings.Join(keys, " "))
	if err := s.fail["delete"]; err != nil {
		return err
	}
	s.deleted = append(s.deleted, keys...)
	if !s.keepOnDelete {
		for _, k := range keys {
			delete(s.objects, k)
		}
	}
	return nil
}

// probeAuthError 는 응답 상태 403 을 단 저장소 오류다 — SDK 의 awshttp.ResponseError 와 같은 HTTPStatusCode 메서드를 쓴다.
type probeAuthError struct{}

func (probeAuthError) Error() string       { return "403 AccessDenied" }
func (probeAuthError) HTTPStatusCode() int { return 403 }

// runProbe 는 탐침을 store 에 돌리고 종료 코드와 JSON 로그를 돌려준다.
func runProbe(t *testing.T, store publish.Store, stream string) (int, string) {
	t.Helper()
	var buf bytes.Buffer
	code := probeObjects(context.Background(), store, stream, slog.New(slog.NewJSONHandler(&buf, nil)))
	return code, buf.String()
}

// 정상 — 원본 Put → 사본 Copy → 사본 Head → 둘 Delete → 둘 Head 없음 순서로 돌고 종료 0 이다. 끝나면 저장소에 아무것도
// 남지 않고, 로그에 단계 다섯이 차례로 있다(판정 D 통과 기준).
func TestObjectProbePassesAndLeavesNothing(t *testing.T) {
	store := newProbeFakeStore()

	code, log := runProbe(t, store, objectProbeStream)

	if code != exitProbeOK {
		t.Fatalf("종료 코드 = %d, want %d\n로그: %s", code, exitProbeOK, log)
	}
	if len(store.objects) != 0 {
		t.Errorf("남은 객체 = %v, want 없음", slices.Sorted(maps.Keys(store.objects)))
	}
	prev := -1
	for _, step := range []string{"object_probe_put", "object_probe_copied", "object_probe_copy_checked", "object_probe_deleted", "object_probe_absent", "object_probe_passed"} {
		at := strings.Index(log, `"msg":"`+step+`"`)
		if at <= prev {
			t.Fatalf("로그 단계 %s 가 없거나 순서가 어긋났다\n로그: %s", step, log)
		}
		prev = at
	}
	if !slices.Contains(store.calls, "copy "+testProbeSrc+" "+testProbeDst) {
		t.Errorf("Copy 호출 = %v, want %s → %s", store.calls, testProbeSrc, testProbeDst)
	}
}

// E31 · M22 — 스트림 이름이 pcprobe- 로 시작하는 키 안전 이름이 아니면 저장소에 아무것도 보내지 않고 종료 6 이다. 이름
// 검사가 빠지면 실제 방송의 ③ 원본 · 사본 키를 덮고 지운다.
func TestObjectProbeRejectsNonProbeStream(t *testing.T) {
	for _, stream := range []string{"", "live-1", "pcprobe-", "PCPROBE-x", "xpcprobe-a", "pcprobe-a/b", "pcprobe-../live-1"} {
		t.Run(stream, func(t *testing.T) {
			store := newProbeFakeStore()

			code, log := runProbe(t, store, stream)

			if code != exitProbeName {
				t.Errorf("종료 코드 = %d, want %d\n로그: %s", code, exitProbeName, log)
			}
			if len(store.calls) != 0 {
				t.Errorf("저장소 호출 = %v, want 없음", store.calls)
			}
		})
	}
}

// E34 · M25 — 앞 실행이 강제 종료돼 탐침 원본 · 사본이 남아 있으면 먼저 지우고(object_probe_stale_cleaned) 정상대로 돈다.
// 시작 정리가 빠지면 원본 Put(IfAbsent)이 412 로 실패한다.
func TestObjectProbeCleansStaleKeysFirst(t *testing.T) {
	store := newProbeFakeStore()
	store.objects[testProbeSrc] = probeFakeObject{body: []byte("p")}
	store.objects[testProbeDst] = probeFakeObject{body: []byte("p")}

	code, log := runProbe(t, store, objectProbeStream)

	if code != exitProbeOK {
		t.Fatalf("종료 코드 = %d, want %d\n로그: %s", code, exitProbeOK, log)
	}
	if !strings.Contains(log, `"msg":"object_probe_stale_cleaned"`) {
		t.Errorf("로그에 object_probe_stale_cleaned 가 없다: %s", log)
	}
	if len(store.objects) != 0 {
		t.Errorf("남은 객체 = %v, want 없음", slices.Sorted(maps.Keys(store.objects)))
	}
}

// 탐침은 자기 키 둘(pcprobe- 원본과 그 사본) 말고는 지우지 않는다 — 같은 버킷의 실제 방송 객체는 그대로 남는다.
func TestObjectProbeDeletesOnlyItsOwnKeys(t *testing.T) {
	store := newProbeFakeStore()
	others := []string{"dvr/live-1/seg/000000.m4s", "vod/live-1/seg/000000.m4s", "dvr/live-1/index.m3u8"}
	for _, k := range others {
		store.objects[k] = probeFakeObject{body: []byte("x")}
	}
	store.objects[testProbeDst] = probeFakeObject{body: []byte("p")} // 시작 정리도 거치게 한다

	code, log := runProbe(t, store, objectProbeStream)

	if code != exitProbeOK {
		t.Fatalf("종료 코드 = %d, want %d\n로그: %s", code, exitProbeOK, log)
	}
	for _, k := range store.deleted {
		if k != testProbeSrc && k != testProbeDst {
			t.Errorf("지운 키 %q — 탐침 키(%s · %s)가 아니다", k, testProbeSrc, testProbeDst)
		}
	}
	if got := slices.Sorted(maps.Keys(store.objects)); !slices.Equal(got, slices.Sorted(slices.Values(others))) {
		t.Errorf("남은 객체 = %v, want 실제 방송 객체 %v 그대로", got, others)
	}
}

// 단계별 실패 → 종료 코드. 인증 거부는 어느 단계든 3(로그에 단계), 망 · 시한 같은 그 밖의 오류는 Put · Copy · Head 면 4 ·
// 끝 정리 Delete 면 8 이다. Head 가 기대와 다르면(사본 없음 · 사본 메타 다름 · 지운 뒤 남음) 7 이다. 원본을 올린 뒤
// 실패하면 지울 수 있는 한 탐침 키를 지우고 나간다(Delete 가 실패한 경우는 빼고).
func TestObjectProbeStageFailureExitCodes(t *testing.T) {
	cases := []struct {
		name      string
		arrange   func(*probeFakeStore)
		wantCode  int
		wantStage string
		wantClean bool
	}{
		{"put 인증 거부", func(s *probeFakeStore) { s.fail["put"] = probeAuthError{} }, exitProbeAuth, "put", true},
		{"put 시한", func(s *probeFakeStore) { s.fail["put"] = context.DeadlineExceeded }, exitProbeTransport, "put", true},
		{"copy 인증 거부", func(s *probeFakeStore) { s.fail["copy"] = probeAuthError{} }, exitProbeAuth, "copy", true},
		{"copy 망 오류", func(s *probeFakeStore) { s.fail["copy"] = errors.New("connection reset") }, exitProbeTransport, "copy", true},
		{"head 인증 거부", func(s *probeFakeStore) { s.fail["head"] = probeAuthError{} }, exitProbeAuth, "head", true},
		{"사본 메타 다름", func(s *probeFakeStore) { s.copyWithoutMeta = true }, exitProbeMismatch, "head", true},
		{"delete 인증 거부", func(s *probeFakeStore) { s.fail["delete"] = probeAuthError{} }, exitProbeAuth, "delete", false},
		{"delete 정리 실패", func(s *probeFakeStore) { s.fail["delete"] = errors.New("DELETE 2키 중 2키를 못 지웠다") }, exitProbeCleanup, "delete", false},
		{"지운 뒤 남음", func(s *probeFakeStore) { s.keepOnDelete = true }, exitProbeMismatch, "head", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store := newProbeFakeStore()
			tc.arrange(store)

			code, log := runProbe(t, store, objectProbeStream)

			if code != tc.wantCode {
				t.Errorf("종료 코드 = %d, want %d\n로그: %s", code, tc.wantCode, log)
			}
			if !strings.Contains(log, `"msg":"object_probe_failed"`) || !strings.Contains(log, `"stage":"`+tc.wantStage+`"`) {
				t.Errorf("로그에 object_probe_failed stage=%s 가 없다: %s", tc.wantStage, log)
			}
			if tc.wantClean && len(store.objects) != 0 {
				t.Errorf("남은 객체 = %v, want 실패 뒤 정리로 없음", slices.Sorted(maps.Keys(store.objects)))
			}
		})
	}
}

// E34 — 시작 정리가 앞 실행의 잔여를 못 지우면(Delete 실패 · 지운 뒤에도 남음) 종료 9 다. 인증 거부면 3(delete)이다.
func TestObjectProbeStaleCleanupFailureExitCodes(t *testing.T) {
	cases := []struct {
		name     string
		arrange  func(*probeFakeStore)
		wantCode int
	}{
		{"delete 실패", func(s *probeFakeStore) { s.fail["delete"] = errors.New("connection reset") }, exitProbeStale},
		{"지운 뒤 남음", func(s *probeFakeStore) { s.keepOnDelete = true }, exitProbeStale},
		{"delete 인증 거부", func(s *probeFakeStore) { s.fail["delete"] = probeAuthError{} }, exitProbeAuth},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store := newProbeFakeStore()
			store.objects[testProbeSrc] = probeFakeObject{body: []byte("p")}
			tc.arrange(store)

			code, log := runProbe(t, store, objectProbeStream)

			if code != tc.wantCode {
				t.Errorf("종료 코드 = %d, want %d\n로그: %s", code, tc.wantCode, log)
			}
			if slices.ContainsFunc(store.calls, func(c string) bool { return strings.HasPrefix(c, "put ") }) {
				t.Errorf("시작 정리가 실패했는데 Put 을 보냈다: %v", store.calls)
			}
		})
	}
}

// 설정 — 환경 변수를 못 읽거나 버킷이 없으면 저장소를 만들지 않고 종료 2 다.
func TestObjectProbeCommandConfigFailureExits2(t *testing.T) {
	cases := []struct {
		name    string
		env     map[string]string
		wantErr string
	}{
		{"설정 읽기 실패", map[string]string{}, "POSTGRES"},
		{"버킷 없음", map[string]string{"POSTGRES_USER": "u", "POSTGRES_PASSWORD": "p", "POSTGRES_DB": "d"}, "S3 버킷이 비었다"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var buf bytes.Buffer

			code := runObjectProbe(func(k string) string { return tc.env[k] }, &buf)

			if code != exitProbeConfig {
				t.Errorf("종료 코드 = %d, want %d\n로그: %s", code, exitProbeConfig, buf.String())
			}
			if log := buf.String(); !strings.Contains(log, `"stage":"config"`) || !strings.Contains(log, tc.wantErr) {
				t.Errorf("로그 %q 에 stage=config 와 %q 가 없다", log, tc.wantErr)
			}
		})
	}
}

// main 배선 — 플래그 -rewind-object-probe 가 있으면 사이드카(run)를 띄우지 않고 탐침 1회 실행의 종료 코드로 나간다.
// main 은 단위 시험이 불가능하므로(boot_test.go 와 같은 사정) 소스에서 순서를 본다.
func TestMainDispatchesObjectProbeBeforeSidecar(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	text := string(src)

	order := []string{`flag.Bool("rewind-object-probe"`, "flag.Parse()", "os.Exit(runObjectProbe(os.Getenv, os.Stdout))", "err := run()"}
	prev := -1
	for _, want := range order {
		at := strings.Index(text, want)
		if at <= prev {
			t.Fatalf("%q 가 없거나 순서가 어긋났다 — 순서는 %v 여야 한다", want, order)
		}
		prev = at
	}
}
