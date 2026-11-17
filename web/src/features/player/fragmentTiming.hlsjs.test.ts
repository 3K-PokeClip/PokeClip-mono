import Hls, {
  Events,
  LoadStats,
  M3U8Parser,
  PlaylistLevelType,
  type HlsConfig,
  type LevelDetails,
  type LoaderCallbacks,
  type LoaderContext,
} from 'hls.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mediaTimeAt } from './dvrWindow';
import {
  TIMING_TAG,
  datedFragments,
  playlistDurationS,
  timingVersions,
  type HlsFragmentLike,
} from './fragmentTiming';
import { HLS_REWIND_CONFIG } from './hlsConfig';

// 판 태그 · 원래 EXTINF 를 진짜 hls.js 1.7.3(dist)이 읽은 조각에서 읽는다(설계 r7 3.2 · 10절 c2). 목록 글자는
// 진짜 파서로 읽고(rewindFloor.hlsjs.test.ts 와 같은 틀), 미닫이 갱신은 가짜 loader 로 목록을 두 번 받아
// LEVEL_UPDATED 의 details.fragments(mergeDetails 를 거친 조각)에서 본다(hlsLiveStart.hlsjs.test.ts 와 같은 틀).
// hls.js 판을 올려 태그가 가는 자리가 바뀌면 여기서 먼저 깨진다.

const URL = 'https://m/dvr/k/a/index.m3u8';
const PDT0 = Date.parse('2026-11-02T05:00:00.000Z');
const TARGET_DURATION_S = 6;
const SEGMENT_DURATION_S = 4;
const VERSION_2 = `#${TIMING_TAG}:2`;
const VERSION_NULL = `#${TIMING_TAG}:0`;

/** 조각 하나의 태그 줄 — before 는 PDT 앞(판 태그 자리) */
function segment(sn: number, before: readonly string[] = []): string[] {
  return [
    ...before,
    `#EXT-X-PROGRAM-DATE-TIME:${new Date(PDT0 + sn * SEGMENT_DURATION_S * 1000).toISOString()}`,
    `#EXTINF:${SEGMENT_DURATION_S.toFixed(3)},`,
    `seg${sn}.mp4`,
  ];
}

/** 미디어 순번 from 부터의 목록 글자 — head 는 MAP 앞, segments 는 MAP 뒤 */
function playlistText(
  from: number,
  segments: ReadonlyArray<string[]>,
  head: string[] = [],
): string {
  return [
    '#EXTM3U',
    '#EXT-X-VERSION:9',
    `#EXT-X-TARGETDURATION:${TARGET_DURATION_S}`,
    `#EXT-X-MEDIA-SEQUENCE:${from}`,
    ...head,
    '#EXT-X-MAP:URI="init.mp4"',
    ...segments.flat(),
  ].join('\n');
}

function parse(text: string): LevelDetails {
  return M3U8Parser.parseLevelPlaylist(text, URL, 0, PlaylistLevelType.MAIN, 0, null);
}

/** 판 태그 자리만 정하는 조각 n 개 · tags[i] 가 i 번째 조각 PDT 앞 줄 */
function versionsOf(tags: ReadonlyArray<readonly string[]>, head: string[] = []) {
  const details = parse(
    playlistText(
      0,
      tags.map((before, sn) => segment(sn, before)),
      head,
    ),
  );
  expect(details.fragments).toHaveLength(tags.length);
  return timingVersions(details.fragments);
}

/** tagList 만 정한 흉내 조각 — 나머지 칸은 이 시험이 보지 않는다 */
function fakeFragment(tagList: string[][]): HlsFragmentLike {
  return {
    start: 0,
    duration: SEGMENT_DURATION_S,
    programDateTime: null,
    gap: false,
    tagList,
    cc: 0,
    sn: 0,
  };
}

