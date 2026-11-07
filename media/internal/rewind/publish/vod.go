package publish

// VOD 동결 — M6 설계 2.1 FreezeVOD · 3.1 phase 3 · 비귀속 행(POK-196 M6-2 커밋 ③).
//
// 회차 하나에 VOD 문서 하나다(POK-549 가 M6 K1 「사슬 하나에 문서 하나」를 회차로 넓혔다 — vod_round.go). 사슬이 하나면
// 그 사슬 범위 · 머리 세션 키이고, 사슬이 여럿이면 마지막 사슬의 동결이 회차 범위 · 회차 키로 쓴다. 새 URL 의 새 문서라
// 기존 목록의 변경이 아니고, 한 번만 쓴다(IfAbsent · RFC 8216 4.3.3.5 VOD 불변). 412 면 Head 의 pc-body-sha256 이 다시
// 렌더한 본문과 같을 때만 그 판을 채택한다(D7 — 쓰고 확정 전에 멈춘 시도의 판). 다시 렌더가 같은 본문을 내도록, 렌더 전에 GAP 으로 실을 행을 원장에 먼저 적는다
// (포기 등재는 추가만 — D17 4.5.4 재현 가능성). 그 뒤 ③ 이 늦게 닿아도 원장 우선이라 같은 GAP 줄이다. 그래서 정산 창
// 안에서는 ③ 미착지 행을 GAP 원장 유무와 무관하게 기다린다 — 창 안에 닿을 조각을 VOD 에 영구 GAP 으로 굳히지 않는다.
//
// fence 를 쓰지 않는다. 쓰기는 새 키의 IfAbsent 하나와 vod_state='freezing' 조건의 확정 CAS 뿐이라 두 writer 가 겹쳐도
// 한 판만 남고 장부는 그 판으로만 확정된다. 다만 같은 본문을 내려면 두 writer 가 같은 장부를 봐야 한다 — 그래서 GAP 등재
// 트랜잭션이 사슬 꼬리 행을 잠그고 같은 트랜잭션에서 장부를 다시 읽어 그 값으로 plan · 렌더한다(잠금 → 재독 → 등재 →
// 커밋 → PUT · kty ⑧).
// 옛 스냅숏의 writer 는 앞 writer 의 커밋 뒤 장부를 읽으므로, 앞 writer 가 GAP 없이 쓴 판에 GAP 을 등재하지 않고 앞
// writer 가 적은 GAP 원장(원장 우선)을 그대로 싣는다(cx r1 H1). 잠금은 꼬리 행 하나다 — 설계 3.3 순서의 ② 자리이고
// 위상 잠금(①)은 잡지 않는다.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"slices"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// VOD 재시도 · 실패 사유 — FreezeOutcome.Reason 값이다(DVR 과 같은 이름은 freeze.go 의 것을 쓴다).
const (
	vodUnsettled    = "unsettled"       // 정산 전(③ 미착지 행) — 포기 없이는 쓰지 않는다
	vodForeign      = "foreign_session" // 사슬 범위 안에 사슬 밖 회차의 행(설계 3.1 — 구조상 없어야 함)
	vodChainChanged = "chain_changed"   // 부른 쪽 사슬이 장부의 사슬과 다르다
	vodTooLarge     = "too_large"       // 본문이 MaxVODBodyBytes 를 넘는다(설계 3.1 크기 · 한계)
	vodConflict     = "vod_conflict"    // 같은 키에 다른 본문이 있다
	vodLedgerWrite  = "gap_ledger"      // 렌더 전 GAP 원장 쓰기 실패
	vodContended    = "contended"       // 꼬리 행 잠금이 lock_timeout(5초)에 걸렸다(55P03)
)

// ReasonUnsettled 는 VOD 동결의 정산 대기 재시도 사유다(FreezeOutcome.Reason) — 정산 창 안의 ③ 미착지라 장애가 아니다.
// 판정부(M6-3b)가 이 사유를 장애 재시도와 갈라 센다.
const ReasonUnsettled = vodUnsettled

// vodMinRowBytes 는 VOD 본문에서 조각 하나가 차지하는 바이트의 하한이다 — PDT 줄(태그 25 · UTC 밀리초 시각 24 · 줄바꿈
// 1) 50 · EXTINF 줄(가장 짧은 「#EXTINF:0.001,」 · 줄바꿈) 15 · URI 줄(BaseURL 한 글자 이상 · 「/」 · 키 한 글자 이상 ·
// 줄바꿈) 4 다. DISCONTINUITY · MAP · GAP 줄은 더하기만 한다.
const vodMinRowBytes = 69

