package control

import (
	"net/http"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

// rewindPattern 은 스트림 경로다(계약3 7-2절). HEAD 는 GET 패턴이 함께 받는다.
const rewindPattern = "GET /api/streams/{streamId}/rewind"

// rewindHandler 는 스트림 경로 처리기다. 요청마다 원자 포인터 읽기 하나 · map 조회 하나다(DB · S3 · 캐시 무접촉).
type rewindHandler struct {
	h   *status.Holder
	opt Options
	sh  *shared // Start 가 묶는다
}

// RewindRoute 는 스트림 경로 GET /api/streams/{streamId}/rewind 다. 차례: id 검사(어기면 404) → Board.Find
// (Unavailable 503 · NotFound 404) → Found 면 opt.Limiter.Allow(s)(넘으면 503) → 200. 칸은 Found 답에만 연다 —
// 모르는 ID 폭주는 칸을 만들지 않는다(R6).
func RewindRoute(h *status.Holder, opt Options) Route {
	opt = opt.withDefaults()
	requireLimiter(opt)
	rh := &rewindHandler{h: h, opt: opt}
	return Route{Pattern: rewindPattern, Handler: rh, bind: func(sh *shared) { rh.sh = sh }}
}

func (rh *rewindHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s := r.PathValue("streamId")
	if !validID(s) {
		writeError(w, r, rh.opt, http.StatusNotFound)
		return
	}
	now := rh.opt.Now()
	b := rh.h.Load()
	age, ok := b.Age(now)
	rh.sh.observeStale(age, ok, rh.opt.StaleAfter)
	st, answer := b.Find(s, now, rh.opt.StaleAfter)
	switch {
	case answer == status.NotFound:
		writeError(w, r, rh.opt, http.StatusNotFound)
	case answer != status.Found || !rh.opt.Limiter.Allow(s):
		writeError(w, r, rh.opt, http.StatusServiceUnavailable)
	default:
		writeStatus(w, r, rh.opt, st)
	}
}
