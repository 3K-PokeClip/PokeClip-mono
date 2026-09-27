package publish

// 인메모리 가짜(fakeStore)의 계약 — 가짜가 S3 조건부 쓰기의 의미론(계획 4.4 [B-1])을 그대로 내야, 그 위에서
// 도는 세대 규약 테스트(계획 4.5 A1 G8 증명 ①–④)가 실제 저장소에서도 같은 판정을 낸다.

import (
	"bytes"
	"context"
	"errors"
	"maps"
	"reflect"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/s3"
)

const testKey = "dvr/demo/sess-1/index.m3u8"

var (
	bodyA = []byte("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:10\n")
	bodyB = []byte("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:11\n")
	bodyC = []byte("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:12\n")
)

// mustPut 은 준비 단계의 쓰기다 — 실패하면 테스트를 더 진행할 수 없다.
func mustPut(t *testing.T, s Store, cond Precondition, body []byte, meta map[string]string) string {
	t.Helper()
	etag, err := s.Put(context.Background(), testKey, cond, body, meta)
	if err != nil {
		t.Fatalf("준비 쓰기 Put(%+v) 실패: %v", cond, err)
	}
	return etag
}

// storedBody 는 지금 저장된 본문이다. 객체가 없으면 nil 이다.
func storedBody(t *testing.T, s Store) []byte {
	t.Helper()
	got, err := s.Get(context.Background(), testKey)
	if errors.Is(err, ErrNotFound) {
		return nil
	}
	if err != nil {
		t.Fatalf("Get 실패: %v", err)
	}
	return got
}

// sameStat 은 두 Head 결과가 같은가다 — Exists · ETag 와 메타의 키 · 값을 본다(maps.Equal 이라 nil 과 빈
// map 은 같다).
func sameStat(got, want Stat) bool {
	return got.Exists == want.Exists && got.ETag == want.ETag && maps.Equal(got.Meta, want.Meta)
}

// fakestore_etag_reflects_body_only(계획 4.5 A1) — ETag 는 본문만의 함수이고 메타는 섞이지 않는다. S3 ETag 의
// 성질(본문 변경만 반영)을 가짜가 가져야 G8 증명의 전제 (나)와, 그 성질이 여는 좀비 경로(같은 본문 · 다른
// 메타 판은 If-Match 로 가를 수 없다 — A1 「ETag 성질과 선재 판정」)를 테스트가 재현할 수 있다.
func TestFakeStoreETagReflectsBodyOnly(t *testing.T) {
	f := newFakeStore(t)
	e1 := mustPut(t, f, IfAbsent(), bodyA, map[string]string{"pc-gen": "1"})

	// 같은 본문 · 다른 메타: ETag 는 그대로이고 메타만 바뀐다.
	e2 := mustPut(t, f, IfMatch(e1), bodyA, map[string]string{"pc-gen": "2"})
	if e2 != e1 {
		t.Errorf("같은 본문 · 다른 메타의 ETag = %q, want %q(그대로)", e2, e1)
	}
	st, err := f.Head(context.Background(), testKey)
	if err != nil {
		t.Fatalf("Head 실패: %v", err)
	}
	if got := st.Meta["pc-gen"]; got != "2" {
		t.Errorf("같은 본문 재쓰기 뒤 pc-gen = %q, want %q(메타는 통째로 새 값)", got, "2")
	}

	// 다른 본문: ETag 가 바뀐다.
	e3 := mustPut(t, f, IfMatch(e2), bodyB, nil)
	if e3 == e2 {
		t.Errorf("다른 본문의 ETag 가 그대로다(%q) — 본문 변경을 반영하지 않는다", e3)
	}
	// 메타 없이 썼으니 옛 메타가 남지 않는다 — 메타는 합치지 않고 통째로 갈아 끼운다(Store.Put).
	st, err = f.Head(context.Background(), testKey)
	if err != nil {
		t.Fatalf("Head 실패: %v", err)
	}
	if v, ok := st.Meta["pc-gen"]; ok {
		t.Errorf("메타 없이 쓴 뒤 pc-gen = %q 가 남았다, want 없음(메타는 통째로 교체)", v)
	}

	// 본문을 되돌리면 ETag 도 돌아온다 — 이력이 아니라 본문만으로 정해진다(ABA).
	if e4 := mustPut(t, f, IfMatch(e3), bodyA, nil); e4 != e1 {
		t.Errorf("본문 A 로 되돌린 ETag = %q, want %q(처음 A 판과 같다)", e4, e1)
	}
}

