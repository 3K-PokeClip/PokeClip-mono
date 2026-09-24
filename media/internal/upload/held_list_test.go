package upload

import (
	"errors"
	"fmt"
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 대조 보류와 보류 목록(계획 2.1 worker.go 행 — 기대 init 을 아직 모르는 ③ 작업)을 잰다. init CAS 가
// 확정(Success·AlreadySame)되면 기다리던 작업을 그 자리에서 다시 넣고, 들지 못한 몫은 다음 워커 완료·
// tick 에 다시 넣는다. 확정이 아닌 갈래(Mismatch·Missing·DB 오류)는 보류를 풀지 않는다.

// 기대 해시가 아직 없으면(작업에도 sessionInit 에도 없음 — 세션 init 미확정) 대조를 보류한다:
// 올리지도 실패로 적지도 않는다. init CAS 가 확정되면 보류한 작업을 **그 자리에서** 다시 넣고,
// 다시 넣은 작업은 보류할 때의 도장 위치 그대로 재포장된다(계획 held_list_requeue_uses_stored_pos).
func TestHeldListRequeueUsesStoredPos(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
	seg := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
	seg.PlaybackPos = 9 * time.Second

	if got := runLive(u, seg); got != outcomeNeutral {
		t.Errorf("보류 outcome = %v, want neutral", got)
	}
	if n := len(put.putCalls()); n != 0 {
		t.Fatalf("보류 중 PUT = %d회, want 0회", n)
	}
	if marked, failed := st.playbackCalls(); len(marked)+len(failed) != 0 {
		t.Fatalf("보류 중 ③ 마킹 = %+v %+v, want 없음 — 보류는 pending 유지다", marked, failed)
	}
	if _, blocked := u.gate.backoffBlocked(targetKeyOf(seg)); blocked {
		t.Error("보류가 백오프를 등록했다 — 재요청이 백오프 게이트에 막힌다")
	}

	if got := runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)); got != outcomeSuccess {
		t.Fatalf("init outcome = %v (%s)", got, cap.dump())
	}
	if n := len(u.queue); n != 1 {
		t.Fatalf("init 확정 직후 큐 = %d건, want 1건 — 보류한 작업을 즉시 다시 넣는다", n)
	}
	runQueue(u)

	calls := prod.produced()
	if last := calls[len(calls)-1]; last.pos != 9*time.Second {
		t.Errorf("재요청 재포장 pos = %v, want 9s — 보류할 때의 위치 그대로다", last.pos)
	}
	if marked, _ := st.playbackCalls(); len(marked) != 1 || marked[0].seq != 7 {
		t.Errorf("③ 마킹 = %+v, want seq 7 1회", marked)
	}
	var origin any
	for _, rec := range cap.find("segment_uploaded") {
		if rec.attrs["seq"] == int64(7) {
			origin = rec.attrs["origin"]
		}
	}
	if origin != OriginLive.String() {
		t.Errorf("재요청 origin = %v, want live — 보류 재요청은 실시간 경로로 다시 든다", origin)
	}
}

// 이미 같은 바이트로 확정된 세션(AlreadySame — 재기동·재시도)도 확정이다: sessionInit 을 채우고
// 보류한 작업을 다시 넣는다(계획 2.1 「Success|AlreadySame → sessionInit 채움 + drain」).
func TestInitAlreadySameAlsoConfirmsSessionInit(t *testing.T) {
	st := &fakeUploadStore{onInitMark: func(string, []byte) (index.InitMark, error) { return index.InitMarkAlreadySame, nil }}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, &fakeProducer{}, nil)
	seg := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
	runLive(u, seg)

	if got := runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)); got != outcomeSuccess {
		t.Fatalf("init outcome = %v, want success (%s)", got, cap.dump())
	}
	runQueue(u)

	if marked, _ := st.playbackCalls(); len(marked) != 1 {
		t.Errorf("③ 마킹 = %+v, want 1회 — AlreadySame 도 보류를 푼다", marked)
	}
	sameEvents(t, u.Dirty().Peek(), []DirtyEvent{
		{Kind: DirtyUploaded, StreamID: "demo", Axis: index.AxisInit, SessionID: "S-1"},
		{Kind: DirtyUploaded, StreamID: "demo", Axis: index.AxisPlayback, Seq: 7, SessionID: "S-1"},
	})
}

