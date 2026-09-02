package upload

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 축 파라미터화(POK-195 M3 단계 5 · 설계 5.5.4)의 게이트 T1·T2·T3·T5·T6·T9 다.
//
// ② 아카이브 축의 거동은 기존 파일들(worker_test·sweep_test·upload_test·guard_test)이
// 그대로 잰다 — 이 파일은 **축이 늘어도 ② 가 변하지 않는다**와 **다른 축이 ② 로 새지 않는다**
// 두 가지만 본다.

// initKeyOf 는 설계 5.2 의 init 키를 생산자 쪽 함수로 만든다.
// 검사기(initKeyRe)와 생산자(playback.InitKey)는 서로를 모르는 두 구현이라,
// 둘이 같은 형상을 말하는지는 이렇게 맞대 봐야 드러난다(worker.go:145 "재계산 비교 금지"는
// 런타임 경로의 규약이고, 테스트가 두 구현의 합치를 재는 것은 그 규약과 다른 축이다).
func initKeyOf(t *testing.T, streamID, sessionID string) string {
	t.Helper()
	k, err := playback.InitKey(streamID, sessionID)
	if err != nil {
		t.Fatalf("playback.InitKey(%q, %q) 실패: %v", streamID, sessionID, err)
	}
	return k
}

func segKeyOf(t *testing.T, streamID string, seq int64) string {
	t.Helper()
	k, err := playback.SegKey(streamID, seq)
	if err != nil {
		t.Fatalf("playback.SegKey(%q, %d) 실패: %v", streamID, seq, err)
	}
	return k
}

// axisTarget 은 축만 다른 접수용 대상이다. 게이트 판정에는 키·파일이 필요 없다
// (검증은 워커의 [0] 단계이고, 접수 게이트는 키를 보지 않는다).
func axisTarget(a index.Axis, streamID string, seq int64, sessionID string) index.UploadTarget {
	return index.UploadTarget{
		StreamID: streamID, Axis: a, Seq: seq, SessionID: sessionID, Bytes: 1,
	}
}

// newGateUploader 는 워커·스위퍼 고루틴 없이 접수 게이트만 도는 업로더다.
// 소비자가 없어야 "무엇이 큐에 들어갔는가"가 스케줄러에 흔들리지 않는다.
func newGateUploader(t *testing.T) *Uploader {
	t.Helper()
	root, dir := newRoot(t)
	opt := DefaultOptions(root, dir)
	opt.SweepEvery = time.Hour
	u := New(&fakeUploadStore{}, &fakePutter{}, opt, newLogCapture().logger())
	u.armForSweepTest()
	return u
}

// T9 — 작업 키는 (streamID, axis, seq, sessionID) 이고 생성 경로는 하나다(설계 5.5.2).
//
// 잡는 결함 둘: ⑴ 축이 키에 안 실리면 ② 와 ③ 가 같은 in-flight 자리를 다퉈 한쪽이
// 통째로 누락된다 ⑵ ②·③ 가 sessionID 로 갈리면 같은 행이 게이트에서 두 자리를 차지해
// 백오프·격리가 반쪽만 걸린다.
func TestJobKeyIsAxisScoped(t *testing.T) {
	t.Run("같은_stream_seq_라도_축이_다르면_다른_키다", func(t *testing.T) {
		u := newGateUploader(t)
		for _, a := range []index.Axis{index.AxisArchive, index.AxisPlayback, index.AxisInit} {
			if got := u.enqueue(axisTarget(a, "demo", 7, "S-1"), OriginLive); got != EnqueueAdmitted {
				t.Fatalf("%s 축 접수 = %v, want admitted — 축이 키에 실리지 않아 in-flight 가 겹쳤다", a, got)
			}
		}
	})

	t.Run("init_은_세션마다_다른_키다", func(t *testing.T) {
		u := newGateUploader(t)
		if got := u.enqueue(axisTarget(index.AxisInit, "demo", 0, "S-1"), OriginLive); got != EnqueueAdmitted {
			t.Fatalf("첫 세션 접수 = %v, want admitted", got)
		}
		if got := u.enqueue(axisTarget(index.AxisInit, "demo", 0, "S-2"), OriginLive); got != EnqueueAdmitted {
			t.Fatalf("둘째 세션 접수 = %v, want admitted — init 의 유일성 축은 sessionID 다", got)
		}
		if got := u.enqueue(axisTarget(index.AxisInit, "demo", 0, "S-1"), OriginLive); got != EnqueueRejected {
			t.Fatalf("같은 세션 재접수 = %v, want rejected — in-flight 가 잡혀 있어야 한다", got)
		}
	})

	t.Run("아카이브_키는_sessionID_로_갈리지_않는다", func(t *testing.T) {
		u := newGateUploader(t)
		if got := u.enqueue(axisTarget(index.AxisArchive, "demo", 7, ""), OriginLive); got != EnqueueAdmitted {
			t.Fatalf("첫 접수 = %v, want admitted", got)
		}
		if got := u.enqueue(axisTarget(index.AxisArchive, "demo", 7, "S-1"), OriginLive); got != EnqueueRejected {
			t.Fatalf("세션을 실은 같은 행 접수 = %v, want rejected — ②·③ 의 키에 세션은 들지 않는다", got)
		}
	})
}

