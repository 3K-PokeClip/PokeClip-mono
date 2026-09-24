package upload

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// sweeper 는 재개 경로다. failed·pending 은 종국 상태가 아니며(G11′) 이 고루틴이 다시 집는다.
//
// 축별 진행(재개 커서·연속 정체 회차 수)은 회차 사이에 살아남는 고루틴 로컬이다.
// 커서는 **단조 전진이거나 불변**이며, 뒤로 가는 경우는 끝에 도달했을 때의 순환 하나뿐이다.
func (u *Uploader) sweeper(ctx context.Context) {
	defer close(u.sweepDone)

	// arm 대기(POK-168 M1) — 첫 완주 수집 전에 스위프가 돌면 인덱서가 곧 요청할 행을
	// 먼저 집어 in-flight 로 막는다(구 main.go:152-153 이 막던 원 상태). 미arm 상태의
	// Shutdown 은 sweepCancel 이 이 대기를 깨워 안전하다(F-42).
	if !u.waitArm(ctx) {
		return
	}

	var progress map[index.Axis]axisProgress
	ticker := time.NewTicker(u.opt.SweepEvery)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			u.tidySessions()
			progress = u.sweepOnce(ctx, progress)
		}
	}
}

// waitArm 은 arm 신호·폴백 경과·종료 중 먼저 오는 것을 기다린다.
// 반환 false = 종료(스위프 없이 sweepDone 을 닫는다).
//
// 폴백 하한의 근거(설계 11.3): RescanEvery 는 설정값이라 낮추면 폴백이 정상 첫 수집보다
// 먼저 와 원 상태가 재현된다 — 그래서 config 가 max(2×RescanEvery, 2×수집예산)로 계산해
// 넣는다. 폴백 경과 arm 은 무조건 arm + sweeper_arm_fallback WARN 이다(f6p ⓔ) —
// 10분 넘게 첫 완주가 없다면 이미 더 큰 문제(FS 정지)고 scan_collect_stalled 가
// 90초부터 그것을 말하고 있다.
func (u *Uploader) waitArm(ctx context.Context) bool {
	var fallback <-chan time.Time
	if u.opt.ArmFallback > 0 {
		t := time.NewTimer(u.opt.ArmFallback)
		defer t.Stop()
		fallback = t.C
	}
	select {
	case <-ctx.Done():
		return false
	case <-u.armCh:
		return true
	case <-fallback:
		u.log.Warn("sweeper_arm_fallback", "after", u.opt.ArmFallback,
			"note", "첫 완주 수집 없이 폴백으로 arm 한다. 커서 미완이면 스위퍼가 행을 선점할 수 있다(성숙 판정은 IsTail 재검·TailGrace 가 담당)")
		return true
	}
}

// sweepAxes 는 스위퍼 회차가 도는 축과 그 순서다(설계 5.5.4 #2·#7). 축마다 장부의 자격 술어가
// 달라 조회도 잔량 집계도 축별 한 벌씩이다 — ③ 은 컷오프-인지이고 init 은 조각이 아니라 세션을
// 센다(index 의 축별 문장).
//
// ② 가 맨 앞인 이유: 세 축이 큐 하나를 나눠 쓰고 앞 축이 먼저 자리를 잡는다. ② 를 앞에 두어 M3
// 가 재던 ② 회차 계약(G16⁗ — 회차 시작 때 큐가 비어 있으면 1·2단계를 다 접수한다)을 그대로 둔다.
// 뒤 축은 남은 자리를 쓰고, 큐가 차서 못 든 행은 그 축 커서가 멈춰 다음 회차에 다시 본다.
var sweepAxes = [...]index.Axis{index.AxisArchive, index.AxisPlayback, index.AxisInit}

// axisProgress 는 한 축이 회차 사이에 들고 가는 두 값이다 — 재개 커서와 연속 정체 회차 수.
//
// 축마다 따로 드는 이유: 커서의 마지막 정렬 키부터 축마다 다르고(index.SweepCursor — init 은
// SessionID 로 끝난다) 한 축의 커서를 다른 축 조회에 넘기면 엉뚱한 자리부터 훑는다. 정체도 그 축
// 커서의 사정이라, 한 축의 큐 포화가 다른 축의 정체 이력을 지우거나 부풀리면 안 된다.
type axisProgress struct {
	resume  index.SweepCursor
	stalled int
}

