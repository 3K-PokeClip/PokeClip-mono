package ingestauth

import (
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/netip"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
)

// MaxDeadline 은 응답기가 인증 하나에 쓰는 시간의 상한이다. 225 의 B_obs 2.75초에서
// 계산한 RT_max 다. 바깥 가드가 50ms 를 더하므로 경로 관리자를 붙잡는 상한은 1.175초다.
// 동시 재접속 묶음의 여유는 이 값이 아니라 resolve 실측 분포가 정한다(3-7절).
// 올리려면 B_obs 를 다시 재야 한다.
const MaxDeadline = 1125 * time.Millisecond

// Options 는 NewHandler 의 설정이다.
type Options struct {
	CallerIP netip.Addr       // MediaMTX 의 제어망 주소 <A_mtx>. TCP 피어가 다르면 403(본문 없음)
	SelfIP   netip.Addr       // 응답기 자신의 제어망 주소 <A_auth>. action=api 는 이 출발지(cut)만 허용
	APIUser  string           // action=api 자격 — cut 이 Control API 요청에 Basic 으로 싣는다
	APIPass  string           // 비면 NewHandler 가 거부. 처리기는 원문을 쥐지 않는다(SHA-256 만)
	Deadline time.Duration    // 0 < Deadline ≤ MaxDeadline. 0 이면 MaxDeadline. 시험만 짧게 준다
	Now      func() time.Time // nil 이면 time.Now. 시험이 예산 · 실패 토큰 차례의 시각을 돌린다
	Log      *slog.Logger     // nil 이면 slog.Default()
}

// NewHandler 는 처리기를 두 겹으로 싸서 돌려준다. 안쪽은 바깥 시한 가드(http.TimeoutHandler —
// Deadline + 50ms, 본문 "timeout")이고, 바깥은 예산 차감(요청마다 걸린 시간을 defer 로 뺀다)이다.
// 구체 타입을 내보내면 가드나 차감 없이 쓰는 길이 열리기 때문이다. 안쪽 처리기는 resolve 를
// 부르기 전에 요청 ctx 를 본다 — 바깥 가드가 이미 끝낸 요청이면 부르지 않는다.
//
// r · deny · conns 가 nil 이거나, CallerIP · SelfIP 가 영값이거나, APIUser · APIPass 가 비었거나, Deadline 이 0
// 이상 MaxDeadline 이하가 아니면 에러다. 에러에는 자격 값을 싣지 않는다.
func NewHandler(r *keyresolve.Client, deny *DenyList, conns *ConnLog, opt Options) (http.Handler, error) {
	h, err := newHandler(r, deny, conns, opt)
	if err != nil {
		return nil, err
	}
	return h.layers(), nil
}

// handler 는 판정표(설계 2-4)의 안쪽 처리기다. layers 가 시한 가드와 예산 차감으로 싸서 내보낸다 — 그대로 쓰지
// 않는다. 여러 고루틴이 함께 불러도 된다.
type handler struct {
	resolver   *keyresolve.Client
	deny       *DenyList
	conns      *ConnLog
	callerIP   netip.Addr
	selfIP     netip.Addr
	apiUserSum [sha256.Size]byte // APIUser 의 SHA-256 — 자격은 다이제스트끼리 상수 시간으로 견준다
	apiPassSum [sha256.Size]byte
	deadline   time.Duration // resolve 의 시한. 요청이 처리기에 닿은 실제 시각부터 잰다
	now        func() time.Time
	log        *slog.Logger

	budget    *holdBudget
	failed    *failedTokens
	authGauge *authGauge
	connGauge *connLogGauge
}

// newHandler 는 opt 를 검사해 안쪽 처리기를 만든다(NewHandler 의 에러 조건과 같다).
func newHandler(r *keyresolve.Client, deny *DenyList, conns *ConnLog, opt Options) (*handler, error) {
	switch {
	case r == nil:
		return nil, errors.New("ingestauth: resolve 클라이언트가 없다")
	case deny == nil:
		return nil, errors.New("ingestauth: 거부 목록이 없다")
	case conns == nil:
		return nil, errors.New("ingestauth: 허용 연결 기록이 없다")
	case !opt.CallerIP.IsValid():
		return nil, errors.New("ingestauth: CallerIP 가 비었다")
	case !opt.SelfIP.IsValid():
		return nil, errors.New("ingestauth: SelfIP 가 비었다")
	case opt.APIUser == "" || opt.APIPass == "":
		return nil, errors.New("ingestauth: action=api 자격(APIUser · APIPass)이 비었다")
	case opt.Deadline < 0 || opt.Deadline > MaxDeadline:
		return nil, fmt.Errorf("ingestauth: Deadline 은 0 이상 MaxDeadline(%v) 이하여야 한다 — %v", MaxDeadline, opt.Deadline)
	}
	h := &handler{
		resolver:   r,
		deny:       deny,
		conns:      conns,
		callerIP:   opt.CallerIP.Unmap(),
		selfIP:     opt.SelfIP.Unmap(),
		apiUserSum: sha256.Sum256([]byte(opt.APIUser)),
		apiPassSum: sha256.Sum256([]byte(opt.APIPass)),
		deadline:   opt.Deadline,
		now:        opt.Now,
		log:        opt.Log,
	}
	if h.deadline == 0 {
		h.deadline = MaxDeadline
	}
	if h.now == nil {
		h.now = time.Now
	}
	if h.log == nil {
		h.log = slog.Default()
	}
	h.budget = newHoldBudget(h.now)
	h.failed = newFailedTokens(h.now)
	h.authGauge = &authGauge{now: h.now, log: h.log}
	h.connGauge = &connLogGauge{log: h.log}
	return h, nil
}

