import { describe, expect, it } from 'vitest';
import {
  TIMELINE_HEIGHT,
  TIMELINE_WIDTH,
  chatVolumeLine,
  highlightMarkers,
  toAreaAttribute,
  toPointsAttribute,
  toTimelinePoint,
} from './statsTimeline';
import type { ChatVolumeSeries } from './useLiveMockState';

const SERIES: ChatVolumeSeries = {
  points: [
    [0, 84],
    [40, 78],
    [200, 40],
    [800, 48],
  ],
  markers: [
    [200, 40],
    [320, 22],
  ],
  timeLabels: ['19:12', '지금'],
};

describe('toTimelinePoint — 계약 좌표 → 시안 뷰박스', () => {
  it('세로는 90 기준을 150으로 늘린다', () => {
    expect(toTimelinePoint([0, 84])).toEqual([0, 140]);
    expect(toTimelinePoint([40, 78])).toEqual([40, 130]);
    expect(toTimelinePoint([200, 40])).toEqual([200, 66.7]);
  });

  it('가로는 끝만 늘린다 — 마커 위치가 시안과 어긋나면 안 된다', () => {
    // 비례 확대(×1.075)였다면 200 → 215가 된다
    expect(toTimelinePoint([200, 40])[0]).toBe(200);
    expect(toTimelinePoint([640, 30])[0]).toBe(640);
    // 마지막 점만 "지금"(현재 시각선)에 붙는다
    expect(toTimelinePoint([800, 48])[0]).toBe(TIMELINE_WIDTH);
  });
});

describe('points 속성 문자열', () => {
  it('좌표쌍을 SVG 표기로 잇는다', () => {
    expect(
      toPointsAttribute([
        [0, 140],
        [40, 130],
      ]),
    ).toBe('0,140 40,130');
  });

  it('면은 바닥까지 닫는다 — 마지막 x에서 내려가 첫 x로 돌아온다', () => {
    expect(
      toAreaAttribute([
        [0, 140],
        [860, 80],
      ]),
    ).toBe(`0,140 860,80 860,${TIMELINE_HEIGHT} 0,${TIMELINE_HEIGHT}`);
  });

  it('점이 없으면 빈 문자열이라 도형이 그려지지 않는다', () => {
    expect(toAreaAttribute([])).toBe('');
  });
});

describe('동결 계약에서 파생하는 레이어', () => {
  // 여기서 파생하기 때문에 POK-180이 훅 내부를 SSE로 갈아끼우면 차트가 공짜로 실데이터를 탄다

  it('채팅량 선은 계약의 points를 그대로 옮긴 것이다', () => {
    expect(chatVolumeLine(SERIES)).toHaveLength(SERIES.points.length);
    expect(chatVolumeLine(SERIES)[0]).toEqual([0, 140]);
  });

  it('하이라이트 마커도 같은 환산을 타 선 위에 정확히 얹힌다', () => {
    const markers = highlightMarkers(SERIES);
    const line = chatVolumeLine(SERIES);
    expect(markers[0]).toEqual(line[2]);
    expect(markers[1]).toEqual([320, 36.7]);
  });
});
