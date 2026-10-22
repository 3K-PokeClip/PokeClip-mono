package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/ingestauth"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxctl"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// cut 이 끝까지 끊지 못한 까닭이다 — ingestauth_cut_incomplete 의 reason(설계 6절 · 운영 순서는 3-4 3). 닫힌 집합이다.
// api_incompatible · internal_error 는 설계 6절의 일곱에 이 실행 파일이 더한 것이다. api_auth 의 401 은 MediaMTX 가
// api 인증을 응답기에 묻다 실패한 것이라, 응답기 부재 · 고장 · 자격 불일치뿐 아니라 MediaMTX 가 응답기에 닿지 못할
// 때(임시 포트 고갈 — MediaMTX 로그 cannot assign requested address)도 난다.
const (
	reasonNotDenied          = "not_denied"           // 거부 목록에 없다 — 아무것도 끊지 않았다(종료 2)
	reasonDenyListUnreadable = "denylist_unreadable"  // 거부 목록을 읽지 못했다 — 아무것도 끊지 않았다
	reasonAPIAuth            = "api_auth"             // Control API 401 — 응답기 부재 · 고장 · 자격 불일치 · 포트 고갈(위)
	reasonAPIIncompatible    = "api_incompatible"     // 꼴이 아닌 404(mtxctl.Error) · 해석 실패 · 여러 페이지 — cut 이 아는 꼴이 아니다
	reasonAPIUnreachable     = "api_unreachable"      // Control API 무응답(cut 시한 포함) · 전송 실패 · 기대 밖 상태
	reasonInternalError      = "internal_error"       // 모르는 에러 꼴 · Cut 의 계약과 어긋난 결과
	reasonConnLogUnreadable  = "connlog_unreadable"   // 기록을 읽지 못했거나 줄이 없다 — 목록 갈래만 끊었다
	reasonConnLogWindowShort = "connlog_window_short" // 기록 밖이면서 30초가 안 된 판독 중 연결이 있다
	reasonVerifyTimeout      = "verify_timeout"       // kick 한 연결이 확인 시한 안에 사라지지 않았다
)

// cut 은 cut 하위 명령이다 — ingestauth.Cut 으로 token 의 연결을 끊고 id 로 확인한다(설계 3-4). 결과는 stdout 에 한 줄
// (ingestauth_cut_completed · ingestauth_cut_incomplete — 설계 6절)이고, 실패의 자세한 까닭은 stderr 에 쓴다. 종료
// 코드는 0(끝까지 끊음) · 1(남음 · 실패 — reason) · 2(사용법 · 설정 · 거부 목록에 없음)다.
//
// 기록은 run 과 같은 site.connLogDir 에서 읽는다. 전체 시한(site.cutTimeout)이 있어 Control API 가 답하지 않아도 끝난다.
func cut(ctx context.Context, s site, token string, getenv func(string) string, stdout, stderr io.Writer) int {
	if !streamkey.IsToken(token) {
		// 값은 싣지 않는다 — 토큰 자리에 비밀을 잘못 붙였을 수 있다.
		fmt.Fprintln(stderr, "ingest-auth: cut 의 인자가 토큰 꼴(26자 Crockford 대문자)이 아니다")
		return exitUsage
	}
	pass, err := resolveSecret(getenv, envMTXAPIPass)
	if err != nil {
		return exitWith(stderr, exitUsage, err)
	}
	mtx, err := newControlClient(getenv, pass)
	if err != nil {
		return exitWith(stderr, exitUsage, err)
	}
	ctx, cancel := context.WithTimeout(ctx, s.cutTimeout)
	defer cancel()
	// cut 의 거부 목록 신호는 버린다 — 출력은 한 줄이고, 읽기 실패는 Cut 의 에러로 돌아온다.
	deny := ingestauth.NewDenyList(s.denyList, slog.New(slog.DiscardHandler))
	report, err := ingestauth.Cut(ctx, token, deny, s.connLogDir, mtx)
	code, reason := cutOutcome(report, err)
	writeCutLine(stdout, token, report, reason)
	if code != exitOK {
		if err == nil {
			err = errors.New("Cut 이 에러 없이 끝나지 않은 결과를 돌려줬다")
		}
		// 이 에러의 문구는 경로 · 시스템 호출의 이유 · Control API 의 갈래와 상태뿐이다(ingestauth · mtxctl 문서).
		fmt.Fprintf(stderr, "ingest-auth: cut %s: %v\n", reason, err)
	}
	return code
}

// writeCutLine 은 cut 의 출력 한 줄을 slog JSON 으로 쓴다(설계 6절). LOG_LEVEL 과 무관하다 — 감사 기록이자 명령의
// 결과다. reason 이 비면 cut_completed(WARN), 아니면 cut_incomplete(ERROR — remaining · reason 을 더한다)다.
func writeCutLine(w io.Writer, token string, r ingestauth.CutReport, reason string) {
	out := slog.New(slog.NewJSONHandler(w, nil))
	attrs := []any{
		"path", token,
		"from_log", r.FromLog,
		"from_list", r.FromList,
		"from_unmapped", r.FromUnmapped,
		"unmapped_young", r.UnmappedYoung,
		"kicked", r.Kicked,
		"already_gone", r.AlreadyGone,
		"covered_s", r.Covered.Seconds(),
	}
	if reason == "" {
		out.Warn("ingestauth_cut_completed", attrs...)
		return
	}
	out.Error("ingestauth_cut_incomplete", append(attrs, "remaining", len(r.Remaining), "reason", reason)...)
}

