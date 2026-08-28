// 통합 타임라인(시안 1b 실시간 통계)의 좌표 계산.
//
// 채팅량 선과 하이라이트 마커는 새로 만들지 않고 동결 계약(ChatVolumeSeries)에서 파생한다 —
// 그래야 POK-180이 훅 내부를 SSE로 갈아끼울 때 이 레이어가 공짜로 실데이터를 탄다.
// 계약 좌표계(0..800 × 0..90)와 시안 뷰박스(860 × 150)가 달라 그 환산만 여기서 한 번 한다.

import type { ChatVolumeSeries } from './useLiveMockState';

/** 시안 1b 통계 차트의 뷰박스 */
export const TIMELINE_WIDTH = 860;
export const TIMELINE_HEIGHT = 150;

/** 계약 좌표계 — ChatVolumeSeries가 쓰는 값의 범위 */
const SERIES_WIDTH = 800;
const SERIES_HEIGHT = 90;

export type Point = readonly [number, number];

/**
 * 계약 좌표 → 시안 뷰박스 좌표.
 *
 * x는 비례 확대가 아니라 **끝만 늘린다**: 마지막 점이 "지금"이고 오른쪽 끝의 현재 시각선과
 * 붙어 있어야 해서, 마지막 점만 뷰박스 끝까지 당긴다. 비례로 늘리면 마커 위치가
 * 시안(200·320·440·640)과 어긋난다.
 */
export function toTimelinePoint([x, y]: Point): Point {
  const scaledX = x >= SERIES_WIDTH ? TIMELINE_WIDTH : x;
  return [scaledX, round((y / SERIES_HEIGHT) * TIMELINE_HEIGHT)];
}

/** SVG polyline/polygon의 points 속성 문자열 */
export function toPointsAttribute(points: readonly Point[]): string {
  return points.map(([x, y]) => `${round(x)},${round(y)}`).join(' ');
}

/**
 * 선 아래를 채우는 면 — 바닥(뷰박스 하단)까지 닫는다.
 * 점이 없으면 빈 문자열이라 polygon이 그려지지 않는다.
 */
export function toAreaAttribute(points: readonly Point[]): string {
  const first = points[0];
  const last = points[points.length - 1];
  if (!first || !last) return '';
  return `${toPointsAttribute(points)} ${round(last[0])},${TIMELINE_HEIGHT} ${round(first[0])},${TIMELINE_HEIGHT}`;
}

/** 채팅량 꺾은선 — 계약의 points를 뷰박스로 옮긴 것 */
export function chatVolumeLine(series: ChatVolumeSeries): Point[] {
  return series.points.map(toTimelinePoint);
}

/** 자동 감지 마커 — 채팅량 선 위에 얹히는 점이라 같은 환산을 탄다 */
export function highlightMarkers(series: ChatVolumeSeries): Point[] {
  return series.markers.map(toTimelinePoint);
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}
