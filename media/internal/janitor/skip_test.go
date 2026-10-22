package janitor

import (
	"testing"
	"time"
)

func TestSkipListHoldsUntilRetryTimeThenReleases(t *testing.T) {
	now := time.Date(2026, 10, 19, 0, 0, 0, 0, time.UTC)
	s := newSkipList(4, time.Second)
	k := rowKey{"s1", 1}

	s.put(k, skipLocked, now, time.Second)
	if !s.held(k, now.Add(999*time.Millisecond)) {
		t.Error("보류 창 안인데 보류가 아니다")
	}
	if s.held(k, now.Add(time.Second)) {
		t.Error("재시도 시각이 지났는데 보류 중이다")
	}
	s.del(k)
	if s.held(k, now) || s.count(skipLocked) != 0 {
		t.Error("del 뒤에도 항목이 남았다")
	}
}

// D03 — 영구 실패는 지수로 성기게 다시 시도한다: Interval × 2^(n−1), 상한 30m(세부 선택 S1).
func TestSkipListFailedBackoffIsExponentialAndCapped(t *testing.T) {
	now := time.Date(2026, 10, 19, 0, 0, 0, 0, time.UTC)
	s := newSkipList(4, 30*time.Second)
	k := rowKey{"s1", 1}

	want := []time.Duration{30 * time.Second, time.Minute, 2 * time.Minute, 4 * time.Minute, 8 * time.Minute, 16 * time.Minute, 30 * time.Minute, 30 * time.Minute}
	for i, w := range want {
		until := s.putFailed(k, now)
		if got := until.Sub(now); got != w {
			t.Errorf("%d번째 실패 보류 = %v, want %v", i+1, got, w)
		}
	}
}

// 상한이 차면 재시도 시각이 지난 항목부터 빼고, 그래도 차면 새 항목을 넣지 않는다(세부 선택 S2).
func TestSkipListCapacityEvictsExpiredFirstThenRefuses(t *testing.T) {
	now := time.Date(2026, 10, 19, 0, 0, 0, 0, time.UTC)
	s := newSkipList(2, time.Second)
	s.put(rowKey{"s", 1}, skipLocked, now, time.Second)
	s.put(rowKey{"s", 2}, skipLocked, now, time.Hour)

	later := now.Add(2 * time.Second)
	if !s.put(rowKey{"s", 3}, skipLocked, later, time.Hour) {
		t.Fatal("지난 항목이 있는데 새 항목을 거절했다")
	}
	if s.has(rowKey{"s", 1}) {
		t.Error("재시도 시각이 지난 항목이 빠지지 않았다")
	}
	if s.put(rowKey{"s", 4}, skipLocked, later, time.Hour) {
		t.Error("지난 항목이 없는데 상한을 넘겨 넣었다")
	}
	if s.len() != 2 {
		t.Errorf("항목 수 = %d, want 2", s.len())
	}
}

// 한 바퀴 동안 다시 방문하지 않은 지난 항목은 바퀴 끝에 빠진다 — 후보에서 사라진 행이 게이지를
// 영영 켜 두지 않게(세부 선택 S8).
func TestSkipListPrunesExpiredEntriesNotSeenThisLap(t *testing.T) {
	now := time.Date(2026, 10, 19, 0, 0, 0, 0, time.UTC)
	s := newSkipList(8, time.Second)
	s.put(rowKey{"s", 1}, skipUnexpected, now, time.Second) // 이번 바퀴에 넣음
	s.endLap(now.Add(time.Hour))
	if !s.has(rowKey{"s", 1}) {
		t.Fatal("이번 바퀴에 본 항목을 뺐다")
	}
	s.endLap(now.Add(time.Hour)) // 다음 바퀴 — 방문 없음
	if s.has(rowKey{"s", 1}) {
		t.Error("한 바퀴 동안 방문하지 않은 지난 항목이 남았다")
	}
}
