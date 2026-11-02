import Hls, { ErrorDetails, ErrorTypes, Events, type ErrorData } from 'hls.js';
import { afterEach, describe, expect, it } from 'vitest';
import { HLS_LIVE_CONFIG } from './hlsConfig';
import { isPlaylistLoadError } from './hlsErrors';

// 진짜 hls.js 1.7.3(dist)의 오류 처리가 받기 오류 404 를 언제 치명으로 올리는지와, 앱이 그 오류를 목록 오류로 가르는지를
// 함께 본다(cx H1). error-helper retryForHttpStatus 는 4xx 를 다시 받지 않지만 조각 오류는 stream-controller
// onFragmentOrKeyLoadError 가 따로 다시 받는다: 갈아탈 변이가 없는 라이브의 마지막 조각은 fragLoadPolicy 한도(기본 6회)까지
// 비치명으로 다시 받고 그 뒤 치명으로 올린다(앞 조각은 틈으로 넘긴다). 변이가 아예 없으면 처음부터 치명이다(error-controller
// onErrorOut). 오류 처리기는 생성 때 등록돼(hls.ts 354-356) 앱 처리기보다 먼저 돈다

const quiet = () => {};
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
