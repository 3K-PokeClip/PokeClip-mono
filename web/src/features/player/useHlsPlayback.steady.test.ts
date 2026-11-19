import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 시크바 떨림 ① · ③(POK-583 c3) — 보이는 시차 숫자는 1초 넘게 벗어날 때만 바뀌고(steadySeconds), 「끝에 있음」 은 반올림 전
// 시차로 판정한다(계획 D5 · R8). 세션 모드(paintSession)와 세션 밖(라이브 창 축) 두 그리기가 같은 규칙이다. hls.js 는 사건
// 처리기 · 목록 · 라이브 끝만 흉내 낸다(useHlsPlayback.position.test.ts 와 같은 흉내).

interface Details {
  fragments: Array<{ start: number; duration: number; programDateTime: number }>;
  fragmentStart: number;
  targetduration: number;
  live: boolean;
}

const fake = vi.hoisted(() => ({ instances: [] as FakeHls[] }));

vi.mock('hls.js', () => ({ default: FakeHls }));

class FakeHls {
  static isSupported = () => true;
  static Events = { LEVEL_UPDATED: 'hlsLevelUpdated' };
  readonly handlers = new Map<string, ((event: string, data: unknown) => void)[]>();
  latestLevelDetails: Details | null = null;
  liveSyncPosition: number | null = null;
  constructor() {
    fake.instances.push(this);
  }
  on(event: string, handler: (event: string, data: unknown) => void) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  trigger(event: string, data: { details: Details }) {
    this.latestLevelDetails = data.details;
    for (const h of this.handlers.get(event) ?? []) {
      h(event, data);
    }
  }
  attachMedia() {}
  loadSource() {}
  startLoad() {}
  destroy() {}
}

const PDT0 = Date.parse('2026-10-25T05:00:00.000Z');
/** 라이브 재생기가 서는 라이브 끝(영상 초) */
const LIVE_EDGE_S = 38;

