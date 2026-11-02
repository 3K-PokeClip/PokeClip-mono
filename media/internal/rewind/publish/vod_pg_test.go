package publish

// VOD 동결(FreezeVOD · phase 3)의 PG 통합 시험이다(POK-196 M6-2 커밋 ③ · M6 설계 2.1 · 3.1 phase 3 · 비귀속 행 · 9절
// D6 · D7 · D57).

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// vodChainKey 는 사슬 P ← S 의 VOD 객체 키다(K1 — 머리 세션).
const vodChainKey = "vod/str/P/index.m3u8"

// vodRow 는 사슬 픽스처의 장부 행 하나다 — 회차("" 면 비귀속 · "X" 는 사슬 밖 회차)와 ③ 상태다.
type vodRow struct {
	session string
	state   string
}

// chainRows 는 P 의 행 0..4 · S 의 행 5..9(전부 ③ 확정)다. 고칠 자리는 부르는 쪽이 바꾼다.
func chainRows() []vodRow {
	rows := make([]vodRow, 10)
	for i := range rows {
		rows[i] = vodRow{session: "P", state: "uploaded"}
		if i >= 5 {
			rows[i].session = "S"
		}
	}
	return rows
}

// newVODChain 은 사슬 P ← S(P 는 계승 없음 · S 는 P 를 계승 · 둘 다 ending · DVR 동결 끝 · 사슬 닫힘 — vod_state
// freezing)와 장부 행 rows(seq 0 부터) · 컷오프 0 을 넣은 픽스처다. S 의 TD 는 7 이다(계승은 TD 를 내리지 않는다).
// 사슬 밖 회차 X 도 넣어 둔다(행 배치가 X 를 쓸 때만 행이 있다).
func newVODChain(t *testing.T, rows []vodRow) (*Publisher, *fakeStore, index.Chain) {
	t.Helper()
	return newVODChainOf(t, []rewind.Session{
		{ID: "P", TargetDuration: 6},
		{ID: "S", InheritsSession: "P", TargetDuration: 7},
	}, rows)
}

// newVODChainOf 는 newVODChain 의 사슬을 chain(머리부터 꼬리까지 · 개시 순)으로 바꾼 픽스처다.
func newVODChainOf(t *testing.T, chain []rewind.Session, rows []vodRow) (*Publisher, *fakeStore, index.Chain) {
	t.Helper()
	pool := newPool(t)
	ids := make([]string, len(chain))
	for i, s := range chain {
		insertSession(t, pool, fxStream, s, "ending")
		ids[i] = s.ID
	}
	insertSession(t, pool, fxStream, rewind.Session{ID: "X", TargetDuration: 6}, "ending")
	exec(t, pool, `UPDATE stream_sessions SET vod_state = 'freezing', dvr_state = 'frozen' WHERE session_id = ANY($1)`, ids)
	for seq, r := range rows {
		row := fxRow(r.session, int64(seq))
		if r.session == "" {
			row = boundary.Row{Seq: int64(seq), DurationMS: 4000}
		}
		insertLedgerRow(t, pool, row, r.state)
	}
	insertCutoff(t, pool, 0)
	store := newFakeStore(t)
	c := index.Chain{StreamID: fxStream, Head: ids[0], Tail: ids[len(ids)-1], SessionIDs: ids}
	return newPublisher(t, pool, store, "w-me", &logRecorder{}), store, c
}

// vodSession 은 stream_sessions 한 행의 VOD 열이다. NULL 은 nil 이다.
type vodSession struct {
	state       string
	key, etag   *string
	sha         []byte
	first, last *int64
}

func readVOD(t *testing.T, p *Publisher, id string) vodSession {
	t.Helper()
	var v vodSession
	err := p.pool.QueryRow(context.Background(), `
		SELECT vod_state, vod_s3_key, vod_etag, vod_sha256, vod_first_seq, vod_last_seq
		  FROM stream_sessions WHERE session_id = $1`, id).Scan(&v.state, &v.key, &v.etag, &v.sha, &v.first, &v.last)
	if err != nil {
		t.Fatalf("회차 %s VOD 열 읽기 실패: %v", id, err)
	}
	return v
}

