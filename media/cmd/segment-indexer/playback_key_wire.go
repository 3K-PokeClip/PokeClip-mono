package main

// ③ 키 꼴 스위치 — 조립 지점(POK-525 설계 r9.4 2-1 · 3절 · 6절).
//
// 장부(index)는 키 파생을 함수로 받고 스위치를 모른다. 키 문법(playback)도 스위치를 모른다 — 두 꼴을 늘 만들고 늘
// 읽는다. 어느 꼴로 쓸지는 상자마다 갈리는 운영 설정(PLAYBACK_ROUND_KEYS)이라 프로그램을 조립하는 여기서 한 번 고른다.

import (
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// playbackKeyFor 는 스위치 roundKeys 에 맞는 ③ 키 파생을 돌려준다.
//
// 끔이면 회차가 있어도 옛 꼴(SegKey)이다 — 교체 겹침 동안 옛 상자가 못 읽는 새 꼴 행을 만들지 않는다(설계 6절 · E5).
// 켬이면 회차가 있는 세션만 새 꼴(RoundSegKey)이고 회차가 없으면(M6 전 세션) 옛 꼴이다. 켬에서 회차 성분이 키 문법을 못
// 지나면 오류를 돌려준다 — 옛 꼴로 물러서지 않고 ⑹ 규약대로 키를 NULL 로 남긴다(옛 꼴로 쓰면 엣지 하한을 비켜 간다).
func playbackKeyFor(roundKeys bool) index.PlaybackKeyFunc {
	if !roundKeys {
		return legacyPlaybackKey
	}
	return roundPlaybackKey
}

func legacyPlaybackKey(streamID, _ string, seq int64) (string, error) {
	return playback.SegKey(streamID, seq)
}

func roundPlaybackKey(streamID, roundID string, seq int64) (string, error) {
	if roundID == "" {
		return playback.SegKey(streamID, seq)
	}
	return playback.RoundSegKey(streamID, roundID, seq)
}
