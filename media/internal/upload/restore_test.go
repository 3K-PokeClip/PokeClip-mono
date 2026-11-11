package upload

import (
	"bytes"
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// ③ 사슬 복원(POK-196 PDT 계획 4절 c10b · 설계 r6 2.10 · T-14 목록)을 시험대(restore_bench_test.go)에서 잰다. 판정은 복원이
// 낸 도장 · 표 · 작업이 실시간 사슬(같은 접기)과 같은가와, 장부 · S3 에 한 일이다.

// contiguous 는 seq 부터 n 행을 같은 녹화기 · 잇단 번호 · 맞닿은 영상 칸으로 놓는다(states 는 행마다 ③ 상태).
func (b *restoreBench) contiguous(seq int64, n0 uint64, dts0 time.Duration, states ...index.UploadState) {
	step := b.step()
	for i, s := range states {
		b.add(benchRow{seq: seq + int64(i), session: benchSession, rec: recA, number: n0 + uint64(i),
			dts: dts0 + time.Duration(i)*step, playback: s, archive: index.UploadStateUploaded})
	}
}

// uploadAt 은 ③ uploaded 행의 객체를 실시간 도장으로 놓는다.
func (b *restoreBench) uploadAt(live map[int64]playback.Link, seqs ...int64) {
	for _, s := range seqs {
		b.setPlaybackObject(s, live[s].Pos)
	}
}

const (
	up      = index.UploadStateUploaded
	failed  = index.UploadStateFailed
	pending = index.UploadStatePending
)

// 구멍은 DB 에서 열거한다(설계 r6 2.10 r2 — cx-N1): seq 9 uploaded → 10 failed → 11 uploaded → 12 pending 이면 구멍 둘을 각자의
// 닻(9 · 11)으로 되살린다. 표에 두 줄이 적히고 두 행이 실시간 도장 그대로 다시 들어간다. 잡는 결함(음성 대조): 「목표 = 깨운
// 요청의 최댓값 · 닻 = 그 앞 최신 uploaded」 꼴이면 10 을 영영 건너뛴다.
func TestRestoreEnumeratesEveryHoleWithItsAnchor(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(8, 1, 600*time.Second, up, up, failed, up, pending)
	live := b.liveChain()
	b.uploadAt(live, 8, 9, 11)

	b.restore()

	jobs := b.queued()
	if len(jobs) != 2 || jobs[0].Seq != 10 || jobs[1].Seq != 12 {
		t.Fatalf("다시 넣은 작업 = %+v, want seq 10 · 12 (%s)", jobs, b.cap.dump())
	}
	for _, j := range jobs {
		if j.PlaybackPos != live[j.Seq].Pos || j.PosUnknown {
			t.Errorf("seq %d 도장 = %v, want 실시간 %v", j.Seq, j.PlaybackPos, live[j.Seq].Pos)
		}
		if off, ok := b.offset(j.Seq); !ok || off != live[j.Seq].Offset {
			t.Errorf("seq %d 표 = (%v, %v), want 실시간 보정값 %v", j.Seq, off, ok, live[j.Seq].Offset)
		}
	}
	if got, ok := b.u.RestoredLink(benchSession); !ok || got.Seq != 12 || got.Pos != live[12].Pos {
		t.Errorf("복원 링크 = (%+v, %v), want seq 12 · 실시간 도장", got, ok)
	}
	if n := b.cap.count("chain_restored"); n != 2 {
		t.Errorf("chain_restored = %d줄, want 구멍마다 1 = 2", n)
	}
}

// 닻의 ② 가 로컬에 없으면 ② 객체로 읽는다(설계 r6 2.10 r2 — cx-N4 「닻 로컬 ② 삭제 → ② 객체로 같은 결과」).
func TestRestoreReadsAnchorArchiveObjectWhenLocalGone(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(8, 1, 600*time.Second, up, up, pending)
	live := b.liveChain()
	b.uploadAt(live, 8, 9)
	if err := os.Remove(b.ledger.rows[9].LocalPath); err != nil {
		t.Fatal(err)
	}

	b.restore()

	jobs := b.queued()
	if len(jobs) != 1 || jobs[0].Seq != 10 || jobs[0].PlaybackPos != live[10].Pos {
		t.Fatalf("작업 = %+v, want seq 10 · 실시간 도장 %v (%s)", jobs, live[10].Pos, b.cap.dump())
	}
}

// 닻의 보정값이 0 이 아니면(앞에서 리셋) ③ 객체만으로는 도장을 한 점으로 되짚지 못한다 — 복원 도장은 실시간과 범위 폭 안에서
// 같고, 링크는 「실제 값 아님」 이다. 같은 프로세스가 표를 들고 있으면 그 줄이 힌트가 되어 실시간 도장 그대로다.
func TestRestoreAnchorWithNonZeroOffset(t *testing.T) {
	for _, withTable := range []bool{false, true} {
		b := newRestoreBench(t)
		step := b.step()
		b.add(benchRow{seq: 20, session: benchSession, rec: recA, number: 1, dts: 600 * time.Second, playback: up, archive: up})
		b.add(benchRow{seq: 21, session: benchSession, rec: recB, number: 1, dts: 3 * time.Second, playback: up, archive: up})
		b.add(benchRow{seq: 22, session: benchSession, rec: recB, number: 2, dts: 3*time.Second + step, playback: up, archive: up})
		b.add(benchRow{seq: 23, session: benchSession, rec: recB, number: 3, dts: 3*time.Second + 2*step, archive: up})
		live := b.liveChain()
		b.uploadAt(live, 20, 21, 22)
		if live[22].Offset == 0 {
			t.Fatal("전제: seq 21 리셋으로 보정값이 0 이 아니어야 한다")
		}
		if withTable {
			b.u.sessions.recordLive(index.UploadTarget{SessionID: benchSession, Seq: 21, StitchOffset: live[21].Offset}, time.Now())
		}

		b.restore()

		jobs := b.queued()
		if len(jobs) != 1 || jobs[0].Seq != 23 {
			t.Fatalf("표 %v: 작업 = %+v, want seq 23 (%s)", withTable, jobs, b.cap.dump())
		}
		diff := jobs[0].PlaybackPos - live[23].Pos
		rl, _ := b.u.sessions.restoredLink(benchSession)
		switch {
		case withTable && (diff != 0 || !rl.exact):
			t.Errorf("표 있음: 도장 차 %v · 실제 값 %v, want 0 · 참", diff, rl.exact)
		case !withTable && (diff < -12*time.Microsecond || diff > 12*time.Microsecond || rl.exact):
			t.Errorf("표 없음: 도장 차 %v · 실제 값 %v, want |차| ≤ 12µs · 거짓", diff, rl.exact)
		}
	}
}

// 회차에 ③ uploaded 행이 하나도 없으면(회차를 열자마자 재기동) 닻 없이 회차 첫 행부터 접는다 — startChain 과 같이 첫 행 자기
// mtxi 에서 연다. 구멍 앞에 같은 회차 행(컷오프 앞)이 있는 경우는 아래 시험 둘이 본다.
func TestRestoreWithoutAnchorFoldsFromSessionStart(t *testing.T) {
	b := newRestoreBench(t)
	b.add(benchRow{seq: 109, session: "S-0", rec: recB, number: 9, dts: time.Second, playback: up, archive: up})
	b.contiguous(110, 1, 600*time.Second, pending, pending)
	live := b.liveChain()

	b.restore()

	jobs := b.queued()
	if len(jobs) != 2 || jobs[0].PlaybackPos != live[110].Pos || jobs[1].PlaybackPos != live[111].Pos || live[110].Offset != 0 {
		t.Fatalf("작업 = %+v, want seq 110 · 111 실시간 도장(첫 행 offset 0) (%s)", jobs, b.cap.dump())
	}
	if a := b.cap.one(t, "chain_restored").attrs; a["anchor_seq"] != int64(-1) || a["exact"] != true {
		t.Errorf("chain_restored = %v, want anchor_seq −1 · exact", a)
	}
}

// 회차 첫 행의 mtxi 가 음수인 회차(새 연결에서 소리가 먼저 — prod 실측 −44ms · POK-550)를 재기동 뒤 닻 없이 되살리면 첫 도장은
// 0 · 보정값 44ms 이고 다음 행이 같은 보정값을 잇는다 — 실시간 사슬(startChain)과 같은 하한이다. 잡는 결함(음성 대조):
// 하한을 실시간 사슬에만 두면 복원 작업이 음수 도장으로 나가 생산자가 ErrNegativePos 로 거부하고 첫 시도에 failed → 구멍이다.
func TestRestoreWithoutAnchorOpensNegativeFirstMtxiAtZero(t *testing.T) {
	b := newRestoreBench(t)
	b.add(benchRow{seq: 109, session: "S-0", rec: recB, number: 9, dts: time.Second, playback: up, archive: up})
	b.contiguous(110, 1, -44*time.Millisecond, pending, pending)

	b.restore()

	jobs := b.queued()
	if len(jobs) != 2 || jobs[0].Seq != 110 || jobs[1].Seq != 111 {
		t.Fatalf("작업 = %+v, want seq 110 · 111 (%s)", jobs, b.cap.dump())
	}
	want := map[int64]time.Duration{110: 0, 111: b.step()}
	for _, j := range jobs {
		if j.PosUnknown || j.PlaybackPos != want[j.Seq] {
			t.Errorf("seq %d 도장 = %v(모름 %v), want %v", j.Seq, j.PlaybackPos, j.PosUnknown, want[j.Seq])
		}
		if off, ok := b.offset(j.Seq); !ok || off != 44*time.Millisecond {
			t.Errorf("seq %d 표 = (%v, %v), want 보정값 44ms", j.Seq, off, ok)
		}
	}
}

// 닻(③ uploaded 행)이 없는 회차는 구멍 앞 같은 회차 행이 있어도(컷오프 앞 — 구멍 조회 밖) 회차 첫 행부터 접는다(설계 r6 2.10
// 「세션에 uploaded ③ 이 없으면 세션 첫 행부터 offset 0」 · c10b cx 검수 H4 재현 입력: 119 pending(컷오프 앞) → 120 pending).
// 잡는 결함: 조회를 첫 구멍 바로 앞에서 시작하고 같은 회차 행을 보면 no_anchor 로 거부해 복원이 영영 돌지 않는다.
func TestRestoreWithoutAnchorFoldsFromSessionStartBeforeCutoff(t *testing.T) {
	b := newRestoreBench(t)
	b.add(benchRow{seq: 118, session: "S-0", rec: recB, number: 9, dts: time.Second, playback: up, archive: up})
	b.add(benchRow{seq: 119, session: benchSession, rec: recA, number: 1, dts: 600 * time.Second, archive: up})
	b.ledger.beforeCutoff = map[int64]bool{119: true}
	b.contiguous(120, 2, 600*time.Second+b.step(), pending)
	live := b.liveChain()

	b.restore()

	jobs := b.queued()
	if len(jobs) != 1 || jobs[0].Seq != 120 || jobs[0].PlaybackPos != live[120].Pos {
		t.Fatalf("작업 = %+v, want seq 120 · 실시간 도장 %v (%s)", jobs, live[120].Pos, b.cap.dump())
	}
	if n := b.cap.count("chain_restore_failed"); n != 0 {
		t.Errorf("chain_restore_failed %d줄, want 0", n)
	}
}

// 닻이 mtxi 없는 고정 행이면(입력 상한 초과 · mtxi 없는 녹화기 — ③ uploaded 일 수 있다) 그 앞의 mtxi 있는 uploaded 행을 닻으로
// 삼아 고정 행을 실시간과 같은 규칙으로 다시 접고 잇는다(설계 r6 2.10 「닻이 고정 행이면 …실시간과 같다」 · c10b cx 검수 H3).
// 잡는 결함: anchor_pinned 로 끝나 뒤 구멍을 영영 복원하지 못한다.
func TestRestorePinnedAnchorContinuesFromEarlierAnchor(t *testing.T) {
	b := newRestoreBench(t)
	step := b.step()
	b.add(benchRow{seq: 150, session: benchSession, rec: recA, number: 1, dts: 600 * time.Second, playback: up, archive: up})
	b.add(benchRow{seq: 151, session: benchSession, src: withoutMtxi(t, withMtxi(t, b.raw, recA, 2, 600*time.Second+step)),
		playback: up, archive: up})
	b.contiguous(152, 3, 600*time.Second+2*step, pending, pending)
	live := b.liveChain()
	if !live[151].Pinned {
		t.Fatal("전제: seq 151 은 고정 행이어야 한다")
	}
	b.uploadAt(live, 150, 151)

	b.restore()

	jobs := b.queued()
	if len(jobs) != 2 || jobs[0].PlaybackPos != live[152].Pos || jobs[1].PlaybackPos != live[153].Pos {
		t.Fatalf("작업 = %+v, want seq 152 · 153 실시간 도장 %v · %v (%s)", jobs, live[152].Pos, live[153].Pos, b.cap.dump())
	}
}

// 따라잡기(설계 r6 2.10 「따라잡기」): 복원이 끝난 뒤 위치 모름 행이 더 들어오면 다시 깨어 지난 복원 링크에서 잇는다 — S3 를 다시
// 부르지 않는다. 그 링크 행의 장부 크기가 바뀌었으면(꼬리 교정) 그 링크를 쓰지 않고 닻 객체부터 다시 읽는다.
func TestRestoreCatchesUpFromLastRestoredLink(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(130, 1, 600*time.Second, up, pending)
	b.uploadAt(b.liveChain(), 130)
	b.restore()
	b.queued()
	gets := b.objs.getCount()
	b.contiguous(132, 3, 600*time.Second+2*b.step(), pending)
	live := b.liveChain()

	b.restore()

	jobs := b.queued()
	if len(jobs) != 1 || jobs[0].Seq != 132 || jobs[0].PlaybackPos != live[132].Pos {
		t.Fatalf("작업 = %+v, want seq 132 하나 · 실시간 도장 %v (%s)", jobs, live[132].Pos, b.cap.dump())
	}
	if b.objs.getCount() != gets {
		t.Errorf("따라잡기 GET %d → %d, want 그대로 — 지난 복원 링크에서 잇는다", gets, b.objs.getCount())
	}

	b.ledger.mu.Lock()
	r := b.ledger.rows[132]
	grown := *r.Bytes + 1
	r.Bytes = &grown
	b.ledger.rows[132] = r
	b.ledger.mu.Unlock()
	b.contiguous(133, 4, 600*time.Second+3*b.step(), pending)
	b.restore()
	if b.objs.getCount() == gets {
		t.Error("링크 행 크기가 바뀌었는데 지난 링크를 그대로 썼다 — 닻 객체부터 다시 읽어야 한다")
	}
}

// 이미 있는 객체가 현재 ② 의 재포장과 같은 바이트인데 세션 init 이 아직 확정되지 않았으면(재기동 뒤 init 확정보다 복원이 먼저)
// 채택 판정을 미룬다 — CAS 하지 않고, 그 행은 객체의 도장으로 다시 넣어 워커가 init 확정 뒤 같은 바이트로 올린다. 다음 행도 그
// 객체의 도장에서 잇는다(c10b cx 검수 H1 재현 입력: 접은 도장보다 40ms 뒤 객체 · init 미확정). 잡는 결함: init 미확정을 다시 쓰기
// 사유로 보면 접은 도장으로 같은 키를 덮어쓴다(10m4.0402s → 10m4.0002s).
func TestRestoreHoldsExistingStampUntilInitConfirmed(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(140, 1, 600*time.Second, up, pending, pending)
	live := b.liveChain()
	b.uploadAt(live, 140)
	shifted := live[141].Pos + 40*time.Millisecond
	b.setPlaybackObject(141, shifted)

	b.restore()

	jobs := b.queued()
	if len(jobs) != 2 || jobs[0].PlaybackPos != shifted || jobs[1].PlaybackPos != live[142].Pos+40*time.Millisecond {
		t.Fatalf("작업 = %+v, want seq 141 객체 도장 %v · 142 그에 이은 도장 (%s)", jobs, shifted, b.cap.dump())
	}
	if marked, _ := b.store.playbackCalls(); len(marked) != 0 {
		t.Errorf("③ CAS = %+v, want 없음 — init 미확정", marked)
	}
	if r := b.cap.one(t, "chain_object_held"); r.attrs["reason"] != "init_unconfirmed" {
		t.Errorf("chain_object_held = %v, want init_unconfirmed", r.attrs)
	}
}

// 닻 도장을 한 점으로 되짚지 못하면(보정값 0 아님 · 표 없음) 구멍 뒤 대조는 닻 범위가 허용하는 도장 범위와 객체 범위가 겹치는지로
// 본다 — 틱 단위다(c10b cx 검수 M: 범위 폭을 거리 허용치로 쓰면 10µs 바뀐 객체가 통과했다). 같은 사슬이면 통과한다.
func TestRestoreCheckIsTickExactWithInexactAnchor(t *testing.T) {
	for _, shift := range []time.Duration{0, 10 * time.Microsecond} {
		b := newRestoreBench(t)
		step := b.step()
		b.add(benchRow{seq: 20, session: benchSession, rec: recA, number: 1, dts: 600 * time.Second, playback: up, archive: up})
		b.add(benchRow{seq: 21, session: benchSession, rec: recB, number: 1, dts: 3 * time.Second, playback: up, archive: up})
		b.add(benchRow{seq: 22, session: benchSession, rec: recB, number: 2, dts: 3*time.Second + step, archive: up})
		b.add(benchRow{seq: 23, session: benchSession, rec: recB, number: 3, dts: 3*time.Second + 2*step, playback: up,
			archive: up})
		live := b.liveChain()
		b.uploadAt(live, 20, 21)
		b.setPlaybackObject(23, live[23].Pos+shift)

		b.restore()

		_, failedCalls := b.store.playbackCalls()
		if mismatched := len(failedCalls) == 1; mismatched != (shift != 0) {
			t.Errorf("객체 %v 어긋남: chain_mismatch %v, want %v (%s)", shift, mismatched, shift != 0, b.cap.dump())
		}
	}
}

// 구멍 안에서 녹화기가 바뀌면(리셋) 앞 행 ② 의 먼저 끝난 트랙 끝에 잇는다 — 실시간 chainEnd 와 같은 규칙이다(결정 F). 잡는
// 결함: 복원이 이음 끝을 장부 길이로만 잡으면 실시간과 도장이 갈린다(같은 키 다른 바이트).
func TestRestoreResetInsideHoleStitchesAtTrackEnds(t *testing.T) {
	b := newRestoreBench(t)
	step := b.step()
	b.add(benchRow{seq: 100, session: benchSession, rec: recA, number: 1, dts: 600 * time.Second, playback: up, archive: up})
	b.add(benchRow{seq: 101, session: benchSession, rec: recB, number: 1, dts: 3 * time.Second, archive: up})
	b.add(benchRow{seq: 102, session: benchSession, rec: recB, number: 2, dts: 3*time.Second + step, archive: up})
	live := b.liveChain()
	b.uploadAt(live, 100)
	if live[101].Offset == 0 {
		t.Fatal("전제: seq 101 리셋으로 보정값이 0 이 아니어야 한다")
	}

	b.restore()

	jobs := b.queued()
	if len(jobs) != 2 || jobs[0].PlaybackPos != live[101].Pos || jobs[1].PlaybackPos != live[102].Pos {
		t.Fatalf("작업 = %+v, want seq 101 · 102 실시간 도장 %v · %v (%s)", jobs, live[101].Pos, live[102].Pos, b.cap.dump())
	}
}

// 물러난 뒤에도 읽히지 않는 행은 지나간다(설계 r6 2.10 r3 — cc-P1): 본문이 잘린 비꼬리 행은 도장을 기대값에 고정한 행으로
// 접고(실시간의 mtxi 없음 행과 같은 규칙) 뒤 행은 이어서 정한다. 그 행 자체는 다시 넣지 않는다(재포장이 실패한다). 잡는 결함
// (음성 대조): 지나가기를 빼면 그 뒤 행이 모두 「모름」 이다.
func TestRestorePassesUnreadableRowAndContinues(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(30, 1, 600*time.Second, up)
	step := b.step()
	broken := withMtxi(t, b.raw, recA, 2, 600*time.Second+step)
	b.add(benchRow{seq: 31, session: benchSession, src: broken[:len(broken)/2], archive: up, nullFact: true})
	b.add(benchRow{seq: 32, session: benchSession, rec: recA, number: 3, dts: 600*time.Second + 2*step, archive: up})
	live := b.liveChain(31)
	b.uploadAt(live, 30)

	b.restore()

	jobs := b.queued()
	if len(jobs) != 1 || jobs[0].Seq != 32 || jobs[0].PlaybackPos != live[32].Pos {
		t.Fatalf("작업 = %+v, want seq 32 · 실시간 도장 %v (%s)", jobs, live[32].Pos, b.cap.dump())
	}
	if pos, ok := b.u.sessions.pinnedPos(benchSession, 31); !ok || pos != live[31].Pos {
		t.Errorf("seq 31 고정 줄 = (%v, %v), want (%v, 참)", pos, ok, live[31].Pos)
	}
	if r := b.cap.one(t, "chain_row_passed"); r.attrs["seq"] != int64(31) || r.attrs["reason"] != "body_unreadable" {
		t.Errorf("chain_row_passed = %v, want seq 31 · body_unreadable", r.attrs)
	}
}

// 꼬리인 행의 본문이 아직 안 읽히면 그 행에서 멈추고 기다린다 — 실패가 아니라 백오프도 신호도 없다(설계 r6 2.10 「꼬리인 행」).
func TestRestoreWaitsAtUnreadableTail(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(40, 1, 600*time.Second, up)
	half := withMtxi(t, b.raw, recA, 2, 600*time.Second+b.step())
	b.add(benchRow{seq: 41, session: benchSession, src: half[:len(half)/2], archive: pending})
	b.uploadAt(b.liveChain(41), 40)

	b.restore()

	if jobs := b.queued(); len(jobs) != 0 {
		t.Errorf("작업 = %+v, want 없음 — 꼬리는 물러날 때까지 기다린다", jobs)
	}
	if _, ok := b.offset(41); ok {
		t.Error("꼬리 seq 41 에 표 줄이 생겼다")
	}
	if n := b.cap.count("chain_restore_failed") + b.cap.count("chain_row_passed"); n != 0 {
		t.Errorf("실패 · 지나가기 신호 %d줄, want 0 (%s)", n, b.cap.dump())
	}
	if got := b.u.sessions.takeWakes(time.Now()); len(got) != 0 {
		t.Errorf("깨움 = %+v — 시험이 깨우지 않았다", got)
	}
}

// confirmInit 은 그 회차의 init 을 확정해 둔다(실물 조각의 재포장 init — 회차 안 어느 조각에서 만들어도 같다).
func (b *restoreBench) confirmInit() {
	out, err := playback.Remuxer{}.Produce(context.Background(), playback.Request{Input: bytes.NewReader(b.raw)})
	if err != nil {
		b.t.Fatal(err)
	}
	b.u.sessions.confirmInit(benchSession, out.InitSHA256[:], time.Now())
}

// 구멍 행의 ③ 키에 이미 객체가 있고(PUT 뒤 CAS 전 멈춤) 그 객체가 현재 ② 를 다른 도장(c0 의 일시 실패 고정 — 40ms 뒤)으로
// 재포장한 것과 같은 바이트면 채택한다: 다시 쓰지 않고 ③ CAS 만 하고(크기 = 객체 길이), 다음 행은 그 객체의 도장에서 잇는다
// (설계 r6 2.10 r2 — cx-N2 · r3 — cx-N1). 잡는 결함: 같은 키에 다른 바이트를 쓴다 · 채택한 객체와 다음 행 사이에 틈이 남는다.
func TestRestoreAdoptsExistingObjectWithSameSamples(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(50, 1, 600*time.Second, up, pending, pending)
	live := b.liveChain()
	b.uploadAt(live, 50)
	b.confirmInit()
	shifted := live[51].Pos + 40*time.Millisecond
	b.setPlaybackObject(51, shifted)

	b.restore()

	jobs := b.queued()
	if len(jobs) != 1 || jobs[0].Seq != 52 || jobs[0].PlaybackPos != live[52].Pos+40*time.Millisecond {
		t.Fatalf("작업 = %+v, want seq 52 하나 · 채택한 도장에서 이은 %v (%s)", jobs, live[52].Pos+40*time.Millisecond, b.cap.dump())
	}
	marked, _ := b.store.playbackCalls()
	if len(marked) != 1 || marked[0].seq != 51 || marked[0].bytes != int64(len(b.produce(51, shifted))) {
		t.Errorf("③ CAS = %+v, want seq 51 · 객체 길이", marked)
	}
	if len(b.objs.putKeys) != 0 {
		t.Errorf("PUT = %v, want 0 — 채택은 다시 쓰지 않는다", b.objs.putKeys)
	}
}

// 이미 있는 객체가 현재 ② 의 재포장이 아니면(다른 판본의 입력 — 표본 열이 다르다) 채택하지 않는다 — 접은 도장으로 다시 쓴다
// (CAS 전이라 어느 목록에도 없다). 잡는 결함: 객체가 있다는 것만으로 확정하면 자라기 전 입력으로 만든 객체가 굳는다.
func TestRestoreRewritesExistingObjectFromOtherInput(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(50, 1, 600*time.Second, up, pending, pending)
	live := b.liveChain()
	b.uploadAt(live, 50)
	b.confirmInit()
	tail, err := os.ReadFile("../playback/testdata/segment_tail_2s.mp4")
	if err != nil {
		t.Fatal(err)
	}
	other, err := playback.Remuxer{}.Produce(context.Background(), playback.Request{Input: bytes.NewReader(tail), PlaybackPos: live[51].Pos})
	if err != nil {
		t.Fatal(err)
	}
	b.objs.set(segKeyOf(t, benchStream, 51), other.Seg)

	b.restore()

	jobs := b.queued()
	if len(jobs) != 2 || jobs[0].Seq != 51 || jobs[0].PlaybackPos != live[51].Pos {
		t.Fatalf("작업 = %+v, want seq 51(접은 도장) · 52 (%s)", jobs, b.cap.dump())
	}
	if marked, _ := b.store.playbackCalls(); len(marked) != 0 {
		t.Errorf("③ CAS = %+v, want 없음", marked)
	}
	if r := b.cap.one(t, "chain_object_rewritten"); r.attrs["reason"] != "samples_differ" {
		t.Errorf("chain_object_rewritten = %v, want samples_differ", r.attrs)
	}
}

// 구멍 뒤 uploaded 행 n 의 객체 도장이 되살린 사슬과 다르면(옛 이미지의 고정으로 n 이 이미 다른 도장에서 이어졌다) 그 구멍은
// 만들지 않는다 — failed(chain_mismatch) · 키 백오프 · 표 줄 없음 · 신호 한 줄. 같은 입력으로 다시 깨도 S3 를 다시 부르지
// 않는다(설계 r6 2.10 「구멍 뒤 uploaded 행 대조」 · r3 cc-P2).
func TestRestoreMismatchAfterHoleFailsHoleOnce(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(60, 1, 600*time.Second, up, pending, up)
	live := b.liveChain()
	b.uploadAt(live, 60)
	b.setPlaybackObject(62, live[62].Pos+20*time.Millisecond)

	b.restore()
	gets := b.objs.getCount()
	b.restore()

	if jobs := b.queued(); len(jobs) != 0 {
		t.Errorf("작업 = %+v, want 없음 — 불일치 구멍은 만들지 않는다", jobs)
	}
	_, failedCalls := b.store.playbackCalls()
	if len(failedCalls) != 1 || failedCalls[0].seq != 61 || failedCalls[0].reason != reasonChainMismatch {
		t.Errorf("③ failed = %+v, want seq 61 · chain_mismatch 한 번", failedCalls)
	}
	if _, ok := b.offset(61); ok {
		t.Error("불일치 구멍 seq 61 에 표 줄이 생겼다")
	}
	if b.objs.getCount() != gets || b.cap.count("chain_mismatch") != 1 {
		t.Errorf("두 번째 복원 GET %d → %d · 신호 %d줄, want GET 그대로 · 신호 1줄", gets, b.objs.getCount(), b.cap.count("chain_mismatch"))
	}
	key := targetKeyOf(index.UploadTarget{StreamID: benchStream, Axis: index.AxisPlayback, Seq: 61})
	if _, blocked := b.u.gate.backoffBlocked(key); !blocked {
		t.Error("seq 61 키에 백오프가 없다 — 스위퍼가 30초마다 집는다")
	}
}

// 구멍 뒤 uploaded 행이 같은 사슬이면 대조를 지나 구멍을 만든다(위 시험의 짝 — 대조를 늘 실패로 보는 꼴을 막는다).
func TestRestoreMatchingRowAfterHolePasses(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(60, 1, 600*time.Second, up, pending, up)
	live := b.liveChain()
	b.uploadAt(live, 60, 62)

	b.restore()

	if jobs := b.queued(); len(jobs) != 1 || jobs[0].Seq != 61 {
		t.Errorf("작업 = %+v, want seq 61 (%s)", jobs, b.cap.dump())
	}
}

// S3 가 접근을 거부하면(일시 실패) 행 상태를 바꾸지 않고 세션 단위 백오프로 다시 본다 — 신호 chain_restore_failed(s3_get).
// 표 · 작업 · 장부 쓰기가 없다.
func TestRestoreS3FailureBacksOffWithoutTouchingRows(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(70, 1, 600*time.Second, up, pending)
	b.uploadAt(b.liveChain(), 70)
	b.objs.getErr = errors.New("AccessDenied")

	b.restore()

	if jobs := b.queued(); len(jobs) != 0 {
		t.Errorf("작업 = %+v, want 없음", jobs)
	}
	marked, failedCalls := b.store.playbackCalls()
	if len(marked)+len(failedCalls) != 0 {
		t.Errorf("③ 장부 쓰기 %+v · %+v, want 없음", marked, failedCalls)
	}
	if r := b.cap.one(t, "chain_restore_failed"); r.attrs["reason"] != "s3_get" {
		t.Errorf("chain_restore_failed = %v, want s3_get", r.attrs)
	}
	b.u.sessions.wake(benchStream, benchSession)
	if got := b.u.sessions.takeWakes(b.u.now()); len(got) != 0 {
		t.Errorf("백오프 안 깨움 = %+v, want 없음", got)
	}
}

// chain_restored 는 구멍 뒤 대조를 했는가(compared)를 싣는다(c10b 보안 보충 M). 구멍 뒤 uploaded 행이 있으면 참이고, 구멍 뒤
// 32행 안에 같은 회차 uploaded 행이 없으면 거짓이다 — 대조 없이 굳은 도장을 운영 알림에서 가를 재료다.
func TestRestoreSignalsWhetherCheckedAfterHole(t *testing.T) {
	tests := []struct {
		name   string
		states []index.UploadState
		want   bool
	}{
		{"구멍 뒤 uploaded 행 대조", []index.UploadState{up, pending, up}, true},
		{"구멍 뒤 대조 행 없음", []index.UploadState{up, pending}, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			b := newRestoreBench(t)
			b.contiguous(60, 1, 600*time.Second, tt.states...)
			live := b.liveChain()
			for i, st := range tt.states {
				if st == up {
					b.uploadAt(live, 60+int64(i))
				}
			}

			b.restore()

			if got := b.cap.one(t, "chain_restored").attrs["compared"]; got != tt.want {
				t.Errorf("chain_restored compared = %v, want %v (%s)", got, tt.want, b.cap.dump())
			}
		})
	}
}

