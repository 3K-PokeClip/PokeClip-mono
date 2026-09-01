package indexer

// 전수 수집의 이벤트 루프 합류(POK-168 r15a 설계 6.5.3 ⑴ — ADR-063 결정 3).
// 디렉터리 순회(collectTree)는 워커로 발사하고 결과를 loop select 의 case 로 받는다 —
// "밖으로 낸다"만으로는 부족하다: 지역 select 대기는 여전히 루프를 세운다.
//
// 세 신호의 국면 분리(정의가 계약이다):
//   scan_collect_truncated        ERROR  결과가 도착했고 truncated=true. 부분 결과는 처리된다.
//   scan_collect_stalled          ERROR  결과가 아직 안 왔고 경과 > soft budget × k —
//                                        holdTicks 가 CollectOverdue 로 판정, 한 시도에 한 번.
//   scan_collect_skipped_inflight WARN   앞 수집이 안 끝나 이번 주기를 건너뛴다. 정상 방어.
// 둘(truncated·stalled)은 한 시점에 배타적이고, 한 시도의 생애에서 stalled 뒤 truncated 는
// 정상 회복 서열이다.

import (
	"context"
	"errors"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// reentryBacklogThreshold 는 f6n 의 phase 구분 임계다(주기당 Handle 호출 수).
// 단언에 쓰지 않는다 — apply_normal/apply_backlog 라벨을 가르는 기록용 눈금일 뿐이다.
const reentryBacklogThreshold = 32

// collectResult 는 수집 워커가 돌려주는 것 전부다. 워커는 ix 의 맵을 만지지 않는다(D10) —
// 거부 디렉토리 경고 재료까지 결과에 실어 ApplyCollect(단일 고루틴)가 처리한다.
type collectResult struct {
	byStream  map[string][]recording.Segment
	rejected  map[string]error
	truncated bool
	elapsed   time.Duration
	err       error
}

// StartCollect 는 수집 워커를 발사하고 즉시 반환한다. 단일 비행이다 —
// 앞 수집이 안 끝났으면 이번 주기를 건너뛰고 WARN 을 남긴다(정상 방어).
//
// 세대 토큰은 두지 않는다: collectInflight 가 ApplyCollect 에서만 내려가므로 늦은 결과의
// 세대는 언제나 현재 세대이고, 채택돼도 scanStream 이 커서를 DB 에서 재적재하므로 무해하다.
func (ix *Indexer) StartCollect(ctx context.Context, root string) bool {
	if ix.collectInflight {
		ix.log.Warn("scan_collect_skipped_inflight", "root", root)
		return false
	}
	ix.collectInflight = true
	ix.collectStart = time.Now()
	ix.collectStalledWarned = false
	go func() {
		// 채널 버퍼가 1 이고 단일 비행이라 이 송신은 막히지 않는다 — 워커는 반드시 끝난다.
		ix.collectDoneCh <- ix.collectTree(ctx, root)
	}()
	return true
}

// CollectDone 은 수집 결과 채널이다. loop select 의 case 로만 소비한다.
func (ix *Indexer) CollectDone() <-chan collectResult { return ix.collectDoneCh }

// CollectOverdue 는 holdTicks 가 부른다 — 결과가 아직 안 왔고 경과가 soft budget × k 를
// 넘겼으면 한 시도에 한 번만 참을 돌려준다. scan_collect_stalled 의 판정 지점이다.
func (ix *Indexer) CollectOverdue(k float64) bool {
	if !ix.collectInflight || ix.collectStalledWarned {
		return false
	}
	if time.Since(ix.collectStart) <= time.Duration(k*float64(ix.opt.ScanCollectBudget)) {
		return false
	}
	ix.collectStalledWarned = true
	return true
}

// ApplyCollect 는 도착한 결과를 처리한다. 반환 firstComplete 는 "첫 완주"다 —
// Indexer 는 업로더 생애주기를 모르고(원칙 4), arm 판정의 소유자는 loop 다.
//
// 조기 반환 규약(설계 11.3 ⒠ 대칭): 래치·op 타임아웃·수집 실패 기인 반환은 nil 이다 —
// loop 이 이 에러를 프로세스 종료로 번역하므로, FS 열화가 에러가 되면 격리 장치가
// 사망 장치가 된다. 에러는 scanStream 이 "프로세스를 끝내야 하는 상황"으로 판정한
// 것(DB 재시도 소진·복구 불가 seq 충돌·ctx 취소)만 그대로 올린다.
func (ix *Indexer) ApplyCollect(ctx context.Context, root string, res collectResult) (bool, error) {
	ix.collectInflight = false // ①

	// f6n — 처리 점유 계측(기록 전용 · 단언 없음). 지표는 case 처리 시간이며 대기 시간을
	// 포함하지 않는다. phase 는 이 주기에 Handle 이 불린 횟수로 가른다: idle=0 /
	// apply_normal ≤ 임계 / apply_backlog > 임계. 임계는 기록만 한다 — 처리 점유는
	// 0 이 아니라 백로그에 비례하고, 상한을 약속하지 않는다(설계 6.5.3 ⓕ-ⓒ).
	applyStart := time.Now()
	handleBefore := ix.handleCount
	defer func() {
		handled := ix.handleCount - handleBefore
		phase := "idle"
		switch {
		case handled == 0:
		case handled <= reentryBacklogThreshold:
			phase = "apply_normal"
		default:
			phase = "apply_backlog"
		}
		ix.log.Debug("loop_select_reentry_seconds", "phase", phase,
			"seconds", time.Since(applyStart).Seconds(),
			"handled", handled, "threshold", reentryBacklogThreshold)
	}()

	// ② 결과 신호 — 거부 경고(워커가 못 만진 ix 맵)와 실패·절단.
	for dir, cause := range res.rejected {
		ix.warnRejectedStream(dir, cause)
	}
	if res.err != nil {
		if !errors.Is(res.err, context.Canceled) {
			ix.log.Error("scan_collect_failed", "root", root, "err", res.err)
		}
		return false, nil
	}
	if res.truncated && ctx.Err() == nil {
		// 부모 ctx 취소(SIGTERM)로 멈춘 것은 절단이 아니다(f6j 대조군 — ctx.Err() 판별).
		ix.log.Error("scan_collect_truncated", "root", root,
			"budget", ix.opt.ScanCollectBudget, "collect_seconds", res.elapsed.Seconds())
	}

	// ③ 래치 해제 시도 — 주기 머리에서 root 프로브 1회.
	ix.fsLatch.Reset(root, ix.opt.FSOpTimeout)

	// ④ 스트림 루프 — 매 스트림 ctx·래치 확인(f6g). 부분 결과(절단)도 처리된다.
	interrupted := false
	for streamID, segs := range res.byStream {
		if ctx.Err() != nil {
			interrupted = true
			break
		}
		if ix.fsLatch.Tripped() {
			interrupted = true
			ix.log.Warn("fs_latch_early_return", "site", "apply_collect", "stream_id", streamID)
			break
		}
		if err := ix.scanStream(ctx, root, streamID, segs); err != nil {
			return false, err
		}
	}

	// ⑤ 루프 **종료 후** 래치 재검사 — 다음 스트림 진입 전에만 검사하면, 스트림이 하나뿐이거나
	// 마지막 스트림 처리 중 트립될 때 완주로 오판해 커서 미완인데 스위퍼가 열린다(f6p ⓒ).
	if ix.fsLatch.Tripped() {
		interrupted = true
	}

	// ⑥ 완주 판정 — 절단 아님 ∧ 중단 없음 ∧ 아직 첫 완주 전이면 firstComplete.
	if res.truncated || interrupted || ctx.Err() != nil || ix.firstCollectDone {
		return false, nil
	}
	ix.firstCollectDone = true
	return true, nil
}