// 4.4 [B-1] 의 응답 분기 가운데 저장소 상태로 정해지는 셋(200 · 412 · 404). 조건은 저장된 판을 기준으로
// 따지고 실패한 쓰기는 판을 바꾸지 않는다. 409 는 동시성에서만 생겨 주입으로 낸다(TestFakeStoreInjectedPutFaults).
func TestFakeStoreConditionalWrite(t *testing.T) {
	ifAbsent := func(string) Precondition { return IfAbsent() }
	cases := []struct {
		name string
		// seed 면 bodyA 를 먼저 올려 두고 그 ETag 를 cond 에 넘긴다.
		seed    bool
		cond    func(seeded string) Precondition
		wantErr error
		// want 는 끝난 뒤 저장된 본문이다. nil 이면 객체가 없어야 한다.
		want []byte
	}{
		{name: "IfAbsent_absent_writes", cond: ifAbsent, want: bodyB},
		{name: "IfAbsent_present_412", seed: true, cond: ifAbsent, wantErr: ErrPreconditionFailed, want: bodyA},
		{name: "IfMatch_current_writes", seed: true, cond: IfMatch, want: bodyB},
		{name: "IfMatch_stale_412", seed: true, cond: func(string) Precondition { return IfMatch(`"stale"`) }, wantErr: ErrPreconditionFailed, want: bodyA},
		{name: "IfMatch_absent_404", cond: func(string) Precondition { return IfMatch(`"any"`) }, wantErr: ErrNotFound},
		{name: "IfMatch_empty_rejected", seed: true, cond: func(string) Precondition { return IfMatch("") }, wantErr: errEmptyETag, want: bodyA},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFakeStore(t)
			var seeded string
			if tc.seed {
				seeded = mustPut(t, f, IfAbsent(), bodyA, nil)
			}
			cond := tc.cond(seeded)

			etag, err := f.Put(context.Background(), testKey, cond, bodyB, nil)
			if tc.wantErr == nil && err != nil {
				t.Fatalf("Put(%+v) 실패: %v", cond, err)
			}
			if tc.wantErr != nil && !errors.Is(err, tc.wantErr) {
				t.Fatalf("Put(%+v) 오류 = %v, want %v", cond, err, tc.wantErr)
			}
			if got := storedBody(t, f); !bytes.Equal(got, tc.want) {
				t.Errorf("저장된 본문 = %q, want %q", got, tc.want)
			}
			if tc.wantErr != nil {
				return
			}
			st, err := f.Head(context.Background(), testKey)
			if err != nil {
				t.Fatalf("Head 실패: %v", err)
			}
			if etag == "" || st.ETag != etag {
				t.Errorf("Put 이 돌려준 ETag = %q, Head 의 ETag = %q — 같은 비어 있지 않은 값이어야 한다", etag, st.ETag)
			}
		})
	}
}

// Head 는 ETag 와 메타만 돌려주고 본문을 읽지 않는다(Head/Get 분리 — 설계 4.4.2). 메타 키는 소문자로
// 돌아온다([B-3]). 저장된 메타는 호출자의 map 과 공유하지 않는다 — S3 에 올린 뒤 호출자가 map 을 고쳐도
// 저장된 판은 그대로다.
func TestFakeStoreHeadReturnsETagAndMetaOnly(t *testing.T) {
	f := newFakeStore(t)
	ctx := context.Background()

	st, err := f.Head(ctx, testKey)
	if err != nil {
		t.Fatalf("없는 키의 Head 가 오류다: %v — 부재는 Exists 거짓이어야 한다", err)
	}
	if !sameStat(st, Stat{}) {
		t.Errorf("없는 키의 Head = %+v, want 영값(Exists 거짓)", st)
	}

	meta := map[string]string{"PC-Gen": "3", "pc-session": "sess-1"}
	etag := mustPut(t, f, IfAbsent(), bodyA, meta)
	meta["pc-session"] = "changed"

	st, err = f.Head(ctx, testKey)
	if err != nil {
		t.Fatalf("Head 실패: %v", err)
	}
	want := Stat{Exists: true, ETag: etag, Meta: map[string]string{"pc-gen": "3", "pc-session": "sess-1"}}
	if !sameStat(st, want) {
		t.Errorf("Head = %+v, want %+v", st, want)
	}
	if gets := f.getKeys(); len(gets) != 0 {
		t.Errorf("Head 만 불렀는데 본문 읽기 기록 %v — Head 는 본문을 읽지 않는다", gets)
	}
	if heads := f.headKeys(); !slices.Equal(heads, []string{testKey, testKey}) {
		t.Errorf("Head 기록 = %v, want [%s %s]", heads, testKey, testKey)
	}
}

