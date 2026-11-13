import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 세션 모드 시크바의 왼쪽 끝(POK-573) — 비율 0(Home) · 왼쪽 끝을 넘는 상대 이동은 「막대 왼쪽 끝」 을 세션에 요청하고,
// 카드의 범위 밖 문턱도 같은 자리다. hls.js 는 사건 처리기 · 목록 · 라이브 끝만 흉내 내고, <video> 는 재생 위치 ·
// seekable · paused 를 시험이 쥔다. 시각 축은 실제 sessionAxis 가 센다(목록은 진짜 loadedList 를 지난다).

interface Frag {
  start: number;
  duration: number;
  programDateTime: number;
  gap?: boolean;
}
interface Details {
  fragments: Frag[];
  fragmentStart: number;
  targetduration: number;
  live: boolean;
}

const fake = vi.hoisted(() => ({ instances: [] as FakeHls[] }));

vi.mock('hls.js', () => ({ default: FakeHls }));

class FakeHls {
  static isSupported = () => true;
  static Events = { LEVEL_UPDATED: 'hlsLevelUpdated', ERROR: 'hlsError' };
  static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  // hls.js 1.7.3 의 값 그대로(errors.ts)
  static ErrorDetails = {
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    LEVEL_LOAD_ERROR: 'levelLoadError',
    AUDIO_TRACK_LOAD_ERROR: 'audioTrackLoadError',
    FRAG_LOAD_ERROR: 'fragLoadError',
  };
  readonly handlers = new Map<string, ((event: string, data: unknown) => void)[]>();
  latestLevelDetails: Details | null = null;
  liveSyncPosition: number | null = null;
  constructor() {
    fake.instances.push(this);
  }
  on(event: string, handler: (event: string, data: unknown) => void) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  off() {}
  /** 목록 갱신 — 처리기보다 먼저 latestLevelDetails 를 채운다(hls.js 의 순서) */
  list(details: Details) {
    this.latestLevelDetails = details;
    for (const h of this.handlers.get(FakeHls.Events.LEVEL_UPDATED) ?? []) {
      h(FakeHls.Events.LEVEL_UPDATED, { details });
    }
  }
  attachMedia() {}
  loadSource() {}
  startLoad() {}
  stopLoad() {}
  recoverMediaError() {}
  destroy() {}
}

const PDT0 = Date.parse('2026-10-25T05:00:00.000Z');
/** 재생기가 되감기 끝을 「지금 − 라이브 지연」 으로 셀 때의 지연(useHlsPlayback 의 첫 값) */
const LAG_MS = 3_000;
/** 맨 앞 GAP 두 조각(4초)의 길이 — 첫 내용 조각 = PDT0 + 이 값 */
const GAP_MS = 8_000;
const CONTENT_MS = PDT0 + GAP_MS;

