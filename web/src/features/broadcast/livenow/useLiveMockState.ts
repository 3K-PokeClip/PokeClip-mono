'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError, apiFetch } from '@/api/client';
import { chzzkLinkQueryOptions } from '@/api/chzzkLink';
import { fetchAllJumpCards } from '@/api/clipEditor';
import { useMe } from '@/features/auth/useSession';
import { useAuthStore } from '@/stores/auth';
import { keepKnownThumbnail } from '@/features/thumbnail/thumbnailUrl';
import { timeAgo } from '@/lib/timeAgo';
import { endedWindowStart } from '@/features/broadcast/endedWindow';
import { COLLECTOR_WINDOW_MS } from './chatCollector';
import {
  emitRelay,
  publishLiveData,
  timeBaseOf,
  useLiveData,
  type LiveChart,
  type LiveInfoScope,
  type RelayEvent,
} from './liveDataStore';

// 디자인 1b 라이브 대시보드의 상태. clip 창구에 붙어 있다(POK-251): 카드 목록(GET jump-cards) +
// 실시간(SSE events: 카드·중계 채팅·방송 정보) + 채팅량 차트(chat-chart) + 방송 정보(broadcast-info).
// 이름에 Mock이 남은 것은 화면 쪽 import를 안 흔들기 위해서다.
//
// ⚠ 반환 형태는 계약이다 (POK-180 SSE 실연동이 "화면은 그대로, 훅 내부만" 전제로 섰다).
// 값은 시안을 따라 바꿔도 되지만 필드·유니온을 늘리면 저쪽이 채울 수 없는 자리가 생긴다.
// 시안이 요구하는데 여기 없는 표기값은 useLiveDetailsMockState로 간다.
// 옛 통계 그래프의 좌표 계약(chatVolume, 800×90 꺾은선)은 뺐다: 새 그래프는 저장소의 차트 칸을 그대로 그리고(useLiveStatsMockState),
// 핀은 카드의 atMs로 선다.

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
  /** 내가 집은 카드인가 — 그러면 「편집 중」이어도 다시 들어갈 수 있어야 한다 */
  claimedByMe?: boolean;
  /** 방금 감지된 카드 — 마젠타 틴트로 강조 */
  emphasized?: boolean;
  /** 카드 시점 장면 사진(POK-277). 없으면 자리표시 */
  thumbnailUrl?: string | null;
  /** 숨긴 카드다(POK-338): 「숨김」 칩에서만 보이고 「숨김 해제」만 할 수 있다 */
  hidden?: boolean;
  /** 카드가 가리키는 절대 시각(epoch ms, cardAt) — 통계 그래프의 핀 자리 */
  atMs?: number;
  /** 감지 사유 한 줄(채팅 ×3.2 급증 (120건) · 수동 마킹) — 통계 그래프 툴팁 */
  reason?: string;
}

export type CardListStatus = 'loading' | 'failed' | 'loaded';

export interface LiveMockState {
  stream: LiveStream;
  highlights: LiveHighlight[];
  /** 숨긴 카드(POK-338). 숨김을 되돌릴 자리가 없으면 행동 기록에 되돌리기가 영영 안 쌓인다 */
  hiddenHighlights: LiveHighlight[];
  hiddenCount: number;
  /**
   * 이 방송의 카드 목록을 받았나. 받기 전의 빈 목록은 「없다」가 아니라 「모른다」다. 'failed'는 받기 전에 읽기가
   * 실패한 것이다(다음 주기에 다시 읽는다). 한 번 받은 뒤의 실패는 받은 목록을 그대로 두므로 'loaded'다
   */
  cardsStatus: CardListStatus;
  /** 채팅 수집 끊김 경고 배너 노출 여부 */
  chatWarning: boolean;
}

// ── clip 창구 배선 ────────────────────────────────────────────────────────────
// 반환 형태(LiveMockState)는 그대로다 — 화면은 손대지 않는다.

