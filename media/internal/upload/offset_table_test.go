package upload

import (
	"context"
	"fmt"
	"io/fs"
	"os"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 회차별 보정값 표(계획 4.2-R R3 「보정값 표」·「스위퍼 재생성」)를 잰다. 표는 인덱서의 실시간 ③
// 요청(RequestUpload)만 쓰고, 스위퍼 작업만 읽는다 — 장부에 도장 위치가 없는 스위퍼 작업이
// 실시간과 같은 도장을 다시 만드는 유일한 수단이다.

const stitch = 5 * time.Second // 회차 안 리셋·구멍을 이어 붙인 보정값(예시)

// liveRequest 는 인덱서가 보내는 실시간 ③ 요청 하나다 — 도장 메타(보정값·고정)를 싣는다.
func liveRequest(t *testing.T, seq int64, sessionID string, offset time.Duration) index.UploadTarget {
	t.Helper()
	tgt := playbackTarget(t, "demo", seq, sessionID, "/recordings/demo/live.mp4", 64)
	tgt.StitchOffset = offset
	return tgt
}

// request 는 실시간 요청을 접수에 넘긴다. 접수된 작업 자체는 이 파일이 재는 것이 아니므로
// 큐에서 버린다 — 표에 남은 줄만 본다.
func request(u *Uploader, tgt index.UploadTarget) bool {
	ok := u.RequestUpload(tgt)
	u.drainQueue()
	return ok
}

// sweepTarget 은 스위퍼 조회가 만드는 ③ 작업이다 — 도장 위치가 없고(0) 세션 확정 해시를 싣는다.
func sweepTarget(t *testing.T, dir string, seq int64, sessionID string) index.UploadTarget {
	t.Helper()
	path, size := copyFixture(t, dir, "demo", fmt.Sprintf("seg%d.mp4", seq))
	tgt := playbackTarget(t, "demo", seq, sessionID, path, size)
	tgt.ExpectedInitSHA = fakeInitSHA()
	return tgt
}

// lastPos 는 가장 최근 재포장의 도장 위치다.
func lastPos(t *testing.T, prod *fakeProducer) time.Duration {
	t.Helper()
	calls := prod.produced()
	if len(calls) == 0 {
		t.Fatal("재포장이 한 번도 없었다")
	}
	return calls[len(calls)-1].pos
}

// assertRefused 는 스위퍼 작업이 도장을 합성하지 않고 거부됐는지 본다 — 재포장도 PUT 도 없이
// ③ failed(GAP)로 적히고 보류되지 않는다.
func assertRefused(t *testing.T, u *Uploader, st *fakeUploadStore, prod *fakeProducer, put *fakePutter, tgt index.UploadTarget) {
	t.Helper()
	producedBefore, putBefore := len(prod.produced()), len(put.putCalls())
	_, failedBefore := st.playbackCalls()
	if got := runTarget(t, u, tgt); got != outcomeNeutral {
		t.Errorf("seq %d outcome = %v, want neutral", tgt.Seq, got)
	}
	if len(prod.produced()) != producedBefore || len(put.putCalls()) != putBefore {
		t.Fatalf("seq %d 가 재포장·PUT 됐다 — 표가 없으면 도장을 합성하지 않는다", tgt.Seq)
	}
	_, failed := st.playbackCalls()
	if len(failed) != len(failedBefore)+1 || failed[len(failed)-1].seq != tgt.Seq || failed[len(failed)-1].reason == index.ReasonInitMismatch {
		t.Errorf("③ failed = %+v, want seq %d 1회 추가(세션을 끝내지 않는 사유)", failed, tgt.Seq)
	}
}

// 스위퍼 작업의 도장 = 그 조각의 mtxi + 회차 표에서 seq_from ≤ k 인 가장 큰 줄의 보정값이다
// (계획 뮤테이션 45). 잡는 결함: 표를 안 보고 보정값 0 을 쓰면 구멍·리셋 뒤 조각의 재생성
// 도장이 실시간과 어긋나 목록에서 역행·겹침이 난다.
func TestSweeperUsesSessionOffsetTable(t *testing.T) {
	st := &fakeUploadStore{}
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, st, &fakePutter{}, prod, nil)
	request(u, liveRequest(t, 10, "S-1", 0))
	request(u, liveRequest(t, 12, "S-1", stitch)) // 12 에서 이어 붙였다

	if got := runTarget(t, u, sweepTarget(t, dir, 13, "S-1")); got != outcomeSuccess {
		t.Fatalf("seq 13 outcome = %v (%s)", got, cap.dump())
	}
	if got := lastPos(t, prod); got != fixture4sMtxi+stitch {
		t.Errorf("seq 13 도장 = %v, want mtxi + 5s = %v", got, fixture4sMtxi+stitch)
	}
	if got := runTarget(t, u, sweepTarget(t, dir, 11, "S-1")); got != outcomeSuccess {
		t.Fatalf("seq 11 outcome = %v (%s)", got, cap.dump())
	}
	if got := lastPos(t, prod); got != fixture4sMtxi {
		t.Errorf("seq 11 도장 = %v, want mtxi + 0 = %v — 11 은 {10→0} 줄 구간이다", got, fixture4sMtxi)
	}
}

