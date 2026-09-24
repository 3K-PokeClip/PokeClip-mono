package upload

import (
	"context"
	"errors"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
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
	// attemptRetryable — PUT 이나 ③·init 산출(재포장·mtxi 판독)이 실패했다. 이 하나만 재시도
	// 루프를 지속시킨다 — 산출 실패도 PUT 실패와 같은 사다리를 탄다(계획 2.1 · A6). 단 같은 입력이면
	// 늘 같은 산출 실패는 여기 오지 않고 첫 시도에서 끝난다(axis_body.go sameInputFailsAgain).
	attemptRetryable
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
//
// 브레이커는 그 작업의 축 것 하나만 만진다(설계 5.5.3). 축을 모르는 작업은 접수 게이트가
// 이미 걸렀지만 여기서 한 번 더 본다 — 호출부 열거로 막지 않는 것이 이 패키지의 규율이다.
//
// 끝나면(in-flight 를 푼 뒤) 보류 목록에 남은 재요청을 다시 넣어 본다 — 큐 포화로 들지 못한
// 작업이 다음 완료를 기다린다(계획 2.1 「다음 워커 완료/tick 에 다시 drain」).
func (u *Uploader) runJob(j job) {
	defer u.drainHeld()
	defer u.gate.releaseInflight(j.key())
	u.lastAttemptErr = nil
	o := u.processTarget(j)
	if brk := u.brk.of(j.target.Axis); brk != nil {
		brk.record(o, u.lastAttemptErr)
	}
}

// processTarget 은 작업 1건 처리다(설계 3.3절).
func (u *Uploader) processTarget(j job) outcome {
	j.target = withInitKey(j.target)
	t := j.target
	k := j.key()
	lg := u.log.With(
		"origin", j.origin.String(),
		"sweep_round", j.sweepRound,
		"stream_id", t.StreamID,
		"axis", t.Axis.String(),
		"seq", t.Seq,
	)

	// [0] 대상 검증은 브레이커 판정보다 앞이다 — HalfOpen 의 probe 를 소비하면 안 된다.
	rel, ok := u.validateTarget(t, lg)
	if !ok {
		u.gate.quarantine(k)
		return outcomeNeutral
	}

	// [1] 브레이커 lazy 평가. 축은 [0] 이 이미 판정했지만 nil 을 다시 본다 —
	//     판정 순서가 바뀌는 날 조용히 nil 역참조가 되지 않게 한다.
	brk := u.brk.of(t.Axis)
	if brk == nil || !brk.allowWork(k) {
		return outcomeNeutral
	}

	// [2] 재시도 루프. retryable 만 루프를 지속시킨다(D-7).
	var lastErr error
	for attempt := 0; attempt < u.opt.RetryMax; attempt++ {
		alg := lg.With("attempt", attempt+1)
		r := u.attemptOnce(j, rel, alg)
		if r.kind == attemptFileMissing {
			return u.handleFileMissing(j, alg)
		}
		if r.kind != attemptRetryable {
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
	return u.finalizeFailure(j, rel, lg, lastErr)
}

// markPlan 은 이 축이 이번 마킹에서 무엇을 하는가다(계획 4.5 — 마킹 3지점 공용).
type markPlan uint8

const (
	// markCAS — 그 축의 장부 CAS 를 실행한다(설계 5.5.5 — ② · ③ · init 성공).
	markCAS markPlan = iota
	// markSkip — 이 축에는 이 상태를 적는 컬럼이 없다(init 실패 — 설계 5.5.5 는 init 에
	// 성공 CAS 한 문장만 정의한다). 장부를 건드리지 않고 후처리만 이어간다.
	markSkip
	// markRefuse — 모르는 축이다. 흘려보내면 그 결과가 다른 축의 CAS 로 새어 엉뚱한 열을
	// 바꾸므로 거부한다(fail-closed).
	markRefuse
)

// planMark 는 축과 목표 상태로 마킹 갈래를 고른다.
//
// **기본 갈래가 markRefuse 인 것이 계약이다.** 축을 열거해 CAS 를 고르고 나머지는 거부한다 —
// "init 이면 건너뛰고 아니면 마킹"으로 쓰면 모르는 축의 결과가 ② 의 CAS 로 흘러간다(계획 4.5).
func planMark(a index.Axis, target index.UploadState) markPlan {
	switch {
	case a == index.AxisArchive, a == index.AxisPlayback:
		return markCAS
	case a == index.AxisInit && target == index.UploadStateUploaded:
		return markCAS
	case a == index.AxisInit && target == index.UploadStateFailed:
		return markSkip
	default:
		return markRefuse
	}
}

// s3KeyRe 는 ② 아카이브 축 예약 키의 전체 문법이다. index.S3Key 로 재계산해 비교하지는 않는다 —
// "예약 키 그대로 PUT"이 계약이라 재계산 비교는 키 포맷을 바꾸는 순간 옛 행을 전부 거부한다.
var s3KeyRe = regexp.MustCompile(`^streams/([A-Za-z0-9_-]{1,64})/\d{4}-\d{2}-\d{2}/\d{2}/seg_(\d{6,})\.m4s$`)

// playbackKeyRe 는 ③ 재생 축 키의 문법이다 — 설계 5.2 의 형상 그대로다(playback.SegKey).
var playbackKeyRe = regexp.MustCompile(`^dvr/([A-Za-z0-9_-]{1,64})/seg/(\d{6,})\.m4s$`)

// initKeyRe 는 init(MAP) 축 키의 문법이다 — 설계 5.2·5.3ⓐ(playback.InitKey).
// **둘째 그룹이 seq 가 아니라 sessionID 인 것이 축의 차이다.**
//
// 세션 성분에 점을 받지 않는다: playback.InitKey 는 `.`·`..` 를 명시로 거부하고(key.go:22),
// 세션 ID 문법은 S-{YYYYMMDD}-{HHMMSS}-{streamID}-{seq} 로 확정됐으며
// (session/registry.go:460) streamID 화이트리스트가 [A-Za-z0-9_-]{1,64} 라 점 자체가
// 나올 수 없다. 그래서 좁혀도 정상 대상이 영구 격리(processTarget 에서 validateTarget 실패 시
// quarantine)될 길이 없고, 경로 참조가 키에 실리는 길만 닫힌다.
var initKeyRe = regexp.MustCompile(`^dvr/([A-Za-z0-9_-]{1,64})/init/([A-Za-z0-9_-]+)\.mp4$`)

// keyPatternFor 는 축의 키 문법이다(설계 5.5.4 #3 — 축별 3벌).
// 미판정 축은 nil 이다 — 호출자가 그 자리에서 거부한다(fail-closed).
func keyPatternFor(a index.Axis) *regexp.Regexp {
	switch a {
	case index.AxisArchive:
		return s3KeyRe
	case index.AxisPlayback:
		return playbackKeyRe
	case index.AxisInit:
		return initKeyRe
	default:
		return nil
	}
}

// keyIdentityMismatch 는 키의 둘째 성분이 대상의 식별자와 같은지 본다.
// 어긋나면 거부 사유를, 맞으면 빈 문자열을 돌려준다.
//
// ②·③ 는 seq 이고 init 은 sessionID 다 — init 축에서 UploadTarget.Seq 는 쓰지 않으므로
// seq 대조를 그대로 두면 언제나 0 과 비교하게 된다(계획 4.5).
func keyIdentityMismatch(t index.UploadTarget, got string) string {
	if t.Axis == index.AxisInit {
		if got != t.SessionID {
			return "session_mismatch"
		}
		return ""
	}
	seq, err := strconv.ParseInt(got, 10, 64)
	if err != nil || seq != t.Seq {
		return "seq_mismatch"
	}
	return ""
}

// validateTarget 은 [0] 대상 검증이다(G17′ · 결정 15″). rel 은 Root.Open 에 넘길
// 루트 기준 상대 경로다.
//
// ★ 판정 순서가 계약이다. 루트 이탈 판정이 디렉토리 일치 판정보다 **앞**이다 —
// 두 조건을 동시에 위반하는 입력(예: /home/sidecar/.aws/credentials)에서 dir_mismatch 가
// 먼저 나오면 AC5 의 3중 필터가 위음성 FAIL 이 된다(r5 · cc M-1).
func (u *Uploader) validateTarget(t index.UploadTarget, lg *slog.Logger) (string, bool) {
	reject := func(reason string) (string, bool) {
		lg.Error("upload_target_rejected", "reason", reason,
			"path", t.LocalPath, "s3_key", t.S3Key)
		return "", false
	}

	// 화이트리스트의 소유자는 recording.ValidStreamID 하나다. 정규식을 복사하지 않는다.
	if !recording.ValidStreamID(t.StreamID) {
		return reject("bad_stream_id")
	}
	// 키 문법은 축이 고른다. 축을 모르면 어떤 문법으로 볼지도 정할 수 없으므로 거부한다.
	re := keyPatternFor(t.Axis)
	if re == nil {
		return reject("bad_axis")
	}
	m := re.FindStringSubmatch(t.S3Key)
	if m == nil || m[1] != t.StreamID {
		return reject("bad_key")
	}
	if reason := keyIdentityMismatch(t, m[2]); reason != "" {
		return reject(reason)
	}

	rel, err := filepath.Rel(filepath.Clean(u.opt.SegmentRoot), filepath.Clean(t.LocalPath))
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return reject("root_escape")
	}
	if filepath.Dir(rel) != t.StreamID {
		return reject("dir_mismatch")
	}
	if t.Bytes <= 0 {
		// bytes 가 없는 행은 처리 불가 데이터 오류다. coalesce 로 펴면 꼬리 대조와 CAS 가
		// 영원히 실패해 조회 창만 낭비한다(결정 14).
		return reject("bad_bytes")
	}
	if t.Axis == index.AxisPlayback && t.SessionID == "" {
		// ③ 은 세션 없이는 기대 init 도 보정값 표도 찾을 수 없고, 불일치를 세션에 결속할 수도
		// 없다. 세션 없는 조각의 ③ 은 애초에 만들지 않으므로(계획 2.1) 여기 온 것은 호출자 결함이다.
		return reject("no_session")
	}
	return rel, true
}

// classifyOpenError 는 Root.Open 실패를 세 갈래로 가른다.
//
// EMFILE·ENFILE 은 **일시적 자원 고갈**이라 격리하면 안 된다 — 격리는 되돌릴 수 없는데
// 원인은 곧 사라진다. 나머지(EACCES·ELOOP·ErrInvalid·루트 이탈)는 재시도해도 결과가
// 같으므로 즉시 격리한다(CX-2 ①).
func (u *Uploader) classifyOpenError(err error, t index.UploadTarget, k targetKey, lg *slog.Logger) attemptResult {
	if errors.Is(err, fs.ErrNotExist) {
		// outcome 을 명시한다. 호출자가 kind 를 먼저 보므로 지금은 쓰이지 않지만,
		// 제로값(success)을 남겨 두면 분기 순서가 바뀌는 날 ENOENT 가 성공으로
		// 브레이커에 집계된다.
		return attemptResult{kind: attemptFileMissing, outcome: outcomeNeutral}
	}
	if errors.Is(err, syscall.EMFILE) || errors.Is(err, syscall.ENFILE) {
		lg.Warn("upload_open_transient", "errno", err.Error())
		return attemptResult{outcome: outcomeNeutral}
	}
	lg.Error("upload_target_rejected", "reason", "open_failed",
		"path", t.LocalPath, "s3_key", t.S3Key, "err", err.Error())
	u.gate.quarantine(k)
	return attemptResult{outcome: outcomeNeutral}
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
		return u.classifyOpenError(err, t, k, lg)
	}
	defer f.Close()

	// (b)·(c) 입력 크기
	size, r, ok := u.measureInput(j, f, lg)
	if !ok {
		return r
	}

	// (c′) 본문 — ② 는 파일 그대로, ③·init 은 그 파일을 재포장한 메모리 산출이다.
	p, r, ok := u.payloadFor(j, f, size, lg)
	if !ok {
		return r
	}

	// (d) PUT — ContentLength 의 출처는 본문의 실측 길이다.
	putCtx, cancel := context.WithTimeout(u.putCtx, u.opt.PutTimeout)
	defer cancel()
	start := u.now()
	if err := u.put.Put(putCtx, t.S3Key, p.body, p.size); err != nil {
		if u.putCtx.Err() != nil {
			lg.Debug("upload_aborted_shutdown")
			return attemptResult{outcome: outcomeShutdown}
		}
		return attemptResult{kind: attemptRetryable, err: err}
	}
	elapsed := u.now().Sub(start)

	// (e) 같은 fd 로 **입력 파일**을 다시 잰다. 경로가 아니라 열린 inode 를 재므로 경로 경합이
	//     없다. ③ 도 입력을 잰다 — 산출 길이는 입력이 같으면 늘 같아 잴 뜻이 없다(계획 2.1
	//     「③ 축 크기 계약」). init 은 재도 멈추지 않는다 — 올린 머리말 바이트는 입력의 꼬리와
	//     무관하다(measureInput). 이 두 분기는 백오프에 등록하지 않는다 — "파일이 방금 바뀌었다"는
	//     양의 증거를 본 경우이고, M-1 표에 없는 분기다(C10).
	fi2, err := u.statFile(f)
	if err != nil {
		lg.Warn("upload_stat_failed", "stage", "post", "err", err.Error())
		return attemptResult{outcome: outcomeNeutral}
	}
	if fi2.Size() != size && t.Axis != index.AxisInit {
		lg.Warn("file_changed_during_put", "put_size", size, "after_size", fi2.Size())
		return attemptResult{outcome: outcomeNeutral}
	}

	// (f) 마킹 — 축 분기는 markUploadedByAxis 가 진다.
	if !u.markUploadedByAxis(j, p, lg) {
		return attemptResult{outcome: outcomeNeutral}
	}

	lg.Info("segment_uploaded", "s3_key", t.S3Key, "bytes", p.size,
		"elapsed_ms", elapsed.Milliseconds())
	u.gate.clearBackoff(k)
	u.afterUploaded(j, p)
	return attemptResult{outcome: outcomeSuccess}
}

// measureInput 은 (b) 입력 파일의 크기를 재고 (c) 장부 크기와 대조한다. ok 가 false 면
// 이번 시도는 r 로 끝난다.
func (u *Uploader) measureInput(j job, f *os.File, lg *slog.Logger) (size int64, r attemptResult, ok bool) {
	t := j.target

	// (b) 크기 — 오류를 버리지 않는다. fi 를 무조건 역참조하면 nil panic 이다.
	fi, err := u.statFile(f)
	if err != nil {
		lg.Warn("upload_stat_failed", "stage", "pre", "err", err.Error())
		return 0, attemptResult{outcome: outcomeNeutral}, false
	}
	if !fi.Mode().IsRegular() {
		lg.Error("upload_target_rejected", "reason", "not_regular",
			"path", t.LocalPath, "s3_key", t.S3Key)
		u.gate.quarantine(j.key())
		return 0, attemptResult{outcome: outcomeNeutral}, false
	}
	size = fi.Size()

	// (c) 크기 재확인은 경로가 아니라 **대상의 속성**이다(결정 4⁵).
	if size != t.Bytes {
		if t.IsTail && t.Axis != index.AxisInit {
			// 꼬리는 아직 자라는 중일 수 있다. 여기서 올리면(③ 이면 재포장하면) 잘린 실물이
			// 굳는다(G12‴ · 계획 2.1 「③ 축 크기 계약」).
			u.logTailGrowing(j, lg, t.Bytes, size)
			return 0, attemptResult{outcome: outcomeNeutral}, false
		}
		// 비꼬리는 correctTail 이 애초에 못 고치므로 막아서 얻을 것이 없고,
		// 막으면 그 조각이 영원히 안 올라간다. 올리되 장부 어긋남을 남긴다(L14).
		// init 은 꼬리여도 여기로 온다 — init 산출은 머리말(ftyp+moov)만의 순수 함수라 파일이 꼬리에서
		// 더 자라도 바이트가 같다(잘린 실물이 없다). 막으면 재기동 뒤 이어지는 회차의 sessionInit 이
		// 되살아나지 않는다 — 스위퍼 init 벌은 이미 확정된 회차를 집지 않는다(cc r3 ⑦-1 선택 개선).
		// 크기 키는 뜻을 축마다 고정한다: ② 는 파일을 그대로 올려 입력 크기가 곧 PUT 크기이고,
		// ③·init 은 재포장 산출을 올리므로 이 값은 입력 크기일 뿐이다(PUT 크기가 아니다).
		sizeKey := "put_bytes"
		if t.Axis != index.AxisArchive {
			sizeKey = "input_bytes"
		}
		lg.Warn("upload_size_mismatch", "db_bytes", t.Bytes, sizeKey, size)
	}
	return size, attemptResult{}, true
}

// markUploadedByAxis 는 성공 마킹의 축 분기다. 돌려주는 값은 "후처리(백오프 해제·통지)를
// 이어가도 되는가"이며, false 면 호출자는 outcomeNeutral 로 끝낸다.
//
// 축마다 CAS 가 다르다(설계 5.5.5): ② 는 장부 크기가 앵커이고, ③ 은 산출 길이를 SET 하며,
// init 은 산출 바이트의 해시로 첫 확정을 가른다.
func (u *Uploader) markUploadedByAxis(j job, p payload, lg *slog.Logger) bool {
	t := j.target
	if planMark(t.Axis, index.UploadStateUploaded) != markCAS {
		lg.Error("upload_mark_unsupported", "target_state", "uploaded", "s3_key", t.S3Key)
		return false
	}

	// err 를 먼저 본다. (false, err) 를 CAS 거부로 오분류하면 안 된다(CX-2 ⑥).
	markCtx, cancel := context.WithTimeout(u.markRoot, u.opt.MarkTimeout)
	defer cancel()
	if t.Axis == index.AxisInit {
		return u.markInitUploaded(markCtx, j, p, lg)
	}
	var (
		marked bool
		err    error
	)
	if t.Axis == index.AxisPlayback {
		marked, err = u.st.MarkPlaybackUploaded(markCtx, t.StreamID, t.Seq, p.size)
	} else {
		marked, err = u.st.MarkUploaded(markCtx, t.StreamID, t.Seq, t.Bytes)
	}
	k := j.key()
	switch {
	case err != nil:
		lg.Error("mark_error", "target_state", "uploaded", "err", err.Error())
		u.gate.registerFailure(k)
		return false
	case !marked:
		lg.Warn("upload_cas_rejected", "expect_bytes", t.Bytes, "target_state", "uploaded")
		u.gate.registerFailure(k)
		return false
	}
	return true
}

// markFailedByAxis 는 실패 마킹의 축 분기다. 돌려주는 값은 "후처리를 이어가도 되는가"이며,
// false 면 호출자는 outcomeNeutral 로 끝낸다(판정 재료가 아니다).
//
//	markCAS    — CAS 를 실행하고 성공했을 때만 이어간다. ③ 은 reason 을 싣는다 —
//	             index.ReasonInitMismatch 면 그 조각의 세션까지 한 문장으로 끝낸다(설계 5.3ⓒ).
//	markSkip   — 장부를 건드리지 않고 이어간다. init 실패에는 CAS 가 없다(설계 5.5.5).
//	markRefuse — 이어가지 않는다. 모르는 축의 결과가 다른 축의 CAS 로 새면 안 된다.
func (u *Uploader) markFailedByAxis(j job, reason string, lg *slog.Logger) bool {
	t := j.target
	switch planMark(t.Axis, index.UploadStateFailed) {
	case markSkip:
		return true
	case markRefuse:
		lg.Error("upload_mark_unsupported", "target_state", "failed", "s3_key", t.S3Key)
		return false
	}

	markCtx, cancel := context.WithTimeout(u.markRoot, u.opt.MarkTimeout)
	defer cancel()
	var (
		marked bool
		err    error
	)
	if t.Axis == index.AxisPlayback {
		marked, err = u.st.MarkPlaybackFailed(markCtx, t.StreamID, t.Seq, t.SessionID, reason)
	} else {
		marked, err = u.st.MarkFailed(markCtx, t.StreamID, t.Seq, t.Bytes)
	}
	k := j.key()
	switch {
	case err != nil:
		lg.Error("mark_error", "target_state", "failed", "err", err.Error())
		u.gate.registerFailure(k) // 격리하지 않는다 — 백오프가 재판정을 벌린다
		return false
	case !marked:
		lg.Warn("upload_cas_rejected", "expect_bytes", t.Bytes, "target_state", "failed")
		u.gate.registerFailure(k)
		return false
	}
	return true
}

// handleFileMissing 은 ENOENT 후처리다(D-7 공식).
//
// 마킹이 먼저이고 격리는 마킹이 확정된 뒤에만 건다 — 격리는 프로세스 수명 동안 되돌릴 수
// 없고 마킹 실패는 다음 회차가 되돌릴 수 있으므로, 되돌릴 수 없는 조치를 뒤에 둔다(CX6-1).
func (u *Uploader) handleFileMissing(j job, lg *slog.Logger) outcome {
	t := j.target
	k := j.key()
	lg.Warn("upload_file_missing", "path", t.LocalPath)

	if !u.markFailedByAxis(j, playbackReasonFileMissing, lg) {
		return outcomeNeutral
	}
	u.gate.quarantine(k)
	u.sendResult(j, index.UploadStateFailed)
	return outcomeSoft
}

// finalizeFailure 는 [3] 재시도 소진이다(결정 13″ — 꼬리 한정 보류).
func (u *Uploader) finalizeFailure(j job, rel string, lg *slog.Logger, lastErr error) outcome {
	t := j.target
	k := j.key()

	// 비꼬리는 재측정 자체를 하지 않는다 — 보류 판정이 없으므로 잴 이유가 없고,
	// 여는 만큼 EMFILE 압력만 늘린다.
	if t.IsTail {
		if done, o := u.recheckTail(j, rel, lg); done {
			return o
		}
	}

	if !u.markFailedByAxis(j, playbackReasonUploadFailed, lg) {
		return outcomeNeutral
	}

	class := classifyPutError(lastErr)
	u.gate.registerFailure(k)
	nextAt, _ := u.gate.backoffBlocked(k)
	lg.Error("upload_failed", "s3_key", t.S3Key, "attempts", u.opt.RetryMax,
		"err", errString(lastErr), "err_class", class.String(),
		"next_attempt_at", nextAt.UTC().Format(time.RFC3339))
	u.sendResult(j, index.UploadStateFailed)

	if class == putErrHard {
		return outcomeHard
	}
	return outcomeSoft
}

// recheckTail 은 꼬리의 크기를 다시 재 failed 확정을 보류할지 정한다.
//
// 여기서 여는 fd 는 [2](e) 의 "같은 fd" 가 아니라 **경로 재열기**다 — attemptOnce 가
// 반환한 시점에 그 fd 는 defer 로 닫혔다. 그래서 이 구간에는 경로 경합 보장이 없다(L15·L18).
// 판정 재료가 없으면 판정하지 않는다: 오류를 무시하고 역참조하면 nil panic 이고,
// "재측정 불가"를 크기 불일치로 뭉뚱그리면 성장 중인 꼬리를 failed 로 확정한다(CX6-2).
func (u *Uploader) recheckTail(j job, rel string, lg *slog.Logger) (bool, outcome) {
	t := j.target
	k := j.key()

	f, err := u.opt.Root.Open(rel)
	if err != nil {
		// ENOENT 도 여기서 확정하지 않는다. 확정의 소유자는 [2] 의 fileMissing 분기 하나이며,
		// 백오프 만료 뒤 첫 회차가 같은 ENOENT 를 정식 경로로 확정한다.
		lg.Warn("upload_stat_failed", "stage", "tail_recheck", "err", err.Error())
		u.gate.registerFailure(k)
		return true, outcomeNeutral
	}
	defer f.Close()

	fi, err := u.statFile(f)
	if err != nil {
		lg.Warn("upload_stat_failed", "stage", "tail_recheck", "err", err.Error())
		u.gate.registerFailure(k)
		return true, outcomeNeutral
	}
	if fi.Size() == t.Bytes {
		return false, 0 // 판정 재료가 맞다 — 아래 MarkFailed 로 진행한다
	}

	// 크기가 변했다는 양의 증거를 방금 봤다. UpdateTail 의 bytes 교정이 다음 회차 판정을
	// 실제로 바꾸므로 지수로 밀지 않는다 — 밀면 30m 뒤 재판정이라 이 결정이 무의미해진다(M-1).
	streak := u.gate.registerDeferred(k)
	lg.Warn("mark_failed_deferred", "db_bytes", t.Bytes, "file_bytes", fi.Size(),
		"deferred_streak", streak)
	return true, outcomeNeutral
}

// logTailGrowing 은 origin 에 따라 레벨이 갈린다 — 실시간 경로에서는 흔한 정상 상황이고
// 스위프 경로에서는 "보류가 오래 풀리지 않는다"는 신호이기 때문이다.
func (u *Uploader) logTailGrowing(j job, lg *slog.Logger, dbBytes, fileBytes int64) {
	args := []any{"db_bytes", dbBytes, "file_bytes", fileBytes, "delta", fileBytes - dbBytes}
	if j.origin == OriginSweep {
		lg.Warn("tail_still_growing", args...)
		return
	}
	lg.Debug("tail_still_growing", args...)
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
