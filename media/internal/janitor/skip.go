package janitor

import "time"

// failedBackoffMax 는 영구 실패 행 재시도 간격의 상한이다(FS 전역 보류 상한과 같은 값).
const failedBackoffMax = 30 * time.Minute

// rowKey 는 장부 한 행의 키다.
type rowKey struct {
	streamID string
	seq      int64
}

// skipKind 는 건너뛰기 항목의 까닭이다.
type skipKind uint8

const (
	skipFailed     skipKind = iota + 1 // 삭제 실패(권한 등) — 지수 보류
	skipRejected                       // 경로 검증 거부 — 1h
	skipUnexpected                     // 젊은 ENOENT(예상 밖) — 10m
	skipLocked                         // 행 잠금 경합(Busy) — Interval
)

type skipEntry struct {
	kind     skipKind
	until    time.Time
	failures int
	lap      int // 마지막으로 넣거나 방문한 바퀴
}

// skipList 는 「언제 다시 시도할까」만 정한다(설계 5-F — 보류는 삭제를 늦출 뿐 앞당기지 않는다).
// 단일 고루틴(Run)이 소유한다. 상한(10 × MaxPerCycle)이 차면 재시도 시각이 지난 항목부터 빼고,
// 그래도 차면 새 항목을 넣지 않는다 — 그 행은 다음 방문에 다시 시도된다.
type skipList struct {
	m        map[rowKey]*skipEntry
	cap      int
	interval time.Duration
	lap      int
	refused  bool // 이번 주기에 상한 때문에 넣지 못한 항목이 있었다(summary skip_full)
}

func newSkipList(capacity int, interval time.Duration) *skipList {
	return &skipList{m: make(map[rowKey]*skipEntry), cap: capacity, interval: interval}
}

// held 는 k 가 재시도 시각 전인가다. 방문으로 치므로 이번 바퀴에 본 항목이 된다.
func (s *skipList) held(k rowKey, now time.Time) bool {
	e, ok := s.m[k]
	if !ok {
		return false
	}
	e.lap = s.lap
	return now.Before(e.until)
}

// put 은 k 를 kind 로 hold 동안 보류한다. 상한 때문에 넣지 못하면 거짓이다.
func (s *skipList) put(k rowKey, kind skipKind, now time.Time, hold time.Duration) bool {
	e, ok := s.m[k]
	if !ok {
		if !s.makeRoom(now) {
			s.refused = true
			return false
		}
		e = &skipEntry{}
		s.m[k] = e
	}
	if e.kind != kind {
		e.failures = 0
	}
	e.kind, e.until, e.lap = kind, now.Add(hold), s.lap
	return true
}

// putFailed 는 k 를 영구 실패로 보류하고 재시도 시각을 돌려준다 — 간격은
// min(Interval × 2^(n−1), 30m) 이다(n = 연속 실패 수).
func (s *skipList) putFailed(k rowKey, now time.Time) time.Time {
	n := 1
	if e, ok := s.m[k]; ok && e.kind == skipFailed {
		n = e.failures + 1
	}
	hold := s.interval
	for i := 1; i < n && hold < failedBackoffMax; i++ {
		hold *= 2
	}
	hold = min(hold, failedBackoffMax)
	if !s.put(k, skipFailed, now, hold) {
		return now
	}
	s.m[k].failures = n
	return now.Add(hold)
}

func (s *skipList) makeRoom(now time.Time) bool {
	if len(s.m) < s.cap {
		return true
	}
	for k, e := range s.m {
		if !now.Before(e.until) {
			delete(s.m, k)
		}
	}
	return len(s.m) < s.cap
}

func (s *skipList) del(k rowKey)      { delete(s.m, k) }
func (s *skipList) has(k rowKey) bool { _, ok := s.m[k]; return ok }
func (s *skipList) len() int          { return len(s.m) }

// count 는 kinds 가운데 하나인 항목 수다.
func (s *skipList) count(kinds ...skipKind) int {
	n := 0
	for _, e := range s.m {
		for _, k := range kinds {
			if e.kind == k {
				n++
				break
			}
		}
	}
	return n
}

// sample 은 kinds 항목 가운데 하나의 키다(게이지 표본 — 없으면 거짓).
func (s *skipList) sample(kinds ...skipKind) (rowKey, bool) {
	for k, e := range s.m {
		for _, kind := range kinds {
			if e.kind == kind {
				return k, true
			}
		}
	}
	return rowKey{}, false
}

// endLap 은 커서가 끝에 닿았을 때 부른다. 이번 바퀴에 방문하지 않았고 재시도 시각이 지난 항목을
// 뺀다 — 후보에서 사라진 행(다른 주체가 NULL · DELETE)이 게이지를 영영 켜 두지 않게 한다.
func (s *skipList) endLap(now time.Time) {
	for k, e := range s.m {
		if e.lap < s.lap && !now.Before(e.until) {
			delete(s.m, k)
		}
	}
	s.lap++
}