// sweepStage 는 한 단계가 무엇을 했는지다. 회차 말미 요약이 이 값을 그대로 싣는다.
type sweepStage struct {
	stage    int
	ran      bool
	picked   int
	examined int
	admitted int
	pages    int
	aborted  bool
	wrapped  bool
	// failed 는 이 단계가 DB 조회에 실패했는가다. QueueFull 중단(aborted)과 구분한다 —
	// 전자는 커서 정체가 아니므로 stalled 카운터를 건드리면 안 된다.
	failed bool

	// cursor 는 이 단계가 검사를 마친 위치이고, advanced 가 false 면 전진분이 없다.
	cursor   index.SweepCursor
	advanced bool

	// rows·next 는 1단계 결과를 2단계가 재조회 없이 승계하기 위한 것이다(R3).
	rows []index.UploadTarget
	next index.SweepCursor
}

func (s sweepStage) rejected() int { return s.examined - s.admitted }

// sweepOnce 는 회차 1번이다. 회차 ID(sweepRound)를 올리는 유일한 지점이다.
//
// 한 회차가 sweepAxes 의 축을 순서대로 한 번씩 돈다. 회차 ID 는 그 축들이 함께 쓴다 — 회차는 tick
// 하나다. 넘겨받은 진행은 고치지 않고 새 맵으로 돌려준다(nil 이면 모든 축이 처음부터다).
func (u *Uploader) sweepOnce(ctx context.Context, progress map[index.Axis]axisProgress) map[index.Axis]axisProgress {
	u.sweepRound++
	next := make(map[index.Axis]axisProgress, len(sweepAxes))
	for _, a := range sweepAxes {
		next[a] = u.sweepAxis(ctx, a, progress[a])
	}
	return next
}

// sweepAxis 는 한 축의 회차다. 단계·커서·정체 규칙은 축과 무관하게 같고, 축은 조회·잔량 집계의
// 문장(index 의 축별 벌)과 로그 라벨을 고른다(설계 5.5.4 #2·#7·#8).
func (u *Uploader) sweepAxis(ctx context.Context, a index.Axis, p axisProgress) axisProgress {
	// axis 라벨은 그 축 회차 로그 전체에 실린다(설계 5.5.4 #8). 라벨과 조회를 같은 a 로 고르는
	// 것이 계약이다 — 둘이 갈리면 ③ 의 잔량·커서·중단이 다른 축의 수치로 읽힌다(#7).
	lg := u.log.With("sweep_round", u.sweepRound, "origin", OriginSweep.String(),
		"axis", a.String())

	s1 := u.sweepStage1(ctx, lg, a)
	s2 := sweepStage{stage: 2}
	// 1단계가 조회에 실패했으면 2단계도 돌리지 않는다. 승계할 페이지가 없는데 진행하면
	// 빈 결과가 "끝 도달 = 순환"으로 흘러가 아무것도 훑지 않은 회차가
	// upload_sweep(stage=2, picked=0, cursor_wrapped=true)로 오관측된다(L-4).
	if !s1.aborted && !s1.failed {
		// 2단계는 1단계의 접수 성공 여부와 무관하게 반드시 진행한다 — 신규 세그먼트가
		// 계속 유입되면 1단계가 매 회차 성공하는데, 거기서 끝내면 resumeCursor 가
		// 영원히 전진하지 않는다(결정 5⁵).
		s2 = u.sweepStage2(ctx, lg, a, p.resume, s1)
	}

	// 회차 말미 집계는 **중단 회차에서도 반드시 실행된다**(M-2).
	// 중단은 조기 return 이 아니라 페이지 루프 탈출이다 — 만성 포화일수록 이 관측이 필요한데
	// 즉시 반환으로 구현하면 경고가 전멸해 CX6-3 처방이 무효가 된다.
	stalled := p.stalled
	switch {
	case s1.aborted || s2.aborted:
		stalled++
	case s1.failed || s2.failed:
		// 조회 실패는 큐 포화가 아니다. 직전 값을 보존해 진짜 정체 이력을 지우지 않는다.
	default:
		stalled = 0
	}
	u.sweepSummary(ctx, lg, a, []sweepStage{s1, s2}, stalled)

	if s2.advanced {
		return axisProgress{resume: s2.cursor, stalled: stalled}
	}
	// 전진분이 없으면 커서를 손대지 않는다. 되감기지도, 앞서가지도 않는다.
	return axisProgress{resume: p.resume, stalled: stalled}
}

