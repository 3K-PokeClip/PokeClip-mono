package upload

import (
	"bytes"
	"context"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 워커 · 스위퍼 쪽 복원 배선(POK-196 PDT 계획 4절 c10b · 설계 r6 2.10)을 잰다 — 위치 모름 작업 · 표 없는 스위퍼 작업 · 로컬
// ② 가 없는 ③ 작업 · 복원 고루틴의 수명.

// 위치 모름 요청은 올리지 않는다 — 접수 자리(RequestUpload)에서 끊김 줄과 깨움을 남기고, 큐에 든 작업은 워커가 아무것도
// 하지 않고 끝낸다(도장이 없으니 재포장할 수 없다).
func TestPosUnknownRequestWakesRestoreAndUploadsNothing(t *testing.T) {
	b := newRestoreBench(t)
	tgt := playbackTarget(t, benchStream, 12, benchSession, filepath.Join(b.dir, benchStream, "x.mp4"), 64)
	tgt.PosUnknown = true

	b.u.RequestUpload(tgt)
	jobs := b.queued()
	for _, j := range jobs {
		b.u.runJob(job{target: j, origin: OriginLive})
	}

	if len(b.objs.putKeys) != 0 || b.cap.count("upload_file_missing")+b.cap.count("upload_target_rejected") != 0 {
		t.Errorf("PUT = %v · 처리 로그 (%s), want 없음 — 워커가 위치 모름 작업을 처리했다", b.objs.putKeys, b.cap.dump())
	}
	if got := b.u.sessions.takeWakes(time.Now()); len(got) != 1 || got[0].sessionID != benchSession {
		t.Errorf("깨움 = %+v, want S-1", got)
	}
	if _, ok := b.offset(12); ok {
		t.Error("위치 모름 seq 12 에 보정값이 있다")
	}
}

// 복원하는 업로더에서 표 없는 스위퍼 ③ 작업은 failed(GAP)로 보내지 않고 복원을 깨운다(설계 r6 2.10 「복원하는 곳」 2 —
// refuseWithoutTable 대체). 잡는 결함(음성 대조 — 부모 커밋): 재기동 뒤 c0 이 남긴 구멍이 복원 기회 없이 failed 로 굳는다.
func TestSweepWithoutTableWakesRestoreInsteadOfFailing(t *testing.T) {
	b := newRestoreBench(t)
	path, size := copyFixture(t, b.dir, benchStream, "seg15.mp4")
	tgt := playbackTarget(t, benchStream, 15, benchSession, path, size)
	tgt.ExpectedInitSHA = []byte{1}

	got := runTarget(t, b.u, tgt)

	if got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral", got)
	}
	if _, failedCalls := b.store.playbackCalls(); len(failedCalls) != 0 {
		t.Errorf("③ failed = %+v, want 없음 — 복원을 기다린다", failedCalls)
	}
	if w := b.u.sessions.takeWakes(time.Now()); len(w) != 1 {
		t.Errorf("깨움 = %+v, want S-1", w)
	}
}

// 로컬 ② 가 없고(외부 삭제) ② 가 올라가 있으면 ② 객체를 재포장 입력으로 쓴다(설계 r6 2.10 r2 — cx-N3 · worker 열기 (a)).
// 객체는 자라지 않으므로 PUT 뒤 다시 재기(e)를 건너뛴다. 잡는 결함: attemptFileMissing 으로 그 ③ 이 영영 failed 다.
func TestPlaybackJobUsesArchiveObjectWhenLocalGone(t *testing.T) {
	b := newRestoreBench(t)
	b.contiguous(80, 1, 600*time.Second, pending)
	row := b.ledger.rows[80]
	if err := os.Remove(row.LocalPath); err != nil {
		t.Fatal(err)
	}
	b.confirmInit()
	tgt := playbackTarget(t, benchStream, 80, benchSession, row.LocalPath, *row.Bytes)
	tgt.PlaybackPos, tgt.ArchiveS3Key, tgt.ArchiveUploadState = 600*time.Second, row.S3Key, index.UploadStateUploaded

	if got := runLive(b.u, tgt); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success (%s)", got, b.cap.dump())
	}

	want := b.produce(80, 600*time.Second)
	if obj := b.objs.objs[segKeyOf(t, benchStream, 80)]; string(obj) != string(want) {
		t.Errorf("③ 객체가 ② 객체를 재포장한 바이트가 아니다(%d · want %d바이트)", len(obj), len(want))
	}
	if marked, _ := b.store.playbackCalls(); len(marked) != 1 || marked[0].bytes != int64(len(want)) {
		t.Errorf("③ CAS = %+v, want 산출 길이 한 번", marked)
	}
}

