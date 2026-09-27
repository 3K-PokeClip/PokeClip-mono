package publish

// G8-P — stale writer 가 최신 계보를 덮지 못한다(계획 4.5 A1 「증명 — G8-P 는 조건부 쓰기로 선다」 ①–④ · 부기 39).
// fake Store(ETag = 본문 해시 · 늦은 적용 주입) + PG 통합이다. 좀비 W 의 PUT 은 붙잡아 두었다가(holdPut) 원하는
// 순간에 적용한다 — S3 가 받은 요청을 얼마 안에 적용하는지(δ)는 가정하지 않는다. 동시 writer 2개 -race 본체는
// 커밋 8 이다(판단 J1) — 여기는 순서를 고정한 결정적 교차다.

import (
	"context"
	"errors"
	"testing"
	"time"
)

// zombieSetup 은 W 가 창 0..5 를 발행한 뒤 다음 틱(창 0..6)의 PUT 을 붙잡힌 채 lease 를 잃고, 후임 B 가 fence 를
// 얻어 화해를 마친 국면이다 — W 의 요청은 아직 적용되지 않았다. B 의 메모리 P 는 W 가 올린 판이다.
func zombieSetup(t *testing.T) (w, b *loop, store *fakeStore, held *putCall, p *Manifest) {
	t.Helper()
	w, store, _ = newSoloLoop(t, 50)
	p = w.mustPublish(t.Context(), 0, 5).State.Prev
	held = new(putCall)
	store.setOnPut(holdPut(store, held))
	w.tick(t.Context(), 0, 6) // W: P0 → PUT 붙잡힘(결과 모름) — 이 뒤로 W 는 멈춘다
	expireLease(t, w.pub.pool, "S")
	b = w.peer("w-successor", &logRecorder{})
	if out := b.tick(t.Context(), 0, 5); out.Published || out.State.Prev == nil || *out.State.Prev != *p {
		t.Fatalf("후임의 첫 틱 = %+v, want 획득 · 화해(P = W 의 판) · 올리지 않음(같은 본문)", out)
	}
	return w, b, store, held, p
}

// zombie_write_is_successor_or_412(G8 증명 ①) — 좀비 W 의 PUT(If-Match: P.ETag)은 저장된 판의 본문이 W 의 P 와 같을
// 때만 통과한다. 후임이 다른 본문 Q 를 먼저 올렸으면 412 로 막혀 Q 가 남고, 저장된 판이 아직 P 면 통과하되 그 판은
// P 를 prev 로 S1–S7 을 통과한 P 의 후속이다(세대 · MSN · 마지막 seq 가 P 보다 뒤로 가지 않는다).
func TestZombieWriteIsSuccessorOr412(t *testing.T) {
	t.Run("후임이_먼저_씀", func(t *testing.T) {
		_, b, store, held, _ := zombieSetup(t)
		q := b.mustPublish(t.Context(), 0, 7).State.Prev

		if _, err := store.applyPut(*held); !errors.Is(err, ErrPreconditionFailed) {
			t.Errorf("늦게 닿은 좀비 쓰기 = %v, want ErrPreconditionFailed", err)
		}
		if _, etag := stored(store, soloKey); etag != q.ETag {
			t.Errorf("저장된 판 ETag = %s, want 후임의 Q %s", etag, q.ETag)
		}
	})
	t.Run("저장된_판이_P", func(t *testing.T) {
		_, _, store, held, p := zombieSetup(t)

		if _, err := store.applyPut(*held); err != nil {
			t.Fatalf("늦게 닿은 좀비 쓰기 = %v, want 통과(저장된 판 = P)", err)
		}
		d := storedDesc(t, store)
		if d.Gen <= p.Gen || d.MediaSequence < p.MediaSequence || d.PublishedSeq < p.PublishedSeq {
			t.Errorf("좀비의 판 %+v, want P %+v 의 후속(세대 · MSN · 마지막 seq 비후퇴)", d, p.Published)
		}
	})
}

// storedDesc 는 저장된 판의 자기기술이다.
func storedDesc(t *testing.T, f *fakeStore) Manifest {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	o := f.objects[soloKey]
	d, err := parseMeta(o.meta, "S")
	if err != nil {
		t.Fatalf("저장된 판의 메타 해석 실패: %v", err)
	}
	return Manifest{Published: d, ETag: o.etag}
}

