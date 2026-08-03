package upload

import (
	"context"
	"errors"
	"io/fs"
	"log/slog"
	"path/filepath"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// outcome 은 행 하나의 종결 분류다. 브레이커가 이 값만 보고 판단한다(설계 3.4절).
type outcome uint8

const (
	// outcomeSuccess — 올라갔고 장부에 확정됐다.
	outcomeSuccess outcome = iota
	// outcomeHard — 설정이 틀렸다는 신호다(401/403/404/PermanentRedirect).
	outcomeHard
	// outcomeSoft — 실패했지만 설정 문제라는 증거는 없다.
	outcomeSoft
	// outcomeNeutral — 판정 재료가 아니다. 보류·거부·마킹 실패가 전부 여기다.
	outcomeNeutral
	// outcomeShutdown — 종료 때문에 중단했다. 실패로 세지 않는다.
	outcomeShutdown
)

func (o outcome) String() string {
	switch o {
	case outcomeSuccess:
		return "success"
	case outcomeHard:
		return "hard"
	case outcomeSoft:
		return "soft"
	case outcomeNeutral:
		return "neutral"
	case outcomeShutdown:
		return "shutdown"
	default:
		return "unknown"
	}
}

// attemptKind 는 시도 1회의 결과가 재시도 루프를 어떻게 다루는지다.
type attemptKind uint8

const (
	// attemptDone — 이 시도로 행의 처리가 끝났다. outcome 을 그대로 쓴다.
	attemptDone attemptKind = iota
	// attemptFileMissing — 파일이 없다. 다음 시도에서 결과가 달라지지 않으므로
	// 재시도하지 않고 곧바로 후처리로 간다(M-3).
	attemptFileMissing
	// attemptPutFailed — PUT 이 실패했다. 이 하나만 재시도 루프를 지속시킨다.
	attemptPutFailed
)

// attemptResult 는 시도 1회의 결과다.
type attemptResult struct {
	kind    attemptKind
	outcome outcome
	err     error
}

// worker 는 큐를 비우는 고루틴 하나다.
//
// ctx 를 보지 않고 workerStop 만 본다 — 종료 순서를 Shutdown 이 단독으로 쥐기 위해서다.
func (u *Uploader) worker() {
	defer close(u.workerDone)
	for {
		select {
		case <-u.workerStop:
			return
		case j := <-u.queue:
			u.runJob(j)
		}
	}
}

// runJob 은 작업 1건의 수명을 감싼다. in-flight 해제와 브레이커 반영이 여기 한 곳이다.
func (u *Uploader) runJob(j job) {
	defer u.gate.releaseInflight(j.key())
	u.lastAttemptErr = nil
	o := u.processTarget(j)
	u.brk.record(o, u.lastAttemptErr)
}

// sweeper 는 재개 경로다. 회차 본체는 sweep.go 가 소유한다.
func (u *Uploader) sweeper(ctx context.Context) {
	defer close(u.sweepDone)
	<-ctx.Done()
}

// processTarget 은 작업 1건 처리다(설계 3.3절).
func (u *Uploader) processTarget(j job) outcome {
	t := j.target
	k := j.key()
	lg := u.log.With(
		"origin", j.origin.String(),
		"sweep_round", j.sweepRound,
		"stream_id", t.StreamID,
		"seq", t.Seq,
	)

	// [0] 대상 검증은 브레이커 판정보다 앞이다 — HalfOpen 의 probe 를 소비하면 안 된다.
	rel, ok := u.validateTarget(t, lg)
	if !ok {
		u.gate.quarantine(k)
		return outcomeNeutral
	}

	// [1] 브레이커 lazy 평가.
	if !u.brk.allowWork(k) {
		return outcomeNeutral
	}

	// [2] 재시도 루프. putFailed 만 루프를 지속시킨다(D-7).
	var lastErr error
	for attempt := 0; attempt < u.opt.RetryMax; attempt++ {
		alg := lg.With("attempt", attempt+1)
		r := u.attemptOnce(j, rel, alg)
		if r.kind == attemptFileMissing {
			return u.handleFileMissing(j, alg)
		}
		if r.kind != attemptPutFailed {
			return r.outcome
		}
		lastErr = r.err
		u.lastAttemptErr = r.err
		if attempt == u.opt.RetryMax-1 {
			break // 마지막 시도는 재시도 로그를 남기지 않는다
		}
		backoff := u.opt.RetryBase << attempt
		alg.Warn("upload_retry", "backoff", backoff.String(), "err", r.err.Error())
		if !u.waitRetry(backoff) {
			return outcomeShutdown
		}
	}

	// [3] 재시도 소진.
	return u.finalizeFailure(j, lg, lastErr)
}

// validateTarget 은 [0] 이다. rel 은 Root.Open 에 넘길 루트 기준 상대 경로다.
func (u *Uploader) validateTarget(t index.UploadTarget, lg *slog.Logger) (string, bool) {
	rel, err := filepath.Rel(filepath.Clean(u.opt.SegmentRoot), filepath.Clean(t.LocalPath))
	if err != nil {
		lg.Error("upload_target_rejected", "reason", "root_escape", "path", t.LocalPath, "s3_key", t.S3Key)
		return "", false
	}
	return rel, true
}

// waitRetry 는 백오프 대기다. 시간만 재는 sleep 이면 최악 종료가 백오프만큼 늘어난다.
func (u *Uploader) waitRetry(d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-u.putCtx.Done():
		return false
	case <-u.workerStop:
		return false
	}
}