// ② 객체(로컬이 지워진 행의 원천)는 장부 크기만큼만 쓴다 — 로컬 원천과 같은 규칙이다(c10b cc 보충 M · 보안 L). 객체가 장부보다
// 길면 장부 크기로 자르고, 짧으면 원천 크기 어긋남(source_size_mismatch)으로 그 행을 지나간다. 잡는 결함: 길이를 보지 않으면
// 긴 객체는 꼬리 쓰레기 때문에 닻을 못 읽고(anchor_unreadable), 파트 경계에서 잘린 짧은 객체는 읽혀 틀린 트랙 끝을 낸다.
func TestRestoreArchiveObjectFollowsLedgerSize(t *testing.T) {
	t.Run("긴 객체는 장부 크기로 자른다", func(t *testing.T) {
		b := newRestoreBench(t)
		b.contiguous(8, 1, 600*time.Second, up, up, pending)
		live := b.liveChain()
		b.uploadAt(live, 8, 9)
		row := b.ledger.rows[9]
		if err := os.Remove(row.LocalPath); err != nil {
			t.Fatal(err)
		}
		b.objs.set(row.S3Key, append(bytes.Clone(b.srcs[9]), make([]byte, 64)...))

		b.restore()

		if jobs := b.queued(); len(jobs) != 1 || jobs[0].Seq != 10 || jobs[0].PlaybackPos != live[10].Pos {
			t.Fatalf("작업 = %+v, want seq 10 · 실시간 도장 %v (%s)", jobs, live[10].Pos, b.cap.dump())
		}
	})
	t.Run("짧은 객체는 지나간다", func(t *testing.T) {
		b := newRestoreBench(t)
		b.contiguous(30, 1, 600*time.Second, up, pending, pending)
		live := b.liveChain()
		b.uploadAt(live, 30)
		row := b.ledger.rows[31]
		if err := os.Remove(row.LocalPath); err != nil {
			t.Fatal(err)
		}
		b.ledger.mu.Lock()
		row.UploadState = up
		b.ledger.rows[31] = row
		b.ledger.mu.Unlock()
		b.objs.set(row.S3Key, b.srcs[31][:len(b.srcs[31])-1000])

		b.restore()

		if r := b.cap.one(t, "chain_row_passed"); r.attrs["seq"] != int64(31) || r.attrs["reason"] != "source_size_mismatch" {
			t.Errorf("chain_row_passed = %v, want seq 31 · source_size_mismatch", r.attrs)
		}
	})
}

