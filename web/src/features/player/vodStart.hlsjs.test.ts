import Hls, {
  Events,
  type HlsConfig,
  LoadStats,
  type LoaderCallbacks,
  type LoaderContext,
} from 'hls.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HLS_REWIND_CONFIG } from './hlsConfig';
import { armStart, startConfig, trackLoadFrom } from './hlsStart';

// 다시보기 재생기의 「처음부터」 기동(POK-573) — 진짜 hls.js 1.7.3(dist)에 끝난 목록(ENDLIST)을 주고, 첫 받기 자리와 첫 main
// 조각 요청을 적는다. 대조는 hls.js 의 자동 시작(HLS_REWIND_CONFIG 그대로)이고, 제품 재생기는 useHlsPlayback 이 다시보기에
// 거는 기동(startConfig · armStart — 첫 목록에서 받기 시작 자리를 정한다)이다. 목록은 가짜 loader 가 주고, 조각 요청은 답하지
// 않고 FRAG_LOADING 으로만 센다(hlsStart.hlsjs.test.ts 와 같은 틀 — 흉내 <video> 를 streamController 에만 붙인다). 조각에
// 답하지 않으므로 화면이 어디에 서는지는 이 층에서 보이지 않는다 — 첫 화면 착지는 브라우저 층에서 따로 잰다. 여기서는 요청만
// 고정한다.

const URL = 'https://m/vod/k/r-A/index.m3u8';
const PDT0 = Date.parse('2026-11-04T10:00:00.000Z');
const TD = 4;
/** 미디어 순번이 조각 번호와 갈리게 0 이 아닌 값에서 시작한다 */
const FIRST_SN = 100;
const CONTENT_COUNT = 6;

/** 맨 앞 gaps 개와 middleGaps 번째 조각이 EXT-X-GAP 인 끝난 목록 — 4초 조각, PDT 는 이어 센다 */
function vodPlaylist(gaps: number, middleGaps: readonly number[]): string {
  const lines = [
    '#EXTM3U',
    '#EXT-X-VERSION:9',
    `#EXT-X-TARGETDURATION:${TD}`,
    '#EXT-X-PLAYLIST-TYPE:VOD',
    `#EXT-X-MEDIA-SEQUENCE:${FIRST_SN}`,
  ];
  for (let i = 0; i < gaps + CONTENT_COUNT; i++) {
    const pdt = new Date(PDT0 + i * TD * 1000).toISOString();
    lines.push(`#EXT-X-PROGRAM-DATE-TIME:${pdt}`);
    if (i < gaps || middleGaps.includes(i)) lines.push('#EXT-X-GAP');
    lines.push(`#EXTINF:${TD}.000,`, `seg${FIRST_SN + i}.mp4`);
  }
  lines.push('#EXT-X-ENDLIST');
  return lines.join('\n');
}

const quiet = () => {};
const SILENT = { trace: quiet, debug: quiet, log: quiet, warn: quiet, info: quiet, error: quiet };

/** 흉내 <video> — 재생점 0 · 조각을 받지 않아 재생은 시작되지 않는다 */
const fakeMedia = () => ({
  readyState: 0,
  duration: Infinity,
  paused: true,
  seeking: false,
  currentTime: 0,
  buffered: { length: 0, start: () => 0, end: () => 0 },
});

interface StreamControllerInternals {
  media: unknown;
}

const players: Array<{ hls: Hls; sc: StreamControllerInternals }> = [];
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
 * 가짜 loader 를 물린 재생기 — 목록에만 답한다. start 가 없으면 hls.js 의 자동 시작(대조), 있으면 제품의 다시보기 기동으로
 * 그 시작 시각(없으면 null)을 준다
 */
