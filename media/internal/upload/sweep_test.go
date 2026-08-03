package upload

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// pageStore 는 PendingUploads 를 페이지 단위로 흉내 내는 가짜다.
// 정렬 키는 (isFailed, startWall, streamID, seq) 이며 실제 SQL 과 같은 순서로 자른다.
type pageStore struct {
	mu   sync.Mutex
	rows []pageRow
	// queries 는 호출된 커서를 순서대로 기록한다 — 재조회 생략(R3)을 관측하는 유일한 방법이다.
	queries []index.SweepCursor
}

type pageRow struct {
	target index.UploadTarget
	cursor index.SweepCursor
}

func newPageStore(n int, prefix string) *pageStore {
	s := &pageStore{}
	base := time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC)
	for i := 0; i < n; i++ {
		sid := fmt.Sprintf("%s-%d", prefix, i)
		s.rows = append(s.rows, pageRow{
			target: index.UploadTarget{
				StreamID: sid, Seq: int64(i),
				S3Key:     index.S3Key(sid, int64(i), base),
				LocalPath: "/recordings/" + sid + "/x.mp4",
				Bytes:     100,
			},
			cursor: index.SweepCursor{StartWall: base.Add(time.Duration(i) * time.Second), StreamID: sid, Seq: int64(i)},
		})
	}
	return s
}

func (s *pageStore) MarkUploaded(context.Context, string, int64, int64) (bool, error) {
	return true, nil
}
func (s *pageStore) MarkFailed(context.Context, string, int64, int64) (bool, error) {
	return true, nil
}
func (s *pageStore) CountBacklog(context.Context) (int64, int64, int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return int64(len(s.rows)), 0, 0, nil
}

func (s *pageStore) PendingUploads(_ context.Context, _ float64, limit int, after index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.queries = append(s.queries, after)

	var out []index.UploadTarget
	next := after
	for _, r := range s.rows {
		if !after.IsZero() && !cursorLess(after, r.cursor) {
			continue
		}
		out = append(out, r.target)
		next = r.cursor
		if len(out) == limit {
			break
		}
	}
	return out, next, nil
}

func cursorLess(a, b index.SweepCursor) bool {
	if a.IsFailed != b.IsFailed {
		return !a.IsFailed
	}
	if !a.StartWall.Equal(b.StartWall) {
		return a.StartWall.Before(b.StartWall)
	}
	if a.StreamID != b.StreamID {
		return a.StreamID < b.StreamID
	}
	return a.Seq < b.Seq
}

func (s *pageStore) queryCursors() []index.SweepCursor {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]index.SweepCursor(nil), s.queries...)
}

// newSweepUploader 는 스위퍼만 돌리기 위한 업로더다. 워커는 큐를 비우지 않는다 —
// 큐 포화 시나리오를 결정적으로 만들려면 소비자가 없어야 한다.
func newSweepUploader(t *testing.T, st index.UploadStore, tune func(*Options)) (*Uploader, *logCapture) {
	t.Helper()
	root, dir := newRoot(t)
	opt := DefaultOptions(root, dir)
	opt.SweepEvery = time.Hour
	opt.SweepLimit = 4
	opt.SweepMaxPages = 8
	opt.QueueLen = 64
	if tune != nil {
		tune(&opt)
	}
	cap := newLogCapture()
	u := New(st, &fakePutter{}, opt, cap.logger())
	// 워커를 띄우지 않고 수명 상태만 Started 로 만든다. 스위퍼 회차를 테스트가 직접 돌린다.
	u.armForSweepTest()
	return u, cap
}