// 진전 없이 끝난 복원(되살린 링크 0 — 꼬리에서 기다림 · 구멍 없음)은 다음 깨움까지 최소 간격(SweepEvery)을 둔다(c10b 보안 M).
// 진전이 있으면 바로 다시 깬다. 잡는 결함: 성공이라 백오프를 풀면 위치 모름 요청(조각마다)이 같은 구멍을 매번 다시 읽는다 —
// 장부 조회 · 닻 GET · ② 재읽기.
func TestRestoreWithoutProgressWaitsBeforeNextWake(t *testing.T) {
	t.Run("꼬리에서 기다림", func(t *testing.T) {
		b := newRestoreBench(t)
		b.contiguous(40, 1, 600*time.Second, up)
		half := withMtxi(t, b.raw, recA, 2, 600*time.Second+b.step())
		b.add(benchRow{seq: 41, session: benchSession, src: half[:len(half)/2], archive: pending})
		b.uploadAt(b.liveChain(41), 40)

		b.restore()
		b.u.sessions.wake(benchStream, benchSession)

		if got := b.u.sessions.takeWakes(b.u.now()); len(got) != 0 {
			t.Errorf("진전 없는 복원 바로 뒤 깨움 = %+v, want 없음", got)
		}
		b.u.sessions.wake(benchStream, benchSession)
		if got := b.u.sessions.takeWakes(b.u.now().Add(b.u.opt.SweepEvery)); len(got) != 1 {
			t.Errorf("최소 간격 뒤 깨움 = %+v, want 하나", got)
		}
		if n := b.cap.count("chain_restore_failed"); n != 0 {
			t.Errorf("chain_restore_failed = %d줄, want 0 — 실패가 아니다", n)
		}
	})
	t.Run("진전 있음", func(t *testing.T) {
		b := newRestoreBench(t)
		b.contiguous(8, 1, 600*time.Second, up, pending)
		b.uploadAt(b.liveChain(), 8)

		b.restore()
		b.u.sessions.wake(benchStream, benchSession)

		if got := b.u.sessions.takeWakes(b.u.now()); len(got) != 1 {
			t.Errorf("진전 있는 복원 뒤 깨움 = %+v, want 하나", got)
		}
	})
}

