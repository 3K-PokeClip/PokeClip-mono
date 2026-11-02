package publish

// 중단 · 포기 로그(계획 4.5 A1 「중단 · 포기 로그」 · 체크리스트 A-5 · J14) — 사유마다(stage 가 있으면 사유 · stage
// 마다) 한 번. fake Store + PG 통합이다.

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// abortCase 는 중단 · 포기 갈래 하나다. setup 은 한 번 발행한 루프를 세우고, trigger 는 그 갈래를 밟는 틱을 한 번
// 낸다(같은 갈래를 되풀이할 수 있게 필요한 픽스처를 다시 세운다).
type abortCase struct {
	name   string
	reason string
	level  slog.Level
	setup  func(t *testing.T) (l *loop, store *fakeStore, logs *logRecorder, trigger func())
}

// publishedSolo 는 창 0..5 를 한 번 발행한 계승 없는 회차의 루프다.
func publishedSolo(t *testing.T) (*loop, *fakeStore, *logRecorder) {
	t.Helper()
	l, store, logs := newSoloLoop(t, 50)
	l.mustPublish(t.Context(), 0, 5)
	return l, store, logs
}

// abortCases 는 A1 로그 표의 행 전부(r45 여덟 · r50 일곱)와 결과 모름 둘(p0_unknown · p4_unknown — r4 처분 R4-3),
// 쓰는 쪽 범위 검사 하나(meta_out_of_range — r5 처분 R5-2), J14 의 재확인 갈래 넷이다.
func abortCases() []abortCase {
	warn, errLv := slog.LevelWarn, slog.LevelError
	return []abortCase{
		{"meta_invalid", reasonMetaInvalid, errLv, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			store.mu.Lock()
			delete(store.objects[soloKey].meta, metaGen)
			store.mu.Unlock()
			return l, store, logs, func() { l.st.ReconcileDue = true; l.tick(t.Context(), 0, 6) }
		}},
		{"meta_out_of_range", reasonMetaOutOfRange, errLv, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			store.mu.Lock()
			store.objects[soloKey].meta[metaGen] = "9007199254740991" // 끝값 — 화해가 싣고 나면 다음 P0 의 세대가 위끝을 넘는다
			store.mu.Unlock()
			l.st.ReconcileDue = true
			l.tick(t.Context(), 0, 5)
			return l, store, logs, func() { l.tick(t.Context(), 0, 6) }
		}},
		{"etag_source_mismatch", reasonETagSourceMismatch, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			return l, store, logs, func() {
				exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_etag = '"elsewhere"' WHERE session_id = 'S'`)
				l.tick(t.Context(), 0, 6)
			}
		}},
		{"publish_deadline", reasonPublishDeadline, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			return l, store, logs, func() { l.pub.now = newStepClock(3 * time.Second).Now; l.tick(t.Context(), 0, 6) }
		}},
		{"update_412", reasonUpdate412, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			next := int64(10)
			return l, store, logs, func() {
				other := otherVersion(t, l, 0, next, 90+next)
				next++
				store.setOnPut(onceBefore(func() { plant(store, other) }))
				l.tick(t.Context(), 0, next+10)
			}
		}},
		{"revoke_unknown", reasonRevokeUnknown, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := newChainLoop(t, 700)
			l.fx.setInitUploaded("P", false)
			exec(t, l.pub.pool, `UPDATE stream_sessions SET inherits_session = NULL, discontinuity_base = 0 WHERE session_id = 'S'`)
			return l, store, logs, func() { l.tick(t.Context(), 100, 610) }
		}},
		{"s4_after_publish", reasonS4AfterPublish, errLv, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := newChainLoop(t, 700)
			l.mustPublish(t.Context(), 100, 610)
			l.fx.setInitUploaded("P", false)
			return l, store, logs, func() { l.tick(t.Context(), 100, 611) }
		}},
		{"validate", reasonValidate, errLv, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			return l, store, logs, func() { l.tick(t.Context(), 0, 4) } // S1 머리 후퇴
		}},
		{"disc_seq_decrease", reasonDiscSeqDecrease, errLv, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			ahead := *l.st.Prev
			ahead.DiscontinuitySequence = 3
			l.st.Prev = &ahead
			return l, store, logs, func() {
				out := l.pub.Tick(t.Context(), l.st, TickInput{Playlist: l.fx.window(0, 6), DiscontinuitySequence: 0})
				l.st = out.State
			}
		}},
		{"p0_no_row", reasonP0NoRow, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			exec(t, l.pub.pool, `UPDATE stream_sessions SET state = 'ending', ending_at = now() WHERE session_id = 'S'`)
			return l, store, logs, func() { l.tick(t.Context(), 0, 6) }
		}},
		{"p0_unknown", reasonP0Unknown, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			fault := injectUpdateFault(t, l.pub.pool, "true")
			return l, store, logs, func() { fault.arm(); l.tick(t.Context(), 0, 6) } // 다음 UPDATE = P0
		}},
		{"create_412", reasonCreate412, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := newSoloLoop(t, 50)
			next := int64(4)
			return l, store, logs, func() {
				// 매번 첫 PUT 전 국면으로 되돌린다 — 객체 없음 · DB ETag NULL · P 없음.
				remove(store, soloKey)
				exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_etag = NULL WHERE session_id = 'S'`)
				l.st.Prev = nil
				other := otherVersion(t, l, 0, next, 80+next)
				next++
				store.setOnPut(onceBefore(func() { plant(store, other) }))
				l.tick(t.Context(), 0, 30)
			}
		}},
		{"update_404", reasonUpdate404, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			published := *l.st.Prev
			return l, store, logs, func() {
				// 매번 발행된 국면(P · DB ETag)에서 객체만 사라진 상태로 되돌린다.
				l.st.Prev = &published
				exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_etag = $1 WHERE session_id = 'S'`, published.ETag)
				remove(store, soloKey)
				l.tick(t.Context(), 0, 6)
			}
		}},
		{"put_conflict", reasonPutConflict, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := newSoloLoop(t, 50)
			return l, store, logs, func() {
				store.setOnPut(func(putCall) error { return ErrConflict })
				l.tick(t.Context(), 0, 5)
			}
		}},
		{"put_unknown", reasonPutUnknown, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			return l, store, logs, func() {
				store.setOnPut(func(putCall) error { return errors.New("connection reset") })
				l.tick(t.Context(), 0, 6)
			}
		}},
		{"p4_no_row", reasonP4NoRow, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			to := int64(5)
			return l, store, logs, func() {
				to++
				store.setOnPut(onceBefore(func() {
					exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_gen = manifest_gen + 5 WHERE session_id = 'S'`)
				}))
				l.tick(t.Context(), 0, to)
			}
		}},
		{"p4_unknown", reasonP4Unknown, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			fault := injectUpdateFault(t, l.pub.pool, "true")
			to := int64(5)
			return l, store, logs, func() {
				to++
				store.setOnPut(onceBefore(fault.arm)) // P0 뒤 · P4 전 — 다음 UPDATE = P4
				l.tick(t.Context(), 0, to)
			}
		}},
		{"head_failed", reasonHeadFailed, errLv, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			l.pub.store = &headFailStore{fakeStore: store, fail: errors.New("403 Forbidden"), armed: true}
			return l, store, logs, func() { l.st.ReconcileDue = true; l.tick(t.Context(), 0, 6) }
		}},
		{"J14_If-Match_409_ETag_다름", reasonUpdate412, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			next := int64(10)
			return l, store, logs, func() {
				other := otherVersion(t, l, 0, next, 90+next)
				next++
				store.setOnPut(func(putCall) error { plant(store, other); store.setOnPut(nil); return ErrConflict })
				l.tick(t.Context(), 0, next+10)
			}
		}},
		{"J14_If-Match_409_Head_없음", reasonUpdate404, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			published := *l.st.Prev
			return l, store, logs, func() {
				l.st.Prev = &published
				exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_etag = $1 WHERE session_id = 'S'`, published.ETag)
				store.setOnPut(func(putCall) error { remove(store, soloKey); store.setOnPut(nil); return ErrConflict })
				l.tick(t.Context(), 0, 6)
			}
		}},
		{"J14_If-Match_409_Head_실패", reasonHeadFailed, errLv, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			failing := &headFailStore{fakeStore: store, fail: errors.New("403 Forbidden")}
			l.pub.store = failing
			return l, store, logs, func() {
				failing.armed = false
				l.st.ReconcileDue = false
				store.setOnPut(func(putCall) error { failing.armed = true; store.setOnPut(nil); return ErrConflict })
				l.tick(t.Context(), 0, 6)
			}
		}},
		{"J14_If-Match_재시도_뒤_409", reasonPutConflict, warn, func(t *testing.T) (*loop, *fakeStore, *logRecorder, func()) {
			l, store, logs := publishedSolo(t)
			return l, store, logs, func() {
				store.setOnPut(func(putCall) error { return ErrConflict })
				l.tick(t.Context(), 0, 6)
			}
		}},
	}
}

// publish_abort_paths_log_once_per_reason(계획 4.5 A1 로그 표 · 체크리스트 A-5 · J14) — 로그 표의 경로마다 같은
// 갈래를 세 번 밟아도 rewind_publish_aborted 는 그 사유로 한 줄이고, 등급은 표 그대로다(틱만 포기하면 WARN · 발행이
// 서면 ERROR). 다른 사유가 섞이지 않는다. 오류는 문장이 아니라 속성으로만 실린다. 세 번인 까닭: 두 번만 밟으면 억제된
// 틱이 가드를 풀어 버리는 회귀(한 틱 걸러 한 줄 — 셋째 틱이 다시 남긴다)와 가를 수 없다.
func TestPublishAbortPathsLogOncePerReason(t *testing.T) {
	for _, tc := range abortCases() {
		t.Run(tc.name, func(t *testing.T) {
			_, _, logs, trigger := tc.setup(t)

			for range 3 {
				trigger()
			}

			recs := logs.abortRecs()
			if len(recs) != 1 || recs[0].attrs["reason"] != tc.reason || recs[0].level != tc.level {
				t.Fatalf("중단 · 포기 로그 %+v, want %s(%v) 한 줄", recs, tc.reason, tc.level)
			}
			if recs[0].attrs["stream"] != fxStream || recs[0].attrs["session"] != "S" {
				t.Errorf("로그 속성 %v, want stream=%s · session=S", recs[0].attrs, fxStream)
			}
		})
	}
}

// 1회 가드는 상태가 바뀌면 풀린다(C3 1회성 가드와 같은 형) — 사유를 남긴 뒤 끝까지 간 틱이 한 번 지나면 같은 사유를
// 다시 남긴다. 발행을 멈춘 상태가 되풀이됐는지, 한 번 나았다가 다시 났는지를 운영자가 가를 수 있다.
func TestAbortLogRearmsAfterCleanTick(t *testing.T) {
	l, _, logs := publishedSolo(t)
	setState := func(state string) {
		exec(t, l.pub.pool, `UPDATE stream_sessions SET state = $1 WHERE session_id = 'S'`, state)
	}

	setState("ending")
	l.tick(t.Context(), 0, 6)
	setState("live")
	l.mustPublish(t.Context(), 0, 6)
	setState("ending")
	l.tick(t.Context(), 0, 7)

	if got := logs.aborts(); len(got) != 2 || got[0] != reasonP0NoRow || got[1] != reasonP0NoRow {
		t.Errorf("중단 · 포기 로그 %v, want [p0_no_row p0_no_row](발행 한 번 사이에 둔 같은 사유)", got)
	}
}

// gap_tx_failed 의 1회 가드는 (사유, stage) 단위다(보안 r3 R3-L1 · c4-fix3 개정 4 · 판단 J25) — 이 사유는 처치가 다른
// 두 갈래(insert = 되돌려서 적용 없음 · commit = 적용 여부 모름 + 요구 적재)를 stage 속성으로 가른다. insert 실패가
// 이어지면 한 줄이고, 그 뒤의 COMMIT 결과 모름은 한 줄을 더 남긴다 — 캐시가 모르는 원장 행(장부 388 G-1 의 전제)을
// 알리는 줄이다.
func TestGapTxFailedLogsOncePerStage(t *testing.T) {
	l, _, logs := newGapLoop(t, 20, 6, "pending")
	var fail string // 이 문장을 끝난 ctx 로 보낸다 — 서버에 닿지 않고 실패한다
	l.pub.pool = hookedPool(t, l.pub.pool, traceSQL(func(ctx context.Context, _ *pgx.Conn, sql string) context.Context {
		if sql == fail {
			return expiredCtx(ctx)
		}
		return ctx
	}))

	fail = gapRecordSQL // stage=insert — 되돌린다
	l.gapTick(t.Context(), 0, 8, 6)
	l.gapTick(t.Context(), 0, 8, 6)
	fail = "commit" // stage=commit — COMMIT 결과를 모른다
	out := l.gapTick(t.Context(), 0, 8, 6)

	var got []string
	for _, r := range logs.abortRecs() {
		got = append(got, r.attrs["reason"]+"/"+r.attrs["stage"])
	}
	if want := []string{"gap_tx_failed/insert", "gap_tx_failed/commit"}; !slices.Equal(got, want) || !out.DemandLoad {
		t.Errorf("중단 · 포기 로그 %v · 셋째 틱 %+v, want %v(같은 stage 가 이어지면 한 줄) · 요구 적재", got, out, want)
	}
}

// 오류는 slog 속성으로만 남긴다(커밋 2 리뷰 인계 · 보안 c2) — 서버 <Message> 의 개행이 오류 문자열에 실리므로 문장에
// 섞지 않는다. 로그 문장은 키 하나이고 오류 문자열은 err 속성 값이다.
func TestAbortLogCarriesErrorAsAttribute(t *testing.T) {
	l, store, logs := publishedSolo(t)
	store.setOnPut(func(putCall) error { return errors.New("server said:\nline two") })

	l.tick(context.Background(), 0, 6)

	recs := logs.abortRecs()
	if len(recs) != 1 || recs[0].msg != abortedLog || recs[0].attrs["err"] != "server said:\nline two" {
		t.Errorf("로그 %+v, want 문장 %q · err 속성 = 원 오류 문자열", recs, abortedLog)
	}
}
