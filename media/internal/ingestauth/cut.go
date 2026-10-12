package ingestauth

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxctl"
)

// UnmappedMinAge 는 Cut 이 기록 밖 판독 중 연결을 토큰과 무관하게 끊는 최소 나이다(3-4절).
// 기록 밖이면서 이보다 어린 판독 중 연결이 있으면 Cut 은 그것을 끊지 않고 미완으로 알린다.
const UnmappedMinAge = 30 * time.Second

// Cut 이 kick 한 id 를 조회로 확인하는 상한과 조회 간격이다(3-4절 2.7 — 최대 2초). kick 은 목록에서 곧바로 지우므로
// (srt/server.go 239행) 대개 첫 조회에서 끝난다. 간격은 그렇지 않은 드문 경우의 조회 수를 묶는다.
const (
	cutVerifyWithin = 2 * time.Second
	cutVerifyEvery  = 100 * time.Millisecond
)

// 목록 항목의 state 가운데 절단이 가르는 값이다(포크 defs/api_srt.go 21–23행).
const (
	connStateIdle    = "idle"
	connStatePublish = "publish"
)

// CutReport 는 Cut 한 번이 한 일의 셈이다 — cut 출력 한 줄(설계 6절 ingestauth_cut_completed ·
// ingestauth_cut_incomplete)의 재료다.
type CutReport struct {
	FromLog, FromList, FromUnmapped int           // id 출처별 개수(앞 출처에서 센 id 는 뒤에서 세지 않는다)
	Kicked, AlreadyGone             int           // kick 이 200 · 404(이미 없음)로 끝난 id 수
	Remaining                       []string      // 확인 뒤에도 남은 id
	UnmappedYoung                   int           // 기록 밖이면서 UnmappedMinAge 가 안 된 판독 중 연결 수. 0 이 아니면 미완이다
	Covered                         time.Duration // 목록을 받은 시각 − Allowed.Since(출력용 — 첫 판독의 값)
}

// Cut 이 끝까지 끊지 못한 까닭이다. cut 하위 명령이 종료 코드와 사유(설계 6절 ingestauth_cut_incomplete)를 이것으로
// 가른다. Control API 실패는 *mtxctl.Error 로 온다.
var (
	// ErrNotDenied 는 token 이 거부 목록에 없다는 것이다. 아무것도 끊지 않았다(종료 코드 2 — not_denied).
	ErrNotDenied = errors.New("ingestauth: 거부 목록에 없는 토큰은 끊지 않는다")

	// ErrDenyListUnreadable 은 거부 목록을 읽지 못했다는 것이다. 아무것도 끊지 않았다(denylist_unreadable).
	ErrDenyListUnreadable = errors.New("ingestauth: 거부 목록을 읽지 못해 끊지 않았다")

	// ErrConnLogUnreadable 은 허용 연결 기록을 읽지 못했거나 줄이 하나도 없다는 것이다(시작 표시도 없음). 그 판독은
	// 목록 갈래만 끊었다(connlog_unreadable).
	ErrConnLogUnreadable = errors.New("ingestauth: 허용 연결 기록을 읽지 못해 목록 갈래만 끊었다")

	// ErrConnLogWindowShort 는 기록 밖이면서 UnmappedMinAge 가 안 된 판독 중 연결이 있다는 것이다. 그 연결은 끊지
	// 않았다 — 30초 뒤 다시 돌리면 닫힌다(connlog_window_short).
	ErrConnLogWindowShort = errors.New("ingestauth: 기록 밖이면서 아직 어린 판독 중 연결이 있다")

	// ErrVerifyTimeout 은 kick 한 연결이 확인 시한 안에 목록에서 사라지지 않았다는 것이다(verify_timeout).
	ErrVerifyTimeout = errors.New("ingestauth: kick 한 연결이 확인 시한 안에 사라지지 않았다")
)