// ② 객체 입력도 장부 크기 규칙을 복원과 같이 따른다(PR 종결 r1 cx H — 재현 입력: 로컬 ② 없음 · 장부 143,007바이트 · 마지막
// 파트 경계에서 잘린 ② 객체). 짧은 객체는 올리지 않고 failed 로 적고, 긴 객체는 장부 크기로 잘라 재포장한다. 잡는 결함: 복원은
// source_size_mismatch 로 지나간 행을 워커가 크기 경고만 남기고 재포장해 짧은 영상을 uploaded 로 굳힌다.
func TestPlaybackJobArchiveInputFollowsLedgerSize(t *testing.T) {
	t.Run("짧은 객체는 올리지 않는다", func(t *testing.T) {
		b := newRestoreBench(t)
		b.contiguous(80, 1, 600*time.Second, pending)
		row := b.ledger.rows[80]
		if err := os.Remove(row.LocalPath); err != nil {
			t.Fatal(err)
		}
		src := b.srcs[80]
		b.objs.set(row.S3Key, src[:lastFragmentStart(t, src)])
		b.confirmInit()
		tgt := playbackTarget(t, benchStream, 80, benchSession, row.LocalPath, *row.Bytes)
		tgt.PlaybackPos, tgt.ArchiveS3Key, tgt.ArchiveUploadState = 600*time.Second, row.S3Key, index.UploadStateUploaded

		if got := runLive(b.u, tgt); got == outcomeSuccess {
			t.Errorf("outcome = success, want 실패 — 잘린 ② 객체를 재포장했다 (%s)", b.cap.dump())
		}

		if len(b.objs.putKeys) != 0 {
			t.Errorf("PUT = %v, want 없음", b.objs.putKeys)
		}
		marked, failedCalls := b.store.playbackCalls()
		if len(marked) != 0 || len(failedCalls) != 1 || failedCalls[0].reason != playbackReasonSourceSizeMismatch {
			t.Errorf("③ CAS = %+v · failed = %+v, want uploaded 없음 · source_size_mismatch 한 번", marked, failedCalls)
		}
	})
	t.Run("긴 객체는 장부 크기로 자른다", func(t *testing.T) {
		b := newRestoreBench(t)
		b.contiguous(80, 1, 600*time.Second, pending)
		row := b.ledger.rows[80]
		if err := os.Remove(row.LocalPath); err != nil {
			t.Fatal(err)
		}
		b.objs.set(row.S3Key, append(bytes.Clone(b.srcs[80]), make([]byte, 64)...))
		b.confirmInit()
		tgt := playbackTarget(t, benchStream, 80, benchSession, row.LocalPath, *row.Bytes)
		tgt.PlaybackPos, tgt.ArchiveS3Key, tgt.ArchiveUploadState = 600*time.Second, row.S3Key, index.UploadStateUploaded

		if got := runLive(b.u, tgt); got != outcomeSuccess {
			t.Fatalf("outcome = %v, want success (%s)", got, b.cap.dump())
		}

		if obj := b.objs.objs[segKeyOf(t, benchStream, 80)]; !bytes.Equal(obj, b.produce(80, 600*time.Second)) {
			t.Error("③ 객체가 장부 크기만큼의 ② 를 재포장한 바이트가 아니다")
		}
		if n := b.cap.count("upload_size_mismatch"); n != 0 {
			t.Errorf("upload_size_mismatch = %d줄, want 0 — 장부 크기로 잘랐다", n)
		}
	})
}

// lastFragmentStart 는 조각 src 의 마지막 moof 상자 자리다 — 거기서 자르면 파트 경계에서 잘린 업로드의 꼴이다.
func lastFragmentStart(t *testing.T, src []byte) int {
	t.Helper()
	last := -1
	for i := 0; i+8 <= len(src); {
		size := int(binary.BigEndian.Uint32(src[i : i+4]))
		if size < 8 || i+size > len(src) {
			t.Fatalf("상자 머리가 어긋났다(자리 %d · 크기 %d)", i, size)
		}
		if string(src[i+4:i+8]) == "moof" {
			last = i
		}
		i += size
	}
	if last <= 0 {
		t.Fatal("픽스처에 moof 가 없다")
	}
	return last
}

// ② 도 안 올라갔으면 지금처럼 파일 없음이다(attemptFileMissing — failed · 격리).
func TestPlaybackJobWithoutLocalOrArchiveIsFileMissing(t *testing.T) {
	b := newRestoreBench(t)
	tgt := playbackTarget(t, benchStream, 81, benchSession, filepath.Join(b.dir, benchStream, "gone.mp4"), 64)
	tgt.ArchiveS3Key, tgt.ArchiveUploadState = "streams/demo/x/seg_000081.m4s", index.UploadStatePending

	runLive(b.u, tgt)

	if _, failedCalls := b.store.playbackCalls(); len(failedCalls) != 1 || failedCalls[0].reason != playbackReasonFileMissing {
		t.Errorf("③ failed = %+v, want file_missing 한 번", failedCalls)
	}
	if b.objs.getCount() != 0 {
		t.Errorf("GET %d번, want 0 — ② 가 안 올라갔으면 객체를 찾지 않는다", b.objs.getCount())
	}
}

// 복원 고루틴은 워커와 함께 뜨고 Shutdown 의 워커 join 안에서 끝난다 — 위치 모름 요청 하나로 깨어 복원 링크를 낸다.
func TestRestoreLoopRunsUnderWorkerAndStopsAtShutdown(t *testing.T) {
	raw, err := os.ReadFile(fixture4sPath)
	if err != nil {
		t.Fatal(err)
	}
	root, dir := newRoot(t)
	b := &restoreBench{t: t, store: &fakeUploadStore{}, ledger: &fakeLedger{rows: map[int64]index.PlaybackChainRow{}},
		objs: newFakeObjects(), raw: raw, srcs: map[int64][]byte{}, dir: dir, cap: newLogCapture()}
	opt := DefaultOptions(root, dir)
	opt.Producer = playback.Remuxer{}
	b.u = New(ledgerStore{b.store, b.ledger}, b.objs, opt, b.cap.logger())
	b.contiguous(90, 1, 600*time.Second, up, pending)
	b.uploadAt(b.liveChain(), 90)
	b.u.Start(context.Background())
	tgt := playbackTarget(t, benchStream, 91, benchSession, b.ledger.rows[91].LocalPath, *b.ledger.rows[91].Bytes)
	tgt.PosUnknown = true

	b.u.RequestUpload(tgt)
	waitFor(t, func() bool { l, ok := b.u.RestoredLink(benchSession); return ok && l.Seq == 91 }, "복원 링크 seq 91")
	b.u.Shutdown()

	if b.u.state.Load() != int32(lifecycleStopped) {
		t.Error("Shutdown 뒤 상태가 Stopped 가 아니다")
	}
}
