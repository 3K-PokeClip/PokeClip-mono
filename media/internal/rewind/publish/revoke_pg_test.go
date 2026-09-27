package publish

// 발행 전 검사 처치(설계 4.5.5 S1–S7 · 계획 4.5 A2 결정 1–4 · 체크리스트 A-2 5 · 6 · 12)와 A0 의 틱 도중 ending —
// fake Store + PG 통합이다.

import (
	"bytes"
	"context"
	"errors"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// chainKey 는 chainFixture 목록(소유 회차 S)의 객체 키다 — soloKey 와 같다.
const chainKey = soloKey

// newChainLoop 은 O ← P ← S 사슬에서 S 의 발행 루프다(S 의 행은 seq 600..lastSeq).
func newChainLoop(t *testing.T, lastSeq int64) (*loop, *fakeStore, *logRecorder) {
	t.Helper()
	pool := newPool(t)
	f := chainFixture(lastSeq)
	insertSessions(t, pool, f)
	store, logs := newFakeStore(t), &logRecorder{}
	return &loop{t: t, pub: newPublisher(t, pool, store, "w-me", logs), fx: f}, store, logs
}

// wantHalt 는 틱이 사유 reason(ERROR · 속성 check)으로 발행을 멈췄고 올리지 않았는가다.
func wantHalt(t *testing.T, out Outcome, logs *logRecorder, store *fakeStore, puts int, reason, check string) {
	t.Helper()
	if out.Published || len(store.putCalls()) != puts {
		t.Errorf("틱 = %+v · PUT %d번, want 발행 없음 · PUT %d번", out, len(store.putCalls()), puts)
	}
	recs := logs.abortRecs()
	if len(recs) != 1 || recs[0].attrs["reason"] != reason || recs[0].level.String() != "ERROR" || recs[0].attrs["check"] != check {
		t.Errorf("중단 · 포기 로그 %+v, want %s(ERROR · check=%s) 한 줄", recs, reason, check)
	}
}

// S1–S7 위반 처치(검증 표 단위 · 체크리스트 A-2 5) — S4 를 뺀 검사에 걸리면 발행 중단이다(validate — 속성 check).
// S2 의 DISC-SEQ 조항만 사유가 따로다(disc_seq_decrease — A2 결정 4). 모두 ERROR 이고 올리지 않는다.
func TestValidationViolationsHaltPublication(t *testing.T) {
	t.Run("S1_머리_후퇴", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 6)
		wantHalt(t, l.tick(t.Context(), 0, 5), logs, store, 1, reasonValidate, "S1")
	})
	t.Run("S2_MSN_후퇴", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 1000)
		l.mustPublish(t.Context(), 1, 900) // 1시간 창(900조각)
		wantHalt(t, l.tick(t.Context(), 0, 899), logs, store, 1, reasonValidate, "S2")
	})
	t.Run("S3_settled_아닌_행", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		l.fx.rows[6].PlaybackUploaded = false
		wantHalt(t, l.tick(t.Context(), 0, 6), logs, store, 1, reasonValidate, "S3")
	})
	t.Run("S5_init_미확정_스냅숏", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		l.fx.setInitUploaded("S", false)
		wantHalt(t, l.tick(t.Context(), 0, 6), logs, store, 1, reasonValidate, "S5")
	})
	t.Run("S6_세대_제자리", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		stale := *l.st.Prev
		stale.Gen = 100 // 같은 판(ETag)인데 자기기술의 세대가 DB 보다 앞선 P
		l.st.Prev = &stale
		wantHalt(t, l.tick(t.Context(), 0, 6), logs, store, 1, reasonValidate, "S6")
	})
	t.Run("S7_PDT_역행", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		l.fx.rows[6].PlaybackPDT = l.fx.rows[5].PlaybackPDT
		wantHalt(t, l.tick(t.Context(), 0, 6), logs, store, 1, reasonValidate, "S7")
	})
	t.Run("S2_DISC-SEQ_감소", func(t *testing.T) {
		l, store, logs := newSoloLoop(t, 10)
		l.mustPublish(t.Context(), 0, 5)
		ahead := *l.st.Prev
		ahead.DiscontinuitySequence = 3 // 루프가 넘긴 DISC-SEQ(0)보다 앞선 P — 기준을 잘못 잡은 계산의 결과
		l.st.Prev = &ahead
		out := l.pub.Tick(t.Context(), l.st, TickInput{Playlist: l.fx.window(0, 6), DiscontinuitySequence: 0})
		wantHalt(t, out, logs, store, 1, reasonDiscSeqDecrease, "S2")
	})
}