// ⑦ — Rejected 는 커서를 전진시키고 QueueFull 만 회차를 중단시킨다(B-1 회귀).
// bool 하나로 두면 2회차부터 격리 행에서 중단돼 커서가 영구 정지한다.
func TestSweepAdvancesOverRejectedRows(t *testing.T) {
	// 한 회차가 도는 최대 페이지(SweepMaxPages-1 = 7 × limit 4 = 28행)보다 넉넉히 둔다.
	// 끝에 닿으면 커서가 순환(zero)해 "전진했는가"를 그 값으로 볼 수 없다.
	st := newPageStore(40, "s")
	u, cap := newSweepUploader(t, st, nil)

	// 전부 격리해 Rejected 만 나오게 만든다.
	for _, r := range st.rows {
		u.gate.quarantine(targetKey{r.target.StreamID, r.target.Seq})
	}

	resume, stalled := u.sweepOnce(context.Background(), index.SweepCursor{}, 0)
	if stalled != 0 {
		t.Errorf("stalled = %d, want 0 — QueueFull 이 없었다", stalled)
	}
	if resume.IsZero() {
		t.Fatal("커서가 전진하지 않았다 — Rejected 는 검사 완료다")
	}
	if n := cap.count("sweep_aborted_queue_full"); n != 0 {
		t.Errorf("sweep_aborted_queue_full = %d건, want 0", n)
	}
}

// ⑧ — 1단계가 접수에 성공해도 2단계는 반드시 진행한다.
// 거기서 회차를 끝내면 resumeCursor 가 영원히 전진하지 않는다.
func TestSweepRunsStage2EvenWhenStage1Admitted(t *testing.T) {
	st := newPageStore(12, "s")
	u, cap := newSweepUploader(t, st, nil)

	// 비-zero 커서에서 시작해 1단계 재사용 분기를 피한다.
	start := st.rows[3].cursor
	resume, _ := u.sweepOnce(context.Background(), start, 0)

	if !cursorLess(start, resume) {
		t.Errorf("커서 = %+v, want %+v 보다 뒤 — 2단계가 돌지 않았다", resume, start)
	}
	stages := map[int64]int{}
	for _, r := range cap.find("upload_sweep") {
		stages[r.attrs["stage"].(int64)]++
	}
	if stages[1] != 1 || stages[2] != 1 {
		t.Errorf("upload_sweep 단계별 건수 = %v, want stage1 1건 stage2 1건", stages)
	}
}

// ⑨ — resumeCursor 가 IsZero 면 2단계는 1단계 결과를 재사용한다.
// 다시 enqueue 하면 방금 Admitted 된 행이 전부 in-flight 로 Rejected 가 되어
// "이 페이지는 접수가 없었다"는 잘못된 결론이 나고 회차 계산이 붕괴한다(R3).
func TestSweepReusesStage1PageWithoutReEnqueue(t *testing.T) {
	st := newPageStore(4, "s") // SweepLimit(4)보다 크지 않아 1페이지로 끝난다
	u, cap := newSweepUploader(t, st, nil)

	resume, _ := u.sweepOnce(context.Background(), index.SweepCursor{}, 0)

	if got := len(st.queryCursors()); got != 1 {
		t.Errorf("PendingUploads 호출 = %d회, want 1회 (재조회 생략)", got)
	}
	if n := len(u.queue); n != 4 {
		t.Fatalf("큐에 %d건, want 4건 — 재-enqueue 로 중복 접수됐거나 접수가 빠졌다", n)
	}
	// N-4 — examined2·admitted2 를 1단계에서 승계한다. 승계하지 않으면 admitted2 == 0 이라
	// 회차가 "접수 없음"으로 판정돼 커서가 전진하지 않는다.
	if resume.IsZero() {
		t.Error("승계가 되지 않아 커서가 전진하지 않았다")
	}
	var stage2 *logRecord
	for i, r := range cap.find("upload_sweep") {
		if r.attrs["stage"].(int64) == 2 {
			stage2 = &cap.find("upload_sweep")[i]
		}
	}
	if stage2 == nil {
		t.Fatalf("2단계 요약이 없다 (%s)", cap.dump())
	}
	if got := stage2.attrs["admitted"].(int64); got != 4 {
		t.Errorf("2단계 admitted = %d, want 4 (1단계 승계)", got)
	}
}