// 한 무리의 결정적 실패(여기 닻의 ② 원천 없음)는 같은 회차의 뒤 무리를 막지 않는다(c10b 보안 M) — 뒤 무리는 되살리고, 회차는
// 앞 무리의 까닭으로 백오프한다. 잡는 결함: 첫 오류에서 돌아가면 뒤 무리가 백오프 상한까지 커지는 동안 영영 안 살아난다.
func TestRestoreDeterministicGroupFailureDoesNotBlockLaterGroups(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(8, 1, 600*time.Second, up, pending, up, pending)
	live := b.liveChain()
	b.uploadAt(live, 8, 10)
	row := b.ledger.rows[8]
	if err := os.Remove(row.LocalPath); err != nil {
		t.Fatal(err)
	}
	b.ledger.mu.Lock()
	row.UploadState = pending
	b.ledger.rows[8] = row
	b.ledger.mu.Unlock()

	b.restore()

	if jobs := b.queued(); len(jobs) != 1 || jobs[0].Seq != 11 || jobs[0].PlaybackPos != live[11].Pos {
		t.Errorf("작업 = %+v, want 뒤 무리 seq 11 · 실시간 도장 (%s)", jobs, b.cap.dump())
	}
	if r := b.cap.one(t, "chain_restore_failed"); r.attrs["reason"] != "anchor_source_missing" {
		t.Errorf("chain_restore_failed = %v, want anchor_source_missing", r.attrs)
	}
}

// 일시 실패(장부 · S3 · 로컬 읽기)는 뒤 무리로 넘어가지 않고 바로 백오프한다 — 장애 동안 뒤 무리마다 같은 실패를 되풀이하지 않는다.
func TestRestoreTransientGroupFailureStopsPass(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(8, 1, 600*time.Second, up, pending, up, pending)
	b.uploadAt(b.liveChain(), 8, 10)
	for _, seq := range []int64{8, 10} {
		if err := os.Remove(b.ledger.rows[seq].LocalPath); err != nil {
			t.Fatal(err)
		}
	}
	b.objs.getErr = errors.New("AccessDenied")

	b.restore()

	if jobs := b.queued(); len(jobs) != 0 {
		t.Errorf("작업 = %+v, want 없음", jobs)
	}
	if gets := b.objs.getCount(); gets != 1 {
		t.Errorf("S3 GET %d번, want 1 — 첫 무리 일시 실패에서 멈춘다", gets)
	}
}
