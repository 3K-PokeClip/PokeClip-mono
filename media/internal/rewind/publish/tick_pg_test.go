package publish

// 발행 한 틱(설계 4.4.3 P0–P4 · 계획 4.5 A0 · A1 · 체크리스트 A-2)의 기본 경로와 P0 조건 — fake Store + PG
// 통합이다(PG_DSN 이 없으면 skip).

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"log/slog"
	"maps"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// soloKey 는 soloFixture 목록의 객체 키다.
const soloKey = "dvr/str/S/index.m3u8"

// newSoloLoop 은 계승 없는 회차 S(seq 0..lastSeq)의 발행 루프다 — PG 에 회차를 넣고 영값 상태에서 시작한다.
func newSoloLoop(t *testing.T, lastSeq int64) (*loop, *fakeStore, *logRecorder) {
	t.Helper()
	pool := newPool(t)
	f := soloFixture(lastSeq)
	insertSessions(t, pool, f)
	store, logs := newFakeStore(t), &logRecorder{}
	return &loop{t: t, pub: newPublisher(t, pool, store, "w-me", logs), fx: f}, store, logs
}

// 재기동 뒤 소유 회차가 가장 먼저 하는 일은 fence 획득과 Reconcile 이다(설계 6.2 · 계획 4.5 A1 결정 5 ·
// 체크리스트 A-4 1). 영값 상태의 첫 틱은 fence 를 얻고 곧바로 Head 로 화해한 뒤(객체 없음 — 최초 생성 경로)
// 같은 틱에서 첫 판을 IfAbsent 로 올린다. Head 는 그 화해 한 번뿐이다.
func TestTickAcquiresFenceThenReconcilesBeforeFirstPublish(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)

	out := l.mustPublish(t.Context(), 0, 5)

	if heads := store.headKeys(); !slices.Equal(heads, []string{soloKey}) {
		t.Errorf("Head 기록 = %v, want [%s](획득 직후 화해 한 번)", heads, soloKey)
	}
	puts := store.putCalls()
	if len(puts) != 1 || puts[0].cond != IfAbsent() {
		t.Fatalf("PUT 기록 = %+v, want IfAbsent 한 번", puts)
	}
	if !out.State.FenceHeld || out.State.ReconcileDue {
		t.Errorf("상태 = %+v, want fence 쥠 · 화해 끝", out.State)
	}
	if got := logs.aborts(); len(got) != 0 {
		t.Errorf("중단 · 포기 로그 %v, want 없음", got)
	}
}

// 다른 writer 가 lease 안에서 fence 를 쥐고 있으면 획득이 0행이다 — 정상 경쟁이라 로그를 남기지 않고, 저장소에도
// 닿지 않는다(Head · PUT 0). 상태는 fence 를 쥐지 않은 채 남아 다음 틱이 다시 얻으려 한다.
func TestTickYieldsToHeldFenceSilently(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	exec(t, l.pub.pool, `UPDATE stream_sessions SET writer_fence = 'w-other', fence_expires_at = now() + interval '5 seconds'`)

	out := l.tick(t.Context(), 0, 5)

	if out.Published || out.State.FenceHeld || out.Err != nil {
		t.Errorf("틱 = %+v, want 발행 없음 · fence 없음", out)
	}
	if n, m := len(store.headKeys()), len(store.putCalls()); n != 0 || m != 0 {
		t.Errorf("Head %d · PUT %d, want 0 · 0", n, m)
	}
	if got := logs.aborts(); len(got) != 0 {
		t.Errorf("중단 · 포기 로그 %v, want 없음(정상 경쟁)", got)
	}
}

