package edgebounds

// 원장 PG 문장(계획 부록 A — raise · closedUnreleased · markReleased · deleteReleased)의 뜻을 흉내 낸 가짜 원장.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"slices"
	"sync"
	"time"
)

// ledgerRow 는 stream_edge_bounds 한 행이다.
type ledgerRow struct {
	StreamID   string     `json:"stream_id"`
	RoundID    string     `json:"round_id"`
	Value      int64      `json:"dvr_floor"`
	ReleasedAt *time.Time `json:"released_at"`
}

// ledgerState 는 가짜 원장의 상태다 — 자식 프로세스 시험이 파일로 넘긴다. Open 은 열린 세션이 있는 회차(FloorKey)다.
type ledgerState struct {
	Open map[string]bool       `json:"open"`
	Rows map[string]*ledgerRow `json:"rows"`
}

// fakeLedger 는 원장이다. Raise 는 열린 회차 · 미해제 행만 GREATEST 로 올리고(없으면 만든다) 1 보다 작은 값 · 한 문장
// 안 같은 행 두 번은 문장 오류다(CHECK · ON CONFLICT 가 같은 행을 두 번 못 고침). ClosedUnreleased 는 열린 세션이 없는
// 미해제 행을 (stream, round) 순으로 limit 개, MarkReleased 는 미해제 행에만 시각을 적고, DeleteReleased 는 해제 뒤
// olderThan 이 지난 행을 지운다. 여러 고루틴이 함께 불러도 된다.
type fakeLedger struct {
	mu   sync.Mutex
	now  func() time.Time
	st   ledgerState
	path string // 비지 않으면 바뀔 때마다 상태를 이 파일에 쓴다

	// beforeMark 는 MarkReleased 가 원장을 바꾸기 전에 잠금 밖에서 부른다(시험 주입점).
	beforeMark func()
	raiseErr   error // 비지 않으면 Raise 가 이 오류다
}

func newFakeLedger(now func() time.Time) *fakeLedger {
	return &fakeLedger{now: now, st: ledgerState{Open: map[string]bool{}, Rows: map[string]*ledgerRow{}}}
}

// loadFakeLedger 는 path 에 저장된 상태로 가짜를 만든다 — 바뀔 때마다 같은 파일에 다시 쓴다.
func loadFakeLedger(path string, now func() time.Time) (*fakeLedger, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	l := newFakeLedger(now)
	if err := json.Unmarshal(data, &l.st); err != nil {
		return nil, err
	}
	l.path = path
	return l, nil
}

func (l *fakeLedger) Raise(_ context.Context, rows []Floor) ([]Floor, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.raiseErr != nil {
		return nil, l.raiseErr
	}
	seen := map[string]bool{}
	for _, r := range rows {
		k := FloorKey(r.StreamID, r.RoundID)
		switch {
		case r.Value < 1:
			return nil, fmt.Errorf("CHECK (dvr_floor >= 1) 위반: %s = %d", k, r.Value)
		case seen[k]:
			return nil, errors.New("ON CONFLICT DO UPDATE command cannot affect row a second time")
		}
		seen[k] = true
	}
	var out []Floor
	for _, r := range rows {
		k := FloorKey(r.StreamID, r.RoundID)
		if !l.st.Open[k] {
			continue
		}
		row, ok := l.st.Rows[k]
		switch {
		case !ok:
			row = &ledgerRow{StreamID: r.StreamID, RoundID: r.RoundID, Value: r.Value}
			l.st.Rows[k] = row
		case row.ReleasedAt != nil:
			continue
		default:
			row.Value = max(row.Value, r.Value)
		}
		out = append(out, Floor{StreamID: row.StreamID, RoundID: row.RoundID, Value: row.Value})
	}
	return out, l.save()
}

func (l *fakeLedger) ClosedUnreleased(_ context.Context, limit int) ([]Floor, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []Floor
	for k, row := range l.st.Rows {
		if row.ReleasedAt == nil && !l.st.Open[k] {
			out = append(out, Floor{StreamID: row.StreamID, RoundID: row.RoundID, Value: row.Value})
		}
	}
	slices.SortFunc(out, compareFloor)
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func (l *fakeLedger) MarkReleased(_ context.Context, rows []Floor) error {
	if f := l.beforeMark; f != nil {
		f()
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	for _, r := range rows {
		if row, ok := l.st.Rows[FloorKey(r.StreamID, r.RoundID)]; ok && row.ReleasedAt == nil {
			row.ReleasedAt = &now
		}
	}
	return l.save()
}

func (l *fakeLedger) DeleteReleased(_ context.Context, olderThan time.Duration) (int64, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	var n int64
	cut := l.now().Add(-olderThan)
	for k, row := range l.st.Rows {
		if row.ReleasedAt != nil && row.ReleasedAt.Before(cut) {
			delete(l.st.Rows, k)
			n++
		}
	}
	return n, l.save()
}

// openRound · closeRound 는 그 회차의 열린 세션을 만들고 닫는다(세션 행의 round_closed_at).
func (l *fakeLedger) openRound(streamID, roundID string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.st.Open[FloorKey(streamID, roundID)] = true
}

func (l *fakeLedger) closeRound(streamID, roundID string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.st.Open, FloorKey(streamID, roundID))
}

// seed 는 시험 준비용 미해제 행을 넣는다.
func (l *fakeLedger) seed(streamID, roundID string, value int64) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.st.Rows[FloorKey(streamID, roundID)] = &ledgerRow{StreamID: streamID, RoundID: roundID, Value: value}
}

// row 는 행의 사본이다.
func (l *fakeLedger) row(streamID, roundID string) (ledgerRow, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	row, ok := l.st.Rows[FloorKey(streamID, roundID)]
	if !ok {
		return ledgerRow{}, false
	}
	return *row, true
}

func (l *fakeLedger) save() error {
	if l.path == "" {
		return nil
	}
	return writeJSONAtomic(l.path, l.st)
}
