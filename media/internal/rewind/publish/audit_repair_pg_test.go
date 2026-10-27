package publish

// 동결 감사의 DVR 조건부 자동 복구(kty 결정 ⑨ · D17 「재PUT」 조건부 개정 · POK-196 M6-2 커밋 ⑥)의 PG 통합 시험이다.
//
// 자동으로 다시 쓰는 갈래는 하나뿐이다 — S3 의 DVR 본문이 장부로 다시 렌더한 본문의 앞부분(꼬리 조각 · ENDLIST 만
// 빠짐)이고, 재렌더 sha 가 장부의 sha 와 같고, 장부 CAS 가 이 감사기를 고르고, 이 문서를 창 30분 안에 고친 적이
// 없을 때다. 조건은 Get 이 본문과 같은 응답에서 받은 ETag 의 If-Match 다. 그 밖은 모두 ERROR 만 남긴다.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// repairSetup 은 auditSetup 에 그 발행자의 감사기를 더한다. 닫힌 판의 본문(고친 뒤 와야 할 판)도 돌려준다.
func repairSetup(t *testing.T) (*loop, *fakeStore, *logRecorder, *FrozenAuditor, []byte) {
	t.Helper()
	l, store, logs := auditSetup(t)
	closed, _ := stored(store, soloKey)
	return l, store, logs, NewFrozenAuditor(l.pub), closed
}

// truncateDVR 는 닫힌 판 closed 의 꼬리 조각 drop 개와 ENDLIST 를 뗀 본문을 그 키에 둔다 — 동결 뒤 누가 덜 닫힌
// 판을 덮어쓴 국면이다. 메타는 닫힌 판의 것을 그대로 싣는다. 둔 판의 ETag 를 돌려준다.
func truncateDVR(t *testing.T, store *fakeStore, closed []byte, drop int) string {
	t.Helper()
	body := cutTail(t, closed, drop)
	store.mu.Lock()
	meta := store.objects[soloKey].meta
	store.mu.Unlock()
	plant(store, putCall{key: soloKey, body: body, meta: meta})
	_, etag := stored(store, soloKey)
	return etag
}

// cutTail 은 본문의 ENDLIST 줄과 끝의 조각 drop 개를 뗀다 — 남은 본문은 조각 URI 줄로 끝난다.
func cutTail(t *testing.T, body []byte, drop int) []byte {
	t.Helper()
	lines := bytes.SplitAfter(bytes.TrimSuffix(body, []byte("#EXT-X-ENDLIST\n")), []byte("\n"))
	end := len(lines)
	for uris := 0; end > 0; end-- {
		if l := lines[end-1]; len(l) > 0 && l[0] != '#' {
			if uris == drop {
				break
			}
			uris++
		}
	}
	if end == 0 {
		t.Fatalf("조각 %d개를 뗄 수 없다", drop)
	}
	return bytes.Join(lines[:end], nil)
}

// auditInfos 는 감사 INFO 줄의 reason 속성들이다.
func auditInfos(logs *logRecorder) []string {
	var out []string
	for _, r := range logs.records(freezeAuditLog) {
		if r.level == slog.LevelInfo {
			out = append(out, r.attrs["reason"])
		}
	}
	return out
}

// putsSince 는 n 번째 이후의 PUT 호출이다.
func putsSince(store *fakeStore, n int) []putCall {
	return store.putCalls()[n:]
}

