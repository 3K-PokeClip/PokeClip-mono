import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { LivePosition } from './livePosition';
import { openSession, type SessionEvent } from './rewindSwitch';
import { sessionView } from './sessionView';
import type { PlaybackSession } from './usePlaybackSession';
import { useLiveRewindPlayback } from './useLiveRewindPlayback';

// 재생 세션 + 실재생 훅의 위치 사건(POK-200 c13 · 설계 r9 2절). 재생기 자리의 재생 여부 · 연속 길이를 position 사건에
// 싣는다. 재생기(useHlsPlayback)는 자리만 시험이 쥐는 흉내로 바꾼다.

const player = vi.hoisted(() => ({ livePosition: null as LivePosition | null }));

vi.mock('./useHlsPlayback', () => ({
  useHlsPlayback: () => ({
    livePosition: player.livePosition,
    seekToMs: () => {},
    seekToLiveEdge: () => {},
  }),
}));

const NOW = Date.parse('2026-10-25T06:00:00.000Z');

function at(patch: Partial<LivePosition>): LivePosition {
  return {
    atMs: NOW,
    edgeAtMs: NOW + 10_000,
    behindSeconds: 10,
    playing: true,
    runMs: 0,
    ...patch,
  };
}

/** 위치를 바꿔 가며 훅을 다시 그리고, 세션에 올라간 position 사건을 돌려준다 */
function positionsFor(...steps: LivePosition[]): SessionEvent[] {
  const state = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: 'https://m/live' });
  const dispatch = vi.fn<(event: SessionEvent) => void>();
  const session: PlaybackSession = {
    state,
    view: sessionView(state, NOW),
    dispatch,
    liveButton: () => {},
    bindPlayer: () => {},
    notice: () => {},
  };
  const ref = { current: null };
  player.livePosition = steps[0] ?? null;
  const view = renderHook(() => useLiveRewindPlayback(ref, session));
  for (const step of steps.slice(1)) {
    player.livePosition = step;
    view.rerender();
  }
  return dispatch.mock.calls.map(([event]) => event).filter((e) => e.type === 'position');
}

describe('position 사건 — 재생 여부 · 연속 길이(설계 r9 2절)', () => {
  it('재생기 자리의 연속 길이와 재생 여부를 싣는다', () => {
    expect(positionsFor(at({ runMs: 4_000 }))).toEqual([
      { type: 'position', atMs: NOW, playing: true, runMs: 4_000 },
    ]);
  });

  it('자리가 그대로여도 일시정지만 하면 playing false 인 사건을 한 번 더 낸다', () => {
    const playing = at({ runMs: 4_000 });
    const paused = { ...playing, playing: false };
    expect(positionsFor(playing, paused, paused)).toEqual([
      { type: 'position', atMs: NOW, playing: true, runMs: 4_000 },
      { type: 'position', atMs: NOW, playing: false, runMs: 4_000 },
    ]);
  });
});
