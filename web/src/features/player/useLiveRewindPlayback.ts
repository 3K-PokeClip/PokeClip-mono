'use client';

import { useEffect, useMemo, useRef, type RefObject } from 'react';
import { rewindAxisLowerMs } from './rewindSwitch';
import type { PlaybackSession } from './usePlaybackSession';
import { useHlsPlayback, type LivePosition, type PlaybackHooks } from './useHlsPlayback';
import type { PlayerSimulation, PlayerSimulationOptions } from './usePlayerSimulation';

// 재생 세션 + 실재생 훅(설계 2.2절, POK-200 M5). 소스를 자기 상태로 들지 않는다 — 세션이 정한 소스를 useHlsPlayback 에 넘기고,
// 재생기의 사건(ENDLIST · 끝 도달 · 라이브 닫힘 · 400 · 목록 · 시크 · LIVE)을 세션 사건으로 올린다. PlayerSimulation 계약 그대로라
// GlassPlayer 몸통은 어느 훅인지 모른다.

/**
 * @param session LiveScreen 층의 재생 세션(GlassPlayer key 바깥). 재생기가 다시 마운트되면 세션이 같은 자리에서 잇는다(R36)
 */
export function useLiveRewindPlayback(
  videoRef: RefObject<HTMLVideoElement | null>,
  session: PlaybackSession,
  options: PlayerSimulationOptions = {},
  onSeek?: () => void,
  mediaKey?: string,
  afterForbidden = false,
  broadcastId?: string,
): PlayerSimulation & { livePosition: LivePosition | null } {
  const { state, view, dispatch, liveButton, bindPlayer, notice, positionEpoch } = session;
  // 되감기 노출은 최신 폴링의 ready 로만 — 재료(entry)가 남아도 ready:false 면 시크바를 넓히지 않는다(계약3 7-2)
  const axisLowerMs = rewindAxisLowerMs(state);
  const hooks = useMemo<PlaybackHooks>(
    () => ({
      onEndlist: () => dispatch({ type: 'endlist' }),
      onEndReached: () => dispatch({ type: 'end-reached' }),
      onStartTruncated: () => dispatch({ type: 'live-start-truncated' }),
      onLiveClosed: () => dispatch({ type: 'live-closed' }),
      onLive400: () => dispatch({ type: 'live-400' }),
      onListLoaded: (list) => dispatch({ type: 'list-loaded', ...list }),
      onPlaying: () => dispatch({ type: 'playing' }),
      onSeekRequest: (targetMs, view) => dispatch({ type: 'seek', targetMs, view }),
      onLiveButton: liveButton,
      onNotice: notice,
      positionEpoch,
      axisLowerMs,
      // 끝에 닿아 덮개가 선 동안 재생 단추를 막는다(끝난 목록이 처음부터 돌지 않게)
      holdPlay: view.cover !== null,
    }),
    [dispatch, liveButton, notice, positionEpoch, axisLowerMs, view.cover],
  );
  const sim = useHlsPlayback(
    videoRef,
    state.source,
    options,
    onSeek,
    mediaKey,
    afterForbidden,
    broadcastId,
    hooks,
  );

  // 재생기 명령을 세션에 묶는다. 마운트에 한 번 — 세션은 두 번째 묶음을 리마운트로 읽는다(R36). 최신 명령은 ref 로 읽는다
  const simRef = useRef(sim);
  simRef.current = sim;
  useEffect(() => {
    bindPlayer({
      seekToMs: (atMs) => simRef.current.seekToMs(atMs),
      seekToLiveEdge: () => simRef.current.seekToLiveEdge(),
      keepPausedOnSwap: () => simRef.current.keepPausedOnSwap(),
    });
    return () => bindPlayer(null);
  }, [bindPlayer]);

  // 마지막으로 본 자리(P) — 리마운트 잇기 · 계승 판정 · 이음 지점의 재료
  // 재생 여부 · 연속 길이도 싣는다 — 같은 자리여도 일시정지만 하면 사건이 한 번 난다(POK-200 c13)
  const atMs = sim.livePosition?.atMs ?? null;
  const playing = sim.livePosition?.playing ?? false;
  const runMs = sim.livePosition?.runMs ?? 0;
  // 잰 때의 위치 시대도 싣는다 — 이 효과는 커밋 뒤에 돌아, 그사이 세션이 재생기를 바꾸거나 옮겼으면 세션이 이 위치를
  // 버린다(POK-200 c13)
  const epoch = sim.positionEpoch;
  useEffect(() => {
    if (atMs !== null && epoch !== null) {
      dispatch({ type: 'position', atMs, playing, runMs, epoch });
    }
  }, [atMs, playing, runMs, epoch, dispatch]);

  // 다시보기(VOD)는 끝난 방송이라 오른쪽 끝이 「실시간」이 아니라 녹화 끝이다(시크바 문구 · LIVE 숨김)
  return { ...sim, replay: state.source.kind === 'vod' };
}
