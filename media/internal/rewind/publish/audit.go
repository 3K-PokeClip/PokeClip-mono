package publish

// 동결 감사 — D17 4.1(b) · M6 설계 6절 동결 행(POK-196 M6-2 커밋 ③). 30초 감시 주기(판정부 · M6-3b 배선)에 부른다.
// DVR 의 앞부분 갈래만 조건부로 다시 쓴다(kty 결정 ⑨ — D17 「재렌더 → 재PUT」 을 조건부로 개정 · 커밋 ⑥).

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// freezeAuditLog 는 동결 감사의 신호 키다 — 바뀌었거나(mismatch) 없는(missing) 동결 문서와 복구 갈래의 멈춤
// (dvr_audit_repair_*)은 ERROR, 읽지 못한 것(read_failed)은 WARN, 고친 것(dvr_audit_repaired)은 INFO 다. 속성은
// axis · stream · session · key · reason(· err)이다.
const freezeAuditLog = "session_freeze_audit"

// 감사 설계값 — D17 4.1(b) FROZEN_AUDIT_WINDOW(30분)와 한 번에 보는 문서 수 상한이다. 창은 문서당 복구 1회의 창이기도
// 하다.
const (
	frozenAuditWindow = 30 * time.Minute
	frozenAuditLimit  = 100
)

// frozenDocsSQL 은 감사 대상이다 — 문서가 있는 동결(DVR frozen · VOD frozen · frozen_with_gaps) 가운데 장부 시각
// COALESCE(ended_at, dvr_frozen_at) 이 감사 창 $1 안인 것. VOD 는 사슬 세션마다 같은 키라 키로 한 번만 고른다. DVR 은
// 재렌더 입력(동결 범위 · base)도 읽는다.
const frozenDocsSQL = `
SELECT DISTINCT ON (key) axis, key, stream_id, session_id, etag, sha, first_seq, last_seq, disc_base
  FROM (SELECT 'dvr' AS axis, dvr_s3_key AS key, stream_id, session_id,
               COALESCE(dvr_etag, '') AS etag, dvr_sha256 AS sha, COALESCE(dvr_first_seq, 0) AS first_seq,
               COALESCE(dvr_last_seq, 0) AS last_seq, COALESCE(dvr_disc_base, 0) AS disc_base
          FROM stream_sessions
         WHERE dvr_state = 'frozen' AND dvr_s3_key IS NOT NULL
           AND COALESCE(ended_at, dvr_frozen_at) > pg_catalog.now() - $1::interval
        UNION ALL
        SELECT 'vod', vod_s3_key, stream_id, session_id, COALESCE(vod_etag, ''), vod_sha256, 0, 0, 0
          FROM stream_sessions
         WHERE vod_state IN ('frozen', 'frozen_with_gaps') AND vod_s3_key IS NOT NULL
           AND COALESCE(ended_at, dvr_frozen_at) > pg_catalog.now() - $1::interval) AS d
 ORDER BY key, session_id
 LIMIT $2`

// repairClaimSQL 은 복구 직전의 장부 CAS 다 — 동결 그대로(키 · ETag · sha)이고 fence 가 비었거나 · 끝났거나 · 내
// 것일 때만 fence 를 쥐고 세대를 하나 올린다. 돌려받는 세대가 고친 판의 pc-gen 과 확정 조건이다. 0행이면 다른
// 감사기가 쥐었거나 장부가 움직였다(물러난다). S3 는 fence 를 모르므로 두 감사기 가운데 하나만 PUT 하게 여기서 고른다.
const repairClaimSQL = `
UPDATE stream_sessions
   SET writer_fence = $2, fence_expires_at = pg_catalog.now() + $3::interval, manifest_gen = manifest_gen + 1
 WHERE session_id = $1 AND stream_id = $4 AND dvr_state = 'frozen' AND dvr_s3_key = $5
   AND dvr_etag = $6 AND dvr_sha256 = $7
   AND (writer_fence IS NULL OR fence_expires_at < pg_catalog.now() OR writer_fence = $2)
RETURNING manifest_gen`