// 앞부분 갈래는 다시 쓴다. 덜 닫힌 판(꼬리 조각 둘 · ENDLIST 빠짐, 또는 ENDLIST 만 빠짐) 위에 장부로 다시 렌더한
// 닫힌 판을 그 판 ETag 의 If-Match 로 한 번 올린다. 메타는 닫힘 · 장부의 새 세대이고, 장부 ETag 는 새 판이 된다.
// INFO dvr_audit_repaired 가 한 번 남고, 다음 감사는 다시 쓰지 않고 통과한다.
func TestAuditRepairsTruncatedDVR(t *testing.T) {
	for _, drop := range []int{0, 2} {
		t.Run(fmt.Sprintf("꼬리_조각_%d개", drop), func(t *testing.T) {
			l, store, logs, a, closed := repairSetup(t)
			// 장부 ETag 를 본문 MD5 가 아닌 값으로 둔다(SSE-KMS · 멀티파트 — ETag 는 불투명하다). 그래야 고친 판의 ETag 가
			// 장부에 새로 남는지 보인다(가짜 저장소의 ETag 는 본문 MD5 라 고친 판이 동결 때와 같은 ETag 다).
			exec(t, l.pub.pool, `UPDATE stream_sessions SET dvr_etag = '"불투명"', manifest_etag = '"불투명"' WHERE session_id = 'S'`)
			seen := truncateDVR(t, store, closed, drop)
			puts := len(store.putCalls())

			out := a.Audit(t.Context())

			if out.Checked != 1 || out.Repaired != 1 || out.Failed != 0 || out.Err != nil {
				t.Errorf("감사 = %+v, want 1건 확인 · 복구 1 · 실패 0", out)
			}
			got := putsSince(store, puts)
			if len(got) != 1 || got[0].cond != IfMatch(seen) {
				t.Fatalf("PUT %+v, want 본 ETag %s 의 If-Match 한 번", got, seen)
			}
			body, etag := stored(store, soloKey)
			if !bytes.Equal(body, closed) {
				t.Errorf("고친 본문이 닫힌 판과 다르다:\n%s", body)
			}
			desc := storedDesc(t, store)
			row, d := readSession(t, l.pub.pool, "S"), readDVR(t, l, "S")
			if !desc.Terminal || desc.Gen != row.gen || desc.BodySHA256 != sha256Of(closed) {
				t.Errorf("고친 판 메타 %+v, want 닫힘 · 장부 세대 %d · 닫힌 판 sha", desc.Published, row.gen)
			}
			if strOrNil(d.etag) != etag || strOrNil(row.etag) != etag || d.state != "frozen" {
				t.Errorf("장부 dvr_etag %s · manifest_etag %s · 상태 %s, want 새 판 %s · frozen", strOrNil(d.etag), strOrNil(row.etag), d.state, etag)
			}
			if infos, errs := auditInfos(logs), auditFailures(logs); len(infos) != 1 || infos[0] != auditRepaired || len(errs) != 0 {
				t.Errorf("감사 INFO %v · ERROR %v, want [%s] · 없음", infos, errs, auditRepaired)
			}

			puts = len(store.putCalls())
			if out := a.Audit(t.Context()); out.Failed != 0 || out.Repaired != 0 || len(store.putCalls()) != puts {
				t.Errorf("고친 뒤 감사 = %+v · PUT %d번, want 통과 · 0", out, len(store.putCalls())-puts)
			}
		})
	}
}

// 앞부분이 아니면 다시 쓰지 않는다 — 줄이 바뀐 본문(같은 길이의 다른 URI · 머리가 다른 본문)은 규격 허용 밖이라
// ERROR mismatch 만 남긴다.
func TestAuditDoesNotRepairChangedDVR(t *testing.T) {
	for name, edit := range map[string]func([]byte) []byte{
		"URI_바뀜": func(b []byte) []byte { return bytes.Replace(cutTail(t, b, 1), []byte(".m4s\n"), []byte(".mp4\n"), 1) },
		"머리_바뀜": func(b []byte) []byte {
			return bytes.Replace(cutTail(t, b, 1), []byte("VERSION:6"), []byte("VERSION:7"), 1)
		},
		"줄_중간": func(b []byte) []byte { return bytes.TrimSuffix(cutTail(t, b, 1), []byte("\n")) },
		"태그_끝": func(b []byte) []byte { return cutAfterTag(t, b) },
	} {
		t.Run(name, func(t *testing.T) {
			_, store, logs, a, closed := repairSetup(t)
			plant(store, putCall{key: soloKey, body: edit(closed)})
			puts := len(store.putCalls())

			out := a.Audit(t.Context())

			if out.Failed != 1 || out.Repaired != 0 || len(store.putCalls()) != puts {
				t.Errorf("감사 = %+v · PUT %d번, want 실패 1 · 복구 0 · PUT 0", out, len(store.putCalls())-puts)
			}
			if got := auditFailures(logs); len(got) != 1 || got[0] != auditMismatch {
				t.Errorf("감사 ERROR %v, want [mismatch]", got)
			}
		})
	}
}

