package publish

// writer fence(설계 6.2 · 계획 부기 40 · 체크리스트 A-4) — 획득 · lazy 갱신 · 반납 · 다중 세션 보유. PG 통합이다
// (PG_DSN 이 없으면 skip).

import (
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// 획득 조건(설계 6.2) — writer_fence 가 비었거나 · lease 가 끝났거나 · 이미 내 것이면 얻는다. 다른 writer 가 lease
// 안에서 쥐고 있으면 얻지 못한다(정상 경쟁 — 로그 없음). 얻으면 fence_expires_at 이 now() + lease 다.
func TestFenceAcquireConditions(t *testing.T) {
	cases := []struct {
		name  string
		setup string // 회차 S 의 fence 를 이 상태로 둔다(빈 문자열이면 DDL 기본값 — 빈 fence)
		want  bool
	}{
		{name: "빈_fence", want: true},
		{name: "내_fence", setup: `UPDATE stream_sessions SET writer_fence = 'w-me', fence_expires_at = now() + interval '4 seconds'`, want: true},
		{name: "남의_fence_lease_안", setup: `UPDATE stream_sessions SET writer_fence = 'w-other', fence_expires_at = now() + interval '4 seconds'`, want: false},
		{name: "남의_fence_lease_끝", setup: `UPDATE stream_sessions SET writer_fence = 'w-other', fence_expires_at = now() - interval '1 second'`, want: true},
		{name: "반납된_fence", setup: `UPDATE stream_sessions SET writer_fence = NULL, fence_expires_at = now() + interval '4 seconds'`, want: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			pool := newPool(t)
			insertSessions(t, pool, soloFixture(0))
			if tc.setup != "" {
				exec(t, pool, tc.setup)
			}
			pub := newPublisher(t, pool, newFakeStore(t), "w-me", &logRecorder{})

			got, err := pub.acquire(t.Context(), "S")
			if err != nil {
				t.Fatalf("acquire 오류: %v", err)
			}
			if got != tc.want {
				t.Errorf("acquire = %v, want %v", got, tc.want)
			}
			row := readSession(t, pool, "S")
			wantFence := "w-other"
			if tc.want {
				wantFence = "w-me"
			}
			if strOrNil(row.fence) != wantFence {
				t.Errorf("writer_fence = %s, want %s", strOrNil(row.fence), wantFence)
			}
			if tc.want && (row.expires == nil || time.Until(*row.expires) < 3*time.Second) {
				t.Errorf("얻은 뒤 fence_expires_at = %v, want 약 now() + 5초", row.expires)
			}
		})
	}
}

// lazy 갱신(설계 6.2) — 마지막 갱신(P0 · 획득 · 갱신)에서 3.5초가 지났을 때만 lease 를 늘린다. 그 전에는 DB 에
// 아무것도 보내지 않는다(주 갱신은 P0 이 겸한다). 평시 빈도는 커밋 7 루프의 일정이다 — 마지막 갱신 뒤 3.5초가
// 지난 holdTick 이 다음 P0 보다 먼저 오면 한 번 돌아 틱당 최대 1회다(틱 간격과 발사 순서에 따라 0 또는 1).
// fence 를 쥐지 않은 상태면 늘릴 것이 없다.
func TestRenewFenceOnlyWhenDue(t *testing.T) {
	pool := newPool(t)
	insertSessions(t, pool, soloFixture(0))
	clock := newStepClock()
	pub := newPublisher(t, pool, newFakeStore(t), "w-me", &logRecorder{})
	pub.now = clock.Now
	if ok, err := pub.acquire(t.Context(), "S"); !ok || err != nil {
		t.Fatalf("acquire = (%v, %v), want (true, nil)", ok, err)
	}
	st := State{FenceHeld: true, RenewedAt: clock.now}
	before := readSession(t, pool, "S").expires

	clock.now = st.RenewedAt.Add(3 * time.Second)
	out := pub.RenewFence(t.Context(), st, "S")
	if got := readSession(t, pool, "S").expires; !got.Equal(*before) || out.State != st || out.Err != nil {
		t.Errorf("3초 뒤 RenewFence = (%+v, lease 끝 %v), want 상태 그대로 · lease 끝 %v(보내지 않음)", out, got, before)
	}

	clock.now = st.RenewedAt.Add(3500 * time.Millisecond)
	out = pub.RenewFence(t.Context(), st, "S")
	if got := readSession(t, pool, "S").expires; !got.After(*before) || !out.State.FenceHeld || !out.State.RenewedAt.Equal(clock.now) || out.Err != nil {
		t.Errorf("3.5초 뒤 RenewFence = (%+v, lease 끝 %v), want lease 가 %v 뒤로 · RenewedAt = %v", out, got, before, clock.now)
	}

	idle := State{RenewedAt: st.RenewedAt}
	if out := pub.RenewFence(t.Context(), idle, "S"); out.State != idle || out.Err != nil {
		t.Errorf("fence 없는 상태의 RenewFence = %+v, want 상태 그대로", out)
	}
}

