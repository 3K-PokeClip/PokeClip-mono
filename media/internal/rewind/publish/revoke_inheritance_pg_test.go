package publish

// 4D round_split 의 계승 해제 작업(POK-196 M6-3b 커밋 ③ — 설계 M6 3.4 「계승 해제」 · 9절 D55 첫 세 갈래). 루프가 계승
// 해제 받은편지함을 비우면 새 회차 첫 세션 B 의 스트림 차선에 이 작업을 낸다. fence → 저장소 Head 화해 → 객체가 없을
// 때(R4)만 P2′ 다. 객체가 있거나 Head 가 실패하면 해제하지 않고 다시 시도하지도 않는다(D55 한계 — 접두 최대 1시간).

import (
	"errors"
	"testing"
)

// D55 첫째 갈래 — 첫 PUT 전(DB ETag NULL · 객체 없음)이면 계승을 푼다(inherits NULL · base 0).
func TestRevokeInheritanceBeforeFirstPutRevokesD55(t *testing.T) {
	l, store, _ := newChainLoop(t, 610)
	out := l.pub.RevokeInheritance(t.Context(), fxStream, "S")
	if !out.Revoked || out.Err != nil {
		t.Fatalf("해제 = %+v, want 해제됨", out)
	}
	if d := readSession(t, l.pub.pool, "S"); d.inherits != nil || d.base != 0 {
		t.Fatalf("S (계승, base) = (%s, %d), want (NULL, 0)", strOrNil(d.inherits), d.base)
	}
	if len(store.putCalls()) != 0 {
		t.Fatalf("해제 작업이 PUT 을 냈다(%d)", len(store.putCalls()))
	}
}

// D55 둘째 갈래 — 첫 PUT 뒤(목록이 S3 에 있음)면 해제하지 않는다. 이미 발행한 목록의 DISC-SEQ 를 되돌리면 시청자 목록이
// 역행한다.
func TestRevokeInheritanceAfterFirstPutKeepsD55(t *testing.T) {
	l, _, _ := newChainLoop(t, 610)
	l.mustPublish(t.Context(), 100, 610)
	out := l.pub.RevokeInheritance(t.Context(), fxStream, "S")
	if out.Revoked || out.Reason != RevokePublished {
		t.Fatalf("해제 = %+v, want 유지(published)", out)
	}
	if d := readSession(t, l.pub.pool, "S"); strOrNil(d.inherits) != "P" || d.base != 5 {
		t.Fatalf("S (계승, base) = (%s, %d), want (P, 5) 그대로", strOrNil(d.inherits), d.base)
	}
}

// D55 셋째 갈래 — P4 실패로 DB ETag 는 NULL 인데 객체는 있다(PUT 은 섰고 확정 CAS 만 잃음). DB 만 보면 첫 PUT 전으로
// 읽히므로 Head 로 저장소를 먼저 본다 — 객체가 있으면 해제하지 않는다. 변이 = Head 선행 제거 → 이 갈래가 해제해 FAIL.
func TestRevokeInheritanceKeepsWhenObjectExistsDespiteNullETagD55(t *testing.T) {
	l, _, _ := newChainLoop(t, 610)
	l.mustPublish(t.Context(), 100, 610)
	exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_etag = NULL WHERE session_id = 'S'`)
	out := l.pub.RevokeInheritance(t.Context(), fxStream, "S")
	if out.Revoked || out.Reason != RevokePublished {
		t.Fatalf("해제 = %+v, want 유지(published — 저장소에 객체가 있다)", out)
	}
	if d := readSession(t, l.pub.pool, "S"); strOrNil(d.inherits) != "P" {
		t.Fatalf("S 계승 = %s, want P 그대로", strOrNil(d.inherits))
	}
}

// 다른 writer 가 lease 안에서 fence 를 쥐었으면 해제하지 않는다(fence) — 그 writer 의 틱이 S4 로 계승을 다룬다.
func TestRevokeInheritanceWithoutFenceKeeps(t *testing.T) {
	l, _, _ := newChainLoop(t, 610)
	exec(t, l.pub.pool, `UPDATE stream_sessions SET writer_fence = 'w-other', fence_expires_at = now() + interval '1 minute' WHERE session_id = 'S'`)
	out := l.pub.RevokeInheritance(t.Context(), fxStream, "S")
	if out.Revoked || out.Reason != RevokeFence {
		t.Fatalf("해제 = %+v, want 유지(fence)", out)
	}
}

// Head 가 실패하면 해제하지 않는다(head_failed · 다시 시도하지 않음 — D55 한계).
func TestRevokeInheritanceHeadFailureKeeps(t *testing.T) {
	l, store, _ := newChainLoop(t, 610)
	l.pub.store = &headFailStore{fakeStore: store, fail: errors.New("head 503"), armed: true}
	out := l.pub.RevokeInheritance(t.Context(), fxStream, "S")
	if out.Revoked || out.Reason != RevokeHeadFailed || out.Err == nil {
		t.Fatalf("해제 = %+v, want 유지(head_failed · 오류 있음)", out)
	}
	if d := readSession(t, l.pub.pool, "S"); strOrNil(d.inherits) != "P" {
		t.Fatalf("S 계승 = %s, want P 그대로", strOrNil(d.inherits))
	}
}