// T2 — 격리는 축 안에서만 돈다.
//
// 잡는 결함: ② 의 영구 격리(worker.go:109)가 축을 안 가리면 같은 seq 의 ③·init 이
// 프로세스 수명 동안 통째로 사라진다. 격리는 되돌릴 수 없어 관측조차 되지 않는다.
func TestQuarantineIsPerAxis(t *testing.T) {
	u := newGateUploader(t)
	u.gate.quarantine(targetKeyOf(axisTarget(index.AxisArchive, "demo", 7, "")))

	if got := u.enqueue(axisTarget(index.AxisArchive, "demo", 7, ""), OriginLive); got != EnqueueRejected {
		t.Fatalf("② 접수 = %v, want rejected — 격리된 축이다", got)
	}
	for _, a := range []index.Axis{index.AxisPlayback, index.AxisInit} {
		if got := u.enqueue(axisTarget(a, "demo", 7, "S-1"), OriginLive); got != EnqueueAdmitted {
			t.Fatalf("%s 축 접수 = %v, want admitted — 다른 축의 격리에 걸리면 안 된다", a, got)
		}
	}
}

// T6 — 백오프도 축 안에서만 돈다.
//
// 잡는 결함: ② 의 실패 간격이 ③·init 을 함께 밀면, 아카이브가 아픈 동안 되감기 축이
// 이유 없이 30분까지 정지한다(설계 5.5.4 #1 "②·③가 백오프·격리 공유").
func TestBackoffIsPerAxis(t *testing.T) {
	u := newGateUploader(t)
	u.gate.registerFailure(targetKeyOf(axisTarget(index.AxisArchive, "demo", 7, "")))

	if got := u.enqueue(axisTarget(index.AxisArchive, "demo", 7, ""), OriginLive); got != EnqueueRejected {
		t.Fatalf("② 접수 = %v, want rejected — 백오프 중이다", got)
	}
	for _, a := range []index.Axis{index.AxisPlayback, index.AxisInit} {
		if got := u.enqueue(axisTarget(a, "demo", 7, "S-1"), OriginLive); got != EnqueueAdmitted {
			t.Fatalf("%s 축 접수 = %v, want admitted — 다른 축의 백오프에 걸리면 안 된다", a, got)
		}
	}
}

// T3 — in-flight 도 축 안에서만 돈다(중복 접수 방지는 축마다 독립이다).
func TestInflightIsPerAxis(t *testing.T) {
	u := newGateUploader(t)
	if got := u.enqueue(axisTarget(index.AxisPlayback, "demo", 7, ""), OriginLive); got != EnqueueAdmitted {
		t.Fatalf("③ 첫 접수 = %v, want admitted", got)
	}
	if got := u.enqueue(axisTarget(index.AxisPlayback, "demo", 7, ""), OriginLive); got != EnqueueRejected {
		t.Fatalf("③ 재접수 = %v, want rejected — 같은 축·같은 행은 한 번만 든다", got)
	}
	if got := u.enqueue(axisTarget(index.AxisArchive, "demo", 7, ""), OriginLive); got != EnqueueAdmitted {
		t.Fatalf("② 접수 = %v, want admitted — ③ 가 처리 중이어도 ② 는 독립이다", got)
	}
}

