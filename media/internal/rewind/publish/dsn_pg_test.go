package publish

// DISC-SEQ 절대식과 재기동 복원(계획 4.5 A1 결정 1 · 2 · 5 · 6 · 7 — r40 · r41 테스트)과 화해 경계(설계 3.2 ·
// 커밋 2 리뷰 인계) — fake Store + PG 통합이다. 사슬 픽스처(O ← P ← S)의 1시간 창(900조각)이 한 칸씩 밀리며 끊김
// 표시(P 의 첫 조각 100 · S 의 첫 조각 600)가 목록 앞에서 빠진다.

import (
	"bytes"
	"errors"
	"log/slog"
	"slices"
	"strings"
	"testing"
	"time"
)

// 축출 Tick PG 통합(뮤테이션 26) — 창이 밀려 끊김 표시가 목록 앞에서 빠질 때마다 DISC-SEQ 가 그 수만큼 오르고(P 의
// DISC-SEQ + 빠진 표시 — 루프가 절대식으로 넘긴 값), P4 가 DB base 에 그 값을 쓴다. 이어진 두 판에 함께 실린 조각의
// DSN 은 바뀌지 않는다(RFC 8216bis-22 6.2.2).
func TestEvictionTickAdvancesDiscontinuitySequence(t *testing.T) {
	l, store, _ := newChainLoop(t, 1600)
	steps := []struct {
		from, to int64
		want     int64
	}{
		{100, 999, 5},  // 첫 판 — 개시 base(결정 6)
		{101, 1000, 6}, // 100 의 표시가 빠짐
		{102, 1001, 6}, // 빠진 표시 없음
		{601, 1500, 7}, // 600 의 표시가 빠짐(접두도 다 빠졌다)
	}
	var prevBody []byte
	for _, s := range steps {
		out := l.mustPublish(t.Context(), s.from, s.to)
		body, _ := stored(store, chainKey)
		if got := out.State.Prev.DiscontinuitySequence; got != s.want {
			t.Errorf("창 [%d, %d] 의 DISC-SEQ = %d, want %d", s.from, s.to, got, s.want)
		}
		if got := readSession(t, l.pub.pool, "S").base; got != s.want {
			t.Errorf("창 [%d, %d] 뒤 DB base = %d, want %d(P4 가 쓴다)", s.from, s.to, got, s.want)
		}
		if prevBody != nil {
			sameCommonDSN(t, prevBody, body)
		}
		prevBody = body
	}
}

// publish_retry_after_failed_put_keeps_dsn(계획 4.5 A1 「멱등」 · 뮤테이션 66) — PUT 이 실패한 틱은 P 를 바꾸지
// 않는다. 그래서 다시 낸 틱의 DISC-SEQ 는 실패한 틱과 같은 값이고(발행 실패만 겪은 재시도에서 DISC-SEQ 가 변하지
// 않는다 — 6.4 음성 대조), 올라간 판과 직전 판에 함께 실린 조각의 DSN 은 같다.
func TestPublishRetryAfterFailedPutKeepsDSN(t *testing.T) {
	l, store, _ := newChainLoop(t, 1600)
	l.mustPublish(t.Context(), 100, 999)
	l.mustPublish(t.Context(), 101, 1000)
	before, _ := stored(store, chainKey)
	failedIn := l.input(102, 1001)
	store.setOnPut(func(putCall) error { store.setOnPut(nil); return errors.New("connection reset") })
	if out := l.tick(t.Context(), 102, 1001); out.Published {
		t.Fatalf("실패 주입 틱 = %+v, want 발행 없음", out)
	}

	retryIn := l.input(102, 1001)
	retried := l.mustPublish(t.Context(), 102, 1001)

	if retryIn.DiscontinuitySequence != failedIn.DiscontinuitySequence || retried.State.Prev.DiscontinuitySequence != 6 {
		t.Errorf("DISC-SEQ 실패 틱 %d · 재시도 %d · 올라간 판 %d, want 6 · 6 · 6",
			failedIn.DiscontinuitySequence, retryIn.DiscontinuitySequence, retried.State.Prev.DiscontinuitySequence)
	}
	after, _ := stored(store, chainKey)
	sameCommonDSN(t, before, after)
}