// sweepStage1 은 zero 커서 1페이지다. 최신 pending 을 무조건 먼저 확보한다.
//
// 이 단계의 결과 커서를 resumeCursor 에 흘려 넣으면 안 된다 — zero 커서에서 출발하므로
// 그 값은 정렬 맨 앞이고, 대입하면 커서가 되감긴다(H-1). 2단계가 승계할 때만 쓴다.
func (u *Uploader) sweepStage1(ctx context.Context, lg *slog.Logger, a index.Axis) sweepStage {
	s := sweepStage{stage: 1, ran: true, pages: 1}

	rows, next, err := u.st.PendingUploads(ctx, a, u.opt.TailGrace.Seconds(), u.opt.SweepLimit, index.SweepCursor{})
	if err != nil {
		logSweepQueryFailure(lg, 1, err)
		s.ran, s.failed = false, true
		return s
	}
	s.picked = len(rows)
	s.rows, s.next = rows, next

	for _, t := range rows {
		switch u.enqueue(t, OriginSweep) {
		case EnqueueQueueFull:
			// ★ resumeCursor 는 손대지 않는다 — 이 회차의 resume 전진은 0행이다(CX6-3 · L21).
			s.aborted = true
			lg.Debug("sweep_aborted_queue_full", "stage", 1,
				"picked", s.picked, "examined", s.examined, "admitted", s.admitted,
				"pages", s.pages, "cursor_wrapped", false)
			return s
		case EnqueueAdmitted:
			s.examined++
			s.admitted++
		default:
			// Rejected 도 검사 완료다 — "지금 이 행에 할 일이 없다"는 판정이 끝난 것이다.
			s.examined++
		}
	}
	if s.picked > 0 {
		s.cursor, s.advanced = next, true
	}
	return s
}

// sweepStage2 는 resume 커서에서 이어 도는 페이지 루프다.
//
// lastExamined 는 이 단계 전용이며 **진입 초기값이 계약이다** — 단계 공유 변수로 두면
// 2단계 첫 행이 QueueFull 일 때 커서가 1단계 위치(정렬 맨 앞)로 되감겨 옛 행 기아가
// 세 번째 문으로 재발한다(H-1).
func (u *Uploader) sweepStage2(ctx context.Context, lg *slog.Logger, a index.Axis, resume index.SweepCursor, s1 sweepStage) sweepStage {
	s := sweepStage{stage: 2, ran: true}

	// 1단계 결과를 승계할 것인가. 재조회만 생략하며 enqueue 를 다시 부르지 않는다(R3) —
	// 다시 부르면 방금 Admitted 된 행이 전부 in-flight 로 Rejected 가 되어
	// "이 페이지는 접수가 없었다"는 잘못된 결론이 나고 회차 계산이 붕괴한다.
	reuse := resume.IsZero()
	cur := resume

	for page := 1; page <= u.opt.SweepMaxPages-1; page++ {
		var (
			rows []index.UploadTarget
			next index.SweepCursor
		)

		if reuse && page == 1 {
			rows, next = s1.rows, s1.next
			s.pages++
			s.picked += s1.picked
			// N-4 — examined2·admitted2 는 1단계 것을 **둘 다** 승계한다.
			s.examined += s1.examined
			s.admitted += s1.admitted
			// 이 분기에서 1단계 커서를 쓰는 것은 되감김이 아니다 —
			// 2단계의 첫 페이지가 곧 1단계 페이지이므로 정당한 전진 위치다.
			if s1.advanced {
				s.cursor, s.advanced = s1.cursor, true
			}
		} else {
			var err error
			rows, next, err = u.st.PendingUploads(ctx, a, u.opt.TailGrace.Seconds(), u.opt.SweepLimit, cur)
			if err != nil {
				logSweepQueryFailure(lg, 2, err)
				s.failed = true
				return s
			}
			s.pages++
			s.picked += len(rows)

			aborted := false
			for _, t := range rows {
				switch u.enqueue(t, OriginSweep) {
				case EnqueueQueueFull:
					aborted = true
				case EnqueueAdmitted:
					s.examined++
					s.admitted++
				default:
					s.examined++
				}
				if aborted {
					break
				}
			}
			if aborted {
				// ★ 이 페이지에서 검사한 행은 커서로 옮기지 않는다. 전진은 페이지 단위이며
				//   되감김이 없다 — 이미 검사한 행은 다음 회차에 다시 검사될 뿐이다(at-least-once).
				s.aborted = true
				lg.Debug("sweep_aborted_queue_full", "stage", 2,
					"picked", s.picked, "examined", s.examined, "admitted", s.admitted,
					"pages", s.pages, "cursor_wrapped", false)
				return s
			}
			// 이 페이지를 끝까지 검사했다 = 여기까지 커서를 옮겨도 된다.
			s.cursor, s.advanced = next, true
		}

		// 분기 우선순위는 rev6 N6 확정 그대로다.
		// ① QueueFull 은 위에서 이미 처리했다.
		// ② 이 페이지에 Admitted 가 하나라도 있었다 → 회차 종료(최소 1페이지를 채웠다).
		if s.admitted > 0 {
			return s
		}
		// ③ 전부 Rejected 이고 페이지가 꽉 찼다 → 다음 페이지로 넘어간다.
		if len(rows) == u.opt.SweepLimit {
			cur = next
			continue
		}
		// ④ 끝에 도달했다 → 순환.
		s.cursor, s.advanced, s.wrapped = index.SweepCursor{}, true, true
		return s
	}
	return s
}

