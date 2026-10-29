package session

// 의도적 종료 적용(계약4 4D — POK-196 M6-3a · 설계 M6 2.3 · 3.3 · 3.4). POK-472 수신부가 부른다.
//
// 4D 는 live 를 끝내지 않는다. 플러그인은 멈추는 즉시 신호를 보내지만 그 연결의 마지막 조각은 약 10초 늦게 INSERT
// 되므로, 신호 때 live 를 닫으면 그 조각이 유령 회차를 연다(D53). 그래서 효과는 셋뿐이다 — live 에 멈춘 시각 표식
// (marked), 이미 끊긴 회차 최신 세션의 사유 승격과 회차 닫힘(upgraded), 신호가 새 송출 합류 뒤에 닿았을 때의 회차
// 분리(round_split). 확실하지 않으면 적용하지 않는다(kty 2026-09-26) — 미적용은 Skip 이 사유다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// IntentionalEnd 는 4D 신호 하나다. streamid 해석 · 인증은 수신부 몫이다.
type IntentionalEnd struct {
	StreamID      string    // 장부 stream_id
	StoppedAt     time.Time // 수신 시각 − elapsedSinceStopMs (Media 시계)
	ConnStartedAt time.Time // StoppedAt − connectionDurationMs
}

// IntentionalResult 는 적용 결과다. 돌아오면 커밋된 뒤다.
type IntentionalResult struct {
	SessionID string // 고른 후보 T(미적용이어도 찾았으면 채운다)
	RoundID   string // T 의 회차(round_split 이면 나뉘기 전 회차 R)
	// Effect 는 적용 효과다. 빈 값이면 미적용이고 Skip 이 사유다.
	//   marked      live T 에 멈춘 시각 표식(회차는 아직 안 닫힘)
	//   upgraded    회차 최신인 ending · ended T 의 사유를 intentional 로 · 회차 닫힘
	//   round_split T 뒤 합류한 세션부터 새 회차 · R 닫힘
	Effect string
	// Skip: no_session · conn_mismatch · short_conn · ambiguous · round_closed · round_split_noop · round_split_blocked · contended
	// · bad_time(연결 시작이 멈춘 시각보다 늦음 또는 멈춘 시각이 PG 시계 + Slack 보다 미래 — 수신부 범위 검사의 겹 방어)
	Skip string
	// RevokeSession 은 계승 해제를 루프에 요청할 세션이다(round_split ∧ 새 회차 첫 세션이 계승 중일 때만).
	RevokeSession string
}

// Retryable 은 같은 신호를 다시 보내면 결과가 달라질 수 있는 미적용인가다 — contended(잠금 경합 · 시한 · 교착)만
// 참이다. 수신부는 이것으로 503(플러그인 재시도)을 고른다. 나머지 미적용(bad_time 등)은 다시 보내도 같은 답이다.
func (r IntentionalResult) Retryable() bool {
	return r.Skip == skipContended
}

// IntentOptions 는 수치와 함수 칸이다. session 은 index · broadcast 를 임포트하지 않으므로 회차 닫힘 · 완결 평가와
// started outbox 는 cmd 가 묶어 끼운다. 비어 있으면 적용 전에 오류다 — 조립(cmd)이 무동작 + WARN 기본값을 끼운다.
type IntentOptions struct {
	ShortConn, Slack time.Duration // 4초 · 8초(K8)
	// CloseRound 는 회차 닫힘 조건 평가(이미 닫혔으면 건너뜀) + 완결 평가다. upgraded · round_split 이 같은 tx
	// 끝에서 부른다 — round_split 은 R 을 직접 닫은 뒤라 완결만 평가된다.
	CloseRound func(ctx context.Context, tx pgx.Tx, round string) error
	// EnqueueStarted 는 broadcast.EnqueueStarted 다 — round_split 의 새 회차 B 에 쓴다.
	EnqueueStarted func(ctx context.Context, tx pgx.Tx, round, ingestStreamID string, at time.Time) error
	// Log 는 intentional_end 신호를 받는다. nil 이면 slog.Default() 다.
	Log *slog.Logger
}