// 기본 경로(설계 4.4.3 · 계획 4.5 A1 결정 5 · 8 · 체크리스트 A-2 9 · 11 · 13) — 첫 판은 IfAbsent, 다음 판은 직전
// 판의 ETag 로 IfMatch 다. PUT 에는 메타 8키가 실리고, P4 가 DB 에 ETag · DISC-SEQ · 마지막 seq 를 쓴다. 결과의
// P 는 (자기기술, ETag) 한 쌍이다. 평시 틱에는 Head 를 넣지 않는다(커밋 2 리뷰 인계 — 화해에서만).
func TestFirstPublishThenUpdate(t *testing.T) {
	l, store, _ := newSoloLoop(t, 10)

	first := l.mustPublish(t.Context(), 0, 5)
	second := l.mustPublish(t.Context(), 0, 6)

	puts := store.putCalls()
	if len(puts) != 2 {
		t.Fatalf("PUT %d번, want 2", len(puts))
	}
	if puts[0].cond != IfAbsent() || puts[1].cond != IfMatch(first.State.Prev.ETag) {
		t.Errorf("조건 = %+v · %+v, want IfAbsent · IfMatch(첫 판 ETag %s)", puts[0].cond, puts[1].cond, first.State.Prev.ETag)
	}
	body := store.objects[soloKey].body
	wantDesc := rewind.Published{Gen: 2, MediaSequence: 0, PublishedSeq: 6, SegmentCount: 7, BodySHA256: sha256.Sum256(body)}
	want := Manifest{Published: wantDesc, ETag: bodyETag(body)}
	if got := second.State.Prev; got == nil || *got != want {
		t.Errorf("둘째 틱의 P = %+v, want %+v", got, want)
	}
	wantMeta := map[string]string{
		"pc-gen": "2", "pc-pub-seq": "6", "pc-session": "S", "pc-terminal": "false",
		"pc-msn": "0", "pc-seg-count": "7", "pc-disc-seq": "0", "pc-body-sha256": encodeMeta(wantDesc, "S")["pc-body-sha256"],
	}
	if got := store.objects[soloKey].meta; !maps.Equal(got, wantMeta) {
		t.Errorf("저장된 메타 = %v, want %v", got, wantMeta)
	}
	row := readSession(t, l.pub.pool, "S")
	if row.gen != 2 || strOrNil(row.etag) != want.ETag || row.base != 0 || row.pubSeq != 6 {
		t.Errorf("DB = %+v(ETag %s), want 세대 2 · ETag %s · base 0 · published_seq 6", row, strOrNil(row.etag), want.ETag)
	}
	if heads := store.headKeys(); len(heads) != 1 {
		t.Errorf("Head %d번, want 1(첫 틱의 획득 뒤 화해뿐)", len(heads))
	}
	if !bytes.HasPrefix(body, []byte("#EXTM3U\n")) || !bytes.Contains(body, []byte(fxBaseURL+"/dvr/str/seg/000006.m4s")) {
		t.Errorf("저장된 본문이 창 0..6 의 렌더가 아니다:\n%s", body)
	}
}

// 빈 창(판단 J5 · 계획 4.5 B #11) — 세션 필터 뒤 행이 0 이면 낼 것이 없다. PUT 도 로그도 없다.
func TestEmptyWindowPublishesNothing(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	l.mustPublish(t.Context(), 0, 5)

	out := l.tick(t.Context(), 20, 30) // 장부에 없는 창

	if out.Published || out.Err != nil {
		t.Errorf("빈 창의 틱 = %+v, want 발행 없음 · 오류 없음", out)
	}
	if n := len(store.putCalls()); n != 1 {
		t.Errorf("PUT %d번, want 1(빈 창은 올리지 않는다)", n)
	}
	if got := logs.aborts(); len(got) != 0 {
		t.Errorf("중단 · 포기 로그 %v, want 없음", got)
	}
}

// p0Case 는 P0 조건 하나를 깨는 픽스처다 — 발행이 한 번 된 뒤 setup 을 돌리고 다음 틱을 낸다.
type p0Case struct {
	name  string
	setup func(l *loop)
}

// runP0Refusal 은 P0 이 0행이어야 하는 경우다 — 틱은 PUT 없이 포기하고 p0_no_row 를 한 번 남긴다.
func runP0Refusal(t *testing.T, tc p0Case) {
	t.Helper()
	l, store, logs := newSoloLoop(t, 10)
	l.mustPublish(t.Context(), 0, 5)
	tc.setup(l)

	out := l.tick(t.Context(), 0, 6)

	if out.Published {
		t.Errorf("틱 = %+v, want 발행 없음", out)
	}
	if n := len(store.putCalls()); n != 1 {
		t.Errorf("PUT %d번, want 1(P0 0행 틱은 올리지 않는다)", n)
	}
	if got := logs.aborts(); !slices.Equal(got, []string{reasonP0NoRow}) {
		t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonP0NoRow)
	}
}

// p0_refuses_ending_session(계획 4.5 A0 결정 3) — ending 회차는 세대 예약을 더 받지 않는다(P0 `state = 'live'`).
// 캐시가 늦게 갱신돼도 새지 않게 하는 DB 가드다.
func TestP0RefusesEndingSession(t *testing.T) {
	runP0Refusal(t, p0Case{"ending", func(l *loop) {
		exec(t, l.pub.pool, `UPDATE stream_sessions SET state = 'ending', ending_at = now() WHERE session_id = 'S'`)
	}})
}

