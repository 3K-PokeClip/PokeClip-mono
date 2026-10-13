package ingestauth

import (
	"sync"
	"time"
)

const (
	failedRecheckGap    = 4 * time.Second   // 기억된 토큰 전체의 재확인 간격. 바깥 가드(c 포함 1.181초)보다 길고 플러그인 재시도 간격(5초)보다 짧다
	failedWaitingWindow = 10 * time.Second  // 기억된 토큰이 이 안에 왔으면 차례를 기다리는 중으로 본다. 플러그인 5초 간격 구간의 한 주기 실측(8.0–8.3초)보다 길고, 30 · 60초 간격 구간의 주기보다 짧다 — 그 구간에서는 돌림이 서지 않는다
	failedForgetAfter   = 300 * time.Second // 토큰 하나의 기억이 그 토큰의 마지막 판정 불가 뒤 풀리는 시간. 플러그인의 5초 간격 구간과 같다. 차례 거절이 이어질 수 있는 상한이기도 하다
	failedTokensMax     = 4096              // 풀리지 않은 기억의 상한. 차면 더하지 않는다 — 그 토큰은 기억 없이 예산만 탄다
)

// admit 이 차례가 아니라서 거절한 까닭이다. 처리기가 recheck_wait 거절 로그의 wait 에 그대로 싣는다(설계 2-4 판정표).
const (
	waitGap   = "gap"   // 마지막 재확인에서 failedRecheckGap 이 지나지 않았다(칸이 닫힘)
	waitYield = "yield" // 그 토큰보다 오래 기다린 기억 토큰이 failedWaitingWindow 안에 왔다(양보)
)

// record 가 기억을 지운 범위다. 처리기가 ingestauth_failed_cleared 의 scope 에 그대로 싣는다(설계 6절).
const (
	clearedAll   = "all"   // 쓸 수 있는 값 — 기억을 모두 지웠다
	clearedToken = "token" // 쓸 수 없는 값 — 그 토큰만 지웠다
)

// resolveOutcome 은 failedTokens 가 보는 resolve 결과의 갈래다.
type resolveOutcome int

const (
	outcomeUndecided resolveOutcome = iota // 판정 불가(keyresolve.UndecidedError)
	outcomeUsable                          // valid:true 이고 passphrase 가 형식 안 — auth 가 비밀 저장소를 거쳐 쓸 수 있는 값을 돌려줬다
	outcomeUnusable                        // valid:false 또는 passphrase 형식 밖 — 판정은 났지만 auth 전체의 회복 증거로 쓰지 않는다
)

// failedTokens 는 resolve 가 판정 불가로 끝난 토큰의 기억이다(설계 3-8 실패 토큰 차례).
// 기억된 토큰은 전체에서 failedRecheckGap 에 하나만 auth 를 다시 부르고(재확인), 나머지는 곧바로
// 401 이다. 재확인 차례는 마지막 판정 불가가 가장 이른 활동 토큰이 갖는다(돌림). 재확인이 쓸 수 있는
// 값으로 끝나면 기억을 모두 지우고 — auth 가 비밀 저장소를 거쳐 답했다 —, 그 밖의 판정이면 그 토큰만
// 지운다. 마지막 판정 불가에서 failedForgetAfter 가 지난 항목은 기억 밖으로 다룬다. 시각은 모두 단조
// 시계로 잰다. 처리기 안에만 있고 내보내지 않는다. 응답기가 다시 뜨면 비어서 시작한다. 부르는 쪽은 설계 2-4
// 판정표 순서를 지킨다 — token 은 streamkey.IsToken 을 지난 값이고, admit 은 거부 목록 · 예산 · 요청 ctx 를 지난
// 요청에서만, record 는 resolve 를 부른 요청에서만 부른다.
//
// failedTokens 의 돌림은 재시도 주기가 failedWaitingWindow 보다 짧은 구간(플러그인의 5초 간격 구간)에서만
// 선다. 주기가 그보다 긴 구간(30 · 60초 간격)에서는 다른 기억 토큰이 올 때 가장 오래 기다린 토큰이 늘
// 활동 밖이라, 칸은 그 물결에 먼저 온 기억 토큰이 갖는다(도착 순서). 어느 구간이든 토큰 하나가 차례
// 때문에 거절되는 것은 그 토큰의 기억이 풀릴 때까지다 — 마지막 판정 불가에서 failedForgetAfter.
// 풀린 토큰은 차례 없이 예산 갈래로 resolve 한다(3-8절 기억 수명 상한).
//
// 「마지막 판정 불가가 더 이른」은 기록 순번으로 가른다. 지금을 잠금 안에서 읽으므로 순번 순서는 시각
// 순서와 같고, 같은 시각에 기록된 토큰은 먼저 기록된 쪽이 오래 기다렸다. 시험 시계가 뒤로 가면 지금을 새
// 기준으로 삼는다 — 그때 이미 풀린 항목은 치우고(되살아나지 않는다) 그보다 늦게 적힌 시각을 모두 지금으로
// 당겨, 칸은 길어야 failedRecheckGap 닫히고 기억은 길어야 failedForgetAfter 남고 활동은 길어야
// failedWaitingWindow 이어진다. 여러 고루틴이 함께 불러도 된다.
type failedTokens struct {
	now   func() time.Time
	limit int // 풀리지 않은 기억의 상한 — failedTokensMax. 시험이 작게 준다

	mu          sync.Mutex              // 아래 넷을 지킨다
	entries     map[string]*failedEntry // 토큰 → 기억. 풀린 항목은 치울 때까지 남지만 어디서도 세지 않는다
	lastRecheck time.Time               // 마지막 재확인(참을 돌려준 기억 토큰의 admit) 시각. 영값이면 아직 없다
	lastNow     time.Time               // 마지막으로 읽은 지금. 적힌 시각은 모두 이보다 늦지 않다
	seq         uint64                  // 마지막으로 매긴 기록 순번
}