// ⑩ — 큐 포화 회차의 관측. 중단 회차에서도 집계 블록이 반드시 실행된다(M-2).
// 즉시 반환으로 구현하면 만성 포화일수록 경고가 전멸해 처방이 무효가 된다.
func TestSweepAbortedRoundStillReportsAndCountsStall(t *testing.T) {
	st := newPageStore(8, "s")
	u, cap := newSweepUploader(t, st, func(o *Options) { o.QueueLen = 2 })

	stalled := 0
	var resume index.SweepCursor
	for i := 0; i < 5; i++ {
		resume, stalled = u.sweepOnce(context.Background(), resume, stalled)
	}

	if stalled != 5 {
		t.Errorf("cursor_stalled_rounds = %d, want 5", stalled)
	}
	// 1단계에서 막혔으므로 stage=1 만 나오고 stage=2 는 아예 없다(N-6).
	stages := map[int64]int{}
	for _, r := range cap.find("upload_sweep") {
		stages[r.attrs["stage"].(int64)]++
	}
	if stages[2] != 0 {
		t.Errorf("1단계 중단 회차인데 stage=2 요약이 %d건 나왔다", stages[2])
	}
	if stages[1] != 5 {
		t.Errorf("stage=1 요약 = %d건, want 5 (중단 회차에도 낸다)", stages[1])
	}
	aborts := cap.find("sweep_aborted_queue_full")
	if len(aborts) != 5 {
		t.Fatalf("sweep_aborted_queue_full = %d건, want 5 (회차당 1건)", len(aborts))
	}
	if got, ok := aborts[0].attrs["stage"].(int64); !ok || got != 1 {
		t.Errorf("stage 필드 = %v(%T), want 정수 1", aborts[0].attrs["stage"], aborts[0].attrs["stage"])
	}
	stall := cap.find("sweep_cursor_stalled")
	if len(stall) == 0 {
		t.Error("sweep_cursor_stalled 가 한 건도 없다")
	}
	if n := cap.count("upload_backlog"); n != 5 {
		t.Errorf("upload_backlog = %d건, want 5 (중단 회차에도 집계한다)", n)
	}
	for _, name := range []string{"upload_sweep", "sweep_aborted_queue_full", "upload_backlog", "sweep_cursor_stalled"} {
		for _, r := range cap.find(name) {
			if _, ok := r.attrs["sweep_round"].(uint64); !ok {
				t.Errorf("%s 에 sweep_round 정수 필드가 없다 (%v)", name, r.attrs)
			}
		}
	}
}

// ⑲ — 1단계가 큐를 소진한 회차에 2단계 resume 이 첫 행부터 막혀도
// resumeCursor 는 되감기지 않고 다음 회차가 그 위치에서 재개한다(H-1 회귀).
func TestSweepDoesNotRewindCursorWhenStage2AbortsAtFirstRow(t *testing.T) {
	st := newPageStore(12, "s")
	// 1단계(zero 페이지)가 큐 4자리를 전부 쓰고, 2단계는 첫 행부터 QueueFull 이 된다.
	u, cap := newSweepUploader(t, st, func(o *Options) { o.QueueLen = 4 })

	start := st.rows[7].cursor // 정렬 뒤쪽 — 되감기면 앞쪽(rows[0..3])으로 간다
	resume, stalled := u.sweepOnce(context.Background(), start, 0)

	if resume != start {
		t.Fatalf("resumeCursor = %+v, want %+v (불변) — 되감기면 옛 행 기아가 재발한다", resume, start)
	}
	if stalled != 1 {
		t.Errorf("stalled = %d, want 1", stalled)
	}
	aborts := cap.find("sweep_aborted_queue_full")
	if len(aborts) != 1 || aborts[0].attrs["stage"].(int64) != 2 {
		t.Fatalf("sweep_aborted_queue_full = %d건 stage=%v, want 1건 stage=2", len(aborts), aborts[0].attrs["stage"])
	}
	round1 := aborts[0].attrs["sweep_round"].(uint64)

	// 워커가 밀린 것을 따라잡은 상황을 만든다 — 큐를 비우고, 1단계가 그 자리를 다시
	// 차지하지 않도록 zero 페이지 행들을 격리한다. 그러면 2단계에 자리가 남는다.
	u.drainQueue()
	for i := 0; i < 4; i++ {
		u.gate.quarantine(targetKey{st.rows[i].target.StreamID, st.rows[i].target.Seq})
	}
	next, _ := u.sweepOnce(context.Background(), resume, stalled)
	if !cursorLess(start, next) {
		t.Errorf("다음 회차 커서 = %+v, want %+v 보다 뒤", next, start)
	}
	if got := u.sweepRound; got != round1+1 {
		t.Errorf("sweep_round = %d, want %d (정확히 1 증가)", got, round1+1)
	}
}

