package upload

import (
	"bytes"
	"errors"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 이 파일은 복원 결과를 장부 · 표 · 큐에 내는 자리다(설계 r6 2.10 「이미 있는 객체는 검증한 뒤 채택한다」 · 「끝맺음」 · 「구멍
// 하나의 처리」 끝).

// reasonInitUnconfirmed 는 기존 객체가 같은 바이트인데 세션 init 이 아직 확정되지 않아 채택 판정을 미룬 까닭이다.
const reasonInitUnconfirmed = "init_unconfirmed"

// reasonChainMismatch 는 구멍 뒤 uploaded 행 대조 실패로 구멍 행을 failed 로 적는 사유다(세션은 끝내지 않는다).
const reasonChainMismatch = "chain_mismatch"

// verifyExisting 은 구멍 행의 ③ 키에 이미 객체가 있으면(PUT 뒤 CAS 전에 멈춤 — 어느 이미지가 만들었든) 검증한다. 그 객체의
// 도장 범위에서 고른 위치로 현재 확정 ② 를 재포장해 바이트가 같고 init 이 세션 init 과 같으면 채택한다 — 다시 쓰지 않고 ③ CAS
// 만 하고, 그 행의 링크는 객체의 도장이다(다음 행은 거기서 잇는다). 바이트는 같은데 세션 init 이 아직 미확정이면 판정을 미루고
// 객체 도장으로 잇는다(작업이 init 확정 뒤 같은 바이트를 올린다). 다르면 채택하지 않는다 — 접은 위치로 다시 쓴다(CAS 전이라 어느
// 목록에도 실리지 않았다). 꼬리 행 · 원천을 못 읽은 행은 부르는 쪽이 거른다.
func (run *groupRun) verifyExisting(r index.PlaybackChainRow, link playback.Link) (playback.Link, error) {
	_, getter, _ := run.u.restoreDeps()
	ctx, cancel := run.u.s3Ctx()
	_, found, err := getter.Head(ctx, r.PlaybackS3Key)
	cancel()
	if err != nil {
		return link, failRestore("s3_head", err)
	}
	if !found {
		return link, nil
	}
	obj, err := run.getObject(r.PlaybackS3Key)
	switch {
	case errors.Is(err, ErrObjectNotFound):
		return link, nil
	case err != nil:
		return link, err
	}
	adopted, why := run.sameObject(r, link, obj)
	switch why {
	case "":
		return run.adopt(r, link, adopted, int64(len(obj)))
	case reasonInitUnconfirmed:
		// 같은 바이트지만 init 을 아직 견줄 수 없다 — 채택 판정을 미룬다. 그 행은 객체의 도장으로 다시 넣어(CAS 없음) 워커가 init
		// 확정 뒤 같은 바이트로 올리고, 다음 행도 그 도장에서 잇는다(c10b cx 검수 H1 — 접은 도장으로 덮어쓰지 않는다).
		run.lg.Info("chain_object_held", "seq", r.Seq, "reason", why, "pos_ms", adopted.Pos.Milliseconds())
		return adopted, nil
	}
	run.lg.Info("chain_object_rewritten", "seq", r.Seq, "reason", why)
	return link, nil
}

// sameObject 는 객체가 현재 확정 ② 를 그 도장 범위의 위치로 재포장한 것과 바이트로 같은가다. 같으면 그 위치, 아니면 까닭이다.
func (run *groupRun) sameObject(r index.PlaybackChainRow, link playback.Link, obj []byte) (adopted playback.Link, why string) {
	body := run.bodies[r.Seq]
	win, err := playback.StampOf(body, obj)
	if err != nil {
		return link, "samples_differ"
	}
	p, _ := win.Pick(link.Pos)
	out, err := run.u.opt.Producer.Produce(run.u.putCtx, playback.Request{Input: bytes.NewReader(body), PlaybackPos: p})
	if err != nil || !bytes.Equal(out.Seg, obj) {
		return link, "bytes_differ"
	}
	adopted = link
	adopted.Pos = p
	if !link.Pinned {
		adopted.Offset = p - link.Mtxi
	}
	want, known := run.u.sessions.initOf(run.w.sessionID)
	switch {
	case !known:
		return adopted, reasonInitUnconfirmed
	case !bytes.Equal(want, out.InitSHA256[:]):
		return link, "init_differs"
	}
	return adopted, ""
}

// adopt 는 검증한 객체를 ③ CAS 로 확정한다(크기 = 객체 길이 — 지금 markUploadedByAxis 와 같은 CAS). 결과 통지는 워커 성공과
// 같다(Dirty · 보류 사본 지움 · 백오프 해제).
func (run *groupRun) adopt(r index.PlaybackChainRow, link, adopted playback.Link, size int64) (playback.Link, error) {
	ctx, cancel := run.u.dbCtx()
	marked, err := run.u.st.MarkPlaybackUploaded(ctx, run.w.streamID, r.Seq, size)
	cancel()
	if err != nil {
		return link, failRestore("ledger", err)
	}
	if !marked {
		run.lg.Warn("upload_cas_rejected", "seq", r.Seq, "target_state", "uploaded", "origin", "restore")
		return link, nil
	}
	run.adopted[r.Seq] = true
	t := run.target(r, adopted)
	run.u.gate.clearBackoff(targetKeyOf(t))
	run.u.dirty.markUploaded(t.StreamID, t.Axis, t.Seq, t.SessionID)
	run.u.sessions.dropHeld(t.SessionID, t.Seq)
	run.lg.Info("chain_object_adopted", "seq", r.Seq, "pos_ms", adopted.Pos.Milliseconds(),
		"folded_pos_ms", link.Pos.Milliseconds())
	return adopted, nil
}

// target 은 구멍 행의 ③ 작업이다 — 도장은 복원한 링크이고, 로컬 ② 가 없으면 워커가 ② 객체를 입력으로 쓸 키 · 상태를 싣는다.
// 기대 init 은 비운다 — 워커가 sessionInit 을 본다(실시간 작업과 같다).
func (run *groupRun) target(r index.PlaybackChainRow, l playback.Link) index.UploadTarget {
	t := index.UploadTarget{StreamID: run.w.streamID, Axis: index.AxisPlayback, Seq: r.Seq, SessionID: r.SessionID,
		S3Key: r.PlaybackS3Key, LocalPath: r.LocalPath, IsTail: run.isTail(r),
		ArchiveS3Key: r.S3Key, ArchiveUploadState: r.UploadState,
		PlaybackPos: l.Pos, StitchOffset: l.Offset, PosPinned: l.Pinned}
	if r.Bytes != nil {
		t.Bytes = *r.Bytes
	}
	return t
}

// settleMismatch 는 대조 실패한 구멍을 만들지 않는다(설계 r6 2.10 「구멍 뒤 uploaded 행 대조」 · r3 cc-P2): 구멍 행을
// failed(chain_mismatch)로 적고, 그 키에 백오프를 걸어 스위퍼가 30초마다 집지 않게 하고, 같은 입력이면 다시 판정하지 않게
// 기억한다. 표 줄은 채우지 않는다 — 채우면 스위퍼가 그 위치로 ③ 을 만든다. 신호는 판정 한 번에 한 줄이다.
func (run *groupRun) settleMismatch(span holeSpan, fp string, mm mismatch) error {
	for seq := range run.g.seqs {
		i := run.rowIndex(seq)
		if i < 0 {
			continue
		}
		t := run.target(run.rows[i], playback.Link{})
		ctx, cancel := run.u.dbCtx()
		_, err := run.u.st.SettlePlaybackFailure(ctx, t.StreamID, seq, t.SessionID, reasonChainMismatch)
		cancel()
		if err != nil {
			return failRestore("ledger", err)
		}
		run.u.gate.registerFailure(targetKeyOf(t))
	}
	run.u.sessions.recordMismatch(run.w.sessionID, span, fp)
	run.lg.Error("chain_mismatch", "hole_from", span.from, "hole_to", span.to, "anchor_seq", span.anchor,
		"check_seq", mm.seq, "expected_pos_ms", mm.expected.Milliseconds(),
		"object_pos_lo_ms", mm.window.Lo.Milliseconds(), "object_pos_hi_ms", mm.window.Hi.Milliseconds())
	return nil
}

// commit 은 복원 결과를 낸다 — 표 줄(모르는 seq 만 — 실시간이 적은 줄이 정본), 구멍 행 작업(채택 · 지나간 행 제외 — 도장을
// 실은 실시간 경로로), 복원 링크(마지막 링크 — 인덱서가 받는다).
func (run *groupRun) commit(anchor *playback.Link) {
	now := run.u.now()
	run.u.sessions.recordRestored(run.w.sessionID, run.links, now)
	for _, l := range run.links {
		if !run.g.seqs[l.Seq] || run.adopted[l.Seq] || run.passed[l.Seq] {
			continue
		}
		run.u.enqueue(run.target(run.rows[run.rowIndex(l.Seq)], l), OriginLive)
	}
	anchorSeq := int64(-1)
	if anchor != nil {
		anchorSeq = anchor.Seq
	}
	if len(run.links) > 0 {
		last := run.links[len(run.links)-1]
		run.setRestored(last)
	}
	run.lg.Info("chain_restored", "hole_from", run.g.first, "hole_to", run.g.last, "anchor_seq", anchorSeq,
		"links", len(run.links), "exact", run.exact, "waited_at_tail", run.waited, "compared", run.compared)
}

// setRestored 는 복원 링크를 바꾼다 — 지난 링크보다 앞이면 두지 않는다(무리는 seq 순으로 돈다).
func (run *groupRun) setRestored(last playback.Link) {
	if rl, ok := run.u.sessions.restoredLink(run.w.sessionID); ok && rl.link.Seq > last.Seq {
		return
	}
	r := run.rows[run.rowIndex(last.Seq)]
	var size int64
	if r.Bytes != nil {
		size = *r.Bytes
	}
	run.u.sessions.setRestored(run.w.sessionID, restoredLink{link: last, bytes: size, exact: run.exact,
		slackLo: run.slackLo, slackHi: run.slackHi})
}
