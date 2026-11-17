import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type * as playbackAccessModule from '@/api/playbackAccess';
import { reportPlaybackForbidden } from '@/api/playbackAccess';
import type * as hlsErrorsModule from './hlsErrors';
import { consumeDvrSegmentGone } from './hlsErrors';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';

// 다시보기(VOD) 조각 404 의 앱 층(POK-528 c6 · W8 (ㄱ) · 계획 8-4 훅 층). kty 결정(2026-11-03)대로 지금 동작을 고정한다:
// hls.js 가 기본 재시도(비치명 6번 — 실물 층은 hlsVodRetry.hlsjs.test.ts)를 다 쓰고 치명으로 올리면 앱이 한 번 다시
// 받고(startLoad), 그 뒤 또 치명이면 끊김 알림으로 재생기를 내린다(useHlsPlayback ERROR 처리기의 한 번 다시 받기 · 끊김).
// /vod 문서 404 는 갈래가 나뉜다 — 변이 목록은 같은 길이고, 첫 목록(멀티변이 목록)은 앱이 다시 받아도 hls.js 가 다시 청하지
// 않으므로 곧바로 끊김을 알린다(POK-528 c8 — c6 X1 에서 고정한 「알림 없이 멈춤」 을 kty 의견 「걍 새로고침하면
// 되는거아닌가」 대로 바꿈). 되감기 조각 404 가로채기(c5)는 kind 'rewind' 에서만 돈다 — 고리를 달아 둔 vod 재생기라도 가로채기
// 판정(consumeDvrSegmentGone) 자체를 부르지 않는다.
//
// hls.js 흉내는 useHlsPlayback.dvr404.test.ts 꼴이다(처음부터 off() 를 둔다). 다만 latestLevelDetails 는 목록 사건
// (LEVEL_LOADED · LEVEL_UPDATED)에서만 갈아 끼운다 — ERROR 자료의 details(문자열)로 덮지 않는다(c5 검수 이연 M).

const spy = vi.hoisted(() => ({ instances: [] as FakeHls[] }));

// 가로채기 판정이 불렸는지 보려고 감싼다 — 동작은 원본 그대로다
vi.mock('./hlsErrors', async (importOriginal) => {
  const actual = await importOriginal<typeof hlsErrorsModule>();
  return { ...actual, consumeDvrSegmentGone: vi.fn(actual.consumeDvrSegmentGone) };
});

// 403 갈래가 출입증을 버렸는지 보려고 감싼다 — 동작은 원본 그대로다
vi.mock('@/api/playbackAccess', async (importOriginal) => {
  const actual = await importOriginal<typeof playbackAccessModule>();
  return { ...actual, reportPlaybackForbidden: vi.fn(actual.reportPlaybackForbidden) };
});

vi.mock('hls.js', () => ({ default: FakeHls }));

type Handler = (event: string, data: unknown) => void;

class FakeHls {
  static readonly isSupported = () => true;
  static readonly Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_LOADED: 'hlsLevelLoaded',
    LEVEL_UPDATED: 'hlsLevelUpdated',
    FRAG_LOADED: 'hlsFragLoaded',
    ERROR: 'hlsError',
  };
  static readonly ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  static readonly ErrorDetails = {
    FRAG_LOAD_ERROR: 'fragLoadError',
    INTERNAL_ABORTED: 'aborted',
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    MANIFEST_LOAD_TIMEOUT: 'manifestLoadTimeOut',
    MANIFEST_PARSING_ERROR: 'manifestParsingError',
    LEVEL_LOAD_ERROR: 'levelLoadError',
    AUDIO_TRACK_LOAD_ERROR: 'audioTrackLoadError',
  };
  /** 목록 사건이 실어 온 목록 — 이 시험은 목록을 쏘지 않아 늘 null 이다 */
  latestLevelDetails: unknown = null;
  readonly liveSyncPosition: number | null = null;
  readonly handlers = new Map<string, Handler[]>();
  readonly startLoad = vi.fn();
  readonly stopLoad = vi.fn();
  readonly destroy = vi.fn();
  readonly loadSource = vi.fn();
  constructor(readonly config: { autoStartLoad?: boolean }) {
    spy.instances.push(this);
  }
  on(event: string, handler: Handler) {
    this.handlers.set(event, [...(this.handlers.get(event) ?? []), handler]);
  }
  off() {}
  once(event: string, handler: Handler) {
    const wrapped: Handler = (e, data) => {
      this.handlers.set(
        event,
        (this.handlers.get(event) ?? []).filter((h) => h !== wrapped),
      );
      handler(e, data);
    };
    this.on(event, wrapped);
  }
  trigger(event: string, data: unknown) {
    if (event === FakeHls.Events.LEVEL_LOADED || event === FakeHls.Events.LEVEL_UPDATED) {
      this.latestLevelDetails = (data as { details: unknown }).details;
    }
    for (const h of this.handlers.get(event) ?? []) {
      h(event, data);
    }
  }
  attachMedia() {}
}

