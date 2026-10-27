import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 재생기 자리의 재생 여부 · 연속 구간(POK-200 c13 · 설계 r9 2절). 위치를 잴 때 같은 순간의 `!video.paused` 와
// playedRunMs(video.played, currentTime) 를 싣는다. hls.js 는 이 시험에 드는 만큼만 흉내 낸다(사건 처리기 · 목록 ·
// 라이브 끝) — 기동 · 오류 · 복구 흉내는 useHlsPlayback.test.ts 몫이다.

interface Details {
  fragments: { start: number; duration: number; programDateTime: number }[];
  fragmentStart: number;
  targetduration: number;
  live: boolean;
}

const fake = vi.hoisted(() => ({ instances: [] as FakeHls[] }));

// 이 시험이 쏘는 사건은 LEVEL_UPDATED 하나다. 다른 처리기는 이름 없이 달려 돌지 않는다
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
    for (const h of this.handlers.get(event) ?? []) h(event, data);
  }
  attachMedia() {}
  loadSource() {}
  startLoad() {}
  destroy() {}
}

const PDT0 = Date.parse('2026-10-25T05:00:00.000Z');
/** 영상 0초부터 4초 조각 n 개(PDT0 부터) */
function details(n: number): Details {
  const fragments = Array.from({ length: n }, (_, i) => ({
    start: i * 4,
    duration: 4,
    programDateTime: PDT0 + i * 4_000,
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live: true };
}

const video = { el: null as HTMLVideoElement | null, paused: false, played: [] as number[][] };

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

const SOURCE: PlaybackSource = {
  kind: 'live',
  url: 'https://m/live/k/index.m3u8',
  startAtMs: null,
  generation: 0,
};

/** 라이브 재생기를 세우고 목록 10조각 · 라이브 끝 38초 · 재생 위치 36초에 둔다 */
async function mountLive(hooks: PlaybackHooks) {
  const el = document.createElement('video');
  video.el = el;
  const ref = { current: el };
  const view = renderHook(() =>
    useHlsPlayback(ref, SOURCE, {}, undefined, undefined, false, undefined, hooks),
  );
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  lastHls().liveSyncPosition = 38;
  act(() => lastHls().trigger('hlsLevelUpdated', { details: details(10) }));
  el.currentTime = 36;
  return view;
}

function timeupdate() {
  act(() => video.el!.dispatchEvent(new Event('timeupdate')));
}

beforeEach(() => {
  vi.useFakeTimers({ now: PDT0 + 40_000 });
  fake.instances.length = 0;
  video.paused = false;
  video.played = [];
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockImplementation(() => video.paused);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockReturnValue(4);
  vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockImplementation(
    () => ({ length: 1, start: () => 0, end: () => 40 }) as unknown as TimeRanges,
  );
  vi.spyOn(HTMLMediaElement.prototype, 'played', 'get').mockImplementation(
    () =>
      ({
        length: video.played.length,
        start: (i: number) => video.played[i]![0],
        end: (i: number) => video.played[i]![1],
      }) as unknown as TimeRanges,
  );
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe.each([
  ['세션 모드(paintSession)', { onSeekRequest: () => {} }],
  ['세션 밖(라이브 창 축)', {}],
] as const)('재생기 자리의 재생 여부 · 연속 구간 — %s', (_name, hooks) => {
  it('위치를 잴 때 같은 순간의 played 연속 길이와 재생 여부를 싣는다', async () => {
    const view = await mountLive(hooks);
    video.played = [
      [0, 10],
      [20, 36],
    ];
    timeupdate();
    expect(view.result.current.livePosition).toMatchObject({
      atMs: PDT0 + 36_000,
      playing: true,
      runMs: 16_000,
    });
  });

  it('사람이 같은 초 안에서 멈춰도 playing false 인 새 값을 한 번 낸다', async () => {
    const view = await mountLive(hooks);
    video.played = [[30, 36]];
    timeupdate();
    const playingPosition = view.result.current.livePosition;
    video.paused = true;
    video.el!.currentTime = 36.2;
    timeupdate();
    expect(view.result.current.livePosition).not.toBe(playingPosition);
    expect(view.result.current.livePosition).toMatchObject({ playing: false });
  });
});