// p0_refuses_session_without_init(계획 4.5 A2 결정 9) — init 이 올라가지 않은 회차는 예약을 받지 않는다(P0
// `init_uploaded_at IS NOT NULL` — G5 하드 가드). 발행 억제 게이트(커밋 4)의 캐시 비트 하나에만 기대지 않는다.
func TestP0RefusesSessionWithoutInit(t *testing.T) {
	runP0Refusal(t, p0Case{"init_없음", func(l *loop) {
		exec(t, l.pub.pool, `UPDATE stream_sessions SET init_uploaded_at = NULL WHERE session_id = 'S'`)
	}})
}

// P0 의 설계 다섯 조건(설계 4.4.3 · 검증 표 PG 행) — 회차 · writer_fence · lease 유효 · manifest_gen =
// $genSeen · dvr_state 'open'. 하나라도 어긋나면 0행이다. A0 · A2 의 둘(state · init)은 위 두 테스트가 본다.
func TestP0ReservationConditions(t *testing.T) {
	for _, tc := range []p0Case{
		{"남의_fence", func(l *loop) {
			exec(t, l.pub.pool, `UPDATE stream_sessions SET writer_fence = 'w-other' WHERE session_id = 'S'`)
		}},
		{"lease_끝남", func(l *loop) {
			exec(t, l.pub.pool, `UPDATE stream_sessions SET fence_expires_at = now() - interval '1 second' WHERE session_id = 'S'`)
		}},
		{"본_세대가_낡음", func(l *loop) {
			exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_gen = manifest_gen + 1 WHERE session_id = 'S'`)
		}},
		{"dvr_state_open_아님", func(l *loop) {
			exec(t, l.pub.pool, `UPDATE stream_sessions SET dvr_state = 'freezing' WHERE session_id = 'S'`)
		}},
		{"회차가_다름", func(l *loop) {
			l.fx.owner = "T" // 장부에 없는 회차의 목록 — 회차 조건이 0행을 낸다
			l.fx.sessions["T"] = rewind.Session{ID: "T", TargetDuration: 6, InitUploaded: true}
			for i := range l.fx.rows {
				l.fx.rows[i].SessionID = "T"
			}
		}},
	} {
		t.Run(tc.name, func(t *testing.T) { runP0Refusal(t, tc) })
	}
}

// R4-1 회귀(커밋 3 r4 · 계획 r51 커밋 순서 3 c3-fix-lease · 부기 41) — P0 은 lease 가 끝나지 않았으면 남은
// 시간을 따지지 않고 예약하고, 같은 문장에서 lease 를 now() + 5초로 새로 쓴다. 틱은 약 4초 간격이라 마지막 갱신
// 뒤 4초에 오는 틱은 남은 lease 가 1초다 — lazy 갱신(RenewFence) 없이도 그 틱이 발행하고 lease 를 이어 가야
// 한다. P0 이 남은 lease 에 여유를 요구하면(설계 4.4.3 원형의 2초) 이 틱은 0행으로 포기한다.
func TestP0RenewsNearlyExpiredLease(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	l.mustPublish(t.Context(), 0, 5)
	exec(t, l.pub.pool, `UPDATE stream_sessions SET fence_expires_at = now() + interval '1 second' WHERE session_id = 'S'`)

	out := l.tick(t.Context(), 0, 6)

	if !out.Published || out.Err != nil || len(store.putCalls()) != 2 {
		t.Errorf("남은 lease 1초의 틱 = %+v · PUT %d번, want 발행 · PUT 2번", out, len(store.putCalls()))
	}
	if got := logs.aborts(); len(got) != 0 {
		t.Errorf("중단 · 포기 로그 %v, want 없음(%s 0 — P0 이 예약한다)", got, reasonP0NoRow)
	}
	if row := readSession(t, l.pub.pool, "S"); row.expires == nil || time.Until(*row.expires) < 3*time.Second {
		t.Errorf("틱 뒤 fence_expires_at = %v, want 약 now() + 5초(P0 이 lease 를 새로 썼다)", row.expires)
	}
}

// identical_body_is_not_republished(계획 4.5 A1 결정 7) — 다시 렌더한 본문의 sha256 이 P 의 pc-body-sha256 과
// 같으면 P3 · P4 를 하지 않는다. 예약한 세대는 쓰지 않고 버린다(DB 세대만 오른다 — S6 은 gen > prev.Gen 만
// 본다). 그러면 성공한 PUT 은 모두 직전 판과 본문이 다르다(G8 증명 전제 (라)). 음성 대조(6.4): 본문이 바뀐
// 틱은 반드시 올린다.
func TestIdenticalBodyIsNotRepublished(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	first := l.mustPublish(t.Context(), 0, 5)

	same := l.tick(t.Context(), 0, 5)

	if same.Published || len(store.putCalls()) != 1 {
		t.Errorf("같은 본문 틱 = %+v · PUT %d번, want 발행 없음 · PUT 1번", same, len(store.putCalls()))
	}
	if !samePrev(same.State.Prev, first.State.Prev) {
		t.Errorf("같은 본문 틱 뒤 P = %+v, want 그대로 %+v", same.State.Prev, first.State.Prev)
	}
	row := readSession(t, l.pub.pool, "S")
	if row.gen != 2 || strOrNil(row.etag) != first.State.Prev.ETag {
		t.Errorf("DB 세대 %d · ETag %s, want 2(예약만 오른다) · %s", row.gen, strOrNil(row.etag), first.State.Prev.ETag)
	}
	if got := logs.aborts(); len(got) != 0 {
		t.Errorf("중단 · 포기 로그 %v, want 없음(같은 본문 생략은 남기지 않는다)", got)
	}

	changed := l.mustPublish(t.Context(), 0, 6)
	if puts := store.putCalls(); len(puts) != 2 || changed.State.Prev.Gen != 3 {
		t.Errorf("본문이 바뀐 틱 뒤 PUT %d번 · 세대 %d, want 2번 · 3", len(puts), changed.State.Prev.Gen)
	}
}

// etag_source_mismatch_aborts_tick(계획 4.5 A1 결정 8) — If-Match 의 출처는 P0 이 돌려준 DB manifest_etag 이고, 그
// 값이 루프의 P.ETag 와 같을 때만 P3 로 간다. 다르면 PUT 하지 않고 틱을 포기한 뒤 화해한다 — 화해가 P 와 DB 를
// 저장된 판 하나로 맞춘다.
func TestEtagSourceMismatchAbortsTick(t *testing.T) {
	cases := []struct {
		name  string
		drift func(l *loop)
	}{
		{"DB_ETag_가_다름", func(l *loop) {
			exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_etag = '"elsewhere"' WHERE session_id = 'S'`)
		}},
		{"루프가_P_를_잃음", func(l *loop) { l.st.Prev = nil }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs := newSoloLoop(t, 10)
			first := l.mustPublish(t.Context(), 0, 5)
			tc.drift(l)

			out := l.tick(t.Context(), 0, 6)

			if out.Published || len(store.putCalls()) != 1 {
				t.Errorf("틱 = %+v · PUT %d번, want 발행 없음 · PUT 1번", out, len(store.putCalls()))
			}
			if got := logs.aborts(); !slices.Equal(got, []string{reasonETagSourceMismatch}) {
				t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonETagSourceMismatch)
			}
			if !samePrev(out.State.Prev, first.State.Prev) || out.State.ReconcileDue {
				t.Errorf("화해 뒤 상태 = %+v, want P = 저장된 판 %+v · 화해 끝", out.State, first.State.Prev)
			}
			if got := strOrNil(readSession(t, l.pub.pool, "S").etag); got != first.State.Prev.ETag {
				t.Errorf("화해 뒤 DB ETag = %s, want %s", got, first.State.Prev.ETag)
			}
		})
	}
}