interface Snapshot {
  id: number;
  streamId: string;
  source: string;
  streamTimestampMs: number;
  window: { startMs: number; endMs: number };
  score: number | null;
  evidence: Record<string, unknown> | null;
  claimedBy: string | null;
  hidden: boolean;
  eventSeq: number;
  createdAt: string;
  /** 목록 문만 싣는다(POK-277). 통로로 온 카드는 비어 있다 */
  thumbnailUrl?: string | null;
}
interface BroadcastInfoResponse {
  latest: {
    title: string | null;
    tags: string[];
    category: string | null;
    viewers?: number | null;
  } | null;
  /** category는 수집기가 관측마다 싣기 전엔 없다(지금은 latest에만 있다) — 오면 통계가 카테고리 구간을 나눈다 */
  series: InfoPoint[];
}
type InfoPoint = { observedAt: string; viewers: number | null; category?: string | null };
/** chat-chart 응답 한 장. 어느 창을 읽었는지(scope)는 부른 쪽이 안다 */
type ChartPage = Omit<LiveChart, 'scope'>;
/** 받아 둔 방송 정보. 시청자 기록을 어느 창으로 읽었는지(scope)를 같이 둔다 */
type LoadedInfo = BroadcastInfoResponse & { scope: LiveInfoScope };

const CHART_MINUTES = 10;
const CHART_BUCKET = 30;
/** 끝난 방송의 카드 목록을 다시 읽는 간격(POK-254). 사진 주소(50분)보다 짧고, 다른 편집자의 집기·숨김이 1분 안에 보인다 */
const ENDED_LIST_MS = 60_000;
/**
 * 끝난 방송의 차트·방송 정보를 굳히기 전에 기다리는 시간(PR #256 codex). 명부가 끝을 먼저 알리면 수집기의 마지막 적재(1초마다 몰아
 * 쓴다)와 마지막 관측(1분마다) 전 답을 받는다: 첫 답을 굳히면 꼬리가 빠진 채 남았다. 이만큼 지난 뒤 받은 답부터 굳힌다
 */
const ENDED_SETTLE_MS = 2 * 60_000;
/** 라이브 시청자 기록을 이어 받을 때 받아 둔 마지막 점보다 이만큼 앞부터 다시 묻는다(수집기의 늦은 적재를 놓치지 않게) */
const INFO_OVERLAP_MS = 2 * 60_000;
export interface CardActionResult {
  ok: boolean;
  status: number;
  text: string;
}

/**
 * 카드 동작이 성공하면 열린 화면이 목록을 그 자리에서 다시 읽는다(POK-338). 끝난 방송은 통로가 없고 목록을 드물게 읽어,
 * 기다리면 「놓기」·「숨김 해제」가 한참 뒤에야 보인다
 */
const cardChangeListeners = new Set<() => void>();

/** 카드 집기·놓기·숨기기·되돌리기. 실패도 값으로 돌려준다: 카드가 사유(409 already_claimed 등)를 토스트로 보인다 */
async function cardCall(method: string, path: string, body?: object): Promise<CardActionResult> {
  try {
    // 문자열 본문이면 apiFetch가 Content-Type을 JSON으로 붙인다
    const r = await apiFetch(path, body ? { method, body: JSON.stringify(body) } : { method });
    cardChangeListeners.forEach((reload) => reload());
    return { ok: true, status: r.status, text: '' };
  } catch (e) {
    if (e instanceof ApiError) return { ok: false, status: e.status, text: e.code ?? e.message };
    return { ok: false, status: 0, text: String(e) };
  }
}

const cardPath = (cardId: string) => `/api/clip/jump-cards/${cardId.replace('card-', '')}`;
export function claimCard(cardId: string) {
  return cardCall('POST', `${cardPath(cardId)}/claim`);
}
export function releaseCard(cardId: string) {
  return cardCall('DELETE', `${cardPath(cardId)}/claim`);
}
/** 숨긴 이유(POK-338). 거짓양성률 = 자동 카드 중 「하이라이트 아님」으로 숨긴 비율이다 */
export type HideReason = 'not_highlight' | 'duplicate' | 'other';
export function hideCard(cardId: string, reason: HideReason) {
  return cardCall('POST', `${cardPath(cardId)}/hide`, { reason });
}
export function unhideCard(cardId: string) {
  return cardCall('DELETE', `${cardPath(cardId)}/hide`);
}

/**
 * 라이브 그래프·시청자 기록을 읽기 시작할 시각(epoch ms) — 방송 시작이다. 12시간이 넘으면 끝 12시간인데(끝난 방송과 같은 상한,
 * endedWindowStart) 그 시작을 방송 시작에서 1시간 단위로 맞춘다: 지금에서 12시간을 빼면 창 경계가 10초마다 밀려 기억한 창을 못 쓴다
 */
export function liveWindowStart(startedAt: number, now: number): number {
  const tail = endedWindowStart(startedAt, now);
  if (tail === startedAt) return startedAt;
  return startedAt + Math.ceil((tail - startedAt) / COLLECTOR_WINDOW_MS) * COLLECTOR_WINDOW_MS;
}