// vodMaxRows 는 렌더 전 적재 상한이다(보안 sec-m61-c4 M1) — 이보다 많은 행은 렌더해도 반드시 MaxVODBodyBytes 를 넘으므로
// 렌더 · 등재 전에 too_large 로 끝낸다. 적재(index.LoadChainLedger · ReadChainLedger)도 이 상한까지만 행을 싣는다.
const vodMaxRows = rewind.MaxVODBodyBytes / vodMinRowBytes

// vodTxnLimitsSQL 은 등재 트랜잭션의 지역 상한이다(index.setTxnLimitsSQL 과 같은 값) — lock_timeout 5초가 꼬리 잠금
// 대기 하나의 상한이고 넘기면 55P03 → 재시도(contended)다.
const vodTxnLimitsSQL = `
SELECT pg_catalog.set_config('lock_timeout','5s',true), pg_catalog.set_config('statement_timeout','10s',true)`

// vodLockTailSQL 은 사슬 꼬리 행 잠금이다(설계 3.3 순서의 ② — CloseChain 이 잡는 행과 같다). 직렬화만 하면 되므로
// FOR NO KEY UPDATE 다 — 그 회차의 늦은 조각 INSERT 가 FK 검사로 거는 FOR KEY SHARE 를 막지 않는다(D71).
const vodLockTailSQL = `SELECT 1 FROM stream_sessions WHERE session_id = $1 AND stream_id = $2 FOR NO KEY UPDATE`

// vodMarkGapSQL 은 렌더 전 GAP 원장 적기다 — 행 하나를 GAP 으로 넣는다(이미 있으면 그대로 — 원장 우선). $4 사유는
// mixed_boundary(비귀속 행 · 회차는 앞 사슬 세션) · settle_deadline(정산 창 만료 미착지 행)이고 발행 상태는
// vod_abandoned 다(설계 3.1 phase 3).
const vodMarkGapSQL = `
INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason, published_state)
VALUES ($1, $2, $3, $4, 'vod_abandoned')
ON CONFLICT (stream_id, seq) DO NOTHING`

// vodAbandonGapsSQL 은 사슬 범위 [$2, $3] 의 GAP 원장 행 가운데 조각이 지금도 미착지인 것을 vod_abandoned 로 바꾼다 —
// VOD 가 그 조각을 버렸다(설계 3.1 phase 3 「DVR GAP 행 중 지금도 미착지」).
const vodAbandonGapsSQL = `
UPDATE stream_published_gaps g
   SET published_state = 'vod_abandoned'
  FROM stream_segments s
 WHERE g.stream_id = $1 AND g.seq BETWEEN $2 AND $3 AND g.published_state <> 'vod_abandoned'
   AND s.stream_id = g.stream_id AND s.seq = g.seq AND s.playback_upload_state <> 'uploaded'`

// vodLockChainSQL 은 확정 전 사슬 행 잠금이다 — 회차 잠금(session lockRoundRowsSQL)과 같은 순서(started_at, session_id)
// · 같은 세기(FOR NO KEY UPDATE)로 잡는다. 확정 UPDATE 가 제 방문 순서(키 · 물리 순)로 여러 행을 잠그면 회차 잠금과
// 서로 기다려 교착(40P01)이 된다(db L2). 40P01 은 그래도 재시도(freezeDB)다.
const vodLockChainSQL = `
SELECT 1 FROM stream_sessions
 WHERE stream_id = $1 AND session_id = ANY($2)
 ORDER BY started_at, session_id
   FOR NO KEY UPDATE`

// vodConfirmSQL 은 VOD 확정이다 — 사슬 세션 $2 가운데 아직 freezing 인 행에 VOD 열을 쓴다. 바꾼 행 수가 사슬 수와 다르면
// 부르는 쪽이 되돌린다(트랜잭션). 행은 vodLockChainSQL 이 먼저 잠근다.
const vodConfirmSQL = `
WITH upd AS (
    UPDATE stream_sessions
       SET vod_state = $3, vod_s3_key = $4, vod_etag = $5, vod_sha256 = $6, vod_first_seq = $7, vod_last_seq = $8
     WHERE stream_id = $1 AND session_id = ANY($2) AND vod_state = 'freezing'
    RETURNING 1
)
SELECT pg_catalog.count(*) FROM upd`

// vodStateSQL 은 확정이 0행일 때 꼬리의 VOD 상태를 읽는다 — 다른 writer 가 먼저 확정했는가.
const vodStateSQL = `SELECT vod_state FROM stream_sessions WHERE session_id = $1 AND stream_id = $2`

