import Hls, {
  ErrorDetails,
  ErrorTypes,
  Events,
  Fragment,
  LoadStats,
  PlaylistLevelType,
  type ErrorData,
  type FragLoadedData,
  type HlsConfig,
  type LoaderCallbacks,
  type LoaderContext,
} from 'hls.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HLS_LIVE_CONFIG, HLS_REWIND_CONFIG } from './hlsConfig';
import {
  consumeDvrSegmentGone,
  isFirstListFailure,
  isMediaSegmentNotFound,
  isPlaylistLoadError,
} from './hlsErrors';

// 진짜 hls.js 1.7.3(dist)의 오류 처리가 받기 오류 404 를 언제 치명으로 올리는지와, 앱이 그 오류를 목록 오류로 가르는지를
// 함께 본다(cx H1). error-helper retryForHttpStatus 는 4xx 를 다시 받지 않지만 조각 오류는 stream-controller
// onFragmentOrKeyLoadError 가 따로 다시 받는다: 갈아탈 변이가 없는 라이브의 마지막 조각은 fragLoadPolicy 한도(기본 6회)까지
// 비치명으로 다시 받고 그 뒤 치명으로 올린다(앞 조각은 틈으로 넘긴다). 변이가 아예 없으면 처음부터 치명이다(error-controller
// onErrorOut). 오류 처리기는 생성 때 등록돼(hls.ts 354-356) 앱 처리기보다 먼저 돈다

function quiet() {}

const SILENT = { trace: quiet, debug: quiet, log: quiet, warn: quiet, info: quiet, error: quiet };

const instances: Hls[] = [];
afterEach(() => {
  for (const hls of instances.splice(0)) hls.destroy();
});

/** 변이가 없는(levels 빈 배열) 재생기에 받기 오류(치명 아님) 하나를 쏘고 앱 처리기가 본 것을 돌려준다 */
function raise(details: ErrorDetails, code: number): ErrorData {
  const hls = new Hls({ ...HLS_LIVE_CONFIG, debug: SILENT });
  instances.push(hls);
  const internals = hls as unknown as { levelController: { _levels: unknown[] } };
  internals.levelController._levels = [];
  let seen: ErrorData | null = null;
  hls.on(Events.ERROR, (_event, data) => {
    seen = { ...data };
  });
  hls.trigger(Events.ERROR, {
    type: ErrorTypes.NETWORK_ERROR,
    details,
    fatal: false,
    response: { code, text: '' },
    context: { level: 0, type: 'level', url: 'https://m/live/k/index.m3u8' },
    networkDetails: null,
    stats: undefined,
  } as unknown as ErrorData);
  if (seen === null) throw new Error('앱 처리기가 오류를 받지 못했다');
  return seen;
}

/**
 * 변이 하나 · 라이브 목록 · 지금 받는 조각이 목록 마지막인 재생기에 조각 404(치명 아님)를 times 번 쏘고 앱 처리기가 본 것을
 * 차례로 돌려준다. 재생기 안 상태는 cx 확인 패스의 실측 꼴 그대로다
 */
function raiseOnLastLiveFragment(times: number): ErrorData[] {
  const hls = new Hls({ ...HLS_LIVE_CONFIG, debug: SILENT });
  instances.push(hls);
  const frag = {
    sn: 1,
    type: 'main',
    level: 0,
    start: 4,
    duration: 4,
    gap: false,
    relurl: 's1.m4s',
    stats: {},
    elementaryStreams: {},
  };
  const details = {
    live: true,
    endSN: 1,
    fragments: [frag],
    fragmentStart: 4,
    fragmentEnd: 8,
    targetduration: 4,
  };
  const level = {
    fragmentError: 0,
    loadError: 0,
    loadErrorTime: 0,
    pathwayId: '.',
    bitrate: 500000,
    maxBitrate: 500000,
    attrs: {},
    audioCodec: 'aac',
    videoCodec: 'avc1',
    hasAudioGroup: () => false,
    hasSubtitleGroup: () => false,
    details,
  };
  const internals = hls as unknown as {
    levelController: Record<string, unknown>;
    streamController: Record<string, unknown>;
  };
  Object.assign(internals.levelController, {
    _levels: [level],
    currentLevelIndex: 0,
    currentLevel: level,
  });
  Object.assign(internals.streamController, {
    _hasEnoughToStart: true,
    levels: [level],
    levelLastLoaded: level,
    fragCurrent: frag,
    tickImmediate: quiet,
  });
  const seen: ErrorData[] = [];
  hls.on(Events.ERROR, (_event, data) => {
    seen.push({ ...data });
  });
  for (let i = 0; i < times; i += 1) {
    hls.trigger(Events.ERROR, {
      type: ErrorTypes.NETWORK_ERROR,
      details: ErrorDetails.FRAG_LOAD_ERROR,
      fatal: false,
      frag,
      response: { code: 404, text: '' },
      error: new Error('HTTP 404'),
    } as unknown as ErrorData);
  }
  return seen;
}

