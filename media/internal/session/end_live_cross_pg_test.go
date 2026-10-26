package session

// 4D 와 오프라인 전이(index.EndLive)의 교차 배리어 시험(POK-196 M6-3b 커밋 ③ — 설계 M6 3.3 잠금 순서 · 3.4 「오프라인
// 전이와의 결속」 · 9절 D70). 둘은 같은 live 행을 쓴다. 4D 는 위상 잠금 → 소속 행(live) FOR NO KEY UPDATE 이고, EndLive 는
// 상한 설정 → 위상 잠금 → live 행 UPDATE 다. 같은 순서(위상 → 행)라 교착하지 않는다.

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// d70Rounds 는 교차 회수다 — 한쪽이 먼저 행을 쥐는 판과 위상 잠금을 먼저 쥐는 판이 둘 다 나오게 여러 번 겨룬다.
const d70Rounds = 30

// D70 — 4D 와 EndLive 를 같은 live 행에 동시에 N 회 낸다 → 40P01 0 · 둘 다 끝남 N/N. 어느 쪽이 먼저 가도 결과는 같다:
// 4D 가 먼저면 표식(marked) → EndLive 가 사유 intentional 로 끝내며 회차를 닫고, EndLive 가 먼저면 offline 으로 끝난
// 세션을 4D 가 사유 승격(upgraded) · 회차 닫힘으로 거둔다. 변이 = EndLive 가 행 UPDATE 뒤에 위상 잠금 → 4D(위상 → 행)와
// 순서가 엇갈려 40P01 로 FAIL.
func TestIntentionalEndAndEndLiveNeverDeadlockD70(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stop := time.Now().Add(-2 * time.Second).UTC().Truncate(time.Microsecond)
	o := realCloseRound()

	for i := range d70Rounds {
		stream := fmt.Sprintf("d70-%02d", i)
		id := stream + "-L"
		seedLedger(t, pool, ledgerRow{id: id, streamID: stream, state: "live", roundID: id, startedAt: stop.Add(-time.Hour)})
		seedSegment(t, pool, stream, id, 1, stop.Add(-5*time.Second))

		var (
			wg             sync.WaitGroup
			go4D           = make(chan struct{})
			errEnd, err4D  error
			effect, ending string
		)
		wg.Add(2)
		go func() {
			defer wg.Done()
			<-go4D
			res, err := ApplyIntentionalEnd(ctx, pool, IntentionalEnd{StreamID: stream, StoppedAt: stop, ConnStartedAt: stop.Add(-time.Hour)}, o)
			effect, err4D = res.Effect+res.Skip, err
		}()
		go func() {
			defer wg.Done()
			<-go4D
			ending, errEnd = index.EndLive(ctx, pool, stream, crossClose, noEnqueue)
		}()
		close(go4D)
		wg.Wait()

		if isDeadlock(err4D) || isDeadlock(errEnd) {
			t.Fatalf("%d번째: 교착(40P01) — 4D %v · EndLive %v", i, err4D, errEnd)
		}
		if err4D != nil || errEnd != nil || ending != id {
			t.Fatalf("%d번째: 4D (%s, %v) · EndLive (%q, %v), want 둘 다 성공 · EndLive 가 %s 를 끝냄", i, effect, err4D, ending, errEnd, id)
		}
		if effect != effectMarked && effect != effectUpgraded {
			t.Fatalf("%d번째: 4D 효과 = %q, want marked 또는 upgraded", i, effect)
		}
		got := roundOf(t, pool, id)
		var closed bool
		if err := pool.QueryRow(ctx, `SELECT round_closed_at IS NOT NULL FROM stream_sessions WHERE session_id = $1`, id).Scan(&closed); err != nil {
			t.Fatal(err)
		}
		if got.state != "ending" || strOf(got.endReason) != "intentional" || !closed {
			t.Fatalf("%d번째: %s = (%s, %s, 회차 닫힘 %v), want (ending, intentional, 닫힘) — 효과 %s",
				i, id, got.state, strOf(got.endReason), closed, effect)
		}
	}
}

// isDeadlock 은 err 가 PG 교착 감지(40P01)인가다.
func isDeadlock(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "40P01"
}
