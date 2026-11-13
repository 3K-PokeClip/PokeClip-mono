import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DvrSegment, PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 되감기 조각 404 의 재생기 몫(POK-528 c5 — 계획 4절 · 4-1 · 4-2 · 8-3): 재생기가 되감기
// 조각 404 를 가로채 받기를 멈추고 세션에 올리는 배선 · 앱이 건 멈춤(holdFrame)이 사람
// 멈춤으로 적히지 않는 것 · 목록 다시 받기의 멈춤 기록 버리기(dropPauseMark) · 사람이 멈춘
// 동안의 404 붙잡기(c9) · 「세우지 않음」(startHeld) 재생기 기동을 본다. 다시 받은 재생기의
// 시작 자리 · 하한 잘림 보고는 useHlsPlayback.relist.test.ts 가 본다(c7 — c3 의 「시작 하한
// 추정 S」 묶음을 대체).
// hls.js 흉내는 useHlsPlayback.start.test.ts 꼴이다(처음부터 off() 를 둔다).

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
    INTERNAL_ABORTED: 'aborted',
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
    // 목록은 목록 사건에서만 갈아 끼운다 — ERROR 자료의 details 는 오류 갈래 문자열이다(hls.js ErrorData)
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

/** 옛 목록의 첫 조각 F */
const F = Date.parse('2026-10-25T05:00:00.000Z');
/** 새 되감기 재생기의 시작 자리 — 옛 목록 창 하한(F + 맨 앞 4초 조각 셋) */
const S = F + 12_000;

