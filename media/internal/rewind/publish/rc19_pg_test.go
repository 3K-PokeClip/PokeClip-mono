package publish

// RC-19 복구표(설계 4.4.5 · 계획 4.5 A1 결정 9 · 10 · 로그 표 · 체크리스트 A-2 10 · J14 · J15) — fake Store + PG
// 통합이다. 다른 writer 의 쓰기는 가짜의 onPut 훅이 우리 PUT 이 닿기 직전에 끼운다(P0 과 P3 사이).

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"strings"
	"testing"
)

// otherVersion 은 다른 writer 가 올린 판이다 — 창 [from, to] 를 세대 gen 으로 렌더한 본문과 그 메타(8키 — 화해가
// 읽을 수 있는 판)다.
func otherVersion(t *testing.T, l *loop, from, to, gen int64) putCall {
	t.Helper()
	pl := l.fx.window(from, to)
	pl.BaseURL = fxBaseURL
	body := mustRender(t, pl)
	return putCall{key: soloKey, cond: IfAbsent(), body: body, meta: encodeMeta(describe(pl, gen, sha256Of(body)), "S")}
}

// plant 는 판 v 를 조건 없이 저장소에 둔다 — 다른 writer 의 쓰기가 이미 닿은 국면이다.
func plant(f *fakeStore, v putCall) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.objects[v.key] = fakeObject{body: v.body, etag: bodyETag(v.body), meta: lowerKeys(v.meta)}
}

// remove 는 키의 객체를 지운다 — 외부 삭제 · 버킷 전환 국면이다.
func remove(f *fakeStore, key string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.objects, key)
}

// onceBefore 는 첫 PUT 이 닿기 직전에 act 를 한 번 하는 훅이다.
func onceBefore(act func()) func(putCall) error {
	done := false
	return func(putCall) error {
		if !done {
			done = true
			act()
		}
		return nil
	}
}

// 최초 생성 412(설계 4.4.5) — 첫 PUT(IfAbsent) 직전에 다른 writer 의 판이 닿으면 412 다. 틱을 포기하고 화해해
// P 를 그 판으로 삼는다(create_412). 다음 틱은 그 판 위에 IfMatch 로 쓴다.
func TestCreate412ReconcilesToStoredVersion(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	other := otherVersion(t, l, 0, 4, 9)
	store.setOnPut(onceBefore(func() { plant(store, other) }))

	out := l.tick(t.Context(), 0, 5)

	if out.Published || len(store.putCalls()) != 1 {
		t.Errorf("틱 = %+v · PUT %d번, want 발행 없음 · PUT 1번(재시도 없음)", out, len(store.putCalls()))
	}
	if got := logs.aborts(); !slices.Equal(got, []string{reasonCreate412}) {
		t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonCreate412)
	}
	if p := out.State.Prev; p == nil || p.ETag != bodyETag(other.body) || p.Gen != 9 {
		t.Errorf("화해 뒤 P = %+v, want 다른 writer 의 판(세대 9)", p)
	}
	next := l.mustPublish(t.Context(), 0, 5)
	if puts := store.putCalls(); puts[len(puts)-1].cond != IfMatch(bodyETag(other.body)) || next.State.Prev.Gen <= 9 {
		t.Errorf("다음 틱의 조건 %+v · 세대 %d, want IfMatch(그 판) · 9 초과", puts[len(puts)-1].cond, next.State.Prev.Gen)
	}
}

