package indexer

import (
	"slices"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// 이 파일의 함수는 전부 메인 select 에서만 호출된다(D10). 락이 없는 이유가 그것이다.

// heldTail 은 "아직 올리지 않고 붙들고 있는 꼬리"다.
type heldTail struct {
	seq int64
	// since 는 보류를 시작한 시각이다. correctTail 이 그 행을 고치면 여기서 다시 센다.
	since time.Time
	// nextTry 는 접수가 거부됐을 때의 다음 시도 시각이다. 매 틱 stat 을 막는다.
	nextTry time.Time
	// eligibleAt = cur.Tail.StartWallUTC + TailGrace.
	// 스위퍼 조회(pendingArchiveUploadsSQL·pendingPlaybackUploadsSQL)의 꼬리 예외와 같은 식이다
	// — 다만 시계가 다르다(결정 4⁵). 이 시각을 넘기면 인덱서는 손을 떼고 스위퍼에 넘긴다.
	eligibleAt time.Time
	// playbackTarget 은 이 꼬리의 ③ 작업이다(계획 4.2-R 규칙 ①). 비성장확정 꼬리(Idle·Scan)는 ③ 도
	// ② 와 같은 시점(보류 해제·다음 INSERT 승격·포기)에 한 번만 요청한다 — 유휴 추정 직후에 뽑으면
	// 뒤늦게 써진 마지막 part 가 빠진 채 굳는다(설계 3.1-2). 요청하면 비운다. nil 이면 없다.
	// 보류가 그 밖의 길(재조정·낡음)로 지워질 때도 지우기 전에 요청한다 — releaseHeldPlayback.
	playbackTarget *index.UploadTarget
}

// growthConfirmed 는 "이 사유로 확정된 조각은 더 이상 자라지 않는가"를 묻는다.
// 참이면 즉시 업로드를 요청하고, 거짓이면 붙들었다가(holdTail) 나중에 다시 본다.
//
// 두 사유가 참인 근거는 서로 다르지만 결론이 같다.
//   - ReasonNextFile: 후속 파일이 이미 생겼다. MediaMTX 는 한 스트림에 한 파일만 쓰므로
//     앞 파일은 닫혔다.
//   - ReasonHook: MediaMTX 의 runOnRecordSegmentComplete 가 완성을 **직접 통보**한 것이다.
//     훅 시점의 파일이 이미 최종 크기라는 것은 실측으로 확인됐다(29/29 —
//     recording/name.go 의 ReasonHook 주석). 여기서 보류 경로로 보내면 TailHold(기본 5s)와
//     틱 주기가 더해져, 훅을 붙여 얻은 시간 이득이 통째로 사라진다(ADR-014 완성 즉시 업로드).
//
// 훅이 오탐이면(통보 후 파일이 더 자라면) 안전망은 ReasonNextFile 과 같은 것을 쓴다 —
// 워커의 PUT 전 크기 대조와 bytes CAS. 단 이 안전망은 "잘린 값이 조용히 굳는 것"까지
// 모든 타이밍에서 막아 주지는 못한다: 쓰기가 PUT 내내 멈춰 있다 재개되는 극단 순서면
// 잘린 객체가 uploaded 로 확정되고, 이후 재성장은 regrow_after_upload_ignored ERROR 로
// **관측만** 된다(무징후 아님 — 회귀 식 1 이 굳음 사고로 집계). 그 전제(훅 시점 = 파일
// 최종 크기)는 POK-58 실측 29/29 가 근거이며, 훅을 실제로 켜는 POK-74 에서 실측 산출물로
// 재검증하는 것이 이월 조건이다. 스풀 위조는 스풀 볼륨 쓰기 권한이 전제라 이 분기가 아닌
// 스풀 신뢰 경계(mtxhook)의 문제다.
//
// Idle·Scan 이 여기 없는 이유: 둘 다 "아무도 완성을 통보하지 않았다"는 추정 판정이라
// 파일이 계속 자라는 중일 수 있다.
func growthConfirmed(r recording.CompletionReason) bool {
	return r == recording.ReasonNextFile || r == recording.ReasonHook
}

// noUploader 는 배선이 없을 때 끼우는 기본값이다. 언제나 false 를 돌려주므로
// 인덱서는 아무 상태도 기록하지 않는다 — Disabled 업로더와 같은 계약이다.
type noUploader struct{}

func (noUploader) RequestUpload(index.UploadTarget) bool { return false }

// requestRowUploads 는 INSERT 한 행 뒤의 업로드 요청을 낸다. ②·③·init 은 형제 분기다 — 한 축의
// 거부가 다른 축을 세우지 않는다(설계 3.3·5.5.3). 보류 중이던 직전 꼬리의 승격이 새 행보다 먼저라
// ③ 요청(업로더의 보정값 표 기록)이 seq 순을 지킨다(계획 4.2-R 규칙 ①). init 은 새 행의 ③ 보다
// 먼저 넣는다 — 워커가 하나라 init 확정이 앞서면 그 회차의 ③ 이 대조 보류를 거치지 않는다.
func (ix *Indexer) requestRowUploads(seg recording.Segment, prev, tail *index.TailRow, res index.SeedResult, playbackTarget *index.UploadTarget) {
	now := time.Now()
	// 보류 중이던 직전 꼬리를 승격한다. 더 이상 꼬리가 아니므로 IsTail=false 다 —
	// 워커의 크기 재확인이 이 값으로 갈린다(결정 4⁵).
	if h, ok := ix.held[seg.StreamID]; ok && prev != nil && h.seq == prev.Seq {
		ix.releaseHeldPlayback(seg.StreamID, false)
		if !ix.requestUpload(seg.StreamID, prev, false) {
			ix.holdAfterRejection(seg.StreamID, prev, now)
		}
	}

	// init 은 회차마다 이 프로세스에서 한 번 접수되면 된다 — 그 회차의 첫 행에서 요청하고, 접수가
	// 거부되면(브레이커·큐 포화·백오프) 그 회차의 다음 행에서 다시 요청한다. 개시 행(SessionOpened)이
	// 첫 행이고, 재기동 뒤 이어지는 회차는 개시 행을 옛 프로세스가 처리했으므로 이 프로세스가 처음
	// 보는 행이 첫 행이다 — 장부에 이미 확정된 init 이면 같은 송출 설정·같은 재포장 산출일 때(바뀌었으면
	// Mismatch — README 알려진 한계 표) CAS 가 AlreadySame 으로 업로더의 sessionInit 을
	// 되살린다(없으면 그 회차의 실시간 ③ 이 전부 대조 보류로 새고, 스위퍼 init 벌은 이미 확정된 회차를
	// 집지 않는다).
	if res.SessionID != "" && ix.initAdmitted[seg.StreamID] != res.SessionID {
		if ix.requestInit(seg.StreamID, res.SessionID, tail) {
			ix.initAdmitted[seg.StreamID] = res.SessionID
		}
	}

	// 새 꼬리의 처우. 더 자라지 않는다고 확증된 사유는 즉시 올린다.
	if growthConfirmed(seg.Reason) {
		if !ix.requestUpload(seg.StreamID, tail, true) {
			ix.holdAfterRejection(seg.StreamID, tail, now)
		}
		ix.requestPlayback(playbackTarget, true)
		return
	}
	// Idle·Scan 으로 확정된 꼬리는 아직 자랄 수 있다. 붙들었다가 다음 INSERT 나
	// TailHold 경과 + 크기 일치에서 올린다. ③ 도 같은 시점까지 붙든다(계획 4.2-R 규칙 ①).
	ix.holdTail(seg.StreamID, tail, playbackTarget, now)
}

// requestUpload 는 ② 아카이브 축의 일을 업로더에게 넘기는 유일한 지점이다(③·init 은 형제 함수
// requestPlayback·requestInit 이 넘긴다).
//
// accepted 가 false 면 "아무 일도 없었다"이며 어떤 상태도 기록하지 않는다(결정 6‴).
// Disabled·격리·백오프·브레이커·큐 포화가 전부 이 값으로 온다 — 인덱서에게는 구분이
// 필요 없고, 구분이 쓸모 있는 곳은 스위퍼의 커서 계산뿐이다.
func (ix *Indexer) requestUpload(streamID string, tail *index.TailRow, isTail bool) bool {
	if tail.Bytes <= 0 {
		// bytes 가 없는 행은 CAS 기대값을 만들 수 없어 처리 불가다(결정 14).
		ix.log.Error("upload_target_rejected",
			"stream_id", streamID, "seq", tail.Seq, "reason", "bad_bytes", "path", tail.LocalPath)
		return false
	}
	accepted := ix.upload.RequestUpload(index.UploadTarget{
		StreamID: streamID,
		// 이 요청은 ② 아카이브 축이다. 축을 비우면 영값(미판정)이라 업로더가 거부한다
		// (설계 5.5.4 #4).
		Axis:      index.AxisArchive,
		Seq:       tail.Seq,
		S3Key:     tail.S3Key,
		LocalPath: tail.LocalPath,
		Bytes:     tail.Bytes,
		IsTail:    isTail,
	})
	if accepted {
		ix.requested[streamID] = tail.Seq
		delete(ix.held, streamID)
	}
	return accepted
}

// requestPlayback 은 ③ 작업을 요청한다. nil 이면 이 행에는 ③ 요청이 없다.
//
// 접수 결과는 보지 않는다 — 사슬은 이미 전진했고(계획 4.2-R 규칙 ①) requested 는 ② 전용 부기다.
// 거부된 ③ 은 스위퍼가 회차 보정값 표로 다시 만든다.
func (ix *Indexer) requestPlayback(t *index.UploadTarget, isTail bool) {
	if t == nil {
		return
	}
	req := *t
	req.IsTail = isTail
	ix.upload.RequestUpload(req)
}

// requestInit 은 회차의 init(MAP) 작업을 요청한다 — 이 프로세스가 본 그 회차의 첫 행(방금 들어간
// 꼬리 — 개시 행이거나 재기동 뒤 첫 행)의 조각이 원천이다. 재포장 init 은 같은 송출 설정이면 회차 안 어느 조각에서
// 만들어도 같은 바이트다. S3Key 는 비워 보낸다: init 키는 예약값이 아니라 playback.InitKey 파생이며
// 업로더가 스위퍼 init 행과 같은 자리에서 파생한다(계획 2.1 upload.go 행). Seq 는 init 축에서 쓰지 않는다.
// 돌려주는 값은 접수됐는가다 — 거부면 부르는 쪽이 그 회차의 다음 행에서 다시 요청한다.
func (ix *Indexer) requestInit(streamID, sessionID string, tail *index.TailRow) bool {
	return ix.upload.RequestUpload(index.UploadTarget{
		StreamID: streamID, Axis: index.AxisInit, SessionID: sessionID,
		LocalPath: tail.LocalPath, Bytes: tail.Bytes, IsTail: true,
	})
}

// holdTail 은 꼬리를 붙들어 둔다. ReasonIdle·ReasonScan 으로 확정된 꼬리는 아직 자랄 수
// 있으므로 바로 올리지 않는다(결정 4⁵ · kty 확정 5). 그 행의 ③ 작업(nil 이면 없음)도 함께 붙든다.
func (ix *Indexer) holdTail(streamID string, tail *index.TailRow, playbackTarget *index.UploadTarget, now time.Time) {
	ix.held[streamID] = heldTail{
		seq:            tail.Seq,
		since:          now,
		nextTry:        now,
		eligibleAt:     tail.StartWallUTC.Add(ix.opt.TailGrace),
		playbackTarget: playbackTarget,
	}
	ix.log.Debug("tail_upload_held", "stream_id", streamID, "seq", tail.Seq,
		"eligible_at", tail.StartWallUTC.Add(ix.opt.TailGrace))
}

// releaseHeldPlayback 은 보류 중인 꼬리의 ③ 작업을 한 번 요청하고 보관을 비운다 — 비우지 않으면
// ② 재시도가 같은 ③ 을 다시 보낸다. **② 요청보다 먼저 부른다**: ② 가 접수되면 requestUpload 가
// 보류를 통째로 지워 ③ 이 함께 사라진다. 보류를 지우는 자리(재조정·낡음)도 같은 이유로 먼저 부른다 —
// 그 행이 회차 첫 조각이면 이 요청이 업로더의 보정값 표를 여는 유일한 자리다(없으면 스위퍼도 거부).
func (ix *Indexer) releaseHeldPlayback(streamID string, isTail bool) {
	h, ok := ix.held[streamID]
	if !ok || h.playbackTarget == nil {
		return
	}
	t := h.playbackTarget
	h.playbackTarget = nil
	ix.held[streamID] = h
	ix.requestPlayback(t, isTail)
}

// holdAfterRejection 은 접수가 거부된 꼬리를 붙들어 둔다.
// held 를 지우지 않고 다음 시도 시각만 미룬다 — 지우면 그 행은 스위퍼가 집을 때까지
// 최대 SweepEvery 만큼 늦어진다.
func (ix *Indexer) holdAfterRejection(streamID string, tail *index.TailRow, now time.Time) {
	h, ok := ix.held[streamID]
	if !ok || h.seq != tail.Seq {
		h = heldTail{seq: tail.Seq, since: now, eligibleAt: tail.StartWallUTC.Add(ix.opt.TailGrace)}
	}
	h.nextTry = now.Add(ix.opt.TailHold)
	ix.held[streamID] = h
}

// ReleaseHeldTails 는 보류가 풀린 꼬리를 올린다. 메인 루프가 HoldTick 마다 부른다.
//
// 틱당 os.Stat 예산을 HoldStatBudget 개로 제한하고 since 오름차순으로 처리한다 —
// Go 맵 순회는 무작위라 정렬 없이는 라운드로빈이 성립하지 않아, 운 나쁜 스트림이
// 계속 뒤로 밀릴 수 있다.
func (ix *Indexer) ReleaseHeldTails() {
	if len(ix.held) == 0 {
		return
	}
	now := time.Now()

	type entry struct {
		streamID string
		h        heldTail
	}
	due := make([]entry, 0, len(ix.held))
	for streamID, h := range ix.held {
		if now.Before(h.nextTry) {
			continue
		}
		due = append(due, entry{streamID, h})
	}
	slices.SortFunc(due, func(a, b entry) int {
		if c := a.h.since.Compare(b.h.since); c != 0 {
			return c
		}
		// 같은 시각이면 이름순으로 고정한다. 순서가 흔들리면 예산 배분도 흔들린다.
		return compareString(a.streamID, b.streamID)
	})

	budget := ix.opt.HoldStatBudget
	for _, e := range due {
		if budget <= 0 {
			return
		}
		budget--
		ix.releaseHeldTail(e.streamID, e.h, now)
	}
}

func compareString(a, b string) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}

