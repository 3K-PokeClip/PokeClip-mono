package edgebounds

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"strconv"
	"sync"
	"time"
)

// updateLimit 는 KVS 요청 하나의 puts + deletes 상한이다(UpdateKeys). 해제는 표지 put 하나를 빼고 releaseBatch 개를
// 지운다(Writer 계약 2 · 5).
const (
	updateLimit  = 50
	releaseBatch = updateLimit - 1
)

// Options 는 Writer 의 주기 · 기한 · 신호 기준이다(설계 3-3). 값의 하한 · 상한은 설정(배선)이 정하고, New 는 동작이
// 성립하지 않는 값만 거절한다.
type Options struct {
	FlushEvery    time.Duration // 300s — 하한 올림(D3). ReleaseEvery 의 정수배여야 한다
	ReleaseEvery  time.Duration // 30s — 틱 하나 · 닫힌 회차 해제 · 해제 행 삭제(결정 7 (가))
	ReleaseSettle time.Duration // 120s — 해제 뒤 vod 까지 유예(실측 기반 · 전역 보증 아님 — 4-3)
	Margin        time.Duration // 60s(D2) — 발행 뒤 하한 후보가 되기까지
	CallTimeout   time.Duration // 5s — 외부 호출 하나(KVS · DB)
	SizeWarnBytes int64         // 4 MiB — edge_bounds_store_large(KVS 5 MB 의 80%)
	WriterID      string        // 해제 표지 값의 앞머리 — 상자마다 다름(발행 fence 의 writer 값)
	Log           *slog.Logger  // nil 이면 slog.Default()
	Now           func() time.Time
}

// Writer 는 열린 회차의 /dvr 하한을 원장에 올려 KVS 에 투영하고, 닫힌 회차의 KVS 키를 지워 원장에 해제를 적는다.
// 정본은 원장이다 — Writer 의 기억(관측 이력 · 신호 상태)은 재기동하면 비워지고, 해제할 목록은 늘 원장에서 읽는다.
// Observe 는 여러 고루틴에서 불러도 되고, 나머지는 Run 고루틴 하나가 차례로 한다.
type Writer struct {
	ledger          Ledger
	store           Store
	opt             Options
	raiseEveryTicks int

	mu     sync.Mutex          // rounds · blocked 를 지킨다 — Observe(발행 루프)와 Run 이 함께 쓴다
	rounds map[string]*history // FloorKey → 관측 이력
	// blocked 는 dvr_floor_blocks_list 가 선 회차(FloorKey)다.
	blocked map[string]bool

	// 아래는 Run 고루틴만 쓴다.
	failing    map[string]bool         // 쓰기 실패 신호가 선 패스
	closed     map[string]*closedRound // FloorKey → 해제를 기다리는 닫힌 회차
	storeLarge bool
	lastMarker int64 // 마지막 해제 표지의 나노초
}

// New 는 시간 값이 0 이하 · Margin < 0 · FlushEvery 가 ReleaseEvery 의 정수배가 아님 · WriterID 빈 값 ·
// SizeWarnBytes 0 이하면 거절한다.
func New(l Ledger, s Store, opt Options) (*Writer, error) {
	switch {
	case l == nil || s == nil:
		return nil, errors.New("edgebounds: 원장 · 저장소는 nil 일 수 없다")
	case opt.FlushEvery <= 0 || opt.ReleaseEvery <= 0 || opt.ReleaseSettle <= 0 || opt.CallTimeout <= 0:
		return nil, fmt.Errorf("edgebounds: FlushEvery(%v) · ReleaseEvery(%v) · ReleaseSettle(%v) · CallTimeout(%v) 는 양수여야 한다",
			opt.FlushEvery, opt.ReleaseEvery, opt.ReleaseSettle, opt.CallTimeout)
	case opt.Margin < 0:
		return nil, fmt.Errorf("edgebounds: Margin(%v) 은 0 이상이어야 한다", opt.Margin)
	case opt.FlushEvery%opt.ReleaseEvery != 0:
		return nil, fmt.Errorf("edgebounds: FlushEvery(%v) 는 ReleaseEvery(%v) 의 정수배여야 한다", opt.FlushEvery, opt.ReleaseEvery)
	case opt.WriterID == "":
		return nil, errors.New("edgebounds: WriterID 가 비었다")
	case opt.SizeWarnBytes <= 0:
		return nil, fmt.Errorf("edgebounds: SizeWarnBytes(%d) 는 양수여야 한다", opt.SizeWarnBytes)
	}
	if opt.Log == nil {
		opt.Log = slog.Default()
	}
	if opt.Now == nil {
		opt.Now = time.Now
	}
	return &Writer{
		ledger:          l,
		store:           s,
		opt:             opt,
		raiseEveryTicks: int(opt.FlushEvery / opt.ReleaseEvery),
		rounds:          map[string]*history{},
		blocked:         map[string]bool{},
		failing:         map[string]bool{},
		closed:          map[string]*closedRound{},
	}, nil
}