// reconnect_prev_init_missing_revokes_at_first_put(판단 J13 · 계획 4.5 A2 결정 2 · 5.3ⓓ) — 계승 회차 S 의 첫
// PUT 전, 스냅숏의 접두 회차 P 는 init 이 올라가지 않았다. P2 의 S4 가 걸리고(접두 MAP 선행 · init) 첫 PUT 전이라
// P2′ 로 계승을 영속 취소한 뒤 접두를 빼고 다시 렌더 · 검사해 올린다. 취소 뒤 DISC-SEQ 는 P2′ 가 돌려준 base
// 0 이다(A1 결정 6). 정상 취소(1행)는 요구 적재를 내지 않는다(6.4 음성 대조).
func TestReconnectPrevInitMissingRevokesAtFirstPut(t *testing.T) {
	l, store, logs := newChainLoop(t, 700)
	l.fx.setInitUploaded("P", false)

	out := l.tick(t.Context(), 100, 610)

	if !out.Published || !out.Revoked || out.DemandLoad || len(logs.aborts()) != 0 {
		t.Fatalf("틱 = %+v · 로그 %v, want 취소 뒤 발행 · 요구 적재 없음 · 로그 없음", out, logs.aborts())
	}
	body := store.objects[chainKey].body
	if !bytes.Contains(body, []byte("#EXT-X-MEDIA-SEQUENCE:600\n")) || !bytes.Contains(body, []byte("#EXT-X-DISCONTINUITY-SEQUENCE:0\n")) ||
		bytes.Contains(body, []byte("#EXT-X-DISCONTINUITY\n")) || bytes.Contains(body, []byte("/init/P.mp4")) {
		t.Errorf("올린 본문이 접두 없는 S 의 목록(MSN 600 · DISC-SEQ 0 · 끊김 표시 · P 의 MAP 없음)이 아니다:\n%.400s", body)
	}
	row := readSession(t, l.pub.pool, "S")
	if row.inherits != nil || row.base != 0 || out.State.Prev.DiscontinuitySequence != 0 {
		t.Errorf("DB 계승 %s · base %d · P 의 DISC-SEQ %d, want NULL · 0 · 0", strOrNil(row.inherits), row.base, out.State.Prev.DiscontinuitySequence)
	}
}

// 음성 대조(계획 4.5 F 6.4 A2 줄) — 직전 init 이 착지한 계승은 취소되지 않고 접두를 싣고 나간다. 발행된 목록의 정상
// 경로도 P2′ 를 부르지 않는다(DB 계승 그대로).
func TestLandedPrefixInitIsNotRevoked(t *testing.T) {
	l, store, _ := newChainLoop(t, 700)

	first := l.mustPublish(t.Context(), 100, 610)
	second := l.mustPublish(t.Context(), 100, 611)

	if first.Revoked || second.Revoked {
		t.Errorf("취소 = %v · %v, want 둘 다 거짓", first.Revoked, second.Revoked)
	}
	if got := strOrNil(readSession(t, l.pub.pool, "S").inherits); got != "P" {
		t.Errorf("DB 계승 = %s, want P", got)
	}
	body := store.objects[chainKey].body
	if !bytes.Contains(body, []byte("#EXT-X-MEDIA-SEQUENCE:100\n")) || !bytes.Contains(body, []byte("/init/P.mp4")) {
		t.Errorf("올린 본문에 접두(P 의 행 · MAP)가 없다:\n%.400s", body)
	}
}

// post_publish_s4_violation_halts_without_revoke(계획 4.5 A2 결정 3) — 첫 PUT 뒤의 S4 위반은 발행 중단이다(ERROR ·
// s4_after_publish). 계승은 유지한다 — 첫 PUT 뒤 취소는 남은 조각의 표시를 지우게 된다(6.2.1 밖). P2′ 를 부르지
// 않으므로 DB 계승이 그대로다.
func TestPostPublishS4ViolationHaltsWithoutRevoke(t *testing.T) {
	l, store, logs := newChainLoop(t, 700)
	l.mustPublish(t.Context(), 100, 610)
	l.fx.setInitUploaded("P", false)

	out := l.tick(t.Context(), 100, 611)

	wantHalt(t, out, logs, store, 1, reasonS4AfterPublish, "S4")
	if out.Revoked || out.DemandLoad {
		t.Errorf("틱 = %+v, want 취소 없음 · 요구 적재 없음", out)
	}
	if got := strOrNil(readSession(t, l.pub.pool, "S").inherits); got != "P" {
		t.Errorf("DB 계승 = %s, want P(유지)", got)
	}
}

