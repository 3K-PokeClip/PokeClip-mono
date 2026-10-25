import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 재생기 교체 · 사건 올림 · 창 가장자리(설계 5.3.1 · 5.4절 — R15 · R17 · R23 · R47). hls.js 와 <video> 를 흉내 낸다:
// 흉내 hls.js 는 시험이 사건(LEVEL_LOADED · LEVEL_UPDATED · ERROR)을 쏘고, <video> 는 currentTime · seekable · paused 를 시험이 쥔다.

interface FakeHlsLike {
  config: Record<string, unknown>;
  startLoads: number[];
  loaded: string[];
  destroyed: boolean;
  latestLevelDetails: { fragments: Frag[]; targetduration: number; live: boolean } | null;
  liveSyncPosition: number | null;
  trigger: (event: string, data: unknown) => void;
}
interface Frag {
  start: number;
  duration: number;
  programDateTime: number | null;
}

const fake = vi.hoisted(() => ({ instances: [] as FakeHlsLike[], supported: true }));

vi.mock('hls.js', () => {
  class FakeHls {
    static isSupported() {
      return fake.supported;
    }
    static Events = {
      ERROR: 'hlsError',
      MANIFEST_PARSED: 'hlsManifestParsed',
      FRAG_LOADED: 'hlsFragLoaded',
      LEVEL_LOADED: 'hlsLevelLoaded',
      LEVEL_UPDATED: 'hlsLevelUpdated',
    };
    static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
    readonly handlers = new Map<string, ((event: string, data: unknown) => void)[]>();
    startLoads: number[] = [];
    loaded: string[] = [];
    destroyed = false;
    latestLevelDetails: FakeHlsLike['latestLevelDetails'] = null;
    liveSyncPosition: number | null = null;
    constructor(readonly config: Record<string, unknown>) {
      fake.instances.push(this as unknown as FakeHlsLike);
    }
    on(event: string, handler: (event: string, data: unknown) => void) {
      this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
    }
    trigger(event: string, data: unknown) {
      const details = (data as { details?: FakeHlsLike['latestLevelDetails'] } | null)?.details;
      if (details && typeof details === 'object') this.latestLevelDetails = details;
      for (const h of this.handlers.get(event) ?? []) h(event, data);
    }
    attachMedia() {}
    loadSource(url: string) {
      this.loaded.push(url);
    }
    startLoad(pos: number) {
      this.startLoads.push(pos);
    }
    recoverMediaError() {}
    destroy() {
      this.destroyed = true;
    }
  }
  return { default: FakeHls };
});

const PDT0 = Date.parse('2026-10-25T05:00:00.000Z');
/** 영상 0초부터 4초 조각 n 개(PDT0 부터) */
function frags(n: number, from = 0): Frag[] {
  return Array.from({ length: n }, (_, i) => ({
    start: (from + i) * 4,
    duration: 4,
    programDateTime: PDT0 + (from + i) * 4_000,
  }));
}
function details(fragments: Frag[], live = true) {
  return { fragments, targetduration: 4, live };
}

const video = { el: null as HTMLVideoElement | null, paused: true, seekEnd: 40 };