// update_412_abandons_tick_then_reconciles(계획 4.5 A1 결정 10) — 갱신 412 는 재시도하지 않는다. 화해로 P 를
// 저장된 판으로 바꾸고 틱을 포기한다(재시도 0). 다음 틱은 새 P 에서 렌더하고, 그 본문이 저장된 판과 같으면
// 올리지 않는다(같은 본문 재PUT 0). 저장된 판의 본문이 P 와 같고 ETag 만 다른 변형도 재시도 0 이다.
func TestUpdate412AbandonsTickThenReconciles(t *testing.T) {
	t.Run("다른_판이_끼어듦", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		other := otherVersion(t, l, 0, 6, 9)
		store.setOnPut(onceBefore(func() { plant(store, other) }))

		out := l.tick(t.Context(), 0, 7)

		if out.Published || len(store.putCalls()) != 2 {
			t.Errorf("틱 = %+v · PUT %d번, want 발행 없음 · PUT 2번(이 틱 1번 — 재시도 없음)", out, len(store.putCalls()))
		}
		if got := logs.aborts(); !slices.Equal(got, []string{reasonUpdate412}) {
			t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonUpdate412)
		}
		if p := out.State.Prev; p == nil || p.ETag != bodyETag(other.body) {
			t.Fatalf("화해 뒤 P = %+v, want 저장된 판", p)
		}
		same := l.tick(t.Context(), 0, 6) // 저장된 판과 같은 창
		if same.Published || len(store.putCalls()) != 2 {
			t.Errorf("저장된 판과 같은 본문 틱 = %+v · PUT %d번, want 올리지 않음", same, len(store.putCalls()))
		}
	})
	t.Run("같은_본문_다른_ETag", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		first := l.mustPublish(t.Context(), 0, 5)
		store.mu.Lock()
		obj := store.objects[soloKey]
		obj.etag = `"re-encrypted"`
		store.objects[soloKey] = obj
		store.mu.Unlock()

		out := l.tick(t.Context(), 0, 6)

		if out.Published || len(store.putCalls()) != 2 {
			t.Errorf("틱 = %+v · PUT %d번, want 발행 없음 · PUT 2번(재시도 없음)", out, len(store.putCalls()))
		}
		if got := logs.aborts(); !slices.Equal(got, []string{reasonUpdate412}) {
			t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonUpdate412)
		}
		want := Manifest{Published: first.State.Prev.Published, ETag: `"re-encrypted"`}
		if p := out.State.Prev; p == nil || *p != want {
			t.Errorf("화해 뒤 P = %+v, want %+v", p, want)
		}
	})
}