// p3_skipped_after_publish_deadline(계획 4.5 A1 결정 9 — 완화) — P3 앞에서 지금이 m0 + lease(5초) − T_pub(2초)
// 이후면 PUT 하지 않는다. 틱을 포기하고(예약 세대는 버린다) 화해를 예약한다 — RC-19 재시도는 타지 않는다.
// 다음 틱이 화해부터 하고 P 가 그대로면 같은 틱에서 발행한다. 경계 바로 앞(2.999초)은 올린다.
func TestP3SkippedAfterPublishDeadline(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	l.mustPublish(t.Context(), 0, 5)
	l.pub.now = newStepClock(3 * time.Second).Now // m0 = T · P3 앞 판정 = T + 3초

	late := l.tick(t.Context(), 0, 6)

	if late.Published || len(store.putCalls()) != 1 || !late.State.ReconcileDue {
		t.Errorf("마감 지난 틱 = %+v · PUT %d번, want 발행 없음 · PUT 1번 · 화해 예약", late, len(store.putCalls()))
	}
	if got := logs.aborts(); !slices.Equal(got, []string{reasonPublishDeadline}) {
		t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonPublishDeadline)
	}
	heads := len(store.headKeys())

	l.pub.now = newStepClock(2999 * time.Millisecond).Now
	next := l.mustPublish(t.Context(), 0, 6)
	if len(store.headKeys()) != heads+1 || next.State.ReconcileDue {
		t.Errorf("다음 틱의 Head %d번 늘고 상태 %+v, want 화해 한 번 뒤 발행", len(store.headKeys())-heads, next.State)
	}
}