function lastHls(): FakeHlsLike {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

async function settle() {
  await act(async () => {
    await vi.dynamicImportSettled();
  });
}

function src(patch: Partial<PlaybackSource> = {}): PlaybackSource {
  return {
    kind: 'live',
    url: 'https://m/live/k/index.m3u8',
    startAtMs: null,
    generation: 0,
    ...patch,
  };
}

function mount(source: PlaybackSource, hooks: PlaybackHooks = {}) {
  const el = document.createElement('video');
  video.el = el;
  const ref = { current: el };
  return renderHook(
    ({ s, h }) => useHlsPlayback(ref, s, {}, undefined, undefined, false, undefined, h),
    { initialProps: { s: source, h: hooks } },
  );
}

beforeEach(() => {
  vi.useFakeTimers({ now: PDT0 + 40_000 });
  fake.instances.length = 0;
  fake.supported = true;
  video.paused = true;
  video.seekEnd = 40;
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function play(
    this: HTMLMediaElement,
  ) {
    video.paused = false;
    return Promise.resolve();
  });
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {
    video.paused = true;
  });
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockImplementation(() => video.paused);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockReturnValue(4);
  vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockImplementation(
    () => ({ length: 1, start: () => 0, end: () => video.seekEnd }) as unknown as TimeRanges,
  );
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('재생기 소스와 세대(5.4절)', () => {
  it('kind 마다 설정이 다르다 — 라이브 backBuffer 90 · 되감기 120 · 둘 다 catch-up 끔 바탕', async () => {
    mount(src());
    await settle();
    expect(lastHls().config).toMatchObject({ backBufferLength: 90, maxLiveSyncPlaybackRate: 1 });
    mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }));
    await settle();
    expect(lastHls().config).toMatchObject({ backBufferLength: 120, maxLiveSyncPlaybackRate: 1 });
  });

  it('같은 url 이어도 세대가 오르면 재생기를 새로 세운다', async () => {
    const view = mount(src());
    await settle();
    view.rerender({ s: src({ generation: 1 }), h: {} });
    await settle();
    expect(fake.instances).toHaveLength(2);
    expect(fake.instances[0]!.destroyed).toBe(true);
  });

  it('startAtMs 가 있으면 자동 시작을 끄고 첫 LEVEL_LOADED 에서 startLoad 를 한 번만 부른다(R15)', async () => {
    mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8', startAtMs: PDT0 + 20_000 }));
    await settle();
    const hls = lastHls();
    expect(hls.config.autoStartLoad).toBe(false);
    act(() => hls.trigger('hlsLevelLoaded', { details: details(frags(10)) }));
    act(() => hls.trigger('hlsLevelLoaded', { details: details(frags(10, 1)) }));
    act(() => hls.trigger('hlsLevelLoaded', { details: details(frags(10, 2)) }));
    expect(hls.startLoads).toEqual([20]);
  });

  it('startAtMs 가 창 하한 앞이면 하한(첫 조각 + 3 TD)으로 자른다 · vod 는 첫 조각 그대로(R47)', async () => {
    mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8', startAtMs: PDT0 }));
    await settle();
    act(() => lastHls().trigger('hlsLevelLoaded', { details: details(frags(10)) }));
    expect(lastHls().startLoads).toEqual([12]);
    mount(src({ kind: 'vod', url: 'https://m/vod/k/r/index.m3u8', startAtMs: PDT0 }));
    await settle();
    act(() => lastHls().trigger('hlsLevelLoaded', { details: details(frags(2), false) }));
    expect(lastHls().startLoads).toEqual([0]);
  });

  it('startAtMs 가 없으면 자동 시작이다(지금 동작)', async () => {
    mount(src());
    await settle();
    expect(lastHls().config.autoStartLoad).toBeUndefined();
  });
});

describe('사건 올림 — ENDLIST 와 끝 도달을 따로(cx H1)', () => {
  it('LEVEL_UPDATED 의 live:false 는 ENDLIST 한 번 · 끝 도달이 아니다', async () => {
    const onEndlist = vi.fn();
    const onEndReached = vi.fn();
    mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), {
      onEndlist,
      onEndReached,
    });
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10)) }));
    expect(onEndlist).not.toHaveBeenCalled();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10), false) }));
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10), false) }));
    expect(onEndlist).toHaveBeenCalledTimes(1);
    expect(onEndReached).not.toHaveBeenCalled();
  });

  it('목록 갱신마다 url · 첫 시각 · 끝 · TD 를 올린다(observedEndMs 재료)', async () => {
    const onListLoaded = vi.fn();
    mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), { onListLoaded });
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10)) }));
    expect(onListLoaded).toHaveBeenLastCalledWith({
      url: 'https://m/dvr/k/a/index.m3u8',
      firstMs: PDT0,
      lastEndMs: PDT0 + 40_000,
      targetDurationS: 4,
    });
  });

  it('<video> 의 ended 는 끝 도달이다', async () => {
    const onEndReached = vi.fn();
    mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), { onEndReached });
    await settle();
    act(() => video.el!.dispatchEvent(new Event('ended')));
    expect(onEndReached).toHaveBeenCalledTimes(1);
  });

  it('목록 끝 정체(끝 1초 안 · waiting · 3 TD 넘게 안 자람)도 끝 도달이다 — 한 번만', async () => {
    const onEndReached = vi.fn();
    mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), { onEndReached });
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10)) }));
    video.el!.currentTime = 39.5;
    video.paused = false;
    act(() => video.el!.dispatchEvent(new Event('waiting')));
    await act(async () => vi.advanceTimersByTime(12_000));
    expect(onEndReached).not.toHaveBeenCalled();
    await act(async () => vi.advanceTimersByTime(2_000));
    expect(onEndReached).toHaveBeenCalledTimes(1);
    await act(async () => vi.advanceTimersByTime(5_000));
    expect(onEndReached).toHaveBeenCalledTimes(1);
  });

  it('라이브 목록 치명 404 는 라이브 닫힘 · 치명 400 은 다시 세움 요청(R17)', async () => {
    const onLiveClosed = vi.fn();
    const onLive400 = vi.fn();
    mount(src(), { onLiveClosed, onLive400 });
    await settle();
    const fatal = (code: number) => ({ fatal: true, type: 'networkError', response: { code } });
    act(() => lastHls().trigger('hlsError', fatal(400)));
    expect(onLive400).toHaveBeenCalledTimes(1);
    act(() => lastHls().trigger('hlsError', fatal(404)));
    expect(onLiveClosed).toHaveBeenCalledTimes(1);
  });
});