describe('timingVersions — 진짜 M3U8Parser 가 읽은 조각의 판', () => {
  it('태그가 없는 목록(옛 발행기 · 판 NULL)은 모두 null 이다', () => {
    expect(versionsOf([[], [], []])).toEqual([null, null, null]);
  });

  it('첫 조각 MAP 뒤 · PDT 앞의 태그 2 는 뒤 조각에 이어진다', () => {
    expect(versionsOf([[VERSION_2], [], []])).toEqual([2, 2, 2]);
  });

  it('중간 조각에서 2 로 바뀌면 그 조각부터 2 다', () => {
    expect(versionsOf([[], [], [VERSION_2], []])).toEqual([null, null, 2, 2]);
  });

  it('값 0 은 판 NULL 로 바뀌는 조각이다 — 그 조각부터 null 이다', () => {
    expect(versionsOf([[VERSION_2], [], [VERSION_NULL], []])).toEqual([2, 2, null, null]);
  });

  it('모르는 값(3 · 2.0 · 값 없음)은 판 NULL 로 읽는다', () => {
    expect(versionsOf([[VERSION_2], [`#${TIMING_TAG}:3`], []])).toEqual([2, null, null]);
    expect(versionsOf([[VERSION_2], [`#${TIMING_TAG}:2.0`]])).toEqual([2, null]);
    expect(versionsOf([[VERSION_2], [`#${TIMING_TAG}`]])).toEqual([2, null]);
  });

  it('한 조각에 태그가 둘이면 마지막 태그의 판이다', () => {
    expect(versionsOf([[VERSION_2, VERSION_NULL], []])).toEqual([null, null]);
    expect(versionsOf([[VERSION_NULL, VERSION_2], []])).toEqual([2, 2]);
  });

  it('반례: 목록 머리 줄(MAP 앞)의 태그는 초기화 조각으로 가 첫 조각이 받지 못한다', () => {
    const details = parse(playlistText(0, [segment(0), segment(1)], [VERSION_2]));
    expect(timingVersions(details.fragments)).toEqual([null, null]);
    expect(details.fragments[0]!.initSegment?.tagList).toContainEqual([TIMING_TAG, '2']);
  });

  it('반례: DISCONTINUITY 뒤 새 MAP 앞의 태그는 그 조각이 받지 못해 앞 판이 이어진다', () => {
    const wrong = parse(
      playlistText(0, [
        segment(0, [VERSION_2]),
        ['#EXT-X-DISCONTINUITY', VERSION_NULL, '#EXT-X-MAP:URI="init2.mp4"', ...segment(1)],
      ]),
    );
    expect(timingVersions(wrong.fragments)).toEqual([2, 2]);
    // 자리 규칙대로 새 MAP 뒤에 두면 그 조각이 받는다
    const right = parse(
      playlistText(0, [
        segment(0, [VERSION_2]),
        ['#EXT-X-DISCONTINUITY', '#EXT-X-MAP:URI="init2.mp4"', ...segment(1, [VERSION_NULL])],
      ]),
    );
    expect(timingVersions(right.fragments)).toEqual([2, null]);
  });

  it('빈 목록은 빈 판 목록이다', () => {
    expect(timingVersions([])).toEqual([]);
  });
});

describe('playlistDurationS — tagList 의 원래 EXTINF(초)', () => {
  it('진짜 파서가 tagList 에 남긴 INF 를 읽는다 — 제목이 있어도 같다', () => {
    const details = parse(
      playlistText(0, [
        ['#EXT-X-PROGRAM-DATE-TIME:2026-11-02T05:00:00.000Z', '#EXTINF:3.967,', 'a.mp4'],
        ['#EXT-X-PROGRAM-DATE-TIME:2026-11-02T05:00:03.967Z', '#EXTINF:4.033,title', 'b.mp4'],
      ]),
    );
    expect(details.fragments.map(playlistDurationS)).toEqual([3.967, 4.033]);
  });

  it('INF 가 없거나 숫자가 아니면 null 이다', () => {
    expect(playlistDurationS(fakeFragment([]))).toBeNull();
    expect(playlistDurationS(fakeFragment([['INF']]))).toBeNull();
    expect(playlistDurationS(fakeFragment([['INF', '']]))).toBeNull();
    expect(playlistDurationS(fakeFragment([['INF', 'abc']]))).toBeNull();
  });
});

// T-ovl 파서 몫(설계 r7 10절 c4 · 12.3) — 1초 미만 PDT 부분 겹침(B8)이 든 판 2 목록을 진짜 파서가 고치지 않고
// 글자 그대로 조각에 남기는가. 끝까지 트는 몫(브라우저)은 실측대 T-ovl 이다.
describe('T-ovl 파서 몫 — 겹친 PDT 목록', () => {
  /** PDT 를 글자로 정한 4초 조각 */
  function datedSegment(sn: number, pdt: string, before: readonly string[] = []): string[] {
    return [...before, `#EXT-X-PROGRAM-DATE-TIME:${pdt}`, '#EXTINF:4.000,', `seg${sn}.mp4`];
  }

  it('999 · 500 · 1ms 겹친 PDT 를 조각마다 글자대로 남기고, 시각 → 자리는 나중 조각이다', () => {
    const pdts = [
      '2026-11-02T05:00:00.000Z',
      // 앞 조각 끝(04.000)보다 999ms 이르다
      '2026-11-02T05:00:03.001Z',
      // 앞 조각 끝(07.001)보다 500ms 이르다
      '2026-11-02T05:00:06.501Z',
      // 앞 조각 끝(10.501)보다 1ms 이르다
      '2026-11-02T05:00:10.500Z',
    ];
    const details = parse(
      playlistText(
        0,
        pdts.map((pdt, sn) => datedSegment(sn, pdt, sn === 0 ? [VERSION_2] : [])),
      ),
    );

    expect(details.fragments.map((frag) => frag.programDateTime)).toEqual(pdts.map(Date.parse));
    const fragments = datedFragments(details.fragments, 'rewind', () => null);
    // 둘째 조각 PDT 03.001 뒤 0.5초 — 첫 조각(00.000 + 3.5초)이 아니라 둘째 조각 자리다
    expect(mediaTimeAt(fragments, Date.parse('2026-11-02T05:00:03.501Z'))).toBeCloseTo(4.5, 9);
  });
});