// 효과 · 미적용 사유의 글자(설계 11절 POK-472 결과 계약).
const (
	effectMarked     = "marked"
	effectUpgraded   = "upgraded"
	effectRoundSplit = "round_split"

	skipNoSession         = "no_session"
	skipConnMismatch      = "conn_mismatch"
	skipShortConn         = "short_conn"
	skipAmbiguous         = "ambiguous"
	skipRoundClosed       = "round_closed"
	skipRoundSplitNoop    = "round_split_noop"
	skipRoundSplitBlocked = "round_split_blocked"
	skipContended         = "contended"
	skipBadTime           = "bad_time"
)

// intentTxnDeadline 은 적용 트랜잭션 하나의 ctx 시한이다(index.TxnDeadline 과 같은 10초 — 문장 상한과 같은 길이).
const intentTxnDeadline = 10 * time.Second

// membershipTries 는 분리 · 닫힘 쪽 소속 잠금의 재확인 상한이다(설계 3.3 — 넘기면 contended).
const membershipTries = 5

// errMembershipContended 는 소속 행이 membershipTries 안에 멈추지 않았다는 뜻이다(contended 로 접힌다).
var errMembershipContended = errors.New("session: 소속 행이 재확인 상한 안에 멈추지 않았다")

// ApplyIntentionalEnd 는 판정과 효과를 한 트랜잭션에서 한다(3.4). 돌아오면 커밋된 뒤다. 부른 쪽은 Effect 가 비지
// 않으면 PlaybackChanged(e.StreamID) 를, RevokeSession 이 비지 않으면 intents.Post(e.StreamID, RevokeSession) 를
// 부른다. 캐시는 만지지 않는다. 미적용(잠금 경합 · 시한의 contended 포함)은 오류가 아니라 Skip 이다. contended 는
// 커밋 전에 접히고 Retryable 이 참이다.
func ApplyIntentionalEnd(ctx context.Context, pool *pgxpool.Pool, e IntentionalEnd, o IntentOptions) (IntentionalResult, error) {
	if err := o.validate(e); err != nil {
		return IntentionalResult{}, err
	}
	tctx, cancel := context.WithTimeout(ctx, intentTxnDeadline)
	defer cancel()
	tx, err := pool.Begin(tctx)
	if err != nil {
		return IntentionalResult{}, fmt.Errorf("4D 적용 트랜잭션 시작 실패 stream_id=%q: %w", e.StreamID, err)
	}
	// 되돌림 ctx 는 되돌리는 순간에 만든다(index.insertOnce 와 같은 이유 — 끝난 ctx 로는 ROLLBACK 을 못 보낸다).
	defer func() {
		rctx, rcancel := context.WithTimeout(context.WithoutCancel(ctx), intentTxnDeadline)
		defer rcancel()
		_ = tx.Rollback(rctx)
	}()

	res, err := applyIntent(tctx, tx, e, o)
	if isContention(err) {
		res.Effect, res.Skip, res.RevokeSession = "", skipContended, ""
		o.logResult(e, res)
		return res, nil
	}
	if err != nil {
		return IntentionalResult{}, err
	}
	if res.Effect != "" {
		if err := tx.Commit(tctx); err != nil {
			return IntentionalResult{}, fmt.Errorf("4D 적용 확정 실패 stream_id=%q: %w", e.StreamID, err)
		}
	}
	o.logResult(e, res)
	return res, nil
}

// validate 는 적용 전 검사다 — 조립 결함과 입력 결함은 쓰기 전에 오류로 올린다.
func (o IntentOptions) validate(e IntentionalEnd) error {
	if o.CloseRound == nil || o.EnqueueStarted == nil {
		return errors.New("session: IntentOptions 함수 칸이 비었다 — 조립이 기본값(무동작 + WARN)을 끼워야 한다")
	}
	if o.ShortConn <= 0 || o.Slack <= 0 {
		return fmt.Errorf("session: IntentOptions 수치가 비었다 ShortConn=%v Slack=%v", o.ShortConn, o.Slack)
	}
	if e.StreamID == "" || e.StoppedAt.IsZero() || e.ConnStartedAt.IsZero() {
		return fmt.Errorf("session: 4D 입력이 비었다 stream_id=%q stopped_at=%v conn_started_at=%v",
			e.StreamID, e.StoppedAt, e.ConnStartedAt)
	}
	return nil
}

