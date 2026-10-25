import { describe, expect, it } from 'vitest';
import type { DatedFragment } from './dvrWindow';
import {
  endStalled,
  sessionAxis,
  loadedList,
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
const LIST: LoadedList = { firstMs: PDT0, lastEndMs: PDT0 + 20_000, targetDurationS: 4 };

describe('loadedList', () => {
  it('첫 조각 시각 · 마지막 조각 끝 · TD 를 낸다', () => {
    expect(loadedList(FRAGS, 4)).toEqual(LIST);
  });

  it('조각이 없거나 시각 표시가 없으면 null', () => {
    expect(loadedList([], 4)).toBeNull();
    expect(loadedList([{ start: 0, duration: 4, programDateTime: null }], 4)).toBeNull();
  });
});

describe('windowLowerMs — 창 하한 = 첫 조각 + 3 × TD(vod 는 여유 0)', () => {
  it('live · rewind 는 12초(TD 4) 여유를 둔다', () => {
    expect(windowLowerMs(LIST, 'live')).toBe(PDT0 + 12_000);
    expect(windowLowerMs(LIST, 'rewind')).toBe(PDT0 + 12_000);
  });

  it('vod 는 ENDLIST 문서라 창이 밀리지 않아 여유가 없다(R47)', () => {
    expect(windowLowerMs(LIST, 'vod')).toBe(PDT0);
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
