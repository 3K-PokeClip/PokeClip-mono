import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 목록 다시 받기로 선 새 되감기 재생기의 시작(POK-528 c7 — 계획 3-3 (2) · 8-3a). 세션은 같은
// url 을 세대 + 1 로 다시 세우고 이어 볼 자리를 넘긴다. 재생기는 첫 LEVEL_LOADED 에서 새 목록
// 창 하한 아래를 자르고(armStart · startCut), 첫 LEVEL_UPDATED 에서 같은 함수로 「하한으로
// 잘랐는가」 를 onListLoaded 의 startFloorCut 에 싣는다 — 세션의 하한 알림 판정 재료다.
// hls.js 흉내는 useHlsPlayback.dvr404.test.ts 꼴이다(처음부터 off() 를 둔다).

interface Fragment {
  start: number;
  duration: number;
  programDateTime: number;
}

interface Details {
  fragments: Fragment[];
  fragmentStart: number;
  targetduration: number;
  live: boolean;
}

const fake = vi.hoisted(() => ({
  instances: [] as FakeHls[],
  /** 참이면 hls.js 실물처럼 LEVEL_LOADED 처리 안에서 LEVEL_UPDATED 를 먼저 낸다(stream-controller.ts 674) */
  updateInsideLoaded: false,
}));

vi.mock('hls.js', () => ({ default: FakeHls }));

type Handler = (event: string, data: unknown) => void;

class FakeHls {
  static readonly isSupported = () => true;
  static readonly Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_LOADED: 'hlsLevelLoaded',
    LEVEL_UPDATED: 'hlsLevelUpdated',
    FRAG_LOADED: 'hlsFragLoaded',
    ERROR: 'hlsError',
  };
  static readonly ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  static readonly ErrorDetails = {
    FRAG_LOAD_ERROR: 'fragLoadError',
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    LEVEL_LOAD_ERROR: 'levelLoadError',
    AUDIO_TRACK_LOAD_ERROR: 'audioTrackLoadError',
  };
  readonly handlers = new Map<string, Handler[]>();
  latestLevelDetails: Details | null = null;
  readonly liveSyncPosition: number | null = null;
  readonly startLoad = vi.fn();
  readonly stopLoad = vi.fn();
  readonly destroy = vi.fn();
  readonly loadSource = vi.fn();
  constructor() {
    fake.instances.push(this);
    // hls.js 의 스트림 제어기는 앱보다 먼저 LEVEL_LOADED 를 받아 그 안에서 LEVEL_UPDATED 를 낸다
    if (fake.updateInsideLoaded) {
      this.on(FakeHls.Events.LEVEL_LOADED, (_event, data) => {
        this.trigger(FakeHls.Events.LEVEL_UPDATED, data);
      });
    }
  }
  on(event: string, handler: Handler) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  off() {}
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
    if (event === FakeHls.Events.LEVEL_LOADED || event === FakeHls.Events.LEVEL_UPDATED) {
      // 목록 사건의 자료는 늘 목록을 싣는다(hls.js LevelLoadedData · LevelUpdatedData 의 details)
      this.latestLevelDetails = (data as { details: Details }).details;
    }
    for (const h of this.handlers.get(event) ?? []) {
      h(event, data);
    }
  }
  attachMedia() {}
}

/** 새 목록의 첫 조각 F — 창 하한은 F + 맨 앞 4초 조각 셋(영상 12초) */
const F = Date.parse('2026-10-25T05:00:00.000Z');
const URL = 'https://m/dvr/k/a1/index.m3u8';