// restart_between_p0_and_put_keeps_dsn(계획 4.5 A1 결정 1 · 5 · 뮤테이션 26 · 66) — writer A 가 P0 을 커밋한 뒤 PUT
// 전에 죽는다. 재기동한 writer B(새 토큰 · 영값 상태)는 A 의 lease 가 끝난 뒤 fence 를 얻고 Head 메타로 P 를
// 되살린 다음, 그 P 로 DISC-SEQ 를 센다. 올라간 판과 A 의 마지막 판에 함께 실린 조각의 DSN 은 같다.
func TestRestartBetweenP0AndPutKeepsDSN(t *testing.T) {
	a, store, _ := newChainLoop(t, 1600)
	a.mustPublish(t.Context(), 100, 999)
	a.mustPublish(t.Context(), 101, 1000)
	before, _ := stored(store, chainKey)
	store.setOnPut(func(putCall) error { store.setOnPut(nil); return errors.New("process killed") })
	a.tick(t.Context(), 102, 1001) // P0 은 커밋됐고 PUT 은 닿지 않았다 — 이 뒤로 A 는 없다
	expireLease(t, a.pub.pool, "S")

	bLogs := &logRecorder{}
	b := a.peer("w-restarted", bLogs)
	first := b.tick(t.Context(), 102, 1001) // 획득 → 화해(P 를 Head 메타로) → P 가 바뀌어 여기서 끝
	if first.Published || first.State.Prev == nil || first.State.Prev.PublishedSeq != 1000 {
		t.Fatalf("재기동 첫 틱 = %+v, want 발행 없음 · P = A 의 마지막 판(마지막 seq 1000)", first)
	}
	out := b.mustPublish(t.Context(), 102, 1001)

	if out.State.Prev.DiscontinuitySequence != 6 {
		t.Errorf("재기동 뒤 DISC-SEQ = %d, want 6", out.State.Prev.DiscontinuitySequence)
	}
	if got := bLogs.aborts(); len(got) != 0 {
		t.Errorf("재기동한 writer 의 중단 · 포기 로그 %v, want 없음(옛 P 로 센 DISC-SEQ 로 렌더하지 않는다)", got)
	}
	after, _ := stored(store, chainKey)
	sameCommonDSN(t, before, after)
}

// restart_restores_published_from_head_meta(계획 4.5 A1 결정 5 · 뮤테이션 67) — 재기동 뒤 P 의 원천은 Head 메타
// 8키다. 되살린 P 는 발행한 writer 가 든 P 와 같다(자기기술 여덟 성분 · ETag). 그 P 가 prev 라서, 재기동한 캐시가
// MSN 이 뒤로 간 창을 내도 S2 가 발행을 멈춘다 — prev 를 nil 로 두면 견줄 것 없이 통과한다. 화해는 본문을 읽지
// 않는다(Head 만 — 설계 3.2 경계 규약).
func TestRestartRestoresPublishedFromHeadMeta(t *testing.T) {
	a, store, _ := newSoloLoop(t, 1000)
	published := a.mustPublish(t.Context(), 1, 900).State.Prev
	expireLease(t, a.pub.pool, "S")
	logs := &logRecorder{}
	b := a.peer("w-restarted", logs)

	first := b.tick(t.Context(), 0, 899)
	if p := first.State.Prev; p == nil || *p != *published {
		t.Fatalf("되살린 P = %+v, want 발행한 writer 의 P %+v", p, published)
	}
	second := b.tick(t.Context(), 0, 899)

	wantHalt(t, second, logs, store, 1, reasonValidate, "S2")
	if gets := store.getKeys(); len(gets) != 0 {
		t.Errorf("본문 읽기 %v, want 없음(화해는 Head 만)", gets)
	}
}

// restart_skips_identical_body_via_meta_hash(계획 4.5 A1 결정 7 · 뮤테이션 104) — 재기동한 writer 의 첫 렌더가
// 저장된 판과 같으면 올리지 않는다. 판정 근거는 Head 메타의 pc-body-sha256 이다 — 루프 메모리의 직전 본문은 재기동
// 뒤에 없다.
func TestRestartSkipsIdenticalBodyViaMetaHash(t *testing.T) {
	a, store, _ := newSoloLoop(t, 10)
	a.mustPublish(t.Context(), 0, 5)
	expireLease(t, a.pub.pool, "S")
	b := a.peer("w-restarted", &logRecorder{})

	b.tick(t.Context(), 0, 5) // 획득 → 화해(P 가 바뀌어 여기서 끝)
	out := b.tick(t.Context(), 0, 5)

	if out.Published || len(store.putCalls()) != 1 {
		t.Errorf("재기동 뒤 같은 본문 틱 = %+v · PUT %d번, want 올리지 않음 · 1번", out, len(store.putCalls()))
	}
}