describe('hls.js 1.7.3 의 받기 오류 404 와 목록 오류 가르기(cx H1)', () => {
  it('변이가 없으면 조각 404 도 곧바로 치명이다 — 그러나 목록 오류가 아니다(라이브 닫힘이 아니다)', () => {
    const seen = raise(ErrorDetails.FRAG_LOAD_ERROR, 404);
    expect(seen.fatal).toBe(true);
    expect(isPlaylistLoadError(seen, ErrorDetails)).toBe(false);
  });

  it('변이 하나 · 라이브 마지막 조각 404 는 6회 비치명 재시도 뒤 치명 — 일곱 번 다 목록 오류가 아니다', () => {
    expect(Hls.DefaultConfig.fragLoadPolicy.default.errorRetry?.maxNumRetry).toBe(6);
    const seen = raiseOnLastLiveFragment(7);
    expect(seen.map((data) => data.fatal)).toEqual([
      false,
      false,
      false,
      false,
      false,
      false,
      true,
    ]);
    // 훅의 라이브 닫힘 갈래는 목록 오류에서만 열린다(useHlsPlayback ERROR 처리) — 닫힘 0회
    expect(seen.filter((data) => isPlaylistLoadError(data, ErrorDetails))).toHaveLength(0);
  });

  it('변이 목록 404 는 치명이고 목록 오류다(라이브 닫힘)', () => {
    const seen = raise(ErrorDetails.LEVEL_LOAD_ERROR, 404);
    expect(seen.fatal).toBe(true);
    expect(isPlaylistLoadError(seen, ErrorDetails)).toBe(true);
  });

  it('멀티변이 목록 · 오디오 목록 받기 오류도 목록 오류다', () => {
    expect(isPlaylistLoadError({ details: ErrorDetails.MANIFEST_LOAD_ERROR }, ErrorDetails)).toBe(
      true,
    );
    expect(
      isPlaylistLoadError({ details: ErrorDetails.AUDIO_TRACK_LOAD_ERROR }, ErrorDetails),
    ).toBe(true);
    expect(isPlaylistLoadError({ details: ErrorDetails.KEY_LOAD_ERROR }, ErrorDetails)).toBe(false);
  });
});

/** 첫 목록 받기가 끝나는 꼴 — HTTP 상태 하나로 실패하거나 · 시한에 걸리거나 · 목록이 아닌 본문을 받는다 */
type FirstListOutcome = { code: number } | 'timeout' | 'not-a-playlist';

const FIRST_LIST_URL = 'https://m/vod/k/a1/index.m3u8';

/**
 * 진짜 hls.js 1.7.3 으로 첫 목록(MANIFEST) 받기를 outcome 으로 끝나게 하고 앱 처리기가 받은 오류를 차례로 돌려준다. 가짜
 * pLoader 는 다시 받지 않는다 — 첫 목록의 다시 받기(manifestLoadPolicy)는 기본 로더 안에서 돌고, 그 뒤 앱이 받는 꼴은
 * 마지막 실패 하나다(loader/playlist-loader.ts 611-615 · 653-665)
 */
async function firstListErrors(outcome: FirstListOutcome): Promise<ErrorData[]> {
  class FirstListLoader {
    readonly stats = new LoadStats();
    load(...[context, , callbacks]: LoadArgs) {
      queueMicrotask(() => {
        if (outcome === 'timeout') {
          callbacks.onTimeout(this.stats, context, null);
          return;
        }
        if (outcome === 'not-a-playlist') {
          this.stats.loading.end = performance.now();
          callbacks.onSuccess(
            { url: context.url, data: '<html></html>' },
            this.stats,
            context,
            null,
          );
          return;
        }
        callbacks.onError({ code: outcome.code, text: '' }, context, null, this.stats);
      });
    }
    abort() {}
    destroy() {}
  }
  const hls = new Hls({
    ...HLS_REWIND_CONFIG,
    pLoader: FirstListLoader as unknown as HlsConfig['pLoader'],
    debug: SILENT,
  });
  instances.push(hls);
  const seen: ErrorData[] = [];
  hls.on(Events.ERROR, (_event, data) => {
    seen.push({ ...data });
  });
  hls.loadSource(FIRST_LIST_URL);
  await vi.waitFor(() => {
    expect(seen.length).toBeGreaterThan(0);
  });
  return seen;
}

/** 표 시험의 첫 목록 받기 오류 — 바꿀 칸만 덮어쓴다 */
function firstListError(overrides: Partial<ErrorData> = {}): ErrorData {
  return {
    type: ErrorTypes.NETWORK_ERROR,
    details: ErrorDetails.MANIFEST_LOAD_ERROR,
    fatal: true,
    response: { url: FIRST_LIST_URL, code: 404, text: '' },
    ...overrides,
  } as unknown as ErrorData;
}

