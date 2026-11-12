'use client';

import {
  broadcastTitle,
  fetchAllBroadcasts,
  fetchAllJumpCards,
  mediaStreamId,
} from '@/api/clipEditor';

/** 지난 방송 목록을 다시 읽는 간격 — 끝난 방송이라 자주 볼 까닭이 없다 */
const LIST_POLL_MS = 60_000;
/** 이 안에 다시 들어오거나 탭으로 돌아오면 받아 둔 값을 그대로 쓴다 */
const REVISIT_FRESH_MS = 20_000;
/** 「다시 시도」가 도는 표시의 상한. 쪽이 많으면 읽기가 길어져 버튼이 오래 묶이지 않게 한다(PR #377 리뷰) */
const RETRY_SPINNER_MAX_MS = 15_000;
/**
 * 기억한 카드 수를 다시 세는 간격(POK-254). 끝난 뒤에도 카드는 늦게 뜨거나 숨겨진다. 방금 끝난 방송은 주기마다,
 * 나머지는 이만큼 지나면 센다: 주기마다 전체 이력을 세면 계정이 오래될수록 요청이 끝없이 는다(PR #200 codex).
 */
const CARD_COUNT_TTL_MS = 10 * 60_000;
/**
 * 한 주기에 다시 세는 오래된 방송 수의 상한. 처음 읽을 때 모든 방송이 같은 시각에 세어져, 상한이 없으면 10분마다 전체 이력의
 * 카드 목록을 한꺼번에 불렀다(리뷰 1라운드). 남은 것은 다음 주기로 넘어가 흩어진다
 */
const CARD_RECOUNT_PER_POLL = 20;
/**
 * 한 주기에 회차 경로를 묻는 오래된 미결 방송(끝난 지 한 시간이 지났고 표지가 아직 안 굳은 것) 수의 상한(POK-568 · kty X2).
 * 처음 보는 방송과 방금 끝난 방송은 이 상한 밖이라 다 묻는다 — 카드 수 다시 세기와 같은 꼴이다
 */
const ROUND_ASKS_PER_POLL = 20;
/** 방송마다 묻는 읽기의 동시 수. 방송이 많은 계정이 처음 들어올 때 전체 이력을 한꺼번에 묻지 않게 한다 */
const ROW_READ_PARALLEL = 6;
import { useCallback, useMemo, useState } from 'react';
import { queryOptions, useQuery } from '@tanstack/react-query';
import { track } from '@/lib/analytics';
import { runLimited } from '@/lib/runLimited';
import { useToast } from '@/ui';
import { trackApiActivity } from '@/api/client';
import { fetchRewindStatus, roundRewindUrl, type RewindStatusResult } from '@/api/rewindStatus';
import { mediaControlBaseUrl } from '@/features/player/mediaSource';
import { REWIND_REQUEST_TIMEOUT_MS } from '@/features/player/useRewindStatus';
import { ddayFor, excludeLive, filterByPeriod, isJustEnded } from './vodListView';
import {
  FRESH_ROW_VOD,
  foldRowVod,
  isRowVodSettled,
  pickRoundAsks,
  type RowVodMemory,
} from './vodRoundBadge';
import { useChannelStreamerId } from '@/features/channel/ChannelScope';

// 시안 1f 지난 방송 목록의 상태. 목록은 clip `GET /api/clip/broadcasts`(POK-174·ADR-055)에서 온다(POK-251).
// VOD 준비 상태를 줄 백엔드(B5)가 아직 없어, 끝난 방송은 줄마다 되감기 제어면의 회차 경로(계약3 7-2절)를 물어 「다시 보기
// 가능」 · 「다시보기가 없어요」를 정한다(POK-568 — 끝난 뒤 들어온 화면과 같은 판정, vodRoundBadge).
// 방송 제목도 명부에 칸이 없어 아직 방송 번호로 보인다.
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
  /** 가장 크게 터진 장면 사진(POK-277). 없으면 자리표시 */
  thumbnailUrl?: string | null;
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
  /** 회차 경로가 이 방송의 다시보기가 없다고 답했다(POK-568). 물을 수 없거나 아직 모르면 비운다 */
  vodMissing?: boolean;
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
  /** D-day·기간 계산의 기준 시각 — 시험이 얼린다. 없으면 지금 */
  now?: Date;
}

