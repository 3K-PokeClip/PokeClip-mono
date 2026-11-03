package indexer

// ③ 시간 도장 사슬(계획 4.2-R R3 — kty 결정 B′·G). 조각 k 의 도장은 pos(k) = mtxi(k) + offset 이다.
// 평소 offset 은 0 이고, 회차 안에서 mtxi 가 리셋되거나(같은 녹화기 이어짐이 아님 ∨ 역행) 같은 녹화기
// 안에 구멍이 나면 직전 조각 끝에 이어 붙이도록 바뀐다. 이어짐 판정은 장부의 원래 사실 칸 위의
// index.SameInstance 이고 리셋 · 구멍은 영상 축에서 잰다(POK-196 PDT 계획 4절 c8 · ADR-073 결정 2 개정).
//
// 이 파일이 루프 쪽 전부다 — 보관값(사슬)을 전진시키고 그 행의 ③ 작업 메타를 만든다. 회차별
// 보정값 표(스위퍼 재생성용)는 업로더 소유이고, 루프는 작업에 메타를 실어 보낼 뿐이다.

import (
	"errors"
	"fmt"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// mtxiGapTolerance 는 같은 녹화기 안에서 구멍으로 보지 않는 이웃 차 d 의 상한이다(kty 결정 G).
// d 는 직전 조각의 끝(영상 축이면 영상 끝, 아니면 mtxi + 장부 길이)부터 이 조각 시작까지다 — 이 값을
// 넘게 늦게 시작하면 그 유실 구간을 압축해 잇는다. 정확히 이 값은 구멍이 아니다 — 같은 연결 안 조각
// 경계의 어긋남은 영상 ±1틱(11µs)이다.
const mtxiGapTolerance = 50 * time.Millisecond

// videoRewindLimit 은 영상 축 이웃 차 d 를 역행(리셋)으로 보는 문턱이다 — d ≤ −1ms 면 리셋이다(ADR-073
// 결정 2 개정 「리셋 = d(k) ≤ −1ms」). 1ms 미만의 음수는 같은 연결 안 경계 어긋남이다.
const videoRewindLimit = time.Millisecond

// playbackChain 은 스트림 하나의 ③ 도장 보관값이다 — 사슬이 마지막으로 도장을 찍은 회차 귀속 행의
// 재료와, 그 회차에서 지금 쓰는 보정값. 루프(D10) 한 goroutine 만 만진다.
type playbackChain struct {
	sessionID string
	// seq 는 보관값 행의 seq 다. INSERT 때 직전 장부 행(prev)과 대조해 사이에 다른 행이 끼었는지
	// 가린다 — 스트림 안에서 seq 가 유일하므로 회차까지 대조할 필요가 없다.
	seq int64
	// facts 는 행의 원래 사실 칸이다(장부 timing_* — ③ 술어의 재료). 판독에 실패한 행이면 기대값(직전 행과
	// 같은 녹화기의 다음 번호 · 영상 칸 없음)이다(④ⓐ — expectedFacts).
	facts index.TimingFacts
	// mtxi 는 누적 DTS 다. 판독에 실패한 행이면 기대값(직전 mtxi + 장부 길이)이다(④ⓐ).
	mtxi time.Duration
	pos  time.Duration
	// dur 은 장부 길이(duration_ms)다 — 다음 행 INSERT 때 꼬리 교정 뒤 값으로 확정된다(②).
	dur time.Duration
	// path 는 리셋을 이을 때 트랙 끝을 다시 읽을 녹화 파일이다.
	path string
	// pdt 는 playback_pdt 다 — G7 skew 재료(부기 30). 영값이면 NULL 이다.
	pdt    time.Time
	offset time.Duration
}

// advanceChain 은 장부 행 하나만큼 사슬을 전진시키고 그 행의 ③ 작업을 만든다(계획 4.2-R 규칙 ①).
// 작업이 nil 이면 이 행에는 ③ 요청이 없다 — 비귀속 행(⑥)이거나, 회차의 보관값 없이 mtxi 를 못 읽은
// 행(④ⓑ)이다. pinned 는 판독 실패로 도장을 기대값에 고정했는가다(④ⓐ).
//
// 접수 게이트보다 앞에서 부른다 — 요청이 거부돼도 사슬은 이어져야 다음 행이 리셋을 오판하지 않는다.
func (ix *Indexer) advanceChain(streamID string, prev *index.TailRow, rec index.Record, res index.InsertCommit, mtxi *playback.Mtxi) (target *index.UploadTarget, pinned bool) {
	c, hasChain := ix.chains[streamID]
	// adjacent 는 직전 장부 행이 곧 사슬 행인가다 — 아니면 사이에 비귀속 행이 끼었다(⑥).
	adjacent := hasChain && prev != nil && prev.Seq == c.seq
	if adjacent {
		// ② 꼬리 교정은 그 행이 꼬리인 동안만 일어나므로, 꼬리에서 물러나는 지금의 길이가 최종값이다.
		// 사슬 행이 아닌 prev(비귀속 행)의 길이로는 어떤 필드도 덮지 않는다. 영상 끝도 같은 교정을 받는
		// 유일한 사실 칸이다(설계 r7 2.3 — 옛 값이 있을 때만 바뀐다). 기대값 행(영상 칸 없음)은 그대로 둔다.
		c.dur = time.Duration(prev.DurationMS) * time.Millisecond
		if c.facts.VideoEnd != nil && prev.Timing.VideoEnd != nil {
			c.facts.VideoEnd = prev.Timing.VideoEnd
		}
		ix.chains[streamID] = c
	}
	if res.SessionID == "" {
		return nil, false
	}
	if !hasChain || c.sessionID != res.SessionID {
		// 인덱서가 직전 회차의 끝(TD 분할·새 개시)을 관측하는 자리다. 업로더의 회차 상태 정리
		// (Uploader.ForgetSession — sessionInit·보류 목록, 계획 2.1)는 여기서 부르지 않는다:
		// 인덱서가 아는 업로더 통로는 RequestUpload 하나뿐이다(새 인터페이스 0). TD 분할은 루프가
		// 관측하지 못해 업로더의 수명(SessionTTL)이 치운다 — 분리 · 오프라인은 루프가 부른다(판단 J86).
		// 사슬 쪽 정리는 이 교체가 전부다(보관값은 회차별 — 규칙 ⑥).
		return ix.startChain(streamID, rec, res, mtxi), false
	}
	link, pinned := ix.nextLink(c, adjacent, rec, res, mtxi)
	ix.recordSkew(streamID, c, link, adjacent)
	ix.chains[streamID] = link
	return playbackTargetOf(streamID, link, rec, res, pinned), pinned
}

// startChain 은 회차의 첫 도장이다 — offset 0, 도장 = 자기 mtxi(⑥ · 결정 B′). mtxi 를 못 읽었으면
// 도장을 지어내지 않는다(④ⓑ): ③ 요청 없이 두고, 다음 정상 판독 행이 사슬을 연다.
func (ix *Indexer) startChain(streamID string, rec index.Record, res index.InsertCommit, mtxi *playback.Mtxi) *index.UploadTarget {
	if mtxi == nil {
		return nil
	}
	link := linkOf(rec, res)
	link.facts, link.mtxi, link.pos = rec.Timing.Facts, mtxi.DTS, mtxi.DTS
	ix.chains[streamID] = link
	return playbackTargetOf(streamID, link, rec, res, false)
}

// nextLink 는 같은 회차 안 다음 행의 보관값이다. mtxi 를 못 읽었으면 도장을 막지 않고 기대값에
// 고정한다(④ⓐ): pos = pos(k−1) + dur(k−1), 사슬은 기대 mtxi·표식 유지로 잇는다 — 그 자리에 실제
// 리셋·구멍이 있었다면 다음 행의 술어가 잡으므로 도장은 뒤로 가지 않는다.
func (ix *Indexer) nextLink(c playbackChain, adjacent bool, rec index.Record, res index.InsertCommit, mtxi *playback.Mtxi) (link playbackChain, pinned bool) {
	link = linkOf(rec, res)
	if mtxi == nil {
		link.facts, link.mtxi, link.offset = expectedFacts(c.facts), c.mtxi+c.dur, c.offset
		link.pos = c.pos + c.dur
		return link, true
	}
	link.facts, link.mtxi = rec.Timing.Facts, mtxi.DTS
	link.offset = ix.stitchOffset(c, adjacent, *mtxi, rec.Timing.Facts)
	link.pos = mtxi.DTS + link.offset
	return link, false
}

// stitchOffset 은 같은 회차 안 다음 행 k 의 보정값이다(R3 산식 · POK-196 PDT 계획 4절 c8). k−1 은 사슬 행이고
// cur 는 k 의 원래 사실 칸이다.
//
//	리셋(¬같은 녹화기 이어짐 ∨ 역행)             offset := end(k−1) − mtxi(k)
//	  또는 사이에 비귀속 행이 끼었다(⑥)
//	구멍(같은 녹화기 이어짐 ∧ d > 허용치)         offset := pos(k−1) + dur(k−1) − mtxi(k)
//	그 밖                                        직전 보정값 그대로
//
// 같은 녹화기 이어짐은 index.SameInstance 다 — 판 2 섞기 · is_discontinuity 와 정의가 한 자리다. 표식이
// 한쪽이라도 nil(mtxi 없음 · 번호 넘침)이면 이어짐이 아니라 리셋이다. 역행 · d 는 neighborGap 이 잰다.
//
// 술어는 사슬 행이 장부의 직전 행일 때만 뜻이 있다 — 끼어든 행이 있으면 구멍·연속을 판정할 기준이
// 없으므로 저장된 마지막 회차 행의 끝에 잇는다(끼어든 미디어는 회차 밖이라 압축된다).
func (ix *Indexer) stitchOffset(c playbackChain, adjacent bool, m playback.Mtxi, cur index.TimingFacts) time.Duration {
	if !adjacent || !index.SameInstance(c.facts, cur) {
		return ix.chainEnd(c) - m.DTS
	}
	d, rewound := neighborGap(c, m, cur)
	switch {
	case rewound:
		return ix.chainEnd(c) - m.DTS
	case d > mtxiGapTolerance:
		return c.pos + c.dur - m.DTS
	}
	return c.offset
}

// neighborGap 은 같은 녹화기 이어짐인 사슬 행 c 와 다음 행 사이의 이웃 차 d 와, 그것이 역행(리셋)인가다.
//
//	영상 축(두 행 모두 영상 칸 있음)  d = a(k) − b(k−1)               역행 = d ≤ −1ms
//	mtxi 축(그 밖)                   d = mtxi(k) − (mtxi(k−1) + dur(k−1))  역행 = mtxi(k) < mtxi(k−1)
//
// 영상 축이 먼저다 — 장부 길이가 영상 길이(판 2)면 mtxi(조각 시작 = 머리 트랙 시작) + 장부 길이는 소리 머리
// 조각의 머리 앞섬만큼 영상 끝보다 이르러 거짓 구멍(실측 최대 226ms)을 낸다(ADR-073 결정 2 개정 「이웃 술어는
// 영상 축이다」). 영상 칸이 없는 행(소리만 송출 · 판독 실패로 기대값에 고정한 앞 행)은 mtxi 축으로 잰다.
func neighborGap(c playbackChain, m playback.Mtxi, cur index.TimingFacts) (d time.Duration, rewound bool) {
	if c.facts.VideoEnd != nil && cur.VideoFirst != nil {
		d = *cur.VideoFirst - *c.facts.VideoEnd
		return d, d <= -videoRewindLimit
	}
	return m.DTS - (c.mtxi + c.dur), m.DTS < c.mtxi
}

// expectedFacts 는 mtxi 를 못 읽어 도장을 기대값에 고정한 행의 사슬 칸이다(④ⓐ) — 앞 행과 같은 녹화기의 다음
// 번호로 둬, 그 자리에 실제 리셋이 없었으면 다음 행이 같은 녹화기 이어짐으로 잇는다. 영상 칸은 비운다: 다음 행은
// mtxi 축(기대 mtxi + 장부 길이)으로 잰다. 앞 행의 표식이 nil 이면 기대값도 nil 이라 다음 행은 리셋이다.
func expectedFacts(prev index.TimingFacts) index.TimingFacts {
	if prev.Recorder == nil || prev.Number == nil {
		return index.TimingFacts{}
	}
	next := *prev.Number + 1
	return index.TimingFacts{Recorder: prev.Recorder, Number: &next}
}

// chainEnd 는 보관값 행의 끝 = pos + 먼저 끝난 트랙의 끝이다. 늦게 끝난 쪽에 이으면 소리 빈틈이
// 남는다(실측 68_ — 끊기는 연결의 마지막 조각은 소리가 먼저 끝난다). 파일을 다시 읽지 못하면 장부
// 길이로 잇는다: 최악 0.2초 소리 빈틈이고 도장이 뒤로 가지는 않는다. 새 로그 키는 두지 않는다 —
// 그 파일의 ② 업로드 실패가 같은 원인을 이미 남긴다(계획 4.2-R R3).
func (ix *Indexer) chainEnd(c playbackChain) time.Duration {
	ends, err := ix.trackEndsT(c.path)
	if err != nil {
		return c.pos + c.dur
	}
	return c.pos + min(ends.Video, ends.Audio)
}

// recordSkew 는 G7 skew 를 남긴다(부기 30): skew(k) = (pdt(k) − pdt(k−1)) − (pos(k) − pos(k−1)).
// 같은 회차의 인접 쌍 중 보정값이 그대로인 쌍만 잰다 — 이어 붙인 쌍의 차이는 압축한 유실·재접속
// 시간이지 PDT(벽시계 축)와 미디어 시계의 어긋남이 아니다. PDT 가 NULL 인 쌍은 잴 수 없다.
//
// 기록 수준은 Debug 다 — 조각마다 한 줄이라 Info 면 스트림당 시간 900줄의 소음이 된다(분포 신호의
// 전례 loop_select_reentry_seconds 도 Debug 다). 그래서 기본 LOG_LEVEL=info 에서는 남지 않고, 분포를
// 모을 때 수준을 내려 켠다. 이것으로 G7 「분포 기록」이 채워지는지는 ⓑ 에서 판정한다.
func (ix *Indexer) recordSkew(streamID string, c, link playbackChain, adjacent bool) {
	if !adjacent || link.offset != c.offset || c.pdt.IsZero() || link.pdt.IsZero() {
		return
	}
	skew := link.pdt.Sub(c.pdt) - (link.pos - c.pos)
	ix.log.Debug("rewind_pdt_media_skew_seconds",
		"stream_id", streamID, "session_id", link.sessionID, "seq", link.seq, "seconds", skew.Seconds())
}

// linkOf 는 행의 보관값 뼈대다. 도장 재료(표식·mtxi·pos·offset)는 부르는 쪽이 채운다. 길이는 store 가 커밋한 값이다
// (POK-196 PDT 계획 4절 c5 — 판 2 에서는 mvhd 가 아니라 영상 길이다).
func linkOf(rec index.Record, res index.InsertCommit) playbackChain {
	return playbackChain{
		sessionID: res.SessionID, seq: rec.Seq, dur: time.Duration(res.DurationMS) * time.Millisecond,
		path: rec.LocalPath, pdt: res.PlaybackPDT,
	}
}

// playbackTargetOf 는 행의 ③ 작업이다. ExpectedInitSHA 는 비운다 — 실시간 작업의 기대 init 은 워커의
// sessionInit 이 채운다(계획 2.1). IsTail 은 요청하는 시점이 정한다.
func playbackTargetOf(streamID string, link playbackChain, rec index.Record, res index.InsertCommit, pinned bool) *index.UploadTarget {
	return &index.UploadTarget{
		StreamID: streamID, Axis: index.AxisPlayback, Seq: rec.Seq, SessionID: res.SessionID,
		S3Key: res.PlaybackS3Key, LocalPath: rec.LocalPath, Bytes: rec.Bytes,
		PlaybackPos: link.pos, StitchOffset: link.offset, PosPinned: pinned,
	}
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