// repairConfirmSQL 은 고친 판의 ETag 를 장부에 남긴다 — 그 CAS 의 세대 · fence 그대로일 때만. 0행이어도 문서는 이미
// 고쳐졌다(다음 감사가 본문 sha 로 통과시킨다).
const repairConfirmSQL = `
UPDATE stream_sessions
   SET dvr_etag = $4, manifest_etag = $4
 WHERE session_id = $1 AND writer_fence = $2 AND manifest_gen = $3
   AND fence_expires_at > pg_catalog.now() AND dvr_state = 'frozen'`

// AuditOutcome 은 감사 한 번의 결과다 — 본 문서 수 · 바뀌었거나 없어 고치지 못한 문서 수 · 고친 문서 수 · 대상 읽기
// 실패다.
type AuditOutcome struct {
	Checked, Failed, Repaired int
	Err                       error
}

// frozenDoc 은 감사 대상 문서 하나다. first · last · discBase 는 DVR 의 동결 범위와 base 다(VOD 는 0).
type frozenDoc struct {
	axis, key, stream, session, etag string
	sha                              []byte
	first, last, discBase            int64
}

// FrozenAuditor 는 동결 감사다(D17 4.1(b)). 감사 창 안의 동결 문서마다 1차 Head 의 ETag 를 장부의 ETag 와 견주고,
// 다르면 2차 Get 본문의 sha256 을 장부의 sha 와 견준다(ETag 는 1차 거름일 뿐이다). 그래도 다르거나 객체가 없으면
// ERROR 다.
//
// 다시 쓰는 갈래는 DVR 의 앞부분 하나다(설계 6절 · 10절 — VOD 는 수동). 아래를 모두 채울 때만 장부로 다시 렌더한
// 닫힌 판을 올린다.
//
//	ⓐ 본 본문이 재렌더 본문의 앞부분 — 꼬리 조각 · ENDLIST 만 빠졌다(RFC 8216bis 6.2.1 허용 변경 안)
//	ⓑ 재렌더 본문의 sha256 = 장부의 dvr_sha256
//	ⓒ If-Match = ⓐ 의 본문을 읽은 Get 응답의 ETag(장부의 옛 ETag 도 Head 의 ETag 도 아니다 — 읽은 판에만 쓴다)
//	ⓓ 그 문서를 창(30분) 안에 고치려 한 적이 없다 — 이 감사기의 메모리라 재기동하면 잊는다(설계 10절 한계)
//	ⓔ 장부 CAS(repairClaimSQL)가 이 감사기를 골랐다 — 0행이면 물러난다
//
// PUT 이 412 면 멈춘다(dvr_audit_repair_rejected — 두 작성자가 살아 있다). 404 는 다시 만들지 않는다(missing — 삭제가
// 운영 의도일 수 있다). 409 면 Head 한 번으로 그 순간의 판을 적고 멈춘다. 결과 모름도 다시 시도하지 않는다 — 적용됐으면
// 다음 감사가 본문 sha 로 통과시킨다.
//
// 여러 고루틴에서 불러도 된다(문서당 1회 표는 잠금 아래에 있다).
type FrozenAuditor struct {
	p *Publisher

	mu sync.Mutex
	// tried 는 문서 키 → 복구 PUT 을 내기로 한 시각이다. 창이 지나면 지운다.
	tried map[string]time.Time
}

// NewFrozenAuditor 는 p 의 저장소 · 장부 · writer 토큰으로 도는 감사기를 만든다. 문서당 1회를 기억하므로 프로세스에
// 하나를 두고 감시 주기마다 Audit 을 부른다.
func NewFrozenAuditor(p *Publisher) *FrozenAuditor {
	return &FrozenAuditor{p: p, tried: map[string]time.Time{}}
}