// late_zombie_write_absorbed_by_next_p3(G8 증명 ④ · 뮤테이션 128) — 좀비의 쓰기가 후임 B 의 R1 뒤에 닿으면 저장된
// 판은 좀비의 판 Q 인데 B 의 P 는 옛 판이다. B 의 다음 P3 는 412 → 화해로 P := Q → 그다음 틱이 Q 위에 쓴다. 공통
// 조각의 DSN 은 그대로이고 DB 세대는 줄지 않는다.
func TestLateZombieWriteAbsorbedByNextP3(t *testing.T) {
	_, b, store, held, _ := zombieSetup(t)
	genBefore := readSession(t, b.pub.pool, "S").gen
	if _, err := store.applyPut(*held); err != nil {
		t.Fatalf("늦게 닿은 좀비 쓰기 실패: %v", err)
	}
	q := storedDesc(t, store)
	qBody, _ := stored(store, soloKey)

	absorbed := b.tick(t.Context(), 0, 7)
	if absorbed.Published || absorbed.State.Prev == nil || *absorbed.State.Prev != q {
		t.Fatalf("다음 변경 틱 = %+v, want 412 → 화해(P = 좀비의 판 %+v)", absorbed, q)
	}
	next := b.mustPublish(t.Context(), 0, 7)

	puts := store.putCalls()
	if puts[len(puts)-1].cond != IfMatch(q.ETag) || next.State.Prev.Gen <= q.Gen {
		t.Errorf("그다음 틱의 조건 %+v · 세대 %d, want IfMatch(Q) · Q 의 세대 %d 초과", puts[len(puts)-1].cond, next.State.Prev.Gen, q.Gen)
	}
	after, _ := stored(store, soloKey)
	sameCommonDSN(t, qBody, after)
	if genAfter := readSession(t, b.pub.pool, "S").gen; genAfter < genBefore {
		t.Errorf("DB 세대 %d → %d, want 줄지 않음(G8 증명 ③)", genBefore, genAfter)
	}
}

// late_zombie_write_with_unchanged_render_reconciles_on_next_change(계획 4.5 A1 〔r45〕) — 좀비의 쓰기 Q 가 B 의 R1
// 뒤에 닿고 B 의 렌더가 P 와 같으면 PUT 이 없다. 그동안 저장된 판은 P 의 검사된 후속 Q 이고 DB 세대는 줄지 않는다.
// 다음 변경 틱의 P3 가 412 → 화해로 P := Q 로 맞추고, 그다음 틱이 올린다.
func TestLateZombieWriteWithUnchangedRenderReconcilesOnNextChange(t *testing.T) {
	_, b, store, held, p := zombieSetup(t)
	if _, err := store.applyPut(*held); err != nil {
		t.Fatalf("늦게 닿은 좀비 쓰기 실패: %v", err)
	}
	q := storedDesc(t, store)

	quiet := b.tick(t.Context(), 0, 5) // B 의 렌더 = P
	if quiet.Published || len(store.putCalls()) != 2 || *quiet.State.Prev != *p {
		t.Errorf("변화 없는 틱 = %+v · PUT %d번, want 올리지 않음(P 그대로)", quiet, len(store.putCalls()))
	}
	if got := storedDesc(t, store); got != q {
		t.Errorf("저장된 판 = %+v, want 좀비의 판 Q %+v", got, q)
	}
	b.tick(t.Context(), 0, 7) // 412 → 화해
	if out := b.mustPublish(t.Context(), 0, 7); out.State.Prev.Gen <= q.Gen {
		t.Errorf("그다음 틱의 세대 %d, want Q 의 세대 %d 초과", out.State.Prev.Gen, q.Gen)
	}
}