// J15 — 갱신 PUT 이 404(객체 없음 — 외부 삭제 · 버킷 전환)면 update_404 로 포기하고 화해한다. 화해의 R4 가 fence 를
// 쥔 채 DB manifest_etag 를 비우고 P 도 비운다 — 다음 틱의 P0 이 최초 생성 경로를 돌려줘 IfAbsent 로 다시
// 만들고 P4 가 새 ETag 를 쓴다. R4 는 다른 열(세대 · published_seq · base · 계승 · state)을 건드리지 않는다 — 사슬
// 픽스처라 그 열들이 영값이 아니다(base 5 · 계승 P). 다시 만드는 PUT 이 적용된 뒤 응답을 잃으면(결과 모름) 화해의 R3 가
// DB base 를 저장된 판의 pc-disc-seq 로 둔다 — max 가 아니다(체크리스트 A-3 3): 다시 만든 판은 개시 base 로 가서 축출로 오른
// DB base 보다 작다. fence 가 없으면 R4 는 아무것도 쓰지 않는다(메모리 P 만 비운다 · 로그 없음).
func TestUpdate404ClearsDBETagThenRecreates(t *testing.T) {
	t.Run("fence_쥠", func(t *testing.T) {
		l, store, logs := newChainLoop(t, 700)
		l.mustPublish(t.Context(), 100, 610)
		remove(store, chainKey)
		var beforeR4 dbSession // 404 틱의 P0 뒤 · R4 앞의 행(P0 이 세대를 올렸다)
		store.setOnPut(onceBefore(func() { beforeR4 = readSession(t, l.pub.pool, "S") }))

		out := l.tick(t.Context(), 100, 611)

		if got := logs.aborts(); !slices.Equal(got, []string{reasonUpdate404}) {
			t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonUpdate404)
		}
		row := readSession(t, l.pub.pool, "S")
		if out.State.Prev != nil || row.etag != nil {
			t.Fatalf("화해 뒤 P %+v · DB ETag %s, want 둘 다 비움", out.State.Prev, strOrNil(row.etag))
		}
		if beforeR4.base != 5 || strOrNil(beforeR4.inherits) != "P" || beforeR4.pubSeq != 610 {
			t.Fatalf("R4 앞 행 %+v, want base 5 · 계승 P · published_seq 610(영값이 아닌 열)", beforeR4)
		}
		if row.gen != beforeR4.gen || row.pubSeq != beforeR4.pubSeq || row.base != beforeR4.base ||
			strOrNil(row.inherits) != strOrNil(beforeR4.inherits) || row.state != beforeR4.state {
			t.Errorf("R4 뒤 세대 · published_seq · base · 계승 · state = %d · %d · %d · %s · %s, want R4 앞 그대로 %d · %d · %d · %s · %s",
				row.gen, row.pubSeq, row.base, strOrNil(row.inherits), row.state,
				beforeR4.gen, beforeR4.pubSeq, beforeR4.base, strOrNil(beforeR4.inherits), beforeR4.state)
		}
		again := l.mustPublish(t.Context(), 100, 611)
		puts := store.putCalls()
		if puts[len(puts)-1].cond != IfAbsent() || strOrNil(readSession(t, l.pub.pool, "S").etag) != again.State.Prev.ETag {
			t.Errorf("다음 틱의 조건 %+v · DB ETag %s, want IfAbsent · 새 ETag %s",
				puts[len(puts)-1].cond, strOrNil(readSession(t, l.pub.pool, "S").etag), again.State.Prev.ETag)
		}
	})
	t.Run("재생성_PUT_결과_모름", func(t *testing.T) {
		l, store, logs := newChainLoop(t, 1600)
		l.mustPublish(t.Context(), 100, 999)
		l.mustPublish(t.Context(), 101, 1000) // seq 100 의 표시가 빠져 DISC-SEQ 6 — P4 가 DB base 6 을 쓴다
		remove(store, chainKey)
		l.tick(t.Context(), 102, 1001) // update_404 → R4(DB ETag · P 비움)
		if base := readSession(t, l.pub.pool, "S").base; base != 6 {
			t.Fatalf("다시 만들기 앞 DB base = %d, want 6(다시 만든 판의 개시 base 5 보다 커야 대입과 max 가 갈린다)", base)
		}
		store.setOnPut(func(c putCall) error { // 다시 만드는 PUT 은 적용되고 응답만 잃는다
			store.setOnPut(nil)
			if _, err := store.applyPut(c); err != nil {
				t.Errorf("주입 훅의 applyPut 실패: %v", err)
			}
			return context.DeadlineExceeded
		})

		out := l.tick(t.Context(), 102, 1001) // P 가 없어 개시 base 5 로 다시 만든다(결정 6)

		if got := logs.aborts(); !slices.Equal(got, []string{reasonUpdate404, reasonPutUnknown}) {
			t.Fatalf("중단 · 포기 로그 %v, want [%s %s]", got, reasonUpdate404, reasonPutUnknown)
		}
		row := readSession(t, l.pub.pool, "S")
		if p := out.State.Prev; p == nil || p.DiscontinuitySequence != 5 || row.base != 5 {
			t.Errorf("화해 뒤 P %+v · DB base %d, want 저장된 판의 pc-disc-seq 5 · 5", p, row.base)
		}
	})
	t.Run("fence_없음", func(t *testing.T) {
		l, store, _ := newSoloLoop(t, 10)
		first := l.mustPublish(t.Context(), 0, 5)
		store.setOnPut(onceBefore(func() {
			remove(store, soloKey)
			expireLease(t, l.pub.pool, "S")
		}))

		out := l.tick(t.Context(), 0, 6)

		if out.State.Prev != nil || out.State.FenceHeld {
			t.Errorf("화해 뒤 상태 = %+v, want P 비움 · fence 없음", out.State)
		}
		if got := strOrNil(readSession(t, l.pub.pool, "S").etag); got != first.State.Prev.ETag {
			t.Errorf("DB ETag = %s, want 그대로 %s(fence 없이 쓰지 않는다)", got, first.State.Prev.ETag)
		}
	})
}

// conflictThen 은 첫 PUT 을 409 로 끊는 훅이다 — 그 뒤 PUT 들은 then 을 차례로 부른다(없으면 그대로 적용한다).
func conflictThen(then ...func(putCall) error) func(putCall) error {
	n := 0
	return func(c putCall) error {
		n++
		switch {
		case n == 1:
			return ErrConflict
		case n-2 < len(then):
			return then[n-2](c)
		}
		return nil
	}
}