// cutAfterTag 는 닫힌 판에서 마지막 조각의 PDT 태그 줄까지만 남긴다 — 줄 경계의 앞부분이지만 조각 URI 줄로 끝나지
// 않는다(꼬리 조각이 통째로 빠진 것이 아니다).
func cutAfterTag(t *testing.T, closed []byte) []byte {
	t.Helper()
	body := cutTail(t, closed, 0)
	i := bytes.LastIndex(body, []byte("#EXT-X-PROGRAM-DATE-TIME:"))
	j := bytes.IndexByte(body[i:], '\n')
	return body[:i+j+1]
}

// 재렌더 sha 가 장부의 sha 와 다르면(동결 뒤 장부 행이 바뀜 · 렌더러가 바뀜) 앞부분이어도 다시 쓰지 않는다 — 장부 ·
// 렌더 결함이 좋은 본문을 덮지 않게. 바꾸는 행은 빠진 꼬리 조각(seq 9)이라 본 본문은 새 렌더의 앞부분 그대로다.
func TestAuditDoesNotRepairWhenRenderDrifts(t *testing.T) {
	l, store, logs, a, closed := repairSetup(t)
	truncateDVR(t, store, closed, 1)
	exec(t, l.pub.pool, `UPDATE stream_segments SET duration_ms = duration_ms + 1 WHERE seq = 9`)
	puts := len(store.putCalls())

	out := a.Audit(t.Context())

	if out.Failed != 1 || out.Repaired != 0 || len(store.putCalls()) != puts {
		t.Errorf("감사 = %+v · PUT %d번, want 실패 1 · PUT 0", out, len(store.putCalls())-puts)
	}
	if got := auditFailures(logs); len(got) != 1 || got[0] != auditMismatch {
		t.Errorf("감사 ERROR %v, want [mismatch]", got)
	}
}

// 객체가 없으면 다시 만들지 않는다 — Head 404(missing)도, Get 뒤 If-Match PUT 의 404 도 IfAbsent 로 넘어가지 않는다
// (삭제가 운영 의도일 수 있다 — 설계 10절 삭제 런북).
func TestAuditNeverRecreatesMissingDVR(t *testing.T) {
	t.Run("Head_404", func(t *testing.T) {
		_, store, logs, a, _ := repairSetup(t)
		remove(store, soloKey)
		puts := len(store.putCalls())

		a.Audit(t.Context())

		if got := auditFailures(logs); len(got) != 1 || got[0] != auditMissing || len(store.putCalls()) != puts {
			t.Errorf("감사 ERROR %v · PUT %d번, want [missing] · 0", got, len(store.putCalls())-puts)
		}
	})
	t.Run("PUT_404", func(t *testing.T) {
		_, store, logs, a, closed := repairSetup(t)
		truncateDVR(t, store, closed, 1)
		store.setOnPut(onceBefore(func() { remove(store, soloKey) }))
		puts := len(store.putCalls())

		out := a.Audit(t.Context())

		got := putsSince(store, puts)
		if len(got) != 1 || !got[0].cond.ifMatch {
			t.Errorf("PUT %+v, want If-Match 한 번(IfAbsent 재생성 없음)", got)
		}
		if body, _ := stored(store, soloKey); body != nil || out.Failed != 1 {
			t.Errorf("객체 %q · 감사 %+v, want 없음 · 실패 1", body, out)
		}
		if errs := auditFailures(logs); len(errs) != 1 || errs[0] != auditMissing {
			t.Errorf("감사 ERROR %v, want [missing]", errs)
		}
	})
}

// 감사가 본 판과 PUT 사이에 누가 또 쓰면 412 로 막히고 멈춘다 — ERROR dvr_audit_repair_rejected · 그 쓰기가 남는다
// · 다시 시도하지 않는다(다음 감사도 그 문서는 다시 쓰지 않는다).
func TestAuditRepairStopsOnPreconditionFailure(t *testing.T) {
	_, store, logs, a, closed := repairSetup(t)
	truncateDVR(t, store, closed, 1)
	other := cutTail(t, closed, 2)
	store.setOnPut(onceBefore(func() { plant(store, putCall{key: soloKey, body: other}) }))
	puts := len(store.putCalls())

	out := a.Audit(t.Context())

	if body, _ := stored(store, soloKey); !bytes.Equal(body, other) || out.Failed != 1 || out.Repaired != 0 {
		t.Errorf("감사 = %+v · 본문이 끼어든 판인가 %t, want 실패 1 · 참", out, bytes.Equal(body, other))
	}
	if errs := auditFailures(logs); len(errs) != 1 || errs[0] != auditRepairRejected {
		t.Errorf("감사 ERROR %v, want [%s]", errs, auditRepairRejected)
	}
	a.Audit(t.Context())
	if got := putsSince(store, puts); len(got) != 1 {
		t.Errorf("PUT %d번, want 1(412 뒤 재시도 없음)", len(got))
	}
}

