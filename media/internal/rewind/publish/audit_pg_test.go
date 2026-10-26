package publish

// 동결 감사(AuditFrozen · D17 4.1(b))와 G8-E(봉인 경로의 두 writer)의 PG 통합 시험이다(POK-196 M6-2 커밋 ③).

import (
	"bytes"
	"errors"
	"log/slog"
	"testing"
)

// auditSetup 은 회차 S 의 DVR 을 닫고(창 [0, 5] 발행 뒤 동결) 사슬 P ← S 와 무관한 픽스처다 — 감사 대상은 DVR 하나다.
func auditSetup(t *testing.T) (*loop, *fakeStore, *logRecorder) {
	t.Helper()
	l, store, logs := newFreezeLoop(t, 9, 5)
	wantOutcome(t, l.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)
	return l, store, logs
}

// auditFailures 는 감사 ERROR 줄의 reason 속성들이다.
func auditFailures(logs *logRecorder) []string {
	var out []string
	for _, r := range logs.records(freezeAuditLog) {
		if r.level == slog.LevelError {
			out = append(out, r.attrs["reason"])
		}
	}
	return out
}

// 손대지 않은 동결 문서는 Head 의 ETag 가 장부의 ETag 와 같아 본문을 읽지 않고 통과한다(1차).
func TestAuditFrozenPassesUntouchedDocument(t *testing.T) {
	l, store, logs := auditSetup(t)
	gets := len(store.getKeys())

	out := l.pub.AuditFrozen(t.Context())

	if out.Checked != 1 || out.Failed != 0 || out.Err != nil {
		t.Errorf("감사 = %+v, want 1건 확인 · 실패 0", out)
	}
	if got := auditFailures(logs); len(got) != 0 || len(store.getKeys()) != gets {
		t.Errorf("감사 ERROR %v · Get %d번, want 없음 · 0", got, len(store.getKeys())-gets)
	}
}

// ETag 가 달라도 본문의 sha256 이 장부의 값과 같으면 통과다(2차 — ETag 는 1차 거름일 뿐이다).
func TestAuditFrozenFallsBackToBodyHash(t *testing.T) {
	l, store, logs := auditSetup(t)
	exec(t, l.pub.pool, `UPDATE stream_sessions SET dvr_etag = '"다른-etag"' WHERE session_id = 'S'`)

	out := l.pub.AuditFrozen(t.Context())

	if out.Failed != 0 || len(store.getKeys()) == 0 || len(auditFailures(logs)) != 0 {
		t.Errorf("감사 = %+v · Get %d번 · ERROR %v, want 본문 대조로 통과", out, len(store.getKeys()), auditFailures(logs))
	}
}

// 동결 문서가 바뀌었거나 없으면 ERROR 다(사람 몫 — 자동으로 다시 쓰지 않는다).
func TestAuditFrozenReportsTamperedOrMissingDocument(t *testing.T) {
	t.Run("바뀜", func(t *testing.T) {
		l, store, logs := auditSetup(t)
		plant(store, putCall{key: soloKey, body: []byte("#EXTM3U\n#바뀐 본문\n")})
		puts := len(store.putCalls())

		if out := l.pub.AuditFrozen(t.Context()); out.Failed != 1 {
			t.Errorf("감사 = %+v, want 실패 1", out)
		}
		if got := auditFailures(logs); len(got) != 1 || got[0] != "mismatch" || len(store.putCalls()) != puts {
			t.Errorf("감사 ERROR %v · PUT %d번, want [mismatch] · 0", got, len(store.putCalls())-puts)
		}
	})
	t.Run("없음", func(t *testing.T) {
		l, store, logs := auditSetup(t)
		remove(store, soloKey)

		l.pub.AuditFrozen(t.Context())

		if got := auditFailures(logs); len(got) != 1 || got[0] != "missing" {
			t.Errorf("감사 ERROR %v, want [missing]", got)
		}
	})
}

// 감사 창(30분 — FROZEN_AUDIT_WINDOW) 밖의 동결은 보지 않는다. 장부의 시각은 ended_at, 없으면 dvr_frozen_at 이다.
func TestAuditFrozenSkipsOldFreezes(t *testing.T) {
	l, store, _ := auditSetup(t)
	exec(t, l.pub.pool, `UPDATE stream_sessions SET dvr_frozen_at = now() - interval '31 minutes' WHERE session_id = 'S'`)
	heads := len(store.headKeys())

	if out := l.pub.AuditFrozen(t.Context()); out.Checked != 0 || len(store.headKeys()) != heads {
		t.Errorf("감사 = %+v · Head %d번, want 0건", out, len(store.headKeys())-heads)
	}
}

// VOD 문서도 감사한다 — 사슬 세션마다 같은 키라 문서 하나로 센다.
func TestAuditFrozenChecksVODOncePerDocument(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultFrozen, false)
	exec(t, p.pool, `UPDATE stream_sessions SET dvr_frozen_at = now() WHERE session_id IN ('P', 'S')`)
	plant(store, putCall{key: vodChainKey, body: []byte("#EXTM3U\n#바뀐 본문\n")})

	out := p.AuditFrozen(t.Context())

	if out.Checked != 1 || out.Failed != 1 {
		t.Errorf("감사 = %+v, want VOD 문서 1건 · 실패 1", out)
	}
}

// g8e_stale_seal_cannot_override_successor(G8-E · 설계 6절 G8-F · G8-E 행) — 봉인 경로의 두 writer.
//
//	좀비 봉인이 후임 동결 뒤에 닿음  If-Match 가 412 로 막는다 — 후임의 닫힌 판 · 장부가 남는다
//	좀비 봉인이 후임 동결 앞에 닿음  후임 준비의 R3 가 그 닫힌 판으로 동결을 확정한다 — 다시 쓰지 않는다
func TestStaleSealCannotOverrideSuccessor(t *testing.T) {
	setup := func(t *testing.T) (w, b *loop, store *fakeStore, held *putCall) {
		w, store, _ = newFreezeLoop(t, 9, 5)
		held = new(putCall)
		store.setOnPut(holdPut(store, held))
		if out := w.pub.Endlist(t.Context(), fxStream, "S"); !out.Retry {
			t.Fatalf("붙잡힌 봉인 = %+v, want 재시도", out)
		}
		expireLease(t, w.pub.pool, "S")
		return w, w.peer("w-successor", &logRecorder{}), store, held
	}
	t.Run("좀비_나중", func(t *testing.T) {
		w, b, store, held := setup(t)
		wantOutcome(t, b.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)
		closed, closedETag := stored(store, soloKey)

		if _, err := store.applyPut(*held); !errors.Is(err, ErrPreconditionFailed) {
			t.Errorf("늦게 닿은 좀비 봉인 = %v, want ErrPreconditionFailed", err)
		}
		if body, etag := stored(store, soloKey); etag != closedETag || !bytes.Equal(body, closed) {
			t.Error("후임의 닫힌 판이 바뀌었다")
		}
		wantFrozen(t, w, store, "S", soloKey)
	})
	t.Run("좀비_먼저", func(t *testing.T) {
		w, b, store, held := setup(t)
		if _, err := store.applyPut(*held); err != nil {
			t.Fatalf("좀비 봉인 적용 실패: %v", err)
		}
		puts := len(store.putCalls())

		wantOutcome(t, b.pub.FreezeDVR(t.Context(), fxStream, "S"), AxisDVR, ResultFrozen, false)

		if got := len(store.putCalls()); got != puts {
			t.Errorf("좀비가 닫은 판 위 PUT %d번, want 0", got-puts)
		}
		wantFrozen(t, w, store, "S", soloKey)
	})
}
