import { beforeEach, describe, expect, it } from 'vitest';
import type { DatedFragment } from './dvrWindow';
import {
  OPEN_STREAM_FACTS,
  endStalled,
  liveStartFloorMs,
  recordingEndedOf,
  streamFactsAfter,
  sessionAxis,
  loadedList,
  startIsTruncated,
  startCut,
  startLoadPosition,
  windowLowerMs,
  type LoadedList,
} from './playbackEdge';
import { guardRewindFloor, type RewindFloorInput } from './rewindFloor';
import type { RewindStatusResult } from '@/api/rewindStatus';
import { resetEventKit } from './handoffTestKit';
import { play, playWatching, type Played, type Step } from './recordingEndTestKit';
import {
  A1,
  A2,
  END_REACHED,
  ENDLIST,
  MIN,
  NOW,
  P20,
  T0,
  X_A,
  X_B,
  X_UNKNOWN,
  fresh,
  gone,
  liveReady,
  poll,
  rewindAt,
  status,
  vodReady,
} from './relistTestKit';
import type { PlaybackSource, SessionEvent, XAnswer } from './rewindSwitch';

// 창 가장자리 · 시작 위치 · 끝 정체 판정(설계 5.3.1 · 5.4절 — R15 · R23 · R47 의 순수 몫)

const PDT0 = Date.parse('2026-10-25T05:00:00.000Z');
/** 영상 10초부터 4초 조각 다섯(PDT0 부터 20초) */
const FRAGS: DatedFragment[] = [0, 1, 2, 3, 4].map((i) => ({
  start: 10 + i * 4,
  duration: 4,
  programDateTime: PDT0 + i * 4_000,
}));
const LIST: LoadedList = {
  firstMs: PDT0,
  lastEndMs: PDT0 + 20_000,
  lastStartMs: PDT0 + 16_000,
  targetDurationS: 4,
  headSpanMs: 12_000,
  contentFirstMs: PDT0,
  rewindHeadSpanMs: 4_000,
};

/**
 * gohlslib 모양 — TD 는 지금까지 본 조각 최대값으로 올라가기만 해 6 에 머물고 실제 조각은 4초다.
 * durations 는 맨 앞부터 조각 길이(초)다(영상 0초 · PDT0 부터 이어 붙음)
 */
function fragsOf(durations: readonly number[]): DatedFragment[] {
  let start = 0;
  return durations.map((duration) => {
    const frag = { start, duration, programDateTime: PDT0 + start * 1000 };
    start += duration;
    return frag;
  });
}

/** durations 조각 가운데 gapAt 자리는 EXT-X-GAP 조각이다(PDT 는 이어 센다) */
function withGaps(durations: readonly number[], gapAt: readonly number[]): DatedFragment[] {
  return fragsOf(durations).map((frag, i) => (gapAt.includes(i) ? { ...frag, gap: true } : frag));
}

describe('loadedList', () => {
  it('첫 조각 시각 · 마지막 조각 끝 · 마지막 조각 시작 · TD · 맨 앞 조각 셋의 길이 합 · 첫 내용 조각 시각 · 그 조각 길이를 낸다', () => {
    expect(loadedList(FRAGS, 4)).toEqual(LIST);
  });

  it('맨 앞 조각 길이 합은 조각이 셋 미만이면 있는 만큼이다 — TD 와 무관하다', () => {
    expect(loadedList(fragsOf([6, 4, 4, 4]), 6)?.headSpanMs).toBe(14_000);
    expect(loadedList(fragsOf([6, 4]), 6)?.headSpanMs).toBe(10_000);
    expect(loadedList(fragsOf([6]), 6)?.headSpanMs).toBe(6_000);
  });

  it('조각이 없거나 시각 표시가 없으면 null', () => {
    expect(loadedList([], 4)).toBeNull();
    expect(loadedList([{ start: 0, duration: 4, programDateTime: null }], 4)).toBeNull();
  });
});

describe('windowLowerMs — 라이브는 첫 조각 + 맨 앞 조각 셋 · 방송 중 되감기는 첫 내용 조각 + 그 조각(vod 는 여유 0)', () => {
  it('live 는 4초 조각 셋인 12초 · 방송 중 rewind 는 조각 하나인 4초 여유를 둔다', () => {
    expect(windowLowerMs(LIST, 'live', true)).toBe(PDT0 + 12_000);
    expect(windowLowerMs(LIST, 'rewind', true)).toBe(PDT0 + 4_000);
  });

  it('vod 는 ENDLIST 문서라 창이 밀리지 않아 여유가 없다(R47)', () => {
    expect(windowLowerMs(LIST, 'vod', false)).toBe(PDT0);
  });

  it('TD 6 에 첫 조각 6초 · 나머지 4초면 여유는 TD 가 아니라 실제 조각 길이다', () => {
    const withFirst = loadedList(fragsOf([6, 4, 4, 4, 4]), 6)!;
    expect(windowLowerMs(withFirst, 'live', true)).toBe(PDT0 + 14_000);
    // 첫 6초 조각이 창에서 빠진 다음 목록
    const afterFirst = loadedList(fragsOf([6, 4, 4, 4, 4, 4]).slice(1), 6)!;
    expect(windowLowerMs(afterFirst, 'live', true)).toBe(PDT0 + 6_000 + 12_000);
  });

  it('조각 하나뿐이면 그 조각 길이만큼 둔다', () => {
    expect(windowLowerMs(loadedList(fragsOf([6]), 6)!, 'rewind', true)).toBe(PDT0 + 6_000);
  });
});