// init CAS 의 나머지 두 갈래는 확정이 아니다(계획 2.1): Mismatch = 그 세션 MAP 이 이미 다른
// 바이트로 굳었다 → ERROR 후 작업 종료(분리 아님 — init 작업에는 조각이 없다), Missing = 세션
// 부재 ERROR. 둘 다 sessionInit 을 채우지 않고 보류를 풀지 않는다 — 풀면 다른 MAP 의 조각이
// 올라간다.
func TestInitMismatchOrMissingDoesNotConfirmSessionInit(t *testing.T) {
	for _, c := range []struct {
		name string
		mark index.InitMark
		log  string
	}{
		{"이미_다른_바이트로_확정", index.InitMarkMismatch, "session_init_mismatch"},
		{"세션_부재", index.InitMarkMissing, "upload_cas_rejected"},
	} {
		t.Run(c.name, func(t *testing.T) {
			st := &fakeUploadStore{onInitMark: func(string, []byte) (index.InitMark, error) { return c.mark, nil }}
			put := &fakePutter{}
			u, cap, dir, _ := newPlaybackUploader(t, st, put, &fakeProducer{}, nil)
			seg := playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64)
			runLive(u, seg)
			initJob := initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)

			if got := runLive(u, initJob); got != outcomeNeutral {
				t.Errorf("init outcome = %v, want neutral", got)
			}
			rec := cap.one(t, c.log)
			if rec.level != slog.LevelError || rec.attrs["init_mark"] != c.mark.String() {
				t.Errorf("%s = %v %v, want ERROR init_mark=%s", c.log, rec.level, rec.attrs, c.mark)
			}
			if n := len(u.queue); n != 0 {
				t.Errorf("큐 = %d건, want 0건 — 확정이 아니면 보류를 풀지 않는다", n)
			}
			if _, blocked := u.gate.backoffBlocked(targetKeyOf(initJob)); !blocked {
				t.Error("백오프가 등록되지 않았다")
			}
			if got := u.Dirty().Peek(); len(got) != 0 {
				t.Errorf("이벤트 = %+v, want 없음", got)
			}
			// 확정되지 않았으므로 새 실시간 ③ 도 올라가지 않는다.
			next := playbackTarget(t, "demo", 8, "S-1", writeSegment(t, dir, "demo", "seg8.mp4", 64), 64)
			runLive(u, next)
			if marked, _ := st.playbackCalls(); len(marked) != 0 {
				t.Errorf("③ 마킹 = %+v, want 없음", marked)
			}
		})
	}
}

// 보류 목록은 세션마다 상한이 있다(계획 2.1 — Options 기본 64). 넘치는 작업은 들지 않는다 —
// 장부에 pending 으로 남아 스위퍼가 다시 집는다. 잡는 결함: 상한이 없으면 init 이 끝내 확정되지
// 않는 세션이 조각마다 메모리를 쌓는다.
func TestHeldListIsCappedPerSession(t *testing.T) {
	st := &fakeUploadStore{}
	u, cap, dir, _ := newPlaybackUploader(t, st, &fakePutter{}, &fakeProducer{}, func(o *Options) { o.HeldPerSession = 2 })
	for seq := int64(7); seq <= 9; seq++ {
		runLive(u, playbackTarget(t, "demo", seq, "S-1", writeSegment(t, dir, "demo", fmt.Sprintf("seg%d.mp4", seq), 64), 64))
	}
	var full []logRecord
	for _, rec := range cap.find("upload_mark_skipped") {
		if rec.attrs["reason"] == "held_list_full" {
			full = append(full, rec)
		}
	}
	if len(full) != 1 || full[0].attrs["seq"] != int64(9) {
		t.Errorf("held_list_full = %+v, want 1건 seq=9", full)
	}

	runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
	if n := len(u.queue); n != 2 {
		t.Errorf("재요청 = %d건, want 2건 — 상한만큼만 들고 있었다", n)
	}
}