// ⑪ — 회차 ID 로 센 포함 회차가 상한 안에 든다(G16⁗).
// 로그 출현 순서가 아니라 ID 차로 세므로 워커·스위퍼의 출력 선후에 흔들리지 않는다.
func TestSweepReachesLastRowWithinBoundedRounds(t *testing.T) {
	const total = 1100
	st := newPageStore(total, "ghost")
	u, cap := newSweepUploader(t, st, func(o *Options) {
		o.SweepLimit = 128
		o.QueueLen = 4096 // 큐가 넉넉해야 G16⁗ 전제 (ii)가 성립한다
	})

	last := st.rows[total-1].target
	var r0, r1 uint64
	var resume index.SweepCursor
	stalled := 0
	admittedRound := map[string]uint64{}

	for round := 0; round < 40; round++ {
		resume, stalled = u.sweepOnce(context.Background(), resume, stalled)
		// 이번 회차에 접수된 행을 큐에서 꺼내 회차 ID 를 기록한다.
		for {
			select {
			case j := <-u.queue:
				if _, seen := admittedRound[j.target.StreamID]; !seen {
					admittedRound[j.target.StreamID] = j.sweepRound
				}
				u.gate.releaseInflight(j.key())
				u.gate.quarantine(j.key()) // 다음 회차에 다시 집히지 않게 한다
				continue
			default:
			}
			break
		}
		if r0 == 0 {
			if v, ok := admittedRound[st.rows[0].target.StreamID]; ok {
				r0 = v
			}
		}
		if v, ok := admittedRound[last.StreamID]; ok {
			r1 = v
			break
		}
	}

	if r0 == 0 || r1 == 0 {
		t.Fatalf("앵커가 비었다 r0=%d r1=%d — 맨 뒤 행이 끝내 접수되지 않았다", r0, r1)
	}
	if r1 < r0 {
		t.Fatalf("r1(%d) < r0(%d) — 앵커가 오염됐다", r1, r0)
	}
	// 포함 회차 = ID 차 + 1 ≤ ceil(N/SweepLimit) + 1
	bound := (total+127)/128 + 1
	if inc := int(r1-r0) + 1; inc > bound {
		t.Errorf("포함 회차 = %d, want <= %d", inc, bound)
	}
	if n := cap.count("sweep_aborted_queue_full"); n != 0 {
		t.Errorf("구간 전체 sweep_aborted_queue_full = %d건, want 0", n)
	}
}

// newErrSweepUploader 는 오류 주입용 가짜 저장소를 단 업로더다.
func newErrSweepUploader(t *testing.T, st index.UploadStore) (*Uploader, *logCapture) {
	t.Helper()
	root, dir := newRoot(t)
	opt := DefaultOptions(root, dir)
	opt.SweepEvery = time.Hour
	opt.SweepLimit = 4
	cap := newLogCapture()
	u := New(st, &fakePutter{}, opt, cap.logger())
	u.armForSweepTest()
	return u, cap
}

// onePage 는 정상 1페이지를 돌려주는 훅이다. 페이지가 limit 보다 짧으므로 순환으로 끝난다.
func onePage(rows []pageRow) func(context.Context, float64, int, index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
	return func(_ context.Context, _ float64, _ int, after index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
		out := make([]index.UploadTarget, 0, len(rows))
		next := after
		for _, r := range rows {
			out = append(out, r.target)
			next = r.cursor
		}
		return out, next, nil
	}
}

