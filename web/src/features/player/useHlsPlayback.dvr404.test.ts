import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 되감기 조각 404 뒤 (가) 가 세운 새 되감기 재생기의 시작(POK-528 c3 — 계획 3-3 「남는
// 차이」 · 8-3 「추정 여유」 · P21). 세션은 새 목록의 조각 길이를 모르므로 시작 자리를
// S = F + 지금 목록의 여유로 추정해 넘긴다. 새 재생기는 첫 LEVEL_LOADED 에서 새 목록
// 첫 조각 + 맨 앞 조각 셋 길이 이상으로 자른다(startLoadPosition). 추정이 맞을 때 ·
// 새 여유가 더 클 때 · 조회 뒤 하한이 더 밀렸을 때의 실제 받기 자리와 재생기 알림 수를
// 실행해 보고 고정한다. 재생기 코드는 c3 에서 바뀌지 않는다 — 지금 동작 고정 시험이다.
// hls.js 흉내는 useHlsPlayback.start.test.ts 꼴이다(처음부터 off() 를 둔다).
//
// c5(계획 4절 · 4-1 · 4-2 · 8-3): 재생기가 되감기 조각 404 를 가로채 받기를 멈추고 세션에
// 올리는 배선 · 앱이 건 멈춤(holdFrame)이 사람 멈춤으로 적히지 않는 것 · (가) 의 멈춤 기록
// 버리기(dropPauseMark) · 「세우지 않음」(startHeld) 재생기 기동을 본다.

interface Details {
  fragments: Array<{ start: number; duration: number; programDateTime: number }>;
  fragmentStart: number;
  targetduration: number;
  live: boolean;
}

const fake = vi.hoisted(() => ({
  instances: [] as FakeHls[],
  /** 있으면 loadSource 가 곧바로 목록 · 조각 사건을 낸다(기동이 막히지 않으면 받기 · 재생이 보이게) */
  bootList: null as Details | null,
}));

vi.mock('hls.js', () => ({ default: FakeHls }));

type Handler = (event: string, data: unknown) => void;

class FakeHls {
  static isSupported = () => true;
  static Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_LOADED: 'hlsLevelLoaded',
    LEVEL_UPDATED: 'hlsLevelUpdated',
    FRAG_LOADED: 'hlsFragLoaded',
    ERROR: 'hlsError',
  };
  static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  static ErrorDetails = {
    FRAG_LOAD_ERROR: 'fragLoadError',
    INTERNAL_ABORTED: 'aborted',
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    LEVEL_LOAD_ERROR: 'levelLoadError',
    AUDIO_TRACK_LOAD_ERROR: 'audioTrackLoadError',
  };
  readonly handlers = new Map<string, Handler[]>();
  latestLevelDetails: Details | null = null;
  liveSyncPosition: number | null = null;
  readonly startLoad = vi.fn();
  readonly stopLoad = vi.fn();
  readonly destroy = vi.fn();
  readonly loadSource = vi.fn(() => {
    const list = fake.bootList;
    if (list === null) return;
    this.trigger(FakeHls.Events.MANIFEST_PARSED, {});
    this.trigger(FakeHls.Events.LEVEL_LOADED, { details: list });
    this.trigger(FakeHls.Events.FRAG_LOADED, {});
  });
  constructor(readonly config: { autoStartLoad?: boolean }) {
    fake.instances.push(this);
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
    const { details } = (data ?? {}) as { details?: Details };
    if (details !== undefined) this.latestLevelDetails = details;
    for (const h of this.handlers.get(event) ?? []) h(event, data);
  }
  attachMedia() {}
}

/** 제어면 답의 첫 조각 F */
const F = Date.parse('2026-10-25T05:00:00.000Z');
/** 세션이 아는 지금 목록의 여유(맨 앞 4초 조각 셋) */
const SESSION_MARGIN_MS = 12_000;
/** 세션이 넘긴 시작 자리 S = F + 지금 목록의 여유 */
const S = F + SESSION_MARGIN_MS;

/** 새 목록 — 첫 조각 firstMs(영상 0초) · segmentS 초 조각 n 개 · 미닫이 live 목록 */
function details(firstMs: number, segmentS: number, n = 10): Details {
  const fragments = [...Array(n).keys()].map((i) => ({
    start: i * segmentS,
    duration: segmentS,
    programDateTime: firstMs + i * segmentS * 1_000,
  }));
  return { fragments, fragmentStart: 0, targetduration: segmentS, live: true };
}

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

