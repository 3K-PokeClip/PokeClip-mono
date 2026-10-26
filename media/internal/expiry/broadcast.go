package expiry

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 묶음 크기 — S3 DeleteObjects 한 요청의 키 상한(1000)과 장부 한 트랜잭션의 행 상한(index.ExpiryDeleteBatch).
const objectBatch = 1000

// ingestKeyRe 는 물리 키 성분 규칙이다(playback 키 성분과 같은 글자 · 64자 — 설계 7절 단계 2 M2).
var ingestKeyRe = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

// validIngestKey 는 ingestStreamId 가 장부의 물리 키로 쓸 수 있는 값인가다. 비면 clip 이 방송 번호(`S-` 회차 좌표)로
// 채우므로 그 꼴도 거부한다 — 장부에서 찾을 수 없는 방송이다(D28).
func validIngestKey(s string) bool {
	return ingestKeyRe.MatchString(s) && s != "." && s != ".." && !strings.HasPrefix(s, "S-")
}

// process 는 방송 하나를 지운다. 실패하면 재시도 집합에 넣는다. 키를 모르는 방송과 상한이 보존 기간 안인 방송은
// 넣지 않는다 — 다시 해도 같은 판정이다.
func (e *Expirer) process(ctx context.Context, b Broadcast) {
	if !validIngestKey(b.IngestStreamID) || b.SegmentsUntil == nil {
		e.log.Warn("expiry_skipped", "reason", "no_ingest_key", "broadcast", b.StreamID,
			"note", "물리 키나 구간 상한이 없다 — 장부에서 찾을 수 없는 방송이라 건너뛴다")
		return
	}
	if ceiling := index.ExpiryCeiling(e.now()); b.SegmentsUntil.After(ceiling) {
		e.log.Error("expiry_until_rejected", "stream_id", b.IngestStreamID, "broadcast", b.StreamID,
			"until", b.SegmentsUntil.UTC(), "ceiling", ceiling.UTC(),
			"note", "구간 상한이 보존 기간 안이다 — clip 응답을 믿고 지우지 않는다(fail-closed)")
		return
	}
	stage, err := e.deleteBroadcast(ctx, b)
	if err == nil {
		return
	}
	e.retry[b.StreamID] = b // 방송 식별자가 키다 — 같은 물리 키의 다른 방송을 덮지 않는다(D22)
	if ctx.Err() != nil {
		return // 종료 신호 — 다음 기동의 처음부터 훑기가 다시 한다(D24)
	}
	if errors.Is(err, errCutoffInvalid) || errors.Is(err, index.ErrCutoffInvalid) {
		return // 경보는 cutoff_invalid 게이지가 켜질 때 · 꺼질 때 한 줄씩 냈다 — 회차마다 되풀이하지 않는다
	}
	e.log.Error("expiry_delete_failed", "stage", stage, "stream_id", b.IngestStreamID, "broadcast", b.StreamID, "err", err)
}

// errCutoffInvalid 는 1단계에서 멈췄다는 표지다(경보는 이미 냈다).
var errCutoffInvalid = errors.New("무효 컷오프 — 객체를 지우지 않는다")