export interface VodListMockState {
  /** 모든 D-day·기간 계산의 기준 시각 */
  now: Date;
  /** live 제외 + 기간 필터를 통과한 행 */
  broadcasts: VodBroadcast[];
  /** 필터와 무관한 전체 수 — 「아직 없다」와 「이 기간에 없다」를 가른다 */
  totalCount: number;
  /**
   * 서버 목록을 아직 한 번도 못 읽었다. 그동안의 빈 목록은 「없다」가 아니라 「모른다」다(QA 2026-10-11)
   */
  loading: boolean;
  /**
   * 한 번도 못 읽었는데 읽기가 실패했다(POK-441). 주기가 60초라 다음 주기까지 「불러오는 중」으로 두면 실패가 1분 가려진다 —
   * 못 불러왔다고 말하고 바로 다시 시도하게 한다. 한 번이라도 읽었으면 실패해도 옛 목록을 두므로 늘 false다
   */
  failed: boolean;
  /** 한 번도 못 읽은 채 실패한 횟수. 다시 실패하면 안내를 새로 그려 낭독기가 다시 읽게 화면이 key로 쓴다(PR #377 리뷰) */
  failCount: number;
  /** 「다시 시도」로 부른 읽기가 도는 중 */
  retrying: boolean;
  /** 지금 바로 새로 읽는다(주기 읽기와 겹쳐도 따로 읽는다) */
  retry: () => void;
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

/**
 * MOCK_NOW에서 거슬러 올라간 시각. setHours 같은 지역 시간 계산을 안 쓰는 이유는 D-day가
 * 실행 환경의 시간대에 따라 하루씩 흔들리지 않게 하려는 것이다 — 「지금」을 얼렸으면
 * 배지도 얼어야 한다. 하루 안쪽으로 시간을 물리면 보관 만료가 `D-(60 - days)`로 떨어진다.
 */

/** 보관 만료는 종료 시각 + 60일이다(ADR-004) — D-day가 저절로 행마다 달라진다 */

// 시안 1f의 네 상태를 모두 담고, 기간 칩을 눌렀을 때 목록이 눈에 띄게 달라지도록 종료일을
// 흩어 뒀다 — 7일 이내 4개 · 30일 이내 9개 · 전체 12개.

const EMPTY_RANGE: VodCustomRange = { from: null, to: null };
const NO_ROWS: VodBroadcast[] = [];
const NO_VISUALS: Record<string, VodRowVisual> = {};

// ── clip 창구 배선(POK-251): `GET /api/clip/broadcasts?state=past|live` + 방송마다 카드 수 ──

interface WireBroadcast {
  streamId: string;
  status: string;
  relation: string;
  startedAt: string | null;
  endedAt: string | null;
  vodExpiresAt: string | null;
  /** 녹화 경로의 키(POK-233). 옛 서버면 빠져 온다 */
  ingestStreamId?: string | null;
  /** 사진 주소(POK-277). 옛 서버면 빠져 온다 */
  thumbnailUrl?: string | null;
  /** 방송 제목(POK-259, 켜진 플랫폼 중에서). 없거나 옛 서버면 비어 온다 */
  title?: string | null;
}

function isoMs(iso: string | null): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

/** 방송마다 재 둔 값. 쿼리 데이터에 함께 두어 화면을 떠났다 돌아와도 남는다: 안 그러면 들어올 때마다 전체 이력의 카드를 다시 센다 */
interface RowMemory {
  /** 아직 한 번도 못 셌으면 null */
  cardCount: number | null;
  /** 마지막으로 세려고 한 시각(실패해도 넘긴다: 늘 실패하는 방송이 다시 세는 차례를 독차지하지 않게) */
  countedAt: number;
  /** 회차 경로로 정한 다시보기 표지(POK-568). 아직 회차를 묻지 않았으면 처음 보는 줄(FRESH_ROW_VOD)과 같다 */
  vod: RowVodMemory;
}

interface VodListData {
  rows: VodBroadcast[];
  visuals: Record<string, VodRowVisual>;
  memory: Record<string, RowMemory>;
}

/**
 * 회차 경로를 한 번 묻는다(POK-568). REWIND_REQUEST_TIMEOUT_MS 안에 답이 없으면 끊고 「잠시 불가」다 — 화면 폴링
 * (useRewindStatus)과 같은 시한이다. 쿼리가 끊기면(내려감 · 채널 전환) 요청도 끊고 던진다: react-query 가 그 주기를 버린다.
 * apiFetch 밖 요청이라 직접 알린다: 목록이 이 답을 기다리니 채널 전환 화면도 기다린다
 */
async function askRound(
  url: string,
  controlBase: string,
  signal: AbortSignal,
): Promise<RewindStatusResult> {
  const request = new AbortController();
  let timedOut = false;
  const deadline = window.setTimeout(() => {
    timedOut = true;
    request.abort();
  }, REWIND_REQUEST_TIMEOUT_MS);
  const onAbort = () => request.abort();
  signal.addEventListener('abort', onAbort);
  if (signal.aborted) request.abort();
  try {
    return await trackApiActivity(() => fetchRewindStatus(url, controlBase, request.signal));
  } catch (e) {
    // fetchRewindStatus 는 끊긴 요청만 던진다. 시한이 끊었으면 「잠시 불가」, 쿼리가 끊었으면 그대로 던진다
    if (timedOut && !signal.aborted) return { kind: 'unavailable' };
    throw e;
  } finally {
    window.clearTimeout(deadline);
    signal.removeEventListener('abort', onAbort);
  }
}

/**
 * 이번 주기에 회차 경로를 물을 방송과 그 경로(POK-568). 물을 수 있는 방송은 ended · 보관 만료 아님 · 표지가 아직 안 굳음
 * (isRowVodSettled) · 경로를 만들 수 있음(제어면 주소 · 키 꼴)이고, 그중 pickRoundAsks 가 고른 것만 묻는다. 제어면 주소가
 * 없으면 비어 표지는 「VOD 준비 중」에 머문다
 */
function pickRoundUrls(
  all: readonly WireBroadcast[],
  vodOf: (streamId: string) => RowVodMemory,
  controlBase: string | null,
  now: Date,
): Map<string, string> {
  const urls = new Map<string, string>();
  for (const b of all) {
    if (b.status !== 'ended' || isRowVodSettled(vodOf(b.streamId))) continue;
    if (ddayFor(b.vodExpiresAt, now).kind === 'expired') continue;
    const url = roundRewindUrl(controlBase, mediaStreamId(b), b.streamId);
    if (url !== null) urls.set(b.streamId, url);
  }
  const asks = pickRoundAsks(
    all
      .filter((b) => urls.has(b.streamId))
      .map((b) => ({
        streamId: b.streamId,
        justEnded: isJustEnded(b.endedAt, now),
        askedAt: vodOf(b.streamId).askedAt,
      })),
    ROUND_ASKS_PER_POLL,
  );
  return new Map([...urls].filter(([streamId]) => asks.has(streamId)));
}

/** 목록 한 벌. 목록을 못 읽으면 던진다: react-query가 지난 목록을 그대로 두고 다음 주기에 다시 읽는다 */
async function loadVodList(
  streamerId: number | null,
  memory: Record<string, RowMemory>,
  signal: AbortSignal,
): Promise<VodListData> {
  // 목록을 끝까지 넘긴다 — 한 쪽만 읽으면 51번째부터 조용히 사라진다(POK-251 리뷰)
  const all: WireBroadcast[] = await fetchAllBroadcasts('past', streamerId, signal);
  const cache = new Map(Object.entries(memory));
  // 방송마다 카드 수·다시보기 표지는 재서 기억한다. 카드 수는 낡으면(countStale) 다시 센다
  const nowMs = Date.now();
  const countStale = (b: WireBroadcast) => {
    const known = cache.get(b.streamId);
    return (
      known === undefined ||
      known.cardCount === null ||
      isJustEnded(b.endedAt, new Date(nowMs)) ||
      nowMs - known.countedAt >= CARD_COUNT_TTL_MS
    );
  };
  // 다시 셀 방송. 처음 보는 방송·못 센 방송·방금 끝난 방송은 다 세고, 오래된 방송은 가장 오래전에 센 것부터 한 주기에
  // CARD_RECOUNT_PER_POLL개까지만. 고정 순서로 고르면 방송이 많을 때 앞쪽이 다시 낡아 뒤쪽은 영영 차례가 안 왔다(PR #256 codex)
  const recountIds = new Set<string>();
  const oldStale: { id: string; countedAt: number }[] = [];
  for (const b of all) {
    if (!countStale(b)) continue;
    const known = cache.get(b.streamId);
    if (known !== undefined && known.cardCount !== null && !isJustEnded(b.endedAt, new Date(nowMs)))
      oldStale.push({ id: b.streamId, countedAt: known.countedAt });
    else recountIds.add(b.streamId);
  }
  oldStale
    .sort((x, y) => x.countedAt - y.countedAt)
    .slice(0, CARD_RECOUNT_PER_POLL)
    .forEach(({ id }) => recountIds.add(id));
  // 기억에 표지가 없으면(처음 보는 방송 · 카드 수만 센 기억) 처음 보는 줄이다: 상한 밖에서 묻는다
  const vodOf = (streamId: string) => cache.get(streamId)?.vod ?? FRESH_ROW_VOD;
  const controlBase = mediaControlBaseUrl();
  const roundUrls = pickRoundUrls(all, vodOf, controlBase, new Date(nowMs));
  await runLimited(
    all.filter((b) => recountIds.has(b.streamId) || roundUrls.has(b.streamId)),
    ROW_READ_PARALLEL,
    async (b) => {
      if (signal.aborted) return;
      const known = cache.get(b.streamId);
      const recount = known === undefined || recountIds.has(b.streamId);
      const roundUrl = roundUrls.get(b.streamId);
      const [fetched, answer] = await Promise.all([
        known !== undefined && !recount
          ? Promise.resolve(known.cardCount)
          : fetchAllJumpCards(b.streamId, signal).then(
              (cards) => cards.length,
              () => null,
            ),
        roundUrl === undefined || controlBase === null
          ? Promise.resolve(null)
          : askRound(roundUrl, controlBase, signal),
      ]);
      // 카드 수를 못 읽었으면 앞 수를 둔다(처음이면 null, 다음 주기에 다시 잰다). 표지는 카드 수와 따로 남긴다:
      // 카드 목록만 실패해도 회차 답을 버리면 그 방송은 「VOD 준비 중」에 갇혔다(PR #256 codex)
      const vod = vodOf(b.streamId);
      cache.set(b.streamId, {
        cardCount: fetched ?? known?.cardCount ?? null,
        countedAt: known !== undefined && !recount ? known.countedAt : nowMs,
        // 물었으면 답을 접는다(못 받은 답도 askedAt 을 세운다). 404 유예의 원점은 명부 endedAt 이다
        vod: answer === null ? vod : foldRowVod(vod, answer, Date.now(), isoMs(b.endedAt)),
      });
    },
  );
  const rows: VodBroadcast[] = all.map((b) => ({
    streamId: b.streamId,
    // 서버 상태가 정본이다. 회차 경로가 다시보기가 섰다고 답한 끝난 방송만 「다시 보기 가능」으로 올린다
    status:
      b.status === 'ended' && vodOf(b.streamId).badge === 'ready'
        ? 'vod_ready'
        : ((b.status as VodBroadcastStatus) ?? 'ended'),
    relation: b.relation === 'EDITOR' ? 'EDITOR' : 'OWNER',
    startedAt: b.startedAt,
    endedAt: b.endedAt,
    vodExpiresAt: b.vodExpiresAt,
    thumbnailUrl: b.thumbnailUrl ?? null,
  }));
  const titles = new Map(all.map((b) => [b.streamId, broadcastTitle(b)]));
  const visuals: Record<string, VodRowVisual> = {};
  for (const b of rows) {
    const dur =
      b.startedAt && b.endedAt
        ? Math.max(0, Math.round((Date.parse(b.endedAt) - Date.parse(b.startedAt)) / 1000))
        : null;
    visuals[b.streamId] = {
      title: titles.get(b.streamId) ?? b.streamId,
      durationSec: dur,
      cardCount: cache.get(b.streamId)?.cardCount ?? 0,
      vodMissing: b.status === 'ended' && vodOf(b.streamId).badge === 'none',
    };
  }
  return { rows, visuals, memory: Object.fromEntries(cache) };
}

export function vodListQueryOptions(streamerId: number | null) {
  const queryKey = ['vod-list', streamerId] as const;
  return queryOptions({
    queryKey,
    queryFn: ({ client, signal }) =>
      loadVodList(streamerId, client.getQueryData<VodListData>(queryKey)?.memory ?? {}, signal),
    // 받아 둔 목록을 바로 보여 주고 뒤에서 다시 읽는다. 금방 돌아오면 읽지 않는다: 탭을 오갈 때마다 전체 목록을 읽지 않게
    staleTime: REVISIT_FRESH_MS,
    // 카드 수를 다시 세는 간격보다 길게 둔다: 금방 돌아오면 다시 세지 않는다
    gcTime: 3 * CARD_COUNT_TTL_MS,
    refetchInterval: LIST_POLL_MS,
    // 다음 주기가 곧 다시 읽고, 첫 읽기 실패는 바로 말해야 한다(POK-441)
    retry: false,
  });
}

export function useVodListMockState(options: VodListOptions = {}): VodListMockState {
  // 고른 채널(POK-390). ChannelScope가 채널이 바뀌면 화면을 다시 세운다
  const streamerId = useChannelStreamerId();
  const { toast } = useToast();
  const [filter, setFilter] = useState<VodPeriodFilter>('all');
  const [customRange, setCustomRange] = useState<VodCustomRange>(EMPTY_RANGE);
  const [downloads, setDownloads] = useState<Record<string, VodDownloadState>>(
    () => options.downloads ?? {},
  );
  const injected = options.broadcasts !== undefined;
  const list = useQuery({ ...vodListQueryOptions(streamerId), enabled: !injected });
  const { refetch, dataUpdatedAt } = list;
  const fetched = list.data?.rows ?? NO_ROWS;
  const fetchedVisuals = list.data?.visuals ?? NO_VISUALS;
  // 한 번도 못 읽었을 때만 실패로 친다. 한 번이라도 읽었으면 실패해도 옛 목록을 두고 다음 주기를 기다린다
  const failCount = list.data === undefined ? list.errorUpdateCount : 0;
  const [retrying, setRetrying] = useState(false);
  const [openedAt] = useState(() => new Date());
  const now = useMemo(
    () => (dataUpdatedAt === 0 ? openedAt : new Date(dataUpdatedAt)),
    [dataUpdatedAt, openedAt],
  );

  const source = options.broadcasts ?? fetched;
  const visuals = options.visuals ?? fetchedVisuals;

  const setDownload = useCallback((streamId: string, state: VodDownloadState) => {
    setDownloads((prev) => ({ ...prev, [streamId]: state }));
  }, []);
  const requestDownload = useCallback(() => {
    track('pending_feature_clicked', { feature: 'vod_download' }); // 수요를 본다 (POK-357)
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
  const retry = useCallback(() => {
    if (injected) return;
    setRetrying(true);
    // 목록은 쪽마다 시한을 받아 다 합치면 길어진다: 도는 표시에만 상한을 둔다. 다음 주기나 다음 클릭이 다시 읽는다
    void Promise.race([
      refetch(),
      new Promise<void>((resolve) => window.setTimeout(resolve, RETRY_SPINNER_MAX_MS)),
    ]).finally(() => setRetrying(false));
  }, [injected, refetch]);

  const past = useMemo(() => excludeLive(source), [source]);
  const baseNow = options.now ?? now;
  const broadcasts = useMemo(
    () => filterByPeriod(past, filter, customRange, baseNow),
    [past, filter, customRange, baseNow],
  );

  return {
    now: baseNow,
    broadcasts,
    totalCount: past.length,
    loading: !injected && list.data === undefined,
    failed: !injected && failCount > 0,
    failCount,
    retrying,
    retry,
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