// Audit 은 감사 한 번이다.
func (a *FrozenAuditor) Audit(ctx context.Context) AuditOutcome {
	docs, err := a.p.frozenDocs(ctx)
	if err != nil {
		return AuditOutcome{Err: err}
	}
	a.forget(a.p.now())
	out := AuditOutcome{Checked: len(docs)}
	for _, d := range docs {
		reason, err := a.auditDoc(ctx, d)
		switch reason {
		case "":
			continue
		case auditRepaired:
			out.Repaired++
		case auditReadFailed:
		default:
			out.Failed++
		}
		a.p.reportAudit(ctx, d, reason, err)
	}
	return out
}

// 감사 사유 — 신호의 reason 속성 값이다.
const (
	auditMissing    = "missing"
	auditMismatch   = "mismatch"
	auditReadFailed = "read_failed"

	auditRepaired        = "dvr_audit_repaired"         // INFO — 앞부분 갈래를 고쳤다
	auditRepairRejected  = "dvr_audit_repair_rejected"  // 복구 PUT 412
	auditRepairConflict  = "dvr_audit_repair_conflict"  // 복구 PUT 409(Head 한 번 뒤 멈춤)
	auditRepairFailed    = "dvr_audit_repair_failed"    // 복구 PUT 결과 모름 · 장부 CAS 오류
	auditRepairExhausted = "dvr_audit_repair_exhausted" // 창 안에 다시 어긋났다(문서당 1회)
	auditRepairFenced    = "dvr_audit_repair_fenced"    // 장부 CAS 0행 — 다른 감사기가 쥐었다
)

// auditDoc 은 문서 d 하나를 본다 — 통과면 "" 다. 받을 수 없는 Get ETag(checkETag)는 읽기 실패다.
func (a *FrozenAuditor) auditDoc(ctx context.Context, d frozenDoc) (string, error) {
	p := a.p
	hctx, cancel := context.WithTimeout(ctx, p.opt.PublishTimeout)
	stat, err := p.store.Head(hctx, d.key)
	cancel()
	switch {
	case err != nil:
		return auditReadFailed, err
	case !stat.Exists:
		return auditMissing, nil
	case stat.ETag == d.etag:
		return "", nil
	}
	gctx, cancel := context.WithTimeout(ctx, p.opt.PublishTimeout)
	body, etag, err := p.store.Get(gctx, d.key)
	cancel()
	switch {
	case err != nil:
		return auditReadFailed, err
	case bytes.Equal(sha256Bytes(body), d.sha):
		return "", nil
	case d.axis != AxisDVR:
		return auditMismatch, nil
	}
	// 조건은 본문과 같은 응답의 ETag 다 — Head 의 ETag 는 1차 거름일 뿐이다(Store.Get).
	if err := checkETag(etag); err != nil {
		return auditReadFailed, err
	}
	return a.repair(ctx, d, etag, body)
}

// repair 는 DVR 문서 d 의 본 판(ETag seen · 본문 seenBody)이 앞부분 갈래면 고친다. 아니면 mismatch 다 — 고치지
// 않은 까닭이 있으면 err 에 싣는다.
func (a *FrozenAuditor) repair(ctx context.Context, d frozenDoc, seen string, seenBody []byte) (string, error) {
	pl, want, err := a.p.renderFrozenDVR(ctx, d)
	switch {
	case err != nil:
		return auditMismatch, err
	case !bytes.Equal(sha256Bytes(want), d.sha):
		return auditMismatch, errors.New("publish: 재렌더 본문의 sha256 이 장부의 dvr_sha256 과 다르다 — 고치지 않는다")
	case !truncatedPrefix(seenBody, want):
		return auditMismatch, nil
	case !a.reserve(d.key, a.p.now()):
		return auditRepairExhausted, nil
	}
	gen, ok, err := a.p.claimRepair(ctx, d)
	if err != nil || !ok {
		a.release(d.key) // PUT 을 내지 않았다 — 1회를 쓰지 않는다
		if err != nil {
			return auditRepairFailed, err
		}
		return auditRepairFenced, nil
	}
	return a.p.putRepair(ctx, d, seen, pl, want, gen)
}