const VOD_URL = 'https://m/vod/k/a1/index.m3u8';
const REWIND_URL = 'https://m/dvr/k/a1/index.m3u8';
const LIVE_URL = 'https://m/live/k/index.m3u8';
// 조각 url 의 경로(/dvr · /vod)는 결과와 무관하다 — 판별이 url 로 보는 것은 실패한 파일이 그 조각의 init 인가뿐이다
// (isMediaSegmentNotFound). 그래서 vod · rewind 행이 같은 꼴을 쓴다
const MEDIA_SEGMENT = 'https://m/dvr/k/a1/seg7.mp4';
const INIT_SEGMENT = 'https://m/dvr/k/a1/init.mp4';

/** 끊김 알림(useHlsPlayback.ts STOPPED_NOTES.broken) */
const BROKEN_NOTE = '영상이 끊겼어요. 새로고침하면 다시 연결해요';

/** 출입증을 버리고 다시 받는 중이라는 알림(useHlsPlayback.ts STOPPED_NOTES.forbidden) */
const FORBIDDEN_NOTE = '영상 출입증이 끝나 다시 받고 있어요';

/** 영상 키(출입증 쿠키의 경로) — 403 갈래는 이 키가 있을 때만 선다 */
const MEDIA_KEY = 'k/a1';

/** hls.js 기본 조각 재시도 수(Hls.DefaultConfig.fragLoadPolicy.default.errorRetry.maxNumRetry — 실물 층이 고정) */
const DEFAULT_FRAG_RETRIES = 6;

function sourceOf(kind: PlaybackSource['kind'], generation: number): PlaybackSource {
  const url = { vod: VOD_URL, rewind: REWIND_URL, live: LIVE_URL }[kind];
  return { kind, url, startAtMs: null, generation, strictStart: false };
}

/**
 * 재생기를 세우고 hls.js 읽기를 기다린다
 * @param mediaKey 영상 키. 없으면 403 갈래가 서지 않는다
 */
async function mount(source: PlaybackSource, hooks: PlaybackHooks, mediaKey?: string) {
  const ref = { current: document.createElement('video') };
  const view = renderHook(() =>
    useHlsPlayback(ref, source, {}, undefined, mediaKey, false, undefined, hooks),
  );
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  return { result: view.result, hls: lastHls() };
}

function lastHls(): FakeHls {
  const h = spy.instances[spy.instances.length - 1];
  if (h === undefined) throw new Error('재생기가 서지 않았다');
  return h;
}

/** hls.js 가 앱에 올리는 미디어 조각 받기 404 꼴(fragment-loader 거절 그대로) */
function fragGone(fatal: boolean): unknown {
  return {
    type: FakeHls.ErrorTypes.NETWORK_ERROR,
    details: FakeHls.ErrorDetails.FRAG_LOAD_ERROR,
    fatal,
    frag: { type: 'main', sn: 7, initSegment: { url: INIT_SEGMENT } },
    response: { url: MEDIA_SEGMENT, code: 404, text: '' },
  };
}

/** /vod 문서(멀티변이 · 변이 목록) 받기 404 — 치명 여부는 진짜 hls.js 의 순서(hlsVodRetry.hlsjs.test.ts 맨 아래 묶음)를 따른다 */
function documentGone(details: string, fatal: boolean): unknown {
  return {
    type: FakeHls.ErrorTypes.NETWORK_ERROR,
    details,
    fatal,
    response: { url: VOD_URL, code: 404, text: '' },
  };
}

/**
 * 첫 목록 받기가 치명으로 끝난 꼴 — hls.js playlist-loader 가 MANIFEST 문맥에서 올린다(loader/playlist-loader.ts 611-615 ·
 * 653-665). 시한에는 응답이 없다
 * @param code 응답 코드. null 이면 응답 없음(시한)
 */
function firstListFailed(details: string, code: number | null, url: string): unknown {
  return {
    type: FakeHls.ErrorTypes.NETWORK_ERROR,
    details,
    fatal: true,
    response: code === null ? undefined : { url, code, text: '' },
  };
}