/** (가) 가 세운 새 되감기 재생기를 S 에서 세우고 첫 목록을 hls.js 순서대로 쏜다 */
async function bootAtS(list: Details, hooks: PlaybackHooks) {
  const source: PlaybackSource = {
    kind: 'rewind',
    url: 'https://m/dvr/k/a1/index.m3u8',
    startAtMs: S,
    generation: 2,
    strictStart: false,
  };
  const ref = { current: document.createElement('video') };
  renderHook(() => useHlsPlayback(ref, source, {}, undefined, undefined, false, undefined, hooks));
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  const hls = lastHls();
  hls.trigger(FakeHls.Events.MANIFEST_PARSED, {});
  hls.trigger(FakeHls.Events.LEVEL_LOADED, { details: list });
  hls.trigger(FakeHls.Events.LEVEL_UPDATED, { details: list });
  await Promise.resolve();
  return hls;
}

beforeEach(() => {
  vi.useFakeTimers({ now: F + 60_000 });
  fake.instances.length = 0;
  fake.bootList = null;
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockImplementation(() => 0);
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('(가) 의 시작 하한 추정 S — 새 재생기가 실제로 받는 자리(P21 · 지금 동작 고정)', () => {
  it('추정 = 새 여유면 S 의 영상 시각에서 받는다 — 더 자르지 않음 · 재생기 알림 0', async () => {
    const onNotice = vi.fn();
    const hls = await bootAtS(details(F, 4), { onNotice });
    expect(hls.startLoad.mock.calls).toEqual([[12]]);
    expect(onNotice).not.toHaveBeenCalled();
  });

  it('새 여유가 더 크면(6초 조각) F + 새 여유로 말없이 더 자른다 — 재생기 알림 0(한계)', async () => {
    const onNotice = vi.fn();
    const hls = await bootAtS(details(F, 6), { onNotice });
    expect(hls.startLoad.mock.calls).toEqual([[18]]);
    expect(onNotice).not.toHaveBeenCalled();
  });

  it('조회 뒤 하한이 한 조각 더 밀리면(F′ = F + 4초) F′ + 여유로 말없이 더 자른다 — 재생기 알림 0(한계)', async () => {
    const onNotice = vi.fn();
    const shifted = F + 4_000;
    const hls = await bootAtS(details(shifted, 4), { onNotice });
    // 영상 12초 = F′ + 12초 = S + 4초
    expect(hls.startLoad.mock.calls).toEqual([[12]]);
    expect(shifted + 12_000 - S).toBe(4_000);
    expect(onNotice).not.toHaveBeenCalled();
  });
});

const REWIND_URL = 'https://m/dvr/k/a1/index.m3u8';
const LIVE_URL = 'https://m/live/k/index.m3u8';
const MEDIA_SEGMENT = 'https://m/dvr/k/a1/seg7.mp4';
const INIT_SEGMENT = 'https://m/dvr/k/a1/init.mp4';
/** 404 를 받은 옛 목록 · (가) 뒤의 새 목록(하한이 2분 밀림) */
const OLD_LIST = details(F, 4);
const NEW_LIST = details(F + 120_000, 4);

function sourceOf(
  kind: PlaybackSource['kind'],
  generation: number,
  startAtMs: number | null = null,
): PlaybackSource {
  const url = kind === 'live' ? LIVE_URL : REWIND_URL;
  return { kind, url, startAtMs, generation, strictStart: false };
}

interface MountProps {
  source: PlaybackSource;
  hooks: PlaybackHooks;
}

/** 재생기를 세우고 hls.js 읽기를 기다린다. rerender 로 소스(세대) · 고리를 바꾼다 */
async function mount(source: PlaybackSource, hooks: PlaybackHooks, mediaKey?: string) {
  const ref = { current: document.createElement('video') };
  const view = renderHook(
    ({ source: s, hooks: h }: MountProps) =>
      useHlsPlayback(ref, s, {}, undefined, mediaKey, false, undefined, h),
    { initialProps: { source, hooks } },
  );
  await settle();
  const swapTo = async (next: MountProps) => {
    view.rerender(next);
    await settle();
  };
  return { result: view.result, video: ref.current, swapTo };
}

async function settle() {
  await act(async () => {
    await vi.dynamicImportSettled();
  });
}

/** hls.js 가 앱에 올리는 조각 받기 오류 꼴 — 실패한 파일은 url */
function fragError(
  code: number,
  patch: { fatal?: boolean; url?: string; details?: string } = {},
): unknown {
  return {
    type: FakeHls.ErrorTypes.NETWORK_ERROR,
    details: patch.details ?? FakeHls.ErrorDetails.FRAG_LOAD_ERROR,
    fatal: patch.fatal ?? false,
    frag: { sn: 7, initSegment: { url: INIT_SEGMENT } },
    response: { url: patch.url ?? MEDIA_SEGMENT, code, text: '' },
  };
}

function raise(hls: FakeHls, data: unknown) {
  act(() => hls.trigger(FakeHls.Events.ERROR, data));
}

describe('되감기 조각 404 가로채기 배선(POK-528 c5 · 계획 4절 · 8-3)', () => {
  it('rewind + 고리 — 비치명 미디어 404 면 받기를 멈추고 이 재생기 세대로 고리를 한 번 부른다', async () => {
    const onDvrSegmentGone = vi.fn();
    const { result } = await mount(sourceOf('rewind', 3), { onDvrSegmentGone });
    const hls = lastHls();
    raise(hls, fragError(404));
    expect(hls.stopLoad).toHaveBeenCalledTimes(1);
    expect(onDvrSegmentGone.mock.calls).toEqual([[3]]);
    expect(hls.startLoad).not.toHaveBeenCalled();
    expect(hls.destroy).not.toHaveBeenCalled();
    expect(result.current.playbackError).toBeNull();
  });

  it('치명으로 올라온 미디어 404 도 같다 — 앱의 한 번 다시 받기 · 끊김 알림으로 가지 않는다', async () => {
    const onDvrSegmentGone = vi.fn();
    const { result } = await mount(sourceOf('rewind', 3), { onDvrSegmentGone });
    const hls = lastHls();
    raise(hls, fragError(404, { fatal: true }));
    expect(hls.stopLoad).toHaveBeenCalledTimes(1);
    expect(onDvrSegmentGone.mock.calls).toEqual([[3]]);
    expect(hls.startLoad).not.toHaveBeenCalled();
    expect(hls.destroy).not.toHaveBeenCalled();
    expect(result.current.playbackError).toBeNull();
  });

  it('init 404 한 건에 오류가 두 번 와도(중단 · 같은 자료) 고리 0 · stopLoad 0 — 치명이면 지금처럼 한 번 다시 받는다', async () => {
    const onDvrSegmentGone = vi.fn();
    await mount(sourceOf('rewind', 3), { onDvrSegmentGone });
    const hls = lastHls();
    const initGone = fragError(404, { url: INIT_SEGMENT });
    // handleFragLoadError 의 init 404 · 바깥 Promise.all 의 중단 · 같은 init 자료(base-stream-controller.ts 815-823 · 1171)
    raise(hls, initGone);
    raise(hls, fragError(0, { details: FakeHls.ErrorDetails.INTERNAL_ABORTED }));
    raise(hls, initGone);
    expect(onDvrSegmentGone).not.toHaveBeenCalled();
    expect(hls.stopLoad).not.toHaveBeenCalled();
    expect(hls.startLoad).not.toHaveBeenCalled();
    raise(hls, fragError(404, { url: INIT_SEGMENT, fatal: true }));
    expect(onDvrSegmentGone).not.toHaveBeenCalled();
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
  });

  it.each([0, 503])(
    '상태 %i 는 하한과 무관하다 — 고리 0 · 첫 치명은 한 번 다시 받기 · 두 번째는 끊김',
    async (code) => {
      const onDvrSegmentGone = vi.fn();
      const { result } = await mount(sourceOf('rewind', 3), { onDvrSegmentGone });
      const hls = lastHls();
      raise(hls, fragError(code, { fatal: true }));
      expect(hls.startLoad).toHaveBeenCalledTimes(1);
      raise(hls, fragError(code, { fatal: true }));
      expect(onDvrSegmentGone).not.toHaveBeenCalled();
      expect(hls.stopLoad).not.toHaveBeenCalled();
      expect(hls.destroy).toHaveBeenCalledTimes(1);
      expect(result.current.playbackError).not.toBeNull();
    },
  );

  it('403 은 지금처럼 출입증 갈래다 — 고리 0 · stopLoad 0 · 재생기를 내린다', async () => {
    const onDvrSegmentGone = vi.fn();
    const { result } = await mount(sourceOf('rewind', 3), { onDvrSegmentGone }, 'key-1');
    const hls = lastHls();
    raise(hls, fragError(403, { fatal: true }));
    expect(onDvrSegmentGone).not.toHaveBeenCalled();
    expect(hls.stopLoad).not.toHaveBeenCalled();
    expect(hls.destroy).toHaveBeenCalledTimes(1);
    expect(result.current.playbackError).not.toBeNull();
  });

  it.each<PlaybackSource['kind']>(['vod', 'live'])(
    '%s 재생기는 고리가 있어도 미디어 404 를 가로채지 않는다 — 첫 치명은 한 번 다시 받기(W8 (ㄱ))',
    async (kind) => {
      const onDvrSegmentGone = vi.fn();
      await mount(sourceOf(kind, 3), { onDvrSegmentGone });
      const hls = lastHls();
      raise(hls, fragError(404));
      raise(hls, fragError(404, { fatal: true }));
      expect(onDvrSegmentGone).not.toHaveBeenCalled();
      expect(hls.stopLoad).not.toHaveBeenCalled();
      expect(hls.startLoad).toHaveBeenCalledTimes(1);
    },
  );

  it('고리가 없으면 지금 동작 그대로다 — stopLoad 0 · 치명에서 한 번 다시 받기', async () => {
    await mount(sourceOf('rewind', 3), {});
    const hls = lastHls();
    raise(hls, fragError(404));
    expect(hls.stopLoad).not.toHaveBeenCalled();
    raise(hls, fragError(404, { fatal: true }));
    expect(hls.stopLoad).not.toHaveBeenCalled();
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
  });
});

/** 재생 여부를 시험이 쥔다 — pause() 는 멈추고 pause 사건을, play() 는 refuse 가 아니면 틀고 play 사건을 낸다 */
const media = { paused: false, refusePlay: false };

function stubMediaPlayback() {
  media.paused = false;
  media.refusePlay = false;
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockImplementation(() => media.paused);
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(function (
    this: HTMLMediaElement,
  ) {
    media.paused = true;
    this.dispatchEvent(new Event('pause'));
  });
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (
    this: HTMLMediaElement,
  ) {
    // 자동 재생 거부는 play 사건 없이 거절로 온다
    if (media.refusePlay) return Promise.reject(new DOMException('', 'NotAllowedError'));
    media.paused = false;
    this.dispatchEvent(new Event('play'));
    return Promise.resolve();
  });
}

function playCalls(): number {
  return vi.mocked(HTMLMediaElement.prototype.play).mock.calls.length;
}

function pauseCalls(): number {
  return vi.mocked(HTMLMediaElement.prototype.pause).mock.calls.length;
}

/** 사람이 멈춘다 — 재생기는 pause 사건으로만 안다 */
function userPause(video: HTMLVideoElement) {
  act(() => {
    media.paused = true;
    video.dispatchEvent(new Event('pause'));
  });
}

/** 세운 재생기에 목록을 쥐여 주고 MANIFEST_PARSED 를 낸다(자동 재생) */
function parsed(hls: FakeHls, list: Details) {
  hls.latestLevelDetails = list;
  act(() => hls.trigger(FakeHls.Events.MANIFEST_PARSED, {}));
}

describe('앱이 건 멈춤(holdFrame)은 사람 멈춤으로 적히지 않는다(POK-528 계획 4-1 · 8-3 hold-frame 넷)', () => {
  beforeEach(stubMediaPlayback);

  /** 세션 모드 고리(사람이 옮긴 목표 · 재생기 알림을 센다) */
  function sessionHooks(patch: Partial<PlaybackHooks> = {}) {
    return { onSeekRequest: vi.fn(), onNotice: vi.fn(), onDvrSegmentGone: vi.fn(), ...patch };
  }

  /** 옛 목록을 쥔 되감기 재생기(세대 1)를 재생 중으로 세운다 */
  async function playingRewind(hooks: PlaybackHooks) {
    const mounted = await mount(sourceOf('rewind', 1), hooks);
    lastHls().latestLevelDetails = OLD_LIST;
    return mounted;
  }

  it('① 재생 중 holdFrame → pause 1 → 다음 되감기 재생기가 스스로 틀어도 옛 자리로 옮겨 달라지 않는다', async () => {
    const hooks = sessionHooks();
    const { result, swapTo } = await playingRewind(hooks);
    act(() => result.current.holdFrame());
    expect(pauseCalls()).toBe(1);
    await swapTo({ source: sourceOf('rewind', 2), hooks });
    parsed(lastHls(), NEW_LIST);
    expect(playCalls()).toBe(1);
    expect(hooks.onSeekRequest).not.toHaveBeenCalled();
    expect(hooks.onNotice).not.toHaveBeenCalled();
  });

  it('② 사람이 먼저 멈췄으면 holdFrame 은 pause 를 다시 부르지 않고 멈춘 자리 기록만 버린다', async () => {
    const hooks = sessionHooks();
    const { result, video, swapTo } = await playingRewind(hooks);
    userPause(video);
    act(() => result.current.holdFrame());
    expect(pauseCalls()).toBe(0);
    await swapTo({ source: sourceOf('rewind', 2), hooks });
    parsed(lastHls(), NEW_LIST);
    // 사람 멈춤은 사실이지만 넘김 되돌림 표가 없으니 다음 재생기는 지금처럼 스스로 튼다
    expect(playCalls()).toBe(1);
    expect(hooks.onSeekRequest).not.toHaveBeenCalled();
  });

  it('③ holdFrame 뒤 LIVE 로 선 라이브 재생기가 자동 재생 거부로 멈춰 있어도 되감기로 되돌려 보내지 않는다', async () => {
    const hooks = sessionHooks();
    const { result, video, swapTo } = await playingRewind(hooks);
    userPause(video);
    act(() => result.current.holdFrame());
    media.refusePlay = true;
    vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockReturnValue({
      length: 1,
      start: () => 0,
      end: () => 40,
    } as unknown as TimeRanges);
    const liveHooks = { ...hooks, axisLowerMs: F - 60_000 };
    await swapTo({ source: sourceOf('live', 2), hooks: liveHooks });
    const live = lastHls();
    parsed(live, { ...NEW_LIST, live: true });
    // 그리기 틱 — 라이브의 멈춘 자리가 창 하한 밖이면 멈춘 채 세션에 넘긴다(paintSession)
    await act(async () => vi.advanceTimersByTime(1_000));
    expect(hooks.onSeekRequest).not.toHaveBeenCalled();
    // 멈춘 채 갈아 끼우기 표도 걸지 않았다 — 같은 재생기의 다음 MANIFEST_PARSED 가 틀려 한다
    const before = playCalls();
    act(() => live.trigger(FakeHls.Events.MANIFEST_PARSED, {}));
    expect(playCalls() - before).toBe(1);
  });

  it('④ holdFrame 뒤 세션의 넘김 되돌림(keepPausedOnSwap)은 다음 재생기를 멈춘 채 세우지 않는다', async () => {
    const hooks = sessionHooks();
    const { result, swapTo } = await playingRewind(hooks);
    act(() => result.current.holdFrame());
    act(() => result.current.keepPausedOnSwap());
    await swapTo({ source: sourceOf('rewind', 2), hooks });
    parsed(lastHls(), NEW_LIST);
    expect(playCalls()).toBe(1);
  });

  it('(가) 의 멈춤 기록 버리기 — 사람 멈춤 → 404 → dropPauseMark → 새 재생기가 틀어도 옛 자리 시크 0 · 재생기 알림 0', async () => {
    const hooks = sessionHooks();
    const { result, video, swapTo } = await playingRewind(hooks);
    userPause(video);
    raise(lastHls(), fragError(404));
    expect(hooks.onDvrSegmentGone).toHaveBeenCalledTimes(1);
    act(() => result.current.dropPauseMark());
    await swapTo({ source: sourceOf('rewind', 2, S), hooks });
    parsed(lastHls(), NEW_LIST);
    expect(playCalls()).toBe(1);
    expect(hooks.onSeekRequest).not.toHaveBeenCalled();
    expect(hooks.onNotice).not.toHaveBeenCalled();
  });
});

describe('「세우지 않음」 재생기 기동(POK-528 U2 · 계획 4-2 · 8-3 리마운트 기동 셋)', () => {
  beforeEach(() => {
    stubMediaPlayback();
    fake.bootList = details(F, 4);
  });

  it('① startHeld 면 hls.js 를 만들지 않는다 — 생성 0 · loadSource 0 · startLoad 0 · play 0 · src 없음', async () => {
    const { video } = await mount(sourceOf('rewind', 5, S), { startHeld: true });
    expect(fake.instances).toHaveLength(0);
    expect(playCalls()).toBe(0);
    expect(video.getAttribute('src')).toBeNull();
  });

  it('② 세대가 올라 startHeld 가 풀리면 정상 기동한다 — 생성 1 · 받기 · 재생', async () => {
    const { swapTo } = await mount(sourceOf('rewind', 5, S), { startHeld: true });
    expect(fake.instances).toHaveLength(0);
    await swapTo({ source: sourceOf('rewind', 6, S), hooks: { startHeld: false } });
    expect(fake.instances).toHaveLength(1);
    const hls = lastHls();
    expect(hls.loadSource).toHaveBeenCalledTimes(1);
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    expect(playCalls()).toBe(1);
  });

  it('③ 기본 HLS 갈래(Safari)도 startHeld 면 video.src 를 세우지 않고 틀지 않는다', async () => {
    vi.spyOn(FakeHls, 'isSupported').mockReturnValue(false);
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    const { video } = await mount(sourceOf('rewind', 5, S), { startHeld: true });
    expect(video.getAttribute('src')).toBeNull();
    expect(playCalls()).toBe(0);
  });
});
