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

  it('LEVEL_UPDATED 에서 창 하한(첫 조각 + 맨 앞 4초 조각 셋)으로 옮기면 hls.js 는 옮기지 않는다 — 쓰기 한 번', () => {
    const { media, writes } = fakeMedia(10, 3);
    const { hls, slide } = started(media, true);
    hls.on(Events.LEVEL_UPDATED, (_event, data) =>
      guardRewindFloor({ hls, video: media, details: data.details, loadFrom: null }),
    );
    slide();
    expect(writes).toEqual([12 + 4 + 4 + 4]);
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

  it('복구 뒤 기동 전에 받기 위치를 모르면(loadFrom null) 옮기지 않고, 창 안 받기 위치는 hls.js 도 그대로 둔다(cc H1)', () => {
    // recoverMediaError 가 재생점(30초)에서 startLoad 한 뒤의 상태 — 떼고 붙이기는 MediaSource 가 없어 흉내 내지 않는다
    const { media, writes } = fakeMedia(30, 0);
    const { hls, sc, slide } = started(media, false);
    sc.nextLoadPosition = 30;
    const moves: unknown[] = [];
    hls.on(Events.LEVEL_UPDATED, (_event, data) =>
      moves.push(guardRewindFloor({ hls, video: media, details: data.details, loadFrom: null })),
    );
    slide();
    expect(moves).toEqual([null]);
    expect(writes).toEqual([]);
    expect(sc.nextLoadPosition).toBe(30);
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

// 앱이 기동 전 받기 위치의 출처로 기대는 hls.js 의 처리기 순서 둘(c12d — cx H · cc M1). MediaSource 가 없는 jsdom 이라 붙이기 ·
// 조각 붙이기는 흉내 낸다: 떼기는 buffer-controller 의 src 지우기 + load() 까지, 조각은 버퍼 범위 하나만
describe('hls.js 1.7.3 의 떼기 · 첫 조각 처리기 순서(c12d)', () => {
  /** load() 가 재생점을 0 으로 만드는 흉내 <video>(HTML 미디어 요소 load 알고리즘) */
  function loadResettingMedia(currentTime: number) {
    const media = {
      ...fakeMedia(currentTime, 4).media,
      src: 'blob:hls',
      addEventListener: () => {},
      removeEventListener: () => {},
      removeAttribute: () => {},
      querySelectorAll: () => [],
      load: () => {
        media.currentTime = 0;
      },
    };
    return media;
  }

  it('복구의 떼기에서 앱 처리기는 재생점 0 을 보고, hls.js 는 떼기 전에 잡은 재생점에서 다시 받는다', () => {
    const { hls } = started(null, true);
    const media = loadResettingMedia(40);
    const internals = hls as unknown as {
      _media: unknown;
      started: boolean;
      bufferController: { media: unknown; _objectUrl: string };
      attachMedia: (m: unknown) => void;
    };
    internals._media = media;
    internals.started = true;
    internals.bufferController.media = media;
    internals.bufferController._objectUrl = media.src;
    // 다시 붙이기는 MediaSource 가 없어 흉내 낸다
    internals.attachMedia = () => {};
    const loads: (number | undefined)[] = [];
    hls.startLoad = (position?: number) => {
      loads.push(position);
    };
    const seen: number[] = [];
    hls.on(Events.MEDIA_DETACHING, () => seen.push(media.currentTime));
    // jsdom 에는 revokeObjectURL 이 없다(떼기가 부른다)
    Object.defineProperty(globalThis.URL, 'revokeObjectURL', {
      value: () => {},
      configurable: true,
    });
    try {
      hls.recoverMediaError();
    } finally {
      Reflect.deleteProperty(globalThis.URL, 'revokeObjectURL');
    }
    expect(seen).toEqual([0]);
    expect(loads).toEqual([40]);
  });

  it('첫 조각이 붙은 FRAG_BUFFERED 의 앱 처리기에서 hasEnoughToStart 가 이미 참이다', () => {
    const { media } = fakeMedia(0, 1);
    const { hls, sc } = started(
      { ...media, buffered: { length: 1, start: () => 0, end: () => 4 } },
      false,
    );
    const seen: boolean[] = [];
    hls.on(Events.FRAG_BUFFERED, () => seen.push(hls.hasEnoughToStart));
    hls.trigger(Events.FRAG_BUFFERED, {
      frag: { type: PlaylistLevelType.SUBTITLE, stats: { aborted: false } },
      part: null,
      stats: {},
      id: 'subtitle',
    } as never);
    expect(sc._hasEnoughToStart).toBe(true);
    expect(seen).toEqual([true]);
  });
});

// 창 하한 여유는 TD 가 아니라 맨 앞 조각 셋의 실제 길이다(POK-200 c13 — gohlslib 은 TD 를 지금까지 본 조각 최대값으로
// 올리기만 해, 첫 조각이 6초면 방송 끝까지 TD 6 · 실제 조각은 4초다). 목록은 진짜 파서로 읽는다
describe('guardRewindFloor — 창 하한 = 첫 내용 조각 + 그 뒤 조각 셋의 길이', () => {
  /** TARGETDURATION td · 조각 길이 durations(초) · ENDLIST 없음 */
  function listOf(
    td: number,
    durations: readonly number[],
    gapAt: readonly number[] = [],
  ): LevelDetails {
    const lines = ['#EXTM3U', '#EXT-X-VERSION:9', `#EXT-X-TARGETDURATION:${td}`];
    let at = PDT0;
    durations.forEach((duration, i) => {
      if (gapAt.includes(i)) lines.push('#EXT-X-GAP');
      lines.push(`#EXT-X-PROGRAM-DATE-TIME:${new Date(at).toISOString()}`);
      lines.push(`#EXTINF:${duration.toFixed(3)},`, `seg${i}.mp4`);
      at += duration * 1000;
    });
    return M3U8Parser.parseLevelPlaylist(lines.join('\n'), URL, 0, PlaylistLevelType.MAIN, 0, null);
  }

  /** 재생 중인 재생기의 첫 조각 afterS 초 뒤 재생점을 옮긴 자리 */
  function seekTo(details: LevelDetails, afterS = 0.5): number | undefined {
    const hls = { hasEnoughToStart: true, startLoad: () => {} };
    const { media } = fakeMedia(details.fragmentStart + afterS, 4);
    return guardRewindFloor({ hls, video: media, details, loadFrom: null })?.to;
  }

  it('TD 6 에 첫 조각 6초 · 나머지 4초면 첫 조각 + 14초로 옮긴다', () => {
    expect(seekTo(listOf(6, [6, 4, 4, 4, 4]))).toBe(14);
  });

  it('6초 조각이 빠진 뒤(4초 조각만)는 첫 조각 + 12초다', () => {
    expect(seekTo(listOf(6, [4, 4, 4, 4]))).toBe(12);
  });

  it('조각이 셋 미만이면 있는 만큼 · 하나도 없어 길이를 모를 때만 3 × TD 다', () => {
    expect(seekTo(listOf(6, [4, 4]))).toBe(8);
    expect(seekTo(listOf(6, []))).toBe(18);
  });

  it('맨 앞 조각 셋이 1 TD 보다 짧으면 하한은 첫 조각 + 1 TD 다 — 옮김 문턱 안의 재생점을 뒤로 당기지 않는다', () => {
    // 조각 셋 합 3초 < 문턱 6초(1 × TD) — 첫 조각 + 4초의 재생점을 + 3초로 되돌리면 역방향 시크다
    const short = listOf(6, [1, 1, 1, 1, 1, 1, 1, 1]);
    expect(seekTo(short, 4)).toBe(6);
    expect(seekTo(short, 0.5)).toBe(6);
  });

  it('하한이 목록 끝을 넘으면 목록 끝으로 옮긴다 — 끝 뒤 자리는 hls.js 가 창 밖으로 보고 다시 옮긴다', () => {
    // 목록 끝 4초 < 문턱 6초(1 × TD) · 모두 GAP 의 3 × TD(18초) 도 끝(6초) 뒤다
    const short = listOf(6, [1, 1, 1, 1]);
    expect(short.edge).toBe(4);
    expect(seekTo(short, 0.5)).toBe(4);
    expect(seekTo(listOf(6, [2, 2, 2], [0, 1, 2]))).toBe(6);
    // 이미 끝에 선 재생점은 옮기지 않는다 — 같은 자리 쓰기 · 역방향 시크가 없다
    expect(seekTo(short, 4)).toBeUndefined();
  });

  it('맨 앞 GAP 조각은 내용이 아니다 — 하한은 첫 내용 조각 + 그 뒤 조각 셋이다(옮김 문턱은 첫 조각 기준 그대로)', () => {
    // GAP 둘(6초씩) 뒤 내용 4초 — GAP 을 세면 하한이 첫 조각 + 16초로 내용(12초) 바로 뒤에 선다
    const details = listOf(6, [6, 6, 4, 4, 4], [0, 1]);
    expect(details.fragments.filter((f) => f.gap)).toHaveLength(2);
    expect(seekTo(details)).toBe(24);
    // 문턱은 첫 조각(GAP) + 1 TD 다 — 그 뒤 재생점은 옮기지 않는다
    expect(seekTo(details, 6)).toBeUndefined();
  });

  it('가운데 GAP 은 창이 밀리는 조각이라 여유에 그대로 센다 — 빼는 것은 맨 앞 GAP 뿐이다', () => {
    expect(seekTo(listOf(6, [4, 6, 4, 4], [1]))).toBe(14);
  });

  it('모두 GAP 이면 조각이 없을 때처럼 첫 조각 + 3 × TD 다', () => {
    expect(seekTo(listOf(6, [6, 6, 6, 6], [0, 1, 2, 3]))).toBe(18);
  });
});