/** [from, to)를 수집기 한 번에 받을 수 있는 1시간 창들로 자른다 */
export function chartWindows(from: number, to: number): [number, number][] {
  const windows: [number, number][] = [];
  for (let a = from; a < to; a += COLLECTOR_WINDOW_MS)
    windows.push([a, Math.min(to, a + COLLECTOR_WINDOW_MS)]);
  return windows;
}

/**
 * 카드가 가리키는 방송 시점의 절대 시각. 카드를 만든 시각(createdAt)은 감지가 늦거나 다시 처리되면 급증보다
 * 뒤에 찍혀 마커가 엇나간다(PR #200 codex). 방송 시작 시각을 모르면 만든 시각으로 대신한다.
 * 기준점은 방송 시작 편지 시각이라 녹화 첫 조각과 수십 초 어긋날 수 있다(README 「시각 기준점」).
 */
/** 카드가 가리키는 절대 시각. 카드 ms는 시각 기준점(녹화 첫 조각) 축이다 — 방송 시작 시각에 더하면 수십 초 어긋난다 */
function cardAt(c: { streamTimestampMs: number; createdAt: string }, base: number | null): number {
  return base !== null ? base + c.streamTimestampMs : Date.parse(c.createdAt);
}

/** 응답을 JSON으로 받는다. 실패는 null — 폴링은 다음 주기에 다시 한다 */
async function getJsonOrNull<T>(path: string): Promise<T | null> {
  try {
    const r = await apiFetch(path);
    return (await r.json()) as T;
  } catch {
    return null;
  }
}

/** 방송 기준 ms → 카드의 시계 표기(h:mm:ss). 지난 방송 채팅 창 바닥도 같은 꼴로 쓴다 */
export function msToClock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
}

/** 지금 로그인한 회원 번호 — 토큰의 sub. 카드의 claimedBy와 같은 값이다 */
function currentUserId(): string | null {
  try {
    const token = useAuthStore.getState().accessToken;
    const payload = token?.split('.')[1];
    if (!payload) return null;
    const json = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))) as {
      sub?: unknown;
    };
    return typeof json.sub === 'string' ? json.sub : null;
  } catch {
    return null;
  }
}

function toHighlight(c: Snapshot, index: number, now: number, base: number | null): LiveHighlight {
  const ev = c.evidence ?? {};
  const ratio = typeof ev.ratio === 'number' ? ev.ratio : null;
  const count =
    typeof ev.count === 'number'
      ? ev.count
      : typeof ev.messageCount === 'number'
        ? ev.messageCount
        : null;
  const winSec = Math.round((c.window.endMs - c.window.startMs) / 1000);
  const manual = c.source === 'hotkey';
  const reason = manual
    ? '수동 마킹'
    : ratio !== null
      ? `채팅 ×${ratio.toFixed(1)} 급증${count !== null ? ` (${count}건)` : ''}`
      : `채팅 급증${count !== null ? ` (${count}건)` : ''}`;
  let status: HighlightStatus = manual ? 'manual' : 'scored';
  if (c.claimedBy) status = 'editing';
  return {
    id: `card-${c.id}`,
    timestamp: msToClock(c.streamTimestampMs),
    title: manual ? '핫키로 남긴 순간' : `채팅이 튄 순간 #${c.id}`,
    meta: `${reason} · ${winSec}초 · ${timeAgo(c.createdAt, now)}`,
    status,
    source: manual ? 'manual' : 'auto',
    score: c.score ?? undefined,
    emphasized: index === 0,
    claimedByMe: c.claimedBy !== null && c.claimedBy === currentUserId(),
    thumbnailUrl: c.thumbnailUrl ?? null,
    ...(c.hidden ? { hidden: true } : {}),
    atMs: cardAt(c, base),
    reason,
  };
}

