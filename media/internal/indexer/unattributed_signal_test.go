package indexer

// 판 NULL 귀속 · E28 신호(POK-196 PDT 설계 r7 8절 「r6 그대로」 · r6 2.16 E28 신호 · 계획 14절 c11) — legacy_unattributed_sum 은
// 판 NULL 귀속 행의 PDT 식이 더한 U 를, unattributed_run 은 비귀속 행 묶음을 다음 귀속 행에서 닫으며 표식별 길이로 남긴다.

import (
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

var (
	markerA = [16]byte{0xa}
	markerB = [16]byte{0xb}
)

// readMarkers 는 처음 읽는 조각 차례(0 부터)마다 녹화기 표식을 돌려주는 판독기를 끼운다 — 같은 파일을 다시 읽으면 같은
// 답이다. nil 칸은 mtxi 없는 조각이다.
func (f *fixture) readMarkers(markers ...*[16]byte) {
	order := map[string]int{}
	f.ix.timingFn = func(p string) (playback.SegmentTiming, error) {
		n, ok := order[p]
		if !ok {
			n = len(order)
			order[p] = n
		}
		if markers[n] == nil {
			return playback.SegmentTiming{}, nil
		}
		return playback.SegmentTiming{Mtxi: &playback.Mtxi{RecorderID: *markers[n], SegmentNumber: uint64(n)}}, nil
	}
}

// restart 는 같은 장부 위에 인덱서를 새로 띄운다(재기동 — 메모리 상태가 비고 커서는 장부에서 다시 읽는다).
func (f *fixture) restart() {
	f.ix = New(f.store, f.probe.fn, noTimingFacts, f.adopter, f.upload, f.observer, f.opt, slog.New(f.logs))
}

// handleRows 는 스트림 s1 에 조각을 4초 간격으로 차례로 넣는다.
func (f *fixture) handleRows(n int) {
	f.t.Helper()
	for i := range n {
		f.mustHandle(f.segment("s1", segName(baseWall, time.Duration(i)*4*time.Second), 1000, recording.ReasonNextFile))
	}
}

// 같은 회차 안 세션 사이 비귀속 행(j → u → k)은 k 에서 unattributed_run 한 줄로 닫힌다 — same_round 참이고 묶음 길이가 그 회차의
// E28 제외 길이다. k 는 판 NULL 귀속 행이라 그 U(u 길이)를 legacy_unattributed_sum 으로 남긴다. 잡는 결함: 줄이 없으면
// E28 제외 길이를 잴 길이 없다.
func TestUnattributedRunClosesAtNextAttributedRowInSameRound(t *testing.T) {
	f := newFixture(t, 4000, 3900, 4000, 4000)
	f.store.scriptSessions("S-1", "", "S-1", "S-1")
	f.readMarkers(&markerA, &markerA, &markerA, &markerA)

	f.handleRows(4)

	runs := f.logs.attrsAll("unattributed_run")
	if len(runs) != 1 {
		t.Fatalf("unattributed_run = %d건, want 1", len(runs))
	}
	got := runs[0]
	want := map[string]any{"stream_id": "s1", "count": int64(1), "duration_ms": int64(3900), "first_seq": int64(1),
		"last_seq": int64(1), "prev_session_id": "S-1", "next_session_id": "S-1", "same_round": true, "partial": false,
		"marker_next_count": int64(1), "marker_next_ms": int64(3900), "marker_prev_count": int64(0),
		"marker_prev_ms": int64(0), "marker_other_count": int64(0), "marker_other_ms": int64(0)}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("unattributed_run %s = %v(%T), want %v", k, got[k], got[k], v)
		}
	}
	sums := f.logs.attrsAll("legacy_unattributed_sum")
	if len(sums) != 1 || sums[0]["seq"] != int64(2) || sums[0]["u_ms"] != int64(3900) || sums[0]["session_id"] != "S-1" {
		t.Errorf("legacy_unattributed_sum = %v, want 한 건(seq 2 · u_ms 3900 · session_id S-1)", sums)
	}
	if n := f.logs.count(slog.LevelInfo, "unattributed_run"); n != 1 {
		t.Errorf("unattributed_run Info = %d건, want 1", n)
	}
}

