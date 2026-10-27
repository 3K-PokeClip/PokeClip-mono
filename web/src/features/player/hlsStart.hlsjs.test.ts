import Hls, {
  Events,
  type HlsConfig,
  LoadStats,
  type LevelDetails,
  type LoaderCallbacks,
  type LoaderContext,
} from 'hls.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HLS_LIVE_CONFIG } from './hlsConfig';
import { armLiveStart, type LiveStart } from './hlsStart';
import { startLoadPosition } from './playbackEdge';

// 진짜 hls.js 1.7.3(dist)로 다변 목록(index.m3u8 → 매체 목록) 라이브 기동을 끝까지 돌린다(R81 · 설계 r9 11절). 목록은 가짜
// loader 가 주고, 조각 요청은 답하지 않고 FRAG_LOADING 으로만 센다. jsdom 에는 MediaSource 가 없고, 매체 없는 재생기는
// 조각을 아예 청하지 않아(doTickIdle) 「라이브 시작 조각 0」 이 저절로 참이 된다. 그래서 흉내 <video> 를 streamController 에만
// 붙인다(rewindFloor.hlsjs.test.ts 와 같은 틀). 맨 앞 양성 대조가 이 틀에서 조각 요청이 보인다는 것을 먼저 못 박는다.

const BASE = 'https://m/live/k';
const MASTER = `${BASE}/index.m3u8`;
const VIDEO = `${BASE}/video1_stream.m3u8`;
const AUDIO = `${BASE}/audio2_stream.m3u8`;
const PDT0 = Date.parse('2026-10-27T05:00:00.000Z');
const TD = 4;
/** 미디어 순번이 조각 번호와 갈리게 0 이 아닌 값에서 시작한다 */
const FIRST_SN = 100;
const COUNT = 7;
/** 이 목록의 라이브 시작 조각 — liveSyncPosition(목록 끝 − 3 TD = 16초)이 든 조각. 맨 앞 대조가 실측한다 */
const LIVE_START_SN = FIRST_SN + 4;

function masterPlaylist(withAudio: boolean): string {
  const audio =
    '#EXT-X-MEDIA:TYPE="AUDIO",GROUP-ID="audio",NAME="audio2",AUTOSELECT=YES,DEFAULT=YES,' +
    'URI="audio2_stream.m3u8"';
  const variant = `#EXT-X-STREAM-INF:BANDWIDTH=2000000${withAudio ? ',AUDIO="audio"' : ''}`;
  const head = ['#EXTM3U', '#EXT-X-VERSION:9', '#EXT-X-INDEPENDENT-SEGMENTS'];
  return [...head, ...(withAudio ? [audio] : []), variant, 'video1_stream.m3u8'].join('\n');
}

/** slide 조각만큼 창이 밀린 매체 목록 · ENDLIST 없음 */
function mediaPlaylist(prefix: string, slide: number): string {
  const from = FIRST_SN + slide;
  const lines = ['#EXTM3U', '#EXT-X-VERSION:9', `#EXT-X-TARGETDURATION:${TD}`];
  lines.push(`#EXT-X-MEDIA-SEQUENCE:${from}`);
  for (let sn = from; sn < from + COUNT; sn++) {
    const pdt = new Date(PDT0 + (sn - FIRST_SN) * TD * 1000).toISOString();
    lines.push(`#EXT-X-PROGRAM-DATE-TIME:${pdt}`, `#EXTINF:${TD}.000,`, `${prefix}_seg${sn}.mp4`);
  }
  return lines.join('\n');
}

const quiet = () => {};
const SILENT = { trace: quiet, debug: quiet, log: quiet, warn: quiet, info: quiet, error: quiet };

/** 첫 목록에서 epoch ms 자리를 라이브 기동 위치로 정하는 결정 — 제품의 startLoadPosition 을 그대로 쓴다 */
const startAt =
  (epochMs: number) =>
  (details: LevelDetails): LiveStart => ({
    kind: 'load',
    position: startLoadPosition(details.fragments, epochMs, 'live', details.targetduration) ?? -1,
  });
const truncate = (): LiveStart => ({ kind: 'truncated' });
const noop = () => {};

/** 흉내 <video> — 재생점 0 · 조각을 받지 않아 재생은 시작되지 않는다 */
const fakeMedia = () => ({
  readyState: 0,
  duration: Infinity,
  paused: true,
  seeking: false,
  currentTime: 0,
  buffered: { length: 0, start: () => 0, end: () => 0 },
});

type StreamControllerInternals = { media: unknown };

const players: { hls: Hls; sc: StreamControllerInternals }[] = [];
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