/** 재생기가 세션에 올리는 고리 전부 — 하나도 불리지 않았는지 본다 */
function sessionHooks() {
  return {
    onEndlist: vi.fn(),
    onStartTruncated: vi.fn(),
    onEndReached: vi.fn(),
    onLiveClosed: vi.fn(),
    onLive400: vi.fn(),
    onListLoaded: vi.fn(),
    onPlaying: vi.fn(),
    onSeekRequest: vi.fn(),
    onLiveButton: vi.fn(),
    onNotice: vi.fn(),
    onDvrSegmentGone: vi.fn(),
  };
}

function calledHooks(hooks: ReturnType<typeof sessionHooks>): string[] {
  return Object.entries(hooks)
    .filter(([, hook]) => hook.mock.calls.length > 0)
    .map(([name]) => name);
}

function raise(hls: FakeHls, data: unknown) {
  act(() => hls.trigger(FakeHls.Events.ERROR, data));
}

/** hls.js 기본 재시도 한 바퀴 — 비치명 6번 뒤 치명 하나 */
function raiseDefaultRound(hls: FakeHls) {
  for (let i = 0; i < DEFAULT_FRAG_RETRIES; i += 1) {
    raise(hls, fragGone(false));
  }
  raise(hls, fragGone(true));
}

beforeEach(() => {
  spy.instances.length = 0;
  vi.mocked(consumeDvrSegmentGone).mockClear();
  vi.mocked(reportPlaybackForbidden).mockClear();
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'readyState', 'get').mockImplementation(() => 0);
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('다시보기 조각 404 의 앱 한 번 다시 받기(POK-528 c6 · W8 (ㄱ) · 계획 8-4 훅 층)', () => {
  it('vod + 고리 — 비치명 6번엔 앱이 손대지 않고, 첫 치명에 startLoad 1 · 고리 0 · 끊김 0', async () => {
    const onDvrSegmentGone = vi.fn();
    const { result, hls } = await mount(sourceOf('vod', 3), { onDvrSegmentGone });
    for (let i = 0; i < DEFAULT_FRAG_RETRIES; i += 1) {
      raise(hls, fragGone(false));
    }
    expect(hls.startLoad).not.toHaveBeenCalled();
    expect(hls.stopLoad).not.toHaveBeenCalled();
    raise(hls, fragGone(true));
    expect(hls.startLoad.mock.calls).toEqual([[]]);
    expect(onDvrSegmentGone).not.toHaveBeenCalled();
    expect(hls.stopLoad).not.toHaveBeenCalled();
    expect(hls.destroy).not.toHaveBeenCalled();
    expect(result.current.playbackError).toBeNull();
  });

  it('다시 받은 뒤 또 한 바퀴를 다 쓰고 치명이면 끊김 — startLoad 는 1 그대로 · destroy 1', async () => {
    const onDvrSegmentGone = vi.fn();
    const { result, hls } = await mount(sourceOf('vod', 3), { onDvrSegmentGone });
    raiseDefaultRound(hls);
    raiseDefaultRound(hls);
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    expect(hls.destroy).toHaveBeenCalledTimes(1);
    expect(onDvrSegmentGone).not.toHaveBeenCalled();
    expect(result.current.playbackError).toBe(BROKEN_NOTE);
  });

  it('vod 재생기는 고리가 있어도 가로채기 판정(consumeDvrSegmentGone)을 부르지 않는다', async () => {
    const { hls } = await mount(sourceOf('vod', 3), { onDvrSegmentGone: vi.fn() });
    raiseDefaultRound(hls);
    expect(consumeDvrSegmentGone).not.toHaveBeenCalled();
  });

  it('대조 — 같은 차림의 rewind 재생기는 첫 404 에서 판정을 부르고 가로챈다(감싼 판정이 살아 있다)', async () => {
    const onDvrSegmentGone = vi.fn();
    const { hls } = await mount(sourceOf('rewind', 3), { onDvrSegmentGone });
    raise(hls, fragGone(false));
    expect(consumeDvrSegmentGone).toHaveBeenCalledTimes(1);
    expect(onDvrSegmentGone.mock.calls).toEqual([[3, { type: 'main', sn: 7 }]]);
    expect(hls.stopLoad).toHaveBeenCalledTimes(1);
  });

  // 멀티변이 목록을 못 받으면 hls.js 에 변이가 없어 앱의 startLoad 가 아무것도 다시 청하지 않는다 — playlist-loader 의
  // startLoad 는 빈 몸통(hls.js src/loader/playlist-loader.ts 86) · stream-controller 는 levels 가 없으면 STOPPED 로만
  // 둔다(src/controller/stream-controller.ts 191-193). 실물 층이 「요청 1 · 두 번째 치명 없음」 을 본다. 그래서 앱은 다시
  // 받지 않고 첫 치명에서 곧바로 끊김을 알린다(POK-528 c8 — 136d1dd1 이 고정한 「알림 없이 멈춤」 을 뒤집음). 세션에 올리는
  // 고리는 없다(kind 'live' 만 목록 404 를 라이브 닫힘으로 올린다)
  it('/vod 멀티변이 목록 404 — 첫 치명에 곧바로 끊김 · startLoad 0 · destroy 1 · 세션 고리 0', async () => {
    const hooks = sessionHooks();
    const { result, hls } = await mount(sourceOf('vod', 3), hooks);
    raise(hls, documentGone(FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR, true));
    expect(result.current.playbackError).toBe(BROKEN_NOTE);
    expect(hls.startLoad).not.toHaveBeenCalled();
    expect(hls.destroy).toHaveBeenCalledTimes(1);
    expect(calledHooks(hooks)).toEqual([]);
    expect(consumeDvrSegmentGone).not.toHaveBeenCalled();
  });

  // 변이 목록 404 는 진짜 hls.js 가 목록 정책으로 두 번 다시 받은 뒤 치명이고, 앱의 startLoad 뒤 한 바퀴 더 돌아 두 번째
  // 치명이 온다(실물 층 「변이 목록 404」)
  it('/vod 변이 목록 404 — 비치명 2 · 치명 → startLoad 1 → 한 바퀴 더 뒤 치명에 끊김 · destroy 1 · 세션 고리 0', async () => {
    const hooks = sessionHooks();
    const { result, hls } = await mount(sourceOf('vod', 3), hooks);
    const round = () => {
      raise(hls, documentGone(FakeHls.ErrorDetails.LEVEL_LOAD_ERROR, false));
      raise(hls, documentGone(FakeHls.ErrorDetails.LEVEL_LOAD_ERROR, false));
      raise(hls, documentGone(FakeHls.ErrorDetails.LEVEL_LOAD_ERROR, true));
    };
    round();
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    expect(result.current.playbackError).toBeNull();
    round();
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    expect(hls.destroy).toHaveBeenCalledTimes(1);
    expect(result.current.playbackError).toBe(BROKEN_NOTE);
    expect(calledHooks(hooks)).toEqual([]);
    expect(consumeDvrSegmentGone).not.toHaveBeenCalled();
  });
});

/** 첫 목록 치명의 404 밖 꼴(계획 0-2 의 2 — 응답 코드는 보지 않는다) · 404 행은 위 시험이 따로 본다 */
const FIRST_LIST_FAILURES: Array<[string, string, number | null]> = [
  ['503(hls.js 가 1초 뒤 한 번 다시 받은 뒤 치명)', FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR, 503],
  ['상태 0', FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR, 0],
  ['시한(hls.js 가 두 번 다시 받은 뒤 치명)', FakeHls.ErrorDetails.MANIFEST_LOAD_TIMEOUT, null],
  ['파싱 오류', FakeHls.ErrorDetails.MANIFEST_PARSING_ERROR, 200],
];

describe('다시보기 · 되감기 첫 목록 치명 → 곧바로 끊김(POK-528 c8 · 계획 4-2 · 8-4)', () => {
  it.each(FIRST_LIST_FAILURES)(
    'vod 첫 목록 %s → 끊김 · startLoad 0 · destroy 1 · 세션 고리 0',
    async (_name, details, code) => {
      const hooks = sessionHooks();
      const { result, hls } = await mount(sourceOf('vod', 3), hooks);
      raise(hls, firstListFailed(details, code, VOD_URL));
      expect(result.current.playbackError).toBe(BROKEN_NOTE);
      expect(hls.startLoad).not.toHaveBeenCalled();
      expect(hls.destroy).toHaveBeenCalledTimes(1);
      expect(calledHooks(hooks)).toEqual([]);
    },
  );

  // 첫 목록 403 도 MANIFEST_LOAD_ERROR 치명이라 isFirstListFailure 가 참이다. 그래도 403 갈래(출입증 버리기)가 이 갈래보다
  // 앞에 서야 출입증을 다시 받는다 — 순서를 바꾸면 403 이 끊김으로 떨어지고 출입증을 버리지 않는다(M8f)
  it('vod 첫 목록 403(영상 키 있음) → 403 갈래가 먼저 — 출입증 버리기 1 · 끊김 아님 · startLoad 0 · destroy 1', async () => {
    const hooks = sessionHooks();
    const { result, hls } = await mount(sourceOf('vod', 3), hooks, MEDIA_KEY);
    raise(hls, firstListFailed(FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR, 403, VOD_URL));
    expect(vi.mocked(reportPlaybackForbidden).mock.calls).toEqual([[MEDIA_KEY]]);
    expect(result.current.playbackError).toBe(FORBIDDEN_NOTE);
    expect(hls.startLoad).not.toHaveBeenCalled();
    expect(hls.destroy).toHaveBeenCalledTimes(1);
    expect(calledHooks(hooks)).toEqual([]);
  });

  // 다시 받는 중이 아닌 되감기의 조각 404 뒤 세션은 같은 url 로 세대를 올려 재생기를 새로 세운다(목록 다시 받기 —
  // 계획 1절 1). 그 새 재생기의 첫 목록이 치명이면 막힘이 아니라 끊김이다(계획 0-2 의 3 — 자동 회복 없음). 세션
  // 층(relist 'list' 유지)은 c7 뒤 몫이다
  it.each<[string, string, number | null]>([
    ['404', FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR, 404],
    ['503', FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR, 503],
    ['시한', FakeHls.ErrorDetails.MANIFEST_LOAD_TIMEOUT, null],
  ])(
    'rewind 다시 받기(같은 url · 세대 + 1)의 첫 목록 %s → 끊김 · startLoad 0 · destroy 1 · 세션 고리 0',
    async (_name, details, code) => {
      const hooks = sessionHooks();
      const ref = { current: document.createElement('video') };
      const first: PlaybackSource = { ...sourceOf('rewind', 3), startAtMs: 1_000 };
      const view = renderHook(
        ({ source }) =>
          useHlsPlayback(ref, source, {}, undefined, undefined, false, undefined, hooks),
        { initialProps: { source: first } },
      );
      await act(async () => {
        await vi.dynamicImportSettled();
      });
      const before = lastHls();
      view.rerender({ source: { ...first, generation: 4 } });
      await act(async () => {
        await vi.dynamicImportSettled();
      });
      const hls = lastHls();
      expect(hls).not.toBe(before);
      expect(hls.loadSource.mock.calls).toEqual([[REWIND_URL]]);
      raise(hls, firstListFailed(details, code, REWIND_URL));
      expect(view.result.current.playbackError).toBe(BROKEN_NOTE);
      expect(hls.startLoad).not.toHaveBeenCalled();
      expect(hls.destroy).toHaveBeenCalledTimes(1);
      expect(calledHooks(hooks)).toEqual([]);
    },
  );

  it('대조 — live 첫 목록 404 는 앞 갈래가 라이브 닫힘으로 올린다(끊김 0 · 회귀용)', async () => {
    const hooks = sessionHooks();
    const { result, hls } = await mount(sourceOf('live', 3), hooks);
    raise(hls, firstListFailed(FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR, 404, LIVE_URL));
    expect(hooks.onLiveClosed).toHaveBeenCalledTimes(1);
    expect(result.current.playbackError).toBeNull();
    expect(hls.destroy).toHaveBeenCalledTimes(1);
  });

  // 라이브의 404 · 400 밖 첫 목록 치명은 이 갈래에 닿지만 넣지 않는다 — 지금 동작(한 번 다시 받기 · 알림 없는 멈춤)을
  // 고정한다(범위 밖 관찰 · 계획 12절). kind 조건을 빼면 이 대조가 깨진다(M8c)
  it.each<[string, string, number | null]>([
    ['503', FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR, 503],
    ['시한', FakeHls.ErrorDetails.MANIFEST_LOAD_TIMEOUT, null],
  ])(
    '대조 — live 첫 목록 %s 는 지금 동작 그대로 — 끊김 0 · startLoad 1',
    async (_name, details, code) => {
      const hooks = sessionHooks();
      const { result, hls } = await mount(sourceOf('live', 3), hooks);
      raise(hls, firstListFailed(details, code, LIVE_URL));
      expect(result.current.playbackError).toBeNull();
      expect(hls.startLoad.mock.calls).toEqual([[]]);
      expect(hls.destroy).not.toHaveBeenCalled();
    },
  );
});