// 409(계획 4.5 A1 결정 9 · J14) — If-None-Match 409 는 즉시 한 번 다시 보낸다. If-Match 409 는 같은 P3 마감 안에서
// Head 로 ETag 를 다시 보고, P.ETag 그대로일 때만 한 번 다시 보낸다. 다르면 갱신 412 처럼 P 를 저장된 판으로
// 바꾸고 포기한다(update_412) · 없으면 update_404 · Head 실패면 head_failed(화해 예약). 다시 보내도 409 면
// put_conflict 다(적용되지 않았다).
func TestPutConflictRetryForms(t *testing.T) {
	t.Run("If-None-Match_즉시_1회", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		store.setOnPut(conflictThen())
		out := l.tick(t.Context(), 0, 5)
		puts := store.putCalls()
		if !out.Published || len(puts) != 2 || puts[1].cond != IfAbsent() || len(logs.aborts()) != 0 {
			t.Errorf("틱 = %+v · PUT %+v · 로그 %v, want 두 번째 IfAbsent 로 발행 · 로그 없음", out, puts, logs.aborts())
		}
		if n := len(store.headKeys()); n != 1 {
			t.Errorf("Head %d번, want 1(획득 뒤 화해뿐 — If-None-Match 409 는 Head 없이 다시 보낸다)", n)
		}
	})
	t.Run("If-Match_ETag_그대로면_1회", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		first := l.mustPublish(t.Context(), 0, 5)
		store.setOnPut(conflictThen())
		out := l.tick(t.Context(), 0, 6)
		puts, heads := store.putCalls(), store.headCalls()
		if !out.Published || len(puts) != 3 || puts[2].cond != IfMatch(first.State.Prev.ETag) || len(logs.aborts()) != 0 {
			t.Fatalf("틱 = %+v · PUT %d번 · 로그 %v, want 같은 IfMatch 로 다시 보내 발행", out, len(puts), logs.aborts())
		}
		if len(heads) != 2 || !heads[1].deadline.Equal(puts[1].deadline) || !puts[2].deadline.Equal(puts[1].deadline) {
			t.Errorf("재확인 Head %+v · PUT 마감 %v · %v, want Head 와 재시도가 첫 PUT 과 같은 P3 마감 안", heads, puts[1].deadline, puts[2].deadline)
		}
	})
	t.Run("If-Match_ETag_다르면_포기", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		other := otherVersion(t, l, 0, 6, 9)
		store.setOnPut(func(c putCall) error {
			plant(store, other)
			store.setOnPut(nil)
			return ErrConflict
		})
		out := l.tick(t.Context(), 0, 7)
		if out.Published || len(store.putCalls()) != 2 {
			t.Errorf("틱 = %+v · PUT %d번, want 발행 없음 · 이 틱 1번(다시 보내지 않음)", out, len(store.putCalls()))
		}
		if got := logs.aborts(); !slices.Equal(got, []string{reasonUpdate412}) {
			t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonUpdate412)
		}
		if p := out.State.Prev; p == nil || p.ETag != bodyETag(other.body) {
			t.Errorf("P = %+v, want 저장된 판(재확인 Head 로 바꿈)", p)
		}
	})
	t.Run("If-Match_Head_없음", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		store.setOnPut(func(putCall) error {
			remove(store, soloKey)
			store.setOnPut(nil)
			return ErrConflict
		})
		out := l.tick(t.Context(), 0, 6)
		if got := logs.aborts(); !slices.Equal(got, []string{reasonUpdate404}) || out.State.Prev != nil {
			t.Errorf("로그 %v · P %+v, want [%s] · P 비움", got, out.State.Prev, reasonUpdate404)
		}
	})
	t.Run("If-Match_Head_실패", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		failing := &headFailStore{fakeStore: store, fail: errors.New("403 Forbidden")}
		l.pub.store = failing
		store.setOnPut(func(putCall) error {
			failing.armed = true
			store.setOnPut(nil)
			return ErrConflict
		})
		out := l.tick(t.Context(), 0, 6)
		if got := logs.abortRecs(); len(got) != 1 || got[0].attrs["reason"] != reasonHeadFailed || got[0].level.String() != "ERROR" {
			t.Errorf("로그 %+v, want head_failed ERROR 한 줄", got)
		}
		if out.Published || !out.State.ReconcileDue {
			t.Errorf("상태 = %+v, want 발행 없음 · 화해 예약", out)
		}
	})
	t.Run("다시_보내도_409", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		store.setOnPut(conflictThen(func(putCall) error { return ErrConflict }))
		out := l.tick(t.Context(), 0, 5)
		if got := logs.aborts(); !slices.Equal(got, []string{reasonPutConflict}) || out.Published || len(store.putCalls()) != 2 {
			t.Errorf("로그 %v · 틱 %+v · PUT %d번, want [%s] · 발행 없음 · 2번", got, out, len(store.putCalls()), reasonPutConflict)
		}
		if n := len(store.headKeys()); n != 1 {
			t.Errorf("Head %d번, want 1(적용되지 않았으니 화해하지 않는다)", n)
		}
	})
}

