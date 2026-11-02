package control

import (
	"encoding/json"
	"net/http"
	"regexp"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

// idPattern 은 경로 id 의 규칙이다 — 발행 키 성분과 같다(3.1절 · 계약3 7-1절). . · .. 는 따로 거른다.
var idPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,128}$`)

// validID 는 경로 id 로 쓸 수 있는가다. 어기면 처리기는 400 이 아니라 404 다 — 탐색 단서를 주지 않는다. 처리기는 그 앞에서
// 퍼센트 인코딩한 경로(r.URL.RawPath 가 빈 값이 아님 — 예: %61bc)도 404 로 끝낸다. PathValue 는 풀린 글자를 돌려주므로
// 엣지 거절 규칙을 비켜 같은 스트림의 칸을 깎을 수 있기 때문이다(보안 c3 M1).
func validID(id string) bool {
	return idPattern.MatchString(id) && id != "." && id != ".."
}

// body 는 응답 본문의 키 다섯이다(계약3 7-2절). 값이 없으면 null 이다.
type body struct {
	Ready    bool    `json:"ready"`
	Mode     *string `json:"mode"`
	URL      *string `json:"url"`
	FirstPDT *string `json:"firstPdt"`
	HeadPDT  *string `json:"headPdt"`
}

func bodyOf(st status.Status) body {
	b := body{Ready: st.Ready}
	if st.Mode != "" {
		m := string(st.Mode)
		b.Mode = &m
	}
	if st.URL != "" {
		b.URL = &st.URL
	}
	if !st.FirstPDT.IsZero() {
		p := rewind.FormatPDT(st.FirstPDT)
		b.FirstPDT = &p
	}
	if !st.HeadPDT.IsZero() {
		p := rewind.FormatPDT(st.HeadPDT)
		b.HeadPDT = &p
	}
	return b
}

// 오류 본문 둘.
var (
	notFoundBody    = []byte(`{"error":"not_found"}` + "\n")
	unavailableBody = []byte(`{"error":"unavailable"}` + "\n")
)

// writeStatus 는 200 과 st 본문이다.
func writeStatus(w http.ResponseWriter, r *http.Request, opt Options, st status.Status) {
	b, err := json.Marshal(bodyOf(st))
	if err != nil {
		// 문자열 · bool 뿐이라 실패할 수 없다. 나면 결함이다 — 끊긴 본문 대신 503 이다.
		writeError(w, r, opt, http.StatusServiceUnavailable)
		return
	}
	writeHead(w, r, opt, http.StatusOK)
	w.Write(append(b, '\n'))
}

// writeError 는 404 또는 503 과 그 본문이다.
func writeError(w http.ResponseWriter, r *http.Request, opt Options, code int) {
	writeHead(w, r, opt, code)
	if code == http.StatusNotFound {
		w.Write(notFoundBody)
		return
	}
	w.Write(unavailableBody)
}

// writeHead 는 상태 코드와 무관하게 같은 머리를 쓴다(R11) — 1초 캐시 · JSON, 503 이면 Retry-After: 1. CORS 허용 머리는
// 요청 Origin 이 설정과 정확히 같을 때만이고 credentials 머리는 싣지 않는다(3.5절 — prod 는 엣지 응답 머리 정책이 덮는다).
// 설정이 있으면 Vary: Origin 은 늘 싣는다 — 허용 머리 없는 답이 캐시돼 허용 Origin 에 재사용되지 않게(보안 L1).
func writeHead(w http.ResponseWriter, r *http.Request, opt Options, code int) {
	h := w.Header()
	h.Set("Cache-Control", "public, max-age=1")
	h.Set("Content-Type", "application/json")
	if code == http.StatusServiceUnavailable {
		h.Set("Retry-After", "1")
	}
	if opt.AllowOrigin != "" {
		h.Set("Vary", "Origin")
		if o := r.Header.Get("Origin"); o == opt.AllowOrigin {
			h.Set("Access-Control-Allow-Origin", o)
		}
	}
	w.WriteHeader(code)
}