func (ix *Indexer) releaseHeldTail(streamID string, h heldTail, now time.Time) {
	cur := ix.cursors[streamID]
	if cur == nil || cur.Tail == nil || cur.Tail.Seq != h.seq {
		// 커서가 앞서갔다 = 이 보류는 이미 의미가 없다. 그 행은 닫혔으니 붙든 ③ 은 비꼬리로 보낸다.
		ix.log.Debug("held_tail_stale", "stream_id", streamID, "held_seq", h.seq)
		ix.releaseHeldPlayback(streamID, false)
		delete(ix.held, streamID)
		return
	}
	// 포기 조건은 시각 하나다. 이 시각을 넘기면 스위퍼의 꼬리 예외가 그 행을 집는다.
	// ③ 은 여기서 한 번 요청한다 — 실시간 요청이 있어야 업로더가 그 행을 회차 보정값 표에 적는다.
	if !now.Before(h.eligibleAt) {
		ix.log.Debug("tail_upload_hold_abandoned",
			"stream_id", streamID, "seq", h.seq, "eligible_at", h.eligibleAt)
		ix.releaseHeldPlayback(streamID, true)
		delete(ix.held, streamID)
		return
	}
	// TailHold 만큼은 무조건 기다린다. 그 시간이 "더 안 자란다"는 근거다.
	if now.Sub(h.since) < ix.opt.TailHold {
		return
	}

	fi, err := ix.statT(cur.Tail.LocalPath, "hold_release")
	if err != nil {
		// 판정 재료가 없으면 판정하지 않는다. 다음 틱에 다시 본다.
		h.nextTry = now.Add(ix.opt.TailHold)
		ix.held[streamID] = h
		return
	}
	if fi.Size() != cur.Tail.Bytes {
		// 아직 자라는 중이다. 크기가 장부와 같아질 때까지 미룬다.
		ix.log.Debug("tail_upload_hold_extended", "stream_id", streamID, "seq", h.seq,
			"waited", now.Sub(h.since), "next_try", now.Add(ix.opt.TailHold))
		h.nextTry = now.Add(ix.opt.TailHold)
		ix.held[streamID] = h
		return
	}

	ix.releaseHeldPlayback(streamID, true)
	if ix.requestUpload(streamID, cur.Tail, true) {
		ix.log.Debug("tail_upload_released", "stream_id", streamID, "seq", h.seq,
			"waited", now.Sub(h.since))
		return
	}
	ix.holdAfterRejection(streamID, cur.Tail, now)
}