// 409 면 Head 한 번으로 그 순간의 판을 보고 멈춘다 — 다시 PUT 하지 않는다.
func TestAuditRepairStopsOnConflict(t *testing.T) {
	_, store, logs, a, closed := repairSetup(t)
	truncateDVR(t, store, closed, 1)
	store.setOnPut(func(putCall) error { return fmt.Errorf("%w: 주입", ErrConflict) })
	puts, heads := len(store.putCalls()), len(store.headKeys())

	out := a.Audit(t.Context())

	if got := putsSince(store, puts); len(got) != 1 || out.Failed != 1 {
		t.Errorf("감사 = %+v · PUT %d번, want 실패 1 · PUT 1", out, len(got))
	}
	if got := len(store.headKeys()) - heads; got != 2 {
		t.Errorf("Head %d번, want 2(감사 1 · 409 뒤 1)", got)
	}
	if errs := auditFailures(logs); len(errs) != 1 || errs[0] != auditRepairConflict {
		t.Errorf("감사 ERROR %v, want [%s]", errs, auditRepairConflict)
	}
}

// PUT 결과를 모르면(네트워크 · 5xx) ERROR 다. 적용됐으면 다음 감사가 본문 sha 로 통과시키고, 적용 안 됐으면 다시
// 쓰지 않는다(문서당 1회).
func TestAuditRepairUnknownResultIsNotRetried(t *testing.T) {
	_, store, logs, a, closed := repairSetup(t)
	truncateDVR(t, store, closed, 1)
	store.setOnPut(func(putCall) error { return errors.New("주입: 연결 끊김") })
	puts := len(store.putCalls())

	a.Audit(t.Context())
	a.Audit(t.Context())

	if got := putsSince(store, puts); len(got) != 1 {
		t.Errorf("PUT %d번, want 1", len(got))
	}
	if errs := auditFailures(logs); len(errs) != 2 || errs[0] != auditRepairFailed || errs[1] != auditRepairExhausted {
		t.Errorf("감사 ERROR %v, want [%s %s]", errs, auditRepairFailed, auditRepairExhausted)
	}
}

// 고친 문서가 창(30분) 안에 다시 어긋나면 멈춘다 — 누군가와 서로 되돌리는 다툼이다. ERROR dvr_audit_repair_exhausted
// · PUT 0. 창이 지나면 다시 한 번 고칠 수 있다.
func TestAuditRepairsOncePerWindow(t *testing.T) {
	l, store, logs, a, closed := repairSetup(t)
	now := time.Now()
	l.pub.now = func() time.Time { return now }
	truncateDVR(t, store, closed, 1)
	if out := a.Audit(t.Context()); out.Repaired != 1 {
		t.Fatalf("첫 감사 = %+v, want 복구 1", out)
	}
	truncateDVR(t, store, closed, 1)
	puts := len(store.putCalls())

	out := a.Audit(t.Context())

	if out.Failed != 1 || out.Repaired != 0 || len(store.putCalls()) != puts {
		t.Errorf("두 번째 어긋남 감사 = %+v · PUT %d번, want 실패 1 · PUT 0", out, len(store.putCalls())-puts)
	}
	if errs := auditFailures(logs); len(errs) != 1 || errs[0] != auditRepairExhausted {
		t.Errorf("감사 ERROR %v, want [%s]", errs, auditRepairExhausted)
	}

	now = now.Add(frozenAuditWindow + time.Second)
	if out := a.Audit(t.Context()); out.Repaired != 1 {
		t.Errorf("창 뒤 감사 = %+v, want 복구 1", out)
	}
}

