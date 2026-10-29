package publish

// 동결 감사(FrozenAuditor · D17 4.1(b))와 G8-E(봉인 경로의 두 writer)의 PG 통합 시험이다(POK-196 M6-2 커밋 ③).

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"testing"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
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

	out := NewFrozenAuditor(l.pub).Audit(t.Context())

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

	out := NewFrozenAuditor(l.pub).Audit(t.Context())

	if out.Failed != 0 || len(store.getKeys()) == 0 || len(auditFailures(logs)) != 0 {
		t.Errorf("감사 = %+v · Get %d번 · ERROR %v, want 본문 대조로 통과", out, len(store.getKeys()), auditFailures(logs))
	}
}

// 동결 문서가 바뀌었거나 없으면 ERROR 다(앞부분 갈래가 아니면 사람 몫 — 자동으로 다시 쓰지 않는다).
func TestAuditFrozenReportsTamperedOrMissingDocument(t *testing.T) {
	t.Run("바뀜", func(t *testing.T) {
		l, store, logs := auditSetup(t)
		plant(store, putCall{key: soloKey, body: []byte("#EXTM3U\n#바뀐 본문\n")})
		puts := len(store.putCalls())

		if out := NewFrozenAuditor(l.pub).Audit(t.Context()); out.Failed != 1 {
			t.Errorf("감사 = %+v, want 실패 1", out)
		}
		if got := auditFailures(logs); len(got) != 1 || got[0] != "mismatch" || len(store.putCalls()) != puts {
			t.Errorf("감사 ERROR %v · PUT %d번, want [mismatch] · 0", got, len(store.putCalls())-puts)
		}
	})
	t.Run("없음", func(t *testing.T) {
		l, store, logs := auditSetup(t)
		remove(store, soloKey)

		NewFrozenAuditor(l.pub).Audit(t.Context())

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

	if out := NewFrozenAuditor(l.pub).Audit(t.Context()); out.Checked != 0 || len(store.headKeys()) != heads {
		t.Errorf("감사 = %+v · Head %d번, want 0건", out, len(store.headKeys())-heads)
	}
}

// VOD 문서도 감사한다 — 사슬 세션마다 같은 키라 문서 하나로 센다.
func TestAuditFrozenChecksVODOncePerDocument(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultFrozen, false)
	exec(t, p.pool, `UPDATE stream_sessions SET dvr_frozen_at = now() WHERE session_id IN ('P', 'S')`)
	plant(store, putCall{key: vodChainKey, body: []byte("#EXTM3U\n#바뀐 본문\n")})

	out := NewFrozenAuditor(p).Audit(t.Context())

	if out.Checked != 1 || out.Failed != 1 {
		t.Errorf("감사 = %+v, want VOD 문서 1건 · 실패 1", out)
	}
}

// VOD 문서의 감사 창 기준 시각은 phase 4 의 ended_at 이다 — ended_at 은 사슬 전원의 VOD 가 종국일 때만 쓰이므로
// (index.MarkChainEnded) 창이 VOD 확정 뒤에 열린다. 사슬의 DVR 이 창보다 오래전에 닫혔어도 phase 4 가 오면 VOD 를
// 감사한다 — 동결이 늦어 영영 감사하지 않는 일이 없다(PR ① 이연 · 보안 m62 미확인 재검).
func TestAuditFrozenWindowsVODFromChainEnd(t *testing.T) {
	p, _, chain := newVODChain(t, chainRows())
	ctx := t.Context()
	wantOutcome(t, p.FreezeVOD(ctx, chain, false), AxisVOD, ResultFrozen, false)
	exec(t, p.pool, `UPDATE stream_sessions SET dvr_frozen_at = now() - interval '2 hours' WHERE session_id IN ('P', 'S')`)
	a := NewFrozenAuditor(p)

	before := a.Audit(ctx)
	ended, err := index.MarkChainEnded(ctx, p.pool, chain, index.CloseOptions{Log: slog.New(&logRecorder{})},
		func(context.Context, pgx.Tx, string) error { return nil })
	if err != nil || ended != 2 {
		t.Fatalf("phase 4 = %d행 · %v, want 사슬 2행", ended, err)
	}
	after := a.Audit(ctx)

	if before.Checked != 0 {
		t.Errorf("phase 4 전 감사 = %+v, want 0건(기준 시각이 2시간 전 DVR 동결)", before)
	}
	if after.Checked != 1 || after.Failed != 0 || after.Err != nil {
		t.Errorf("phase 4 뒤 감사 = %+v, want VOD 문서 1건 통과", after)
	}
}

// 축의 본문 상한을 넘는 동결 문서는 우리가 쓴 판일 수 없다 — 읽기 실패(WARN)가 아니라 ERROR mismatch 다(보안 M-1).
// DVR 은 앞부분 갈래를 따지지 않고 PUT 하지 않는다.
func TestAuditFrozenReportsOversizedDocumentAsMismatch(t *testing.T) {
	t.Run("VOD", func(t *testing.T) {
		p, store, chain := newVODChain(t, chainRows())
		wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultFrozen, false)
		exec(t, p.pool, `UPDATE stream_sessions SET dvr_frozen_at = now() WHERE session_id IN ('P', 'S')`)
		plant(store, putCall{key: vodChainKey, body: bytes.Repeat([]byte{'x'}, rewind.MaxVODBodyBytes+1)})
		logs := &logRecorder{}
		p.log = slog.New(logs)

		out := NewFrozenAuditor(p).Audit(t.Context())

		if out.Failed != 1 {
			t.Errorf("감사 = %+v, want 실패 1", out)
		}
		if got := auditFailures(logs); len(got) != 1 || got[0] != auditMismatch {
			t.Errorf("감사 ERROR %v, want [mismatch]", got)
		}
	})
	t.Run("DVR", func(t *testing.T) {
		l, store, logs := auditSetup(t)
		plant(store, putCall{key: soloKey, body: bytes.Repeat([]byte{'x'}, rewind.MaxBodyBytes+1)})
		puts := len(store.putCalls())

		out := NewFrozenAuditor(l.pub).Audit(t.Context())

		if out.Failed != 1 || len(store.putCalls()) != puts {
			t.Errorf("감사 = %+v · PUT %d번, want 실패 1 · PUT 0", out, len(store.putCalls())-puts)
		}
		if got := auditFailures(logs); len(got) != 1 || got[0] != auditMismatch {
			t.Errorf("감사 ERROR %v, want [mismatch]", got)
		}
	})
}