// FreezeVOD 는 닫힌 사슬의 VOD 문서를 IfAbsent 로 한 번 쓰고 사슬 전 세션의 vod_* 를 확정한다(phase 3).
// 새 URL 의 새 문서라 기존 목록의 변경이 아니다. 412 면 Head 의 pc-body-sha256 이
// 재렌더 값과 같을 때만 채택한다.
//
// chain 의 StreamID · RoundID · Tail · SessionIDs 를 쓴다(SessionIDs 는 사슬을 닫은 집합 — 장부와 다르면 실패). abandon 은
// 정산 창이 끝났는가다 — 참이면 미착지 행을 GAP 줄로 싣고(settle_deadline) 거짓이면 미착지 행이 있을 때 재시도다.
//
// 범위는 판정(decideScope)이 정한다 — 사슬 범위(사슬 세션 행의 첫 seq 부터 끝 seq · 지금 키) 또는 회차 범위(회차 세션 행의
// 첫 seq 부터 끝 seq · 회차 키 · 앞 사슬 행은 정산을 끝낸 것으로 본다). 둘 다 컷오프 아래는 빼고 KindVOD · Ended 다. 회차
// 대기면 Retry + 대기 사유(round_open · front_pending)다. 회차 경로가 결정적으로 실패하면 같은 호출에서 사슬 범위로
// 물러서고, 이때 Result · Retry · Reason · Err 는 그 사슬 호출의 것이며 회차 쪽 원인은 FallbackCause · FallbackErr 에
// 싣는다. 행이 없으면 빈 동결이다. GAP 줄이 하나라도 있으면 frozen_with_gaps 다.
func (p *Publisher) FreezeVOD(ctx context.Context, chain index.Chain, abandon bool) FreezeOutcome {
	if len(chain.SessionIDs) == 0 {
		return vodFailed(vodChainChanged, errEmptyChain)
	}
	if chain.RoundID == "" {
		return p.freezeChain(ctx, chain, abandon, "") // M6 전 세션 — 판정 재료를 읽지 않는다
	}
	g, err := index.LoadRoundGate(ctx, p.pool, chain.StreamID, chain.RoundID)
	if err != nil {
		return vodRetry(freezeLedger, err)
	}
	return p.freezeScoped(ctx, chain, abandon, decideScope(chain, g))
}

// errEmptyChain 은 부른 쪽 사슬의 세션 목록이 비었다는 뜻이다.
var errEmptyChain = errors.New("publish: 사슬 세션 목록이 비었다")

// vodJob 은 VOD 동결 한 번의 문맥이다. round 가 참이면 회차 범위(회차 장부 · 회차 키)다.
type vodJob struct {
	p     *Publisher
	chain index.Chain
	key   string
	round bool
}

// vodInput 은 VOD 렌더 입력이다 — 사슬 장부(사슬 경로)나 회차 장부(회차 경로)에서 온 같은 칸이다.
type vodInput struct {
	// chain 은 다시 읽은 사슬(회차 경로는 마지막 사슬)의 세션이다.
	chain     []string
	cutoffSeq int64
	hasCutoff bool
	rows      []index.RewindRow
	sessions  []index.RewindSession
	// front 는 회차 경로의 앞 회원(마지막 사슬 밖 회차 세션)이다 — 사슬 경로는 비어 있다.
	front map[string]bool
}

// chainInput 은 사슬 장부 l 의 렌더 입력이다.
func chainInput(l index.ChainLedger) vodInput {
	return vodInput{chain: l.Chain.SessionIDs, cutoffSeq: l.CutoffSeq, hasCutoff: l.HasCutoff, rows: l.Rows, sessions: l.Sessions}
}

// freeze 는 동결 한 번이다 — 잠금 밖 첫 읽기 · 꼬리 잠금 아래 재독과 원장 적기 · 렌더와 PUT · 확정.
func (v *vodJob) freeze(ctx context.Context, abandon bool) FreezeOutcome {
	// 잠금 밖 첫 읽기 — 재시도 · 실패 · 빈 동결은 꼬리를 잠그지 않고 가른다(정산 창 동안 스캔이 되풀이해 부른다).
	_, plan, out, ok := v.load(ctx, abandon)
	if !ok {
		return out
	}
	if len(plan.rows) == 0 {
		return v.confirm(ctx, ResultEmpty, nil, "", nil)
	}
	in, plan, out, ok := v.markGaps(ctx, abandon)
	switch {
	case !ok:
		return out
	case len(plan.rows) == 0:
		return v.confirm(ctx, ResultEmpty, nil, "", nil)
	}
	return v.write(ctx, in, plan)
}

// vodPlan 은 장부에서 고른 VOD 렌더 행과 렌더 전에 원장에 적을 GAP 이다.
type vodPlan struct {
	rows []boundary.Row
	// marks 는 렌더 전에 원장에 넣을 GAP 행이다(seq · 회차 · 사유).
	marks []vodMark
	// abandon 은 정산 창이 끝났는가다 — 참일 때만 [abandonFirst, abandonLast] 의 미착지 GAP 원장 행을 vod_abandoned 로
	// 바꾼다.
	abandon bool
	// abandonFirst · abandonLast 는 마지막 사슬 회원 행의 첫 · 끝 seq 다(사슬 경로는 rows 의 첫 · 끝과 같다). lastChain 이
	// 거짓이면 마지막 사슬 행이 없어 바꿀 범위도 없다(K28).
	lastChain                 bool
	abandonFirst, abandonLast int64
}