// T1 — 브레이커는 축별 3개다(설계 5.5.3 확정).
//
// 잡는 결함: ② 의 하드 실패 폭풍이 브레이커를 열 때 되감기·init 축까지 함께 멈추면
// "②의 폭풍이 ③를 세우지 않는다"는 계약이 깨진다.
func TestCircuitBreakerIsPerAxis(t *testing.T) {
	t.Run("한_축의_하드_실패_연속은_그_축만_연다", func(t *testing.T) {
		u := newGateUploader(t)
		for i := 0; i < u.opt.CircuitMax; i++ {
			u.brk.of(index.AxisArchive).record(outcomeHard, errors.New("403"))
		}

		if got := u.enqueue(axisTarget(index.AxisArchive, "demo", 7, ""), OriginLive); got != EnqueueRejected {
			t.Fatalf("② 접수 = %v, want rejected — 그 축의 브레이커가 열렸다", got)
		}
		for _, a := range []index.Axis{index.AxisPlayback, index.AxisInit} {
			if got := u.enqueue(axisTarget(a, "demo", 7, "S-1"), OriginLive); got != EnqueueAdmitted {
				t.Fatalf("%s 축 접수 = %v, want admitted — 다른 축의 폭풍에 멈추면 안 된다", a, got)
			}
		}
	})

	t.Run("OpenCircuit_은_축_인자_없이_3축_전부를_연다", func(t *testing.T) {
		u := newGateUploader(t)
		u.OpenCircuit("no_credentials")

		for _, a := range []index.Axis{index.AxisArchive, index.AxisPlayback, index.AxisInit} {
			if got := u.enqueue(axisTarget(a, "demo", 7, "S-1"), OriginLive); got != EnqueueRejected {
				t.Fatalf("%s 축 접수 = %v, want rejected — 자격증명 부재는 축과 무관한 사정이다", a, got)
			}
		}
	})
}

// 축 미판정 대상은 접수 단계에서 거부한다 — 브레이커를 고를 수 없으니 게이트가 성립하지 않는다.
func TestEnqueueRefusesUnknownAxis(t *testing.T) {
	u := newGateUploader(t)
	if got := u.enqueue(index.UploadTarget{StreamID: "demo", Seq: 7, Bytes: 1}, OriginLive); got != EnqueueRejected {
		t.Fatalf("접수 = %v, want rejected — 영값 축은 fail-closed 다", got)
	}
	if len(u.queue) != 0 {
		t.Fatalf("큐 길이 = %d, want 0 — 축을 모르는 작업이 큐에 들면 안 된다", len(u.queue))
	}
}

// init 축은 Result 를 보내지 않는다(계획 4.5 · upload.go:65-71 Result 계약).
//
// 잡는 결함: init 이 바꾸는 장부는 stream_sessions 이고 세그먼트 커서가 아니다.
// 통지하면 main.go 를 지나 indexer 가 **seq=0 꼬리**(= 모든 스트림의 첫 조각)의 UploadState 를
// 뒤집어 correctTail 위양성·duration 교정 차단·PDT 누적 오염을 낳는다.
// 구조 강제라 함수 머리에서 축을 본다 — 호출부 열거로 막지 않는다.
func TestOnlyArchiveAxisSendsResult(t *testing.T) {
	cases := []struct {
		name string
		axis index.Axis
		want int
	}{
		{"아카이브는_통지한다", index.AxisArchive, 1},
		{"재생축은_통지하지_않는다", index.AxisPlayback, 0},
		{"init축은_통지하지_않는다", index.AxisInit, 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			u := newGateUploader(t)
			u.sendResult(job{target: axisTarget(c.axis, "demo", 0, "S-1")}, index.UploadStateUploaded)
			if got := len(u.results); got != c.want {
				t.Fatalf("Result = %d건, want %d건", got, c.want)
			}
		})
	}
}

