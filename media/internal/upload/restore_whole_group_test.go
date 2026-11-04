package upload

import (
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 복원은 구멍 무리 하나를 한 번에 끝까지 접고, 구멍 뒤 대조를 마친 뒤에만 다시 넣는다. 접기 시작점은 회차 시작(또는 mtxi 있는
// 닻)까지 뒤로 읽어 정한다. 메모리는 ② 본문 하나만 쥐어 묶인다(TestRestoreLongHoleKeepsMemoryBounded) — 행 메타데이터는 행당
// 수백 바이트라 무리 전체를 들어도 된다. c10b 확인 패스 H 셋(cx H-1 · H-2 · cc H-A)이 판을 나누고 스스로 다시 깨우던 판에서 나왔다.

// 긴 무리(150행)도 한 번의 복원으로 전부 다시 들어가고 스스로 깨우지 않는다.
func TestRestoreLongGroupInOnePassWithoutSelfWake(t *testing.T) {
	b := newRestoreBench(t)
	n := int64(150)
	states := []index.UploadState{up}
	for range n {
		states = append(states, pending)
	}
	b.contiguous(0, 1, 600*time.Second, states...)
	live := b.liveChain()
	b.uploadAt(live, 0)

	b.restore()

	jobs := b.queued()
	if int64(len(jobs)) != n || jobs[n-1].PlaybackPos != live[n].Pos {
		t.Fatalf("작업 %d개, want %d개 · 마지막 실시간 도장 (%s)", len(jobs), n, b.cap.dump())
	}
	if w := b.u.sessions.takeWakes(time.Now()); len(w) != 0 {
		t.Errorf("스스로 깨움 = %+v, want 없음", w)
	}
}

// 긴 구멍 뒤 uploaded 행이 다른 사슬이면(옛 이미지 고정 — 40ms 뒤) 구멍을 한 행도 만들지 않는다(c10b 확인 패스 cx H-1 재현 입력:
// 0 uploaded → 1–100 pending → 101 uploaded +40ms). 잡는 결함: 앞 64행을 먼저 올린 뒤 다음 판에서 불일치를 알아, 틀린 64행이
// uploaded 로 남는다.
func TestRestoreLongHoleMismatchUploadsNothing(t *testing.T) {
	b := newRestoreBench(t)
	states := []index.UploadState{up}
	for range 100 {
		states = append(states, pending)
	}
	states = append(states, up)
	b.contiguous(0, 1, 600*time.Second, states...)
	live := b.liveChain()
	b.uploadAt(live, 0)
	b.setPlaybackObject(101, live[101].Pos+40*time.Millisecond)

	for range 3 {
		b.restore()
	}

	if jobs := b.queued(); len(jobs) != 0 {
		t.Errorf("다시 넣은 작업 %d개, want 0 — 불일치 구멍은 한 행도 만들지 않는다", len(jobs))
	}
	if _, failedCalls := b.store.playbackCalls(); len(failedCalls) != 100 {
		t.Errorf("③ failed %d행, want 100(chain_mismatch)", len(failedCalls))
	}
}

// 앞 무리가 뒤 무리의 복원 링크에 밀려 굶지 않는다(c10b 확인 패스 cx H-2 재현 입력: 0 uploaded → 1–64 원천 부재 → 65–138 복구
// 가능 → 139 uploaded → 140 pending). 한 번의 복원으로 65–138 · 140 이 모두 다시 들어가고(1–64 는 지나감) 스스로 깨우지 않는다.
func TestRestoreFrontGroupNotStarvedByLaterGroup(t *testing.T) {
	b := newRestoreBench(t)
	step := b.step()
	b.contiguous(0, 1, 600*time.Second, up)
	var passed []int64
	for i := int64(1); i <= 64; i++ {
		b.add(benchRow{seq: i, session: benchSession, rec: recA, number: uint64(i + 1),
			dts: 600*time.Second + time.Duration(i)*step, noLocal: true})
		passed = append(passed, i)
	}
	var states []index.UploadState
	for range 74 {
		states = append(states, pending)
	}
	states = append(states, up, pending)
	b.contiguous(65, 66, 600*time.Second+65*step, states...)
	live := b.liveChain(passed...)
	b.uploadAt(live, 0, 139)

	b.restore()

	jobs := b.queued()
	right := map[int64]bool{}
	for _, j := range jobs {
		right[j.Seq] = j.PlaybackPos == live[j.Seq].Pos
	}
	for seq := int64(65); seq <= 140; seq++ {
		if seq != 139 && !right[seq] {
			t.Errorf("seq %d 작업이 없거나 도장이 실시간과 다르다", seq)
		}
	}
	if len(jobs) != 75 {
		t.Errorf("작업 %d개, want 75(65–138 · 140) (%s)", len(jobs), b.cap.dump())
	}
	if w := b.u.sessions.takeWakes(time.Now()); len(w) != 0 {
		t.Errorf("스스로 깨움 = %+v, want 없음", w)
	}
}

// 회차 시작이 뒤로 멀어도(컷오프 앞 같은 회차 행 65개) 회차 첫 행까지 읽어 닻 없이 접는다(c10b 확인 패스 cx H-2 의 H4 경계 재현
// 입력: 0–64 pending 컷오프 앞 → 65 pending). 잡는 결함: 고정 창 끝에서 no_anchor · 판 상한으로 작업 없이 되풀이한다.
func TestRestoreWithoutAnchorFoldsFromFarSessionStart(t *testing.T) {
	b := newRestoreBench(t)
	cutoff := map[int64]bool{}
	var states []index.UploadState
	for i := range int64(66) {
		if i < 65 {
			cutoff[i] = true
		}
		states = append(states, pending)
	}
	b.contiguous(0, 1, 600*time.Second, states...)
	b.ledger.beforeCutoff = cutoff
	live := b.liveChain()

	b.restore()

	if jobs := b.queued(); len(jobs) != 1 || jobs[0].Seq != 65 || jobs[0].PlaybackPos != live[65].Pos {
		t.Fatalf("작업 = %+v, want seq 65 · 실시간 도장 (%s)", jobs, b.cap.dump())
	}
	if w := b.u.sessions.takeWakes(time.Now()); len(w) != 0 {
		t.Errorf("스스로 깨움 = %+v, want 없음", w)
	}
}

// 회차 시작이 뒤로 한 쪽(restoreBackPage 행)보다 멀면 다음 쪽을 이어 읽어 회차 첫 행(앞 회차 행 바로 뒤)을 찾는다. 잡는 결함:
// 한 쪽만 읽고 장부 첫 행부터 접으면 앞 회차 행을 만나 foreign_session 으로 끝난다.
func TestRestoreWithoutAnchorFoldsFromSessionStartBeyondOnePage(t *testing.T) {
	b := newRestoreBench(t)
	for i := range int64(10) {
		b.add(benchRow{seq: i, session: "S-0", rec: recB, number: uint64(i + 1), dts: time.Duration(i+1) * time.Second,
			playback: up, archive: up})
	}
	far := int64(restoreBackPage + 44)
	cutoff := map[int64]bool{}
	var states []index.UploadState
	for i := range far + 1 {
		if i < far {
			cutoff[10+i] = true
		}
		states = append(states, pending)
	}
	b.contiguous(10, 1, 600*time.Second, states...)
	b.ledger.beforeCutoff = cutoff
	live := b.liveChain()

	b.restore()

	last := 10 + far
	if jobs := b.queued(); len(jobs) != 1 || jobs[0].Seq != last || jobs[0].PlaybackPos != live[last].Pos {
		t.Fatalf("작업 = %+v, want seq %d · 실시간 도장 (%s)", jobs, last, b.cap.dump())
	}
}

// mtxi 닻 뒤에 고정 uploaded 행이 길게(64 · 65 · 200행) 이어져도 그 닻에서 고정 행을 다시 접어 구멍까지 닿는다(c10b 확인 패스 cc
// H-A 재현 입력 n = 64 · 65). 잡는 결함: 뒤로 읽는 창 · 판 상한이 구멍에 못 닿아 같은 닻 GET 을 끝없이 되풀이한다.
func TestRestorePinnedRunOfAnyLengthReachesHoles(t *testing.T) {
	for _, n := range []int64{64, 65, 200} {
		b := newRestoreBench(t)
		step := b.step()
		b.contiguous(0, 1, 600*time.Second, up)
		for i := int64(1); i <= n; i++ {
			src := withoutMtxi(t, withMtxi(t, b.raw, recA, uint64(i+1), 600*time.Second+time.Duration(i)*step))
			b.add(benchRow{seq: i, session: benchSession, src: src, playback: up, archive: up})
		}
		b.contiguous(n+1, uint64(n+2), 600*time.Second+time.Duration(n+1)*step, pending, pending)
		live := b.liveChain()
		for i := int64(0); i <= n; i++ {
			b.uploadAt(live, i)
		}

		b.restore()

		jobs := b.queued()
		if len(jobs) != 2 || jobs[0].PlaybackPos != live[n+1].Pos || jobs[1].PlaybackPos != live[n+2].Pos {
			t.Errorf("고정 행 %d개: 작업 %d개, want 구멍 둘 · 실시간 도장", n, len(jobs))
		}
		if w := b.u.sessions.takeWakes(time.Now()); len(w) != 0 {
			t.Errorf("고정 행 %d개: 스스로 깨움 = %+v, want 없음", n, w)
		}
	}
}
