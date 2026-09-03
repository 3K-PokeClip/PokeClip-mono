import { describe, expect, it } from 'vitest';
import type { AudioPeaks, FilmstripSheets } from './editorSource';
import { filmstripTiles, waveformBars } from './timelineLaneView';

// jsdom 에는 레이아웃이 없어 레인이 실제로 어떻게 그려지는지 못 잰다.
// 어떤 썸네일이 어디 놓이고 파형이 어떻게 접히는지는 계산이라 여기서 검증한다.

const FILMSTRIP: FilmstripSheets & { sheetUrls: readonly string[] } = {
  sheets: ['a.jpg', 'b.jpg'],
  sheetUrls: ['http://x/a.jpg', 'http://x/b.jpg'],
  columns: 10,
  rows: 10,
  tileWidth: 160,
  tileHeight: 90,
  intervalSeconds: 2,
  // 시트 하나는 100칸 — 150장이면 두 번째 시트는 50칸만 유효하다
  count: 150,
  lastSheetCount: 50,
};

describe('filmstripTiles', () => {
  it('창을 균등하게 나누고 칸 가운데 시각의 썸네일을 고른다', () => {
    // 0~8초 창을 4칸으로 → 가운데 1·3·5·7초 → 인덱스 0·1·2·3
    const tiles = filmstripTiles(FILMSTRIP, { startSeconds: 0, endSeconds: 8 }, 4);

    expect(tiles).toHaveLength(4);
    expect(tiles[0]?.left).toBe(0);
    expect(tiles[0]?.width).toBe(0.25);
    expect(tiles[3]?.left).toBeCloseTo(0.75, 5);
    // 인덱스 0~3 은 첫 시트의 첫 줄 — 세로 위치는 0%
    expect(tiles[0]?.backgroundPosition).toBe('0% 0%');
    expect(tiles[1]?.backgroundPosition).toBe(`${(1 / 9) * 100}% 0%`);
    expect(tiles[0]?.sheetUrl).toBe('http://x/a.jpg');
  });

  it('스프라이트 배율은 시트 격자 그대로다', () => {
    const [tile] = filmstripTiles(FILMSTRIP, { startSeconds: 0, endSeconds: 8 }, 1);
    expect(tile?.backgroundSize).toBe('1000% 1000%');
  });

  it('시트를 넘어가면 다음 시트를 집는다', () => {
    // 인덱스 100 = 200초 → 두 번째 시트의 첫 칸
    const tiles = filmstripTiles(FILMSTRIP, { startSeconds: 200, endSeconds: 202 }, 1);
    expect(tiles[0]?.sheetUrl).toBe('http://x/b.jpg');
    expect(tiles[0]?.backgroundPosition).toBe('0% 0%');
  });

  it('유효 장수를 넘는 칸은 비운다 — 패딩 칸을 프레임처럼 그리지 않는다', () => {
    // count 150 → 마지막 유효 썸네일은 인덱스 149(=298초). 300초 뒤는 없다.
    const tiles = filmstripTiles(FILMSTRIP, { startSeconds: 300, endSeconds: 320 }, 4);
    expect(tiles).toHaveLength(0);
  });

  it('창이 소스 앞뒤로 걸쳐도 있는 칸만 그린다', () => {
    const tiles = filmstripTiles(FILMSTRIP, { startSeconds: -10, endSeconds: 10 }, 4);
    // 앞 두 칸의 가운데(-7.5초·-2.5초)는 소스 밖
    expect(tiles).toHaveLength(2);
    expect(tiles[0]?.left).toBeCloseTo(0.5, 5);
  });

  it('창 폭이 0이거나 칸이 없으면 아무것도 안 그린다', () => {
    expect(filmstripTiles(FILMSTRIP, { startSeconds: 5, endSeconds: 5 }, 4)).toEqual([]);
    expect(filmstripTiles(FILMSTRIP, { startSeconds: 0, endSeconds: 8 }, 0)).toEqual([]);
  });

  it('한 줄짜리 시트에서 0으로 나누지 않는다', () => {
    const single = { ...FILMSTRIP, columns: 1, rows: 1, count: 4 };
    const tiles = filmstripTiles(single, { startSeconds: 0, endSeconds: 4 }, 2);
    expect(tiles[0]?.backgroundPosition).toBe('0% 0%');
    expect(tiles[0]?.backgroundSize).toBe('100% 100%');
  });
});

function peaksOf(values: number[]): AudioPeaks {
  return {
    binMs: 100,
    count: values.length,
    scale: 'abs16',
    maxPeak: Math.max(...values),
    peaks: values,
  };
}

describe('waveformBars', () => {
  it('bin 을 그대로 쓸 수 있으면 하나씩 그린다', () => {
    const bars = waveformBars(
      peaksOf([0.2, 0.5, 0.9, 0.4]),
      { startSeconds: 0, endSeconds: 0.4 },
      320,
    );

    expect(bars).toHaveLength(4);
    expect(bars[0]?.height).toBe(0.2);
    expect(bars[2]?.height).toBe(0.9);
    expect(bars[0]?.width).toBe(0.25);
  });

  it('열 수를 넘으면 최댓값으로 접는다 — 짧은 소리가 뭉개지면 안 된다', () => {
    const bars = waveformBars(
      peaksOf([0.1, 0.9, 0.2, 0.3]),
      { startSeconds: 0, endSeconds: 0.4 },
      2,
    );

    expect(bars).toHaveLength(2);
    // 앞 두 bin 의 최댓값이 0.9 — 평균(0.5)이 아니다
    expect(bars[0]?.height).toBe(0.9);
    expect(bars[1]?.height).toBe(0.3);
  });

  it('소리가 없는 열은 막대를 안 그린다', () => {
    const bars = waveformBars(peaksOf([0, 0, 0.5, 0]), { startSeconds: 0, endSeconds: 0.4 }, 4);
    expect(bars).toHaveLength(1);
    expect(bars[0]?.x).toBeCloseTo(0.5, 5);
  });

  it('파형 범위 밖은 비운다', () => {
    const bars = waveformBars(peaksOf([0.5, 0.5]), { startSeconds: 10, endSeconds: 12 }, 8);
    expect(bars).toEqual([]);
  });

  it('창 폭이 0이면 아무것도 안 그린다', () => {
    expect(waveformBars(peaksOf([0.5]), { startSeconds: 3, endSeconds: 3 }, 8)).toEqual([]);
  });

  it('높이는 1을 넘지 않는다', () => {
    const bars = waveformBars(peaksOf([1.4]), { startSeconds: 0, endSeconds: 0.1 }, 4);
    expect(bars[0]?.height).toBe(1);
  });
});