describe('첫 목록 실패 가르기(POK-528 c8) — 첫 목록 치명만 참 · 응답 코드는 보지 않는다', () => {
  it.each<[string, boolean, Partial<ErrorData>]>([
    ['첫 목록 404', true, {}],
    ['첫 목록 503', true, { response: { url: FIRST_LIST_URL, code: 503, text: '' } }],
    ['첫 목록 상태 0', true, { response: { url: FIRST_LIST_URL, code: 0, text: '' } }],
    ['첫 목록 시한', true, { details: ErrorDetails.MANIFEST_LOAD_TIMEOUT, response: undefined }],
    ['첫 목록 파싱 오류', true, { details: ErrorDetails.MANIFEST_PARSING_ERROR }],
    ['비치명 첫 목록 404', false, { fatal: false }],
    [
      '비치명 파싱 오류(변이 목록의 파싱 오류는 이 꼴)',
      false,
      { details: ErrorDetails.MANIFEST_PARSING_ERROR, fatal: false },
    ],
    ['치명 변이 목록 404', false, { details: ErrorDetails.LEVEL_LOAD_ERROR }],
    ['치명 조각 404', false, { details: ErrorDetails.FRAG_LOAD_ERROR }],
    ['네트워크 오류가 아닌 갈래', false, { type: ErrorTypes.MEDIA_ERROR }],
  ])('%s → %s', (_name, expected, overrides) => {
    expect(isFirstListFailure(firstListError(overrides), ErrorTypes, ErrorDetails)).toBe(expected);
  });

  it.each<[string, FirstListOutcome, ErrorDetails]>([
    ['404', { code: 404 }, ErrorDetails.MANIFEST_LOAD_ERROR],
    ['503', { code: 503 }, ErrorDetails.MANIFEST_LOAD_ERROR],
    ['시한', 'timeout', ErrorDetails.MANIFEST_LOAD_TIMEOUT],
    ['목록이 아닌 본문', 'not-a-playlist', ErrorDetails.MANIFEST_PARSING_ERROR],
  ])('진짜 hls.js 의 첫 목록 %s 는 치명 하나로 오고 참이다', async (_name, outcome, details) => {
    const seen = await firstListErrors(outcome);
    expect(seen.map((data) => [data.type, data.details, data.fatal])).toEqual([
      [ErrorTypes.NETWORK_ERROR, details, true],
    ]);
    expect(seen.every((data) => isFirstListFailure(data, ErrorTypes, ErrorDetails))).toBe(true);
  });
});

/** 조각 받기가 끝나는 꼴 — HTTP 상태 하나로 실패하거나 시한에 걸린다 */
type FragOutcome = { code: number } | 'timeout';

type LoadArgs = [LoaderContext, unknown, LoaderCallbacks<LoaderContext>];

interface StreamControllerInternals {
  state: string;
  fragmentLoader: { load(frag: Fragment): Promise<FragLoadedData> };
  loadInitSegmentIfNeeded(mediaFrag: Fragment): Promise<void> | undefined;
}

const PLAYLIST_URL = 'https://m/dvr/k/a/index.m3u8';

/** 받는 조각마다 끝나는 꼴을 정하는 가짜 fLoader 를 물린 되감기 재생기 — 받기는 앱이 직접 부른다(autoStartLoad 끔) */
function rewindPlayerEndingWith(outcomeFor: (url: string) => FragOutcome): {
  hls: Hls;
  streamController: StreamControllerInternals;
} {
  class EndingLoader {
    stats = new LoadStats();
    load(...[context, , callbacks]: LoadArgs) {
      const outcome = outcomeFor(context.url);
      queueMicrotask(() => {
        if (outcome === 'timeout') {
          callbacks.onTimeout(this.stats, context, null);
          return;
        }
        callbacks.onError({ code: outcome.code, text: '' }, context, null, this.stats);
      });
    }
    abort() {}
    destroy() {}
  }
  const hls = new Hls({
    ...HLS_REWIND_CONFIG,
    autoStartLoad: false,
    fLoader: EndingLoader as unknown as HlsConfig['fLoader'],
    debug: SILENT,
  });
  instances.push(hls);
  const { streamController } = hls as unknown as { streamController: StreamControllerInternals };
  return { hls, streamController };
}

/** 미디어 조각 sn 과 그 조각의 init 조각(MediaMTX 처럼 별도 파일 init.mp4) */
function mediaFragWithInit(sn: number): Fragment {
  const initSegment = new Fragment(PlaylistLevelType.MAIN, PLAYLIST_URL);
  initSegment.sn = 'initSegment';
  initSegment.relurl = 'init.mp4';
  const frag = new Fragment(PlaylistLevelType.MAIN, PLAYLIST_URL);
  frag.sn = sn;
  frag.relurl = `seg${sn}.mp4`;
  frag.initSegment = initSegment;
  return frag;
}