/** 가짜 loader 를 단 라이브 재생기. 매체 목록은 받을 때마다 한 조각씩 밀린다 */
function player(withAudio: boolean) {
  const requests: string[] = [];
  const served: Record<string, number> = {};
  const respond = (url: string): string | undefined => {
    if (url === MASTER) return masterPlaylist(withAudio);
    if (url !== VIDEO && url !== AUDIO) return undefined;
    const slide = served[url] ?? 0;
    served[url] = slide + 1;
    return mediaPlaylist(url === VIDEO ? 'v' : 'a', slide);
  };
  class FakeLoader {
    stats = new LoadStats();
    load(context: LoaderContext, _config: unknown, callbacks: LoaderCallbacks<LoaderContext>) {
      const url = context.url.split('?')[0] ?? context.url;
      requests.push(url);
      const body = respond(url);
      // 조각에는 답하지 않는다 — 요청 자체만 본다
      if (body === undefined) return;
      queueMicrotask(() => {
        const now = performance.now();
        this.stats.loading = { start: now, first: now, end: now };
        callbacks.onSuccess({ url: context.url, data: body }, this.stats, context, null);
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
  const sc = (hls as unknown as { streamController: StreamControllerInternals }).streamController;
  sc.media = fakeMedia();
  players.push({ hls, sc });
  const frags: number[] = [];
  hls.on(Events.FRAG_LOADING, (_event, data) => {
    if (data.frag.type === 'main' && typeof data.frag.sn === 'number') frags.push(data.frag.sn);
  });
  const updates: number[] = [];
  hls.on(Events.LEVEL_UPDATED, (_event, data) => updates.push(data.details.startSN));
  // 원래 구현을 그대로 부르며 호출만 센다
  const startLoad = vi.spyOn(hls, 'startLoad');
  const stopLoad = vi.spyOn(hls, 'stopLoad');
  return { hls, requests, frags, updates, startLoad, stopLoad };
}

/** 가짜 loader 의 마이크로태스크 응답과 hls.js 의 0ms 타이머를 비운다 */
async function settle() {
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(0);
}

/** 조각 sn 이 덮는 실제 시각 안의 자리(epoch ms) */
const insideSn = (sn: number, offsetS: number) => PDT0 + ((sn - FIRST_SN) * TD + offsetS) * 1000;

describe('양성 대조 — 이 틀에서 hls.js 의 라이브 기동 조각 요청이 보인다', () => {
  it('문지기 없이 MANIFEST_PARSED 에서 startLoad(-1) 만 부르면 첫 조각은 라이브 시작(liveSync) 조각이다', async () => {
    const { hls, frags } = player(false);
    hls.on(Events.MANIFEST_PARSED, () => hls.startLoad(-1));
    hls.loadSource(MASTER);
    await settle();
    expect(frags).toEqual([LIVE_START_SN]);
  });
});

describe.each([
  ['단변 목록', false],
  ['EXT-X-MEDIA 음성 트랙 목록', true],
])('armLiveStart — 진짜 hls.js 다변 목록 기동(R81 후보 1 · %s)', (_name, withAudio) => {
  it('첫 LEVEL_UPDATED 에서 정한 자리의 조각을 먼저 · 한 번만 청하고, 라이브 시작 조각은 청하지 않는다', async () => {
    const { hls, frags } = player(withAudio);
    armLiveStart(hls, Events, startAt(insideSn(FIRST_SN + 3, 1.5)), noop);
    hls.loadSource(MASTER);
    await settle();
    expect(frags).toEqual([FIRST_SN + 3]);
  });

  it('잘림이면 조각을 하나도 청하지 않고 stopLoad 한 번 · 처리기 밖 다음 마이크로태스크에 onTruncated 한 번', async () => {
    const { hls, frags, stopLoad } = player(withAudio);
    /** onTruncated 가 불린 때까지의 stopLoad 수 */
    const truncated: number[] = [];
    /** 문지기 뒤에 단 처리기가 같은 LEVEL_UPDATED 안에서 본 [stopLoad 수, onTruncated 수] */
    const seenInHandler: number[] = [];
    armLiveStart(hls, Events, truncate, () => truncated.push(stopLoad.mock.calls.length));
    hls.on(Events.LEVEL_UPDATED, () =>
      seenInHandler.push(stopLoad.mock.calls.length, truncated.length),
    );
    hls.loadSource(MASTER);
    await settle();
    expect(frags).toEqual([]);
    // stopLoad 첫 번째는 loadSource 가 스스로 부르는 것, 두 번째가 문지기의 것이다
    expect(stopLoad).toHaveBeenCalledTimes(2);
    expect(seenInHandler).toEqual([2, 0]);
    expect(truncated).toEqual([2]);
  });

  it('처리기는 재생기 하나에 한 번만 돈다 — 둘째 LEVEL_UPDATED 에서 startLoad 0', async () => {
    const { hls, updates, startLoad } = player(withAudio);
    const position = 13.5;
    armLiveStart(hls, Events, () => ({ kind: 'load', position }), noop);
    hls.loadSource(MASTER);
    await settle();
    // 라이브 목록 다시 받기 한 번(TARGETDURATION 만큼)
    await vi.advanceTimersByTimeAsync(TD * 1000);
    await settle();
    expect(updates.length).toBeGreaterThanOrEqual(2);
    expect(startLoad.mock.calls.map(([at]) => at)).toEqual([-1, position]);
  });
});

describe('armLiveStart — 음성 트랙(진짜 hls.js)', () => {
  it('EXT-X-MEDIA 음성 트랙 견본에서 음성 매체 목록을 받는다(후보 2 의 위험인 음성 트랙 잃음이 없다)', async () => {
    const { hls, requests } = player(true);
    armLiveStart(hls, Events, startAt(insideSn(FIRST_SN + 3, 1.5)), noop);
    hls.loadSource(MASTER);
    await settle();
    expect(requests).toContain(AUDIO);
  });
});
