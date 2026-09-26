import type {
  VodBroadcast,
  VodBroadcastStatus,
  VodDownloadState,
  VodListOptions,
  VodRowVisual,
} from './useVodListMockState';

// 시안 1f 지난 방송 목록의 고정 데이터 — 시험·스토리북 전용(POK-251).
// 화면은 clip 명부에서 행을 받는다. 예전에 훅 안에 있던 목업 12행을 여기로 옮겨, 시험이
// 시안의 네 상태(준비 중·받는 중·받기 완료·만료 임박)와 기간 필터를 계속 잴 수 있게 한다.

// 「지금」을 고정한다 — 목업 날짜와 함께 얼면 D-day가 결정적이라 테스트가 시계를 조작할 필요도 없다.
export const MOCK_NOW = new Date('2026-08-24T21:00:00+09:00');

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;
const VOD_RETENTION_DAYS = 60;

/**
 * MOCK_NOW에서 거슬러 올라간 시각. setHours 같은 지역 시간 계산을 안 쓰는 이유는 D-day가
 * 실행 환경의 시간대에 따라 하루씩 흔들리지 않게 하려는 것이다 — 「지금」을 얼렸으면
 * 배지도 얼어야 한다. 하루 안쪽으로 시간을 물리면 보관 만료가 `D-(60 - days)`로 떨어진다.
 */
function ago(days: number, hours: number): string {
  return new Date(MOCK_NOW.getTime() - days * DAY_MS - hours * HOUR_MS).toISOString();
}

/** 보관 만료는 종료 시각 + 60일이다(ADR-004) — D-day가 저절로 행마다 달라진다 */
function expiresFrom(endedAt: string): string {
  return new Date(new Date(endedAt).getTime() + VOD_RETENTION_DAYS * DAY_MS).toISOString();
}

interface MockRow {
  streamId: string;
  endedAt: string | null;
  startedAt?: string | null;
  status?: VodBroadcastStatus;
  /** 만료 임박 행처럼 종료+60일과 다른 만료 시각을 줄 때만 */
  vodExpiresAt?: string | null;
  visual: VodRowVisual;
}

// 시안 1f의 네 상태를 모두 담고, 기간 칩을 눌렀을 때 목록이 눈에 띄게 달라지도록 종료일을
// 흩어 뒀다 — 7일 이내 4개 · 30일 이내 9개 · 전체 12개.
const MOCK_ROWS: MockRow[] = [
  {
    streamId: 'stream-2608',
    status: 'ended',
    startedAt: ago(0, 3.5),
    endedAt: ago(0, 0.4),
    // 준비 중 — VOD가 아직 없어 보관 기한도 안 정해졌다
    vodExpiresAt: null,
    visual: { title: '새벽 랭크 — 마스터 승급전', durationSec: null, cardCount: 3 },
  },
  {
    streamId: 'stream-2607',
    endedAt: ago(1, 2),
    visual: { title: '고민상담 라디오', durationSec: 11144, cardCount: 6 },
  },
  {
    streamId: 'stream-2606',
    endedAt: ago(3, 1),
    visual: { title: '합방 특집 — 4인 내전', durationSec: 15128, cardCount: 11 },
  },
  {
    streamId: 'stream-2605',
    endedAt: ago(6, 3),
    visual: { title: '시청자 참여 — 밸런스 게임', durationSec: 9668, cardCount: 4 },
  },
  {
    streamId: 'stream-2604',
    endedAt: ago(9, 2),
    visual: { title: '스크림 — 대회 연습', durationSec: 19330, cardCount: 9 },
  },
  {
    streamId: 'stream-2603',
    endedAt: ago(13, 4),
    visual: { title: '신작 첫인상 리뷰', durationSec: 8102, cardCount: 5 },
  },
  {
    streamId: 'stream-2602',
    endedAt: ago(17, 1),
    visual: { title: '구독자 감사 이벤트', durationSec: 12240, cardCount: 7 },
  },
  {
    streamId: 'stream-2601',
    endedAt: ago(21, 3),
    visual: { title: '랭크 복습 — 리플레이 정주행', durationSec: 10380, cardCount: 3 },
  },
  {
    streamId: 'stream-2600',
    endedAt: ago(26, 2),
    visual: { title: '심야 수다 — 아무 말 대잔치', durationSec: 7460, cardCount: 2 },
  },
  {
    streamId: 'stream-2599',
    endedAt: ago(34, 5),
    visual: { title: '팬아트 리액션', durationSec: 6320, cardCount: 4 },
  },
  {
    streamId: 'stream-2598',
    // 시작 알림의 발생 시각이 비어 온 방송 — 계약이 허용하는 null을 화면이 견디는지 본다
    startedAt: null,
    endedAt: ago(41, 1),
    visual: { title: '레트로 게임 마라톤', durationSec: 17880, cardCount: 6 },
  },
  {
    streamId: 'stream-2597',
    endedAt: ago(57, 2),
    // 만료 임박 — 종료 + 60일이라 자연히 D-3이다
    visual: {
      title: '6월 랭크 마라톤',
      durationSec: 21690,
      cardCount: 9,
      unsavedCardCount: 9,
    },
  },
];

export const MOCK_BROADCASTS: VodBroadcast[] = MOCK_ROWS.map((row) => ({
  streamId: row.streamId,
  status: row.status ?? 'vod_ready',
  relation: 'OWNER',
  startedAt: row.startedAt !== undefined ? row.startedAt : row.endedAt,
  endedAt: row.endedAt,
  vodExpiresAt:
    row.vodExpiresAt !== undefined
      ? row.vodExpiresAt
      : row.endedAt
        ? expiresFrom(row.endedAt)
        : null,
}));

export const MOCK_VISUALS: Record<string, VodRowVisual> = Object.fromEntries(
  MOCK_ROWS.map((row) => [row.streamId, row.visual]),
);

// 시안이 그리는 세 상태를 한 화면에서 다 볼 수 있게 둘을 심어 둔다 — 받기를 눌러도
// 지금은 「준비 중」만 뜨므로, 심지 않으면 받는 중·받기 완료 자리를 아무도 못 본다.
export const MOCK_DOWNLOADS: Record<string, VodDownloadState> = {
  'stream-2607': { kind: 'downloading', progress: 46 },
  'stream-2604': { kind: 'done' },
};

/** VodListScreen에 그대로 펼쳐 넣는 주입값 */
export const VOD_LIST_FIXTURE: Required<VodListOptions> = {
  broadcasts: MOCK_BROADCASTS,
  visuals: MOCK_VISUALS,
  downloads: MOCK_DOWNLOADS,
  now: MOCK_NOW,
};