// 장부 CAS 가 한 감사기만 고른다 — 다른 writer 가 lease 안에서 fence 를 쥐고 있으면 그 감사기는 PUT 없이 물러나고
// (ERROR dvr_audit_repair_fenced) 문서당 1회를 쓰지 않는다. lease 가 끝나면 다음 감사가 고친다.
func TestAuditRepairClaimsLedgerFirst(t *testing.T) {
	l, store, _, _, closed := repairSetup(t)
	other := &logRecorder{}
	b := NewFrozenAuditor(l.peer("w-auditor", other).pub)
	truncateDVR(t, store, closed, 1)
	puts := len(store.putCalls())

	out := b.Audit(t.Context())

	if out.Failed != 1 || len(store.putCalls()) != puts {
		t.Errorf("fence 를 못 쥔 감사 = %+v · PUT %d번, want 실패 1 · PUT 0", out, len(store.putCalls())-puts)
	}
	if errs := auditFailures(other); len(errs) != 1 || errs[0] != auditRepairFenced {
		t.Errorf("감사 ERROR %v, want [%s]", errs, auditRepairFenced)
	}

	expireLease(t, l.pub.pool, "S")
	if out := b.Audit(t.Context()); out.Repaired != 1 {
		t.Errorf("lease 끝난 뒤 감사 = %+v, want 복구 1", out)
	}
}

// VOD 는 자동 복구 대상이 아니다 — 앞부분이어도 ERROR mismatch 만 남기고 PUT 하지 않는다(설계 10절 — VOD 수동). 복구를
// 따지지도 않으므로(재렌더 없음) 그 줄에 고치지 않은 까닭(err)이 없다.
func TestAuditDoesNotRepairVOD(t *testing.T) {
	p, store, chain := newVODChain(t, chainRows())
	wantOutcome(t, p.FreezeVOD(t.Context(), chain, false), AxisVOD, ResultFrozen, false)
	exec(t, p.pool, `UPDATE stream_sessions SET dvr_frozen_at = now() WHERE session_id IN ('P', 'S')`)
	closed, _ := stored(store, vodChainKey)
	plant(store, putCall{key: vodChainKey, body: cutTail(t, closed, 1)})
	puts := len(store.putCalls())

	logs := &logRecorder{}
	p.log = slog.New(logs)

	out := NewFrozenAuditor(p).Audit(t.Context())

	if out.Failed != 1 || out.Repaired != 0 || len(store.putCalls()) != puts {
		t.Errorf("VOD 감사 = %+v · PUT %d번, want 실패 1 · PUT 0", out, len(store.putCalls())-puts)
	}
	recs := logs.records(freezeAuditLog)
	if len(recs) != 1 || recs[0].attrs["reason"] != auditMismatch || recs[0].attrs["err"] != "" {
		t.Errorf("VOD 감사 줄 %+v, want mismatch 하나 · err 없음", recs)
	}
}

// 창이 지난 복구 기록은 다음 감사가 지운다 — 표가 감사 창 안의 문서 수를 넘어 자라지 않는다.
func TestAuditForgetsRepairsAfterWindow(t *testing.T) {
	l, store, _, a, closed := repairSetup(t)
	now := time.Now()
	l.pub.now = func() time.Time { return now }
	truncateDVR(t, store, closed, 1)
	if out := a.Audit(t.Context()); out.Repaired != 1 || len(a.tried) != 1 {
		t.Fatalf("첫 감사 = %+v · 기록 %d개, want 복구 1 · 1개", out, len(a.tried))
	}

	now = now.Add(frozenAuditWindow)
	a.Audit(t.Context())

	if len(a.tried) != 0 {
		t.Errorf("창 뒤 기록 %d개, want 0", len(a.tried))
	}
}

