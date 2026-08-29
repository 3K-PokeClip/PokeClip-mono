'use client';

import { useCallback, useMemo, useState } from 'react';
import { useToast } from '@/ui';
import { excludeLive, filterByPeriod } from './vodListView';

// 시안 1f 지난 방송 목록의 목업 상태.
//
// 목록 문(`GET /api/clip/broadcasts?state=past` — POK-174·ADR-055)은 이미 있지만 VOD 준비
// 상태를 줄 백엔드(B5)가 아직 없어 전부 목업이다. 연동 티켓에서 이 훅 내부만 useQuery로
// 갈아끼우면 화면은 그대로 쓴다(useLiveMockState가 라이브 화면에 쓴 방식과 같다).
//
// ⚠ VodBroadcast는 계약 미러다 — services/clip `BroadcastListResponse.Item`과 칸 이름·값이
// 같다. 실연동 때 화면을 안 고치려면 이 모양을 지켜야 한다. 계약에 없는 표기값은 아래
// VodRowVisual로 갈라 뒀다 — 그쪽은 B5가 생기면 통째로 서버 값에 자리를 내준다.
//
// 화면 컴포넌트에는 목업 값을 두지 않는다 — 언젠가 서버가 내려줄 값은 전부 여기서 나오고,
// 화면에는 구조 라벨('지난 방송' 같은 고정 문구)만 남는다.

/** 계약의 status — 소문자 그대로다(BroadcastStatus.dbValue). `state=past`는 뒤 둘로 펼쳐진다 */
export type VodBroadcastStatus = 'live' | 'ended' | 'vod_ready';

/** 「내 방송」과 「내가 편집하는 방송」을 가르는 표시 재료 — 권한이 아니다(ADR-055) */
export type VodBroadcastRelation = 'OWNER' | 'EDITOR';

/** 계약 한 줄 — 이 여섯 칸이 서버가 주는 전부다 */
export interface VodBroadcast {
  streamId: string;
  status: VodBroadcastStatus;
  relation: VodBroadcastRelation;
  /** ⚠ null 가능 — 시작 알림의 발생 시각이 비어 오면 그렇다(ADR-016 종료 선도착) */
  startedAt: string | null;
  endedAt: string | null;
  /** 60일 보관 만료 시각. 기한이 지난 방송도 목록에 남으므로 과거일 수 있다 */
  vodExpiresAt: string | null;
}

/**
 * 계약에 없는 표기값 — 제목·썸네일 길이·카드 수는 목록 문이 아직 안 싣는다.
 * B5(VOD 확정)가 생기면 이 타입은 통째로 사라지고 계약 필드로 대체된다.
 */
export interface VodRowVisual {
  title: string;
  /** 준비 중이라 아직 모르면 null */
  durationSec: number | null;
  cardCount: number;
  /** 만료 임박 행의 「저장하지 않은 카드 N개가 함께 삭제됩니다」 */
  unsavedCardCount?: number;
}

/**
 * 풀 VOD 내려받기 상태 — 시안 1f의 행 오른쪽 조작부 3분기가 이 값에서 갈린다.
 *
 * ⚠ 실제로 받는 일은 아직 아무것도 안 한다. 받기를 시작하면 「준비 중인 기능」 토스트가
 * 뜨고 행은 idle에 머문다 — 다운로드 백엔드가 기능명세·계약에 아직 없기 때문이다(POK-226).
 * 화면은 시안대로 세 상태를 모두 그릴 수 있어야 하므로 목업이 두 상태를 심어 둔다.
 */
export type VodDownloadState =
  { kind: 'idle' } | { kind: 'downloading'; progress: number } | { kind: 'done' };

export const VOD_DOWNLOAD_IDLE: VodDownloadState = { kind: 'idle' };

export type VodPeriodFilter = 'all' | '7d' | '30d' | 'custom';

/** 「기간 지정」의 두 입력. 'YYYY-MM-DD'이고 안 채운 쪽은 null */
export interface VodCustomRange {
  from: string | null;
  to: string | null;
}

/** 테스트 주입 — 빈 상태·경계 케이스를 화면 밖에서 만든다 (ClipEditorOptions 선례) */
export interface VodListOptions {
  broadcasts?: VodBroadcast[];
  visuals?: Record<string, VodRowVisual>;
  downloads?: Record<string, VodDownloadState>;
}