describe('startLoadPosition — 첫 목록에서 시작 위치(영상 초)를 고른다(5.4절)', () => {
  it('목록 안 시각은 그 영상 초다', () => {
    expect(startLoadPosition(FRAGS, PDT0 + 14_000, 'rewind', 4, true)).toBe(24);
  });

  it('live · rewind 는 창 하한 앞을 하한으로 자른다(R23)', () => {
    expect(startLoadPosition(FRAGS, PDT0, 'rewind', 4, true)).toBe(14);
  });

  it('vod 는 첫 조각 시작 그대로 연다 — 2 TD VOD 도 첫 조각에서(R47)', () => {
    const short = FRAGS.slice(0, 2);
    expect(startLoadPosition(short, PDT0, 'vod', 4, false)).toBe(10);
  });

  it('목록 끝 뒤는 끝으로 자른다', () => {
    expect(startLoadPosition(FRAGS, PDT0 + 99_000, 'rewind', 4, true)).toBe(30);
  });

  it('시각 표시가 없으면 null(부르는 쪽이 기본 시작)', () => {
    expect(
      startLoadPosition([{ start: 0, duration: 4, programDateTime: null }], PDT0, 'vod', 4, false),
    ).toBeNull();
  });
});

/** 맨 앞부터 [영상 시작, PDT0 뒤 ms] 의 4초 조각 */
function placed(points: ReadonlyArray<readonly [number, number]>): DatedFragment[] {
  return points.map(([start, afterMs]) => ({
    start,
    duration: 4,
    programDateTime: PDT0 + afterMs,
  }));
}

describe('startCut — 첫 목록의 시작 자리와 창 하한으로 잘렸는가(POK-528 c7 · 계획 3-3 (2))', () => {
  it.each<[string, number, { position: number; floorCut: boolean }]>([
    ['첫 조각 앞 → 하한으로 · 잘림', PDT0 - 5_000, { position: 14, floorCut: true }],
    ['하한 앞 → 하한으로 · 잘림', PDT0 + 2_000, { position: 14, floorCut: true }],
    ['하한 = 요청 → 그대로 · 잘림 아님(경계)', PDT0 + 4_000, { position: 14, floorCut: false }],
    ['하한 위 → 그대로', PDT0 + 14_000, { position: 24, floorCut: false }],
    ['끝 뒤 → 끝으로 · 잘림 아님', PDT0 + 99_000, { position: 30, floorCut: false }],
  ])('평목록(방송 중 되감기 · 하한 영상 14초): %s', (_name, startAtMs, expected) => {
    expect(startCut(FRAGS, startAtMs, 'rewind', 4, true)).toEqual(expected);
    expect(startLoadPosition(FRAGS, startAtMs, 'rewind', 4, true)).toBe(expected.position);
  });

  it('틈 목록 — 요청 16초는 틈 안이라 다음 조각(영상 8초)이다: 라이브 하한(12초) 앞이라 잘리고(epoch 비교는 거짓) · 방송 중 되감기 하한(4초) 위라 그대로', () => {
    const gap = placed([
      [0, 0],
      [4, 4_000],
      [8, 20_000],
      [12, 24_000],
      [16, 28_000],
    ]);
    expect(startCut(gap, PDT0 + 16_000, 'live', 4, true)).toEqual({ position: 12, floorCut: true });
    expect(startCut(gap, PDT0 + 16_000, 'rewind', 4, true)).toEqual({
      position: 8,
      floorCut: false,
    });
  });

  it('겹침 목록 — 요청 11.8초는 나중 조각의 영상 13.8초라 라이브 하한(12초) 위 · 잘리지 않는다(epoch 비교는 참)', () => {
    const overlap = placed([
      [0, 0],
      [4, 2_000],
      [8, 6_000],
      [12, 10_000],
      [16, 14_000],
    ]);
    const cut = startCut(overlap, PDT0 + 11_800, 'live', 4, true);
    expect(cut?.floorCut).toBe(false);
    expect(cut?.position).toBeCloseTo(13.8);
    // 방송 중 되감기 하한(영상 4초)으로도 같은 자리다
    expect(startCut(overlap, PDT0 + 11_800, 'rewind', 4, true)?.position).toBeCloseTo(13.8);
  });

  it('vod 는 여유가 없어 첫 조각에서 잘리지 않는다 · 시각 표시가 없으면 null', () => {
    expect(startCut(FRAGS, PDT0, 'vod', 4, false)).toEqual({ position: 10, floorCut: false });
    expect(
      startCut([{ start: 0, duration: 4, programDateTime: null }], PDT0, 'rewind', 4, true),
    ).toBeNull();
  });
});

/** 하한 한 갈래 — 종류 · 방송 중인가 · 기대 하한(PDT0 뒤 초 = 영상 초) */
type LowerRow = [string, PlaybackSource['kind'], boolean, number];

