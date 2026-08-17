'use client';

import { useCallback, useState } from 'react';

// 디자인 1a 홈 대시보드의 목업 상태.
// 홈 API가 아직 없어 표시값은 전부 목업이다(디자인 1a 표기값 그대로) —
// 연동 티켓에서 이 훅 내부만 useQuery로 갈아끼우면 화면은 그대로 쓴다.
// 빈 상태·시작 가이드는 POK-113에서 별도로 다룬다.

/** 이어서 편집 배너 — 닫으면 이번 세션에서만 사라진다 */
export interface ResumeDraft {
  title: string;
  meta: string;
}

/** 방송 중일 때만 노출되는 라이브 밴드 — 오프라인이면 섹션 전체 미노출 (디자인 1a ②) */
export interface LiveNow {
  title: string;
  platform: string;
  startedNote: string;
  /** 방송 경과 표기 — LIVE 배지 옆에 그대로 붙는다 */
  uptimeLabel: string;
  viewers: string;
  detectedCards: number;
  completedClips: number;
}

export type VodBadge = { kind: 'preparing' } | { kind: 'dday'; label: string };

export interface HomeVod {
  id: string;
  title: string;
  meta: string;
  badge?: VodBadge;
  duration?: string;
}

export type PublishStatus = 'uploading' | 'scheduled' | 'published';

export interface PublishRow {
  id: string;
  title: string;
  status: PublishStatus;
  /** uploading일 때만 존재하는 진행률(%) */
  progress?: number;
  /** scheduled·published의 우측 보조 텍스트 (예약 시각·조회수) */
  note?: string;
}

export interface ExpiringVod {
  id: string;
  dday: string;
  /** D-3 이하 — 붉은 배지로 급함을 표시 */
  urgent: boolean;
  title: string;
}

const MOCK_USER_NAME = '게임하는너구리';

// 시간대별 인사말은 서버 데이터 연동 시 서버에서 내려준다 —
// 클라이언트 시계로 계산하면 하이드레이션이 어긋난다 (MOCK_ISSUED_AT 선례).
const MOCK_GREETING = '좋은 저녁이에요';

const MOCK_RESUME: ResumeDraft = {
  title: '승급전 마지막 한타 역전',
  meta: '9:16 상하분할 · 구간 12.4초 · 어제 23:41 자동 저장',
};

const MOCK_LIVE: LiveNow = {
  title: '새벽 랭크 올리기 — 다이아 승급전 가보자',
  platform: '치지직',
  startedNote: '오후 7:12 시작 · 편집자 1명 접속 중',
  uptimeLabel: '1:24:03',
  viewers: '1,842',
  detectedCards: 8,
  completedClips: 3,
};

const MOCK_VODS: HomeVod[] = [
  {
    id: 'vod-1',
    title: '새벽 랭크 — 마스터 승급전',
    meta: '방금 종료 · 준비되면 알림',
    badge: { kind: 'preparing' },
  },
  {
    id: 'vod-2',
    title: '합방 특집 — 4인 내전',
    meta: '7월 26일 · 카드 11개',
    duration: '4:12:08',
  },
  {
    id: 'vod-3',
    title: '고민상담 라디오',
    meta: '7월 24일 · 카드 6개',
    duration: '3:05:44',
  },
  {
    id: 'vod-4',
    title: '6월 랭크 마라톤',
    meta: '6월 12일 · 카드 9개 · 곧 만료',
    badge: { kind: 'dday', label: 'D-3' },
    duration: '6:01:30',
  },
];

const MOCK_PUBLISH_ROWS: PublishRow[] = [
  { id: 'pub-1', title: '승급전 마지막 한타 역전', status: 'uploading', progress: 62 },
  { id: 'pub-2', title: '고민상담 레전드 사연', status: 'scheduled', note: '오늘 18:00' },
  { id: 'pub-3', title: '스크림 에이스 장면', status: 'published', note: '조회 1.2만' },
];

const MOCK_EXPIRING: ExpiringVod[] = [
  { id: 'exp-1', dday: 'D-3', urgent: true, title: '6월 랭크 마라톤 · 카드 9개' },
  { id: 'exp-2', dday: 'D-6', urgent: false, title: '합방 특집 — 4인 내전 · 카드 5개' },
];

export interface HomeMockState {
  userName: string;
  greeting: string;
  resumeDraft: ResumeDraft | null;
  dismissResume: () => void;
  live: LiveNow | null;
  vods: HomeVod[];
  publishRows: PublishRow[];
  expiringVods: ExpiringVod[];
}

export function useHomeMockState(): HomeMockState {
  const [resumeDraft, setResumeDraft] = useState<ResumeDraft | null>(MOCK_RESUME);
  const dismissResume = useCallback(() => setResumeDraft(null), []);

  return {
    userName: MOCK_USER_NAME,
    greeting: MOCK_GREETING,
    resumeDraft,
    dismissResume,
    live: MOCK_LIVE,
    vods: MOCK_VODS,
    publishRows: MOCK_PUBLISH_ROWS,
    expiringVods: MOCK_EXPIRING,
  };
}