// initJobTarget 은 실물 파일이 있는 init 축 대상이다.
func initJobTarget(t *testing.T, dir, streamID, sessionID, file string, bytes int64) index.UploadTarget {
	t.Helper()
	return index.UploadTarget{
		StreamID: streamID, Axis: index.AxisInit, SessionID: sessionID,
		S3Key:     initKeyOf(t, streamID, sessionID),
		LocalPath: writeSegment(t, dir, streamID, file, int(bytes)),
		Bytes:     bytes,
	}
}

// init 축의 성공 마킹은 M3 에 없다 — CAS 문장은 index 층에 착지했지만 워커 호출부 배선은 M4 다.
// 기본 갈래로 흘려보내면 init 결과가 ② 의 CAS(markUploadedSQL)로 새어 아카이브 열을 바꾼다.
func TestInitSuccessDoesNotTouchArchiveLedger(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir := newWorkerUploader(t, st, put, nil)

	got := runTarget(t, u, initJobTarget(t, dir, "demo", "S-20260901-0001", "init.mp4", 64))

	if got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral — 판정 재료가 아니다", got)
	}
	if len(put.putCalls()) != 1 {
		t.Errorf("PUT = %d회, want 1회 — 바이트는 올라가되 장부만 손대지 않는다", len(put.putCalls()))
	}
	uploaded, failed := st.markCalls()
	if len(uploaded) != 0 || len(failed) != 0 {
		t.Errorf("② 마킹 = uploaded %d·failed %d회, want 0회 — init 은 세그먼트 열을 바꾸지 않는다",
			len(uploaded), len(failed))
	}
	// init CAS 의 문장·계약은 index 층에 착지했지만 워커 호출부 배선은 M4 다 —
	// sha256 을 만드는 생산자(Producer.Init)가 M3 에 없어 여기서 부를 값이 없다.
	if got := st.initMarkedSessions(); len(got) != 0 {
		t.Errorf("MarkInitUploaded 호출 = %v, want 0회 — 워커 호출부 배선은 M4 다", got)
	}
	if len(u.results) != 0 {
		t.Errorf("Result = %d건, want 0건", len(u.results))
	}
	rec := cap.one(t, "upload_mark_unsupported")
	if rec.attrs["axis"] != index.AxisInit.String() {
		t.Errorf("axis = %v, want %q", rec.attrs["axis"], index.AxisInit.String())
	}
}

// ③ 축의 마킹은 통째로 M4 다(설계 5.5.5 첫·둘째 문장). M3 에서는 큐에 들 수도 없지만,
// 기본 갈래를 AxisArchive 한정으로 못 박아 두지 않으면 M4 의 첫 ③ 작업이 ② 의 CAS 로 흐른다.
func TestPlaybackMarkingIsRefused(t *testing.T) {
	st := &fakeUploadStore{}
	u, cap, dir := newWorkerUploader(t, st, &fakePutter{}, nil)
	target := index.UploadTarget{
		StreamID: "demo", Axis: index.AxisPlayback, Seq: 7,
		S3Key:     segKeyOf(t, "demo", 7),
		LocalPath: writeSegment(t, dir, "demo", "seg.mp4", 64),
		Bytes:     64,
	}

	if got := runTarget(t, u, target); got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral", got)
	}
	if uploaded, failed := st.markCalls(); len(uploaded) != 0 || len(failed) != 0 {
		t.Errorf("② 마킹 = uploaded %d·failed %d회, want 0회", len(uploaded), len(failed))
	}
	rec := cap.one(t, "upload_mark_unsupported")
	if rec.attrs["axis"] != index.AxisPlayback.String() {
		t.Errorf("axis = %v, want %q", rec.attrs["axis"], index.AxisPlayback.String())
	}
}