/**
 * 진짜 FragmentLoader(stream-controller 가 쓰는 것)로 조각 하나를 받다가 outcome 으로 끝나게 하고, 그 거절(LoadError)에
 * 담긴 오류 자료를 돌려준다(fragment-loader.ts 118-131 · 147-159). 미디어 조각이면 stream-controller 가 이 자료를 그대로
 * ERROR 로 올려 앱이 받는다. init 조각이면 앱이 받는 꼴이 아니다 — hls.js 가 frag 를 미디어 조각으로 바꿔 끼운 뒤
 * 올린다(아래 initLoadErrorSeenByApp)
 */
async function rejectedFragLoad(frag: Fragment, outcome: FragOutcome): Promise<ErrorData> {
  const { streamController } = rewindPlayerEndingWith(() => outcome);
  const { fragmentLoader } = streamController;
  const failure: unknown = await fragmentLoader.load(frag).then(
    () => {
      throw new Error('조각 받기가 거절되지 않았다');
    },
    (error: unknown) => error,
  );
  return (failure as { data: ErrorData }).data;
}

/**
 * 진짜 stream-controller 의 init 받기 경로(loadInitSegmentIfNeeded — _doFragLoad 가 미디어 조각 받기 앞에 부른다)로
 * 미디어 조각의 init 만 404 로 끝나게 하고, 앱의 ERROR 처리기가 받은 자료를 돌려준다. hls.js 는 거절 자료의 frag 를
 * 미디어 조각으로 바꿔 끼운 뒤 올린다(base-stream-controller.ts 815-823)
 */
async function initLoadErrorSeenByApp(mediaFrag: Fragment): Promise<ErrorData> {
  const initUrl = mediaFrag.initSegment?.url;
  const requested: string[] = [];
  const { hls, streamController } = rewindPlayerEndingWith((url) => {
    requested.push(url);
    return { code: 404 };
  });
  const seen: ErrorData[] = [];
  hls.on(Events.ERROR, (_event, data) => {
    seen.push({ ...data });
  });
  // 받기 중인 국면 — STOPPED · ERROR 면 hls.js 가 ERROR 를 올리지 않고 거절만 다시 던진다(816-818)
  streamController.state = 'FRAG_LOADING';
  const loading = streamController.loadInitSegmentIfNeeded(mediaFrag);
  if (loading === undefined) throw new Error('init 받기가 시작되지 않았다');
  await loading.then(
    () => {
      throw new Error('init 받기가 거절되지 않았다');
    },
    () => undefined,
  );
  // 받은 파일은 init 하나뿐이다 — 실패한 파일이 init 임을 고정한다
  expect(requested).toEqual([initUrl]);
  const [data, ...rest] = seen;
  if (data === undefined || rest.length > 0) {
    throw new Error(`앱 처리기가 오류를 ${seen.length}번 받았다`);
  }
  return data;
}

const MEDIA_URL = 'https://m/dvr/k/a/seg7.mp4';
const INIT_URL = 'https://m/dvr/k/a/init.mp4';

/** 조각 받기가 HTTP 상태 code 로 끝난 응답 — 실패한 파일은 url */
function httpStatus(code: number, url: string = MEDIA_URL): ErrorData['response'] {
  return { url, code, text: '' };
}

/** 표 시험의 조각 받기 오류 — 바꿀 칸만 덮어쓴다 */
function fragError(overrides: Partial<ErrorData> = {}): ErrorData {
  return {
    type: ErrorTypes.NETWORK_ERROR,
    details: ErrorDetails.FRAG_LOAD_ERROR,
    fatal: false,
    frag: { sn: 7, initSegment: { url: INIT_URL } },
    response: httpStatus(404),
    ...overrides,
  } as unknown as ErrorData;
}

