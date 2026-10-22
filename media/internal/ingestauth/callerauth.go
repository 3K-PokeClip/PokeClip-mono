package ingestauth

import (
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"net/http"
)

// callerBasicOn 은 opt 의 호출자 자격 설정을 검사하고 Basic 검사를 켤지 돌려준다(NewHandler 의 에러 조건). 끄는 길은
// 명시 끔(CallerBasicOff)뿐이다 — 자격이 빠진 설정이 조용히 출발지 검사만 남기지 않게 한다.
func callerBasicOn(opt Options) (bool, error) {
	hasUser, hasPass := opt.CallerUser != "", opt.CallerPass.reveal() != ""
	switch {
	case hasUser != hasPass:
		return false, errors.New("ingestauth: 호출자 Basic 자격(CallerUser · CallerPass)은 둘 다 주거나 둘 다 비워야 한다")
	case hasUser && opt.CallerBasicOff:
		return false, errors.New("ingestauth: 호출자 Basic 자격과 CallerBasicOff 를 함께 줄 수 없다")
	case !hasUser && !opt.CallerBasicOff:
		return false, errors.New("ingestauth: 호출자 Basic 자격(CallerUser · CallerPass)이 비었다 — 끄려면 CallerBasicOff 를 준다")
	}
	return hasUser, nil
}

// 호출자 Basic 실패의 까닭이다 — ingestauth_caller_unauthenticated 의 reason. 닫힌 집합이다.
const (
	callerMissing   = "missing"   // Authorization 머리가 없다
	callerMalformed = "malformed" // Basic 이 아니거나 base64 · 쌍점이 깨졌다
	callerMismatch  = "mismatch"  // 자격이 틀리다
)

// denyUnauthenticatedCaller 는 출발지 검사 다음 층이다. 호출자 Basic 검사가 켜져 있고 요청의 Authorization 이 없거나
// 깨졌거나 틀리면 401(본문 없음)을 쓰고 ingestauth_caller_unauthenticated(WARN — remote_ip · reason, 자격 값은 싣지
// 않는다)를 낸 뒤 참을 돌려준다. 본문은 읽지 않는다. 바깥 층이 예산 차감 · 시한 가드보다 먼저 부른다 — 출발지 403 과
// 같은 까닭이다: 같은 netns 의 다른 프로세스가 틀린 자격을 몰아 보내도 모든 계정의 새 송출이 budget_exhausted 가 되지
// 않게.
func (h *handler) denyUnauthenticatedCaller(w http.ResponseWriter, r *http.Request) bool {
	if !h.callerBasic {
		return false
	}
	reason := h.callerBasicFailure(r)
	if reason == "" {
		return false
	}
	remote := r.RemoteAddr
	if peer, ok := peerAddr(r.RemoteAddr); ok {
		remote = peer.String()
	}
	h.log.Warn("ingestauth_caller_unauthenticated", "remote_ip", remote, "reason", reason)
	w.WriteHeader(http.StatusUnauthorized)
	return true
}

// callerBasicFailure 는 r 의 호출자 Basic 이 맞으면 빈 값을, 아니면 그 까닭을 돌려준다. 자격은 apiCredentialsMatch 와
// 같은 꼴로 견준다 — 다이제스트끼리 상수 시간으로, 사용자와 비번을 늘 둘 다.
func (h *handler) callerBasicFailure(r *http.Request) string {
	if r.Header.Get("Authorization") == "" {
		return callerMissing
	}
	user, pass, ok := r.BasicAuth()
	if !ok {
		return callerMalformed
	}
	userSum := sha256.Sum256([]byte(user))
	passSum := sha256.Sum256([]byte(pass))
	if subtle.ConstantTimeCompare(userSum[:], h.callerUserSum[:])&subtle.ConstantTimeCompare(passSum[:], h.callerPassSum[:]) != 1 {
		return callerMismatch
	}
	return ""
}