// wantVODFrozen 은 사슬 전원이 저장된 VOD 판으로 state 동결됐는지 본다.
func wantVODFrozen(t *testing.T, p *Publisher, store *fakeStore, state string, first, last int64) {
	t.Helper()
	body, etag := stored(store, vodChainKey)
	sum := sha256Of(body)
	for _, id := range []string{"P", "S"} {
		v := readVOD(t, p, id)
		if v.state != state || strOrNil(v.key) != vodChainKey || strOrNil(v.etag) != etag || !bytes.Equal(v.sha, sum[:]) ||
			v.first == nil || *v.first != first || v.last == nil || *v.last != last {
			t.Errorf("회차 %s VOD 열 = %+v(키 %s · ETag %s), want %s · %s · %s · 범위 %d–%d",
				id, v, strOrNil(v.key), strOrNil(v.etag), state, vodChainKey, etag, first, last)
		}
	}
}

// vodLines 는 본문의 조각 URI 줄마다 그 앞 태그 줄들을 붙인 목록이다(seq 순).
func vodLines(body []byte) []string {
	var out []string
	var tags []string
	for _, line := range strings.Split(string(body), "\n") {
		switch {
		case line == "#EXT-X-DISCONTINUITY", line == "#EXT-X-GAP":
			tags = append(tags, line)
		case line != "" && !strings.HasPrefix(line, "#"):
			out = append(out, strings.Join(append(tags, line), " "))
			tags = nil
		}
	}
	return out
}

// FreezeVOD 는 닫힌 사슬의 VOD 문서를 머리 세션 키에 IfAbsent 로 한 번 쓰고 사슬 전원의 vod_* 를 확정한다(K1 · phase 3).
// 문서는 사슬 전체(P 의 첫 조각부터 S 의 끝 조각까지) · PLAYLIST-TYPE:VOD · ENDLIST 다. DISC-SEQ 는 새 문서라 0 이고
// TARGETDURATION 은 사슬의 최댓값(S 의 7)이다. S 의 첫 조각 앞에 계승 경계의 끊김 표시가 서고, 머리 앞에는 없다.
func TestFreezeVODWritesChainDocumentOnce(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())

	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultFrozen, false)

	puts := store.putCalls()
	if len(puts) != 1 || puts[0].cond != IfAbsent() || puts[0].key != vodChainKey {
		t.Fatalf("PUT = %+v, want %s 에 IfAbsent 한 번", puts, vodChainKey)
	}
	body, _ := stored(store, vodChainKey)
	h := headerLines(body)
	if h["#EXT-X-PLAYLIST-TYPE"] != "VOD" || h["#EXT-X-TARGETDURATION"] != "7" || h["#EXT-X-DISCONTINUITY-SEQUENCE"] != "0" ||
		h["#EXT-X-MEDIA-SEQUENCE"] != "0" || !bytes.HasSuffix(body, []byte("#EXT-X-ENDLIST\n")) {
		t.Errorf("VOD 머리 %v · ENDLIST %t, want VOD · TD 7 · DISC-SEQ 0 · MSN 0 · 닫힘", h, bytes.HasSuffix(body, []byte("#EXT-X-ENDLIST\n")))
	}
	lines := vodLines(body)
	if len(lines) != 10 || strings.Contains(lines[0], "DISCONTINUITY") || !strings.HasPrefix(lines[5], "#EXT-X-DISCONTINUITY ") {
		t.Errorf("VOD 조각 줄 = %q, want 10줄 · 머리 앞 표시 없음 · S 첫 조각 앞 표시", lines)
	}
	wantVODFrozen(t, p, store, ResultFrozen, 0, 9)
}

