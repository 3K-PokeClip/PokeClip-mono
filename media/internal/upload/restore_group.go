package upload

import (
	"bytes"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 이 파일은 구멍 한 무리의 복원이다(설계 r6 2.10 「구멍 하나의 처리」 · 「닻 되살리기」 · 「이미 있는 객체는 검증한 뒤 채택한다」
// · 「구멍 뒤 uploaded 행 대조」 · 「물러난 뒤에도 읽히지 않는 행은 지나간다」).
//
//	닻      같은 회차의 앞선 ③ uploaded 행 — 그 ③ 객체와 ② 로 도장을 되짚는다(같은 프로세스의 지난 복원 링크가 있으면 그것)
//	접기    닻부터 구멍 끝까지 장부 행마다 ② 를 읽어 playback.ChainFold 로 잇는다(실시간 사슬과 한 걸음 함수)
//	대조    구멍 뒤 같은 회차의 첫 uploaded 행 n 의 객체 도장이 접은 도장과 다르면 그 구멍은 만들지 않는다(chain_mismatch)
//	적용    표 줄(모르는 seq 만) · 구멍 행 작업 · 복원 링크

// groupRun 은 무리 하나의 복원 상태다 — 복원 고루틴 하나만 만진다.
type groupRun struct {
	u    *Uploader
	w    restoreWake
	g    holeGroup
	lg   *slog.Logger
	rows []index.PlaybackChainRow
	// limit 은 읽은 장부 구간의 끝이다 — 마지막 행이 이보다 앞이면 그 행이 스트림 꼬리다.
	limit int64
	// bodies 는 읽은 ② 바이트다(seq → 본문 · 원천이 없으면 nil) — 이음 끝 · 객체 검증의 재료다.
	bodies map[int64][]byte
	// exact 는 닻 도장이 실제 값인가다. 아니면 실제 값은 닻 도장(그리고 뒤 행 도장 모두)에서 [−slackLo, +slackHi] 안이다.
	exact            bool
	slackLo, slackHi time.Duration
	// ruleErr 는 이음 규칙이 원천을 읽다 만난 일시 실패다(규칙은 오류를 돌려줄 수 없다).
	ruleErr error
	// anchorLink · start 는 접기의 닻(nil 이면 회차 첫 행부터)과 접기를 시작할 행 자리다.
	anchorLink *playback.Link
	start      int

	links []playback.Link
	// rowAt 은 rows 의 seq → 자리 색인이다(fetchFrom 이 만든다).
	rowAt   map[int64]int
	adopted map[int64]bool
	passed  map[int64]bool
	waited  bool
}

// mismatch 는 구멍 뒤 uploaded 행의 도장 대조 실패다.
type mismatch struct {
	seq      int64
	expected time.Duration
	window   playback.StampWindow
}

// restoreBackPage 는 접기 시작점을 찾아 뒤로 읽을 때 한 번에 읽는 장부 행 수다 — 회차 시작이나 mtxi 있는 닻을 만날 때까지
// 이만큼씩 더 읽는다(행 메타데이터만 — ② 본문은 닻 후보 하나씩).
const restoreBackPage = 256

// errMemoryStale 은 지난 복원 링크에서 이으려고 장부 구간을 그 링크부터 읽었는데 그 행의 장부 크기가 바뀌었다는 뜻이다 — 닻
// 행부터 다시 읽는다.
var errMemoryStale = errors.New("지난 복원 링크의 행이 바뀌었다")

// restoreGroup 은 무리 g 를 한 번에 끝까지 되살린다 — 장부 구간은 접기 시작점(지난 복원 링크 · 닻 · 회차 첫 행)부터 limit
// 까지다. 구멍 뒤 대조를 마친 뒤에만 표 줄 · 작업을 낸다(c10b 확인 패스 cx H-1 — 판을 나누면 앞 판이 대조 전에 올린다). 메모리는
// ② 본문을 하나만 쥐어(keepBodyOf) 무리 길이와 무관하다(c10b 보안 검수 H). 행 메타데이터는 행당 수백 바이트다.
func (u *Uploader) restoreGroup(w restoreWake, g holeGroup, limit int64, lg *slog.Logger) error {
	run, err := u.newGroupRun(w, g, limit, lg, true)
	if errors.Is(err, errMemoryStale) {
		run, err = u.newGroupRun(w, g, limit, lg, false)
	}
	if err != nil || run == nil {
		return err
	}
	mm, err := run.fold(run.anchorLink, run.start)
	switch {
	case err != nil:
		return err
	case mm != nil:
		return run.settleMismatch(run.span(), run.fingerprint(), *mm)
	}
	run.commit(run.anchorLink)
	return nil
}

// newGroupRun 은 무리 하나의 장부 구간을 읽고 닻을 정한다. 같은 입력으로 이미 불일치 판정한 무리면 nil 이다. useMemory 면 같은
// 프로세스의 지난 복원 링크가 닻 뒤 · 구멍 끝 이전에 있을 때 그 링크에서 잇는다(따라잡기 — 구간도 그 링크부터 · S3 를 다시 부르지
// 않는다). 그 링크까지의 구멍은 지난 복원이 표 줄을 적고 다시 넣었다 — 아직 ③ pending 이라 구멍으로 다시 열거될 뿐이다. 그 행의
// 장부 크기가 바뀌었으면(꼬리 교정) errMemoryStale 이다.
func (u *Uploader) newGroupRun(w restoreWake, g holeGroup, limit int64, lg *slog.Logger, useMemory bool) (*groupRun, error) {
	from := g.first - 1
	if g.anchor != nil {
		from = *g.anchor
	}
	run := &groupRun{u: u, w: w, g: g, lg: lg, bodies: map[int64][]byte{}, adopted: map[int64]bool{}, passed: map[int64]bool{}}
	shifted := false
	if rl, ok := u.sessions.restoredLink(w.sessionID); useMemory && ok && rl.link.Seq > from && rl.link.Seq <= g.last {
		from, shifted = rl.link.Seq, true
	}
	run.limit = limit
	if err := run.fetchFrom(max(from, 0)); err != nil {
		return nil, err
	}
	if u.sessions.knownMismatch(w.sessionID, run.span(), run.fingerprint()) {
		return nil, nil
	}
	if shifted {
		l, i, ok := run.memoryAnchor()
		if !ok {
			return nil, errMemoryStale
		}
		run.anchorLink, run.start = l, i
		return run, run.preload(*l)
	}
	l, i, err := run.anchor()
	run.anchorLink, run.start = l, i
	return run, err
}

// checkSeq 는 구멍 뒤 같은 회차의 첫 ③ uploaded 행이다(없으면 −1) — 장부만으로 정해진다.
func (run *groupRun) checkSeq() int64 {
	for _, r := range run.rows {
		if r.Seq > run.g.last && r.SessionID == run.w.sessionID && r.PlaybackUploadState == index.UploadStateUploaded {
			return r.Seq
		}
	}
	return -1
}

// span 은 불일치 기억의 열쇠다.
func (run *groupRun) span() holeSpan {
	anchor := int64(-1)
	if run.g.anchor != nil {
		anchor = *run.g.anchor
	}
	return holeSpan{from: run.g.first, to: run.g.last, anchor: anchor, check: run.checkSeq()}
}

// fingerprint 는 판정에 쓰는 행들의 장부 크기다 — 꼬리 교정으로 바뀌면 다시 판정한다.
func (run *groupRun) fingerprint() string {
	parts := make([]string, 0, len(run.rows))
	for _, r := range run.rows {
		b := "-"
		if r.Bytes != nil {
			b = strconv.FormatInt(*r.Bytes, 10)
		}
		parts = append(parts, b)
	}
	return strings.Join(parts, ",")
}

// fetchFrom 은 장부 구간 [from, limit] 을 읽어 접을 행으로 둔다(seq → 자리 색인도 — 행마다 찾기가 선형이면 긴 무리에서 O(n²)).
func (run *groupRun) fetchFrom(from int64) error {
	rows, err := run.chainRows(from, run.limit)
	run.rows = rows
	run.rowAt = make(map[int64]int, len(rows))
	for i, r := range rows {
		run.rowAt[r.Seq] = i
	}
	return err
}

// chainRows 는 스트림의 장부 구간 [from, to] 다.
func (run *groupRun) chainRows(from, to int64) ([]index.PlaybackChainRow, error) {
	ledger, _, _ := run.u.restoreDeps()
	ctx, cancel := run.u.dbCtx()
	defer cancel()
	rows, err := ledger.PlaybackChainRows(ctx, run.w.streamID, from, to)
	if err != nil {
		return nil, failRestore("ledger", err)
	}
	return rows, nil
}

// isTail 은 r 이 스트림의 꼬리인가다 — 읽은 구간의 마지막 행이고 구간 끝에 닿지 않았다(seq 는 이어 매긴다).
func (run *groupRun) isTail(r index.PlaybackChainRow) bool {
	return len(run.rows) > 0 && run.rows[len(run.rows)-1].Seq == r.Seq && r.Seq < run.limit
}

// rowIndex 는 seq 행의 자리다(없으면 −1).
func (run *groupRun) rowIndex(seq int64) int {
	if i, ok := run.rowAt[seq]; ok {
		return i
	}
	return -1
}

// anchor 는 지난 복원 링크를 쓰지 않을 때(newGroupRun 이 그 링크에서 잇는 갈래를 먼저 본다) 접기의 닻과 접기를 시작할 행
// 자리다 — ③ uploaded 닻 객체로 되짚는다. 닻이 고정 행이거나 없으면 뒤로 읽어 정한다(backAnchor).
func (run *groupRun) anchor() (*playback.Link, int, error) {
	if run.g.anchor != nil {
		i := run.rowIndex(*run.g.anchor)
		if i < 0 {
			return nil, 0, anchorErr(*run.g.anchor, "ledger", errors.New("장부에 닻 행이 없다"))
		}
		l, err := run.objectAnchor(run.rows[i])
		if !errors.Is(err, errAnchorPinned) {
			if err != nil {
				return nil, 0, err
			}
			return l, i + 1, nil
		}
	}
	return run.backAnchor()
}

// backAnchor 는 구멍(닻이 고정 행이면 그 닻) 앞을 뒤로 읽어 접기 시작점을 정한다(c10b cx 검수 H3 · H4 · 확인 패스 cc H-A ·
// cx H-2). restoreBackPage 행씩 읽어 나간다.
//
//	mtxi 있는 같은 회차 ③ uploaded 행   그 행을 닻으로 삼아 사이의 고정 행을 실시간 규칙으로 다시 접는다(r6 2.10 「닻이 고정
//	                                    행이면 …실시간과 같다」 — 고정 행의 기대값은 앞 링크에서 나온다)
//	다른 회차 행 · 장부 첫 행           회차 첫 행부터 닻 없이 접는다(r6 2.10 「세션에 uploaded ③ 이 없으면 세션 첫 행부터」 —
//	                                    컷오프 앞이라 구멍 조회 밖인 같은 회차 행도 접는다)
//
// 정한 시작점부터 장부 구간을 다시 읽는다. 회차가 길면 접는 행이 많아질 뿐(② 를 하나씩 읽는다) 구간에 상한을 두지 않는다 — 상한을
// 두면 그 끝에서 같은 일을 되풀이한다.
func (run *groupRun) backAnchor() (*playback.Link, int, error) {
	base := run.g.first - 1
	if run.g.anchor != nil {
		base = *run.g.anchor - 1
	}
	for base >= 0 {
		if run.u.restoreStopping() {
			return nil, 0, errRestoreStopped
		}
		low := max(base-restoreBackPage+1, 0)
		back, err := run.chainRows(low, base)
		if err != nil {
			return nil, 0, err
		}
		for i := len(back) - 1; i >= 0; i-- {
			l, start, found, err := run.backCandidate(back[i])
			if err != nil || found {
				return l, start, err
			}
		}
		base = low - 1
	}
	return nil, 0, run.startAt(0)
}

// backCandidate 는 뒤로 읽은 행 r 하나의 판정이다 — 시작점을 정했으면 found 다.
func (run *groupRun) backCandidate(r index.PlaybackChainRow) (*playback.Link, int, bool, error) {
	switch {
	case r.SessionID == "":
		return nil, 0, false, nil
	case r.SessionID != run.w.sessionID:
		return nil, 0, true, run.startAt(r.Seq + 1)
	case r.PlaybackUploadState != index.UploadStateUploaded || r.Timing.Recorder == nil:
		return nil, 0, false, nil // 장부 표식이 없으면 mtxi 없는 행이다 — 닻이 못 된다
	}
	l, err := run.objectAnchor(r)
	switch {
	case errors.Is(err, errAnchorPinned):
		delete(run.bodies, r.Seq) // 닻이 못 된 후보의 본문은 쥐지 않는다
		return nil, 0, false, nil
	case err != nil:
		return nil, 0, true, err
	}
	if err := run.fetchFrom(r.Seq); err != nil {
		return nil, 0, true, err
	}
	return l, run.rowIndex(r.Seq) + 1, true, nil
}

// startAt 은 seq 행부터 닻 없이 접는다(회차 첫 행 — startChain 과 같다). 접기 시작 자리는 0 이다.
func (run *groupRun) startAt(seq int64) error {
	run.exact, run.slackLo, run.slackHi = true, 0, 0 // 회차 첫 행부터 접으면 실시간과 같은 재료뿐이다
	return run.fetchFrom(seq)
}

// memoryAnchor 는 지난 복원 링크를 닻으로 쓸 수 있는가다.
func (run *groupRun) memoryAnchor() (*playback.Link, int, bool) {
	rl, ok := run.u.sessions.restoredLink(run.w.sessionID)
	floor := run.g.first - 1
	if run.g.anchor != nil {
		floor = *run.g.anchor
	}
	if !ok || rl.link.Seq < floor || rl.link.Seq > run.g.last {
		return nil, 0, false
	}
	i := run.rowIndex(rl.link.Seq)
	if i < 0 || run.rows[i].Bytes == nil || *run.rows[i].Bytes != rl.bytes {
		return nil, 0, false
	}
	run.exact, run.slackLo, run.slackHi = rl.exact, rl.slackLo, rl.slackHi
	l := rl.link
	return &l, i + 1, true
}

// preload 는 닻 행의 ② 를 읽어 둔다 — 닻 바로 뒤가 리셋이면 이음 끝 재료다.
func (run *groupRun) preload(l playback.Link) error {
	i := run.rowIndex(l.Seq)
	src, err := run.u.loadSource(run.w.streamID, run.rows[i])
	run.bodies[l.Seq] = src.body
	return err
}

// objectAnchor 는 ③ uploaded 닻 행의 링크를 그 ③ 객체와 ② 로 되짚는다(설계 r6 2.10 「닻 되살리기」). 도장은 객체의 트랙별
// 틱 차가 가리키는 범위에서 고른다 — 보정값 0 이나 같은 프로세스 표의 줄이 범위 안이면 그것이 실제 값이고(exact), 아니면 범위
// 가운데다. 그때 실제 값은 고른 값에서 [−slackLo, +slackHi] 안이고, 뒤 행 도장도 모두 같은 차만큼 어긋난다(구멍 뒤 대조의 틱 범위).
func (run *groupRun) objectAnchor(r index.PlaybackChainRow) (*playback.Link, error) {
	seq := r.Seq
	src, err := run.u.loadSource(run.w.streamID, r)
	if err != nil {
		return nil, err
	}
	if src.state != sourceRead {
		return nil, anchorErr(seq, "anchor_source_missing", errors.New("② 원천이 없다"))
	}
	run.bodies[seq] = src.body
	timing, err := playback.ReadTiming(bytes.NewReader(src.body))
	switch {
	case err != nil:
		return nil, anchorErr(seq, "anchor_unreadable", err)
	case timing.Mtxi == nil:
		return nil, anchorErr(seq, "anchor_pinned", errAnchorPinned)
	}
	win, err := run.objectWindow(r, src.body)
	if err != nil {
		return nil, err
	}
	dts := timing.Mtxi.DTS
	pos, exact := win.Pick(run.offsetHints(seq, dts)...)
	run.exact, run.slackLo, run.slackHi = exact, 0, 0
	if !exact {
		run.slackLo, run.slackHi = pos-win.Lo, win.Hi-pos
	}
	return &playback.Link{Seq: seq, SessionID: r.SessionID, Recorder: r.Timing.Recorder, Number: r.Timing.Number,
		VideoEnd: r.Timing.VideoEnd, Mtxi: dts, Dur: time.Duration(r.DurationMS) * time.Millisecond, Pos: pos,
		Offset: pos - dts}, nil
}

// offsetHints 는 닻 도장 후보다 — 보정값 0(리셋 · 구멍이 없던 회차)과, 이 프로세스의 표가 그 seq 를 알면 그 줄.
func (run *groupRun) offsetHints(seq int64, dts time.Duration) []time.Duration {
	hints := []time.Duration{dts}
	if off, ok := run.u.sessions.offsetAt(run.w.sessionID, seq); ok {
		hints = append([]time.Duration{dts + off}, hints...)
	}
	return hints
}

// objectWindow 는 행 r 의 ③ 객체가 ② body 의 어느 도장 범위인가다. 객체가 없거나 그 ② 의 재포장이 아니면 오류다.
func (run *groupRun) objectWindow(r index.PlaybackChainRow, body []byte) (playback.StampWindow, error) {
	obj, err := run.getObject(r.PlaybackS3Key)
	switch {
	case errors.Is(err, ErrObjectNotFound):
		return playback.StampWindow{}, anchorErr(r.Seq, "anchor_object_missing", err)
	case err != nil:
		return playback.StampWindow{}, err
	}
	win, err := playback.StampOf(body, obj)
	if err != nil {
		return playback.StampWindow{}, anchorErr(r.Seq, "anchor_mismatch", err)
	}
	return win, nil
}

// getObject 는 S3 GET 한 번이다 — 부재는 ErrObjectNotFound 그대로, 그 밖의 실패는 까닭 s3_get 이다.
func (run *groupRun) getObject(key string) ([]byte, error) {
	_, getter, _ := run.u.restoreDeps()
	ctx, cancel := run.u.s3Ctx()
	defer cancel()
	obj, err := getter.Get(ctx, key)
	if err != nil && !errors.Is(err, ErrObjectNotFound) {
		return nil, failRestore("s3_get", err)
	}
	return obj, err
}

// rule 은 리셋 이음 끝이다 — 실시간 chainEnd 와 같이 앞 행 ② 의 먼저 끝난 트랙 끝, 못 읽으면 장부 길이(결정 F).
func (run *groupRun) rule(prev playback.Link) time.Duration {
	body, ok := run.bodies[prev.Seq]
	if !ok {
		if i := run.rowIndex(prev.Seq); i >= 0 {
			src, err := run.u.loadSource(run.w.streamID, run.rows[i])
			if err != nil {
				run.ruleErr = err
			}
			body, run.bodies[prev.Seq] = src.body, src.body
		}
	}
	if body == nil {
		return prev.Pos + prev.Dur
	}
	ends, err := playback.TrackEnds(bytes.NewReader(body))
	if err != nil {
		return prev.Pos + prev.Dur
	}
	return prev.Pos + min(ends.Video, ends.Audio)
}

// errForeignSession 은 닻과 구멍 사이에 다른 회차 행이 끼었다는 뜻이다 — 회차는 장부에서 이어 붙어 있어야 한다.
var errForeignSession = errors.New("구멍 구간에 다른 회차 행이 있다")

// fold 는 rows[start:] 를 접는다 — 구멍 끝을 지나 대조 행에서 멈춘다. 꼬리에서 읽히지 않는 행이면 거기서 멈춘다(waited).
func (run *groupRun) fold(anchor *playback.Link, start int) (*mismatch, error) {
	f := playback.NewChainFold(anchor, run.rule, sameInstanceLink)
	check := run.checkSeq()
	for n, r := range run.rows[start:] {
		if r.Seq > run.g.last && check < 0 {
			return nil, nil
		}
		if err := run.progress(n, r.Seq); err != nil {
			return nil, err
		}
		switch r.SessionID {
		case "":
			f.Step(playback.SourceTiming{Seq: r.Seq})
			continue
		case run.w.sessionID:
		default:
			return nil, failRestore("foreign_session", fmt.Errorf("seq %d: %w", r.Seq, errForeignSession))
		}
		link, ok, err := run.step(f, r)
		if err != nil || run.waited {
			return nil, err
		}
		run.keepBodyOf(r.Seq)
		if r.Seq == check {
			return run.compare(r, link, ok)
		}
		if ok && r.Seq <= run.g.last {
			run.links = append(run.links, link)
		}
	}
	return nil, nil
}

// restoreProgressEvery 는 긴 접기의 진행 신호 간격(행 수)이다 — 닻 없는 긴 회차는 수천 행을 접어 정지와 구별이 안 된다(c10b
// 확인 패스 2회차 cc 부수 3). 4초 조각 1,000행 ≈ 67분 몫이다.
const restoreProgressEvery = 1000

// progress 는 접기 n 번째 행 앞에서 멈춤 신호(워커 정지 — Shutdown 4단계)를 보고, 긴 접기면 진행 신호를 남긴다. 로컬 ② 만 읽는 긴
// 접기는 S3 · DB 문맥 취소가 닿지 않아 행 사이에서 직접 봐야 Shutdown 이 유예 안에 끝난다(c10b 확인 패스 2회차 cx H).
func (run *groupRun) progress(n int, seq int64) error {
	if run.u.restoreStopping() {
		return errRestoreStopped
	}
	if n > 0 && n%restoreProgressEvery == 0 {
		run.lg.Info("chain_restore_progress", "seq", seq, "folded_rows", n, "hole_to", run.g.last)
	}
	return nil
}

// keepBodyOf 는 읽은 ② 본문 가운데 행 seq 의 것만 남긴다(c10b 보안 검수 H — 무리 끝까지 쥐면 행 수만큼 는다). 다음 행이 쓰는
// 것은 이음 끝 재료인 사슬 행(방금 접은 행)의 본문뿐이고, 그보다 앞 행이 필요하면(방금 행이 도장 없는 행) 이음 규칙이 다시 읽는다.
func (run *groupRun) keepBodyOf(seq int64) {
	body, ok := run.bodies[seq]
	clear(run.bodies)
	if ok {
		run.bodies[seq] = body
	}
}

// step 은 행 r 하나를 읽어 접는다 — 꼬리에서 읽히지 않으면 멈추고(waited), 아니면 지나간다. 구멍 행이면 이미 있는 객체를 검증한다.
func (run *groupRun) step(f *playback.ChainFold, r index.PlaybackChainRow) (playback.Link, bool, error) {
	src, err := run.u.loadSource(run.w.streamID, r)
	if err != nil {
		return playback.Link{}, false, err
	}
	run.bodies[r.Seq] = src.body
	st, unreadable := sourceTiming(r, src)
	if unreadable != "" {
		if run.isTail(r) {
			run.waited = true
			return playback.Link{}, false, nil
		}
		run.passed[r.Seq] = true
		run.lg.Warn("chain_row_passed", "seq", r.Seq, "reason", unreadable)
	}
	link, ok := f.Step(st)
	if run.ruleErr != nil || !ok || !run.g.seqs[r.Seq] || run.passed[r.Seq] || run.isTail(r) {
		return link, ok, run.ruleErr
	}
	adopted, err := run.verifyExisting(r, link)
	if err != nil {
		return playback.Link{}, false, err
	}
	f.Replace(adopted)
	return adopted, true, nil
}

// compare 는 구멍 뒤 uploaded 행 n 의 객체 도장을 접은 도장과 틱 단위로 견준다 — 접은 도장이 닻 불확실 범위([−slackLo, +slackHi],
// 실제 값이면 0)만큼 움직여 객체의 틱 범위에 닿을 수 있어야 같은 사슬이다(c10b cx 검수 M — 범위 폭을 거리 허용치로 쓰면 10µs
// 바뀐 객체가 통과했다). 판단 재료가 없으면(도장 없음 · 원천 · 객체 없음) 견주지 않는다.
func (run *groupRun) compare(r index.PlaybackChainRow, link playback.Link, ok bool) (*mismatch, error) {
	body := run.bodies[r.Seq]
	if !ok || body == nil || run.passed[r.Seq] {
		return nil, nil
	}
	obj, err := run.getObject(r.PlaybackS3Key)
	switch {
	case errors.Is(err, ErrObjectNotFound):
		return nil, nil
	case err != nil:
		return nil, err
	}
	win, err := playback.StampOf(body, obj)
	if err != nil {
		return &mismatch{seq: r.Seq, expected: link.Pos}, nil
	}
	if link.Pos+run.slackHi < win.Lo || link.Pos-run.slackLo > win.Hi {
		return &mismatch{seq: r.Seq, expected: link.Pos, window: win}, nil
	}
	return nil, nil
}