// sweepSummary 는 한 축 회차의 말미 집계다. 실행된 단계만 요약을 낸다(N-6). 잔량은 그 축의 것만
// 센다 — 축 라벨은 수치와 함께 갈려야 한다(설계 5.5.4 #7).
func (u *Uploader) sweepSummary(ctx context.Context, lg *slog.Logger, a index.Axis, stages []sweepStage, stalled int) {
	for _, s := range stages {
		if !s.ran {
			continue
		}
		lg.Debug("upload_sweep",
			"picked", s.picked, "examined", s.examined, "admitted", s.admitted,
			"rejected", s.rejected(), "pages", s.pages,
			"cursor_wrapped", s.wrapped, "stage", s.stage)
	}

	pending, failed, bytesNull, err := u.st.CountBacklog(ctx, a)
	if err != nil {
		logSweepQueryFailure(lg, 0, err)
		return
	}
	queueLen := len(u.queue)
	if pending+failed > u.opt.BacklogWarn || int64(queueLen) > int64(u.opt.QueueWarnLen) ||
		bytesNull > 0 || stalled > 0 {
		lg.Warn("upload_backlog",
			"pending", pending, "failed", failed, "bytes_null", bytesNull,
			"queue_len", queueLen, "cursor_stalled_rounds", stalled,
			"backlog_warn", u.opt.BacklogWarn, "queue_warn_len", u.opt.QueueWarnLen)
	}
	if stalled >= u.opt.CursorStallWarn {
		// 큐 포화가 지속된다 = 워커가 유입을 못 따라간다. 처치는 설계가 아니라 운영·이월이다(L21).
		lg.Warn("sweep_cursor_stalled",
			"stalled_rounds", stalled, "limit", u.opt.CursorStallWarn, "queue_len", queueLen)
	}
}

// logSweepQueryFailure 는 DB 조회 실패를 남긴다. stage 0 은 회차 말미 집계 블록이다.
//
// ctx 취소·마감은 **실패가 아니라 종료 신호다.** ERROR 로 내면 계획 5.2·6.4절의
// "업로더 기인 ERROR·WARN 0건" 판정이 SIGTERM 한 번으로 위양성 FAIL 이 된다 —
// 종료는 정상 경로이므로 계측을 깨서는 안 된다. 이름은 그대로 두고 레벨만 내린다.
func logSweepQueryFailure(lg *slog.Logger, stage int, err error) {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		lg.Debug("upload_sweep_failed", "stage", stage, "reason", "canceled", "err", err.Error())
		return
	}
	lg.Error("upload_sweep_failed", "stage", stage, "err", err.Error())
}