// revoke_persisted_before_put(계획 4.5 A2 결정 2 — 대안 「취소 영속을 P4 로」 기각) — 계승 취소는 PUT 전에 DB 에
// 닿는다. PUT 200 뒤 P4 전에 죽어도 재기동한 writer 가 계승을 되살려 MSN 을 역행시키지 않는다.
func TestRevokePersistedBeforePut(t *testing.T) {
	l, store, _ := newChainLoop(t, 700)
	l.fx.setInitUploaded("P", false)
	atPut := "<PUT 없음>"
	store.setOnPut(onceBefore(func() { atPut = strOrNil(readSession(t, l.pub.pool, "S").inherits) }))

	l.mustPublish(t.Context(), 100, 610)

	if atPut != "<NULL>" {
		t.Errorf("PUT 순간의 DB 계승 = %s, want <NULL>(취소가 PUT 전에 영속)", atPut)
	}
}

// revoke_decision_uses_loop_snapshot(계획 4.5 A2 결정 2 · 뮤테이션 37) — 취소 판정의 입력은 P2 의 S4 가 보는 목록
// 값(루프 스냅숏)이다. DB 를 다시 읽지 않는다: 스냅숏이 접두 init 미확정이면 DB 가 확정이어도 취소하고, 스냅숏이
// 확정이면 DB 가 미확정이어도 접두를 싣는다(판정과 렌더가 한 값에서 나와 서로 어긋날 수 없다).
func TestRevokeDecisionUsesLoopSnapshot(t *testing.T) {
	t.Run("스냅숏_미확정_DB_확정", func(t *testing.T) {
		l, _, _ := newChainLoop(t, 700)
		l.fx.setInitUploaded("P", false)
		if out := l.mustPublish(t.Context(), 100, 610); !out.Revoked {
			t.Errorf("취소 = 거짓, want 참(스냅숏이 판정한다)")
		}
	})
	t.Run("스냅숏_확정_DB_미확정", func(t *testing.T) {
		l, _, _ := newChainLoop(t, 700)
		exec(t, l.pub.pool, `UPDATE stream_sessions SET init_uploaded_at = NULL WHERE session_id = 'P'`)
		if out := l.mustPublish(t.Context(), 100, 610); out.Revoked {
			t.Errorf("취소 = 참, want 거짓(스냅숏이 판정한다)")
		}
	})
}