/** 첫 조각 firstMs(영상 0초) · 4초 조각 n 개 미닫이 live 목록 */
function details(firstMs: number, n = 10): Details {
  const fragments = Array.from({ length: n }, (_, i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: firstMs + i * 4_000,
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live: true };
}

/** 맨 앞부터 [영상 시작, F 뒤 ms] 의 4초 조각 목록 */
function placed(points: ReadonlyArray<readonly [number, number]>): Details {
  const fragments = points.map(([start, afterMs]) => ({
    start,
    duration: 4,
    programDateTime: F + afterMs,
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live: true };
}

function sourceOf(
  startAtMs: number | null,
  kind: PlaybackSource['kind'] = 'rewind',
): PlaybackSource {
  return { kind, url: URL, startAtMs, generation: 3, strictStart: false };
}

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

async function mount(source: PlaybackSource, hooks: PlaybackHooks) {
  const ref = { current: document.createElement('video') };
  const view = renderHook(() =>
    useHlsPlayback(ref, source, {}, undefined, undefined, false, undefined, hooks),
  );
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  return view.result;
}

/** 세운 재생기에 첫 목록을 hls.js 순서대로 쏜다(흉내가 안에서 낼 때는 LEVEL_UPDATED 를 따로 쏘지 않는다) */
async function boot(source: PlaybackSource, list: Details, hooks: PlaybackHooks = {}) {
  const onListLoaded = vi.fn();
  const onNotice = vi.fn();
  await mount(source, { onListLoaded, onNotice, ...hooks });
  const hls = lastHls();
  act(() => {
    hls.trigger(FakeHls.Events.MANIFEST_PARSED, {});
    hls.trigger(FakeHls.Events.LEVEL_LOADED, { details: list });
    if (!fake.updateInsideLoaded) hls.trigger(FakeHls.Events.LEVEL_UPDATED, { details: list });
  });
  return { hls, onListLoaded, onNotice };
}

/** onListLoaded 로 받은 startFloorCut 칸 — 칸이 없으면 'none' */
function cutsOf(onListLoaded: ReturnType<typeof vi.fn>): Array<boolean | 'none'> {
  return onListLoaded.mock.calls.map(([list]) => {
    const loaded = list as { startFloorCut?: boolean };
    return 'startFloorCut' in loaded ? loaded.startFloorCut === true : 'none';
  });
}

beforeEach(() => {
  vi.useFakeTimers({ now: F + 60_000 });
  fake.instances.length = 0;
  fake.updateInsideLoaded = false;
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockImplementation(() => 0);
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('다시 받은 재생기의 시작과 하한 잘림 보고(계획 8-3a)', () => {
  it('① 이어 볼 자리가 하한 앞이면 하한(영상 12초)에서 받고 startFloorCut 참 · 재생기 알림 0', async () => {
    const { hls, onListLoaded, onNotice } = await boot(sourceOf(F + 4_000), details(F));
    expect(hls.startLoad.mock.calls).toEqual([[12]]);
    expect(onListLoaded.mock.calls).toEqual([
      [
        {
          url: URL,
          firstMs: F,
          lastEndMs: F + 40_000,
          targetDurationS: 4,
          headSpanMs: 12_000,
          startFloorCut: true,
        },
      ],
    ]);
    expect(onNotice).not.toHaveBeenCalled();
  });

  it('② 하한 위면 그 자리의 영상 시각에서 받고 startFloorCut 거짓', async () => {
    const { hls, onListLoaded } = await boot(sourceOf(F + 20_000), details(F));
    expect(hls.startLoad.mock.calls).toEqual([[20]]);
    expect(cutsOf(onListLoaded)).toEqual([false]);
  });

  it('③ 목록 하한에서(0) 서면 하한에서 받고 startFloorCut 참', async () => {
    const { hls, onListLoaded } = await boot(sourceOf(0), details(F));
    expect(hls.startLoad.mock.calls).toEqual([[12]]);
    expect(cutsOf(onListLoaded)).toEqual([true]);
  });

  it('④ 틈 목록 — 받는 자리와 startFloorCut 이 startCut 표와 같다(영상 12초 · 참)', async () => {
    const gap = placed([
      [0, 0],
      [4, 4_000],
      [8, 20_000],
      [12, 24_000],
      [16, 28_000],
    ]);
    const { hls, onListLoaded } = await boot(sourceOf(F + 16_000), gap);
    expect(hls.startLoad.mock.calls).toEqual([[12]]);
    expect(cutsOf(onListLoaded)).toEqual([true]);
  });

  it('⑤ 겹침 목록 — 받는 자리와 startFloorCut 이 startCut 표와 같다(영상 13.8초 · 거짓)', async () => {
    const overlap = placed([
      [0, 0],
      [4, 2_000],
      [8, 6_000],
      [12, 10_000],
      [16, 14_000],
    ]);
    const { hls, onListLoaded } = await boot(sourceOf(F + 11_800), overlap);
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    expect(hls.startLoad.mock.calls[0]?.[0]).toBeCloseTo(13.8);
    expect(cutsOf(onListLoaded)).toEqual([false]);
  });

  it('⑥ 실물 순서(LEVEL_LOADED 처리 안의 LEVEL_UPDATED 가 기동 처리기보다 먼저)여도 startFloorCut 이 실린다', async () => {
    fake.updateInsideLoaded = true;
    const { hls, onListLoaded } = await boot(sourceOf(F + 4_000), details(F));
    expect(hls.startLoad.mock.calls).toEqual([[12]]);
    expect(cutsOf(onListLoaded)).toEqual([true]);
  });

  it('⑦ 둘째 목록부터는 칸 없음 · 시작 요청 없는 되감기 · live · vod 재생기도 칸 없음', async () => {
    const first = await boot(sourceOf(F + 4_000), details(F));
    act(() => first.hls.trigger(FakeHls.Events.LEVEL_UPDATED, { details: details(F + 4_000) }));
    expect(cutsOf(first.onListLoaded)).toEqual([true, 'none']);
    for (const source of [
      sourceOf(null),
      sourceOf(F + 4_000, 'live'),
      sourceOf(F + 4_000, 'vod'),
    ]) {
      const other = await boot(source, details(F));
      expect(cutsOf(other.onListLoaded), source.kind).toEqual(['none']);
    }
  });

  it('⑧ (P44) 새 재생기 첫 목록 전 시크는 세션에 가지 않는다 — onSeekRequest 0 · seekToDate unknown(지금 동작)', async () => {
    const onSeekRequest = vi.fn();
    const result = await mount(sourceOf(F + 4_000), { onSeekRequest });
    act(() => {
      result.current.seekToFraction(0.5);
      result.current.seekBy(-10);
    });
    let dated: string | undefined;
    act(() => {
      dated = result.current.seekToDate?.(F + 8_000);
    });
    expect(onSeekRequest).not.toHaveBeenCalled();
    expect(dated).toBe('unknown');
  });
});