// 표가 없으면 스위퍼는 도장을 합성하지 않고 거부한다 — ③ failed(GAP), 보류하지 않는다(계획
// 뮤테이션 41 · 결정 B′: 재기동 복구는 범위 밖). 잡는 결함: 표 부재에 보정값 0 으로 재생성하면
// 리셋 뒤 구간의 도장이 뒤로 가 되감기가 그 지점에서 깨진다.
func TestSweeperRefusesWithoutTableAfterReset(t *testing.T) {
	st := &fakeUploadStore{}
	prod := &fakeProducer{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil) // 재기동 직후 — 표가 비었다
	tgt := sweepTarget(t, dir, 13, "S-1")
	tgt.ExpectedInitSHA = nil // 세션 init 미확정이어도 보류가 아니라 거부다 — 표 판정이 먼저다

	assertRefused(t, u, st, prod, put, tgt)
	rec := cap.one(t, "upload_failed")
	if rec.attrs["reason"] != "no_offset_table" {
		t.Errorf("upload_failed reason = %v, want no_offset_table", rec.attrs["reason"])
	}
	if _, blocked := u.gate.backoffBlocked(targetKeyOf(tgt)); !blocked {
		t.Error("백오프가 등록되지 않았다 — 재수집마다 같은 거부를 되풀이한다")
	}
	// 거부한 작업은 보류 목록에 없다 — init 이 확정돼도 다시 들지 않는다.
	runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
	if n := len(u.queue); n != 0 {
		t.Errorf("init 확정 뒤 재요청 = %d건, want 0건 — 표 판정이 보류 판정보다 앞이다", n)
	}
}

// 세션 종료 관측(ForgetSession) 뒤에도 표는 남는다 — 정산 창(ending)의 스위퍼 재시도가 같은
// 보정값을 써야 한다(계획 뮤테이션 46). 잡는 결함: 종료와 함께 표를 버리면 마지막 조각들의
// 재생성 도장이 보정값 0 으로 찍힌다.
func TestSweeperUsesOffsetAfterSessionEnding(t *testing.T) {
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, prod, nil)
	request(u, liveRequest(t, 10, "S-1", 0))
	request(u, liveRequest(t, 12, "S-1", stitch))

	u.ForgetSession("S-1")

	if got := runTarget(t, u, sweepTarget(t, dir, 13, "S-1")); got != outcomeSuccess {
		t.Fatalf("outcome = %v (%s)", got, cap.dump())
	}
	if got := lastPos(t, prod); got != fixture4sMtxi+stitch {
		t.Errorf("도장 = %v, want mtxi + 5s — 종료 관측 뒤에도 같은 보정값", got)
	}
}