describe('미디어 조각 404 가르기(POK-528) — 하한 밖으로 밀린 조각만 참', () => {
  it.each<[string, boolean, Partial<ErrorData>]>([
    ['미디어 조각 404', true, {}],
    ['치명으로 올라온 미디어 조각 404(치명 여부는 보지 않는다)', true, { fatal: true }],
    ['상태 0(망 끊김)', false, { response: httpStatus(0) }],
    ['403', false, { response: httpStatus(403) }],
    ['503', false, { response: httpStatus(503) }],
    ['조각 받기 시한', false, { details: ErrorDetails.FRAG_LOAD_TIMEOUT, response: undefined }],
    // 앱이 받는 꼴 — hls.js 가 frag 를 미디어 조각으로 바꿔 끼워 sn 은 미디어, 실패한 파일은 그 init
    ['init 조각 404(미디어 조각으로 바꿔 끼운 꼴)', false, { response: httpStatus(404, INIT_URL) }],
    [
      'init 조각 자체로 온 404(바꿔 끼우기 전 꼴)',
      false,
      {
        frag: { sn: 'initSegment' } as ErrorData['frag'],
        response: httpStatus(404, INIT_URL),
      },
    ],
    ['조각 받기 오류에 응답 없음', false, { response: undefined }],
    ['변이 목록 404', false, { details: ErrorDetails.LEVEL_LOAD_ERROR, frag: undefined }],
    ['멀티변이 목록 404', false, { details: ErrorDetails.MANIFEST_LOAD_ERROR, frag: undefined }],
    ['네트워크 오류가 아닌 갈래', false, { type: ErrorTypes.MEDIA_ERROR }],
  ])('%s → %s', (_name, expected, overrides) => {
    expect(isMediaSegmentNotFound(fragError(overrides), ErrorTypes, ErrorDetails)).toBe(expected);
  });

  it('진짜 FragmentLoader 의 미디어 조각 404 거절은 참이다(init 이 붙어 있어도)', async () => {
    const data = await rejectedFragLoad(mediaFragWithInit(7), { code: 404 });
    expect(data).toMatchObject({
      type: ErrorTypes.NETWORK_ERROR,
      details: ErrorDetails.FRAG_LOAD_ERROR,
      fatal: false,
      response: { code: 404, url: MEDIA_URL },
    });
    expect(isMediaSegmentNotFound(data, ErrorTypes, ErrorDetails)).toBe(true);
  });

  it('진짜 init 받기 경로의 init 404 는 앱에 미디어 sn 으로 오지만 거짓이다(하한 대상이 아니다)', async () => {
    const mediaFrag = mediaFragWithInit(7);
    const data = await initLoadErrorSeenByApp(mediaFrag);
    expect(data).toMatchObject({
      type: ErrorTypes.NETWORK_ERROR,
      details: ErrorDetails.FRAG_LOAD_ERROR,
      response: { code: 404, url: INIT_URL },
    });
    // 전제 고정 — 앱이 받는 frag 는 init 이 아니라 바꿔 끼운 미디어 조각이다(sn 만으로는 못 가른다)
    expect(data.frag).toBe(mediaFrag);
    expect(data.frag?.sn).toBe(7);
    expect(data.frag?.initSegment?.url).toBe(INIT_URL);
    expect(isMediaSegmentNotFound(data, ErrorTypes, ErrorDetails)).toBe(false);
  });

  it('진짜 FragmentLoader 의 미디어 조각 403 · 시한 거절은 거짓이다', async () => {
    const forbidden = await rejectedFragLoad(mediaFragWithInit(7), { code: 403 });
    const timedOut = await rejectedFragLoad(mediaFragWithInit(7), 'timeout');
    expect(timedOut.details).toBe(ErrorDetails.FRAG_LOAD_TIMEOUT);
    expect(isMediaSegmentNotFound(forbidden, ErrorTypes, ErrorDetails)).toBe(false);
    expect(isMediaSegmentNotFound(timedOut, ErrorTypes, ErrorDetails)).toBe(false);
  });
});

/** 404 뒤 hls.js 가 받기를 다시 여는 세 갈래(계획 8-2) — 방송 중 가운데 조각 · 방송 중 마지막 조각 · ENDLIST 목록 */
type GoneBranch = 'live-middle' | 'live-last' | 'endlist';

interface LoadingInternals {
  state: string;
  setInterval(millis: number): boolean;
  hasInterval(): boolean;
  hasNextTick(): boolean;
  fragmentLoader: { load(frag: Fragment): Promise<FragLoadedData> };
}

interface LoadingPlayer {
  hls: Hls;
  streamController: LoadingInternals;
  /** 404 를 낼, 지금 받는 조각(sn 1) */
  frag: Fragment;
  /** 조각 받기 시도 수(fragmentLoader.load — 네트워크 없이 센다) */
  loads: () => number;
  /** FRAG_LOADING 사건 수(base-stream-controller.ts 1173 — 받기를 시작할 때마다 난다) */
  fragLoadings: () => number;
}

function fragmentAt(sn: number): Fragment {
  const frag = new Fragment(PlaylistLevelType.MAIN, PLAYLIST_URL);
  frag.sn = sn;
  frag.level = 0;
  frag.start = (sn - 1) * 4;
  frag.duration = 4;
  frag.relurl = `seg${sn}.mp4`;
  return frag;
}

/**
 * 변이 하나 · 미디어가 붙은 되감기 재생기가 조각 sn 1 을 받는 중인 꼴. tick 은 100ms 주기로
 * 돌고(stream-controller 기동 꼴), 다음 tick(tickImmediate)도 막지 않는다 — 갈래마다 404 뒤의
 * 예약(틈 넘김의 다음 tick · 같은 조각 재시도)이 판별 신호다. 받기는 끝나지 않는 약속으로
 * 바꿔 시도 수만 센다
 */