describe('되감기 · 다시보기 하한 — 첫 내용 조각 기준 · 방송 중 되감기만 그 조각 하나의 여유', () => {
  // 맨 앞 GAP 하나(6초) 뒤 내용 5초 · 4초 셋 — 라이브 셋 15초 · 방송 중 되감기 11초 · 끝난 목록 · vod 6초가 갈린다
  const GAPPED = withGaps([6, 5, 4, 4, 4], [0]);
  // GAP 없이 첫 조각 5초 — 셋 13초 · 하나 5초 · 0
  const PLAIN = fragsOf([5, 4, 4, 4, 4]);

  it.each<LowerRow>([
    ['방송 중 되감기 = 첫 내용 조각 + 그 조각 길이', 'rewind', true, 11],
    ['끝난 되감기 목록(ENDLIST) = 첫 내용 조각', 'rewind', false, 6],
    ['다시보기 = 첫 내용 조각', 'vod', false, 6],
    ['라이브 = 첫 조각 + 맨 앞 조각 셋 그대로', 'live', true, 15],
    ['라이브는 방송 중 여부와 무관하다', 'live', false, 15],
  ])('맨 앞 GAP 목록: %s', (_name, kind, live, lowerS) => {
    const list = loadedList(GAPPED, 6)!;
    expect(windowLowerMs(list, kind, live)).toBe(PDT0 + lowerS * 1000);
    expect(startCut(GAPPED, PDT0, kind, 6, live)).toEqual({ position: lowerS, floorCut: true });
  });

  it.each<LowerRow>([
    ['방송 중 되감기 = 첫 조각 + 그 조각 길이', 'rewind', true, 5],
    ['끝난 되감기 목록(ENDLIST) = 첫 조각', 'rewind', false, 0],
    ['다시보기 = 첫 조각', 'vod', false, 0],
    ['라이브 = 첫 조각 + 맨 앞 조각 셋 그대로', 'live', true, 13],
  ])('GAP 없는 목록: %s', (_name, kind, live, lowerS) => {
    const list = loadedList(PLAIN, 6)!;
    expect(windowLowerMs(list, kind, live)).toBe(PDT0 + lowerS * 1000);
    expect(startCut(PLAIN, PDT0, kind, 6, live)).toEqual({
      position: lowerS,
      floorCut: lowerS > 0,
    });
  });

  it('loadedList 는 첫 내용 조각 시각과 그 조각 길이를 싣는다', () => {
    expect(loadedList(GAPPED, 6)).toMatchObject({
      firstMs: PDT0,
      contentFirstMs: PDT0 + 6_000,
      rewindHeadSpanMs: 5_000,
      headSpanMs: 15_000,
    });
  });

  it('첫 내용 조각 다음이 GAP 이면 하한은 그 GAP 시작(첫 내용 조각 끝)이다', () => {
    const frags = withGaps([4, 4, 4, 4], [1]);
    expect(windowLowerMs(loadedList(frags, 4)!, 'rewind', true)).toBe(PDT0 + 4_000);
    expect(startCut(frags, PDT0, 'rewind', 4, true)).toEqual({ position: 4, floorCut: true });
  });

  it.each<[string, DatedFragment[], number]>([
    ['GAP 둘 뒤 내용 4초', withGaps([6, 6, 4, 4, 4], [0, 1]), 6],
    [
      'MediaMTX 모양 GAP 셋(5.933초) 뒤 내용 4초',
      withGaps([5.933, 5.933, 5.933, 4, 4, 4], [0, 1, 2]),
      6,
    ],
    ['모두 GAP — 첫 조각 + 1 × TD', withGaps([6, 6, 6, 6], [0, 1, 2, 3]), 6],
  ])('맨 앞 GAP 에서 재생기 하한과 창 하한 지키기의 하한이 같다: %s', (_name, frags, td) => {
    const lowerMs = windowLowerMs(loadedList(frags, td)!, 'rewind', true);
    const cut = startCut(frags, PDT0 - 1_000, 'rewind', td, true);
    const details = { fragmentStart: 0, targetduration: td, live: true, fragments: frags };
    const media = { currentTime: -0.5, readyState: 4 };
    const hls = { hasEnoughToStart: true, startLoad: () => {} };
    const move = guardRewindFloor({
      hls,
      video: media,
      details: details as unknown as RewindFloorInput['details'],
      loadFrom: null,
    });
    expect(move?.to).toBeDefined();
    expect(cut?.position).toBeCloseTo(move!.to, 9);
    expect((lowerMs - PDT0) / 1000).toBeCloseTo(move!.to, 9);
  });
});

describe('끝난 되감기 목록의 시작 — 한 시간을 넘어도 첫 내용 조각', () => {
  // 맨 앞 GAP 하나 뒤 4초 조각 900 개(합 3606초)
  const LONG = withGaps([6, ...Array<number>(900).fill(4)], [0]);

  it('ENDLIST 면 startCut · startLoadPosition 이 첫 내용 조각(영상 6초)이다', () => {
    expect(startCut(LONG, PDT0, 'rewind', 6, false)).toEqual({ position: 6, floorCut: true });
    expect(startLoadPosition(LONG, PDT0, 'rewind', 6, false)).toBe(6);
  });

  it('방송 중이면 같은 목록이 첫 내용 조각 + 그 조각 하나(영상 10초)다', () => {
    expect(startLoadPosition(LONG, PDT0, 'rewind', 6, true)).toBe(10);
  });
});