// d57_unattributed_row_becomes_gap(D57 · 설계 3.1 비귀속 행) — 사슬 범위 안의 비귀속 행(세션 NULL)은 GAP 줄로 채우고
// 원장에 mixed_boundary · vod_abandoned · 앞 사슬 세션으로 적는다. 문서는 GAP 이 있어 frozen_with_gaps 다.
func TestFreezeVODFillsUnattributedRowWithGap(t *testing.T) {
	rows := chainRows()
	rows[5] = vodRow{session: "", state: "pending"}
	p, store, chain := newVODChain(t, rows)

	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultFrozenWithGaps, false)

	body, _ := stored(store, vodChainKey)
	if lines := vodLines(body); len(lines) != 10 || !strings.HasPrefix(lines[5], "#EXT-X-GAP ") || !strings.HasSuffix(lines[5], "/dvr/str/seg/000005.m4s") {
		t.Errorf("VOD 조각 줄 = %q, want seq 5 가 GAP 줄(③ 키 이름)", lines)
	}
	wantGapLedger(t, p, map[int64]string{5: "P/mixed_boundary/vod_abandoned"})
	wantVODFrozen(t, p, store, ResultFrozenWithGaps, 0, 9)
}

// 사슬 범위 안에 사슬 밖 회차의 행이 끼면(구조상 없어야 함) 렌더하지 않고 실패다 — 원장도 쓰지 않는다.
func TestFreezeVODRejectsForeignSessionRow(t *testing.T) {
	rows := chainRows()
	rows[5] = vodRow{session: "X", state: "uploaded"}
	p, store, chain := newVODChain(t, rows)

	out := p.FreezeVOD(t.Context(), chain, false)

	wantOutcome(t, out, AxisVOD, ResultFailed, false)
	if out.Reason != "foreign_session" || len(store.putCalls()) != 0 {
		t.Errorf("사유 %q · PUT %d번, want foreign_session · 0", out.Reason, len(store.putCalls()))
	}
	wantGapLedger(t, p, map[int64]string{})
	if v := readVOD(t, p, "S"); v.state != "freezing" {
		t.Errorf("VOD 상태 %q, want freezing 그대로", v.state)
	}
}

// 정산이 끝나지 않았으면(③ 미착지 행) 포기 없이는 쓰지 않고 재시도다. 포기(정산 창 만료)면 미착지 행을 GAP 줄로 싣고
// 원장에 settle_deadline · vod_abandoned 로 적는다.
func TestFreezeVODWaitsForSettlementUnlessAbandoned(t *testing.T) {
	rows := chainRows()
	rows[7].state = "pending"
	t.Run("정산_중", func(t *testing.T) {
		p, store, chain := newVODChain(t, rows)
		out := p.FreezeVOD(t.Context(), chain, false)
		wantOutcome(t, out, AxisVOD, ResultRetry, true)
		if out.Reason != "unsettled" || len(store.putCalls()) != 0 {
			t.Errorf("사유 %q · PUT %d번, want unsettled · 0", out.Reason, len(store.putCalls()))
		}
	})
	t.Run("포기", func(t *testing.T) {
		p, store, chain := newVODChain(t, rows)
		wantOutcome(t, p.FreezeVOD(t.Context(), chain, true), AxisVOD, ResultFrozenWithGaps, false)
		body, _ := stored(store, vodChainKey)
		if lines := vodLines(body); !strings.HasPrefix(lines[7], "#EXT-X-GAP ") {
			t.Errorf("seq 7 줄 %q, want GAP", lines[7])
		}
		wantGapLedger(t, p, map[int64]string{7: "S/settle_deadline/vod_abandoned"})
		wantVODFrozen(t, p, store, ResultFrozenWithGaps, 0, 9)
	})
}