// late_zombie_write_then_ending_leaves_checked_successor(계획 4.5 A1 〔r45〕 · G8 증명 ④) — 늦은 좀비 쓰기 뒤 회차가
// ending 이면 B 에게 다음 P3 가 없다. 저장된 판은 P 의 검사된 후속 Q 로 남고 DB 세대는 줄지 않는다. 두 국면을 가른다.
//
//	틱 없음    ending 회차에는 루프가 틱을 내지 않는다(커밋 4 의 가드) — DB manifest_etag 는 P.ETag 그대로이고
//	          화해는 M6 첫 쓰기가 한다(M6 인계)
//	늦은 틱    캐시가 늦어 틱이 오면 P0 이 state = 'live' 로 거부하고(PUT 0) 설계 4.4.3 대로 화해한다 — DB 가 저장된
//	          판 Q 에 맞춰질 뿐 Q 는 그대로다
func TestLateZombieWriteThenEndingLeavesCheckedSuccessor(t *testing.T) {
	setup := func(t *testing.T) (*loop, *fakeStore, *Manifest, Manifest, int64) {
		_, b, store, held, p := zombieSetup(t)
		genBefore := readSession(t, b.pub.pool, "S").gen
		if _, err := store.applyPut(*held); err != nil {
			t.Fatalf("늦게 닿은 좀비 쓰기 실패: %v", err)
		}
		exec(t, b.pub.pool, `UPDATE stream_sessions SET state = 'ending', ending_at = now() WHERE session_id = 'S'`)
		return b, store, p, storedDesc(t, store), genBefore
	}
	t.Run("틱_없음", func(t *testing.T) {
		b, store, p, q, genBefore := setup(t)
		row := readSession(t, b.pub.pool, "S")
		if got := storedDesc(t, store); got != q || strOrNil(row.etag) != p.ETag || row.gen < genBefore {
			t.Errorf("저장된 판 %+v · DB ETag %s · DB 세대 %d, want Q · P 의 %s · %d 이상", got, strOrNil(row.etag), row.gen, p.ETag, genBefore)
		}
	})
	t.Run("늦은_틱", func(t *testing.T) {
		b, store, _, q, genBefore := setup(t)
		for range 2 {
			b.tick(t.Context(), 0, 7)
		}
		row := readSession(t, b.pub.pool, "S")
		if len(store.putCalls()) != 2 {
			t.Errorf("PUT %d번, want 2(ending 뒤 PUT 0)", len(store.putCalls()))
		}
		if got := storedDesc(t, store); got != q || strOrNil(row.etag) != q.ETag || row.gen < genBefore {
			t.Errorf("저장된 판 %+v · DB ETag %s · DB 세대 %d, want Q · Q 의 %s(화해) · %d 이상", got, strOrNil(row.etag), row.gen, q.ETag, genBefore)
		}
	})
}

// adjacent_same_body_zombie_cannot_regress_meta_gen(계획 4.5 A1 결정 7 · 뮤테이션 102 — 가정 (δ) 위반 주입) — 인접한
// 같은 본문 판에서는 좀비가 메타 세대를 되돌리지 못한다. 후임 B 는 저장된 P 와 같은 본문을 다시 올리지 않으므로(결정
// 7), 늦게 닿은 좀비 W 의 쓰기(If-Match: P.ETag)는 P 위에 W 의 후속을 쓸 뿐이다 — 저장된 pc-gen 은 늘 앞으로만
// 간다. 결정 7 이 없으면 B 가 같은 본문을 새 세대로 다시 올리고(ETag 그대로), 그 위에 닿은 W 의 옛 세대가 메타
// 세대를 되돌린다.
func TestAdjacentSameBodyZombieCannotRegressMetaGen(t *testing.T) {
	_, b, store, held, p := zombieSetup(t)
	gens := []int64{storedGen(t, store, soloKey)}

	b.tick(t.Context(), 0, 5) // B 의 렌더 = P — 같은 본문
	gens = append(gens, storedGen(t, store, soloKey))
	if _, err := store.applyPut(*held); err != nil {
		t.Fatalf("늦게 닿은 좀비 쓰기 실패: %v", err)
	}
	gens = append(gens, storedGen(t, store, soloKey))

	for i := 1; i < len(gens); i++ {
		if gens[i] < gens[i-1] {
			t.Errorf("저장된 pc-gen 의 차례 %v 가 뒤로 갔다(P 의 세대 %d)", gens, p.Gen)
		}
	}
}