export interface VodListMockState {
  /** 모든 D-day·기간 계산의 기준 시각 */
  now: Date;
  /** live 제외 + 기간 필터를 통과한 행 */
  broadcasts: VodBroadcast[];
  /** 필터와 무관한 전체 수 — 「아직 없다」와 「이 기간에 없다」를 가른다 */
  totalCount: number;
  visuals: Record<string, VodRowVisual>;
  filter: VodPeriodFilter;
  setFilter: (filter: VodPeriodFilter) => void;
  customRange: VodCustomRange;
  setCustomRange: (range: VodCustomRange) => void;
  downloads: Record<string, VodDownloadState>;
  /** 화질을 고르고 받기를 눌렀을 때 — 지금은 「준비 중」만 알린다 */
  requestDownload: (streamId: string, quality: string) => void;
  cancelDownload: (streamId: string) => void;
  /** 「받기 완료」를 눌러 다시 받기 — 자리를 idle로 되돌린다 */
  resetDownload: (streamId: string) => void;
}

// 「지금」을 고정한다. 클라이언트 시계로 계산하면 하이드레이션이 어긋나고(MOCK_GREETING 선례),
// 목업 날짜와 함께 얼면 D-day가 결정적이라 테스트가 시계를 조작할 필요도 없다.
// 연동 때는 서버 응답이 CSR로 오므로 이 상수가 `new Date()`가 된다 — 훅 내부만 바뀐다.
const MOCK_NOW = new Date('2026-08-24T21:00:00+09:00');

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

const MOCK_BROADCASTS: VodBroadcast[] = MOCK_ROWS.map((row) => ({
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

const MOCK_VISUALS: Record<string, VodRowVisual> = Object.fromEntries(
  MOCK_ROWS.map((row) => [row.streamId, row.visual]),
);

const EMPTY_RANGE: VodCustomRange = { from: null, to: null };

// 시안이 그리는 세 상태를 한 화면에서 다 볼 수 있게 둘을 심어 둔다 — 받기를 눌러도
// 지금은 「준비 중」만 뜨므로, 심지 않으면 받는 중·받기 완료 자리를 아무도 못 본다.
const MOCK_DOWNLOADS: Record<string, VodDownloadState> = {
  'stream-2607': { kind: 'downloading', progress: 46 },
  'stream-2604': { kind: 'done' },
};

export function useVodListMockState(options: VodListOptions = {}): VodListMockState {
  const { toast } = useToast();
  const [filter, setFilter] = useState<VodPeriodFilter>('all');
  const [customRange, setCustomRange] = useState<VodCustomRange>(EMPTY_RANGE);
  const [downloads, setDownloads] = useState<Record<string, VodDownloadState>>(
    () => options.downloads ?? MOCK_DOWNLOADS,
  );

  const source = options.broadcasts ?? MOCK_BROADCASTS;
  const visuals = options.visuals ?? MOCK_VISUALS;

  const setDownload = useCallback((streamId: string, state: VodDownloadState) => {
    setDownloads((prev) => ({ ...prev, [streamId]: state }));
  }, []);

  // 받기를 실제로 시작하지 않는다 — 다운로드 백엔드가 기능명세·계약에 아직 없다.
  // 진행 중인 척하고 멈춰 있느니 준비 중이라고 말하는 편이 낫다(ADR-044의 「거짓말 금지」).
  const requestDownload = useCallback(() => {
    toast({
      tone: 'info',
      title: '준비 중인 기능이에요',
      description: '풀 VOD 내려받기는 아직 준비 중이에요. 준비되면 알려드릴게요.',
    });
  }, [toast]);

  const cancelDownload = useCallback(
    (streamId: string) => setDownload(streamId, VOD_DOWNLOAD_IDLE),
    [setDownload],
  );
  const resetDownload = useCallback(
    (streamId: string) => setDownload(streamId, VOD_DOWNLOAD_IDLE),
    [setDownload],
  );

  // 필터링은 순수 함수에 맡긴다 — 연동 때 이 계산이 서버 질의 조건으로 옮겨가더라도
  // 화면은 클라에서 걸렀는지 서버가 걸러 줬는지 몰라야 한다.
  const past = useMemo(() => excludeLive(source), [source]);
  const broadcasts = useMemo(
    () => filterByPeriod(past, filter, customRange, MOCK_NOW),
    [past, filter, customRange],
  );

  return {
    now: MOCK_NOW,
    broadcasts,
    totalCount: past.length,
    visuals,
    filter,
    setFilter,
    customRange,
    setCustomRange,
    downloads,
    requestDownload,
    cancelDownload,
    resetDownload,
  };
}