// p2prime_unknown_result_demands_load(계획 4.5 A2 결정 2 · 판단 J3 · 체크리스트 A-2 6) — P2′ 가 계승 취소를 1행으로
// 확인하지 못하면 틱을 포기하고 화해한 뒤 그 스트림에 요구 적재를 낸다(revoke_unknown). 0행뿐 아니라 모든 오류를
// 결과 모름으로 본다(r44 미확인 8) — 서버가 취소를 커밋했는지 워커는 모른다. 커밋됐는데 캐시가 계승을 든 채면 적재가
// DB 값(해제 · base 0)을 싣기 전까지 틱이 없어야 한다. 세 변형이다(오류 변형은 r4 cc 지적 1).
//
//	0행              서버는 앞선 P2′ 를 커밋했는데 워커가 결과를 잃었다 — DB 계승은 이미 NULL
//	서버 오류         P2′ 가 서버 오류로 끝났다 — 문장이 되돌려져 DB 계승 그대로(테스트 DB 한정 트리거)
//	커밋 뒤 시한 초과  서버는 이 P2′ 를 커밋했는데 워커는 문장 시한 초과(ctx 오류)를 받았다(계획 문언 「서버에서
//	                 커밋되고 워커는 ctx 오류를 본다」 — 같은 문장을 다른 연결로 커밋하고 워커 문장에는 끝난 ctx 를 준다)
//
// 오류 변형은 오류를 err 속성으로 싣는다. CompleteLoad 까지 잇는 것은 커밋 7 이다.
func TestP2PrimeUnknownResultDemandsLoad(t *testing.T) {
	cases := []struct {
		name         string
		setup        func(t *testing.T, l *loop)
		wantErr      string // err 속성에 든 말 — 빈 값이면 속성이 없다
		wantInherits string // 틱 뒤 DB 계승
	}{
		{"0행", func(t *testing.T, l *loop) {
			exec(t, l.pub.pool, `UPDATE stream_sessions SET inherits_session = NULL, discontinuity_base = 0 WHERE session_id = 'S'`)
		}, "", "<NULL>"},
		{"서버_오류", func(t *testing.T, l *loop) {
			injectUpdateFault(t, l.pub.pool, "OLD.inherits_session IS NOT NULL AND NEW.inherits_session IS NULL").arm()
		}, "pc_test_fault", "P"},
		{"커밋_뒤_시한_초과", func(t *testing.T, l *loop) {
			base := l.pub.pool
			l.pub.pool = hookedPool(t, base, stmtHook{sql: p2RevokeSQL, before: func(sctx context.Context, args []any) context.Context {
				if _, err := base.Exec(context.Background(), p2RevokeSQL, args...); err != nil {
					t.Errorf("P2′ 를 서버에 커밋하지 못했다: %v", err)
				}
				expired, cancel := context.WithDeadline(sctx, time.Now().Add(-time.Second))
				cancel()
				return expired
			}})
		}, "context deadline exceeded", "<NULL>"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs := newChainLoop(t, 700)
			l.fx.setInitUploaded("P", false)
			tc.setup(t, l)

			out := l.tick(t.Context(), 100, 610)

			if out.Published || out.Revoked || !out.DemandLoad || out.Err != nil || len(store.putCalls()) != 0 {
				t.Errorf("틱 = %+v · PUT %d번, want 발행 없음 · 취소 결과 없음 · 요구 적재 · Err 없음 · PUT 0", out, len(store.putCalls()))
			}
			recs := logs.abortRecs()
			if len(recs) != 1 || recs[0].attrs["reason"] != reasonRevokeUnknown {
				t.Fatalf("중단 · 포기 로그 %+v, want %s 한 줄", recs, reasonRevokeUnknown)
			}
			if got, ok := recs[0].attrs["err"]; ok != (tc.wantErr != "") || !strings.Contains(got, tc.wantErr) {
				t.Errorf("err 속성 = %q(있음 %v), want %q 를 담음(빈 값이면 속성 없음)", got, ok, tc.wantErr)
			}
			if heads := store.headKeys(); len(heads) != 2 {
				t.Errorf("Head %d번, want 2(획득 뒤 화해 · 포기 뒤 화해)", len(heads))
			}
			if got := strOrNil(readSession(t, l.pub.pool, "S").inherits); got != tc.wantInherits {
				t.Errorf("틱 뒤 DB 계승 = %s, want %s", got, tc.wantInherits)
			}
		})
	}
}

// P2′ 로 계승을 취소하고 접두를 빼니 남는 행이 없는 틱은 PUT 없이 조용히 끝난다(계획 4.5 A2 결정 2 「남은 행이 0 이면
// PUT 없이」 · 체크리스트 A-2 6 · r4 cc 지적 2). 계승 회차의 첫 조각이 아직 창에 없고 접두 회차 init 은 착지 전인
// 첫 PUT 전 국면이다(계획 4.5 B #11 의 「접두만 실린 목록」). 빈 목록을 렌더하지 않으므로 입력 결함(Outcome.Err)도
// 로그도 없다. 취소는 영속됐고, 다음 틱은 접두 없는 목록을 낸다.
func TestRevokeLeavingNoRowsPublishesNothing(t *testing.T) {
	l, store, logs := newChainLoop(t, 700)
	l.fx.setInitUploaded("P", false)

	out := l.tick(t.Context(), 100, 599) // 접두(P 의 행)만 실린 창

	if out.Published || !out.Revoked || out.Err != nil || len(store.putCalls()) != 0 || len(logs.aborts()) != 0 {
		t.Errorf("틱 = %+v · PUT %d번 · 로그 %v, want 발행 없음 · 취소 · Err 없음 · PUT 0 · 로그 없음",
			out, len(store.putCalls()), logs.aborts())
	}
	if got := strOrNil(readSession(t, l.pub.pool, "S").inherits); got != "<NULL>" {
		t.Errorf("DB 계승 = %s, want <NULL>(취소 영속)", got)
	}
	l.mustPublish(t.Context(), 100, 610)
}