// 오류 주입 — PUT 이 어디서 끊겼는지에 따라 저장소 상태가 다르다. 적용 전에 끊긴 요청(409 · 네트워크)은 판을
// 바꾸지 않고, 적용 뒤 응답만 잃은 요청은 판을 바꾼 채 호출자에게 오류를 준다(결과 모름).
func TestFakeStoreInjectedPutFaults(t *testing.T) {
	ctx := context.Background()
	errLost := errors.New("응답 유실")

	t.Run("conflict_not_applied", func(t *testing.T) {
		f := newFakeStore(t)
		e := mustPut(t, f, IfAbsent(), bodyA, nil)
		f.onPut = func(putCall) error { return ErrConflict }
		if _, err := f.Put(ctx, testKey, IfMatch(e), bodyB, nil); !errors.Is(err, ErrConflict) {
			t.Fatalf("Put 오류 = %v, want ErrConflict", err)
		}
		if got := storedBody(t, f); !bytes.Equal(got, bodyA) {
			t.Errorf("409 뒤 저장된 본문 = %q, want %q(적용되지 않았다)", got, bodyA)
		}
	})

	t.Run("applied_response_lost", func(t *testing.T) {
		f := newFakeStore(t)
		e := mustPut(t, f, IfAbsent(), bodyA, nil)
		f.onPut = func(c putCall) error {
			if _, err := f.applyPut(c); err != nil {
				t.Errorf("주입 훅의 applyPut 실패: %v", err)
			}
			return errLost
		}
		if _, err := f.Put(ctx, testKey, IfMatch(e), bodyB, nil); !errors.Is(err, errLost) {
			t.Fatalf("Put 오류 = %v, want 주입한 응답 유실", err)
		}
		if got := storedBody(t, f); !bytes.Equal(got, bodyB) {
			t.Errorf("응답을 잃은 쓰기 뒤 저장된 본문 = %q, want %q(적용은 됐다)", got, bodyB)
		}
	})
}

// 늦게 닿는 요청의 조건은 보낸 순간이 아니라 닿는 순간의 판으로 따진다 — G8 증명 ① 이 기대는 성질이다. 마감에
// 잘려 호출자가 포기한 요청을 붙잡아 두었다가, 그사이 다른 writer 가 판을 바꿨으면 412 로 막히고 아니면
// 적용된다.
func TestFakeStoreLateApplyChecksConditionOnArrival(t *testing.T) {
	cases := []struct {
		name string
		// successorWrites 면 붙잡힌 요청이 닿기 전에 다른 writer 가 bodyC 를 쓴다.
		successorWrites bool
		wantErr         error
		want            []byte
	}{
		{name: "after_successor_412", successorWrites: true, wantErr: ErrPreconditionFailed, want: bodyC},
		{name: "nothing_between_writes", want: bodyB},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFakeStore(t)
			e := mustPut(t, f, IfAbsent(), bodyA, nil)

			var held putCall
			f.onPut = func(c putCall) error {
				held = c
				return context.DeadlineExceeded
			}
			if _, err := f.Put(context.Background(), testKey, IfMatch(e), bodyB, nil); !errors.Is(err, context.DeadlineExceeded) {
				t.Fatalf("붙잡힌 Put 의 오류 = %v, want context.DeadlineExceeded", err)
			}
			f.onPut = nil
			if tc.successorWrites {
				mustPut(t, f, IfMatch(e), bodyC, nil)
			}

			if _, err := f.applyPut(held); !errors.Is(err, tc.wantErr) {
				t.Errorf("늦게 닿은 요청의 결과 = %v, want %v", err, tc.wantErr)
			}
			if got := storedBody(t, f); !bytes.Equal(got, tc.want) {
				t.Errorf("저장된 본문 = %q, want %q", got, tc.want)
			}
		})
	}
}