describe('창 가장자리를 hls.js 보다 먼저 지키기(5.3.1절 · R23)', () => {
  const live = () => mount(src(), {}).result;

  async function sessionLive(hooks: PlaybackHooks) {
    const view = mount(src(), hooks);
    await settle();
    lastHls().liveSyncPosition = 38;
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10)) }));
    video.el!.currentTime = 36;
    return view;
  }

  it('세션 모드의 시크는 currentTime 을 쓰기 전에 세션에 목표 · 창을 넘긴다(라이브 창 밖)', async () => {
    const onSeekRequest = vi.fn();
    const view = await sessionLive({ onSeekRequest });
    act(() => view.result.current.seekToDate?.(PDT0 + 2_000));
    expect(onSeekRequest).toHaveBeenCalledWith(PDT0 + 2_000, {
      lowerMs: PDT0 + 12_000,
      endMs: PDT0 + 38_000,
    });
    expect(video.el!.currentTime).toBe(36);
  });

  it('seekToMs 는 실제 시각을 영상 초로 바꿔 옮긴다', async () => {
    const view = await sessionLive({ onSeekRequest: vi.fn() });
    act(() => view.result.current.seekToMs(PDT0 + 20_000));
    expect(video.el!.currentTime).toBe(20);
  });

  it('일시정지의 원래 시각을 지킨다 — 멈춘 동안 라이브러리가 옮겨도 재개 판정은 멈춘 시각으로', async () => {
    const onSeekRequest = vi.fn();
    const view = await sessionLive({ onSeekRequest });
    act(() => view.result.current.seekToMs(PDT0 + 14_000));
    video.paused = false;
    act(() => {
      video.paused = true;
      video.el!.dispatchEvent(new Event('pause'));
    });
    // 창이 밀려 하한이 멈춘 자리를 지나기 전에 라이브러리가 재생점을 끝으로 옮긴다(synchronizeToLiveEdge)
    video.el!.currentTime = 38;
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10, 1)) }));
    act(() => {
      video.paused = false;
      video.el!.dispatchEvent(new Event('play'));
    });
    expect(onSeekRequest).toHaveBeenLastCalledWith(PDT0 + 14_000, expect.anything());
  });

  it('멈춘 자리가 창 하한에 닿으면 멈춘 채로 세션에 넘긴다(1초 보조 틱)', async () => {
    const onSeekRequest = vi.fn();
    const view = await sessionLive({ onSeekRequest });
    act(() => view.result.current.seekToMs(PDT0 + 13_000));
    act(() => {
      video.paused = true;
      video.el!.dispatchEvent(new Event('pause'));
    });
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10, 1)) }));
    await act(async () => vi.advanceTimersByTime(1_000));
    expect(onSeekRequest).toHaveBeenCalledWith(PDT0 + 13_000, expect.anything());
    await act(async () => vi.advanceTimersByTime(3_000));
    expect(onSeekRequest).toHaveBeenCalledTimes(1);
  });

  it('LIVE(실시간으로) 는 세션 모드면 세션에 넘긴다', async () => {
    const onLiveButton = vi.fn();
    const view = await sessionLive({ onSeekRequest: vi.fn(), onLiveButton });
    act(() => view.result.current.returnToLive());
    expect(onLiveButton).toHaveBeenCalledTimes(1);
  });

  it('세션이 없으면(지금 동작) 시크를 재생기가 바로 쓴다', async () => {
    const result = live();
    await settle();
    lastHls().liveSyncPosition = 38;
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10)) }));
    video.el!.currentTime = 36;
    act(() => result.current.seekBy(10));
    expect(video.el!.currentTime).toBe(26);
  });

  it('되감기 재생 중 라이브러리가 끝 쪽으로 튀면 튀기 전 자리를 세션에 다시 넘긴다 — 그새 창이 밀렸으면 새 하한으로(cx H2 · R23)', async () => {
    const onNotice = vi.fn();
    const requests: [number, { lowerMs: number; endMs: number }][] = [];
    // 세션 흉내: 하한 앞 목표는 하한으로 잘라 안에서 시크한다(rewindSwitch 의 seekInList)
    const view = mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), {});
    view.rerender({
      s: src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }),
      h: {
        onNotice,
        onSeekRequest: (t, v) => {
          requests.push([t, v]);
          view.result.current.seekToMs(Math.max(t, v.lowerMs));
        },
      },
    });
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10)) }));
    act(() => view.result.current.seekToMs(PDT0 + 16_000));
    video.paused = false;
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    // 16초를 그린 뒤 창이 밀려(첫 조각 20초 · 하한 32초) 라이브러리가 목록 끝으로 옮긴다
    await act(async () => vi.advanceTimersByTime(1_000));
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10, 5)) }));
    video.el!.currentTime = 56;
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    expect(requests).toEqual([[PDT0 + 16_000, { lowerMs: PDT0 + 32_000, endMs: PDT0 + 60_000 }]]);
    expect(video.el!.currentTime).toBe(32);
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    // 되돌림이 또 튀면 두지 않고 알린다
    video.el!.currentTime = 56;
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    expect(requests).toHaveLength(1);
    expect(video.el!.currentTime).toBe(56);
    expect(onNotice).toHaveBeenCalledTimes(1);
  });

  it('ENDLIST 목록에서 끝 → 뒤로 시크 → 다시 끝이면 끝 도달을 두 번 올린다(cx H1 · R55)', async () => {
    const onEndReached = vi.fn();
    const view = mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), {
      onEndReached,
      onSeekRequest: vi.fn(),
    });
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10), false) }));
    video.el!.currentTime = 40;
    act(() => video.el!.dispatchEvent(new Event('ended')));
    act(() => view.result.current.seekToMs(PDT0 + 10_000));
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    video.el!.currentTime = 40;
    act(() => video.el!.dispatchEvent(new Event('ended')));
    expect(onEndReached).toHaveBeenCalledTimes(2);
  });

  it('끝 근처에서 옮긴 것은 끝 도달을 다시 열지 않는다(같은 끝을 두 번 올리지 않음)', async () => {
    const onEndReached = vi.fn();
    mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), { onEndReached });
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10), false) }));
    video.el!.currentTime = 40;
    act(() => video.el!.dispatchEvent(new Event('ended')));
    video.el!.currentTime = 39.5;
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    act(() => video.el!.dispatchEvent(new Event('ended')));
    expect(onEndReached).toHaveBeenCalledTimes(1);
  });

  it('hls.js 가 없는 브라우저(네이티브 HLS)는 세션 고리가 있어도 지금처럼 라이브 창 안에서 시크한다(cx M)', async () => {
    fake.supported = false;
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    const onSeekRequest = vi.fn();
    const view = mount(src(), { onSeekRequest });
    await settle();
    video.el!.currentTime = 36;
    await act(async () => vi.advanceTimersByTime(1_000));
    expect(view.result.current.windowSeconds).toBeGreaterThan(0);
    // 라이브 끝 = seekable 끝(40) − 물러남 3초 = 37 · 시차 1초 + 10초 → 26초
    act(() => view.result.current.seekBy(10));
    expect(video.el!.currentTime).toBe(26);
    expect(onSeekRequest).not.toHaveBeenCalled();
  });

  it('멈춘 채 넘겼다가 사용자가 다시 틀면 다음 재생기의 자동 재생을 막지 않는다(keepPaused 잔존 — cx M)', async () => {
    const onSeekRequest = vi.fn();
    const view = await sessionLive({ onSeekRequest });
    act(() => view.result.current.seekToMs(PDT0 + 13_000));
    act(() => {
      video.paused = true;
      video.el!.dispatchEvent(new Event('pause'));
    });
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10, 1)) }));
    await act(async () => vi.advanceTimersByTime(1_000));
    expect(onSeekRequest).toHaveBeenCalledTimes(1);
    // 세션이 교체하지 않고(준비 전) 사용자가 다시 튼다
    act(() => {
      video.paused = false;
      video.el!.dispatchEvent(new Event('play'));
    });
    view.rerender({ s: src({ generation: 1 }), h: { onSeekRequest } });
    await settle();
    const play = vi.mocked(HTMLMediaElement.prototype.play);
    play.mockClear();
    act(() => lastHls().trigger('hlsManifestParsed', {}));
    expect(play).toHaveBeenCalled();
  });

  it('앱이 끝 쪽으로 옮긴 시크는 라이브러리 시크가 아니다 — 되돌리지 않는다', async () => {
    const view = mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), {
      onSeekRequest: vi.fn(),
    });
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10)) }));
    act(() => view.result.current.seekToMs(PDT0 + 16_000));
    video.paused = false;
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    await act(async () => vi.advanceTimersByTime(1_000));
    act(() => view.result.current.seekToMs(PDT0 + 36_000));
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    expect(video.el!.currentTime).toBe(36);
  });
});
