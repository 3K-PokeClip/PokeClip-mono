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
  /** 영상 시작 기록을 놓은(dispose) 재생기 */
  disposed: [] as unknown[],
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

// 이 시험이 쏘는 사건은 LEVEL_UPDATED · ERROR 둘이다. 다른 처리기는 이름 없이 달려 돌지 않는다(라이브 기동
// 문지기의 once(LEVEL_UPDATED)는 돈다)
vi.mock('hls.js', () => ({ default: FakeHls }));

vi.mock('./videoStarts', () => ({
  trackVideoStarts: (hls: unknown) => {
    fake.tracked.push(hls);
    return {
      startOf: (frag: { sn: number | string }) => {
        const videoStartS = typeof frag.sn === 'number' ? fake.videoStarts.get(frag.sn) : undefined;
        return videoStartS === undefined ? null : { videoStartS, firstCtoS: 0 };
      },
      dispose: () => {
        fake.disposed.push(hls);
      },
    };
  },
}));

class FakeHls {
  static isSupported = () => true;
  static Events = { LEVEL_UPDATED: 'hlsLevelUpdated', ERROR: 'hlsError' };
  static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  // hls.js 1.7.3 의 값 그대로(errors.ts)
  static ErrorDetails = {
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    LEVEL_LOAD_ERROR: 'levelLoadError',
    AUDIO_TRACK_LOAD_ERROR: 'audioTrackLoadError',
  };
  readonly handlers = new Map<string, ((event: string, data: unknown) => void)[]>();
  latestLevelDetails: Details | null = null;
  liveSyncPosition: number | null = 12;
  /** 기동 끝 — 시험이 true 로 두면 되감기 창 하한 지키기가 currentTime 을 본다 */
  hasEnoughToStart?: boolean;
  constructor() {
    fake.instances.push(this);
  }
  on(event: string, handler: (event: string, data: unknown) => void) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  once(event: string, handler: (event: string, data: unknown) => void) {
    const wrapped = (e: string, data: unknown) => {
      this.handlers.set(
        event,
        (this.handlers.get(event) ?? []).filter((h) => h !== wrapped),
      );
      handler(e, data);
    };
    this.on(event, wrapped);
  }
  trigger(event: string, data: { details?: Details | string }) {
    // 오류 사건의 details 는 오류 이름 글자다 — 목록은 LEVEL_UPDATED 만 바꾼다
    if (event === FakeHls.Events.LEVEL_UPDATED && typeof data.details === 'object') {
      this.latestLevelDetails = data.details;
    }
    for (const h of this.handlers.get(event) ?? []) {
      h(event, data);
    }
  }
  attachMedia() {}
  loadSource() {}
  startLoad() {}
  stopLoad() {}
  destroy() {}
}

const PDT0 = Date.parse('2026-11-03T05:00:00.000Z');

/**
 * 판 2 조각(기본은 순번 0–2 셋) — hls.js 는 해체한 조각의 start · duration 을 소리 기준 · 모든 트랙 끝(4.3초)으로
 * 덮었고, 원래 EXTINF 는 4초다. 새 장의 PDT 가 앞 조각 끝보다 500ms 이르다(겹침 — B8). 판 태그는 목록 첫 조각에
 * 싣는다
 */
function versionTwoDetails(sns: readonly number[] = [0, 1, 2]): Details {
  const fragments = sns.map((sn, i) => ({
    sn,
    cc: 0,
    start: sn * 4.3,
    duration: 4.3,
    programDateTime: PDT0 + sn * 4_000 - (sn > 0 ? 500 : 0),
    gap: false,
    tagList:
      i === 0
        ? [
            [TIMING_TAG, '2'],
            ['INF', '4.000'],
          ]
        : [['INF', '4.000']],
  }));
  return { fragments, fragmentStart: fragments[0]?.start ?? 0, targetduration: 4, live: true };
}

/** 순번 0 부터 차례로 v_k(초)를 준다 */
function setVideoStarts(...starts: number[]) {
  starts.forEach((videoStartS, sn) => fake.videoStarts.set(sn, videoStartS));
}

const video = { el: null as HTMLVideoElement | null };

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