// deleteBroadcast 는 한 방송의 다섯 걸음이다. 실패한 걸음 이름과 오류를 돌려준다.
func (e *Expirer) deleteBroadcast(ctx context.Context, b Broadcast) (string, error) {
	q := index.ExpiryQuery{StreamID: b.IngestStreamID, From: b.SegmentsFrom, Until: *b.SegmentsUntil}
	if _, err := e.ledger.MarkExpired(ctx, q); err != nil {
		return "mark", err
	}
	e.opt.Changed(q.StreamID) // 0단계 커밋 뒤 — 뒤 걸음이 실패해도 조회 제외는 상태 전달에 닿는다(D16)
	valid, err := e.ledger.CutoffValid(ctx, q.StreamID)
	if err != nil {
		return "cutoff", err
	}
	e.signalCutoff(q.StreamID, valid)
	if !valid {
		return "cutoff", errCutoffInvalid
	}
	rows, err := e.ledger.Rows(ctx, q)
	if err != nil {
		return "rows", err
	}
	targets := e.targets(q.StreamID, rows)
	docs, err := e.ledger.Documents(ctx, q)
	if err != nil {
		return "documents", err
	}
	if err := e.deleteDocuments(ctx, docs); err != nil {
		return "documents", err
	}
	seqs, keyErr := e.deleteSegmentKeys(ctx, targets)
	if ctx.Err() != nil {
		return "segments", ctx.Err() // 취소된 회차는 행을 지우지 않는다(D24)
	}
	res, err := e.deleteRows(ctx, q.StreamID, seqs)
	e.logDeleted(q.StreamID, b.StreamID, res, len(docs), len(seqs))
	if err != nil {
		return "rows", err
	}
	if keyErr != nil {
		return "segments", keyErr
	}
	return "", nil
}

// signalCutoff 는 cutoff_invalid axis=expiry 게이지를 스트림별로 맞춘다 — 켜질 때 · 꺼질 때 한 줄씩(e2).
func (e *Expirer) signalCutoff(stream string, valid bool) {
	switch {
	case !valid && !e.invalid[stream]:
		e.invalid[stream] = true
		e.log.Error("cutoff_invalid", "axis", "expiry", "stream_id", stream, "value", 1,
			"note", "컷오프가 가리키는 행이 장부에 없다 — 이 스트림의 만료 삭제를 멈춘다")
	case valid && e.invalid[stream]:
		delete(e.invalid, stream)
		e.log.Error("cutoff_invalid", "axis", "expiry", "stream_id", stream, "value", 0)
	}
}

// targets 는 대상 행만 고르고, 건너뛴 행은 사유별로 WARN 한 줄씩 낸다.
func (e *Expirer) targets(stream string, rows []index.ExpiryRow) []index.ExpiryRow {
	out := make([]index.ExpiryRow, 0, len(rows))
	skipped := map[string]int{}
	for _, r := range rows {
		if r.Skip != "" {
			skipped[r.Skip]++
			continue
		}
		out = append(out, r)
	}
	for reason, n := range skipped {
		e.log.Warn("expiry_skipped", "reason", reason, "stream_id", stream, "rows", n)
	}
	return out
}

// deleteDocuments 는 문서를 종류마다 따로 지운다(docs 는 지우는 순서 — dvr → vod → init). 한 종류에서 하나라도
// 실패하면 뒤 종류는 지우지 않는다 — 남은 재생 목록이 지워진 init 을 가리키면 MAP 404 다(설계 7절 단계 3).
func (e *Expirer) deleteDocuments(ctx context.Context, docs []index.ExpiryDocument) error {
	for start := 0; start < len(docs); {
		end := start + 1
		for end < len(docs) && docs[end].Kind == docs[start].Kind {
			end++
		}
		keys := make([]string, 0, end-start)
		for _, d := range docs[start:end] {
			keys = append(keys, d.Key)
		}
		if failed, err := e.deleteKeys(ctx, keys); err != nil || len(failed) > 0 {
			return failedErr(err, failed)
		}
		start = end
	}
	return nil
}

// deleteSegmentKeys 는 대상 행의 두 키(s3_key · playback_s3_key)를 지우고, 두 키가 다 지워진 행의 seq 를 돌려준다.
// 일부 실패는 오류로 함께 돌려준다 — 그 행은 다음 회차에 다시 본다.
func (e *Expirer) deleteSegmentKeys(ctx context.Context, rows []index.ExpiryRow) ([]int64, error) {
	keys := make([]string, 0, 2*len(rows))
	for _, r := range rows {
		keys = append(keys, r.S3Key)
		if r.PlaybackS3Key != "" {
			keys = append(keys, r.PlaybackS3Key)
		}
	}
	failed, err := e.deleteKeys(ctx, keys)
	seqs := make([]int64, 0, len(rows))
	for _, r := range rows {
		if _, bad := failed[r.S3Key]; bad {
			continue
		}
		if _, bad := failed[r.PlaybackS3Key]; bad && r.PlaybackS3Key != "" {
			continue
		}
		seqs = append(seqs, r.Seq)
	}
	if err != nil || len(failed) > 0 {
		return seqs, failedErr(err, failed)
	}
	return seqs, nil
}

