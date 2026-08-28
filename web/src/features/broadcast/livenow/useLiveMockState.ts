'use client';

// 디자인 1b 라이브 대시보드의 목업 상태.
// 감지 파이프라인·방송 상태 API가 아직 없어 표시값은 전부 목업(디자인 1b 표기값)이다 —
// 연동 티켓에서 이 훅 내부만 useQuery/실시간 구독으로 갈아끼우면 화면은 그대로 쓴다.
//
// ⚠ 반환 형태는 계약이다 (POK-180 SSE 실연동이 "화면은 그대로, 훅 내부만" 전제로 선다).
// 값은 시안을 따라 바꿔도 되지만 필드·유니온을 늘리면 저쪽이 채울 수 없는 자리가 생긴다.
// 시안이 요구하는데 여기 없는 표기값은 useLiveDetailsMockState로 간다.

export interface LiveStream {
  title: string;
  platform: string;
  channelName: string;
  startedNote: string;
  /** 플레이어 시뮬레이션 초기값 — 1:24:03 */
  uptimeSeconds: number;
  uptimeLabel: string;
  viewers: string;
  editorName: string;
}

export type HighlightStatus =
  'scored' | 'manual' | 'editing' | 'clipped' | 'unprocessed' | 'expired';

/** 전체/자동/수동 필터의 기준 */
export type HighlightSource = 'auto' | 'manual';

export interface LiveHighlight {
  id: string;
  timestamp: string;
  title: string;
  meta: string;
  status: HighlightStatus;
  source: HighlightSource;
  /** 채점 점수 — 채점 전(unprocessed)·수동 카드에는 없다 */
  score?: number;
  /** editing일 때 편집 중인 사람 */
  editorName?: string;
  /** 방금 감지된 카드 — 마젠타 틴트로 강조 */
  emphasized?: boolean;
}

export interface ChatVolumeSeries {
  /** SVG 좌표계(0..800 × 0..90)의 꺾은선 점들 */
  points: ReadonlyArray<readonly [number, number]>;
  /** 자동 감지 시점 마커 */
  markers: ReadonlyArray<readonly [number, number]>;
  timeLabels: readonly string[];
}

const MOCK_STREAM: LiveStream = {
  title: '새벽 랭크 올리기 — 다이아 승급전 가보자',
  platform: '치지직',
  channelName: '게임하는너구리',
  startedNote: '오후 7:12 시작',
  uptimeSeconds: 5043,
  uptimeLabel: '1:24:03',
  viewers: '1,842',
  editorName: '박편집',
};

const MOCK_HIGHLIGHTS: LiveHighlight[] = [
  {
    id: 'hl-1',
    timestamp: '1:24:03',
    title: '승급전 마지막 한타 역전',
    meta: '채팅 ×4.2 급증 · 42초 · 방금',
    status: 'scored',
    source: 'auto',
    score: 97,
    emphasized: true,
  },
  {
    id: 'hl-7',
    timestamp: '1:15:20',
    title: '핫키로 남긴 백도어 각',
    meta: '수동 마킹 · 48초 · 9분 전',
    status: 'manual',
    source: 'manual',
  },
  {
    id: 'hl-2',
    timestamp: '1:07:50',
    title: '시청자 참여 미션 성공',
    meta: '수동 마킹 · 1분 12초 · 16분 전',
    status: 'manual',
    source: 'manual',
  },
  {
    id: 'hl-3',
    timestamp: '0:58:41',
    title: '채팅 폭발 — 3연속 클러치',
    meta: '채팅 ×3.1 급증 · 1분 5초 · 26분 전',
    status: 'editing',
    source: 'auto',
    score: 92,
    editorName: '박편집',
  },
  {
    id: 'hl-4',
    timestamp: '0:47:22',
    title: '역대급 스나이핑 각',
    meta: '채팅 ×2.8 급증 · 38초 · 37분 전',
    status: 'clipped',
    source: 'auto',
    score: 85,
  },
  {
    id: 'hl-8',
    timestamp: '0:39:55',
    title: '정글 3연속 카운터',
    meta: '키워드 감지 · 51초 · 44분 전',
    status: 'scored',
    source: 'auto',
    score: 78,
  },
  {
    id: 'hl-5',
    timestamp: '0:31:09',
    title: '오프닝 인사 — 오늘 목표 선언',
    meta: '키워드 감지 · 55초 · 53분 전',
    status: 'unprocessed',
    source: 'auto',
  },
  {
    id: 'hl-6',
    timestamp: '0:12:44',
    title: '대기화면 — 시작 전 채팅 타임',
    meta: '되감기 1시간 이탈 — 곧 만료',
    status: 'expired',
    source: 'auto',
  },
];

const MOCK_CHAT_VOLUME: ChatVolumeSeries = {
  points: [
    [0, 84],
    [40, 78],
    [80, 80],
    [120, 66],
    [160, 72],
    [200, 40],
    [240, 58],
    [280, 64],
    [320, 22],
    [360, 50],
    [400, 60],
    [440, 34],
    [480, 55],
    [520, 62],
    [560, 45],
    [600, 68],
    [640, 30],
    [680, 52],
    [720, 58],
    [760, 40],
    [800, 48],
  ],
  markers: [
    [200, 40],
    [320, 22],
    [440, 34],
    [640, 30],
  ],
  timeLabels: ['19:12', '19:41', '20:10', '지금'],
};

export interface LiveMockState {
  stream: LiveStream;
  highlights: LiveHighlight[];
  hiddenCount: number;
  chatVolume: ChatVolumeSeries;
  /** 채팅 수집 끊김 경고 배너 노출 여부 */
  chatWarning: boolean;
}

export function useLiveMockState(): LiveMockState {
  return {
    stream: MOCK_STREAM,
    highlights: MOCK_HIGHLIGHTS,
    hiddenCount: 2,
    chatVolume: MOCK_CHAT_VOLUME,
    chatWarning: true,
  };
}