// Observe 는 발행 성공 하나다. roundID 는 그 목록 마지막 행 ③ 키의 회차(ParseSegKey) — 옛 꼴이면 부르지 않는다.
// msn 이 1 보다 작은 목록은 하한이 될 수 없어 버린다. 망을 쓰지 않는다(짧은 잠금뿐).
func (w *Writer) Observe(streamID, roundID string, msn int64, at time.Time) {
	if msn < 1 {
		return
	}
	key := FloorKey(streamID, roundID)
	w.mu.Lock()
	defer w.mu.Unlock()
	h, ok := w.rounds[key]
	if !ok {
		h = &history{streamID: streamID, roundID: roundID}
		w.rounds[key] = h
	}
	h.add(observation{msn: msn, at: at}, w.opt.Now().Add(-w.opt.Margin))
}

// Run 은 쓰기 고루틴 하나다. ReleaseEvery 마다 틱 하나 — 틱마다 해제를 하고, FlushEvery/ReleaseEvery 번째 틱마다
// 그 뒤에 올림을 한다(같은 고루틴에서 차례로). ctx 가 끝나면 돌아온다 — 정본은 원장이라 남은 투영은 버린다.
func (w *Writer) Run(ctx context.Context) {
	t := time.NewTicker(w.opt.ReleaseEvery)
	defer t.Stop()
	for n := 1; ; n++ {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		w.runPass(ctx, passRelease, w.release)
		if n%w.raiseEveryTicks == 0 {
			w.runPass(ctx, passRaise, w.raise)
		}
	}
}

// runPass 는 패스 하나를 돌려 신호를 맞춘다. ctx 가 끝나 실패한 것은 알리지 않는다(종료는 장애가 아니다).
func (w *Writer) runPass(ctx context.Context, pass string, f func(context.Context) error) {
	err := f(ctx)
	if ctx.Err() != nil {
		return
	}
	w.report(pass, err)
}

// raise 는 올림 한 번이다(Writer 계약 1). Margin 을 지난 관측이 있는 회차를 50개씩 — 판 E → 원장 Raise(열린 회차 ·
// 미해제만 GREATEST) → 원장이 돌려준 값만 If-Match E 로 put. KVS 를 읽어 값을 정하지 않는다. 첫 실패에서 멈춘다 —
// 다음 올림 틱이 처음부터 다시 한다.
func (w *Writer) raise(ctx context.Context) error {
	for chunk := range slices.Chunk(w.candidates(), updateLimit) {
		read := func(ctx context.Context) (batch, error) { return w.raiseBatch(ctx, chunk) }
		if _, err := w.attempt(ctx, read); err != nil {
			return err
		}
	}
	return nil
}

// candidates 는 Margin 을 지난 관측이 있는 회차의 하한 후보다((stream, round) 순 — 원장 잠금 순서와 같다).
func (w *Writer) candidates() []Floor {
	cutoff := w.opt.Now().Add(-w.opt.Margin)
	w.mu.Lock()
	defer w.mu.Unlock()
	var out []Floor
	for _, h := range w.rounds {
		h.mature(cutoff)
		if h.ready >= 1 {
			out = append(out, Floor{StreamID: h.streamID, RoundID: h.roundID, Value: h.ready})
		}
	}
	slices.SortFunc(out, compareFloor)
	return out
}

func compareFloor(a, b Floor) int {
	return cmp.Or(cmp.Compare(a.StreamID, b.StreamID), cmp.Compare(a.RoundID, b.RoundID))
}

