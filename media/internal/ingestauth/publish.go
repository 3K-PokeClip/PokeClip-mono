package ingestauth

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// guardMargin 은 바깥 시한 가드가 Deadline 에 더하는 여유다 — resolve 가 시한에 끝난 뒤 처리기가 401 을 쓸 몫이다(설계
// 2-4 · 3-7 「바깥 가드(+50ms)」).
const guardMargin = 50 * time.Millisecond

// ingestauth_publish_denied 의 reason 이다(설계 2-4 판정표 · 6절). 닫힌 집합이다 — auth 가 돌려준 이유 문자열을
// 그대로 싣지 않는다.
const (
	reasonNotPublish         = "not_publish"
	reasonNotSRT             = "not_srt"
	reasonNotToken           = "not_token"
	reasonNoConnID           = "no_conn_id"
	reasonDenylistUnreadable = "denylist_unreadable"
	reasonDenylisted         = "denylisted"
	reasonBudgetExhausted    = "budget_exhausted"
	reasonRecheckWait        = "recheck_wait"
	reasonUndecided          = "undecided"
	reasonMalformed          = "malformed"
	reasonNotFound           = "not_found"
	reasonRevoked            = "revoked"
	reasonRejected           = "rejected" // valid:false 인데 auth 의 이유가 위 셋이 아니다(모르는 값 · 빈 값)
	reasonConnLogUnwritable  = "connlog_unwritable"
	reasonInternalError      = "internal_error" // resolve 의 그 밖의 에러 — 처리기 · 설정 결함이고 auth 의 상태가 아니다
)

// publish 는 판정표의 publish 행이다. 차례는 형식 → 거부 목록 → 예산 → 요청 ctx → 실패 토큰 차례 → resolve → 기억
// 갱신 → 기록 추가 → 마지막 거부 확인 → 200 이고(설계 2-4 · 3-1), 위에서부터 처음 맞는 행이 답이다. 예산 · 차례
// 거절은 resolve 를 부르지 않고, 예산 거절 · 끝난 ctx 의 요청은 차례를 쓰지 않는다. 응답기는 기다리게 하지 않는다 —
// 양보도 곧바로 401 이다. ctx 는 요청 ctx 다 — 그 시한이 바깥 가드다(layers).
func (h *handler) publish(ctx context.Context, w http.ResponseWriter, req mtxRequest) {
	if reason, ok := publishForm(req); !ok {
		h.denyPublish(w, req, denial{reason: reason})
		return
	}
	if reason, denied := h.denylistDenial(req.path); denied {
		h.denyPublish(w, req, denial{reason: reason})
		return
	}
	if !h.budget.allow() {
		h.denyPublish(w, req, denial{reason: reasonBudgetExhausted})
		return
	}
	// 바깥 가드가 이미 끝낸 요청(MediaMTX 가 버린 요청 포함)이거나 resolve 할 시간이 남지 않은 요청이면 resolve 를
	// 부르지 않는다 — 헛된 auth 호출 · 판정 불가 · 게이지 전이 · 차례 소비를 막는다. 시간이 남지 않은 요청에 부르면
	// auth 에 묻지도 않고 곧바로 판정 불가(timeout)다.
	resolveBy := h.resolveDeadline(ctx)
	if ctx.Err() != nil || !time.Now().Before(resolveBy) {
		h.late(w, req)
		return
	}
	if wait, ok := h.failed.admit(req.path); !ok {
		h.denyPublish(w, req, denial{reason: reasonRecheckWait, wait: wait})
		return
	}
	// resolve 는 요청 취소에서 뗀다 — MediaMTX 가 resolve 도중 요청을 버려도(재시작 · 리로드 · 망) 그 취소가 판정 불가
	// (timeout)로 와 헛된 auth_degraded 와 그 토큰의 기억을 남기지 않게. 시한은 resolveBy 하나이고 바깥 가드보다
	// 앞이라, 떼어도 가드가 끊을 resolve 는 없다. 버린 요청의 resolve 는 그 시한까지만 산다.
	resolveCtx, cancel := context.WithDeadline(context.WithoutCancel(ctx), resolveBy)
	defer cancel()
	j := h.judge(resolveCtx, req.path)
	h.report(req, j)
	switch j.kind {
	case judgedUsable:
		h.allowPublish(ctx, w, req, j)
	case judgedOutOfFormat:
		// auth 의 계약 위반이다. 값은 싣지 않는다.
		h.log.Error("ingestauth_passphrase_invalid", "path", req.path)
		w.WriteHeader(http.StatusUnauthorized)
	default:
		h.denyPublish(w, req, j.denial(j.deniedReason()))
	}
}

