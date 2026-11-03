import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TIMING_TAG } from './fragmentTiming';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 재생기 배선 — 판 2 조각을 영상 축 조각으로 바꿔 시각 환산에 넘기는가(설계 r7 3.5 · 4.2절 · 10절 c4). hls.js 는
// 사건 처리기 · 목록만 흉내 내고, 조각마다의 영상 시작(v_k)은 videoStarts 기록을 흉내 내 시험이 순번별로 준다
// (바이트 읽기 몫은 videoStarts.hlsjs.test.ts).

interface Frag {
  sn: number;
  cc: number;
  start: number;
  duration: number;
  programDateTime: number;
  gap: boolean;
  tagList: string[][];
}
interface Details {
  fragments: Frag[];
  fragmentStart: number;
  targetduration: number;
  live: boolean;
}

const fake = vi.hoisted(() => ({
  instances: [] as FakeHls[],
  /** trackVideoStarts 를 단 재생기 */
  tracked: [] as unknown[],
  /** 순번 → v_k(초) */
  videoStarts: new Map<number, number>(),
  /** 라이브 구성 시차 스위치(LIVE_PTS_CORRECTION) — 시험이 두 값을 주입한다 */
  liveCorrection: false,
}));

// 상수 하나만 갈아 끼운다. 환산(datedFragments)은 진짜 모듈의 기본값(OFF) 그대로다 — 재생기 배선만 본다
vi.mock('./fragmentTiming', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./fragmentTiming')>()),
  get LIVE_PTS_CORRECTION() {
    return fake.liveCorrection;
  },
}));

// 이 시험이 쏘는 사건은 LEVEL_UPDATED 하나다. 다른 처리기는 이름 없이 달려 돌지 않는다
vi.mock('hls.js', () => ({ default: FakeHls }));

vi.mock('./videoStarts', () => ({
  trackVideoStarts: (hls: unknown) => {
    fake.tracked.push(hls);
    return {
      startOf: (frag: { sn: number | string }) => {
        const videoStartS = typeof frag.sn === 'number' ? fake.videoStarts.get(frag.sn) : undefined;
        return videoStartS === undefined ? null : { videoStartS, firstCtoS: 0 };
      },
      dispose: () => {},
    };
  },
}));

class FakeHls {
  static isSupported = () => true;
  static Events = { LEVEL_UPDATED: 'hlsLevelUpdated' };
  readonly handlers = new Map<string, ((event: string, data: unknown) => void)[]>();
  latestLevelDetails: Details | null = null;
  liveSyncPosition: number | null = 12;
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

const PDT0 = Date.parse('2026-11-03T05:00:00.000Z');

/**
 * 판 2 조각 셋 — hls.js 는 해체한 조각의 start · duration 을 소리 기준 · 모든 트랙 끝(4.3초)으로 덮었고, 원래
 * EXTINF 는 4초다. 새 장의 PDT 가 앞 조각 끝보다 500ms 이르다(겹침 — B8)
 */
function versionTwoDetails(): Details {
  const fragments = [0, 1, 2].map((sn) => ({
    sn,
    cc: 0,
    start: sn * 4.3,
    duration: 4.3,
    programDateTime: PDT0 + sn * 4_000 - (sn > 0 ? 500 : 0),
    gap: false,
    tagList:
      sn === 0
        ? [
            [TIMING_TAG, '2'],
            ['INF', '4.000'],
          ]
        : [['INF', '4.000']],
  }));
  return { fragments, fragmentStart: 0, targetduration: 4, live: true };
}

const video = { el: null as HTMLVideoElement | null };

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

/** 재생기를 세우고 판 2 목록을 한 번 갱신한다 */
async function mount(kind: PlaybackSource['kind'], hooks: PlaybackHooks = {}) {
  const el = document.createElement('video');
  video.el = el;
  const ref = { current: el };
  const view = renderHook(() =>
    useHlsPlayback(ref, source(kind), {}, undefined, undefined, false, undefined, hooks),
  );
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  act(() => lastHls().trigger('hlsLevelUpdated', { details: versionTwoDetails() }));
  return view;
}

beforeEach(() => {
  vi.useFakeTimers({ now: PDT0 + 20_000 });
  fake.instances.length = 0;
  fake.tracked.length = 0;
  fake.videoStarts.clear();
  fake.liveCorrection = false;
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockReturnValue(false);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockReturnValue(4);
  vi.spyOn(HTMLMediaElement.prototype, 'seekable', 'get').mockReturnValue({
    length: 1,
    start: () => 0,
    end: () => 13,
  } as unknown as TimeRanges);
  vi.spyOn(HTMLMediaElement.prototype, 'played', 'get').mockReturnValue({
    length: 0,
    start: () => 0,
    end: () => 0,
  } as unknown as TimeRanges);
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('영상 시작 기록을 다는 재생기', () => {
  it.each(['rewind', 'vod'] as const)('%s 재생기에 단다', async (kind) => {
    await mount(kind);
    expect(fake.tracked).toEqual([lastHls()]);
  });

  it('라이브 구성 시차 스위치가 꺼져 있으면(기본) 라이브 재생기에는 달지 않는다', async () => {
    await mount('live');
    expect(fake.tracked).toEqual([]);
  });

  it('라이브 구성 시차 스위치가 켜져 있으면 라이브 재생기에도 단다', async () => {
    fake.liveCorrection = true;
    await mount('live');
    expect(fake.tracked).toEqual([lastHls()]);
  });
});

describe('판 2 목록의 시각 환산 — 영상 축 조각', () => {
  it('목록 갱신(LEVEL_UPDATED)의 끝 · 창 하한 여유는 원래 EXTINF 로 잰다 — 겹침 목록', async () => {
    const onListLoaded = vi.fn();
    await mount('rewind', { onListLoaded });
    expect(onListLoaded).toHaveBeenLastCalledWith({
      url: 'https://m/rewind/k/index.m3u8',
      firstMs: PDT0,
      lastEndMs: PDT0 + 11_500,
      targetDurationS: 4,
      headSpanMs: 12_000,
    });
  });

  it('재생기 자리(세션 밖)는 조각의 v_k 에서 센다', async () => {
    fake.videoStarts.set(0, 0.2).set(1, 4.2).set(2, 8.2);
    const view = await mount('rewind');
    video.el!.currentTime = 5.2;
    act(() => video.el!.dispatchEvent(new Event('timeupdate')));
    expect(view.result.current.livePosition?.atMs).toBeCloseTo(PDT0 + 4_500, 3);
  });

  it('실제 시각으로 옮기기(seekToMs)는 나중 조각의 v_k 자리다', async () => {
    fake.videoStarts.set(0, 0.2).set(1, 4.2).set(2, 8.2);
    const view = await mount('rewind');
    act(() => view.result.current.seekToMs?.(PDT0 + 4_000));
    expect(video.el!.currentTime).toBeCloseTo(4.7, 9);
  });
});
