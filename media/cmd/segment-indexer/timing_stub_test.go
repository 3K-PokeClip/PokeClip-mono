package main

import (
	"io"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// noTimingFacts 는 「머리말에 mtxi 도 영상도 없는 조각」 을 돌려주는 시각 판독기다(POK-196 PDT 생산자 c5) — 결정적
// 부재라 조각은 그대로 커밋된다. 이 패키지 시험의 조각 파일은 0 으로 채운 바이트라 실물 판독(playback.ReadTiming)이면
// 모두 일시 실패(막힘)가 되므로, 판독과 무관한 시험의 indexer.New 는 이것을 넘긴다.
func noTimingFacts(io.ReadSeeker) (playback.SegmentTiming, error) {
	return playback.SegmentTiming{}, nil
}
