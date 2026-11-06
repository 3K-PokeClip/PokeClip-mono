import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useVodPlayback, type VodSource } from './useVodPlayback';

// 다시보기 재생기(POK-546 설계 2-3) — 회차 VOD 목록 하나를 PDT 축으로 튼다. hls.js 는 사건 처리기 · 목록만 흉내 내고
// (ENDLIST · 조각 여럿 · ms PDT), 영상 노드의 재생 위치 · readyState 는 시험이 정한 뒤 timeupdate · seeking 을 쏜다.
// 재생기는 첫 LEVEL_UPDATED 를 시험이 쏠 때까지 목록이 없다(첫 목록 지연).

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

const fake = vi.hoisted(() => ({ instances: [] as FakeHls[] }));

vi.mock('hls.js', () => ({ default: FakeHls }));

// 판 태그 없는 목록이라 영상 시작 기록은 환산에 쓰이지 않는다 — 처리기만 달지 않게 비운다
vi.mock('./videoStarts', () => ({
  trackVideoStarts: () => ({ startOf: () => null, dispose: () => {} }),
}));

class FakeHls {
  static isSupported = () => true;
  static Events = { LEVEL_UPDATED: 'hlsLevelUpdated', ERROR: 'hlsError' };
  static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  // hls.js 1.7.3 의 값 그대로(errors.ts)
  static ErrorDetails = {
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    MANIFEST_LOAD_TIMEOUT: 'manifestLoadTimeOut',
    MANIFEST_PARSING_ERROR: 'manifestParsingError',
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
  trigger(event: string, data: unknown) {
    for (const h of this.handlers.get(event) ?? []) {
      h(event, data);
    }
  }
  /** 목록 갱신 — 처리기보다 먼저 latestLevelDetails 를 채운다(hls.js 의 순서) */
  list(details: Details) {
    this.latestLevelDetails = details;
    this.trigger(FakeHls.Events.LEVEL_UPDATED, { details });
  }
  attachMedia() {}
  loadSource() {}
  startLoad() {}
  stopLoad() {}
  recoverMediaError() {}
  destroy() {}
}

const PDT0 = Date.parse('2026-11-04T10:00:00.000Z');
const MIN = 60_000;
const VOD_URL = 'https://media.pokeclip.com/vod/key-1/r-A/index.m3u8';

/** ENDLIST 목록 — 기본은 4초 조각 1 800개(2시간). 조각 시작 · PDT 는 앞 조각 끝에 붙는다 */
function vodDetails(count = 1_800, durationS = 4): Details {
  const fragments = Array.from({ length: count }, (_, sn) => ({
    sn,
    cc: 0,
    start: sn * durationS,
    duration: durationS,
    programDateTime: PDT0 + sn * durationS * 1000,
    gap: false,
    tagList: [],
  }));
  return { fragments, fragmentStart: 0, targetduration: Math.ceil(durationS), live: false };
}

function vodSource(patch: Partial<VodSource> = {}): VodSource {
  return {
    url: VOD_URL,
    firstPdtMs: PDT0,
    headPdtMs: PDT0 + 120 * MIN,
    axisZeroMs: PDT0,
    originAxis: true,
    ...patch,
  };
}

/** 시험이 정하는 영상 노드 상태 — readyState 는 프로토타입 게터가 읽는다 */
const media = { readyState: 4 };

function lastHls(): FakeHls {
  const h = fake.instances[fake.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

interface HookProps {
  vod: VodSource;
  startAtSeconds: number | null;
}

/** 재생기를 세우고 hls.js 읽기까지 마친다(목록은 아직 없다) */
async function renderVod(initial: Partial<HookProps> = {}) {
  const video = document.createElement('video');
  const ref = { current: video };
  const onSeek = vi.fn();
  const onStartApplied = vi.fn();
  const view = renderHook(
    ({ vod, startAtSeconds }: HookProps) =>
      useVodPlayback(
        ref,
        vod,
        {},
        onSeek,
        undefined,
        false,
        undefined,
        startAtSeconds,
        onStartApplied,
      ),
    { initialProps: { vod: vodSource(), startAtSeconds: null, ...initial } },
  );
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  return { view, video, onSeek, onStartApplied };
}

/** 영상 노드가 그 자리에 있다(readyState 도 함께 정한다) — 사건 하나를 쏜다 */
function sit(video: HTMLVideoElement, seconds: number, readyState = 4, event = 'timeupdate') {
  media.readyState = readyState;
  video.currentTime = seconds;
  act(() => {
    video.dispatchEvent(new Event(event));
  });
}

/** 다시 해 보지 않는 치명 오류 */
function fatal(details: string) {
  return { fatal: true, type: 'networkError', details, response: {} };
}

/** 첫 목록을 받은 뒤의 치명 — 첫 번은 한 번 다시 받고 둘째에 내린다 */
function breakAfterList() {
  act(() => lastHls().trigger('hlsError', fatal('fragLoadError')));
  act(() => lastHls().trigger('hlsError', fatal('fragLoadError')));
}

beforeEach(() => {
  vi.useFakeTimers({ now: PDT0 + 200 * MIN });
  fake.instances.length = 0;
  media.readyState = 4;
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockReturnValue(false);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockImplementation(
    () => media.readyState,
  );
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

describe('국면 — 재생기가 선 뒤 첫 목록 · 첫 화면 위치를 차례로 낸다', () => {
  it('첫 목록 전은 loading, 첫 목록 뒤는 listed, 화면에 붙은 위치를 처음 내면 showing 이다', async () => {
    const { view, video } = await renderVod();
    expect(view.result.current.phase).toBe('loading');
    act(() => lastHls().list(vodDetails()));
    expect(view.result.current.phase).toBe('listed');
    // 재생 위치에 영상이 아직 없다 — 화면에 붙은 위치를 모른다
    sit(video, 3, 1);
    expect(view.result.current.phase).toBe('listed');
    sit(video, 3);
    expect(view.result.current.phase).toBe('showing');
  });

  it('hls.js 가 없고 기본 HLS 로 틀면 unsupported 다 — 조각 목록 · PDT 축이 없다', async () => {
    vi.spyOn(FakeHls, 'isSupported').mockReturnValue(false);
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('maybe');
    const { view } = await renderVod();
    expect(view.result.current.phase).toBe('unsupported');
  });

  it('hls.js 도 기본 HLS 도 없으면 unsupported 다 — 재생 불가가 정해져 loading 에 머물지 않는다', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(FakeHls, 'isSupported').mockReturnValue(false);
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('');
    const { view } = await renderVod();
    expect(view.result.current.phase).toBe('unsupported');
    expect(view.result.current.playbackError).toBeNull();
  });

  it('첫 목록 전에 멎으면(첫 목록 치명) failed 다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { view } = await renderVod();
    act(() => lastHls().trigger('hlsError', fatal('manifestLoadError')));
    expect(view.result.current.phase).toBe('failed');
  });

  it('첫 목록 뒤 · 첫 화면 위치 전에 멎으면 failed 다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { view } = await renderVod();
    act(() => lastHls().list(vodDetails()));
    breakAfterList();
    expect(view.result.current.phase).toBe('failed');
  });

  it('화면 위치를 한 번 낸 뒤 멎으면 showing 그대로다 — 영상 위 알림이 까닭을 말한다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { view, video } = await renderVod();
    act(() => lastHls().list(vodDetails()));
    sit(video, 3);
    breakAfterList();
    expect(view.result.current.playbackError).not.toBeNull();
    expect(view.result.current.phase).toBe('showing');
  });
});

describe('위치 계약 — 위치를 모르면 끝까지 null', () => {
  it('첫 목록 전이면 화면 위치 · 지금 자리 모두 null 이다(재생 위치 · readyState 가 있어도)', async () => {
    const { view, video } = await renderVod();
    sit(video, 30);
    expect(view.result.current.positionSeconds).toBeNull();
    expect(view.result.current.currentPositionSeconds?.()).toBeNull();
  });

  it('첫 목록 뒤라도 재생 위치에 영상이 없으면(readyState 1) 화면 위치 · 지금 자리 모두 null 이다', async () => {
    const { view, video } = await renderVod();
    act(() => lastHls().list(vodDetails()));
    sit(video, 30, 1);
    expect(view.result.current.positionSeconds).toBeNull();
    expect(view.result.current.currentPositionSeconds?.()).toBeNull();
  });

  it('화면 위치는 기준점 축 초로 내린다 — 1초 미만 축에서 경계 ±10ms 를 timeupdate 한 번에 넘는다', async () => {
    // 소수 조각 길이(4.004초) · 기준점 = 첫 PDT − 1 370ms. 8.62초 = 첫 PDT + 8 620ms = 기준점 + 9 990ms
    const vod = vodSource({ axisZeroMs: PDT0 - 1_370 });
    const { view, video } = await renderVod({ vod });
    act(() => lastHls().list(vodDetails(10, 4.004)));
    sit(video, 8.62);
    expect(view.result.current.positionSeconds).toBe(9);
    // 재생기 자리(livePosition)는 실제 초로 묶여 같은 초(첫 PDT + 8초) 안에서는 그대로다 — 그것에 기대면 바뀌지 않는다
    const live = (view.result.current as { livePosition?: unknown }).livePosition;
    // 칸 이름이 바뀌거나 반환에서 빠지면 undefined 다 — 아래 무변경 단언이 빈 단언이 되지 않게 모양까지 본다
    expect(live).toEqual(expect.objectContaining({ atMs: expect.any(Number) }));
    sit(video, 8.64);
    expect((view.result.current as { livePosition?: unknown }).livePosition).toBe(live);
    expect(view.result.current.positionSeconds).toBe(10);
    expect(view.result.current.currentPositionSeconds?.()).toBeCloseTo(10.01, 6);
  });

  it('화면 위치를 한 번 낸 뒤 영상이 다시 비면(readyState 1) 알리는 위치는 직전 값을 둔다', async () => {
    const { view, video } = await renderVod();
    act(() => lastHls().list(vodDetails()));
    sit(video, 30);
    sit(video, 300, 1, 'seeking');
    expect(view.result.current.positionSeconds).toBe(30);
    // 부르는 순간 읽는 지금 자리는 모른다
    expect(view.result.current.currentPositionSeconds?.()).toBeNull();
  });

  it.each(['loadeddata', 'canplay'])(
    '자동 재생이 막혀 timeupdate 가 없어도 첫 프레임(%s)에서 화면 위치를 낸다',
    async (event) => {
      const { view, video } = await renderVod();
      act(() => lastHls().list(vodDetails()));
      sit(video, 0, 4, event);
      expect(view.result.current.positionSeconds).toBe(0);
      expect(view.result.current.phase).toBe('showing');
    },
  );
});

describe('시크바 — 다시보기 전체 길이 축(1시간에서 자르지 않는다)', () => {
  /** 2시간 목록을 받고 10분 자리에서 화면에 붙었다 */
  async function atTenMinutes() {
    const rendered = await renderVod();
    act(() => lastHls().list(vodDetails()));
    sit(rendered.video, 600);
    return rendered;
  }

  it('폭은 목록 첫 조각부터 끝까지 7 200초, 10분 자리의 시차는 6 600초다', async () => {
    const { view } = await atTenMinutes();
    expect(view.result.current.windowSeconds).toBe(7_200);
    expect(view.result.current.behindSeconds).toBe(6_600);
    expect(view.result.current.atEdge).toBe(false);
  });

  it('막대 맨 왼쪽(Home 과 같은 값 0)은 첫 조각으로 간다', async () => {
    const { view, video, onSeek } = await atTenMinutes();
    act(() => view.result.current.seekToFraction(0));
    expect(video.currentTime).toBe(0);
    expect(onSeek).toHaveBeenCalledOnce();
  });

  it('화살표 한 걸음은 양수가 과거 · 음수가 끝 쪽이다(±10초)', async () => {
    const { view, video } = await atTenMinutes();
    act(() => view.result.current.seekBy(10));
    expect(video.currentTime).toBe(590);
    sit(video, 600);
    act(() => view.result.current.seekBy(-10));
    expect(video.currentTime).toBe(610);
  });

  it('끝으로(returnToLive) · 막대 맨 오른쪽은 목록 끝이다', async () => {
    const { view, video } = await atTenMinutes();
    act(() => view.result.current.returnToLive());
    expect(video.currentTime).toBe(7_200);
    sit(video, 600);
    act(() => view.result.current.seekToFraction(1));
    expect(video.currentTime).toBe(7_200);
  });

  it('timeupdate 없이 ← 를 두 번 누르면 9분 40초다 — 명령 순간의 재생 위치로 센다', async () => {
    const { view, video } = await atTenMinutes();
    act(() => view.result.current.seekBy(10));
    act(() => view.result.current.seekBy(10));
    expect(video.currentTime).toBe(580);
  });

  it('1:30:00 을 놓은 직후(readyState 1) 시차는 1 800초다 — 화면에 붙기 전에도 막대는 놓은 자리다', async () => {
    const { view, video } = await atTenMinutes();
    act(() => view.result.current.seekToFraction(0.75));
    expect(video.currentTime).toBe(5_400);
    sit(video, 5_400, 1, 'seeking');
    expect(view.result.current.behindSeconds).toBe(1_800);
  });

  it('첫 목록 전에는 Home · ← · 막대가 재생 위치를 바꾸지 않는다 — 폭은 0 이다', async () => {
    const { view, video, onSeek } = await renderVod();
    video.currentTime = 5;
    act(() => view.result.current.seekToFraction(0));
    act(() => view.result.current.seekBy(10));
    act(() => view.result.current.seekToFraction(0.5));
    expect(video.currentTime).toBe(5);
    expect(onSeek).not.toHaveBeenCalled();
    expect(view.result.current.windowSeconds).toBe(0);
  });

  it('실제 시각으로 옮기기는 목록 끝 뒤를 목록 끝으로 가둔다', async () => {
    const { view, video } = await atTenMinutes();
    let result: string | undefined;
    act(() => {
      result = view.result.current.seekToDate?.(PDT0 + 130 * MIN);
    });
    expect(result).toBe('moved');
    expect(video.currentTime).toBe(7_200);
  });
});

describe('받아 둔 자리(startAtSeconds) — 목록이 서면 그 커밋에 한 번 적용한다', () => {
  it('첫 목록 전에 받아 둔 30분 자리는 첫 목록 뒤 그 자리로 옮기고 적용을 알린다', async () => {
    const { video, onStartApplied } = await renderVod({ startAtSeconds: 30 * 60 });
    expect(onStartApplied).not.toHaveBeenCalled();
    act(() => lastHls().list(vodDetails()));
    expect(video.currentTime).toBe(1_800);
    expect(onStartApplied).toHaveBeenCalledOnce();
  });

  it('첫 목록 뒤에 들어온 자리도 같은 규칙으로 옮긴다', async () => {
    const { view, video, onStartApplied } = await renderVod();
    act(() => lastHls().list(vodDetails()));
    act(() => view.rerender({ vod: vodSource(), startAtSeconds: 45 * 60 }));
    expect(video.currentTime).toBe(2_700);
    expect(onStartApplied).toHaveBeenCalledOnce();
  });

  it.each([
    ['첫 목록이 먼저 오고 자리가 뒤따른다', true],
    ['자리가 먼저 들어오고 첫 목록이 뒤따른다', false],
  ])('한 박자 틈(같은 act) — %s', async (_order, listFirst) => {
    const { view, video, onStartApplied } = await renderVod();
    act(() => {
      if (listFirst) {
        lastHls().list(vodDetails());
      }
      view.rerender({ vod: vodSource(), startAtSeconds: 20 * 60 });
      if (!listFirst) {
        lastHls().list(vodDetails());
      }
    });
    expect(video.currentTime).toBe(1_200);
    expect(onStartApplied).toHaveBeenCalledOnce();
  });

  it('받아 둔 자리가 목록 앞(범위 밖)이면 알림 없이 첫 조각으로 자르고 적용을 알린다', async () => {
    // 기준점이 첫 PDT 보다 1분 이르다 — 10초 자리는 첫 조각 50초 앞이다
    const vod = vodSource({ axisZeroMs: PDT0 - MIN });
    const { video, onStartApplied } = await renderVod({ vod, startAtSeconds: 10 });
    video.currentTime = 33;
    act(() => lastHls().list(vodDetails()));
    expect(video.currentTime).toBe(0);
    expect(onStartApplied).toHaveBeenCalledOnce();
  });

  it('그 시각의 영상 자리를 모르면(unknown) 적용을 알리지 않고 남겨 두었다가 다음 목록에서 적용한다', async () => {
    const { video, onStartApplied } = await renderVod({ startAtSeconds: 30 * 60 });
    video.currentTime = 33;
    act(() => {
      lastHls().list(vodDetails());
      // 목록 상태는 섰지만 재생기의 목록이 비었다 — 시각 축을 읽지 못한다
      lastHls().latestLevelDetails = null;
    });
    expect(video.currentTime).toBe(33);
    expect(onStartApplied).not.toHaveBeenCalled();
    act(() => lastHls().list(vodDetails()));
    expect(video.currentTime).toBe(1_800);
    expect(onStartApplied).toHaveBeenCalledOnce();
  });

  it('적용한 뒤에는 재생 위치가 바뀌어도 다시 옮기지 않는다', async () => {
    const { video, onStartApplied } = await renderVod({ startAtSeconds: 30 * 60 });
    act(() => lastHls().list(vodDetails()));
    sit(video, 1_900);
    expect(video.currentTime).toBe(1_900);
    expect(onStartApplied).toHaveBeenCalledOnce();
  });
});

describe('카드 · 다 본 뒤 — 그 밖의 계약', () => {
  it('자리로 옮기기(seekToPosition)는 옮겼으면 true, 그 시각의 영상 자리를 모르면 false 다', async () => {
    const { view, video } = await renderVod();
    let moved: boolean | void = undefined;
    act(() => {
      moved = view.result.current.seekToPosition?.(60);
    });
    expect(moved).toBe(false);
    act(() => lastHls().list(vodDetails()));
    act(() => {
      moved = view.result.current.seekToPosition?.(60);
    });
    expect(moved).toBe(true);
    expect(video.currentTime).toBe(60);
  });

  it('자리로 옮기기는 목록 앞(범위 밖)이면 옮기지 않고 false 다', async () => {
    const { view, video } = await renderVod({ vod: vodSource({ axisZeroMs: PDT0 - MIN }) });
    act(() => lastHls().list(vodDetails()));
    video.currentTime = 33;
    let moved: boolean | void = undefined;
    act(() => {
      moved = view.result.current.seekToPosition?.(10);
    });
    expect(moved).toBe(false);
    expect(video.currentTime).toBe(33);
  });

  it('다시보기는 오른쪽 끝이 녹화 끝이다(replay)', async () => {
    const { view } = await renderVod();
    expect(view.result.current.replay).toBe(true);
  });

  it('다 본 뒤 재생하면(처음부터) 사람이 옮긴 것으로 알린다 — 멈춘 뒤 다시 틀기는 알리지 않는다', async () => {
    const { view, video, onSeek } = await renderVod();
    vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockReturnValue(true);
    act(() => view.result.current.togglePlay());
    expect(onSeek).not.toHaveBeenCalled();
    vi.spyOn(HTMLMediaElement.prototype, 'ended', 'get').mockReturnValue(true);
    act(() => view.result.current.togglePlay());
    expect(onSeek).toHaveBeenCalledOnce();
    expect(video.play).toHaveBeenCalledTimes(2);
  });

  it('소스가 바뀌면 받아 둔 목록 · 위치를 버린다 — 새 목록 전 폭 0 · 위치 null', async () => {
    const { view, video } = await renderVod();
    act(() => lastHls().list(vodDetails()));
    sit(video, 600);
    act(() => view.rerender({ vod: vodSource({ url: `${VOD_URL}?b` }), startAtSeconds: null }));
    await act(async () => {
      await vi.dynamicImportSettled();
    });
    expect(view.result.current.windowSeconds).toBe(0);
    expect(view.result.current.positionSeconds).toBeNull();
    expect(view.result.current.phase).toBe('loading');
  });

  it('A → B(목록 전) → A 로 돌아와도 버린 A 의 목록 · 위치를 되살리지 않는다 — 새 A 재생기의 목록부터 다시 선다', async () => {
    const { view, video } = await renderVod();
    act(() => lastHls().list(vodDetails()));
    sit(video, 600);
    for (const url of [`${VOD_URL}?b`, VOD_URL]) {
      act(() => view.rerender({ vod: vodSource({ url }), startAtSeconds: null }));
      await act(async () => {
        await vi.dynamicImportSettled();
      });
    }
    expect(view.result.current.windowSeconds).toBe(0);
    expect(view.result.current.behindSeconds).toBe(0);
    expect(view.result.current.positionSeconds).toBeNull();
    expect(view.result.current.phase).toBe('loading');

    // 새 A 재생기의 목록이 오면 폭이 서고, 화면 위치는 새 사건이 낼 때까지 모른다
    act(() => lastHls().list(vodDetails()));
    expect(view.result.current.windowSeconds).toBe(7_200);
    expect(view.result.current.positionSeconds).toBeNull();
    expect(view.result.current.phase).toBe('listed');
  });
});
