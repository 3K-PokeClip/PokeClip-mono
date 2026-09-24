package upload

import (
	"sync"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// DirtyKind 는 이벤트의 종류다. 값이 1 부터인 이유는 안 채운 원소가 조용히 한 종류로
// 읽히지 않게 하기 위해서다(index.Axis 와 같은 이디엄).
type DirtyKind uint8

const (
	// DirtyUploaded 는 ③ 조각 또는 세션 init 이 장부에 uploaded 로 확정됐다는 사실이다.
	DirtyUploaded DirtyKind = iota + 1
	// DirtySplit 은 그 세션이 init 불일치로 끝났다는 사실이다 — 조각 failed 와 세션
	// ending(init_mismatch)이 장부에 이미 영속된 뒤에 표시한다(설계 5.3ⓒ).
	DirtySplit
)

// DirtyEvent 는 Dirty 의 원소다. **같은 사실은 같은 값이다** — 값 자체가 원소 키라서 같은
// 사실을 두 번 표시해도 한 원소로 접힌다. 종류마다 채우는 칸이 정해져 있다:
//
//	③ 업로드   {DirtyUploaded, stream, AxisPlayback, seq, 조각의 세션, ""}
//	init 업로드 {DirtyUploaded, stream, AxisInit, 0, 세션, ""}  — init 축은 seq 를 쓰지 않는다
//	분리        {DirtySplit, stream, 0, 0, 세션, 사유}           — 세션마다 하나
type DirtyEvent struct {
	Kind      DirtyKind
	StreamID  string
	Axis      index.Axis
	Seq       int64
	SessionID string
	Reason    string
}

// Dirty 는 ③·init 결과를 되감기 루프에 알리는 이벤트 집합이다(계획 2.1 · 부기 18 — 결과
// 채널 대신). 워커가 CAS 성공(또는 init 불일치 영속) 뒤에 표시하고, 루프가 Peek 으로 읽어
// 메모리 캐시에 반영한 뒤 Ack 로 지운다.
//
// 채널이 아니라 집합인 이유: 워커는 멈추면 안 되고(표시는 뮤텍스 한 번이며 막히지 않는다)
// 사실은 잃으면 안 된다(가득 차서 버리는 일이 없다). 같은 사실은 한 원소로 접히므로 크기는
// 표시 횟수가 아니라 아직 Ack 되지 않은 사실의 가짓수로 묶인다.
//
// 영값을 그대로 쓴다. nil 이면 모든 메서드가 아무것도 하지 않는다 — 소비자가 없는 국면에는
// 집합을 만들지 않아 아무도 비우지 않는 원소가 쌓이지 않는다. 고루틴 안전하다.
type Dirty struct {
	mu     sync.Mutex
	events map[DirtyEvent]struct{}
}

// markUploaded 는 "이 축의 이 대상이 uploaded 로 확정됐다"를 표시한다.
func (d *Dirty) markUploaded(streamID string, axis index.Axis, seq int64, sessionID string) {
	if axis == index.AxisInit {
		seq = 0 // init 의 유일성 축은 세션이다 — seq 로 갈리면 같은 사실이 두 원소가 된다
	}
	d.add(DirtyEvent{Kind: DirtyUploaded, StreamID: streamID, Axis: axis, Seq: seq, SessionID: sessionID})
}

// markSplit 은 "이 세션이 끝났다"를 표시한다. 세션마다 하나이며 같은 세션의 업로드 사실이
// 뒤따라도 지워지지 않는다 — 지우는 것은 Ack 뿐이다.
func (d *Dirty) markSplit(streamID, sessionID, reason string) {
	d.add(DirtyEvent{Kind: DirtySplit, StreamID: streamID, SessionID: sessionID, Reason: reason})
}

func (d *Dirty) add(e DirtyEvent) {
	if d == nil {
		return
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.events == nil {
		d.events = map[DirtyEvent]struct{}{}
	}
	d.events[e] = struct{}{}
}

// Peek 은 지금 들고 있는 원소의 복사본이다. 집합은 비우지 않는다 — 처리에 실패한 소비자는
// Ack 를 하지 않고 다음 차례에 같은 원소를 다시 본다. 순서는 정하지 않는다.
func (d *Dirty) Peek() []DirtyEvent {
	if d == nil {
		return nil
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	out := make([]DirtyEvent, 0, len(d.events))
	for e := range d.events {
		out = append(out, e)
	}
	return out
}

// Ack 는 소비자가 반영을 마친 원소를 지운다. 넘긴 원소만 지운다 — Peek 뒤에 새로 표시된
// 사실은 남는다.
func (d *Dirty) Ack(events []DirtyEvent) {
	if d == nil {
		return
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	for _, e := range events {
		delete(d.events, e)
	}
}