// reserve 는 문서 key 의 복구 1회를 now 에 쓴다. 창 안에 이미 썼으면 거짓이다.
func (a *FrozenAuditor) reserve(key string, now time.Time) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if at, ok := a.tried[key]; ok && now.Sub(at) < frozenAuditWindow {
		return false
	}
	a.tried[key] = now
	return true
}

// release 는 reserve 를 되돌린다.
func (a *FrozenAuditor) release(key string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.tried, key)
}

// forget 은 창이 지난 기록을 지운다 — 표가 감사 창 안의 문서 수를 넘어 자라지 않게.
func (a *FrozenAuditor) forget(now time.Time) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for key, at := range a.tried {
		if now.Sub(at) >= frozenAuditWindow {
			delete(a.tried, key)
		}
	}
}

// renderFrozenDVR 는 장부로 DVR 문서 d 의 닫힌 판을 다시 렌더한다 — 동결 범위 [first, last] 의 소유 회차 거름 ·
// base · 닫힘이 FreezeDVR 의 F1 과 같다(ledgerPlaylist 의 하한 = 동결 MSN).
func (p *Publisher) renderFrozenDVR(ctx context.Context, d frozenDoc) (rewind.Playlist, []byte, error) {
	first := d.first
	l, err := index.LoadSessionLedger(ctx, p.pool, d.stream, d.session,
		index.LedgerBounds{Hint: &first, Lookback: freezeLookback, RowCap: freezeRowCap})
	switch {
	case err != nil:
		return rewind.Playlist{}, nil, err
	case l.Capped:
		return rewind.Playlist{}, nil, errors.New("publish: 재렌더 장부 적재가 행 수 상한에 닿았다")
	}
	pl, ok := selectOwner(l, d.session, d.first, d.last)
	if !ok {
		return rewind.Playlist{}, nil, fmt.Errorf("publish: 소유 회차 %q 가 장부에 없다", d.session)
	}
	pl.StreamID, pl.BaseURL, pl.Ended = d.stream, p.opt.BaseURL, true
	pl.Sessions = slices.Clone(pl.Sessions)
	for i := range pl.Sessions {
		if pl.Sessions[i].ID == pl.Owner {
			pl.Sessions[i].DiscontinuityBase = d.discBase
		}
	}
	body, err := rewind.Render(pl)
	return pl, body, err
}

// truncatedPrefix 는 seen 이 want 의 앞부분 가운데 꼬리 조각(과 ENDLIST)만 빠진 것인가다 — want 보다 짧은 줄 경계의
// 앞부분이고 마지막 줄이 조각 URI 줄이다. 머리 · 조각 줄이 하나라도 다르거나 조각 중간(태그 줄)에서 끊겼으면 거짓이다.
func truncatedPrefix(seen, want []byte) bool {
	if len(seen) >= len(want) || !bytes.HasPrefix(want, seen) || !bytes.HasSuffix(seen, []byte("\n")) {
		return false
	}
	last := seen[bytes.LastIndexByte(seen[:len(seen)-1], '\n')+1:]
	return len(last) > 1 && last[0] != '#'
}

// claimRepair 는 repairClaimSQL 이다 — 고른 세대 · 골랐는가다.
func (p *Publisher) claimRepair(ctx context.Context, d frozenDoc) (int64, bool, error) {
	dctx, cancel := p.stmtCtx(ctx)
	defer cancel()
	var gen int64
	err := p.pool.QueryRow(dctx, repairClaimSQL, d.session, p.opt.Writer, p.opt.Lease, d.stream, d.key, d.etag, d.sha).Scan(&gen)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		return 0, false, nil
	case err != nil:
		return 0, false, fmt.Errorf("publish: 감사 복구 장부 CAS session=%q: %w", d.session, err)
	}
	return gen, true, nil
}