// 마감 기록(계획 4.5 A1 커밋 2) — 가짜는 Put 호출마다 ctx 의 마감을 남긴다. 세대 규약의 P3 마감(A1 결정 9 —
// min(P3 시작 + T_pub, m0 + lease − T_pub))을 테스트가 이 기록으로 잰다. 마감이 없으면 영값이다.
func TestFakeStoreRecordsPutCallsWithDeadline(t *testing.T) {
	f := newFakeStore(t)
	deadline := time.Now().Add(time.Hour)
	ctx, cancel := context.WithDeadline(context.Background(), deadline)
	defer cancel()

	meta := map[string]string{"pc-gen": "1"}
	if _, err := f.Put(ctx, testKey, IfAbsent(), bodyA, meta); err != nil {
		t.Fatalf("Put 실패: %v", err)
	}
	if _, err := f.Put(context.Background(), testKey, IfAbsent(), bodyB, nil); !errors.Is(err, ErrPreconditionFailed) {
		t.Fatalf("두 번째 Put 오류 = %v, want ErrPreconditionFailed", err)
	}

	calls := f.putCalls()
	if len(calls) != 2 {
		t.Fatalf("Put 기록 %d건, want 2(실패한 호출도 남는다)", len(calls))
	}
	first := calls[0]
	if !first.deadline.Equal(deadline) {
		t.Errorf("첫 호출의 마감 = %v, want %v", first.deadline, deadline)
	}
	if first.key != testKey || first.cond != IfAbsent() || !bytes.Equal(first.body, bodyA) || first.meta["pc-gen"] != "1" {
		t.Errorf("첫 호출 기록 = %+v, want key=%s · IfAbsent · bodyA · pc-gen=1", first, testKey)
	}
	if !calls[1].deadline.IsZero() {
		t.Errorf("마감 없는 호출의 기록 마감 = %v, want 영값", calls[1].deadline)
	}
}

// 이미 끝난 ctx 로는 저장소에 닿지 않는다 — SDK 가 요청을 보내지 않는 것과 같다. 호출은 기록되지만 판은
// 바뀌지 않는다.
func TestFakeStoreHonorsDoneContext(t *testing.T) {
	f := newFakeStore(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	if _, err := f.Put(ctx, testKey, IfAbsent(), bodyA, nil); !errors.Is(err, context.Canceled) {
		t.Errorf("Put 오류 = %v, want context.Canceled", err)
	}
	if _, err := f.Head(ctx, testKey); !errors.Is(err, context.Canceled) {
		t.Errorf("Head 오류 = %v, want context.Canceled", err)
	}
	if _, err := f.Get(ctx, testKey); !errors.Is(err, context.Canceled) {
		t.Errorf("Get 오류 = %v, want context.Canceled", err)
	}
	if got := storedBody(t, f); got != nil {
		t.Errorf("끝난 ctx 의 Put 뒤 저장된 본문 = %q, want 없음", got)
	}
	if n := len(f.putCalls()); n != 1 {
		t.Errorf("끝난 ctx 의 Put 기록 %d건, want 1(보내지 못한 호출도 기록은 남는다)", n)
	}
}

// failureRecorder 는 가짜가 테스트를 실패로 적는 순간을 가로챈다 — 진짜 t 를 실패시키지 않고 센다.
type failureRecorder struct {
	testing.TB

	mu       sync.Mutex
	failures []string
}

func (r *failureRecorder) Errorf(format string, args ...any) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.failures = append(r.failures, format)
}

func (r *failureRecorder) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.failures)
}

var (
	_ Store                     = (*fakeStore)(nil)
	_ s3.ListObjectsV2APIClient = (*fakeStore)(nil)
	_ s3.ListBucketsAPIClient   = (*fakeStore)(nil)
)

// 계획 6.5 ⑵ — S3 LIST 금지(프로필 4절)의 동적 그물. Store 에는 목록 연산이 없어서 Store 를 든 코드가 목록에
// 닿는 길은 SDK 목록 인터페이스로의 형 단언뿐이다. 가짜는 *s3.Client 의 목록 연산 전부(clientListMethods —
// 정적 단언과 같은 집합)를 같은 시그니처로 가져 어느 형 단언이든 성립하고, 불리면 테스트를 실패로 적으며
// 오류를 돌려준다.
func TestFakeStoreFailsTestOnListCalls(t *testing.T) {
	client := reflect.ValueOf((*s3.Client)(nil))
	for _, name := range clientListMethods() {
		t.Run(name, func(t *testing.T) {
			rec := &failureRecorder{TB: t}
			var s Store = newFakeStore(rec)
			want := client.MethodByName(name).Type()
			trap := reflect.ValueOf(s).MethodByName(name)
			if !trap.IsValid() || trap.Type() != want {
				t.Fatalf("가짜에 %s 함정(%v)이 없다 — 형 단언이 실패해 목록 호출이 그물을 빠져나간다", name, want)
			}
			// 함정은 인자를 보지 않는다 — ctx 와 빈 입력만 넘긴다.
			out := trap.Call([]reflect.Value{reflect.ValueOf(context.Background()), reflect.Zero(want.In(1))})
			if err, _ := out[1].Interface().(error); err == nil {
				t.Errorf("%s 결과 오류 = nil, want 목록 금지 오류", name)
			}
			if n := rec.count(); n != 1 {
				t.Errorf("%s 가 테스트 실패를 %d번 적었다, want 1", name, n)
			}
		})
	}
}