// aba_zombie_write_meta_gen_lags_until_next_write(계획 4.5 A1 〔r44 · r45〕 · 부기 39) — 본문이 P → Q → P′(P 와 같은
// 본문 — 꼬리 교정이 되돌아온 판 · 인접하지 않은 되돌림)로 가고, 그 위에 P 를 기준으로 붙잡혔던 좀비의 쓰기가 닿는다.
// ETag 가 본문만 반영하므로 통과해 S3 메타 pc-gen 이 DB 세대보다 뒤처진다 — 판정에 쓰지 않는 성분이다. 잃은 본문은
// 없다(좀비의 판은 P 의 후속이다). B 의 다음 P3 가 412 → 화해 → 그다음 성공 PUT 의 메타가 뒤처짐을 푼다.
func TestAbaZombieWriteMetaGenLagsUntilNextWrite(t *testing.T) {
	_, b, store, held, _ := zombieSetup(t)
	b.fx.rows[5].DurationMS = 3000 // 꼬리 교정 — Q
	b.mustPublish(t.Context(), 0, 5)
	b.fx.rows[5].DurationMS = 4000 // 되돌아온 교정 — P′(본문 = P)
	b.mustPublish(t.Context(), 0, 5)

	if _, err := store.applyPut(*held); err != nil {
		t.Fatalf("P′ 위에 늦게 닿은 좀비 쓰기 = %v, want 통과(ETag 가 P 와 같다)", err)
	}
	if meta, db := storedGen(t, store, soloKey), readSession(t, b.pub.pool, "S").gen; meta >= db {
		t.Errorf("좀비 쓰기 뒤 메타 pc-gen %d · DB 세대 %d, want 메타가 뒤처짐", meta, db)
	}

	b.tick(t.Context(), 0, 7) // 412 → 화해
	out := b.mustPublish(t.Context(), 0, 7)

	if meta, db := storedGen(t, store, soloKey), readSession(t, b.pub.pool, "S").gen; meta != db || out.State.Prev.Gen != db {
		t.Errorf("다음 성공 PUT 뒤 메타 pc-gen %d · DB 세대 %d, want 같다(뒤처짐이 풀림)", meta, db)
	}
}

// zombie_cannot_write_after_successor_acquires(계획 4.5 A1 결정 9 — 완화 · 뮤테이션 113) — W 가 P0 뒤 오래 멈춘 사이
// lease 를 잃고 후임 B 가 fence 를 얻었다. 깨어난 W 는 P3 앞 마감(m0 + 3초)이 지났으므로 PUT 하지 않는다 — B 가 아직
// PUT 하지 않아 저장된 판이 P 인 창에서 좀비의 다른 본문이 통과하는 빈도를 줄인다(정확성은 조건부 쓰기 — G8 증명).
func TestZombieCannotWriteAfterSuccessorAcquires(t *testing.T) {
	w, store, logs := newSoloLoop(t, 50)
	w.mustPublish(t.Context(), 0, 5)
	b := w.peer("w-successor", &logRecorder{})
	calls := 0
	m0 := time.Now()
	w.pub.now = func() time.Time {
		calls++
		if calls == 2 { // W 의 P3 앞 판정 — 그 사이 lease 가 끝나고 후임이 fence 를 얻었다
			expireLease(t, w.pub.pool, "S")
			if ok, err := b.pub.acquire(context.Background(), "S"); !ok || err != nil {
				t.Errorf("후임의 획득 = (%v, %v), want (true, nil)", ok, err)
			}
			return m0.Add(3500 * time.Millisecond)
		}
		return m0
	}

	out := w.tick(t.Context(), 0, 6)

	if out.Published || len(store.putCalls()) != 1 {
		t.Errorf("깨어난 좀비의 틱 = %+v · PUT %d번, want PUT 없음", out, len(store.putCalls()))
	}
	if got := logs.aborts(); len(got) != 1 || got[0] != reasonPublishDeadline {
		t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonPublishDeadline)
	}
	b.st = State{FenceHeld: true, ReconcileDue: true}
	b.tick(t.Context(), 0, 6) // 화해 — P 가 nil 에서 W 의 첫 판으로 바뀌어 이 틱은 여기서 끝
	if out := b.mustPublish(t.Context(), 0, 6); out.State.Prev.ETag == w.st.Prev.ETag {
		t.Error("후임이 발행한 판이 W 의 첫 판 그대로다")
	}
}

// G8 대조군(계획 6.4 「발행」 줄) — 정상 writer 는 stale 판정에 걸리지 않는다. 혼자 도는 writer 의 연속 틱은 모두
// 발행되고, 중단 · 포기 로그가 하나도 없다.
func TestNormalWriterIsNotJudgedStale(t *testing.T) {
	l, store, logs := newSoloLoop(t, 50)
	for to := int64(5); to < 25; to++ {
		l.mustPublish(t.Context(), 0, to)
	}
	if got := logs.aborts(); len(got) != 0 {
		t.Errorf("중단 · 포기 로그 %v, want 없음", got)
	}
	if n := len(store.headKeys()); n != 1 {
		t.Errorf("Head %d번, want 1(평시 틱에는 Head 가 없다 — 획득 뒤 화해뿐)", n)
	}
}