// deleteKeys 는 키를 objectBatch 단위로 지운다. 요청 자체가 실패하면 그 묶음과 뒤 묶음 키를 모두 실패로 친다.
func (e *Expirer) deleteKeys(ctx context.Context, keys []string) (map[string]error, error) {
	failed := map[string]error{}
	for start := 0; start < len(keys); start += objectBatch {
		batch := keys[start:min(start+objectBatch, len(keys))]
		bad, err := e.objects.Delete(ctx, batch)
		if err != nil {
			for _, k := range keys[start:] {
				failed[k] = err
			}
			return failed, err
		}
		for k, v := range bad {
			failed[k] = v
		}
	}
	return failed, nil
}

// deleteRows 는 seq 를 index.ExpiryDeleteBatch 단위 트랜잭션으로 지운다. 결과는 묶음 합이다.
func (e *Expirer) deleteRows(ctx context.Context, stream string, seqs []int64) (index.DeleteResult, error) {
	var total index.DeleteResult
	for start := 0; start < len(seqs); start += index.ExpiryDeleteBatch {
		res, err := e.ledger.DeleteRows(ctx, stream, seqs[start:min(start+index.ExpiryDeleteBatch, len(seqs))])
		if err != nil {
			if errors.Is(err, index.ErrCutoffInvalid) {
				e.signalCutoff(stream, false)
			}
			return total, err
		}
		total = addResult(total, res)
		e.logCutoff(stream, res)
	}
	return total, nil
}

func addResult(a, b index.DeleteResult) index.DeleteResult {
	a.RowsDeleted += b.RowsDeleted
	a.GapsDeleted += b.GapsDeleted
	a.OutboxDeleted += b.OutboxDeleted
	a.SessionsDeleted = append(a.SessionsDeleted, b.SessionsDeleted...)
	if b.Cutoff != index.CutoffUnchanged {
		a.Cutoff, a.CutoffSeq = b.Cutoff, b.CutoffSeq
	}
	return a
}

// logCutoff 는 컷오프 전진 · 제거를 WARN 한 줄로 낸다(설계 8절).
func (e *Expirer) logCutoff(stream string, res index.DeleteResult) {
	switch res.Cutoff {
	case index.CutoffAdvanced:
		e.log.Warn("expiry_cutoff_moved", "stream_id", stream, "to", res.CutoffSeq)
	case index.CutoffRemoved:
		e.log.Warn("expiry_cutoff_removed", "stream_id", stream,
			"note", "뒤에 자격 행이 없어 컷오프를 지웠다 — 새 방송 첫 자격 조각이 다시 주조한다")
	}
}

// logDeleted 는 한 방송의 결과를 INFO 한 줄로 낸다.
func (e *Expirer) logDeleted(stream, broadcast string, res index.DeleteResult, docs, rows int) {
	e.log.Info("expiry_broadcast_deleted", "stream_id", stream, "broadcast", broadcast, "rows", res.RowsDeleted,
		"documents", docs, "target_rows", rows, "gaps", res.GapsDeleted, "sessions", len(res.SessionsDeleted),
		"outbox", res.OutboxDeleted)
}

// failedErr 은 요청 오류 또는 키별 실패 하나를 오류로 만든다.
func failedErr(err error, failed map[string]error) error {
	if err != nil {
		return err
	}
	for k, v := range failed {
		return fmt.Errorf("객체 삭제 실패 %d키(예 key=%s): %w", len(failed), k, v)
	}
	return nil
}
