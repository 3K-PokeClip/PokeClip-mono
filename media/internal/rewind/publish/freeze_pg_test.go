package publish

// DVR 동결(FreezeDVR · F0–F4)과 봉인(Endlist · E0–E5)의 PG 통합 시험이다(POK-196 M6-2 커밋 ① · M6 설계 2.1 발행 층 ·
// 4.1 R1 · 9절 D3 · D6 · D32). 픽스처의 장부 행은 DB(stream_segments)에도 넣는다 — 동결은 캐시가 아니라 장부에서
// 렌더 입력을 읽는다.

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// newFreezeLoop 은 newSoloLoop 에 장부 행 0..lastSeq(전부 ③ 확정)와 컷오프 0 을 더하고 창 [0, published] 를 한 번
// 발행한 픽스처다. published 가 음수면 발행하지 않는다.
func newFreezeLoop(t *testing.T, lastSeq, published int64) (*loop, *fakeStore, *logRecorder) {
	t.Helper()
	l, store, logs := newSoloLoop(t, lastSeq)
	seedLedger(t, l)
	if published >= 0 {
		l.mustPublish(t.Context(), 0, published)
	}
	return l, store, logs
}

// seedLedger 는 픽스처 l 의 행을 모두 장부에 ③ 확정으로 넣고 컷오프 0 을 둔다.
func seedLedger(t *testing.T, l *loop) {
	t.Helper()
	for _, r := range l.fx.rows {
		insertLedgerRow(t, l.pub.pool, r, "uploaded")
	}
	insertCutoff(t, l.pub.pool, 0)
}

// dvrRow 는 stream_sessions 한 행의 동결 열이다. NULL 은 nil 이다.
type dvrRow struct {
	state      string
	key, etag  *string
	sha        []byte
	first      *int64
	last       *int64
	discBase   *int64
	freezingAt *time.Time
	frozenAt   *time.Time
}

// readDVR 는 회차 id 의 동결 열을 읽는다.
func readDVR(t *testing.T, l *loop, id string) dvrRow {
	t.Helper()
	var d dvrRow
	err := l.pub.pool.QueryRow(context.Background(), `
		SELECT dvr_state, dvr_s3_key, dvr_etag, dvr_sha256, dvr_first_seq, dvr_last_seq, dvr_disc_base,
		       dvr_freezing_at, dvr_frozen_at
		  FROM stream_sessions WHERE session_id = $1`, id).
		Scan(&d.state, &d.key, &d.etag, &d.sha, &d.first, &d.last, &d.discBase, &d.freezingAt, &d.frozenAt)
	if err != nil {
		t.Fatalf("회차 %s 동결 열 읽기 실패: %v", id, err)
	}
	return d
}

// wantFrozen 은 회차 id 가 저장된 판(키 key)으로 동결됐는지 본다 — 동결 열(키 · ETag · sha · 범위 · base · 시각)과
// 세대 규약 열(manifest_etag · published_seq · discontinuity_base)이 모두 저장된 판의 값이다(F4 · E5).
func wantFrozen(t *testing.T, l *loop, store *fakeStore, id, key string) {
	t.Helper()
	d, row := readDVR(t, l, id), readSession(t, l.pub.pool, id)
	body, etag := stored(store, key)
	desc := storedDesc(t, store)
	sum := sha256Of(body)
	switch {
	case d.state != "frozen" || d.frozenAt == nil || d.freezingAt == nil:
		t.Errorf("동결 상태 %q · 동결 시각 %v · 동결 시작 %v, want frozen · 둘 다 있음", d.state, d.frozenAt, d.freezingAt)
	case strOrNil(d.key) != key || strOrNil(d.etag) != etag || !bytes.Equal(d.sha, sum[:]):
		t.Errorf("동결 키 %s · ETag %s · sha %x, want %s · %s · %x", strOrNil(d.key), strOrNil(d.etag), d.sha, key, etag, sum)
	case d.first == nil || *d.first != desc.MediaSequence || d.last == nil || *d.last != desc.PublishedSeq:
		t.Errorf("동결 범위 %v–%v, want %d–%d", d.first, d.last, desc.MediaSequence, desc.PublishedSeq)
	case d.discBase == nil || *d.discBase != desc.DiscontinuitySequence:
		t.Errorf("동결 base %v, want %d", d.discBase, desc.DiscontinuitySequence)
	case strOrNil(row.etag) != etag || row.pubSeq != desc.PublishedSeq || row.base != desc.DiscontinuitySequence:
		t.Errorf("DB ETag %s · 마지막 seq %d · base %d, want 저장된 판 %s · %d · %d",
			strOrNil(row.etag), row.pubSeq, row.base, etag, desc.PublishedSeq, desc.DiscontinuitySequence)
	case !desc.Terminal || !bytes.HasSuffix(body, []byte("#EXT-X-ENDLIST\n")):
		t.Errorf("저장된 판의 닫힘 메타 %t · 본문 끝 ENDLIST %t, want 둘 다 참", desc.Terminal, bytes.HasSuffix(body, []byte("#EXT-X-ENDLIST\n")))
	}
}