// 접수가 거부돼도(브레이커·백오프·격리·큐 포화) 그 요청의 줄은 표에 남는다(계획 뮤테이션 52 ·
// upload.go RequestUpload 주석). 잡는 결함: 기록을 접수 뒤로 옮기면 브레이커가 열린 동안의
// 이어 붙임 줄이 빠져, 그 조각들의 스위퍼 재생성이 옛 보정값으로 찍힌다.
func TestRejectedRequestStillRecordsOffsetRow(t *testing.T) {
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, prod, nil)
	request(u, liveRequest(t, 10, "S-1", 0))
	rejected := liveRequest(t, 12, "S-1", stitch)
	u.gate.registerFailure(targetKeyOf(rejected)) // 그 조각은 백오프 중이다

	if request(u, rejected) {
		t.Fatal("준비 실패: 백오프 중인 요청이 접수됐다")
	}
	if got := runTarget(t, u, sweepTarget(t, dir, 13, "S-1")); got != outcomeSuccess {
		t.Fatalf("outcome = %v (%s)", got, cap.dump())
	}
	if got := lastPos(t, prod); got != fixture4sMtxi+stitch {
		t.Errorf("도장 = %v, want mtxi + 5s — 거부된 요청의 줄도 표에 있어야 한다", got)
	}
}

// 표는 그 회차의 **첫 실시간 ③ 요청**이 만든다 — 이어 붙인 적 없는 회차에도 첫 줄 {k → 0} 이
// 있다(계획 뮤테이션 53). 첫 행을 못 읽으면 인덱서가 ③ 요청을 내지 않으므로 표가 없다.
func TestTableStartsAtFirstLivePlaybackRequest(t *testing.T) {
	st := &fakeUploadStore{}
	prod := &fakeProducer{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
	request(u, liveRequest(t, 10, "S-1", 0)) // 이어 붙인 적 없는 회차

	if got := runTarget(t, u, sweepTarget(t, dir, 11, "S-1")); got != outcomeSuccess {
		t.Fatalf("구멍 없는 회차의 재생성 outcome = %v, want success — 거부되면 GAP 이다 (%s)", got, cap.dump())
	}
	if got := lastPos(t, prod); got != fixture4sMtxi {
		t.Errorf("도장 = %v, want mtxi + 0", got)
	}

	// S-2 는 첫 행을 못 읽어 ③ 요청이 한 번도 없었다 — 표가 없다.
	assertRefused(t, u, st, prod, put, sweepTarget(t, dir, 20, "S-2"))
}

// 표는 요청이 온 seq 부터다 — 재기동 뒤 첫 판독 행(k=20)이 표를 만들면 그 앞 seq 의 재생성은
// 거부된다(계획 뮤테이션 58). 잡는 결함: 표를 소급해서(seq_from = 0) 만들면 재기동 전 구간의
// 도장을 모르는 채로 보정값 0 을 써 역행 도장을 낸다.
func TestRestartTableStartsAtFirstReadableRow(t *testing.T) {
	st := &fakeUploadStore{}
	prod := &fakeProducer{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
	request(u, liveRequest(t, 20, "S-1", 0))

	assertRefused(t, u, st, prod, put, sweepTarget(t, dir, 15, "S-1"))
	if got := runTarget(t, u, sweepTarget(t, dir, 21, "S-1")); got != outcomeSuccess {
		t.Fatalf("seq 21 outcome = %v (%s)", got, cap.dump())
	}
}

// 판독에 실패해 고정한 조각은 표의 고정 줄이 먼저다 — mtxi 를 읽지 않고 그 위치를 그대로 쓴다
// (계획 뮤테이션 56). 여기 입력은 mtxi 가 없는 파일이다. 잡는 결함: 고정 줄을 무시하면 판독이
// 실패해 재생성이 영영 안 되거나(여기), 읽히더라도 다음 조각과 겹치는 도장을 낸다.
func TestSweeperUsesPinnedPosForUnreadableSegment(t *testing.T) {
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, prod, nil)
	request(u, liveRequest(t, 10, "S-1", 0))
	pinned := liveRequest(t, 11, "S-1", 0)
	pinned.PosPinned, pinned.PlaybackPos = true, 7*time.Second
	request(u, pinned)

	tgt := playbackTarget(t, "demo", 11, "S-1", writeSegment(t, dir, "demo", "no-mtxi.mp4", 64), 64)
	tgt.ExpectedInitSHA = fakeInitSHA()
	if got := runTarget(t, u, tgt); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success (%s)", got, cap.dump())
	}
	if got := lastPos(t, prod); got != 7*time.Second {
		t.Errorf("도장 = %v, want 고정 7s", got)
	}
}

