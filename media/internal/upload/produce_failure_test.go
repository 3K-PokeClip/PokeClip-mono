package upload

import (
	"io"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// ③ 산출 실패의 파급 범위를 잰다(계획 6.4 「③ 실패 주입」 음성 대조 — 산출 실패형). ③ 이 PUT 에서
// 실패하는 형(브레이커)은 TestPlaybackHardFailuresOpenOnlyPlaybackBreaker 가 잰다.

// ③ 을 만들지 못한 조각도 그 조각의 ② 클립 소재는 그대로 올라간다. ② 와 ③ 은 형제 축이라(설계
// 3.3·5.5.3) 게이트 키·장부 열·결과 통지가 축마다 따로다. 무결성 4항과 임의 오류 전부로 잰다 —
// 재시도 사다리 부류(해석 실패·임의 오류)도, 첫 시도에서 끝나는 부류(트랙 부족·빈 트랙·크기 상한)도
// 같은 조각의 ② 를 건드리지 않는다.
//
// 잡는 결함: 산출 실패를 조각 전체의 결함으로 보고 ② 까지 막는 변경 — 같은 조각의 ② 키를 격리하거나
// 백오프에 걸면, ② 장부에 failed 를 적으면, ② 커서에 실패를 통지하면 소리 트랙이 빠진 송출 하나가
// 클립 소재를 통째로 잃는다.
func TestPlaybackProduceFailureLeavesArchiveOfSameSegmentUploading(t *testing.T) {
	for _, c := range []struct {
		name string
		err  error
	}{
		{"입력_해석_실패", playback.ErrMalformedInput},
		{"영상1_소리1_아님", playback.ErrMissingTracks},
		{"샘플_0", playback.ErrEmptyTrack},
		{"크기_상한_초과", playback.ErrInputTooLarge},
		{"임의_오류", io.ErrUnexpectedEOF},
	} {
		t.Run(c.name, func(t *testing.T) {
			st := &fakeUploadStore{}
			put := &fakePutter{}
			u, cap, dir, _ := newPlaybackUploader(t, st, put, &fakeProducer{err: c.err}, nil)
			path := writeSegment(t, dir, "demo", "seg7.mp4", 64)
			playbackJob := playbackTarget(t, "demo", 7, "S-1", path, 64)
			playbackJob.ExpectedInitSHA = fakeInitSHA()
			archiveJob := newTarget("demo", 7, path, 64, false)

			// ③ 이 먼저 산출에 실패하고 같은 조각의 ② 가 뒤에 온다 — 재시도·스위퍼로 늦게 오는 ② 의 모양이다.
			if got := u.enqueue(playbackJob, OriginLive); got != EnqueueAdmitted {
				t.Fatalf("③ 접수 = %v, want admitted", got)
			}
			runQueue(u)
			if got := u.enqueue(archiveJob, OriginLive); got != EnqueueAdmitted {
				t.Fatalf("② 접수 = %v, want admitted — ③ 산출 실패가 같은 조각의 ② 를 막았다 (%s)", got, cap.dump())
			}
			runQueue(u)

			uploaded, failed := st.markCalls()
			if want := (markCall{"demo", 7, 64}); len(uploaded) != 1 || uploaded[0] != want || len(failed) != 0 {
				t.Errorf("② 장부 = uploaded %v · failed %v, want uploaded [%v] · failed 없음", uploaded, failed, want)
			}
			if marked, playbackFailed := st.playbackCalls(); len(marked) != 0 || len(playbackFailed) != 1 || playbackFailed[0].seq != 7 {
				t.Errorf("③ 장부 = uploaded %+v · failed %+v, want failed seq 7 1회", marked, playbackFailed)
			}
			var putKeys []string
			for _, call := range put.putCalls() {
				putKeys = append(putKeys, call.key)
			}
			if len(putKeys) != 1 || putKeys[0] != archiveJob.S3Key {
				t.Errorf("PUT 키 = %v, want [%s] — ③ 은 올라가지 않고 ② 는 올라간다", putKeys, archiveJob.S3Key)
			}
			// ② 커서가 받는 통지는 올라간 것 하나뿐이다 — ③ 실패를 failed 로 알리면 그 조각의 꼬리 상태가 뒤집힌다.
			var results []Result
			for len(u.results) > 0 {
				results = append(results, <-u.results)
			}
			if want := (Result{StreamID: "demo", Seq: 7, State: index.UploadStateUploaded}); len(results) != 1 || results[0] != want {
				t.Errorf("② 결과 통지 = %+v, want [%+v]", results, want)
			}
		})
	}
}