// DVR 의 GAP 원장 행이 있어도 ③ 이 미착지인 행은 정산 전이다(설계 3.1 phase 2 「③ 미착지 0 ∨ 정산 창 만료」). 정산 창
// 안이면 재시도다 — 원장도 VOD 도 건드리지 않아, 창 안에 ③ 이 닿으면 그 조각이 VOD 에 실린다. 포기(창 만료)면 지금도
// 미착지인 GAP 원장 행만 vod_abandoned 로 바꾼다(VOD 가 버린 조각). 착지한 GAP 행은 그대로다. GAP 원장 우선이라 둘 다
// VOD 에서도 GAP 줄이다.
func TestFreezeVODUnlandedDVRGapsWaitUnlessAbandoned(t *testing.T) {
	rows := chainRows()
	rows[6].state = "pending"
	setup := func(t *testing.T) (*Publisher, *fakeStore, index.Chain) {
		p, store, chain := newVODChain(t, rows)
		insertGap(t, p.pool, fxStream, 6, "put_confirmed")
		insertGap(t, p.pool, fxStream, 8, "recorded") // 8 은 착지했다
		return p, store, chain
	}
	t.Run("정산_중", func(t *testing.T) {
		p, store, chain := setup(t)

		out := p.FreezeVOD(t.Context(), chain, false)

		wantOutcome(t, out, AxisVOD, ResultRetry, true)
		if out.Reason != "unsettled" || len(store.putCalls()) != 0 {
			t.Errorf("사유 %q · PUT %d번, want unsettled · 0", out.Reason, len(store.putCalls()))
		}
		wantGapLedger(t, p, map[int64]string{6: "S/upload_stall/put_confirmed", 8: "S/upload_stall/recorded"})
		if v := readVOD(t, p, "S"); v.state != "freezing" {
			t.Errorf("회차 S VOD 상태 %q, want freezing", v.state)
		}
	})
	t.Run("포기", func(t *testing.T) {
		p, store, chain := setup(t)

		wantOutcome(t, p.FreezeVOD(t.Context(), chain, true), AxisVOD, ResultFrozenWithGaps, false)

		wantGapLedger(t, p, map[int64]string{6: "S/upload_stall/vod_abandoned", 8: "S/upload_stall/recorded"})
		body, _ := stored(store, vodChainKey)
		if lines := vodLines(body); !strings.HasPrefix(lines[6], "#EXT-X-GAP ") || !strings.HasPrefix(lines[8], "#EXT-X-GAP ") {
			t.Errorf("seq 6 · 8 줄 = %q · %q, want 둘 다 GAP", lines[6], lines[8])
		}
	})
}

// 사슬 세션에 행이 없으면 문서 없이 빈 동결이다.
func TestFreezeVODWithoutRowsIsEmptyFreeze(t *testing.T) {
	p, store, chain := newVODChain(t, nil)

	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultEmpty, false)

	if len(store.putCalls()) != 0 {
		t.Errorf("PUT %d번, want 0", len(store.putCalls()))
	}
	for _, id := range []string{"P", "S"} {
		if v := readVOD(t, p, id); v.state != "frozen" || v.key != nil {
			t.Errorf("회차 %s = %+v, want frozen · 키 NULL", id, v)
		}
	}
}

// 부른 쪽이 준 사슬이 장부의 사슬과 다르면(그 사이 계승이 바뀜) 쓰지 않는다.
func TestFreezeVODRejectsChangedChain(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	chain.SessionIDs = []string{"S"}

	out := p.FreezeVOD(t.Context(), chain, false)

	wantOutcome(t, out, AxisVOD, ResultFailed, false)
	if out.Reason != "chain_changed" || len(store.putCalls()) != 0 {
		t.Errorf("사유 %q · PUT %d번, want chain_changed · 0", out.Reason, len(store.putCalls()))
	}
}

// d6_vod_put_unknown_retries(D6 VOD 축) — PUT 결과를 모르면 재시도다. 장부는 freezing 그대로다.
func TestFreezeVODPutFailureRetries(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	store.setOnPut(func(putCall) error { store.setOnPut(nil); return errors.New("503 Service Unavailable") })

	out := p.FreezeVOD(t.Context(), chain, false)

	wantOutcome(t, out, AxisVOD, ResultRetry, true)
	if v := readVOD(t, p, "S"); out.Reason != "put_unknown" || v.state != "freezing" {
		t.Errorf("사유 %q · 상태 %q, want put_unknown · freezing", out.Reason, v.state)
	}
}

