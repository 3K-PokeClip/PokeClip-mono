package publish

// 동결 감사 — D17 4.1(b) · M6 설계 6절 동결 행(POK-196 M6-2 커밋 ③). 30초 감시 주기(판정부 · M6-3b 배선)에 부른다.

import (
	"bytes"
	"context"
	"crypto/sha256"
	"fmt"
	"log/slog"
	"time"
)

// freezeAuditLog 는 동결 감사의 신호 키다 — 바뀌었거나(mismatch) 없는(missing) 동결 문서는 ERROR, 읽지 못한 것(read_failed)
// 은 WARN 이다. 속성은 axis · stream · session · key · reason(· err)이다.
const freezeAuditLog = "session_freeze_audit"

// 감사 설계값 — D17 4.1(b) FROZEN_AUDIT_WINDOW(30분)와 한 번에 보는 문서 수 상한이다.
const (
	frozenAuditWindow = 30 * time.Minute
	frozenAuditLimit  = 100
)

// frozenDocsSQL 은 감사 대상이다 — 문서가 있는 동결(DVR frozen · VOD frozen · frozen_with_gaps) 가운데 장부 시각
// COALESCE(ended_at, dvr_frozen_at) 이 감사 창 $1 안인 것. VOD 는 사슬 세션마다 같은 키라 키로 한 번만 고른다.
const frozenDocsSQL = `
SELECT DISTINCT ON (key) axis, key, stream_id, session_id, etag, sha
  FROM (SELECT 'dvr' AS axis, dvr_s3_key AS key, stream_id, session_id,
               COALESCE(dvr_etag, '') AS etag, dvr_sha256 AS sha
          FROM stream_sessions
         WHERE dvr_state = 'frozen' AND dvr_s3_key IS NOT NULL
           AND COALESCE(ended_at, dvr_frozen_at) > pg_catalog.now() - $1::interval
        UNION ALL
        SELECT 'vod', vod_s3_key, stream_id, session_id, COALESCE(vod_etag, ''), vod_sha256
          FROM stream_sessions
         WHERE vod_state IN ('frozen', 'frozen_with_gaps') AND vod_s3_key IS NOT NULL
           AND COALESCE(ended_at, dvr_frozen_at) > pg_catalog.now() - $1::interval) AS d
 ORDER BY key, session_id
 LIMIT $2`

// AuditOutcome 은 감사 한 번의 결과다 — 본 문서 수 · 바뀌었거나 없는 문서 수 · 대상 읽기 실패다.
type AuditOutcome struct {
	Checked, Failed int
	Err             error
}

// frozenDoc 은 감사 대상 문서 하나다.
type frozenDoc struct {
	axis, key, stream, session, etag string
	sha                              []byte
}

// AuditFrozen 은 동결 감사다(D17 4.1(b)). 감사 창 안의 동결 문서마다 1차 Head 의 ETag 를 장부의 ETag 와 견주고, 다르면
// 2차 Get 본문의 sha256 을 장부의 sha 와 견준다(ETag 는 1차 거름일 뿐이다). 그래도 다르거나 객체가 없으면 ERROR 다.
//
// 다시 쓰지 않는다(D17 의 3차 「재렌더 → 재PUT」 은 두지 않는다). VOD 는 자동 재동결을 지원하지 않고(설계 10절 IfAbsent
// VOD 오기록 복구 — 런북 몫), 닫힌 DVR 목록도 사람이 원인을 본 뒤 고친다.
func (p *Publisher) AuditFrozen(ctx context.Context) AuditOutcome {
	docs, err := p.frozenDocs(ctx)
	if err != nil {
		return AuditOutcome{Err: err}
	}
	out := AuditOutcome{Checked: len(docs)}
	for _, d := range docs {
		if reason, err := p.auditDoc(ctx, d); reason != "" {
			p.reportAudit(ctx, d, reason, err)
			if reason != auditReadFailed {
				out.Failed++
			}
		}
	}
	return out
}

// 감사 사유 — 신호의 reason 속성 값이다.
const (
	auditMissing    = "missing"
	auditMismatch   = "mismatch"
	auditReadFailed = "read_failed"
)

// auditDoc 은 문서 d 하나를 본다 — 통과면 "" 다.
func (p *Publisher) auditDoc(ctx context.Context, d frozenDoc) (string, error) {
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
	body, err := p.store.Get(gctx, d.key)
	cancel()
	if err != nil {
		return auditReadFailed, err
	}
	if sum := sha256.Sum256(body); !bytes.Equal(sum[:], d.sha) {
		return auditMismatch, nil
	}
	return "", nil
}

// reportAudit 은 감사 실패 한 줄을 남긴다 — 읽기 실패는 WARN, 나머지는 ERROR 다.
func (p *Publisher) reportAudit(ctx context.Context, d frozenDoc, reason string, err error) {
	level := slog.LevelError
	if reason == auditReadFailed {
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
		if err := rows.Scan(&d.axis, &d.key, &d.stream, &d.session, &d.etag, &d.sha); err != nil {
			return nil, fmt.Errorf("publish: 동결 감사 대상 스캔 실패: %w", err)
		}
		docs = append(docs, d)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("publish: 동결 감사 대상 조회 중 오류: %w", err)
	}
	return docs, nil
}