type vodMark struct {
	seq     int64
	session string
	reason  string
}

// plan 은 사슬 장부 l 의 행을 VOD 렌더 행으로 바꾼다(planInput).
func (v *vodJob) plan(l index.ChainLedger, abandon bool) (vodPlan, FreezeOutcome, bool) {
	return v.planInput(chainInput(l), abandon)
}

// planInput 은 입력 in 의 행을 VOD 렌더 행으로 바꾼다(설계 3.1 비귀속 행 · D57 · POK-549 설계 2.2). 회원은 사슬 세션과
// 앞 회원(회차 경로)이고, 앞 회원 행은 포기 참으로 본다 — 앞 사슬은 정산을 끝냈다(R3).
//
//	회원 행 · ③ 미착지 · 포기 아님   재시도(정산 전 — DVR GAP 원장이 있어도 · 설계 3.1 phase 2)
//	회원 행 · settled                그대로(포기면 DVR GAP 원장 행도 여기 — markGaps 가 vod_abandoned 로 바꾼다)
//	회원 행 · 미착지 · 포기           GAP(settle_deadline)
//	비귀속 행(세션 NULL)         GAP(mixed_boundary) — 회차는 앞 행 세션 · PDT 는 앞 행 PDT + 길이 · URI 는 ③ 키 이름
//	회원 밖 회차 행              실패(foreign_session)
//
// 실을 행이 vodMaxRows 를 넘으면 렌더 전에 too_large 실패다.
func (v *vodJob) planInput(in vodInput, abandon bool) (vodPlan, FreezeOutcome, bool) {
	plan := vodPlan{abandon: abandon}
	for _, lr := range in.rows {
		if in.hasCutoff && lr.Seq < in.cutoffSeq {
			continue
		}
		r := boundaryRow(lr)
		chain, settled := slices.Contains(in.chain, r.SessionID), abandon || in.front[r.SessionID]
		switch {
		case r.SessionID != "" && !chain && !in.front[r.SessionID]:
			return vodPlan{}, vodFailed(vodForeign, fmt.Errorf("publish: seq %d 는 사슬 밖 회차 %q 의 행이다", r.Seq, r.SessionID)), false
		case r.SessionID == "":
			if len(plan.rows) == 0 {
				return vodPlan{}, vodFailed(vodForeign, fmt.Errorf("publish: 사슬 범위 첫 행 seq %d 가 비귀속이다", r.Seq)), false
			}
			r = v.fillGap(r, plan.rows[len(plan.rows)-1])
			plan.marks = append(plan.marks, vodMark{seq: r.Seq, session: r.SessionID, reason: "mixed_boundary"})
		case !settled && !r.PlaybackUploaded:
			return vodPlan{}, vodRetry(vodUnsettled, nil), false
		case !boundary.Settled(r, in.cutoffSeq):
			if !settled {
				return vodPlan{}, vodRetry(vodUnsettled, nil), false
			}
			r = v.fillGap(r, r)
			plan.marks = append(plan.marks, vodMark{seq: r.Seq, session: r.SessionID, reason: "settle_deadline"})
		}
		if chain {
			plan = plan.withLastChainRow(r.Seq)
		}
		plan.rows = append(plan.rows, r)
	}
	if len(plan.rows) > vodMaxRows {
		return vodPlan{}, vodFailed(vodTooLarge, fmt.Errorf("publish: VOD 행 %d 개가 적재 상한 %d 개를 넘는다", len(plan.rows), vodMaxRows)), false
	}
	return plan, FreezeOutcome{}, true
}

// withLastChainRow 는 마지막 사슬 회원 행 seq 를 abandon 갱신 범위에 넣은 plan 이다(행은 seq 오름차순으로 온다).
func (p vodPlan) withLastChainRow(seq int64) vodPlan {
	if !p.lastChain {
		p.lastChain, p.abandonFirst = true, seq
	}
	p.abandonLast = seq
	return p
}

// fillGap 은 행 r 을 GAP 줄로 실을 값으로 채운다 — 회차가 없으면 앞 행 prev 의 회차, PDT 가 없으면 앞 행 PDT + 길이, ③ 키가
// 없으면 키 이름(gapKey — 객체는 없어도 된다 · GAP 줄은 받지 않는다)이다.
func (v *vodJob) fillGap(r, prev boundary.Row) boundary.Row {
	if r.SessionID == "" {
		r.SessionID = prev.SessionID
	}
	if r.PlaybackPDT.IsZero() {
		r.PlaybackPDT = prev.PlaybackPDT.Add(msDuration(prev.DurationMS))
	}
	if r.PlaybackS3Key == "" {
		r.PlaybackS3Key = v.gapKey(r.Seq, prev.PlaybackS3Key)
	}
	r.IsGap = true
	return r
}