describe('liveStartFloorMs · startIsTruncated — 라이브 시작 하한 S_live(POK-200 c13 · S10)', () => {
  const NO_PDT: DatedFragment[] = [{ start: 0, duration: 4, programDateTime: null }];

  it('하한 = 첫 조각 + 맨 앞 조각 셋 길이의 실제 시각 — 창 하한(windowLowerMs)과 같은 식이다', () => {
    expect(liveStartFloorMs(FRAGS, 4)).toBe(PDT0 + 12_000);
    // 목록 TD 가 6 이어도 창이 밀리는 단위는 4초 조각이다
    expect(liveStartFloorMs(FRAGS, 6)).toBe(PDT0 + 12_000);
  });

  it('M5c 실측 모양 — TD 6 · 4초 조각에서 라이브 끝 8초 앞 P 는 하한 뒤라 잘리지 않는다', () => {
    // 2026-10-28 M5c: 하한 = 끝 − 6.2초(3 × TD) 로 P = 끝 − 8.0초를 1.8초 잘랐다
    const frags = fragsOf([4.2, 4, 4, 4, 4, 4]);
    const endMs = PDT0 + 24_200;
    const p = endMs - 8_000;
    expect(liveStartFloorMs(frags, 6)).toBe(endMs - 12_000);
    expect(startIsTruncated(frags, p, 'live', 6)).toBe(false);
  });

  it('시각 표시가 없으면 하한을 모른다(null)', () => {
    expect(liveStartFloorMs(NO_PDT, 4)).toBeNull();
  });

  it('맨 앞 GAP 조각은 내용이 아니다 — 하한은 첫 내용 조각 + 내용 조각 셋 길이다(M5b 실측 모양)', () => {
    // GAP 여섯(5.933초) 뒤 내용 4초 조각 셋 — GAP 을 세면 하한이 첫 GAP + 17.8초로 내용 앞에 선다
    const frags = withGaps([5.933, 5.933, 5.933, 5.933, 5.933, 5.933, 4, 4, 4], [0, 1, 2, 3, 4, 5]);
    const contentFirst = PDT0 + 35_598;
    expect(liveStartFloorMs(frags, 6)).toBe(contentFirst + 12_000);
    expect(startIsTruncated(frags, contentFirst + 11_999, 'live', 6)).toBe(true);
    expect(startIsTruncated(frags, contentFirst + 12_000, 'live', 6)).toBe(false);
  });

  it('가운데 GAP 은 창이 밀리는 조각이라 여유에 그대로 센다 — 빼는 것은 맨 앞 GAP 뿐이다', () => {
    // 세 번 밀린 창은 GAP 을 지나 c3(첫 조각 + 14초)에서 시작한다 — GAP 을 빼 12초로 재면 그 앞에 선다
    const frags = withGaps([4, 6, 4, 4, 4], [1]);
    expect(liveStartFloorMs(frags, 6)).toBe(PDT0 + 14_000);
    expect(startIsTruncated(frags, PDT0 + 13_999, 'live', 6)).toBe(true);
  });

  it('내용 조각이 셋보다 적으면 있는 만큼 · 모두 GAP 이면 하한이 없다(넘기지 않는다)', () => {
    expect(liveStartFloorMs(withGaps([6, 6, 4], [0, 1]), 6)).toBe(PDT0 + 16_000);
    const allGap = withGaps([6, 6, 6], [0, 1, 2]);
    expect(liveStartFloorMs(allGap, 6)).toBeNull();
    expect(startIsTruncated(allGap, PDT0, 'live', 6)).toBe(false);
  });

  it('시작이 하한 이상이면 잘리지 않고 하한보다 1ms 라도 앞이면 잘린다', () => {
    expect(startIsTruncated(FRAGS, PDT0 + 12_000, 'live', 4)).toBe(false);
    expect(startIsTruncated(FRAGS, PDT0 + 11_999, 'live', 4)).toBe(true);
    expect(startIsTruncated(FRAGS, PDT0 + 11_999, 'live', 6)).toBe(true);
  });

  it('vod 는 여유가 없어 첫 조각부터 잘리지 않는다', () => {
    expect(startIsTruncated(FRAGS, PDT0, 'vod', 4)).toBe(false);
  });

  it('시각 표시가 없으면 판정할 수 없어 잘림으로 보지 않는다 — startLoadPosition 도 null 이다', () => {
    expect(startIsTruncated(NO_PDT, PDT0, 'live', 4)).toBe(false);
  });
});

describe('endStalled — 목록 끝 정체(D9)', () => {
  const base = { list: LIST, atMs: PDT0 + 19_500, waiting: true, sinceGrowMs: 12_001 };

  it('끝 1초 안 ∧ waiting ∧ 3 × TD 넘게 목록이 자라지 않음이면 끝 도달이다', () => {
    expect(endStalled(base)).toBe(true);
  });

  it('셋 중 하나라도 아니면 끝 도달이 아니다', () => {
    expect(endStalled({ ...base, waiting: false })).toBe(false);
    expect(endStalled({ ...base, atMs: PDT0 + 18_000 })).toBe(false);
    expect(endStalled({ ...base, sinceGrowMs: 12_000 })).toBe(false);
  });
});