// 보류된 스위퍼 작업은 **표로 확정한 위치를 실은 채** 다시 든다(계획 뮤테이션 61). 잡는 결함:
// 위치를 정하기 전에 보류하면 실시간 경로로 다시 들 때 위치가 0 이라 도장이 역행한다.
func TestHeldSweeperJobRequeuesWithTablePos(t *testing.T) {
	st := &fakeUploadStore{}
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, st, &fakePutter{}, prod, nil)
	request(u, liveRequest(t, 10, "S-1", 0))
	request(u, liveRequest(t, 12, "S-1", stitch))
	tgt := sweepTarget(t, dir, 13, "S-1")
	tgt.ExpectedInitSHA = nil // 세션 init 이 아직 확정되지 않았다 — 조회가 NULL 을 실어 왔다

	if got := runTarget(t, u, tgt); got != outcomeNeutral {
		t.Fatalf("보류 outcome = %v, want neutral (%s)", got, cap.dump())
	}
	runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
	runQueue(u)

	if got := lastPos(t, prod); got != fixture4sMtxi+stitch {
		t.Errorf("재요청 도장 = %v, want mtxi + 5s — 보류 때 표로 정한 위치", got)
	}
	if marked, _ := st.playbackCalls(); len(marked) != 1 || marked[0].seq != 13 {
		t.Errorf("③ 마킹 = %+v, want seq 13 1회", marked)
	}
}

// 스위퍼와 보류 재요청은 표를 **쓰지 않는다** — 기록과 수명 갱신은 인덱서의 실시간 요청뿐이다
// (계획 뮤테이션 64). 셋 다 접수 경로(enqueue)를 실제로 지나며 잰다.
func TestSweeperAndRequeueNeverWriteOffsetTable(t *testing.T) {
	t.Run("스위퍼_작업이_표를_만들지_않는다", func(t *testing.T) {
		// 잡는 결함: 스위퍼 작업의 영값 보정값이 {k→0} 줄을 만들면 재기동 뒤 표가 소급 생성된다.
		st := &fakeUploadStore{}
		prod := &fakeProducer{}
		put := &fakePutter{}
		u, _, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
		u.enqueue(sweepTarget(t, dir, 5, "S-1"), OriginSweep)
		runQueue(u)
		if n := len(put.putCalls()); n != 0 {
			t.Fatalf("PUT = %d회, want 0회 — 표 없는 스위퍼 작업이 스스로 표를 만들었다", n)
		}
		assertRefused(t, u, st, prod, put, sweepTarget(t, dir, 6, "S-1"))
	})

	t.Run("보류_재요청이_줄을_덧붙이지_않는다", func(t *testing.T) {
		// 잡는 결함: 재요청(보정값 필드 0)이 {13→0} 줄을 붙이면 그 뒤 조각의 재생성이 옛 값을 쓴다.
		prod := &fakeProducer{}
		u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, prod, nil)
		request(u, liveRequest(t, 10, "S-1", 0))
		request(u, liveRequest(t, 12, "S-1", stitch))
		held := sweepTarget(t, dir, 13, "S-1")
		held.ExpectedInitSHA = nil
		runTarget(t, u, held)
		runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
		runQueue(u) // 재요청이 enqueue 를 지난다

		if got := runTarget(t, u, sweepTarget(t, dir, 14, "S-1")); got != outcomeSuccess {
			t.Fatalf("seq 14 outcome = %v (%s)", got, cap.dump())
		}
		if got := lastPos(t, prod); got != fixture4sMtxi+stitch {
			t.Errorf("seq 14 도장 = %v, want mtxi + 5s", got)
		}
	})

	t.Run("스위퍼_작업이_표_수명을_늘리지_않는다", func(t *testing.T) {
		// 잡는 결함: 재수집마다 수명이 갱신되면 failed 행이 도는 동안 표가 영영 안 치워진다.
		st := &fakeUploadStore{}
		prod := &fakeProducer{}
		put := &fakePutter{}
		u, _, dir, clock := newPlaybackUploader(t, st, put, prod, func(o *Options) { o.SessionTTL = 24 * time.Hour })
		request(u, liveRequest(t, 10, "S-1", 0))
		clock.advance(23 * time.Hour)
		u.enqueue(sweepTarget(t, dir, 11, "S-1"), OriginSweep)
		runQueue(u)
		clock.advance(2 * time.Hour) // 마지막 실시간 요청으로부터 25시간
		u.tidySessions()

		assertRefused(t, u, st, prod, put, sweepTarget(t, dir, 12, "S-1"))
	})
}