// failedEntry 는 기억된 토큰 하나다.
type failedEntry struct {
	failedAt  time.Time // 마지막 판정 불가 시각. 여기서 failedForgetAfter 가 지나면 풀린다
	seq       uint64    // 마지막 판정 불가의 기록 순번. 작을수록 오래 기다렸다
	arrivedAt time.Time // 기억된 토큰으로 마지막에 온 시각(admit 만 적는다). 영값이면 아직 오지 않았다
}

// newFailedTokens 는 빈 기억을 만든다. now 가 nil 이면 time.Now 다.
func newFailedTokens(now func() time.Time) *failedTokens {
	if now == nil {
		now = time.Now
	}
	return &failedTokens{now: now, limit: failedTokensMax, entries: make(map[string]*failedEntry)}
}

// admit 는 token 이 지금 auth 를 불러도 되는지다. 기억에 없거나 기억이 풀렸으면 참이다 — 칸을 쓰지 않는다.
// 기억에 있으면 그 토큰이 온 시각을 적고, 마지막 재확인에서 failedRecheckGap 이 지났고 그 토큰보다 오래
// 기다린(마지막 판정 불가가 더 이른) 기억 토큰이 failedWaitingWindow 안에 오지 않았을 때만 참이다. 참이면
// 재확인 시각을 지금으로 둔다. 확인과 설정은 잠금 하나 안에서 한다.
//
// 거짓이면 wait 가 그 까닭이다 — waitGap(칸이 닫힘) · waitYield(양보). 참이면 wait 는 비어 있다.
func (f *failedTokens) admit(token string) (wait string, ok bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	now := f.readClock()
	e := f.live(token, now)
	if e == nil {
		return "", true
	}
	e.arrivedAt = now
	if f.gapClosed(now) {
		return waitGap, false
	}
	if f.olderWaiting(e, now) {
		return waitYield, false
	}
	f.lastRecheck = now
	return "", true
}

// record 는 token 의 resolve 결과를 적는다. outcomeUndecided 면 그 토큰을 지금부터 failedForgetAfter
// 동안 기억한다 — 이미 기억에 있으면 다시 재어 차례 맨 뒤로 보내고, 새로 기억할 때는 재확인 시각을
// 건드리지 않는다. 풀리지 않은 기억이 상한(limit)에 닿았으면 새로 기억하지 않는다. 판정이 났고 그 토큰이
// 풀리지 않은 기억에 있었으면, outcomeUsable 은 기억을 모두 지우고 outcomeUnusable 은 그 토큰만 지운다.
// 기억에 없던 토큰(풀린 항목 포함)의 판정은 기억을 바꾸지 않는다.
//
// 지웠으면 scope 가 clearedAll · clearedToken 이고 cleared 가 지운 기억 토큰 수다 — 풀린 항목은 세지
// 않는다. 지우지 않았으면 scope 는 비어 있고 cleared 는 0 이다.
func (f *failedTokens) record(token string, o resolveOutcome) (scope string, cleared int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	now := f.readClock()
	e := f.live(token, now)
	switch {
	case o == outcomeUndecided:
		f.remember(token, e, now)
	case e == nil:
		// 기억에 없던 토큰의 판정은 기억을 바꾸지 않는다.
	case o == outcomeUsable:
		f.sweep(now) // 풀린 항목은 세지 않는다
		scope, cleared = clearedAll, len(f.entries)
		clear(f.entries)
	case o == outcomeUnusable:
		delete(f.entries, token)
		scope, cleared = clearedToken, 1
	}
	return scope, cleared
}