// 보류 목록 넘침은 세션당 첫 번만 WARN 이고 그 뒤는 Debug 다(cc r3 #2). init 이 끝내 확정되지 않는
// 회차(재기동 뒤 init 재요청이 거부된 경우 등)는 조각마다(4초) 넘치므로, 매번 WARN 이면 그 방송이 끝날
// 때까지 경보가 쏟아져 다른 경보를 묻는다. 다른 세션의 첫 넘침은 따로 WARN 이다.
func TestHeldListFullWarnsOncePerSession(t *testing.T) {
	u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, &fakeProducer{}, func(o *Options) { o.HeldPerSession = 1 })
	for _, c := range []struct {
		session string
		seq     int64
	}{{"S-1", 7}, {"S-1", 8}, {"S-1", 9}, {"S-2", 20}, {"S-2", 21}} {
		runLive(u, playbackTarget(t, "demo", c.seq, c.session, writeSegment(t, dir, "demo", fmt.Sprintf("seg%d.mp4", c.seq), 64), 64))
	}

	levels := map[string][]slog.Level{}
	for _, rec := range cap.find("upload_mark_skipped") {
		if rec.attrs["reason"] == "held_list_full" {
			id := rec.attrs["session_id"].(string)
			levels[id] = append(levels[id], rec.level)
		}
	}
	want := map[string][]slog.Level{
		"S-1": {slog.LevelWarn, slog.LevelDebug},
		"S-2": {slog.LevelWarn},
	}
	if fmt.Sprint(levels) != fmt.Sprint(want) {
		t.Errorf("held_list_full 수준 = %v, want %v", levels, want)
	}
}

// 같은 키(stream, session, seq)가 다시 보류되면 **기존 항목을 유지한다**(교체 금지 — 계획
// 뮤테이션 61 의 둘째 갈래). 잡는 결함: 나중 것으로 바꾸면 같은 조각이 다른 도장으로 재포장될 수
// 있다.
func TestHeldListKeepsFirstEntryForSameKey(t *testing.T) {
	st := &fakeUploadStore{}
	prod := &fakeProducer{}
	u, _, dir, _ := newPlaybackUploader(t, st, &fakePutter{}, prod, nil)
	path := writeSegment(t, dir, "demo", "seg7.mp4", 64)
	first := playbackTarget(t, "demo", 7, "S-1", path, 64)
	first.PlaybackPos = 9 * time.Second
	again := first
	again.PlaybackPos = 99 * time.Second
	runLive(u, first)
	runLive(u, again)

	runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
	if n := len(u.queue); n != 1 {
		t.Fatalf("재요청 = %d건, want 1건 — 같은 키는 한 항목이다", n)
	}
	runQueue(u)
	calls := prod.produced()
	if last := calls[len(calls)-1]; last.pos != 9*time.Second {
		t.Errorf("재요청 pos = %v, want 9s — 먼저 보류한 항목을 유지한다", last.pos)
	}
}

// 재요청이 큐 포화로 들지 못하면 남겨 두고, 다음 워커 완료 때 다시 넣는다(계획 2.1 「QueueFull
// 이면 남겨 두고 다음 워커 완료/tick 에 다시 drain」). 잡는 결함: 한 번 실패로 버리면 그 조각은
// 스위퍼가 집을 때까지(또는 영영) 올라가지 않는다.
func TestHeldRequeueRetriesAfterNextJobCompletes(t *testing.T) {
	st := &fakeUploadStore{}
	u, _, dir, _ := newPlaybackUploader(t, st, &fakePutter{}, &fakeProducer{}, func(o *Options) { o.QueueLen = 1 })
	runLive(u, playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64))
	runLive(u, playbackTarget(t, "demo", 8, "S-1", writeSegment(t, dir, "demo", "seg8.mp4", 64), 64))

	runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))
	if n := len(u.queue); n != 1 {
		t.Fatalf("큐 = %d건, want 1건(용량 1)", n)
	}
	runQueue(u)

	marked, _ := st.playbackCalls()
	if len(marked) != 2 {
		t.Errorf("③ 마킹 = %+v, want 2회 — 큐 포화로 남은 작업도 다음 완료 때 들어간다", marked)
	}
}