// resolveDeadline 은 resolve 의 시한이다 — 요청 ctx 의 시한(바깥 가드 — 바깥 층이 도착 시각에서 잰다)보다 guardMargin
// 앞이라, resolve 가 시한에 끝나면 처리기가 가드보다 먼저 401 을 쓴다. 요청 ctx 에 시한이 없으면(바깥 층을 거치지 않고
// 안쪽만 부른 요청) 지금부터 Deadline 이다.
func (h *handler) resolveDeadline(ctx context.Context) time.Time {
	guardBy, ok := ctx.Deadline()
	if !ok {
		return time.Now().Add(h.deadline)
	}
	return guardBy.Add(-guardMargin)
}

// publishForm 은 publish 형식 행(판정표 — publish 가 아님 · protocol≠srt · 토큰 꼴 아님 · id 없음)에 걸린 까닭이다.
// 걸리지 않으면 ok 가 참이다. 이 행들이 거부 목록 · 예산 · 차례보다 앞이라 실패 토큰 기억의 키는 늘 토큰 꼴이다.
func publishForm(req mtxRequest) (reason string, ok bool) {
	switch {
	case req.action != actionPublish:
		return reasonNotPublish, false
	case req.protocol != protocolSRT:
		return reasonNotSRT, false
	case !streamkey.IsToken(req.path):
		return reasonNotToken, false
	case req.connID == "":
		return reasonNoConnID, false
	}
	return "", true
}

// denylistDenial 은 거부 목록을 지금 다시 읽어 token 을 막아야 하는지 본다. 막으면 denied 가 참이고 reason 이 그
// 까닭이다 — 읽지 못함(denylist_unreadable — 거부 목록 게이지는 DenyList 가 낸다) 또는 목록에 있음(denylisted).
func (h *handler) denylistDenial(token string) (reason string, denied bool) {
	listed, err := h.deny.Contains(token)
	switch {
	case err != nil:
		return reasonDenylistUnreadable, true
	case listed:
		return reasonDenylisted, true
	}
	return "", false
}

// judge 는 token 을 resolve 하고 그 결과로 실패 토큰 기억을 한 번 갱신한다 — resolve 와 형식 검사가 끝난 직후다(설계
// 2-4 기억 갱신). admit 가 참을 돌려준 요청만 부른다. 그 밖의 에러(처리기 · 설정 결함)는 기억에 적지 않는다.
//
// resolve 나 결과 가르기가 패닉하면 그 토큰을 판정 불가로 적고 패닉을 그대로 이어 던진다(recover 하지 않는다). 적지
// 않으면 재확인을 받은 그 토큰이 차례 맨 앞에 남아, 기억이 풀릴 때까지 칸을 쥐고 다른 기억 토큰을 모두 양보시킨다.
func (h *handler) judge(ctx context.Context, token string) judgment {
	settled := false
	defer func() {
		if !settled {
			h.failed.record(token, outcomeUndecided)
		}
	}()
	start := h.now()
	v, err := h.resolver.Resolve(ctx, token)
	j := judgeResolve(v, err, h.now().Sub(start))
	if o, ok := j.outcome(); ok {
		j.scope, j.cleared = h.failed.record(token, o)
	}
	settled = true
	return j
}

// report 는 resolve 결과로 auth 게이지를 맞추고, 기억을 지웠으면 ingestauth_failed_cleared(WARN — recheck_wait 거절과
// 같은 등급)를 낸다.
func (h *handler) report(req mtxRequest, j judgment) {
	switch j.kind {
	case judgedUndecided:
		// 게이지 set 은 그 거절의 셈 · 로그와 한 잠금 구간에서 한다(denyPublish).
	case judgedBroken:
		// 처리기 · 설정 결함이다 — auth 의 판정이 아니므로 게이지를 건드리지 않는다.
	default:
		h.authGauge.decided()
	}
	if j.scope != "" {
		h.log.Warn("ingestauth_failed_cleared", "path", req.path, "scope", j.scope, "cleared", j.cleared)
	}
}