function player(
  gaps: number,
  start?: { startAtMs: number | null },
  middleGaps: readonly number[] = [],
) {
  /** loader 까지 온 조각 요청(주소 끝의 조각 이름) */
  const fetched: string[] = [];
  class FakeLoader {
    stats = new LoadStats();
    load(...[context, , callbacks]: LoadArgs) {
      const url = context.url.split('?')[0] ?? context.url;
      // 조각에는 답하지 않는다 — 요청 자체만 본다
      if (url !== URL) {
        fetched.push(url.slice(url.lastIndexOf('/') + 1));
        return;
      }
      queueMicrotask(() => {
        const now = performance.now();
        this.stats.loading = { start: now, first: now, end: now };
        callbacks.onSuccess(
          { url: context.url, data: vodPlaylist(gaps, middleGaps) },
          this.stats,
          context,
          null,
        );
      });
    }
    abort() {}
    destroy() {}
  }
  const source = { kind: 'vod', startAtMs: start?.startAtMs ?? null, strictStart: false } as const;
  const hls = new Hls({
    ...(start === undefined ? HLS_REWIND_CONFIG : startConfig(HLS_REWIND_CONFIG, source)),
    loader: FakeLoader as unknown as HlsConfig['loader'],
    debug: SILENT,
  });
  const sc = (hls as unknown as { streamController: StreamControllerInternals }).streamController;
  sc.media = fakeMedia();
  players.push({ hls, sc });
  if (start !== undefined) armStart(hls, Events, source, trackLoadFrom({ currentTime: 0 }), quiet);
  const frags: number[] = [];
  hls.on(Events.FRAG_LOADING, (...[, data]) => {
    if (data.frag.type === 'main' && typeof data.frag.sn === 'number') frags.push(data.frag.sn);
  });
  const starts: Array<number | undefined> = [];
  hls.on(Events.LEVEL_LOADED, (...[, data]) =>
    starts.push(data.details.fragments.find((frag) => !frag.gap)?.start),
  );
  return { hls, frags, starts, fetched };
}

/** 가짜 loader 의 마이크로태스크 응답과 hls.js 의 0ms 타이머를 비운다 */
async function settle() {
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(0);
}

describe('대조 — hls.js 의 자동 시작이 청하는 첫 조각(화면 착지가 아니다)', () => {
  it('양성 대조 — GAP 없는 끝난 목록이면 첫 main 조각 요청은 목록 첫 조각이고 loader 까지 간다', async () => {
    const { hls, frags, fetched } = player(0);
    hls.loadSource(URL);
    await settle();
    expect({ frags, fetched }).toEqual({ frags: [FIRST_SN], fetched: [`seg${FIRST_SN}.mp4`] });
  });

  // 기록(통과 조건 아님): hls.js 1.7.3 기본 시작은 영상 0초 — 맨 앞 GAP 조각부터 받기를 시작하고, GAP 조각은 망으로
  // 청하지 않는다. 그 뒤 화면이 첫 내용 조각에 서는지는 조각에 답하는 브라우저 층에서만 보인다
  it('맨 앞 GAP 셋인 끝난 목록 — 첫 내용 조각은 영상 12초에 놓이고, 첫 main 받기는 첫 GAP 조각이며 망 요청은 없다', async () => {
    const { hls, frags, starts, fetched } = player(3);
    hls.loadSource(URL);
    await settle();
    expect(starts[0]).toBe(3 * TD);
    expect({ frags, fetched }).toEqual({ frags: [FIRST_SN], fetched: [] });
  });
});

describe('다시보기 재생기의 기동 — 첫 목록에서 정한 자리의 조각을 먼저 청한다', () => {
  it('맨 앞 GAP 셋 · 시작 자리 없음 — 첫 main 받기는 첫 내용 조각이고 loader 까지 간다', async () => {
    const { hls, frags, fetched } = player(3, { startAtMs: null });
    hls.loadSource(URL);
    await settle();
    expect({ frags, fetched }).toEqual({
      frags: [FIRST_SN + 3],
      fetched: [`seg${FIRST_SN + 3}.mp4`],
    });
  });

  it('맨 앞 GAP 셋 · 시작 자리 30초 — 첫 main 받기는 그 자리(영상 30초)가 든 조각이다', async () => {
    const { hls, frags, fetched } = player(3, { startAtMs: PDT0 + 30_000 });
    hls.loadSource(URL);
    await settle();
    const sn = FIRST_SN + Math.floor(30 / TD);
    expect({ frags, fetched }).toEqual({ frags: [sn], fetched: [`seg${sn}.mp4`] });
  });

  it('시작 자리 14초가 가운데 GAP 조각 안이면 — 첫 main 받기는 다음 내용 조각이다', async () => {
    const gapSn = FIRST_SN + Math.floor(14 / TD);
    const { hls, frags, fetched } = player(0, { startAtMs: PDT0 + 14_000 }, [gapSn - FIRST_SN]);
    hls.loadSource(URL);
    await settle();
    expect({ frags, fetched }).toEqual({ frags: [gapSn + 1], fetched: [`seg${gapSn + 1}.mp4`] });
  });

  it('맨 앞 GAP 이 없으면 자동 시작과 같다 — 첫 main 받기는 목록 첫 조각이다', async () => {
    const { hls, frags, fetched } = player(0, { startAtMs: null });
    hls.loadSource(URL);
    await settle();
    expect({ frags, fetched }).toEqual({ frags: [FIRST_SN], fetched: [`seg${FIRST_SN}.mp4`] });
  });
});