// raiseBatch 는 원장에 want 를 올리고 원장이 돌려준 행의 put 묶음을 만든다.
func (w *Writer) raiseBatch(ctx context.Context, want []Floor) (batch, error) {
	var rows []Floor
	err := w.call(ctx, func(ctx context.Context) (err error) {
		rows, err = w.ledger.Raise(ctx, want)
		return err
	})
	if err != nil {
		return batch{}, fmt.Errorf("edgebounds: 원장 Raise: %w", err)
	}
	rows = uniqueRows(rows)
	w.settle(want, rows)
	puts := make([]Entry, 0, len(rows))
	for _, r := range rows {
		v, err := EncodeValue(r.Value)
		if err != nil {
			return batch{}, err
		}
		puts = append(puts, Entry{Key: FloorKey(r.StreamID, r.RoundID), Value: v})
	}
	return batch{rows: rows, puts: puts}, nil
}

// settle 은 원장 Raise 결과로 이력과 차단 상태를 맞춘다. 원장이 돌려주지 않은 회차는 닫혔거나 해제된 것이라(회차는
// 다시 열리지 않는다 — 설계 2-2) 잊는다. 돌려준 하한이 그 회차 최근 발행 목록의 MSN 보다 크면 엣지가 그 목록의 앞
// 조각을 막는다 — 차단 상태다(Writer 계약 6).
func (w *Writer) settle(want, rows []Floor) {
	floors := make(map[string]int64, len(rows))
	for _, r := range rows {
		floors[FloorKey(r.StreamID, r.RoundID)] = r.Value
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	for _, f := range want {
		key := FloorKey(f.StreamID, f.RoundID)
		floor, open := floors[key]
		h := w.rounds[key]
		switch {
		case !open:
			delete(w.rounds, key)
			w.setBlocked(f, false, 0, 0)
		case h != nil:
			w.setBlocked(f, floor > h.latest, floor, h.latest)
		}
	}
}

// release 는 해제 한 번이다(Writer 계약 2) — 유예가 지난 해제 행 삭제 → 판 E → 닫힌 미해제 회차 releaseBatch 개 →
// If-Match E 로 키 지우기 + 해제 표지 put → 원장 해제. 해제할 목록은 늘 원장에서 읽는다 — 키를 지운 뒤 원장에 적기
// 전에 프로세스가 죽어도 다음 프로세스가 같은 행을 다시 읽어 마무리한다(E2 · 없는 키 지우기는 성공 — H3-3).
func (w *Writer) release(ctx context.Context) error {
	delErr := w.call(ctx, func(ctx context.Context) error {
		_, err := w.ledger.DeleteReleased(ctx, w.opt.ReleaseSettle)
		return err
	})
	if delErr != nil {
		delErr = failed(fmt.Errorf("edgebounds: 원장 DeleteReleased: %w", delErr))
	}
	b, err := w.attempt(ctx, w.releaseBatch)
	if err != nil {
		return errors.Join(delErr, err)
	}
	if len(b.rows) == 0 {
		return delErr
	}
	err = w.call(ctx, func(ctx context.Context) error { return w.ledger.MarkReleased(ctx, b.rows) })
	if err != nil {
		return errors.Join(delErr, failed(fmt.Errorf("edgebounds: 원장 MarkReleased: %w", err)))
	}
	w.released(b.rows)
	return delErr
}

// releaseBatch 는 닫힌 미해제 회차를 원장에서 읽어 지우기 묶음을 만든다. 표지 put 은 요청마다 새 값이다 — 지울 키가
// KVS 에 아직 없어(그 회차 첫 올림 전) 지우기가 판을 바꾸지 않을 때도 판을 바꿔, 옛 판으로 보낸 늦은 put 을 막는다
// (Writer 계약 3 (ii) · 실측 H3-4 · ISB (1)).
func (w *Writer) releaseBatch(ctx context.Context) (batch, error) {
	var rows []Floor
	err := w.call(ctx, func(ctx context.Context) (err error) {
		rows, err = w.ledger.ClosedUnreleased(ctx, releaseBatch)
		return err
	})
	if err != nil {
		return batch{}, fmt.Errorf("edgebounds: 원장 ClosedUnreleased: %w", err)
	}
	complete := len(rows) < releaseBatch
	rows = uniqueRows(rows)
	rows = rows[:min(len(rows), releaseBatch)]
	w.trackClosed(rows, complete)
	if len(rows) == 0 {
		return batch{}, nil
	}
	deletes := make([]string, 0, len(rows))
	for _, r := range rows {
		deletes = append(deletes, FloorKey(r.StreamID, r.RoundID))
	}
	return batch{rows: rows, puts: []Entry{{Key: ReleaseMarkerKey, Value: w.nextMarker()}}, deletes: deletes}, nil
}

// nextMarker 는 해제 표지 값 WriterID + ":" + 나노초다. 시계가 멈추거나 되돌아도 앞 값보다 큰 나노초를 써 요청마다
// 다르다.
func (w *Writer) nextMarker() string {
	w.lastMarker = max(w.opt.Now().UnixNano(), w.lastMarker+1)
	return w.opt.WriterID + ":" + strconv.FormatInt(w.lastMarker, 10)
}

// batch 는 KVS 요청 하나의 묶음과 그것을 만든 원장 행이다.
type batch struct {
	rows    []Floor
	puts    []Entry
	deletes []string
}

func (b batch) empty() bool { return len(b.puts)+len(b.deletes) == 0 }

// attempt 는 판 E 를 읽고, read 로 원장을 읽어 묶음을 만들어, If-Match E 로 보낸다 — 판을 얻은 뒤에 원장을 읽는다
// (Writer 계약 1 · 2). 보낸 요청이 실패하면 Describe 로 판을 다시 본다(계약 4). 판이 E 와 다르면 경합이다 — read 로
// 원장을 다시 읽어 새 판으로 한 번 더 보낸다. 같으면 실패다. 오류 종류 · 상태 코드로 가르지 않는다 — 선행 조건 실패는
// 실측상 ValidationException · HTTP 400 이라(83 C1) 409 · 412 를 기다리면 놓친다. 응답을 잃은 성공도 판이 바뀌어
// 경합으로 가고, 다시 보내도 같은 결과다. 묶음이 비면 보내지 않는다. 돌려주는 묶음은 성공한 요청의 것이다.
func (w *Writer) attempt(ctx context.Context, read func(context.Context) (batch, error)) (batch, error) {
	etag, err := w.describe(ctx)
	if err != nil {
		return batch{}, failed(err)
	}
	b, err := read(ctx)
	if err != nil || b.empty() {
		return batch{}, failedOrNil(err)
	}
	sendErr := w.update(ctx, etag, b)
	if sendErr == nil {
		return b, nil
	}
	now, err := w.describe(ctx)
	switch {
	case err != nil:
		return batch{}, failed(errors.Join(sendErr, err))
	case now == etag:
		return batch{}, failed(sendErr)
	}
	b, err = read(ctx)
	if err != nil || b.empty() {
		return batch{}, failedOrNil(err)
	}
	if err := w.update(ctx, now, b); err != nil {
		return batch{}, &passError{reason: reasonConflict, err: err}
	}
	return b, nil
}

func failedOrNil(err error) error {
	if err == nil {
		return nil
	}
	return failed(err)
}

// describe 는 KVS 의 지금 판이다. 크기 경고도 여기서 본다.
func (w *Writer) describe(ctx context.Context) (string, error) {
	var st Stat
	err := w.call(ctx, func(ctx context.Context) (err error) {
		st, err = w.store.Describe(ctx)
		return err
	})
	switch {
	case err != nil:
		return "", fmt.Errorf("edgebounds: KVS Describe: %w", err)
	case st.ETag == "":
		return "", errors.New("edgebounds: KVS Describe 가 빈 판(ETag)을 돌려줬다")
	}
	w.checkSize(st)
	return st.ETag, nil
}

func (w *Writer) update(ctx context.Context, etag string, b batch) error {
	err := w.call(ctx, func(ctx context.Context) error {
		_, err := w.store.Update(ctx, etag, b.puts, b.deletes)
		return err
	})
	if err != nil {
		return fmt.Errorf("edgebounds: KVS Update: %w", err)
	}
	return nil
}

// call 은 외부 호출 하나를 CallTimeout 안에 부른다.
func (w *Writer) call(ctx context.Context, f func(context.Context) error) error {
	ctx, cancel := context.WithTimeout(ctx, w.opt.CallTimeout)
	defer cancel()
	return f(ctx)
}

// uniqueRows 는 같은 회차가 두 번 든 행을 하나로 줄인다(큰 하한을 남긴다) — 한 요청에 같은 키가 둘이면 요청 전체가
// 400 이다(실측 H3-5 · Writer 계약 5).
func uniqueRows(rows []Floor) []Floor {
	out := make([]Floor, 0, len(rows))
	at := make(map[string]int, len(rows))
	for _, r := range rows {
		key := FloorKey(r.StreamID, r.RoundID)
		if i, ok := at[key]; ok {
			out[i].Value = max(out[i].Value, r.Value)
			continue
		}
		at[key] = len(out)
		out = append(out, r)
	}
	return out
}