// fence lease 만료(검증 표 단위) — lease 가 끝난 사이 다른 writer 가 fence 를 얻으면 이 writer 의 갱신은 0행이다.
// 갱신 결과는 fence 를 잃었다고 알린다 — 다음 틱이 획득부터 다시 한다.
func TestRenewFenceDetectsLoss(t *testing.T) {
	pool := newPool(t)
	insertSessions(t, pool, soloFixture(0))
	clock := newStepClock()
	me := newPublisher(t, pool, newFakeStore(t), "w-me", &logRecorder{})
	me.now = clock.Now
	if ok, err := me.acquire(t.Context(), "S"); !ok || err != nil {
		t.Fatalf("acquire = (%v, %v)", ok, err)
	}
	expireLease(t, pool, "S")
	other := newPublisher(t, pool, newFakeStore(t), "w-other", &logRecorder{})
	if ok, err := other.acquire(t.Context(), "S"); !ok || err != nil {
		t.Fatalf("다른 writer 의 acquire = (%v, %v), want (true, nil) — lease 가 끝났다", ok, err)
	}

	clock.now = clock.now.Add(4 * time.Second)
	out := me.RenewFence(t.Context(), State{FenceHeld: true, RenewedAt: clock.now.Add(-4 * time.Second)}, "S")
	if out.State.FenceHeld || out.Err != nil {
		t.Errorf("fence 를 잃은 뒤 RenewFence = %+v, want FenceHeld 거짓", out)
	}
	if got := strOrNil(readSession(t, pool, "S").fence); got != "w-other" {
		t.Errorf("writer_fence = %s, want w-other(갱신이 남의 fence 를 건드리지 않는다)", got)
	}
}

// 반납(설계 6.2 · 계획 4.5 A1 「반납 예외」) — shutdown 때 이 writer 가 쥔 fence 를 모두 NULL 로 둔다. 다중 세션
// 보유를 허용하므로(설계 6.2) 두 회차를 쥔 채 반납하면 둘 다 풀린다. 반납 뒤에는 lease 끝을 기다리지 않고 다른
// writer 가 얻는다. 남의 fence 는 건드리지 않는다.
func TestReleaseFreesEveryFenceOfTheWriter(t *testing.T) {
	pool := newPool(t)
	insertSession(t, pool, "str", rewind.Session{ID: "S", TargetDuration: 6}, "live")
	insertSession(t, pool, "str2", rewind.Session{ID: "S2", TargetDuration: 6}, "live")
	insertSession(t, pool, "str3", rewind.Session{ID: "S3", TargetDuration: 6}, "live")
	me := newPublisher(t, pool, newFakeStore(t), "w-me", &logRecorder{})
	other := newPublisher(t, pool, newFakeStore(t), "w-other", &logRecorder{})
	for _, id := range []string{"S", "S2"} {
		if ok, err := me.acquire(t.Context(), id); !ok || err != nil {
			t.Fatalf("acquire(%s) = (%v, %v)", id, ok, err)
		}
	}
	if ok, err := other.acquire(t.Context(), "S3"); !ok || err != nil {
		t.Fatalf("다른 writer 의 acquire(S3) = (%v, %v)", ok, err)
	}

	if err := me.Release(t.Context()); err != nil {
		t.Fatalf("Release 오류: %v", err)
	}

	for _, id := range []string{"S", "S2"} {
		if got := readSession(t, pool, id).fence; got != nil {
			t.Errorf("반납 뒤 %s 의 writer_fence = %s, want NULL", id, *got)
		}
	}
	if got := strOrNil(readSession(t, pool, "S3").fence); got != "w-other" {
		t.Errorf("반납 뒤 S3 의 writer_fence = %s, want w-other(남의 fence 는 그대로)", got)
	}
	if ok, err := other.acquire(t.Context(), "S"); !ok || err != nil {
		t.Errorf("반납 직후 다른 writer 의 acquire(S) = (%v, %v), want (true, nil) — lease 끝을 기다리지 않는다", ok, err)
	}
}
