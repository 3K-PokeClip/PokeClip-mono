import Hls, { Events, M3U8Parser, PlaylistLevelType, type LevelDetails } from 'hls.js';
import { afterEach, describe, expect, it } from 'vitest';
import { HLS_REWIND_CONFIG } from './hlsConfig';
import { guardRewindFloor } from './rewindFloor';

// 진짜 hls.js 1.7.3(dist)의 streamController 로 목록 갱신 하나를 돌린다(진단 28_ 재현 9 · 10 — 설계 5.3.1절). 목록은 진짜
// 파서로 읽고, <video> 와 라이브 끝(liveSyncPosition)만 흉내 낸다. 고정하는 것은 둘이다: hls.js 는 LEVEL_UPDATED 를
// synchronizeToLiveEdge 보다 먼저 동기로 내보낸다 · 그 처리기에서 재생점을 창 하한으로 옮기면 hls.js 는 옮기지 않는다.
// hls.js 판을 올려 이 순서가 바뀌면 여기서 먼저 깨진다.

const URL = 'https://m/dvr/k/a/index.m3u8';
const PDT0 = Date.parse('2026-10-25T05:00:00.000Z');
const TD = 4;
/** 흉내 라이브 끝(목록 끝 − 3 TD 근처) — hls.js 가 창 밖 재생점을 옮기는 자리 */
const LIVE_SYNC = 40;

/** 미디어 순번 from 부터 4초 조각 n 개 · ENDLIST 없음(미닫이 live 목록) */
function playlist(from: number, n: number): LevelDetails {
  const lines = [
    '#EXTM3U',
    '#EXT-X-VERSION:9',
    `#EXT-X-TARGETDURATION:${TD}`,
    `#EXT-X-MEDIA-SEQUENCE:${from}`,
  ];
  for (let i = from; i < from + n; i++) {
    lines.push(`#EXT-X-PROGRAM-DATE-TIME:${new Date(PDT0 + i * TD * 1000).toISOString()}`);
    lines.push(`#EXTINF:${TD}.000,`, `seg${i}.mp4`);
  }
  return M3U8Parser.parseLevelPlaylist(lines.join('\n'), URL, 0, PlaylistLevelType.MAIN, 0, null);
}

// debug 가 켜져 있으면 hls.js 가 처리기 오류를 삼키지 않는다(hls.ts trigger) — 흉내가 모자라 내부 처리기가 던지면 그대로
// 드러나게 켜되, 기록은 버린다
const quiet = () => {};
const SILENT = { trace: quiet, debug: quiet, log: quiet, warn: quiet, info: quiet, error: quiet };

/** currentTime 쓰기를 세는 흉내 <video> */
function fakeMedia(currentTime: number, readyState: number) {
  const writes: number[] = [];
  let time = currentTime;
  const media = {
    readyState,
    duration: Infinity,
    paused: false,
    seeking: false,
    buffered: { length: 0, start: () => 0, end: () => 0 },
    get currentTime() {
      return time;
    },
    set currentTime(value: number) {
      writes.push(value);
      time = value;
    },
  };
  return { media, writes };
}

interface StreamControllerInternals {
  media: unknown;
  levels: unknown[];
  levelLastLoaded: unknown;
  startFragRequested: boolean;
  _hasEnoughToStart: boolean;
  nextLoadPosition: number;
  state: string;
  tick: () => void;
  onLevelLoaded: (event: string, data: unknown) => void;
}

const instances: { hls: Hls; sc: StreamControllerInternals }[] = [];
afterEach(() => {
  for (const { hls, sc } of instances.splice(0)) {
    // 흉내 <video> 에는 이벤트 떼기가 없다 — 떼기 전에 놓는다
    sc.media = null;
    hls.destroy();
  }
});