// 미닫이 갱신 — 진짜 Hls 의 목록 갱신 경로(playlist-loader → level-controller mergeDetails →
// stream-controller LEVEL_UPDATED). 매체는 붙이지 않는다(목록 받기만 본다 — 조각은 청하지 않는다).
// 전제: /dvr 는 델타 갱신(EXT-X-SKIP)을 광고하지 않는다(계약3 7-5). 델타 목록이면 hls.js 가 건너뛴
// 자리를 옛 조각 객체로 채워 이 시험이 보는 경로가 아니다. /vod 는 VOD 목록을 다시 받지 않아 델타 갱신이 없다
// (base-playlist-controller 라이브 갈래).
describe('미닫이 갱신 — 진짜 Hls 의 LEVEL_UPDATED 조각', () => {
  /** 가짜 loader 의 load 인자 — 설정(가운데)은 쓰지 않는다 */
  type LoadArgs = [LoaderContext, unknown, LoaderCallbacks<LoaderContext>];
  const quiet = () => {};
  const silentLogger = {
    trace: quiet,
    debug: quiet,
    log: quiet,
    warn: quiet,
    info: quiet,
    error: quiet,
  };
  const players: Hls[] = [];

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
  });
  afterEach(() => {
    for (const hls of players.splice(0)) {
      hls.destroy();
    }
    vi.useRealTimers();
  });

  /** 받을 때마다 lists 의 다음 글자를 주는(끝이면 마지막 것을 되풀이) 가짜 loader 를 단 재생기 */
  function player(lists: readonly string[]) {
    let served = 0;
    class FakeLoader {
      stats = new LoadStats();
      load(...[context, , callbacks]: LoadArgs) {
        if (context.url !== URL) return;
        const data = lists[Math.min(served++, lists.length - 1)]!;
        queueMicrotask(() => {
          // 받은 시각을 찍는다 — 0 이면 다음 받기 예약이 늘 과거라 hls.js 가 쉬지 않고 다시 받는다
          const now = performance.now();
          this.stats.loading = { start: now, first: now, end: now };
          callbacks.onSuccess({ url: URL, data }, this.stats, context, null);
        });
      }
      abort() {}
      destroy() {}
    }
    const hls = new Hls({
      ...HLS_REWIND_CONFIG,
      autoStartLoad: false,
      loader: FakeLoader as unknown as HlsConfig['loader'],
      debug: silentLogger,
    });
    players.push(hls);
    const updates: LevelDetails[] = [];
    hls.on(Events.LEVEL_UPDATED, (...[, data]) => {
      updates.push(data.details);
      // 받은 조각의 PTS 를 hls.js 가 잰 것처럼 적는다 — 다음 갱신의 mergeDetails 가 duration 을 다시 잡는다
      for (const frag of data.details.fragments) {
        frag.startPTS = frag.start;
        frag.endPTS = frag.start + SEGMENT_DURATION_S + 0.5;
      }
    });
    hls.on(Events.MANIFEST_PARSED, () => hls.startLoad(0));
    hls.loadSource(URL);
    return updates;
  }

  async function reload(times: number) {
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);
    for (let i = 0; i < times; i++) {
      await vi.advanceTimersByTimeAsync(TARGET_DURATION_S * 1000);
    }
  }

  it('앞 조각이 빠진 둘째 목록에서도 새 첫 조각의 판 태그와 원래 EXTINF 가 남는다', async () => {
    const first = playlistText(0, [segment(0, [VERSION_2]), segment(1), segment(2), segment(3)]);
    // 발행기는 목록 첫 조각(판 2)에 태그를 다시 싣는다(계약3 7-6)
    const second = playlistText(1, [segment(1, [VERSION_2]), segment(2), segment(3), segment(4)]);
    const updates = player([first, second]);
    await reload(1);

    expect(updates.length).toBeGreaterThanOrEqual(2);
    const merged = updates[1]!.fragments;
    expect(merged.map((frag) => frag.sn)).toEqual([1, 2, 3, 4]);
    expect(timingVersions(merged)).toEqual([2, 2, 2, 2]);
    // hls.js 는 겹친 조각의 duration 을 앞 목록 PTS 로 다시 잡는다 — tagList 의 INF 는 그대로다
    expect(merged.map((frag) => frag.duration)).not.toEqual([
      SEGMENT_DURATION_S,
      SEGMENT_DURATION_S,
      SEGMENT_DURATION_S,
      SEGMENT_DURATION_S,
    ]);
    expect(merged.map(playlistDurationS)).toEqual([
      SEGMENT_DURATION_S,
      SEGMENT_DURATION_S,
      SEGMENT_DURATION_S,
      SEGMENT_DURATION_S,
    ]);
  });
});
