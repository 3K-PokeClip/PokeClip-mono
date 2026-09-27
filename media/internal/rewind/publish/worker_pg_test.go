package publish

// 발행 워커는 값만 받는다(계획 4.5 A3 결정 1 「동시성」 · 체크리스트 A-6 4 · 6.4 ③·init 통지 줄) — 캐시 · Dirty 를
// 만지지 않는다. 실제 캐시(rewind/cache)와 차선으로 잰다. -race 로 돌린다.

import (
	"bytes"
	"context"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
)

// soloCache 는 픽스처 soloFixture(lastSeq)와 같은 장부를 적재한 캐시다 — 루프 고루틴이 소유하는 쪽이다.
func soloCache(lastSeq int64) *cache.Cache {
	f := soloFixture(lastSeq)
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "S", State: "live", InitUploaded: true, TargetDuration: 6},
	}}
	for _, r := range f.rows {
		l.Rows = append(l.Rows, index.RewindRow(r))
	}
	c := &cache.Cache{}
	c.Reload(fxStream, l)
	return c
}

// 캐시가 내준 목록 값은 워커가 도는 동안 루프가 캐시를 고쳐도 흔들리지 않는다 — 워커가 쓰는 행은 발사 때 복사된
// 값이다. 발사 뒤 · 렌더 전에 루프가 seq 3 의 길이를 교정해도(꼬리 교정 push) 올라간 본문은 발사 때 값(4초)을 싣고,
// 워커가 도는 내내 루프가 캐시에 push 를 이어 가도 경합이 없다(-race). 워커가 스냅숏 슬라이스(Snapshot().RowsFrom
// — 캐시의 저장소를 복사 없이 내준다)를 받았다면 두 쪽 다 깨진다.
func TestWorkerRendersOnlyTheValueItWasGiven(t *testing.T) {
	pool := newPool(t)
	insertSessions(t, pool, soloFixture(0))
	store := newFakeStore(t)
	pub := newPublisher(t, pool, store, "w-me", &logRecorder{})
	c := soloCache(20)
	pl, ok := c.Playlist(fxStream, "S", boundary.Window{TailSeq: 0, HeadSeq: 5})
	if !ok {
		t.Fatal("캐시 Playlist = 거짓")
	}
	lanes := NewLanes()
	release, done := make(chan struct{}), make(chan struct{})
	lanes.Start(t.Context(), fxStream, func(ctx context.Context) Result {
		<-release
		defer close(done)
		return Result{Kind: KindPublish, Publish: pub.Tick(ctx, State{}, TickInput{Playlist: pl})}
	})

	c.ApplyTailCorrection(fxStream, 3, 3000) // 발사 뒤 · 렌더 전 교정
	close(release)
	for seq := int64(0); ; seq = (seq + 1) % 20 { // 워커가 도는 내내 push
		select {
		case <-done:
		default:
			c.ApplyTailCorrection(fxStream, seq, 4000)
			c.ApplyPlaybackUploaded(fxStream, seq)
			continue
		}
		break
	}
	var r Result
	select {
	case r = <-lanes.Done():
	case <-time.After(10 * time.Second):
		t.Fatal("워커 결과가 오지 않았다")
	}
	lanes.Finish(r)

	if !r.Publish.Published {
		t.Fatalf("워커의 틱 = %+v, want 발행", r.Publish)
	}
	body, _ := stored(store, soloKey)
	if !bytes.Contains(body, []byte("#EXTINF:4.000,\n"+fxBaseURL+"/dvr/str/seg/000003.m4s\n")) {
		t.Errorf("올라간 본문의 seq 3 길이가 발사 때 값(4초)이 아니다:\n%s", body)
	}
}
