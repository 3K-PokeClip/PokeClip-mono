package session

// 받은편지함 둘(POK-196 M6-3a 커밋 ③ · 설계 M6 2.2 상태 전달 규칙 · 2.3 · 3.2 ㉤) — 상태 표지(PlaybackInbox — index.
// PlaybackChanged 의 구현)와 계승 해제 요청(IntentInbox). 둘 다 막히지 않고 여러 고루틴에서 불러도 되며, 루프가 holdTick
// 의 받은편지함 비우기 걸음에서 비운다(비우는 배선은 M6-3b).

import (
	"fmt"
	"slices"
	"sync"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// PlaybackInbox.Changed 는 index.PlaybackChanged 꼴이다 — M6-4 만료 · POK-472 수신부 · 루프 차선 결과가 같은 함수 값을 받는다.
var _ index.PlaybackChanged = NewPlaybackInbox().Changed

// 같은 스트림 표지는 한 번으로 합쳐진다(값은 루프가 다시 읽는다 — 표지는 「바뀌었다」 뿐). Drain 은 비우고 정렬된 목록을
// 돌려준다. 잡는 결함: 합치지 않으면 표지 수만큼 재조회 작업이 나가고, Drain 이 비우지 않으면 같은 스트림을 매 틱 다시 읽는다.
func TestPlaybackInboxMergesAndDrains(t *testing.T) {
	in := NewPlaybackInbox()
	in.Changed("b")
	in.Changed("a")
	in.Changed("b")

	if got := in.Drain(); !slices.Equal(got, []string{"a", "b"}) {
		t.Fatalf("Drain = %v, want [a b]", got)
	}
	if got := in.Drain(); len(got) != 0 {
		t.Errorf("두 번째 Drain = %v, want 빈 목록(비웠다)", got)
	}
	in.Changed("a") // 읽기 실패 → 표지를 되돌림(설계 2.2 규칙 5)
	if got := in.Drain(); !slices.Equal(got, []string{"a"}) {
		t.Errorf("되돌린 표지 Drain = %v, want [a]", got)
	}
}

// 계승 해제 요청은 (스트림, 세션) 쌍으로 쌓이고 같은 쌍은 합쳐진다. 같은 스트림의 다른 세션은 둘 다 남는다(한 쌍이 다른 쌍을
// 덮으면 해제 하나가 사라진다).
func TestIntentInboxKeepsDistinctPairs(t *testing.T) {
	in := NewIntentInbox()
	in.Post("s1", "S-B")
	in.Post("s1", "S-C")
	in.Post("s1", "S-B")
	in.Post("s0", "S-A")

	want := []RevokeIntent{{StreamID: "s0", SessionID: "S-A"}, {StreamID: "s1", SessionID: "S-B"}, {StreamID: "s1", SessionID: "S-C"}}
	if got := in.Drain(); !slices.Equal(got, want) {
		t.Fatalf("Drain = %v, want %v", got, want)
	}
	if got := in.Drain(); len(got) != 0 {
		t.Errorf("두 번째 Drain = %v, want 빈 목록", got)
	}
}

// 여러 고루틴이 동시에 넣고 비워도 경합 없이 하나도 잃지 않는다(-race 로 돈다).
func TestInboxesAreSafeForConcurrentUse(t *testing.T) {
	pb, it := NewPlaybackInbox(), NewIntentInbox()
	const writers, each = 8, 200
	var wg sync.WaitGroup
	seen := map[string]bool{}
	var mu sync.Mutex
	stop := make(chan struct{})
	drained := make(chan struct{})
	go func() {
		defer close(drained)
		for {
			select {
			case <-stop:
				return
			default:
			}
			got := pb.Drain()
			mu.Lock()
			for _, s := range got {
				seen[s] = true
			}
			mu.Unlock()
			_ = it.Drain()
		}
	}()
	for w := range writers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range each {
				s := fmt.Sprintf("s%d-%d", w, i)
				pb.Changed(s)
				it.Post(s, "S")
			}
		}()
	}
	wg.Wait()
	close(stop)
	<-drained
	for _, s := range pb.Drain() {
		seen[s] = true
	}
	if len(seen) != writers*each {
		t.Errorf("받은 스트림 %d 개, want %d — 표지를 잃었다", len(seen), writers*each)
	}
}