// init 축의 **실패**는 CAS 가 없다(설계 5.5.5 는 init 에 성공 CAS 한 문장만 정의한다).
// 마킹을 건너뛰고 격리·브레이커만 남긴다 — 실패 상태 컬럼을 새로 만들지 않는다.
func TestInitFailureSkipsMarkingButStillQuarantines(t *testing.T) {
	st := &fakeUploadStore{}
	u, cap, dir := newWorkerUploader(t, st, &fakePutter{}, nil)
	target := initJobTarget(t, dir, "demo", "S-20260901-0002", "init.mp4", 64)
	if err := os.Remove(target.LocalPath); err != nil {
		t.Fatalf("실물 제거 실패: %v", err)
	}

	if got := runTarget(t, u, target); got != outcomeSoft {
		t.Errorf("outcome = %v, want soft — 실패로는 세되 장부는 안 바꾼다", got)
	}
	if _, failed := st.markCalls(); len(failed) != 0 {
		t.Errorf("MarkFailed = %d회, want 0회 — init 에는 실패 CAS 가 없다", len(failed))
	}
	if len(u.results) != 0 {
		t.Errorf("Result = %d건, want 0건", len(u.results))
	}
	if !u.gate.quarantined(targetKeyOf(target)) {
		t.Error("격리되지 않았다 — 없는 파일은 다음 회차에도 없다")
	}
	if len(cap.find("upload_mark_unsupported")) != 0 {
		t.Error("upload_mark_unsupported 가 났다 — 실패 마킹의 init 갈래는 거부가 아니라 생략이다")
	}
}

// ② 경로 바이트 동일성 — 축이 늘어도 아카이브 경로의 키·바이트·마킹·통지가 그대로여야 한다.
// 축 파라미터화가 건드릴 수 있는 네 지점을 한 번에 못 박는다.
func TestArchivePathIsUnchangedByAxisParameterization(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, _, dir := newWorkerUploader(t, st, put, nil)
	path := writeSegment(t, dir, "demo", "seg.mp4", 128)
	target := newTarget("demo", 7, path, 128, false)

	if got := runTarget(t, u, target); got != outcomeSuccess {
		t.Fatalf("outcome = %v, want success", got)
	}

	calls := put.putCalls()
	if len(calls) != 1 {
		t.Fatalf("PUT = %d회, want 1회", len(calls))
	}
	if calls[0].key != index.S3Key("demo", 7, fixtureWall) {
		t.Errorf("PUT 키 = %q, want 예약 키 그대로", calls[0].key)
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("실물 읽기 실패: %v", err)
	}
	if !bytes.Equal(calls[0].body, want) {
		t.Errorf("PUT 바이트가 실물과 다르다 (%d != %d 바이트)", len(calls[0].body), len(want))
	}

	uploaded, _ := st.markCalls()
	if len(uploaded) != 1 || uploaded[0] != (markCall{"demo", 7, 128}) {
		t.Errorf("MarkUploaded = %v, want 1회 {demo 7 128}", uploaded)
	}
	if len(u.results) != 1 {
		t.Fatalf("Result = %d건, want 1건", len(u.results))
	}
	if got := <-u.results; got != (Result{StreamID: "demo", Seq: 7, State: index.UploadStateUploaded}) {
		t.Errorf("Result = %+v, want {demo 7 uploaded}", got)
	}
}

// 스위퍼 회차 로그는 axis 라벨을 단다(설계 5.5.4 #8).
//
// 잡는 결함: M3 의 스위퍼 조회는 ② 축 하나뿐인데(축별 조회·축별 backlog 집계는 M4)
// 라벨이 없으면 그 잔량 수치가 세 축 전부를 말하는 것처럼 읽힌다.
// 라벨이 있어야 "M3 의 backlog 는 archive 축만 나타낸다"는 공시가 로그에서 확인된다.
func TestSweepRoundLogsCarryArchiveAxisLabel(t *testing.T) {
	st := newPageStore(2, "s")
	u, cap := newSweepUploader(t, st, func(o *Options) { o.BacklogWarn = -1 })

	u.sweepOnce(context.Background(), index.SweepCursor{}, 0)

	for _, msg := range []string{"upload_sweep", "upload_backlog"} {
		recs := cap.find(msg)
		if len(recs) == 0 {
			t.Fatalf("%s 로그가 없다 (%s)", msg, cap.dump())
		}
		for _, rec := range recs {
			if rec.attrs["axis"] != index.AxisArchive.String() {
				t.Errorf("%s 의 axis = %v, want %q", msg, rec.attrs["axis"], index.AxisArchive.String())
			}
		}
	}
}