/** 창이 3 조각 밀린 둘째 목록(첫 조각 12초)을 받기 직전까지 — 재생은 이미 시작했다(startFragRequested) */
function started(media: unknown, hasEnoughToStart: boolean) {
  const hls = new Hls({ ...HLS_REWIND_CONFIG, debug: SILENT });
  Object.defineProperty(hls, 'liveSyncPosition', { get: () => LIVE_SYNC });
  const internals = hls as unknown as {
    streamController: StreamControllerInternals;
    levelController: { _levels: unknown[] };
  };
  const sc = internals.streamController;
  const level = { details: playlist(0, 13) };
  // hls.levels — 앞서 LEVEL_UPDATED 를 받는 내부 처리기(interstitials)가 읽는다
  internals.levelController._levels = [level];
  instances.push({ hls, sc });
  sc.media = media;
  sc.levels = [level];
  sc.levelLastLoaded = level;
  sc.startFragRequested = true;
  sc._hasEnoughToStart = hasEnoughToStart;
  sc.state = 'IDLE';
  // 조각 받기는 이 시험의 대상이 아니다(목록 갱신 하나의 동기 구간만 본다)
  sc.tick = () => {};
  const slide = () =>
    sc.onLevelLoaded(Events.LEVEL_LOADED, {
      details: playlist(3, 13),
      id: 0,
      level: 0,
      levelInfo: level,
      networkDetails: null,
      stats: {},
      deliveryDirectives: null,
    });
  return { hls, sc, slide };
}

describe('hls.js 1.7.3 의 목록 갱신 순서(진단 재현 9 · 10)', () => {
  it('앱 처리기가 없으면 창 밖 재생점(readyState 3)을 라이브 끝으로 옮긴다 — 고칠 대상', () => {
    const { media, writes } = fakeMedia(10, 3);
    started(media, true).slide();
    expect(writes).toEqual([LIVE_SYNC]);
  });

  it('LEVEL_UPDATED 는 synchronizeToLiveEdge 보다 먼저 동기로 온다 — 처리기 안의 재생점은 아직 옮겨지지 않았다', () => {
    const { media, writes } = fakeMedia(10, 3);
    const { hls, slide } = started(media, true);
    const seen: number[] = [];
    hls.on(Events.LEVEL_UPDATED, () => seen.push(media.currentTime));
    slide();
    expect(seen).toEqual([10]);
    expect(writes).toEqual([LIVE_SYNC]);
  });

  it('LEVEL_UPDATED 에서 창 하한(첫 조각 + 3 TD)으로 옮기면 hls.js 는 옮기지 않는다 — 쓰기 한 번', () => {
    const { media, writes } = fakeMedia(10, 3);
    const { hls, slide } = started(media, true);
    hls.on(Events.LEVEL_UPDATED, (_event, data) =>
      guardRewindFloor({ hls, video: media, details: data.details, loadFrom: null }),
    );
    slide();
    expect(writes).toEqual([12 + 3 * TD]);
    expect(media.currentTime).toBe(24);
  });

  it('기동 전(hasEnoughToStart 거짓)은 받기 위치를 창 하한으로 다시 잡는다 — hls.js 가 받기 위치를 라이브 끝으로 바꾸지 않는다', () => {
    const { media, writes } = fakeMedia(0, 0);
    const { hls, sc, slide } = started(media, false);
    sc.nextLoadPosition = 8;
    hls.on(Events.LEVEL_UPDATED, (_event, data) =>
      guardRewindFloor({ hls, video: media, details: data.details, loadFrom: 8 }),
    );
    slide();
    expect(writes).toEqual([]);
    expect(sc.nextLoadPosition).toBe(24);
  });

  it('기동 전에 앱 처리기가 없으면 hls.js 가 받기 위치만 조용히 라이브 끝으로 바꾼다 — 고칠 대상', () => {
    const { media, writes } = fakeMedia(0, 0);
    const { sc, slide } = started(media, false);
    sc.nextLoadPosition = 8;
    slide();
    expect(writes).toEqual([]);
    expect(sc.nextLoadPosition).toBe(LIVE_SYNC);
  });
});