// R1 Head 에도 마감 있는 ctx 를 넘긴다(커밋 2 리뷰 인계 · 판단 J8) — 마감은 T_pub(2초)다. Store 는 스스로 시간을
// 재지 않고, S3Store 가 쓰는 SDK 기본 HTTP 클라이언트에는 타임아웃이 없다.
func TestReconcileHeadHasPublishTimeoutDeadline(t *testing.T) {
	l, store, _ := newSoloLoop(t, 10)
	before := time.Now()
	l.mustPublish(t.Context(), 0, 5) // 획득 뒤 화해의 Head
	after := time.Now()

	heads := store.headCalls()
	if len(heads) != 1 {
		t.Fatalf("Head %d번, want 1", len(heads))
	}
	d := heads[0].deadline
	if d.Before(before.Add(2*time.Second)) || d.After(after.Add(2*time.Second)) {
		t.Errorf("Head ctx 마감 = %v, want 부른 시각 + 2초([%v, %v])", d, before.Add(2*time.Second), after.Add(2*time.Second))
	}
}

// R2 메타 엄격 파싱(계획 4.5 A1 결정 5 · 커밋 2 리뷰 인계) — 저장된 판의 메타가 하나라도 없거나 해석에 실패하면
// 발행을 멈춘다(meta_invalid · ERROR). P 는 nil 로 두지 않고 그대로 두며, 화해는 다음 틱에 다시 한다 — 메타가 고쳐질
// 때까지 PUT 은 없다.
func TestReconcileMetaInvalidHaltsAndKeepsPrev(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	published := l.mustPublish(t.Context(), 0, 5).State.Prev
	store.mu.Lock()
	store.objects[soloKey].meta[metaDiscSeq] = "-1"
	store.mu.Unlock()
	l.st.ReconcileDue = true

	first := l.tick(t.Context(), 0, 6)
	second := l.tick(t.Context(), 0, 6)

	for i, out := range []Outcome{first, second} {
		if out.Published || out.State.Prev == nil || *out.State.Prev != *published || !out.State.ReconcileDue {
			t.Errorf("틱 %d = %+v, want 발행 없음 · P 그대로 · 화해 예약 유지", i+1, out)
		}
	}
	if len(store.putCalls()) != 1 {
		t.Errorf("PUT %d번, want 1(메타가 틀린 동안 올리지 않는다)", len(store.putCalls()))
	}
	if got := logs.aborts(); !slices.Equal(got, []string{reasonMetaInvalid}) {
		t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonMetaInvalid)
	}
}