// gapKey 는 ③ 키가 없는 GAP 행 seq 의 키 이름이다 — 앞 행 키 prevKey 의 꼴을 따른다(POK-525 설계 2-1 · E12). prevKey 가
// 회차 꼴이면 그 회차의 회차 꼴(playback.RoundSegKey), 옛 꼴 · 빈 키 · 문법 밖이면 옛 꼴(playback.SegKey)이다.
//
// 사슬의 회차로 짓지 않는다 — 새 꼴 쓰기를 끈 상자의 장부에도 회차는 있어, 그러면 옛 꼴 목록에 회차 꼴 GAP 이 섞인다.
// 앞 행 키의 꼴은 그 행을 쓴 상자의 스위치가 정한 것이라, publish 는 스위치를 모른 채 꼴을 정한다. 파생 오류는 정상 장부에서
// 나지 않는다 — 스트림은 vodKey 가, 회차 성분은 ParseSegKey 가 같은 검사로 이미 봤고 seq 는 장부 값(0 이상)이다. 그래도 나면
// 빈 키를 돌려준다 — 키를 지어내지 않고 발행 전 검사(S5)가 그 목록을 막게 둔다.
//
// 정산 기한 GAP 은 fillGap(r, r) 로 자기 행 키를 prevKey 로 넘긴다(E12 예외). 그 행 키가 NULL 이면 빈 키라 옛 꼴이고, 뒤따르는
// 비귀속 행도 그 GAP 을 앞 행으로 따라 옛 꼴이 된다(TestVODSettleDeadlineGapWithNullKeyIsLegacyForm).
func (v *vodJob) gapKey(seq int64, prevKey string) string {
	var (
		key string
		err error
	)
	if ref, perr := playback.ParseSegKey(prevKey); perr == nil && ref.RoundID != "" {
		key, err = playback.RoundSegKey(v.chain.StreamID, ref.RoundID, seq)
	} else {
		key, err = playback.SegKey(v.chain.StreamID, seq)
	}
	if err != nil {
		return ""
	}
	return key
}

// markGaps 는 렌더 전 원장 적기다 — 한 트랜잭션에서 사슬 꼬리 행을 잠그고, 장부를 다시 읽어 plan 을 다시 세운 뒤 GAP
// 행을 넣고 포기면 범위의 미착지 GAP 원장 행을 vod_abandoned 로 바꾼다. 돌려준 장부 · plan 이 렌더 입력이다(잠금 밖의
// 첫 읽기는 재시도 · 실패를 일찍 가르는 데만 쓴다).
//
// 다시 읽기는 같은 트랜잭션 · 같은 연결이다(index.ReadChainLedger · ReadRoundLedger — 잠금을 쥔 채 풀에서 연결을 더 받지
// 않는다). 등재 트랜잭션은 READ COMMITTED 라 문장마다 잠금 뒤의 새 스냅숏이고 다섯(회차는 여섯) 문장이 한 스냅숏은 아니다.
// 다른 VOD writer · 사슬 닫힘은 꼬리 잠금으로 직렬이라 원장 · 사슬은 그대로이고, 문장 사이에 끼어드는 것은 ③ 착지와 꼬리
// 잠금이 막지 않는 늦은 조각 INSERT 뿐이다 — 행 범위는 범위 문장이 정하고 뒤 문장은 더 늦은 착지를 볼 뿐이다. 회차 경로의
// 앞 회원은 재판정(rejudge)이 종국 · 회차 닫힘을 확인한 뒤라 그 원장 · 상태가 더 바뀌지 않는다. 꼬리 행을 쥔 트랜잭션이
// 있으면 lock_timeout 5초 뒤 재시도다.
func (v *vodJob) markGaps(ctx context.Context, abandon bool) (vodInput, vodPlan, FreezeOutcome, bool) {
	dctx, cancel := v.p.stmtCtx(ctx)
	defer cancel()
	var (
		in   vodInput
		plan vodPlan
		out  FreezeOutcome
		ok   bool
	)
	err := pgx.BeginFunc(dctx, v.p.pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(dctx, vodTxnLimitsSQL); err != nil {
			return err
		}
		if _, err := tx.Exec(dctx, vodLockTailSQL, v.chain.Tail, v.chain.StreamID); err != nil {
			return err
		}
		if in, plan, out, ok = v.reread(dctx, tx, abandon); !ok || len(plan.rows) == 0 {
			return nil // 적을 것이 없다 — 커밋이 잠금만 푼다(재독 오류로 깨진 트랜잭션이면 되돌린다)
		}
		return v.insertMarks(dctx, tx, plan)
	})
	switch {
	case !ok && out.Result != "": // 재독이 갈랐다 — 쓴 것이 없어 커밋 오류는 더할 것이 없다
		return vodInput{}, vodPlan{}, out, false
	case index.IsLockTimeout(err):
		return vodInput{}, vodPlan{}, vodRetry(vodContended, err), false
	case err != nil:
		return vodInput{}, vodPlan{}, vodRetry(vodLedgerWrite, err), false
	}
	return in, plan, out, ok
}