/** 4초 조각 count 개(영상 0초 · PDT0 부터). 맨 앞 gaps 개는 EXT-X-GAP 이다 */
function details(count: number, gaps: number, live: boolean): Details {
  const fragments = Array.from({ length: count }, (_, i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: PDT0 + i * 4_000,
    gap: i < gaps,
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live };
}

const video = { el: null as HTMLVideoElement | null, paused: false };

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

function source(kind: PlaybackSource['kind']): PlaybackSource {
  return {
    kind,
    url: `https://m/${kind}/k/index.m3u8`,
    startAtMs: null,
    generation: 0,
    strictStart: false,
  };
}

/** 세션 모드 재생기를 세우고 목록을 준 뒤 재생 위치 atSeconds 에서 한 번 그린다(React 의 창 폭 · 시차가 선다) */
async function mountSession(
  kind: PlaybackSource['kind'],
  list: Details,
  atSeconds: number,
  hooks: Omit<PlaybackHooks, 'onSeekRequest'> = {},
) {
  const el = document.createElement('video');
  video.el = el;
  const ref = { current: el };
  const onSeekRequest = vi.fn();
  const all: PlaybackHooks = { ...hooks, onSeekRequest };
  const view = renderHook(() =>
    useHlsPlayback(ref, source(kind), {}, undefined, undefined, false, undefined, all),
  );
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  el.currentTime = atSeconds;
  act(() => lastHls().list(list));
  act(() => {
    el.dispatchEvent(new Event('timeupdate'));
  });
  return { view, onSeekRequest };
}

/** 마지막 요청의 목표 시각 */
function lastTarget(onSeekRequest: ReturnType<typeof vi.fn>): unknown {
  return onSeekRequest.mock.lastCall?.[0];
}

beforeEach(() => {
  fake.instances.length = 0;
  video.paused = false;
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {
    video.paused = true;
  });
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockImplementation(() => video.paused);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockReturnValue(4);
  vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockImplementation(
    () => ({ length: 1, start: () => 0, end: () => 40 }) as unknown as TimeRanges,
  );
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('Home(비율 0)은 막대 왼쪽 끝을 요청한다 — 창 폭 · 1시간 표시 상한을 거치지 않는다', () => {
  // 맨 앞 GAP 둘 + 4초 내용 조각 900개(3 600초) — 끝 = 첫 내용 조각 + 3 600초
  const LIST_END_MS = CONTENT_MS + 3_600_000;

  it.each([
    ['창이 1시간보다 5초 넓다', 3_605_000],
    ['창이 1시간보다 30초 넓다(옛 여유 12초보다 크다)', 3_630_000],
  ])(
    '방송 중 되감기 — %s: 목표는 첫 내용 조각이고 하한(그 조각 하나 안쪽)보다 앞이라 세션이 하한으로 옮긴다',
    async (_where, spanMs) => {
      vi.useFakeTimers({ now: CONTENT_MS + spanMs + LAG_MS });
      const { view, onSeekRequest } = await mountSession('rewind', details(902, 2, true), 3_000);
      act(() => view.result.current.seekToFraction(0));
      expect(onSeekRequest).toHaveBeenCalledOnce();
      expect(onSeekRequest).toHaveBeenLastCalledWith(CONTENT_MS, {
        lowerMs: CONTENT_MS + 4_000,
        endMs: LIST_END_MS,
      });
    },
  );

  it.each([
    ['끝난 되감기 목록(ENDLIST)', 'rewind'],
    ['다시보기', 'vod'],
  ] as const)('%s — 목표가 하한(첫 내용 조각)과 같다', async (_where, kind) => {
    // 맨 앞 GAP 둘 + 내용 조각 450개(1 800초) — 1시간 표시 상한에 닿지 않는 폭
    vi.useFakeTimers({ now: CONTENT_MS + 1_800_000 + LAG_MS });
    const { view, onSeekRequest } = await mountSession(kind, details(452, 2, false), 100);
    act(() => view.result.current.seekToFraction(0));
    expect(onSeekRequest).toHaveBeenLastCalledWith(CONTENT_MS, {
      lowerMs: CONTENT_MS,
      endMs: CONTENT_MS + 1_800_000,
    });
  });

  it('그린 뒤 창이 자라 React 의 창 폭이 낡아도 목표는 첫 내용 조각이다', async () => {
    vi.useFakeTimers({ now: CONTENT_MS + 1_800_000 + LAG_MS });
    const { view, onSeekRequest } = await mountSession('rewind', details(452, 2, true), 100);
    expect(view.result.current.windowSeconds).toBe(1_800);
    // 그리지 않고 30초 흐른다 — 되감기 끝(지금 − 지연)만 자란다
    vi.setSystemTime(CONTENT_MS + 1_830_000 + LAG_MS);
    act(() => view.result.current.seekToFraction(0));
    expect(view.result.current.windowSeconds).toBe(1_800);
    expect(lastTarget(onSeekRequest)).toBe(CONTENT_MS);
  });

  it('비율이 0 보다 크면 지금처럼 끝에서 시차만큼 앞이다', async () => {
    vi.useFakeTimers({ now: CONTENT_MS + 1_800_000 + LAG_MS });
    const { view, onSeekRequest } = await mountSession('rewind', details(452, 2, true), 100);
    act(() => view.result.current.seekToFraction(0.5));
    expect(lastTarget(onSeekRequest)).toBe(CONTENT_MS + 900_000);
  });
});

describe('상대 이동이 막대 왼쪽 끝을 넘으면 왼쪽 끝을 요청한다', () => {
  it('방송 중 되감기에서 시차 + 이동이 창 폭 이상이면 목표는 첫 내용 조각이다', async () => {
    vi.useFakeTimers({ now: CONTENT_MS + 1_800_000 + LAG_MS });
    const { view, onSeekRequest } = await mountSession('rewind', details(452, 2, true), 100);
    act(() => view.result.current.seekBy(3_600));
    expect(lastTarget(onSeekRequest)).toBe(CONTENT_MS);
  });

  it('창 안에 머무는 이동은 지금처럼 끝에서 (시차 + 이동) 앞이다', async () => {
    vi.useFakeTimers({ now: CONTENT_MS + 1_800_000 + LAG_MS });
    // 재생 위치 1 000초 = PDT0 + 1 000 000 · 끝 = 첫 내용 조각 + 1 800 000 → 시차 808초
    const { view, onSeekRequest } = await mountSession('rewind', details(452, 2, true), 1_000);
    act(() => view.result.current.seekBy(10));
    expect(lastTarget(onSeekRequest)).toBe(CONTENT_MS + 1_800_000 - 818_000);
  });
});

describe('카드의 범위 밖 문턱은 막대 왼쪽 끝이다(카드 초 1초 봐줌)', () => {
  it('되감기는 낡은 axisLowerMs(목록보다 10분 이른)를 쓰지 않는다 — 첫 조각 1.5초 앞은 범위 밖 · 0.5초 앞은 옮긴다', async () => {
    vi.useFakeTimers({ now: PDT0 + 1_800_000 + LAG_MS });
    const { view, onSeekRequest } = await mountSession('rewind', details(450, 0, true), 100, {
      axisLowerMs: PDT0 - 600_000,
    });
    let result: string | undefined;
    act(() => {
      result = view.result.current.seekToDate?.(PDT0 - 1_500);
    });
    expect(result).toBe('outside');
    expect(onSeekRequest).not.toHaveBeenCalled();
    act(() => {
      result = view.result.current.seekToDate?.(PDT0 - 500);
    });
    expect(result).toBe('moved');
    expect(lastTarget(onSeekRequest)).toBe(PDT0 - 500);
  });

  it.each(['rewind', 'vod'] as const)(
    '%s 는 맨 앞 GAP 을 범위에 넣지 않는다 — 첫 내용 조각 1.5초 앞은 범위 밖 · 0.5초 앞은 옮긴다',
    async (kind) => {
      vi.useFakeTimers({ now: PDT0 + 1_800_000 + LAG_MS });
      const { view, onSeekRequest } = await mountSession(kind, details(450, 2, true), 100);
      let result: string | undefined;
      act(() => {
        result = view.result.current.seekToDate?.(CONTENT_MS - 1_500);
      });
      expect(result).toBe('outside');
      expect(onSeekRequest).not.toHaveBeenCalled();
      act(() => {
        result = view.result.current.seekToDate?.(CONTENT_MS - 500);
      });
      expect(result).toBe('moved');
      expect(lastTarget(onSeekRequest)).toBe(CONTENT_MS - 500);
    },
  );
});

describe('라이브는 되감기 범위(axisLowerMs)까지 넓힌 왼쪽 끝을 요청한다 — 1시간을 넘어도', () => {
  /** 라이브 목록 10조각 · 라이브 끝 38초(PDT0 + 38 000) · 되감기 범위는 그보다 3 610초 이르다 */
  const EDGE_MS = PDT0 + 38_000;
  const AXIS_LOWER_MS = EDGE_MS - 3_610_000;

  async function mountLive() {
    vi.useFakeTimers({ now: PDT0 + 40_000 });
    const el = document.createElement('video');
    video.el = el;
    const ref = { current: el };
    const onSeekRequest = vi.fn();
    const hooks: PlaybackHooks = { onSeekRequest, axisLowerMs: AXIS_LOWER_MS };
    const view = renderHook(() =>
      useHlsPlayback(ref, source('live'), {}, undefined, undefined, false, undefined, hooks),
    );
    await act(async () => {
      await vi.dynamicImportSettled();
    });
    lastHls().liveSyncPosition = 38;
    el.currentTime = 36;
    act(() => lastHls().list(details(10, 0, true)));
    act(() => {
      el.dispatchEvent(new Event('timeupdate'));
    });
    return { view, onSeekRequest };
  }

  it('Home 의 목표는 axisLowerMs 다', async () => {
    const { view, onSeekRequest } = await mountLive();
    expect(view.result.current.windowSeconds).toBe(3_600);
    act(() => view.result.current.seekToFraction(0));
    expect(lastTarget(onSeekRequest)).toBe(AXIS_LOWER_MS);
  });

  it('왼쪽 끝을 넘는 상대 이동의 목표도 axisLowerMs 다', async () => {
    const { view, onSeekRequest } = await mountLive();
    act(() => view.result.current.seekBy(4_000));
    expect(lastTarget(onSeekRequest)).toBe(AXIS_LOWER_MS);
  });
});

describe('멈췄다 다시 틀 때의 하한 — 끝난 목록은 첫 내용 조각 · 방송 중은 그 조각 하나 안쪽', () => {
  it.each([
    ['끝난 되감기 목록(ENDLIST)은 첫 내용 조각에서 다시 틀어도 세션에 넘기지 않는다', false, 0],
    ['방송 중 되감기는 첫 내용 조각에서 다시 틀면 하한으로 옮기게 세션에 넘긴다', true, 1],
  ])('%s', async (_where, live, requests) => {
    vi.useFakeTimers({ now: PDT0 + 1_800_000 + LAG_MS });
    const { onSeekRequest } = await mountSession('rewind', details(450, 2, live), 8);
    act(() => {
      video.paused = true;
      video.el!.dispatchEvent(new Event('pause'));
    });
    act(() => {
      video.paused = false;
      video.el!.dispatchEvent(new Event('play'));
    });
    expect(onSeekRequest).toHaveBeenCalledTimes(requests);
  });
});
