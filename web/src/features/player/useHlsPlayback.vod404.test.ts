import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type * as hlsErrorsModule from './hlsErrors';
import { consumeDvrSegmentGone } from './hlsErrors';
import { recordingTimeline } from './recordingTimeline';
import type { PlaybackSource } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';
import { useRecordedPlayback } from './useRecordedPlayback';

// 다시보기(VOD) 조각 404 의 앱 층(POK-528 c6 · W8 (ㄱ) · 계획 8-4 훅 층). kty 결정(2026-11-03)대로 지금 동작을 고정한다:
// hls.js 가 기본 재시도(비치명 6번 — 실물 층은 hlsVodRetry.hlsjs.test.ts)를 다 쓰고 치명으로 올리면 앱이 한 번 다시
// 받고(startLoad), 그 뒤 또 치명이면 끊김 알림으로 재생기를 내린다(useHlsPlayback.ts 778-794). 되감기 조각 404 가로채기
// (c5)는 kind 'rewind' 에서만 돈다 — 고리를 달아 둔 vod 재생기라도 가로채기 판정(consumeDvrSegmentGone) 자체를 부르지 않는다.
//
// K6 — 녹화 재생(지난 방송 상세 · useRecordedPlayback)은 W8 대상이 아니다. hls.js 재생기가 아니라 녹화 재생 서버의 mp4
// 줄기를 video.src 로 붙인다(useRecordedPlayback.ts 147-165 — recordingClipUrl · `video.src = src`). 그래서 hls.js 조각
// 재시도도, 세션 고리(onDvrSegmentGone)도, 재생기 kind 도 없다. 아래 마지막 시험이 그 사실(hls.js 생성 0)을 단언한다.
//
// hls.js 흉내는 useHlsPlayback.dvr404.test.ts 꼴이다(처음부터 off() 를 둔다). 다만 latestLevelDetails 는 목록 사건
// (LEVEL_LOADED · LEVEL_UPDATED)에서만 갈아 끼운다 — ERROR 자료의 details(문자열)로 덮지 않는다(c5 검수 이연 M).

const spy = vi.hoisted(() => ({ instances: [] as FakeHls[] }));

// 가로채기 판정이 불렸는지 보려고 감싼다 — 동작은 원본 그대로다
vi.mock('./hlsErrors', async (importOriginal) => {
  const actual = await importOriginal<typeof hlsErrorsModule>();
  return { ...actual, consumeDvrSegmentGone: vi.fn(actual.consumeDvrSegmentGone) };
});

vi.mock('hls.js', () => ({ default: FakeHls }));

type Handler = (event: string, data: unknown) => void;

class FakeHls {
  static isSupported = () => true;
  static Events = {
    MANIFEST_PARSED: 'hlsManifestParsed',
    LEVEL_LOADED: 'hlsLevelLoaded',
    LEVEL_UPDATED: 'hlsLevelUpdated',
    FRAG_LOADED: 'hlsFragLoaded',
    ERROR: 'hlsError',
  };
  static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' };
  static ErrorDetails = {
    FRAG_LOAD_ERROR: 'fragLoadError',
    INTERNAL_ABORTED: 'aborted',
    MANIFEST_LOAD_ERROR: 'manifestLoadError',
    LEVEL_LOAD_ERROR: 'levelLoadError',
    AUDIO_TRACK_LOAD_ERROR: 'audioTrackLoadError',
  };
  /** 목록 사건이 실어 온 목록 — 이 시험은 목록을 쏘지 않아 늘 null 이다 */
  latestLevelDetails: unknown = null;
  liveSyncPosition: number | null = null;
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
    for (const h of this.handlers.get(event) ?? []) h(event, data);
  }
  attachMedia() {}
}

const VOD_URL = 'https://m/vod/k/a1/index.m3u8';
const REWIND_URL = 'https://m/dvr/k/a1/index.m3u8';
const MEDIA_SEGMENT = 'https://m/dvr/k/a1/seg7.mp4';
const INIT_SEGMENT = 'https://m/dvr/k/a1/init.mp4';

/** 끊김 알림(useHlsPlayback.ts STOPPED_NOTES.broken) */
const BROKEN_NOTE = '영상이 끊겼어요. 새로고침하면 다시 연결해요';

