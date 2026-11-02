package main

// 동결 감사 배선(D17 4.1(b) · POK-196 M6 PR 2 갈래 A4 — kty 2026-10-29 「추천대로」). 감사는 감시 주기마다 감시와 함께 나가되
// 예약 차선(audit:frozen)에서 돈다 — 감시와 한 차선을 쓰면 오래 걸리는 감사가 감시를 굶긴다(D66 과 같은 처분). 픽스처는
// publish_loop_fixture_test.go 다.

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// withAudit 는 루프에 가짜 감사 몸체를 끼운다(운영은 조립이 FrozenAuditor.Audit 를 끼운다).
func (f *publishLoop) withAudit() { f.rewind.work.audit = f.work.audit }

// 감사는 감시 주기마다 감시와 함께 나가고 감시와 다른 차선에서 돈다 — 감시가 멈춰 있어도 감시 주기 셋에 감사 결과가 셋 온다.
// 변이 = 감사를 프로세스 차선(StartProcess)에 냄 → 감시와 병합돼 FAIL.
func TestAuditRunsEachWatchPeriodBesideStuckWatch(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.withAudit()
	f.work.watchGate = make(chan struct{})
	t.Cleanup(func() { f.work.open(&f.work.watchGate) })

	f.hold(loopBase)
	waitFor(t, "첫 감시가 문에 닿음", func() bool { return len(f.work.watchCalls()) == 1 })
	f.settleAudit(loopBase, 1)
	for i := 1; i < 3; i++ {
		now := loopBase.Add(time.Duration(i) * 30 * time.Second)
		f.hold(now)
		f.settleAudit(now, i+1)
	}

	if n := f.work.auditCalls(); n != 3 {
		t.Errorf("감사 = %d번, want 3(감시 주기마다)", n)
	}
	if n := len(f.work.watchCalls()); n != 1 {
		t.Errorf("감시 = %d번, want 1(첫 감시가 멈춰 있고 뒤 차례는 병합)", n)
	}
}

// 느린 감사 — 감사가 도는 동안 다음 감시 주기가 오면 병합하고, 결과를 받은 뒤 한 번 더 낸다(겹쳐 돌지 않는다).
func TestMergedAuditRerunsOnceAfterSlowAudit(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.withAudit()
	f.work.auditGate = make(chan struct{})

	f.hold(loopBase)
	waitFor(t, "첫 감사가 문에 닿음", func() bool { return f.work.auditCalls() == 1 })
	f.hold(loopBase.Add(30 * time.Second))
	time.Sleep(50 * time.Millisecond) // 병합되지 않았다면 둘째 몸체가 이 사이에 문에 닿는다
	if n := f.work.auditCalls(); n != 1 {
		t.Fatalf("도는 감사가 있는데 감사 = %d번, want 1(병합)", n)
	}
	f.work.open(&f.work.auditGate)
	f.settleAudit(loopBase.Add(30*time.Second), 2)

	if n := f.work.auditCalls(); n != 2 {
		t.Errorf("감사 = %d번, want 2(병합된 차례는 끝난 뒤 한 번)", n)
	}
}

// 감사 대상을 읽지 못하면 ERROR rewind_job_failed(job=audit) 한 줄이다 — 감시 실패와 같은 키다.
func TestAuditFailureLogsJobFailed(t *testing.T) {
	f := newPublishLoop(t)
	f.watchEveryHold()
	f.withAudit()
	f.work.auditFn = func() publish.AuditOutcome { return publish.AuditOutcome{Err: errors.New("대상 조회 실패")} }

	f.hold(loopBase)
	f.settleAudit(loopBase, 1)

	if a := f.logs.attrs("rewind_job_failed"); a["job"] != "audit" {
		t.Errorf("rewind_job_failed 속성 = %v, want job=audit", a)
	}
}

// 감사 차선 키는 스트림 차선 키와 겹치지 않는다 — 「:」 를 담고 stream_id 화이트리스트는 「:」 를 받지 않는다(정산 차선 키
// 시험과 같은 꼴 · 보안 LOW-1). 변이 = 키에서 「:」 를 뺌 → FAIL.
func TestAuditLaneKeyCannotBeStreamID(t *testing.T) {
	if !strings.Contains(auditLaneKey, ":") || recording.ValidStreamID(auditLaneKey) {
		t.Errorf("감사 차선 키 %q 가 stream_id 로 읽힐 수 있다(「:」 있음 %t · 화이트리스트 통과 %t)",
			auditLaneKey, strings.Contains(auditLaneKey, ":"), recording.ValidStreamID(auditLaneKey))
	}
}