// Cut 은 token 의 연결과 기록 밖 판독 중 연결을 id 로 끊고 id 로 확인한다(3-4절).
// 순서는 거부 목록 확인 → 기록 판독(LoadAllowed) → 연결 목록 조회이고, 이 순서가 3-4 증명의 전제다.
// 기록 밖 갈래는 목록의 created 를 Allowed.Since 와 절대 시각으로 견주고, 나이는 목록을 받은
// 시각으로 잰다. 그래서 두 조회 사이의 틈은 판정에 끼지 않는다.
//
// 판독은 두 번이다(3-4절 2.8) — 둘째 판독은 첫 판독 뒤 기록된 줄의 새 id 만 끊는다. kick 한 id 는 조회로 사라졌는지
// 최대 2초 확인한다. 시한은 ctx 다 — MediaMTX 가 답하지 않으면 ctx 가 끝날 때까지 기다린다.
//
// 에러가 nil 이면 끝까지 끊었다 — 두 판독 모두 기록 밖 미완 0 · 남은 id 0 · 실패 0 이다(cut 종료 코드 0). token 이 거부
// 목록에 없으면 ErrNotDenied, 거부 목록을 읽지 못하면 ErrDenyListUnreadable 이고 둘 다 아무것도 끊지 않는다. 그 밖의
// 까닭은 끊을 것을 끝까지 끊은 뒤 errors.Join 으로 묶어 돌려준다 — ErrConnLogUnreadable · ErrConnLogWindowShort ·
// ErrVerifyTimeout · Control API 실패(*mtxctl.Error — 목록을 받지 못하면 그 자리에서 멈춘다) · ctx 의 에러. 에러여도
// CutReport 는 그때까지 한 일을 담는다.
func Cut(ctx context.Context, token string, deny *DenyList, logDir string, mtx *mtxctl.Client) (CutReport, error) {
	c := cutter{now: time.Now, verifyWithin: cutVerifyWithin, verifyEvery: cutVerifyEvery}
	return c.cut(ctx, token, deny, logDir, mtx)
}

// cutter 는 Cut 의 본문이다. 시험이 시계 · 확인 시한 · 판독 사이 고리를 바꾼다.
type cutter struct {
	now          func() time.Time // 목록을 받은 시각(T_l)을 찍는다
	verifyWithin time.Duration    // 확인의 상한
	verifyEvery  time.Duration    // 확인 조회 사이의 간격
	between      func()           // 기록 판독의 두 세대 읽기 사이에 부른다(loadAllowed). nil 이면 아무것도 하지 않는다
}

// cut 은 Cut 의 차례다 — (T_0) 거부 목록 확인 → 판독 두 번(3-4절 2.1–2.8).
func (c cutter) cut(ctx context.Context, token string, deny *DenyList, logDir string, mtx *mtxctl.Client) (CutReport, error) {
	// (T_0) 거부 목록을 지금 다시 읽어 토큰이 있는지 본다 — 「쓰기 → cut」 차례의 확인이다. 기록 판독보다 먼저다.
	listed, err := deny.Contains(token)
	if err != nil {
		return CutReport{}, fmt.Errorf("%w: %v", ErrDenyListUnreadable, err)
	}
	if !listed {
		return CutReport{}, ErrNotDenied
	}
	run := &cutRun{token: token, seen: make(map[string]bool), young: make(map[string]bool)}
	for pass := range 2 {
		if err := c.runPass(ctx, run, logDir, mtx, pass == 0); err != nil {
			run.fail(err)
			break
		}
	}
	return run.report, run.err()
}

// runPass 는 판독 한 번을 돈다 — 기록 판독(T_c) → 목록(T_l) → 세 갈래 → kick → 확인(3-4절 2.2–2.7). 목록을 받지 못하면 그
// 에러를 돌려준다 — 목록 없이는 갈래를 가를 수 없어 거기서 멈춘다. 그 밖의 실패는 run 에 모으고 끝까지 간다.
func (c cutter) runPass(ctx context.Context, run *cutRun, logDir string, mtx *mtxctl.Client, first bool) error {
	s, err := c.survey(ctx, run.token, logDir, mtx)
	if err != nil {
		return err
	}
	pending := run.kick(ctx, mtx, run.collect(s, first))
	left, err := c.verify(ctx, mtx, pending)
	run.report.Remaining = append(run.report.Remaining, left...)
	run.fail(err)
	return nil
}

// surveyResult 는 판독 한 번이 가른 id 다.
type surveyResult struct {
	fromLog, fromList, fromUnmapped []string      // 갈래별 id(앞 갈래에 든 id 는 뒤 갈래에 넣지 않는다)
	young                           []string      // 기록 밖이면서 UnmappedMinAge 가 안 된 판독 중 연결
	covered                         time.Duration // 목록을 받은 시각 − Since
	logErr                          error         // 기록을 읽지 못했다 — 목록 갈래만 갈랐다
}