// 화해의 Head 응답은 믿을 수 없는 입력이다(보안 r4 M-1) — 메타 정수는 JavaScript 안전 정수 한계(2^53−1) 안, ETag
// 는 1–1024바이트의 인쇄 가능 ASCII 일 때만 받는다. 벗어나면 해석 실패와 같다(meta_invalid · 발행 정지 · P 유지).
// DB 는 그 값을 싣지 않는다 — int64 최대값 한 번이 R3 의 GREATEST 로 DB 세대에 박혀 P0 의 +1 이 넘치고(SQLSTATE
// 22003) 객체를 고쳐도 풀리지 않던 경로(보안 탐침 B)가 닫힌다.
func TestReconcileRejectsOutOfRangeHead(t *testing.T) {
	cases := []struct {
		name  string
		spoil func(o *fakeObject)
	}{
		{"pc-gen_int64_최대", func(o *fakeObject) { o.meta[metaGen] = "9223372036854775807" }},
		{"pc-disc-seq_2^53", func(o *fakeObject) { o.meta[metaDiscSeq] = "9007199254740992" }},
		{"ETag_1025바이트", func(o *fakeObject) { o.etag = `"` + strings.Repeat("a", 1023) + `"` }},
		{"ETag_제어_문자", func(o *fakeObject) { o.etag = "\"ab\ncd\"" }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs := newSoloLoop(t, 10)
			published := l.mustPublish(t.Context(), 0, 5).State.Prev
			before := readSession(t, l.pub.pool, "S")
			store.mu.Lock()
			obj := store.objects[soloKey]
			tc.spoil(&obj)
			store.objects[soloKey] = obj
			store.mu.Unlock()
			l.st.ReconcileDue = true

			out := l.tick(t.Context(), 0, 6)

			if out.Published || out.State.Prev == nil || *out.State.Prev != *published || !out.State.ReconcileDue {
				t.Errorf("틱 = %+v, want 발행 없음 · P 그대로 · 화해 예약 유지", out)
			}
			if got := logs.aborts(); !slices.Equal(got, []string{reasonMetaInvalid}) {
				t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonMetaInvalid)
			}
			after := readSession(t, l.pub.pool, "S")
			if after.gen != before.gen || strOrNil(after.etag) != strOrNil(before.etag) || after.base != before.base || after.pubSeq != before.pubSeq {
				t.Errorf("DB 세대 · ETag · base · published_seq = %d · %.40s · %d · %d, want 그대로 %d · %.40s · %d · %d",
					after.gen, strOrNil(after.etag), after.base, after.pubSeq, before.gen, strOrNil(before.etag), before.base, before.pubSeq)
			}
		})
	}
}

// 쓰는 쪽 대칭 검사(보안 r5 M1 · r6 M1) — 읽는 쪽 위끝의 끝값(2^53−1)을 실은 위조 Head 가 한 번 오면 R3 가 그 값을 DB 와
// P 에 싣는다(읽는 쪽이 받는 값이다). 그 위에서 쓰는 쪽이 세대에 1 을 더하거나(P0) DISC-SEQ 에 축출 수를 더하면 위끝을
// 넘는다. 축출이 없어도 목록에 끊김 표시가 남아 있으면 끝 조각의 DSN(DISC-SEQ + 목록 안 표시 수 — RFC 8216bis-22 6.2.1)이
// 위끝을 넘는다. 끝값 base 를 복사받은 새 회차(TD 분할)의 첫 판도 같다. 그런 판은 올리지 않고 그 스트림 발행을 멈춘다
// (meta_out_of_range · ERROR — 같은 사유는 한 줄). PUT 이 없으므로 시청자가 받는 목록은 그대로다. DB 에 실린 끝값은 남는다 —
// 이 검사는 그것을 풀지 않는다.
func TestWriterRefusesMetaBeyondReaderBound(t *testing.T) {
	cases := []struct {
		name    string
		arrange func(t *testing.T) (*loop, *fakeStore, *logRecorder) // 검사가 설 틱 바로 앞의 루프
		next    [2]int64                                             // 그 틱의 창
		wantErr string                                               // err 속성에 든 말
	}{
		// 다음 P0 이 세대 2^53 을 예약한다.
		{"세대", forgedBoundaryHead(newSoloLoop, [2]int64{0, 5}, metaGen), [2]int64{0, 6}, "pc-gen=9007199254740992"},
		// 창이 밀려 seq 100 앞의 끊김 표시가 빠진다 — DISC-SEQ = 2^53−1 + 1.
		{"DISC-SEQ_축출_틱", forgedBoundaryHead(newChainLoop, [2]int64{100, 999}, metaDiscSeq), [2]int64{101, 1000},
			"pc-disc-seq=9007199254740992"},
		// 빠지는 표시가 없어 DISC-SEQ 는 2^53−1 그대로다 — 창에 남은 표시 둘(seq 100 · 600)이 끝 조각 DSN 을 2^53+1 로 민다.
		{"DISC-SEQ_비축출_틱", forgedBoundaryHead(newChainLoop, [2]int64{100, 998}, metaDiscSeq), [2]int64{100, 999},
			"끊김 표시 2 = 9007199254740993"},
		// P 가 없어 첫 판의 DISC-SEQ 가 복사받은 base 다 — 표시 둘이 끝 조각 DSN 을 2^53+1 로 민다.
		{"끝값_base_새_회차", boundaryBaseSession, [2]int64{100, 999}, "끊김 표시 2 = 9007199254740993"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs := tc.arrange(t)
			puts := len(store.putCalls())
			listBefore, _ := stored(store, soloKey)

			for range 2 {
				if out := l.tick(t.Context(), tc.next[0], tc.next[1]); out.Published || out.Err != nil {
					t.Errorf("틱 = %+v, want 발행 없음 · Err 없음", out)
				}
			}

			if n := len(store.putCalls()) - puts; n != 0 {
				t.Errorf("PUT %d번, want 0(위끝을 넘는 판은 올리지 않는다)", n)
			}
			if listAfter, _ := stored(store, soloKey); !bytes.Equal(listAfter, listBefore) {
				t.Errorf("시청자가 받는 목록이 바뀌었다:\n%.300s\nwant 그대로:\n%.300s", listAfter, listBefore)
			}
			recs := logs.abortRecs()
			if len(recs) != 1 || recs[0].attrs["reason"] != reasonMetaOutOfRange || recs[0].level != slog.LevelError ||
				!strings.Contains(recs[0].attrs["err"], tc.wantErr) {
				t.Errorf("로그 %+v, want %s(ERROR · err 에 %q) 한 줄", recs, reasonMetaOutOfRange, tc.wantErr)
			}
		})
	}
}

