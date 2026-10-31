import Hls, {
  Events,
  LoadStats,
  type HlsConfig,
  type LoaderCallbacks,
  type LoaderContext,
} from 'hls.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HLS_LIVE_CONFIG } from './hlsConfig';

// 진짜 hls.js 1.7.3(dist)의 라이브 기동 순서를 고정한다(설계 r3 9절 · r9 11절). 이 파일은 hls.js 와
// hlsConfig 만 읽어, 제품 코드를 되돌린 트리에서도 돈다. 기동 문지기(hlsStart.ts)가 기대는 hls.js 동작이다: 창
// 하한 + 1 초에서 시작하면 목록이 밀려도 시작 위치를 두고 재생점을 옮기지 않는다 · startLoad(-1) 은 라이브
// 시작(liveSync) · 창 시작보다 앞 자리는 -1 로 바뀐다(base-stream-controller
// setStartPosition). hls.js 판을 올려 이 순서가 바뀌면 여기서 먼저 깨진다. jsdom 에는 MediaSource 가
// 없다. 매체 없는 재생기는 조각을 청하지 않으므로(doTickIdle) 흉내 <video> 를 streamController 에만
// 붙인다(rewindFloor.hlsjs.test.ts 와 같은 틀).

const VIDEO_URL = 'https://m/live/k/video1_stream.m3u8';
const PDT0 = Date.parse('2026-10-27T05:00:00.000Z');
const TD = 4;
const FIRST_SN = 100;
/** 라이브 시작 조각 — liveSyncPosition(목록 끝 − 3 TD = 16초)이 든 조각 */
const LIVE_START_SN = FIRST_SN + 4;

/** slide 조각만큼 창이 밀린 7조각 매체 목록 · ENDLIST 없음 */
function mediaPlaylist(slide: number): string {
  const lines = ['#EXTM3U', '#EXT-X-VERSION:9', `#EXT-X-TARGETDURATION:${TD}`];
  lines.push(`#EXT-X-MEDIA-SEQUENCE:${FIRST_SN + slide}`);
  for (let i = slide; i < slide + 7; i++) {
    lines.push(`#EXT-X-PROGRAM-DATE-TIME:${new Date(PDT0 + i * TD * 1000).toISOString()}`);
    lines.push(`#EXTINF:${TD}.000,`, `seg${FIRST_SN + i}.mp4`);
  }
  return lines.join('\n');
}

const quiet = () => {};
const SILENT = { trace: quiet, debug: quiet, log: quiet, warn: quiet, info: quiet, error: quiet };

interface StreamController {
  media: unknown;
  startPosition: number;
  nextLoadPosition: number;
}

const players: Array<{ hls: Hls; sc: StreamController }> = [];
/** 가짜 loader 의 load 인자 — 설정(가운데)은 쓰지 않는다 */
type LoadArgs = [LoaderContext, unknown, LoaderCallbacks<LoaderContext>];
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
});
afterEach(() => {
  for (const { hls, sc } of players.splice(0)) {
    // 흉내 <video> 에는 이벤트 떼기가 없다 — 떼기 전에 놓는다
    sc.media = null;
    hls.destroy();
  }
  vi.useRealTimers();
});

/**
 * 재생점이 playhead 에 선(그 자리에서 재생이 시작된) 흉내 <video> 를 streamController 에만 단다 —
 * currentTime 쓰기를 센다
 */
function attachFakeMedia(sc: StreamController, playhead: number): number[] {
  const writes: number[] = [];
  let time = playhead;
  sc.media = {
    readyState: 3,
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
  return writes;
}

/**
 * 목록을 받을 때마다 한 조각씩 밀어 주는 가짜 loader 를 단 재생기 · 재생점은 시작 자리 · MANIFEST_PARSED 에서
 * startLoad(position)
 */
function player(position: number) {
  let slide = 0;
  class FakeLoader {
    stats = new LoadStats();
    load(...[context, , callbacks]: LoadArgs) {
      // 조각에는 답하지 않는다 — 요청 자체만 본다
      if (context.url !== VIDEO_URL) return;
      const data = mediaPlaylist(slide++);
      queueMicrotask(() => {
        // 받은 시각을 찍는다 — 0 으로 두면 다음 받기 예약이 늘 과거라 hls.js 가 목록을 쉬지 않고 다시 받는다
        const now = performance.now();
        this.stats.loading = { start: now, first: now, end: now };
        callbacks.onSuccess({ url: VIDEO_URL, data }, this.stats, context, null);
      });
    }
    abort() {}
    destroy() {}
  }
  const hls = new Hls({
    ...HLS_LIVE_CONFIG,
    autoStartLoad: false,
    loader: FakeLoader as unknown as HlsConfig['loader'],
    debug: SILENT,
  });
  const sc = (hls as unknown as { streamController: StreamController }).streamController;
  players.push({ hls, sc });
  const frags: number[] = [];
  hls.on(Events.FRAG_LOADING, (...[, data]) => {
    if (typeof data.frag.sn === 'number') frags.push(data.frag.sn);
  });
  let updates = 0;
  hls.on(Events.LEVEL_UPDATED, () => updates++);
  hls.on(Events.MANIFEST_PARSED, () => hls.startLoad(position));
  const writes = attachFakeMedia(sc, Math.max(position, 0));
  hls.loadSource(VIDEO_URL);
  return { hls, sc, frags, writes, updates: () => updates };
}

/** 가짜 loader 의 마이크로태스크 응답과 hls.js 의 0ms 타이머를 비우고, 목록 다시 받기를 reloads 번 돈다 */
async function run(reloads: number) {
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(0);
  for (let i = 0; i < reloads; i++) await vi.advanceTimersByTimeAsync(TD * 1000);
}

describe('hls.js 라이브 기동 — 기동 문지기가 기대는 순서(진짜 hls.js)', () => {
  it('대조 1: startLoad(-1) 이면 첫 조각 요청은 라이브 시작(liveSync) 조각 하나다', async () => {
    const { frags } = player(-1);
    await run(0);
    expect(frags).toEqual([LIVE_START_SN]);
  });

  it('대조 2: 창 시작보다 앞 자리(첫 − 0.5 초)는 -1 로 바뀌어 라이브 시작으로 간다 — 하한이 이 갈래를 막는다', async () => {
    const { frags } = player(-0.5);
    await run(0);
    expect(frags).toEqual([LIVE_START_SN]);
  });

  it('보존: 창 하한(첫 조각 + 맨 앞 4초 조각 셋 길이 합) + 1 초에서 시작하면 목록이 두 번 밀려도 시작 위치를 두고 재생점을 옮기지 않는다', async () => {
    const position = 4 + 4 + 4 + 1;
    const { sc, frags, writes, updates } = player(position);
    await run(2);
    expect(updates()).toBe(3);
    expect(sc.startPosition).toBe(position);
    expect(writes).toEqual([]);
    expect(frags).toEqual([FIRST_SN + 3]);
  });

  it('보존의 대조: 여유 없이(첫 + 1 초) 시작하면 창이 두 번 밀릴 때 hls.js 가 재생점을 옮긴다', async () => {
    const { sc, writes, updates } = player(1);
    await run(2);
    expect(updates()).toBe(3);
    expect(writes.length).toBeGreaterThan(0);
    expect(sc.nextLoadPosition).toBe(writes[writes.length - 1]);
  });
});
