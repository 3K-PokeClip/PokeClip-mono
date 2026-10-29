import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 재생기 교체 · 사건 올림 · 창 가장자리(설계 5.3.1 · 5.4절 — R15 · R17 · R23 · R47). hls.js 와 <video> 를 흉내 낸다:
// 흉내 hls.js 는 시험이 사건(LEVEL_LOADED · LEVEL_UPDATED · FRAG_BUFFERED · ERROR)을 쏘고, <video> 는
// currentTime · seekable · paused 를 시험이 쥔다.

interface FakeHlsLike {
  config: Record<string, unknown>;
  startLoads: (number | undefined)[];
  loaded: string[];
  destroyed: boolean;
  latestLevelDetails: Details | null;
  liveSyncPosition: number | null;
  hasEnoughToStart: boolean;
  trigger: (event: string, data: unknown) => void;
  recoverMediaError: () => void;
}
interface Frag {
  start: number;
  duration: number;
  programDateTime: number | null;
}
interface Details {
  fragments: Frag[];
  fragmentStart: number;
  targetduration: number;
  live: boolean;
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
      MEDIA_DETACHING: 'hlsMediaDetaching',
      FRAG_BUFFERED: 'hlsFragBuffered',
    };
    static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
    // hls.js 1.7.3 의 값 그대로(errors.ts)
    static ErrorDetails = {
      MANIFEST_LOAD_ERROR: 'manifestLoadError',
      LEVEL_LOAD_ERROR: 'levelLoadError',
      AUDIO_TRACK_LOAD_ERROR: 'audioTrackLoadError',
      FRAG_LOAD_ERROR: 'fragLoadError',
    };
    readonly handlers = new Map<string, ((event: string, data: unknown) => void)[]>();
    startLoads: (number | undefined)[] = [];
    loaded: string[] = [];
    destroyed = false;
    latestLevelDetails: FakeHlsLike['latestLevelDetails'] = null;
    liveSyncPosition: number | null = null;
    hasEnoughToStart = true;
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
    media: HTMLMediaElement | null = null;
    attachMedia(media: HTMLMediaElement) {
      this.media = media;
    }
    loadSource(url: string) {
      this.loaded.push(url);
    }
    startLoad(pos?: number) {
      this.startLoads.push(pos);
    }
    // hls.js 1.7.3 의 복구(hls.ts 699-712): 재생점을 먼저 잡고 뗀다. 떼기의 첫 처리기는 buffer-controller
    // (먼저 등록 · buffer-controller.ts 186)라 src 를 지우고 load() 해 재생점을 0 으로 만든다(575-579) — 앱의
    // MEDIA_DETACHING 처리기는 늘 0 을 본다. 다시 붙여 기동 전으로 돌아가고(stream-controller.ts 532), 잡아 둔
    // 재생점에서 startLoad 한다(0 이면 자동 시작이 아닐 때만 인자 없이). 시험은 기동한 뒤에만 부른다(hls.started)
    recoverMediaError() {
      const time = this.media?.currentTime ?? 0;
      if (this.media) this.media.currentTime = 0;
      this.trigger('hlsMediaDetaching', {});
      this.hasEnoughToStart = false;
      if (time) this.startLoad(time);
      else if (this.config.autoStartLoad === false) this.startLoad();
    }
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
function details(fragments: Frag[], live = true): Details {
  return { fragments, fragmentStart: fragments[0]?.start ?? 0, targetduration: 4, live };
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
    const fatal = (code: number) => ({
      fatal: true,
      type: 'networkError',
      details: 'levelLoadError',
      response: { code },
    });
    act(() => lastHls().trigger('hlsError', fatal(400)));
    expect(onLive400).toHaveBeenCalledTimes(1);
    act(() => lastHls().trigger('hlsError', fatal(404)));
    expect(onLiveClosed).toHaveBeenCalledTimes(1);
  });

  it.each(['manifestLoadError', 'audioTrackLoadError'])(
    '목록 받기 오류(%s)의 치명 404 도 라이브 닫힘이다',
    async (details) => {
      const onLiveClosed = vi.fn();
      mount(src(), { onLiveClosed });
      await settle();
      const fatal = { fatal: true, type: 'networkError', details, response: { code: 404 } };
      act(() => lastHls().trigger('hlsError', fatal));
      expect(onLiveClosed).toHaveBeenCalledTimes(1);
    },
  );

  it.each([404, 400])(
    '조각 받기의 치명 %i 는 라이브 닫힘 · 다시 세움이 아니다 — 재생기를 두고 한 번 다시 받는다(cx H1)',
    async (code) => {
      const onLiveClosed = vi.fn();
      const onLive400 = vi.fn();
      mount(src(), { onLiveClosed, onLive400 });
      await settle();
      const hls = lastHls();
      const fatal = {
        fatal: true,
        type: 'networkError',
        details: 'fragLoadError',
        response: { code },
      };
      act(() => hls.trigger('hlsError', fatal));
      expect(onLiveClosed).not.toHaveBeenCalled();
      expect(onLive400).not.toHaveBeenCalled();
      expect(hls.destroyed).toBe(false);
      expect(hls.startLoads).toEqual([undefined]);
    },
  );
});

