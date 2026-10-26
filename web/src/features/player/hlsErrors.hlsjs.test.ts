import Hls, { ErrorDetails, ErrorTypes, Events, type ErrorData } from 'hls.js';
import { afterEach, describe, expect, it } from 'vitest';
import { HLS_LIVE_CONFIG } from './hlsConfig';
import { isPlaylistLoadError } from './hlsErrors';

// 진짜 hls.js 1.7.3(dist)의 오류 처리(error-controller)가 받기 오류 404 를 치명으로 올리는지와, 앱이 그 오류를 목록 오류로
// 가르는지를 함께 본다(cx H1). hls.js 는 4xx 를 다시 받지 않고(error-helper retryForHttpStatus) 갈아탈 변이가 없으면 치명으로
// 올린다(error-controller onErrorOut). 그 처리기는 생성 때 등록돼(hls.ts 354-356) 앱 처리기보다 먼저 돈다

const quiet = () => {};
const SILENT = { trace: quiet, debug: quiet, log: quiet, warn: quiet, info: quiet, error: quiet };

const instances: Hls[] = [];
afterEach(() => {
  for (const hls of instances.splice(0)) hls.destroy();
});

/** 변이 하나짜리 라이브 재생기에 받기 오류(치명 아님) 하나를 쏘고 앱 처리기가 본 것을 돌려준다 */
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

describe('hls.js 1.7.3 의 받기 오류 404 와 목록 오류 가르기(cx H1)', () => {
  it('조각 404 도 치명으로 올라온다 — 그러나 목록 오류가 아니다(라이브 닫힘이 아니다)', () => {
    const seen = raise(ErrorDetails.FRAG_LOAD_ERROR, 404);
    expect(seen.fatal).toBe(true);
    expect(isPlaylistLoadError(seen, ErrorDetails)).toBe(false);
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
