'use client';

// 실시간 통계(시안 1b)의 목업 — 채팅량 선과 하이라이트 마커는 여기 없다.
//
// 그 둘은 동결 계약(useLiveMockState.chatVolume)에서 statsTimeline이 파생한다. 여기 복사해
// 두면 POK-180이 계약을 실데이터로 바꿔도 차트만 목업에 남아 두 값이 어긋난다.
//
// 여기 있는 것은 계약에 아직 원천이 없는 값뿐이다: 시청자 추이·후원 시점·카테고리 구간·지표.
// 채팅 패널과 같은 이유로 POK-180의 범위가 아니며, 창구가 생기는 티켓까지 목업으로 남는다.
// 하이라이트 개수 지표는 여기 두지 않는다 — 화면이 카드 목록에서 세야 필터 표기와 어긋나지 않는다.

import type { Point } from './statsTimeline';

export interface CategorySegment {
  label: string;
  minutes: number;
  /** 구간 바에서 차지하는 비율 0..100 */
  percent: number;
}

export interface LiveMetric {
  label: string;
  value: string;
}

export interface LiveStatsMockState {
  /** 시청자 추이 — 시안 뷰박스(860 × 150) 좌표 */
  viewerLine: readonly Point[];
  /** 후원이 들어온 시점 마커 — 같은 뷰박스 좌표 */
  donations: readonly Point[];
  categorySegments: CategorySegment[];
  metrics: LiveMetric[];
}

const MOCK_VIEWER_LINE: readonly Point[] = [
  [0, 74],
  [80, 72],
  [160, 70],
  [240, 62],
  [320, 46],
  [400, 48],
  [480, 36],
  [560, 38],
  [640, 29],
  [720, 31],
  [800, 30],
  [860, 30],
];

const MOCK_DONATIONS: readonly Point[] = [
  [360, 68],
  [604, 84],
];

const MOCK_CATEGORY_SEGMENTS: CategorySegment[] = [
  { label: '저스트 채팅', minutes: 29, percent: 35 },
  { label: '리그 오브 레전드', minutes: 55, percent: 65 },
];

const MOCK_METRICS: LiveMetric[] = [
  { label: '최고 시청자', value: '2,310' },
  { label: '평균 시청자', value: '1,626' },
  { label: '총 채팅', value: '12,480' },
  { label: '분당 평균 채팅', value: '402' },
];

export function useLiveStatsMockState(): LiveStatsMockState {
  return {
    viewerLine: MOCK_VIEWER_LINE,
    donations: MOCK_DONATIONS,
    categorySegments: MOCK_CATEGORY_SEGMENTS,
    metrics: MOCK_METRICS,
  };
}
