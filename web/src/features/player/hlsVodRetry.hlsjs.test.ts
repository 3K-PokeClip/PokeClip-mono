import Hls, {
  ErrorDetails,
  ErrorTypes,
  Events,
  Fragment,
  LoadStats,
  PlaylistLevelType,
  type ErrorData,
  type HlsConfig,
  type LoaderCallbacks,
  type LoaderContext,
} from 'hls.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HLS_REWIND_CONFIG } from './hlsConfig';

// 다시보기(VOD) 재생 중 조각 404 를 진짜 hls.js 1.7.3(dist)이 얼마나 감추는지 고정한다(POK-528 c6 · W8 (ㄱ) 실물 층).
// kty 결정(2026-11-03): VOD 조각 404 는 지금 동작 그대로 — hls.js 기본 재시도와 앱의 한 번 다시 받기로 감춘다(hlsConfig.ts
// 무변경). 기본 재시도는 Hls.DefaultConfig.fragLoadPolicy.default.errorRetry 다(config.ts 596-610: maxNumRetry 6 ·
// retryDelayMs 1000 · maxRetryDelayMs 8000 · backoff 없음 = 지수 — error-helper.ts 37-48). 4xx 는 「다시 받을 상태」가
// 아니지만(error-helper.ts 86-92) 갈아탈 변이가 없으면 stream 쪽이 같은 조각을 다시 받는다(base-stream-controller.ts
// 2141-2178 의 noAlternate 갈래). ENDLIST 목록(details.live 거짓)이라 틈 넘김 갈래(2153-2167)는 타지 않는다. 한도를 다
// 쓰면 치명으로 올리고 hls.js 가 stopLoad 한다(error-controller.ts 502-504) — 이때 level-controller.stopLoad 가 조각 오류
// 수를 0 으로 되돌려(level-controller.ts 82-89) 앱의 다시 받기(useHlsPlayback ERROR 처리기의 한 번 다시 받기) 뒤에 같은
// 꼴이 한 번 더 돈다. 그래서 감춤은 약 31초 × 2 다. 훅 층(앱이 치명에서 startLoad 한 번 · 두 번째 치명에서 끊김)은
// useHlsPlayback.vod404.test.ts 가 본다. /vod 문서 404 는 조각과 다르다 — 멀티변이 목록 404 는 앱이 다시 받아도 다시 청하지
// 않아 두 번째 치명이 없다(맨 아래 묶음 · POK-528 c6 X1). 그래서 앱은 그 꼴에서 다시 받지 않고 곧바로 끊김을 알린다(c8).

function quiet() {}

const SILENT = { trace: quiet, debug: quiet, log: quiet, warn: quiet, info: quiet, error: quiet };

const PLAYLIST_URL = 'https://m/vod/k/a/index.m3u8';

/** 기본 재시도 간격(ms) — 1초에서 두 배씩, 8초에서 멈춘다(getRetryDelay) */
const DEFAULT_RETRY_GAPS_MS = [1_000, 2_000, 4_000, 8_000, 8_000, 8_000];

/** 한 바퀴(첫 받기 + 재시도 6)의 받기 수 */
const LOADS_PER_ROUND = 7;

/** 한 바퀴의 감춤 시간 — 기본 간격의 합 */
const ROUND_MS = 31_000;

/** 한도를 다 쓴 뒤 더 받지 않는지 볼 시간 */
const AFTER_FATAL_MS = 60_000;

type LoadArgs = [LoaderContext, unknown, LoaderCallbacks<LoaderContext>];

interface StreamControllerInternals {
  state: string;
  setInterval(millis: number): boolean;
}

interface VodPlayer {
  readonly hls: Hls;
  readonly streamController: StreamControllerInternals;
  /** 조각 받기마다 그때의 performance.now()(재시도 시각 비교에 hls.js 가 쓰는 시계 — checkRetryDate) */
  readonly loadTimes: number[];
  /** 앱 처리기가 본 오류 */
  readonly errors: ErrorData[];
}

const instances: Hls[] = [];

afterEach(() => {
  for (const hls of instances.splice(0)) {
    hls.destroy();
  }
  vi.useRealTimers();
});

/** tick · 재시도 시각(performance.now)을 함께 미는 가짜 시계 */
function fakeClock(): void {
  vi.useFakeTimers({
    toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance', 'Date'],
  });
}