function rewindPlayerLoading(branch: GoneBranch): LoadingPlayer {
  const hls = new Hls({ ...HLS_REWIND_CONFIG, debug: SILENT });
  instances.push(hls);
  const frag = fragmentAt(1);
  const fragments = branch === 'live-middle' ? [frag, fragmentAt(2)] : [frag];
  const details = {
    live: branch !== 'endlist',
    startSN: 1,
    endSN: fragments.length,
    fragments,
    fragmentStart: 0,
    fragmentEnd: fragments.length * 4,
    edge: fragments.length * 4,
    targetduration: 4,
    totalduration: fragments.length * 4,
    partList: null,
    updated: true,
    advanced: true,
  };
  const level = {
    fragmentError: 0,
    loadError: 0,
    loadErrorTime: 0,
    pathwayId: '.',
    bitrate: 500000,
    maxBitrate: 500000,
    attrs: {},
    audioCodec: 'aac',
    videoCodec: 'avc1',
    hasAudioGroup: () => false,
    hasSubtitleGroup: () => false,
    details,
  };
  const internals = hls as unknown as {
    levelController: Record<string, unknown>;
    streamController: LoadingInternals;
  };
  Object.assign(internals.levelController, {
    _levels: [level],
    currentLevelIndex: 0,
    currentLevel: level,
  });
  const { streamController } = internals;
  // 미디어가 붙어야 doTickIdle 이 받기까지 간다(stream-controller.ts 245-256)
  const media = document.createElement('video');
  Object.assign(streamController, {
    _hasEnoughToStart: true,
    levels: [level],
    levelLastLoaded: level,
    fragCurrent: frag,
    media,
    mediaBuffer: media,
    state: 'FRAG_LOADING',
  });
  const load = vi
    .spyOn(streamController.fragmentLoader, 'load')
    .mockImplementation(() => new Promise<FragLoadedData>(() => {}));
  let fragLoadings = 0;
  hls.on(Events.FRAG_LOADING, () => {
    fragLoadings += 1;
  });
  streamController.setInterval(100);
  return {
    hls,
    streamController,
    frag,
    loads: () => load.mock.calls.length,
    fragLoadings: () => fragLoadings,
  };
}

/** fragment-loader 가 미디어 조각 404 로 거절한 꼴의 비치명 오류를 쏜다 */
function raiseMedia404(hls: Hls, frag: Fragment): void {
  hls.trigger(Events.ERROR, {
    type: ErrorTypes.NETWORK_ERROR,
    details: ErrorDetails.FRAG_LOAD_ERROR,
    fatal: false,
    frag,
    response: { url: `https://m/dvr/k/a/${frag.relurl}`, code: 404, text: '' },
    error: new Error('HTTP 404'),
  } as unknown as ErrorData);
}

/** tick · 재시도 시각(performance.now — checkRetryDate)을 함께 미는 가짜 시계 */
function fakeClock(): void {
  vi.useFakeTimers({
    toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance', 'Date'],
  });
}

/** 제어면 응답 지연 꼴(계획 8-2) — 이 동안 받기가 다시 나가면 하한 밖 조각을 또 청한 것이다 */
const CONTROL_DELAY_MS = 60_000;

