package indexer

// 비성장확정 꼬리(Idle·Scan)의 ③ 요청 시점 — 계획 4.2-R 규칙 ①: ③ 작업은 INSERT 자리에서 만들되
// 요청은 ② 와 같은 세 시점(보류 해제 · 다음 INSERT 승격 · 포기)에 한 번만 한다. 그때까지 작업은
// heldTail.playbackTarget 에 머물고, 보류가 그 밖의 길(재조정·낡음)로 지워질 때도 그 자리에서 한 번 나간다.

import (
	"context"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// idleRow 는 wall 에 시작한 조각을 유휴 판정으로 처리한다. 그 조각의 mtxi 는 recA·dts 다.
func (f *fixture) idleRow(r *fakeReads, wall time.Time, size int, dts time.Duration) recording.Segment {
	f.t.Helper()
	seg := f.segment("s1", segName(wall, 0), size, recording.ReasonIdle)
	r.mtxi[seg.Path] = *mtxiOf(recA, dts)
	f.mustHandle(seg)
	return seg
}

// idle_tail_playback_waits_for_tail_hold(뮤테이션 65) — 유휴 판정은 "10초간 안 자라서 완성으로
// 추정"이다. 그 자리에서 ③ 을 뽑으면 FIN 없는 끊김에서 뒤늦게 써진 마지막 part 가 빠진 채
// uploaded 로 굳는다(설계 3.1-2·A6). 그래서 ③ 은 ② 와 함께 보류 해제 때 한 번 요청되고, ② 가 거부돼
// 다시 시도될 때 다시 나가지 않는다.
func TestIdleTailPlaybackWaitsForTailHold(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1")
	f.idleRow(r, time.Now().UTC(), 1000, 7*time.Second)

	if n := len(f.upload.targetsOf(index.AxisPlayback)); n != 0 {
		t.Fatalf("보류 해제 전에 ③ 요청 %d건 — 유휴 추정 직후의 파일은 아직 자랄 수 있다", n)
	}

	time.Sleep(f.opt.TailHold + 10*time.Millisecond)
	f.upload.accept = false // ② 는 거부된다 — ③ 은 접수 결과와 무관하게 한 번 나간다
	f.ix.ReleaseHeldTails()
	got := f.upload.targetsOf(index.AxisPlayback)
	if len(got) != 1 || got[0].Seq != 0 || !got[0].IsTail || got[0].PlaybackPos != 7*time.Second {
		t.Fatalf("보류 해제 때 ③ 요청 = %+v, want seq 0 · 꼬리 · pos 7s 1건", got)
	}

	time.Sleep(f.opt.TailHold + 10*time.Millisecond)
	f.upload.accept = true
	f.ix.ReleaseHeldTails() // ② 재시도
	if n := len(f.upload.targetsOf(index.AxisArchive)); n != 2 {
		t.Fatalf("② 요청 %d건, want 2건(거부 + 재시도) — 전제", n)
	}
	if n := len(f.upload.targetsOf(index.AxisPlayback)); n != 1 {
		t.Errorf("② 재시도 때 ③ 이 또 나갔다(%d건) — ③ 은 한 번만 요청한다", n)
	}
}

// regrown_idle_tail_never_fixes_truncated_playback(뮤테이션 65) — 유휴 판정 뒤 파일이 더 자라면
// 꼬리 교정이 보관한 ③ 작업의 크기도 고친다. 나가는 ③ 은 언제나 자란 뒤의 크기를 싣는다 —
// 교정 전 크기의 ③ 이 한 번이라도 나갔다면 잘린 렌디션이 uploaded 로 굳을 수 있다.
func TestRegrownIdleTailNeverFixesTruncatedPlayback(t *testing.T) {
	f := newFixture(t, 4000, 6000) // 첫 측정 · 교정 재측정
	r := f.injectReads()
	f.store.scriptSessions("S1")
	wall := time.Now().UTC()
	seg := f.idleRow(r, wall, 1000, 7*time.Second)

	f.makeFile("s1", segName(wall, 0), 2500)
	seg.Reason = recording.ReasonRegrown
	f.mustHandle(seg)
	if got := f.ix.cursors["s1"].Tail.Bytes; got != 2500 {
		t.Fatalf("꼬리 교정 뒤 장부 크기 = %d, want 2500(전제)", got)
	}

	time.Sleep(f.opt.TailHold + 10*time.Millisecond)
	f.ix.ReleaseHeldTails()

	got := f.upload.targetsOf(index.AxisPlayback)
	if len(got) != 1 || got[0].Bytes != 2500 {
		t.Errorf("③ 요청 = %+v, want 자란 뒤 크기 2500 의 1건", got)
	}
}

// 다음 INSERT 가 보류 중인 꼬리를 승격하면 ③ 도 그때 한 번 나가고(더 자랄 수 없으니 IsTail=false),
// 새 행의 ③ 보다 앞선다 — 업로더의 보정값 표 기록이 seq 순을 지킨다(규칙 ①).
func TestHeldPlaybackPromotedBeforeNextRow(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1")
	f.idleRow(r, baseWall, 1000, 10*time.Second)

	f.row(r, 4*time.Second, mtxiOf(recA, 14*time.Second))

	got := f.upload.targetsOf(index.AxisPlayback)
	if len(got) != 2 || got[0].Seq != 0 || got[0].IsTail || got[1].Seq != 1 || !got[1].IsTail {
		t.Fatalf("③ 요청 순서 = %+v, want [seq 0 비꼬리, seq 1 꼬리]", got)
	}
	if _, held := f.ix.held["s1"]; held {
		t.Error("승격과 새 꼬리 접수 뒤에도 보류가 남아 있다")
	}
}

// 보류를 포기하면(TailGrace 경과 — ② 는 스위퍼의 꼬리 예외에 넘긴다) ③ 은 그때 한 번 요청한다.
// 여기서도 안 보내면 그 행의 ③ 은 실시간 요청이 영영 없다 — 스위퍼 재생성은 회차 보정값 표가 그
// 행을 덮을 때만 되는데, 표 줄은 실시간 요청만 적는다.
func TestAbandonedHoldStillSendsPlaybackOnce(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1")
	f.idleRow(r, baseWall, 1000, 10*time.Second) // baseWall 은 과거 — 첫 틱에 포기한다

	f.ix.ReleaseHeldTails()
	f.ix.ReleaseHeldTails()

	got := f.upload.targetsOf(index.AxisPlayback)
	if len(got) != 1 || !got[0].IsTail {
		t.Errorf("포기 때 ③ 요청 = %+v, want 꼬리 1건", got)
	}
	if n := len(f.upload.targetsOf(index.AxisArchive)); n != 0 {
		t.Errorf("포기했는데 ② 요청 %d건 — ② 는 스위퍼 몫이다", n)
	}
}

// setArchiveState 는 장부 행의 ② 상태를 바꾼다 — 스위퍼(꼬리 예외)가 그 행을 먼저 올린 국면이다.
func (s *fakeStore) setArchiveState(streamID string, seq int64, state index.UploadState) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, r := range s.rows[streamID] {
		if r.Seq == seq {
			s.rows[streamID][i].UploadState = state
		}
	}
}