// allowPublish 는 쓸 수 있는 값을 받은 연결을 허용한다 — 기록 추가 → 마지막 거부 확인 → 200 이다. 기록이 응답보다
// 먼저라서 허용된 연결은 반드시 기록에 있고, 확인이 기록 뒤라서 그 사이 적힌 거부를 놓치지 않는다(설계 2-4 허용
// 순서 · 3-4 증명). 기록은 있는데 연결이 없는 경우(마지막 확인의 거부 · 가드의 503)는 무해하다. 200 을 가드 버퍼에 쓰면
// ingestauth_publish_allowed 의 필드를 요청의 허용 칸에 남긴다 — 그 로그는 바깥 층이 가드가 실제로 200 을 쓴 것을 본 뒤에
// 낸다(publishAllowed).
func (h *handler) allowPublish(ctx context.Context, w http.ResponseWriter, req mtxRequest, j judgment) {
	if err := h.connGauge.append(h.conns, req.path, req.connID); err != nil {
		h.denyPublish(w, req, j.denial(reasonConnLogUnwritable))
		return
	}
	if reason, denied := h.denylistDenial(req.path); denied {
		h.denyPublish(w, req, j.denial(reason))
		return
	}
	if err := writeAllowed(w, j.verdict.Passphrase); err != nil {
		h.late(w, req) // 바깥 가드가 먼저 답했다 — 200 은 버려졌다
		return
	}
	if slot := allowedSlot(ctx); slot != nil {
		*slot = publishAllowed{set: true, path: req.path, connID: req.connID, userID: j.verdict.UserID, resolveTook: j.took}
	}
}

// publishAllowed 는 ingestauth_publish_allowed 의 필드다(설계 6절 — path · conn_id · user_id · resolve_ms). 안쪽
// 처리기가 200 을 가드 버퍼에 쓴 뒤 요청의 허용 칸에 남기고, 바깥 층이 가드가 MediaMTX 에 실제로 200 을 쓴 것을 본 뒤에
// 낸다 — 가드 버퍼의 200 이 시한에 닥쳐 503 으로 바뀌면(TimeoutHandler 는 안쪽의 끝과 시한이 함께 준비되면 어느 쪽이든
// 고른다) MediaMTX 는 거절을 받았으므로 내지 않는다.
type publishAllowed struct {
	set         bool // 안쪽이 200 을 가드 버퍼에 썼다
	path        string
	connID      string
	userID      int64
	resolveTook time.Duration
}

// log 는 a 를 ingestauth_publish_allowed(INFO)로 낸다.
func (a *publishAllowed) log(l *slog.Logger) {
	l.Info("ingestauth_publish_allowed",
		"path", a.path,
		"conn_id", a.connID,
		"user_id", a.userID,
		"resolve_ms", a.resolveTook.Milliseconds())
}

// allowedSlotKey 는 요청 ctx 에 실린 허용 칸(*publishAllowed)의 열쇠다. 칸은 바깥 층이 요청마다 하나 만들어 가드에
// 넘긴다 — 안쪽 처리기는 가드(http.TimeoutHandler)를 지나 요청 ctx 로만 바깥 층에 닿는다.
type allowedSlotKey struct{}

// withAllowedSlot 은 slot 을 실은 ctx 다.
func withAllowedSlot(ctx context.Context, slot *publishAllowed) context.Context {
	return context.WithValue(ctx, allowedSlotKey{}, slot)
}

// allowedSlot 은 ctx 에 실린 허용 칸이다. 없으면(바깥 층을 거치지 않은 요청) nil 이다.
func allowedSlot(ctx context.Context) *publishAllowed {
	slot, _ := ctx.Value(allowedSlotKey{}).(*publishAllowed)
	return slot
}

// allowedBody 는 허용 응답의 본문이다 — 2xx 본문 JSON 객체의 srtPassphrase(설계 2-1 A2 — U 형식 확정 전 가정).
type allowedBody struct {
	SRTPassphrase string `json:"srtPassphrase"`
}

// writeAllowed 는 허용 응답 200 {"srtPassphrase":"<원문>"} · Cache-Control: no-store 를 쓴다. 200 본문 조립은 이
// 함수 하나이고, Passphrase.Reveal 을 부르는 곳도 여기 하나다(설계 6절 누설 규칙). U 의 응답 형식이
// 바뀌면 이 함수와 그 시험만 고친다(설계 2-9). 바깥 가드가 이미 답했으면 쓰기가 버려지고 에러다.
func writeAllowed(w http.ResponseWriter, p streamkey.Passphrase) error {
	// 문자열 필드 하나뿐인 구조체라 Marshal 은 실패하지 않는다.
	body, _ := json.Marshal(allowedBody{SRTPassphrase: p.Reveal()})
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, err := w.Write(body)
	return err
}

