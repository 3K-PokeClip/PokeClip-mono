package upload

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"os"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// init 축의 크기 계약(cc r3 ⑦-1 선택 개선)을 잰다. init 산출은 머리말(ftyp+moov)만의 순수 함수라 입력
// 파일이 꼬리에서 더 자라도 바이트가 같다 — ②·③ 와 달리 자라는 꼬리에서 만들어도 잘린 실물이 없다.
// 그래서 init 작업은 크기가 장부와 달라도 막지 않는다. 막으면 재기동 뒤 이어지는 회차의 첫 행에서
// init 이 중립 종료로 끝나 sessionInit 이 그 프로세스에서 되살아나지 않는다(스위퍼 init 벌은 이미
// 확정된 회차를 집지 않는다). ③ 의 중립 종료는 TestPlaybackGrowingTailIsNotProduced 가 그대로 잰다.

// initTailTarget 은 인덱서가 보내는 모양의 init 작업이다 — 꼬리(IsTail)이고 키는 비워 온다.
func initTailTarget(path string, ledgerBytes int64) index.UploadTarget {
	return index.UploadTarget{
		StreamID: "demo", Axis: index.AxisInit, SessionID: "S-1",
		LocalPath: path, Bytes: ledgerBytes, IsTail: true,
	}
}

// 장부 뒤로 더 자란 꼬리의 init 작업도 경고 한 줄을 남기고 산출·PUT·CAS 로 간다 — CAS 가 확정하면
// sessionInit 이 채워지고 그 회차에서 기다리던 ③ 가 다시 든다. 잡는 결함: ② 처럼 중립 종료로 막으면
// 그 회차의 실시간 ③ 이 전부 대조 보류로 샌다.
func TestInitJobOnGrowingTailConfirmsSessionInit(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, &fakeProducer{}, nil)
	waiting := playbackTarget(t, "demo", 8, "S-1", writeSegment(t, dir, "demo", "seg8.mp4", 64), 64)
	runLive(u, waiting) // 기대 init 을 아직 몰라 보류된다

	initJob := initTailTarget(writeSegment(t, dir, "demo", "first.mp4", 96), 64) // 장부 64 · 실물 96
	if got := runLive(u, initJob); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success — init 은 자라는 꼬리에서도 만든다 (%s)", got, cap.dump())
	}
	if calls := put.putCalls(); len(calls) != 1 || calls[0].key != "dvr/demo/init/S-1.mp4" || !bytes.Equal(calls[0].body, fakeInit) {
		t.Errorf("PUT = %+v, want init 산출 1회", calls)
	}
	if got := st.initCalls(); len(got) != 1 || got[0].sessionID != "S-1" || !bytes.Equal(got[0].sha256, fakeInitSHA()) {
		t.Errorf("MarkInitUploaded = %+v, want S-1 · 산출 해시 1회", got)
	}
	rec := cap.one(t, "upload_size_mismatch")
	if rec.level != slog.LevelWarn || rec.attrs["input_bytes"] != int64(96) || rec.attrs["db_bytes"] != int64(64) {
		t.Errorf("upload_size_mismatch = %v %v, want WARN input_bytes=96 · db_bytes=64", rec.level, rec.attrs)
	}
	if n := cap.count("tail_still_growing"); n != 0 {
		t.Errorf("tail_still_growing = %d건, want 0건 — init 은 꼬리 성장으로 멈추지 않는다", n)
	}

	runQueue(u) // 확정 직후 다시 든 ③
	if marked, _ := st.playbackCalls(); len(marked) != 1 || marked[0].seq != 8 {
		t.Errorf("③ 마킹 = %+v, want seq 8 1회 — sessionInit 이 채워져 보류가 풀린다", marked)
	}
}

// PUT 하는 사이 입력이 자라도 init 은 확정한다 — 올린 머리말 바이트는 입력의 꼬리와 무관하다. ③ 는
// 같은 경우 확정하지 않는다(TestPlaybackInputChangedDuringPutIsNotMarked). 잡는 결함: 쓰는 중인 꼬리의
// init 이 PUT 뒤 재측정에서 매번 멈추면 앞 테스트의 구제가 실제 파일에서는 거의 닿지 않는다.
func TestInitJobIgnoresInputGrowthDuringPut(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, &fakeProducer{}, nil)
	path := writeSegment(t, dir, "demo", "first.mp4", 64)
	put.fn = func(context.Context, string, io.Reader, int64) error {
		f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
		if err != nil {
			return err
		}
		defer f.Close()
		_, err = f.Write(make([]byte, 32)) // 녹화기가 다음 part 를 붙였다
		return err
	}

	if got := runLive(u, initTailTarget(path, 64)); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success (%s)", got, cap.dump())
	}
	if got := st.initCalls(); len(got) != 1 {
		t.Errorf("MarkInitUploaded = %d회, want 1회", len(got))
	}
}