/** hls.js 기본 조각 재시도 수(Hls.DefaultConfig.fragLoadPolicy.default.errorRetry.maxNumRetry — 실물 층이 고정) */
const DEFAULT_FRAG_RETRIES = 6;

function sourceOf(kind: PlaybackSource['kind'], generation: number): PlaybackSource {
  const url = kind === 'vod' ? VOD_URL : REWIND_URL;
  return { kind, url, startAtMs: null, generation, strictStart: false };
}

/** 재생기를 세우고 hls.js 읽기를 기다린다 */
async function mount(source: PlaybackSource, hooks: PlaybackHooks) {
  const ref = { current: document.createElement('video') };
  const view = renderHook(() =>
    useHlsPlayback(ref, source, {}, undefined, undefined, false, undefined, hooks),
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
    frag: { sn: 7, initSegment: { url: INIT_SEGMENT } },
    response: { url: MEDIA_SEGMENT, code: 404, text: '' },
  };
}

/** /vod 문서(멀티변이 · 변이 목록) 받기 404 — 목록 오류는 hls.js 가 다시 받지 않고 곧바로 치명이다(RC-16) */
function documentGone(details: string): unknown {
  return {
    type: FakeHls.ErrorTypes.NETWORK_ERROR,
    details,
    fatal: true,
    response: { url: VOD_URL, code: 404, text: '' },
  };
}

function raise(hls: FakeHls, data: unknown) {
  act(() => hls.trigger(FakeHls.Events.ERROR, data));
}

/** hls.js 기본 재시도 한 바퀴 — 비치명 6번 뒤 치명 하나 */
function raiseDefaultRound(hls: FakeHls) {
  for (let i = 0; i < DEFAULT_FRAG_RETRIES; i += 1) raise(hls, fragGone(false));
  raise(hls, fragGone(true));
}

beforeEach(() => {
  spy.instances.length = 0;
  vi.mocked(consumeDvrSegmentGone).mockClear();
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
    for (let i = 0; i < DEFAULT_FRAG_RETRIES; i += 1) raise(hls, fragGone(false));
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
    expect(onDvrSegmentGone.mock.calls).toEqual([[3]]);
    expect(hls.stopLoad).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['멀티변이 목록', FakeHls.ErrorDetails.MANIFEST_LOAD_ERROR],
    ['변이 목록', FakeHls.ErrorDetails.LEVEL_LOAD_ERROR],
  ])(
    '/vod 문서(%s) 404 는 콘텐츠 없음 그대로 — 고리 0 · 라이브 닫힘 0 · startLoad 1 → 다음 치명에 끊김',
    async (_name, details) => {
      const onDvrSegmentGone = vi.fn();
      const onLiveClosed = vi.fn();
      const { result, hls } = await mount(sourceOf('vod', 3), { onDvrSegmentGone, onLiveClosed });
      raise(hls, documentGone(details));
      expect(hls.startLoad).toHaveBeenCalledTimes(1);
      expect(result.current.playbackError).toBeNull();
      raise(hls, documentGone(details));
      expect(onDvrSegmentGone).not.toHaveBeenCalled();
      expect(onLiveClosed).not.toHaveBeenCalled();
      expect(consumeDvrSegmentGone).not.toHaveBeenCalled();
      expect(hls.startLoad).toHaveBeenCalledTimes(1);
      expect(hls.destroy).toHaveBeenCalledTimes(1);
      expect(result.current.playbackError).toBe(BROKEN_NOTE);
    },
  );
});

describe('K6 — 녹화 재생(useRecordedPlayback)은 W8 대상이 아니다', () => {
  it('hls.js 재생기를 세우지 않고 녹화 재생 서버의 mp4 줄기를 video.src 로 붙인다', async () => {
    const startMs = Date.parse('2026-11-03T01:00:00Z');
    const timeline = recordingTimeline([{ startMs, durationSeconds: 60 }], null, null);
    if (timeline === null) throw new Error('녹화 줄기표가 서지 않았다');
    const video = document.createElement('video');
    renderHook(() => useRecordedPlayback({ current: video }, { streamId: 'k6', ...timeline }));
    // hls.js 는 동적 import 로 읽힌다(useHlsPlayback) — 읽기가 끝난 뒤에 센다
    await act(async () => {
      await vi.dynamicImportSettled();
    });
    expect(spy.instances).toHaveLength(0);
    expect(new URL(video.src).searchParams.get('format')).toBe('mp4');
  });
});