/** 재생기 하나의 시작 — 기본은 시작 시각 없음 */
type Start = Pick<PlaybackSource, 'startAtMs' | 'strictStart'>;

function source(kind: PlaybackSource['kind'], start: Start): PlaybackSource {
  return {
    kind,
    url: `https://m/${kind}/k/index.m3u8`,
    generation: 0,
    ...start,
  };
}

/**
 * 재생기를 세우고 판 2 목록을 한 번 갱신한다. mediaKey 가 있으면 403 에 출입증을 버린다. start 가 있으면 그 시작
 * 시각으로 선다
 */
async function mount(
  kind: PlaybackSource['kind'],
  hooks: PlaybackHooks = {},
  mediaKey?: string,
  start: Start = { startAtMs: null, strictStart: false },
) {
  const el = document.createElement('video');
  video.el = el;
  const ref = { current: el };
  const view = renderHook(() =>
    useHlsPlayback(ref, source(kind, start), {}, undefined, mediaKey, false, undefined, hooks),
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
  fake.disposed.length = 0;
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

describe('영상 시작 기록은 재생기를 내리는 모든 자리에서 놓는다', () => {
  /** 다시 해 보지 않는 치명 오류 하나. 기본은 조각 받기 오류다 */
  function fatal(type: string, code?: number, details = 'fragLoadError') {
    return { fatal: true, type, details, response: { code } };
  }

  it('정리(재생기 해체)에서 놓는다', async () => {
    const view = await mount('rewind');
    view.unmount();
    expect(fake.disposed).toEqual([fake.tracked[0]]);
  });

  it('403 으로 내릴 때 놓고, 뒤 정리에서 다시 놓지 않는다', async () => {
    const view = await mount('rewind', {}, 'k');
    act(() => lastHls().trigger('hlsError', fatal('networkError', 403)));
    expect(fake.disposed).toEqual([lastHls()]);
    view.unmount();
    expect(fake.disposed).toHaveLength(1);
  });

  it('복구 불가로 내릴 때 놓는다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    await mount('rewind');
    // 첫 네트워크 오류는 한 번 다시 받고, 두 번째에 내린다
    act(() => lastHls().trigger('hlsError', fatal('networkError')));
    expect(fake.disposed).toEqual([]);
    act(() => lastHls().trigger('hlsError', fatal('networkError')));
    expect(fake.disposed).toEqual([lastHls()]);
  });

  it('시작이 잘려 내릴 때 놓고, 뒤 정리에서 다시 놓지 않는다', async () => {
    // 기록은 라이브 구성 시차 스위치를 켰을 때만 라이브 재생기에 달린다. 시작 시각 PDT0 은 라이브 시작 하한(첫 조각 +
    // 조각 셋)보다 앞이라 엄격 시작이면 잘린다
    fake.liveCorrection = true;
    const onStartTruncated = vi.fn();
    const view = await mount('live', { onStartTruncated }, undefined, {
      startAtMs: PDT0,
      strictStart: true,
    });
    // 잘림 알림은 다음 마이크로태스크에 온다(armLiveStart)
    await act(async () => {
      await Promise.resolve();
    });
    expect(onStartTruncated).toHaveBeenCalledOnce();
    expect(fake.disposed).toEqual([lastHls()]);
    view.unmount();
    expect(fake.disposed).toHaveLength(1);
  });

  it('라이브가 닫혀(목록 404) 내릴 때 놓고, 뒤 정리에서 다시 놓지 않는다', async () => {
    fake.liveCorrection = true;
    const onLiveClosed = vi.fn();
    const view = await mount('live', { onLiveClosed });
    act(() => lastHls().trigger('hlsError', fatal('networkError', 404, 'levelLoadError')));
    expect(onLiveClosed).toHaveBeenCalledOnce();
    expect(fake.disposed).toEqual([lastHls()]);
    view.unmount();
    expect(fake.disposed).toHaveLength(1);
  });
});

describe('판 2 목록의 끝 · 창 하한 판정도 영상 축 조각으로 잰다', () => {
  it('목록 끝 정체(D9)는 영상 축 조각의 끝 1초 안에서 끝 도달이다', async () => {
    // v_k 는 hls.js 가 덮은 start 보다 이르다. 영상 축이면 끝 PDT0 + 11.5초 · 재생 위치 PDT0 + 10.9초(끝 1초 안),
    // hls.js 조각이면 끝 PDT0 + 11.8초 · 재생 위치 PDT0 + 10.4초(끝 1초 밖)다
    setVideoStarts(0.1, 4.1, 8.1);
    const onEndReached = vi.fn();
    await mount('rewind', { onEndReached });
    video.el!.currentTime = 11.5;
    act(() => video.el!.dispatchEvent(new Event('waiting')));
    // 목록이 3 × TD(12초) 넘게 자라지 않는다 — 1초 틱마다 판정한다
    act(() => vi.advanceTimersByTime(13_000));
    expect(onEndReached).toHaveBeenCalledOnce();
  });

  it('끝에서 물러난 시크는 영상 축 조각으로 잰 자리가 끝 앞이면 끝 도달을 다시 연다', async () => {
    // v_k 는 hls.js 가 덮은 start 보다 늦다. 영상 축이면 12.8초는 PDT0 + 11.3초(끝 PDT0 + 11.5초 앞), hls.js
    // 조각이면 PDT0 + 11.7초(끝 뒤)다
    setVideoStarts(1, 5, 9);
    const onEndReached = vi.fn();
    await mount('rewind', { onEndReached });
    act(() => video.el!.dispatchEvent(new Event('ended')));
    video.el!.currentTime = 12.8;
    act(() => video.el!.dispatchEvent(new Event('seeking')));
    act(() => video.el!.dispatchEvent(new Event('ended')));
    expect(onEndReached).toHaveBeenCalledTimes(2);
  });

  it('멈춘 채 창 하한으로 옮긴 자리의 시각을 영상 축 조각으로 세어 재개 판정에 쓴다', async () => {
    vi.spyOn(console, 'info').mockImplementation(() => {});
    setVideoStarts(0.1, 4.1, 8.1, 12.1, 16.1);
    const onSeekRequest = vi.fn();
    await mount('rewind', { onSeekRequest });
    // 기동이 끝난 재생기가 1초 자리(PDT0 + 0.9초 — 창 하한 PDT0 + 12초 앞)에서 멈춘다
    lastHls().hasEnoughToStart = true;
    video.el!.currentTime = 1;
    vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockReturnValue(true);
    act(() => video.el!.dispatchEvent(new Event('pause')));
    // 목록이 자라 1초 자리가 옮김 문턱(첫 조각 + 1 TD) 안이다 — hls.js 조각으로 센 창 하한 12.9초로 옮긴다
    act(() =>
      lastHls().trigger('hlsLevelUpdated', { details: versionTwoDetails([0, 1, 2, 3, 4]) }),
    );
    expect(video.el!.currentTime).toBeCloseTo(12.9, 9);
    // 옮긴 자리는 영상 축으로 PDT0 + 12.3초라 창 하한 뒤다 — 다시 틀어도 세션에 넘기지 않는다. hls.js 조각으로
    // 재면 PDT0 + 11.5초(하한 앞)라 옛 자리처럼 넘긴다
    act(() => video.el!.dispatchEvent(new Event('play')));
    expect(onSeekRequest).not.toHaveBeenCalled();
  });
});

describe('세션 모드(onSeekRequest)의 시각 축도 영상 축 조각으로 잰다', () => {
  it('실제 시각으로 옮기기(seekToDate)가 세션에 넘기는 창 하한 · 끝은 원래 EXTINF 다', async () => {
    const onSeekRequest = vi.fn();
    const view = await mount('rewind', { onSeekRequest });
    act(() => {
      view.result.current.seekToDate?.(PDT0 + 5_000);
    });
    // hls.js 가 덮은 길이(4.3초)면 하한 PDT0 + 12.9초 · 끝 PDT0 + 11.8초다
    expect(onSeekRequest).toHaveBeenLastCalledWith(PDT0 + 5_000, {
      lowerMs: PDT0 + 12_000,
      endMs: PDT0 + 11_500,
    });
  });
});
