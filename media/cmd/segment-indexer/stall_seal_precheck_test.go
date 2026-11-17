package main

// L2′ 봉인 전 장부 끝 확인(POK-591). 스위치를 켠 상자에서 다른 상자가 넣은 행의 push 가 없으면 캐시는 첫 감시까지 뒤처짐을
// 모르고, 봉인 문턱(5.5초)이 감시(30초)보다 먼저 와서 복구 전에 회차를 봉인했다. 봉인 작업(Publisher.EndlistIfCaughtUp)은 fence 를
// 잡은 뒤 같은 트랜잭션에서 장부 마지막 seq 를 읽어, 뷰 다음 seq 이상이면 되돌리고 봉인 없이 retry ledger_ahead 로 돌아온다 —
// 루프는 그 값을 캐시에 알린다. 확인이 봉인 작업 안이라 차선에 따로 내는 단계가 없다. 남는 창은
// 확인부터 ENDLIST 저장 확정까지다.

import (
	"log/slog"
	"slices"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// first 는 그 메시지의 첫 기록이다(last 의 짝 — finalize_loop_fixture_test.go).
func (c *logCapture) first(msg string) *capRec {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, r := range c.records {
		if r.Message != msg {
			continue
		}
		out := &capRec{level: r.Level, attrs: map[string]any{}}
		r.Attrs(func(a slog.Attr) bool {
			out.attrs[a.Key] = a.Value.Any()
			return true
		})
		return out
	}
	return nil
}

// sealTicks 는 loopBase 뒤 from 부터 to 까지 1초마다 holdTick 하나를 돌린다(holdTick 1초 — 운영 기본값).
func (f *publishLoop) sealTicks(from, to time.Duration) {
	f.t.Helper()
	for d := from; d <= to; d += time.Second {
		f.at(d)
	}
}

// aheadLedger 는 뷰보다 앞선 장부 꼴이다 — 다른 상자가 4(③ 영구 실패)와 그 뒤 5 · 6(③ 확정)을 넣었다.
func aheadLedger() index.RewindLedger {
	l := liveLedger("s1", "S", 6, 3)
	l.Rows[5].PlaybackUploaded, l.Rows[6].PlaybackUploaded = true, true
	return l
}

// 4.5초 틱 뒤 5.5초 봉인 작업 전에 다른 상자가 행을 넣으면 봉인 작업이 그때의 장부를 본다. 4.5초까지 장부
// 끝은 3(진짜 끝처럼 보임)이고 그 뒤 4 – 6 이 들어온다(push 없음). 5.5초 봉인 작업이 장부 끝 6 을 읽어
// 봉인하지 않고, 루프가 캐시에 알려 적재 뒤 GAP · 5 · 6 발행으로 이어진다. 봉인보다 먼저 확인한 결과를
// 썼다면 5.5초에 봉인했다(ending · PublishedSeq 3).
func TestStallSealChecksLedgerAtSealTime(t *testing.T) {
	f, sc, ends := newSealLoop(t)
	sc.ledger, sc.hasLedger = 3, true
	stallAfterPublish(f)
	f.work.ledgers["s1"] = aheadLedger()

	f.at(4500 * time.Millisecond)
	sc.mu.Lock()
	sc.ledger = 6 // 4.5 – 5.5초 사이에 다른 상자가 4 – 6 을 넣었다
	sc.mu.Unlock()
	f.sealTicks(5500*time.Millisecond, 26*time.Second)

	if len(ends.calls) != 0 {
		t.Fatalf("종료 = %v, want 없음(봉인 순간 장부가 앞서 있다)", ends.calls)
	}
	sc.mu.Lock()
	nexts := slices.Clone(sc.nexts)
	sc.mu.Unlock()
	if !slices.Equal(nexts, []int64{4}) {
		t.Errorf("봉인 작업의 뷰 다음 seq = %v, want [4] — 한 번(그 뒤 사다리 까닭은 view_behind)", nexts)
	}
	if n := len(f.work.loadCalls()); n != 1 {
		t.Errorf("적재 = %d번, want 1", n)
	}
	if p := f.rewind.streams["s1"].pub.Prev; p == nil || p.PublishedSeq != 6 {
		t.Errorf("발행본 = %+v, want PublishedSeq 6", p)
	}
	if rec := f.logs.first("stall_seal"); rec == nil || rec.level != slog.LevelWarn || rec.attrs["reason"] != "ledger_ahead" ||
		rec.attrs["ledger_last"] != int64(6) || rec.attrs["view_next"] != int64(4) {
		t.Errorf("stall_seal = %+v, want WARN reason=ledger_ahead ledger_last=6 view_next=4", rec)
	}
}

// 진짜 끝(장부에도 뷰 끝 다음 행이 없음)은 이 PR 전과 같은 틱(5.5초)에 봉인하고 다음 틱에 끝낸다 — 앞당긴 확인 단계가 없고, 봉인
// 전 틱(4.5초)에는 아무 작업도 내지 않는다.
func TestStallSealSealsTrueEndOnTime(t *testing.T) {
	f, sc, ends := newSealLoop(t)
	sc.ledger, sc.hasLedger = 3, true
	stallAfterPublish(f)

	f.at(4500 * time.Millisecond)
	if got := sc.got(); len(got) != 0 {
		t.Fatalf("4.5초 봉인 작업 = %v, want 없음", got)
	}
	f.at(5500 * time.Millisecond)
	if got := sc.got(); !slices.Equal(got, []string{"s1/S"}) {
		t.Fatalf("5.5초 봉인 = %v, want [s1/S]", got)
	}
	f.at(6500 * time.Millisecond)
	if !slices.Equal(ends.calls, []string{"s1/S"}) {
		t.Errorf("종료 = %v, want 6.5초에 [s1/S]", ends.calls)
	}
}

// 봉인 전 틱에 차선이 lazy fence 갱신으로 바빠도 봉인 틱은 그대로다. 갱신(마지막 갱신 뒤 3.5초)이 3.5초에
// 나가 4.5초 틱까지 차선을 쥐어도(느린 DB) 따로 예약할 확인이 없으므로, 5.5초에 봉인 작업이 나가고 6.5초에 끝낸다. 확인을 봉인
// 앞의 따로 된 차선 작업으로 두면 4.5초 확인을 못 내 5.5초에 확인 · 6.5초에 봉인이 된다(6.5 + PUT 2 + 엣지 1.5 = 10초).
func TestStallSealOnTimeWhenRenewHeldLaneBefore(t *testing.T) {
	f, sc, ends := newSealLoop(t)
	sc.ledger, sc.hasLedger = 3, true
	stallAfterPublish(f)
	release := make(chan struct{})
	f.work.renewFn = func(st publish.State, _ string) publish.Outcome {
		<-release
		st.RenewedAt = loopBase.Add(3500 * time.Millisecond)
		return publish.Outcome{State: st}
	}

	f.hold(loopBase.Add(3500 * time.Millisecond)) // lazy 갱신이 차선을 쥔다
	f.hold(loopBase.Add(4500 * time.Millisecond)) // 차선이 바쁘다
	if j := f.running("s1"); j == nil || j.kind != jobRenew {
		t.Fatalf("준비: 4.5초 틱의 차선 = %+v, want 도는 lazy 갱신", j)
	}
	close(release)
	f.settle(loopBase.Add(4600 * time.Millisecond))
	f.sealTicks(5500*time.Millisecond, 6500*time.Millisecond)

	if got := sc.got(); !slices.Equal(got, []string{"s1/S"}) || !slices.Equal(ends.calls, []string{"s1/S"}) {
		t.Errorf("봉인 %v · 종료 %v, want 5.5초 봉인 · 6.5초 종료", got, ends.calls)
	}
}

// 봉인 문턱 앞의 GAP 재시도를 밀어내지 않는다. 4 의 첫 GAP 틱(24초)이 서지 않고(unstood) 정체가 4.5 – 5.5초 구간인
// 다음 틱(25초)에 GAP 을 다시 내면 그 틱이 차선을 받아 GAP 이 선다 — 봉인 전 마지막 재시도가 사라지지 않고 5 · 6
// 발행이 이어지며 회차는 live 다. 확인을 따로 된 차선 작업으로 두면 같은 틱의 그 작업이 차선을 먼저 잡아 GAP 요청이
// 후보 없는 평시 틱으로 바뀐다.
func TestStallSealKeepsGapRetryBeforeSeal(t *testing.T) {
	f, sc, ends := newSealLoop(t)
	sc.ledger, sc.hasLedger = 6, true
	f.put("s1", aheadLedger())
	published := loopBase.Add(20 * time.Second)
	f.publishOnce("s1", "S", 3, published) // 정체 기점 20초 · 행 4 의 dueAt 22초
	failed := false
	f.work.tickFn = func(st publish.State, in publish.TickInput) publish.Outcome {
		if in.GapCandidate != nil && !failed {
			failed = true
			return publish.Outcome{State: st} // 첫 GAP 틱은 서지 않는다
		}
		return publishedOutcome(st, in, published)
	}

	f.at(24 * time.Second) // 정체 4초 — GAP 틱(서지 않음)
	f.at(25 * time.Second) // 정체 5초 — GAP 재시도

	if len(ends.calls) != 0 || len(sc.got()) != 0 {
		t.Fatalf("봉인 %v · 종료 %v, want 없음(GAP 이 섰다)", sc.got(), ends.calls)
	}
	if p := f.rewind.streams["s1"].pub.Prev; p == nil || p.PublishedSeq != 6 {
		t.Errorf("발행본 = %+v, want PublishedSeq 6(GAP 재시도가 섰다)", p)
	}
}

// 장부를 못 읽은 봉인(ledger_failed — DB 장애 · 확인 시한)은 그 틱만 봉인하지 않고 다음 틱에 다시 낸다(POK-591). 갱신 의무
// 안에 닫는 것이 L2′ 의 목적이라 쉬지 않는다. 다만 WARN 은 같은 (회차, 사유, 장부 끝)에 한 줄이다 — 틱마다 같은 줄이 쌓이지
// 않는다. DB 가 돌아오면 그 틱에 봉인하고 다음 틱에 끝낸다.
func TestStallSealLedgerFailedRetriesEachTickWarnsOnce(t *testing.T) {
	f, sc, ends := newSealLoop(t)
	failed := publish.FreezeOutcome{Axis: publish.AxisDVR, Result: publish.ResultRetry, Retry: true,
		Reason: publish.ReasonLedgerFailed}
	sc.outs = []publish.FreezeOutcome{failed, failed, failed}
	stallAfterPublish(f)

	f.sealTicks(5500*time.Millisecond, 9500*time.Millisecond)

	if got := sc.got(); len(got) != 4 {
		t.Errorf("봉인 작업 = %d번, want 4(실패 셋 · 성공 하나 — 틱마다)", len(got))
	}
	if !slices.Equal(ends.calls, []string{"s1/S"}) {
		t.Errorf("종료 = %v, want [s1/S](8.5초 봉인 · 9.5초 종료)", ends.calls)
	}
	if n := f.logs.countLevel(slog.LevelWarn, "stall_seal"); n != 1 {
		t.Errorf("stall_seal WARN = %d줄, want 1(같은 사유는 한 줄)", n)
	}
	if rec := f.logs.first("stall_seal"); rec == nil || rec.level != slog.LevelWarn || rec.attrs["reason"] != "ledger_failed" {
		t.Errorf("첫 stall_seal = %+v, want WARN reason=ledger_failed", rec)
	}
}

// 스위치 끔은 봉인 작업이 없다 — 사다리가 Seal 을 내지 않는다.
func TestStallSealOffSealsNothing(t *testing.T) {
	f := newPublishLoop(t)
	sc, ends := &sealCalls{ledger: 6, hasLedger: true}, &gapRunCalls{}
	f.rewind.work.seal, f.rewind.endStallSeal = sc.seal, ends.end
	stallAfterPublish(f)
	for d := 4500 * time.Millisecond; d < time.Minute; d += 5 * time.Second {
		f.at(d)
	}
	if got := sc.got(); len(got) != 0 {
		t.Errorf("꺼짐 봉인 작업 = %v, want 없음", got)
	}
}
