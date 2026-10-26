package control

import (
	"container/list"
	"context"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

// roundPattern 은 회차 경로다(경계 확정 4).
const roundPattern = "GET /api/streams/{streamId}/rounds/{roundId}/rewind"

// 회차 경로 캐시 · 관측 상수.
const (
	roundCacheTTL     = time.Second      // max-age=1 과 같은 1초 메모리 캐시
	roundShedClear    = 60 * time.Second // control_round_shed 를 내리는 무거부 시간
	lookupFailedBatch = 10 * time.Second // control_round_lookup_failed 를 묶는 시간
)

// RoundLookup 은 Board 가 모르는 회차를 DB 에서 한 번 읽는다. M6 가 구현한다 — index.LookupRoundPlayback 을 부르고
// status.FromPlaybackState 로 옮긴다(Drain 과 같은 함수). Found · NotFound 만 캐시하고, Unavailable · 오류는 503 이다.
type RoundLookup func(ctx context.Context, streamID, roundID string) (status.Status, status.Answer, error)

// RoundLimits 는 회차 경로 lookup 의 자원 상한이다(보완 2 ㉡ · 정정 ㉡ · R54).
type RoundLimits struct {
	MaxInFlight int           // 전역 동시 lookup
	PerSecond   int           // 전역 초당 lookup
	Timeout     time.Duration // lookup ctx 시한(WriteTimeout 5s 보다 짧게)
	CacheCells  int           // 1초 캐시 칸 상한(넘으면 가장 오래 쓰지 않은 칸 축출)
}

// DefaultRoundLimits 는 운영 상한이다 — 동시 2 · 초당 20 · 2초 · 4096 칸. 동시 2 는 인덱서 풀(pgx 기본 max(4, CPU) —
// 2 vCPU 상자에서 4)의 절반이라 조각 INSERT · 틱 장부에 둘을 남긴다. 초당 20 은 회차 닫힘 전 끝남 대기 회차 수의
// 위다. 값을 바꾸려면 R54 근거를 함께 바꾼다.
func DefaultRoundLimits() RoundLimits {
	return RoundLimits{MaxInFlight: 2, PerSecond: 20, Timeout: 2 * time.Second, CacheCells: 4096}
}

// roundKey 는 회차 하나다.
type roundKey struct{ stream, round string }

// roundReply 는 회차 경로의 답 하나다. code 가 200 이면 st 를 싣는다.
type roundReply struct {
	st   status.Status
	code int
}

// cachedReply 는 1초 캐시 칸이다.
type cachedReply struct {
	key   roundKey
	reply roundReply
	at    time.Time
}

// flight 는 같은 키의 동시 miss 가 기다리는 lookup 하나다. reply 는 done 을 닫기 전에 쓴다.
type flight struct {
	done  chan struct{}
	reply roundReply
}

// failBatch 는 lookup 실패 줄을 묶는 칸이다 — 처음 줄을 쓰고, lookupFailedBatch 안의 실패는 세었다가 그 뒤 첫 실패에
// 합쳐 쓴다.
type failBatch struct {
	loggedAt time.Time
	pending  int
}

// roundHandler 는 회차 경로 처리기다 — 처리기 · 1초 캐시 · 한도 · 상한 · 머리는 이것 하나다(보완 2 ㉠).
type roundHandler struct {
	h      *status.Holder
	opt    Options
	lookup RoundLookup
	sh     *shared // Start 가 묶는다

	mu       sync.Mutex // 아래를 지킨다. lookup 은 이 잠금 밖에서 부른다
	cache    map[roundKey]*list.Element
	lru      *list.List // 값 *cachedReply · 앞이 가장 최근
	flights  map[roundKey]*flight
	inFlight int
	second   time.Time // 초당 예산 창의 시작
	started  int       // 그 창에서 시작한 lookup 수
	shedSet  bool      // control_round_shed 게이지
	shedAt   time.Time // 마지막 거부 시각
	fails    map[string]*failBatch
}

// RoundRoute 는 회차 경로 GET /api/streams/{streamId}/rounds/{roundId}/rewind 다. 차례(보완 3):
//
//	id 검사(퍼센트 인코딩 경로 · 둘 다 3.1절 규칙 · 어기면 404) → FindRound(Unavailable 503 · NotFound 404) →
//	  hit        → Limiter.Allow(s)(넘으면 503) → 그 답
//	  Miss       → 1초 캐시 200 적중이면 Limiter.Allow(s) → 그 답 · 404 적중이면 칸 없이 404
//	             → 캐시 없음이면 공유 칸을 깎지 않는다 → 같은 (s, r) 키 singleflight → 전역 예산(동시 · 초당 — 넘으면
//	               503 Retry-After: 1) → lookup(ctx 시한) → 결과를 캐시(오류 · 시한 초과는 503 · 캐시 안 함)
//
// 그래서 무작위 roundId 로 남의 스트림 칸을 고갈시킬 수 없다. 같은 시청자의 두 경로 합산은 답한 요청이 같은 칸을
// 세므로 막힌다. 머리 · 본문은 RewindRoute 와 같은 함수로 쓴다.
func RoundRoute(h *status.Holder, opt Options, lookup RoundLookup) Route {
	opt = opt.withDefaults()
	requireLimiter(opt)
	rh := &roundHandler{
		h: h, opt: opt, lookup: lookup,
		cache: map[roundKey]*list.Element{}, lru: list.New(), flights: map[roundKey]*flight{}, fails: map[string]*failBatch{},
	}
	return Route{Pattern: roundPattern, Handler: rh, bind: func(sh *shared) {
		rh.sh = sh
		sh.sweeps = append(sh.sweeps, rh.sweep)
	}}
}

func (rh *roundHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s, rid := r.PathValue("streamId"), r.PathValue("roundId")
	if r.URL.RawPath != "" || !validID(s) || !validID(rid) {
		writeError(w, r, rh.opt, http.StatusNotFound)
		return
	}
	now := rh.opt.Now()
	b := rh.h.Load()
	age, ok := b.Age(now)
	rh.sh.observeStale(age, ok, rh.opt.StaleAfter)
	st, answer, hit := b.FindRound(s, rid, now, rh.opt.StaleAfter)
	switch {
	case hit:
		rh.answer(w, r, s, roundReply{st: st, code: http.StatusOK})
	case answer == status.NotFound:
		writeError(w, r, rh.opt, http.StatusNotFound)
	case answer == status.Miss:
		rh.miss(w, r, roundKey{s, rid}, now)
	default:
		writeError(w, r, rh.opt, http.StatusServiceUnavailable)
	}
}

// answer 는 답한 요청(hit · 캐시 200 적중)을 공유 칸에 세고 답을 쓴다.
func (rh *roundHandler) answer(w http.ResponseWriter, r *http.Request, s string, rep roundReply) {
	if !rh.opt.Limiter.Allow(s) {
		writeError(w, r, rh.opt, http.StatusServiceUnavailable)
		return
	}
	rh.write(w, r, rep)
}

func (rh *roundHandler) write(w http.ResponseWriter, r *http.Request, rep roundReply) {
	if rep.code == http.StatusOK {
		writeStatus(w, r, rh.opt, rep.st)
		return
	}
	writeError(w, r, rh.opt, rep.code)
}

// miss 는 Board 가 모르는 회차다. 1초 캐시 → singleflight → 전역 예산 → lookup 차례다.
func (rh *roundHandler) miss(w http.ResponseWriter, r *http.Request, key roundKey, now time.Time) {
	rh.mu.Lock()
	if rep, ok := rh.cached(key, now); ok {
		rh.mu.Unlock()
		if rep.code == http.StatusNotFound {
			rh.write(w, r, rep) // 404 적중은 칸을 열지 않는다 — 무작위 (s, r) 가 Board 밖 스트림 칸을 만들지 않게(보안 L2)
			return
		}
		rh.answer(w, r, key.stream, rep)
		return
	}
	if f := rh.flights[key]; f != nil {
		rh.mu.Unlock()
		select {
		case <-f.done:
			rh.write(w, r, f.reply)
		case <-r.Context().Done():
		}
		return
	}
	f := &flight{done: make(chan struct{})}
	rh.flights[key] = f
	granted := rh.take(now)
	rh.mu.Unlock()

	rep := roundReply{code: http.StatusServiceUnavailable}
	if granted {
		rep = rh.call(r.Context(), key, now)
	}
	rh.mu.Lock()
	delete(rh.flights, key)
	if granted {
		rh.inFlight--
		if rep.code != http.StatusServiceUnavailable {
			rh.store(key, rep, rh.opt.Now()) // 1초는 lookup 이 끝난 시각부터 — 느린 lookup 의 답이 저장 직후 만료되지 않게
		}
	}
	rh.mu.Unlock()
	f.reply = rep
	close(f.done)
	rh.write(w, r, rep)
}

// errLookupPanicked 는 lookup 이 패닉했다는 오류다(safeLookup 이 패닉을 이것으로 바꾼다).
var errLookupPanicked = errors.New("control: 회차 lookup 이 패닉했다")

// call 은 lookup 한 번이다. 시한은 RoundLimits.Timeout 이고 요청 취소와 떼어 둔다 — 같은 키를 기다리는 다른 요청이
// 있으므로 첫 요청의 연결이 끊겨도 끝까지 간다(시한이 상한이다). Found 는 200 · NotFound 는 404 이고 나머지는 503 이다.
// 실패 줄의 reason 은 error · timeout · panic 이다.
func (rh *roundHandler) call(ctx context.Context, key roundKey, now time.Time) roundReply {
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), rh.opt.RoundLimits.Timeout)
	defer cancel()
	st, answer, err := rh.safeLookup(ctx, key)
	switch {
	case err == nil && answer == status.Found:
		return roundReply{st: st, code: http.StatusOK}
	case err == nil && answer == status.NotFound:
		return roundReply{code: http.StatusNotFound}
	}
	reason := "error"
	switch {
	case errors.Is(err, errLookupPanicked):
		reason = "panic"
	case ctx.Err() != nil:
		reason = "timeout"
	}
	rh.mu.Lock()
	rh.noteFailure(reason, now)
	rh.mu.Unlock()
	return roundReply{code: http.StatusServiceUnavailable}
}

