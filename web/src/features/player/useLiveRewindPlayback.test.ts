import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { resetEventKit } from './handoffTestKit';
import type { LivePosition } from './livePosition';
import { sessionAxis } from './playbackEdge';
import { playWatching, type Step } from './recordingEndTestKit';
import {
  A1,
  A2,
  ENDLIST,
  MIN,
  NOW as LIST_NOW,
  T0,
  X_A,
  X_B,
  X_UNKNOWN,
  gone,
  liveReady,
  poll,
  status,
  vodReady,
} from './relistTestKit';
import { openSession, type DvrSegment, type SessionEvent, type XAnswer } from './rewindSwitch';
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
    isStartHeld: () => false,
    watchesDrain: () => false,
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

  it('세션에 묶는 재생기 명령의 readSound 는 부르는 순간의 영상 노드 소리 상태를 읽는다 — 노드가 없으면 null', () => {
    const bindPlayer = vi.fn<(p: SessionPlayer | null) => void>();
    const video = document.createElement('video');
    const ref = { current: video as HTMLVideoElement | null };
    renderHook(() => useLiveRewindPlayback(ref, fakeSession({ bindPlayer })));
    const bound = bindPlayer.mock.calls[0]?.[0];

    video.muted = false;
    video.volume = 0.3;
    expect(bound?.readSound()).toEqual({ muted: false, volume: 30 });
    video.muted = true;
    expect(bound?.readSound()).toEqual({ muted: true, volume: 30 });
    ref.current = null;
    expect(bound?.readSound()).toBeNull();
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

describe('받기 중단 · 고갈의 고리(POK-547)', () => {
  it('받기 중단 · 고갈 고리를 그 재생기 세대를 실은 dvr-load-stopped · dvr-drain 사건으로 올린다', () => {
    const dispatch = vi.fn<(event: SessionEvent) => void>();
    renderHook(() => useLiveRewindPlayback({ current: null }, fakeSession({ dispatch })));

    player.hooks?.onDvrLoadStopped?.(7);
    player.hooks?.onDvrDrain?.(7, true, NOW);
    player.hooks?.onDvrDrain?.(7, false, null);

    const types = new Set(['dvr-load-stopped', 'dvr-drain']);
    expect(dispatch.mock.calls.filter(([e]) => types.has(e.type))).toEqual([
      [{ type: 'dvr-load-stopped', generation: 7 }],
      [{ type: 'dvr-drain', generation: 7, drained: true, atPdtMs: NOW }],
      [{ type: 'dvr-drain', generation: 7, drained: false, atPdtMs: null }],
    ]);
  });

  it.each([true, false])(
    '재생기의 보류 · 고갈 감시 질의를 세션의 지금 상태 질의에 잇고 답(%s)을 그대로 돌려준다',
    (answer) => {
      const isStartHeld = vi.fn(() => answer);
      const watchesDrain = vi.fn(() => !answer);
      renderHook(() =>
        useLiveRewindPlayback({ current: null }, fakeSession({ isStartHeld, watchesDrain })),
      );

      expect(player.hooks?.isStartHeld?.()).toBe(answer);
      expect(player.hooks?.watchesDrain?.()).toBe(!answer);
      expect(isStartHeld).toHaveBeenCalledTimes(1);
      expect(watchesDrain).toHaveBeenCalledTimes(1);
    },
  );
});

describe('되감기 축의 끝 — 세션 사건 순서 → 고리 recordingEnded → 축', () => {
  beforeEach(resetEventKit);

  /** 4초 조각 다섯(T0 부터 20초) — 축을 셀 로드한 목록 */
  const frags = [0, 1, 2, 3, 4].map((i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: T0 + i * 4_000,
  }));
  /** 목록 끝(T0 + 20 000)보다 40초 뒤 벽시계 · 지연 3초 */
  const lateMs = T0 + 60_000;

  /**
   * 방송 중 A1 을 P20 에서 되감는 세션(첫 사건은 A1 이 아직 라이브라는 스트림 답)에 사건을 차례로 먹이고, 그 상태 · 스트림
   * 사실로 훅을 그려 고리에 실린 값으로 재생 위치 14초(T0 + 14 000)의 시차 · 창 폭을 센다
   */
  function axisAfter(...steps: Step[]) {
    const { state, facts } = playWatching(...steps);
    const session = { ...fakeSession(), state, view: sessionView(state, LIST_NOW), ...facts };
    renderHook(() => useLiveRewindPlayback({ current: null }, session));
    const recordingEnded = player.hooks?.recordingEnded;
    const axis = sessionAxis({
      kind: 'rewind',
      live: false,
      recordingEnded,
      fragments: frags,
      targetDurationS: 4,
      currentTime: 14,
      liveEdgeTime: null,
      nowMs: lateMs,
      lagMs: 3_000,
      axisLowerMs: null,
    });
    return { recordingEnded, behind: axis?.behindSeconds, window: axis?.windowSeconds };
  }
  const endlist: Step = () => ENDLIST;
  const reconnected =
    (x: XAnswer = X_A): Step =>
    () =>
      poll(liveReady(A2, T0 + 50 * MIN), x);
  const notLive: Step = () => poll(status({ mode: 'ending' }));
  const segmentGone: Step = (state) => gone(state);
  /** 벽시계 축 — 끝 = 지금 − 지연 */
  const clock = { recordingEnded: false, behind: 43, window: 57 };
  /** 목록 끝 축 */
  const listEnd = { recordingEnded: true, behind: 6, window: 20 };

  it('ENDLIST → 같은 회차 새 라이브 답(재접속 · X 모름 포함)은 벽시계다', () => {
    expect(axisAfter(endlist, reconnected())).toEqual(clock);
    expect(axisAfter(endlist, reconnected(X_UNKNOWN))).toEqual(clock);
  });

  it('새 라이브 답 → ENDLIST(pendingNext)는 벽시계다', () => {
    expect(axisAfter(reconnected(), endlist)).toEqual(clock);
  });

  it('정상 종료 ENDLIST → 정리 중 → 회차 다시보기는 목록 끝이다', () => {
    const roundVod: Step = () => ({ type: 'round-poll', result: vodReady() });
    expect(axisAfter(endlist, notLive)).toEqual(listEnd);
    expect(axisAfter(endlist, notLive, roundVod)).toEqual(listEnd);
  });

  it('404 막힘 + 정리 중은 목록 끝 · 404 막힘인데 스트림이 아직 라이브(X 모름)면 벽시계다', () => {
    const stillLive: Step = () => poll(liveReady(A1, T0, LIST_NOW + MIN), X_UNKNOWN);
    expect(axisAfter(segmentGone, segmentGone, notLive)).toEqual(listEnd);
    expect(axisAfter(segmentGone, segmentGone, stillLive)).toEqual(clock);
  });

  it('pendingNext 가 남은 채 이 회차를 부정하는 라이브 답(X = B)만 와도 목록 끝 · 이 회차 답이 오면 벽시계다', () => {
    const otherLive: Step = () => poll(liveReady(A2, T0 + 50 * MIN), X_B);
    expect(axisAfter(reconnected(), endlist, otherLive)).toEqual(listEnd);
    expect(axisAfter(reconnected(), endlist, otherLive, reconnected())).toEqual(clock);
  });

  it('pendingNext 가 남은 채 이 회차가 끝났다는 답 뒤 다른 방송이 시작해도 목록 끝이다', () => {
    const streamVod: Step = () => poll(vodReady());
    const otherLive: Step = () =>
      poll(liveReady('https://media.pokeclip.com/dvr/key-1/b1/index.m3u8', LIST_NOW), X_B);
    expect(axisAfter(reconnected(), endlist, streamVod)).toEqual(listEnd);
    expect(axisAfter(reconnected(), endlist, streamVod, otherLive)).toEqual(listEnd);
  });
});