// reread 는 꼬리 잠금 트랜잭션 tx 안의 장부 재독과 plan 이다. 회차 경로는 다시 읽은 판정 재료로 범위를 다시 판정하고
// (rejudge), 회차 범위가 아니면 plan 없이 그 결과를 돌려준다.
func (v *vodJob) reread(ctx context.Context, tx pgx.Tx, abandon bool) (vodInput, vodPlan, FreezeOutcome, bool) {
	if !v.round {
		l, err := index.ReadChainLedger(ctx, tx, v.chain, vodMaxRows)
		l, plan, out, ok := v.judge(l, err, abandon)
		return chainInput(l), plan, out, ok
	}
	l, err := index.ReadRoundLedger(ctx, tx, v.chain, vodMaxRows)
	if err == nil {
		if out, ok := rejudge(v.chain, l.Gate); !ok {
			return vodInput{}, vodPlan{}, out, false
		}
	}
	return v.judgeRound(l, err, abandon)
}

// load 는 장부(사슬 경로는 사슬 장부 · 회차 경로는 회차 장부)를 풀의 읽기 전용 스냅숏으로 읽어 plan 을 세운다.
func (v *vodJob) load(ctx context.Context, abandon bool) (vodInput, vodPlan, FreezeOutcome, bool) {
	if v.round {
		l, err := index.LoadRoundLedger(ctx, v.p.pool, v.chain, vodMaxRows)
		return v.judgeRound(l, err, abandon)
	}
	l, err := index.LoadChainLedger(ctx, v.p.pool, v.chain, vodMaxRows)
	l, plan, out, ok := v.judge(l, err, abandon)
	return chainInput(l), plan, out, ok
}

// judge 는 읽은 장부 l(읽기 오류 err)로 plan 을 세운다 — 장부의 사슬이 v.chain 과 다르거나 꼬리가 없으면 실패다.
func (v *vodJob) judge(l index.ChainLedger, err error, abandon bool) (index.ChainLedger, vodPlan, FreezeOutcome, bool) {
	switch {
	case errors.Is(err, index.ErrChainChanged), errors.Is(err, index.ErrChainNotFound):
		return l, vodPlan{}, vodFailed(vodChainChanged, err), false
	case errors.Is(err, index.ErrChainTooLarge):
		return l, vodPlan{}, vodFailed(vodTooLarge, err), false
	case err != nil:
		return l, vodPlan{}, vodRetry(freezeLedger, err), false
	}
	plan, out, ok := v.plan(l, abandon)
	return l, plan, out, ok
}

// insertMarks 는 plan 의 GAP 행을 넣고, 포기면 마지막 사슬 범위의 미착지 GAP 원장 행을 vod_abandoned 로 바꾼다 — 회차
// 경로에서 앞 범위의 원장은 앞 사슬 동결이 이미 정했다(R26).
func (v *vodJob) insertMarks(ctx context.Context, tx pgx.Tx, plan vodPlan) error {
	for _, m := range plan.marks {
		if _, err := tx.Exec(ctx, vodMarkGapSQL, v.chain.StreamID, m.seq, m.session, m.reason); err != nil {
			return err
		}
	}
	if !plan.abandon || !plan.lastChain {
		return nil
	}
	_, err := tx.Exec(ctx, vodAbandonGapsSQL, v.chain.StreamID, plan.abandonFirst, plan.abandonLast)
	return err
}

// write 는 렌더 · 검사 · IfAbsent PUT · 확정이다. 회차 경로면 확정 결과에 마지막 사슬 범위의 GAP 유무를 싣는다.
func (v *vodJob) write(ctx context.Context, in vodInput, plan vodPlan) FreezeOutcome {
	pl := v.playlistInput(in, plan.rows)
	body, err := rewind.Render(pl)
	if err != nil {
		return vodFailed(freezeRender, err)
	}
	if err := rewind.Validate(pl, body, 1, nil); err != nil {
		return vodFailed(vodValidateReason(err), err)
	}
	sum := sha256.Sum256(body)
	session := v.chain.SessionIDs[0]
	if v.round {
		session = v.chain.RoundID
	}
	meta := map[string]string{metaSession: session, metaBodySHA256: encodeSHA(sum)}
	pctx, cancel := context.WithTimeout(ctx, v.p.opt.PublishTimeout)
	etag, err := v.p.store.Put(pctx, v.key, IfAbsent(), body, meta)
	cancel()
	if err == nil {
		err = checkETag(etag)
	}
	if errors.Is(err, ErrPreconditionFailed) {
		etag, err = v.adopt(ctx, sum)
		if err != nil {
			return v.adoptFailed(err)
		}
	} else if err != nil {
		return vodRetry(putReason(err), err)
	}
	result := ResultFrozen
	if slices.ContainsFunc(plan.rows, func(r boundary.Row) bool { return r.IsGap }) {
		result = ResultFrozenWithGaps
	}
	out := v.confirm(ctx, result, &plan, etag, sum[:])
	if v.round {
		out.LastChainHasGaps = plan.lastChainHasGaps()
	}
	return out
}