// 표의 수명 = 그 회차의 마지막 실시간 ③ 요청으로부터 TTL 이다(계획 뮤테이션 54 — 녹화 파일
// 보존 1일과 같은 창). 잡는 결함: 수명이 없으면 끝난 회차의 표가 프로세스 수명 내내 쌓인다
// (M4 에는 ended 전이가 없어 다른 폐기 자리가 없다).
func TestOffsetTableEvictedAfterTTL(t *testing.T) {
	st := &fakeUploadStore{}
	prod := &fakeProducer{}
	put := &fakePutter{}
	u, cap, dir, clock := newPlaybackUploader(t, st, put, prod, func(o *Options) { o.SessionTTL = 24 * time.Hour })
	request(u, liveRequest(t, 10, "S-1", 0))
	clock.advance(23 * time.Hour)
	request(u, liveRequest(t, 11, "S-1", 0)) // 요청이 오면 수명이 다시 센다
	clock.advance(23 * time.Hour)
	u.tidySessions()
	if got := runTarget(t, u, sweepTarget(t, dir, 12, "S-1")); got != outcomeSuccess {
		t.Fatalf("수명 안(마지막 요청 23시간 뒤) outcome = %v, want success (%s)", got, cap.dump())
	}

	clock.advance(2 * time.Hour) // 마지막 요청으로부터 25시간
	u.tidySessions()
	assertRefused(t, u, st, prod, put, sweepTarget(t, dir, 13, "S-1"))
}

// 기본 수명은 tick 몇 번에 사라질 만큼 짧지 않다 — 기본값이 비면(0) 표가 매 tick 에 지워져 모든
// 스위퍼 재생성이 거부된다.
func TestDefaultTableOutlivesATick(t *testing.T) {
	prod := &fakeProducer{}
	u, cap, dir, clock := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, prod, nil)
	request(u, liveRequest(t, 10, "S-1", 0))
	clock.advance(time.Hour)
	u.tidySessions()
	if got := runTarget(t, u, sweepTarget(t, dir, 11, "S-1")); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success — 기본 수명 안이다 (%s)", got, cap.dump())
	}
}

// 스위퍼는 이미 연 입력(Root.Open fd)에서 mtxi 를 읽고 위치를 되돌린 뒤 재포장에 넘긴다(계획
// 4.2-R R3 「스위퍼 재생성」). 파일을 따로 열지 않는다 — 여기서는 연 직후 경로를 지워 둔다.
// 잡는 결함: 경로로 다시 열면(여기서는 실패) 루트 안전 열기를 우회하고 같은 파일이라는 보장도
// 없다 / 위치를 되돌리지 않으면 생산자가 머리말을 건너뛴 입력을 받는다.
func TestSweeperReadsMtxiFromOpenInputBeforeProduce(t *testing.T) {
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, prod, nil)
	request(u, liveRequest(t, 10, "S-1", stitch))
	tgt := sweepTarget(t, dir, 11, "S-1")
	removed := false
	u.statFile = func(f *os.File) (fs.FileInfo, error) {
		if !removed {
			removed = true
			if err := os.Remove(tgt.LocalPath); err != nil {
				t.Errorf("경로 제거 실패: %v", err)
			}
		}
		return f.Stat()
	}

	if got := runTarget(t, u, tgt); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success — 연 fd 에서 읽어야 한다 (%s)", got, cap.dump())
	}
	calls := prod.produced()
	if len(calls) != 1 || calls[0].pos != fixture4sMtxi+stitch || calls[0].inputAt != 0 {
		t.Errorf("Produce = %+v, want 1회 pos mtxi+5s · 입력 위치 0", calls)
	}
}