// 보류가 세 시점 밖의 길 — 커서 재적재의 재조정(reconcileUploadState) — 으로 지워져도 붙든 ③ 은
// 그 자리에서 한 번 요청된다(cc·cx r3 #1). 그 행이 회차 첫 조각이면 이 요청이 업로더의 보정값 표를
// 여는 유일한 자리다: 요청 없이 버리면 스위퍼도 표가 없어 거부해(no_offset_table — 업로더 쪽은
// TestTableStartsAtFirstLivePlaybackRequest) 첫 조각이 영구히 빠지고 연속 접두가 멈춘다. 그 행은 이미
// 닫혔다 — ② 가 확정됐거나 커서가 다른 행으로 넘어갔다(IsTail=false).
func TestReconcileDroppingHoldStillRequestsPlaybackOnce(t *testing.T) {
	tests := []struct {
		name string
		// ledger 는 재적재가 읽을 장부를 바꾼다 — 보류한 행이 더는 인덱서의 꼬리 몫이 아니게 된다.
		ledger func(f *fixture, wall time.Time)
	}{
		{"②_가_스위퍼_손에_확정됐다", func(f *fixture, _ time.Time) {
			f.store.setArchiveState("s1", 0, index.UploadStateUploaded)
		}},
		{"커서가_다른_행으로_바뀌었다", func(f *fixture, wall time.Time) {
			path := f.makeFile("s1", segName(wall, 4*time.Second), 1000)
			f.store.seed(index.Record{
				StreamID: "s1", Seq: 1, StartWallUTC: wall.Add(4 * time.Second), DurationMS: 4000,
				LocalPath: path, UploadState: index.UploadStatePending, Bytes: 1000,
			})
		}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newFixture(t, 4000)
			r := f.injectReads()
			f.store.scriptSessions("S1")
			wall := time.Now().UTC()
			f.idleRow(r, wall, 1000, 7*time.Second) // 회차 첫 조각 — ③ 은 보류에만 있다
			tt.ledger(f, wall)

			if err := f.ix.Scan(context.Background(), f.root); err != nil {
				t.Fatalf("Scan 실패: %v", err)
			}

			if _, held := f.ix.held["s1"]; held {
				t.Fatal("재적재 뒤에도 보류가 남아 있다(전제)")
			}
			got := f.upload.targetsOf(index.AxisPlayback)
			if len(got) != 1 || got[0].Seq != 0 || got[0].IsTail || got[0].PlaybackPos != 7*time.Second {
				t.Fatalf("보류를 지울 때 ③ 요청 = %+v, want seq 0 · 비꼬리 · pos 7s 1건", got)
			}
			f.ix.ReleaseHeldTails()
			if n := len(f.upload.targetsOf(index.AxisPlayback)); n != 1 {
				t.Errorf("③ 요청 %d건, want 1건 — 한 행의 ③ 은 한 번만 요청한다", n)
			}
		})
	}
}

// 보류 해제 틱이 그 보류를 낡았다고 버릴 때(held_tail_stale — 커서가 그 행을 떠났다)도 붙든 ③ 은
// 한 번 요청된다. 버리는 자리가 어디든 그 행의 실시간 ③ 요청은 정확히 1회다(규칙 ①).
func TestStaleHoldStillRequestsPlaybackOnce(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1")
	f.idleRow(r, time.Now().UTC(), 1000, 7*time.Second)
	f.ix.cursors["s1"].Tail.Seq = 1 // 다음 행이 들어온 커서 — 보류는 낡았다

	f.ix.ReleaseHeldTails()
	f.ix.ReleaseHeldTails()

	if _, held := f.ix.held["s1"]; held {
		t.Fatal("낡은 보류가 남아 있다(전제)")
	}
	got := f.upload.targetsOf(index.AxisPlayback)
	if len(got) != 1 || got[0].Seq != 0 || got[0].IsTail {
		t.Errorf("낡은 보류를 버릴 때 ③ 요청 = %+v, want seq 0 · 비꼬리 1건", got)
	}
}