// 새 회차 앞 비귀속 셋(앞 표식 4100ms · 새 표식 3900ms · mtxi 없음 4200ms)은 same_round 거짓으로 닫히고, 표식별 길이로 갈린다 —
// 앞 귀속 행과 같은 표식은 marker_prev(닫힌 회차 꼬리), k 와 같은 표식은 marker_next(새 회차 앞부분), mtxi 없는 행은
// marker_other 다(r6 cx-N10 — 표식별 행 수만 세면 섞인 묶음의 갈래별 길이를 되살릴 수 없다).
func TestUnattributedRunSplitsLengthByMarker(t *testing.T) {
	f := newFixture(t, 4000, 4100, 3900, 4200, 4000)
	f.store.scriptSessions("S-1", "", "", "", "S-2")
	f.readMarkers(&markerA, &markerA, &markerB, nil, &markerB)

	f.handleRows(5)

	got := f.logs.attrs("unattributed_run")
	want := map[string]any{"count": int64(3), "duration_ms": int64(12200), "first_seq": int64(1), "last_seq": int64(3),
		"prev_session_id": "S-1", "next_session_id": "S-2", "same_round": false,
		"marker_prev_count": int64(1), "marker_prev_ms": int64(4100), "marker_next_count": int64(1),
		"marker_next_ms": int64(3900), "marker_other_count": int64(1), "marker_other_ms": int64(4200)}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("unattributed_run %s = %v, want %v", k, got[k], v)
		}
	}
}

// 묶음 뒤 귀속 행이 끝내 오지 않으면(방송 종료) 줄이 없다 — E28 은 회차가 있는 컷의 제외량이라 그 묶음은 셈 밖이다. 비귀속
// 행 없이 잇는 귀속 행도 줄을 내지 않는다(U = 0 이라 legacy_unattributed_sum 도 없다).
func TestUnattributedRunNeedsFollowingAttributedRow(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000, 4000)
	f.store.scriptSessions("S-1", "S-1", "", "")
	f.readMarkers(&markerA, &markerA, &markerA, &markerA)

	f.handleRows(4)

	if n := len(f.logs.attrsAll("unattributed_run")); n != 0 {
		t.Errorf("unattributed_run = %d건, want 0", n)
	}
	if n := len(f.logs.attrsAll("legacy_unattributed_sum")); n != 0 {
		t.Errorf("legacy_unattributed_sum = %d건, want 0", n)
	}
}

// 재기동 뒤 첫 묶음은 앞 귀속 행을 이 프로세스가 보지 못했다 — partial 참 · prev_session_id 빈 값 · same_round 거짓이다(재적재한
// 꼬리가 귀속 행인지는 커서가 싣지 않아 모름 쪽으로 둔다). 처음 생긴 스트림(앞 행 없음)의 비귀속 머리는 partial 이 아니다.
func TestUnattributedRunAfterRestartIsPartial(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	f.store.scriptSessions("")
	f.readMarkers(&markerB)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
	f.restart()
	f.store.scriptSessions("", "S-2")
	f.readMarkers(&markerB, &markerB)

	f.mustHandle(f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile))
	f.mustHandle(f.segment("s1", segName(baseWall, 8*time.Second), 1000, recording.ReasonNextFile))

	runs := f.logs.attrsAll("unattributed_run")
	if len(runs) != 1 {
		t.Fatalf("unattributed_run = %d건, want 1", len(runs))
	}
	got := runs[0]
	if got["partial"] != true || got["prev_session_id"] != "" || got["same_round"] != false || got["first_seq"] != int64(1) {
		t.Errorf("재기동 뒤 묶음 = %v, want partial 참 · prev_session_id \"\" · same_round 거짓 · first_seq 1", got)
	}

	g := newFixture(t, 4000, 4000)
	g.store.scriptSessions("", "S-1")
	g.readMarkers(&markerA, &markerA)
	g.handleRows(2)
	if got := g.logs.attrs("unattributed_run"); got["partial"] != false {
		t.Errorf("새 스트림 비귀속 머리 partial = %v, want false", got["partial"])
	}
}