// headFailStore 는 armed 가 참인 동안 Head 를 fail 로 끊는 저장소다 — 403 · 시한 초과처럼 Head 가 실패하는 국면.
type headFailStore struct {
	*fakeStore
	fail  error
	armed bool
}

func (s *headFailStore) Head(ctx context.Context, key string) (Stat, error) {
	if s.armed {
		return Stat{}, s.fail
	}
	return s.fakeStore.Head(ctx, key)
}

// etagStore 는 PUT 응답의 ETag 만 etag 로 바꾸는 저장소다 — 쓰기는 가짜에 그대로 적용되는데 비정상 엔드포인트가 받을
// 수 없는 ETag 를 돌려준 국면이다. Head 는 저장된 판의 실제 ETag 를 돌려준다.
type etagStore struct {
	*fakeStore
	etag string
}

func (s *etagStore) Put(ctx context.Context, key string, cond Precondition, body []byte, meta map[string]string) (string, error) {
	if _, err := s.fakeStore.Put(ctx, key, cond, body, meta); err != nil {
		return "", err
	}
	return s.etag, nil
}

// PUT 응답의 ETag 도 믿을 수 없는 입력이다(보안 r4 M-1) — 받을 수 없는 ETag(1–1024바이트의 인쇄 가능 ASCII 밖)를
// 돌려받으면 결과 모름으로 다룬다(put_unknown — 쓰기는 적용됐을 수 있다). P4 는 그 값을 DB manifest_etag 에 쓰지
// 않고, 화해의 Head 가 저장된 판으로 P 와 DB 를 맞춘다.
func TestPutWithUnusableETagIsUnknownResult(t *testing.T) {
	for name, etag := range map[string]string{
		"1025바이트": `"` + strings.Repeat("a", 1023) + `"`,
		"제어_문자":   "\"ab\x00cd\"",
	} {
		t.Run(name, func(t *testing.T) {
			l, store, logs := newSoloLoop(t, 10)
			l.mustPublish(t.Context(), 0, 5)
			l.pub.store = &etagStore{fakeStore: store, etag: etag}

			out := l.tick(t.Context(), 0, 6)

			if got := logs.aborts(); !slices.Equal(got, []string{reasonPutUnknown}) || out.Published {
				t.Errorf("로그 %v · 틱 %+v, want [%s] · 발행 없음", got, out, reasonPutUnknown)
			}
			applied := store.objects[soloKey]
			if p := out.State.Prev; p == nil || p.ETag != applied.etag {
				t.Errorf("화해 뒤 P = %+v, want 저장된 판(ETag %s)", p, applied.etag)
			}
			if got := strOrNil(readSession(t, l.pub.pool, "S").etag); got != applied.etag {
				t.Errorf("DB ETag = %.40q, want 저장된 판의 %s(받을 수 없는 값을 싣지 않는다)", got, applied.etag)
			}
		})
	}
}

