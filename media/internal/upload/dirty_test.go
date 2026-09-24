package upload

import (
	"sort"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// Dirty 는 ③·init 결과를 루프에 알리는 이벤트 집합이다(계획 2.1 upload.go 행 · 부기 18).
// 여기서는 집합 자체의 계약만 잰다 — 워커가 언제 표시하는가는 playback_axis_test 가 잰다.

// sortedEvents 는 비교용으로 이벤트를 정렬한다. Peek 은 순서를 정하지 않는다.
func sortedEvents(events []DirtyEvent) []DirtyEvent {
	out := append([]DirtyEvent(nil), events...)
	sort.Slice(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if a.Kind != b.Kind {
			return a.Kind < b.Kind
		}
		if a.Axis != b.Axis {
			return a.Axis < b.Axis
		}
		if a.SessionID != b.SessionID {
			return a.SessionID < b.SessionID
		}
		return a.Seq < b.Seq
	})
	return out
}

func sameEvents(t *testing.T, got, want []DirtyEvent) {
	t.Helper()
	g, w := sortedEvents(got), sortedEvents(want)
	if len(g) != len(w) {
		t.Fatalf("이벤트 = %+v, want %+v", g, w)
	}
	for i := range g {
		if g[i] != w[i] {
			t.Fatalf("이벤트 = %+v, want %+v", g, w)
		}
	}
}

var (
	playbackUploaded7 = DirtyEvent{Kind: DirtyUploaded, StreamID: "demo", Axis: index.AxisPlayback, Seq: 7, SessionID: "S-1"}
	playbackUploaded8 = DirtyEvent{Kind: DirtyUploaded, StreamID: "demo", Axis: index.AxisPlayback, Seq: 8, SessionID: "S-1"}
	initUploadedS1    = DirtyEvent{Kind: DirtyUploaded, StreamID: "demo", Axis: index.AxisInit, SessionID: "S-1"}
	initUploadedS2    = DirtyEvent{Kind: DirtyUploaded, StreamID: "demo", Axis: index.AxisInit, SessionID: "S-2"}
	splitS1           = DirtyEvent{Kind: DirtySplit, StreamID: "demo", SessionID: "S-1", Reason: index.ReasonInitMismatch}
)

// 같은 사실은 한 원소로 접힌다(멱등). 잡는 결함: 원소를 쌓기만 하면 소비자가 느린 동안
// 크기가 사실의 가짓수가 아니라 표시 횟수로 자란다.
func TestDirtyFoldsSameFactIntoOneEvent(t *testing.T) {
	d := &Dirty{}
	d.markUploaded("demo", index.AxisPlayback, 7, "S-1")
	d.markUploaded("demo", index.AxisPlayback, 7, "S-1")

	sameEvents(t, d.Peek(), []DirtyEvent{playbackUploaded7})
}

// Peek 은 복사만 하고 비우지 않는다. 비우는 것은 Ack 뿐이다 — 소비자가 처리에 실패하면
// Ack 를 하지 않아 다음 tick 에 같은 원소를 다시 본다(계획 2.1 「인수 실패 = Ack 안 함」).
func TestDirtyPeekKeepsEventsUntilAck(t *testing.T) {
	d := &Dirty{}
	d.markUploaded("demo", index.AxisPlayback, 7, "S-1")
	d.markUploaded("demo", index.AxisPlayback, 8, "S-1")

	first := d.Peek()
	sameEvents(t, first, []DirtyEvent{playbackUploaded7, playbackUploaded8})
	sameEvents(t, d.Peek(), []DirtyEvent{playbackUploaded7, playbackUploaded8})

	d.Ack([]DirtyEvent{playbackUploaded7})
	sameEvents(t, d.Peek(), []DirtyEvent{playbackUploaded8})
}

// Ack 는 넘긴 원소만 지운다. 잡는 결함: Peek 뒤에 새로 표시된 사실까지 지우면 그 사실은
// 영영 소비자에게 닿지 않는다(유실 불가 계약).
func TestDirtyAckKeepsEventsMarkedAfterPeek(t *testing.T) {
	d := &Dirty{}
	d.markUploaded("demo", index.AxisPlayback, 7, "S-1")
	peeked := d.Peek()
	d.markUploaded("demo", index.AxisPlayback, 8, "S-1")

	d.Ack(peeked)

	sameEvents(t, d.Peek(), []DirtyEvent{playbackUploaded8})
}

// init 사실의 원소 키는 (stream, AxisInit, sessionID) 다(계획 뮤테이션 31). init 축은 seq 를
// 쓰지 않으므로 seq 로 갈리면 안 되고, 세션으로는 갈려야 한다.
func TestDirtyKeepsInitEventsPerSession(t *testing.T) {
	d := &Dirty{}
	d.markUploaded("demo", index.AxisInit, 0, "S-1")
	d.markUploaded("demo", index.AxisInit, 5, "S-1") // 같은 세션 — seq 는 init 축에서 뜻이 없다
	d.markUploaded("demo", index.AxisInit, 0, "S-2")

	sameEvents(t, d.Peek(), []DirtyEvent{initUploadedS1, initUploadedS2})
}

// split 은 세션마다 하나이고, 같은 세션의 uploaded 가 와도 지워지지 않는다(sticky).
// 잡는 결함: 분리 사실이 뒤이은 업로드 사실에 덮이면 루프가 캐시의 세션 상태를 끝내지
// 못한 채 그 세션에 계속 조각을 붙인다.
func TestDirtySplitIsPerSessionAndSticky(t *testing.T) {
	d := &Dirty{}
	d.markSplit("demo", "S-1", index.ReasonInitMismatch)
	d.markSplit("demo", "S-1", index.ReasonInitMismatch)
	d.markUploaded("demo", index.AxisPlayback, 7, "S-1")

	sameEvents(t, d.Peek(), []DirtyEvent{playbackUploaded7, splitS1})

	d.Ack([]DirtyEvent{playbackUploaded7})
	sameEvents(t, d.Peek(), []DirtyEvent{splitS1})
}

// nil 집합은 아무것도 하지 않는다 — 소비자가 없는 국면에서는 집합을 만들지 않으므로
// 워커의 표시가 panic 없이 사라져야 한다(계획 6.4 「ⓐ 단독 국면 메모리 0」).
func TestNilDirtyIsNoop(t *testing.T) {
	var d *Dirty
	d.markUploaded("demo", index.AxisPlayback, 7, "S-1")
	d.markSplit("demo", "S-1", index.ReasonInitMismatch)
	d.Ack([]DirtyEvent{playbackUploaded7})
	if got := d.Peek(); len(got) != 0 {
		t.Fatalf("nil 집합 Peek = %+v, want 빈 결과", got)
	}
}
