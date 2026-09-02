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
	// pendingUploadsSQL 의 꼬리 예외와 같은 식이다 — 다만 시계가 다르다(결정 4⁵).
	// 이 시각을 넘기면 인덱서는 손을 떼고 스위퍼에 넘긴다.
	eligibleAt time.Time
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

// requestUpload 는 업로더에게 일을 넘기는 유일한 지점이다.
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
		// 실시간 유입은 ② 아카이브 축이다. 축을 비우면 영값(미판정)이라 업로더가 거부한다
		// (③·init 축의 생산자는 M4 다 — POK-195 M3 설계 5.5.4 #4).
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

// holdTail 은 꼬리를 붙들어 둔다. ReasonIdle·ReasonScan 으로 확정된 꼬리는 아직 자랄 수
// 있으므로 바로 올리지 않는다(결정 4⁵ · kty 확정 5).
func (ix *Indexer) holdTail(streamID string, tail *index.TailRow, now time.Time) {
	ix.held[streamID] = heldTail{
		seq:        tail.Seq,
		since:      now,
		nextTry:    now,
		eligibleAt: tail.StartWallUTC.Add(ix.opt.TailGrace),
	}
	ix.log.Debug("tail_upload_held", "stream_id", streamID, "seq", tail.Seq,
		"eligible_at", tail.StartWallUTC.Add(ix.opt.TailGrace))
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
		// 커서가 앞서갔다 = 이 보류는 이미 의미가 없다.
		ix.log.Debug("held_tail_stale", "stream_id", streamID, "held_seq", h.seq)
		delete(ix.held, streamID)
		return
	}
	// 포기 조건은 시각 하나다. 이 시각을 넘기면 스위퍼의 꼬리 예외가 그 행을 집는다.
	if !now.Before(h.eligibleAt) {
		ix.log.Debug("tail_upload_hold_abandoned",
			"stream_id", streamID, "seq", h.seq, "eligible_at", h.eligibleAt)
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
// D13 교정(확인 2.5)과 보류 판정이 엉뚱한 행을 근거로 돌아간다.
func (ix *Indexer) reconcileUploadState(streamID string) {
	cur := ix.cursors[streamID]
	if cur == nil || cur.Tail == nil || cur.Tail.UploadState != index.UploadStatePending {
		// 꼬리가 없거나 이미 확정된 행이면 인덱서가 할 일이 없다.
		delete(ix.held, streamID)
		delete(ix.requested, streamID)
		return
	}
	if h, ok := ix.held[streamID]; ok && h.seq != cur.Tail.Seq {
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