// remember 는 token 의 판정 불가를 적는다. e 는 token 의 풀리지 않은 기억이고, 없으면 nil 이다. 이미
// 기억에 있으면 마지막 판정 불가를 다시 재어 차례 맨 뒤로 보낸다. 새로 기억할 때 맵이 상한이면 풀린 항목을
// 먼저 치우고, 그래도 차 있으면 기억하지 않는다. 재확인 시각은 건드리지 않는다. f.mu 를 잡은 채로 부른다.
func (f *failedTokens) remember(token string, e *failedEntry, now time.Time) {
	f.seq++
	if e != nil {
		e.failedAt, e.seq = now, f.seq
		return
	}
	if len(f.entries) >= f.limit {
		f.sweep(now)
		if len(f.entries) >= f.limit {
			return // 이 토큰은 기억 없이 예산만 탄다
		}
	}
	f.entries[token] = &failedEntry{failedAt: now, seq: f.seq}
}

// live 는 token 의 풀리지 않은 기억이다. 없거나 풀렸으면 nil 이고, 풀린 항목은 이때 치운다. f.mu 를 잡은
// 채로 부른다.
func (f *failedTokens) live(token string, now time.Time) *failedEntry {
	e, ok := f.entries[token]
	if !ok {
		return nil
	}
	if e.released(now) {
		delete(f.entries, token)
		return nil
	}
	return e
}

// gapClosed 는 재확인 칸이 닫혀 있는지다 — 마지막 재확인에서 failedRecheckGap 이 지나지 않았다. f.mu 를
// 잡은 채로 부른다.
func (f *failedTokens) gapClosed(now time.Time) bool {
	return !f.lastRecheck.IsZero() && now.Sub(f.lastRecheck) < failedRecheckGap
}

// olderWaiting 은 e 보다 오래 기다린(기록 순번이 작은) 풀리지 않은 기억 토큰이 차례를 기다리는 중인지다.
// f.mu 를 잡은 채로 부른다.
func (f *failedTokens) olderWaiting(e *failedEntry, now time.Time) bool {
	for _, o := range f.entries {
		if o.seq < e.seq && !o.released(now) && o.waiting(now) {
			return true
		}
	}
	return false
}

// sweep 은 풀린 항목을 모두 치운다. f.mu 를 잡은 채로 부른다.
func (f *failedTokens) sweep(now time.Time) {
	for token, e := range f.entries {
		if e.released(now) {
			delete(f.entries, token)
		}
	}
}

// readClock 은 지금을 읽는다. 시험 시계가 마지막으로 읽은 지금보다 뒤로 갔으면, 그때 이미 풀린 항목을 먼저
// 치운 뒤 그보다 늦게 적힌 시각을 모두 지금으로 당긴다 — 지금이 새 기준이다. 치우지 않고 당기면 풀린 항목이
// 되살아난다. 그래서 적힌 시각은 늘 마지막으로 읽은 지금보다 늦지 않다. f.mu 를 잡은 채로 부른다.
func (f *failedTokens) readClock() time.Time {
	now := f.now()
	if now.Before(f.lastNow) {
		f.sweep(f.lastNow)
		f.lastRecheck = earlier(f.lastRecheck, now)
		for _, e := range f.entries {
			e.failedAt = earlier(e.failedAt, now)
			e.arrivedAt = earlier(e.arrivedAt, now)
		}
	}
	f.lastNow = now
	return now
}

// released 는 e 의 기억이 풀렸는지다 — 마지막 판정 불가에서 failedForgetAfter 가 지났다.
func (e *failedEntry) released(now time.Time) bool {
	return now.Sub(e.failedAt) >= failedForgetAfter
}

// waiting 은 e 가 차례를 기다리는 중인지다 — 기억된 토큰으로 failedWaitingWindow 안에 왔다.
func (e *failedEntry) waiting(now time.Time) bool {
	return !e.arrivedAt.IsZero() && now.Sub(e.arrivedAt) < failedWaitingWindow
}

// earlier 는 t 와 u 가운데 이른 쪽이다.
func earlier(t, u time.Time) time.Time {
	if t.After(u) {
		return u
	}
	return t
}
