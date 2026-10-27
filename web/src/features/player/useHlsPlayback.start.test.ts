import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 재생기 기동(POK-200 c13 · 설계 r9 11절 훅 시험). 시작 시각이 있는 라이브는 첫 목록에서 자리를 정하고, strictStart 면
// 못 본 녹화가 잘리는 시작을 거절한다. 되감기 기동 · strictStart 가 꺼진 라이브는 현행 그대로다. 기동에 드는 사건 셋
// (MANIFEST_PARSED · LEVEL_LOADED · LEVEL_UPDATED)을 hls.js 순서대로 쏜다 — 진짜 hls.js 순서는 hlsStart.hlsjs.test.ts 몫이다.
// 이 파일은 useHlsPlayback 만 읽는다: 제품 파일을 되돌린 트리(음성 대조)에서도 현행 보존 단언이 돈다.

interface Details {
  fragments: { start: number; duration: number; programDateTime: number }[];
  fragmentStart: number;
  targetduration: number;
  live: boolean;
}

const fake = vi.hoisted(() => ({ instances: [] as FakeHls[] }));

vi.mock('hls.js', () => ({ default: FakeHls }));

type Handler = (event: string, data: unknown) => void;

class FakeHls {
  static isSupported = () => true;
  static Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_LOADED: 'hlsLevelLoaded',
    LEVEL_UPDATED: 'hlsLevelUpdated',
  };
  readonly handlers = new Map<string, Handler[]>();
  latestLevelDetails: Details | null = null;
  liveSyncPosition: number | null = null;
  readonly startLoad = vi.fn();
  readonly stopLoad = vi.fn();
  readonly destroy = vi.fn();
  constructor(readonly config: { autoStartLoad?: boolean }) {
    fake.instances.push(this);
  }
  on(event: string, handler: Handler) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  once(event: string, handler: Handler) {
    const wrapped: Handler = (e, data) => {
      this.handlers.set(
        event,
        (this.handlers.get(event) ?? []).filter((h) => h !== wrapped),
      );
      handler(e, data);
    };
    this.on(event, wrapped);
  }
  trigger(event: string, data: unknown) {
    const { details } = (data ?? {}) as { details?: Details };
    if (details !== undefined) this.latestLevelDetails = details;
    for (const h of this.handlers.get(event) ?? []) h(event, data);
  }
  attachMedia() {}
  loadSource() {}
}

