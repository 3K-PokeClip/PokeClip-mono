package expiry

// 만료 루프의 방어 시험(POK-196 M6-4 c4) — 보존 기간 안 상한 거부(보안 H1) · 재시도 포화에도 새 방송 진행(cx H2 · D22).

import (
	"fmt"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 보안 H1 — clip 응답의 segmentsUntil 이 보존 기간(60일 − 여유 1일) 안이면 장부에 묻지도 않고 ERROR 한 줄로
// 건너뛴다(fail-closed · 재시도 집합 아님). 응답 한 줄이 비가역 삭제를 부르지 않게 한다.
func TestSweepRejectsUntilInsideRetention(t *testing.T) {
	for name, age := range map[string]time.Duration{
		"미래":          -time.Hour,
		"최근":          time.Hour,
		"59일 하고 1초 전": -time.Second + 59*24*time.Hour,
	} {
		t.Run(name, func(t *testing.T) {
			r := newRig(t, nil)
			r.ledger.put("x", 1)
			it := item("x")
			it.SegmentsUntil = ptr(r.now.Add(-age))
			r.clip.pages[""] = clipPage{items: []clipItem{it}}
			r.sweep(t)

			if calls := r.ledger.callLog(); len(calls) != 0 {
				t.Fatalf("장부에 물었다: %v", calls)
			}
			rejects := r.log.all("expiry_until_rejected")
			if len(rejects) != 1 || rejects[0].level.String() != "ERROR" || rejects[0].attrs["stream_id"] != "x" {
				t.Fatalf("expiry_until_rejected %v (ERROR 한 줄 기대)", rejects)
			}
			if len(r.e.retry) != 0 {
				t.Fatal("재시도 집합에 들어갔다")
			}
		})
	}
}

// 보존 기간 경계 — 상한이 정확히 ExpiryCeiling 이면 지운다(그보다 늦어야 거부).
func TestSweepAcceptsUntilAtCeiling(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("x", 1)
	it := item("x")
	it.SegmentsUntil = ptr(index.ExpiryCeiling(r.now))
	r.clip.pages[""] = clipPage{items: []clipItem{it}}
	r.sweep(t)
	if len(r.ledger.deletedSeqs("x")) != 1 || len(r.log.all("expiry_until_rejected")) != 0 {
		t.Fatalf("경계 상한을 지우지 않았다: 삭제 %v", r.ledger.deletedSeqs("x"))
	}
}

// cx H2 · D22 — 서로 다른 물리 키의 영구 실패 방송이 RunCap(2000)만큼 재시도 집합에 쌓여도 새 방송은 그 회차에
// 처리된다. 재시도는 처리 수(RunCap)에 세지 않는다.
func TestSweepRetrySaturationStillProcessesNewBroadcasts(t *testing.T) {
	r := newRig(t, nil)
	r.e.lastFull = r.now // 부팅 회차가 아니다 — 처음부터 훑기의 재시도 비움과 떼어 본다
	r.e.cursor = ptr("c1")
	r.fillPermanentFailures(DefaultOptions().RunCap)
	r.ledger.put("fresh", 1)
	r.clip.pages["c1"] = clipPage{items: []clipItem{item("fresh")}, next: ptr("c2")}
	r.sweep(t)

	if len(r.ledger.deletedSeqs("fresh")) != 1 {
		t.Fatal("재시도 포화로 새 방송을 처리하지 못했다")
	}
	if len(r.e.retry) != DefaultOptions().RunCap {
		t.Fatalf("재시도 집합 %d (영구 실패 %d 유지 기대)", len(r.e.retry), DefaultOptions().RunCap)
	}
}

// 하루 한 번 처음부터 훑을 때 재시도 집합을 비운다 — 처음부터 훑기가 그 방송을 다시 받으므로 잃지 않고, 영구 실패가
// 하루를 넘겨 쌓이지 않는다.
func TestSweepFullScanClearsRetry(t *testing.T) {
	r := newRig(t, nil)
	r.e.lastFull = r.now
	r.fillPermanentFailures(3)
	r.now = r.now.Add(24 * time.Hour)
	r.sweep(t)
	if len(r.e.retry) != 0 || len(r.ledger.callLog()) != 0 {
		t.Fatalf("처음부터 훑기 뒤 재시도 %d · 장부 호출 %v (비움 · 호출 없음 기대)", len(r.e.retry), r.ledger.callLog())
	}
}

// fillPermanentFailures 는 무효 컷오프로 늘 실패하는 방송 n 개를 재시도 집합에 넣는다.
func (r *rig) fillPermanentFailures(n int) {
	for i := range n {
		b := toBroadcast(item(fmt.Sprintf("dead-%d", i)))
		r.ledger.invalid[b.IngestStreamID] = true
		r.e.retry[b.IngestStreamID] = b
	}
}

func toBroadcast(c clipItem) Broadcast {
	return Broadcast{StreamID: c.StreamID, IngestStreamID: c.IngestStreamID, SegmentsFrom: c.SegmentsFrom, SegmentsUntil: c.SegmentsUntil}
}