// cutOutcome 은 Cut 의 결과를 종료 코드와 reason 으로 옮긴다. 0 은 err 가 nil 이고 보고도 끝났을 때(남은 연결 0 · 두
// 판독 모두 기록 밖 미완 0 — 설계 2-6)뿐이다. 거부 목록에 없으면 2(not_denied)다. 그 밖은 1 이다 — err 가 nil 인데
// 보고가 끝나지 않았으면 Cut 의 계약과 어긋난 것이라 internal_error 다.
func cutOutcome(r ingestauth.CutReport, err error) (code int, reason string) {
	switch {
	case err == nil && len(r.Remaining) == 0 && r.UnmappedYoung == 0:
		return exitOK, ""
	case errors.Is(err, ingestauth.ErrNotDenied):
		return exitUsage, reasonNotDenied
	case err == nil:
		return exitFailure, reasonInternalError
	}
	return exitFailure, cutReason(err)
}

// cutReason 은 err(errors.Join 일 수 있다)를 reason 하나로 옮긴다. 까닭이 여럿이면 먼저 풀어야 다른 일을 다시 할 수
// 있는 것을 고른다 — 거부 목록 → Control API(401 → 꼴 불일치 → 무응답) → 모르는 꼴 → 기록 읽기 → 기록 창 → 확인
// 시한. Cut 은 까닭을 갈래(401 · 그 밖의 Control API · ctx 끝 · 모르는 꼴 …)마다 첫 하나만 남긴다 — 그래서 앞의 500
// 뒤에 온 401 도 api_auth 로 나온다. 한계: 「그 밖의 Control API」 한 칸 안에서는 첫 것만 남아, 앞의 무응답(500 등)
// 뒤에 온 꼴 불일치(경로 없음 404 · 해석 실패)는 api_unreachable 에 가려진다. 두 사유의 운영 순서가 같은 것은 첫
// 단계(응답기 healthy 확인)뿐이다(설계 3-4 3).
func cutReason(err error) string {
	found := make(map[string]bool)
	for _, part := range joinedParts(err) {
		found[partReason(part)] = true
	}
	for _, reason := range [...]string{
		reasonDenyListUnreadable, reasonAPIAuth, reasonAPIIncompatible, reasonAPIUnreachable,
		reasonInternalError, reasonConnLogUnreadable, reasonConnLogWindowShort, reasonVerifyTimeout,
	} {
		if found[reason] {
			return reason
		}
	}
	return reasonInternalError
}

// joinedParts 는 err 가 errors.Join 이면 그 갈래들을, 아니면 err 하나를 돌려준다.
func joinedParts(err error) []error {
	if joined, ok := err.(interface{ Unwrap() []error }); ok {
		return joined.Unwrap()
	}
	return []error{err}
}

// partReason 은 Cut 이 묶은 까닭 하나의 reason 이다. 부르는 쪽 ctx 가 끝난 것(cut 시한)은 단계마다 꼴이 다르게 온다 —
// 목록 · kick 단계면 *mtxctl.Error{Kind: timeout}(ctx 에러를 감싸지 않는다), 확인 단계면 ctx 의 에러 그대로다. 둘 다
// Control API 가 시한 안에 답하지 않은 것이라 같은 api_unreachable 이다.
func partReason(err error) string {
	var apiErr *mtxctl.Error
	switch {
	case errors.Is(err, ingestauth.ErrDenyListUnreadable):
		return reasonDenyListUnreadable
	case errors.Is(err, ingestauth.ErrConnLogUnreadable):
		return reasonConnLogUnreadable
	case errors.Is(err, ingestauth.ErrConnLogWindowShort):
		return reasonConnLogWindowShort
	case errors.Is(err, ingestauth.ErrVerifyTimeout):
		return reasonVerifyTimeout
	case errors.Is(err, context.DeadlineExceeded), errors.Is(err, context.Canceled):
		return reasonAPIUnreachable
	case errors.As(err, &apiErr):
		return apiReason(apiErr)
	}
	return reasonInternalError
}

// apiReason 은 Control API 실패 하나의 reason 이다. Op · Kind 의 값과 404 의 갈래는 mtxctl.Error 문서의 것이다 — 목록의
// 404 는 경로가 없다는 것이고(설계의 /v3/srtconns/* 는 원본에서 deprecated 별칭 — 포크 api.go 217–220), 조회 · kick 의
// 404 는 연결 없음의 꼴이 아닌 404 다(연결 없음 404 는 mtxctl 이 「없음」으로 바꿔 여기 오지 않는다). 404 · 해석 실패 ·
// 여러 페이지는 무응답과 가른다 — cut 은 재시작으로 풀리지 않는다(판 · 경로 호환을 고쳐야 한다). 유출 키의 송출을 바로
// 끊는 수단은 MediaMTX 최후 재시작이고, 그것은 이 사유와 별개다(설계 3-4 3).
func apiReason(e *mtxctl.Error) string {
	switch {
	case e.Kind == "status" && e.Status == http.StatusUnauthorized:
		return reasonAPIAuth
	case e.Kind == "status" && e.Status == http.StatusNotFound, e.Kind == "parse", e.Kind == "pages":
		return reasonAPIIncompatible
	}
	return reasonAPIUnreachable
}
