package status_test

// Board(불변 스냅숏 · 3.2절 응답의 404 · 503 갈래 · 경계 확정 4 의 회차 조회 첫 단계 — 보완 2 ㉡ · cc L8)의
// 시험이다. 처리기는 Board 만 읽고 루프의 상태를 만지지 않으므로, Board 가 받은 map 을 복사해 쥐는지(R2)와
// 답의 갈래(Found · NotFound · Unavailable · Miss)가 계약 그대로인지가 여기서 선다.

import (
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

const stale = 30 * time.Second

var built = time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)

func vodEntry(roundID string) status.Entry {
	st := status.Status{Ready: true, Mode: status.ModeVOD, URL: base + "/vod/a", FirstPDT: first, HeadPDT: head}
	return status.Entry{Stream: st, Round: &status.RoundEntry{RoundID: roundID, Status: st}}
}

// R2: NewBoard 는 받은 map 을 복사한다 — Store 뒤에 루프가 같은 map 을 고쳐도 처리기가 본 스냅숏은 그대로다.
func TestNewBoardCopiesEntries(t *testing.T) {
	entries := map[string]status.Entry{"s": vodEntry("R1")}
	b := status.NewBoard(built, true, entries)
	entries["s"] = status.Entry{Stream: status.Status{Mode: status.ModeLive}}
	delete(entries, "s")
	entries["t"] = vodEntry("R2")

	if got, answer := b.Find("s", built, stale); answer != status.Found || got.Mode != status.ModeVOD {
		t.Errorf("Find(s) = (%+v, %v), want vod Found — NewBoard 뒤 map 변경이 스냅숏에 닿았다", got, answer)
	}
	if _, answer := b.Find("t", built, stale); answer != status.NotFound {
		t.Errorf("Find(t) answer = %v, want NotFound — NewBoard 뒤에 더한 항목이 스냅숏에 닿았다", answer)
	}
}

func TestFindAnswers(t *testing.T) {
	entries := map[string]status.Entry{"s": vodEntry("R1")}
	for _, c := range []struct {
		name   string
		board  *status.Board
		stream string
		now    time.Time
		want   status.Answer
	}{
		{"아는_스트림", status.NewBoard(built, true, entries), "s", built, status.Found},
		{"부팅_감시_전에도_아는_스트림은_답", status.NewBoard(built, false, entries), "s", built, status.Found},
		{"부팅_감시_뒤_모르는_스트림", status.NewBoard(built, true, entries), "x", built, status.NotFound},
		{"부팅_감시_전_모르는_스트림", status.NewBoard(built, false, entries), "x", built, status.Unavailable},
		{"낡음_경계_안", status.NewBoard(built, true, entries), "s", built.Add(stale), status.Found},
		{"낡음", status.NewBoard(built, true, entries), "s", built.Add(stale + time.Millisecond), status.Unavailable},
		// 꺼짐 Board 는 갱신되지 않으므로 낡음보다 먼저 404 다(3.3절 꺼짐 행).
		{"꺼짐", status.DisabledBoard(), "s", built.Add(time.Hour), status.NotFound},
		{"첫_스냅숏_전", nil, "s", built, status.Unavailable},
	} {
		if _, got := c.board.Find(c.stream, c.now, stale); got != c.want {
			t.Errorf("%s: Find answer = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestFindRoundAnswers(t *testing.T) {
	entries := map[string]status.Entry{
		"s":    vodEntry("R1"),
		"prov": {Stream: status.Status{Mode: status.ModeEnding}}, // Forget 잠정 항목 — 회차 없음
		// 회차 칸이 빈 RoundID 를 든 항목 — 빈 roundID 요청과 맞으면 안 된다.
		"blank": {Stream: status.Status{Mode: status.ModeEnding}, Round: &status.RoundEntry{Status: status.Status{Mode: status.ModeEnding}}},
	}
	warm, cold := status.NewBoard(built, true, entries), status.NewBoard(built, false, entries)
	for _, c := range []struct {
		name          string
		board         *status.Board
		stream, round string
		now           time.Time
		want          status.Answer
		wantHit       bool
	}{
		{"같은_회차_hit", warm, "s", "R1", built, status.Found, true},
		{"부팅_감시_전_hit", cold, "s", "R1", built, status.Found, true},
		{"다른_회차_Miss", warm, "s", "R2", built, status.Miss, false},
		{"빈_회차는_hit_아님", warm, "s", "", built, status.Miss, false},
		{"잠정_항목은_Miss", warm, "prov", "", built, status.Miss, false},
		{"빈_RoundID_칸과_빈_회차는_Miss", warm, "blank", "", built, status.Miss, false},
		{"Board_밖_스트림_Miss", warm, "x", "R9", built, status.Miss, false},
		{"부팅_감시_전_모름", cold, "x", "R9", built, status.Unavailable, false},
		{"낡음", warm, "s", "R1", built.Add(stale + time.Millisecond), status.Unavailable, false},
		{"꺼짐", status.DisabledBoard(), "s", "R1", built, status.NotFound, false},
		{"첫_스냅숏_전", nil, "s", "R1", built, status.Unavailable, false},
	} {
		got, answer, hit := c.board.FindRound(c.stream, c.round, c.now, stale)
		if answer != c.want || hit != c.wantHit {
			t.Errorf("%s: FindRound = (%v, hit %v), want (%v, hit %v)", c.name, answer, hit, c.want, c.wantHit)
		}
		if hit && got != entries["s"].Round.Status {
			t.Errorf("%s: FindRound hit Status = %+v, want 종료 항목 회차 사본 %+v", c.name, got, entries["s"].Round.Status)
		}
	}
}

func TestHolderLoadBeforeStoreIsNil(t *testing.T) {
	var h status.Holder
	if h.Load() != nil {
		t.Fatal("Store 전 Load = nil 이 아니다")
	}
	b := status.NewBoard(built, true, nil)
	h.Store(b)
	if h.Load() != b {
		t.Error("Load 가 Store 한 Board 를 돌려주지 않는다")
	}
}

// Age 는 낡음 게이지의 재료다 — 처리기가 답과 따로 「스냅숏이 얼마나 낡았나」를 읽는다. 첫 스냅숏 전 · 꺼짐 Board 는
// 낡음을 말하지 않는다(갱신되지 않는 것이 정상이다).
func TestBoardAge(t *testing.T) {
	b := status.NewBoard(built, true, nil)
	if age, ok := b.Age(built.Add(31 * time.Second)); !ok || age != 31*time.Second {
		t.Errorf("Age = (%v, %v), want (31s, true)", age, ok)
	}
	if _, ok := status.DisabledBoard().Age(built); ok {
		t.Error("꺼짐 Board 의 Age ok = true, want false")
	}
	if _, ok := (*status.Board)(nil).Age(built); ok {
		t.Error("nil Board 의 Age ok = true, want false")
	}
}