// 본 본문과 조건이 같은 판이어야 한다 — Head 가 앞부분이 아닌 판 A 를 보고, Get 이 앞부분 판 B 를 읽고, PUT 직전에
// A 로 되돌아가도(A → B → A) A 를 덮지 않는다. 조건은 Get 이 본문과 같은 응답에서 받은 B 의 ETag 라 412 로 막히고
// 멈춘다(ERROR dvr_audit_repair_rejected). Head 의 ETag 로 걸면 If-Match(A) 가 통과해 A 를 덮는다(c6 r1 cx H1).
func TestAuditRepairBindsConditionToReadBody(t *testing.T) {
	_, store, logs, a, closed := repairSetup(t)
	prefix := cutTail(t, closed, 1)
	changed := bytes.Replace(prefix, []byte(".m4s\n"), []byte(".mp4\n"), 1)
	plant(store, putCall{key: soloKey, body: changed})
	store.setOnGet(func() { plant(store, putCall{key: soloKey, body: prefix}) })
	store.setOnPut(onceBefore(func() { plant(store, putCall{key: soloKey, body: changed}) }))
	puts := len(store.putCalls())

	out := a.Audit(t.Context())

	if body, _ := stored(store, soloKey); !bytes.Equal(body, changed) || out.Repaired != 0 || out.Failed != 1 {
		t.Errorf("감사 = %+v · 앞부분이 아닌 판이 남았나 %t, want 복구 0 · 실패 1 · 참", out, bytes.Equal(body, changed))
	}
	if got := putsSince(store, puts); len(got) != 1 || got[0].cond != IfMatch(bodyETag(prefix)) {
		conds := make([]Precondition, len(got))
		for i, c := range got {
			conds[i] = c.cond
		}
		t.Errorf("PUT 조건 %+v, want 읽은 판 ETag %s 의 If-Match 한 번", conds, bodyETag(prefix))
	}
	if errs := auditFailures(logs); len(errs) != 1 || errs[0] != auditRepairRejected {
		t.Errorf("감사 ERROR %v, want [%s]", errs, auditRepairRejected)
	}
}

// 받을 수 없는 ETag(빈 값 · 1024바이트 초과 · 인쇄 불가 문자)로는 다시 쓰지 않는다 — If-Match 에 싣기 전에 checkETag 로
// 거르고 읽기 실패(WARN read_failed)로 둔다. 동결 경로(reconcile)와 같은 검사다(c6 보안 L1).
func TestAuditRepairRejectsMalformedETag(t *testing.T) {
	_, store, logs, a, closed := repairSetup(t)
	store.mu.Lock()
	store.objects[soloKey] = fakeObject{body: cutTail(t, closed, 1), etag: "\"e\nf\"", meta: store.objects[soloKey].meta}
	store.mu.Unlock()
	puts := len(store.putCalls())

	out := a.Audit(t.Context())

	if out.Repaired != 0 || len(store.putCalls()) != puts {
		t.Errorf("감사 = %+v · PUT %d번, want 복구 0 · PUT 0", out, len(store.putCalls())-puts)
	}
	recs := logs.records(freezeAuditLog)
	if len(recs) != 1 || recs[0].level != slog.LevelWarn || recs[0].attrs["reason"] != auditReadFailed {
		t.Errorf("감사 줄 %+v, want WARN read_failed 하나", recs)
	}
}