// 재요청이 큐 포화를 만나면 그 작업과 남은 작업을 전부 목록에 되돌리고 멈춘다(Phase 3 r4 cc #1). 포화는
// 게이트 거부와 달리 뒤 작업에도 똑같이 걸리고, 재요청은 init 확정 순간과 워커 작업이 끝날 때마다 다시
// 돈다. 포화 로그는 drain 한 번에 Debug 요약 한 줄이다(실시간 요청의 WARN 은 요청 1건 1줄 그대로).
// 잡는 결함: 탈출이 없으면 큐가 찬 동안 drain 마다 보류 작업 수만큼 upload_queue_full 이 쏟아지고,
// 남은 작업을 되돌리지 않고 멈추면 그 조각들이 스위퍼 몫으로 밀린다.
func TestHeldRequeueStopsAtFullQueueAndReturnsTheRest(t *testing.T) {
	const held = 20
	st := &fakeUploadStore{}
	u, cap, dir, _ := newPlaybackUploader(t, st, &fakePutter{}, &fakeProducer{}, func(o *Options) { o.QueueLen = 2 })
	for seq := int64(0); seq < held; seq++ {
		runLive(u, playbackTarget(t, "demo", seq, "S-1", writeSegment(t, dir, "demo", fmt.Sprintf("seg%d.mp4", seq), 64), 64))
	}
	fillers := int64(0)
	fill := func() { // 실시간 ② 요청이 큐의 빈자리를 채운다
		t.Helper()
		for len(u.queue) < u.opt.QueueLen {
			path := writeSegment(t, dir, "demo", fmt.Sprintf("fill%d.mp4", fillers), 64)
			if !u.RequestUpload(newTarget("demo", 1000+fillers, path, 64, false)) {
				t.Fatalf("채움 요청 %d 이 접수되지 않았다", fillers)
			}
			fillers++
		}
	}
	assertOneSummary := func(when string) {
		t.Helper()
		got := cap.find("upload_queue_full")
		if len(got) != 1 {
			t.Errorf("%s upload_queue_full = %d줄, want 1줄 (%s)", when, len(got), cap.dump())
			return
		}
		if rec := got[0]; rec.level != slog.LevelDebug || rec.attrs["held_returned"] != int64(held) {
			t.Errorf("%s upload_queue_full = %v %v, want Debug · held_returned=%d", when, rec.level, rec.attrs, held)
		}
	}

	fill()
	cap.reset()
	runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)) // 확정 → 재요청 → 큐 포화
	assertOneSummary("init 확정 순간")

	// 실시간 요청의 포화는 그대로 요청 1건마다 WARN 이다 — 줄이는 것은 재요청 경로뿐이다.
	cap.reset()
	if u.RequestUpload(newTarget("demo", 999, writeSegment(t, dir, "demo", "late.mp4", 64), 64, false)) {
		t.Fatal("큐가 찼는데 실시간 요청이 접수됐다")
	}
	if rec := cap.one(t, "upload_queue_full"); rec.level != slog.LevelWarn {
		t.Errorf("실시간 요청의 upload_queue_full = %v, want WARN", rec.level)
	}

	for i := 1; i <= 3; i++ {
		j := <-u.queue
		fill() // 워커가 한 건을 꺼낸 사이 실시간 요청이 빈자리를 채운다
		cap.reset()
		u.runJob(j) // 끝나면 남은 보류를 다시 넣어 본다
		assertOneSummary(fmt.Sprintf("작업 완료 %d회째", i))
	}

	// 되돌린 작업은 유실이 아니다 — 큐가 풀리면 전부 올라간다.
	runQueue(u)
	if marked, _ := st.playbackCalls(); len(marked) != held {
		t.Errorf("③ 마킹 = %d회, want %d회 — 되돌린 작업이 빠졌다", len(marked), held)
	}
}

