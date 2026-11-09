package publish

// 회차 writer 와 사슬 writer 의 실제 겹침 PG 시험이다(POK-549 PR 단계 · cx C3 M · 설계 r4 7절 R15). 순차 호출로 순서만 바꾼
// TestFreezeVODRoundAndChainWritersConverge 를 보탠다 — 한 writer 를 등재 전(꼬리 잠금) · PUT 뒤 확정 전(사슬 잠금)에 세워 둔
// 사이 다른 writer 가 끝까지 돈다. 새 함수(FreezeChainVOD)를 쓰므로 변이로 교정한다.

import (
	"bytes"
	"testing"
)

// overlapRound 은 회차 A 의 두 사슬이다 — 앞 사슬 A(문서 확정) · 마지막 사슬 B → C(C 가 B 를 계승 · 둘 다 freezing). 행은 A
// 0..4 · B 5..7 · C 8..9 다. 마지막 사슬 회원이 둘이라 확정이 전원에 한 판으로 서는지 본다.
func overlapRound(t *testing.T) (*Publisher, *fakeStore) {
	t.Helper()
	ss := []roundSess{
		{id: "A", round: "A", td: 6, vod: ResultFrozen, doc: true},
		{id: "B", round: "A", td: 8, vod: "freezing"},
		{id: "C", round: "A", inherits: "B", td: 8, vod: "freezing"},
	}
	rows := twoChainRows()
	rows[8].session, rows[9].session = "C", "C"
	return newRoundVOD(t, ss, rows)
}

// wantOneVersion 은 마지막 사슬 ids 전원의 VOD 열이 키 key 에 저장된 판 하나(ETag · SHA)와 범위 first–last 로 확정됐는지 본다.
func wantOneVersion(t *testing.T, p *Publisher, store *fakeStore, key string, first, last int64, ids ...string) {
	t.Helper()
	body, etag := stored(store, key)
	if body == nil {
		t.Fatalf("이긴 판의 키 %s 에 객체가 없다", key)
	}
	sum := sha256Of(body)
	for _, id := range ids {
		v := readVOD(t, p, id)
		if v.state != ResultFrozen || strOrNil(v.key) != key || strOrNil(v.etag) != etag || !bytes.Equal(v.sha, sum[:]) ||
			v.first == nil || *v.first != first || v.last == nil || *v.last != last {
			t.Errorf("%s VOD 열 = %+v(키 %s), want frozen · %s · 저장된 판의 ETag · SHA · 범위 %d–%d", id, v, strOrNil(v.key), key,
				first, last)
		}
	}
}

// round_vs_chain_writer_overlap(R15 · cx C3 M) — 한 writer(w-a)를 꼬리 잠금 문장 앞(등재 전) 또는 사슬 잠금 문장 앞(PUT 뒤
// 확정 전)에 세운 사이 다른 writer 가 동결을 끝내면, 선 writer 는 풀린 뒤 제 키에 문서를 쓰더라도 확정 0행으로 먼저 확정한
// 결과를 받는다. 마지막 사슬 전원(B · C)이 먼저 확정한 판 하나(키 · ETag · SHA · 범위)로 수렴하고, 늦은 쪽 문서는 제 키의 고아
// 하나다. 변이(확정 문장의 vod_state = 'freezing' 조건 제거)는 늦은 쪽이 덮어써 FAIL. 늦은 쪽 결과의 범위(Scope)는 제 경로가
// 아니라 채택한 문서의 범위다(PR 점검 r1 cx M1) — 고치기 전(92749335)은 늦은 쪽이 제 경로 범위를 돌려 FAIL.
func TestFreezeVODRoundAndChainWritersOverlap(t *testing.T) {
	const chainKey = "vod/str/B/index.m3u8"
	tests := []struct {
		name       string
		at         string // 선 writer 가 멈추는 문장
		roundWaits bool   // 참이면 회차 writer 가 서고 사슬 writer 가 먼저 확정한다
	}{
		{name: "회차_등재_전_사슬_먼저", at: vodLockTailSQL, roundWaits: true},
		{name: "회차_PUT_뒤_사슬_먼저", at: vodLockChainSQL, roundWaits: true},
		{name: "사슬_등재_전_회차_먼저", at: vodLockTailSQL},
		{name: "사슬_PUT_뒤_회차_먼저", at: vodLockChainSQL},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p, store := overlapRound(t)
			chain := chainOf("A", "B", "C")
			a, reached, release := blockedWriter(t, p, store, tt.at)
			done := make(chan FreezeOutcome, 1)
			go func() {
				if tt.roundWaits {
					done <- a.FreezeVOD(t.Context(), chain, false)
					return
				}
				done <- a.FreezeChainVOD(t.Context(), chain, false)
			}()
			waitReached(t, reached, "멈춤 문장")

			var first FreezeOutcome
			if tt.roundWaits {
				first = p.FreezeChainVOD(t.Context(), chain, false)
			} else {
				first = p.FreezeVOD(t.Context(), chain, false)
			}
			wantOutcome(t, first, AxisVOD, ResultFrozen, false)
			release()
			late := <-done
			wantOutcome(t, late, AxisVOD, ResultFrozen, false)

			key, lo, scope := roundDocKey, int64(0), ScopeRound
			if tt.roundWaits {
				key, lo, scope = chainKey, 5, ScopeChain
			}
			if first.Scope != scope || late.Scope != scope {
				t.Errorf("범위 = 먼저 %q · 늦은 쪽 %q, want 둘 다 %q(늦은 쪽은 채택한 문서의 범위)", first.Scope, late.Scope, scope)
			}
			wantOneVersion(t, p, store, key, lo, 9, "B", "C")
			if puts := store.putCalls(); len(puts) != 2 {
				t.Errorf("PUT %d번, want 2(두 키 — 늦은 쪽 고아 하나)", len(puts))
			}
			if front := readVOD(t, p, "A"); strOrNil(front.key) != "vod/str/A/index.m3u8" {
				t.Errorf("앞 사슬 A VOD 키 = %s, want 그대로", strOrNil(front.key))
			}
		})
	}
}
