import Hls, {
  Events,
  LoadStats,
  type HlsConfig,
  type LoaderCallbacks,
  type LoaderContext,
} from 'hls.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HLS_LIVE_CONFIG } from './hlsConfig';
import { readHlsCatchup } from './liveCatchup';

// 앱 따라잡기(liveCatchup.ts)가 기대는 진짜 hls.js 1.7.3(dist)의 동작을 고정한다(POK-583 c2). J1: 라이브 설정
// (maxLiveSyncPlaybackRate: 1)에서 hls.js 의 지연 제어기는 앱이 건 배속을 덮지 않는다(latency-controller onTimeupdate 의
// 「maxLiveSyncPlaybackRate === 1 이면 돌아감」). J2: 앱이 읽는 값(latency · targetLatency · liveSyncPosition ·
// latestLevelDetails.age · targetduration)의 뜻. jsdom 에는 MediaSource 가 없어 영상 노드를 재생기 전체에 붙이지 않고, 지연
// 제어기에만 그 MEDIA_ATTACHED 처리기로 붙인다 — 제어기는 그 노드의 timeupdate 를 듣는다(hlsLiveStart.hlsjs.test.ts 와 같은 틀).

const VIDEO_URL = 'https://m/live/k/video1_stream.m3u8';
const PDT0 = Date.parse('2026-11-19T05:00:00.000Z');
const TD = 4;
const PART_TARGET = 0.5;
/** 계약3 7-5 의 PART-HOLD-BACK 정본 */
const PART_HOLD_BACK = 1.25;
const FIRST_SN = 100;
const SEGMENTS = 7;

/** slide 조각만큼 창이 밀린 7조각 LL-HLS 매체 목록 · ENDLIST 없음 */
function mediaPlaylist(slide: number): string {
  const lines = [
    '#EXTM3U',
    '#EXT-X-VERSION:9',
    `#EXT-X-TARGETDURATION:${TD}`,
    `#EXT-X-SERVER-CONTROL:PART-HOLD-BACK=${PART_HOLD_BACK}`,
    `#EXT-X-PART-INF:PART-TARGET=${PART_TARGET}`,
    `#EXT-X-MEDIA-SEQUENCE:${FIRST_SN + slide}`,
  ];
  for (let i = slide; i < slide + SEGMENTS; i++) {
    lines.push(`#EXT-X-PROGRAM-DATE-TIME:${new Date(PDT0 + i * TD * 1000).toISOString()}`);
    lines.push(`#EXTINF:${TD}.000,`, `seg${FIRST_SN + i}.mp4`);
  }
  return lines.join('\n');
}

const quiet = () => {};
const SILENT = { trace: quiet, debug: quiet, log: quiet, warn: quiet, info: quiet, error: quiet };

/** hls.js 1.7.3 지연 제어기의 비공개 몫 — 노드 붙이기만 쓴다 */
interface LatencyController {
  onMediaAttached: (event: Events.MEDIA_ATTACHED, data: { media: HTMLMediaElement }) => void;
}

/** 가짜 loader 의 load 인자 — 설정(가운데)은 쓰지 않는다 */
type LoadArgs = [LoaderContext, unknown, LoaderCallbacks<LoaderContext>];

const players: Hls[] = [];
beforeEach(() => {
  vi.useFakeTimers({
    now: PDT0 + SEGMENTS * TD * 1000,
    toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'],
  });
});
afterEach(() => {
  for (const hls of players.splice(0)) hls.destroy();
  vi.useRealTimers();
});

/**
 * 가짜 loader 를 단 라이브 재생기. 첫 받기 뒤로 state.advance 가 참이면 받을 때마다 창을 한 조각 민다(목록 전진), 거짓이면
 * 같은 목록을 다시 준다(전진 없는 응답)
 */