// survey 는 기록을 읽고(T_c) 목록을 한 번에 받아(T_l) 세 갈래로 가른다(3-4절 2.2–2.4). 기록을 읽지 못하면 목록 갈래만
// 가르고 logErr 에 까닭을 둔다. 목록을 받지 못하면 에러다.
func (c cutter) survey(ctx context.Context, token, logDir string, mtx *mtxctl.Client) (surveyResult, error) {
	between := c.between
	if between == nil {
		between = func() {}
	}
	allowed, logErr := loadAllowed(logDir, between)
	conns, err := mtx.SRTConns(ctx)
	if err != nil {
		return surveyResult{}, err
	}
	listedAt := c.now()
	if logErr != nil {
		return surveyResult{fromList: publishers(token, conns, nil), logErr: logErr}, nil
	}
	return classify(token, allowed, conns, listedAt), nil
}

// classify 는 목록을 받은 시각 listedAt 의 연결을 세 갈래로 가른다(3-4절 2.4).
//   - 기록 갈래: 기록에서 token 의 id — 목록에 없어도 넣는다(kick 의 404 는 이미 없는 것이다).
//   - 목록 갈래: path == token ∧ state == publish.
//   - 기록 밖 갈래: state == idle ∧ path 빈 값 ∧ 어느 토큰으로도 기록에 없음 ∧ created 가 Since 보다 앞 ∧ 나이
//     (listedAt − created)가 UnmappedMinAge 를 넘음. 넘지 않으면 young 이다(미완).
func classify(token string, allowed Allowed, conns []mtxctl.SRTConn, listedAt time.Time) surveyResult {
	s := surveyResult{covered: listedAt.Sub(allowed.Since)}
	for id, t := range allowed.Tokens {
		if t == token {
			s.fromLog = append(s.fromLog, id)
		}
	}
	slices.Sort(s.fromLog)
	s.fromList = publishers(token, conns, allowed.Tokens)
	for _, conn := range conns {
		if conn.State != connStateIdle || conn.Path != "" {
			continue
		}
		if _, logged := allowed.Tokens[conn.ID]; logged || !conn.Created.Before(allowed.Since) {
			continue
		}
		if listedAt.Sub(conn.Created) > UnmappedMinAge {
			s.fromUnmapped = append(s.fromUnmapped, conn.ID)
		} else {
			s.young = append(s.young, conn.ID)
		}
	}
	return s
}

// publishers 는 목록에서 token 경로에 등록된 송출 연결(path == token ∧ state == publish)의 id 다. 기록에 token 으로 있는
// id(logged — 기록 갈래가 먼저 셌다)는 뺀다.
func publishers(token string, conns []mtxctl.SRTConn, logged map[string]string) []string {
	var ids []string
	for _, conn := range conns {
		if conn.Path == token && conn.State == connStatePublish && logged[conn.ID] != token {
			ids = append(ids, conn.ID)
		}
	}
	return ids
}

// verify 는 pending 의 id 마다 조회해 목록에서 사라졌는지(404) 확인한다(3-4절 2.7 — 최대 verifyWithin). 모두 사라지면
// nil, nil 이다. 시한 안에 사라지지 않은 id 와 ErrVerifyTimeout, 또는 조회가 실패하면 아직 확인하지 못한 id 와 그
// 에러를 돌려준다 — 조회 실패를 사라진 것으로 세지 않는다.
func (c cutter) verify(ctx context.Context, mtx *mtxctl.Client, pending []string) ([]string, error) {
	if len(pending) == 0 {
		return nil, nil
	}
	vctx, cancel := context.WithTimeout(ctx, c.verifyWithin)
	defer cancel()
	tick := time.NewTicker(c.verifyEvery)
	defer tick.Stop()
	for {
		left, err := stillListed(vctx, mtx, pending)
		switch {
		case err != nil:
			return left, verifyFailure(ctx, vctx, err)
		case len(left) == 0:
			return nil, nil
		}
		pending = left
		select {
		case <-vctx.Done():
			return pending, verifyFailure(ctx, vctx, nil)
		case <-tick.C:
		}
	}
}

