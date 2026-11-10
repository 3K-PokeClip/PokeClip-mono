import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { LivePosition } from './livePosition';
import { openSession, type DvrSegment, type SessionEvent } from './rewindSwitch';
import { sessionView } from './sessionView';
import type { PlaybackHooks } from './useHlsPlayback';
import type { PlaybackSession, SessionPlayer } from './usePlaybackSession';
import { useLiveRewindPlayback } from './useLiveRewindPlayback';

// 재생 세션 + 실재생 훅의 위치 사건(POK-200 c13 · 설계 r9 2절). 재생기 자리의 재생 여부 · 연속 길이 · 위치 시대를
// position 사건에 싣는다. 재생기(useHlsPlayback)는 자리만 시험이 쥐는 흉내로 바꾼다.

const player = vi.hoisted(() => ({
  livePosition: null as LivePosition | null,
  hooks: null as PlaybackHooks | null,
  keepPausedOnSwap: () => {},
  holdFrame: () => {},
  dropPauseMark: () => {},
}));

/** 실재생 훅의 인자 — 흉내가 고리(hooks) 자리를 타입으로 집는다 */
type HlsPlaybackArgs = Parameters<typeof import('./useHlsPlayback').useHlsPlayback>;

vi.mock('./useHlsPlayback', () => ({
  // 여덟째 인자가 세션 고리다. 자리가 바뀌면 타입이 어긋나 tsc 가 잡는다
  useHlsPlayback: (...[, , , , , , , hooks]: HlsPlaybackArgs) => {
    player.hooks = hooks ?? null;
    return {
      livePosition: player.livePosition,
      positionEpoch: player.livePosition === null ? null : EPOCH,
      seekToMs: () => {},
      seekToLiveEdge: () => {},
      keepPausedOnSwap: player.keepPausedOnSwap,
      holdFrame: player.holdFrame,
      dropPauseMark: player.dropPauseMark,
    };
  },
}));

const NOW = Date.parse('2026-10-25T06:00:00.000Z');
/** 재생기가 자리를 잰 때의 위치 시대 */
const EPOCH = 4;

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

function fakeSession(patch: Partial<PlaybackSession> = {}): PlaybackSession {
  const state = openSession({ roundId: 'r-A', accessKey: 'r-A|key-1', liveUrl: 'https://m/live' });
  return {
    state,
    view: sessionView(state, NOW),
    dispatch: vi.fn<(event: SessionEvent) => void>(),
    liveButton: () => {},
    bindPlayer: () => {},
    notice: () => {},
    positionEpoch: () => 0,
    isKnownDvrHole: () => false,
    ...patch,
  };
}

/** 위치를 바꿔 가며 훅을 다시 그리고, 세션에 올라간 position 사건을 돌려준다 */
function positionsFor(...steps: LivePosition[]): SessionEvent[] {
  const dispatch = vi.fn<(event: SessionEvent) => void>();
  const session = fakeSession({ dispatch });
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
      { type: 'position', atMs: NOW, playing: true, runMs: 4_000, epoch: EPOCH },
    ]);
  });

  it('자리가 그대로여도 일시정지만 하면 playing false 인 사건을 한 번 더 낸다', () => {
    const playing = at({ runMs: 4_000 });
    const paused = { ...playing, playing: false };
    expect(positionsFor(playing, paused, paused)).toEqual([
      { type: 'position', atMs: NOW, playing: true, runMs: 4_000, epoch: EPOCH },
      { type: 'position', atMs: NOW, playing: false, runMs: 4_000, epoch: EPOCH },
    ]);
  });
});