describe('되감기 조각 404 가로채기 — 진짜 hls.js 1.7.3 의 받기 멈춤(POK-528 c5 · 계획 8-2)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it.each<[GoneBranch, string]>([
    ['live-middle', 'IDLE'],
    ['live-last', 'FRAG_LOADING_WAITING_RETRY'],
    ['endlist', 'FRAG_LOADING_WAITING_RETRY'],
  ])(
    '전제 — 앱이 가로채지 않으면 %s 404 뒤 hls.js 가 받기를 다시 연다(앱 처리기가 볼 때 국면 %s)',
    (branch, stateSeenByApp) => {
      fakeClock();
      const player = rewindPlayerLoading(branch);
      // 전제는 hls.js 의 상태만 본다 — 제품 판정(consumeDvrSegmentGone)을 부르지 않는다
      const seen: string[] = [];
      player.hls.on(Events.ERROR, () => {
        seen.push(player.streamController.state);
      });
      raiseMedia404(player.hls, player.frag);
      expect(seen).toEqual([stateSeenByApp]);
      vi.advanceTimersByTime(CONTROL_DELAY_MS);
      // 요청 관측의 판별력 — 이 차림에서는 가로채지 않으면 받기 시도가 보인다
      expect(player.loads()).toBeGreaterThan(0);
      expect(player.fragLoadings()).toBeGreaterThan(0);
    },
  );

  /** 가로채기 판정을 앱 처리기 자리에 단다 — 판정 결과를 차례로 모은다 */
  function intercept(player: LoadingPlayer, onGone: () => void): boolean[] {
    const consumed: boolean[] = [];
    player.hls.on(Events.ERROR, (_event, data) => {
      consumed.push(consumeDvrSegmentGone(player.hls, data, ErrorTypes, ErrorDetails, onGone));
    });
    return consumed;
  }

  it.each<GoneBranch>(['live-middle', 'live-last', 'endlist'])(
    '%s 404 를 가로채면 STOPPED · 주기 tick 없음 · 다음 tick 없음 · 고리 1',
    (branch) => {
      fakeClock();
      const player = rewindPlayerLoading(branch);
      const onGone = vi.fn();
      const consumed = intercept(player, onGone);
      raiseMedia404(player.hls, player.frag);
      // 상태 단언(1차 증거)
      expect(consumed).toEqual([true]);
      expect(onGone).toHaveBeenCalledTimes(1);
      expect(player.streamController.state).toBe('STOPPED');
      expect(player.streamController.hasInterval()).toBe(false);
      expect(player.streamController.hasNextTick()).toBe(false);
    },
  );

  // 요청 관측(2차 증거)은 상태 단언과 따로 둔다 — 상태 단언이 먼저 끊기면 받기 수가 기록되지 않는다(c5 검수 이연 M ·
  // 변이 M5a). 관측을 다 모은 뒤 한 번에 견주어, 실패하면 받기 수가 그대로 출력에 남는다
  it.each<GoneBranch>(['live-middle', 'live-last', 'endlist'])(
    '%s 404 를 가로채면 제어면 지연 60초 동안 받기 0 · 받기 시작 사건 0 · 그 뒤에도 STOPPED',
    (branch) => {
      fakeClock();
      const player = rewindPlayerLoading(branch);
      const consumed = intercept(player, vi.fn());
      raiseMedia404(player.hls, player.frag);
      vi.advanceTimersByTime(CONTROL_DELAY_MS);
      expect({
        consumed,
        loads: player.loads(),
        fragLoadings: player.fragLoadings(),
        state: player.streamController.state,
      }).toEqual({ consumed: [true], loads: 0, fragLoadings: 0, state: 'STOPPED' });
    },
  );

  it('고리가 없으면 미디어 404 라도 가로채지 않는다 — 판정 거짓 · stopLoad 0(지금 동작)', () => {
    fakeClock();
    const player = rewindPlayerLoading('live-middle');
    // 가로채기가 부르는 stopLoad 만 센다
    const interceptor = { stopLoad: vi.fn() };
    const seen: Array<{ media404: boolean; consumed: boolean }> = [];
    player.hls.on(Events.ERROR, (_event, data) => {
      seen.push({
        media404: isMediaSegmentNotFound(data, ErrorTypes, ErrorDetails),
        consumed: consumeDvrSegmentGone(interceptor, data, ErrorTypes, ErrorDetails),
      });
    });
    raiseMedia404(player.hls, player.frag);
    // 판별은 참인 자료다 — 거짓은 고리가 없어서다
    expect(seen).toEqual([{ media404: true, consumed: false }]);
    expect(interceptor.stopLoad).not.toHaveBeenCalled();
  });

  it('init 404 는 가로채지 않는다 — 고리 0 · 가로채기의 stopLoad 0(hls.js 의 지금 경로 그대로)', async () => {
    const { hls, streamController } = rewindPlayerEndingWith(() => ({ code: 404 }));
    // 가로채기가 부르는 stopLoad 만 센다 — 변이가 없는 이 차림에서는 hls.js 오류 처리가 치명으로 올리며 스스로
    // stopLoad 를 부를 수 있다(error-controller)
    const interceptor = { stopLoad: vi.fn() };
    const onGone = vi.fn();
    const consumed: boolean[] = [];
    hls.on(Events.ERROR, (_event, data) => {
      consumed.push(consumeDvrSegmentGone(interceptor, data, ErrorTypes, ErrorDetails, onGone));
    });
    streamController.state = 'FRAG_LOADING';
    const loading = streamController.loadInitSegmentIfNeeded(mediaFragWithInit(7));
    if (loading === undefined) throw new Error('init 받기가 시작되지 않았다');
    await loading.then(
      () => {
        throw new Error('init 받기가 거절되지 않았다');
      },
      () => undefined,
    );
    expect(consumed.length).toBeGreaterThan(0);
    expect(consumed).not.toContain(true);
    expect(onGone).not.toHaveBeenCalled();
    expect(interceptor.stopLoad).not.toHaveBeenCalled();
  });
});

/** 되감기 미디어 목록(방송 중 — ENDLIST 없음 · 4초 조각 셋) */
const LIVE_REWIND_PLAYLIST = [
  '#EXTM3U',
  '#EXT-X-VERSION:6',
  '#EXT-X-TARGETDURATION:4',
  '#EXT-X-MEDIA-SEQUENCE:1',
  '#EXT-X-PROGRAM-DATE-TIME:2026-10-25T05:00:00.000Z',
  '#EXTINF:4.000,',
  'seg1.mp4',
  '#EXTINF:4.000,',
  'seg2.mp4',
  '#EXTINF:4.000,',
  'seg3.mp4',
  '',
].join('\n');