// layers 는 h 를 바깥 시한 가드(http.TimeoutHandler — Deadline + guardMargin, 본문 "timeout")로 싸고, 그 바깥에 예산
// 차감 층을 둔 처리기다. 차감은 요청마다 한 번이고 행 · 결과와 무관하다(설계 2-4 예산 차감). 가드가 끝낸 요청은 가드
// 시각까지, 패닉으로 끝난 요청은 패닉 시각까지 뺀다 — TimeoutHandler 가 안쪽 패닉을 이 고루틴으로 다시 던지므로
// defer 가 돈다. 가드 뒤에 늦게 끝난 안쪽 처리기는 예산을 만지지 않는다(안쪽은 allow 만 부른다).
func (h *handler) layers() http.Handler {
	guard := http.TimeoutHandler(h, h.deadline+guardMargin, "timeout")
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := h.now()
		// 걸린 시간은 defer 가 돌 때 잰다. defer h.budget.charge(h.now().Sub(start)) 꼴은 인자를 이 자리에서
		// 평가해 약 0 을 뺀다.
		defer func() { h.budget.charge(h.now().Sub(start)) }()
		guard.ServeHTTP(w, r)
	})
}

// ServeHTTP 는 판정표의 위에서부터 처음 맞는 행으로 답한다 — 출발지(403) → 본문(400) → api → read → publish.
// 비-2xx 는 본문을 쓰지 않는다 — MediaMTX 가 비-2xx 본문을 오류 문구에 싣는다(설계 2-1 A4).
func (h *handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// resolve 의 시한은 요청이 처리기에 닿은 때부터 잰다(설계 3-7 「요청 도착부터 응답까지」). ctx 는 실제 시계로
	// 끝나므로 Options.Now 가 아니라 time.Now 다. 바깥 가드는 같은 때부터 Deadline + guardMargin 이라, resolve 가
	// 시한에 끝나도 처리기가 가드보다 먼저 401 을 쓴다.
	resolveBy := time.Now().Add(h.deadline)
	if peer, ok := peerAddr(r.RemoteAddr); !ok || peer != h.callerIP {
		remote := r.RemoteAddr // 해석하지 못한 피어 주소는 서버가 준 문자열 그대로 싣는다
		if ok {
			remote = peer.String()
		}
		h.log.Warn("ingestauth_caller_denied", "remote_ip", remote)
		w.WriteHeader(http.StatusForbidden)
		return
	}
	req, invalid, ok := readRequest(r.Body)
	if !ok {
		h.log.Warn("ingestauth_request_invalid", "reason", invalid)
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	switch req.action {
	case actionAPI:
		h.api(w, req)
	case actionRead:
		// 설정에서 제외하므로 오지 않는다. 오면 지금 동작(무인증 읽기)을 유지한다 — SRT 읽기는 경로 잠금값이
		// 막는다(설계 2-8).
		w.WriteHeader(http.StatusOK)
	default:
		h.publish(r.Context(), w, req, resolveBy)
	}
}

// api 는 판정표의 api 행이다. 출발지(본문의 ip)가 SelfIP(cut)이고 자격이 맞으면 200, 아니면 401 이다. 둘 다 본문이
// 없다. 예산 · 차례에 걸리지 않는다 — 잔액이 바닥나도 cut 은 돈다(설계 2-4).
func (h *handler) api(w http.ResponseWriter, req mtxRequest) {
	if req.ip == h.selfIP && h.apiCredentialsMatch(req.user, req.password) {
		h.log.Debug("ingestauth_api_allowed")
		w.WriteHeader(http.StatusOK)
		return
	}
	h.log.Warn("ingestauth_api_denied", "ip", req.ip.String())
	w.WriteHeader(http.StatusUnauthorized)
}

// apiCredentialsMatch 는 user · password 가 설정된 자격과 같은지 상수 시간으로 견준다. 다이제스트끼리 견주므로 길이가
// 달라도 같은 길을 타고, 사용자와 비번을 늘 둘 다 견준다.
func (h *handler) apiCredentialsMatch(user, password string) bool {
	userSum := sha256.Sum256([]byte(user))
	passSum := sha256.Sum256([]byte(password))
	return subtle.ConstantTimeCompare(userSum[:], h.apiUserSum[:])&subtle.ConstantTimeCompare(passSum[:], h.apiPassSum[:]) == 1
}