// stillListed 는 ids 가운데 아직 목록에 있는 id 다. 조회가 실패하면 그 id 와 아직 조회하지 않은 id 를 남긴 채 그
// 에러를 돌려준다.
func stillListed(ctx context.Context, mtx *mtxctl.Client, ids []string) ([]string, error) {
	var left []string
	for i, id := range ids {
		exists, err := mtx.SRTConnExists(ctx, id)
		if err != nil {
			return append(left, ids[i:]...), err
		}
		if exists {
			left = append(left, id)
		}
	}
	return left, nil
}

// verifyFailure 는 확인이 끝난 까닭이다 — 부르는 쪽 ctx 가 끝났으면 그 에러, 확인 시한이 지났으면 ErrVerifyTimeout,
// 그 밖에는 조회의 에러 err 다.
func verifyFailure(ctx, vctx context.Context, err error) error {
	switch {
	case ctx.Err() != nil:
		return ctx.Err()
	case vctx.Err() != nil:
		return ErrVerifyTimeout
	}
	return err
}

// cutRun 은 Cut 한 번의 진행 상태다.
type cutRun struct {
	token  string
	report CutReport
	seen   map[string]bool // 두 판독에서 이미 모은 id — 둘째 판독은 새 id 만 세고 kick 한다
	young  map[string]bool // 기록 밖 미완 id(두 판독의 합집합)
	cutFailures
}

// collect 는 판독 s 의 새 id 를 갈래별로 세고 kick 할 차례(기록 → 목록 → 기록 밖)로 돌려준다. 앞 판독에서 모은 id 는
// 다시 세지도 돌려주지도 않는다. 기록 읽기 실패와 기록 밖 미완은 까닭으로 남긴다. Covered 는 첫 판독의 값이다.
func (r *cutRun) collect(s surveyResult, first bool) []string {
	if first {
		r.report.Covered = s.covered
	}
	if s.logErr != nil {
		r.fail(fmt.Errorf("%w: %v", ErrConnLogUnreadable, s.logErr))
	}
	for _, id := range s.young {
		r.young[id] = true
	}
	r.report.UnmappedYoung = len(r.young)
	if len(s.young) > 0 {
		r.fail(ErrConnLogWindowShort)
	}
	var ids []string
	for _, branch := range []struct {
		ids   []string
		count *int
	}{
		{s.fromLog, &r.report.FromLog},
		{s.fromList, &r.report.FromList},
		{s.fromUnmapped, &r.report.FromUnmapped},
	} {
		for _, id := range branch.ids {
			if r.seen[id] {
				continue
			}
			r.seen[id] = true
			*branch.count++
			ids = append(ids, id)
		}
	}
	return ids
}

// kick 은 ids 를 차례로 kick 한다(3-4절 2.6). 404 는 이미 없는 것이다. 그 밖의 실패는 까닭으로 남기고 다음 id 로 간다
// — 끊을 것은 끝까지 끊는다. 확인할 id(200 이었거나 실패한 것)를 돌려준다.
func (r *cutRun) kick(ctx context.Context, mtx *mtxctl.Client, ids []string) (pending []string) {
	for _, id := range ids {
		err := mtx.KickSRTConn(ctx, id)
		switch {
		case err == nil:
			r.report.Kicked++
		case errors.Is(err, mtxctl.ErrNotFound):
			r.report.AlreadyGone++
			continue
		default:
			r.fail(err)
		}
		pending = append(pending, id)
	}
	return pending
}

// cutFailures 는 미완의 까닭이다. 갈래마다 처음 것 하나만 둔다 — 같은 까닭이 id 마다 되풀이되지 않게.
type cutFailures struct {
	connLog, windowShort, verify, other error
}

// fail 은 err 를 그 갈래의 첫 까닭으로 남긴다. nil 이면 아무것도 하지 않는다.
func (f *cutFailures) fail(err error) {
	slot := &f.other
	switch {
	case err == nil:
		return
	case errors.Is(err, ErrConnLogUnreadable):
		slot = &f.connLog
	case errors.Is(err, ErrConnLogWindowShort):
		slot = &f.windowShort
	case errors.Is(err, ErrVerifyTimeout):
		slot = &f.verify
	}
	if *slot == nil {
		*slot = err
	}
}

// err 는 모인 까닭을 하나로 묶는다. 까닭이 없으면 nil 이다 — 끝까지 끊었다.
func (f *cutFailures) err() error {
	return errors.Join(f.other, f.connLog, f.windowShort, f.verify)
}
