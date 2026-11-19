import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 라이브 재생기가 「라이브 보는 중」 에만 따라잡는다(POK-583 c2 — H1–H10). 켜고 끄는 것은 사람 명령 자리(세션 seekToMs ·
// 비세션 시크 · LIVE · 사람 pause)와 재생기가 설 때(MANIFEST_PARSED)다. hls.js 는 따라잡기가 읽는 값(latency ·
// targetLatency · liveSyncPosition · latestLevelDetails)만 흉내 내고, <video> 는 재생 위치 · 배속 쓰기를 센다. 영상
// 0–32초 · 4초 조각 8개 · 라이브 동기 위치 30초 = 라이브 끝. 목표 1.5초.

interface Details {
  fragments: Array<{ start: number; duration: number; programDateTime: number }>;
  fragmentStart: number;
  targetduration: number;
  live: boolean;
  age: number;
}

interface FakeHlsLike {
  latestLevelDetails: Details | null;
  liveSyncPosition: number | null;
  latency: number;
  targetLatency: number | null;
  trigger: (event: string, data: unknown) => void;
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
      LEVEL_UPDATED: 'hlsLevelUpdated',
    };
    static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
    static ErrorDetails = { LEVEL_LOAD_ERROR: 'levelLoadError' };
    readonly handlers = new Map<string, ((event: string, data: unknown) => void)[]>();
    latestLevelDetails: Details | null = null;
    liveSyncPosition: number | null = null;
    latency = 0;
    targetLatency: number | null = null;
    constructor() {
      fake.instances.push(this);
    }
    on(event: string, handler: (event: string, data: unknown) => void) {
      this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
    }
    once(event: string, handler: (event: string, data: unknown) => void) {
      this.on(event, handler);
    }
    off() {}
    trigger(event: string, data: unknown) {
      for (const h of this.handlers.get(event) ?? []) h(event, data);
    }
    attachMedia() {}
    loadSource() {}
    startLoad() {}
    destroy() {}
  }
  return { default: FakeHls };
});

const PDT0 = Date.parse('2026-11-19T05:00:00.000Z');
const EDGE_S = 30;
const EDGE_MS = PDT0 + EDGE_S * 1000;
const TARGET_S = 1.5;
const CHROME_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const SAFARI_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15';