// wantOutcome 은 동결 결과의 축 · 결과 · 재시도가 want 인지 본다.
func wantOutcome(t *testing.T, got FreezeOutcome, axis, result string, retry bool) {
	t.Helper()
	if got.Axis != axis || got.Result != result || got.Retry != retry {
		t.Fatalf("동결 결과 = %+v, want 축 %s · 결과 %s · 재시도 %t", got, axis, result, retry)
	}
}

// ended 는 목록 값 pl 을 닫은 값이다(BaseURL 을 채운다).
func ended(pl rewind.Playlist) rewind.Playlist {
	pl.BaseURL, pl.Ended = fxBaseURL, true
	return pl
}

// FreezeDVR 는 방송 끝 꼬리까지 장부에서 읽어 닫는다(설계 1절 목표 4 — 캐시가 놓친 꼬리). 루프가 창 [0, 5] 까지만 올린
// 뒤 장부에 행 6..9 가 더 있어도 닫힌 판은 0..9 를 싣고 끝에 ENDLIST 한 줄이 있다. 조건은 직전 판 ETag 의 If-Match 이고
// 메타는 닫힘 · 세대 증가다. 장부의 동결 열과 세대 규약 열이 그 판으로 확정된다(F4).
func TestFreezeDVRSealsLedgerTail(t *testing.T) {
	l, store, _ := newFreezeLoop(t, 9, 5)
	prev := l.st.Prev

	out := l.pub.FreezeDVR(t.Context(), fxStream, "S")

	wantOutcome(t, out, AxisDVR, ResultFrozen, false)
	body, _ := stored(store, soloKey)
	if want := mustRender(t, ended(l.fx.window(0, 9))); !bytes.Equal(body, want) {
		t.Errorf("닫힌 판 본문 =\n%s\nwant\n%s", body, want)
	}
	puts := store.putCalls()
	if last := puts[len(puts)-1]; last.cond != IfMatch(prev.ETag) {
		t.Errorf("동결 PUT 조건 = %+v, want IfMatch(직전 판 %s)", last.cond, prev.ETag)
	}
	if d := storedDesc(t, store); d.Gen <= prev.Gen {
		t.Errorf("닫힌 판 세대 %d, want 직전 판 %d 초과", d.Gen, prev.Gen)
	}
	wantFrozen(t, l, store, "S", soloKey)
}

// 동결 뒤에는 평시 틱이 그 목록을 다시 열지 못한다 — P0 이 dvr_state = 'open' 이 아닌 회차의 세대를 예약하지 않는다.
func TestTickRefusesFrozenList(t *testing.T) {
	l, store, _ := newFreezeLoop(t, 9, 5)
	wantOutcome(t, l.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)
	puts := len(store.putCalls())

	for range 2 {
		if out := l.tick(t.Context(), 0, 9); out.Published {
			t.Fatalf("동결 뒤 틱 = %+v, want 발행 없음", out)
		}
	}
	if got := len(store.putCalls()); got != puts {
		t.Errorf("동결 뒤 PUT %d번, want 0", got-puts)
	}
}

// 발행한 적 없는 회차는 첫 판을 IfAbsent 로 닫는다(최초 생성 경로).
func TestFreezeDVRCreatesClosedListWhenNeverPublished(t *testing.T) {
	l, store, _ := newFreezeLoop(t, 3, -1)

	wantOutcome(t, l.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)

	puts := store.putCalls()
	if len(puts) != 1 || puts[0].cond != IfAbsent() {
		t.Fatalf("PUT = %+v, want IfAbsent 한 번", puts)
	}
	wantFrozen(t, l, store, "S", soloKey)
}

