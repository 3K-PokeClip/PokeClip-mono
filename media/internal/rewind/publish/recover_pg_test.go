package publish

// 좌초 회수(RecoverOrphan · D17 4.1(d))와 R3 terminal 승격 · freeze_failed 확정(FailDVR)의 PG 통합 시험이다(POK-196 M6-2
// 커밋 ② · M6 설계 2.1 · 5절 reconcile.go 행 · 9절 D5).

import (
	"bytes"
	"errors"
	"testing"
)

// strandedSetup 은 writer W 가 창 [0, 5] 를 올린 뒤 동결을 시작해(terminal 예약 — freezing) PUT 에서 멈추고 lease 를
// 잃은 국면이다. 후임 B 의 루프를 돌려준다. 저장된 판은 W 의 열린 판 그대로다.
func strandedSetup(t *testing.T) (w, b *loop, store *fakeStore) {
	t.Helper()
	w, store, _ = newFreezeLoop(t, 9, 5)
	store.setOnPut(func(putCall) error { store.setOnPut(nil); return errors.New("connection reset") })
	if out := w.pub.FreezeDVR(t.Context(), fxStream, "S"); !out.Retry {
		t.Fatalf("멈춘 동결 = %+v, want 재시도", out)
	}
	if d := readDVR(t, w, "S"); d.state != "freezing" {
		t.Fatalf("멈춘 동결 뒤 상태 %q, want freezing", d.state)
	}
	expireLease(t, w.pub.pool, "S")
	return w, w.peer("w-successor", &logRecorder{}), store
}

// d5_recover_orphan_seals_stranded_list(D5) — 죽은 writer 가 freezing 에 둔 회차를 후임이 fence 를 얻어 봉인한다. 닫힌
// 판은 W 의 마지막 발행 본문 + ENDLIST 한 줄이다.
func TestRecoverOrphanSealsStrandedList(t *testing.T) {
	w, b, store := strandedSetup(t)
	before, _ := stored(store, soloKey)

	wantOutcome(t, b.pub.RecoverOrphan(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)

	body, _ := stored(store, soloKey)
	if want := append(bytes.Clone(before), "#EXT-X-ENDLIST\n"...); !bytes.Equal(body, want) {
		t.Errorf("회수한 판 =\n%s\nwant W 의 본문 + ENDLIST", body)
	}
	wantFrozen(t, w, store, "S", soloKey)
}

// 회수가 실패하면 10분(FREEZE_ORPHAN_HARD) 전에는 다음 주기에 다시 하고, 넘으면 freeze_failed 로 확정한다(D17 4.1(d) ·
// 설계 3.1 좌초 줄). 시각은 DB 시계(dvr_freezing_at)로 잰다.
func TestRecoverOrphanFailsAfterHardDeadline(t *testing.T) {
	for _, tc := range []struct {
		name      string
		age       string
		result    string
		retry     bool
		wantState string
	}{
		{"10분_전", "9 minutes", ResultRetry, true, "freezing"},
		{"10분_뒤", "11 minutes", ResultFailed, false, "freeze_failed"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w, b, store := strandedSetup(t)
			exec(t, w.pub.pool, `UPDATE stream_sessions SET dvr_freezing_at = now() - $1::interval WHERE session_id = 'S'`, tc.age)
			store.setOnPut(func(putCall) error { return errors.New("503 Service Unavailable") })

			wantOutcome(t, b.pub.RecoverOrphan(t.Context(), fxStream, "S"), AxisDVR, tc.result, tc.retry)

			if d := readDVR(t, w, "S"); d.state != tc.wantState {
				t.Errorf("회수 실패 뒤 상태 %q, want %s", d.state, tc.wantState)
			}
		})
	}
}

