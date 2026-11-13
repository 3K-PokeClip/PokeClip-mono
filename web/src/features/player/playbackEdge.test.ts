import { describe, expect, it } from 'vitest';
import type { DatedFragment } from './dvrWindow';
import {
  endStalled,
  liveStartFloorMs,
  sessionAxis,
  loadedList,
  startIsTruncated,
  startCut,
  startLoadPosition,
  windowLowerMs,
  type LoadedList,
} from './playbackEdge';
import { guardRewindFloor, type RewindFloorInput } from './rewindFloor';
import type { PlaybackSource } from './rewindSwitch';

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
    const axis = sessionAxis({ ...at(14), kind: 'rewind', liveEdgeTime: null, axisLowerMs: null });
    expect(axis).toMatchObject({
      atMs: PDT0 + 4_000,
      edgeMs: PDT0 + 20_000,
      view: { lowerMs: PDT0 + 4_000, endMs: PDT0 + 20_000 },
      windowSeconds: 20,
      behindSeconds: 16,
    });
  });

  it('vod 는 문서 범위가 축이다(여유 0)', () => {
    const axis = sessionAxis({ ...at(14), kind: 'vod', liveEdgeTime: null, axisLowerMs: PDT0 - 1 });
    expect(axis).toMatchObject({
      view: { lowerMs: PDT0, endMs: PDT0 + 20_000 },
      windowSeconds: 20,
    });
  });

  it('라이브 끝을 모르면(목록 전 · seekable 없음) null', () => {
    expect(
      sessionAxis({ ...at(14), kind: 'live', liveEdgeTime: null, axisLowerMs: null }),
    ).toBeNull();
  });
});
