package indexer

// ③ 사슬 모름 상태(E18 — POK-196 PDT 계획 4절 c10b · 설계 r6 2.10 「인덱서 쪽 — 사슬 모름 상태」).
//
// 사슬은 앎 · 모름 두 상태다. 모름이 되는 자리는 둘이다 — 재기동 뒤 이미 행이 있는 회차의 첫 행(이 프로세스가 그 회차의
// 시작을 못 봤다 — 지금까지는 offset 0 으로 다시 열어 겹침 · 틈을 냈다)과, 판독 포기 행(c5 (ㄱ) 상한 뒤 칸 NULL 커밋 — 일시
// 실패라 고정하지 않는다). 모름이면 그 행은 도장을 짓지 않고 위치 모름(PosUnknown)으로 요청한다 — 업로더가 장부와 S3 로 사슬을
// 되살린다(인덱서 루프는 S3 를 부르지 않는다). 다음 행마다 업로더의 복원 링크를 비차단으로 읽어, 그 링크가 모르는 마지막 행에
// 닿았으면 그 링크를 사슬로 받는다. 입력 상한 초과는 결정적이라 모름이 아니다(지금처럼 기대값 고정 — 복원도 같은 규칙).
//
// r6 의 셋째 자리(꼬리에서 물러나는 사슬 행의 축 끝 모름)는 r7 에 없다 — 영상 끝은 장부 칸이고 교정은 옛 값이 있을 때만 값을
// 바꾸므로(withTailCorrection) 사슬 행의 끝이 사라지는 길이 없다.

import (
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// UploadRequester 는 인덱서가 업로더에게 일을 넘기고 복원 링크를 읽는 유일한 통로다.
//
// 인터페이스를 쓰는 쪽인 여기에 둔 이유는 Adopter 와 같다. *upload.Uploader 가 그대로
// 만족하며, 그래서 indexer 는 upload 패키지를 임포트하지 않는다 — "이 조각이 지금 올릴
// 대상인가"의 판정(여기)과 "어떻게 올리는가"의 정책(거기)은 변경 이유가 다르다.
//
// 반드시 논블로킹이어야 한다. 여기서 막히면 D10 메인 루프가 통째로 선다.
type UploadRequester interface {
	RequestUpload(t index.UploadTarget) (accepted bool)
	// RestoredLink 는 그 회차의 ③ 사슬 복원이 마지막으로 정한 링크다(없으면 거짓). 뮤텍스 아래 맵 읽기 하나다.
	RestoredLink(sessionID string) (playback.Link, bool)
}

// 사슬을 모르게 된 까닭(chain_unknown 의 reason)이다.
const (
	chainUnknownRestart    = "restart"
	chainUnknownReadFailed = "read_failed"
)

// markUnknown 은 이 행에서 사슬을 모르게 된다 — 신호 chain_unknown 한 줄(모름이 시작된 행에서만).
func (ix *Indexer) markUnknown(streamID string, rec index.Record, res index.InsertCommit, reason string) *index.UploadTarget {
	c := playbackChain{link: playback.Link{Seq: rec.Seq, SessionID: res.SessionID}, path: rec.LocalPath, pdt: res.PlaybackPDT,
		unknown: true, unknownFrom: rec.Seq}
	ix.chains[streamID] = c
	ix.log.Info("chain_unknown", "stream_id", streamID, "session_id", res.SessionID, "seq", rec.Seq, "reason", reason)
	return playbackTargetOf(streamID, c, rec, res)
}

// stayUnknown 은 모름 상태로 한 행 더 간다 — 모르는 마지막 행이 이 행이 된다(복원 링크가 닿아야 할 자리).
func (ix *Indexer) stayUnknown(streamID string, c playbackChain, rec index.Record, res index.InsertCommit) *index.UploadTarget {
	c.link.Seq, c.path, c.pdt = rec.Seq, rec.LocalPath, res.PlaybackPDT
	ix.chains[streamID] = c
	return playbackTargetOf(streamID, c, rec, res)
}

// resumeChain 은 복원 링크가 모르는 마지막 행에 닿았으면 그 링크를 사슬로 받는다(앎으로 돌아온다 — 신호 chain_resumed). 링크가
// 앞 행이면(복원이 늦다) 모름 그대로다 — 업로더는 위치 모름 요청마다 다시 깨어 따라잡는다. 받은 링크의 길이 · 영상 끝은 그 행이
// 꼬리에서 물러나는 이 자리의 교정 값으로 다시 덮인다(부르는 쪽의 인접 처리 — 실시간과 같다).
func (ix *Indexer) resumeChain(streamID string, c playbackChain) playbackChain {
	l, ok := ix.upload.RestoredLink(c.link.SessionID)
	if !ok || l.Seq != c.link.Seq || l.SessionID != c.link.SessionID {
		return c
	}
	ix.log.Info("chain_resumed", "stream_id", streamID, "session_id", l.SessionID, "seq", l.Seq,
		"unknown_rows", l.Seq-c.unknownFrom+1)
	known := playbackChain{link: l, path: c.path, pdt: c.pdt}
	ix.chains[streamID] = known
	return known
}