describe('sessionAxis — 시크바 하나 · 실제 시각 축(설계 5.2절)', () => {
  const at = (mediaSeconds: number) => ({
    fragments: FRAGS,
    targetDurationS: 4,
    currentTime: mediaSeconds,
    nowMs: PDT0 + 23_000,
    lagMs: 3_000,
  });

  it('라이브는 라이브 끝 시각이 끝 · 시크바 왼쪽은 되감기 범위 첫 시각(axisLowerMs)이다', () => {
    const axis = sessionAxis({
      ...at(26),
      kind: 'live',
      live: true,
      liveEdgeTime: 28,
      axisLowerMs: PDT0 - 600_000,
    });
    expect(axis).toMatchObject({
      atMs: PDT0 + 16_000,
      edgeMs: PDT0 + 18_000,
      view: { lowerMs: PDT0 + 12_000, endMs: PDT0 + 18_000 },
      windowSeconds: 618,
      behindSeconds: 2,
    });
  });

  it('되감기는 끝을 「지금 − 라이브 지연」으로 센다 · 창 끝은 목록 끝(E)이다', () => {
    const axis = sessionAxis({
      ...at(14),
      kind: 'rewind',
      live: true,
      liveEdgeTime: null,
      axisLowerMs: null,
    });
    expect(axis).toMatchObject({
      atMs: PDT0 + 4_000,
      edgeMs: PDT0 + 20_000,
      view: { lowerMs: PDT0 + 4_000, endMs: PDT0 + 20_000 },
      windowSeconds: 20,
      behindSeconds: 16,
    });
  });

  describe('되감기의 오른쪽 끝 — 방송 중은 「지금 − 라이브 지연」 · 방송이 끝난 쪽 국면은 로드한 목록 끝', () => {
    /** 목록 끝(PDT0 + 20 000)보다 40초 뒤의 벽시계 — 방송이 끝나고 끝남 대기가 흐르는 동안 */
    const lateMs = PDT0 + 60_000;
    const rewindAt = (
      mediaSeconds: number,
      nowMs: number,
      flags: { live: boolean; recordingEnded?: boolean },
    ) =>
      sessionAxis({
        ...at(mediaSeconds),
        nowMs,
        kind: 'rewind',
        ...flags,
        liveEdgeTime: null,
        axisLowerMs: null,
      });
    /** 끝남 대기의 정상 흐름 — 목록에 ENDLIST 가 붙었다 */
    const sealed = { live: false, recordingEnded: true };

    it('끝남 대기는 벽시계가 흘러도 끝이 목록 끝이다', () => {
      expect(rewindAt(14, lateMs, sealed)).toMatchObject({
        atMs: PDT0 + 4_000,
        edgeMs: PDT0 + 20_000,
        view: { lowerMs: PDT0, endMs: PDT0 + 20_000 },
        windowSeconds: 20,
        behindSeconds: 16,
      });
    });

    it('끝남 대기에서 재생이 앞으로 가면 시차가 그만큼 줄고 창 폭은 녹화 길이에 머문다', () => {
      const before = rewindAt(14, lateMs, sealed);
      const after = rewindAt(18, lateMs + 4_000, sealed);
      expect([before?.behindSeconds, after?.behindSeconds]).toEqual([16, 12]);
      expect([before?.windowSeconds, after?.windowSeconds]).toEqual([20, 20]);
    });

    it.each([10, 14, 22, 29.5])(
      '끝남 대기의 시차는 녹화 길이를 넘지 않는다 — 재생 위치 %d초',
      (mediaSeconds) => {
        const axis = rewindAt(mediaSeconds, lateMs, sealed);
        expect(axis?.windowSeconds).toBe(20);
        expect(axis?.behindSeconds).toBeLessThanOrEqual(20);
      },
    );

    it('끝에 닿은 뒤 벽시계가 몇 분 흘러도 시차 · 창 폭이 커지지 않는다', () => {
      const atEnd = rewindAt(30, lateMs, sealed);
      const later = rewindAt(30, lateMs + 300_000, sealed);
      expect([atEnd?.behindSeconds, later?.behindSeconds]).toEqual([0, 0]);
      expect([atEnd?.windowSeconds, later?.windowSeconds]).toEqual([20, 20]);
    });

    it('ENDLIST 전에 404 로 막힌 끝남 대기(목록은 아직 live)도 끝이 목록 끝이다', () => {
      expect(rewindAt(14, lateMs, { live: true, recordingEnded: true })).toMatchObject({
        edgeMs: PDT0 + 20_000,
        windowSeconds: 20,
        behindSeconds: 16,
      });
    });

    it('방송 중 재접속을 기다리는 되감기는 앞 목록에 ENDLIST 가 붙어도 끝이 「지금 − 라이브 지연」이다', () => {
      expect(rewindAt(14, lateMs, { live: false, recordingEnded: false })).toMatchObject({
        edgeMs: PDT0 + 57_000,
        windowSeconds: 57,
        behindSeconds: 53,
      });
    });

    it('방송 중 목록은 끝이 지금도 「지금 − 라이브 지연」이다 — 목록 끝보다 뒤여도', () => {
      expect(rewindAt(14, lateMs, { live: true })).toMatchObject({
        edgeMs: PDT0 + 57_000,
        view: { lowerMs: PDT0 + 4_000, endMs: PDT0 + 20_000 },
        windowSeconds: 57,
        behindSeconds: 53,
      });
    });
  });

  it('라이브는 방송이 끝난 쪽 국면이어도 라이브 끝 시각이 끝이다', () => {
    const axis = sessionAxis({
      ...at(26),
      nowMs: PDT0 + 60_000,
      kind: 'live',
      live: true,
      recordingEnded: true,
      liveEdgeTime: 28,
      axisLowerMs: null,
    });
    expect(axis).toMatchObject({ edgeMs: PDT0 + 18_000, behindSeconds: 2 });
  });

  it('vod 는 벽시계와 무관하게 문서 끝이 끝이다', () => {
    const axis = sessionAxis({
      ...at(14),
      nowMs: PDT0 + 60_000,
      kind: 'vod',
      live: false,
      liveEdgeTime: null,
      axisLowerMs: null,
    });
    expect(axis).toMatchObject({ edgeMs: PDT0 + 20_000, windowSeconds: 20, behindSeconds: 16 });
  });
  it('vod 는 문서 범위가 축이다(여유 0)', () => {
    const axis = sessionAxis({
      ...at(14),
      kind: 'vod',
      live: false,
      liveEdgeTime: null,
      axisLowerMs: PDT0 - 1,
    });
    expect(axis).toMatchObject({
      view: { lowerMs: PDT0, endMs: PDT0 + 20_000 },
      windowSeconds: 20,
    });
  });

  it('라이브 끝을 모르면(목록 전 · seekable 없음) null', () => {
    expect(
      sessionAxis({ ...at(14), kind: 'live', live: true, liveEdgeTime: null, axisLowerMs: null }),
    ).toBeNull();
  });

  describe('시크바 왼쪽 끝(barLowerMs) — 되감기 · 다시보기는 첫 내용 조각 · 라이브는 되감기 범위까지 넓힌다', () => {
    /** 맨 앞 GAP 둘 · 4초 내용 조각 넷(첫 내용 조각 = PDT0 + 8 000 · 끝 = PDT0 + 24 000) */
    const gapped = {
      fragments: withGaps([4, 4, 4, 4, 4, 4], [0, 1]),
      targetDurationS: 4,
      currentTime: 14,
      nowMs: PDT0 + 27_000,
      lagMs: 3_000,
      liveEdgeTime: null,
      /** 낡은 되감기 범위 첫 시각 — 지금 목록 첫 조각보다 10분 이르다 */
      axisLowerMs: PDT0 - 600_000,
    };

    it('방송 중 되감기는 낡은 axisLowerMs 를 쓰지 않고 첫 내용 조각이 왼쪽 끝 · 하한은 그 조각 하나 안쪽이다', () => {
      const axis = sessionAxis({ ...gapped, kind: 'rewind', live: true });
      expect(axis).toMatchObject({
        barLowerMs: PDT0 + 8_000,
        edgeMs: PDT0 + 24_000,
        view: { lowerMs: PDT0 + 12_000, endMs: PDT0 + 24_000 },
        windowSeconds: 16,
      });
    });

    it('끝난 되감기 목록(ENDLIST)은 왼쪽 끝과 하한이 같은 첫 내용 조각이다', () => {
      const axis = sessionAxis({ ...gapped, kind: 'rewind', live: false });
      expect(axis).toMatchObject({
        barLowerMs: PDT0 + 8_000,
        view: { lowerMs: PDT0 + 8_000, endMs: PDT0 + 24_000 },
      });
    });

    it('vod 도 첫 내용 조각이 왼쪽 끝 · 하한이고 폭은 그 조각부터 문서 끝까지다', () => {
      const axis = sessionAxis({ ...gapped, kind: 'vod', live: false });
      expect(axis).toMatchObject({
        barLowerMs: PDT0 + 8_000,
        view: { lowerMs: PDT0 + 8_000, endMs: PDT0 + 24_000 },
        windowSeconds: 16,
      });
    });

    it('라이브는 되감기 범위 첫 시각(axisLowerMs)으로 넓힌다 — 하한은 맨 앞 조각 셋 그대로', () => {
      const axis = sessionAxis({ ...gapped, kind: 'live', live: true, liveEdgeTime: 22 });
      expect(axis).toMatchObject({
        barLowerMs: PDT0 - 600_000,
        view: { lowerMs: PDT0 + 12_000, endMs: PDT0 + 22_000 },
        windowSeconds: 622,
      });
    });

    it('라이브에서 되감기 범위를 모르면 지금 목록 첫 조각이다(GAP 포함)', () => {
      const axis = sessionAxis({
        ...gapped,
        kind: 'live',
        live: true,
        liveEdgeTime: 22,
        axisLowerMs: null,
      });
      expect(axis?.barLowerMs).toBe(PDT0);
    });
  });
});