// d7_vod_put_applied_then_lost(D7) — PUT 이 적용된 뒤 확정 전에 결과를 잃었으면 다음 시도의 IfAbsent 는 412 다. Head 의
// pc-body-sha256 이 다시 렌더한 본문과 같으면 그 판을 채택해 확정한다(같은 문서 — 두 번 쓰지 않는다).
func TestFreezeVODAdoptsSameBodyAfter412(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	store.setOnPut(func(c putCall) error {
		store.setOnPut(nil)
		if _, err := store.applyPut(c); err != nil {
			return err
		}
		return context.DeadlineExceeded
	})
	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultRetry, true)

	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultFrozen, false)

	wantVODFrozen(t, p, store, ResultFrozen, 0, 9)
}

// d7_vod_conflict(D7) — 같은 키에 다른 본문이 있으면 채택하지 않는다. 실패이고 장부는 freezing 그대로다(VOD 는 덮어쓰지
// 않는다 — 오기록 복구는 런북 몫).
func TestFreezeVODRefusesDifferentBodyAfter412(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	plant(store, putCall{key: vodChainKey, body: []byte("#EXTM3U\n#다른 본문\n"),
		meta: map[string]string{metaSession: "P", metaBodySHA256: strings.Repeat("0", 64)}})

	out := p.FreezeVOD(t.Context(), chain, false)

	wantOutcome(t, out, AxisVOD, ResultFailed, false)
	if v := readVOD(t, p, "S"); out.Reason != "vod_conflict" || v.state != "freezing" {
		t.Errorf("사유 %q · 상태 %q, want vod_conflict · freezing", out.Reason, v.state)
	}
}

// wantGapLedger 는 GAP 원장이 want(seq → "회차/사유/발행 상태")와 같은지 본다.
func wantGapLedger(t *testing.T, p *Publisher, want map[int64]string) {
	t.Helper()
	rows, err := p.pool.Query(context.Background(),
		`SELECT seq, session_id || '/' || reason || '/' || published_state FROM stream_published_gaps WHERE stream_id = $1`, fxStream)
	if err != nil {
		t.Fatalf("GAP 원장 읽기 실패: %v", err)
	}
	defer rows.Close()
	got := map[int64]string{}
	for rows.Next() {
		var seq int64
		var v string
		if err := rows.Scan(&seq, &v); err != nil {
			t.Fatalf("GAP 원장 행 읽기 실패: %v", err)
		}
		got[seq] = v
	}
	if len(got) != len(want) {
		t.Errorf("GAP 원장 = %v, want %v", got, want)
		return
	}
	for seq, v := range want {
		if got[seq] != v {
			t.Errorf("GAP 원장 = %v, want %v", got, want)
			return
		}
	}
}

// 확정은 사슬 전원이거나 아무도 아니다 — 사슬 일부만 freezing 이면(닫힘이 반쯤 된 장부) 되돌리고 재시도다.
func TestFreezeVODConfirmsWholeChainOrNothing(t *testing.T) {
	p, _, chain := newVODChain(t, chainRows())
	exec(t, p.pool, `UPDATE stream_sessions SET vod_state = 'none' WHERE session_id = 'P'`)

	out := p.FreezeVOD(t.Context(), chain, false)

	wantOutcome(t, out, AxisVOD, ResultRetry, true)
	if v := readVOD(t, p, "S"); v.state != "freezing" || v.key != nil {
		t.Errorf("꼬리 VOD 열 = %+v, want freezing · 키 NULL(되돌림)", v)
	}
}