// 조회 실패는 커서를 건드리지 않고, 다음 회차가 정상적으로 이어진다.
//
// 1단계 실패 회차가 2단계를 도는 것도 막는다 — 아무것도 훑지 않았는데
// upload_sweep(stage=2, picked=0, cursor_wrapped=true)가 나오면 "한 바퀴 돌았다"로
// 오관측된다(L-4).
func TestSweepQueryFailureKeepsCursorAndReportsOnce(t *testing.T) {
	rows := newPageStore(2, "s").rows
	start := index.SweepCursor{StartWall: time.Date(2026, 8, 1, 0, 0, 5, 0, time.UTC), StreamID: "anchor", Seq: 5}

	t.Run("1단계_조회_실패", func(t *testing.T) {
		st := &fakeUploadStore{onPending: func(context.Context, float64, int, index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
			return nil, index.SweepCursor{}, errors.New("연결 끊김")
		}}
		u, cap := newErrSweepUploader(t, st)

		resume, stalled := u.sweepOnce(context.Background(), start, 3)

		if resume != start {
			t.Errorf("커서 = %+v, want %+v (불변)", resume, start)
		}
		if stalled != 3 {
			t.Errorf("stalled = %d, want 3 — 조회 실패는 커서 정체가 아니다", stalled)
		}
		rec := cap.one(t, "upload_sweep_failed")
		if rec.level != slog.LevelError {
			t.Errorf("레벨 = %v, want ERROR", rec.level)
		}
		if rec.attrs["stage"] != int64(1) {
			t.Errorf("stage = %v, want 1", rec.attrs["stage"])
		}
		for _, r := range cap.find("upload_sweep") {
			if r.attrs["stage"] == int64(2) {
				t.Errorf("1단계가 실패했는데 2단계 요약이 나왔다: %v", r.attrs)
			}
		}

		// 다음 회차는 정상 진행한다.
		st.mu.Lock()
		st.onPending = onePage(rows)
		st.mu.Unlock()
		cap.reset()
		next, _ := u.sweepOnce(context.Background(), resume, stalled)
		if next != rows[len(rows)-1].cursor && !next.IsZero() {
			t.Errorf("다음 회차 커서 = %+v — 회차가 정상 진행하지 않았다", next)
		}
		if n := cap.count("upload_sweep_failed"); n != 0 {
			t.Errorf("정상 회차에 upload_sweep_failed 가 %d건", n)
		}
	})

	t.Run("1단계_실패_회차는_순환으로_관측되지_않는다", func(t *testing.T) {
		// resume 가 IsZero 인 회차가 L-4 의 무대다. 1단계 결과를 승계하는 분기라
		// 빈 결과가 그대로 "끝 도달 = 순환"으로 흘러갔다.
		st := &fakeUploadStore{onPending: func(context.Context, float64, int, index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
			return nil, index.SweepCursor{}, errors.New("연결 끊김")
		}}
		u, cap := newErrSweepUploader(t, st)

		resume, _ := u.sweepOnce(context.Background(), index.SweepCursor{}, 0)

		if !resume.IsZero() {
			t.Errorf("커서 = %+v, want zero (불변)", resume)
		}
		for _, r := range cap.find("upload_sweep") {
			if r.attrs["cursor_wrapped"] == true {
				t.Errorf("아무것도 훑지 않았는데 cursor_wrapped=true 로 관측됐다: %v", r.attrs)
			}
		}
	})

	t.Run("2단계_조회_실패", func(t *testing.T) {
		var calls int
		st := &fakeUploadStore{}
		st.onPending = func(ctx context.Context, g float64, l int, after index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
			st.mu.Lock()
			calls++
			n := calls
			st.mu.Unlock()
			if n == 1 { // 1단계(zero 커서)는 성공
				return onePage(rows)(ctx, g, l, after)
			}
			return nil, index.SweepCursor{}, errors.New("연결 끊김")
		}
		u, cap := newErrSweepUploader(t, st)

		resume, stalled := u.sweepOnce(context.Background(), start, 2)

		if resume != start {
			t.Errorf("커서 = %+v, want %+v (불변)", resume, start)
		}
		if stalled != 2 {
			t.Errorf("stalled = %d, want 2", stalled)
		}
		rec := cap.one(t, "upload_sweep_failed")
		if rec.attrs["stage"] != int64(2) {
			t.Errorf("stage = %v, want 2", rec.attrs["stage"])
		}
		for _, r := range cap.find("upload_sweep") {
			if r.attrs["stage"] == int64(2) && r.attrs["cursor_wrapped"] == true {
				t.Errorf("조회에 실패했는데 순환으로 관측됐다: %v", r.attrs)
			}
		}
	})

	t.Run("CountBacklog_실패", func(t *testing.T) {
		st := &fakeUploadStore{
			onPending: onePage(rows),
			onBacklog: func(context.Context) (int64, int64, int64, error) {
				return 0, 0, 0, errors.New("연결 끊김")
			},
		}
		u, cap := newErrSweepUploader(t, st)

		_, stalled := u.sweepOnce(context.Background(), index.SweepCursor{}, 0)

		rec := cap.one(t, "upload_sweep_failed")
		if rec.attrs["stage"] != int64(0) {
			t.Errorf("stage = %v, want 0 (집계 블록)", rec.attrs["stage"])
		}
		if n := cap.count("upload_backlog"); n != 0 {
			t.Errorf("집계에 실패했는데 upload_backlog 가 %d건 나왔다", n)
		}
		if stalled != 0 {
			t.Errorf("stalled = %d, want 0 — 단계는 정상이었다", stalled)
		}
		// 다음 회차는 정상 진행한다.
		st.mu.Lock()
		st.onBacklog = nil
		st.mu.Unlock()
		cap.reset()
		u.sweepOnce(context.Background(), index.SweepCursor{}, 0)
		if n := cap.count("upload_sweep_failed"); n != 0 {
			t.Errorf("정상 회차에 upload_sweep_failed 가 %d건", n)
		}
	})
}