describe('멎음 알림(POK-337)', () => {
  it('복구를 포기해 멎은 뒤 같은 주소로 새로 선 재생기가 재생에 성공하면 알림을 내린다(cx M · stopped 잔존)', async () => {
    const view = mount(src());
    await settle();
    const fatal = { fatal: true, type: 'mediaError', details: 'bufferStalledError' };
    act(() => lastHls().trigger('hlsError', fatal));
    act(() => lastHls().trigger('hlsError', fatal));
    expect(view.result.current.playbackError).toBe('영상이 끊겼어요. 새로고침하면 다시 연결해요');
    view.rerender({ s: src({ generation: 1 }), h: {} });
    await settle();
    expect(view.result.current.playbackError).not.toBeNull();
    act(() => video.el!.dispatchEvent(new Event('playing')));
    expect(view.result.current.playbackError).toBeNull();
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

  it('카드 시각이 지금 목록보다 이르고 되감기가 준비되지 않았으면 세션에 넘기지 않고 범위 밖이다(POK-498)', async () => {
    const onSeekRequest = vi.fn();
    const view = await sessionLive({ onSeekRequest });
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10, 5)) }));
    let result: string | undefined;
    act(() => {
      result = view.result.current.seekToDate?.(PDT0 + 2_000);
    });
    expect(result).toBe('outside');
    expect(onSeekRequest).not.toHaveBeenCalled();
    expect(video.el!.currentTime).toBe(36);
  });

  it('되감기가 준비됐으면 그 하한까지는 세션에 넘긴다 · 그보다 이르면 범위 밖이다(POK-498)', async () => {
    const onSeekRequest = vi.fn();
    const view = await sessionLive({ onSeekRequest, axisLowerMs: PDT0 + 1_000 });
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10, 5)) }));
    let result: string | undefined;
    act(() => {
      result = view.result.current.seekToDate?.(PDT0 + 2_000);
    });
    expect(result).toBe('moved');
    expect(onSeekRequest).toHaveBeenCalledWith(PDT0 + 2_000, expect.anything());
    act(() => {
      result = view.result.current.seekToDate?.(PDT0 - 500);
    });
    expect(result).toBe('outside');
    expect(onSeekRequest).toHaveBeenCalledTimes(1);
  });

  it('세션 없는 라이브도 되감기 창(seekable)보다 이른 시각은 옮기지 않고 범위 밖이다(POK-498)', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockImplementation(
      () => ({ length: 1, start: () => 20, end: () => 40 }) as unknown as TimeRanges,
    );
    const view = mount(src());
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(5, 5)) }));
    video.el!.currentTime = 36;
    let result: string | undefined;
    act(() => {
      result = view.result.current.seekToDate?.(PDT0 + 2_000);
    });
    expect(result).toBe('outside');
    expect(video.el!.currentTime).toBe(36);
    act(() => {
      result = view.result.current.seekToDate?.(PDT0 + 24_000);
    });
    expect(result).toBe('moved');
    expect(video.el!.currentTime).toBeCloseTo(24, 0);
    // 카드 초는 정각 초로 내린 값이라 창 처음보다 1초 안쪽 이른 것은 창 안으로 친다
    act(() => {
      result = view.result.current.seekToDate?.(PDT0 + 19_500);
    });
    expect(result).toBe('moved');
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

  it('되감기가 준비된 라이브는 멈춘 자리가 창 하한에 닿으면 멈춘 채로 세션에 넘긴다(1초 보조 틱)', async () => {
    const onSeekRequest = vi.fn();
    const view = await sessionLive({ onSeekRequest, axisLowerMs: PDT0 });
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

  it('되감기가 준비되지 않은 라이브는 멈춘 동안 넘기지 않고 재개 때 넘긴다 — 창이 밀릴 때마다 알리지 않게(5.3.1절 3번 · cc M2)', async () => {
    const onSeekRequest = vi.fn();
    const view = await sessionLive({ onSeekRequest });
    act(() => view.result.current.seekToMs(PDT0 + 13_000));
    act(() => {
      video.paused = true;
      video.el!.dispatchEvent(new Event('pause'));
    });
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10, 1)) }));
    await act(async () => vi.advanceTimersByTime(1_000));
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10, 2)) }));
    await act(async () => vi.advanceTimersByTime(4_000));
    expect(onSeekRequest).not.toHaveBeenCalled();
    act(() => {
      video.paused = false;
      video.el!.dispatchEvent(new Event('play'));
    });
    expect(onSeekRequest).toHaveBeenCalledTimes(1);
    expect(onSeekRequest).toHaveBeenCalledWith(PDT0 + 13_000, expect.anything());
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

  it('덮개가 선 동안(holdPlay) 재생 단추는 재생을 시작하지 않는다 — 끝난 목록이 처음부터 돌지 않게(cc 확인 패스)', async () => {
    const view = mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), {
      onSeekRequest: vi.fn(),
      holdPlay: true,
    });
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10), false) }));
    video.paused = true;
    const play = vi.mocked(HTMLMediaElement.prototype.play);
    play.mockClear();
    act(() => view.result.current.togglePlay());
    expect(play).not.toHaveBeenCalled();
    view.rerender({
      s: src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }),
      h: { onSeekRequest: vi.fn(), holdPlay: false },
    });
    act(() => view.result.current.togglePlay());
    expect(play).toHaveBeenCalledTimes(1);
  });

  it.each([0.5, 1.001])(
    'ENDLIST 목록에서 끝 → %s초 뒤로 시크 → 다시 끝이면 두 번째 끝 도달도 올린다(폭 무관 — cx H1)',
    async (back) => {
      const onEndReached = vi.fn();
      mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), { onEndReached });
      await settle();
      act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10), false) }));
      video.el!.currentTime = 40;
      act(() => video.el!.dispatchEvent(new Event('ended')));
      video.el!.currentTime = 40 - back;
      act(() => video.el!.dispatchEvent(new Event('seeking')));
      video.el!.currentTime = 40;
      act(() => video.el!.dispatchEvent(new Event('ended')));
      expect(onEndReached).toHaveBeenCalledTimes(2);
    },
  );

  it('목록 끝 정체 뒤 끝 1초 안으로 물러나면 시크 뒤의 새 waiting 을 봐야 다시 올린다(같은 끝을 두 번 올리지 않음)', async () => {
    const onEndReached = vi.fn();
    mount(src({ kind: 'rewind', url: 'https://m/dvr/k/a/index.m3u8' }), { onEndReached });
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(10)) }));
    video.el!.currentTime = 39.5;
    video.paused = false;
    act(() => video.el!.dispatchEvent(new Event('waiting')));
    await act(async () => vi.advanceTimersByTime(14_000));
    expect(onEndReached).toHaveBeenCalledTimes(1);
    video.el!.currentTime = 39.2;
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    await act(async () => vi.advanceTimersByTime(2_000));
    expect(onEndReached).toHaveBeenCalledTimes(1);
    act(() => video.el!.dispatchEvent(new Event('waiting')));
    await act(async () => vi.advanceTimersByTime(1_000));
    expect(onEndReached).toHaveBeenCalledTimes(2);
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
    const view = await sessionLive({ onSeekRequest, axisLowerMs: PDT0 });
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
    view.rerender({ s: src({ generation: 1 }), h: { onSeekRequest, axisLowerMs: PDT0 } });
    await settle();
    const play = vi.mocked(HTMLMediaElement.prototype.play);
    play.mockClear();
    act(() => lastHls().trigger('hlsManifestParsed', {}));
    expect(play).toHaveBeenCalled();
  });
});