/** 목록 다시 받기를 기다리는 동안의 가짜 시계 길이(계획 8-2 c7 ① — 30초) */
const RELIST_WAIT_MS = 30_000;

/** 가짜 pLoader 가 받은 목록 요청 하나 — 응답을 보류하고 끊김(abort · destroy)을 기록한다 */
interface HeldListRequest {
  context: LoaderContext;
  aborted: boolean;
  /** 보류한 요청에 목록을 흘린다 — 끊긴 요청은 실물 XHR 처럼 답하지 않는다 */
  respond: () => void;
}

/**
 * 목록 요청을 시험이 쥐는 되감기 재생기 — 첫 목록(MANIFEST)은 곧바로 답하고 그 뒤 목록 요청은
 * 보류한다. 받기 사건(LEVEL_LOADED · LEVEL_UPDATED) 수를 센다
 */
function rewindPlayerWithHeldLists(): {
  hls: Hls;
  requests: HeldListRequest[];
  listEvents: () => number;
} {
  const requests: HeldListRequest[] = [];
  class HeldListLoader {
    stats = new LoadStats();
    private request: HeldListRequest | null = null;
    load(...[context, , callbacks]: LoadArgs) {
      const stats = this.stats;
      const request: HeldListRequest = {
        context,
        aborted: false,
        respond: () => {
          if (request.aborted) return;
          stats.loading.end = performance.now();
          callbacks.onSuccess(
            { url: context.url, data: LIVE_REWIND_PLAYLIST },
            stats,
            context,
            null,
          );
        },
      };
      this.request = request;
      requests.push(request);
      if (requests.length === 1) queueMicrotask(request.respond);
    }
    abort() {
      if (this.request !== null) this.request.aborted = true;
    }
    destroy() {
      this.abort();
    }
  }
  const hls = new Hls({
    ...HLS_REWIND_CONFIG,
    autoStartLoad: true,
    pLoader: HeldListLoader as unknown as HlsConfig['pLoader'],
    debug: SILENT,
  });
  instances.push(hls);
  let listEvents = 0;
  const count = () => {
    listEvents += 1;
  };
  hls.on(Events.LEVEL_LOADED, count);
  hls.on(Events.LEVEL_UPDATED, count);
  return { hls, requests, listEvents: () => listEvents };
}

/** 첫 목록을 받아 다음 목록 요청 하나가 보류된 채 나갈 때까지 가짜 시계를 민다 */
async function untilReloadHeld(player: ReturnType<typeof rewindPlayerWithHeldLists>) {
  player.hls.loadSource(PLAYLIST_URL);
  await vi.advanceTimersByTimeAsync(0);
  for (let t = 0; t < RELIST_WAIT_MS && player.requests.length < 2; t += 500) {
    await vi.advanceTimersByTimeAsync(500);
  }
}

describe('되감기 조각 404 가로채기 뒤 목록 요청 — 진짜 hls.js 1.7.3(POK-528 c7 · 계획 8-2)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('전제 — 가로채지 않으면 방송 중 목록은 30초 동안 다시 청해진다(요청 관측의 판별력)', async () => {
    fakeClock();
    const player = rewindPlayerWithHeldLists();
    await untilReloadHeld(player);
    expect(player.requests.length).toBeGreaterThanOrEqual(2);
  });

  it('① 가로채기 뒤 가짜 시계 30초 동안 새 목록 요청 0(P27 — 다시 받기는 세션이 새 재생기로 한다)', async () => {
    fakeClock();
    const player = rewindPlayerWithHeldLists();
    player.hls.loadSource(PLAYLIST_URL);
    await vi.advanceTimersByTimeAsync(0);
    expect(player.requests).toHaveLength(1);
    expect(consumeDvrSegmentGone(player.hls, fragError(), ErrorTypes, ErrorDetails, vi.fn())).toBe(
      true,
    );
    await vi.advanceTimersByTimeAsync(RELIST_WAIT_MS);
    expect(player.requests.map((request) => request.context.type)).toEqual(['manifest']);
  });

  it('② 진행 중이던 목록 요청은 가로채기의 stopLoad 가 끊는다 — 그 응답이 와도 목록 사건 0', async () => {
    fakeClock();
    const player = rewindPlayerWithHeldLists();
    await untilReloadHeld(player);
    const held = player.requests[1];
    if (held === undefined) throw new Error('다시 청한 목록 요청이 없다');
    const before = player.listEvents();
    expect(consumeDvrSegmentGone(player.hls, fragError(), ErrorTypes, ErrorDetails, vi.fn())).toBe(
      true,
    );
    held.respond();
    await vi.advanceTimersByTimeAsync(RELIST_WAIT_MS);
    expect({ aborted: held.aborted, listEvents: player.listEvents() - before }).toEqual({
      aborted: true,
      listEvents: 0,
    });
  });
});