function parseSseBlock(block: string): { event: string; data: string } | null {
  let event = 'message';
  const data: string[] = [];
  for (const line of block.split('\n')) {
    if (line.startsWith(':')) continue;
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  if (data.length === 0) return null;
  return { event, data: data.join('\n') };
}

/**
 * @param pastRoute 지난 방송 주소(/broadcast/vod/…)로 열었나. 그 주소에서는 명부가 답하기 전(방송 상태를 모름)에 그래프를 라이브의
 *   최근 창으로 읽지 않는다(리뷰 3라운드). 방송 정보는 기다리지 않는다: 제목·카테고리는 창과 무관하고, 시청자 기록은 읽은 창을
 *   같이 둬서 끝난 방송이 최근 창의 기록을 믿지 않게 한다(리뷰 4라운드)
 */
export function useLiveMockState(pastRoute: boolean): LiveMockState {
  const [cards, setCards] = useState<Record<number, Snapshot>>({});
  const [cardsStatus, setCardsStatus] = useState<CardListStatus>('loading');
  const [now, setNow] = useState(() => Date.now());
  const [sseOk, setSseOk] = useState(false);
  const [info, setInfo] = useState<LoadedInfo | null>(null);
  const live = useLiveData();
  // 카드 ms의 0초(서버 기준점 → 녹화 재생 서버 → 방송 시작, POK-255)
  const base = timeBaseOf(live);
  // 방송 번호는 화면 맨 위(useLiveStreamSelection)가 정한다. 정해지기 전(빈 문자열)에는 아무것도 안 받는다
  const streamId = live.streamId;
  const rangeRef = useRef<{ status: string; startedAt: number | null; endedAt: number | null }>({
    status: 'unknown',
    startedAt: null,
    endedAt: null,
  });
  rangeRef.current = { status: live.status, startedAt: live.startedAt, endedAt: live.endedAt };

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  // 방송 상태(라이브→종료, 시작·종료 시각 도착)가 바뀌면 차트 범위와 시청자 기록의 시작이 바뀐다 — 그 둘만 다시 읽는다.
  // 🔴 아래 큰 효과(카드·통로·방송 정보)를 다시 돌리면 카드가 비고 통로가 끊겨 그 사이 중계 채팅을 잃는다(POK-251 리뷰 2라운드)
  const reloadRangeRef = useRef<(() => void) | null>(null);
  useEffect(() => {
    reloadRangeRef.current?.();
  }, [live.status, live.startedAt, live.endedAt]);

  useEffect(() => {
    if (!streamId) return;
    // 방송이 바뀌면 앞 방송의 것은 전부 비운다. 방송 번호는 올리지 않는다: 올리는 곳은 방송을 고르는 훅 하나다(저장소는 번호가
    // 바뀌면 통째로 비운다)
    setCards({});
    setCardsStatus('loading');
    setInfo(null);
    publishLiveData({ chart: null, info: null, cardTimes: [] });
    let stopped = false;
    type Range = typeof rangeRef.current;
    /** 지난 방송 주소인데 방송 상태를 아직 모른다: 그래프를 어느 창으로 읽을지 모르니 기다린다 */
    const waitForRange = (rg: Range) => pastRoute && rg.status !== 'live' && rg.status !== 'ended';
    /**
     * 읽은 답을 실어도 되나. 방송이 바뀌었거나(효과가 끝남) 읽는 동안 방송 상태·시각이 바뀌었으면 옛 창의 답이라 버린다: 늦게 와서
     * 새 방송의 제목·시청자 수를, 끝난 방송의 「끝날 때 N명」을 덮었다(리뷰 3라운드)
     */
    const stillCurrent = (rg: Range) =>
      !stopped &&
      rangeRef.current.status === rg.status &&
      rangeRef.current.startedAt === rg.startedAt &&
      rangeRef.current.endedAt === rg.endedAt;
    // fromStream: 통로(SSE)로 온 카드인가. 통로 카드는 사진 칸이 비어 오므로 받아 둔 사진을 지키고, 목록 응답은 서버 값이
    // 정본이라 그대로 덮는다(사진이 사라졌으면 자리표시로 돌아가야 한다, PR #213 codex)
    const merge = (list: Snapshot[], fromStream = false) => {
      if (stopped) return;
      setCards((prev) => {
        const next = { ...prev };
        // 통로(SSE)로 온 카드는 사진 칸이 비어 온다. 목록에서 받아 둔 사진을 지우지 않는다(POK-277)
        for (const c of list) next[c.id] = fromStream ? keepKnownThumbnail(prev[c.id], c) : c;
        return next;
      });
    };

    /**
     * 끝난 방송은 덜 읽는다(POK-254). 차트·방송 정보는 그 창을 한 번 받으면 더 바뀌지 않는다(창이 바뀌면 열쇠가 바뀌어 다시 읽는다).
     * 카드 목록은 다른 편집자의 집기·숨김과 50분짜리 사진 주소 때문에 1분마다 읽는다. 전에는 끝난 방송도 3초·10초마다 읽어
     * 12시간 방송이면 탭 하나에 분당 차트 72건이었다
     */
    const endedKey = (rg: Range) =>
      rg.status === 'ended' && rg.endedAt !== null ? `${rg.startedAt}|${rg.endedAt}` : null;
    /** 이 답으로 굳혀도 되나: 끝난 지 ENDED_SETTLE_MS가 지난 뒤 떠난 읽기만 */
    const settledKey = (rg: Range, askedAt: number) =>
      rg.endedAt !== null && askedAt - rg.endedAt >= ENDED_SETTLE_MS ? endedKey(rg) : null;
    let chartDoneFor: string | null = null;
    let infoDoneFor: string | null = null;
    let listReadAt = 0;

    const loadList = async () => {
      listReadAt = Date.now();
      try {
        // 끝까지 넘기고 숨긴 카드도 받는다 — 숨김이 목록에 반영돼야 한다(통로가 끊겨 폴링이 대신할 때도).
        // 한 쪽만 읽으면 51번째 카드부터 빠진다(PR #200 codex)
        merge(await fetchAllJumpCards<Snapshot>(streamId, { includeHidden: true }));
        if (!stopped) setCardsStatus('loaded');
      } catch {
        // 다음 주기에 다시. 받기 전의 실패만 「못 읽었다」고 말한다: 받은 목록이 있으면 그것을 그대로 보인다
        if (!stopped) setCardsStatus((s) => (s === 'loaded' ? s : 'failed'));
      }
    };
    /**
     * 라이브 그래프의 다 지난 1시간 창(열쇠 = 창 시작|끝|칸). 방송 중에도 처음부터 지금까지 그리므로, 길어진 방송을 10초마다
     * 처음부터 다시 읽지 않게 끝난 지 ENDED_SETTLE_MS가 지난 창은 한 번 받은 답을 쓴다(그 안은 수집기가 아직 쓰고 있을 수 있다)
     */
    const settledPages = new Map<string, ChartPage>();
    /** 지금 보이는 그래프를 라이브 때 읽었나. 끝난 방송으로 바뀌면 그 그래프는 끝 몇 초가 빠졌을 수 있어 버린다 */
    let chartReadLive = false;
    const chartPage = (a: number, b: number, bucket: number) =>
      getJsonOrNull<ChartPage>(
        `/api/clip/broadcasts/${encodeURIComponent(streamId)}/chat-chart?${new URLSearchParams({
          from: new Date(a).toISOString(),
          to: new Date(b).toISOString(),
          bucket: String(bucket),
        })}`,
      );
    const loadChart = async () => {
      try {
        const rg = rangeRef.current;
        if (waitForRange(rg)) return;
        if (endedKey(rg) !== null && endedKey(rg) === chartDoneFor) return;
        const askedAt = Date.now();
        // 지난 방송은 시작~종료 전체, 라이브는 시작~지금이다(둘 다 12시간이 넘으면 끝 12시간, 지난 방송은 시작을 몰라도 끝 12시간).
        // 수집기는 한 번에 1시간까지만 받으므로(window-max PT1H, 넘으면 400 too_wide) 1시간씩 나눠 읽어 이어 붙인다(PR #200 codex).
        // 버킷은 길이에 맞춰 고른다. 라이브는 10초 칸을 쓰지 않는다: 그래프가 30초보다 잘게 그리지 않고, 1시간을 넘는 순간 칸이
        // 바뀌어 기억한 창을 다 버리게 된다
        const ended = rg.status === 'ended' && rg.endedAt !== null;
        const liveWhole = rg.status === 'live' && rg.startedAt !== null;
        if (ended || liveWhole) {
          const end = ended ? rg.endedAt! : askedAt;
          const start = ended
            ? endedWindowStart(rg.startedAt, end)
            : liveWindowStart(rg.startedAt!, end);
          const dur = end - start;
          const bucket = dur <= 60 * 60_000 && ended ? 10 : dur <= 6 * 60 * 60_000 ? 30 : 60;
          // 라이브 때 읽은 그래프는 끝난 방송의 창이 아니다(끝 몇 초가 빠졌고, 최근 창이면 처음이 없다). 남겨 두면 다시 읽지 않아
          // 「지금」이 굳는다(리뷰 3라운드)
          if (ended && chartReadLive) {
            chartReadLive = false;
            publishLiveData({ chart: null });
          }
          const windows = chartWindows(start, end);
          const keys = new Set(windows.map(([a, b]) => `${a}|${b}|${bucket}`));
          // 지금 창에 없는 기억은 버린다: 6시간째에 칸이 30→60초로 바뀐 옛 열쇠, 12시간 꼬리에서 밀려난 창. 남겨 두면 탭을 닫을
          // 때까지 쌓인다(PR #316 리뷰). 칸이 바뀐 순간 한 번은 창을 전부 다시 읽는다
          for (const key of settledPages.keys()) if (!keys.has(key)) settledPages.delete(key);
          const pages = await Promise.all(
            windows.map(async ([a, b]) => {
              const key = `${a}|${b}|${bucket}`;
              const kept = settledPages.get(key);
              if (kept) return kept;
              const page = await chartPage(a, b, bucket);
              if (page && liveWhole && askedAt - b >= ENDED_SETTLE_MS) settledPages.set(key, page);
              return page;
            }),
          );
          if (!stillCurrent(rg)) return;
          const first = pages[0];
          // 한 조각이라도 못 받으면 구멍 난 차트를 그리지 않는다 — 다음 주기에 다시
          if (first && pages.every((pg) => pg !== null)) {
            const joined: LiveChart = {
              ...first,
              buckets: pages.flatMap((pg) => pg.buckets),
              scope: start === rg.startedAt ? 'broadcast' : 'tail',
            };
            chartReadLive = liveWhole;
            publishLiveData({ chart: joined });
            if (ended) chartDoneFor = settledKey(rg, askedAt);
          }
          return;
        } else if (rg.status === 'offline') {
          publishLiveData({ chart: null });
          return;
        }
        // 시작 시각을 모르는 라이브(자리표시 방송)와 방송 상태를 아직 모를 때는 지금 기준 최근 10분이다: 처음을 모른다
        const to = new Date(askedAt);
        const from = new Date(askedAt - CHART_MINUTES * 60_000);
        const page = await chartPage(from.getTime(), to.getTime(), CHART_BUCKET);
        if (page && stillCurrent(rg)) {
          const recent: LiveChart = { ...page, scope: 'recent' };
          chartReadLive = true;
          publishLiveData({ chart: recent });
        }
      } catch {
        /* 다음 주기에 다시 */
      }
    };

    /**
     * 라이브 시청자 기록(그 방송 시작의 것). 받아 둔 기록 뒤만 더 받아 이어 붙인다: 10초마다 방송 처음부터 다시 받으면 10시간
     * 방송은 600점짜리 답이 10초마다 왔다(PR #316 리뷰)
     */
    let liveSeries: { startedAt: number; series: InfoPoint[] } | null = null;
    const loadInfo = async () => {
      const rg = rangeRef.current;
      // 꺼짐은 명부에 없는 방송이다(위임 회수·탈퇴): 볼 수 없게 된 방송이라 읽지 않는다. 시계가 명부를 다시 물어 방송이
      // 돌아오면 상태가 바뀌어 그때 읽는다(리뷰 4·5라운드)
      if (rg.status === 'offline') return;
      if (endedKey(rg) !== null && endedKey(rg) === infoDoneFor) return;
      const askedAt = Date.now();
      // 그래프와 같은 창의 시청자 기록을 달라고 한다: 안 주면 서버가 최근 1시간만 준다(PR #200 codex). 지난 방송은 그 방송의 창,
      // 라이브는 시작~지금이다(그래프가 처음부터라 선도 처음부터다). 시작을 모르는 라이브와 아직 모를 때는 최근 창으로 읽고 그렇다고
      // 표시한다. 제목·카테고리는 창과 무관해 명부 답을 기다리지 않는다(리뷰 4라운드)
      const liveFrom =
        rg.status === 'live' && rg.startedAt !== null
          ? liveWindowStart(rg.startedAt, askedAt)
          : null;
      const kept = liveFrom !== null && liveSeries?.startedAt === rg.startedAt ? liveSeries : null;
      const lastSeen = kept?.series.at(-1);
      // 라이브에서 받아 둔 기록이 있으면 마지막 점 INFO_OVERLAP_MS 앞부터만 묻는다. 수집기가 관측을 늦게 적을 수 있어 조금 겹친다
      const sinceMs =
        rg.status === 'ended' && rg.endedAt !== null
          ? endedWindowStart(rg.startedAt, rg.endedAt)
          : liveFrom !== null
            ? lastSeen
              ? Math.max(liveFrom, Date.parse(lastSeen.observedAt) - INFO_OVERLAP_MS)
              : liveFrom
            : null;
      const since =
        sinceMs === null
          ? ''
          : `?${new URLSearchParams({ since: new Date(sinceMs).toISOString() })}`;
      const page = await getJsonOrNull<BroadcastInfoResponse>(
        `/api/clip/broadcasts/${encodeURIComponent(streamId)}/broadcast-info${since}`,
      );
      if (page && stillCurrent(rg)) {
        let series = page.series;
        if (liveFrom !== null && rg.startedAt !== null && sinceMs !== null) {
          // 받아 둔 앞 기록 + 이번 답. 겹친 구간은 관측 시각으로 하나만 두고, 12시간 꼬리 밖으로 밀려난 점은 버린다
          const byTime = new Map<string, InfoPoint>();
          for (const p of kept?.series ?? [])
            if (Date.parse(p.observedAt) <= sinceMs) byTime.set(p.observedAt, p);
          for (const p of page.series) byTime.set(p.observedAt, p);
          series = [...byTime.values()]
            .filter((p) => Date.parse(p.observedAt) >= liveFrom)
            .sort((a, b) => Date.parse(a.observedAt) - Date.parse(b.observedAt));
          liveSeries = { startedAt: rg.startedAt, series };
        }
        const loaded: LoadedInfo = {
          ...page,
          series,
          scope: sinceMs === null ? 'recent' : 'broadcast',
        };
        setInfo(loaded);
        publishLiveData({ info: loaded });
        if (sinceMs !== null && rg.status === 'ended') infoDoneFor = settledKey(rg, askedAt);
      }
    };
    const ctrl = new AbortController();
    let sseCooldown = 5_000;
    const listenSse = async () => {
      try {
        let r: Response;
        try {
          r = await apiFetch(`/api/clip/broadcasts/${encodeURIComponent(streamId)}/events`, {
            headers: { Accept: 'text/event-stream' },
            signal: ctrl.signal,
          });
        } catch (e) {
          // 503은 용량 초과라 길게 쉰다 — 폴링이 카드를 메운다. 404는 방송 편지가 아직 안 온 것
          sseCooldown = e instanceof ApiError && e.status === 503 ? 60_000 : 15_000;
          return;
        }
        if (!r.body) {
          sseCooldown = 15_000;
          return;
        }
        sseCooldown = 5_000;
        setSseOk(true);
        const reader = r.body.getReader();
        const dec = new TextDecoder();
        let buf = '';
        while (!stopped) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          let idx = buf.indexOf('\n\n');
          while (idx >= 0) {
            const parsed = parseSseBlock(buf.slice(0, idx));
            buf = buf.slice(idx + 2);
            if (parsed && parsed.event === 'card') {
              try {
                merge([JSON.parse(parsed.data) as Snapshot], true);
              } catch {
                /* 무시 */
              }
            } else if (parsed && (parsed.event === 'chat' || parsed.event === 'donation')) {
              // 중계 채팅·후원(PR-B) — 채팅 패널이 받아 그린다
              try {
                emitRelay(JSON.parse(parsed.data) as RelayEvent);
              } catch {
                /* 무시 */
              }
            } else if (parsed && parsed.event === 'broadcast-info') {
              // 1분마다 오는 제목·태그·카테고리·시청자 수(PR-C) — 화면 정보 바·통계가 바로 반영된다
              try {
                const ev = JSON.parse(parsed.data) as {
                  time: string;
                  title: string | null;
                  tags: string[];
                  category: string | null;
                  viewers: number | null;
                };
                setInfo((prev) => {
                  // 통로는 방송 중에만 온다. 기록은 받아 둔 창 그대로 이어 붙인다: 끝난 뒤 늦게 온 이벤트가 방송 창 기록을
                  // 최근 창으로 바꾸지 않게
                  const next: LoadedInfo = {
                    scope: prev?.scope ?? 'recent',
                    latest: {
                      title: ev.title,
                      tags: ev.tags ?? [],
                      category: ev.category,
                      viewers: ev.viewers,
                    },
                    series: [
                      ...(prev?.series ?? []),
                      { observedAt: ev.time, viewers: ev.viewers },
                    ].slice(-720),
                  };
                  publishLiveData({ info: next });
                  return next;
                });
              } catch {
                /* 무시 */
              }
            }
            idx = buf.indexOf('\n\n');
          }
        }
      } catch {
        /* abort 또는 연결 끊김 — 폴링이 메운다 */
      } finally {
        setSseOk(false);
      }
    };
    // 방송 편지가 아직 안 왔으면 404다 — 5초마다 다시 붙는다(폴링은 별도로 계속 돈다)
    const sseLoop = async () => {
      while (!stopped) {
        // 통로는 방송 중에만 온다: 끝난 방송에는 다시 붙지 않는다(POK-254). 상태가 바뀌는지만 쉬며 본다
        if (rangeRef.current.status !== 'ended') await listenSse();
        if (stopped) break;
        await new Promise((res) => setTimeout(res, sseCooldown));
      }
    };

    const reloadRange = () => {
      void loadChart();
      void loadInfo();
    };
    reloadRangeRef.current = reloadRange;
    void loadList();
    void loadChart();
    void loadInfo();
    void sseLoop();
    cardChangeListeners.add(loadList);
    // 끝난 방송이면 주기 읽기만 1분에 한 번이다. 그 밖에서 부르는 읽기(카드 동작 뒤 등)는 막지 않는다
    const t1 = setInterval(() => {
      if (rangeRef.current.status === 'ended' && Date.now() - listReadAt < ENDED_LIST_MS) return;
      void loadList();
    }, 3000);
    const t2 = setInterval(loadChart, 10_000);
    const t3 = setInterval(loadInfo, 10_000);
    return () => {
      stopped = true;
      ctrl.abort();
      cardChangeListeners.delete(loadList);
      clearInterval(t1);
      clearInterval(t2);
      clearInterval(t3);
      if (reloadRangeRef.current === reloadRange) reloadRangeRef.current = null;
    };
  }, [streamId, pastRoute]);

  useEffect(() => {
    publishLiveData({
      cardTimes: Object.values(cards)
        .filter((c) => !c.hidden)
        .map((c) => cardAt(c, base)),
    });
  }, [cards, base]);

  const highlights = useMemo(() => {
    const list = Object.values(cards)
      .filter((c) => !c.hidden)
      .sort((a, b) => b.eventSeq - a.eventSeq);
    return list.map((c, i) => toHighlight(c, i, now, base));
  }, [cards, now, base]);
  const hiddenHighlights = useMemo(() => {
    const list = Object.values(cards)
      .filter((c) => c.hidden)
      .sort((a, b) => b.eventSeq - a.eventSeq);
    // 강조(맨 앞 「방금」)는 보이는 목록의 몫이라 숨긴 카드에는 주지 않는다
    return list.map((c) => toHighlight(c, -1, now, base));
  }, [cards, now, base]);
  const hiddenCount = hiddenHighlights.length;

  const { data: chzzk } = useQuery(chzzkLinkQueryOptions);
  const { data: me } = useMe();
  // 채널 이름은 내 방송일 때만 내 치지직 연동에서 읽는다. 위임받은 방송(EDITOR)은 방송 줄에 스트리머 채널이
  // 실려 오지 않아 비운다 — 내 채널 이름을 남의 방송에 붙이면 거짓이다(POK-251 리뷰)
  const channelName = live.relation === 'OWNER' ? (chzzk?.channelName ?? '') : '';
  const myName = me?.name ?? '';
  const stream = useMemo<LiveStream>(
    () => ({
      // 방송 정보 관측의 제목이 먼저고, 그 관측에 제목이 비면 목록 제목(POK-259), 그것도 없으면 방송 번호
      title:
        info?.latest?.title?.trim() ||
        live.listTitle ||
        // 지난 방송 주소는 방송 번호를 정하기 전에도 라이브 안내(「방송 중인 채널이 없어요」)를 쓰지 않는다(리뷰 3라운드)
        streamId ||
        (pastRoute ? '지난 방송' : '방송 중인 채널이 없어요'),
      platform: '치지직',
      channelName,
      startedNote: !streamId
        ? '방송을 시작하면 자동으로 나타나요'
        : live.status === 'ended'
          ? '지난 방송'
          : sseOk
            ? '실시간 연결됨'
            : '실시간 연결 중',
      uptimeSeconds: 0,
      uptimeLabel: '0:00:00',
      viewers: (() => {
        const lastPt = info?.series?.length ? info.series[info.series.length - 1] : undefined;
        const v = lastPt ? lastPt.viewers : null;
        return v === null || v === undefined ? '-' : String(v);
      })(),
      editorName: myName,
    }),
    [streamId, sseOk, info, live.status, live.listTitle, channelName, myName, pastRoute],
  );

  return {
    stream,
    highlights,
    hiddenHighlights,
    hiddenCount,
    cardsStatus,
    chatWarning: false,
  };
}