describe('되감기 창 하한을 LEVEL_UPDATED 에서 먼저 지키기(설계 5.3.1절 · 진단 28_)', () => {
  const REWIND = 'https://m/dvr/k/a/index.m3u8';

  /** 되감기 재생기 · 첫 목록(첫 조각 0초) 뒤 재생점 at · 세션 흉내(하한 앞은 하한으로 잘라 안에서 시크) */
  async function rewindAt(at: number, hooks: PlaybackHooks = {}) {
    const onNotice = vi.fn();
    const onSeekRequest = vi.fn();
    const view = mount(src({ kind: 'rewind', url: REWIND }), { onNotice, onSeekRequest, ...hooks });
    await settle();
    video.el!.currentTime = at;
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(13)) }));
    return { view, onNotice, onSeekRequest };
  }
  /** 창이 n 조각 밀린 목록 갱신 */
  const slide = (n: number) =>
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(13, n)) }));

  it.each([1, 2, 3])(
    '재생점이 첫 조각 + 1 TD 앞이면(readyState %s) 처리기 안에서 바로 창 하한(첫 조각 + 3 TD)으로 옮기고 한 번 알린다',
    async (readyState) => {
      vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockReturnValue(readyState);
      const { onNotice, onSeekRequest } = await rewindAt(15);
      video.paused = false;
      slide(3);
      expect(video.el!.currentTime).toBe(24);
      expect(onNotice).toHaveBeenCalledTimes(1);
      expect(onNotice).toHaveBeenCalledWith('가장 오래된 지점으로 옮겼어요');
      expect(onSeekRequest).not.toHaveBeenCalled();
    },
  );

  it('옮길 때마다 한 줄 남긴다 — 알림은 조용한 구간에 묶여도 기록은 빠지지 않는다(cc L5)', async () => {
    const info = vi.spyOn(console, 'info').mockImplementation(() => {});
    await rewindAt(15);
    slide(3);
    video.el!.currentTime = 17;
    slide(4);
    expect(info).toHaveBeenCalledTimes(2);
    expect(info).toHaveBeenLastCalledWith(
      '[player] 되감기 재생점을 창 하한으로 옮겼어요:',
      'seek',
      28,
    );
  });

  it('첫 조각 + 1 TD 이상이면 두고 알리지 않는다', async () => {
    const { onNotice } = await rewindAt(16);
    slide(3);
    expect(video.el!.currentTime).toBe(16);
    expect(onNotice).not.toHaveBeenCalled();
  });

  it('멈춰 있어도 옮긴다 — hls.js 는 일시정지를 보지 않는다 · 재개는 옮긴 자리로 판정해 다시 넘기지 않는다', async () => {
    const { onNotice, onSeekRequest } = await rewindAt(15);
    act(() => {
      video.paused = true;
      video.el!.dispatchEvent(new Event('pause'));
    });
    slide(3);
    expect(video.el!.currentTime).toBe(24);
    expect(onNotice).toHaveBeenCalledTimes(1);
    act(() => {
      video.paused = false;
      video.el!.dispatchEvent(new Event('play'));
    });
    expect(onSeekRequest).not.toHaveBeenCalled();
    expect(onNotice).toHaveBeenCalledTimes(1);
  });

  it('멈춘 채 창이 밀려도 1초 틱이 세션에 넘기지 않는다(옮기는 자리는 LEVEL_UPDATED 하나)', async () => {
    const { onSeekRequest } = await rewindAt(13);
    act(() => {
      video.paused = true;
      video.el!.dispatchEvent(new Event('pause'));
    });
    slide(1);
    await act(async () => vi.advanceTimersByTime(3_000));
    expect(onSeekRequest).not.toHaveBeenCalled();
  });

  it('2 TD 안에 또 옮기면 알림을 겹치지 않는다 · 그 뒤에 옮기면 다시 알린다', async () => {
    const { onNotice } = await rewindAt(15);
    slide(3);
    video.el!.currentTime = 27;
    slide(6);
    expect(video.el!.currentTime).toBe(36);
    expect(onNotice).toHaveBeenCalledTimes(1);
    await act(async () => vi.advanceTimersByTime(9_000));
    video.el!.currentTime = 39;
    slide(9);
    expect(video.el!.currentTime).toBe(48);
    expect(onNotice).toHaveBeenCalledTimes(2);
  });

  it('기동 전(hasEnoughToStart 거짓)은 currentTime 대신 받기 위치를 창 하한으로 다시 잡는다', async () => {
    const onNotice = vi.fn();
    mount(src({ kind: 'rewind', url: REWIND, startAtMs: PDT0 }), {
      onNotice,
      onSeekRequest: vi.fn(),
    });
    await settle();
    const hls = lastHls();
    hls.hasEnoughToStart = false;
    act(() => hls.trigger('hlsLevelLoaded', { details: details(frags(13)) }));
    act(() => hls.trigger('hlsLevelUpdated', { details: details(frags(13)) }));
    expect(hls.startLoads).toEqual([12]);
    video.el!.currentTime = 0;
    slide(3);
    expect(hls.startLoads).toEqual([12, 24]);
    expect(video.el!.currentTime).toBe(0);
    expect(onNotice).toHaveBeenCalledTimes(1);
    slide(4);
    expect(hls.startLoads).toEqual([12, 24]);
  });

  it('readyState 0 이면 기동 뒤라도 받기 위치로 다시 잡는다 — currentTime 쓰기가 먹지 않는다', async () => {
    mount(src({ kind: 'rewind', url: REWIND, startAtMs: PDT0 }), { onSeekRequest: vi.fn() });
    await settle();
    const hls = lastHls();
    vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockReturnValue(0);
    act(() => hls.trigger('hlsLevelLoaded', { details: details(frags(13)) }));
    video.el!.currentTime = 30;
    slide(3);
    expect(hls.startLoads).toEqual([12, 24]);
    expect(video.el!.currentTime).toBe(30);
  });

  it('기동 전 시크 자리도 받기 위치로 본다(hls.js 가 seeking 에서 받기 위치를 그 자리로 옮기는 것과 같게)', async () => {
    mount(src({ kind: 'rewind', url: REWIND, startAtMs: PDT0 + 40_000 }), {
      onSeekRequest: vi.fn(),
    });
    await settle();
    const hls = lastHls();
    hls.hasEnoughToStart = false;
    act(() => hls.trigger('hlsLevelLoaded', { details: details(frags(13)) }));
    expect(hls.startLoads).toEqual([40]);
    video.el!.currentTime = 13;
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    slide(3);
    expect(hls.startLoads).toEqual([40, 24]);
  });

  /**
   * 되감기 재생기 · 받기 위치 startSec 로 기동해(첫 조각이 붙어 hasEnoughToStart — 목록 갱신은 아직 없다) 보던 자리(at)로
   * 시크한 데까지. 시크가 끝난 자리를 브라우저가 알리는 사건은 seeked · timeupdate 다
   */
  async function rewindStarted(startSec: number, at: number, playheadEvent = 'seeked') {
    const onNotice = vi.fn();
    mount(src({ kind: 'rewind', url: REWIND, startAtMs: PDT0 + startSec * 1000 }), {
      onNotice,
      onSeekRequest: vi.fn(),
    });
    await settle();
    const hls = lastHls();
    hls.hasEnoughToStart = false;
    act(() => hls.trigger('hlsLevelLoaded', { details: details(frags(13)) }));
    video.el!.currentTime = startSec;
    hls.hasEnoughToStart = true;
    act(() => hls.trigger('hlsFragBuffered', {}));
    video.el!.currentTime = at;
    act(() => {
      video.el!.dispatchEvent(new Event('seeking'));
      video.el!.dispatchEvent(new Event(playheadEvent));
    });
    return { hls, onNotice };
  }
  const fatalMediaError = (hls: FakeHlsLike) =>
    act(() => hls.trigger('hlsError', { fatal: true, type: 'mediaError', details: 'x' }));
  const internalRecovery = (hls: FakeHlsLike) => act(() => hls.recoverMediaError());

  it.each([
    ['앱이 치명 MEDIA_ERROR 를 복구', fatalMediaError],
    ['hls.js 가 스스로 복구(MEDIA_SOURCE_REQUIRES_RESET)', internalRecovery],
  ])(
    '기동 뒤 첫 목록 갱신 전에 %s 해도 시작 때 받기 위치로 옮기지 않는다 — 보던 자리에서 다시 받고 알리지 않는다(cx H)',
    async (_name, recover) => {
      const { hls, onNotice } = await rewindStarted(12, 40);
      recover(hls);
      slide(3);
      expect(hls.startLoads).toEqual([12, 40]);
      expect(onNotice).not.toHaveBeenCalled();
    },
  );

  it('기동이 끝나면 시작 때 받기 위치를 버린다 — hls.js 가 떼지 않고 기동 전으로 돌아가도 그 자리로 옮기지 않는다', async () => {
    const { hls, onNotice } = await rewindStarted(12, 40);
    hls.hasEnoughToStart = false;
    slide(3);
    expect(hls.startLoads).toEqual([12]);
    expect(onNotice).not.toHaveBeenCalled();
  });

  it.each([
    ['앱이 치명 MEDIA_ERROR 를 복구', fatalMediaError, 'seeked'],
    ['hls.js 가 스스로 복구(MEDIA_SOURCE_REQUIRES_RESET)', internalRecovery, 'seeked'],
    ['hls.js 가 스스로 복구 · 재생점은 timeupdate 로만 앎', internalRecovery, 'timeupdate'],
  ])(
    '%s 할 때 떼기 전 재생점이 창 하한 가까이고 첫 조각 전에 창이 지나가면 창 하한으로 다시 잡는다(cc M1)',
    async (_name, recover, playheadEvent) => {
      const { hls, onNotice } = await rewindStarted(40, 13, playheadEvent);
      recover(hls);
      slide(3);
      expect(hls.startLoads).toEqual([40, 13, 24]);
      expect(onNotice).toHaveBeenCalledTimes(1);
    },
  );

  it.each([
    ['앱이 치명 MEDIA_ERROR 를 복구', fatalMediaError],
    ['hls.js 가 스스로 복구(MEDIA_SOURCE_REQUIRES_RESET)', internalRecovery],
  ])(
    '시크 중(seeked 전)에 %s 하면 시크 목표에서 다시 받는다 — 앞서 적은 재생점으로 되돌리지 않는다(cx M1)',
    async (_name, recover) => {
      const { hls, onNotice } = await rewindStarted(40, 13);
      video.el!.currentTime = 40;
      act(() => video.el!.dispatchEvent(new Event('seeking')));
      recover(hls);
      slide(3);
      expect(hls.startLoads).toEqual([40, 40]);
      expect(onNotice).not.toHaveBeenCalled();
    },
  );

  it('MEDIA_ERROR 복구 때 재생점이 0 이면 옮기지 않는다 — hls.js 가 제 자리를 고른다(자동 시작이 아니면 startLoad())', async () => {
    const { hls, onNotice } = await rewindStarted(40, 0);
    fatalMediaError(hls);
    slide(3);
    expect(hls.startLoads).toEqual([40, undefined]);
    expect(onNotice).not.toHaveBeenCalled();
  });

  it('치명 NETWORK_ERROR 를 다시 받을 때도 재생점을 받기 위치로 본다 — 기동 전으로 돌아가면 창 하한으로 다시 잡는다', async () => {
    const { hls, onNotice } = await rewindStarted(40, 13);
    act(() => hls.trigger('hlsError', { fatal: true, type: 'networkError', details: 'x' }));
    hls.hasEnoughToStart = false;
    slide(3);
    expect(hls.startLoads).toEqual([40, undefined, 24]);
    expect(onNotice).toHaveBeenCalledTimes(1);
  });

  it('ENDLIST 목록은 옮기지 않는다 — hls.js 가 live 목록에서만 옮긴다', async () => {
    const onNotice = vi.fn();
    mount(src({ kind: 'rewind', url: REWIND }), { onNotice, onSeekRequest: vi.fn() });
    await settle();
    video.el!.currentTime = 2;
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(13), false) }));
    expect(video.el!.currentTime).toBe(2);
    expect(onNotice).not.toHaveBeenCalled();
  });

  it('라이브 재생기는 옮기지 않는다 — 라이브는 창 밖이면 되감기로 갈아 끼운다', async () => {
    const onNotice = vi.fn();
    mount(src(), { onNotice, onSeekRequest: vi.fn() });
    await settle();
    act(() => lastHls().trigger('hlsLevelUpdated', { details: details(frags(13)) }));
    video.el!.currentTime = 15;
    slide(3);
    expect(video.el!.currentTime).toBe(15);
    expect(onNotice).not.toHaveBeenCalled();
  });
});