// vod_three_session_chain(M6-1 인계 · S4 · S3 · S6) — 사슬 P ← Q ← S 셋의 VOD 는 한 문서다. 소유 회차가 첫 행의 회차(머리
// P)라 Q · S 의 계승 접두 검사(S4)에 걸리지 않고, 계승 경계마다(Q · S 의 첫 조각 앞) 끊김 표시가 서며 머리 앞에는 없다.
func TestFreezeVODWritesThreeSessionChain(t *testing.T) {
	rows := make([]vodRow, 12)
	for i := range rows {
		rows[i] = vodRow{session: []string{"P", "Q", "S"}[i/4], state: "uploaded"}
	}
	p, store, chain := newVODChainOf(t, []rewind.Session{
		{ID: "P", TargetDuration: 6},
		{ID: "Q", InheritsSession: "P", TargetDuration: 6},
		{ID: "S", InheritsSession: "Q", TargetDuration: 7},
	}, rows)

	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultFrozen, false)

	body, _ := stored(store, vodChainKey)
	lines := vodLines(body)
	if len(lines) != 12 || strings.Contains(lines[0], "DISCONTINUITY") ||
		!strings.HasPrefix(lines[4], "#EXT-X-DISCONTINUITY ") || !strings.HasPrefix(lines[8], "#EXT-X-DISCONTINUITY ") {
		t.Errorf("VOD 조각 줄 = %q, want 12줄 · 머리 앞 표시 없음 · Q · S 첫 조각 앞 표시", lines)
	}
	for _, id := range chain.SessionIDs {
		if v := readVOD(t, p, id); v.state != ResultFrozen || v.first == nil || *v.first != 0 || v.last == nil || *v.last != 11 {
			t.Errorf("회차 %s VOD 열 = %+v, want frozen · 범위 0–11", id, v)
		}
	}
}

// vod_chain_longer_than_window(M6-1 인계 · S4 1시간 상한) — VOD 는 1시간 창 문서가 아니다. 4초 조각 1,000개(약 67분)의
// 두 세션 사슬이 한 문서로 동결된다. 소유 회차가 머리 P 라 P 의 행이 계승 접두가 아니어서, 접두가 1시간 창보다 먼 곳에서
// 시작하면 거부하는 S4 상한에 걸리지 않는다. 변이 = 소유 회차를 꼬리 S 로(S 의 계승은 그대로) → P 가 접두가 되고 목록
// 첫 조각을 뺀 길이가 약 67분이라 S4 상한으로 FAIL. 10행 시험은 이 변이를 잡지 못한다(접두가 1시간 안쪽이다).
func TestFreezeVODWritesChainLongerThanOneHour(t *testing.T) {
	rows := make([]vodRow, 1000)
	for i := range rows {
		rows[i] = vodRow{session: "P", state: "uploaded"}
		if i >= 500 {
			rows[i].session = "S"
		}
	}
	p, store, chain := newVODChain(t, rows)

	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultFrozen, false)

	body, _ := stored(store, vodChainKey)
	if lines := vodLines(body); len(lines) != 1000 {
		t.Errorf("VOD 조각 줄 %d개, want 1000", len(lines))
	}
	wantVODFrozen(t, p, store, ResultFrozen, 0, 999)
}

// vod_empty_session_ids(M6-1 인계 — SessionIDs 필수) — 사슬 세션 목록이 빈 호출은 장부 대조를 건너뛰게 되므로 읽기 전에
// chain_changed 실패다. 문서도 장부도 그대로다. 변이 = 첫 검사 삭제 → 머리 세션을 고르다 멈춰(범위 밖 색인) FAIL.
func TestFreezeVODRejectsEmptySessionIDs(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	chain.SessionIDs = nil

	out := p.FreezeVOD(t.Context(), chain, false)

	wantOutcome(t, out, AxisVOD, ResultFailed, false)
	if out.Reason != vodChainChanged || len(store.putCalls()) != 0 {
		t.Errorf("사유 %q · PUT %d번, want chain_changed · 0", out.Reason, len(store.putCalls()))
	}
	for _, id := range []string{"P", "S"} {
		if v := readVOD(t, p, id); v.state != "freezing" {
			t.Errorf("회차 %s vod_state = %s, want freezing(그대로)", id, v.state)
		}
	}
}