/**
 * 앱의 치명 처리(useHlsPlayback ERROR 처리기의 한 번 다시 받기)를 같은 자리에 단다 — 첫 치명 네트워크 오류의 처리기
 * 안에서 동기로 위치 없이 startLoad 한 번. 첫 목록 치명을 먼저 끊김으로 내리는 갈래(POK-528 c8)는 흉내 내지 않는다.
 * hls.js 자신의 처리기(error-controller 의 stopLoad 등)는 생성 때 등록돼 이보다 먼저 돈다
 * @returns 앱 꼴로 부른 startLoad 수 — 위치 없는 부름만 센다. hls.js 의 자동 시작도 hls.startLoad 를 거치지만 늘 위치를
 *     싣는다(playlist-loader.ts 398 checkAutostartLoad 의 startPosition)
 */
function recoverLikeApp(hls: Hls): () => number {
  const startLoad = vi.spyOn(hls, 'startLoad');
  let recovered = false;
  hls.on(Events.ERROR, (_event, data) => {
    if (!data.fatal || data.type !== ErrorTypes.NETWORK_ERROR || recovered) return;
    recovered = true;
    hls.startLoad();
  });
  return () => startLoad.mock.calls.filter((args) => args.length === 0).length;
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
 * 다시보기 재생기 꼴 — 앱의 vod 재생기 설정(HLS_REWIND_CONFIG · useHlsPlayback.ts 694) · 변이 하나 · ENDLIST 목록(조각 둘)
 * · 미디어가 붙은 채 받기 직전(IDLE). 조각 받기는 진짜 FragmentLoader 가 하고, 그 아래 망만 가짜 fLoader 가 늘 404 로 끝낸다.
 * hls.js 안쪽 칸은 hlsErrors.hlsjs.test.ts 의 되감기 재생기 꼴과 같이 채운다(목록 받기를 건너뛰려고)
 */
function vodPlayerAlways404(): VodPlayer {
  const loadTimes: number[] = [];
  class NotFoundLoader {
    readonly stats = new LoadStats();
    load(...[context, , callbacks]: LoadArgs) {
      loadTimes.push(performance.now());
      queueMicrotask(() => {
        callbacks.onError({ code: 404, text: 'Not Found' }, context, null, this.stats);
      });
    }
    abort() {}
    destroy() {}
  }
  const hls = new Hls({
    ...HLS_REWIND_CONFIG,
    // 시험 전용 망 — 제품의 「커스텀 loader 금지」(useHlsPlayback.ts 의 loadSource 앞 주석)와 무관하다
    fLoader: NotFoundLoader as unknown as HlsConfig['fLoader'],
    debug: SILENT,
  });
  instances.push(hls);
  const fragments = [fragmentAt(1), fragmentAt(2)];
  const details = {
    live: false,
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
  // hls.js 안쪽 칸 주입 — 공개 형에 없는 칸이라 형을 좁혀 쓴다(hlsErrors.hlsjs.test.ts 와 같은 꼴)
  const internals = hls as unknown as {
    levelController: Record<string, unknown>;
    streamController: StreamControllerInternals;
  };
  Object.assign(internals.levelController, {
    _levels: [level],
    currentLevelIndex: 0,
    currentLevel: level,
  });
  const { streamController } = internals;
  const media = document.createElement('video');
  Object.assign(streamController, {
    _hasEnoughToStart: true,
    levels: [level],
    levelLastLoaded: level,
    media,
    mediaBuffer: media,
    state: 'IDLE',
  });
  const errors: ErrorData[] = [];
  hls.on(Events.ERROR, (_event, data) => {
    errors.push({ ...data });
  });
  streamController.setInterval(100);
  return { hls, streamController, loadTimes, errors };
}

/** 이웃한 받기 사이 간격 */
function gaps(times: number[]): number[] {
  return times.slice(1).map((time, i) => time - (times[i] ?? time));
}

describe('다시보기 조각 404 — 진짜 hls.js 1.7.3 의 기본 재시도(POK-528 c6 · W8 (ㄱ) · 계획 8-4)', () => {
  it('앱 vod 재생기 설정은 조각 받기 정책을 덮지 않는다 — hls.js 기본값(6회 · 1초 · 8초 상한 · 지수)이 그대로 산다', () => {
    expect(Object.keys(HLS_REWIND_CONFIG)).not.toContain('fragLoadPolicy');
    const errorRetry = Hls.DefaultConfig.fragLoadPolicy.default.errorRetry;
    expect(errorRetry).toMatchObject({
      maxNumRetry: 6,
      retryDelayMs: 1_000,
      maxRetryDelayMs: 8_000,
    });
    expect(errorRetry?.backoff).toBeUndefined();
    const hls = new Hls({ ...HLS_REWIND_CONFIG, debug: SILENT });
    instances.push(hls);
    expect(hls.config.fragLoadPolicy.default.errorRetry).toEqual(errorRetry);
  });

  it('미디어 조각 404 는 7번 받고(재시도 6 · 1 → 2 → 4 → 8 → 8 → 8초) 일곱 번째에 치명 — 그 뒤 hls.js 는 더 받지 않는다', async () => {
    fakeClock();
    const player = vodPlayerAlways404();
    await vi.advanceTimersByTimeAsync(ROUND_MS + AFTER_FATAL_MS);
    expect(player.loadTimes).toHaveLength(LOADS_PER_ROUND);
    expect(gaps(player.loadTimes)).toEqual(DEFAULT_RETRY_GAPS_MS);
    expect(player.errors.map((data) => data.fatal)).toEqual([
      false,
      false,
      false,
      false,
      false,
      false,
      true,
    ]);
    // 일곱 번 다 같은 조각 · 같은 자세한 갈래다(틈으로 넘기지 않는다)
    expect(player.errors.map((data) => data.frag?.sn)).toEqual(
      Array.from({ length: LOADS_PER_ROUND }, () => 1),
    );
    expect(player.errors.map((data) => data.details)).toEqual(
      Array.from({ length: LOADS_PER_ROUND }, () => ErrorDetails.FRAG_LOAD_ERROR),
    );
    expect(player.errors.every((data) => data.type === ErrorTypes.NETWORK_ERROR)).toBe(true);
    expect(player.streamController.state).toBe('STOPPED');
  });

  it('치명 뒤 앱의 한 번 다시 받기(startLoad)는 같은 꼴을 한 바퀴 더 돈다 — 감춤 약 31초 × 2', async () => {
    fakeClock();
    const player = vodPlayerAlways404();
    // 앱과 같은 시점 — 첫 치명의 처리기 안에서 곧바로 startLoad(c6 X2)
    const appStartLoads = recoverLikeApp(player.hls);
    await vi.advanceTimersByTimeAsync(2 * (ROUND_MS + AFTER_FATAL_MS));
    expect(appStartLoads()).toBe(1);
    expect(player.loadTimes).toHaveLength(2 * LOADS_PER_ROUND);
    const second = player.loadTimes.slice(LOADS_PER_ROUND);
    expect(gaps(second)).toEqual(DEFAULT_RETRY_GAPS_MS);
    expect(player.errors.slice(LOADS_PER_ROUND).map((data) => data.fatal)).toEqual([
      false,
      false,
      false,
      false,
      false,
      false,
      true,
    ]);
  });
});

/** /vod 문서 받기의 끝 — 멀티변이 목록이 404 거나, 멀티변이는 열리고 변이 목록이 404 다 */
type DocumentGone = 'multivariant' | 'variant';

const VOD_MULTIVARIANT_URL = 'https://m/vod/k/a/index.m3u8';
const VOD_VARIANT_URL = 'https://m/vod/k/a/video.m3u8';
const MULTIVARIANT_TEXT = [
  '#EXTM3U',
  '#EXT-X-STREAM-INF:BANDWIDTH=500000,CODECS="avc1.64001f,mp4a.40.2"',
  'video.m3u8',
  '',
].join('\n');

/** 목록 받기 요청 하나 — url 과 그때의 performance.now() */
interface DocumentRequest {
  readonly url: string;
  readonly atMs: number;
}

interface DocumentPlayer {
  /** 목록 받기 요청(차례대로) */
  readonly requests: DocumentRequest[];
  /** 앱 처리기가 본 오류 */
  readonly errors: ErrorData[];
  /** 앱이 부른 startLoad 수(recoverLikeApp) — 처리기가 돌지 않으면 「다시 청하지 않는다」 가 공허하게 맞는다 */
  readonly appStartLoads: () => number;
}

/**
 * 다시보기 재생기를 앱처럼 세운다(HLS_REWIND_CONFIG · loadSource). 목록 망만 가짜 pLoader 로 바꾼다. 앱 처리기는
 * useHlsPlayback 의 ERROR 처리기와 같은 자리에서 돈다(recoverLikeApp). 멀티변이 갈래는 미디어를 붙인다. 변이 갈래는
 * jsdom 에 MediaSource 가 없어 코덱 검사(level-controller 의 MANIFEST_INCOMPATIBLE_CODECS_ERROR)를 넘으려고
 * isTypeSupported 만 가진 MediaSource 를 끼우고,
 * 그것으로는 미디어를 붙일 수 없어 붙이지 않는다 — 목록 받기는 미디어 없이도 돈다
 */
function vodDocumentGone(gone: DocumentGone): DocumentPlayer {
  const requests: DocumentRequest[] = [];
  class DocumentLoader {
    readonly stats = new LoadStats();
    load(...[context, , callbacks]: LoadArgs) {
      requests.push({ url: context.url, atMs: performance.now() });
      const found = gone === 'variant' && context.url === VOD_MULTIVARIANT_URL;
      queueMicrotask(() => {
        if (!found) {
          callbacks.onError({ code: 404, text: 'Not Found' }, context, null, this.stats);
          return;
        }
        this.stats.loading.end = performance.now();
        callbacks.onSuccess(
          { url: context.url, data: MULTIVARIANT_TEXT },
          this.stats,
          context,
          null,
        );
      });
    }
    abort() {}
    destroy() {}
  }
  const hls = new Hls({
    ...HLS_REWIND_CONFIG,
    pLoader: DocumentLoader as unknown as HlsConfig['pLoader'],
    debug: SILENT,
  });
  instances.push(hls);
  const errors: ErrorData[] = [];
  hls.on(Events.ERROR, (_event, data) => {
    errors.push({ ...data });
  });
  const appStartLoads = recoverLikeApp(hls);
  if (gone === 'variant') {
    vi.stubGlobal('MediaSource', { isTypeSupported: () => true });
  } else {
    hls.attachMedia(document.createElement('video'));
  }
  hls.loadSource(VOD_MULTIVARIANT_URL);
  return { requests, errors, appStartLoads };
}

describe('/vod 문서 404 — 진짜 hls.js 1.7.3 이 앱의 한 번 다시 받기에 다시 청하는가(POK-528 c6 X1)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // 멀티변이 목록을 못 받으면 변이(levels)가 없다. 그때 hls.startLoad 는 목록 받기를 다시 열지 않는다 — playlist-loader 의
  // startLoad 는 빈 몸통이고(loader/playlist-loader.ts 86), stream-controller 는 levels 가 없으면 _forceStartLoad 만 세우고
  // STOPPED 로 둔다(controller/stream-controller.ts 191-193). 그래서 두 번째 치명이 오지 않는다. 앱은 이제 이 꼴에서
  // startLoad 하지 않고 곧바로 끊김을 알린다(POK-528 c8 · isFirstListFailure — 훅 층은 useHlsPlayback.vod404.test.ts).
  // 이 행은 그 까닭의 실물 근거다 — recoverLikeApp 은 c8 앞의 앱 꼴(한 번 다시 받기)을 흉내 낸다
  it('멀티변이 목록 404 — 치명 하나 · 앱의 startLoad 뒤 다시 청하지 않는다(요청 1 · 두 번째 치명 없음)', async () => {
    fakeClock();
    const player = vodDocumentGone('multivariant');
    await vi.advanceTimersByTimeAsync(AFTER_FATAL_MS);
    expect(player.appStartLoads()).toBe(1);
    expect(player.requests).toEqual([{ url: VOD_MULTIVARIANT_URL, atMs: 0 }]);
    expect(player.errors.map((data) => [data.details, data.fatal, data.response?.code])).toEqual([
      [ErrorDetails.MANIFEST_LOAD_ERROR, true, 404],
    ]);
  });

  // 변이 목록 404 는 목록 정책(playlistLoadPolicy.default.errorRetry — config.ts 580-593: maxNumRetry 2 · 1초에서 지수)으로
  // 두 번 다시 받고(base-playlist-controller.ts 412-458 의 checkRetry) 치명이 된다. 앱의 startLoad 는 level-controller 의
  // 목록 받기를 다시 열어(base-playlist-controller.ts 55-58) 같은 꼴이 한 바퀴 더 돌고 두 번째 치명이 온다
  it('변이 목록 404 — 3번 받고(1 → 2초) 치명 · 앱의 startLoad 뒤 한 바퀴 더(3번) 돌고 두 번째 치명', async () => {
    fakeClock();
    const player = vodDocumentGone('variant');
    await vi.advanceTimersByTimeAsync(AFTER_FATAL_MS);
    expect(player.appStartLoads()).toBe(1);
    expect(player.requests).toEqual([
      { url: VOD_MULTIVARIANT_URL, atMs: 0 },
      { url: VOD_VARIANT_URL, atMs: 0 },
      { url: VOD_VARIANT_URL, atMs: 1_000 },
      { url: VOD_VARIANT_URL, atMs: 3_000 },
      { url: VOD_VARIANT_URL, atMs: 3_000 },
      { url: VOD_VARIANT_URL, atMs: 4_000 },
      { url: VOD_VARIANT_URL, atMs: 6_000 },
    ]);
    expect(player.errors.map((data) => [data.details, data.fatal, data.response?.code])).toEqual([
      [ErrorDetails.LEVEL_LOAD_ERROR, false, 404],
      [ErrorDetails.LEVEL_LOAD_ERROR, false, 404],
      [ErrorDetails.LEVEL_LOAD_ERROR, true, 404],
      [ErrorDetails.LEVEL_LOAD_ERROR, false, 404],
      [ErrorDetails.LEVEL_LOAD_ERROR, false, 404],
      [ErrorDetails.LEVEL_LOAD_ERROR, true, 404],
    ]);
  });
});