// safeLookup 은 lookup 을 부르고, 패닉하면 errLookupPanicked 로 바꿔 돌려준다. 패닉이 miss 를 빠져나가면 flight 칸 ·
// 전역 예산(inFlight)이 돌아오지 않고 같은 키를 기다리는 요청도 풀리지 않는다 — 패닉 둘이면 재기동까지 회차 경로 전체가
// 503 이다(보안 c2 M2). 그래서 패닉은 503 한 번으로 끝낸다.
func (rh *roundHandler) safeLookup(ctx context.Context, key roundKey) (st status.Status, answer status.Answer, err error) {
	defer func() {
		if p := recover(); p != nil {
			st, answer, err = status.Status{}, status.Unavailable, fmt.Errorf("%w: %v", errLookupPanicked, p)
		}
	}()
	return rh.lookup(ctx, key.stream, key.round)
}

// cached 는 key 의 1초 안 캐시 답이다(rh.mu 아래).
func (rh *roundHandler) cached(key roundKey, now time.Time) (roundReply, bool) {
	e, ok := rh.cache[key]
	if !ok {
		return roundReply{}, false
	}
	c := e.Value.(*cachedReply)
	if now.Sub(c.at) >= roundCacheTTL {
		return roundReply{}, false
	}
	rh.lru.MoveToFront(e)
	return c.reply, true
}