/** 새 목록 — 첫 조각 firstMs(영상 0초) · segmentS 초 조각 n 개 · 미닫이 live 목록 */
function details(firstMs: number, segmentS: number, n = 10): Details {
  const fragments = Array.from({ length: n }, (_, i) => ({
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

const REWIND_URL = 'https://m/dvr/k/a1/index.m3u8';
const LIVE_URL = 'https://m/live/k/index.m3u8';
const MEDIA_SEGMENT = 'https://m/dvr/k/a1/seg7.mp4';
const INIT_SEGMENT = 'https://m/dvr/k/a1/init.mp4';
/** 404 를 받은 옛 목록 · 다시 받은 새 목록(하한이 2분 밀림) */
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

/**
 * hls.js 가 앱에 올리는 조각 받기 오류 꼴 — 실패한 파일은 url. gap 은 hls.js 가 그 조각을 틈으로 넘기기로
 * 했는지(frag.gap — hls.js 처리기가 앱 처리기보다 먼저 적는다). 주지 않으면 칸이 없다
 */
function fragError(
  code: number,
  patch: {
    fatal?: boolean;
    url?: string;
    details?: string;
    gap?: boolean;
    segment?: DvrSegment;
  } = {},
): unknown {
  const gap = patch.gap === undefined ? {} : { gap: patch.gap };
  const segment = patch.segment ?? { type: 'main', sn: 7 };
  return {
    type: FakeHls.ErrorTypes.NETWORK_ERROR,
    details: patch.details ?? FakeHls.ErrorDetails.FRAG_LOAD_ERROR,
    fatal: patch.fatal ?? false,
    frag: { ...segment, ...gap, initSegment: { url: INIT_SEGMENT } },
    response: { url: patch.url ?? MEDIA_SEGMENT, code, text: '' },
  };
}

function raise(hls: FakeHls, data: unknown) {
  act(() => hls.trigger(FakeHls.Events.ERROR, data));
}

describe('되감기 조각 404 가로채기 배선(POK-528 c5 · 계획 4절 · 8-3)', () => {
  it('rewind + 고리 — 비치명 미디어 404 면 받기를 멈추고 이 재생기 세대 · 그 조각으로 고리를 한 번 부른다', async () => {
    const onDvrSegmentGone = vi.fn();
    const { result } = await mount(sourceOf('rewind', 3), { onDvrSegmentGone });
    const hls = lastHls();
    raise(hls, fragError(404));
    expect(hls.stopLoad).toHaveBeenCalledTimes(1);
    expect(onDvrSegmentGone.mock.calls).toEqual([[3, { type: 'main', sn: 7 }]]);
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
    expect(onDvrSegmentGone.mock.calls).toEqual([[3, { type: 'main', sn: 7 }]]);
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

// 아는 구멍 넘김의 재생기 몫(POK-528 후속 c2 · 계획 2-1). 세션이 아는 구멍(isKnownDvrHole)이고 hls.js 가 그 조각을
// 틈으로 넘기기로 했으면(frag.gap) 가로채지 않는다 — stopLoad 0 · 고리 0 이라 hls.js 가 예약한 다음 받기가 그대로 산다.
// 둘 중 하나라도 거짓이면 지금처럼 가로챈다
describe('아는 구멍 넘김 배선 — 세션이 아는 구멍 ∧ hls.js 가 틈으로 넘김이면 가로채지 않는다(POK-528 후속 c2)', () => {
  it.each<[string, DvrSegment]>([
    ['영상 조각', { type: 'main', sn: 7 }],
    ['소리 조각(Z2)', { type: 'audio', sn: 41 }],
  ])(
    '① %s — 아는 구멍 ∧ frag.gap 참 → stopLoad 0 · 고리 0 · 세션에는 이 세대 · 그 조각으로 묻는다',
    async (_name, segment) => {
      const onDvrSegmentGone = vi.fn();
      const isKnownDvrHole = vi.fn(() => true);
      const { result } = await mount(sourceOf('rewind', 3), { onDvrSegmentGone, isKnownDvrHole });
      const hls = lastHls();
      raise(hls, fragError(404, { gap: true, segment }));
      expect({
        asked: isKnownDvrHole.mock.calls,
        stopLoads: hls.stopLoad.mock.calls.length,
        gone: onDvrSegmentGone.mock.calls.length,
        startLoads: hls.startLoad.mock.calls.length,
        destroyed: hls.destroy.mock.calls.length,
        error: result.current.playbackError,
      }).toEqual({
        asked: [[3, segment]],
        stopLoads: 0,
        gone: 0,
        startLoads: 0,
        destroyed: 0,
        error: null,
      });
    },
  );

  it.each<[string, boolean, { gap?: boolean }]>([
    ['② 아는 구멍이어도 gap 거짓(ENDLIST · 마지막 조각 · 다시 받기 예약)', true, { gap: false }],
    ['② 아는 구멍이어도 gap 칸 없음', true, {}],
    ['③ 모르는 구멍이면 gap 참이어도(첫 404 는 다시 받기 재료)', false, { gap: true }],
  ])('%s → 지금처럼 가로챈다 — stopLoad 1 · 고리 (3, 그 조각)', async (_name, known, patch) => {
    const onDvrSegmentGone = vi.fn();
    await mount(sourceOf('rewind', 3), { onDvrSegmentGone, isKnownDvrHole: () => known });
    const hls = lastHls();
    raise(hls, fragError(404, patch));
    expect(hls.stopLoad).toHaveBeenCalledTimes(1);
    expect(onDvrSegmentGone.mock.calls).toEqual([[3, { type: 'main', sn: 7 }]]);
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

  it('목록 다시 받기의 멈춤 기록 버리기 — 재생 중 404 → 사람 멈춤 → dropPauseMark → 다시 받은 새 재생기가 틀어도 옛 자리 시크 0 · 재생기 알림 0', async () => {
    const hooks = sessionHooks();
    const { result, video, swapTo } = await playingRewind(hooks);
    // 재생 중 404 는 곧바로 올라간다 — 멈춤은 그 뒤 새 재생기가 서기 전이다(멈춘 동안의 404 는 아래 묶음)
    raise(lastHls(), fragError(404));
    expect(hooks.onDvrSegmentGone).toHaveBeenCalledTimes(1);
    userPause(video);
    act(() => result.current.dropPauseMark());
    await swapTo({ source: sourceOf('rewind', 2, S), hooks });
    parsed(lastHls(), NEW_LIST);
    expect(playCalls()).toBe(1);
    expect(hooks.onSeekRequest).not.toHaveBeenCalled();
    expect(hooks.onNotice).not.toHaveBeenCalled();
  });
});

/** 세션이 마지막으로 받은 제어면 첫 조각 — 옛 목록 첫 조각 F 보다 20초 늦다(다시 틀 때 비교값에 넣지 않는다) */
const CONTROL_F = F + 20_000;
/** 옛 목록의 창 하한(방송 중 되감기 — 첫 4초 조각 하나) · 끝(4초 조각 열 개) */
const OLD_LOWER = F + 4_000;
const OLD_END = F + 40_000;

// 사람이 멈춘 동안의 되감기 조각 404(POK-528 c9 · 계획 4-3 · 8-5). 받기만 멈추고(stopLoad) 세션에 올리지 않은 채
// 기다리다가, 다시 틀 때 하한 안팎을 보지 않고 이 세대로 고리를 한 번 부른다 — 세션이 그때 국면의 404 행으로 잇는다
// (되감기면 다시 받는 중이 아닐 때 같은 목록 다시 받기 · 다시 받는 중(화면 전)이면 막힘 · 끝남 대기면 녹화로 정하기
// 전이면 막힘 · 정한 뒤면 마지막 확인 · 다시보기 제안이면 같은 자리 다시보기). 옛 자리 시크 · 제자리 다시
// 받기(startLoad)는 하지 않는다. 404 가 없으면 재개 판정은 지금처럼 재생기 목록 하한만 본다(제어면
// 첫 조각 axisLowerMs 는 넣지 않음 — P35). tmp 41b85090 의 시험 차림을 옮겨 이 판정으로 고쳤다
describe('사람이 멈춘 동안의 되감기 조각 404 — 받기만 멈추고 다시 틀 때 세션에 올린다(POK-528 c9 · 계획 4-3 · 8-5)', () => {
  beforeEach(stubMediaPlayback);

  /** 세션 모드 고리 — 제어면 첫 조각을 쥐여 둔다(비교값에 넣지 않는지 보려고) */
  function resumeHooks() {
    return {
      onSeekRequest: vi.fn(),
      onNotice: vi.fn(),
      onDvrSegmentGone: vi.fn(),
      axisLowerMs: CONTROL_F,
    };
  }

  /**
   * 옛 목록을 쥔 재생기(세대 1)를 세우고, 재생 위치를 atS 초에 둔 채 사람이 멈춘다. isKnownDvrHole 을 주면 세션
   * 질의로 단다
   */
  async function pausedAt(
    atS: number,
    kind: PlaybackSource['kind'] = 'rewind',
    isKnownDvrHole?: PlaybackHooks['isKnownDvrHole'],
  ) {
    const hooks = { ...resumeHooks(), isKnownDvrHole };
    const mounted = await mount(sourceOf(kind, 1), hooks);
    const hls = lastHls();
    hls.latestLevelDetails = OLD_LIST;
    mounted.video.currentTime = atS;
    userPause(mounted.video);
    return { ...mounted, hooks, hls };
  }

  /** 사람이 다시 튼다 */
  function resume(video: HTMLVideoElement) {
    act(() => {
      void video.play();
    });
  }

  it.each([
    ['하한 밖(영상 0초)', 0],
    ['하한 안(영상 36초)', 36],
  ])(
    '멈춘 동안 404 · 자리 %s → 받기만 멈춤(고리 0 · 교체 0 · 화면 그대로) → 다시 틀면 이 세대 · 그 조각으로 고리 1 · 시크 요청 0 · startLoad 0',
    async (_name, atS) => {
      const { video, hooks, hls } = await pausedAt(atS);
      raise(hls, fragError(404));
      expect(hls.stopLoad).toHaveBeenCalledTimes(1);
      expect(hooks.onDvrSegmentGone).not.toHaveBeenCalled();
      expect(fake.instances).toHaveLength(1);
      expect(pauseCalls()).toBe(0);
      expect(media.paused).toBe(true);
      resume(video);
      expect(hooks.onDvrSegmentGone.mock.calls).toEqual([[1, { type: 'main', sn: 7 }]]);
      expect(hooks.onSeekRequest).not.toHaveBeenCalled();
      expect(hls.startLoad).not.toHaveBeenCalled();
      expect(video.currentTime).toBe(atS);
      expect(fake.instances).toHaveLength(1);
      // 붙잡은 것은 한 번 쓰면 버린다 — 다시 멈췄다 틀어도 또 부르지 않는다
      userPause(video);
      resume(video);
      expect(hooks.onDvrSegmentGone).toHaveBeenCalledTimes(1);
    },
  );

  // 넘김 판정은 붙잡기(reportGone)보다 앞이다(POK-528 후속 c2 · 계획 2-3 「멈춘 동안 404」) — 넘기면 받기를 멈추지도
  // 붙잡지도 않아 다시 틀 때 세션에 올리지 않는다. 넘기지 않으면 지금처럼 다시 틀 때 이 세대 · 그 조각으로 올린다
  it.each<[string, number, number, boolean]>([
    ['아는 구멍 ∧ gap 참 → 붙잡지 않음', 0, 0, true],
    ['모르는 구멍 ∧ gap 참 → 지금처럼 붙잡음', 1, 1, false],
  ])(
    '멈춘 동안 404 · %s — stopLoad %i · 다시 틀면 고리 %i',
    async (_name, stopLoads, gone, known) => {
      const isKnownDvrHole = vi.fn(() => known);
      const { video, hooks, hls } = await pausedAt(36, 'rewind', isKnownDvrHole);
      raise(hls, fragError(404, { gap: true }));
      expect(isKnownDvrHole.mock.calls).toEqual([[1, { type: 'main', sn: 7 }]]);
      expect(hooks.onDvrSegmentGone).not.toHaveBeenCalled();
      resume(video);
      expect({
        stopLoads: hls.stopLoad.mock.calls.length,
        gone: hooks.onDvrSegmentGone.mock.calls,
      }).toEqual({
        stopLoads,
        gone: gone === 0 ? [] : [[1, { type: 'main', sn: 7 }]],
      });
    },
  );

  it('404 없이 멈췄다 다시 틀 때 멈춘 자리가 목록 하한 안이면 제어면 첫 조각이 그 자리보다 뒤여도 그대로 튼다 — 시크 요청 0(41b85090 (ii) 를 뒤집음 · P35 — 비교값은 재생기 목록 하한만)', async () => {
    const { video, hooks, hls } = await pausedAt(16);
    resume(video);
    expect(hooks.onSeekRequest).not.toHaveBeenCalled();
    expect(video.currentTime).toBe(16);
    expect(hooks.onDvrSegmentGone).not.toHaveBeenCalled();
    expect(hls.startLoad).not.toHaveBeenCalled();
  });

  it('404 없이 멈춘 자리가 목록 하한 앞이면 지금처럼 그 하한으로 옮겨 달라고 한다 — 시크 요청 1 · 고리 0', async () => {
    const { video, hooks, hls } = await pausedAt(0);
    resume(video);
    expect(hooks.onSeekRequest.mock.calls).toEqual([[F, { lowerMs: OLD_LOWER, endMs: OLD_END }]]);
    expect(hooks.onDvrSegmentGone).not.toHaveBeenCalled();
    expect(hls.startLoad).not.toHaveBeenCalled();
  });

  it('앱이 건 멈춤(holdFrame) 뒤의 404 는 미루지 않는다 — 곧바로 이 세대 · 그 조각으로 고리 1(사람 멈춤만 기다린다)', async () => {
    const hooks = resumeHooks();
    const { result } = await mount(sourceOf('rewind', 1), hooks);
    const hls = lastHls();
    hls.latestLevelDetails = OLD_LIST;
    act(() => result.current.holdFrame());
    expect(media.paused).toBe(true);
    raise(hls, fragError(404));
    expect(hooks.onDvrSegmentGone.mock.calls).toEqual([[1, { type: 'main', sn: 7 }]]);
  });

  it('다시보기 재생기의 재개는 지금 비교 그대로다 — 녹화 첫 조각 뒤 자리면 그대로 튼다', async () => {
    const { video, hooks } = await pausedAt(16, 'vod');
    resume(video);
    expect(hooks.onSeekRequest).not.toHaveBeenCalled();
    expect(video.currentTime).toBe(16);
  });

  // P34 — 붙잡은 채 사람이 옮기면 받기를 멈춘 재생기는 그 자리를 받지 않는다. 다시 틀 때 세션에 올린다 — 다시 받는
  // 중이 아닌 되감기면 다시 받기가 선다(세션 시크면 세션이 목표를 들고 있다 — 세션 훅 층 시험). 지금 동작을 고정한다
  it('붙잡은 채 시크(seekToMs) → 받기 0 · 고리 0 · 시크 요청 0 → 다시 틀면 고리 1 · 시크 요청 0 · startLoad 0', async () => {
    const { result, video, hooks, hls } = await pausedAt(36);
    raise(hls, fragError(404));
    act(() => result.current.seekToMs(F + 24_000));
    expect(video.currentTime).toBe(24);
    expect(hls.startLoad).not.toHaveBeenCalled();
    expect(hooks.onDvrSegmentGone).not.toHaveBeenCalled();
    resume(video);
    expect(hooks.onDvrSegmentGone.mock.calls).toEqual([[1, { type: 'main', sn: 7 }]]);
    expect(hooks.onSeekRequest).not.toHaveBeenCalled();
    expect(hls.startLoad).not.toHaveBeenCalled();
  });

  it.each<PlaybackSource['kind']>(['rewind', 'live'])(
    '붙잡은 채 세대가 바뀌면(%s 새 재생기 — 폴링 교체 · LIVE) 붙잡은 404 는 옛 재생기와 함께 버린다 — 다시 틀어도 고리 0',
    async (kind) => {
      const { video, hooks, hls, swapTo } = await pausedAt(0);
      raise(hls, fragError(404));
      await swapTo({ source: sourceOf(kind, 2, kind === 'rewind' ? S : null), hooks });
      expect(fake.instances).toHaveLength(2);
      resume(video);
      expect(hooks.onDvrSegmentGone).not.toHaveBeenCalled();
    },
  );
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

/** 고갈 시험의 멎은 자리(영상 초) — 옛 목록 창 하한(12초) 안이라 다시 틀 때 시크를 청하지 않는다 */
const STALL_S = 20;

/**
 * 영상 노드의 받아 둔 구간 · 시크 중 여부를 시험이 쥔다(jsdom 에는 미디어 구현이 없다 — error 도 브라우저의 null 이 아니라
 * undefined 라 null 로 세운다). 기본은 STALL_S 에서 받아 둔 영상이 0.3초 남은 꼴이다
 */
function mediaState(video: HTMLVideoElement) {
  const state = { buffered: [[0, STALL_S + 0.3]] as Array<[number, number]>, seeking: false };
  Object.defineProperty(video, 'buffered', {
    get: () => ({
      length: state.buffered.length,
      start: (i: number) => state.buffered[i]?.[0] ?? 0,
      end: (i: number) => state.buffered[i]?.[1] ?? 0,
    }),
    configurable: true,
  });
  Object.defineProperty(video, 'seeking', { get: () => state.seeking, configurable: true });
  Object.defineProperty(video, 'error', { get: () => null, configurable: true });
  video.currentTime = STALL_S;
  return state;
}

function fire(video: HTMLVideoElement, type: string) {
  act(() => {
    video.dispatchEvent(new Event(type));
  });
}

async function advance(ms: number) {
  await act(async () => vi.advanceTimersByTime(ms));
}

describe('받기를 멈춘 되감기 재생기의 고갈 감시 배선(POK-547)', () => {
  beforeEach(() => {
    stubMediaPlayback();
    vi.spyOn(console, 'info').mockImplementation(() => {});
    // 화면은 섰지만 이어 틀 데이터가 없다(HAVE_CURRENT_DATA)
    vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockImplementation(() => 2);
  });

  /** 세션 모드 고리 — 고갈 감시를 켜고 받기 중단 · 고갈 · 404 를 센다 */
  function drainHooks(patch: Pick<PlaybackHooks, 'onEndReached' | 'watchesDrain'> = {}) {
    return {
      onSeekRequest: vi.fn(),
      onDvrSegmentGone: vi.fn(),
      onDvrLoadStopped: vi.fn(),
      onDvrDrain: vi.fn(),
      watchesDrain: vi.fn(() => true),
      ...patch,
    };
  }

  /** 옛 목록을 쥔 되감기 재생기(세대 1)를 STALL_S 에서 재생 중으로 세운다 */
  async function playingAt(hooks: PlaybackHooks, kind: PlaybackSource['kind'] = 'rewind') {
    const mounted = await mount(sourceOf(kind, 1), hooks);
    const media = mediaState(mounted.video);
    const hls = fake.instances.at(-1) ?? null;
    if (hls !== null) hls.latestLevelDetails = OLD_LIST;
    return { ...mounted, media, hls };
  }

  function resume(video: HTMLVideoElement) {
    act(() => {
      void video.play();
    });
  }

  it('재생 중 404 → 받기 중단 1 · 무장 → 정체 · waiting 1,000ms 넘게 → 이 세대 · 멎은 자리의 실제 시각으로 고갈 1회', async () => {
    const hooks = drainHooks();
    const { video, hls } = await playingAt(hooks);

    raise(hls!, fragError(404));
    fire(video, 'waiting');
    expect(hooks.onDvrLoadStopped.mock.calls).toEqual([[1]]);
    expect(hooks.onDvrSegmentGone).toHaveBeenCalledTimes(1);
    await advance(900);
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
    await advance(1_100);
    expect(hooks.onDvrDrain.mock.calls).toEqual([[1, true, F + STALL_S * 1_000]]);
    await advance(5_000);
    expect(hooks.onDvrDrain).toHaveBeenCalledTimes(1);
  });

  it('목록 끝 정체가 먼저 서면 끝 도달만 올리고 고갈은 알리지 않는다 — 그리기 틱 밖의 waiting 에서도', async () => {
    const onEndReached = vi.fn();
    const hooks = drainHooks({ onEndReached });
    const { video, media, hls } = await playingAt(hooks);
    // 목록(40초) 끝 0.5초 앞에서 받아 둔 영상이 0.3초 남았고, 목록은 3 × TD 넘게 자라지 않았다
    video.currentTime = 39.5;
    media.buffered = [[0, 39.8]];
    await advance(13_000);
    expect(onEndReached).not.toHaveBeenCalled();

    raise(hls!, fragError(404));
    // waiting 없이 1,500ms 정체 — 그리기 틱의 점검은 waiting 을 못 봐 끝도 고갈도 세우지 않는다
    await advance(1_500);
    expect(onEndReached).not.toHaveBeenCalled();
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
    fire(video, 'waiting');

    expect(onEndReached).toHaveBeenCalledTimes(1);
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
  });

  it('멈춤 → 404 → 다시 틈 — 받기 중단은 404 순간 1 · 404 통지 · 무장은 다시 틀 때 · 그 뒤 정체로 고갈 1회', async () => {
    const hooks = drainHooks();
    const { video, hls } = await playingAt(hooks);
    userPause(video);

    raise(hls!, fragError(404));
    expect(hooks.onDvrLoadStopped.mock.calls).toEqual([[1]]);
    expect(hooks.onDvrSegmentGone).not.toHaveBeenCalled();
    await advance(3_000);
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
    resume(video);
    expect(hooks.onDvrSegmentGone).toHaveBeenCalledTimes(1);
    fire(video, 'waiting');
    await advance(2_000);

    expect(hooks.onDvrDrain.mock.calls).toEqual([[1, true, F + STALL_S * 1_000]]);
    expect(hooks.onDvrLoadStopped).toHaveBeenCalledTimes(1);
  });

  it('멈춤 → 404 → 버퍼 밖 시크(닿지 않음) → 다시 틈 — 무장하는 순간 곧바로 그 목표로 고갈', async () => {
    const hooks = drainHooks();
    const { video, media, hls } = await playingAt(hooks);
    userPause(video);
    raise(hls!, fragError(404));
    media.seeking = true;
    video.currentTime = 30;
    fire(video, 'seeking');
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();

    resume(video);

    expect(hooks.onDvrDrain.mock.calls).toEqual([[1, true, F + 30_000]]);
    expect(hooks.onDvrLoadStopped).toHaveBeenCalledTimes(1);
  });

  it('멈춤 → 404 → 버퍼 안 시크(닿음) → 다시 틈 — 곧바로 알리지 않고 보통 판정 규칙대로 잰다', async () => {
    const hooks = drainHooks();
    const { video, media, hls } = await playingAt(hooks);
    userPause(video);
    raise(hls!, fragError(404));
    media.seeking = true;
    video.currentTime = 5;
    fire(video, 'seeking');
    media.seeking = false;
    fire(video, 'timeupdate');
    fire(video, 'seeked');

    resume(video);
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
    await advance(3_000);
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
    video.currentTime = STALL_S;
    fire(video, 'waiting');
    await advance(2_000);

    expect(hooks.onDvrDrain.mock.calls).toEqual([[1, true, F + STALL_S * 1_000]]);
    expect(hooks.onDvrLoadStopped).toHaveBeenCalledTimes(1);
  });

  it('404 → 무장 → 멈춤 → 다시 틈 — 재던 후보를 버리고 처음부터 잰다 · 404 통지 · 무장은 한 번', async () => {
    const hooks = drainHooks();
    const { video, hls } = await playingAt(hooks);
    raise(hls!, fragError(404));
    fire(video, 'waiting');
    await advance(800);
    userPause(video);
    resume(video);
    fire(video, 'waiting');

    await advance(800);
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
    await advance(1_200);
    expect(hooks.onDvrDrain).toHaveBeenCalledTimes(1);
    expect(hooks.onDvrSegmentGone).toHaveBeenCalledTimes(1);
    expect(hooks.onDvrLoadStopped).toHaveBeenCalledTimes(1);
    expect(hooks.watchesDrain).toHaveBeenCalledTimes(1);
  });

  it('고갈을 보라고 하지 않는 세션의 404 — 받기 중단만 올리고 무장하지 않는다', async () => {
    const hooks = drainHooks({ watchesDrain: vi.fn(() => false) });
    const { video, hls } = await playingAt(hooks);
    raise(hls!, fragError(404));
    fire(video, 'waiting');
    await advance(3_000);
    expect(hooks.onDvrLoadStopped).toHaveBeenCalledTimes(1);
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
  });

  it('라이브 재생기의 404 는 가로채지 않아 받기 중단 · 고갈 모두 0', async () => {
    const hooks = drainHooks();
    const { video, hls } = await playingAt(hooks, 'live');
    raise(hls!, fragError(404));
    fire(video, 'waiting');
    await advance(3_000);
    expect(hooks.onDvrLoadStopped).not.toHaveBeenCalled();
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
  });

  it('404 가 없으면 정체가 이어져도 고갈 0', async () => {
    const hooks = drainHooks();
    const { video } = await playingAt(hooks);
    fire(video, 'waiting');
    await advance(3_000);
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
  });

  it('기본 HLS 갈래(Safari)는 404 를 가로채지 않아 무장하지 않는다 — 정체가 이어져도 고갈 0', async () => {
    vi.spyOn(FakeHls, 'isSupported').mockReturnValue(false);
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    const hooks = drainHooks();
    const { video, result } = await playingAt(hooks);
    expect(result.current.engine).toBe('native');
    fire(video, 'waiting');
    await advance(3_000);
    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
  });

  it('무장 여부는 404 순간의 세션 상태로 정한다 — 재생기가 선 뒤 바뀐 답도 다시 그리기 없이 읽는다', async () => {
    let watching = false;
    const hooks = drainHooks({ watchesDrain: vi.fn(() => watching) });
    const { video, hls } = await playingAt(hooks);
    watching = true;

    raise(hls!, fragError(404));
    fire(video, 'waiting');
    await advance(2_000);

    expect(hooks.onDvrDrain).toHaveBeenCalledTimes(1);
  });

  it('세대가 바뀌면 옛 재생기의 감시를 정리한다 — 늦은 고갈 통지 0', async () => {
    const hooks = drainHooks();
    const { video, hls, swapTo } = await playingAt(hooks);
    raise(hls!, fragError(404));
    fire(video, 'waiting');
    await advance(500);

    await swapTo({ source: sourceOf('rewind', 2, S), hooks });
    fire(video, 'waiting');
    await advance(3_000);

    expect(hooks.onDvrDrain).not.toHaveBeenCalled();
  });
});

describe('세우지 않음의 지금 상태 읽기 — 효과 첫머리와 hls.js 읽기를 기다린 뒤(POK-547)', () => {
  beforeEach(() => {
    stubMediaPlayback();
    fake.bootList = details(F, 4);
  });

  it('효과 첫머리에는 거짓이던 보류가 hls.js 를 읽는 사이 참이 되면 hls.js 를 만들지 않는다', async () => {
    const isStartHeld = vi.fn().mockReturnValueOnce(false).mockReturnValue(true);
    const { video } = await mount(sourceOf('rewind', 5, S), { isStartHeld });
    expect(isStartHeld).toHaveBeenCalledTimes(2);
    expect(fake.instances).toHaveLength(0);
    expect(playCalls()).toBe(0);
    expect(video.getAttribute('src')).toBeNull();
  });

  it('기본 HLS 갈래(Safari)도 같다 — video.src 를 세우지 않는다', async () => {
    vi.spyOn(FakeHls, 'isSupported').mockReturnValue(false);
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    const isStartHeld = vi.fn().mockReturnValueOnce(false).mockReturnValue(true);
    const { video } = await mount(sourceOf('rewind', 5, S), { isStartHeld });
    expect(video.getAttribute('src')).toBeNull();
    expect(playCalls()).toBe(0);
  });

  it('지금 상태의 보류가 처음부터 참이면 렌더 값이 거짓이어도 세우지 않는다 · 늘 거짓이면 지금처럼 선다', async () => {
    await mount(sourceOf('rewind', 5, S), { startHeld: false, isStartHeld: () => true });
    expect(fake.instances).toHaveLength(0);
    await mount(sourceOf('rewind', 6, S), { startHeld: false, isStartHeld: () => false });
    expect(fake.instances).toHaveLength(1);
  });
});