// errVODConflict 는 412 뒤 Head 의 본문 sha 가 다시 렌더한 본문과 다르다는 뜻이다(D7 vod_conflict).
var errVODConflict = errors.New("publish: VOD 키에 다른 본문이 있다")

// adopt 는 412 뒤 Head 로 저장된 판을 본다 — pc-body-sha256 이 sum 과 같으면 그 판의 ETag 를 돌려준다(D7 채택).
func (v *vodJob) adopt(ctx context.Context, sum [sha256.Size]byte) (string, error) {
	hctx, cancel := context.WithTimeout(ctx, v.p.opt.PublishTimeout)
	stat, err := v.p.store.Head(hctx, v.key)
	cancel()
	if err != nil {
		return "", err
	}
	got, err := metaSHA256(stat.Meta)
	if err == nil {
		err = checkETag(stat.ETag)
	}
	switch {
	case !stat.Exists:
		return "", fmt.Errorf("publish: 412 뒤 VOD 키 %q 에 객체가 없다", v.key)
	case err != nil:
		return "", fmt.Errorf("%w — 저장된 판의 메타를 읽을 수 없다: %v", errVODConflict, err)
	case got != sum:
		return "", errVODConflict
	}
	return stat.ETag, nil
}

// adoptFailed 는 채택 실패의 결과다 — 다른 본문이면 실패(덮어쓰지 않는다), Head 를 못 읽었으면 재시도다.
func (v *vodJob) adoptFailed(err error) FreezeOutcome {
	if errors.Is(err, errVODConflict) {
		return vodFailed(vodConflict, err)
	}
	return vodRetry(freezeHeadFailed, err)
}

// playlist 는 사슬 장부 l 의 VOD 렌더 값이다(playlistInput).
func (v *vodJob) playlist(l index.ChainLedger, rows []boundary.Row) rewind.Playlist {
	return v.playlistInput(chainInput(l), rows)
}

// playlistInput 은 VOD 렌더 값이다. 소유 회차는 첫 행의 회차다 — 그래야 첫 행 앞이 계승 접두가 아니고(S4) 범위 검사(S3)의
// 「회차 첫 조각부터」가 선다. 머리는 새 문서의 것이다 — TARGETDURATION 은 입력 세션 전부의 최댓값(사슬은 계승이 TD 를
// 내리지 않아 꼬리 값과 같고, 회차는 TD 분할로 사슬마다 다를 수 있다), DISC-SEQ 는 0 이다. 첫 회차의 계승은 지운다 — 그 앞
// 회차는 이 문서에 없어 첫 행 앞 끊김 표시가 뜻이 없다.
//
// 계승이 빈 다른 세션(회차 경로의 뒤 사슬 머리)은 렌더에서만 앞 행 세션을 계승한 것으로 둔다 — 사슬 경계 첫 조각 앞에 끊김
// 표시가 서고(R1 · 발행 전 검사 S5 VOD 세션 경계), 장부의 inherits_session 은 바꾸지 않는다. 사슬 경로에는 그런 세션이
// 없어(사슬 = 계승) 본문이 그대로다.
func (v *vodJob) playlistInput(in vodInput, rows []boundary.Row) rewind.Playlist {
	owner := rows[0].SessionID
	var td int32
	for _, s := range in.sessions {
		td = max(td, s.TargetDuration)
	}
	pl := rewind.Playlist{StreamID: v.chain.StreamID, BaseURL: v.p.opt.BaseURL, Cutoff: in.cutoffSeq, Owner: owner,
		Rows: rows, Kind: rewind.KindVOD, Ended: true}
	before := sessionPredecessors(rows)
	for _, s := range in.sessions {
		rs := renderSession(s)
		switch {
		case rs.ID == owner:
			rs.InheritsSession, rs.DiscontinuityBase, rs.TargetDuration = "", 0, td
		case rs.InheritsSession == "":
			rs.InheritsSession = before[rs.ID]
		}
		pl.Sessions = append(pl.Sessions, rs)
	}
	return pl
}

