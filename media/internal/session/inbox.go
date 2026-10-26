package session

// 받은편지함 둘(POK-196 M6-3a · 설계 M6 2.2 상태 전달 규칙 · 2.3 · 3.2 holdTick 순서 ㉤).
//
// 장부를 바꾼 쪽(4D 수신부 · 만료 · 루프 차선 결과)은 커밋 뒤 표지만 넣고 돌아간다. 값을 계산해 보내지 않는다 —
// 루프가 holdTick 의 받은편지함 비우기 걸음에서 비우고 스트림마다 장부를 다시 읽는다. 그래서 넣는 쪽은 막히지
// 않아야 하고(뮤텍스 아래 맵 한 칸), 같은 스트림 표지는 한 번으로 합쳐도 잃는 것이 없다(표지는 「바뀌었다」 뿐이다).
// 채널을 쓰지 않는 것은 가득 찬 채널이 HTTP · 만료 고루틴을 세우거나 표지를 버리게 하기 때문이다.

import (
	"cmp"
	"slices"
	"sync"
)

// PlaybackInbox 는 상태 표지 받은편지함이다. Changed 의 메서드 값이 index.PlaybackChanged 다 — 조립(cmd)이 그 값을
// 4D 수신부 · 만료(M6-4)에 넘기고, 루프(M6-3b)가 Drain 한다.
type PlaybackInbox struct {
	mu      sync.Mutex
	streams map[string]struct{}
}

// NewPlaybackInbox 는 빈 받은편지함이다.
func NewPlaybackInbox() *PlaybackInbox {
	return &PlaybackInbox{streams: map[string]struct{}{}}
}

// Changed 는 stream 의 장부가 바뀌었다는 표지를 넣는다. 막히지 않는다. 읽기 실패로 표지를 되돌릴 때도 이것을 부른다.
func (p *PlaybackInbox) Changed(streamID string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.streams[streamID] = struct{}{}
}

// Drain 은 쌓인 표지를 비우고 스트림 목록을 정렬해 돌려준다(차선 발사 순서가 실행마다 같게).
func (p *PlaybackInbox) Drain() []string {
	p.mu.Lock()
	taken := p.streams
	p.streams = map[string]struct{}{}
	p.mu.Unlock()
	out := make([]string, 0, len(taken))
	for s := range taken {
		out = append(out, s)
	}
	slices.Sort(out)
	return out
}

// RevokeIntent 는 계승 해제 요청 하나다 — round_split 의 새 회차 첫 세션(SessionID)이 옛 회차를 계승 중일 때 4D 수신부가 넣는다.
type RevokeIntent struct {
	StreamID  string
	SessionID string
}

// IntentInbox 는 계승 해제 요청 받은편지함이다(설계 2.3 — intents 는 이 요청 하나에만 쓴다). 루프가 비우면 그 세션
// 차선에 해제 작업(fence → Head 화해 → R4 일 때만 P2′)을 낸다(M6-3b).
type IntentInbox struct {
	mu   sync.Mutex
	reqs map[RevokeIntent]struct{}
}

// NewIntentInbox 는 빈 받은편지함이다.
func NewIntentInbox() *IntentInbox {
	return &IntentInbox{reqs: map[RevokeIntent]struct{}{}}
}

// Post 는 해제 요청을 넣는다. 막히지 않는다. 같은 (스트림, 세션) 쌍은 합쳐지고 다른 세션은 따로 남는다.
func (i *IntentInbox) Post(streamID, sessionID string) {
	i.mu.Lock()
	defer i.mu.Unlock()
	i.reqs[RevokeIntent{StreamID: streamID, SessionID: sessionID}] = struct{}{}
}

// Drain 은 쌓인 요청을 비우고 (스트림, 세션) 순으로 정렬해 돌려준다.
func (i *IntentInbox) Drain() []RevokeIntent {
	i.mu.Lock()
	taken := i.reqs
	i.reqs = map[RevokeIntent]struct{}{}
	i.mu.Unlock()
	out := make([]RevokeIntent, 0, len(taken))
	for r := range taken {
		out = append(out, r)
	}
	slices.SortFunc(out, func(a, b RevokeIntent) int {
		return cmp.Or(cmp.Compare(a.StreamID, b.StreamID), cmp.Compare(a.SessionID, b.SessionID))
	})
	return out
}