// 게이트 거부(백오프·in-flight 같은 키별 사정)는 그 작업만 목록에 되돌리고 뒤 작업은 계속 넣는다 — 큐
// 포화와 달리 뒤 작업에는 걸리지 않는다. 잡는 결함: 거부에서도 멈추면 백오프 중인 앞 조각 하나가 그
// 회차의 남은 재요청을 통째로 세운다.
func TestHeldRequeueContinuesPastGateRejection(t *testing.T) {
	u, _, dir, clock := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, &fakeProducer{}, nil)
	targets := map[int64]index.UploadTarget{}
	for seq := int64(7); seq <= 9; seq++ {
		targets[seq] = playbackTarget(t, "demo", seq, "S-1", writeSegment(t, dir, "demo", fmt.Sprintf("seg%d.mp4", seq), 64), 64)
		runLive(u, targets[seq])
	}
	u.gate.registerFailure(targetKeyOf(targets[7])) // 앞 조각이 백오프 중이다
	queued := func() []int64 {
		var seqs []int64
		for len(u.queue) > 0 {
			j := <-u.queue
			u.gate.releaseInflight(j.key())
			seqs = append(seqs, j.target.Seq)
		}
		return seqs
	}

	runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))

	if got := queued(); fmt.Sprint(got) != "[8 9]" {
		t.Errorf("재요청 = %v, want [8 9] — 거부된 7 뒤의 작업도 들어야 한다", got)
	}
	// 거부된 7 은 목록에 남아 백오프가 풀린 뒤 다시 든다.
	clock.advance(time.Hour)
	u.drainHeld()
	if got := queued(); fmt.Sprint(got) != "[7]" {
		t.Errorf("백오프 뒤 재요청 = %v, want [7]", got)
	}
}

// 재요청 요약의 held_returned 는 그 drain 이 목록에 되돌리는 **총수**다(Phase 3 r5 cx #1). 앞 작업이 게이트
// 거부로 먼저 되돌아간 뒤 큐 포화를 만나도 거부된 몫까지 센다. drain 하나는 여러 회차를 함께 다루므로 요약
// 줄에 회차(session_id)를 싣지 않는다. 잡는 결함: 포화 지점부터만 세면 거부된 몫이 빠지고, 포화 지점 작업의
// 회차를 붙이면 다른 회차 몫까지 그 회차 것으로 읽힌다(S-1 2건 + S-2 2건 → "S-1 에서 4건").
func TestHeldRequeueSummaryCountsEveryReturnedJob(t *testing.T) {
	u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, &fakeProducer{}, func(o *Options) { o.QueueLen = 1 })
	targets := map[int64]index.UploadTarget{}
	for _, c := range []struct {
		session string
		seq     int64
	}{{"S-1", 7}, {"S-1", 8}, {"S-2", 20}, {"S-2", 21}} {
		targets[c.seq] = playbackTarget(t, "demo", c.seq, c.session, writeSegment(t, dir, "demo", fmt.Sprintf("seg%d.mp4", c.seq), 64), 64)
		runLive(u, targets[c.seq]) // init 미확정 → 보류
	}
	if !u.RequestUpload(newTarget("demo", 100, writeSegment(t, dir, "demo", "fill.mp4", 64), 64, false)) {
		t.Fatal("큐(용량 1)를 채울 요청이 접수되지 않았다")
	}
	runLive(u, initJobTarget(t, dir, "demo", "S-1", "init1.mp4", 64)) // 확정 → 재요청이 포화로 되돌아간다
	runLive(u, initJobTarget(t, dir, "demo", "S-2", "init2.mp4", 64))
	// 회차마다 앞 조각이 백오프 중이다 — drain 이 어느 회차부터 돌든 거부 1건 뒤에 큐 포화를 만난다.
	u.gate.registerFailure(targetKeyOf(targets[7]))
	u.gate.registerFailure(targetKeyOf(targets[20]))

	cap.reset()
	u.drainHeld()

	returned := len(u.sessions.claimReady()) // 목록에 실제로 되돌아간 수
	if returned != 4 {
		t.Fatalf("되돌아간 작업 = %d건, want 4건 — 거부 1건 + 포화 지점부터 3건", returned)
	}
	rec := cap.one(t, "upload_queue_full")
	if rec.level != slog.LevelDebug || rec.attrs["held_returned"] != int64(returned) {
		t.Errorf("upload_queue_full = %v %v, want Debug · held_returned=%d", rec.level, rec.attrs, returned)
	}
	if id, ok := rec.attrs["session_id"]; ok {
		t.Errorf("upload_queue_full session_id = %v, want 없음 — 이 drain 은 두 회차를 되돌렸다", id)
	}
}