// p3_deadline_anchored_at_p0_send(계획 4.5 A1 결정 9) — P3 의 ctx 마감은 min(P3 시작 + T_pub, m0 + lease − T_pub)
// 이다. P0 과 P3 사이가 2초 벌어지면 마감은 P3 시작 + 2초가 아니라 m0 + 3초다. 음성 대조(6.4): P0–P3 가 짧은
// 정상 경로의 마감은 설계 원형(P3 시작 + T_pub) 그대로다.
func TestP3DeadlineAnchoredAtP0Send(t *testing.T) {
	cases := []struct {
		name string
		gap  time.Duration // m0 → P3 시작
		want time.Duration // m0 → PUT ctx 마감
	}{
		{"P0_뒤_2초에_P3", 2 * time.Second, 3 * time.Second},
		{"정상_경로", 100 * time.Millisecond, 2100 * time.Millisecond},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, _ := newSoloLoop(t, 10)
			l.mustPublish(t.Context(), 0, 5)
			clock := newStepClock(tc.gap)
			m0 := clock.now
			l.pub.now = clock.Now

			l.mustPublish(t.Context(), 0, 6)

			puts := store.putCalls()
			if got := puts[len(puts)-1].deadline.Sub(m0); got != tc.want {
				t.Errorf("PUT ctx 마감 = m0 + %v, want m0 + %v", got, tc.want)
			}
		})
	}
}