// if_match_etag_equals_db_manifest_etag_in_reachable_states(계획 4.5 A1 결정 8) — 도달 가능한 상태에서 루프의 P 와 DB
// manifest_etag 는 같다(둘 다 없음 포함). 발행에 성공하면 P4 가 같은 ETag 를 쓰고, 화해는 fence 를 쥔 채 두 쪽을 같은
// Head 값으로 맞춘다 — 같은 본문 생략 · 갱신 412 · 결과 모름 · P4 0행 · 객체 사라짐의 화해 뒤에도.
func TestIfMatchETagEqualsDBManifestETagInReachableStates(t *testing.T) {
	l, store, _ := newSoloLoop(t, 50)
	check := func(step string) {
		t.Helper()
		db := readSession(t, l.pub.pool, "S").etag
		if !sameSource(db, l.st.Prev) {
			t.Errorf("%s 뒤 DB ETag %s · P %+v, want 같은 판", step, strOrNil(db), l.st.Prev)
		}
	}
	check("시작")
	l.mustPublish(t.Context(), 0, 5)
	check("첫 판")
	l.tick(t.Context(), 0, 5)
	check("같은 본문 생략")
	store.setOnPut(onceBefore(func() { plant(store, otherVersion(t, l, 0, 6, 40)) }))
	l.tick(t.Context(), 0, 7)
	check("갱신 412 화해")
	store.setOnPut(func(c putCall) error {
		store.setOnPut(nil)
		_, _ = store.applyPut(c) // 적용 뒤 응답만 잃는다 — 결과는 화해가 확인한다
		return context.DeadlineExceeded
	})
	l.tick(t.Context(), 0, 8)
	check("결과 모름 화해")
	store.setOnPut(onceBefore(func() {
		exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_gen = manifest_gen + 3 WHERE session_id = 'S'`)
	}))
	l.tick(t.Context(), 0, 9)
	check("P4 0행 화해")
	remove(store, soloKey)
	l.tick(t.Context(), 0, 10)
	check("객체 사라짐 화해")
	l.mustPublish(t.Context(), 0, 10)
	check("다시 만든 첫 판")
}

// P4 는 fence 조건 안에서만 확정한다(설계 4.4.3 P4 · 6.2 「검증 = P0 · P4 의 CAS 안」 · 뮤테이션 11) — W 의 PUT 이
// 닿은 뒤 P4 전에 lease 가 끝나 후임 B 가 fence 를 얻었으면, W 의 P4 는 세대가 그대로여도 0행이다(p4_no_row). W 는
// DB 에 아무것도 쓰지 못한다 — DB 의 확정은 fence 를 쥔 쪽만 한다. W 의 화해도 fence 가 없어 R3 를 쓰지 않는다.
func TestP4RefusesZombieAfterTakeover(t *testing.T) {
	w, store, logs := newSoloLoop(t, 50)
	p := w.mustPublish(t.Context(), 0, 5).State.Prev
	b := w.peer("w-successor", &logRecorder{})
	store.setOnPut(onceBefore(func() {
		expireLease(t, w.pub.pool, "S")
		if ok, err := b.pub.acquire(context.Background(), "S"); !ok || err != nil {
			t.Errorf("후임의 획득 = (%v, %v), want (true, nil)", ok, err)
		}
	}))

	out := w.tick(t.Context(), 0, 6)

	if out.Published || out.State.FenceHeld {
		t.Errorf("좀비의 틱 = %+v, want 발행 불성립 · fence 없음", out)
	}
	if got := logs.aborts(); len(got) != 1 || got[0] != reasonP4NoRow {
		t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonP4NoRow)
	}
	if row := readSession(t, w.pub.pool, "S"); strOrNil(row.etag) != p.ETag || strOrNil(row.fence) != "w-successor" {
		t.Errorf("DB ETag %s · fence %s, want P 의 %s 그대로 · w-successor", strOrNil(row.etag), strOrNil(row.fence), p.ETag)
	}
}
