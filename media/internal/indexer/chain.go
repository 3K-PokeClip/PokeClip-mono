package indexer

// ③ 시간 도장 사슬(계획 4.2-R R3 — kty 결정 B′·G). 조각 k 의 도장은 pos(k) = mtxi(k) + offset 이다.
// 평소 offset 은 0 이고, 회차 안에서 mtxi 가 리셋되거나(같은 녹화기 이어짐이 아님 ∨ 역행) 같은 녹화기
// 안에 구멍이 나면 직전 조각 끝에 이어 붙이도록 바뀐다. 이어짐 판정은 장부의 원래 사실 칸 위의
// index.SameInstance 이고 리셋 · 구멍은 영상 축에서 잰다(POK-196 PDT 계획 4절 c8 · ADR-073 결정 2 개정).
//
// 이 파일이 루프 쪽 전부다 — 보관값(사슬)을 전진시키고 그 행의 ③ 작업 메타를 만든다. 한 걸음의 산식(리셋 · 구멍 ·
// 고정)은 playback.NextLink 하나이고 복원(playback.RestoreChain)도 그것을 접는다(POK-196 PDT 계획 4절 c10a) — 여기는 인접
// 판정 · 꼬리 교정 값 덮기 · 회차 전환 · 앞 행 파일 재판독(이음 끝) · skew 신호다. 회차별 보정값 표(스위퍼 재생성용)는
// 업로더 소유이고, 루프는 작업에 메타를 실어 보낼 뿐이다.

