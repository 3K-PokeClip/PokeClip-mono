package upload

import (
	"bytes"
	"os"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 본문 단계((c′) payloadFor 와 그 아래 playbackPos · 재포장)는 열린 파일이 아니라 「리더 + 잰 크기」 를 받는다(POK-196
// PDT 계획 4절 c10a · 설계 r6 2.10 「입력 꼴」) — 복원(c10b)이 로컬 ② 가 없는 행에서 ② 객체를 메모리 입력으로 쓸 자리다.
// 파일 입력은 지금 그대로다. 잡는 결함: 본문 단계가 *os.File 에 묶이면 메모리 입력을 넣을 수 없다.

// 스위퍼 ③ 작업이 메모리 입력에서도 파일과 같은 도장(mtxi + 표의 보정값)으로 재포장하고, 입력 위치를 처음으로 되돌려
// 넘긴다(재포장은 같은 리더를 처음부터 읽는다).
func TestPlaybackPayloadReadsInMemoryInput(t *testing.T) {
	prod := &fakeProducer{}
	u, cap, _, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, prod, nil)
	request(u, liveRequest(t, 12, "S-1", stitch))
	raw, err := os.ReadFile(fixture4sPath)
	if err != nil {
		t.Fatalf("실물 조각 읽기 실패: %v", err)
	}
	tgt := playbackTarget(t, "demo", 13, "S-1", "/recordings/demo/gone.mp4", int64(len(raw)))
	tgt.ExpectedInitSHA = fakeInitSHA()

	p, _, ok := u.payloadFor(job{target: tgt, origin: OriginSweep}, input{r: bytes.NewReader(raw), size: int64(len(raw))}, cap.logger())

	if !ok {
		t.Fatalf("payloadFor 실패 (%s)", cap.dump())
	}
	calls := prod.produced()
	if len(calls) != 1 || calls[0].pos != fixture4sMtxi+stitch || calls[0].inputAt != 0 {
		t.Errorf("재포장 = %+v, want 1회 · pos mtxi + 5s(%v) · 입력 위치 0", calls, fixture4sMtxi+stitch)
	}
	if p.size != int64(len(fakeSeg)) {
		t.Errorf("본문 크기 = %d, want 재포장 산출 %d", p.size, len(fakeSeg))
	}
}

// ② 축 본문은 받은 리더 그대로이고 크기는 (b) 에서 잰 값이다 — 장부 크기(Bytes)가 아니다. PUT 의 ContentLength 출처가
// 바뀌지 않는다(비꼬리 크기 어긋남은 올리되 어긋남만 남긴다 — L14).
func TestArchivePayloadIsInputReaderWithMeasuredSize(t *testing.T) {
	u, cap, _ := newWorkerUploader(t, &fakeUploadStore{}, &fakePutter{}, nil)
	r := bytes.NewReader([]byte("segment-bytes"))
	tgt := index.UploadTarget{StreamID: "demo", Axis: index.AxisArchive, Seq: 1, Bytes: 99}

	p, _, ok := u.payloadFor(job{target: tgt, origin: OriginSweep}, input{r: r, size: 13}, cap.logger())

	if !ok || p.body != r || p.size != 13 {
		t.Errorf("② 본문 = (%v · %d, ok %v), want 받은 리더 · 13 · ok", p.body, p.size, ok)
	}
}