// 10분이 넘었어도 다른 writer 가 lease 안에서 fence 를 쥐고 있으면 그 writer 가 살아서 닫는 중이다 — freeze_failed 로
// 덮지 않는다.
func TestRecoverOrphanLeavesLiveWriterAlone(t *testing.T) {
	w, b, _ := strandedSetup(t)
	exec(t, w.pub.pool, `UPDATE stream_sessions SET dvr_freezing_at = now() - interval '11 minutes' WHERE session_id = 'S'`)
	if ok, err := w.pub.acquire(t.Context(), "S"); !ok || err != nil {
		t.Fatalf("W 의 fence 재획득 = %t · %v", ok, err)
	}

	wantOutcome(t, b.pub.RecoverOrphan(t.Context(), fxStream, "S"), AxisDVR, ResultRetry, true)

	if d := readDVR(t, w, "S"); d.state != "freezing" {
		t.Errorf("살아 있는 writer 의 회차 상태 %q, want freezing 그대로", d.state)
	}
}

// FailDVR 는 3단(2단 봉인도 실패)의 종국 확정이다 — fence 를 얻어 아직 닫히지 않은 회차를 freeze_failed 로 쓴다. 닫힌
// 회차는 건드리지 않는다.
func TestFailDVR(t *testing.T) {
	t.Run("freezing", func(t *testing.T) {
		_, b, _ := strandedSetup(t)
		wantOutcome(t, b.pub.FailDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFailed, false)
		if d := readDVR(t, b, "S"); d.state != "freeze_failed" {
			t.Errorf("상태 %q, want freeze_failed", d.state)
		}
	})
	t.Run("이미_닫힘", func(t *testing.T) {
		l, _, _ := newFreezeLoop(t, 9, 5)
		wantOutcome(t, l.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)
		wantOutcome(t, l.pub.FailDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)
		if d := readDVR(t, l, "S"); d.state != "frozen" {
			t.Errorf("상태 %q, want frozen 그대로", d.state)
		}
	})
	t.Run("fence_양보", func(t *testing.T) {
		l, _, _ := newFreezeLoop(t, 9, 5)
		other := l.peer("w-other", &logRecorder{})
		wantOutcome(t, other.pub.FailDVR(t.Context(), fxStream, "S"), AxisDVR, ResultRetry, true)
		if d := readDVR(t, l, "S"); d.state != "open" {
			t.Errorf("상태 %q, want open 그대로", d.state)
		}
	})
}

// r3_promotes_terminal_head(설계 5절 reconcile.go 행 · D17 4.4.3 R3) — 저장된 판이 닫혔는데(메타 terminal) 장부가 아직
// 열려 있으면(봉인 PUT 뒤 확정 전에 멈춤) 화해가 장부를 frozen 으로 올린다. 평시 틱의 화해도 같은 문장이라, 그 회차의
// 다음 틱은 저장된 닫힌 판에 맞춘 뒤 더는 예약하지 않는다.
func TestReconcilePromotesTerminalHead(t *testing.T) {
	l, store, _ := newFreezeLoop(t, 9, 5)
	wantOutcome(t, l.pub.Endlist(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)
	exec(t, l.pub.pool, `UPDATE stream_sessions SET dvr_state = 'open', dvr_s3_key = NULL, dvr_etag = NULL, dvr_sha256 = NULL,
		dvr_first_seq = NULL, dvr_last_seq = NULL, dvr_disc_base = NULL, dvr_frozen_at = NULL WHERE session_id = 'S'`)
	l.st.ReconcileDue = true
	puts := len(store.putCalls())

	for range 2 {
		if out := l.tick(t.Context(), 0, 9); out.Published {
			t.Fatalf("닫힌 판 위 틱 = %+v, want 발행 없음", out)
		}
	}

	if got := len(store.putCalls()); got != puts {
		t.Errorf("닫힌 판 위 PUT %d번, want 0", got-puts)
	}
	wantFrozen(t, l, store, "S", soloKey)
}

// 열린 판의 화해는 동결 열을 건드리지 않는다.
func TestReconcileLeavesOpenListOpen(t *testing.T) {
	l, _, _ := newFreezeLoop(t, 9, 5)
	l.st.ReconcileDue = true

	l.mustPublish(t.Context(), 0, 7)

	if d := readDVR(t, l, "S"); d.state != "open" || d.etag != nil || d.frozenAt != nil {
		t.Errorf("열린 판 화해 뒤 동결 열 %+v, want open · 비어 있음", d)
	}
}
