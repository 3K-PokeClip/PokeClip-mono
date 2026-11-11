package indexer

// 판 2 섞기 신호(POK-196 PDT 설계 r7 8절 · 계획 14절 c4b). store(pgStore)에는 로거가 없어 섞기 결과는 InsertCommit 으로
// 돌아오고, advance 가 행마다 한 번 낸다. 판 NULL 행은 아무것도 내지 않는다. pdt_lead_kept 는 겹침 처리 (가) 의 신호라
// K1 = (나) 인 이 이미지에는 없다.

import (
	"context"
	"log/slog"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// overlapWarnMS 는 pdt_overlap 을 Warn 으로 올리는 |Δ| 문턱이다(설계 8절 — 1초 미만 Info · 그 밖 Warn). 1초 넘는 겹침은 한
// 번에 다 당기지 못한다(maxOverlap 999ms).
const overlapWarnMS = 1000

// 새 장 · 겹침의 까닭(설계 8절 pdt_new_chapter).
const (
	reasonNoMtxi          = "no_mtxi"
	reasonNewRecording    = "new_recording"
	reasonInstanceChanged = "instance_changed"
	reasonNumberSkipped   = "number_skipped"
	reasonInChapter       = "in_chapter"
)

// isV2 는 커밋된 행이 판 2 인가다.
func isV2(res index.InsertCommit) bool { return res.TimingVersion != nil && *res.TimingVersion == 2 }

// logMix 는 판 2 행의 섞기 갈래 신호 하나를 낸다 — 판 고정 위반 · 회차 첫 행 · 새 장 · 겹침. join · continue 는 내지
// 않는다(segment_indexed 의 속성이 그 몫이다).
func (ix *Indexer) logMix(seg recording.Segment, rec index.Record, res index.InsertCommit, read timingRead) {
	if !isV2(res) {
		return
	}
	base := []any{"stream_id", seg.StreamID, "seq", rec.Seq, "round_id", res.TimingRoundID}
	switch {
	case res.TimingBroken != "":
		ix.log.Error("timing_round_broken", append(base, "reason", res.TimingBroken,
			"timing_version", versionText(res.TimingVersion), "prev_version", versionText(res.TimingPrevVersion),
			"note", "판 고정 위반 · 회차 행 빠짐 — 회차 첫 행(PDT = w)으로 썼다")...)
	case res.TimingReset != "":
		attrs := append(base, "reason", res.TimingReset)
		if res.TimingResetBackMS != nil {
			attrs = append(attrs, "reset_back_ms", *res.TimingResetBackMS)
		}
		ix.log.Info("timing_reset", attrs...)
	case res.TimingBranch == index.BranchNewChapterGap:
		head, _ := headTrack(read)
		ix.log.Info("pdt_new_chapter", append(base, "delta_ms", res.DeltaMS,
			"reason", chapterReason(res.TimingPrevFacts, rec.Timing.Facts), "head_track", head)...)
	case res.TimingBranch == index.BranchNewChapterOverlap:
		// E = w − Δ 이므로 겹친 양 = E − PDT, 남은 양 = PDT − w 다(둘의 합이 |Δ|).
		w, pdt := rec.Timing.Facts.Wall.UnixMilli(), res.PlaybackPDT.UnixMilli()
		level := slog.LevelInfo
		if -res.DeltaMS >= overlapWarnMS {
			level = slog.LevelWarn
		}
		ix.log.Log(context.Background(), level, "pdt_overlap", append(base, "delta_ms", res.DeltaMS, "overlap_ms", w-res.DeltaMS-pdt,
			"residual_ms", pdt-w, "limited_by", res.TimingLimitedBy,
			"reason", chapterReason(res.TimingPrevFacts, rec.Timing.Facts))...)
	}
}

// chapterReason 은 p 칸 prev 와 지금 조각 칸 cur 로 새 장 · 겹침의 까닭을 가른다. MediaMTX 녹화기 인스턴스의 조각 번호는 0
// 부터라(recorder format_fmp4_track.go — nextSegmentNumber 영값) 녹화기가 바뀌고 번호 0 이면 새 녹화다.
func chapterReason(prev *index.TimingFacts, cur index.TimingFacts) string {
	switch {
	case prev == nil || prev.Recorder == nil || cur.Recorder == nil:
		return reasonNoMtxi
	case *prev.Recorder != *cur.Recorder && *cur.Number == 0:
		return reasonNewRecording
	case *prev.Recorder != *cur.Recorder:
		return reasonInstanceChanged
	case index.SameInstance(*prev, cur):
		return reasonInChapter
	}
	return reasonNumberSkipped
}