function details(): Details {
  const fragments = Array.from({ length: 8 }, (_, i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: PDT0 + i * 4_000,
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live: true, age: 0.5 };
}

const LIVE: PlaybackSource = {
  kind: 'live',
  url: 'https://m/live/k/index.m3u8',
  startAtMs: null,
  generation: 0,
  strictStart: false,
};

/** 시험이 쥐는 영상 노드 상태 — 재생 위치 · 배속은 쓰기를 센다 */
const media = {
  time: 0,
  paused: false,
  timeWrites: [] as number[],
  rateWrites: [] as number[],
  rate: 1,
};
const userAgent = { value: CHROME_UA };
let onSeek = vi.fn();

function lastHls(): FakeHlsLike {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

/** 영상 노드에 재생 위치 · 배속을 단다(쓰기를 센다) */
function videoElement(): HTMLVideoElement {
  const el = document.createElement('video');
  Object.defineProperty(el, 'currentTime', {
    configurable: true,
    get: () => media.time,
    set: (value: number) => {
      media.timeWrites.push(value);
      media.time = value;
    },
  });
  Object.defineProperty(el, 'playbackRate', {
    configurable: true,
    get: () => media.rate,
    set: (value: number) => {
      media.rateWrites.push(value);
      media.rate = value;
    },
  });
  return el;
}

/**
 * 재생기를 세우고 목록을 준 뒤 MANIFEST_PARSED 를 쏜다. 재생 위치는 atS · 지연은 라이브 끝 − 재생 위치 · 목표 1.5초.
 * 쏘기 전에 쓰기 기록을 비운다
 */
async function mount(source: PlaybackSource, hooks: PlaybackHooks = {}, atS = 20) {
  const el = videoElement();
  const ref = { current: el };
  const view = renderHook(
    ({ s }) => useHlsPlayback(ref, s, {}, onSeek, undefined, false, undefined, hooks),
    { initialProps: { s: source } },
  );
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  media.time = atS;
  if (fake.supported) {
    const hls = lastHls();
    hls.latestLevelDetails = details();
    hls.liveSyncPosition = EDGE_S;
    hls.targetLatency = TARGET_S;
    hls.latency = EDGE_S - atS;
    act(() => hls.trigger('hlsManifestParsed', {}));
  }
  media.timeWrites.length = 0;
  media.rateWrites.length = 0;
  return { el, view };
}

/** 지연을 바꾼다(hls.latency) */
function setLatency(seconds: number) {
  lastHls().latency = seconds;
}

function timeupdate(el: HTMLVideoElement, times = 1) {
  for (let i = 0; i < times; i++) act(() => el.dispatchEvent(new Event('timeupdate')));
}

/** 사람이 멈춘다(pause 사건) */
function userPause(el: HTMLVideoElement) {
  act(() => el.pause());
}

/** 다시 튼다 — 재개는 재생 위치를 건드리지 않는다 */
function resume() {
  media.paused = false;
}

/** 배속을 한 번이라도 1 밖으로 걸었나 */
function rateRaised(): boolean {
  return media.rateWrites.some((rate) => rate !== 1);
}

beforeEach(() => {
  vi.useFakeTimers({ now: PDT0 + 35_000 });
  fake.instances.length = 0;
  fake.supported = true;
  media.time = 0;
  media.paused = false;
  media.rate = 1;
  media.timeWrites.length = 0;
  media.rateWrites.length = 0;
  userAgent.value = CHROME_UA;
  onSeek = vi.fn();
  vi.spyOn(navigator, 'userAgent', 'get').mockImplementation(() => userAgent.value);
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(() => {
    media.paused = false;
    return Promise.resolve();
  });
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(function (
    this: HTMLMediaElement,
  ) {
    media.paused = true;
    this.dispatchEvent(new Event('pause'));
  });
  vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {});
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockImplementation(() => media.paused);
  vi.spyOn(HTMLMediaElement.prototype, 'seeking', 'get').mockReturnValue(false);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockReturnValue(4);
  vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockReturnValue({
    length: 1,
    start: () => 0,
    end: () => 32,
  } as unknown as TimeRanges);
  vi.spyOn(HTMLMediaElement.prototype, 'buffered', 'get').mockReturnValue({
    length: 1,
    start: () => 0,
    end: () => 32,
  } as unknown as TimeRanges);
});
afterEach(() => {
  // 흉내 load() 가 살아 있는 동안 재생기를 내린다
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

/** 세션 모드 고리 — 사람 시크 목표를 세션에 넘긴다 */
function sessionHooks(over: PlaybackHooks = {}): PlaybackHooks {
  return { onSeekRequest: vi.fn(), ...over };
}

describe('H 기준 — 라이브 첫 진입은 full 로 켜진다', () => {
  it('H8 많이 밀리면 라이브 동기 위치로 건너뛰고, 자동 점프는 onSeek · onSeekRequest 를 부르지 않는다', async () => {
    const hooks = sessionHooks();
    const { el } = await mount(LIVE, hooks);
    setLatency(15);
    timeupdate(el);
    expect(media.timeWrites).toEqual([EDGE_S]);
    expect(onSeek).not.toHaveBeenCalled();
    expect(hooks.onSeekRequest).not.toHaveBeenCalled();
  });

  it('조금 밀리면 배속으로 좁힌다(지연 − 목표 1.5초 → 1.1)', async () => {
    const { el } = await mount(LIVE, {}, EDGE_S);
    setLatency(3);
    timeupdate(el);
    expect(media.rate).toBe(1.1);
    expect(media.timeWrites).toEqual([]);
  });
});

describe('H1 원복 — 배속은 재생기가 떠나거나 읽을 것이 없어지면 1 로', () => {
  it('재생기를 내리면(언마운트) 배속이 1 로 돌아온다', async () => {
    const { el, view } = await mount(LIVE, {}, EDGE_S);
    setLatency(3);
    timeupdate(el);
    expect(media.rate).toBe(1.1);
    view.unmount();
    expect(media.rate).toBe(1);
  });

  it('목록이 끝나면(ENDLIST · live:false) 다음 표본에서 배속을 1 로 되돌린다', async () => {
    const { el } = await mount(LIVE, {}, EDGE_S);
    setLatency(3);
    timeupdate(el);
    expect(media.rate).toBe(1.1);
    const ended = details();
    lastHls().latestLevelDetails = { ...ended, live: false };
    timeupdate(el);
    expect(media.rate).toBe(1);
  });
});

describe.each([
  ['세션 모드 — seekToMs(사람 시크가 세션을 거쳐 온다)', true],
  ['세션 밖 — 키 이동(seekBy → seekToBehind)', false],
] as const)('H2 되감은 시청자를 끌어당기지 않는다 — %s', (...[, session]) => {
  it('10초 되감은 뒤 지연 15초 · timeupdate 20회 — 배속 1 · 재생 위치 쓰기는 그 시크 하나', async () => {
    const { el, view } = await mount(LIVE, session ? sessionHooks() : {}, EDGE_S);
    act(() => {
      if (session) view.result.current.seekToMs(EDGE_MS - 10_000);
      else view.result.current.seekBy(10);
    });
    expect(media.timeWrites).toEqual([EDGE_S - 10]);
    setLatency(15);
    timeupdate(el, 20);
    expect(rateRaised()).toBe(false);
    expect(media.rate).toBe(1);
    expect(media.timeWrites).toEqual([EDGE_S - 10]);
  });
});

describe('H3 사람 일시정지만 끈다', () => {
  it('사람이 멈췄다 다시 틀면 많이 밀려도 따라잡지 않는다', async () => {
    const { el } = await mount(LIVE);
    userPause(el);
    resume();
    setLatency(15);
    timeupdate(el, 5);
    expect(media.timeWrites).toEqual([]);
    expect(rateRaised()).toBe(false);
  });

  it('앱이 건 멈춤(holdFrame)은 끄지 않는다 — 다시 틀면 따라잡는다', async () => {
    const { el, view } = await mount(LIVE);
    act(() => view.result.current.holdFrame());
    expect(media.paused).toBe(true);
    resume();
    setLatency(15);
    timeupdate(el);
    expect(media.timeWrites).toEqual([EDGE_S]);
  });

  it('배속 중에 사람이 멈추면 배속을 1 로 되돌린다', async () => {
    const { el } = await mount(LIVE, {}, EDGE_S);
    setLatency(3);
    timeupdate(el);
    expect(media.rate).toBe(1.1);
    userPause(el);
    expect(media.rate).toBe(1);
  });
});

describe('H4 꺼진 뒤 LIVE 로 다시 켠다', () => {
  it.each([
    ['세션 LIVE(seekToLiveEdge)', true],
    ['세션 밖 「실시간으로」(returnToLive)', false],
  ] as const)('%s', async (...[, session]) => {
    const { el, view } = await mount(LIVE, session ? sessionHooks() : {});
    userPause(el);
    act(() => {
      if (session) view.result.current.seekToLiveEdge();
      else view.result.current.returnToLive();
    });
    expect(media.time).toBe(EDGE_S);
    setLatency(3);
    timeupdate(el);
    expect(media.rate).toBe(1.1);
  });
});

describe('H5 넘김 재생기는 넘김 확인이 끝날 때까지 배속만(Q3 (다))', () => {
  const HANDOFF: PlaybackSource = {
    ...LIVE,
    startAtMs: PDT0 + 20_000,
    generation: 1,
    strictStart: true,
  };

  it('확인 중에는 지연 15초여도 건너뛰지 않고 1.1 배속 · 확인이 끝난 첫 표본에서 건너뛴다', async () => {
    const confirming = { value: true };
    const { el } = await mount(
      HANDOFF,
      sessionHooks({ isHandoffConfirming: () => confirming.value }),
    );
    setLatency(15);
    timeupdate(el, 3);
    expect(media.timeWrites).toEqual([]);
    expect(media.rate).toBe(1.1);
    confirming.value = false;
    timeupdate(el);
    expect(media.timeWrites).toEqual([EDGE_S]);
    expect(media.rate).toBe(1);
  });

  it('R3 반례 — 넘긴 자리 24 · 되감기 끝 28 · 지금 25 · 라이브 끝 32 · 목표 7.25 · 지연 7: 확인 중 점프 0 · 배속 0', async () => {
    const { el } = await mount(HANDOFF, sessionHooks({ isHandoffConfirming: () => true }), 25);
    const hls = lastHls();
    hls.targetLatency = 7.25;
    hls.liveSyncPosition = 32 - 7.25;
    setLatency(7);
    timeupdate(el, 5);
    expect(media.timeWrites).toEqual([]);
    expect(rateRaised()).toBe(false);
  });

  it('C5 확인 중에 사람이 끝 3초 안으로 시크해 full 로 켜도 점프 금지가 이긴다', async () => {
    const { el, view } = await mount(HANDOFF, sessionHooks({ isHandoffConfirming: () => true }));
    act(() => view.result.current.seekToMs(EDGE_MS - 1_000));
    // 목록이 자라 라이브 동기 위치가 10초 앞으로 갔다
    lastHls().liveSyncPosition = EDGE_S + 10;
    setLatency(15);
    timeupdate(el, 3);
    expect(media.timeWrites).toEqual([EDGE_S - 1]);
  });
});

describe('H6 되감기 · 다시보기 재생기는 따라잡지 않는다', () => {
  it.each(['rewind', 'vod'] as const)(
    'D1 %s 재생기 끝 근처 seekToMs — 배속 0 · 점프 0',
    async (kind) => {
      const source: PlaybackSource = {
        kind,
        url: `https://m/${kind}/k/index.m3u8`,
        startAtMs: null,
        generation: 1,
        strictStart: false,
      };
      const { el, view } = await mount(source, sessionHooks());
      // 목록 끝(32초 · 되감기는 지금 − 3초 = 32초) 1초 앞 — 라이브라면 「실시간으로」 와 같은 자리다
      act(() => view.result.current.seekToMs(PDT0 + 31_000));
      lastHls().liveSyncPosition = EDGE_S + 10;
      setLatency(15);
      timeupdate(el, 5);
      expect(media.timeWrites).toEqual([31]);
      expect(rateRaised()).toBe(false);
    },
  );

  it('배속 중인 라이브 재생기를 되감기 재생기로 갈아 끼우면 배속이 1 로 돌아온다', async () => {
    const { el, view } = await mount(LIVE, sessionHooks(), EDGE_S);
    setLatency(3);
    timeupdate(el);
    expect(media.rate).toBe(1.1);
    view.rerender({
      s: { ...LIVE, kind: 'rewind', url: 'https://m/dvr/k/index.m3u8', generation: 1 },
    });
    await act(async () => {
      await vi.dynamicImportSettled();
    });
    expect(media.rate).toBe(1);
  });
});

describe('H7 멈춘 채 갈아 끼운 라이브 재생기(넘김 경로)', () => {
  it('켜지 않는다 — 다시 틀어도 따라잡지 않는다', async () => {
    const { el, view } = await mount(LIVE);
    userPause(el);
    act(() => view.result.current.keepPausedOnSwap());
    view.rerender({ s: { ...LIVE, generation: 1, startAtMs: PDT0 + 20_000 } });
    await act(async () => {
      await vi.dynamicImportSettled();
    });
    const hls = lastHls();
    hls.latestLevelDetails = details();
    hls.liveSyncPosition = EDGE_S;
    hls.targetLatency = TARGET_S;
    act(() => hls.trigger('hlsManifestParsed', {}));
    media.time = 20;
    media.timeWrites.length = 0;
    resume();
    setLatency(15);
    timeupdate(el, 3);
    expect(media.timeWrites).toEqual([]);
    expect(rateRaised()).toBe(false);
  });
});

describe('H9 기본 HLS(hls.js 미지원 · Safari 기본 재생)', () => {
  it('따라잡기를 달지 않는다 — LIVE 뒤에도 배속을 건드리지 않는다', async () => {
    fake.supported = false;
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    const { el, view } = await mount(LIVE);
    expect(fake.instances).toEqual([]);
    act(() => view.result.current.seekToLiveEdge());
    act(() => view.result.current.returnToLive());
    timeupdate(el, 5);
    expect(media.rateWrites).toEqual([]);
  });
});

describe('H10 오른쪽 끝 3초 안 시크 = 「실시간으로」(Q2 (가) · C3 경계)', () => {
  it.each([
    ['세션 seekToMs 끝 2초 앞 → 켬', true, 2],
    ['세션 seekToMs 정확히 3초 앞 → 켜지 않음', true, 3],
    ['세션 밖 키 이동으로 시차 2초 → 켬(끝으로 스냅)', false, 2],
    ['세션 밖 키 이동으로 정확히 시차 3초 → 켜지 않음', false, 3],
  ] as const)('%s', async (...[, session, behindS]) => {
    const { el, view } = await mount(LIVE, session ? sessionHooks() : {}, EDGE_S);
    // 먼저 10초 되감아 끈다(재생 위치 20초)
    act(() => {
      if (session) view.result.current.seekToMs(EDGE_MS - 10_000);
      else view.result.current.seekBy(10);
    });
    expect(media.time).toBe(EDGE_S - 10);
    act(() => {
      if (session) view.result.current.seekToMs(EDGE_MS - behindS * 1000);
      else view.result.current.seekBy(behindS - 10);
    });
    expect(media.time).toBe(behindS < 3 && !session ? EDGE_S : EDGE_S - behindS);
    setLatency(TARGET_S + 1.5);
    timeupdate(el);
    expect(media.rate).toBe(behindS < 3 ? 1.1 : 1);
  });
});

describe('브라우저별 배속 정책', () => {
  it.each([
    ['Chromium — 초과 1.5초면 켠다', CHROME_UA, 1.5, 1.1],
    ['WebKit(Safari) — 초과 1.5초에 켜지 않는다(Q1 (다) 점프만)', SAFARI_UA, 1.5, 1],
    ['WebKit(Safari) — 초과 3초에도 켜지 않는다(Q1 (다) 점프만)', SAFARI_UA, 3, 1],
  ] as const)('%s', async (...[, ua, excessS, rate]) => {
    userAgent.value = ua;
    const { el } = await mount(LIVE, {}, EDGE_S);
    setLatency(TARGET_S + excessS);
    timeupdate(el);
    expect(media.rate).toBe(rate);
  });
});