const PDT0 = Date.parse('2026-10-25T05:00:00.000Z');
/** 영상 0초부터 4초 조각 n 개(PDT0 부터). 라이브 시작 하한 = 첫 조각 + 3 × TD = PDT0 + 12초(영상 12초) */
function details(n: number): Details {
  const fragments = Array.from({ length: n }, (_, i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: PDT0 + i * 4_000,
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live: true };
}

const media = { readyState: 4 };

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

function source(patch: Partial<PlaybackSource>): PlaybackSource {
  return {
    kind: 'live',
    url: 'https://m/live/k/index.m3u8',
    startAtMs: null,
    generation: 0,
    strictStart: false,
    ...patch,
  };
}

/** 재생기를 세우고 동적 import 를 기다린다 */
async function mount(first: PlaybackSource, hooks: PlaybackHooks = {}) {
  const el = document.createElement('video');
  const ref = { current: el };
  const view = renderHook(
    (src: PlaybackSource) =>
      useHlsPlayback(ref, src, {}, undefined, undefined, false, undefined, hooks),
    { initialProps: first },
  );
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  return { ...view, el };
}

/** 기동 사건 셋을 hls.js 순서대로 쏜다(목록 7조각) */
function boot(hls: FakeHls) {
  hls.trigger(FakeHls.Events.MANIFEST_PARSED, {});
  hls.trigger(FakeHls.Events.LEVEL_LOADED, { details: details(7) });
  hls.trigger(FakeHls.Events.LEVEL_UPDATED, { details: details(7) });
}

beforeEach(() => {
  vi.useFakeTimers({ now: PDT0 + 30_000 });
  fake.instances.length = 0;
  media.readyState = 4;
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockReturnValue(false);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockImplementation(
    () => media.readyState,
  );
  vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockImplementation(
    () => ({ length: 1, start: () => 0, end: () => 40 }) as unknown as TimeRanges,
  );
  vi.spyOn(HTMLMediaElement.prototype, 'played', 'get').mockImplementation(
    () => ({ length: 0, start: () => 0, end: () => 0 }) as unknown as TimeRanges,
  );
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('라이브 기동 — 첫 목록에서 시작 자리를 정한다(설계 r9 11절)', () => {
  it('시작 시각이 있으면 자동 시작을 끄고 목록만 깨운 뒤 첫 목록 갱신에서 그 자리로 한 번 받는다', async () => {
    await mount(source({ startAtMs: PDT0 + 13_000 }));
    const hls = lastHls();
    expect(hls.config.autoStartLoad).toBe(false);
    hls.trigger(FakeHls.Events.MANIFEST_PARSED, {});
    expect(hls.startLoad.mock.calls).toEqual([[-1]]);
    hls.trigger(FakeHls.Events.LEVEL_LOADED, { details: details(7) });
    hls.trigger(FakeHls.Events.LEVEL_UPDATED, { details: details(7) });
    expect(hls.startLoad.mock.calls).toEqual([[-1], [13]]);
    hls.trigger(FakeHls.Events.LEVEL_UPDATED, { details: details(8) });
    expect(hls.startLoad.mock.calls).toEqual([[-1], [13]]);
  });

  it('strictStart 이고 첫 목록이 시작을 자르면 받지 않고 멈춘 뒤 다음 마이크로태스크에 내리고 알린다', async () => {
    const onStartTruncated = vi.fn();
    await mount(source({ startAtMs: PDT0 + 11_000, strictStart: true }), { onStartTruncated });
    const hls = lastHls();
    boot(hls);
    expect(hls.startLoad.mock.calls).toEqual([[-1]]);
    expect(hls.stopLoad).toHaveBeenCalledTimes(1);
    expect(hls.destroy).not.toHaveBeenCalled();
    expect(onStartTruncated).not.toHaveBeenCalled();
    await Promise.resolve();
    expect(hls.destroy).toHaveBeenCalledTimes(1);
    expect(onStartTruncated).toHaveBeenCalledTimes(1);
  });

  it('strictStart 가 꺼져 있으면 잘려도 현행대로 하한에서 시작하고 알리지 않는다', async () => {
    const onStartTruncated = vi.fn();
    await mount(source({ startAtMs: PDT0 + 11_000 }), { onStartTruncated });
    const hls = lastHls();
    boot(hls);
    await Promise.resolve();
    expect(hls.startLoad).toHaveBeenLastCalledWith(12);
    expect(hls.stopLoad).not.toHaveBeenCalled();
    expect(hls.destroy).not.toHaveBeenCalled();
    expect(onStartTruncated).not.toHaveBeenCalled();
  });

  it('되감기는 현행대로 첫 목록 로드에서 하한으로 잘라 시작한다', async () => {
    const onStartTruncated = vi.fn();
    const rewind = source({ kind: 'rewind', url: 'https://m/dvr/k/a1/index.m3u8' });
    await mount({ ...rewind, startAtMs: PDT0 + 5_000 }, { onStartTruncated });
    const hls = lastHls();
    expect(hls.config.autoStartLoad).toBe(false);
    boot(hls);
    await Promise.resolve();
    expect(hls.startLoad.mock.calls).toEqual([[12]]);
    expect(hls.stopLoad).not.toHaveBeenCalled();
    expect(onStartTruncated).not.toHaveBeenCalled();
  });
});

describe.each([
  ['세션 모드(paintSession)', { onSeekRequest: () => {} }],
  ['세션 밖(라이브 창 축)', {}],
] as const)('위치 가드 — 재생 위치에 영상이 없으면 자리를 알리지 않는다 · %s', (_name, hooks) => {
  it('readyState 가 HAVE_CURRENT_DATA 아래인 동안 위치는 null 이고 영상이 붙으면 처음 알린다', async () => {
    const { result, el } = await mount(source({}), hooks);
    const hls = lastHls();
    hls.liveSyncPosition = 38;
    act(() => hls.trigger(FakeHls.Events.LEVEL_UPDATED, { details: details(10) }));
    el.currentTime = 36;
    media.readyState = 1;
    act(() => el.dispatchEvent(new Event('timeupdate')));
    expect(result.current.livePosition).toBeNull();
    media.readyState = 2;
    act(() => el.dispatchEvent(new Event('timeupdate')));
    expect(result.current.livePosition).toMatchObject({ atMs: PDT0 + 36_000 });
  });
});

describe('멈춘 채 갈아 끼우기 — 세션이 멈춤 유지를 건 재생기(설계 16절 6 · kty 결정)', () => {
  async function swapToRewind(holdPaused: boolean) {
    const view = await mount(source({}));
    if (holdPaused) act(() => view.result.current.keepPausedOnSwap());
    view.rerender(source({ kind: 'rewind', url: 'https://m/dvr/k/a1/index.m3u8', generation: 1 }));
    await act(async () => {
      await vi.dynamicImportSettled();
    });
    const play = vi.mocked(HTMLMediaElement.prototype.play);
    play.mockClear();
    lastHls().trigger(FakeHls.Events.MANIFEST_PARSED, {});
    return play;
  }

  it('멈춤 유지를 건 뒤 갈아 끼운 되감기 재생기는 스스로 틀지 않는다', async () => {
    expect(await swapToRewind(true)).not.toHaveBeenCalled();
  });

  it('걸지 않았으면 현행대로 갈아 끼운 재생기가 스스로 튼다', async () => {
    expect(await swapToRewind(false)).toHaveBeenCalledTimes(1);
  });
});