// indoubt_put_reconciled_before_next_base(계획 4.5 A1 「멱등」 · 결정 2) — PUT 이 적용됐는데 응답을 잃으면(결과
// 모름 — put_unknown) 화해의 Head 가 새 판의 메타로 P 를 되살리고 R3 가 DB 를 그 판에 맞춘다(base = pc-disc-seq ·
// ETag · 세대 · 마지막 seq). 다음 틱은 그 판 위에 쓴다. 적용 전에 끊긴 변형은 P 가 그대로다.
// 사슬 픽스처의 축출 틱(DISC-SEQ 5 → 6)에서 적용된 뒤 응답을 잃으면 저장된 판의 pc-disc-seq 6 이 직전 P4 가 쓴 DB
// base 5 보다 크다 — R3 는 base 를 그 값으로 올린다(대입 — min 이 아니다, 체크리스트 A-3 3).
func TestIndoubtPutReconciledBeforeNextBase(t *testing.T) {
	t.Run("적용_뒤_응답_유실", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		store.setOnPut(func(c putCall) error {
			store.setOnPut(nil)
			if _, err := store.applyPut(c); err != nil {
				t.Errorf("주입 훅의 applyPut 실패: %v", err)
			}
			return context.DeadlineExceeded
		})

		out := l.tick(t.Context(), 0, 6)

		if got := logs.aborts(); !slices.Equal(got, []string{reasonPutUnknown}) {
			t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonPutUnknown)
		}
		applied := store.objects[soloKey]
		if p := out.State.Prev; p == nil || p.ETag != applied.etag || p.PublishedSeq != 6 || p.Gen != 2 {
			t.Fatalf("화해 뒤 P = %+v, want 적용된 판(세대 2 · 마지막 seq 6)", p)
		}
		row := readSession(t, l.pub.pool, "S")
		if strOrNil(row.etag) != applied.etag || row.gen != 2 || row.pubSeq != 6 || row.base != 0 {
			t.Errorf("화해 뒤 DB %+v(ETag %s), want 적용된 판의 ETag · 세대 2 · published_seq 6 · base 0", row, strOrNil(row.etag))
		}
		next := l.mustPublish(t.Context(), 0, 7)
		if puts := store.putCalls(); puts[len(puts)-1].cond != IfMatch(applied.etag) || next.State.Prev.Gen != 3 {
			t.Errorf("다음 틱의 조건 %+v · 세대 %d, want IfMatch(적용된 판) · 3", puts[len(puts)-1].cond, next.State.Prev.Gen)
		}
	})
	t.Run("축출_틱_적용_뒤_응답_유실", func(t *testing.T) {
		l, store, logs := newChainLoop(t, 1600)
		l.mustPublish(t.Context(), 100, 999)   // DISC-SEQ 5 — P4 가 DB base 5 를 쓴다
		var beforeR3 dbSession                 // 축출 틱의 P0 뒤 · R3 앞의 행(put_unknown 은 P4 없이 곧바로 화해한다)
		store.setOnPut(func(c putCall) error { // 축출 틱의 PUT 은 적용되고 응답만 잃는다
			store.setOnPut(nil)
			beforeR3 = readSession(t, l.pub.pool, "S")
			if _, err := store.applyPut(c); err != nil {
				t.Errorf("주입 훅의 applyPut 실패: %v", err)
			}
			return context.DeadlineExceeded
		})

		out := l.tick(t.Context(), 101, 1000) // seq 100 의 표시가 빠져 DISC-SEQ 6

		if got := logs.aborts(); !slices.Equal(got, []string{reasonPutUnknown}) {
			t.Fatalf("중단 · 포기 로그 %v, want [%s]", got, reasonPutUnknown)
		}
		if beforeR3.base != 5 {
			t.Fatalf("화해 앞 DB base = %d, want 5(저장된 판의 pc-disc-seq 6 보다 작아야 대입과 min 이 갈린다)", beforeR3.base)
		}
		row := readSession(t, l.pub.pool, "S")
		if p := out.State.Prev; p == nil || p.DiscontinuitySequence != 6 || row.base != 6 {
			t.Errorf("화해 뒤 P %+v · DB base %d, want 저장된 판의 pc-disc-seq 6 · 6", p, row.base)
		}
	})
	t.Run("적용_전_끊김", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		first := l.mustPublish(t.Context(), 0, 5)
		store.setOnPut(func(putCall) error {
			store.setOnPut(nil)
			return errors.New("connection reset")
		})

		out := l.tick(t.Context(), 0, 6)

		if got := logs.aborts(); !slices.Equal(got, []string{reasonPutUnknown}) || !samePrev(out.State.Prev, first.State.Prev) {
			t.Errorf("로그 %v · P %+v, want [%s] · P 그대로", got, out.State.Prev, reasonPutUnknown)
		}
	})
}

