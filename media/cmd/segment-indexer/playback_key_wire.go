package main

// ③ 키 꼴 스위치 — 조립 지점(POK-525 설계 r9.4 2-1 · 3절 · 6절).
//
// 장부(index)는 키 파생을 함수로 받고 스위치를 모른다. 키 문법(playback)도 스위치를 모른다 — 두 꼴을 늘 만들고 늘
// 읽는다. 어느 꼴로 쓸지는 상자마다 갈리는 운영 설정(PLAYBACK_ROUND_KEYS)이라 프로그램을 조립하는 여기서 한 번 고른다.

import (
	"log/slog"
	"sync"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// playbackKeyFor 는 스위치 roundKeys 에 맞는 ③ 키 파생을 돌려준다. 파생 실패는 log 에 WARN 으로 알린다(warnOnKeyFailure).
//
// 끔이면 회차가 있어도 옛 꼴(SegKey)이다 — 교체 겹침 동안 옛 상자가 못 읽는 새 꼴 행을 만들지 않는다(설계 6절 · E5).
// 켬이면 회차가 있는 세션만 새 꼴(RoundSegKey)이고 회차가 없으면(M6 전 세션) 옛 꼴이다. 켬에서 회차 성분이 키 문법을 못
// 지나면 오류를 돌려준다 — 옛 꼴로 물러서지 않고 ⑹ 규약대로 키를 NULL 로 남긴다(옛 꼴로 쓰면 엣지 하한을 비켜 간다).
func playbackKeyFor(roundKeys bool, log *slog.Logger) index.PlaybackKeyFunc {
	derive := legacyPlaybackKey
	if roundKeys {
		derive = roundPlaybackKey
	}
	return warnOnKeyFailure(derive, log)
}

// warnOnKeyFailure 는 derive 의 실패를 playback_key_derive_failed WARN 한 줄로 알린다 — 장부(index)는 실패를 키 NULL 로만
// 남기고 로거가 없어, 업로드 · 목록 발행이 그 조각을 건너뛰는 원인이 로그에 없었다. 결과(키 · 오류)는 그대로 돌려준다.
//
// 같은 스트림의 같은 회차 실패는 첫 번만 알린다 — 회차 성분이 불량이면 그 회차의 조각마다 실패해 줄이 조각 수만큼 쌓인다.
// 기억은 스트림마다 마지막으로 알린 회차 하나라 스트림 수를 넘어 자라지 않는다. 장부는 이 함수를 여러 고루틴에서 부를 수 있어 잠근다.
func warnOnKeyFailure(derive index.PlaybackKeyFunc, log *slog.Logger) index.PlaybackKeyFunc {
	var (
		mu     sync.Mutex
		warned = map[string]string{} // 스트림 → 마지막으로 알린 회차
	)
	return func(streamID, roundID string, seq int64) (string, error) {
		key, err := derive(streamID, roundID, seq)
		if err == nil {
			return key, nil
		}
		mu.Lock()
		last, seen := warned[streamID]
		first := !seen || last != roundID
		warned[streamID] = roundID
		mu.Unlock()
		if first {
			log.Warn("playback_key_derive_failed", "stream_id", streamID, "round_id", roundID, "seq", seq, "err", err,
				"note", "③ 키 NULL — 이 조각은 업로드 · 목록 발행에서 빠진다. 같은 회차의 다음 실패는 다시 알리지 않는다")
		}
		return key, err
	}
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
