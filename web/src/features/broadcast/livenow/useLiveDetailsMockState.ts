'use client';

// 디자인 1b가 화면에 그리지만 동결 계약(useLiveMockState)이 담지 않는 표기값.
//
// 왜 저쪽에 안 넣는가: useLiveMockState의 반환 형태는 POK-180(SSE 실연동)이 내부만
// 갈아끼우기로 한 계약이다. 카테고리·태그·카드 길이 같은 값은 아직 어느 계약에도 없어
// 지금 계약에 얹으면 실연동 때 채울 수 없는 필드가 남는다.
//
// 교체 방법: 실 페이로드가 이 값들을 싣게 되면 이 훅은 통째로 사라진다 —
// 화면은 highlightCardView의 폴백(계약 필드에서 파생)으로 이미 돌아가고 있으므로,
// cardVisuals가 빈 맵이 돼도 깨지지 않는다.

export interface StreamMeta {
  /** 방송 카테고리 — 시안 1b의 제목 아래 강조 텍스트 */
  category: string;
  tags: string[];
  /** 카테고리 썸네일 자리 표시 문구 (이미지 소스가 아직 없다) */
  thumbLabel: string;
}

export interface CardVisual {
  /** 카드 길이 표기 — `0:42` */
  duration: string;
  /** 감지 사유 캡슐 — 채팅 급증·키워드 감지·시청자 급증·수동 마킹 */
  reason: string;
  /** 방송 타임라인에서의 위치 0..100 (썸네일 하단 인디케이터) */
  posPercent: number;
  /** 미니 파형 진폭 0..1 — 뷰박스는 그리는 쪽이 정한다 */
  spark: readonly number[];
  timeAgo: string;
  /** 클립 생성 진행률 0..100 — 만드는 중인 카드에만 */
  progress?: number;
}

export interface LiveDetailsMockState {
  streamMeta: StreamMeta;
  cardVisuals: Record<string, CardVisual>;
}

const MOCK_STREAM_META: StreamMeta = {
  category: '리그 오브 레전드',
  tags: ['랭크', '다이아승급', '새벽방송'],
  thumbLabel: '카테고리',
};

// 시안 HighlightCard의 미니 파형(0..1 진폭) — 카드마다 같은 물결이면 눈에 띄게 어색해 셋을 돌린다
const SPARK_A = [0.18, 0.23, 0.18, 0.32, 0.27, 0.59, 0.41, 0.32, 0.82, 0.45, 0.36, 0.55, 0.41, 0.36];
const SPARK_B = [0.22, 0.3, 0.26, 0.2, 0.34, 0.28, 0.45, 0.68, 0.5, 0.36, 0.3, 0.42, 0.33, 0.27];
const SPARK_C = [0.3, 0.24, 0.36, 0.48, 0.35, 0.28, 0.34, 0.24, 0.4, 0.55, 0.74, 0.46, 0.32, 0.29];

const MOCK_CARD_VISUALS: Record<string, CardVisual> = {
  'hl-1': {
    duration: '0:42',
    reason: '채팅 급증',
    posPercent: 94,
    spark: SPARK_A,
    timeAgo: '방금',
  },
  'hl-7': {
    duration: '0:48',
    reason: '수동 마킹',
    posPercent: 90,
    spark: SPARK_B,
    timeAgo: '9분 전',
  },
  'hl-2': {
    duration: '1:12',
    reason: '수동 마킹',
    posPercent: 80,
    spark: SPARK_C,
    timeAgo: '16분 전',
  },
  'hl-3': {
    duration: '1:05',
    reason: '채팅 급증',
    posPercent: 70,
    spark: SPARK_A,
    timeAgo: '26분 전',
    progress: 62,
  },
  'hl-4': {
    duration: '0:38',
    reason: '시청자 급증',
    posPercent: 56,
    spark: SPARK_B,
    timeAgo: '37분 전',
  },
  'hl-8': {
    duration: '0:51',
    reason: '키워드 감지',
    posPercent: 47,
    spark: SPARK_C,
    timeAgo: '44분 전',
  },
  'hl-5': {
    duration: '0:55',
    reason: '키워드 감지',
    posPercent: 37,
    spark: SPARK_A,
    timeAgo: '53분 전',
  },
  'hl-6': {
    duration: '1:03',
    reason: '채팅 급증',
    posPercent: 15,
    spark: SPARK_B,
    timeAgo: '1시간 11분 전',
  },
};

export function useLiveDetailsMockState(): LiveDetailsMockState {
  return { streamMeta: MOCK_STREAM_META, cardVisuals: MOCK_CARD_VISUALS };
}