// ③ 이 uploaded 로 확정되면 같은 조각의 보류 사본을 목록에서 뺀다(Phase 3 r4 cc #4). 컷오프가 있는
// 국면의 형상이다: 실시간 ③ k 가 보류된 채 init 확정 순간 큐가 차 목록에 되돌아가고, 그사이 같은 k 의
// 스위퍼 사본(장부 확정 해시를 싣는다)이 먼저 올라가 확정된다. 잡는 결함: 사본을 남기면 다음 drain 이
// 같은 키를 또 PUT 하고, 장부는 이미 uploaded 라 CAS 가 0행 — upload_cas_rejected WARN 과 백오프만 남는다.
func TestPlaybackUploadDropsHeldCopyOfSameSegment(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, &fakeProducer{}, func(o *Options) { o.QueueLen = 1 })
	request(u, liveRequest(t, 7, "S-1", 0)) // 회차 표 {7 → 0} — 스위퍼 사본이 도장 위치를 다시 만드는 재료다
	live := sweepTarget(t, dir, 7, "S-1")   // 실물 조각 — 스위퍼 사본은 여기서 mtxi 를 읽는다
	live.ExpectedInitSHA = nil              // 실시간 작업은 기대 해시를 싣지 않는다
	live.PlaybackPos = fixture4sMtxi
	runLive(u, live) // init 미확정 → 보류

	if !u.RequestUpload(newTarget("demo", 100, writeSegment(t, dir, "demo", "fill.mp4", 64), 64, false)) {
		t.Fatal("큐(용량 1)를 채울 요청이 접수되지 않았다")
	}
	runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)) // 확정 → 재요청이 포화로 되돌아간다
	u.drainQueue()

	sweepCopy := live
	sweepCopy.ExpectedInitSHA = fakeInitSHA() // 스위퍼 조회는 세션 확정 해시를 싣는다
	sweepCopy.PlaybackPos = 0                 // 장부에는 도장 위치가 없다 — 워커가 표로 정한다
	if got := runTarget(t, u, sweepCopy); got != outcomeSuccess {
		t.Fatalf("스위퍼 사본 outcome = %v, want success (%s)", got, cap.dump())
	}

	u.drainHeld()
	runQueue(u)

	key := segKeyOf(t, "demo", 7)
	puts := 0
	for _, c := range put.putCalls() {
		if c.key == key {
			puts++
		}
	}
	if puts != 1 {
		t.Errorf("seq 7 PUT = %d회, want 1회 — 스위퍼 사본이 이미 올렸다", puts)
	}
	if marked, _ := st.playbackCalls(); len(marked) != 1 {
		t.Errorf("③ 마킹 = %+v, want 1회", marked)
	}
}

// heldSeqs 는 보류 작업들의 seq 다.
func heldSeqs(targets []index.UploadTarget) []int64 {
	seqs := make([]int64, 0, len(targets))
	for _, tgt := range targets {
		seqs = append(seqs, tgt.Seq)
	}
	return seqs
}

// ③ 이 확정된 조각은 재요청이 꺼내 간 사이에 확정됐어도 목록에 되돌아가지 않는다(Phase 3 r5 cx #2). 형상:
// tick 의 drain 이 7·8 을 꺼낸다(claimReady) → 7 의 스위퍼 사본이 확정된다(dropHeld — 목록에는 이미
// 없다) → tick 의 재요청이 들지 못한 몫을 되돌린다(giveBack). 잡는 결함: 7 을 되돌리면 다음 drain 이 같은
// 바이트를 한 번 더 PUT 하고, 장부는 이미 uploaded 라 CAS 가 0행 — upload_cas_rejected 와 백오프만 남는다.
func TestGiveBackSkipsSegmentUploadedWhileClaimed(t *testing.T) {
	m := newSessionMemory(DefaultOptions(nil, ""))
	m.confirmInit("S-1", fakeInitSHA(), time.Date(2026, 9, 24, 0, 0, 0, 0, time.UTC))
	m.giveBack([]index.UploadTarget{{SessionID: "S-1", Seq: 7}, {SessionID: "S-1", Seq: 8}}) // 큐 포화로 남은 재요청

	claimed := m.claimReady()
	m.dropHeld("S-1", 7)
	m.giveBack(claimed)

	if got := heldSeqs(m.claimReady()); fmt.Sprint(got) != "[8]" {
		t.Errorf("다음 drain = %v, want [8] — 확정된 7 은 되돌리지 않고, 확정 표시가 없는 8 은 되돌린다", got)
	}
}