// revokeStatement 는 P2′ 문장을 픽스처 회차 S 에 그대로 보내고 바뀐 행이 있었는지 돌려준다 — fence 는 w-me 가
// 쥐었고 세대 조건은 지금 세대다.
func revokeStatement(t *testing.T, l *loop) bool {
	t.Helper()
	gen := readSession(t, l.pub.pool, "S").gen
	var base int64
	err := l.pub.pool.QueryRow(context.Background(), p2RevokeSQL, "S", "w-me", gen).Scan(&base)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		t.Fatalf("P2′ 문장 오류: %v", err)
	}
	return err == nil
}

// holdFence 는 픽스처 회차 S 의 fence 를 w-me 가 lease 안에서 쥔 상태로 둔다.
func holdFence(t *testing.T, l *loop) {
	t.Helper()
	exec(t, l.pub.pool, `UPDATE stream_sessions SET writer_fence = 'w-me', fence_expires_at = now() + interval '5 seconds' WHERE session_id = 'S'`)
}

// revoke_statement_refuses_published_list(계획 4.5 A2 결정 2 · 뮤테이션 69) — P2′ 는 첫 PUT 전에만 계승을
// 취소한다(`manifest_etag IS NULL`). 발행된 목록의 계승은 문장이 거절한다. 대조군: 첫 PUT 전이면 1행이다.
func TestRevokeStatementRefusesPublishedList(t *testing.T) {
	l, _, _ := newChainLoop(t, 700)
	holdFence(t, l)
	exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_etag = '"published"' WHERE session_id = 'S'`)
	if revokeStatement(t, l) {
		t.Error("발행된 목록의 P2′ = 1행, want 0행")
	}
	exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_etag = NULL WHERE session_id = 'S'`)
	if !revokeStatement(t, l) {
		t.Error("첫 PUT 전의 P2′ = 0행, want 1행(대조군)")
	}
}

// revoke_statement_refuses_ending_session(계획 4.5 A2 결정 2 · 6 · 뮤테이션 70) — P2′ 는 live 회차에서만 취소한다
// (`state = 'live'`). 대조군: live 면 1행이다.
func TestRevokeStatementRefusesEndingSession(t *testing.T) {
	l, _, _ := newChainLoop(t, 700)
	holdFence(t, l)
	exec(t, l.pub.pool, `UPDATE stream_sessions SET state = 'ending', ending_at = now() WHERE session_id = 'S'`)
	if revokeStatement(t, l) {
		t.Error("ending 회차의 P2′ = 1행, want 0행")
	}
	exec(t, l.pub.pool, `UPDATE stream_sessions SET state = 'live', ending_at = NULL WHERE session_id = 'S'`)
	if !revokeStatement(t, l) {
		t.Error("live 회차의 P2′ = 0행, want 1행(대조군)")
	}
}

// p0_committed_tick_completes_after_ending(계획 4.5 A0 결정 2 · 체크리스트 A-2 12 · 장부 G-2) — ending 전이 전에
// P0 이 커밋된 틱은 PUT · P4 까지 간다(P4 에는 state 조건이 없다). 워커는 틱 도중 회차 state 를 다시 보지 않는다.
// 다음 틱은 P0 의 state = 'live' 가 거부한다(p0_no_row 한 줄 · PUT 0) — 새 틱을 내지 않는 루프 가드는 커밋 4 다.
func TestP0CommittedTickCompletesAfterEnding(t *testing.T) {
	l, store, logs := newSoloLoop(t, 10)
	l.mustPublish(t.Context(), 0, 5)
	store.setOnPut(onceBefore(func() {
		exec(t, l.pub.pool, `UPDATE stream_sessions SET state = 'ending', ending_at = now() WHERE session_id = 'S'`)
	}))

	during := l.tick(t.Context(), 0, 6)

	if !during.Published || len(store.putCalls()) != 2 {
		t.Fatalf("P0 뒤 ending 된 틱 = %+v · PUT %d번, want 발행(P4 1행) · PUT 2번", during, len(store.putCalls()))
	}
	if got := strOrNil(readSession(t, l.pub.pool, "S").etag); got != during.State.Prev.ETag {
		t.Errorf("DB ETag = %s, want P4 가 쓴 %s", got, during.State.Prev.ETag)
	}
	after := l.tick(t.Context(), 0, 7)
	if after.Published || len(store.putCalls()) != 2 {
		t.Errorf("ending 뒤 틱 = %+v · PUT %d번, want 발행 없음 · PUT 2번", after, len(store.putCalls()))
	}
	if got := logs.aborts(); !slices.Equal(got, []string{reasonP0NoRow}) {
		t.Errorf("중단 · 포기 로그 %v, want [%s]", got, reasonP0NoRow)
	}
}