// denial 은 publish 거절 하나의 로그 필드다(ingestauth_publish_denied — 설계 6절).
type denial struct {
	reason        string
	wait          string          // reason 이 recheck_wait 일 때만 — gap(칸이 닫힘) · yield(양보)
	resolveStatus string          // resolve 를 불렀을 때만(judgment.resolveStatus)
	resolveTook   time.Duration   // resolve 에 걸린 시간. 부르지 않았으면 0
	cause         *undecidedCause // resolve 가 판정 불가로 끝났을 때만 — auth 게이지를 세운다
}

// denyPublish 는 publish 를 401(본문 없음)로 거절하고 ingestauth_publish_denied(WARN)를 낸다. 판정 불가로 끝난 거절이면
// auth 게이지를 세우고, 게이지가 서 있으면 그 동안의 거절로 센다 — 게이지 전이 · 셈 · 거절 로그가 한 잠금
// 구간이다(authGauge.denied).
func (h *handler) denyPublish(w http.ResponseWriter, req mtxRequest, d denial) {
	attrs := append(identityAttrs(req),
		"reason", d.reason,
		"resolve_status", d.resolveStatus,
		"resolve_ms", d.resolveTook.Milliseconds())
	if d.wait != "" {
		attrs = append(attrs, "wait", d.wait)
	}
	h.authGauge.denied(d.reason, d.cause, func() { h.log.Warn("ingestauth_publish_denied", attrs...) })
	w.WriteHeader(http.StatusUnauthorized)
}

// late 는 늦은 publish 다 — 요청 ctx 가 끝났거나(바깥 가드가 이미 답함 · MediaMTX 가 버림) resolve 할 시간이 남지 않아
// resolve 를 건너뛰었거나, 200 쓰기가 버려졌다(판정표 「요청 ctx 가 이미 끝남」). ingestauth_publish_late(DEBUG)를 내고
// 401(본문 없음)을 쓴다. 가드가 이미 답했으면 그 쓰기는 버려진다. 그래도 쓰는 것은 가드와 겨루는 경우의 몫이다 — 안쪽
// 처리기가 아무것도 쓰지 않고 돌아가면 TimeoutHandler 는 처리 끝과 시한이 함께 준비됐을 때 그 응답을 200(빈 본문)으로
// 낼 수 있다.
func (h *handler) late(w http.ResponseWriter, req mtxRequest) {
	h.log.Debug("ingestauth_publish_late", identityAttrs(req)...)
	w.WriteHeader(http.StatusUnauthorized)
}

// identityAttrs 는 req 의 공개 식별자 로그 필드다. path 는 토큰 꼴일 때만 싣는다 — 토큰 꼴이 아닌 값은 잘못 붙인
// 비밀일 수 있다. conn_id 는 있을 때만 싣는다.
func identityAttrs(req mtxRequest) []any {
	var attrs []any
	if streamkey.IsToken(req.path) {
		attrs = append(attrs, "path", req.path)
	}
	if req.connID != "" {
		attrs = append(attrs, "conn_id", req.connID)
	}
	return attrs
}

// judgmentKind 는 resolve 결과의 갈래다 — 판정표의 resolve 행 · 기억 갱신 · auth 게이지를 정한다.
type judgmentKind int

const (
	judgedUsable      judgmentKind = iota // valid:true · passphrase 형식 안 — 기록 · 마지막 확인 · 200 으로
	judgedRejected                        // valid:false
	judgedOutOfFormat                     // valid:true 인데 passphrase 가 형식 밖이거나 없다
	judgedUndecided                       // 판정 불가(keyresolve.UndecidedError)
	judgedBroken                          // 그 밖의 에러 — 처리기 · 설정 결함. 기억 · 게이지를 건드리지 않는다
)