// 실을 행도 발행본도 없으면 PUT 없이 빈 동결로 확정한다(설계 2.1 FreezeDVR 주석 · 0절 빈 동결).
func TestFreezeDVRWithoutRowsIsEmptyFreeze(t *testing.T) {
	l, store, _ := newSoloLoop(t, 3)
	insertCutoff(t, l.pub.pool, 0)

	wantOutcome(t, l.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultEmpty, false)

	if puts := store.putCalls(); len(puts) != 0 {
		t.Errorf("PUT %d번, want 0", len(puts))
	}
	if d := readDVR(t, l, "S"); d.state != "frozen" || d.key != nil || d.frozenAt == nil {
		t.Errorf("빈 동결 = %+v, want frozen · 키 NULL · 동결 시각 있음", d)
	}
}

// 다른 writer 가 lease 안에서 fence 를 쥐었으면 손대지 않고 재시도를 돌려준다.
func TestFreezeDVRYieldsToHeldFence(t *testing.T) {
	l, store, _ := newFreezeLoop(t, 9, 5)
	other := l.peer("w-other", &logRecorder{})
	puts := len(store.putCalls())

	out := other.pub.FreezeDVR(t.Context(), fxStream, "S")

	wantOutcome(t, out, AxisDVR, ResultRetry, true)
	if d := readDVR(t, l, "S"); d.state != "open" || len(store.putCalls()) != puts {
		t.Errorf("fence 못 얻은 동결 뒤 상태 %q · PUT %d번, want open · 0", d.state, len(store.putCalls())-puts)
	}
}

// d6_freeze_put_failure_retries(D6) — 동결 PUT 이 412 · 503 이면 판을 잃지 않고 재시도를 돌려준다. 장부는 freezing 에
// 머물고(동결 시작 시각은 첫 시도의 값) 저장된 판은 그대로다. 다음 시도가 닫는다. 3회 상한 · 2단 전환은 판정부 몫이다.
func TestFreezeDVRPutFailureRetries(t *testing.T) {
	for _, tc := range []struct {
		name   string
		err    error
		reason string
	}{
		{"412", ErrPreconditionFailed, "put_412"},
		{"503", errors.New("503 Service Unavailable"), "put_unknown"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			l, store, _ := newFreezeLoop(t, 9, 5)
			before, beforeETag := stored(store, soloKey)
			store.setOnPut(func(putCall) error { store.setOnPut(nil); return tc.err })

			out := l.pub.FreezeDVR(t.Context(), fxStream, "S")

			wantOutcome(t, out, AxisDVR, ResultRetry, true)
			if out.Reason != tc.reason {
				t.Errorf("재시도 사유 = %q, want %q", out.Reason, tc.reason)
			}
			first := readDVR(t, l, "S")
			if body, etag := stored(store, soloKey); first.state != "freezing" || !bytes.Equal(body, before) || etag != beforeETag {
				t.Fatalf("실패 뒤 상태 %q · 저장된 판 바뀜 %t, want freezing · 그대로", first.state, etag != beforeETag)
			}

			wantOutcome(t, l.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)
			wantFrozen(t, l, store, "S", soloKey)
			if d := readDVR(t, l, "S"); !d.freezingAt.Equal(*first.freezingAt) {
				t.Errorf("동결 시작 시각 %v → %v, want 첫 시도의 값 그대로", first.freezingAt, d.freezingAt)
			}
		})
	}
}

// d32_freeze_keeps_header_source(D32 F 경로 · 설계 4.1 R1) — 닫힌 판은 평시 틱과 같은 산식이다. 꼬리 한 줄이 더해져 머리
// 한 줄이 축출되면 MSN 이 1 오르고 DISC-SEQ 는 축출된 끊김 표시만큼 오른다(NextDiscontinuitySequence). TARGETDURATION
// 줄은 직전 판과 같고 PLAYLIST-TYPE 줄은 없다.
//
// 사슬 O ← P ← S 에서 창 [100, 999](900조각 = 1시간)를 올린 뒤 S 의 행 1000 이 장부에 들어온다. 닫힌 판은 [101, 1000]
// 이고, 축출된 seq 100 은 P 의 첫 조각이라 끊김 표시를 달고 있었다 — DISC-SEQ 5 → 6.
func TestFreezeDVRKeepsHeaderSource(t *testing.T) {
	l, store, _ := newChainLoop(t, 999)
	seedLedger(t, l)
	l.mustPublish(t.Context(), 100, 999)
	prevBody, _ := stored(store, chainKey)
	tail := fxRow("S", 1000)
	l.fx.rows = append(l.fx.rows, tail)
	insertLedgerRow(t, l.pub.pool, tail, "uploaded")

	wantOutcome(t, l.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)

	body, _ := stored(store, chainKey)
	got, prev := headerLines(body), headerLines(prevBody)
	if got["#EXT-X-MEDIA-SEQUENCE"] != "101" || got["#EXT-X-DISCONTINUITY-SEQUENCE"] != "6" {
		t.Errorf("닫힌 판 MSN %q · DISC-SEQ %q, want 101 · 6(축출 1건 · 끊김 표시 1)", got["#EXT-X-MEDIA-SEQUENCE"], got["#EXT-X-DISCONTINUITY-SEQUENCE"])
	}
	if got["#EXT-X-TARGETDURATION"] != prev["#EXT-X-TARGETDURATION"] {
		t.Errorf("닫힌 판 TARGETDURATION %q, want 직전 판의 %q", got["#EXT-X-TARGETDURATION"], prev["#EXT-X-TARGETDURATION"])
	}
	if v, ok := got["#EXT-X-PLAYLIST-TYPE"]; ok {
		t.Errorf("닫힌 판에 PLAYLIST-TYPE 줄 %q, want 없음(6.2.1 허용 밖)", v)
	}
	sameCommonDSN(t, prevBody, body)
	wantFrozen(t, l, store, "S", chainKey)
}