describe('재생기 고리 · 명령 묶음 — 연결 통합(설계 r9 11절 · 16절 6)', () => {
  it('재생기의 시작 잘림(onStartTruncated)을 live-start-truncated 사건으로 한 번 올린다', () => {
    const dispatch = vi.fn<(event: SessionEvent) => void>();
    renderHook(() => useLiveRewindPlayback({ current: null }, fakeSession({ dispatch })));
    player.hooks?.onStartTruncated?.();
    expect(dispatch.mock.calls.filter(([e]) => e.type === 'live-start-truncated')).toEqual([
      [{ type: 'live-start-truncated' }],
    ]);
  });

  it('세션에 묶는 재생기 명령의 멈춤 유지가 재생기의 keepPausedOnSwap 을 부른다', () => {
    const bindPlayer = vi.fn<(p: SessionPlayer | null) => void>();
    const keep = vi.fn();
    player.keepPausedOnSwap = keep;
    renderHook(() => useLiveRewindPlayback({ current: null }, fakeSession({ bindPlayer })));
    bindPlayer.mock.calls[0]?.[0]?.keepPausedOnSwap();
    expect(keep).toHaveBeenCalledTimes(1);
  });
});

describe('되감기 조각 404 의 고리 · 명령 묶음(POK-528 c5 · 계획 4절 · 4-1 · 4-2)', () => {
  it('재생기의 404 고리(onDvrSegmentGone)를 그 재생기 세대 · 조각을 실은 dvr-segment-gone 사건으로 올린다', () => {
    const dispatch = vi.fn<(event: SessionEvent) => void>();
    renderHook(() => useLiveRewindPlayback({ current: null }, fakeSession({ dispatch })));
    player.hooks?.onDvrSegmentGone?.(7, { type: 'main', sn: 41 });
    expect(dispatch.mock.calls.filter(([e]) => e.type === 'dvr-segment-gone')).toEqual([
      [{ type: 'dvr-segment-gone', generation: 7, segment: { type: 'main', sn: 41 } }],
    ]);
  });

  it.each([true, false])(
    '재생기의 구멍 질의(isKnownDvrHole)를 세션 질의에 그 인자 그대로 한 번 잇고 답(%s)을 그대로 돌려준다(POK-528 후속)',
    (answer) => {
      const isKnownDvrHole = vi.fn<(generation: number, segment: DvrSegment) => boolean>(
        () => answer,
      );
      renderHook(() => useLiveRewindPlayback({ current: null }, fakeSession({ isKnownDvrHole })));
      const asked = player.hooks?.isKnownDvrHole?.(9, { type: 'audio', sn: 53 });
      expect(asked).toBe(answer);
      expect(isKnownDvrHole.mock.calls).toEqual([[9, { type: 'audio', sn: 53 }]]);
    },
  );

  it('세션에 묶는 재생기 명령의 holdFrame · dropPauseMark 가 재생기의 같은 명령을 부른다', () => {
    const bindPlayer = vi.fn<(p: SessionPlayer | null) => void>();
    const hold = vi.fn();
    const drop = vi.fn();
    player.holdFrame = hold;
    player.dropPauseMark = drop;
    renderHook(() => useLiveRewindPlayback({ current: null }, fakeSession({ bindPlayer })));
    const bound = bindPlayer.mock.calls[0]?.[0];
    bound?.holdFrame();
    bound?.dropPauseMark();
    expect(hold).toHaveBeenCalledTimes(1);
    expect(drop).toHaveBeenCalledTimes(1);
  });

  // K3 — 덮개 · 주소가 그대로인데 「세우지 않음」 값만 바뀌어도(덮개가 이미 preparing 인 채 막힘이 서는 전이) 고리가 새 값을 싣는다
  it('세션의 「세우지 않음」(view.startHeld)을 고리 startHeld 로 넘기고, 그 값만 바뀌어도 새 값을 싣는다', () => {
    const dispatch = vi.fn<(event: SessionEvent) => void>();
    const base = fakeSession({ dispatch });
    const held = { ...base, view: { ...base.view, startHeld: true } };
    const view = renderHook(
      (session: PlaybackSession) => useLiveRewindPlayback({ current: null }, session),
      {
        initialProps: held,
      },
    );
    expect(player.hooks?.startHeld).toBe(true);
    view.rerender({ ...held, view: { ...held.view, startHeld: false } });
    expect(player.hooks?.startHeld).toBe(false);
  });
});