import (
	"errors"
	"fmt"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// playbackChain 은 스트림 하나의 ③ 도장 보관값이다 — 사슬이 마지막으로 도장을 찍은 회차 귀속 행의 링크(술어 재료 ·
// 도장 · 보정값)와, 그 링크 밖에서 루프만 쓰는 재료. 루프(D10) 한 goroutine 만 만진다.
type playbackChain struct {
	// link 는 playback.NextLink 의 입출력이다(실시간과 복원이 한 코드 — POK-196 PDT 계획 4절 c10a). 판독에 실패한 행이면
	// 기대값(같은 녹화기의 다음 번호 · 직전 mtxi + 장부 길이 · 영상 칸 없음)이다(④ⓐ). 길이는 다음 행 INSERT 때 꼬리 교정
	// 뒤 값으로 확정된다(②).
	link playback.Link
	// path 는 리셋을 이을 때 트랙 끝을 다시 읽을 녹화 파일이다.
	path string
	// pdt 는 playback_pdt 다 — G7 skew 재료(부기 30). 영값이면 NULL 이다.
	pdt time.Time
	// unknown 은 사슬을 모르는 상태다(POK-196 PDT 계획 4절 c10b — chain_restore.go). link 에는 모르는 마지막 행의 seq · 회차만
	// 있고, unknownFrom 은 모름이 시작된 행이다.
	unknown     bool
	unknownFrom int64
	// awaiting 은 이 프로세스가 회차를 연 행을 봤으나 mtxi 가 없어 아직 도장을 못 연 상태다(④ⓑ) — 다음 정상 판독 행이 연다.
	awaiting bool
}

// advanceChain 은 장부 행 하나만큼 사슬을 전진시키고 그 행의 ③ 작업을 만든다(계획 4.2-R 규칙 ①).
// 작업이 nil 이면 이 행에는 ③ 요청이 없다 — 비귀속 행(⑥)이거나, 회차의 보관값 없이 mtxi 를 못 읽은
// 행(④ⓑ)이다. pinned 는 판독 실패로 도장을 기대값에 고정했는가다(④ⓐ). 사슬을 모르는 행이면 위치 모름 작업이다(chain_restore.go).
//
// 접수 게이트보다 앞에서 부른다 — 요청이 거부돼도 사슬은 이어져야 다음 행이 리셋을 오판하지 않는다.
func (ix *Indexer) advanceChain(streamID string, prev *index.TailRow, rec index.Record, res index.InsertCommit, read timingRead) (target *index.UploadTarget, pinned bool) {
	c, hasChain := ix.chains[streamID]
	if hasChain && c.unknown && c.link.SessionID == res.SessionID {
		c = ix.resumeChain(streamID, c)
	}
	// adjacent 는 직전 장부 행이 곧 사슬 행인가다 — 아니면 사이에 비귀속 행이 끼었다(⑥). 모름 · 연 행 대기 상태에는 사슬 행이 없다.
	adjacent := hasChain && !c.unknown && !c.awaiting && prev != nil && prev.Seq == c.link.Seq
	if adjacent {
		c.link = withTailCorrection(c.link, prev)
		ix.chains[streamID] = c
	}
	if res.SessionID == "" {
		return nil, false
	}
	src := sourceOf(rec, res.SessionID, res.DurationMS, read.mtxi())
	sameSession := hasChain && c.link.SessionID == res.SessionID
	switch {
	case sameSession && c.unknown:
		return ix.stayUnknown(streamID, c, rec, res), false
	case read.failure == partialReadFailed:
		return ix.markUnknown(streamID, rec, res, chainUnknownReadFailed), false
	case !sameSession && !res.SessionOpened:
		return ix.markUnknown(streamID, rec, res, chainUnknownRestart), false
	}
	if !sameSession || c.awaiting {
		// 인덱서가 직전 회차의 끝(TD 분할·새 개시)을 관측하는 자리다. 업로더의 회차 상태 정리
		// (Uploader.ForgetSession — sessionInit·보류 목록, 계획 2.1)는 여기서 부르지 않는다:
		// 인덱서가 아는 업로더 통로는 RequestUpload · RestoredLink 둘뿐이다. TD 분할은 루프가
		// 관측하지 못해 업로더의 수명(SessionTTL)이 치운다 — 분리 · 오프라인은 루프가 부른다(판단 J86).
		// 사슬 쪽 정리는 이 교체가 전부다(보관값은 회차별 — 규칙 ⑥).
		return ix.startChain(streamID, rec, res, src), false
	}
	// 같은 녹화기 이어짐은 index.SameInstance 다 — 판 2 섞기 · is_discontinuity 와 정의가 한 자리다(sameInstance).
	// 리셋을 이을 앞 행의 끝은 그 행 파일을 다시 읽어 정한다(chainEnd — 리셋일 때만 불린다).
	rule := func(playback.Link) time.Duration { return ix.chainEnd(c) }
	link := playbackChain{
		link: playback.NextLink(c.link, adjacent && sameInstance(c.link, src), src, rule),
		path: rec.LocalPath, pdt: res.PlaybackPDT,
	}
	ix.recordSkew(streamID, c, link, adjacent)
	ix.chains[streamID] = link
	return playbackTargetOf(streamID, link, rec, res), link.link.Pinned
}

// withTailCorrection 은 꼬리에서 물러나는 사슬 행 prev 의 교정 뒤 값으로 링크를 덮는다. ② 꼬리 교정은 그 행이 꼬리인 동안만
// 일어나므로, 꼬리에서 물러나는 지금의 길이가 최종값이다. 사슬 행이 아닌 prev(비귀속 행)의 길이로는 어떤 필드도 덮지 않는다
// (부르는 쪽이 인접일 때만 부른다). 영상 끝도 같은 교정을 받는 유일한 사실 칸이다(설계 r7 2.3 — 옛 값이 있을 때만 바뀐다).
// 기대값 행(영상 칸 없음)은 그대로 둔다.
func withTailCorrection(l playback.Link, prev *index.TailRow) playback.Link {
	l.Dur = time.Duration(prev.DurationMS) * time.Millisecond
	if l.VideoEnd != nil && prev.Timing.VideoEnd != nil {
		l.VideoEnd = prev.Timing.VideoEnd
	}
	return l
}

// startChain 은 회차의 첫 도장이다 — offset 0, 도장 = 자기 mtxi(⑥ · 결정 B′). mtxi 를 못 읽었으면
// 도장을 지어내지 않는다(④ⓑ): ③ 요청 없이 두고, 다음 정상 판독 행이 사슬을 연다.
func (ix *Indexer) startChain(streamID string, rec index.Record, res index.InsertCommit, src playback.SourceTiming) *index.UploadTarget {
	first, ok := playback.StartLink(src)
	if !ok {
		// 회차의 시작을 이 프로세스가 봤다 — 다음 행은 모름이 아니라 여기서처럼 연다.
		ix.chains[streamID] = playbackChain{link: playback.Link{Seq: rec.Seq, SessionID: res.SessionID}, awaiting: true}
		return nil
	}
	link := playbackChain{link: first, path: rec.LocalPath, pdt: res.PlaybackPDT}
	ix.chains[streamID] = link
	return playbackTargetOf(streamID, link, rec, res)
}

// sourceOf 는 사슬이 장부 행 하나에서 쓰는 재료다 — 원래 사실 칸은 장부에 실은 값(rec.Timing)이고 회차 · 길이는 store 가
// 커밋한 값이다(POK-196 PDT 계획 4절 c5 — 판 2 에서는 mvhd 가 아니라 영상 길이다). mtxi 가 nil 이면 도장을 기대값에
// 고정한다(머리말에 mtxi 없음 · 판독 포기 — timingRead.mtxi).
func sourceOf(rec index.Record, sessionID string, durationMS int32, mtxi *playback.Mtxi) playback.SourceTiming {
	f := rec.Timing.Facts
	return playback.SourceTiming{
		Seq: rec.Seq, SessionID: sessionID, Mtxi: mtxi, Recorder: f.Recorder, Number: f.Number,
		VideoFirst: f.VideoFirst, VideoEnd: f.VideoEnd, Dur: time.Duration(durationMS) * time.Millisecond,
	}
}

// sameInstance 는 ③ 술어의 「같은 녹화기 이어짐」 이다 — 정의는 index.SameInstance 한 자리이고(변이 m21), 여기서는
// 링크 · 재료의 표식 · 번호 칸을 옮겨 부를 뿐이다. 표식이 한쪽이라도 nil(mtxi 없음 · 번호 넘침)이면 이어짐이 아니라
// 리셋이다. 실시간 사슬과 복원 접기(playback.RestoreChain)가 같은 함수를 넘긴다.
func sameInstance(prev playback.Link, cur playback.SourceTiming) bool {
	return index.SameInstance(
		index.TimingFacts{Recorder: prev.Recorder, Number: prev.Number},
		index.TimingFacts{Recorder: cur.Recorder, Number: cur.Number})
}

// chainEnd 는 보관값 행의 끝 = pos + 먼저 끝난 트랙의 끝이다(결정 F — playback.StitchRule 의 지금 규칙). 늦게 끝난 쪽에
// 이으면 소리 빈틈이 남는다(실측 68_ — 끊기는 연결의 마지막 조각은 소리가 먼저 끝난다). 파일을 다시 읽지 못하면 장부
// 길이로 잇는다: 최악 0.2초 소리 빈틈이고 도장이 뒤로 가지는 않는다. 새 로그 키는 두지 않는다 —
// 그 파일의 ② 업로드 실패가 같은 원인을 이미 남긴다(계획 4.2-R R3).
func (ix *Indexer) chainEnd(c playbackChain) time.Duration {
	ends, err := ix.trackEndsT(c.path)
	if err != nil {
		return c.link.Pos + c.link.Dur
	}
	return c.link.Pos + min(ends.Video, ends.Audio)
}

// recordSkew 는 G7 skew 를 남긴다(부기 30): skew(k) = (pdt(k) − pdt(k−1)) − (pos(k) − pos(k−1)).
// 같은 회차의 인접 쌍 중 보정값이 그대로인 쌍만 잰다 — 이어 붙인 쌍의 차이는 압축한 유실·재접속
// 시간이지 PDT(벽시계 축)와 미디어 시계의 어긋남이 아니다. PDT 가 NULL 인 쌍은 잴 수 없다.
//
// 기록 수준은 Debug 다 — 조각마다 한 줄이라 Info 면 스트림당 시간 900줄의 소음이 된다(분포 신호의
// 전례 loop_select_reentry_seconds 도 Debug 다). 그래서 기본 LOG_LEVEL=info 에서는 남지 않고, 분포를
// 모을 때 수준을 내려 켠다. 이것으로 G7 「분포 기록」이 채워지는지는 ⓑ 에서 판정한다.
func (ix *Indexer) recordSkew(streamID string, c, link playbackChain, adjacent bool) {
	if !adjacent || link.link.Offset != c.link.Offset || c.pdt.IsZero() || link.pdt.IsZero() {
		return
	}
	skew := link.pdt.Sub(c.pdt) - (link.link.Pos - c.link.Pos)
	ix.log.Debug("rewind_pdt_media_skew_seconds",
		"stream_id", streamID, "session_id", link.link.SessionID, "seq", link.link.Seq, "seconds", skew.Seconds())
}

// playbackTargetOf 는 행의 ③ 작업이다. ExpectedInitSHA 는 비운다 — 실시간 작업의 기대 init 은 워커의
// sessionInit 이 채운다(계획 2.1). IsTail 은 요청하는 시점이 정한다.
func playbackTargetOf(streamID string, c playbackChain, rec index.Record, res index.InsertCommit) *index.UploadTarget {
	t := &index.UploadTarget{
		StreamID: streamID, Axis: index.AxisPlayback, Seq: rec.Seq, SessionID: res.SessionID,
		S3Key: res.PlaybackS3Key, LocalPath: rec.LocalPath, Bytes: rec.Bytes, PosUnknown: c.unknown,
	}
	if !c.unknown {
		t.PlaybackPos, t.StitchOffset, t.PosPinned = c.link.Pos, c.link.Offset, c.link.Pinned
	}
	return t
}

// timingT 는 조각 시각 판독(playback.ReadTiming — mtxi · 트랙별 시작 · 끝)의 유일한 관문이다(계획 4.2-R 규칙 ③ — m2 ·
// POK-196 PDT 계획 4절 c5). 열고·읽고·닫기가 fsop 워커 안에서 끝나고, 래치 규약은 probeT 와 같다: 트립 뒤에는 워커를
// 만들지 않고, 시간 초과는 fs_op_stalled 와 래치 트립으로 잇는다. 신호의 site 는 옛 mtxi 관문의 이름(mtxi)을 그대로 쓴다 —
// 같은 자리 · 같은 재료(도장 재료 판독)이고, 운영 문서의 판별 문자열이 그 이름이다(README 신호 절).
func (ix *Indexer) timingT(path string) (playback.SegmentTiming, error) {
	if ix.fsLatch.Tripped() {
		return playback.SegmentTiming{}, fmt.Errorf("%w: op=read site=mtxi (latched)", fsop.ErrStalled)
	}
	st, err := ix.timingFn(path)
	if errors.Is(err, fsop.ErrStalled) {
		ix.log.Error("fs_op_stalled", "op", "read", "site", "mtxi", "path", path)
		ix.fsLatch.Trip(path, "mtxi")
	}
	return st, err
}

// trackEndsT 는 리셋을 이을 때 직전 조각의 두 트랙 끝을 다시 읽는 관문이다(timingT 와 같은 규약).
func (ix *Indexer) trackEndsT(path string) (playback.Ends, error) {
	if ix.fsLatch.Tripped() {
		return playback.Ends{}, fmt.Errorf("%w: op=read site=track_ends (latched)", fsop.ErrStalled)
	}
	e, err := ix.trackEndsFn(path)
	if errors.Is(err, fsop.ErrStalled) {
		ix.log.Error("fs_op_stalled", "op", "read", "site", "track_ends", "path", path)
		ix.fsLatch.Trip(path, "track_ends")
	}
	return e, err
}