// Disabled 업로더는 표를 만들지 않는다 — 맵 자체가 없다(계획 4.2-R R3 「Disabled 무표」).
// 잡는 결함: 기록이 u.off 를 안 보면 nil 맵에서 panic 하거나, 아무도 쓰지 않는 표가 쌓인다.
func TestDisabledUploaderPlaybackRequestRecordsNothing(t *testing.T) {
	u := Disabled(newLogCapture().logger())
	if u.RequestUpload(liveRequest(t, 10, "S-1", stitch)) {
		t.Error("RequestUpload = true, want false")
	}
	u.ForgetSession("S-1")
	if u.sessions != nil {
		t.Error("Disabled 업로더에 회차 메모리가 생겼다")
	}
}

// ForgetSession 은 sessionInit·보류 목록만 지우고 표는 남긴다(계획 2.1 upload.go 행).
func TestForgetSessionDropsInitAndHeldButKeepsTable(t *testing.T) {
	t.Run("보류_목록을_지운다", func(t *testing.T) {
		u, _, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, &fakeProducer{}, nil)
		runLive(u, playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64))
		u.ForgetSession("S-1")
		runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
		if n := len(u.queue); n != 0 {
			t.Errorf("재요청 = %d건, want 0건 — 끝난 세션의 보류는 버린다", n)
		}
	})

	t.Run("sessionInit_을_지운다", func(t *testing.T) {
		st := &fakeUploadStore{}
		u, _, dir, _ := newPlaybackUploader(t, st, &fakePutter{}, &fakeProducer{}, nil)
		runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
		u.ForgetSession("S-1")
		runLive(u, playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64))
		if marked, _ := st.playbackCalls(); len(marked) != 0 {
			t.Errorf("③ 마킹 = %+v, want 없음 — 끝난 세션의 확정값으로 대조하지 않는다", marked)
		}
	})
}

// sessionInit·보류 목록도 TTL 로 치운다(계획 2.1 「전역 TTL 24h」 — 종료 관측을 놓친 세션).
// 다만 회차가 살아 있는 동안(실시간 ③ 요청이 오는 동안) sessionInit 은 남는다 — 24시간 넘는
// 방송에서 확정값이 사라지면 그 뒤 실시간 ③ 이 전부 보류로 샌다.
func TestSessionInitAndHeldListExpireAfterTTL(t *testing.T) {
	ttl := func(o *Options) { o.SessionTTL = 24 * time.Hour }

	t.Run("sessionInit_은_마지막_활동으로부터_TTL", func(t *testing.T) {
		st := &fakeUploadStore{}
		u, _, dir, clock := newPlaybackUploader(t, st, &fakePutter{}, &fakeProducer{}, ttl)
		runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
		clock.advance(23 * time.Hour)
		request(u, liveRequest(t, 10, "S-1", 0))
		clock.advance(2 * time.Hour) // 확정 25시간 · 마지막 요청 2시간
		u.tidySessions()
		runLive(u, playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64))
		if marked, _ := st.playbackCalls(); len(marked) != 1 {
			t.Fatalf("③ 마킹 = %+v, want 1회 — 살아 있는 회차의 확정값은 남는다", marked)
		}

		clock.advance(23 * time.Hour) // 마지막 요청 25시간
		u.tidySessions()
		runLive(u, playbackTarget(t, "demo", 8, "S-1", writeSegment(t, dir, "demo", "seg8.mp4", 64), 64))
		if marked, _ := st.playbackCalls(); len(marked) != 1 {
			t.Errorf("③ 마킹 = %+v, want 여전히 1회 — 만료된 확정값으로 대조하지 않는다", marked)
		}
	})

	t.Run("보류_목록은_TTL_뒤_버린다", func(t *testing.T) {
		u, _, dir, clock := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, &fakeProducer{}, ttl)
		runLive(u, playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64))
		clock.advance(25 * time.Hour)
		u.tidySessions()
		runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
		if n := len(u.queue); n != 0 {
			t.Errorf("재요청 = %d건, want 0건 — 수명이 지난 보류는 버린다", n)
		}
	})
}