// applyIntent 는 트랜잭션 안의 판정 · 효과다. 잠금 순서(3.3): ⓪ 상한 설정(PG 시계로 시각 검사) → ① 위상 잠금 →
// ② 소속 잠금 → (판정) → ③ 회차 행. 후보 · 효과 판정은 소속 잠금 뒤 새 문장으로 한다.
func applyIntent(ctx context.Context, tx pgx.Tx, e IntentionalEnd, o IntentOptions) (IntentionalResult, error) {
	var now time.Time
	if err := tx.QueryRow(ctx, txnLimitsSQL).Scan(&now); err != nil {
		return IntentionalResult{}, fmt.Errorf("4D 상한 설정 실패 stream_id=%q: %w", e.StreamID, err)
	}
	if implausibleTimes(e, now, o.Slack) {
		return IntentionalResult{Skip: skipBadTime}, nil
	}
	if _, err := tx.Exec(ctx, topologyLockSQL, e.StreamID); err != nil {
		return IntentionalResult{}, fmt.Errorf("4D 위상 잠금 실패 stream_id=%q: %w", e.StreamID, err)
	}
	member, err := lockMembership(ctx, tx, e.StreamID)
	if err != nil || member == "" {
		return IntentionalResult{Skip: skipNoSession}, err
	}
	t, found, err := findIntentCandidate(ctx, tx, e, o.Slack)
	if err != nil || !found {
		return IntentionalResult{Skip: skipConnMismatch}, err
	}
	res := IntentionalResult{SessionID: t.id, RoundID: t.roundID}
	if e.StoppedAt.Sub(e.ConnStartedAt) < o.ShortConn {
		res.Skip = skipShortConn
		return res, nil
	}
	if amb, err := otherRoundStartedNear(ctx, tx, e, t.roundID, o.Slack); err != nil || amb {
		res.Skip = skipAmbiguous
		return res, err
	}
	if t.state == "live" {
		return markLive(ctx, tx, e, res)
	}
	if t.roundID == "" || t.roundClosed {
		res.Skip = skipRoundClosed // M6 전 NULL 회차는 닫힘 개념이 없어 닫힌 것과 같이 다룬다
		return res, nil
	}
	rows, err := lockRoundRows(ctx, tx, t.roundID)
	if err != nil {
		return res, err
	}
	return applyToEndedRound(ctx, tx, e, o, res, rows)
}

// txnLimitsSQL 은 index.setTxnLimitsSQL 과 같은 트랜잭션 지역 상한이다 — lock_timeout 5초는 잠금 시도 하나의
// 상한이고(PG 17 19.11) 넘기면 55P03 → contended 다. 위상 잠금은 이 문장 **다음** 문장이어야 그 대기가 5초에서 끊긴다(D74).
const txnLimitsSQL = `
SELECT pg_catalog.clock_timestamp()
  FROM (SELECT pg_catalog.set_config('lock_timeout','5s',true),
               pg_catalog.set_config('statement_timeout','10s',true)) AS _limits`

// topologyLockSQL 은 스트림별 위상 잠금이다(설계 3.3) — 회차 · 사슬 소속을 바꾸거나 여러 세션 행을 함께 쓰는
// 트랜잭션끼리 줄 세운다. 이름 공간 pc_topology_ 는 컷오프 주조의 pc_cutoff_ 와 겹치지 않는다. index 의 EndLive ·
// CloseChain · CloseRound(M6-3b)가 같은 글자를 쓴다 — index 는 session 을 임포트하지 않아 글자를 따로 든다.
const topologyLockSQL = `SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('pc_topology_' || $1::text)::bigint)`