/** 영상 0초부터 4초 조각 열 개(PDT0 부터) */
function details(): Details {
  const fragments = Array.from({ length: 10 }, (_, i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: PDT0 + i * 4_000,
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live: true };
}

const SOURCE: PlaybackSource = {
  kind: 'live',
  url: 'https://m/live/k/index.m3u8',
  startAtMs: null,
  generation: 0,
  strictStart: false,
};

let videoEl: HTMLVideoElement | null = null;

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

/** 막 선 재생기(갈아 끼운 새 재생기도)에 목록 · 라이브 끝 38초를 준다 */
async function startPlayer() {
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  lastHls().liveSyncPosition = LIVE_EDGE_S;
  act(() => lastHls().trigger('hlsLevelUpdated', { details: details() }));
}

/** 라이브 재생기를 세우고 목록 · 라이브 끝 38초를 준다 */
async function mountLive(hooks: PlaybackHooks) {
  const el = document.createElement('video');
  videoEl = el;
  const ref = { current: el };
  const view = renderHook(
    ({ source }) => useHlsPlayback(ref, source, {}, undefined, undefined, false, undefined, hooks),
    { initialProps: { source: SOURCE } },
  );
  await startPlayer();
  return view;
}

/** 라이브 끝에서 behindS 초 뒤에 두고 한 번 그린다 */
function paintAt(behindS: number) {
  act(() => {
    videoEl!.currentTime = LIVE_EDGE_S - behindS;
    videoEl!.dispatchEvent(new Event('timeupdate'));
  });
}

beforeEach(() => {
  vi.useFakeTimers({ now: PDT0 + 40_000 });
  fake.instances.length = 0;
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockReturnValue(false);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockReturnValue(4);
  vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockImplementation(
    () => ({ length: 1, start: () => 0, end: () => 40 }) as unknown as TimeRanges,
  );
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe.each([
  ['세션 모드(paintSession)', { onSeekRequest: () => {} }],
  ['세션 밖(라이브 창 축)', {}],
] as const)('시차 표시 · 끝 판정 — %s', (...[, hooks]) => {
  it('시차가 2.4 · 2.6 · 2.4 초를 오가도 보이는 숫자는 2 그대로다(떨림 ①)', async () => {
    const view = await mountLive(hooks);
    const shown = [2.4, 2.6, 2.4].map((behindS) => {
      paintAt(behindS);
      return view.result.current.behindSeconds;
    });
    expect(shown).toEqual([2, 2, 2]);
  });

  it('1초 넘게 벗어나면 숫자가 바뀐다', async () => {
    const view = await mountLive(hooks);
    paintAt(2.4);
    paintAt(3.6);
    expect(view.result.current.behindSeconds).toBe(4);
  });

  it('반올림 전 시차가 3초면 보이는 숫자가 2여도 끝이 아니다 — 끝 판정은 원시 시차다', async () => {
    const view = await mountLive(hooks);
    paintAt(2.4);
    expect(view.result.current).toMatchObject({ behindSeconds: 2, atEdge: true });
    paintAt(3);
    expect(view.result.current).toMatchObject({ behindSeconds: 2, atEdge: false });
  });

  it('반올림 전 시차가 3초 미만이면 보이는 숫자가 3이어도 끝이다', async () => {
    const view = await mountLive(hooks);
    paintAt(3.4);
    expect(view.result.current).toMatchObject({ behindSeconds: 3, atEdge: false });
    paintAt(2.6);
    expect(view.result.current).toMatchObject({ behindSeconds: 3, atEdge: true });
  });

  it('소스를 갈아 끼운 뒤 첫 그리기는 0 에 묶이지 않고 실제 시차를 반올림한다', async () => {
    const view = await mountLive(hooks);
    paintAt(5);
    expect(view.result.current.behindSeconds).toBe(5);
    act(() => view.rerender({ source: { ...SOURCE, generation: 1 } }));
    await startPlayer();
    paintAt(0.8);
    expect(view.result.current.behindSeconds).toBe(1);
  });
});

// 사람 시크 · LIVE 뒤 첫 그리기는 옛 숫자를 버리고 실제 시차를 반올림한다(POK-583 c3 #1) — 1초 안쪽으로 옮기면
// steadySeconds 가 옛 숫자를 둬, 10초를 보이다 9초로 옮겨도 10이 남았다. 그 뒤로는 다시 steadySeconds 다
describe('시크 · LIVE 뒤 첫 그리기', () => {
  it('세션 모드: 10초를 보이다 9초로 옮기면 첫 그리기에 9다', async () => {
    let forward: (atMs: number) => void = () => {};
    const view = await mountLive({ onSeekRequest: (atMs) => forward(atMs) });
    // 세션은 같은 재생기 안의 시크를 그 재생기의 seekToMs 로 돌려준다
    forward = (atMs) => view.result.current.seekToMs(atMs);
    paintAt(10);
    act(() => view.result.current.seekBy(-1));
    expect(videoEl!.currentTime).toBe(LIVE_EDGE_S - 9);
    paintAt(9);
    expect(view.result.current.behindSeconds).toBe(9);
    paintAt(9.6);
    expect(view.result.current.behindSeconds).toBe(9);
  });

  it('세션 밖: 10초를 보이다 9초로 옮기면 첫 그리기에 9다', async () => {
    const view = await mountLive({});
    paintAt(10);
    act(() => view.result.current.seekBy(-1));
    expect(videoEl!.currentTime).toBe(LIVE_EDGE_S - 9);
    paintAt(9);
    expect(view.result.current.behindSeconds).toBe(9);
    paintAt(9.6);
    expect(view.result.current.behindSeconds).toBe(9);
  });

  it('세션 밖: 옮긴 뒤 라이브 끝이 자라면 첫 그리기는 옮길 때 정한 숫자가 아니라 그때의 실제 시차다', async () => {
    const view = await mountLive({});
    paintAt(10);
    act(() => view.result.current.seekBy(-1));
    expect(view.result.current.behindSeconds).toBe(9);
    act(() => {
      lastHls().liveSyncPosition = LIVE_EDGE_S + 0.9;
      videoEl!.dispatchEvent(new Event('timeupdate'));
    });
    expect(view.result.current.behindSeconds).toBe(10);
  });

  it('세션 모드: 1을 보이다 LIVE(seekToLiveEdge)로 가면 첫 그리기에 0이다', async () => {
    const view = await mountLive({ onSeekRequest: () => {} });
    paintAt(1.4);
    act(() => view.result.current.seekToLiveEdge());
    paintAt(0);
    expect(view.result.current.behindSeconds).toBe(0);
  });

  it('세션 밖: 1을 보이다 LIVE(returnToLive)로 가면 첫 그리기에 0이다', async () => {
    const view = await mountLive({});
    paintAt(1.4);
    act(() => view.result.current.returnToLive());
    paintAt(0);
    expect(view.result.current.behindSeconds).toBe(0);
  });
});

// 세션 밖 시크는 그리기를 기다리지 않고 시차 · 끝 판정을 바로 바꾼다(seekToBehind — POK-583 c3 #3)
describe('세션 밖 seekBy 직후', () => {
  it('끝에서 10초 뒤로 가면 그리기 전에도 11초 · 끝 아님이다', async () => {
    const view = await mountLive({});
    paintAt(1.4);
    expect(view.result.current.atEdge).toBe(true);
    act(() => view.result.current.seekBy(10));
    expect(view.result.current).toMatchObject({ behindSeconds: 11, atEdge: false });
  });
});