// 청소 자리는 기존 스위퍼 tick 이다(새 select case 0 — 계획 2.1). tick 이 TTL 청소와 남은 보류
// 재요청을 함께 돈다. 잡는 결함: tick 에서 부르지 않으면 표·보류가 영영 안 치워지고, 큐 포화로
// 남은 재요청이 다음 워커 완료가 올 때까지(유입이 끊기면 영영) 묶인다.
func TestSweeperTickTidiesSessionMemory(t *testing.T) {
	st := &fakeUploadStore{}
	u, _, dir, clock := newPlaybackUploader(t, st, &fakePutter{}, &fakeProducer{}, func(o *Options) {
		o.SessionTTL = 24 * time.Hour
		o.SweepEvery = time.Millisecond
		o.QueueLen = 1
	})
	// 만료될 표 하나(25시간 전 요청).
	request(u, liveRequest(t, 10, "S-9", 0))
	clock.advance(25 * time.Hour)
	// 큐 포화로 남은 재요청 하나: 둘을 보류하고 init 을 확정하면 한 건만 든다.
	runLive(u, playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64))
	runLive(u, playbackTarget(t, "demo", 8, "S-1", writeSegment(t, dir, "demo", "seg8.mp4", 64), 64))
	runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
	first := <-u.queue
	u.gate.releaseInflight(first.key())

	ctx, cancel := context.WithCancel(context.Background())
	u.sweepDone = make(chan struct{})
	u.ArmSweeper()
	go u.sweeper(ctx)
	waitFor(t, func() bool { return len(u.queue) == 1 }, "tick 의 보류 재요청")
	waitFor(t, func() bool { _, ok := u.sessions.offsetAt("S-9", 10); return !ok }, "tick 의 표 청소")
	cancel()
	<-u.sweepDone
}

// 표에 offset 줄이 있는데 입력의 mtxi 를 읽지 못하면(손상·절단) 재시도 사다리를 탄다 — 도장을
// 합성하지도(보정값만으로) 표 부재처럼 거부하지도 않는다. 잡는 결함: 판독 실패를 0 으로 보고
// 재포장하면 그 조각만 도장이 0 + offset 으로 역행한다.
func TestSweeperMtxiReadFailureRetriesWithoutPublishing(t *testing.T) {
	st := &fakeUploadStore{}
	prod := &fakeProducer{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
	request(u, liveRequest(t, 10, "S-1", 0))
	tgt := playbackTarget(t, "demo", 11, "S-1", writeSegment(t, dir, "demo", "no-mtxi.mp4", 64), 64)
	tgt.ExpectedInitSHA = fakeInitSHA()

	if got := runTarget(t, u, tgt); got != outcomeSoft {
		t.Errorf("outcome = %v, want soft", got)
	}
	if n := len(prod.produced()); n != 0 {
		t.Errorf("Produce = %d회, want 0회 — 위치를 모르면 재포장하지 않는다", n)
	}
	if n := len(put.putCalls()); n != 0 {
		t.Errorf("PUT = %d회, want 0회", n)
	}
	if n := cap.count("upload_retry"); n != u.opt.RetryMax-1 {
		t.Errorf("upload_retry = %d건, want %d건 — 판독 실패도 재시도한다", n, u.opt.RetryMax-1)
	}
	if _, failed := st.playbackCalls(); len(failed) != 1 || failed[0].reason == index.ReasonInitMismatch {
		t.Errorf("③ failed = %+v, want 1회(세션을 끝내지 않는 사유)", failed)
	}
}