// forgedBoundaryHead 는 창 first 를 한 번 발행한 뒤, 저장된 판의 메타 metaKey 를 읽는 쪽 위끝의 끝값(2^53−1)으로 바꾼 Head 를
// 한 번 화해한 루프를 세운다 — R3 가 끝값을 DB 와 P 에 싣고 P 가 바뀌어 그 틱은 끝난다. 위조는 그 응답에만 있었다(저장소의
// 판은 되돌린다).
func forgedBoundaryHead(newLoop func(*testing.T, int64) (*loop, *fakeStore, *logRecorder), first [2]int64, metaKey string) func(*testing.T) (*loop, *fakeStore, *logRecorder) {
	return func(t *testing.T) (*loop, *fakeStore, *logRecorder) {
		t.Helper()
		l, store, logs := newLoop(t, 1600)
		l.mustPublish(t.Context(), first[0], first[1])
		store.mu.Lock()
		meta := store.objects[soloKey].meta
		genuine := meta[metaKey]
		meta[metaKey] = "9007199254740991"
		store.mu.Unlock()
		l.st.ReconcileDue = true
		l.tick(t.Context(), first[0], first[1])
		store.mu.Lock()
		meta[metaKey] = genuine
		store.mu.Unlock()
		return l, store, logs
	}
}

// boundaryBaseSession 은 끝값 base(2^53−1)를 복사받은 새 회차 S 의 루프를 세운다 — TD 분할이 live 회차의 base 를 새 회차에
// 그대로 복사한 국면이다(session/registry.go). S 는 아직 발행한 판이 없다.
func boundaryBaseSession(t *testing.T) (*loop, *fakeStore, *logRecorder) {
	t.Helper()
	l, store, logs := newChainLoop(t, 1600)
	for _, id := range []string{"P", "S"} {
		s := l.fx.sessions[id]
		s.DiscontinuityBase = 9007199254740991
		l.fx.sessions[id] = s
	}
	exec(t, l.pub.pool, `UPDATE stream_sessions SET discontinuity_base = 9007199254740991 WHERE session_id IN ('P', 'S')`)
	return l, store, logs
}

// R1 Head 실패(403 포함 · 계획 7절 8) — Reconcile 이 서지 않으면 Head 가 돌아올 때까지 그 스트림 발행이 선다
// (head_failed · ERROR). Head 가 돌아오면 화해하고 같은 틱에서 발행한다.
func TestHeadFailureStallsPublicationUntilHeadReturns(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	l.mustPublish(t.Context(), 0, 5)
	failing := &headFailStore{fakeStore: store, fail: errors.New("403 Forbidden"), armed: true}
	l.pub.store = failing
	l.st.ReconcileDue = true

	for range 2 {
		if out := l.tick(t.Context(), 0, 6); out.Published || !out.State.ReconcileDue {
			t.Errorf("Head 실패 중 틱 = %+v, want 발행 없음 · 화해 예약 유지", out)
		}
	}
	failing.armed = false
	l.mustPublish(t.Context(), 0, 6)

	if got := logs.aborts(); !slices.Equal(got, []string{reasonHeadFailed}) {
		t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonHeadFailed)
	}
}