function player(config: Partial<HlsConfig> = {}) {
  let slide = 0;
  let loads = 0;
  const state = { advance: true };
  class FakeLoader {
    stats = new LoadStats();
    load(...[context, , callbacks]: LoadArgs) {
      if (!context.url.startsWith(VIDEO_URL)) return;
      if (loads++ > 0 && state.advance) slide++;
      const data = mediaPlaylist(slide);
      queueMicrotask(() => {
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
    ...config,
    autoStartLoad: false,
    loader: FakeLoader as unknown as HlsConfig['loader'],
    debug: SILENT,
  });
  players.push(hls);
  hls.on(Events.MANIFEST_PARSED, () => hls.startLoad(-1));
  hls.loadSource(VIDEO_URL);
  return { hls, state };
}

/** 지연 제어기에만 붙이는 영상 노드 — 재생 위치 · 앞 버퍼 · 배속을 시험이 쥔다 */
function attachToLatencyController(hls: Hls, currentTime: number): HTMLVideoElement {
  const media = document.createElement('video');
  Object.defineProperty(media, 'currentTime', { configurable: true, value: currentTime });
  Object.defineProperty(media, 'buffered', {
    configurable: true,
    value: { length: 1, start: () => 0, end: () => currentTime + 3 },
  });
  const lc = (hls as unknown as { latencyController: LatencyController }).latencyController;
  lc.onMediaAttached(Events.MEDIA_ATTACHED, { media });
  return media;
}

/** 가짜 loader 의 마이크로태스크 응답과 hls.js 의 0ms 타이머를 비우고, 목록 다시 받기를 reloads 번 돈다 */
async function run(reloads: number) {
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(0);
  for (let i = 0; i < reloads; i++) await vi.advanceTimersByTimeAsync(TD * 1000);
}

describe('J1 hls.js 는 앱이 건 배속을 덮지 않는다(maxLiveSyncPlaybackRate: 1)', () => {
  it('라이브 설정이면 timeupdate · 목록 전진 어디서도 1.1 을 그대로 둔다', async () => {
    const { hls } = player();
    await run(0);
    // 라이브 동기 범위(목표 + TD) 밖으로 밀린 자리 — 배속 추격이 켜져 있으면 hls.js 가 1 로 되돌리는 갈래다
    const media = attachToLatencyController(hls, 2);
    media.playbackRate = 1.1;
    media.dispatchEvent(new Event('timeupdate'));
    await run(2);
    media.dispatchEvent(new Event('timeupdate'));
    expect(hls.latency).toBeGreaterThan(PART_HOLD_BACK + TD);
    expect(media.playbackRate).toBe(1.1);
  });

  it('대조: 배속 추격을 켜면(maxLiveSyncPlaybackRate 1.1) 같은 자리에서 hls.js 가 배속을 1 로 되돌린다', async () => {
    const { hls } = player({ maxLiveSyncPlaybackRate: 1.1 });
    await run(0);
    const media = attachToLatencyController(hls, 2);
    media.playbackRate = 1.1;
    media.dispatchEvent(new Event('timeupdate'));
    expect(media.playbackRate).toBe(1);
  });
});

describe('J2 앱이 읽는 hls.js 값의 뜻(readHlsCatchup)', () => {
  it('목표는 PART-HOLD-BACK · 지연은 목록 끝 + age − 재생 위치 · TD 는 목록의 것 · 동기 위치 상한은 끝 − 파트 목표', async () => {
    const { hls } = player();
    await run(0);
    const media = attachToLatencyController(hls, 10);
    vi.setSystemTime(Date.now() + 1_000);
    media.dispatchEvent(new Event('timeupdate'));
    const details = hls.latestLevelDetails;
    if (details === null) throw new Error('목록이 없다');
    const reading = readHlsCatchup(hls, false);
    expect(details.live).toBe(true);
    expect(reading.targetLatencyS).toBe(PART_HOLD_BACK);
    expect(reading.list?.targetDurationS).toBe(TD);
    expect(reading.list?.ageS).toBeCloseTo(1, 1);
    expect(reading.latencyS).toBeCloseTo(details.edge + details.age - 10, 1);
    expect(reading.liveSyncPositionS).toBe(details.edge - PART_TARGET);
    expect(reading.handoffConfirming).toBe(false);
  });

  it('D3 age 는 목록이 마지막으로 전진한 뒤의 초 — 전진 없는 응답은 앞 전진 시각을 잇고 · 전진하면 0 으로', async () => {
    const { hls, state } = player();
    // 목록을 받을 때마다 그 순간의 age 와 전진 여부를 적는다
    const updates: Array<{ advanced: boolean; ageS: number }> = [];
    hls.on(Events.LEVEL_UPDATED, (...[, data]) => {
      updates.push({ advanced: data.details.advanced, ageS: data.details.age });
    });
    state.advance = false;
    await run(2);
    // 첫 응답 뒤로는 전진 없는 응답뿐이다 — age 는 첫 응답부터 센다(8초)
    const still = updates.slice(1);
    expect(still.length).toBeGreaterThan(0);
    expect(still.every((u) => !u.advanced && u.ageS > 0)).toBe(true);
    expect(readHlsCatchup(hls, false).list?.ageS).toBeCloseTo(2 * TD, 0);
    state.advance = true;
    updates.length = 0;
    await run(1);
    const moved = updates.filter((u) => u.advanced);
    expect(moved.length).toBeGreaterThan(0);
    expect(moved.every((u) => u.ageS < 0.1)).toBe(true);
  });
});