// P4 0행(설계 4.4.3 P4) — PUT 뒤 확정 CAS 가 0행이면(세대가 그 사이 올랐다 — 다른 writer) 발행이 서지 않은 것이라
// 곧바로 화해한다(p4_no_row). 올린 판은 저장소에 있으므로 화해가 그 판을 P 로 삼고 DB 를 맞춘다. 세대 · 마지막 seq 는
// max 다(R3) — Head 가 본 판의 값(세대 2 · pc-pub-seq 6)이 다른 writer 가 앞세운 DB 값보다 작으면 DB 가 그대로다.
func TestP4NoRowReconcilesImmediately(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	l.mustPublish(t.Context(), 0, 5)
	store.setOnPut(func(c putCall) error {
		store.setOnPut(nil)
		exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_gen = manifest_gen + 5, published_seq = published_seq + 100 WHERE session_id = 'S'`)
		return nil
	})

	out := l.tick(t.Context(), 0, 6)

	if got := logs.aborts(); !slices.Equal(got, []string{reasonP4NoRow}) || out.Published {
		t.Errorf("로그 %v · 틱 %+v, want [%s] · 발행 없음", got, out, reasonP4NoRow)
	}
	applied := store.objects[soloKey]
	if p := out.State.Prev; p == nil || p.ETag != applied.etag {
		t.Errorf("화해 뒤 P = %+v, want 올린 판", p)
	}
	row := readSession(t, l.pub.pool, "S")
	if strOrNil(row.etag) != applied.etag || row.gen != 7 || out.State.Gen != 7 {
		t.Errorf("화해 뒤 DB 세대 %d · ETag %s · 상태 세대 %d, want 7(max) · 올린 판 · 7", row.gen, strOrNil(row.etag), out.State.Gen)
	}
	if row.pubSeq != 105 {
		t.Errorf("화해 뒤 DB published_seq = %d, want 105(DB 값 그대로 — Head 의 pc-pub-seq 6 보다 크다)", row.pubSeq)
	}
}

// P4 가 0행이 아니라 DB 오류로 끝나면 결과 모름이다(서버가 확정을 커밋했는지 모른다) — 사유 p4_unknown 으로 CAS
// 거부(p4_no_row)와 가른다(r4 cx 지적 2). 처치는 0행과 같다: 발행이 서지 않은 것으로 보고 곧바로 화해한다 — 올린
// 판은 저장소에 있으므로 화해가 그 판을 P 로 삼고 DB 를 맞춘다. 오류 문자열은 err 속성이다.
func TestP4ErrorIsUnknownResult(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	l.mustPublish(t.Context(), 0, 5)
	fault := injectUpdateFault(t, l.pub.pool, "true")
	store.setOnPut(onceBefore(fault.arm)) // P0 뒤 · P4 전 — 다음 UPDATE = P4

	out := l.tick(t.Context(), 0, 6)

	recs := logs.abortRecs()
	if out.Published || len(recs) != 1 || recs[0].attrs["reason"] != reasonP4Unknown || recs[0].level != slog.LevelWarn ||
		!strings.Contains(recs[0].attrs["err"], "pc_test_fault") {
		t.Errorf("틱 = %+v · 로그 %+v, want 발행 없음 · p4_unknown(WARN · err = 주입한 서버 오류) 한 줄", out, recs)
	}
	applied := store.objects[soloKey]
	if p := out.State.Prev; p == nil || p.ETag != applied.etag || out.State.ReconcileDue {
		t.Errorf("화해 뒤 상태 %+v, want P = 올린 판 · 화해 끝", out.State)
	}
	if got := strOrNil(readSession(t, l.pub.pool, "S").etag); got != applied.etag {
		t.Errorf("화해 뒤 DB ETag = %s, want 올린 판 %s", got, applied.etag)
	}
}