// store 는 key 의 답을 캐시한다 — 칸 상한이면 가장 오래 쓰지 않은 칸을 먼저 축출한다(rh.mu 아래).
func (rh *roundHandler) store(key roundKey, rep roundReply, now time.Time) {
	if e, ok := rh.cache[key]; ok {
		rh.lru.Remove(e)
		delete(rh.cache, key)
	}
	for len(rh.cache) >= rh.opt.RoundLimits.CacheCells && rh.lru.Len() > 0 {
		old := rh.lru.Back()
		rh.lru.Remove(old)
		delete(rh.cache, old.Value.(*cachedReply).key)
	}
	rh.cache[key] = rh.lru.PushFront(&cachedReply{key: key, reply: rep, at: now})
}

// take 는 전역 예산에서 lookup 하나를 받는다 — 동시 상한 · 초당 상한을 넘으면 거짓이고 control_round_shed 를
// 세운다(rh.mu 아래).
func (rh *roundHandler) take(now time.Time) bool {
	lim := rh.opt.RoundLimits
	if now.Sub(rh.second) >= time.Second {
		rh.second, rh.started = now, 0
	}
	switch {
	case rh.inFlight >= lim.MaxInFlight:
		rh.shed("in_flight", lim.MaxInFlight, now)
		return false
	case rh.started >= lim.PerSecond:
		rh.shed("per_second", lim.PerSecond, now)
		return false
	}
	rh.inFlight++
	rh.started++
	return true
}

// shed 는 예산 거부 하나를 적는다 — 처음이면 게이지를 세운다(rh.mu 아래).
func (rh *roundHandler) shed(reason string, limit int, now time.Time) {
	rh.shedAt = now
	if rh.shedSet {
		return
	}
	rh.shedSet = true
	rh.opt.Log.Warn("control_round_shed", "value", 1, "reason", reason, "limit", limit)
}

// noteFailure 는 lookup 실패 하나를 적는다 — 사유마다 lookupFailedBatch 에 한 줄로 묶는다(rh.mu 아래).
func (rh *roundHandler) noteFailure(reason string, now time.Time) {
	b := rh.fails[reason]
	if b == nil {
		b = &failBatch{}
		rh.fails[reason] = b
	}
	if !b.loggedAt.IsZero() && now.Sub(b.loggedAt) < lookupFailedBatch {
		b.pending++
		return
	}
	rh.opt.Log.Warn("control_round_lookup_failed", "reason", reason, "count", b.pending+1)
	b.loggedAt, b.pending = now, 0
}

// sweep 은 now 기준으로 1초 지난 캐시 칸을 지우고, 60초 거부가 없으면 control_round_shed 를 내린다(서버의 청소
// 고루틴이 부른다).
func (rh *roundHandler) sweep(now time.Time) {
	rh.mu.Lock()
	defer rh.mu.Unlock()
	for e := rh.lru.Back(); e != nil; {
		prev, c := e.Prev(), e.Value.(*cachedReply)
		if now.Sub(c.at) >= roundCacheTTL {
			rh.lru.Remove(e)
			delete(rh.cache, c.key)
		}
		e = prev
	}
	if rh.shedSet && now.Sub(rh.shedAt) >= roundShedClear {
		rh.shedSet = false
		rh.opt.Log.Warn("control_round_shed", "value", 0)
	}
}