// ApplyUploadResult 는 업로더의 통지를 커서에 반영한다.
//
// 그 행이 아직 꼬리일 때만 갱신한다 — 이미 다음 행이 들어왔다면 그 커서는 다른 행의
// 것이고, 거기에 남의 상태를 쓰면 correctTail 의 확인 2 가 엉뚱한 판정을 한다.
func (ix *Indexer) ApplyUploadResult(streamID string, seq int64, state index.UploadState) {
	if cur, ok := ix.cursors[streamID]; ok && cur != nil && cur.Tail != nil && cur.Tail.Seq == seq {
		cur.Tail.UploadState = state
	}
	if got, ok := ix.requested[streamID]; ok && got == seq {
		delete(ix.requested, streamID)
	}
}

// reconcileUploadState 는 커서를 재적재한 뒤 메모리 상태를 새 커서에 맞춘다.
//
// 커서가 바뀌면 옛 seq 를 가리키는 requested·held 는 전부 의미를 잃는다. 남겨 두면
// D13 교정(확인 2.5)과 보류 판정이 엉뚱한 행을 근거로 돌아간다. 보류에 붙든 ③ 작업은 지우기 전에
// 요청한다(규칙 ① — 한 행의 ③ 요청은 보류가 어느 길로 사라지든 1회). 그 행은 ② 가 이미 확정됐거나
// 커서가 떠났으니 닫혔다(IsTail=false).
func (ix *Indexer) reconcileUploadState(streamID string) {
	cur := ix.cursors[streamID]
	if cur == nil || cur.Tail == nil || cur.Tail.UploadState != index.UploadStatePending {
		// 꼬리가 없거나 이미 확정된 행이면 인덱서가 할 일이 없다.
		ix.releaseHeldPlayback(streamID, false)
		delete(ix.held, streamID)
		delete(ix.requested, streamID)
		return
	}
	if h, ok := ix.held[streamID]; ok && h.seq != cur.Tail.Seq {
		ix.releaseHeldPlayback(streamID, false)
		delete(ix.held, streamID)
	}
	if seq, ok := ix.requested[streamID]; ok && seq != cur.Tail.Seq {
		delete(ix.requested, streamID)
	}
}

// tailGrew 는 "실제로 자랐는가"를 묻는 공통 게이트다.
// 확인 2·확인 2.5·recoverTail 이 같은 질문을 하므로 한 곳에 둔다.
func (ix *Indexer) tailGrew(tail *index.TailRow) (fileBytes int64, grew bool, statErr error) {
	fi, err := ix.statT(tail.LocalPath, "tail_grew")
	if err != nil {
		return 0, false, err
	}
	return fi.Size(), fi.Size() > tail.Bytes, nil
}