// headerLines 는 본문의 머리 태그(첫 조각 앞의 `#EXT-X-이름:값` 줄)를 이름 → 값으로 편다.
func headerLines(body []byte) map[string]string {
	out := map[string]string{}
	for _, line := range strings.Split(string(body), "\n") {
		if strings.HasPrefix(line, "#EXTINF") || strings.HasPrefix(line, "#EXT-X-MAP") || strings.HasPrefix(line, "#EXT-X-PROGRAM-DATE-TIME") {
			break
		}
		if name, value, ok := strings.Cut(line, ":"); ok && strings.HasPrefix(name, "#EXT-X-") {
			out[name] = value
		}
	}
	return out
}

// d32_endlist_keeps_published_bytes(D32 E 경로 · 설계 4.6.4 E3) — 봉인 본문은 직전 발행 본문에 `#EXT-X-ENDLIST` 한 줄을
// 더한 바이트다. 메타는 세대 · 닫힘 · sha 만 바뀌고 MSN · 마지막 seq · 조각 수 · DISC-SEQ 는 직전 판 그대로다.
func TestEndlistKeepsPublishedBytes(t *testing.T) {
	l, store, _ := newFreezeLoop(t, 9, 5)
	prev := l.st.Prev
	prevBody, _ := stored(store, soloKey)

	wantOutcome(t, l.pub.Endlist(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)

	body, _ := stored(store, soloKey)
	if want := append(bytes.Clone(prevBody), "#EXT-X-ENDLIST\n"...); !bytes.Equal(body, want) {
		t.Errorf("봉인 본문 =\n%s\nwant 직전 본문 + ENDLIST 한 줄\n%s", body, want)
	}
	d := storedDesc(t, store)
	if d.Gen <= prev.Gen || d.MediaSequence != prev.MediaSequence || d.PublishedSeq != prev.PublishedSeq ||
		d.SegmentCount != prev.SegmentCount || d.DiscontinuitySequence != prev.DiscontinuitySequence {
		t.Errorf("봉인 판 메타 %+v, want 직전 판 %+v 에서 세대만 증가", d.Published, prev.Published)
	}
	puts := store.putCalls()
	if last := puts[len(puts)-1]; last.cond != IfMatch(prev.ETag) {
		t.Errorf("봉인 PUT 조건 = %+v, want IfMatch(직전 판 %s)", last.cond, prev.ETag)
	}
	wantFrozen(t, l, store, "S", soloKey)
}

// 발행본이 없으면 봉인할 본문이 없다 — PUT 없이 빈 동결이다.
func TestEndlistWithoutPublishedListIsEmptyFreeze(t *testing.T) {
	l, store, _ := newFreezeLoop(t, 3, -1)

	wantOutcome(t, l.pub.Endlist(t.Context(), fxStream, "S"), AxisDVR, ResultEmpty, false)

	if puts := store.putCalls(); len(puts) != 0 {
		t.Errorf("PUT %d번, want 0", len(puts))
	}
	if d := readDVR(t, l, "S"); d.state != "frozen" || d.key != nil {
		t.Errorf("빈 동결 = %+v, want frozen · 키 NULL", d)
	}
}

// 이미 닫힌 판(앞 시도가 PUT 뒤 확정 전에 멈춤)이면 다시 쓰지 않고 그 판으로 확정한다 — 닫힌 목록은 바꾸지 않는다
// (설계 4.6.4 E2 · 멱등). 동결 · 봉인 두 경로가 같다.
func TestClosedListIsAdoptedWithoutRewrite(t *testing.T) {
	for _, tc := range []struct {
		name  string
		close func(*Publisher, context.Context) FreezeOutcome
	}{
		{"동결", func(p *Publisher, ctx context.Context) FreezeOutcome { return p.FreezeDVR(ctx, fxStream, "S") }},
		{"봉인", func(p *Publisher, ctx context.Context) FreezeOutcome { return p.Endlist(ctx, fxStream, "S") }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			l, store, _ := newFreezeLoop(t, 9, 5)
			wantOutcome(t, l.pub.Endlist(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)
			exec(t, l.pub.pool, `UPDATE stream_sessions SET dvr_state = 'freezing', dvr_etag = NULL, dvr_frozen_at = NULL WHERE session_id = 'S'`)
			insertLedgerRow(t, l.pub.pool, fxRow("S", 10), "uploaded") // 닫힌 뒤 꼬리가 더 와도 싣지 않는다
			puts := len(store.putCalls())

			wantOutcome(t, tc.close(l.pub, t.Context()), AxisDVR, ResultFrozen, false)

			if got := len(store.putCalls()); got != puts {
				t.Errorf("닫힌 판 위 PUT %d번, want 0", got-puts)
			}
			wantFrozen(t, l, store, "S", soloKey)
		})
	}
}

// d3_reserved_tick_cannot_reopen_or_regress(D3 · G8-F) — 예약 뒤 ending 된 틱이 동결과 겹쳐도 닫힌 판이 이긴다.
//
//	틱의 PUT 이 동결보다 먼저 닿음  동결 F0 이 세대를 올려 그 틱의 P4 가 0행이다 — 장부는 닫힌 판에 맞춰지고 되돌아가지 않는다
//	틱의 PUT 이 동결 뒤에 닿음      If-Match 가 412 로 막는다 — 닫힌 판이 그대로다(un-ENDLIST 없음)
func TestReservedTickCannotReopenFrozenList(t *testing.T) {
	t.Run("틱_PUT_먼저", func(t *testing.T) {
		l, store, logs := newFreezeLoop(t, 9, 5)
		var freeze FreezeOutcome
		after := &afterPutStore{fakeStore: store}
		l.pub = newPublisher(t, l.pub.pool, after, "w-me", logs) // 같은 writer — fence 를 이어 쥔다
		after.then = func() { freeze = l.pub.FreezeDVR(context.Background(), fxStream, "S") }

		tick := l.tick(t.Context(), 0, 7) // P0 → PUT 적용 → (동결) → P4

		wantOutcome(t, freeze, AxisDVR, ResultFrozen, false)
		if tick.Published {
			t.Errorf("동결과 겹친 틱 = %+v, want P4 0행(확정 없음)", tick)
		}
		wantFrozen(t, l, store, "S", soloKey)
	})
	t.Run("틱_PUT_나중", func(t *testing.T) {
		l, store, _ := newFreezeLoop(t, 9, 5)
		held := new(putCall)
		store.setOnPut(holdPut(store, held))
		l.tick(t.Context(), 0, 7) // P0 → PUT 붙잡힘
		wantOutcome(t, l.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)
		closed, closedETag := stored(store, soloKey)

		if _, err := store.applyPut(*held); !errors.Is(err, ErrPreconditionFailed) {
			t.Errorf("동결 뒤 닿은 틱 쓰기 = %v, want ErrPreconditionFailed", err)
		}
		if body, etag := stored(store, soloKey); etag != closedETag || !bytes.Equal(body, closed) {
			t.Error("닫힌 판이 바뀌었다(un-ENDLIST)")
		}
		wantFrozen(t, l, store, "S", soloKey)
	})
}

// afterPutStore 는 성공한 첫 Put 이 돌아오기 직전에 then 을 한 번 부르는 저장소다 — 틱의 PUT 이 적용된 뒤 P4 전에 다른
// 작업(동결)이 끼어드는 국면을 만든다.
type afterPutStore struct {
	*fakeStore
	then func()
}

func (s *afterPutStore) Put(ctx context.Context, key string, cond Precondition, body []byte, meta map[string]string) (string, error) {
	etag, err := s.fakeStore.Put(ctx, key, cond, body, meta)
	if then := s.then; err == nil && then != nil {
		s.then = nil
		then()
	}
	return etag, err
}
