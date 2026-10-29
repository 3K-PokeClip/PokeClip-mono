import { describe, expect, it } from 'vitest';
import type { DatedFragment } from './dvrWindow';
import {
  endStalled,
  liveStartFloorMs,
  sessionAxis,
  loadedList,
  startIsTruncated,
  startLoadPosition,
  windowLowerMs,
  type LoadedList,
} from './playbackEdge';

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
  targetDurationS: 4,
  headSpanMs: 12_000,
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

describe('loadedList', () => {
  it('첫 조각 시각 · 마지막 조각 끝 · TD · 맨 앞 조각 셋의 길이 합을 낸다', () => {
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

describe('windowLowerMs — 창 하한 = 첫 조각 + 맨 앞 조각 셋의 길이(vod 는 여유 0)', () => {
  it('live · rewind 는 4초 조각 셋인 12초 여유를 둔다', () => {
    expect(windowLowerMs(LIST, 'live')).toBe(PDT0 + 12_000);
    expect(windowLowerMs(LIST, 'rewind')).toBe(PDT0 + 12_000);
  });

  it('vod 는 ENDLIST 문서라 창이 밀리지 않아 여유가 없다(R47)', () => {
    expect(windowLowerMs(LIST, 'vod')).toBe(PDT0);
  });

  it('TD 6 에 첫 조각 6초 · 나머지 4초면 여유는 TD 가 아니라 실제 조각 길이다', () => {
    const withFirst = loadedList(fragsOf([6, 4, 4, 4, 4]), 6)!;
    expect(windowLowerMs(withFirst, 'live')).toBe(PDT0 + 14_000);
    // 첫 6초 조각이 창에서 빠진 다음 목록
    const afterFirst = loadedList(fragsOf([6, 4, 4, 4, 4, 4]).slice(1), 6)!;
    expect(windowLowerMs(afterFirst, 'live')).toBe(PDT0 + 6_000 + 12_000);
  });

  it('조각 하나뿐이면 그 조각 길이만큼 둔다', () => {
    expect(windowLowerMs(loadedList(fragsOf([6]), 6)!, 'rewind')).toBe(PDT0 + 6_000);
  });
});

describe('startLoadPosition — 첫 목록에서 시작 위치(영상 초)를 고른다(5.4절)', () => {
  it('목록 안 시각은 그 영상 초다', () => {
    expect(startLoadPosition(FRAGS, PDT0 + 14_000, 'rewind', 4)).toBe(24);
  });

  it('live · rewind 는 창 하한 앞을 하한으로 자른다(R23)', () => {
    expect(startLoadPosition(FRAGS, PDT0, 'rewind', 4)).toBe(22);
  });

  it('vod 는 첫 조각 시작 그대로 연다 — 2 TD VOD 도 첫 조각에서(R47)', () => {
    const short = FRAGS.slice(0, 2);
    expect(startLoadPosition(short, PDT0, 'vod', 4)).toBe(10);
  });

  it('목록 끝 뒤는 끝으로 자른다', () => {
    expect(startLoadPosition(FRAGS, PDT0 + 99_000, 'rewind', 4)).toBe(30);
  });

  it('시각 표시가 없으면 null(부르는 쪽이 기본 시작)', () => {
    expect(
      startLoadPosition([{ start: 0, duration: 4, programDateTime: null }], PDT0, 'vod', 4),
    ).toBeNull();
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
      view: { lowerMs: PDT0 + 12_000, endMs: PDT0 + 20_000 },
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