// T5 — 축마다 키 문법이 다르다.
//
// 잡는 결함: 검사기가 ② 문법 하나뿐이면 ③·init 키가 `bad_key` 로 판정돼
// worker.go:109 의 **되돌릴 수 없는 격리**에 걸린다(설계 5.5.4 #3 "왜 막히나").
// 반대 방향도 함께 못 박는다 — 축을 바꿔 낸 키는 통과하면 안 된다.
func TestKeyGrammarIsPerAxis(t *testing.T) {
	u, cap, dir := newWorkerUploader(t, &fakeUploadStore{}, &fakePutter{}, nil)
	local := writeSegment(t, dir, "demo", "seg.mp4", 64)
	const session = "S-20260901-0001"

	cases := []struct {
		name   string
		target index.UploadTarget
		reason string // "" 이면 통과해야 한다
	}{
		{"아카이브_축은_기존_문법_그대로", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisArchive, Seq: 7,
			S3Key: index.S3Key("demo", 7, fixtureWall), LocalPath: local, Bytes: 64}, ""},
		{"재생_축은_dvr_seg_문법", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisPlayback, Seq: 7,
			S3Key: segKeyOf(t, "demo", 7), LocalPath: local, Bytes: 64}, ""},
		{"init_축은_dvr_init_문법", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisInit, SessionID: session,
			S3Key: initKeyOf(t, "demo", session), LocalPath: local, Bytes: 64}, ""},
		{"init_축_리터럴_형상", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisInit, SessionID: session,
			S3Key: "dvr/demo/init/" + session + ".mp4", LocalPath: local, Bytes: 64}, ""},

		{"아카이브_축에_재생_키", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisArchive, Seq: 7,
			S3Key: segKeyOf(t, "demo", 7), LocalPath: local, Bytes: 64}, "bad_key"},
		{"재생_축에_아카이브_키", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisPlayback, Seq: 7,
			S3Key: index.S3Key("demo", 7, fixtureWall), LocalPath: local, Bytes: 64}, "bad_key"},
		{"init_축에_아카이브_키", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisInit, SessionID: session,
			S3Key: index.S3Key("demo", 7, fixtureWall), LocalPath: local, Bytes: 64}, "bad_key"},
		{"init_축에_재생_키", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisInit, SessionID: session,
			S3Key: segKeyOf(t, "demo", 7), LocalPath: local, Bytes: 64}, "bad_key"},
		{"키의_stream_이_다르면_축과_무관하게_거부", index.UploadTarget{
			StreamID: "demo", Axis: index.AxisInit, SessionID: session,
			S3Key: initKeyOf(t, "other", session), LocalPath: local, Bytes: 64}, "bad_key"},

		{"축_미판정은_거부", index.UploadTarget{
			StreamID: "demo", Seq: 7,
			S3Key: index.S3Key("demo", 7, fixtureWall), LocalPath: local, Bytes: 64}, "bad_axis"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			cap.reset()
			_, ok := u.validateTarget(c.target, u.log)
			if c.reason == "" {
				if !ok {
					t.Fatalf("검증 실패 — 통과해야 한다 (%s)", cap.dump())
				}
				return
			}
			if ok {
				t.Fatal("검증 통과 — 거부해야 한다")
			}
			rec := cap.one(t, "upload_target_rejected")
			if rec.level != slog.LevelError {
				t.Errorf("레벨 = %v, want ERROR", rec.level)
			}
			if rec.attrs["reason"] != c.reason {
				t.Errorf("reason = %v, want %q", rec.attrs["reason"], c.reason)
			}
		})
	}
}