// plantFrozenDVRs 는 손대지 않은 DVR 동결 문서 n 개(키 사전순 = 만든 순)를 장부와 저장소에 둔다. 키 목록을 돌려준다.
func plantFrozenDVRs(t *testing.T, p *Publisher, store *fakeStore, n int) []string {
	t.Helper()
	keys := make([]string, n)
	for i := range keys {
		id := fmt.Sprintf("F%03d", i)
		keys[i] = "dvr/" + fxStream + "/" + id + "/index.m3u8"
		body := []byte("#EXTM3U\n#" + id + "\n")
		plant(store, putCall{key: keys[i], body: body})
		sum := sha256Of(body)
		exec(t, p.pool, `
			INSERT INTO stream_sessions (session_id, stream_id, started_at, ended_at, state, dvr_state, dvr_s3_key, dvr_etag, dvr_sha256)
			VALUES ($1, $2, now(), now(), 'ended', 'frozen', $3, $4, $5)`, id, fxStream, keys[i], bodyETag(body), sum[:])
	}
	return keys
}

// firstKey 는 keys 의 첫 키다. 비었으면 "" 다.
func firstKey(keys []string) string {
	if len(keys) == 0 {
		return ""
	}
	return keys[0]
}

// 한 번에 보는 문서 수 상한(100)을 넘으면 다음 감사가 마지막으로 본 키 다음부터 본다 — 사전순 앞 100개만 되풀이해
// 보고 나머지가 창 밖으로 빠지지 않게(cx r1 설계 M · 보안 L-3). 끝까지 보면 처음으로 돌아간다. 상한에 닿은 감사는
// WARN limit_reached 를 한 줄 남긴다.
func TestAuditFrozenAdvancesPastLimit(t *testing.T) {
	pool, store, logs := newPool(t), newFakeStore(t), &logRecorder{}
	p := newPublisher(t, pool, store, "w-me", logs)
	keys := plantFrozenDVRs(t, p, store, frozenAuditLimit+5)
	a := NewFrozenAuditor(p)
	audit := func() ([]string, AuditOutcome) {
		heads := len(store.headKeys())
		out := a.Audit(t.Context())
		return store.headKeys()[heads:], out
	}

	first, out1 := audit()
	second, out2 := audit()
	third, _ := audit()

	if out1.Checked != frozenAuditLimit || !slices.Equal(first, keys[:frozenAuditLimit]) {
		t.Errorf("첫 감사 %d건 · 처음 %q, want 앞 %d건", out1.Checked, firstKey(first), frozenAuditLimit)
	}
	if out2.Checked != 5 || !slices.Equal(second, keys[frozenAuditLimit:]) {
		t.Errorf("둘째 감사 %d건 · Head %d개(처음 %q), want 상한 뒤 5건 %q", out2.Checked, len(second), firstKey(second), keys[frozenAuditLimit:])
	}
	if !slices.Equal(third, keys[:frozenAuditLimit]) {
		t.Errorf("셋째 감사 %d건, want 처음부터 앞 %d건", len(third), frozenAuditLimit)
	}
	var warns []string
	for _, r := range logs.records(freezeAuditLog) {
		if r.level == slog.LevelWarn {
			warns = append(warns, r.attrs["reason"])
		}
	}
	if !slices.Equal(warns, []string{auditLimitReached, auditLimitReached}) {
		t.Errorf("감사 WARN %v, want 상한에 닿은 감사(첫 · 셋째)마다 %s", warns, auditLimitReached)
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
