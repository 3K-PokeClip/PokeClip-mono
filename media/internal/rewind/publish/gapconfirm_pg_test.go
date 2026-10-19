package publish

// GAP 원장의 확정(설계 6.3 원자성 「P4 성공 시 put_confirmed」 · 판단 J27) — P4 문장이 확정 본문 범위의 recorded 행을
// put_confirmed 로 바꾸는 전이다. fake Store + PG 통합이다(PG_DSN 이 없으면 skip). GAP 원장 픽스처(insertGap ·
// readGaps · wantGapStates)는 gap_pg_test.go 에 있다.

import (
	"testing"
	"time"
)

// newLateSoloLoop 은 계승 없는 회차 S 가 seq first 에서 열린 발행 루프다(행 first..last · 컷오프 0). 1시간이 안 되는
// 목록은 회차 첫 조각부터라(S3) MSN 이 first 다 — MSN 아래에 GAP 원장 행을 둘 자리가 생긴다.
func newLateSoloLoop(t *testing.T, first, last int64) (*loop, *fakeStore, *logRecorder) {
	t.Helper()
	l, store, logs := newSoloLoop(t, last)
	l.fx.rows = l.fx.rows[first:]
	s := l.fx.sessions["S"]
	s.MinSeq = first
	l.fx.sessions["S"] = s
	return l, store, logs
}

// put_confirmed(설계 6.3 원자성 「P4 성공 시 put_confirmed」 · 판단 J27) — P4 가 적용된 그 문장이 확정 본문 범위
// [MediaSequence, PublishedSeq] 안의 recorded 행을 put_confirmed 로 바꾼다. 범위 밖(MSN 아래 · 끝 seq 위) · 다른
// 스트림 · recorded 가 아닌 행은 그대로다. 이미 확정한 행은 다시 바꾸지 않는다(멱등 — 확정 시각이 그대로다).
func TestP4ConfirmsRecordedGapsInPublishedRange(t *testing.T) {
	l, _, _ := newLateSoloLoop(t, 10, 20)
	pool := l.pub.pool
	for seq, state := range map[int64]string{9: "recorded", 10: "recorded", 13: "recorded", 14: "vod_abandoned", 16: "recorded", 17: "recorded"} {
		insertGap(t, pool, fxStream, seq, state)
		l.fx.markGap(seq)
	}
	insertGap(t, pool, fxStream, 15, "put_confirmed")
	l.fx.markGap(15)
	old := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	exec(t, pool, `UPDATE stream_published_gaps SET put_confirmed_at = $1 WHERE stream_id = $2 AND seq = 15`, old, fxStream)
	insertGap(t, pool, "other", 13, "recorded")

	l.mustPublish(t.Context(), 10, 16)

	first := readGaps(t, pool, fxStream)
	wantGapStates(t, first, map[int64]string{
		9: "recorded", 10: "put_confirmed", 13: "put_confirmed", 14: "vod_abandoned",
		15: "put_confirmed", 16: "put_confirmed", 17: "recorded",
	})
	if at := first[15].confirmedAt; at == nil || !at.Equal(old) {
		t.Errorf("이미 확정한 seq 15 의 확정 시각 = %v, want 그대로 %v", at, old)
	}
	wantGapStates(t, readGaps(t, pool, "other"), map[int64]string{13: "recorded"})

	l.mustPublish(t.Context(), 10, 17)

	second := readGaps(t, pool, fxStream)
	if got := second[17].state; got != "put_confirmed" {
		t.Errorf("다음 발행 [10, 17] 뒤 seq 17 = %s, want put_confirmed", got)
	}
	for _, seq := range []int64{10, 13, 16} {
		if a, b := first[seq].confirmedAt, second[seq].confirmedAt; a == nil || b == nil || !a.Equal(*b) {
			t.Errorf("seq %d 의 확정 시각 %v → %v, want 그대로(멱등)", seq, a, b)
		}
	}
}

// P4 가 0행이거나(CAS 거부 — p4_no_row) 오류로 끝나면(p4_unknown) GAP 행은 recorded 그대로다 — 전이가 P4 와 같은
// 문장이라 P4 가 적용되지 않으면 전이도 없다(판단 J27). 뒤따르는 화해(R3)도 확정하지 않는다. 다음 틱의 P4 성공이
// 확정한다.
func TestP4RefusalLeavesGapsRecorded(t *testing.T) {
	cases := []struct {
		name   string
		reason string
		inject func(t *testing.T, l *loop, store *fakeStore)
	}{
		{"0행", reasonP4NoRow, func(t *testing.T, l *loop, store *fakeStore) {
			store.setOnPut(onceBefore(func() {
				exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_gen = manifest_gen + 5 WHERE session_id = 'S'`)
			}))
		}},
		{"오류", reasonP4Unknown, func(t *testing.T, l *loop, store *fakeStore) {
			store.setOnPut(onceBefore(injectUpdateFault(t, l.pub.pool, "true").arm)) // P0 뒤 · P4 전 — 다음 UPDATE = P4
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs := newLateSoloLoop(t, 10, 20)
			l.mustPublish(t.Context(), 10, 12)
			insertGap(t, l.pub.pool, fxStream, 13, "recorded")
			l.fx.markGap(13)
			tc.inject(t, l, store)

			out := l.tick(t.Context(), 10, 14)

			if got := logs.aborts(); len(got) != 1 || got[0] != tc.reason || out.Published {
				t.Fatalf("틱 = %+v · 로그 %v, want 발행 없음 · [%s]", out, got, tc.reason)
			}
			wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{13: "recorded"})

			l.mustPublish(t.Context(), 10, 15)
			wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{13: "put_confirmed"})
		})
	}
}

// 재기동(영값 State)한 writer 의 첫 P4 성공이 창 안의 recorded 를 모두 확정한다 — 범위가 창 전체라 발행자 상태가
// 필요 없다(판단 J27). 앞 writer 의 GAP INSERT 는 커밋됐는데 그 PUT 전에 프로세스가 죽은 국면이다(재기동 적재가
// 원장 행을 캐시에 싣는다).
func TestRestartFirstP4ConfirmsRecordedGaps(t *testing.T) {
	l, _, _ := newLateSoloLoop(t, 10, 20)
	l.mustPublish(t.Context(), 10, 12)
	for _, seq := range []int64{13, 14} {
		insertGap(t, l.pub.pool, fxStream, seq, "recorded")
		l.fx.markGap(seq)
	}
	expireLease(t, l.pub.pool, "S")
	b := l.peer("w-new", &logRecorder{})
	b.tick(t.Context(), 10, 15) // 획득 → 화해(P 가 바뀌어 여기서 끝)
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{13: "recorded", 14: "recorded"})

	b.mustPublish(t.Context(), 10, 15)

	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{13: "put_confirmed", 14: "put_confirmed"})
}