// judgment 는 resolve 한 번의 결과를 판정표의 resolve 행으로 가른 것이다.
type judgment struct {
	kind    judgmentKind
	verdict keyresolve.Verdict // judgedUsable 일 때 — 200 본문의 passphrase 와 user_id
	reason  string             // judgedRejected 일 때 — 닫힌 집합으로 옮긴 auth 의 이유
	failure string             // judgedUndecided 일 때 — UndecidedError.Kind(timeout · unreachable · status · parse)
	status  int                // judgedUndecided 이고 failure 가 status 일 때 — auth 의 HTTP 상태
	took    time.Duration      // resolve 에 걸린 시간(Options.Now)
	scope   string             // 기억을 지웠으면 그 범위 — ingestauth_failed_cleared
	cleared int                // 지운 기억 토큰 수
}

// judgeResolve 는 Resolve 한 번의 결과(v · err)를 판정표의 resolve 행으로 가른다. took 은 그 resolve 에 걸린
// 시간이다(음수면 0 — 시험 시계가 뒤로 감). 에러는 갈래로만 남기고 그 문구를 담지 않는다 — 판정 불가의 갈래(Kind ·
// Status)만 로그에 싣는다.
func judgeResolve(v keyresolve.Verdict, err error, took time.Duration) judgment {
	j := judgment{took: max(took, 0)}
	var undecided *keyresolve.UndecidedError
	switch {
	case errors.As(err, &undecided):
		j.kind, j.failure, j.status = judgedUndecided, undecided.Kind, undecided.Status
	case errors.Is(err, keyresolve.ErrInvalidPassphrase):
		j.kind = judgedOutOfFormat
	case err != nil:
		j.kind = judgedBroken
	case !v.Valid:
		j.kind, j.reason = judgedRejected, rejectReason(v.Reason)
	case v.Passphrase.IsZero():
		// 허락인데 값이 없다(keyresolve 는 이 값을 내지 않는다). 200 을 쓰면 MediaMTX 가 경로 잠금값으로 판정해
		// 잠금값이 거절을 떠맡으므로 형식 밖과 같게 다룬다(설계 2-4 「확정 거절도 401」).
		j.kind = judgedOutOfFormat
	default:
		j.kind, j.verdict = judgedUsable, v
	}
	return j
}

// rejectReason 은 auth 의 valid:false 이유(4C — MALFORMED · NOT_FOUND · REVOKED)를 publish_denied 의 reason 으로
// 옮긴다. 그 밖의 값은 원문을 싣지 않고 rejected 다 — 응답 본문의 임의 문자열이다.
func rejectReason(authReason string) string {
	switch authReason {
	case "MALFORMED":
		return reasonMalformed
	case "NOT_FOUND":
		return reasonNotFound
	case "REVOKED":
		return reasonRevoked
	}
	return reasonRejected
}

// outcome 은 실패 토큰 기억에 적을 결과의 갈래다. 그 밖의 에러면 ok 가 거짓이다 — 기억에 적지 않는다.
func (j judgment) outcome() (o resolveOutcome, ok bool) {
	switch j.kind {
	case judgedUsable:
		return outcomeUsable, true
	case judgedRejected, judgedOutOfFormat:
		return outcomeUnusable, true
	case judgedUndecided:
		return outcomeUndecided, true
	}
	return 0, false
}

// resolveStatus 는 publish_denied 의 resolve_status 다. auth 가 판정을 돌려줬으면 "200", 판정 불가면 auth 의 HTTP
// 상태(비-200) 또는 그 갈래(timeout · unreachable · parse), auth 에 닿지 못한 그 밖의 에러면 비어 있다.
func (j judgment) resolveStatus() string {
	switch j.kind {
	case judgedUndecided:
		if j.status != 0 {
			return strconv.Itoa(j.status)
		}
		return j.failure
	case judgedBroken:
		return ""
	}
	return strconv.Itoa(http.StatusOK)
}

// deniedReason 은 401 로 끝난 resolve 결과(거절 · 판정 불가 · 그 밖의 에러)의 reason 이다.
func (j judgment) deniedReason() string {
	switch j.kind {
	case judgedRejected:
		return j.reason
	case judgedUndecided:
		return reasonUndecided
	}
	return reasonInternalError
}

// denial 은 resolve 를 부른 뒤 reason 으로 끝난 거절이다. 판정 불가면 그 까닭을 실어 auth 게이지를 세우게 한다.
func (j judgment) denial(reason string) denial {
	d := denial{reason: reason, resolveStatus: j.resolveStatus(), resolveTook: j.took}
	if j.kind == judgedUndecided {
		d.cause = &undecidedCause{failure: j.failure, status: j.status}
	}
	return d
}