// membershipSQL 은 소속 행 선택 규칙이다(설계 3.3) — live 가 있으면 그 live, 없으면 started_at 최대(같으면 session_id 큰 쪽).
// COALESCE 는 함수가 아니라 구문이라 pg_catalog 로 한정하지 않는다(search_path 로 가로챌 수 없다).
const membershipSQL = `
SELECT COALESCE(
  (SELECT session_id FROM stream_sessions WHERE stream_id = $1 AND state = 'live'),
  (SELECT session_id FROM stream_sessions WHERE stream_id = $1 ORDER BY started_at DESC, session_id DESC LIMIT 1))`

// lockSessionSQL 은 세션 행 하나의 소속 잠금이다 — 강도는 FOR NO KEY UPDATE(조각 INSERT 의 FK 잠금과 충돌하지 않음 · D71).
const lockSessionSQL = `SELECT 1 FROM stream_sessions WHERE session_id = $1 FOR NO KEY UPDATE`

// lockMembership 은 소속 행을 잡고 **새 문장으로 다시 고른다**(설계 3.3 분리 · 닫힘 쪽). 바뀌었으면 새 소속 행을 잡고
// 다시 본다. membershipTries 안에 멈추지 않으면 errMembershipContended 다. 세션이 없으면 "" 다.
func lockMembership(ctx context.Context, tx pgx.Tx, streamID string) (string, error) {
	locked := ""
	for range membershipTries {
		var cur *string
		if err := tx.QueryRow(ctx, membershipSQL, streamID).Scan(&cur); err != nil {
			return "", fmt.Errorf("소속 행 조회 실패 stream_id=%q: %w", streamID, err)
		}
		if cur == nil {
			return "", nil
		}
		if *cur == locked {
			return locked, nil
		}
		if _, err := tx.Exec(ctx, lockSessionSQL, *cur); err != nil {
			return "", fmt.Errorf("소속 잠금 실패 session_id=%q: %w", *cur, err)
		}
		locked = *cur
	}
	return "", errMembershipContended
}

// implausibleTimes 는 4D 시각이 말이 안 되는가다 — 연결 시작이 멈춘 시각보다 늦거나, 멈춘 시각이 PG 시계 now 에
// 대조 여유를 더한 것보다 미래다. 수신부 범위 검사(POK-472 P10)의 겹 방어다. 미래 표식은 markSQL 의 COALESCE 로 첫
// 표식에 고착되어 그 뒤 조각을 모두 옛 세션에 붙이므로 쓰기 전에 거부한다.
func implausibleTimes(e IntentionalEnd, now time.Time, slack time.Duration) bool {
	return e.ConnStartedAt.After(e.StoppedAt) || e.StoppedAt.After(now.Add(slack))
}

// isContention 은 err 가 잠금 상한(55P03) · 문장 시한(57014) · 교착 희생(40P01) · ctx 시한 · 소속 재확인 초과로 끊긴
// 실패인가다 — 모두 contended 로 접는다(index.isContention 과 같은 집합).
func isContention(err error) bool {
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, errMembershipContended) {
		return true
	}
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) {
		return false
	}
	switch pgErr.Code {
	case "55P03", "57014", "40P01":
		return true
	}
	return false
}

// logResult 는 결과 신호다 — 효과는 INFO, 미적용은 WARN(설계 11절 「로그」).
func (o IntentOptions) logResult(e IntentionalEnd, res IntentionalResult) {
	log := o.Log
	if log == nil {
		log = slog.Default()
	}
	if res.Effect != "" {
		log.Info("intentional_end", "stream_id", e.StreamID, "effect", res.Effect,
			"round", res.RoundID, "session", res.SessionID, "revoke", res.RevokeSession)
		return
	}
	log.Warn("intentional_end", "stream_id", e.StreamID, "skip", res.Skip,
		"round", res.RoundID, "session", res.SessionID, "stopped_at", e.StoppedAt, "conn_started_at", e.ConnStartedAt)
}