// P0 0행 뒤 화해(설계 4.4.3 「0행이면 tick 포기 + Reconcile」) — 다른 쪽이 세대를 올려 $genSeen 이 낡았으면 P0 은
// 0행이다. 화해의 R3 가 DB 세대를 다시 읽어 오므로 다음 틱은 발행한다. 화해가 없으면 낡은 $genSeen 으로 영영 0행이다.
func TestP0NoRowReconcileRelearnsGeneration(t *testing.T) {
	l, _, _ := newSoloLoop(t, 10)
	l.mustPublish(t.Context(), 0, 5)
	exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_gen = manifest_gen + 4 WHERE session_id = 'S'`)

	refused := l.tick(t.Context(), 0, 6)
	next := l.mustPublish(t.Context(), 0, 6)

	if refused.Published || refused.State.Gen != 5 || next.State.Prev.Gen != 6 {
		t.Errorf("0행 틱 %+v · 다음 틱 세대 %d, want 화해로 본 세대 5 · 다음 판 세대 6", refused, next.State.Prev.Gen)
	}
}

// 주 갱신은 P0 이 겸한다(설계 6.2 · 체크리스트 A-4 2) — P0 을 보낸 시각(m0)이 lease 갱신 시각이라, P0 뒤 3.5초가
// 지나기 전에는 lazy 갱신이 DB 에 아무것도 보내지 않는다. 평시 빈도는 커밋 7 루프의 일정이다 — 마지막 갱신 뒤
// 3.5초가 지난 holdTick 이 다음 P0 보다 먼저 오면 한 번 돌아 틱당 최대 1회다(틱 간격과 발사 순서에 따라 0 또는 1).
func TestP0CountsAsFenceRenewal(t *testing.T) {
	l, _, _ := newSoloLoop(t, 10)
	clock := newStepClock()
	l.pub.now = clock.Now
	l.mustPublish(t.Context(), 0, 5) // 획득(T) · P0(T)
	clock.now = clock.now.Add(10 * time.Second)
	tickAt := clock.now
	l.mustPublish(t.Context(), 0, 6) // P0(T + 10초)

	before := readSession(t, l.pub.pool, "S").expires
	clock.now = tickAt.Add(3 * time.Second)
	if out := l.pub.RenewFence(t.Context(), l.st, "S"); out.State.RenewedAt != tickAt {
		t.Errorf("P0 뒤 3초의 RenewFence = %+v, want RenewedAt 그대로 %v(P0 이 갱신했다)", out.State, tickAt)
	}
	if got := readSession(t, l.pub.pool, "S").expires; !got.Equal(*before) {
		t.Errorf("P0 뒤 3초에 lease 끝이 %v → %v 로 바뀌었다, want 그대로", before, got)
	}
}

// 입력 결함(키 성분 · 렌더할 수 없는 목록)은 중단 · 포기 표 밖이라 Outcome.Err 로 돌려주고 로그하지 않는다(부른 쪽
// 몫). 키가 틀리면 DB · 저장소에 닿지 않는다. 렌더할 수 없는 목록(소유 회차가 회차 목록에 없다)은 올리지 않는다.
func TestTickReturnsInputDefectsAsErr(t *testing.T) {
	t.Run("키_성분", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		in := l.input(0, 5)
		in.Playlist.StreamID = "a/b"
		out := l.pub.Tick(t.Context(), l.st, in)
		if out.Err == nil || out.Published || len(store.headKeys())+len(store.putCalls()) != 0 || len(logs.aborts()) != 0 {
			t.Errorf("틱 = %+v · 저장소 호출 %d · 로그 %v, want Err · 저장소 0 · 로그 없음",
				out, len(store.headKeys())+len(store.putCalls()), logs.aborts())
		}
	})
	t.Run("렌더_불가", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		in := l.input(0, 6)
		in.Playlist.Sessions = nil
		out := l.pub.Tick(t.Context(), l.st, in)
		if out.Err == nil || out.Published || len(store.putCalls()) != 1 || len(logs.aborts()) != 0 {
			t.Errorf("틱 = %+v · PUT %d번 · 로그 %v, want Err · PUT 1번 · 로그 없음", out, len(store.putCalls()), logs.aborts())
		}
	})
}

// P0 이 0행이 아니라 DB 오류로 끝나면 결과 모름이다(서버가 예약을 커밋했는지 모른다) — 사유 p0_unknown 으로 CAS
// 거부(p0_no_row)와 가른다. DB 장애가 정상 경쟁처럼 보이지 않게 한다(r4 cx 지적 2). 처치는 0행과 같다: 틱을 포기하고
// 화해한다(설계 4.4.3). 화해의 R3 가 DB 세대를 다시 읽어 오므로 다음 틱은 발행한다. 오류 문자열은 err 속성이다.
func TestP0ErrorIsUnknownResult(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	l.mustPublish(t.Context(), 0, 5)
	injectUpdateFault(t, l.pub.pool, "true").arm() // 다음 UPDATE = 이 틱의 P0
	heads := len(store.headKeys())

	out := l.tick(t.Context(), 0, 6)

	recs := logs.abortRecs()
	if out.Published || len(recs) != 1 || recs[0].attrs["reason"] != reasonP0Unknown || recs[0].level != slog.LevelWarn ||
		!strings.Contains(recs[0].attrs["err"], "pc_test_fault") {
		t.Errorf("틱 = %+v · 로그 %+v, want 발행 없음 · p0_unknown(WARN · err = 주입한 서버 오류) 한 줄", out, recs)
	}
	if n := len(store.headKeys()) - heads; n != 1 || out.State.ReconcileDue || out.Err != nil {
		t.Errorf("Head %d번 · 상태 %+v · Err %v, want 화해 한 번으로 끝남", n, out.State, out.Err)
	}
	l.mustPublish(t.Context(), 0, 6)
}

// 루프가 끝나는 중이면(부른 쪽 ctx — 루프 수명 ctx 가 취소됐다) 도는 틱은 실패 갈래에서 로그도 화해도 남기지 않고
// Outcome.Err 에 그 ctx 오류를 싣고 끝난다(r4 cc 지적 3). ctx 가 끊은 실패는 저장소 · DB 의 상태를 말하지 않는다 —
// 배포 때마다 도는 스트림 수만큼 거짓 head_failed ERROR · 결과 모름 WARN 이 나지 않는다. 다음 프로세스는 fence 를
// 얻자마자 화해한다(설계 6.2 · 체크리스트 A-4 1). 틱의 실패 갈래 전부(획득 · R1 · P0 · P2′ · P3 · 409 재확인 · P4)다.
func TestCancelledTickEndsWithoutLogOrReconcile(t *testing.T) {
	type arrange func(t *testing.T) (*loop, *fakeStore, *logRecorder, context.Context)
	cancelled := func(t *testing.T) context.Context {
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		return ctx
	}
	// cancelAtPut 은 발행된 루프의 다음 PUT 이 닿는 순간 루프 ctx 를 취소하고 그 PUT 을 then 으로 끝낸다.
	cancelAtPut := func(then error) arrange {
		return func(t *testing.T) (*loop, *fakeStore, *logRecorder, context.Context) {
			l, store, logs := publishedSolo(t)
			ctx, cancel := context.WithCancel(t.Context())
			store.setOnPut(func(putCall) error { store.setOnPut(nil); cancel(); return then })
			return l, store, logs, ctx
		}
	}
	cases := []struct {
		name     string
		from, to int64
		heads    int // 이 틱이 부르는 Head 수 — 실패 뒤 화해는 없다
		setup    arrange
	}{
		{"획득", 0, 5, 0, func(t *testing.T) (*loop, *fakeStore, *logRecorder, context.Context) {
			l, store, logs := newSoloLoop(t, 10)
			return l, store, logs, cancelled(t)
		}},
		{"R1", 0, 6, 1, func(t *testing.T) (*loop, *fakeStore, *logRecorder, context.Context) {
			l, store, logs := publishedSolo(t)
			l.st.ReconcileDue = true
			return l, store, logs, cancelled(t)
		}},
		{"P0", 0, 6, 0, func(t *testing.T) (*loop, *fakeStore, *logRecorder, context.Context) {
			l, store, logs := publishedSolo(t)
			return l, store, logs, cancelled(t)
		}},
		{"P2′", 100, 610, 1, func(t *testing.T) (*loop, *fakeStore, *logRecorder, context.Context) {
			l, store, logs := newChainLoop(t, 700)
			l.fx.setInitUploaded("P", false) // S4 → 첫 PUT 전이라 P2′
			ctx, cancel := context.WithCancel(t.Context())
			l.pub.pool = hookedPool(t, l.pub.pool, stmtHook{sql: p2RevokeSQL, before: func(sctx context.Context, _ []any) context.Context {
				cancel()
				return sctx
			}})
			return l, store, logs, ctx
		}},
		{"P3", 0, 6, 0, cancelAtPut(context.Canceled)},
		{"409_재확인", 0, 6, 1, cancelAtPut(ErrConflict)},
		{"P4", 0, 6, 0, cancelAtPut(nil)},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs, ctx := tc.setup(t)
			due, heads := l.st.ReconcileDue, len(store.headKeys())

			out := l.tick(ctx, tc.from, tc.to)

			if got := logs.aborts(); len(got) != 0 {
				t.Errorf("중단 · 포기 로그 %v, want 없음", got)
			}
			if !errors.Is(out.Err, context.Canceled) || out.Published || out.DemandLoad {
				t.Errorf("틱 = %+v, want Err = context.Canceled · 발행 없음 · 요구 적재 없음", out)
			}
			if n := len(store.headKeys()) - heads; n != tc.heads || out.State.ReconcileDue != due {
				t.Errorf("Head %d번 · 화해 예약 %v, want %d번 · 그대로 %v(화해하지도 예약하지도 않는다)", n, out.State.ReconcileDue, tc.heads, due)
			}
		})
	}
}

// 루프 ctx 는 살아 있는데 틱 안의 하위 시한 — P3 마감 · DB 문장 시한 — 만 지난 실패는 멈춤이 아니다(r5 cc 지적 2(a) ·
// cx 지적 1). 저장소 · DB 가 느린 국면이라 운영자가 봐야 하는 신호다. 실패 갈래는 그대로 사유를 남기고 화해한다(결과
// 모름은 화해 Head · Head 실패는 화해 예약 · P2′ 는 요구 적재까지). stopped 는 루프 ctx 만 본다 — 위 취소 시험의 짝이다.
//
//	P3_마감_지난_PUT   시계를 과거에 두어 P3 마감이 이미 지난 PUT → put_unknown + 화해 Head
//	409_재확인_Head   If-Match 409 가 P3 마감을 넘겨 돌아온 뒤의 재확인 Head → head_failed + 화해 예약
//	P0 · P2′ · P4     다른 연결이 그 행을 잠근 동안 문장 시한(200ms)이 지남 → 결과 모름 사유 + 화해(P2′ 는 요구 적재도)
//
// 문장 갈래의 잠금은 화해의 Head 가 풀어 R3 · R4 가 선다. pgx 는 문장 시한이 지나면 연결에 시한을 걸어 응답 읽기를 끊고(v5
// 기본 DeadlineContextWatcherHandler), 그 연결을 닫으면서 서버에 취소 요청(CancelRequest)을 비동기로 보낸다(pgconn 의
// asyncClose). 취소가 잠금이 풀리기 전에 닿는다는 보장이 없어 서버에 남은 그 문장은 잠금이 풀린 뒤 적용될 수 있다 — 결과 모름
// 국면 그대로다.
func TestTimedOutStepWithLiveLoopLogsAndReconciles(t *testing.T) {
	const stmtTimeout = 200 * time.Millisecond
	cases := []struct {
		name     string
		from, to int64
		reason   string
		level    slog.Level
		heads    int  // 이 틱이 부르는 Head 수
		due      bool // 틱 뒤 화해 예약
		demand   bool // 요구 적재
		setup    func(t *testing.T) (*loop, *fakeStore, *logRecorder)
	}{
		{"P3_마감_지난_PUT", 0, 6, reasonPutUnknown, slog.LevelWarn, 1, false, false, func(t *testing.T) (*loop, *fakeStore, *logRecorder) {
			l, store, logs := publishedSolo(t)
			l.pub.now = (&stepClock{now: time.Now().Add(-10 * time.Second)}).Now // m0 · P3 시작 = 10초 전 → P3 마감 = 8초 전
			return l, store, logs
		}},
		{"409_재확인_Head", 0, 6, reasonHeadFailed, slog.LevelError, 1, true, false, func(t *testing.T) (*loop, *fakeStore, *logRecorder) {
			l, store, logs := publishedSolo(t)
			// m0 = 2.5초 전 · P3 시작 = m0 + 1.5초 → P3 마감 = m0 + lease − T_pub = 지금 + 0.5초
			l.pub.now = (&stepClock{now: time.Now().Add(-2500 * time.Millisecond), steps: []time.Duration{1500 * time.Millisecond}}).Now
			l.pub.store = lateConflictStore{store}
			return l, store, logs
		}},
		{"P0_문장_시한", 0, 6, reasonP0Unknown, slog.LevelWarn, 1, false, false, func(t *testing.T) (*loop, *fakeStore, *logRecorder) {
			l, store, logs := publishedSolo(t)
			l.pub.opt.statementTimeout = stmtTimeout
			l.pub.store = &onHeadStore{fakeStore: store, before: lockRow(t, l.pub.pool, "S")}
			return l, store, logs
		}},
		{"P2′_문장_시한", 100, 610, reasonRevokeUnknown, slog.LevelWarn, 2, false, true, func(t *testing.T) (*loop, *fakeStore, *logRecorder) {
			l, store, logs := newChainLoop(t, 700)
			l.fx.setInitUploaded("P", false) // S4 → 첫 PUT 전이라 P2′
			l.pub.opt.statementTimeout = stmtTimeout
			heads := &onHeadStore{fakeStore: store}
			l.pub.store = heads
			base := l.pub.pool
			l.pub.pool = hookedPool(t, base, stmtHook{sql: p2RevokeSQL, before: func(sctx context.Context, _ []any) context.Context {
				heads.before = lockRow(t, base, "S")
				return sctx
			}})
			return l, store, logs
		}},
		{"P4_문장_시한", 0, 6, reasonP4Unknown, slog.LevelWarn, 1, false, false, func(t *testing.T) (*loop, *fakeStore, *logRecorder) {
			l, store, logs := publishedSolo(t)
			l.pub.opt.statementTimeout = stmtTimeout
			heads := &onHeadStore{fakeStore: store}
			l.pub.store = heads
			store.setOnPut(func(putCall) error { // P0 뒤 · P4 전
				store.setOnPut(nil)
				heads.before = lockRow(t, l.pub.pool, "S")
				return nil
			})
			return l, store, logs
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs := tc.setup(t)
			heads := len(store.headKeys())

			out := l.tick(t.Context(), tc.from, tc.to)

			recs := logs.abortRecs()
			if len(recs) != 1 || recs[0].attrs["reason"] != tc.reason || recs[0].level != tc.level ||
				!strings.Contains(recs[0].attrs["err"], "deadline exceeded") {
				t.Errorf("로그 %+v, want %s(%v · err = 시한 초과) 한 줄", recs, tc.reason, tc.level)
			}
			if out.Err != nil || out.Published || out.DemandLoad != tc.demand {
				t.Errorf("틱 = %+v, want Err 없음(루프 ctx 는 살아 있다) · 발행 없음 · 요구 적재 %v", out, tc.demand)
			}
			if n := len(store.headKeys()) - heads; n != tc.heads || out.State.ReconcileDue != tc.due {
				t.Errorf("Head %d번 · 화해 예약 %v, want %d번 · %v", n, out.State.ReconcileDue, tc.heads, tc.due)
			}
		})
	}
}

// onHeadStore 는 Head 가 저장소에 닿기 직전에 before 를 한 번 부르는 저장소다(부른 뒤 비운다) — 화해의 Head 가 오는
// 순간에 행 잠금을 푸는 자리다. 나머지는 가짜 그대로다.
type onHeadStore struct {
	*fakeStore
	before func()
}

func (s *onHeadStore) Head(ctx context.Context, key string) (Stat, error) {
	if f := s.before; f != nil {
		s.before = nil
		f()
	}
	return s.fakeStore.Head(ctx, key)
}

// lateConflictStore 는 PUT 을 그 ctx(P3 마감)가 끝날 때까지 붙잡았다가 409 로 끝내는 저장소다 — 느린 S3 가 마감을 넘겨
// 409 를 돌려준 국면이다. 나머지는 가짜 그대로다.
type lateConflictStore struct{ *fakeStore }

func (lateConflictStore) Put(ctx context.Context, _ string, _ Precondition, _ []byte, _ map[string]string) (string, error) {
	<-ctx.Done()
	return "", ErrConflict
}