// 확정 표시는 보류 목록과 함께 지운다 — 마지막 보류로부터 수명이 지나거나(evict) 회차를 잊을 때(forget)다.
// 지운 뒤에는 같은 조각을 되돌리면 다시 든다. 잡는 결함: 지우지 않으면 ③ 을 올릴 때마다 표시가 회차
// 메모리에 쌓인다.
func TestUploadedMarkIsClearedWithHeldList(t *testing.T) {
	t0 := time.Date(2026, 9, 24, 0, 0, 0, 0, time.UTC)
	back := []index.UploadTarget{{SessionID: "S-1", Seq: 7}}

	t.Run("수명", func(t *testing.T) {
		m := newSessionMemory(DefaultOptions(nil, ""))            // 수명 24시간
		m.hold(index.UploadTarget{SessionID: "S-1", Seq: 1}, t0)  // 마지막 보류 = t0
		m.confirmInit("S-1", fakeInitSHA(), t0.Add(12*time.Hour)) // 확정값은 t0+36h 까지 남아 몫이 산다
		m.dropHeld("S-1", 7)
		m.evict(t0.Add(25 * time.Hour)) // 보류 목록의 수명만 지났다
		m.giveBack(back)
		if got := heldSeqs(m.claimReady()); fmt.Sprint(got) != "[7]" {
			t.Errorf("수명 뒤 되돌린 작업 = %v, want [7] — 표시도 보류 목록과 함께 지운다", got)
		}
	})

	t.Run("forget", func(t *testing.T) {
		m := newSessionMemory(DefaultOptions(nil, ""))
		m.recordLive(index.UploadTarget{SessionID: "S-1", Seq: 1}, t0) // 보정값 표는 forget 뒤에도 남아 몫이 산다
		m.dropHeld("S-1", 7)
		m.forget("S-1")
		m.giveBack(back)
		if got := heldSeqs(m.confirmInit("S-1", fakeInitSHA(), t0)); fmt.Sprint(got) != "[7]" {
			t.Errorf("forget 뒤 되돌린 작업 = %v, want [7] — 표시도 보류 목록과 함께 지운다", got)
		}
	})
}

// 재요청은 seq 순서로 든다. 되감기 목록은 ③ uploaded 의 **연속 접두**까지만 실리므로(인덱스
// 불변식 1) 순서 없이 넣으면 앞 조각 하나가 늦는 동안 뒤 조각들이 올라가도 목록이 전진하지 않는다.
func TestHeldRequeueFollowsSeqOrder(t *testing.T) {
	u, _, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, &fakePutter{}, &fakeProducer{}, nil)
	for _, seq := range []int64{15, 10, 17, 12, 11, 16, 14, 13} {
		runLive(u, playbackTarget(t, "demo", seq, "S-1", writeSegment(t, dir, "demo", fmt.Sprintf("seg%d.mp4", seq), 64), 64))
	}

	runLive(u, initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64))

	var got []int64
	for len(u.queue) > 0 {
		got = append(got, (<-u.queue).target.Seq)
	}
	want := []int64{10, 11, 12, 13, 14, 15, 16, 17}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Errorf("재요청 순서 = %v, want %v", got, want)
	}
}

// init CAS 가 DB 오류면 확정이 아니다 — mark_error 를 남기고 백오프를 건다. sessionInit 을 채우지
// 않는다(보류가 풀리면 다른 MAP 의 조각이 올라갈 수 있다).
func TestInitCASErrorDoesNotConfirmSessionInit(t *testing.T) {
	st := &fakeUploadStore{onInitMark: func(string, []byte) (index.InitMark, error) { return 0, errors.New("연결 끊김") }}
	u, cap, dir, _ := newPlaybackUploader(t, st, &fakePutter{}, &fakeProducer{}, nil)
	runLive(u, playbackTarget(t, "demo", 7, "S-1", writeSegment(t, dir, "demo", "seg7.mp4", 64), 64))
	initJob := initJobTarget(t, dir, "demo", "S-1", "first.mp4", 64)

	if got := runLive(u, initJob); got != outcomeNeutral {
		t.Errorf("outcome = %v, want neutral", got)
	}
	cap.one(t, "mark_error")
	if n := len(u.queue); n != 0 {
		t.Errorf("재요청 = %d건, want 0건", n)
	}
	if _, blocked := u.gate.backoffBlocked(targetKeyOf(initJob)); !blocked {
		t.Error("백오프가 등록되지 않았다")
	}
}
