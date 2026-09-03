// 타임라인 레인에 실데이터를 그리기 위한 계산 (POK-238).
//
// 컴포넌트에서 뗀 순수 함수다 — 창(줌·구간)에 따라 어떤 썸네일이 어디 놓이고 파형 막대가
// 어떻게 접히는지는 레이아웃 없이도 검증할 수 있어야 한다(timelineMath 와 같은 이유).

import type { AudioPeaks, FilmstripSheets } from './editorSource';
import type { TimelineView } from './timelineMath';

export interface FilmstripTile {
  /** 레인 안 가로 위치·너비 (0..1) */
  left: number;
  width: number;
  sheetUrl: string;
  /** 스프라이트에서 이 칸을 꺼내는 값 — 그대로 CSS 로 나간다 */
  backgroundPosition: string;
  backgroundSize: string;
}

/**
 * 보이는 창을 `columns` 칸으로 나누고, 각 칸의 한가운데 시각에 해당하는 썸네일을 고른다.
 *
 * 칸마다 시각을 다시 계산하는 이유: 줌을 바꾸면 같은 폭에 다른 시간이 들어오므로 썸네일 간격을
 * 화면 좌표에 고정해 둘 수 없다. 창이 소스 밖으로 나가면 그 칸은 비운다 — 마지막 프레임을
 * 늘려 붙이면 없는 영상이 있는 것처럼 보인다.
 */
export function filmstripTiles(
  filmstrip: FilmstripSheets & { sheetUrls: readonly string[] },
  view: TimelineView,
  columns: number,
): FilmstripTile[] {
  const span = view.endSeconds - view.startSeconds;
  const perSheet = filmstrip.columns * filmstrip.rows;
  if (!(span > 0) || columns < 1 || perSheet < 1 || filmstrip.intervalSeconds <= 0) return [];

  const tiles: FilmstripTile[] = [];
  const width = 1 / columns;
  for (let column = 0; column < columns; column += 1) {
    const centerSeconds = view.startSeconds + span * ((column + 0.5) / columns);
    if (centerSeconds < 0) continue;
    const index = Math.floor(centerSeconds / filmstrip.intervalSeconds);
    // count 는 유효 썸네일 장수다. 마지막 시트의 남는 칸은 tile 필터가 검게 채운 패딩이라
    // 그리면 없는 프레임이 화면에 뜬다.
    if (index < 0 || index >= filmstrip.count) continue;

    const sheetIndex = Math.floor(index / perSheet);
    const sheetUrl = filmstrip.sheetUrls[sheetIndex];
    if (sheetUrl === undefined) continue;

    const withinSheet = index % perSheet;
    const col = withinSheet % filmstrip.columns;
    const row = Math.floor(withinSheet / filmstrip.columns);
    // % 좌표를 쓴다 — 레인 높이가 트랙 높이 드래그로 달라져도 같은 칸을 가리킨다.
    // 한 칸짜리 축은 0으로 나누는 것을 피한다.
    const x = filmstrip.columns > 1 ? (col / (filmstrip.columns - 1)) * 100 : 0;
    const y = filmstrip.rows > 1 ? (row / (filmstrip.rows - 1)) * 100 : 0;

    tiles.push({
      left: column * width,
      width,
      sheetUrl,
      backgroundPosition: `${x}% ${y}%`,
      backgroundSize: `${filmstrip.columns * 100}% ${filmstrip.rows * 100}%`,
    });
  }
  return tiles;
}

export interface WaveformBar {
  /** 0..1 */
  x: number;
  width: number;
  /** 0..1 — 레인 높이에 대한 비율 */
  height: number;
}

/**
 * 창 안의 파형 bin 을 화면 열 수에 맞춰 접는다. 접을 때는 최댓값을 쓴다 —
 * 평균을 쓰면 짧은 소리(총성·환호 시작)가 뭉개져 구간을 귀 대신 눈으로 찾는 일이 안 된다.
 *
 * 창이 파형 밖으로 나가면 그 열은 그리지 않는다.
 */
export function waveformBars(
  peaks: AudioPeaks,
  view: TimelineView,
  maxColumns: number,
): WaveformBar[] {
  const span = view.endSeconds - view.startSeconds;
  if (!(span > 0) || maxColumns < 1 || peaks.binMs <= 0) return [];

  const binSeconds = peaks.binMs / 1000;
  const binsInView = span / binSeconds;
  const columns = Math.max(1, Math.min(maxColumns, Math.ceil(binsInView)));
  const width = 1 / columns;

  const bars: WaveformBar[] = [];
  for (let column = 0; column < columns; column += 1) {
    const from = view.startSeconds + span * (column / columns);
    const to = view.startSeconds + span * ((column + 1) / columns);
    const fromIndex = Math.max(0, Math.floor(from / binSeconds));
    const toIndex = Math.min(peaks.count, Math.ceil(to / binSeconds));

    let peak = 0;
    for (let i = fromIndex; i < toIndex; i += 1) {
      const value = peaks.peaks[i];
      if (value !== undefined && value > peak) peak = value;
    }
    if (peak <= 0) continue;
    bars.push({ x: column * width, width, height: Math.min(1, peak) });
  }
  return bars;
}
