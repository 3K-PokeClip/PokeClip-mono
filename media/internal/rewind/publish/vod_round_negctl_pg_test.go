package publish

// 회차 단위 VOD 의 음성 대조 PG 시험이다(POK-549 C3 · 설계 r4 10.5 · 계획 K9 · K23). 기존 진입점(Publisher.FreezeVOD ·
// index.LookupRoundPlayback)과 기존 칸만 부르고 키는 문자열 리터럴로 단언한다 — 제품 코드를 기준(a9c39331)으로 되돌린
// 트리에서도 컴파일되고 단언에서 FAIL 해야 한다. 웹 요구(마지막 사슬 freeze_failed → ended)는 회귀 고정이라 두 트리에서
// PASS 한다. 회차 픽스처 도우미는 이 파일에 둔다 — 음성 대조가 지우는 새 시험 파일에 두면 이 파일이 컴파일되지 않는다.

import (
	"bytes"
	"context"
	"encoding/hex"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// roundDocKey 는 회차 A 의 회차 문서 키다(K-c — vod/{stream}/{round}.round/index.m3u8).
const roundDocKey = "vod/str/A.round/index.m3u8"

// roundSess 는 회차 픽스처 세션 하나다. round 가 "" 면 round_id NULL(M6 전 세션)이다.
type roundSess struct {
	id, round, inherits string
	td                  int32
	// vod 는 vod_state 다. doc 이 참이면 vod_s3_key 에 그 세션 머리 키(앞 사슬 문서)를 둔다.
	vod string
	doc bool
	// open 이 참이면 round_closed_at 이 NULL(열린 회차)이다.
	open bool
}

// insertRoundSessions 는 ss 를 개시 순(fxDay 부터 1분 간격)으로 넣는다 — 모두 ending · DVR 동결 끝 · init 확정이다.
// round_id 는 INSERT 때만 넣을 수 있다(회차 불변 트리거 — UPDATE 로 바꾸면 23514).
func insertRoundSessions(t *testing.T, pool *pgxpool.Pool, ss []roundSess) {
	t.Helper()
	for i, s := range ss {
		var round, inherits, key, closed any
		if s.round != "" {
			round = s.round
		}
		if s.inherits != "" {
			inherits = s.inherits
		}
		if s.doc {
			key = "vod/" + fxStream + "/" + s.id + "/index.m3u8"
		}
		if !s.open && s.round != "" {
			closed = fxDay.Add(time.Hour)
		}
		exec(t, pool, `
			INSERT INTO stream_sessions (session_id, stream_id, started_at, state, init_uploaded_at, inherits_session,
			                             target_duration, dvr_state, vod_state, vod_s3_key, round_id, round_closed_at)
			VALUES ($1, $2, $3, 'ending', now(), $4, $5, 'frozen', $6, $7, $8, $9)`,
			s.id, fxStream, fxDay.Add(time.Duration(i)*time.Minute), inherits, s.td, s.vod, key, round, closed)
	}
}

// newRoundVOD 는 회차 세션 ss 와 장부 행 rows(seq 0 부터 · vodRow 규약) · 컷오프 0 을 넣은 픽스처다.
func newRoundVOD(t *testing.T, ss []roundSess, rows []vodRow) (*Publisher, *fakeStore) {
	t.Helper()
	pool := newPool(t)
	insertRoundSessions(t, pool, ss)
	for seq, r := range rows {
		row := fxRow(r.session, int64(seq))
		if r.session == "" {
			row = boundary.Row{Seq: int64(seq), DurationMS: 4000}
		}
		insertLedgerRow(t, pool, row, r.state)
	}
	insertCutoff(t, pool, 0)
	store := newFakeStore(t)
	return newPublisher(t, pool, store, "w-me", &logRecorder{}), store
}

// twoChainRound 은 회차 A 의 두 사슬이다 — 앞 사슬 A(TD 6 · VOD 문서 확정) · 마지막 사슬 B(TD 8 — TD 분할로 끊겨 계승
// 없음 · freezing).
func twoChainRound() []roundSess {
	return []roundSess{
		{id: "A", round: "A", td: 6, vod: ResultFrozen, doc: true},
		{id: "B", round: "A", td: 8, vod: "freezing"},
	}
}

// twoChainRows 는 A 의 행 0..4 · B 의 행 5..9(전부 ③ 확정)다.
func twoChainRows() []vodRow {
	rows := make([]vodRow, 10)
	for i := range rows {
		rows[i] = vodRow{session: "A", state: "uploaded"}
		if i >= 5 {
			rows[i].session = "B"
		}
	}
	return rows
}

// chainOf 는 회차 round 의 사슬 ids 다(머리가 처음 · 꼬리가 끝).
func chainOf(round string, ids ...string) index.Chain {
	return index.Chain{StreamID: fxStream, RoundID: round, Head: ids[0], Tail: ids[len(ids)-1], SessionIDs: ids}
}

// endRound 는 회차 세션 ids 를 ended 로 둔다 — 판정식이 완결로 보게.
func endRound(t *testing.T, p *Publisher, ids ...string) {
	t.Helper()
	exec(t, p.pool, `UPDATE stream_sessions SET state = 'ended', ended_at = $2 WHERE session_id = ANY($1)`, ids, fxDay.Add(time.Hour))
}

// wantRoundFrozen 은 마지막 사슬 ids 가 회차 문서(roundDocKey · 범위 first–last)로 state 확정됐는지 본다.
func wantRoundFrozen(t *testing.T, p *Publisher, store *fakeStore, state string, first, last int64, ids ...string) []byte {
	t.Helper()
	body, etag := stored(store, roundDocKey)
	if body == nil {
		var keys []string
		for _, c := range store.putCalls() {
			keys = append(keys, c.key)
		}
		t.Fatalf("회차 문서 %s 가 없다 — PUT 키 %q", roundDocKey, keys)
	}
	sum := sha256Of(body)
	for _, id := range ids {
		v := readVOD(t, p, id)
		if v.state != state || strOrNil(v.key) != roundDocKey || strOrNil(v.etag) != etag || !bytes.Equal(v.sha, sum[:]) ||
			v.first == nil || *v.first != first || v.last == nil || *v.last != last {
			t.Errorf("회차 %s VOD 열 = %+v(키 %s), want %s · %s · 범위 %d–%d", id, v, strOrNil(v.key), state, roundDocKey, first, last)
		}
	}
	return body
}

// round_vod_two_chains(R1 · R11 · R12 · 설계 1절 1) — 사슬이 둘인 회차는 마지막 사슬의 동결이 회차 전체(A 의 첫 조각부터
// B 의 끝 조각까지)를 회차 키 문서 하나로 쓴다. 사슬 경계(B 의 첫 조각 seq 5) 앞에만 끊김 표시가 서고 TARGETDURATION 은
// 회차 세션 최댓값(8)이며 메타 pc-session 은 회차다. 판정식은 그 문서를 mode vod · firstPdt = A 의 첫 조각 PDT 로 낸다.
// 앞 사슬 A 의 VOD 열은 그대로다. 기준 트리는 사슬 키(vod/str/B/…) · 범위 5–9 라 FAIL.
func TestFreezeVODWritesRoundDocumentForTwoChains(t *testing.T) {
	p, store := newRoundVOD(t, twoChainRound(), twoChainRows())

	wantOutcome(t, p.FreezeVOD(t.Context(), chainOf("A", "B"), false), AxisVOD, ResultFrozen, false)

	body := wantRoundFrozen(t, p, store, ResultFrozen, 0, 9, "B")
	lines := vodLines(body)
	for seq, line := range lines {
		if got := strings.HasPrefix(line, "#EXT-X-DISCONTINUITY "); got != (seq == 5) {
			t.Errorf("seq %d 줄 %q — 끊김 표시는 사슬 경계 seq 5 앞에만", seq, line)
		}
	}
	if len(lines) != 10 || !bytes.Contains(body, []byte("#EXT-X-TARGETDURATION:8\n")) {
		t.Errorf("회차 문서 = 조각 %d 개, want 10 · TARGETDURATION 8:\n%s", len(lines), body)
	}
	if st, err := store.Head(t.Context(), roundDocKey); err != nil || st.Meta["pc-session"] != "A" {
		t.Errorf("회차 문서 메타 = (%v, %v), want pc-session=A(회차)", st.Meta, err)
	}
	if a := readVOD(t, p, "A"); strOrNil(a.key) != "vod/str/A/index.m3u8" || a.state != ResultFrozen {
		t.Errorf("앞 사슬 A VOD 열 = %+v(키 %s), want 그대로", a, strOrNil(a.key))
	}

	endRound(t, p, "A", "B")
	st, err := index.LookupRoundPlayback(t.Context(), p.pool, fxStream, "A")
	if err != nil {
		t.Fatalf("LookupRoundPlayback 실패: %v", err)
	}
	if st.Phase != index.PhaseVOD || st.VODKey != roundDocKey || !st.FirstPDT.Equal(fxRow("A", 0).PlaybackPDT) ||
		!st.HeadPDT.Equal(fxRow("B", 9).PlaybackPDT.Add(4*time.Second)) {
		t.Errorf("판정식 = %+v, want vod · %s · firstPdt %v · headPdt %v", st, roundDocKey, fxRow("A", 0).PlaybackPDT,
			fxRow("B", 9).PlaybackPDT.Add(4*time.Second))
	}
}

// round_vod_key_never_collides_with_chain_key(R6) — 회차 id 는 회차 머리 세션 id 라 앞 사슬 문서 키(vod/str/A/…)와 성분이
// 같고, 교체 겹침의 옛 writer 는 마지막 사슬 키(vod/str/B/…)에 사슬 본문을 올리고 확정 전에 멈췄을 수 있다. 회차 문서는
// 두 키와 다른 키라 412 · vod_conflict 없이 확정한다. 기준 트리는 B 키에 다른 본문이라 · 변이 M5(회차 키 → 머리 키)는 A
// 키에 다른 본문이라 vod_conflict 로 FAIL.
func TestFreezeVODRoundKeyIgnoresChainKeyObject(t *testing.T) {
	p, store := newRoundVOD(t, twoChainRound(), twoChainRows())
	for _, id := range []string{"A", "B"} {
		if _, err := store.Put(t.Context(), "vod/str/"+id+"/index.m3u8", IfAbsent(), []byte("#EXTM3U\n# 사슬 "+id+" 의 문서\n"),
			map[string]string{"pc-session": id}); err != nil {
			t.Fatalf("사슬 %s 문서 심기 실패: %v", id, err)
		}
	}

	wantOutcome(t, p.FreezeVOD(t.Context(), chainOf("A", "B"), false), AxisVOD, ResultFrozen, false)

	wantRoundFrozen(t, p, store, ResultFrozen, 0, 9, "B")
}

// round_vod_front_rows_are_settled(R3) — 앞 사슬 A 의 ③ 미착지 행(seq 2 · DVR GAP 원장 있음)은 마지막 사슬의 정산 창 안
// (abandon 거짓)에도 기다리지 않는다 — 앞 사슬은 이미 정산을 끝냈다. 회차 문서는 그 행을 GAP 줄로 싣고 frozen_with_gaps 로
// 확정한다. 기준 트리는 사슬 범위(5–9)라 GAP 없이 사슬 키로 FAIL.
func TestFreezeVODTreatsFrontRowsAsSettled(t *testing.T) {
	rows := twoChainRows()
	rows[2].state = "pending"
	p, store := newRoundVOD(t, twoChainRound(), rows)
	exec(t, p.pool, `INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason) VALUES ($1, 2, 'A', 'upload_stall')`, fxStream)

	wantOutcome(t, p.FreezeVOD(t.Context(), chainOf("A", "B"), false), AxisVOD, ResultFrozenWithGaps, false)

	body := wantRoundFrozen(t, p, store, ResultFrozenWithGaps, 0, 9, "B")
	if lines := vodLines(body); len(lines) != 10 || !strings.HasPrefix(lines[2], "#EXT-X-GAP ") {
		t.Errorf("회차 문서 seq 2 줄 = %q, want GAP 줄", lines)
	}
}

// round_vod_waits_while_round_open(R4) — 회차가 열려 있으면(round_closed_at NULL) 회차 대기 재시도(round_open)다 —
// 문서를 쓰지 않고 vod_state 는 freezing 그대로다. 회차가 닫히면 다음 호출이 회차 문서로 확정한다. 기준 트리는 열린
// 회차에서 사슬 문서를 확정해 FAIL.
func TestFreezeVODWaitsWhileRoundOpen(t *testing.T) {
	ss := twoChainRound()
	ss[1].open = true
	p, store := newRoundVOD(t, ss, twoChainRows())

	out := p.FreezeVOD(t.Context(), chainOf("A", "B"), true)

	if !out.Retry || out.Reason != "round_open" {
		t.Fatalf("열린 회차 동결 = %+v, want 재시도 round_open", out)
	}
	if v := readVOD(t, p, "B"); v.state != "freezing" || v.key != nil {
		t.Errorf("B VOD 열 = %+v, want freezing · 키 없음", v)
	}
	if puts := store.putCalls(); len(puts) != 0 {
		t.Errorf("PUT %d번, want 0", len(puts))
	}

	exec(t, p.pool, `UPDATE stream_sessions SET round_closed_at = $2 WHERE session_id = $1`, "B", fxDay.Add(time.Hour))
	wantOutcome(t, p.FreezeVOD(t.Context(), chainOf("A", "B"), true), AxisVOD, ResultFrozen, false)
	wantRoundFrozen(t, p, store, ResultFrozen, 0, 9, "B")
}

// round_playback_ended_without_last_vod(웹 요구 POK-546 · pdt-web-27) — 마지막 사슬 B 의 VOD 가 freeze_failed 인 끝난 회차는
// 앞 사슬 문서가 있어도 판정식이 mode ended 다(판정식 SQL 무변경 — 두 트리에서 PASS 하는 회귀 고정).
func TestRoundPlaybackEndedWhenLastChainFreezeFailed(t *testing.T) {
	ss := twoChainRound()
	ss[1].vod = freezeStateFailed
	p, _ := newRoundVOD(t, ss, twoChainRows())
	endRound(t, p, "A", "B")

	st, err := index.LookupRoundPlayback(context.Background(), p.pool, fxStream, "A")

	if err != nil || st.Phase != index.PhaseEnded || st.LatestSessionID != "B" {
		t.Errorf("판정식 = (%+v, %v), want ended · 최신 B", st, err)
	}
}

// round_vod_golden_td_split(R1 · R11 · R12 · R20 · 설계 10.1 · 완료 조건 ①) — prod 의 TD 분할 회차(앞 사슬 1,623조각 · GAP
// 셋 · TD 6 / 마지막 사슬 107조각 · TD 8)를 장부로 되살려, 앞 사슬을 사슬 문서로 동결한 뒤 마지막 사슬을 동결하면 회차 문서가
// 골든과 바이트로 같다. 앞 사슬 문서는 골든 앞 범위와 바이트로 같고(R20) 차이는 설계 10.1 의 셋뿐이다. 판정식은 그 문서를
// mode vod · firstPdt = 앞 사슬 첫 조각(GAP 줄) PDT 로 낸다. 기준 트리는 마지막 사슬을 사슬 키로 동결해 FAIL.
func TestFreezeVODRoundMatchesTDSplitGolden(t *testing.T) {
	golden, segs := readRoundGolden(t)
	p, store := newRoundGolden(t, segs)

	wantOutcome(t, p.FreezeVOD(t.Context(), goldenChain(goldenFront), true), AxisVOD, ResultFrozenWithGaps, false)
	front, _ := stored(store, goldenFrontKey)
	begin := time.Now()
	out := p.FreezeVOD(t.Context(), goldenChain(goldenLast), false)
	t.Logf("T_vod(회차 %d조각 동결 한 번) = %v", len(segs), time.Since(begin))

	wantOutcome(t, out, AxisVOD, ResultFrozenWithGaps, false)
	body := wantGoldenBody(t, store, golden)
	wantGoldenSplice(t, front, golden)
	sum := sha256Of(body)
	if v := readVOD(t, p, goldenLast); v.state != ResultFrozenWithGaps || strOrNil(v.key) != goldenRoundKey ||
		!bytes.Equal(v.sha, sum[:]) || v.first == nil || *v.first != 4 || v.last == nil || *v.last != 1733 {
		t.Errorf("마지막 사슬 VOD 열 = %+v(키 %s), want frozen_with_gaps · %s · 범위 4–1733", v, strOrNil(v.key), goldenRoundKey)
	}

	endRound(t, p, goldenFront, goldenLast)
	st, err := index.LookupRoundPlayback(t.Context(), p.pool, goldenStream, goldenFront)
	first, tail := segs[0].row, segs[len(segs)-1].row
	head := tail.PlaybackPDT.Add(time.Duration(tail.DurationMS) * time.Millisecond)
	if err != nil || st.Phase != index.PhaseVOD || st.VODKey != goldenRoundKey || st.LatestSessionID != goldenLast ||
		!st.FirstPDT.Equal(first.PlaybackPDT) || !st.HeadPDT.Equal(head) {
		t.Errorf("판정식 = (%+v, %v), want vod · %s · 최신 %s · firstPdt %v · headPdt %v", st, err, goldenRoundKey, goldenLast,
			first.PlaybackPDT, head)
	}
}

// round_vod_golden_last_chain_document(cc C4 L · cx C4 L · R20 뒤 범위) — 골든에서 마지막 사슬 조각(1627–1733)만 장부로 되살리면
// 앞 세션에 렌더할 행이 없어 FreezeVOD 가 사슬 하나로 동결한다(마지막 사슬 키 · 범위 1627–1733). 그 사슬 문서는 골든 회차
// 문서의 뒤 범위(DISCONTINUITY 다음 줄부터 끝까지)와 바이트로 같고, 머리는 골든 머리에서 MEDIA-SEQUENCE 만 1627 이다 — 회차
// 문서의 마지막 사슬 범위가 지금 코드가 따로 내는 그 사슬 문서와 같다. 사슬 하나는 기준 트리도 같은 동작이라 두 트리에서
// PASS 하는 회귀 고정이다.
func TestFreezeVODGoldenLastChainMatchesRoundTail(t *testing.T) {
	golden, segs := readRoundGolden(t)
	var last []goldenSeg
	for _, s := range segs {
		if s.row.SessionID == goldenLast {
			last = append(last, s)
		}
	}
	p, store := newRoundGolden(t, last)
	key := "vod/" + goldenStream + "/" + goldenLast + "/index.m3u8"

	wantOutcome(t, p.FreezeVOD(t.Context(), goldenChain(goldenLast), false), AxisVOD, ResultFrozen, false)

	body, _ := stored(store, key)
	if body == nil {
		t.Fatalf("마지막 사슬 문서 %s 가 없다", key)
	}
	lines := strings.Split(string(golden), "\n")
	cut := slices.Index(lines, "#EXT-X-DISCONTINUITY")
	if cut < 6 || lines[3] != "#EXT-X-MEDIA-SEQUENCE:4" {
		t.Fatalf("골든 꼴 — DISCONTINUITY %d행 · MSN 줄 %q, want 머리 여섯 줄 뒤 · MSN 4", cut+1, lines[3])
	}
	head := append([]string{}, lines[:6]...)
	head[3] = "#EXT-X-MEDIA-SEQUENCE:1627"
	want := strings.Join(append(head, lines[cut+1:]...), "\n")
	if string(body) != want {
		t.Errorf("마지막 사슬 문서 %dB 가 골든 뒤 범위(머리 MSN 1627) %dB 와 다르다", len(body), len(want))
	}
	sum := sha256Of(body)
	if v := readVOD(t, p, goldenLast); v.state != ResultFrozen || strOrNil(v.key) != key || !bytes.Equal(v.sha, sum[:]) ||
		v.first == nil || *v.first != 1627 || v.last == nil || *v.last != 1733 {
		t.Errorf("마지막 사슬 VOD 열 = %+v(키 %s), want frozen · %s · 범위 1627–1733", v, strOrNil(v.key), key)
	}
}

// round_vod_target_duration_front_larger(R12 변형) — 앞 사슬 TD 가 마지막 사슬보다 큰 회차(앞 8 · 마지막 6)도 회차 문서의
// TARGETDURATION 은 회차 세션 최댓값 8 이다(조각 길이 상한 위반 없음). 기준 트리는 사슬 키 · TD 6 이라 FAIL.
func TestFreezeVODRoundTargetDurationTakesLargerFront(t *testing.T) {
	ss := twoChainRound()
	ss[0].td, ss[1].td = 8, 6
	p, store := newRoundVOD(t, ss, twoChainRows())

	wantOutcome(t, p.FreezeVOD(t.Context(), chainOf("A", "B"), false), AxisVOD, ResultFrozen, false)

	body := wantRoundFrozen(t, p, store, ResultFrozen, 0, 9, "B")
	if !bytes.Contains(body, []byte("#EXT-X-TARGETDURATION:8\n")) {
		t.Errorf("회차 문서 머리에 TARGETDURATION 8 이 없다:\n%s", body)
	}
}

// round_vod_single_chain_unchanged(R7 · 설계 10.2 · 완료 조건 ②) — 렌더할 행이 있는 사슬이 하나뿐인 회차는 지금과 바이트로
// 같다: 키 = 머리 세션 키 · 본문 sha = 같은 장부를 기준(a9c39331) 판이 낸 값(리터럴) · 대기 사유 없음 · 열린 회차도 닫힘을
// 기다리지 않고 확정한다. 네 경우 — 회원 하나 · 앞 세션이 행 없음 · 앞 사슬 행이 컷오프 아래뿐 · 열린 회차의 사슬 하나. 앞
// 세션은 freezing 이고 TD 가 더 커서, 그 세션을 앞 회원으로 세면 대기(front_pending)나 TD 차이로 갈린다. 두 트리에서 PASS
// 하는 회귀 고정이다.
func TestFreezeVODSingleChainRoundKeepsChainDocument(t *testing.T) {
	solo := make([]vodRow, 10)
	for i := range solo {
		solo[i] = vodRow{session: "A", state: "uploaded"}
	}
	onlyB := twoChainRows()
	for i := range onlyB {
		onlyB[i].session = "B"
	}
	frontFreezing := []roundSess{{id: "A", round: "A", td: 8, vod: "freezing"}, {id: "B", round: "A", td: 6, vod: "freezing"}}
	tests := []struct {
		name        string
		ss          []roundSess
		rows        []vodRow
		cutoff      int64
		chain       index.Chain
		key         string
		first, last int64
		sha         string
	}{
		{name: "회원_하나", ss: []roundSess{{id: "A", round: "A", td: 6, vod: "freezing"}}, rows: solo,
			chain: chainOf("A", "A"), key: "vod/str/A/index.m3u8", first: 0, last: 9, sha: "c099e912c1927ce8604f9cd7d51b2bece14059695a25da306c49e40c9d341257"},
		{name: "앞_세션_행_없음", ss: frontFreezing, rows: onlyB,
			chain: chainOf("A", "B"), key: "vod/str/B/index.m3u8", first: 0, last: 9, sha: "f0953d2dbd0cf87da8303864dc7d112a9a8c6f4b42a4ce7397a46190a08a0625"},
		{name: "앞_사슬_컷오프_아래", ss: frontFreezing, rows: twoChainRows(), cutoff: 5,
			chain: chainOf("A", "B"), key: "vod/str/B/index.m3u8", first: 5, last: 9, sha: "514d056aa2fe8870bf55b055e7fde57ecc0b99c31d6c313011e39c193f529b41"},
		{name: "열린_회차_사슬_하나", ss: []roundSess{{id: "A", round: "A", td: 6, vod: "freezing", open: true}}, rows: solo,
			chain: chainOf("A", "A"), key: "vod/str/A/index.m3u8", first: 0, last: 9, sha: "c099e912c1927ce8604f9cd7d51b2bece14059695a25da306c49e40c9d341257"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p, store := newRoundVOD(t, tt.ss, tt.rows)
			exec(t, p.pool, `UPDATE stream_cutoffs SET cutoff_seq = $2 WHERE stream_id = $1`, fxStream, tt.cutoff)

			out := p.FreezeVOD(t.Context(), tt.chain, false)

			wantOutcome(t, out, AxisVOD, ResultFrozen, false)
			if out.Reason != "" {
				t.Errorf("동결 사유 = %q, want 없음", out.Reason)
			}
			if puts := store.putCalls(); len(puts) != 1 || puts[0].key != tt.key {
				t.Errorf("PUT %d번(%+v), want %s 한 번", len(puts), puts, tt.key)
			}
			body, _ := stored(store, tt.key)
			sum := sha256Of(body)
			if got := hex.EncodeToString(sum[:]); got != tt.sha {
				t.Errorf("본문 sha = %s, want 기준 판 %s:\n%s", got, tt.sha, body)
			}
			if v := readVOD(t, p, tt.chain.Tail); v.state != ResultFrozen || strOrNil(v.key) != tt.key ||
				!bytes.Equal(v.sha, sum[:]) || v.first == nil || *v.first != tt.first || v.last == nil || *v.last != tt.last {
				t.Errorf("꼬리 %s VOD 열 = %+v(키 %s), want frozen · %s · 범위 %d–%d", tt.chain.Tail, v, strOrNil(v.key), tt.key,
					tt.first, tt.last)
			}
		})
	}
}