// attemptOnce 는 시도 1회다. 파일의 수명이 이 함수 하나에 갇히므로
// 어떤 중도 return 에서도 FD 가 새지 않는다(CX-2 ③).
func (u *Uploader) attemptOnce(j job, rel string, lg *slog.Logger) attemptResult {
	t := j.target
	k := j.key()

	// (a) 열기
	f, err := u.opt.Root.Open(rel)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return attemptResult{kind: attemptFileMissing}
		}
		lg.Error("upload_target_rejected", "reason", "open_failed",
			"path", t.LocalPath, "s3_key", t.S3Key, "err", err.Error())
		u.gate.quarantine(k)
		return attemptResult{outcome: outcomeNeutral}
	}
	defer f.Close()

	// (b) 크기 — 오류를 버리지 않는다. fi 를 무조건 역참조하면 nil panic 이다.
	fi, err := f.Stat()
	if err != nil {
		lg.Warn("upload_stat_failed", "stage", "pre", "err", err.Error())
		return attemptResult{outcome: outcomeNeutral}
	}
	size := fi.Size()

	// (d) PUT — ContentLength 의 출처는 위 실측값이다.
	putCtx, cancel := context.WithTimeout(u.putCtx, u.opt.PutTimeout)
	defer cancel()
	start := u.now()
	if err := u.put.Put(putCtx, t.S3Key, f, size); err != nil {
		if u.putCtx.Err() != nil {
			lg.Debug("upload_aborted_shutdown")
			return attemptResult{outcome: outcomeShutdown}
		}
		return attemptResult{kind: attemptPutFailed, err: err}
	}
	elapsed := u.now().Sub(start)

	// (f) 마킹 — err 를 먼저 본다. (false, err) 를 CAS 거부로 오분류하면 안 된다(CX-2 ⑥).
	markCtx, markCancel := context.WithTimeout(u.markRoot, u.opt.MarkTimeout)
	defer markCancel()
	marked, err := u.st.MarkUploaded(markCtx, t.StreamID, t.Seq, t.Bytes)
	switch {
	case err != nil:
		lg.Error("mark_error", "target_state", "uploaded", "err", err.Error())
		u.gate.registerFailure(k)
		return attemptResult{outcome: outcomeNeutral}
	case !marked:
		lg.Warn("upload_cas_rejected", "expect_bytes", t.Bytes, "target_state", "uploaded")
		u.gate.registerFailure(k)
		return attemptResult{outcome: outcomeNeutral}
	}

	lg.Info("segment_uploaded", "s3_key", t.S3Key, "bytes", size,
		"elapsed_ms", elapsed.Milliseconds())
	u.gate.clearBackoff(k)
	u.sendResult(Result{StreamID: t.StreamID, Seq: t.Seq, State: index.UploadStateUploaded})
	return attemptResult{outcome: outcomeSuccess}
}

// handleFileMissing 은 ENOENT 후처리다(D-7 공식).
//
// 마킹이 먼저이고 격리는 마킹이 확정된 뒤에만 건다 — 격리는 프로세스 수명 동안 되돌릴 수
// 없고 마킹 실패는 다음 회차가 되돌릴 수 있으므로, 되돌릴 수 없는 조치를 뒤에 둔다(CX6-1).
func (u *Uploader) handleFileMissing(j job, lg *slog.Logger) outcome {
	t := j.target
	k := j.key()
	lg.Warn("upload_file_missing", "path", t.LocalPath)

	markCtx, cancel := context.WithTimeout(u.markRoot, u.opt.MarkTimeout)
	defer cancel()
	marked, err := u.st.MarkFailed(markCtx, t.StreamID, t.Seq, t.Bytes)
	switch {
	case err != nil:
		lg.Error("mark_error", "target_state", "failed", "err", err.Error())
		u.gate.registerFailure(k) // 격리하지 않는다 — 백오프가 재판정을 벌린다
		return outcomeNeutral
	case !marked:
		lg.Warn("upload_cas_rejected", "expect_bytes", t.Bytes, "target_state", "failed")
		u.gate.registerFailure(k)
		return outcomeNeutral
	}
	u.gate.quarantine(k)
	u.sendResult(Result{StreamID: t.StreamID, Seq: t.Seq, State: index.UploadStateFailed})
	return outcomeSoft
}

// finalizeFailure 는 [3] 재시도 소진이다.
func (u *Uploader) finalizeFailure(j job, lg *slog.Logger, lastErr error) outcome {
	t := j.target
	k := j.key()

	markCtx, cancel := context.WithTimeout(u.markRoot, u.opt.MarkTimeout)
	defer cancel()
	marked, err := u.st.MarkFailed(markCtx, t.StreamID, t.Seq, t.Bytes)
	switch {
	case err != nil:
		lg.Error("mark_error", "target_state", "failed", "err", err.Error())
		u.gate.registerFailure(k)
		return outcomeNeutral
	case !marked:
		lg.Warn("upload_cas_rejected", "expect_bytes", t.Bytes, "target_state", "failed")
		u.gate.registerFailure(k)
		return outcomeNeutral
	}

	class := classifyPutError(lastErr)
	u.gate.registerFailure(k)
	nextAt, _ := u.gate.backoffBlocked(k)
	lg.Error("upload_failed", "s3_key", t.S3Key, "attempts", u.opt.RetryMax,
		"err", errString(lastErr), "err_class", class.String(),
		"next_attempt_at", nextAt.UTC().Format(time.RFC3339))
	u.sendResult(Result{StreamID: t.StreamID, Seq: t.Seq, State: index.UploadStateFailed})

	if class == putErrHard {
		return outcomeHard
	}
	return outcomeSoft
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
