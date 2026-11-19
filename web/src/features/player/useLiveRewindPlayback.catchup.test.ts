import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { openSession, type SessionEvent } from './rewindSwitch';
import { sessionView } from './sessionView';
import type { PlaybackHooks, useHlsPlayback } from './useHlsPlayback';
import type { PlaybackSession } from './usePlaybackSession';
import { useLiveRewindPlayback } from './useLiveRewindPlayback';

// 넘김 확인 여부의 고리(POK-583 Q3 (다)) — 라이브 따라잡기는 표본마다 isHandoffConfirming 을 읽는다. 재생기는 설 때 받은
// 고리를 쥐고 있으므로, 그 고리가 그린 뒤의 세션 상태(state.handoff)를 읽어야 확인이 끝난 표본에서 점프가 풀린다. 재생기
// (useHlsPlayback)는 고리만 붙잡는 흉내로 바꾼다.

const player = vi.hoisted(() => ({ hooks: null as PlaybackHooks | null }));

/** 실재생 훅의 인자 — 흉내가 고리(hooks) 자리를 타입으로 집는다 */
type HlsPlaybackArgs = Parameters<typeof useHlsPlayback>;

vi.mock('./useHlsPlayback', () => ({
  useHlsPlayback: (...[, , , , , , , hooks]: HlsPlaybackArgs) => {
    player.hooks = hooks ?? null;
    return { livePosition: null, positionEpoch: null };
  },
}));

const NOW = Date.parse('2026-11-19T06:00:00.000Z');
const HANDOFF = {
  fromUrl: 'https://m/dvr/k/index.m3u8',
  endMs: NOW,
  atMs: NOW - 4_000,
  deadlineMs: NOW + 8_000,
  seenAtMs: null,
};

function fakeSession(confirming: boolean): PlaybackSession {
  const opened = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: 'https://m/live' });
  const state = { ...opened, handoff: confirming ? HANDOFF : null };
  return {
    state,
    view: sessionView(state, NOW),
    dispatch: vi.fn<(event: SessionEvent) => void>(),
    liveButton: () => {},
    bindPlayer: () => {},
    notice: () => {},
    positionEpoch: () => 0,
    isKnownDvrHole: () => false,
    isStartHeld: () => false,
    watchesDrain: () => false,
  };
}

describe('isHandoffConfirming 고리', () => {
  it('세션이 넘김을 확인하는 중이면 참 · 확인이 끝나면 처음 받은 고리도 거짓을 읽는다', () => {
    const ref = { current: null };
    const view = renderHook(({ session }) => useLiveRewindPlayback(ref, session), {
      initialProps: { session: fakeSession(true) },
    });
    const read = player.hooks?.isHandoffConfirming;
    expect(read?.()).toBe(true);
    view.rerender({ session: fakeSession(false) });
    expect(read?.()).toBe(false);
  });
});
