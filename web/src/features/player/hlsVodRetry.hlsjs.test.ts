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
// 수를 0 으로 되돌려(level-controller.ts 82-89) 앱의 다시 받기(useHlsPlayback.ts 778-783) 뒤에 같은 꼴이 한 번 더 돈다.
// 그래서 감춤은 약 31초 × 2 다. 훅 층(앱이 치명에서 startLoad 한 번 · 두 번째 치명에서 끊김)은
// useHlsPlayback.vod404.test.ts 가 본다.

const quiet = () => {};
const SILENT = { trace: quiet, debug: quiet, log: quiet, warn: quiet, info: quiet, error: quiet };

const PLAYLIST_URL = 'https://m/dvr/k/a/index.m3u8';

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
  hls: Hls;
  streamController: StreamControllerInternals;
  /** 조각 받기마다 그때의 performance.now()(재시도 시각 비교에 hls.js 가 쓰는 시계 — checkRetryDate) */
  loadTimes: number[];
  /** 앱 처리기가 본 오류 */
  errors: ErrorData[];
}

const instances: Hls[] = [];

afterEach(() => {
  for (const hls of instances.splice(0)) hls.destroy();
  vi.useRealTimers();
});

/** tick · 재시도 시각(performance.now)을 함께 미는 가짜 시계 */
function fakeClock(): void {
  vi.useFakeTimers({
    toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance', 'Date'],
  });
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
 * 다시보기 재생기 꼴 — 앱의 vod 재생기 설정(HLS_REWIND_CONFIG · useHlsPlayback.ts 657) · 변이 하나 · ENDLIST 목록(조각 둘)
 * · 미디어가 붙은 채 받기 직전(IDLE). 조각 받기는 진짜 FragmentLoader 가 하고, 그 아래 망만 가짜 fLoader 가 늘 404 로 끝낸다.
 * hls.js 안쪽 칸은 hlsErrors.hlsjs.test.ts 의 되감기 재생기 꼴과 같이 채운다(목록 받기를 건너뛰려고)
 */
function vodPlayerAlways404(): VodPlayer {
  const loadTimes: number[] = [];
  class NotFoundLoader {
    stats = new LoadStats();
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
    // 시험 전용 망 — 제품의 「커스텀 loader 금지」(useHlsPlayback.ts 760)와 무관하다
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
    await vi.advanceTimersByTimeAsync(ROUND_MS + AFTER_FATAL_MS);
    expect(player.loadTimes).toHaveLength(LOADS_PER_ROUND);
    // 앱의 치명 처리와 같은 부름(useHlsPlayback.ts 778-783 — 위치 없이 startLoad)
    player.hls.startLoad();
    await vi.advanceTimersByTimeAsync(ROUND_MS + AFTER_FATAL_MS);
    const second = player.loadTimes.slice(LOADS_PER_ROUND);
    expect(second).toHaveLength(LOADS_PER_ROUND);
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