// 고친 판의 장부 ETag 확정이 0행이면(세대 · fence 가 움직였다) 문서는 고쳐졌지만 장부가 옛 ETag 다 — INFO 가 아니라
// WARN dvr_audit_repaired(err 포함)다(c6 cc M — 확정 실패가 INFO 에 묻혀 알람이 안 걸린다).
func TestAuditRepairWarnsWhenConfirmMisses(t *testing.T) {
	l, store, logs, a, closed := repairSetup(t)
	truncateDVR(t, store, closed, 1)
	store.setOnPut(func(putCall) error {
		exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_gen = manifest_gen + 1 WHERE session_id = 'S'`)
		return nil
	})

	out := a.Audit(t.Context())

	if body, _ := stored(store, soloKey); out.Repaired != 1 || !bytes.Equal(body, closed) {
		t.Errorf("감사 = %+v · 닫힌 판인가 %t, want 복구 1 · 참", out, bytes.Equal(body, closed))
	}
	recs := logs.records(freezeAuditLog)
	if len(recs) != 1 || recs[0].level != slog.LevelWarn || recs[0].attrs["reason"] != auditRepaired || recs[0].attrs["err"] == "" {
		t.Errorf("감사 줄 %+v, want WARN %s 하나(err 포함)", recs, auditRepaired)
	}
}

// countingAuditor 는 l 의 발행자와 같은 writer · 저장소로 도는 감사기다 — 그 풀이 보낸 문장 수를 센다.
func countingAuditor(t *testing.T, l *loop, store *fakeStore) (*FrozenAuditor, *atomic.Int64) {
	t.Helper()
	var n atomic.Int64
	pool := hookedPool(t, l.pub.pool, traceSQL(func(ctx context.Context, _ *pgx.Conn, _ string) context.Context {
		n.Add(1)
		return ctx
	}))
	p := newPublisher(t, pool, store, "w-me", &logRecorder{})
	p.now = l.pub.now
	return NewFrozenAuditor(p), &n
}

// 창 안에 이미 고친 문서가 다시 어긋나면 장부를 다시 읽지 않고 멈춘다 — 소진 여부를 재렌더보다 먼저 본다(c6 보안 M2).
// 감사는 대상 조회 한 문장만 보낸다.
func TestAuditRepairChecksBudgetBeforeRender(t *testing.T) {
	l, store, _, _, closed := repairSetup(t)
	a, sent := countingAuditor(t, l, store)
	truncateDVR(t, store, closed, 1)
	if out := a.Audit(t.Context()); out.Repaired != 1 {
		t.Fatalf("첫 감사 = %+v, want 복구 1", out)
	}
	truncateDVR(t, store, closed, 1)
	sent.Store(0)

	out := a.Audit(t.Context())

	if out.Failed != 1 || sent.Load() != 1 {
		t.Errorf("소진 문서 감사 = %+v · 문장 %d개, want 실패 1 · 1개(대상 조회만 — 재렌더 없음)", out, sent.Load())
	}
}

// 복구 불가로 판정한 판(앞부분이 아님)은 같은 ETag 인 동안 다시 렌더하지 않는다 — 고칠 수 없는 문서가 감시 주기마다
// 장부를 다시 읽지 않게(c6 보안 M2). 판이 바뀌면(ETag 가 다르면) 다시 따진다.
func TestAuditRemembersUnrepairableVersion(t *testing.T) {
	l, store, _, _, closed := repairSetup(t)
	a, sent := countingAuditor(t, l, store)
	changed := bytes.Replace(cutTail(t, closed, 1), []byte(".m4s\n"), []byte(".mp4\n"), 1)
	plant(store, putCall{key: soloKey, body: changed})
	if out := a.Audit(t.Context()); out.Failed != 1 {
		t.Fatalf("첫 감사 = %+v, want 실패 1", out)
	}

	sent.Store(0)
	same := a.Audit(t.Context())
	sameSent := sent.Load()
	plant(store, putCall{key: soloKey, body: cutAfterTag(t, closed)})
	sent.Store(0)
	other := a.Audit(t.Context())

	if same.Failed != 1 || sameSent != 1 {
		t.Errorf("같은 판 감사 = %+v · 문장 %d개, want 실패 1 · 1개(대상 조회만)", same, sameSent)
	}
	if other.Failed != 1 || sent.Load() <= 1 {
		t.Errorf("바뀐 판 감사 = %+v · 문장 %d개, want 실패 1 · 재렌더(2개 이상)", other, sent.Load())
	}
}

// PUT 키는 장부 열 그대로 믿지 않는다 — 스트림 · 세션에서 다시 만든 목록 키와 장부의 dvr_s3_key 가 다르면 앞부분
// 갈래여도 고치지 않고 ERROR mismatch(err 포함)다(c6 보안 L2 · 방어 심화).
func TestAuditRepairRequiresDerivedKey(t *testing.T) {
	l, store, logs, a, closed := repairSetup(t)
	const otherKey = "dvr/" + fxStream + "/OTHER/index.m3u8"
	plant(store, putCall{key: otherKey, body: cutTail(t, closed, 1)})
	exec(t, l.pub.pool, `UPDATE stream_sessions SET dvr_s3_key = $1 WHERE session_id = 'S'`, otherKey)
	puts := len(store.putCalls())

	out := a.Audit(t.Context())

	if out.Failed != 1 || out.Repaired != 0 || len(store.putCalls()) != puts {
		t.Errorf("감사 = %+v · PUT %d번, want 실패 1 · 복구 0 · PUT 0", out, len(store.putCalls())-puts)
	}
	recs := logs.records(freezeAuditLog)
	if len(recs) != 1 || recs[0].attrs["reason"] != auditMismatch || recs[0].attrs["err"] == "" {
		t.Errorf("감사 줄 %+v, want mismatch 하나(err 포함)", recs)
	}
}