// putRepair 는 닫힌 판 want(목록 값 pl · 세대 gen)를 본 ETag seen 의 If-Match 로 한 번 올리고 장부 ETag 를 맞춘다.
func (p *Publisher) putRepair(ctx context.Context, d frozenDoc, seen string, pl rewind.Playlist, want []byte, gen int64) (string, error) {
	desc := describe(pl, gen, sha256.Sum256(want))
	if err := checkMetaRange(desc, pl); err != nil {
		return auditRepairFailed, err
	}
	pctx, cancel := context.WithTimeout(ctx, p.opt.PublishTimeout)
	etag, err := p.store.Put(pctx, d.key, IfMatch(seen), want, encodeMeta(desc, d.session))
	cancel()
	if err == nil {
		err = checkETag(etag)
	}
	switch {
	case errors.Is(err, ErrPreconditionFailed):
		return auditRepairRejected, err
	case errors.Is(err, ErrNotFound):
		return auditMissing, err
	case errors.Is(err, ErrConflict):
		return auditRepairConflict, p.headAfterConflict(ctx, d.key, err)
	case err != nil:
		return auditRepairFailed, err
	}
	dctx, cancel := p.stmtCtx(ctx)
	defer cancel()
	tag, err := p.pool.Exec(dctx, repairConfirmSQL, d.session, p.opt.Writer, gen, etag)
	switch {
	case err != nil:
		return auditRepaired, fmt.Errorf("publish: 고친 판의 장부 ETag 갱신 실패(다음 감사가 본문 sha 로 확인한다): %w", err)
	case tag.RowsAffected() != 1:
		return auditRepaired, errors.New("publish: 고친 판의 장부 ETag 갱신이 0행이다(다음 감사가 본문 sha 로 확인한다)")
	}
	return auditRepaired, nil
}

// headAfterConflict 는 409 뒤 Head 한 번이다 — 그 순간의 판을 conflict 오류에 덧붙인다. 다시 PUT 하지 않는다.
func (p *Publisher) headAfterConflict(ctx context.Context, key string, conflict error) error {
	hctx, cancel := context.WithTimeout(ctx, p.opt.PublishTimeout)
	stat, err := p.store.Head(hctx, key)
	cancel()
	if err != nil {
		return fmt.Errorf("%w; 409 뒤 Head 실패: %v", conflict, err)
	}
	return fmt.Errorf("%w; 409 뒤 Head: 있음=%t ETag=%.64q", conflict, stat.Exists, stat.ETag)
}

// reportAudit 은 감사 한 줄을 남긴다 — 고침은 INFO, 읽기 실패는 WARN, 나머지는 ERROR 다.
func (p *Publisher) reportAudit(ctx context.Context, d frozenDoc, reason string, err error) {
	level := slog.LevelError
	switch reason {
	case auditRepaired:
		level = slog.LevelInfo
	case auditReadFailed:
		level = slog.LevelWarn
	}
	attrs := []any{"axis", d.axis, "stream", d.stream, "session", d.session, "key", d.key, "reason", reason}
	if err != nil {
		attrs = append(attrs, "err", clipErr(err.Error()))
	}
	p.log.Log(ctx, level, freezeAuditLog, attrs...)
}

// frozenDocs 는 감사 대상을 읽는다.
func (p *Publisher) frozenDocs(ctx context.Context) ([]frozenDoc, error) {
	dctx, cancel := p.stmtCtx(ctx)
	defer cancel()
	rows, err := p.pool.Query(dctx, frozenDocsSQL, frozenAuditWindow, frozenAuditLimit)
	if err != nil {
		return nil, fmt.Errorf("publish: 동결 감사 대상 조회 실패: %w", err)
	}
	defer rows.Close()
	var docs []frozenDoc
	for rows.Next() {
		var d frozenDoc
		if err := rows.Scan(&d.axis, &d.key, &d.stream, &d.session, &d.etag, &d.sha, &d.first, &d.last, &d.discBase); err != nil {
			return nil, fmt.Errorf("publish: 동결 감사 대상 스캔 실패: %w", err)
		}
		docs = append(docs, d)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("publish: 동결 감사 대상 조회 중 오류: %w", err)
	}
	return docs, nil
}

// sha256Bytes 는 body 의 sha256 이다(장부의 bytea 와 견주는 모양).
func sha256Bytes(body []byte) []byte {
	sum := sha256.Sum256(body)
	return sum[:]
}