// sessionPredecessors 는 rows 에서 세션마다 그 세션 첫 행 바로 앞 행의 세션이다(첫 행의 세션은 없다).
func sessionPredecessors(rows []boundary.Row) map[string]string {
	before := map[string]string{}
	for i := 1; i < len(rows); i++ {
		id, prev := rows[i].SessionID, rows[i-1].SessionID
		if _, seen := before[id]; !seen && id != prev && id != rows[0].SessionID {
			before[id] = prev
		}
	}
	return before
}

// confirm 은 사슬 전원의 VOD 확정이다 — 한 트랜잭션에서 바꾼 행 수가 사슬 수와 같을 때만 확정한다. 0행이면 다른 writer
// 가 먼저 확정했는지 본다(그러면 그 결과 · 아니면 재시도).
func (v *vodJob) confirm(ctx context.Context, result string, plan *vodPlan, etag string, sha []byte) FreezeOutcome {
	var key, etagArg any
	var first, last any
	if plan != nil {
		key, etagArg = v.key, etag
		first, last = plan.rows[0].Seq, plan.rows[len(plan.rows)-1].Seq
	}
	state := result
	if result == ResultEmpty {
		state = ResultFrozen
	}
	dctx, cancel := v.p.stmtCtx(ctx)
	defer cancel()
	var n int64
	err := pgx.BeginFunc(dctx, v.p.pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(dctx, vodLockChainSQL, v.chain.StreamID, v.chain.SessionIDs); err != nil {
			return err
		}
		if err := tx.QueryRow(dctx, vodConfirmSQL, v.chain.StreamID, v.chain.SessionIDs, state, key, etagArg, sha, first, last).Scan(&n); err != nil {
			return err
		}
		if n != 0 && n != int64(len(v.chain.SessionIDs)) {
			return errPartialChain
		}
		return nil
	})
	switch {
	case errors.Is(err, errPartialChain):
		return vodRetry(freezeConfirm, err)
	case err != nil:
		return vodRetry(freezeDB, err)
	case n == 0:
		return v.alreadyConfirmed(ctx)
	}
	return FreezeOutcome{Axis: AxisVOD, Result: result}
}

// errPartialChain 은 사슬 일부만 freezing 이라 확정을 되돌렸다는 뜻이다.
var errPartialChain = errors.New("publish: 사슬 일부만 VOD freezing 이다")

// alreadyConfirmed 는 확정 0행의 까닭이다 — 꼬리가 이미 VOD 종국이면 그 결과(멱등), 아니면 재시도다.
func (v *vodJob) alreadyConfirmed(ctx context.Context) FreezeOutcome {
	dctx, cancel := v.p.stmtCtx(ctx)
	defer cancel()
	var state string
	if err := v.p.pool.QueryRow(dctx, vodStateSQL, v.chain.Tail, v.chain.StreamID).Scan(&state); err != nil {
		return vodRetry(freezeDB, err)
	}
	switch state {
	case ResultFrozen, ResultFrozenWithGaps:
		return FreezeOutcome{Axis: AxisVOD, Result: state}
	case freezeStateFailed:
		return vodFailed(freezeStateFailed, nil)
	}
	return vodRetry(freezeConfirm, nil)
}

// vodValidateReason 은 VOD 검사 위반 err 의 사유다 — 본문 상한 초과는 too_large(설계 3.1 한계), 나머지는 validate 다.
func vodValidateReason(err error) string {
	if errors.Is(err, rewind.ErrBodyTooLarge) {
		return vodTooLarge
	}
	return freezeValidate
}

// vodKey 는 사슬 머리 head 의 VOD 객체 키다(사슬 범위 — 회차 범위는 vodRoundKey).
//
//	vod/{streamID}/{head}/index.m3u8
//
// 성분 검사는 목록 키(manifestKey)와 같다.
func vodKey(streamID, head string) (string, error) {
	if _, err := manifestKey(streamID, head); err != nil {
		return "", err
	}
	return vodKeyPrefix + streamID + "/" + head + "/index.m3u8", nil
}

// msDuration 은 ms 정수 길이다.
func msDuration(ms int32) time.Duration { return time.Duration(ms) * time.Millisecond }

// encodeSHA 는 sha256 의 메타 표기(소문자 16진 — encodeMeta 의 pc-body-sha256 과 같다)다.
func encodeSHA(sum [sha256.Size]byte) string { return hex.EncodeToString(sum[:]) }

func vodRetry(reason string, err error) FreezeOutcome {
	return FreezeOutcome{Axis: AxisVOD, Result: ResultRetry, Retry: true, Reason: reason, Err: err}
}

func vodFailed(reason string, err error) FreezeOutcome {
	return FreezeOutcome{Axis: AxisVOD, Result: ResultFailed, Reason: reason, Err: err}
}