// ctx 취소는 실패가 아니라 종료 신호다.
//
// ERROR 로 내면 계획 5.2·6.4절의 "업로더 기인 ERROR 0건" 판정이 SIGTERM 한 번으로
// 위양성 FAIL 이 된다 — 종료는 정상 경로이므로 계측을 깨서는 안 된다.
func TestSweepCancellationIsNotAnError(t *testing.T) {
	for _, c := range []struct {
		name string
		err  error
	}{
		{"Canceled", context.Canceled},
		{"DeadlineExceeded", context.DeadlineExceeded},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Run("조회", func(t *testing.T) {
				st := &fakeUploadStore{onPending: func(context.Context, float64, int, index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
					return nil, index.SweepCursor{}, fmt.Errorf("업로드 대상 조회 실패: %w", c.err)
				}}
				u, cap := newErrSweepUploader(t, st)

				u.sweepOnce(context.Background(), index.SweepCursor{}, 0)

				assertNoUploaderErrorOrWarn(t, cap)
			})
			t.Run("집계", func(t *testing.T) {
				st := &fakeUploadStore{
					onBacklog: func(context.Context) (int64, int64, int64, error) {
						return 0, 0, 0, fmt.Errorf("업로드 잔량 집계 실패: %w", c.err)
					},
				}
				u, cap := newErrSweepUploader(t, st)

				u.sweepOnce(context.Background(), index.SweepCursor{}, 0)

				assertNoUploaderErrorOrWarn(t, cap)
			})
		})
	}
}

// assertNoUploaderErrorOrWarn 은 AC 스크립트의 판정식과 같은 눈으로 본다 —
// 레벨이 ERROR·WARN 인 업로더 로그가 한 건도 없어야 한다.
func assertNoUploaderErrorOrWarn(t *testing.T, cap *logCapture) {
	t.Helper()
	cap.mu.Lock()
	defer cap.mu.Unlock()
	for _, r := range cap.records {
		if r.level >= slog.LevelWarn {
			t.Errorf("종료 경로인데 %v %s 가 나왔다 (%v)", r.level, r.msg, r.attrs)
		}
	}
}