describe('recordingEndedOf — 끝났다는 확인 · 스트림이 아직 라이브인가(실제 세션 사건 순서)', () => {
  beforeEach(resetEventKit);

  const endlist: Step = () => ENDLIST;
  /** 보던 자리 P20 을 덮지 않는 같은 회차 새 url A2 의 답(재접속) */
  const reconnected =
    (x: XAnswer = X_A): Step =>
    () =>
      poll(liveReady(A2, T0 + 50 * MIN), x);
  const notLive: Step = () => poll(status({ mode: 'ending' }));
  const streamVod =
    (x: XAnswer = X_A): Step =>
    () =>
      poll(vodReady(), x);
  const unavailable: Step = () => poll({ kind: 'unavailable' });
  const segmentGone: Step = (state) => gone(state);
  const ended = (played: Played) => recordingEndedOf(played.state, played.facts);

  it('방송 중 되감기는 아니다', () => {
    expect(ended(playWatching())).toBe(false);
  });

  it('세션을 열고 첫 스트림 답 전에 ENDLIST 가 와도 아니다', () => {
    expect(ended(play(rewindAt(P20), [endlist]))).toBe(false);
  });

  it('정상 종료 — ENDLIST 뒤 다음 답이 정리 중(ending)이면 한 폴링 안에 그렇다', () => {
    expect(ended(playWatching(endlist))).toBe(false);
    expect(ended(playWatching(endlist, notLive))).toBe(true);
  });

  it('정상 종료 — 그 뒤 회차 다시보기가 제안돼도 그렇다', () => {
    const played = playWatching(endlist, notLive, () => ({
      type: 'round-poll',
      result: vodReady(),
    }));
    expect(played.state.phase.kind).toBe('vod-offered');
    expect(ended(played)).toBe(true);
  });

  it.each<[string, XAnswer]>([
    ['X = 이 회차', X_A],
    ['X 모름', X_UNKNOWN],
  ])('재접속 — ENDLIST 뒤 새 라이브 답(%s)이면 아니다', (_x, x) => {
    const played = playWatching(endlist, reconnected(x));
    expect(played.state.phase.kind).toBe('ending-wait');
    expect(ended(played)).toBe(false);
  });

  it('재접속 — 라이브 보기 제안 뒤 X 모름 답이 와도 깜박이지 않는다', () => {
    expect(ended(playWatching(endlist, reconnected(), reconnected(X_UNKNOWN)))).toBe(false);
  });

  it('재접속 — ENDLIST 뒤 새 세션이 발행 전(live · ready:false)이어도 아니다', () => {
    const notReady: Step = () => poll(status({ mode: 'live', ready: false }));
    expect(ended(playWatching(endlist, notReady))).toBe(false);
  });

  it('재접속 — 새 라이브 답 뒤 ENDLIST 가 와도 아니다(pendingNext)', () => {
    const played = playWatching(reconnected(), endlist);
    expect(played.state.phase).toMatchObject({ kind: 'rewind', pendingNext: { url: A2 } });
    expect(ended(played)).toBe(false);
  });

  it('재접속 — pendingNext 가 끝 도달로 사라진 끝남 대기에서 답을 못 받아도 아니다', () => {
    const beyond: Step = () => poll(liveReady(A2, NOW + 30_000, NOW + 2 * MIN));
    const played = playWatching(beyond, endlist, () => END_REACHED, unavailable);
    expect(played.state.phase).toMatchObject({ kind: 'ending-wait', atEnd: true });
    expect(ended(played)).toBe(false);
  });

  it('404 막힘 뒤 다음 답이 정리 중이면 그렇다', () => {
    const played = playWatching(segmentGone, segmentGone, notLive);
    expect(played.state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    expect(ended(played)).toBe(true);
  });

  it.each<[string, XAnswer]>([
    ['X = 이 회차', X_A],
    ['X 모름', X_UNKNOWN],
  ])('404 막힘이어도 스트림이 아직 라이브라고(%s) 하면 아니다', (_x, x) => {
    const stillLive: Step = () => poll(liveReady(A1, T0, NOW + MIN), x);
    const played = playWatching(segmentGone, segmentGone, stillLive);
    expect(played.state.phase).toMatchObject({ kind: 'ending-wait', blocked: true });
    expect(ended(played)).toBe(false);
  });

  it('pendingNext 가 남은 되감기도 스트림이 이 회차 다시보기라고 하면 그렇다', () => {
    const played = playWatching(reconnected(), endlist, streamVod());
    expect(played.state.phase).toMatchObject({ kind: 'rewind', pendingNext: { url: A2 } });
    expect(ended(played)).toBe(true);
  });

  it('이 회차가 끝났다는 답은 붙잡는다 — 그 뒤 다른 방송 B 의 라이브 답에도 그렇다', () => {
    const otherLive: Step = () =>
      poll(liveReady('https://media.pokeclip.com/dvr/key-1/b1/index.m3u8', NOW, NOW + MIN), X_B);
    const played = playWatching(reconnected(), endlist, notLive, streamVod(), otherLive);
    expect(played.state.phase.kind).toBe('rewind');
    expect(ended(played)).toBe(true);
  });

  it('이 회차 끝남 답 없이 다른 방송 B 의 라이브 답만 이어져도 그렇다 — 붙잡아 풀리지 않는다', () => {
    const otherLive: Step = () =>
      poll(liveReady('https://media.pokeclip.com/dvr/key-1/b1/index.m3u8', NOW, NOW + MIN), X_B);
    const played = playWatching(reconnected(), endlist, otherLive);
    expect(played.state.phase).toMatchObject({ kind: 'rewind', pendingNext: { url: A2 } });
    expect(ended(played)).toBe(true);
    expect(ended(play(played.state, [otherLive, otherLive], played.facts))).toBe(true);
  });

  it.each<[string, RewindStatusResult, RewindStatusResult, boolean]>([
    [
      '정리 중(900) 먼저 · 라이브(899) 늦게 → 늦은 라이브를 버려 그렇다',
      status({ mode: 'ending' }),
      liveReady(A2, T0 + 50 * MIN),
      true,
    ],
    [
      '라이브(900) 먼저 · 정리 중(899) 늦게 → 늦은 정리 중을 버려 아니다',
      liveReady(A2, T0 + 50 * MIN),
      status({ mode: 'ending' }),
      false,
    ],
  ])('늦은 답 — %s', (_where, first, late, expected) => {
    const asked =
      (seq: number, result: RewindStatusResult): Step =>
      () => ({
        type: 'stream-poll',
        result,
        x: X_A,
        ask: { seq, receivedAtMs: NOW },
      });
    const played = playWatching(endlist, asked(900, first), asked(899, late));
    expect(played.state.phase.kind).toBe('ending-wait');
    expect(ended(played)).toBe(expected);
  });

  it.each<[string, XAnswer]>([
    ["방송 없음(X = '') 한 번", { kind: 'known', id: '' }],
    ['다른 방송(X = B) 오답 한 번', X_B],
  ])('%s 뒤 X = 이 회차 답 → 새 송출 A2 로 바꾸면 벽시계로 돌아온다', (_where, wrong) => {
    const denied: Step = () => poll(liveReady(A1, T0, NOW), wrong);
    const backA: Step = () => poll(liveReady(A1, T0, NOW + MIN), X_A);
    const toA2: Step = () => poll(liveReady(A2, T0, NOW + 2 * MIN), X_A);
    expect(ended(playWatching(denied))).toBe(true);
    expect(ended(playWatching(denied, backA))).toBe(false);
    const switched = playWatching(denied, backA, toA2);
    expect(switched.state.source.url).toBe(A2);
    expect(ended(switched)).toBe(false);
  });

  it('다른 방송 B 라이브 뒤 라이브가 아닌 답(정리 중)은 부정을 지우지 않는다', () => {
    const otherLive: Step = () => poll(liveReady(A2, T0 + 50 * MIN), X_B);
    const played = playWatching(reconnected(), endlist, otherLive, notLive);
    expect(played.state.phase.kind).toBe('rewind');
    expect(ended(played)).toBe(true);
  });

  it('다른 방송 B 라이브가 이어지다 X 모름 라이브 답이 오면 부정이 아니라 벽시계다', () => {
    const otherLive: Step = () => poll(liveReady(A2, T0 + 50 * MIN), X_B);
    const unknownLive: Step = () => poll(liveReady(A2, T0 + 50 * MIN), X_UNKNOWN);
    expect(ended(playWatching(reconnected(), endlist, otherLive))).toBe(true);
    expect(ended(playWatching(reconnected(), endlist, otherLive, unknownLive))).toBe(false);
  });

  it('다른 방송 B 의 다시보기 답은 이 회차의 끝으로 치지 않는다', () => {
    expect(ended(playWatching(reconnected(), endlist, streamVod(X_B)))).toBe(false);
  });

  it('회차가 끝났다는 답(닫기로 정함)은 그렇다', () => {
    const roundEnded: Step = () => ({ type: 'round-poll', result: status({ mode: 'ended' }) });
    const played = playWatching(endlist, roundEnded);
    expect(played.state.phase).toMatchObject({ kind: 'ending-wait', closeOnEnd: true });
    expect(ended(played)).toBe(true);
  });

  it('라이브 국면은 끝났다는 답이 있어도 아니다 — 라이브 축 · 문구는 그대로다', () => {
    expect(
      recordingEndedOf(fresh(), { streamEnded: true, streamLive: false, roundDenied: true }),
    ).toBe(false);
  });
});

describe('streamFactsAfter — 스트림 답을 세션 사실로 접는다', () => {
  const session = fresh();
  const after = (result: RewindStatusResult, x?: XAnswer, prev = OPEN_STREAM_FACTS) =>
    streamFactsAfter(prev, session, {
      type: 'stream-poll',
      result,
      x,
      ask: { seq: session.streamAsk + 1, receivedAtMs: NOW },
    });
  const notLiveFacts = { streamEnded: false, streamLive: false, roundDenied: false };

  it('세션을 연 직후는 라이브로 본다 — 방송 중에 연 세션이다', () => {
    expect(OPEN_STREAM_FACTS).toEqual({ streamEnded: false, streamLive: true, roundDenied: false });
  });

  it.each<[string, RewindStatusResult, XAnswer | undefined, boolean]>([
    ['준비된 라이브 · X 모름', liveReady(A1, T0), X_UNKNOWN, true],
    ['발행 전 라이브(ready:false) · X 없음', status({ mode: 'live' }), undefined, true],
    ['라이브 · 다른 방송 X', liveReady(A1, T0), X_B, false],
    ['라이브 · 방송 없음 X', liveReady(A1, T0), { kind: 'known', id: '' }, false],
    ['정리 중', status({ mode: 'ending' }), X_A, false],
  ])('%s → 라이브 %s', (_where, result, x, live) => {
    expect(after(result, x, notLiveFacts).streamLive).toBe(live);
  });

  it('상태기계가 버리는 늦은 답(이미 반영한 순번 이하)은 접지 않는다', () => {
    const late = { ...session, streamAsk: 900 };
    const event: SessionEvent = {
      type: 'stream-poll',
      result: status({ mode: 'ending' }),
      x: X_A,
      ask: { seq: 900, receivedAtMs: NOW },
    };
    expect(streamFactsAfter(OPEN_STREAM_FACTS, late, event)).toBe(OPEN_STREAM_FACTS);
  });

  it('최신 라이브 답이 이 회차를 부정하면(다른 방송 · 방송 없음) roundDenied 다 — 붙잡지 않는다', () => {
    const denied = after(liveReady(A1, T0), X_B);
    expect(denied).toMatchObject({ roundDenied: true, streamEnded: false });
    expect(after(liveReady(A1, T0), { kind: 'known', id: '' }).roundDenied).toBe(true);
    expect(after(liveReady(A1, T0), X_A, denied).roundDenied).toBe(false);
    expect(after(liveReady(A1, T0), X_UNKNOWN, denied).roundDenied).toBe(false);
    expect(after({ kind: 'unavailable' }, X_A, denied).roundDenied).toBe(true);
  });

  it('받지 못한 답(unavailable · none)은 앞 값을 둔다', () => {
    expect(after({ kind: 'unavailable' }, X_A, notLiveFacts)).toBe(notLiveFacts);
    expect(after({ kind: 'none' }, X_A)).toBe(OPEN_STREAM_FACTS);
  });

  it('이 회차(또는 모름 · 방송 없음)의 다시보기 · 끝남은 붙잡고 다른 방송의 것은 붙잡지 않는다', () => {
    expect(after(vodReady(), X_A).streamEnded).toBe(true);
    expect(after(status({ mode: 'ended' }), { kind: 'known', id: '' }).streamEnded).toBe(true);
    expect(after(vodReady(), X_B).streamEnded).toBe(false);
    const latched = after(vodReady(), X_UNKNOWN);
    expect(after(liveReady(A1, T0), X_A, latched).streamEnded).toBe(true);
  });
});
